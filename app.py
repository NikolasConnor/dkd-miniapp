import hashlib
import hmac
import json
import os
import time
from urllib.parse import parse_qsl

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from supabase import create_client, Client

APP_DIR = os.path.dirname(os.path.abspath(__file__))
SUPABASE_URL = os.getenv("SUPABASE_URL", "").strip()
SUPABASE_KEY = (os.getenv("SUPABASE_SECRET_KEY") or os.getenv("SUPABASE_SERVICE_ROLE_KEY") or "").strip()
BOT_TOKEN = os.getenv("BOT_TOKEN", "").strip()

app = FastAPI(title="DKD Mini App API", version="1.0.0")
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
    return FileResponse(os.path.join(APP_DIR, "index.html"))

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
    try:
        result = client.table("subjects").update({"full_name": name}).eq("user_id", int(user["id"])).eq("is_citizen", True).execute()
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Не удалось обновить профиль в Supabase: {type(exc).__name__}") from exc
    if not result.data:
        raise HTTPException(status_code=403, detail="Профиль не найден или изменение ФИО недоступно для этого пользователя.")
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
