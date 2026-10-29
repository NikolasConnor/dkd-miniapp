import hashlib
import hmac
import json
import os
import time
from urllib.parse import parse_qsl
from pathlib import Path

from fastapi import FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field
from supabase import Client, create_client

BASE_DIR = Path(__file__).resolve().parent
SUPABASE_URL = os.getenv("SUPABASE_URL", "").strip()
SUPABASE_KEY = (os.getenv("SUPABASE_SECRET_KEY") or os.getenv("SUPABASE_SERVICE_ROLE_KEY") or os.getenv("SUPABASE_KEY") or "").strip()
BOT_TOKEN = os.getenv("BOT_TOKEN", "").strip()
ADMIN_TELEGRAM_ID = 7934244888
SECRET_PHRASE = " ".join(os.getenv("CITIZENSHIP_SECRET_PHRASE", "Бог Эфиопии").split()).casefold()
AVATARS = ["👤", "😀", "😎", "🤓", "🦊", "🐺", "🐼", "🐸", "🦁", "🐯", "🐉", "👑", "⚡", "🔥", "🌟", "🎭", "🗳️", "🏛️", "🦅", "🐈"]

app = FastAPI(title="DKD Mini App API", version="1.2.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])
supabase: Client | None = None

@app.on_event("startup")
def startup():
    global supabase
    if SUPABASE_URL and SUPABASE_KEY:
        supabase = create_client(SUPABASE_URL, SUPABASE_KEY)

def db() -> Client:
    if supabase is None:
        raise HTTPException(503, "Supabase не настроен: проверьте SUPABASE_URL и ключ.")
    return supabase

def verify_telegram_init_data(raw: str) -> dict:
    if not BOT_TOKEN:
        raise HTTPException(503, "На сервере не настроен BOT_TOKEN.")
    if not raw:
        raise HTTPException(401, "Откройте приложение через Telegram.")
    values = dict(parse_qsl(raw, keep_blank_values=True))
    received_hash = values.pop("hash", None)
    if not received_hash:
        raise HTTPException(401, "В данных Telegram отсутствует hash.")
    try:
        auth_date = int(values.get("auth_date", "0"))
    except ValueError:
        raise HTTPException(401, "Некорректная дата авторизации Telegram.")
    if auth_date <= 0 or time.time() - auth_date > 86400 or auth_date - time.time() > 60:
        raise HTTPException(401, "Сессия Telegram устарела. Закройте и снова откройте Mini App.")
    check_string = "\n".join(f"{k}={v}" for k, v in sorted(values.items()))
    secret = hmac.new(b"WebAppData", BOT_TOKEN.encode(), hashlib.sha256).digest()
    expected = hmac.new(secret, check_string.encode(), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, received_hash):
        raise HTTPException(401, "Не удалось подтвердить авторизацию Telegram.")
    try:
        user = json.loads(values["user"])
    except (KeyError, json.JSONDecodeError):
        raise HTTPException(401, "В данных Telegram отсутствует пользователь.")
    if not user.get("id"):
        raise HTTPException(401, "Не найден Telegram ID.")
    return user

def current_user(raw: str | None) -> dict:
    return verify_telegram_init_data(raw or "")

def rows(query):
    try:
        return query.execute().data or []
    except Exception as exc:
        raise HTTPException(502, f"Ошибка Supabase ({type(exc).__name__}). Проверьте схему и доступ Data API.") from exc

def one(query):
    result = rows(query)
    return result[0] if result else None

def is_admin(user: dict) -> bool:
    return int(user.get("id", 0)) == ADMIN_TELEGRAM_ID

def profile_for(user: dict) -> dict:
    client = db()
    uid = int(user["id"])
    subject = one(client.table("subjects").select("id,user_id,username,full_name,is_citizen,reputation,avatar_emoji").eq("user_id", uid).limit(1))
    if not subject:
        return {"user_id": uid, "username": user.get("username"),
                "full_name": " ".join(x for x in [user.get("first_name"), user.get("last_name")] if x) or "Пользователь Telegram",
                "role": "Не зарегистрирован", "reputation": 0, "party_name": None,
                "is_citizen": False, "registered": False, "avatar_emoji": "👤",
                "is_admin": is_admin(user)}
    role_names = []
    try:
        links = rows(client.table("subject_roles").select("role_id").eq("subject_id", subject["id"]))
        ids = [x["role_id"] for x in links]
        if ids:
            role_names = [f'{x.get("emoji", "")} {x.get("name", "")}'.strip()
                          for x in rows(client.table("roles").select("name,emoji").in_("id", ids))]
    except HTTPException:
        pass
    party_name = None
    username = subject.get("username") or user.get("username") or ""
    if username:
        try:
            membership = one(client.table("party_members").select("party_id").ilike("username", username).limit(1))
            if membership:
                party = one(client.table("parties").select("name").eq("id", membership["party_id"]).limit(1))
                party_name = party.get("name") if party else None
        except HTTPException:
            pass
    return {**subject, "role": ", ".join(role_names) or ("Гражданин" if subject.get("is_citizen") else "Участник ДКДУ"),
            "party_name": party_name, "registered": True, "is_admin": is_admin(user)}

@app.get("/")
def home():
    index = BASE_DIR / "index.html"
    if index.is_file():
        return FileResponse(index, media_type="text/html")
    raise HTTPException(500, "Не найден index.html рядом с app.py.")

@app.get("/health")
@app.get("/api/health")
def health():
    return {"ok": True, "supabase_configured": bool(SUPABASE_URL and SUPABASE_KEY),
            "telegram_configured": bool(BOT_TOKEN), "api_version": app.version}

@app.get("/api/config")
def config():
    return {"app": "ДКДУСЛУГИ", "api_version": app.version, "avatars": AVATARS}

@app.get("/api/profile")
def get_profile(x_telegram_init_data: str | None = Header(default=None)):
    return {"profile": profile_for(current_user(x_telegram_init_data))}

class ProfileUpdate(BaseModel):
    full_name: str = Field(min_length=2, max_length=120)
    avatar_emoji: str | None = Field(default=None, max_length=16)

@app.patch("/api/profile")
def update_profile(payload: ProfileUpdate, x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data)
    name = " ".join(payload.full_name.split())
    if len(name) < 2:
        raise HTTPException(422, "Введите корректное ФИО.")
    updates = {"full_name": name}
    if payload.avatar_emoji is not None:
        if payload.avatar_emoji not in AVATARS:
            raise HTTPException(422, "Выберите эмодзи из списка.")
        updates["avatar_emoji"] = payload.avatar_emoji
    client = db()
    uid = int(user["id"])
    try:
        existing = one(client.table("subjects").select("id,user_id").eq("user_id", uid).limit(1))
        if existing:
            result = client.table("subjects").update(updates).eq("user_id", uid).execute()
            if not result.data:
                raise HTTPException(502, "Не удалось сохранить профиль.")
        else:
            insert = {"user_id": uid, "username": user.get("username"), "full_name": name,
                      "is_citizen": False, "avatar_emoji": updates.get("avatar_emoji", "👤")}
            result = client.table("subjects").insert(insert).execute()
            if not result.data:
                raise HTTPException(502, "Не удалось создать профиль.")
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(502, f"Не удалось обновить профиль ({type(exc).__name__}). Проверьте миграцию avatar_emoji.") from exc
    return {"ok": True, "profile": profile_for(user)}

class CitizenshipApplication(BaseModel):
    secret_phrase: str = Field(min_length=1, max_length=120)
    full_name: str = Field(min_length=2, max_length=120)

class ApplicationReview(BaseModel):
    decision: str = Field(pattern="^(approved|rejected)$")
    note: str = Field(default="", max_length=500)

@app.get("/api/citizenship")
def citizenship_status(x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data)
    application = one(db().table("citizenship_applications").select("id,full_name,status,created_at,reviewed_at,review_note").eq("user_id", int(user["id"])).order("id", desc=True).limit(1))
    return {"is_citizen": bool(profile_for(user).get("is_citizen")), "application": application}

@app.post("/api/citizenship")
def apply_citizenship(payload: CitizenshipApplication, x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data)
    if " ".join(payload.secret_phrase.split()).casefold() != SECRET_PHRASE:
        raise HTTPException(403, "Секретная фраза не подошла.")
    if profile_for(user).get("is_citizen"):
        raise HTTPException(409, "Вы уже являетесь гражданином.")
    client, uid = db(), int(user["id"])
    pending = one(client.table("citizenship_applications").select("id").eq("user_id", uid).eq("status", "pending").limit(1))
    if pending:
        raise HTTPException(409, "Ваша заявка уже ожидает рассмотрения.")
    name = " ".join(payload.full_name.split())
    result = client.table("citizenship_applications").insert({"user_id": uid, "username": user.get("username"), "full_name": name, "status": "pending"}).execute()
    return {"ok": True, "application": result.data[0] if result.data else {"status": "pending"}}

@app.get("/api/admin/overview")
def admin_overview(x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data)
    if not is_admin(user):
        raise HTTPException(403, "Доступ только президенту.")
    citizens = rows(db().table("subjects").select("id,user_id,username,full_name,is_citizen,avatar_emoji,reputation").order("full_name").limit(200))
    pending = rows(db().table("citizenship_applications").select("id,user_id,username,full_name,status,created_at").eq("status", "pending").order("created_at").limit(100))
    return {"citizens": citizens, "pending_applications": pending, "is_admin": True}

@app.get("/api/admin/citizenship")
def admin_citizenship(x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data)
    if not is_admin(user):
        raise HTTPException(403, "Доступ только президенту.")
    return {"applications": rows(db().table("citizenship_applications").select("*").order("created_at", desc=True).limit(100))}

@app.post("/api/admin/citizenship/{application_id}/review")
def review_citizenship(application_id: int, payload: ApplicationReview, x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data)
    if not is_admin(user):
        raise HTTPException(403, "Доступ только президенту.")
    client = db()
    application = one(client.table("citizenship_applications").select("*").eq("id", application_id).limit(1))
    if not application:
        raise HTTPException(404, "Заявка не найдена.")
    if application.get("status") != "pending":
        raise HTTPException(409, "Заявка уже рассмотрена.")
    uid = int(application["user_id"])
    if payload.decision == "approved":
        subject = one(client.table("subjects").select("id").eq("user_id", uid).limit(1))
        values = {"full_name": application["full_name"], "username": application.get("username"), "is_citizen": True}
        if subject:
            rows(client.table("subjects").update(values).eq("user_id", uid))
        else:
            rows(client.table("subjects").insert({**values, "user_id": uid, "avatar_emoji": "👤"}))
    now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    result = client.table("citizenship_applications").update({"status": payload.decision, "reviewed_at": now, "reviewed_by": int(user["id"]), "review_note": payload.note.strip()}).eq("id", application_id).eq("status", "pending").execute()
    if not result.data:
        raise HTTPException(409, "Заявка уже была изменена.")
    return {"ok": True, "application": result.data[0]}

@app.get("/api/parties")
def get_parties(x_telegram_init_data: str | None = Header(default=None)):
    current_user(x_telegram_init_data)
    client = db()
    parties = rows(client.table("parties").select("id,name,emoji,description,leader_username,status").eq("status", "active").order("name"))
    for party in parties:
        try:
            party["members_count"] = len(rows(client.table("party_members").select("username").eq("party_id", party["id"])))
        except HTTPException:
            party["members_count"] = None
    return {"parties": parties}

@app.get("/api/votes")
def get_votes(x_telegram_init_data: str | None = Header(default=None)):
    current_user(x_telegram_init_data)
    client = db()
    return {"presidential": rows(client.table("pres_elections").select("id,ends_at,status,results,created_at").order("id", desc=True).limit(10)),
            "duma": rows(client.table("duma_elections").select("id,ends_at,status,results,created_at").order("id", desc=True).limit(10))}

@app.get("/api/documents")
def get_documents(x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data)
    client = db()
    laws = rows(client.table("laws").select("id,title,description,status,created_at").order("id", desc=True).limit(30))
    decrees = rows(client.table("decrees").select("id,title,text,is_secret,secret_for,secret_for_user_id,created_at").order("id", desc=True).limit(30))
    visible = [d for d in decrees if not d.get("is_secret") or d.get("secret_for_user_id") == int(user["id"])]
    return {"laws": laws, "decrees": visible}

@app.get("/api/news")
def get_news(x_telegram_init_data: str | None = Header(default=None)):
    current_user(x_telegram_init_data)
    return {"news": rows(db().table("news").select("id,text,created_at").order("id", desc=True).limit(30))}

@app.exception_handler(404)
async def not_found(request, exc):
    if request.url.path.startswith("/api/"):
        return JSONResponse(status_code=404, content={"detail": f"API-маршрут не найден: {request.url.path}"})
    return JSONResponse(status_code=404, content={"detail": "Страница не найдена."})
