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
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from supabase import Client, create_client

BASE_DIR = Path(__file__).resolve().parent
SUPABASE_URL = os.getenv("SUPABASE_URL", "").strip()
SUPABASE_KEY = (os.getenv("SUPABASE_SECRET_KEY") or os.getenv("SUPABASE_SERVICE_ROLE_KEY") or os.getenv("SUPABASE_KEY") or "").strip()
BOT_TOKEN = os.getenv("BOT_TOKEN", "").strip()

app = FastAPI(title="DKD Mini App API", version="1.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)
supabase: Client | None = None

@app.on_event("startup")
def startup():
    global supabase
    if SUPABASE_URL and SUPABASE_KEY:
        supabase = create_client(SUPABASE_URL, SUPABASE_KEY)

def db() -> Client:
    if supabase is None:
        raise HTTPException(status_code=503, detail="Supabase не настроен: проверьте SUPABASE_URL и ключ.")
    return supabase

def verify_telegram_init_data(raw: str) -> dict:
    if not BOT_TOKEN:
        raise HTTPException(status_code=503, detail="На сервере не настроен BOT_TOKEN.")
    if not raw:
        raise HTTPException(status_code=401, detail="Откройте приложение через Telegram.")
    values = dict(parse_qsl(raw, keep_blank_values=True))
    received_hash = values.pop("hash", None)
    if not received_hash:
        raise HTTPException(status_code=401, detail="В данных Telegram отсутствует hash.")
    try:
        auth_date = int(values.get("auth_date", "0"))
    except ValueError:
        raise HTTPException(status_code=401, detail="Некорректная дата авторизации Telegram.")
    if auth_date <= 0 or time.time() - auth_date > 86400 or auth_date - time.time() > 60:
        raise HTTPException(status_code=401, detail="Сессия Telegram устарела. Закройте и снова откройте Mini App.")
    check_string = "\n".join(f"{k}={v}" for k, v in sorted(values.items()))
    secret = hmac.new(b"WebAppData", BOT_TOKEN.encode(), hashlib.sha256).digest()
    expected = hmac.new(secret, check_string.encode(), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, received_hash):
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

def rows(query):
    try:
        return query.execute().data or []
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Ошибка Supabase ({type(exc).__name__}). Проверьте названия таблиц, столбцов и доступ Data API.") from exc

def one(query):
    result = rows(query)
    return result[0] if result else None

def profile_for(user: dict) -> dict:
    client = db()
    uid = int(user["id"])
    subject = one(client.table("subjects").select("id,user_id,username,full_name,is_citizen,reputation").eq("user_id", uid).limit(1))
    if not subject:
        return {
            "user_id": uid,
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
            "party_name": party_name, "registered": True}

@app.get("/")
def home():
    index = BASE_DIR / "index.html"
    if index.is_file():
        return FileResponse(index, media_type="text/html")
    raise HTTPException(status_code=500, detail="Не найден index.html рядом с app.py.")

@app.get("/health")
@app.get("/api/health")
def health():
    return {"ok": True, "supabase_configured": bool(SUPABASE_URL and SUPABASE_KEY),
            "telegram_configured": bool(BOT_TOKEN), "api_version": app.version}

@app.get("/api/config")
def config():
    return {"app": "ДКДУСЛУГИ", "api_version": app.version}

@app.get("/api/profile")
def get_profile(x_telegram_init_data: str | None = Header(default=None)):
    return {"profile": profile_for(current_user(x_telegram_init_data))}

class ProfileUpdate(BaseModel):
    full_name: str = Field(min_length=2, max_length=120)

@app.patch("/api/profile")
def update_profile(payload: ProfileUpdate, x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data)
    name = " ".join(payload.full_name.split())
    try:
        result = db().table("subjects").update({"full_name": name}).eq("user_id", int(user["id"])).eq("is_citizen", True).execute()
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Не удалось обновить профиль в Supabase ({type(exc).__name__}).") from exc
    if not result.data:
        raise HTTPException(status_code=403, detail="Профиль не найден или изменение ФИО недоступно этому пользователю.")
    return {"ok": True, "profile": profile_for(user)}

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
    pres = rows(client.table("pres_elections").select("id,ends_at,status,results,created_at").order("id", desc=True).limit(10))
    duma = rows(client.table("duma_elections").select("id,ends_at,status,results,created_at").order("id", desc=True).limit(10))
    return {"presidential": pres, "duma": duma}

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
    news = rows(db().table("news").select("id,text,created_at").order("id", desc=True).limit(30))
    return {"news": news}

@app.exception_handler(404)
async def not_found(request, exc):
    # Keep unknown API paths machine-readable, not an HTML 404 page.
    if request.url.path.startswith("/api/"):
        return JSONResponse(status_code=404, content={"detail": f"API-маршрут не найден: {request.url.path}"})
    return JSONResponse(status_code=404, content={"detail": "Страница не найдена."})
