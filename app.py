import hashlib
import hmac
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

app = FastAPI(title="DKD Mini App API", version="1.1.0")
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
        if abs(time.time() - int(auth_date)) > 86400:
            raise HTTPException(status_code=401, detail="Сессия Telegram устарела. Закройте и снова откройте Mini App.")
    except ValueError:
        raise HTTPException(status_code=401, detail="Некорректная дата авторизации Telegram.")
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
        # First authenticated visit creates a basic account. It deliberately
        # does NOT grant citizenship or administrator privileges.
        full_name = " ".join(x for x in [user.get("first_name"), user.get("last_name")] if x).strip() or "Пользователь Telegram"
        try:
            created = client.table("subjects").upsert({
                "user_id": user_id,
                "username": user.get("username"),
                "full_name": full_name,
                "is_citizen": False,
                "reputation": 0,
            }, on_conflict="user_id").execute()
            subject = created.data[0] if created.data else None
        except Exception as exc:
            raise HTTPException(status_code=502, detail=f"Не удалось зарегистрировать аккаунт в Supabase: {type(exc).__name__}") from exc
        if not subject:
            # Read after upsert to handle a concurrent first login.
            subject = one_or_none(client.table("subjects").select("id,user_id,username,full_name,is_citizen,reputation").eq("user_id", user_id).limit(1))
        if not subject:
            raise HTTPException(status_code=502, detail="Supabase не подтвердил создание аккаунта.")
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
        membership = one_or_none(client.table("party_members").select("party_id").eq("user_id", user_id).limit(1))
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
    uid = int(user["id"])
    try:
        existing = one_or_none(client.table("subjects").select("id").eq("user_id", uid).limit(1))
        if existing:
            result = client.table("subjects").update({"full_name": name, "username": user.get("username")}).eq("user_id", uid).execute()
        else:
            result = client.table("subjects").upsert({
                "user_id": uid,
                "username": user.get("username"),
                "full_name": name,
                "is_citizen": False,
                "reputation": 0,
            }, on_conflict="user_id").execute()
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Не удалось обновить профиль в Supabase: {type(exc).__name__}") from exc
    if not result.data:
        raise HTTPException(status_code=502, detail="Supabase не подтвердил сохранение профиля.")
    return {"ok": True, "profile": profile_for(user)}

@app.get("/api/parties")
def get_parties(x_telegram_init_data: str | None = Header(default=None)):
    current_user(x_telegram_init_data)
    client = db()
    parties = rows_or_error(client.table("parties").select("id,name,emoji,description,leader_username,leader_user_id,status").eq("status", "active").order("name"))
    for party in parties:
        try:
            members = rows_or_error(client.table("party_members").select("user_id").eq("party_id", party["id"]))
            party["members_count"] = len(members)
        except HTTPException:
            party["members_count"] = None
    return {"parties": parties}

@app.get("/api/votes")
def get_votes(x_telegram_init_data: str | None = Header(default=None)):
    current_user(x_telegram_init_data)
    client = db()
    pres = rows_or_error(client.table("pres_elections").select("id,title,options,ends_at,status,results,created_at").order("id", desc=True).limit(10))
    duma = rows_or_error(client.table("duma_elections").select("id,title,options,ends_at,status,results,created_at").order("id", desc=True).limit(10))
    user = current_user(x_telegram_init_data)
    uid = int(user["id"])
    try:
        cast = rows_or_error(client.table("votes").select("election_type,election_id").eq("user_id", uid))
        voted = {(v["election_type"], int(v["election_id"])) for v in cast}
    except HTTPException:
        voted = set()
    for row in pres:
        row["kind"] = "presidential"
        row["has_voted"] = ("presidential", int(row["id"])) in voted
    for row in duma:
        row["kind"] = "duma"
        row["has_voted"] = ("duma", int(row["id"])) in voted
    try:
        ballots = rows_or_error(client.table("votes").select("election_type,election_id,option"))
        counts = {}
        for ballot in ballots:
            key = (ballot["election_type"], int(ballot["election_id"]))
            counts.setdefault(key, {})[ballot["option"]] = counts.setdefault(key, {}).get(ballot["option"], 0) + 1
        for row in pres:
            row["vote_counts"] = counts.get(("presidential", int(row["id"])), {}) if row.get("status") in ("closed", "finished", "completed") else None
        for row in duma:
            row["vote_counts"] = counts.get(("duma", int(row["id"])), {}) if row.get("status") in ("closed", "finished", "completed") else None
    except HTTPException:
        for row in pres + duma: row["vote_counts"] = None
    return {"presidential": pres, "duma": duma}

class CastVote(BaseModel):
    option: str = Field(min_length=1, max_length=200)

@app.post("/api/votes/{kind}/{election_id}/cast")
def cast_vote(kind: str, election_id: int, payload: CastVote, x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data)
    client = db()
    if kind not in ("presidential", "duma"):
        raise HTTPException(status_code=404, detail="Неизвестный тип выборов.")
    subject = one_or_none(client.table("subjects").select("id,is_citizen").eq("user_id", int(user["id"])).limit(1))
    if not subject or not subject.get("is_citizen"):
        raise HTTPException(status_code=403, detail="Голосовать могут только зарегистрированные граждане.")
    table = "pres_elections" if kind == "presidential" else "duma_elections"
    election = one_or_none(client.table(table).select("id,title,options,ends_at,status").eq("id", election_id).limit(1))
    if not election:
        raise HTTPException(status_code=404, detail="Выборы не найдены.")
    if election.get("status") not in ("active", "open"):
        raise HTTPException(status_code=409, detail="Эти выборы сейчас не открыты.")
    if election.get("ends_at"):
        from datetime import datetime, timezone
        try:
            end = datetime.fromisoformat(str(election["ends_at"]).replace("Z", "+00:00"))
            if end.tzinfo is None: end = end.replace(tzinfo=timezone.utc)
            if end <= datetime.now(timezone.utc):
                raise HTTPException(status_code=409, detail="Срок голосования истёк.")
        except ValueError:
            raise HTTPException(status_code=502, detail="Некорректная дата окончания выборов в базе.")
    options = election.get("options") or []
    if isinstance(options, str):
        try: options = json.loads(options)
        except json.JSONDecodeError: options = []
    if payload.option not in options:
        raise HTTPException(status_code=400, detail="Выбранный вариант отсутствует в списке.")
    try:
        client.table("votes").insert({"user_id": int(user["id"]), "election_type": kind, "election_id": election_id, "option": payload.option}).execute()
    except Exception as exc:
        # A UNIQUE constraint on (user_id, election_type, election_id) is the authoritative duplicate-vote guard.
        raise HTTPException(status_code=409, detail="Голос уже учтён или не удалось сохранить голос. Повторно проголосовать нельзя.") from exc
    return {"ok": True, "message": "Ваш голос сохранён."}

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
    try:
        result = db().table("roles").insert({"name": payload.name.strip(), "emoji": payload.emoji}).execute()
        return {"ok": True, "role": result.data[0]}
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


# Admin publishing and election management. All actions use server-side admin authorization.
class ElectionCreate(BaseModel):
    title: str = Field(min_length=3, max_length=160)
    options: list[str] = Field(min_length=2, max_length=50)
    ends_at: str | None = None

@app.post("/api/admin/elections/{kind}")
def admin_create_election(kind: str, payload: ElectionCreate, x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); require_admin(user)
    if kind not in ("presidential", "duma"):
        raise HTTPException(status_code=404, detail="Неизвестный тип выборов.")
    options = [x.strip() for x in payload.options if x and x.strip()]
    if len(options) < 2 or len(set(options)) != len(options):
        raise HTTPException(status_code=400, detail="Нужно минимум два разных варианта.")
    table = "pres_elections" if kind == "presidential" else "duma_elections"
    try:
        row = db().table(table).insert({"title": payload.title.strip(), "options": options, "ends_at": payload.ends_at, "status": "active"}).execute().data
        db().table("audit_log").insert({"actor_user_id": int(user["id"]), "action": "election_created", "details": f"{kind}:{row[0]['id']}"}).execute()
        return {"ok": True, "election": row[0]}
    except Exception as exc:
        raise HTTPException(status_code=502, detail="Не удалось создать выборы. Проверьте миграцию schema.sql.") from exc

class NewsCreate(BaseModel):
    text: str = Field(min_length=1, max_length=10000)

@app.post("/api/admin/news")
def admin_create_news(payload: NewsCreate, x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); require_admin(user)
    try:
        row = db().table("news").insert({"text": payload.text.strip()}).execute().data
        db().table("audit_log").insert({"actor_user_id": int(user["id"]), "action": "news_created", "details": f"news:{row[0]['id']}"}).execute()
        return {"ok": True, "news": row[0]}
    except Exception as exc:
        raise HTTPException(status_code=502, detail="Не удалось опубликовать новость.") from exc

class LawCreate(BaseModel):
    title: str = Field(min_length=2, max_length=200)
    description: str = Field(default="", max_length=20000)

@app.post("/api/admin/laws")
def admin_create_law(payload: LawCreate, x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); require_admin(user)
    try:
        row = db().table("laws").insert({"title": payload.title.strip(), "description": payload.description.strip(), "status": "active"}).execute().data
        db().table("audit_log").insert({"actor_user_id": int(user["id"]), "action": "law_created", "details": f"law:{row[0]['id']}"}).execute()
        return {"ok": True, "law": row[0]}
    except Exception as exc:
        raise HTTPException(status_code=502, detail="Не удалось опубликовать документ.") from exc
