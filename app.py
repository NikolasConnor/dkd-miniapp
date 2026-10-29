import hashlib
import hmac
import json
import os
import time
from pathlib import Path
from urllib.parse import parse_qsl

from fastapi import FastAPI, Header, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field
from supabase import Client, create_client

BASE_DIR = Path(__file__).resolve().parent
SUPABASE_URL = os.getenv("SUPABASE_URL", "").strip()
SUPABASE_KEY = (os.getenv("SUPABASE_SECRET_KEY") or os.getenv("SUPABASE_SERVICE_ROLE_KEY") or os.getenv("SUPABASE_KEY") or "").strip()
BOT_TOKEN = os.getenv("BOT_TOKEN", "").strip()
ADMIN_TELEGRAM_ID = int(os.getenv("ADMIN_TELEGRAM_ID", "7934244888"))
PRESIDENT_TELEGRAM_ID = int(os.getenv("PRESIDENT_TELEGRAM_ID", "0"))
SECRET_PHRASE = " ".join(os.getenv("CITIZENSHIP_SECRET_PHRASE", "Бог Эфиопии").split()).casefold()
# Explicitly approved legacy citizen usernames supplied by the owner.
AUTO_CITIZEN_USERNAMES = {
    x.strip().lstrip("@").casefold()
    for x in os.getenv(
        "AUTO_CITIZEN_USERNAMES",
        "Bloha_71,G12_inthehearts,Sergooo_71,Haiser101,vozduhanprimee,spar9d,Cakcer_12,Dronus01,Nikolas_Connor"
    ).split(",") if x.strip()
}
AVATARS = ["👤","😀","😎","🤓","🦊","🐺","🐼","🐸","🦁","🐯","🐉","👑","⚡","🔥","🌟","🎭","🗳️","🏛️","🦅","🐈"]

app = FastAPI(title="DKD Mini App API", version="2.0.0")
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])
supabase: Client | None = None

@app.on_event("startup")
def startup():
    global supabase
    if SUPABASE_URL and SUPABASE_KEY:
        supabase = create_client(SUPABASE_URL, SUPABASE_KEY)

def db() -> Client:
    if supabase is None:
        raise HTTPException(503, "Supabase не настроен: проверьте SUPABASE_URL и серверный ключ.")
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
        raise HTTPException(502, f"Ошибка Supabase ({type(exc).__name__}). Проверьте schema.sql и переменные окружения.") from exc

def one(query):
    result = rows(query)
    return result[0] if result else None

def is_admin(user: dict) -> bool:
    return int(user.get("id", 0)) == ADMIN_TELEGRAM_ID

def is_president(user: dict) -> bool:
    return PRESIDENT_TELEGRAM_ID > 0 and int(user.get("id", 0)) == PRESIDENT_TELEGRAM_ID

def require_admin(user):
    if not is_admin(user):
        raise HTTPException(403, "Доступ только администратору.")

def require_leadership(user):
    if not (is_admin(user) or is_president(user)):
        raise HTTPException(403, "Доступ только президенту или администратору.")

def upsert_subject(user, full_name=None, avatar=None):
    client, uid = db(), int(user["id"])
    username = (user.get("username") or "").strip()
    existing = one(client.table("subjects").select("*").eq("user_id", uid).limit(1))
    auto_citizen = uid == ADMIN_TELEGRAM_ID or username.casefold() in AUTO_CITIZEN_USERNAMES
    values = {
        "user_id": uid,
        "username": username or None,
        "full_name": full_name or (" ".join(x for x in [user.get("first_name"), user.get("last_name")] if x) or "Пользователь Telegram"),
    }
    if avatar:
        values["avatar_emoji"] = avatar
    if existing:
        # Preserve manually granted citizenship; ensure only the configured allowlist/admin can be auto-promoted.
        if auto_citizen and not existing.get("is_citizen"):
            values["is_citizen"] = True
        if not existing.get("avatar_emoji"):
            values["avatar_emoji"] = avatar or "👤"
        rows(client.table("subjects").update(values).eq("user_id", uid))
    else:
        values.update({"is_citizen": bool(auto_citizen), "avatar_emoji": avatar or "👤", "reputation": 0})
        rows(client.table("subjects").insert(values))
    return one(client.table("subjects").select("*").eq("user_id", uid).limit(1))

def profile_for(user):
    subject = upsert_subject(user)
    client = db()
    role_names = []
    try:
        links = rows(client.table("subject_roles").select("role_id").eq("subject_id", subject["id"]))
        ids = [x["role_id"] for x in links]
        if ids:
            role_names = [f'{x.get("emoji") or ""} {x.get("name") or ""}'.strip()
                          for x in rows(client.table("roles").select("name,emoji").in_("id", ids))]
    except HTTPException:
        pass
    username = subject.get("username") or user.get("username") or ""
    party_name = None
    if username:
        try:
            membership = one(client.table("party_members").select("party_id").ilike("username", username).limit(1))
            if membership:
                party = one(client.table("parties").select("name").eq("id", membership["party_id"]).limit(1))
                party_name = party.get("name") if party else None
        except HTTPException:
            pass
    return {**subject, "role": ", ".join(role_names) or ("Гражданин" if subject.get("is_citizen") else "Участник ДКДУ"),
            "party_name": party_name, "registered": True, "is_admin": is_admin(user),
            "is_president": is_president(user), "admin_mode_available": is_admin(user)}

@app.get("/")
def home():
    file = BASE_DIR / "index.html"
    if not file.is_file():
        raise HTTPException(500, "Не найден index.html рядом с app.py.")
    return FileResponse(file, media_type="text/html")

@app.get("/health")
@app.get("/api/health")
def health():
    return {"ok": True, "supabase_configured": bool(SUPABASE_URL and SUPABASE_KEY),
            "telegram_configured": bool(BOT_TOKEN), "api_version": app.version}

@app.get("/api/config")
def config():
    return {"app": "ДКДУСЛУГИ", "api_version": app.version, "avatars": AVATARS}

@app.get("/api/access")
def access_info(x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data)
    return {"is_admin": is_admin(user), "is_president": is_president(user),
            "admin_mode_available": is_admin(user)}

@app.get("/api/profile")
def get_profile(x_telegram_init_data: str | None = Header(default=None)):
    return {"profile": profile_for(current_user(x_telegram_init_data))}

class ProfileUpdate(BaseModel):
    full_name: str = Field(min_length=2, max_length=120)
    avatar_emoji: str = Field(default="👤", max_length=16)

@app.patch("/api/profile")
def update_profile(payload: ProfileUpdate, x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data)
    name = " ".join(payload.full_name.split())
    if len(name) < 2:
        raise HTTPException(422, "Введите корректное ФИО.")
    if payload.avatar_emoji not in AVATARS:
        raise HTTPException(422, "Выберите эмодзи из списка.")
    upsert_subject(user, full_name=name, avatar=payload.avatar_emoji)
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
    app_row = one(db().table("citizenship_applications").select("*").eq("user_id", int(user["id"])).order("id", desc=True).limit(1))
    return {"is_citizen": bool(profile_for(user).get("is_citizen")), "application": app_row}

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
    result = rows(client.table("citizenship_applications").insert({"user_id": uid, "username": user.get("username"), "full_name": name, "status": "pending"}))
    return {"ok": True, "application": result[0] if result else {"status": "pending"}}

@app.get("/api/admin/overview")
def admin_overview(x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); require_admin(user)
    citizens = rows(db().table("subjects").select("id,user_id,username,full_name,is_citizen,avatar_emoji,reputation").order("full_name").limit(500))
    pending = rows(db().table("citizenship_applications").select("*").eq("status", "pending").order("created_at").limit(200))
    return {"citizens": citizens, "pending_applications": pending}

@app.get("/api/president/overview")
def president_overview(x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); require_leadership(user)
    citizens = rows(db().table("subjects").select("id,user_id,username,full_name,is_citizen,avatar_emoji,reputation").order("full_name").limit(500))
    pending = rows(db().table("citizenship_applications").select("*").eq("status", "pending").order("created_at").limit(200))
    return {"citizens": citizens, "pending_applications": pending}

@app.get("/api/admin/users")
def admin_users(q: str = "", x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); require_admin(user)
    people = rows(db().table("subjects").select("*").order("full_name").limit(500))
    if q.strip():
        needle = q.strip().casefold()
        people = [p for p in people if needle in str(p.get("full_name","")).casefold() or needle in str(p.get("username","")).casefold() or needle in str(p.get("user_id",""))]
    return {"users": people}

class CitizenStatus(BaseModel):
    is_citizen: bool

@app.patch("/api/admin/users/{user_id}/citizenship")
def set_citizen(user_id: int, payload: CitizenStatus, x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); require_admin(user)
    result = rows(db().table("subjects").update({"is_citizen": payload.is_citizen}).eq("user_id", user_id))
    if not result:
        raise HTTPException(404, "Профиль не найден.")
    return {"ok": True, "user": result[0]}

@app.get("/api/admin/citizenship")
def admin_citizenship(x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); require_admin(user)
    return {"applications": rows(db().table("citizenship_applications").select("*").order("created_at", desc=True).limit(200))}

@app.post("/api/admin/citizenship/{application_id}/review")
def review_citizenship(application_id: int, payload: ApplicationReview, x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); require_leadership(user)
    client = db()
    application = one(client.table("citizenship_applications").select("*").eq("id", application_id).limit(1))
    if not application: raise HTTPException(404, "Заявка не найдена.")
    if application.get("status") != "pending": raise HTTPException(409, "Заявка уже рассмотрена.")
    uid = int(application["user_id"])
    if payload.decision == "approved":
        subject = one(client.table("subjects").select("id").eq("user_id", uid).limit(1))
        values = {"full_name": application["full_name"], "username": application.get("username"), "is_citizen": True}
        if subject: rows(client.table("subjects").update(values).eq("user_id", uid))
        else: rows(client.table("subjects").insert({**values, "user_id": uid, "avatar_emoji": "👤", "reputation": 0}))
    now = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    result = rows(client.table("citizenship_applications").update({"status": payload.decision, "reviewed_at": now, "reviewed_by": int(user["id"]), "review_note": payload.note.strip()}).eq("id", application_id).eq("status", "pending"))
    if not result: raise HTTPException(409, "Заявка уже была изменена.")
    try:
        rows(client.table("audit_log").insert({"actor_user_id": int(user["id"]), "action": "citizenship_"+payload.decision, "target_user_id": uid, "details": payload.note.strip()}))
    except HTTPException: pass
    return {"ok": True, "application": result[0]}

@app.get("/api/parties")
def get_parties(x_telegram_init_data: str | None = Header(default=None)):
    current_user(x_telegram_init_data)
    parties = rows(db().table("parties").select("*").order("name").limit(200))
    for p in parties:
        try: p["members_count"] = len(rows(db().table("party_members").select("username").eq("party_id", p["id"])))
        except HTTPException: p["members_count"] = None
    return {"parties": parties}

@app.get("/api/votes")
def get_votes(x_telegram_init_data: str | None = Header(default=None)):
    current_user(x_telegram_init_data); client = db()
    return {"presidential": rows(client.table("pres_elections").select("*").order("id", desc=True).limit(50)),
            "duma": rows(client.table("duma_elections").select("*").order("id", desc=True).limit(50))}

@app.get("/api/documents")
def get_documents(x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); client = db(); uid = int(user["id"])
    laws = rows(client.table("laws").select("*").order("id", desc=True).limit(100))
    # Private records are filtered server-side; only owner or leadership can see them.
    docs = rows(client.table("decrees").select("*").order("id", desc=True).limit(200))
    leadership = is_admin(user) or is_president(user)
    visible = [d for d in docs if not d.get("is_secret") or leadership or d.get("secret_for_user_id") == uid]
    return {"laws": laws, "decrees": visible, "personal_user_id": uid}

@app.get("/api/news")
def get_news(x_telegram_init_data: str | None = Header(default=None)):
    current_user(x_telegram_init_data)
    return {"news": rows(db().table("news").select("*").order("id", desc=True).limit(100))}

@app.get("/api/admin/content")
def admin_content(x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); require_admin(user); client = db()
    result = {}
    for table in ["news","laws","decrees","parties","pres_elections","duma_elections"]:
        result[table] = rows(client.table(table).select("*").order("id", desc=True).limit(200))
    return result

class ContentItem(BaseModel):
    title: str = Field(default="", max_length=200)
    text: str = Field(default="", max_length=10000)
    description: str = Field(default="", max_length=5000)
    emoji: str = Field(default="📌", max_length=16)
    status: str = Field(default="active", max_length=40)
    is_secret: bool = False
    secret_for_user_id: int | None = None
    leader_username: str = Field(default="", max_length=64)

@app.post("/api/admin/content/{table}")
def create_content(table: str, payload: ContentItem, x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); require_admin(user)
    allowed = {"news","laws","decrees","parties","pres_elections","duma_elections"}
    if table not in allowed: raise HTTPException(404, "Неизвестный тип данных.")
    data = payload.model_dump(exclude_unset=True)
    # Map common editor fields to the schema used by each table.
    if table == "news": data = {"text": payload.text or payload.title}
    elif table == "laws": data = {"title": payload.title, "description": payload.description or payload.text, "status": payload.status}
    elif table == "decrees": data = {"title": payload.title, "text": payload.text or payload.description, "is_secret": payload.is_secret, "secret_for_user_id": payload.secret_for_user_id}
    elif table == "parties": data = {"name": payload.title, "emoji": payload.emoji, "description": payload.description or payload.text, "leader_username": payload.leader_username or None, "status": payload.status}
    elif table in {"pres_elections","duma_elections"}: data = {"title": payload.title, "status": payload.status, "ends_at": None}
    result = rows(db().table(table).insert(data))
    try: rows(db().table("audit_log").insert({"actor_user_id": int(user["id"]), "action": "create_"+table, "target_user_id": payload.secret_for_user_id, "details": payload.title or payload.text[:300]}))
    except HTTPException: pass
    return {"ok": True, "item": result[0] if result else {}}

@app.get("/api/admin/audit")
def get_audit(x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); require_admin(user)
    return {"entries": rows(db().table("audit_log").select("*").order("id", desc=True).limit(200))}

@app.get("/api/president/requests")
def president_requests(x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); require_leadership(user)
    return {"applications": rows(db().table("citizenship_applications").select("*").order("created_at", desc=True).limit(200))}

@app.get("/api/president/passports")
def president_passports(x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); require_leadership(user)
    return {"passports": rows(db().table("subjects").select("user_id,username,full_name,is_citizen,avatar_emoji").eq("is_citizen", True).order("full_name").limit(500))}

@app.get("/api/president/decrees")
def president_decrees(x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); require_leadership(user)
    return {"decrees": rows(db().table("decrees").select("*").order("id", desc=True).limit(200))}

@app.get("/api/president/parties")
def president_parties(x_telegram_init_data: str | None = Header(default=None)):
    user = current_user(x_telegram_init_data); require_leadership(user)
    return {"parties": rows(db().table("parties").select("*").order("id", desc=True).limit(200))}
