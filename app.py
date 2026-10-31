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


class AvatarUpdate(BaseModel):
    avatar_emoji: str = Field(min_length=1, max_length=16)


@app.get("/api/profile")
def get_profile(user: dict = Depends(auth_user)):
    return {"profile": core.build_profile(user)}


@app.patch("/api/profile")
def update_profile(payload: ProfileUpdate, user: dict = Depends(auth_user)):
    return {"ok": True, "profile": core.update_profile_name(user, payload.full_name)}


@app.patch("/api/profile/avatar")
def update_profile_avatar(payload: AvatarUpdate, user: dict = Depends(auth_user)):
    return {"ok": True, "profile": core.update_profile_avatar(user, payload.avatar_emoji)}


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


class PartySettingsUpdate(BaseModel):
    name: str = Field(min_length=2, max_length=60)
    emoji: str = Field(default="🎭", max_length=8)
    description: str = Field(default="", max_length=1000)
    color: str = Field(pattern="^#[0-9A-Fa-f]{6}$")


@app.put("/api/parties/{party_id}")
def update_party_settings(party_id: int, payload: PartySettingsUpdate, user: dict = Depends(auth_user)):
    return {"ok": True, "party": core.update_party_settings(user, party_id, payload.name, payload.emoji, payload.description, payload.color)}


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
    term = core.president_term_status(user)
    return {"is_president": core.is_president(user), "is_admin": core.is_admin(user), "term": term}


class DecreeCreate(BaseModel):
    title: str = Field(min_length=2, max_length=200)
    text: str = Field(min_length=1, max_length=20000)
    secret_for_user_id: int | None = Field(default=None, gt=0)


@app.post("/api/president/decrees")
def president_create_decree(payload: DecreeCreate, user: dict = Depends(president_user)):
    return {"ok": True, "decree": core.publish_decree(user["id"], payload.title, payload.text, payload.secret_for_user_id)}


class PresidentApplicationDecision(BaseModel):
    status: str = Field(pattern="^(approved|rejected)$")
    note: str = Field(default="", max_length=2000)


class PresidentRoleAssignment(BaseModel):
    subject_id: int = Field(gt=0)
    role_id: int = Field(gt=0)
    assigned: bool = True


class PresidentElectionCreate(BaseModel):
    title: str = Field(min_length=2, max_length=200)
    starts_at: str
    ends_at: str


class PresidentLawCreate(BaseModel):
    title: str = Field(min_length=2, max_length=200)
    description: str = Field(default="", max_length=20000)


class PresidentLawDecision(BaseModel):
    status: str = Field(pattern="^(signed|vetoed)$")


@app.get("/api/president/subjects")
def president_subjects(user: dict = Depends(president_user)):
    return core.president_list_subjects()


@app.put("/api/president/roles")
def president_assign_role(payload: PresidentRoleAssignment, user: dict = Depends(president_user)):
    return core.president_assign_role(user["id"], payload.subject_id, payload.role_id, payload.assigned)


@app.get("/api/president/applications")
def president_applications(user: dict = Depends(president_user)):
    return {"applications": core.admin_list_applications()}


@app.patch("/api/president/applications/{application_id}")
def president_review_application(application_id: int, payload: PresidentApplicationDecision,
                                user: dict = Depends(president_user)):
    return core.president_review_application(user["id"], application_id, payload.status, payload.note)


@app.post("/api/president/elections/duma")
def president_create_duma_election(payload: PresidentElectionCreate, user: dict = Depends(president_user)):
    return {"ok": True, "election": core.president_create_duma_election(user["id"], payload.title, payload.starts_at, payload.ends_at)}

@app.get("/api/elections/duma/party-lists")
def duma_party_lists(user: dict = Depends(auth_user)):
    return core.list_duma_elections_for_party(user)


class DumaPartyListSubmit(BaseModel):
    candidates: list[str] = Field(min_length=1, max_length=50)


@app.post("/api/elections/duma/{election_id}/party-list")
def submit_duma_party_list(election_id: int, payload: DumaPartyListSubmit, user: dict = Depends(auth_user)):
    return core.submit_duma_party_list(user, election_id, payload.candidates)


@app.post("/api/admin/elections/duma/{election_id}/close")
def admin_close_duma_election(election_id: int, user: dict = Depends(admin_user)):
    return core.admin_close_duma_election(user["id"], election_id)


@app.post("/api/president/laws")
def president_publish_law(payload: PresidentLawCreate, user: dict = Depends(president_user)):
    return {"ok": True, "law": core.president_publish_law(user["id"], payload.title, payload.description)}


@app.patch("/api/president/laws/{law_id}")
def president_decide_law(law_id: int, payload: PresidentLawDecision, user: dict = Depends(president_user)):
    return {"ok": True, "law": core.president_set_law_status(user["id"], law_id, payload.status)}


class DumaSessionCreate(BaseModel):
    title: str = Field(min_length=2, max_length=160)


class DumaLawCreate(BaseModel):
    title: str = Field(min_length=2, max_length=200)
    description: str = Field(min_length=1, max_length=20000)


class DumaLawVote(BaseModel):
    vote: str = Field(pattern="^(for|against)$")


@app.get("/api/parliament-roster")
def parliament_roster(user: dict = Depends(auth_user)):
    return core.public_duma_roster()


@app.get("/api/duma/constitutional-status")
def duma_constitutional_status(user: dict = Depends(auth_user)):
    core.require_deputy(user)
    return core.duma_constitutional_status(user)


@app.post("/api/duma/presidential-election")
def duma_call_presidential_election(user: dict = Depends(auth_user)):
    core.require_deputy(user)
    return core.duma_call_presidential_election(user["id"])


class ImpeachmentProposal(BaseModel):
    reason: str = Field(min_length=10, max_length=3000)


class ImpeachmentVote(BaseModel):
    vote: str = Field(pattern="^(for|against)$")


@app.post("/api/duma/impeachment")
def duma_propose_impeachment(payload: ImpeachmentProposal, user: dict = Depends(auth_user)):
    core.require_deputy(user)
    return core.duma_propose_impeachment(user["id"], payload.reason)


@app.post("/api/duma/impeachment/vote")
def duma_vote_impeachment(payload: ImpeachmentVote, user: dict = Depends(auth_user)):
    core.require_deputy(user)
    return core.duma_vote_impeachment(user["id"], payload.vote)


@app.get("/api/duma")
def duma_list(user: dict = Depends(auth_user)):
    core.require_deputy(user)
    return core.duma_list(user)


@app.post("/api/duma/sessions")
def duma_open_session(payload: DumaSessionCreate, user: dict = Depends(auth_user)):
    core.require_deputy(user)
    return {"ok": True, "session": core.duma_open_session(user["id"], payload.title)}


@app.post("/api/duma/sessions/{session_id}/close")
def duma_close_session(session_id: int, user: dict = Depends(auth_user)):
    core.require_deputy(user)
    return core.duma_close_session(user["id"], session_id)


@app.post("/api/duma/sessions/{session_id}/laws")
def duma_submit_law(session_id: int, payload: DumaLawCreate, user: dict = Depends(auth_user)):
    core.require_deputy(user)
    return {"ok": True, "law": core.duma_submit_law(user["id"], session_id, payload.title, payload.description)}


@app.post("/api/duma/laws/{law_id}/vote")
def duma_vote_law(law_id: int, payload: DumaLawVote, user: dict = Depends(auth_user)):
    core.require_deputy(user)
    return core.duma_vote_law(user["id"], law_id, payload.vote)


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
