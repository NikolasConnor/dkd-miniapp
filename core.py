"""Бизнес-логика ДКД Mini App.

Модуль намеренно не зависит от FastAPI/pydantic: вся логика (права, гражданство,
паспорта, заявления, выборы, партии) работает поверх клиента Supabase и
проверяется автотестами с имитацией базы (см. tests/).

Маршруты HTTP находятся в app.py и только вызывают функции отсюда.
"""
from __future__ import annotations

import calendar
import hashlib
import hmac
import json
import logging
import os
import re
import secrets
import time
import uuid
from collections import Counter
from datetime import datetime, timezone
from typing import Any, Callable
from urllib.parse import parse_qsl

log = logging.getLogger("dkd")

APP_VERSION = "2.0.0"

# ----------------------------------------------------------------------------
# Ошибки и конфигурация
# ----------------------------------------------------------------------------


class DKDError(Exception):
    """Ошибка с HTTP-статусом и текстом для пользователя."""

    def __init__(self, status: int, detail: str):
        super().__init__(detail)
        self.status = status
        self.detail = detail


def env(name: str, default: str = "") -> str:
    return (os.getenv(name, default) or "").strip()


_client: Any = None


def set_client(client: Any) -> None:
    """Подмена клиента БД (используется в тестах)."""
    global _client
    _client = client


def init_client_from_env() -> None:
    global _client
    if _client is not None:
        return
    url = env("SUPABASE_URL")
    key = env("SUPABASE_SECRET_KEY") or env("SUPABASE_SERVICE_ROLE_KEY")
    if url and key:
        from supabase import create_client  # импорт здесь, чтобы тесты работали без пакета

        _client = create_client(url, key)


def configured() -> dict:
    return {
        "supabase_configured": bool(env("SUPABASE_URL") and (env("SUPABASE_SECRET_KEY") or env("SUPABASE_SERVICE_ROLE_KEY"))),
        "telegram_configured": bool(env("BOT_TOKEN")),
    }


def db() -> Any:
    if _client is None:
        raise DKDError(503, "На сервере не настроены SUPABASE_URL и SUPABASE_SECRET_KEY.")
    return _client


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def now_iso() -> str:
    return utcnow().isoformat()


# ----------------------------------------------------------------------------
# Обёртки над запросами к БД
# ----------------------------------------------------------------------------

_MIGRATION_HINT = " Похоже, SQL-миграция не применена к базе Supabase (см. README, шаг 1)."


def db_error(exc: Exception, public: str = "Ошибка базы данных.") -> DKDError:
    """Пользователю — короткое сообщение и код; подробности только в журнале сервера."""
    ref = uuid.uuid4().hex[:8]
    log.error("DB error %s: %s: %s", ref, type(exc).__name__, exc)
    text = str(exc)
    detail = f"{public} Код ошибки: {ref}."
    if "does not exist" in text or "Could not find" in text or "PGRST" in text:
        detail += _MIGRATION_HINT
    if env("DEBUG_ERRORS").lower() in ("1", "true", "yes"):
        detail += f" [{type(exc).__name__}: {text[:300]}]"
    return DKDError(502, detail)


def execute(query: Any, public: str = "Ошибка базы данных.") -> list:
    try:
        return query.execute().data or []
    except DKDError:
        raise
    except Exception as exc:  # noqa: BLE001 — клиент Supabase бросает разные типы
        raise db_error(exc, public) from exc


def rows(query: Any, public: str = "Ошибка базы данных.") -> list:
    return execute(query, public)


def one(query: Any, public: str = "Ошибка базы данных.") -> dict | None:
    data = execute(query, public)
    return data[0] if data else None


def fetch_all(build: Callable[[], Any], page: int = 1000, max_rows: int = 50000,
              public: str = "Ошибка базы данных.") -> list:
    """Постраничная выборка (у Supabase лимит 1000 строк на запрос).

    build должна возвращать НОВЫЙ запрос с устойчивой сортировкой.
    """
    out: list = []
    start = 0
    while start < max_rows:
        chunk = execute(build().range(start, start + page - 1), public)
        out.extend(chunk)
        if len(chunk) < page:
            break
        start += page
    return out


def select_in(table: str, cols: str, column: str, values: list) -> list:
    values = list(dict.fromkeys(values))
    out: list = []
    for i in range(0, len(values), 100):
        out.extend(rows(db().table(table).select(cols).in_(column, values[i:i + 100])))
    return out


def audit(actor_id: int, action: str, target_user_id: int | None = None, details: str | None = None) -> None:
    """Запись в журнал. Сбой журнала никогда не должен отменять уже выполненное действие."""
    try:
        db().table("audit_log").insert({
            "actor_user_id": int(actor_id),
            "action": action,
            "target_user_id": int(target_user_id) if target_user_id is not None else None,
            "details": details,
        }).execute()
    except Exception as exc:  # noqa: BLE001
        log.error("audit_log write failed (%s): %s", action, exc)


def clean_name(value: Any) -> str:
    return " ".join(str(value or "").split())


# ----------------------------------------------------------------------------
# Авторизация Telegram
# ----------------------------------------------------------------------------


def verify_init_data(raw: str, bot_token: str | None = None, max_age: int = 86400, now: float | None = None) -> dict:
    token = bot_token if bot_token is not None else env("BOT_TOKEN")
    if not token:
        raise DKDError(503, "На сервере не настроен BOT_TOKEN.")
    if not raw:
        raise DKDError(401, "Откройте приложение через Telegram.")
    values = dict(parse_qsl(raw, keep_blank_values=True))
    received_hash = values.pop("hash", None)
    if not received_hash:
        raise DKDError(401, "Некорректные данные Telegram.")
    try:
        auth_date = int(values.get("auth_date", ""))
    except ValueError:
        raise DKDError(401, "Некорректная дата авторизации Telegram.") from None
    if abs((now if now is not None else time.time()) - auth_date) > max_age:
        raise DKDError(401, "Сессия Telegram устарела. Закройте и снова откройте Mini App.")
    data_check_string = "\n".join(f"{k}={v}" for k, v in sorted(values.items()))
    secret_key = hmac.new(b"WebAppData", token.encode(), hashlib.sha256).digest()
    expected = hmac.new(secret_key, data_check_string.encode(), hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, received_hash):
        raise DKDError(401, "Не удалось подтвердить авторизацию Telegram.")
    try:
        user = json.loads(values["user"])
    except (KeyError, json.JSONDecodeError):
        raise DKDError(401, "В данных Telegram отсутствует пользователь.") from None
    if not isinstance(user, dict) or not user.get("id"):
        raise DKDError(401, "Не найден Telegram ID.")
    return user


def tg_full_name(user: dict) -> str:
    return clean_name(" ".join(x for x in [user.get("first_name"), user.get("last_name")] if x)) or "Пользователь Telegram"


# ----------------------------------------------------------------------------
# Пользователи (subjects)
# ----------------------------------------------------------------------------

SUBJECT_COLS = "id,user_id,username,full_name,is_citizen,reputation,created_at,citizenship_granted_at,avatar_emoji"


def get_subject(user_id: int) -> dict | None:
    return one(db().table("subjects").select(SUBJECT_COLS).eq("user_id", int(user_id)).limit(1))


def ensure_subject(user_id: int, username: str | None = None, full_name: str | None = None) -> dict:
    """Находит или создаёт запись пользователя по ЧИСЛОВОМУ Telegram ID.

    Новый пользователь НЕ получает гражданство и права администратора.
    """
    uid = int(user_id)
    subject = get_subject(uid)
    if subject:
        if username and subject.get("username") != username:
            try:
                db().table("subjects").update({"username": username}).eq("id", subject["id"]).execute()
                subject["username"] = username
            except Exception as exc:  # noqa: BLE001
                log.warning("username refresh failed: %s", exc)
        return subject
    name = clean_name(full_name) or "Пользователь Telegram"
    try:
        data = db().table("subjects").insert({
            "user_id": uid,
            "username": username,
            "full_name": name,
            "is_citizen": False,
            "reputation": 0,
            "avatar_emoji": "👤",
        }).execute().data
    except Exception as exc:  # noqa: BLE001
        subject = get_subject(uid)  # параллельная первая регистрация
        if subject:
            return subject
        raise db_error(exc, "Не удалось зарегистрировать аккаунт.") from exc
    if data:
        return data[0]
    subject = get_subject(uid)
    if not subject:
        raise DKDError(502, "Supabase не подтвердил создание аккаунта.")
    return subject


# ----------------------------------------------------------------------------
# Администратор и президент
# ----------------------------------------------------------------------------


def env_admin_ids() -> set[str]:
    return {x.strip() for x in env("ADMIN_TELEGRAM_ID").split(",") if x.strip()}


def saved_admin_id() -> str | None:
    try:
        row = one(db().table("app_settings").select("key,value").eq("key", "admin_telegram_id").limit(1))
    except DKDError:
        return None
    return str(row["value"]) if row and row.get("value") else None


MAIN_ADMIN_TELEGRAM_ID = "7934244888"


def is_admin(user: dict) -> bool:
    # Доступ к административным функциям закреплён только за владельцем проекта.
    return str(user.get("id")) == MAIN_ADMIN_TELEGRAM_ID


def require_admin(user: dict) -> None:
    if not is_admin(user):
        raise DKDError(403, "Доступ только для главного администратора.")


def president_role_name() -> str:
    return (env("PRESIDENT_ROLE_NAME") or "Президент").casefold()


def user_roles(subject_id: int) -> list[dict]:
    links = fetch_all(lambda: db().table("subject_roles").select("role_id").eq("subject_id", subject_id)
                      .order("role_id"))
    ids = [x["role_id"] for x in links]
    if not ids:
        return []
    return select_in("roles", "id,name,emoji", "id", ids)


PRESIDENT_TERM_START_KEY = "president_term_start"
PRESIDENT_TERM_USER_KEY = "president_term_user_id"
PRESIDENT_TERM_END_KEY = "president_term_end"
ROLE_LIMITED_SINGLE = {"президент", "министр иностранных дел", "премьер-министр"}
ROLE_CANONICAL = {
    "президент": ("Президент", "👑"),
    "министр иностранных дел": ("Министр иностранных дел", "🌐"),
    "премьер-министр": ("Премьер-министр", "🏛️"),
    "депутат": ("Депутат", "🏛️"),
    "гражданин": ("Гражданин", "🪪"),
}
PRESIDENT_ASSIGNABLE_ROLES = {"министр иностранных дел", "премьер-министр", "гражданин"}


def _setting_value(key: str) -> str | None:
    try:
        row = one(db().table("app_settings").select("key,value").eq("key", key).limit(1))
        return str(row["value"]) if row and row.get("value") else None
    except DKDError:
        return None


def _save_setting(key: str, value: str) -> None:
    existing = one(db().table("app_settings").select("key").eq("key", key).limit(1))
    if existing:
        execute(db().table("app_settings").update({"value": value}).eq("key", key), "Не удалось сохранить срок президентских полномочий.")
    else:
        execute(db().table("app_settings").insert({"key": key, "value": value}), "Не удалось сохранить срок президентских полномочий.")


def _add_calendar_months(value: datetime, months: int) -> datetime:
    month_index = value.month - 1 + months
    year = value.year + month_index // 12
    month = month_index % 12 + 1
    day = min(value.day, calendar.monthrange(year, month)[1])
    return value.replace(year=year, month=month, day=day)


def _ensure_president_term(user_id: int) -> dict:
    stored_user = _setting_value(PRESIDENT_TERM_USER_KEY)
    start_raw = _setting_value(PRESIDENT_TERM_START_KEY)
    end_raw = _setting_value(PRESIDENT_TERM_END_KEY)
    start = parse_dt(start_raw) if start_raw else None
    end = parse_dt(end_raw) if end_raw else None
    if stored_user != str(user_id) or not start or not end:
        start = utcnow()
        end = _add_calendar_months(start, 3)
        _save_setting(PRESIDENT_TERM_USER_KEY, str(user_id))
        _save_setting(PRESIDENT_TERM_START_KEY, start.isoformat())
        _save_setting(PRESIDENT_TERM_END_KEY, end.isoformat())
    now = utcnow()
    return {"user_id": int(user_id), "start_at": start.isoformat(), "end_at": end.isoformat(),
            "expired": now >= end, "days_left": max(0, (end - now).days)}


def president_term_status(user: dict) -> dict:
    if is_admin(user):
        return {"managed": False, "is_admin": True, "expired": False,
                "message": "Главный администратор не ограничен президентским сроком."}
    subject = get_subject(int(user["id"]))
    has_role = bool(subject and any(clean_name(r.get("name")).casefold() == president_role_name()
                                    for r in user_roles(subject["id"])))
    if not has_role:
        return {"managed": False, "expired": False, "message": "Президентская должность не назначена."}
    term = _ensure_president_term(int(user["id"]))
    term["managed"] = True
    term["message"] = "Срок полномочий истёк." if term["expired"] else "Контроль срока активен."
    return term


def is_president(user: dict) -> bool:
    """Президент — роль Президента с контролируемым трёхмесячным сроком; администратор имеет служебный доступ."""
    if is_admin(user):
        return True
    try:
        term = president_term_status(user)
        return bool(term.get("managed") and not term.get("expired"))
    except DKDError:
        return False


def require_president(user: dict) -> None:
    if not is_president(user):
        raise DKDError(403, "Доступ только президенту или главному администратору.")


_claim_failures: dict[int, list[float]] = {}
CLAIM_MAX_FAILURES = 5
CLAIM_WINDOW = 900


def _claim_register_failure(uid: int, now: float) -> None:
    _claim_failures.setdefault(uid, []).append(now)


def _claim_locked(uid: int, now: float) -> bool:
    recent = [t for t in _claim_failures.get(uid, []) if now - t < CLAIM_WINDOW]
    _claim_failures[uid] = recent
    return len(recent) >= CLAIM_MAX_FAILURES


def admin_status(user: dict) -> dict:
    admin = is_admin(user)
    available = (
        str(user.get("id")) == MAIN_ADMIN_TELEGRAM_ID
        and bool(env("ADMIN_BOOTSTRAP_CODE"))
        and saved_admin_id() is None
    )
    return {"is_admin": admin, "bootstrap_available": available and not admin}


def claim_admin(user: dict, code: str, now: float | None = None) -> dict:
    now = now if now is not None else time.time()
    uid = int(user["id"])
    if str(uid) != MAIN_ADMIN_TELEGRAM_ID:
        raise DKDError(403, "Активировать панель может только владелец проекта.")
    expected = env("ADMIN_BOOTSTRAP_CODE")
    if not expected:
        raise DKDError(503, "Первичная настройка администратора отключена: задайте ADMIN_BOOTSTRAP_CODE в Render.")
    if env_admin_ids():
        raise DKDError(409, "Главный администратор задан переменной ADMIN_TELEGRAM_ID. Повторное назначение закрыто.")
    if saved_admin_id() is not None:
        raise DKDError(409, "Главный администратор уже назначен. Повторное назначение закрыто.")
    if _claim_locked(uid, now):
        raise DKDError(429, "Слишком много неверных попыток. Попробуйте позже.")
    if not secrets.compare_digest(str(code).encode(), expected.encode()):
        _claim_register_failure(uid, now)
        raise DKDError(403, "Неверный код активации.")
    try:
        db().table("app_settings").insert({"key": "admin_telegram_id", "value": str(uid)}).execute()
    except Exception as exc:  # noqa: BLE001
        if saved_admin_id() is not None:  # проигранная гонка двух активаций
            raise DKDError(409, "Главный администратор уже назначен. Повторное назначение закрыто.") from exc
        raise db_error(exc, "Не удалось активировать администратора.") from exc
    subject = ensure_subject(uid, user.get("username"), tg_full_name(user))
    grant_citizenship(subject)
    role = one(db().table("roles").select("id").eq("name", "Главный администратор").limit(1))
    if not role:
        role = execute(db().table("roles").insert({"name": "Главный администратор", "emoji": "🛡️"}))[0]
    link = one(db().table("subject_roles").select("subject_id").eq("subject_id", subject["id"]).eq("role_id", role["id"]).limit(1))
    if not link:
        execute(db().table("subject_roles").insert({"subject_id": subject["id"], "role_id": role["id"]}))
    audit(uid, "admin_claimed", uid, None)
    return {"ok": True, "message": "Вы назначены главным администратором. Удалите ADMIN_BOOTSTRAP_CODE из Render после успешной активации."}


# ----------------------------------------------------------------------------
# Паспорта и гражданство
# ----------------------------------------------------------------------------

COUNTRY = "Демократическая Конституционная Держава"
PASSPORT_AUTHORITY = "Служба гражданства ДКД"
PASSPORT_DIVISION = "001-001"
PASSPORT_COLS = "id,user_id,subject_id,number,full_name,issued_at,issued_by,division_code,status,revoked_at"


def passport_number(subject_id: int) -> str:
    """Стабильный уникальный номер: id записи пользователя уникален, значит уникален и номер."""
    return f"ДКД-{int(subject_id):06d}"


def get_passport(user_id: int) -> dict | None:
    return one(db().table("passports").select(PASSPORT_COLS).eq("user_id", int(user_id)).limit(1))


def _date_part(value: Any) -> str:
    text = str(value or "")
    return text[:10] if len(text) >= 10 else ""


def issue_passport(subject: dict) -> dict:
    """Идемпотентно выдаёт паспорт. Повторный вызов ничего не дублирует."""
    uid = int(subject["user_id"])
    today = utcnow().date().isoformat()
    existing = get_passport(uid)
    if existing:
        if existing.get("status") == "active":
            return existing
        upd = {"status": "active", "revoked_at": None, "issued_at": today,
               "full_name": subject["full_name"], "updated_at": now_iso()}
        execute(db().table("passports").update(upd).eq("id", existing["id"]), "Не удалось восстановить паспорт.")
        existing.update(upd)
        return existing
    row = {
        "user_id": uid,
        "subject_id": int(subject["id"]),
        "number": passport_number(subject["id"]),
        "full_name": subject["full_name"],
        "issued_at": _date_part(subject.get("citizenship_granted_at") or subject.get("created_at")) or today,
        "issued_by": PASSPORT_AUTHORITY,
        "division_code": PASSPORT_DIVISION,
        "status": "active",
    }
    try:
        data = db().table("passports").insert(row).execute().data
    except Exception as exc:  # noqa: BLE001
        existing = get_passport(uid)
        if existing:
            return existing
        raise db_error(exc, "Не удалось выдать паспорт.") from exc
    return data[0] if data else (get_passport(uid) or row)


def revoke_passport(user_id: int) -> None:
    execute(db().table("passports").update({"status": "revoked", "revoked_at": now_iso(), "updated_at": now_iso()})
            .eq("user_id", int(user_id)).eq("status", "active"), "Не удалось аннулировать паспорт.")


def grant_citizenship(subject: dict, full_name: str | None = None) -> tuple[dict, dict]:
    """Единственная точка выдачи гражданства: обновляет запись и ВСЕГДА выдаёт паспорт."""
    upd: dict = {}
    name = clean_name(full_name)
    if name and name != subject.get("full_name"):
        upd["full_name"] = name
    if not subject.get("is_citizen"):
        upd["is_citizen"] = True
        upd["citizenship_granted_at"] = now_iso()
    if upd:
        execute(db().table("subjects").update(upd).eq("id", subject["id"]), "Не удалось обновить гражданство.")
        subject.update(upd)
    return subject, issue_passport(subject)


def revoke_citizenship(subject: dict) -> dict:
    execute(db().table("subjects").update({"is_citizen": False, "citizenship_granted_at": None}).eq("id", subject["id"]),
            "Не удалось изменить гражданство.")
    subject.update({"is_citizen": False, "citizenship_granted_at": None})
    revoke_passport(subject["user_id"])
    return subject


def passport_view(p: dict) -> dict:
    active = p.get("status") == "active"
    return {
        "number": p["number"],
        "full_name": p.get("full_name") or "Гражданин ДКД",
        "issued_at": _date_part(p.get("issued_at")),
        "issued_by": p.get("issued_by") or PASSPORT_AUTHORITY,
        "division_code": p.get("division_code") or PASSPORT_DIVISION,
        "status": "active" if active else "revoked",
        "status_label": "Действителен" if active else "Аннулирован",
        "country": COUNTRY,
        "citizenship": "Гражданин ДКД",
        "citizen_id": str(p.get("user_id", "")),
        "document_type": "Паспорт гражданина ДКД",
    }


def my_passport(user: dict) -> dict:
    uid = int(user["id"])
    subject = get_subject(uid)
    passport = get_passport(uid)
    if subject and subject.get("is_citizen") and not passport:
        passport = issue_passport(subject)  # гражданин без паспорта (старые данные) — выдаём автоматически
    if passport:
        return {"has_passport": True, "passport": passport_view(passport)}
    return {"has_passport": False, "message": "Паспорт появляется автоматически после одобрения заявления на гражданство."}


# ----------------------------------------------------------------------------
# Профиль
# ----------------------------------------------------------------------------


def active_membership(user_id: int) -> tuple[dict | None, dict | None]:
    members = rows(db().table("party_members").select("id,party_id,username,user_id").eq("user_id", int(user_id)))
    for m in members:
        party = one(db().table("parties").select("id,name,emoji,description,leader_username,leader_user_id,status")
                    .eq("id", m["party_id"]).limit(1))
        if party and party.get("status") == "active":
            return m, party
    return None, None


def purge_stale_memberships(user_id: int) -> None:
    """Удаляет членство в РОСПУЩЕННЫХ партиях: оно ничего не значит, но блокирует (UNIQUE по user_id) вступление в новую."""
    for m in rows(db().table("party_members").select("id,party_id").eq("user_id", int(user_id))):
        party = one(db().table("parties").select("id,status").eq("id", m["party_id"]).limit(1))
        if not party or party.get("status") != "active":
            execute(db().table("party_members").delete().eq("id", m["id"]))


def build_profile(user: dict) -> dict:
    uid = int(user["id"])
    subject = ensure_subject(uid, user.get("username"), tg_full_name(user))
    roles: list[dict] = []
    party_name = None
    party_id = None
    try:
        roles = user_roles(subject["id"])
    except DKDError:
        pass
    try:
        _, party = active_membership(uid)
        if party:
            party_name, party_id = party.get("name"), party["id"]
    except DKDError:
        pass
    labels = [f'{r.get("emoji") or ""} {r.get("name") or ""}'.strip() for r in roles]
    return {
        **subject,
        "role": ", ".join(labels) if labels else ("Гражданин" if subject.get("is_citizen") else "Участник ДКДУ"),
        "roles": [{"id": r["id"], "name": r.get("name"), "emoji": r.get("emoji")} for r in roles],
        "party_name": party_name,
        "party_id": party_id,
        "citizenship": "Гражданин ДКД" if subject.get("is_citizen") else "Не гражданин",
        "registered": True,
    }


AVATAR_EMOJI_ALLOWLIST = set("""👤 👑 🤴 👸 🫅 🎖️ 🏛️ ⚖️ 🗳️ 🏅 🛡️ 👨‍💼 👩‍💼 🧑‍⚖️ 👨‍⚖️ 👩‍⚖️ 🧑‍💻 👨‍💻 👩‍💻 🧑‍🔬 👨‍🔬 👩‍🔬 😎 🤓 🧐 🤠 🥸 😈 👿 👹 👺 🤡 💀 👻 🤖 👽 🐺 🦊 🦁 🐯 🐻 🐼 🐸 🦉 🦅 🐲 🐉 🦈 🐍 🐈 🐕 🐈‍⬛ 🦄 🧙 🧙‍♂️ 🧙‍♀️ 🧛 🧛‍♂️ 🧛‍♀️ 🧟 🧟‍♂️ 🧟‍♀️ 🦸 🦹 🥷 ⚔️ 🏹 💎 🔥 ⚡ 🌙 ☀️ 🌟 💫 🌀 🌈 🦋 🪬 🎭 🎩 🚀 🛸 🛰️ 🧑‍🚀 🌌 💻 🔬 👾""".split())


def update_profile_avatar(user: dict, avatar_emoji: str) -> dict:
    avatar = str(avatar_emoji or "").strip()
    if avatar not in AVATAR_EMOJI_ALLOWLIST:
        raise DKDError(422, "Выберите аватарку из доступного каталога.")
    subject = ensure_subject(int(user["id"]), user.get("username"), tg_full_name(user))
    execute(db().table("subjects").update({"avatar_emoji": avatar}).eq("id", subject["id"]),
            "Не удалось сохранить аватарку.")
    return build_profile(user)


def update_profile_name(user: dict, full_name: str) -> dict:
    name = clean_name(full_name)
    if not 2 <= len(name) <= 120:
        raise DKDError(422, "ФИО должно содержать от 2 до 120 символов.")
    subject = ensure_subject(int(user["id"]), user.get("username"), name)
    if subject.get("full_name") != name:
        execute(db().table("subjects").update({"full_name": name}).eq("id", subject["id"]),
                "Не удалось обновить профиль.")
    return build_profile(user)


# ----------------------------------------------------------------------------
# Заявления
# ----------------------------------------------------------------------------

APPLICATION_KINDS = ("citizenship", "other")
APP_COLS = "id,user_id,username,kind,full_name,details,status,created_at,reviewed_at,review_note"
MAX_PENDING_OTHER = 5


def create_application(user: dict, kind: str, full_name: str, details: str = "") -> dict:
    if kind not in APPLICATION_KINDS:
        raise DKDError(422, "Неизвестный тип заявления.")
    name = clean_name(full_name)
    if not 2 <= len(name) <= 120:
        raise DKDError(422, "Укажите ФИО (от 2 до 120 символов).")
    details = str(details or "").strip()
    if len(details) > 5000:
        raise DKDError(422, "Слишком длинное описание (максимум 5000 символов).")
    uid = int(user["id"])
    subject = ensure_subject(uid, user.get("username"), tg_full_name(user))
    if kind == "citizenship" and subject.get("is_citizen"):
        raise DKDError(409, "Вы уже являетесь гражданином ДКД.")
    pending = rows(db().table("document_applications").select("id").eq("user_id", uid).eq("kind", kind).eq("status", "pending"))
    if kind == "citizenship" and pending:
        raise DKDError(409, "Ваше заявление на гражданство уже на рассмотрении.")
    if len(pending) >= MAX_PENDING_OTHER:
        raise DKDError(429, "Слишком много нерассмотренных заявлений.")
    created = execute(db().table("document_applications").insert({
        "user_id": uid, "username": user.get("username"), "kind": kind,
        "full_name": name, "details": details, "status": "pending",
    }), "Не удалось сохранить заявление.")
    return created[0]


def list_my_applications(user_id: int) -> list:
    return rows(db().table("document_applications").select(APP_COLS).eq("user_id", int(user_id)).order("id", desc=True).limit(50))


def admin_list_applications() -> list:
    return rows(db().table("document_applications").select(APP_COLS).order("id", desc=True).limit(200))


def review_application(actor_id: int, application_id: int, status: str, note: str = "") -> dict:
    if status not in ("approved", "rejected"):
        raise DKDError(422, "Недопустимый статус.")
    app_row = one(db().table("document_applications").select("id,user_id,username,kind,full_name,status").eq("id", application_id).limit(1))
    if not app_row:
        raise DKDError(404, "Заявление не найдено.")
    if app_row.get("status") != "pending":
        raise DKDError(409, "Заявление уже рассмотрено.")
    note = str(note or "").strip()[:2000]
    passport = None
    if status == "approved" and app_row["kind"] == "citizenship":
        # Сначала выдаём гражданство и паспорт (идемпотентно), и только потом закрываем заявление:
        # при сбое заявление остаётся «на рассмотрении», и его можно одобрить повторно.
        subject = ensure_subject(app_row["user_id"], None, app_row["full_name"])
        _, passport = grant_citizenship(subject, full_name=app_row["full_name"])
    updated = execute(db().table("document_applications").update({
        "status": status, "review_note": note, "reviewed_by": int(actor_id), "reviewed_at": now_iso(),
    }).eq("id", application_id).eq("status", "pending"), "Не удалось обработать заявление.")
    if not updated:
        raise DKDError(409, "Заявление уже рассмотрено.")
    audit(actor_id, "application_reviewed", app_row["user_id"], f"application:{application_id}:{status}")
    return {"ok": True, "status": status, "passport_number": passport["number"] if passport else None}


# ----------------------------------------------------------------------------
# Выборы
# ----------------------------------------------------------------------------

ELECTION_TABLES = {"presidential": "pres_elections", "duma": "duma_elections"}
OPEN_STATUSES = ("active", "open")
CLOSED_STATUSES = ("closed", "finished", "completed")
ELECTION_COLS = "id,title,options,ends_at,status,results,created_at"

DUMA_START_KEY_PREFIX = "duma_election_start:"
DUMA_LISTS_KEY_PREFIX = "duma_election_lists:"


def _setting_get(key: str) -> str | None:
    row = one(db().table("app_settings").select("key,value").eq("key", key).limit(1))
    return str(row.get("value")) if row and row.get("value") is not None else None


def _setting_put(key: str, value: str) -> None:
    row = one(db().table("app_settings").select("key").eq("key", key).limit(1))
    if row:
        execute(db().table("app_settings").update({"value": value}).eq("key", key), "Не удалось сохранить настройки выборов.")
    else:
        execute(db().table("app_settings").insert({"key": key, "value": value}), "Не удалось сохранить настройки выборов.")


def duma_start_at(election_id: int) -> datetime | None:
    raw = _setting_get(DUMA_START_KEY_PREFIX + str(int(election_id)))
    return parse_dt(raw) if raw else None


def duma_party_lists(election_id: int) -> dict:
    raw = _setting_get(DUMA_LISTS_KEY_PREFIX + str(int(election_id)))
    if not raw:
        return {}
    try:
        data = json.loads(raw)
        return data if isinstance(data, dict) else {}
    except json.JSONDecodeError:
        return {}


def president_schedule_duma_election(actor_id: int, title: str, starts_at: str, ends_at: str) -> dict:
    start, end = parse_dt(starts_at), parse_dt(ends_at)
    if not start or not end:
        raise DKDError(400, "Укажите корректные дату и время начала и окончания выборов.")
    if start <= utcnow() or end <= start:
        raise DKDError(400, "Начало должно быть в будущем, а окончание — позже начала.")
    title = clean_name(title) or "Выборы в Государственную думу"
    if len(title) > 200:
        raise DKDError(422, "Название выборов не должно превышать 200 символов.")
    election = execute(db().table("duma_elections").insert({
        "title": title, "options": [], "ends_at": end.isoformat(), "status": "scheduled", "results": {},
    }), "Не удалось назначить выборы.")[0]
    _setting_put(DUMA_START_KEY_PREFIX + str(election["id"]), start.isoformat())
    _setting_put(DUMA_LISTS_KEY_PREFIX + str(election["id"]), "{}")
    audit(actor_id, "duma_election_scheduled", None, f"duma:{election['id']}:{start.isoformat()}:{end.isoformat()}")
    return {**election, "starts_at": start.isoformat(), "status": "scheduled"}


def submit_duma_party_list(user: dict, election_id: int, candidates: list[str]) -> dict:
    uid = int(user["id"])
    parties = rows(db().table("parties").select(PARTY_COLS).eq("status", "active"))
    party = next((p for p in parties if int(p.get("leader_user_id") or 0) == uid), None)
    if not party:
        raise DKDError(403, "Подать список кандидатов может только председатель партии.")
    election = one(db().table("duma_elections").select(ELECTION_COLS).eq("id", int(election_id)).limit(1))
    if not election:
        raise DKDError(404, "Выборы в Госдуму не найдены.")
    start, end = duma_start_at(election_id), parse_dt(election.get("ends_at"))
    if not start or utcnow() >= start or (end and utcnow() >= end) or election.get("status") in CLOSED_STATUSES:
        raise DKDError(409, "Список кандидатов можно подать только до начала выборов.")
    cleaned = [clean_name(x) for x in candidates if clean_name(x)]
    if not cleaned or len(cleaned) > 50 or any(len(x) > 120 for x in cleaned):
        raise DKDError(400, "Укажите от 1 до 50 кандидатов; имя каждого — до 120 символов.")
    if len({x.casefold() for x in cleaned}) != len(cleaned):
        raise DKDError(400, "В списке не должно быть повторяющихся кандидатов.")
    lists = duma_party_lists(election_id)
    lists[str(party["id"])] = {"party_id": party["id"], "party_name": party["name"], "party_emoji": party.get("emoji") or "🎭",
                               "leader_user_id": uid, "candidates": cleaned, "submitted_at": utcnow().isoformat()}
    _setting_put(DUMA_LISTS_KEY_PREFIX + str(int(election_id)), json.dumps(lists, ensure_ascii=False))
    options = [item["party_name"] for item in lists.values()]
    execute(db().table("duma_elections").update({"options": options}).eq("id", int(election_id)), "Не удалось обновить списки партий на выборах.")
    audit(uid, "duma_party_list_submitted", None, f"election:{election_id}:party:{party['id']}")
    return {"ok": True, "party_name": party["name"], "candidates": cleaned}


def list_duma_elections_for_party(user: dict) -> dict:
    uid = int(user["id"])
    parties = rows(db().table("parties").select(PARTY_COLS).eq("status", "active"))
    leader_parties = [p for p in parties if int(p.get("leader_user_id") or 0) == uid]
    elections = rows(db().table("duma_elections").select(ELECTION_COLS).order("id", desc=True).limit(20))
    out = []
    for e in elections:
        start = duma_start_at(e["id"])
        end = parse_dt(e.get("ends_at"))
        status = effective_status(e)
        if status == "active" and start and utcnow() < start:
            status = "scheduled"
        elif status in ("scheduled", "pending") and start and start <= utcnow() and (not end or utcnow() < end):
            status = "active"
        elif start and end and utcnow() >= end:
            status = "closed"
        lists = duma_party_lists(e["id"])
        out.append({"id": e["id"], "title": e.get("title"), "starts_at": start.isoformat() if start else None,
                    "ends_at": e.get("ends_at"), "status": status,
                    "my_party": next(({"id": p["id"], "name": p["name"]} for p in leader_parties), None),
                    "my_list_submitted": any(str(p["id"]) in lists for p in leader_parties),
                    "party_lists": [{"party_name": x["party_name"], "candidates": x["candidates"]} for x in lists.values()]})
    return {"elections": out}


def admin_close_duma_election(actor_id: int, election_id: int) -> dict:
    election = one(db().table("duma_elections").select(ELECTION_COLS).eq("id", int(election_id)).limit(1))
    if not election:
        raise DKDError(404, "Выборы в Госдуму не найдены.")
    counts = tally("duma", election_id)
    execute(db().table("duma_elections").update({"status": "closed", "results": counts}).eq("id", int(election_id)),
            "Не удалось завершить выборы.")
    audit(actor_id, "duma_election_closed", None, f"duma:{election_id}")
    return {"ok": True, "results": counts}



def parse_dt(value: Any) -> datetime | None:
    if not value:
        return None
    text = str(value).strip().replace("Z", "+00:00").replace(" ", "T", 1)
    text = re.sub(r"\.(\d+)", lambda m: "." + (m.group(1) + "000000")[:6], text)
    try:
        dt = datetime.fromisoformat(text)
    except ValueError:
        return None
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


def normalize_options(options: Any) -> list[str]:
    if isinstance(options, str):
        try:
            options = json.loads(options)
        except json.JSONDecodeError:
            options = []
    return [str(x) for x in options] if isinstance(options, list) else []


def effective_status(election: dict, now: datetime | None = None) -> str:
    current = now or utcnow()
    status = election.get("status")
    if status in CLOSED_STATUSES:
        return "closed"
    if status in ("scheduled", "pending"):
        start = duma_start_at(int(election["id"])) if "id" in election else None
        end = parse_dt(election.get("ends_at"))
        if end and end <= current:
            return "closed"
        if start and current < start:
            return "scheduled"
        return "active"
    if status in OPEN_STATUSES:
        end = parse_dt(election.get("ends_at"))
        if end and end <= current:
            return "closed"
        if election.get("id") and "starts_at" not in election:
            start = duma_start_at(int(election["id"])) if election.get("id") else None
            if start and current < start:
                return "scheduled"
        return "active"
    return str(status or "")


def tally(kind: str, election_id: int) -> dict[str, int]:
    ballots = fetch_all(lambda: db().table("votes").select("option").eq("election_type", kind)
                        .eq("election_id", int(election_id)).order("id"))
    return dict(Counter(b["option"] for b in ballots))


def list_elections(user_id: int) -> dict:
    mine = fetch_all(lambda: db().table("votes").select("election_type,election_id,option").eq("user_id", int(user_id)).order("id"))
    my = {(v["election_type"], int(v["election_id"])): v["option"] for v in mine}
    result: dict[str, list] = {}
    for kind, table in ELECTION_TABLES.items():
        items = rows(db().table(table).select(ELECTION_COLS).order("id", desc=True).limit(10))
        out = []
        for e in items:
            status = effective_status(e)
            view = {
                "id": e["id"], "kind": kind, "title": e.get("title"), "options": normalize_options(e.get("options")),
                "ends_at": e.get("ends_at"), "created_at": e.get("created_at"),
                "status": status, "is_open": status == "active",
                "has_voted": (kind, int(e["id"])) in my, "my_option": my.get((kind, int(e["id"]))),
                "vote_counts": None, "votes_total": None,
            }
            if status == "closed":
                counts = tally(kind, e["id"])
                view["vote_counts"] = counts
                view["votes_total"] = sum(counts.values())
            out.append(view)
        result[kind] = out
    return result


def cast_vote(user: dict, kind: str, election_id: int, option: str) -> dict:
    if kind not in ELECTION_TABLES:
        raise DKDError(404, "Неизвестный тип выборов.")
    uid = int(user["id"])
    subject = get_subject(uid)
    if not subject or not subject.get("is_citizen"):
        raise DKDError(403, "Голосовать могут только граждане ДКД.")
    election = one(db().table(ELECTION_TABLES[kind]).select(ELECTION_COLS).eq("id", int(election_id)).limit(1))
    if not election:
        raise DKDError(404, "Выборы не найдены.")
    status = effective_status(election)
    if status != "active":
        raise DKDError(409, "Голосование ещё не началось." if status == "scheduled" else "Срок голосования истёк." if status == "closed" else "Эти выборы сейчас не открыты.")
    if option not in normalize_options(election.get("options")):
        raise DKDError(400, "Выбранный вариант отсутствует в списке.")
    already = one(db().table("votes").select("id").eq("user_id", uid).eq("election_type", kind)
                  .eq("election_id", int(election_id)).limit(1))
    if already:
        raise DKDError(409, "Вы уже голосовали на этих выборах.")
    try:
        db().table("votes").insert({"user_id": uid, "election_type": kind, "election_id": int(election_id), "option": option}).execute()
    except Exception as exc:  # noqa: BLE001
        # UNIQUE-ограничение в БД — окончательная защита от двойного голоса (гонка двух запросов).
        again = one(db().table("votes").select("id").eq("user_id", uid).eq("election_type", kind)
                    .eq("election_id", int(election_id)).limit(1))
        if again:
            raise DKDError(409, "Вы уже голосовали на этих выборах.") from exc
        raise db_error(exc, "Не удалось сохранить голос.") from exc
    return {"ok": True, "message": "Ваш голос сохранён."}


def create_election(actor_id: int, kind: str, title: str, options: list[str], ends_at: str | None) -> dict:
    if kind not in ELECTION_TABLES:
        raise DKDError(404, "Неизвестный тип выборов.")
    title = clean_name(title)
    if not 3 <= len(title) <= 160:
        raise DKDError(422, "Название выборов: от 3 до 160 символов.")
    cleaned = [clean_name(x) for x in options if clean_name(x)]
    if len(cleaned) < 2 or len({x.casefold() for x in cleaned}) != len(cleaned):
        raise DKDError(400, "Нужно минимум два разных варианта.")
    if len(cleaned) > 50 or any(len(x) > 200 for x in cleaned):
        raise DKDError(400, "Слишком много вариантов или слишком длинное название варианта.")
    end_iso = None
    if ends_at:
        end = parse_dt(ends_at)
        if not end:
            raise DKDError(400, "Некорректная дата окончания.")
        if end <= utcnow():
            raise DKDError(400, "Дата окончания должна быть в будущем.")
        end_iso = end.isoformat()
    created = execute(db().table(ELECTION_TABLES[kind]).insert({
        "title": title, "options": cleaned, "ends_at": end_iso, "status": "active", "results": {},
    }), "Не удалось создать выборы.")
    audit(actor_id, "election_created", None, f"{kind}:{created[0]['id']}")
    return created[0]


def close_election(actor_id: int, kind: str, election_id: int) -> dict:
    if kind not in ELECTION_TABLES:
        raise DKDError(404, "Неизвестный тип выборов.")
    table = ELECTION_TABLES[kind]
    election = one(db().table(table).select(ELECTION_COLS).eq("id", int(election_id)).limit(1))
    if not election:
        raise DKDError(404, "Выборы не найдены.")
    if election.get("status") in CLOSED_STATUSES:
        raise DKDError(409, "Выборы уже завершены.")
    counts = tally(kind, election_id)
    execute(db().table(table).update({"status": "closed", "results": counts}).eq("id", int(election_id)),
            "Не удалось завершить выборы.")
    audit(actor_id, "election_closed", None, f"{kind}:{election_id}")
    return {"ok": True, "results": counts}


# ----------------------------------------------------------------------------
# Партии
# ----------------------------------------------------------------------------

PARTY_COLS = "id,name,emoji,description,leader_username,leader_user_id,status"


def _member_username(user: dict) -> str:
    return user.get("username") or str(user["id"])  # party_members.username NOT NULL


def list_parties(user: dict, admin: bool) -> dict:
    uid = int(user["id"])
    subject = get_subject(uid)
    parties = rows(db().table("parties").select(PARTY_COLS).eq("status", "active").order("name"))
    members = fetch_all(lambda: db().table("party_members").select("id,party_id,username,user_id").order("id"))
    names = {s["user_id"]: s["full_name"] for s in select_in(
        "subjects", "user_id,full_name", "user_id", [m["user_id"] for m in members if m.get("user_id") is not None])}
    by_party: dict[int, list] = {}
    for m in members:
        by_party.setdefault(m["party_id"], []).append(m)
    my_party_id = None
    out = []
    for p in parties:
        ms = by_party.get(p["id"], [])
        leader_uname = (p.get("leader_username") or "").casefold()
        is_mine = any(m.get("user_id") == uid for m in ms)
        if is_mine:
            my_party_id = p["id"]
        out.append({
            "id": p["id"], "name": p["name"], "emoji": p.get("emoji") or "🎭", "description": p.get("description"),
            "leader_username": p.get("leader_username"), "status": p["status"],
            "members_count": len(ms),
            "members": [{
                "member_id": m["id"],
                "name": names.get(m.get("user_id")) or (f'@{m["username"]}' if m.get("username") else "Участник"),
                "username": m.get("username"),
                "is_leader": bool((m.get("user_id") is not None and m.get("user_id") == p.get("leader_user_id"))
                                  or (leader_uname and (m.get("username") or "").casefold() == leader_uname)),
            } for m in ms[:100]],
            "is_member": is_mine,
            "can_manage": admin or p.get("leader_user_id") == uid,
        })
    return {"parties": out, "me": {"is_citizen": bool(subject and subject.get("is_citizen")),
                                  "party_id": my_party_id, "is_admin": admin}}


def _require_citizen(user: dict) -> dict:
    subject = get_subject(int(user["id"]))
    if not subject or not subject.get("is_citizen"):
        raise DKDError(403, "Действие доступно только гражданам ДКД.")
    return subject


def create_party(user: dict, name: str, emoji: str = "🎭", description: str = "") -> dict:
    _require_citizen(user)
    uid = int(user["id"])
    name = clean_name(name)
    if not 2 <= len(name) <= 60:
        raise DKDError(422, "Название партии: от 2 до 60 символов.")
    description = str(description or "").strip()
    if len(description) > 1000:
        raise DKDError(422, "Описание партии: не более 1000 символов.")
    emoji = (emoji or "🎭").strip()[:8] or "🎭"
    if active_membership(uid)[0]:
        raise DKDError(409, "Вы уже состоите в партии. Сначала выйдите из неё.")
    purge_stale_memberships(uid)
    existing = rows(db().table("parties").select("id,name").eq("status", "active"))
    if any(clean_name(p["name"]).casefold() == name.casefold() for p in existing):
        raise DKDError(409, "Партия с таким названием уже существует.")
    party = execute(db().table("parties").insert({
        "name": name, "emoji": emoji, "description": description,
        "leader_username": user.get("username"), "leader_user_id": uid, "status": "active",
    }), "Не удалось создать партию.")[0]
    try:
        execute(db().table("party_members").insert({"party_id": party["id"], "username": _member_username(user), "user_id": uid}))
    except DKDError:
        execute(db().table("parties").delete().eq("id", party["id"]))  # откат: партия без председателя не нужна
        raise
    return party


def join_party(user: dict, party_id: int) -> dict:
    _require_citizen(user)
    uid = int(user["id"])
    party = one(db().table("parties").select(PARTY_COLS).eq("id", int(party_id)).limit(1))
    if not party or party.get("status") != "active":
        raise DKDError(404, "Партия не найдена.")
    if active_membership(uid)[0]:
        raise DKDError(409, "Вы уже состоите в партии. Сначала выйдите из неё.")
    purge_stale_memberships(uid)
    execute(db().table("party_members").insert({"party_id": party["id"], "username": _member_username(user), "user_id": uid}),
            "Не удалось вступить в партию.")
    return {"ok": True}


def leave_party(user: dict) -> dict:
    uid = int(user["id"])
    member, party = active_membership(uid)
    if not member:
        raise DKDError(404, "Вы не состоите в партии.")
    if party.get("leader_user_id") == uid:
        others = rows(db().table("party_members").select("id,user_id,username").eq("party_id", party["id"]).order("id"))
        others = [m for m in others if m["id"] != member["id"]]
        if others:
            heir = others[0]  # председательство переходит самому давнему участнику
            execute(db().table("parties").update({"leader_user_id": heir.get("user_id"), "leader_username": heir.get("username")})
                    .eq("id", party["id"]))
        else:
            execute(db().table("parties").update({"status": "disbanded"}).eq("id", party["id"]))
    execute(db().table("party_members").delete().eq("id", member["id"]), "Не удалось выйти из партии.")
    return {"ok": True}


def kick_member(actor: dict, admin: bool, party_id: int, member_id: int) -> dict:
    party = one(db().table("parties").select(PARTY_COLS).eq("id", int(party_id)).limit(1))
    if not party or party.get("status") != "active":
        raise DKDError(404, "Партия не найдена.")
    if not (admin or party.get("leader_user_id") == int(actor["id"])):
        raise DKDError(403, "Исключать участников может только председатель партии.")
    target = one(db().table("party_members").select("id,party_id,user_id,username").eq("id", int(member_id)).limit(1))
    if not target or target["party_id"] != party["id"]:
        raise DKDError(404, "Участник не найден.")
    if target.get("user_id") is not None and target["user_id"] == party.get("leader_user_id"):
        raise DKDError(409, "Председателя нельзя исключить; он может передать полномочия, выйдя из партии.")
    execute(db().table("party_members").delete().eq("id", target["id"]), "Не удалось исключить участника.")
    audit(int(actor["id"]), "party_member_kicked", target.get("user_id"), f"party:{party['id']}")
    return {"ok": True}


def disband_party(actor: dict, admin: bool, party_id: int) -> dict:
    party = one(db().table("parties").select(PARTY_COLS).eq("id", int(party_id)).limit(1))
    if not party or party.get("status") != "active":
        raise DKDError(404, "Партия не найдена.")
    if not (admin or party.get("leader_user_id") == int(actor["id"])):
        raise DKDError(403, "Распустить партию может председатель или администратор.")
    execute(db().table("parties").update({"status": "disbanded"}).eq("id", party["id"]), "Не удалось распустить партию.")
    execute(db().table("party_members").delete().eq("party_id", party["id"]), "Не удалось очистить состав партии.")
    audit(int(actor["id"]), "party_disbanded", None, f"party:{party['id']}")
    return {"ok": True}


# ----------------------------------------------------------------------------
# Документы, новости, публикации
# ----------------------------------------------------------------------------


def list_documents(user: dict, admin: bool) -> dict:
    uid = int(user["id"])
    laws = rows(db().table("laws").select("id,title,description,status,created_at").order("id", desc=True).limit(50))
    decrees = rows(db().table("decrees").select("id,title,text,is_secret,secret_for_user_id,created_at").order("id", desc=True).limit(150))
    visible = [d for d in decrees if not d.get("is_secret") or admin or d.get("secret_for_user_id") == uid][:50]
    for d in visible:
        d.pop("secret_for_user_id", None)
    return {"laws": laws, "decrees": visible}


def list_news() -> list:
    return rows(db().table("news").select("id,text,created_at").order("id", desc=True).limit(30))


def publish_news(actor_id: int, text: str) -> dict:
    text = str(text or "").strip()
    if not text or len(text) > 10000:
        raise DKDError(422, "Текст новости: от 1 до 10000 символов.")
    row = execute(db().table("news").insert({"text": text}), "Не удалось опубликовать новость.")[0]
    audit(actor_id, "news_created", None, f"news:{row['id']}")
    return row


def publish_law(actor_id: int, title: str, description: str) -> dict:
    title = clean_name(title)
    if not 2 <= len(title) <= 200:
        raise DKDError(422, "Название документа: от 2 до 200 символов.")
    description = str(description or "").strip()
    if len(description) > 20000:
        raise DKDError(422, "Текст документа слишком длинный.")
    row = execute(db().table("laws").insert({"title": title, "description": description, "status": "active"}),
                  "Не удалось опубликовать документ.")[0]
    audit(actor_id, "law_created", None, f"law:{row['id']}")
    return row


def publish_decree(actor_id: int, title: str, text: str, secret_for_user_id: int | None = None) -> dict:
    title = clean_name(title)
    if not 2 <= len(title) <= 200:
        raise DKDError(422, "Название указа: от 2 до 200 символов.")
    text = str(text or "").strip()
    if not text or len(text) > 20000:
        raise DKDError(422, "Текст указа: от 1 до 20000 символов.")
    row = {"title": title, "text": text, "is_secret": secret_for_user_id is not None}
    if secret_for_user_id is not None:
        row["secret_for_user_id"] = int(secret_for_user_id)
        row["secret_for"] = str(int(secret_for_user_id))
    created = execute(db().table("decrees").insert(row), "Не удалось опубликовать указ.")[0]
    audit(actor_id, "presidential_decree_created", secret_for_user_id, f"decree:{created['id']}")
    return created


# ----------------------------------------------------------------------------
# Администрирование пользователей и ролей
# ----------------------------------------------------------------------------


def admin_list_subjects() -> dict:
    subjects = fetch_all(lambda: db().table("subjects").select("id,user_id,username,full_name,is_citizen,reputation").order("id"))
    _ensure_canonical_role_rows()
    roles = [r for r in rows(db().table("roles").select("id,name,emoji").order("name")) if _role_key(r.get("name", "")) in ROLE_CANONICAL or _role_key(r.get("name", "")) == "главный администратор"]
    links = fetch_all(lambda: db().table("subject_roles").select("subject_id,role_id").order("subject_id").order("role_id"))
    passports = fetch_all(lambda: db().table("passports").select("user_id,number,status").order("id"))
    pmap = {p["user_id"]: p for p in passports}
    role_by_id = {r["id"]: r for r in roles}
    ids_by_subject: dict[int, list] = {}
    for link in links:
        ids_by_subject.setdefault(link["subject_id"], []).append(link["role_id"])
    for s in subjects:
        s["roles"] = [role_by_id[i] for i in ids_by_subject.get(s["id"], []) if i in role_by_id]
        p = pmap.get(s["user_id"])
        s["passport_number"] = p["number"] if p else None
        s["passport_status"] = p["status"] if p else None
    return {"subjects": subjects, "roles": roles}


def admin_set_citizenship(actor_id: int, subject_id: int, is_citizen: bool) -> dict:
    subject = one(db().table("subjects").select(SUBJECT_COLS).eq("id", int(subject_id)).limit(1))
    if not subject:
        raise DKDError(404, "Пользователь не найден.")
    if is_citizen:
        _, passport = grant_citizenship(subject)
        audit(actor_id, "citizenship_granted", subject["user_id"], f"subject:{subject['id']}")
        return {"ok": True, "passport_number": passport["number"]}
    revoke_citizenship(subject)
    audit(actor_id, "citizenship_revoked", subject["user_id"], f"subject:{subject['id']}")
    return {"ok": True, "passport_number": None}


def admin_grant_by_telegram_id(actor_id: int, telegram_id: int, full_name: str) -> dict:
    """Безопасное первоначальное назначение: администратор вводит проверенный ЧИСЛОВОЙ Telegram ID."""
    if int(telegram_id) <= 0:
        raise DKDError(422, "Telegram ID должен быть положительным числом.")
    name = clean_name(full_name)
    if not 2 <= len(name) <= 120:
        raise DKDError(422, "Укажите ФИО (от 2 до 120 символов).")
    subject = ensure_subject(int(telegram_id), None, name)
    _, passport = grant_citizenship(subject, full_name=name)
    audit(actor_id, "citizenship_granted_by_id", int(telegram_id), f"subject:{subject['id']}")
    return {"ok": True, "passport_number": passport["number"], "subject_id": subject["id"]}


def admin_create_role(actor_id: int, name: str, emoji: str = "👤") -> dict:
    name = clean_name(name)
    key = name.casefold()
    if key not in ROLE_CANONICAL:
        raise DKDError(422, "Разрешены только роли: Президент, Министр иностранных дел, Премьер-министр, Депутат, Гражданин.")
    canonical_name, canonical_emoji = ROLE_CANONICAL[key]
    existing = one(db().table("roles").select("id,name,emoji").eq("name", canonical_name).limit(1))
    if existing:
        return existing
    role = execute(db().table("roles").insert({"name": canonical_name, "emoji": canonical_emoji}), "Не удалось создать роль.")[0]
    audit(actor_id, "role_created", None, f"role:{role['id']}")
    return role


def _role_key(name: str) -> str:
    return re.sub(r"\s+", " ", clean_name(name).casefold()).strip()


def _ensure_canonical_role_rows() -> list[dict]:
    existing = rows(db().table("roles").select("id,name,emoji"))
    by_key = {_role_key(r.get("name", "")): r for r in existing}
    for key, (name, emoji) in ROLE_CANONICAL.items():
        if key not in by_key:
            row = execute(db().table("roles").insert({"name": name, "emoji": emoji}), "Не удалось подготовить системные роли.")[0]
            by_key[key] = row
    return [by_key[k] for k in ROLE_CANONICAL if k in by_key]


def admin_assign_role(actor_id: int, subject_id: int, role_id: int, assigned: bool) -> dict:
    subject = one(db().table("subjects").select("id,user_id").eq("id", int(subject_id)).limit(1))
    if not subject:
        raise DKDError(404, "Пользователь не найден.")
    role = one(db().table("roles").select("id,name").eq("id", int(role_id)).limit(1))
    if not role:
        raise DKDError(404, "Роль не найдена.")
    key = _role_key(role["name"])
    if key not in ROLE_CANONICAL:
        raise DKDError(403, "Можно назначать только пять системных ролей.")
    link = one(db().table("subject_roles").select("subject_id").eq("subject_id", subject["id"]).eq("role_id", int(role_id)).limit(1))
    if assigned and key in ROLE_LIMITED_SINGLE:
        links = fetch_all(lambda: db().table("subject_roles").select("subject_id").eq("role_id", int(role_id)))
        if any(int(item["subject_id"]) != int(subject["id"]) for item in links):
            raise DKDError(409, f"Роль «{role['name']}» уже назначена другому человеку.")
    if assigned and not link:
        execute(db().table("subject_roles").insert({"subject_id": subject["id"], "role_id": int(role_id)}), "Не удалось назначить роль.")
    elif not assigned and link:
        execute(db().table("subject_roles").delete().eq("subject_id", subject["id"]).eq("role_id", int(role_id)), "Не удалось снять роль.")
    audit(actor_id, "role_assigned" if assigned else "role_removed", subject["user_id"], f"role:{role_id}")
    return {"ok": True}


def president_list_subjects() -> dict:
    """Список участников и ролей, которые Президент вправе назначать."""
    data = admin_list_subjects()
    allowed = [r for r in data["roles"] if _role_key(r.get("name", "")) in PRESIDENT_ASSIGNABLE_ROLES]
    data["roles"] = allowed
    return data


def president_assign_role(actor_id: int, subject_id: int, role_id: int, assigned: bool) -> dict:
    role = one(db().table("roles").select("id,name").eq("id", int(role_id)).limit(1))
    if not role or _role_key(role.get("name", "")) not in PRESIDENT_ASSIGNABLE_ROLES:
        raise DKDError(403, "Президент может назначать все роли, кроме Президента.")
    return admin_assign_role(actor_id, subject_id, role_id, assigned)


def president_review_application(actor_id: int, application_id: int, status: str, note: str = "") -> dict:
    result = review_application(actor_id, application_id, status, note)
    audit(actor_id, "president_application_reviewed", None, f"application:{application_id}:{status}")
    return result


def president_create_duma_election(actor_id: int, title: str, starts_at: str, ends_at: str) -> dict:
    return president_schedule_duma_election(actor_id, title, starts_at, ends_at)


def president_publish_law(actor_id: int, title: str, description: str) -> dict:
    return publish_law(actor_id, title, description)


def president_set_law_status(actor_id: int, law_id: int, status: str) -> dict:
    if status not in ("signed", "vetoed"):
        raise DKDError(422, "Недопустимое решение по закону.")
    law = one(db().table("laws").select("id,title,status").eq("id", int(law_id)).limit(1))
    if not law:
        raise DKDError(404, "Закон не найден.")
    updated = execute(db().table("laws").update({"status": status}).eq("id", int(law_id)),
                      "Не удалось обновить статус закона.")
    audit(actor_id, "president_law_signed" if status == "signed" else "president_law_vetoed", None,
          f"law:{law_id}")
    return updated[0] if updated else {"id": law_id, "status": status}


# ----------------------------------------------------------------------------
# Самодиагностика схемы БД (только для администратора)
# ----------------------------------------------------------------------------

REQUIRED_SCHEMA = {
    "subjects": SUBJECT_COLS + ",avatar_emoji",
    "passports": PASSPORT_COLS + ",updated_at",
    "document_applications": APP_COLS + ",reviewed_by",
    "roles": "id,name,emoji",
    "subject_roles": "subject_id,role_id",
    "parties": PARTY_COLS,
    "party_members": "id,party_id,username,user_id",
    "pres_elections": ELECTION_COLS,
    "duma_elections": ELECTION_COLS,
    "votes": "id,user_id,election_type,election_id,option",
    "laws": "id,title,description,status,created_at",
    "decrees": "id,title,text,is_secret,secret_for,secret_for_user_id,created_at",
    "news": "id,text,created_at",
    "audit_log": "id,actor_user_id,action,target_user_id,details",
    "app_settings": "key,value",
}


def diagnostics() -> list[dict]:
    report = []
    for table, cols in REQUIRED_SCHEMA.items():
        try:
            db().table(table).select(cols).limit(1).execute()
            report.append({"table": table, "ok": True})
        except Exception as exc:  # noqa: BLE001
            report.append({"table": table, "ok": False, "error": str(exc)[:200]})
    return report
