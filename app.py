"""ДКД Mini App — HTTP API (FastAPI).

Здесь только маршруты и проверка входных данных. Вся логика — в core.py.
Права проверяются на сервере через зависимости admin_user / president_user:
каждый маршрут /api/admin/* и /api/president/* обязан их использовать
(это проверяет tests/test_static.py).
"""
import logging
import os
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field

import core
from core import DKDError

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))

APP_DIR = os.path.dirname(os.path.abspath(__file__))


@asynccontextmanager
async def lifespan(_: FastAPI):
    core.init_client_from_env()
    yield


app = FastAPI(title="DKD Mini App API", version=core.APP_VERSION, lifespan=lifespan)

# Фронтенд отдаётся этим же сервером (same-origin), CORS не нужен.
# Если интерфейс размещён на другом домене — перечислите его в CORS_ORIGINS через запятую.
_origins = [o.strip() for o in os.getenv("CORS_ORIGINS", "").split(",") if o.strip()]
if _origins:
    app.add_middleware(CORSMiddleware, allow_origins=_origins, allow_methods=["*"],
                       allow_headers=["Content-Type", "X-Telegram-Init-Data"])


@app.exception_handler(DKDError)
async def dkd_error_handler(_: Request, exc: DKDError):
    return JSONResponse(status_code=exc.status, content={"detail": exc.detail})


@app.middleware("http")
async def security_headers(request: Request, call_next):
    response = await call_next(request)
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("Referrer-Policy", "no-referrer")
    if request.url.path.startswith("/api/"):
        response.headers["Cache-Control"] = "no-store"
    return response


# ----------------------------------------------------------------------------
# Авторизация (зависимости)
# ----------------------------------------------------------------------------


def auth_user(x_telegram_init_data: str | None = Header(default=None)) -> dict:
    return core.verify_init_data(x_telegram_init_data or "")


def admin_user(user: dict = Depends(auth_user)) -> dict:
    core.require_admin(user)
    return user


def president_user(user: dict = Depends(auth_user)) -> dict:
    core.require_president(user)
    return user


# ----------------------------------------------------------------------------
# Страница и служебное
# ----------------------------------------------------------------------------


@app.get("/")
def home():
    path = os.path.join(APP_DIR, "index.html")
    if not os.path.isfile(path):
        raise DKDError(500, "Не найден index.html рядом с app.py.")
    return FileResponse(path, media_type="text/html", headers={"Cache-Control": "no-cache"})


@app.get("/health")
def health():
    return {"ok": True, **core.configured()}


@app.get("/api/config")
def config():
    return {"app": "ДКД Mini App", "api_version": core.APP_VERSION}


# ----------------------------------------------------------------------------
# Профиль
# ----------------------------------------------------------------------------


class ProfileUpdate(BaseModel):
    full_name: str = Field(min_length=2, max_length=120)


@app.get("/api/profile")
def get_profile(user: dict = Depends(auth_user)):
    return {"profile": core.build_profile(user)}


@app.patch("/api/profile")
def update_profile(payload: ProfileUpdate, user: dict = Depends(auth_user)):
    return {"ok": True, "profile": core.update_profile_name(user, payload.full_name)}


# ----------------------------------------------------------------------------
# Паспорт и заявления
# ----------------------------------------------------------------------------


@app.get("/api/passport")
def get_passport(user: dict = Depends(auth_user)):
    return core.my_passport(user)


class ApplicationCreate(BaseModel):
    kind: str = Field(pattern="^(citizenship|other)$")
    full_name: str = Field(min_length=2, max_length=120)
    details: str = Field(default="", max_length=5000)


@app.get("/api/applications")
def list_my_applications(user: dict = Depends(auth_user)):
    return {"applications": core.list_my_applications(user["id"])}


@app.post("/api/applications")
def create_application(payload: ApplicationCreate, user: dict = Depends(auth_user)):
    return {"ok": True, "application": core.create_application(user, payload.kind, payload.full_name, payload.details)}


# ----------------------------------------------------------------------------
# Документы, новости
# ----------------------------------------------------------------------------


@app.get("/api/documents")
def get_documents(user: dict = Depends(auth_user)):
    return core.list_documents(user, core.is_admin(user))


@app.get("/api/news")
def get_news(user: dict = Depends(auth_user)):
    return {"news": core.list_news()}


# ----------------------------------------------------------------------------
# Партии
# ----------------------------------------------------------------------------


class PartyCreate(BaseModel):
    name: str = Field(min_length=2, max_length=60)
    emoji: str = Field(default="🎭", max_length=8)
    description: str = Field(default="", max_length=1000)


@app.get("/api/parties")
def get_parties(user: dict = Depends(auth_user)):
    return core.list_parties(user, core.is_admin(user))


@app.post("/api/parties")
def create_party(payload: PartyCreate, user: dict = Depends(auth_user)):
    return {"ok": True, "party": core.create_party(user, payload.name, payload.emoji, payload.description)}


@app.post("/api/parties/leave")
def leave_party(user: dict = Depends(auth_user)):
    return core.leave_party(user)


@app.post("/api/parties/{party_id}/join")
def join_party(party_id: int, user: dict = Depends(auth_user)):
    return core.join_party(user, party_id)


@app.delete("/api/parties/{party_id}/members/{member_id}")
def kick_member(party_id: int, member_id: int, user: dict = Depends(auth_user)):
    return core.kick_member(user, core.is_admin(user), party_id, member_id)


@app.delete("/api/parties/{party_id}")
def disband_party(party_id: int, user: dict = Depends(auth_user)):
    return core.disband_party(user, core.is_admin(user), party_id)


# ----------------------------------------------------------------------------
# Выборы
# ----------------------------------------------------------------------------


class CastVote(BaseModel):
    option: str = Field(min_length=1, max_length=200)


@app.get("/api/votes")
def get_votes(user: dict = Depends(auth_user)):
    return core.list_elections(user["id"])


@app.post("/api/votes/{kind}/{election_id}/cast")
def cast_vote(kind: str, election_id: int, payload: CastVote, user: dict = Depends(auth_user)):
    return core.cast_vote(user, kind, election_id, payload.option)


# ----------------------------------------------------------------------------
# Президент
# ----------------------------------------------------------------------------


@app.get("/api/president/status")
def president_status(user: dict = Depends(auth_user)):
    return {"is_president": core.is_president(user), "is_admin": core.is_admin(user)}


class DecreeCreate(BaseModel):
    title: str = Field(min_length=2, max_length=200)
    text: str = Field(min_length=1, max_length=20000)
    secret_for_user_id: int | None = Field(default=None, gt=0)


@app.post("/api/president/decrees")
def president_create_decree(payload: DecreeCreate, user: dict = Depends(president_user)):
    return {"ok": True, "decree": core.publish_decree(user["id"], payload.title, payload.text, payload.secret_for_user_id)}


# ----------------------------------------------------------------------------
# Администратор
# ----------------------------------------------------------------------------


class ClaimAdmin(BaseModel):
    code: str = Field(min_length=8, max_length=256)


@app.post("/api/admin/claim")
def claim_admin(payload: ClaimAdmin, user: dict = Depends(auth_user)):
    # Единственный admin-маршрут без admin_user: им как раз назначают первого администратора.
    # Защита: одноразовый код ADMIN_BOOTSTRAP_CODE, лимит попыток, закрывается после назначения.
    return core.claim_admin(user, payload.code)


@app.get("/api/admin/status")
def admin_status(user: dict = Depends(auth_user)):
    return core.admin_status(user)


@app.get("/api/admin/diagnostics")
def admin_diagnostics(user: dict = Depends(admin_user)):
    return {"tables": core.diagnostics()}


@app.get("/api/admin/applications")
def admin_list_applications(user: dict = Depends(admin_user)):
    return {"applications": core.admin_list_applications()}


class ApplicationReview(BaseModel):
    status: str = Field(pattern="^(approved|rejected)$")
    review_note: str = Field(default="", max_length=2000)


@app.patch("/api/admin/applications/{application_id}")
def admin_review_application(application_id: int, payload: ApplicationReview, user: dict = Depends(admin_user)):
    return core.review_application(user["id"], application_id, payload.status, payload.review_note)


@app.get("/api/admin/subjects")
def admin_subjects(user: dict = Depends(admin_user)):
    return core.admin_list_subjects()


class RoleCreate(BaseModel):
    name: str = Field(min_length=2, max_length=80)
    emoji: str = Field(default="👤", max_length=12)


@app.post("/api/admin/roles")
def admin_create_role(payload: RoleCreate, user: dict = Depends(admin_user)):
    return {"ok": True, "role": core.admin_create_role(user["id"], payload.name, payload.emoji)}


class AssignRole(BaseModel):
    role_id: int
    assigned: bool = True


@app.put("/api/admin/subjects/{subject_id}/roles")
def admin_assign_role(subject_id: int, payload: AssignRole, user: dict = Depends(admin_user)):
    return core.admin_assign_role(user["id"], subject_id, payload.role_id, payload.assigned)


class CitizenUpdate(BaseModel):
    is_citizen: bool


@app.patch("/api/admin/subjects/{subject_id}/citizenship")
def admin_set_citizenship(subject_id: int, payload: CitizenUpdate, user: dict = Depends(admin_user)):
    return core.admin_set_citizenship(user["id"], subject_id, payload.is_citizen)


class GrantById(BaseModel):
    telegram_id: int = Field(gt=0)
    full_name: str = Field(min_length=2, max_length=120)


@app.post("/api/admin/citizenship/by-id")
def admin_grant_by_id(payload: GrantById, user: dict = Depends(admin_user)):
    return core.admin_grant_by_telegram_id(user["id"], payload.telegram_id, payload.full_name)


class ElectionCreate(BaseModel):
    title: str = Field(min_length=3, max_length=160)
    options: list[str] = Field(min_length=2, max_length=50)
    ends_at: str | None = None


@app.post("/api/admin/elections/{kind}")
def admin_create_election(kind: str, payload: ElectionCreate, user: dict = Depends(admin_user)):
    return {"ok": True, "election": core.create_election(user["id"], kind, payload.title, payload.options, payload.ends_at)}


@app.post("/api/admin/elections/{kind}/{election_id}/close")
def admin_close_election(kind: str, election_id: int, user: dict = Depends(admin_user)):
    return core.close_election(user["id"], kind, election_id)


class NewsCreate(BaseModel):
    text: str = Field(min_length=1, max_length=10000)


@app.post("/api/admin/news")
def admin_create_news(payload: NewsCreate, user: dict = Depends(admin_user)):
    return {"ok": True, "news": core.publish_news(user["id"], payload.text)}


class LawCreate(BaseModel):
    title: str = Field(min_length=2, max_length=200)
    description: str = Field(default="", max_length=20000)


@app.post("/api/admin/laws")
def admin_create_law(payload: LawCreate, user: dict = Depends(admin_user)):
    return {"ok": True, "law": core.publish_law(user["id"], payload.title, payload.description)}
