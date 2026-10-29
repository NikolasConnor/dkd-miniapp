import hashlib
import hmac
import secrets
import json
import os
import time
from urllib.parse import parse_qsl

from fastapi import FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from supabase import create_client, Client

APP_DIR = os.path.dirname(os.path.abspath(__file__))
SUPABASE_URL = os.getenv("SUPABASE_URL", "").strip()
SUPABASE_KEY = (os.getenv("SUPABASE_SECRET_KEY") or os.getenv("SUPABASE_SERVICE_ROLE_KEY") or "").strip()
BOT_TOKEN = os.getenv("BOT_TOKEN", "").strip()

app = FastAPI(title="DKD Mini App API", version="1.0.1")
# Telegram WebViews or an externally hosted frontend may send a CORS preflight
# before requests carrying X-Telegram-Init-Data. Handle OPTIONS centrally.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)
app.mount("/static", StaticFiles(directory=APP_DIR), name="static")
supabase: Client | None = None

@app.on_event("startup")
def startup():
    global supabase
    if SUPABASE_URL and SUPABASE_KEY:
        supabase = create_client(SUPABASE_URL, SUPABASE_KEY)

def db() -> Client:
    if supabase is None:
        raise HTTPException(status_code=503, detail="На сервере не настроены SUPABASE_URL и SUPABASE_SECRET_KEY.")
    return supabase

def verify_telegram_init_data(raw: str) -> dict:
    if not BOT_TOKEN:
        raise HTTPException(status_code=503, detail="На сервере не настроен BOT_TOKEN.")
    if not raw:
        raise HTTPException(status_code=401, detail="Откройте приложение через Telegram.")
    values = dict(parse_qsl(raw, keep_blank_values=True))
    received_hash = values.pop("hash", None)
    if not received_hash:
        raise HTTPException(status_code=401, detail="Некорректные данные Telegram.")
    auth_date = values.get("auth_date", "")
    try:
        auth_timestamp = int(auth_date)
    except (TypeError, ValueError):
        raise HTTPException(status_code=401, detail="Некорректная дата авторизации Telegram.")
    now = time.time()
    if auth_timestamp <= 0 or auth_timestamp > now + 60:
        raise HTTPException(status_code=401, detail="Некорректная дата авторизации Telegram.")
    if now - auth_timestamp > 86400:
        raise HTTPException(status_code=401, detail="Сессия Telegram устарела. Закройте и снова откройте Mini App.")
    data_check_string = "\n".join(f"{k}={v}" for k, v in sorted(values.items()))
    secret_key = hmac.new(b"WebAppData", BOT_TOKEN.encode(), hashlib.sha256).digest()
    expected_hash = hmac.new(secret_key, data_check_string.encode(), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected_hash, received_hash):
        raise HTTPException(status_code=401, detail="Не удалось подтвердить авторизацию Telegram.")
    try:
        user = json.loads(values["user"])
    except (KeyError, json.JSONDecodeError):
        raise HTTPException(status_code=401, detail="В данных Telegram отсутствует пользователь.")
    if not user.get("id"):
        raise HTTPException(status_code=401, detail="Не найден Telegram ID.")
    return user

def current_user(x_telegram_init_data: str | None) -> dict:
    return verify_telegram_init_data(x_telegram_init_data or "")

def one_or_none(query):
    try:
        rows = query.execute().data or []
        return rows[0] if rows else None
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Ошибка чтения Supabase. Проверьте таблицы и доступ Data API: {type(exc).__name__}") from exc

def rows_or_error(query):
    try:
        return query.execute().data or []
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Ошибка чтения Supabase. Проверьте таблицы и доступ Data API: {type(exc).__name__}") from exc

def profile_for(user: dict) -> dict:
    client = db()
    user_id = int(user["id"])
    subject = one_or_none(client.table("subjects").select("id,user_id,username,full_name,is_citizen,reputation").eq("user_id", user_id).limit(1))
    if not subject:
        return {
            "user_id": user_id,
            "username": user.get("username"),
            "full_name": " ".join(x for x in [user.get("first_name"), user.get("last_name")] if x) or "Пользователь Telegram",
            "role": "Не зарегистрирован",
            "reputation": 0,
            "party_name": None,
            "is_citizen": False,
            "registered": False,
        }
    role_names = []
    try:
        role_links = rows_or_error(client.table("subject_roles").select("role_id").eq("subject_id", subject["id"]))
        role_ids = [r["role_id"] for r in role_links]
        if role_ids:
            roles = rows_or_error(client.table("roles").select("name,emoji").in_("id", role_ids))
            role_names = [f'{r.get("emoji","")} {r.get("name","")}'.strip() for r in roles]
    except HTTPException:
        role_names = []
    party_name = None
    username = subject.get("username") or user.get("username") or ""
    try:
        membership = one_or_none(client.table("party_members").select("party_id").ilike("username", username).limit(1))
        if membership:
            party = one_or_none(client.table("parties").select("name").eq("id", membership["party_id"]).limit(1))
            party_name = party.get("name") if party else None
    except HTTPException:
        party_name = None
    return {
        **subject,
        "role": ", ".join(role_names) if role_names else ("Гражданин" if subject.get("is_citizen") else "Участник ДКДУ"),
        "party_name": party_name,
        "registered": True,
    }

@app.get("/")
def home():
    # Prefer the expected filename. If it was uploaded as index_fixed.html,
    # serve that file as a recovery fallback instead of crashing with 500.
    candidates = ("index.html", "index_fixed.html")
    for filename in candidates:
        path = os.path.join(APP_DIR, filename)
        if os.path.isfile(path):
            return FileResponse(path, media_type="text/html")
    raise HTTPException(
        status_code=500,
        detail="Не найден index.html. Загрузите index.html в корень репозитория рядом с app.py.",
    )

@app.get("/health")
def health():
    return {"ok": True, "supabase_configured": bool(SUPABASE_URL and SUPABASE_KEY), "telegram_configured": bool(BOT_TOKEN)}

@app.get("/api/profile")
def get_profile(x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data)
    return {"profile": profile_for(user)}

class ProfileUpdate(BaseModel):
    full_name: str = Field(min_length=2, max_length=120)

@app.patch("/api/profile")
def update_profile(payload: ProfileUpdate, x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data)
    client = db()
    name = " ".join(payload.full_name.split())
    if len(name) < 2:
        raise HTTPException(status_code=422, detail="Введите ФИО длиной не менее двух символов.")
    uid = int(user["id"])
    try:
        existing = one_or_none(client.table("subjects").select("id,user_id").eq("user_id", uid).limit(1))
        if existing:
            result = client.table("subjects").update({"full_name": name, "username": user.get("username")}).eq("user_id", uid).execute()
        else:
            result = client.table("subjects").insert({
                "user_id": uid,
                "username": user.get("username"),
                "full_name": name,
                "is_citizen": False,
            }).execute()
        if not result.data:
            raise HTTPException(status_code=502, detail="Supabase не подтвердил сохранение профиля.")
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Не удалось обновить профиль в Supabase: {type(exc).__name__}") from exc
    return {"ok": True, "profile": profile_for(user)}

@app.get("/api/parties")
def get_parties(x_telegram_init_data: str | None = Header(default=None)):
    current_user(x_telegram_init_data)
    client = db()
    parties = rows_or_error(client.table("parties").select("id,name,emoji,description,leader_username,status").eq("status", "active").order("name"))
    for party in parties:
        try:
            members = rows_or_error(client.table("party_members").select("username").eq("party_id", party["id"]))
            party["members_count"] = len(members)
        except HTTPException:
            party["members_count"] = None
    return {"parties": parties}

@app.get("/api/votes")
def get_votes(x_telegram_init_data: str | None = Header(default=None)):
    current_user(x_telegram_init_data)
    client = db()
    pres = rows_or_error(client.table("pres_elections").select("id,ends_at,status,results,created_at").order("id", desc=True).limit(10))
    duma = rows_or_error(client.table("duma_elections").select("id,ends_at,status,results,created_at").order("id", desc=True).limit(10))
    return {"presidential": pres, "duma": duma}

@app.get("/api/documents")
def get_documents(x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data)
    client = db()
    laws = rows_or_error(client.table("laws").select("id,title,description,status,created_at").order("id", desc=True).limit(30))
    decrees = rows_or_error(client.table("decrees").select("id,title,text,is_secret,secret_for,secret_for_user_id,created_at").order("id", desc=True).limit(30))
    visible_decrees = [d for d in decrees if not d.get("is_secret") or d.get("secret_for_user_id") == int(user["id"])]
    return {"laws": laws, "decrees": visible_decrees}

@app.get("/api/news")
def get_news(x_telegram_init_data: str | None = Header(default=None)):
    current_user(x_telegram_init_data)
    news = rows_or_error(db().table("news").select("id,text,created_at").order("id", desc=True).limit(30))
    return {"news": news}

@app.get("/api/config")
def config():
    return {"app": "ДКДУСЛУГИ", "api_version": "1.0.0"}

# --- Secure one-time admin bootstrap and role management ---
import secrets

ADMIN_BOOTSTRAP_CODE = os.getenv("ADMIN_BOOTSTRAP_CODE", "").strip()


def setting_value(key: str):
    row = one_or_none(db().table("app_settings").select("key,value").eq("key", key).limit(1))
    return row.get("value") if row else None


def is_admin(user: dict) -> bool:
    configured_id = os.getenv("ADMIN_TELEGRAM_ID", "").strip()
    if configured_id and str(user.get("id")) == configured_id:
        return True
    try:
        saved_id = setting_value("admin_telegram_id")
        return bool(saved_id) and str(user.get("id")) == str(saved_id)
    except HTTPException:
        return False


def require_admin(user: dict):
    if not is_admin(user):
        raise HTTPException(status_code=403, detail="Доступ только для главного администратора.")


class ClaimAdmin(BaseModel):
    code: str = Field(min_length=8, max_length=256)


@app.post("/api/admin/claim")
def claim_admin(payload: ClaimAdmin, x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data)
    if not ADMIN_BOOTSTRAP_CODE:
        raise HTTPException(status_code=503, detail="Первичная настройка администратора отключена: задайте ADMIN_BOOTSTRAP_CODE в Render.")
    client = db()
    try:
        existing = one_or_none(client.table("app_settings").select("key,value").eq("key", "admin_telegram_id").limit(1))
        if existing and existing.get("value"):
            raise HTTPException(status_code=409, detail="Главный администратор уже назначен. Повторное назначение закрыто.")
        if not secrets.compare_digest(payload.code, ADMIN_BOOTSTRAP_CODE):
            raise HTTPException(status_code=403, detail="Неверный код активации.")
        client.table("app_settings").upsert({"key": "admin_telegram_id", "value": str(user["id"])}, on_conflict="key").execute()
        # Ensure admin role exists and is linked to the claimed Telegram account.
        subj = one_or_none(client.table("subjects").select("id").eq("user_id", int(user["id"])).limit(1))
        if not subj:
            name = " ".join(x for x in [user.get("first_name"), user.get("last_name")] if x) or "Главный администратор"
            subj = client.table("subjects").insert({"user_id": int(user["id"]), "username": user.get("username"), "full_name": name, "is_citizen": True}).execute().data[0]
        admin_role = one_or_none(client.table("roles").select("id").eq("name", "Главный администратор").limit(1))
        if not admin_role:
            admin_role = client.table("roles").insert({"name": "Главный администратор", "emoji": "🛡️"}).execute().data[0]
        client.table("subject_roles").upsert({"subject_id": subj["id"], "role_id": admin_role["id"]}, on_conflict="subject_id,role_id").execute()
        return {"ok": True, "message": "Вы назначены главным администратором. Удалите ADMIN_BOOTSTRAP_CODE из Render после успешной активации."}
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Не удалось активировать администратора: {type(exc).__name__}") from exc


@app.get("/api/admin/status")
def admin_status(x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data)
    return {"is_admin": is_admin(user), "bootstrap_available": bool(ADMIN_BOOTSTRAP_CODE and not setting_value("admin_telegram_id"))}


@app.get("/api/admin/subjects")
def admin_subjects(x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data)
    require_admin(user)
    client = db()
    subjects = rows_or_error(client.table("subjects").select("id,user_id,username,full_name,is_citizen,reputation").order("id"))
    roles = rows_or_error(client.table("roles").select("id,name,emoji").order("name"))
    links = rows_or_error(client.table("subject_roles").select("subject_id,role_id"))
    for subject in subjects:
        ids = [link["role_id"] for link in links if link["subject_id"] == subject["id"]]
        subject["roles"] = [r for r in roles if r["id"] in ids]
    return {"subjects": subjects, "roles": roles}


class RoleCreate(BaseModel):
    name: str = Field(min_length=2, max_length=80)
    emoji: str = Field(default="👤", max_length=12)


class AssignRole(BaseModel):
    role_id: int
    assigned: bool = True


class CitizenUpdate(BaseModel):
    is_citizen: bool


@app.post("/api/admin/roles")
def admin_create_role(payload: RoleCreate, x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); require_admin(user)
    name = " ".join(payload.name.split())
    if len(name) < 2:
        raise HTTPException(status_code=422, detail="Название должности должно содержать минимум 2 символа.")
    try:
        result = db().table("roles").insert({"name": name, "emoji": payload.emoji}).execute()
        if not result.data:
            raise HTTPException(status_code=502, detail="Supabase не подтвердил создание должности.")
        return {"ok": True, "role": result.data[0]}
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=409, detail="Не удалось создать должность: проверьте, что название ещё не занято.") from exc


@app.put("/api/admin/subjects/{subject_id}/roles")
def admin_assign_role(subject_id: int, payload: AssignRole, x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); require_admin(user)
    client = db()
    subject = one_or_none(client.table("subjects").select("id,user_id").eq("id", subject_id).limit(1))
    if not subject:
        raise HTTPException(status_code=404, detail="Пользователь не найден.")
    role = one_or_none(client.table("roles").select("id").eq("id", payload.role_id).limit(1))
    if not role:
        raise HTTPException(status_code=404, detail="Должность не найдена.")
    try:
        query = client.table("subject_roles").delete().eq("subject_id", subject_id).eq("role_id", payload.role_id)
        if payload.assigned:
            client.table("subject_roles").upsert({"subject_id": subject_id, "role_id": payload.role_id}, on_conflict="subject_id,role_id").execute()
        else:
            query.execute()
        return {"ok": True}
    except Exception as exc:
        raise HTTPException(status_code=502, detail="Не удалось обновить должность.") from exc


@app.patch("/api/admin/subjects/{subject_id}/citizenship")
def admin_set_citizenship(subject_id: int, payload: CitizenUpdate, x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); require_admin(user)
    try:
        result = db().table("subjects").update({"is_citizen": payload.is_citizen}).eq("id", subject_id).execute()
        if not result.data:
            raise HTTPException(status_code=404, detail="Пользователь не найден.")
        return {"ok": True}
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=502, detail="Не удалось изменить гражданство.") from exc
