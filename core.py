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
import urllib.error
import urllib.request
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
    """Выполнить запрос; кратко повторить только безопасное чтение при сетевом обрыве.

    Запросы на запись намеренно не повторяем: соединение может оборваться уже
    после фиксации изменения на сервере, и повтор способен создать дубликат.
    """
    method = str(getattr(query, "method", "") or "").upper()
    attempts = 3 if method == "GET" else 1
    last_exc: Exception | None = None
    for attempt in range(attempts):
        try:
            return query.execute().data or []
        except DKDError:
            raise
        except Exception as exc:  # noqa: BLE001 — Supabase может бросать разные типы
            last_exc = exc
            message = str(exc).lower()
            transient = any(marker in message for marker in (
                "server disconnected", "remoteprotocolerror", "connection reset",
                "connection aborted", "read timeout", "timed out",
                "temporarily unavailable", "connecterror",
            ))
            if attempt + 1 >= attempts or not transient:
                break
            # Короткая пауза даёт HTTP-соединению восстановиться, не задерживая
            # обычные ошибки схемы/SQL и не повторяя операции записи.
            time.sleep(0.2 * (attempt + 1))
    assert last_exc is not None
    raise db_error(last_exc, public) from last_exc


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


def audit(actor_id: int, action: str, target_user_id: int | None = None, details: str | None = None) -> dict | None:
    """Записать событие в журнал и вернуть строку, если БД её подтвердила.

    Большинство вызывающих функций используют журнал только для аудита; отдельные
    процессы (например, голосование по импичменту) также используют ID события.
    Сбой журнала не должен скрывать уже выполненное действие, поэтому при ошибке
    возвращается None, а вызывающая функция сама решает, критичен ли этот результат.
    """
    try:
        response = db().table("audit_log").insert({
            "actor_user_id": int(actor_id),
            "action": action,
            "target_user_id": int(target_user_id) if target_user_id is not None else None,
            "details": details,
        }).execute()
        data = getattr(response, "data", None) or []
        return data[0] if data and isinstance(data[0], dict) else None
    except Exception as exc:  # noqa: BLE001
        log.error("audit_log write failed (%s): %s", action, exc)
        return None


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
    # При входе пользователя синхронизируем истёкший срок и результаты выборов.
    _sync_duma_term()
    _maybe_finalize_duma_elections()
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
    decision_text = "одобрено" if status == "approved" else "отклонено"
    create_notification(int(app_row["user_id"]), "Рассмотрено ваше заявление",
        f"Ваше заявление («{app_row.get('kind', 'документ')}») {decision_text}." + (f"\nКомментарий: {note}" if note else ""))
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
PRESIDENTIAL_CANDIDATES_KEY = "presidential_candidate_applications"


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
    # Подтягиваем заранее подготовленные списки председателей партий.
    prepared = {}
    parties = rows(db().table("parties").select(PARTY_COLS).eq("status", "active"))
    for party in parties:
        raw_ids = _setting_get(DUMA_PARTY_TEMPLATE_KEY_PREFIX + str(int(party["id"])))
        if not raw_ids:
            continue
        try:
            candidate_ids = [int(x) for x in json.loads(raw_ids)]
        except (ValueError, TypeError, json.JSONDecodeError):
            continue
        if not candidate_ids:
            continue
        candidates = select_in("subjects", "user_id,full_name", "user_id", candidate_ids)
        names_by_id = {int(x["user_id"]): clean_name(x.get("full_name", "")) for x in candidates}
        valid_candidate_ids = [candidate_id for candidate_id in candidate_ids
                               if candidate_id in names_by_id and names_by_id[candidate_id]]
        names = [names_by_id[candidate_id] for candidate_id in valid_candidate_ids]
        if names:
            prepared[str(int(party["id"]))] = {
                "party_id": int(party["id"]), "party_name": party["name"],
                "party_emoji": party.get("emoji") or "🎭",
                "leader_user_id": int(party.get("leader_user_id") or 0),
                "candidates": names, "candidate_user_ids": valid_candidate_ids,
                "submitted_at": utcnow().isoformat()
            }
    _setting_put(DUMA_LISTS_KEY_PREFIX + str(election["id"]), json.dumps(prepared, ensure_ascii=False))
    if prepared:
        execute(db().table("duma_elections").update({"options": [x["party_name"] for x in prepared.values()]})
                .eq("id", int(election["id"])), "Не удалось применить подготовленные списки партий.")
    audit(actor_id, "duma_election_scheduled", None, f"duma:{election['id']}:{start.isoformat()}:{end.isoformat()}")
    return {**election, "starts_at": start.isoformat(), "status": "scheduled"}


DUMA_PARTY_TEMPLATE_KEY_PREFIX = "duma_party_template:"


def _is_party_leader(party: dict, user: dict) -> bool:
    """Проверяет председательство по Telegram ID; username — только для старых записей без ID."""
    try:
        uid = int(user.get("id") or 0)
    except (TypeError, ValueError):
        return False
    leader_uid = party.get("leader_user_id")
    if leader_uid not in (None, ""):
        try:
            return int(leader_uid) == uid
        except (TypeError, ValueError):
            return False
    leader_name = str(party.get("leader_username") or "").strip().lstrip("@").casefold()
    user_name = str(user.get("username") or "").strip().lstrip("@").casefold()
    return bool(leader_name and user_name and leader_name == user_name)

def get_duma_party_template(user: dict) -> dict:
    uid = int(user["id"])
    parties = rows(db().table("parties").select(PARTY_COLS).eq("status", "active"))
    party = next((p for p in parties if _is_party_leader(p, user)), None)
    if not party:
        return {"party": None, "members": [], "selected_user_ids": []}
    members = rows(db().table("party_members").select("user_id,username").eq("party_id", int(party["id"])))
    ids = [int(m["user_id"]) for m in members if m.get("user_id") is not None]
    subjects = select_in("subjects", "user_id,full_name", "user_id", ids) if ids else []
    by_id = {int(x["user_id"]): x for x in subjects}
    raw = _setting_get(DUMA_PARTY_TEMPLATE_KEY_PREFIX + str(int(party["id"])))
    try:
        selected = [int(x) for x in json.loads(raw or "[]")]
    except (ValueError, TypeError, json.JSONDecodeError):
        selected = []
    selected = [x for x in selected if x in by_id]
    return {"party": {"id": party["id"], "name": party["name"], "emoji": party.get("emoji") or "🎭"},
            "members": [{"user_id": uid_, "full_name": by_id[uid_].get("full_name") or next(
                (m.get("username") for m in members if int(m.get("user_id") or 0) == uid_), str(uid_))}
                for uid_ in ids if uid_ in by_id], "selected_user_ids": selected}


def save_duma_party_template(user: dict, candidate_user_ids: list[int]) -> dict:
    uid = int(user["id"])
    current = get_duma_party_template(user)
    if not current["party"]:
        raise DKDError(403, "Составлять партийный список может только председатель партии.")
    allowed = {int(m["user_id"]) for m in current["members"]}
    chosen = [int(x) for x in candidate_user_ids]
    if not chosen or len(chosen) > 50:
        raise DKDError(400, "Выберите от 1 до 50 кандидатов из состава своей партии.")
    if len(set(chosen)) != len(chosen):
        raise DKDError(400, "В списке не должно быть повторяющихся участников.")
    if any(x not in allowed for x in chosen):
        raise DKDError(422, "Кандидатами могут быть только действующие члены вашей партии.")
    party_id = int(current["party"]["id"])
    _setting_put(DUMA_PARTY_TEMPLATE_KEY_PREFIX + str(party_id), json.dumps(chosen))
    # Синхронизируем сохранённый состав с уже назначенными выборами, не меняя завершённые.
    elections = rows(db().table("duma_elections").select(ELECTION_COLS).order("id", desc=True).limit(50))
    names = {int(m["user_id"]): m["full_name"] for m in current["members"]}
    synced = 0
    for election in elections:
        if election.get("status") in CLOSED_STATUSES:
            continue
        # Не меняем кампанию после фактического окончания, даже если статус ещё не обновлён.
        election_end = parse_dt(election.get("ends_at"))
        if election_end and election_end <= utcnow():
            continue
        lists = duma_party_lists(int(election["id"]))
        lists[str(party_id)] = {"party_id": party_id, "party_name": current["party"]["name"],
            "party_emoji": current["party"]["emoji"], "leader_user_id": uid,
            "candidates": [names[x] for x in chosen], "candidate_user_ids": chosen,
            "submitted_at": utcnow().isoformat()}
        _setting_put(DUMA_LISTS_KEY_PREFIX + str(int(election["id"])), json.dumps(lists, ensure_ascii=False))
        execute(db().table("duma_elections").update({"options": [x["party_name"] for x in lists.values()]})
                .eq("id", int(election["id"])), "Не удалось синхронизировать список с выборами.")
        synced += 1
    audit(uid, "duma_party_template_saved", None, f"party:{party_id}:candidates:{len(chosen)}")
    return {"ok": True, "selected_user_ids": chosen, "synced_elections": synced}


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
    if election.get("status") in CLOSED_STATUSES or (end and utcnow() >= end):
        raise DKDError(409, "Приём списков для этих выборов завершён.")
    cleaned = [clean_name(x) for x in candidates if clean_name(x)]
    if not cleaned or len(cleaned) > 50 or any(len(x) > 120 for x in cleaned):
        raise DKDError(400, "Укажите от 1 до 50 кандидатов; имя каждого — до 120 символов.")
    if len({x.casefold() for x in cleaned}) != len(cleaned):
        raise DKDError(400, "В списке не должно быть повторяющихся кандидатов.")
    registered = rows(db().table("subjects").select("user_id,full_name"))
    registered_by_name: dict[str, list[dict]] = {}
    for subject in registered:
        name_key = clean_name(subject.get("full_name", "")).casefold()
        if name_key:
            registered_by_name.setdefault(name_key, []).append(subject)
    missing = [name for name in cleaned if name.casefold() not in registered_by_name]
    if missing:
        raise DKDError(422, "Сначала каждый кандидат должен открыть Mini App и создать профиль. Не найдены: " + ", ".join(missing[:5]))
    ambiguous = [name for name in cleaned if len(registered_by_name.get(name.casefold(), [])) > 1]
    if ambiguous:
        raise DKDError(422, "Невозможно однозначно связать одинаковые ФИО с Telegram-пользователем: " + ", ".join(ambiguous[:5]))
    candidate_user_ids = [
        int(registered_by_name[name.casefold()][0]["user_id"])
        for name in cleaned
        if registered_by_name[name.casefold()][0].get("user_id") is not None
    ]
    if len(candidate_user_ids) != len(cleaned):
        raise DKDError(422, "У каждого кандидата должен быть Telegram-профиль с числовым ID.")
    if len(set(candidate_user_ids)) != len(candidate_user_ids):
        raise DKDError(422, "Один участник не может быть указан в списке несколько раз.")
    lists = duma_party_lists(election_id)
    lists[str(party["id"])] = {"party_id": party["id"], "party_name": party["name"], "party_emoji": party.get("emoji") or "🎭",
                               "leader_user_id": uid, "candidates": cleaned,
                               "candidate_user_ids": candidate_user_ids,
                               "submitted_at": utcnow().isoformat()}
    _setting_put(DUMA_LISTS_KEY_PREFIX + str(int(election_id)), json.dumps(lists, ensure_ascii=False))
    options = [item["party_name"] for item in lists.values()]
    execute(db().table("duma_elections").update({"options": options}).eq("id", int(election_id)), "Не удалось обновить списки партий на выборах.")
    audit(uid, "duma_party_list_submitted", None, f"election:{election_id}:party:{party['id']}")
    return {"ok": True, "party_name": party["name"], "candidates": cleaned}


def list_duma_elections_for_party(user: dict) -> dict:
    uid = int(user["id"])
    parties = rows(db().table("parties").select(PARTY_COLS).eq("status", "active"))
    leader_parties = [p for p in parties if _is_party_leader(p, user)]
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


DUMA_TERM_START_KEY = "duma_term_start"
DUMA_TERM_END_KEY = "duma_term_end"
DUMA_TERM_ELECTION_KEY = "duma_term_election_id"
DUMA_TERM_STATUS_KEY = "duma_term_status"


def _add_months(value: datetime, months: int) -> datetime:
    month_index = value.month - 1 + months
    year = value.year + month_index // 12
    month = month_index % 12 + 1
    day = min(value.day, calendar.monthrange(year, month)[1])
    return value.replace(year=year, month=month, day=day)


def _deputy_role_row() -> dict:
    roles = _ensure_canonical_role_rows()
    role = next((r for r in roles if _role_key(r.get("name", "")) == "депутат"), None)
    if not role:
        raise DKDError(500, "Не удалось подготовить системную роль «Депутат».")
    return role


def _sync_duma_term() -> None:
    """Синхронизирует срок парламента и прекращает полномочия после его окончания."""
    raw_end = _setting_get(DUMA_TERM_END_KEY)
    if not raw_end:
        # Первый созыв был внесён отдельной SQL-таблицей; переносим его даты в настройки
        # Mini App, чтобы срок первого созыва тоже завершился автоматически.
        try:
            initial = rows(db().table("duma_initial_roster").select(
                "parliament_term_start,parliament_term_end"
            ).limit(1))
            if initial and initial[0].get("parliament_term_end"):
                raw_end = str(initial[0]["parliament_term_end"])
                _setting_put(DUMA_TERM_END_KEY, raw_end)
                if initial[0].get("parliament_term_start"):
                    _setting_put(DUMA_TERM_START_KEY, str(initial[0]["parliament_term_start"]))
                _setting_put(DUMA_TERM_STATUS_KEY, "active")
        except Exception as exc:  # noqa: BLE001
            log.info("Initial Duma term table is not available: %s", exc)
    if not raw_end or _setting_get(DUMA_TERM_STATUS_KEY) in {"expired", "dissolved"}:
        return
    end = parse_dt(raw_end)
    if not end or utcnow() < end:
        return
    role = one(db().table("roles").select("id,name").eq("name", "Депутат").limit(1))
    if role:
        execute(db().table("subject_roles").delete().eq("role_id", int(role["id"])),
                "Не удалось завершить полномочия предыдущего созыва.")
    _setting_put(DUMA_TERM_STATUS_KEY, "expired")
    audit(0, "duma_term_expired", None, json.dumps({"end_at": raw_end}, ensure_ascii=False))


def president_dissolve_parliament(actor_id: int) -> dict:
    require_president({"id": int(actor_id)})
    key = "president_last_parliament_dissolution_at"
    previous_raw = _setting_value(key)
    previous = parse_dt(previous_raw) if previous_raw else None
    now = utcnow()
    if previous and now < previous + timedelta(days=30):
        available = previous + timedelta(days=30)
        raise DKDError(409, "Повторный роспуск доступен " + available.strftime("%d.%m.%Y %H:%M UTC") + ". Разрешён один роспуск за 30 дней.")
    if _setting_get(DUMA_TERM_STATUS_KEY) == "dissolved":
        raise DKDError(409, "Парламент уже распущен. Сначала сформируйте новый созыв.")
    roster = public_duma_roster()
    if not roster.get("formed") or not roster.get("seats_occupied"):
        raise DKDError(409, "Нет действующего парламента для роспуска.")
    role = _deputy_role_row()
    execute(db().table("subject_roles").delete().eq("role_id", int(role["id"])), "Не удалось прекратить полномочия депутатов.")
    _setting_put(DUMA_TERM_STATUS_KEY, "dissolved")
    _save_setting(key, now.isoformat())
    audit(int(actor_id), "president_parliament_dissolved", None, json.dumps({"dissolved_at": now.isoformat()}, ensure_ascii=False))
    try:
        publish_news(int(actor_id), "🏛️ Президент распустил Государственную думу. Полномочия депутатов прекращены; для формирования нового созыва необходимо провести выборы.")
    except Exception as exc:
        log.warning("Could not publish parliament dissolution news: %s", exc)
    return {"ok": True, "dissolved_at": now.isoformat(), "next_available_at": (now + timedelta(days=30)).isoformat(), "message": "Парламент распущен. Повторный роспуск доступен через 30 дней."}


def _issue_duma_mandates(election_id: int, deputies: list[dict], term_start: str, term_end: str) -> int:
    """Создаёт персональные документы-мандаты. Повторный вызов безопасен."""
    created = 0
    for deputy in deputies:
        user_id = int(deputy.get("telegram_user_id") or 0)
        full_name = clean_name(deputy.get("full_name") or "")
        if not user_id or not full_name:
            continue
        title = f"Мандат депутата Госдумы · созыв №{int(election_id)}"
        existing = one(db().table("decrees").select("id")
                       .eq("title", title).eq("secret_for_user_id", user_id).limit(1))
        if existing:
            continue
        party = clean_name(deputy.get("party_name") or "Без партии")
        body = "\n".join([
            "ДЕМОКРАТИЧЕСКАЯ КОНФЕДЕРАЦИЯ ДЕБИЛОВ",
            "ГОСУДАРСТВЕННАЯ ДУМА",
            "",
            "УДОСТОВЕРЕНИЕ / МАНДАТ ДЕПУТАТА",
            "",
            f"Настоящим подтверждается, что {full_name} избран(а) депутатом Государственной Думы.",
            f"Политическая партия: {party}.",
            f"Номер созыва: {int(election_id)}.",
            f"Дата выдачи: {term_start}.",
            f"Действителен до: {term_end}.",
            "",
            "Мандат действует в течение трёхмесячного срока полномочий созыва.",
        ])
        execute(db().table("decrees").insert({
            "title": title, "text": body, "is_secret": True,
            "secret_for_user_id": user_id,
        }), "Не удалось выдать мандат депутату.")
        create_notification(
            user_id, "Вам выдан депутатский мандат",
            f"Вам выдан мандат депутата Госдумы. Срок полномочий действует до {term_end}. Документ доступен в разделе «Документы» Mini App."
        )
        created += 1
    return created


def _finalize_duma_election(actor_id: int, election_id: int) -> dict:
    """Подводит итоги парламентских выборов, назначает роли и срок нового созыва."""
    election = one(db().table("duma_elections").select(ELECTION_COLS).eq("id", int(election_id)).limit(1))
    if not election:
        raise DKDError(404, "Выборы в Госдуму не найдены.")
    saved_results = election.get("results") if isinstance(election.get("results"), dict) else {}
    if saved_results.get("parliament_term_end"):
        # Повторный вызов не должен возвращать к жизни старый созыв или
        # выдавать новые мандаты после окончания его полномочий.
        current_election_raw = _setting_get(DUMA_TERM_ELECTION_KEY)
        # Более новый ID означает, что уже сформирован следующий созыв.
        # Более старый ID может означать прерванную финализацию текущих выборов.
        if current_election_raw and current_election_raw.isdigit() and int(current_election_raw) > int(election_id):
            return {"ok": True, "results": saved_results, "already_finalized": True,
                    "historical": True, "mandates_issued": 0}
        term_start = str(saved_results.get("parliament_term_start") or "")
        term_end = str(saved_results["parliament_term_end"])
        end_dt = parse_dt(term_end)
        deputy_role = _deputy_role_row()
        if end_dt and utcnow() >= end_dt:
            execute(db().table("subject_roles").delete().eq("role_id", int(deputy_role["id"])),
                    "Не удалось синхронизировать завершившийся созыв.")
            _setting_put(DUMA_TERM_START_KEY, term_start)
            _setting_put(DUMA_TERM_END_KEY, term_end)
            _setting_put(DUMA_TERM_ELECTION_KEY, str(int(election_id)))
            _setting_put(DUMA_TERM_STATUS_KEY, "expired")
            return {"ok": True, "results": saved_results, "already_finalized": True,
                    "term_expired": True, "mandates_issued": 0}
        mandate_roster = saved_results.get("elected_deputies") or []
        if not mandate_roster:
            mandate_roster = public_duma_roster().get("members") or []
        subjects = rows(db().table("subjects").select("id,user_id"))
        subject_by_user = {int(x["user_id"]): int(x["id"]) for x in subjects if x.get("user_id") is not None}
        expected_subject_ids = set()
        expected_user_ids = set()
        for deputy in mandate_roster:
            try:
                uid = int(deputy.get("telegram_user_id") or 0)
            except (TypeError, ValueError):
                continue
            if uid > 0:
                expected_user_ids.add(uid)
        for uid in expected_user_ids:
            if uid in subject_by_user:
                expected_subject_ids.add(subject_by_user[uid])
        # Старые вручную внесённые списки или удалённые профили могут оставить
        # неполные ID. Не удаляем роли, если не удалось сопоставить весь состав.
        if expected_subject_ids and len(expected_subject_ids) == len(expected_user_ids):
            execute(db().table("subject_roles").delete().eq("role_id", int(deputy_role["id"])),
                    "Не удалось синхронизировать состав депутатов.")
            for subject_id in sorted(expected_subject_ids):
                execute(db().table("subject_roles").insert({"subject_id": subject_id, "role_id": int(deputy_role["id"])}),
                        "Не удалось восстановить полномочия депутата.")
        _setting_put(DUMA_TERM_START_KEY, term_start)
        _setting_put(DUMA_TERM_END_KEY, term_end)
        _setting_put(DUMA_TERM_ELECTION_KEY, str(int(election_id)))
        _setting_put(DUMA_TERM_STATUS_KEY, "active")
        metadata_keys = {"parliament_seats", "parliament_term_start", "parliament_term_end",
                         "elected_deputies", "parliament_term_status"}
        vote_counts = {str(k): int(v) for k, v in saved_results.items()
                       if str(k) not in metadata_keys and isinstance(v, (int, float)) and not isinstance(v, bool)}
        mandate_count = _issue_duma_mandates(int(election_id), mandate_roster, term_start, term_end)
        _publish_election_results_news_once(
            actor_id, "duma", election, vote_counts,
            "Парламент сформирован на срок до " + term_end + "."
        )
        return {"ok": True, "results": saved_results, "already_finalized": True,
                "mandates_issued": mandate_count}

    lists = duma_party_lists(election_id)
    if not lists:
        raise DKDError(409, "Нельзя сформировать парламент: партии ещё не подали списки кандидатов.")
    counts = tally("duma", election_id)
    party_by_name = {str(item.get("party_name", "")).casefold(): item for item in lists.values()}
    # Все учтённые голоса определяют размер парламента, даже если у партии
    # отсутствует корректный список: её места останутся вакантными, а голоса
    # не исчезнут из общего числа мест.
    valid = [(name, max(0, int(counts.get(name, 0))), party_by_name.get(str(name).casefold()))
             for name in normalize_options(election.get("options"))]
    total_votes = sum(votes for _, votes, _ in valid)
    if total_votes <= 0:
        raise DKDError(409, "Нельзя сформировать парламент без поданных голосов.")

    # Один голос за партию даёт одно место. Недостающие кандидаты не заменяются
    # повторными мандатами: места остаются вакантными.
    parliament_seats = total_votes
    seats_by_party = {name: votes for name, votes, _ in valid}
    raw = [(name, votes, item, float(votes)) for name, votes, item in valid]

    subjects = rows(db().table("subjects").select("id,user_id,full_name"))
    subject_by_user = {
        int(subject["user_id"]): subject
        for subject in subjects if subject.get("user_id") is not None
    }
    subjects_by_name: dict[str, list[dict]] = {}
    for subject in subjects:
        key = clean_name(subject.get("full_name", "")).casefold()
        if key:
            subjects_by_name.setdefault(key, []).append(subject)

    winners: dict[int, dict] = {}
    roster = []
    elected_user_ids: set[int] = set()
    for party_name, votes, party_data, quota in raw:
        party_seats = seats_by_party[party_name]
        if not party_data:
            continue  # Голоса учтены в размере парламента; места партии останутся вакантными.
        candidates = party_data.get("candidates") or []
        candidate_user_ids = party_data.get("candidate_user_ids") or []
        if not candidates:
            continue
        # Размер парламента равен голосам; один кандидат занимает ровно одно место.
        # Если список короче числа мест партии, разница остаётся вакантной.
        elected_count = min(len(candidates), party_seats)
        for idx, full_name in enumerate(candidates[:elected_count]):
            subject = None
            if idx < len(candidate_user_ids):
                try:
                    candidate_uid = int(candidate_user_ids[idx])
                except (TypeError, ValueError):
                    candidate_uid = 0
                if candidate_uid:
                    subject = subject_by_user.get(candidate_uid)
                    if not subject:
                        raise DKDError(409, f"Для кандидата «{full_name}» не найден профиль Telegram. Проверьте партийный список.")
            if subject is None:
                matches = subjects_by_name.get(clean_name(full_name).casefold(), [])
                if len(matches) > 1:
                    raise DKDError(409, f"ФИО «{full_name}» не уникально. Обновите список кандидатов, чтобы связать его с Telegram-пользователем.")
                subject = matches[0] if matches else None
            if not subject:
                raise DKDError(409, f"Кандидат «{full_name}» должен сначала открыть Mini App, чтобы система связала его ФИО с Telegram-профилем.")
            subject_id = int(subject["id"])
            user_id = int(subject["user_id"])
            if user_id in elected_user_ids:
                raise DKDError(409, f"Участник «{full_name}» указан более чем в одном партийном списке. Исправьте списки перед формированием парламента.")
            elected_user_ids.add(user_id)
            winners[subject_id] = {"subject": subject, "full_name": full_name, "party_name": party_name, "seats": 1}
            roster.append({"full_name": clean_name(subject.get("full_name") or full_name),
                           "party_id": party_data.get("party_id"),
                           "party_name": party_name, "party_emoji": party_data.get("party_emoji") or "🎭",
                           "votes": votes, "party_seats": party_seats, "seats": 1,
                           "is_party_leader": user_id == int(party_data.get("leader_user_id") or 0),
                           "telegram_user_id": user_id})
    if not winners:
        raise DKDError(409, "Не удалось определить участников нового созыва.")

    deputy_role = _deputy_role_row()
    # Снимаем только роль депутата, не затрагивая партийные и иные должности.
    execute(db().table("subject_roles").delete().eq("role_id", int(deputy_role["id"])),
            "Не удалось завершить предыдущий созыв.")
    for subject_id in winners:
        execute(db().table("subject_roles").insert({"subject_id": subject_id, "role_id": int(deputy_role["id"])}),
                "Не удалось назначить роль депутата избранному кандидату.")

    started = utcnow()
    ends = _add_months(started, 3)
    results = dict(counts)
    results.update({
        "parliament_seats": parliament_seats,
        "parliament_term_start": started.isoformat(),
        "parliament_term_end": ends.isoformat(),
        "elected_deputies": roster,
    })
    execute(db().table("duma_elections").update({"status": "closed", "results": results}).eq("id", int(election_id)),
            "Не удалось сохранить результаты выборов.")
    _setting_put(DUMA_TERM_START_KEY, started.isoformat())
    _setting_put(DUMA_TERM_END_KEY, ends.isoformat())
    _setting_put(DUMA_TERM_ELECTION_KEY, str(int(election_id)))
    _setting_put(DUMA_TERM_STATUS_KEY, "active")
    mandates_issued = _issue_duma_mandates(
        int(election_id), roster, started.isoformat(), ends.isoformat()
    )
    audit(actor_id, "duma_election_finalized", None, json.dumps({
        "election_id": int(election_id), "term_start": started.isoformat(), "term_end": ends.isoformat(),
        "deputies": len(winners), "seats": parliament_seats
    }, ensure_ascii=False))
    _publish_election_results_news_once(actor_id, "duma", election, counts,
                                         "Парламент сформирован на срок до " + ends.isoformat() + ".")
    return {"ok": True, "results": results, "deputies": roster, "term_start": started.isoformat(),
            "term_end": ends.isoformat(), "mandates_issued": mandates_issued}


def _maybe_finalize_duma_elections() -> None:
    """Закрывает истёкшие парламентские выборы при первом следующем обращении к приложению."""
    try:
        elections = rows(db().table("duma_elections").select(ELECTION_COLS).order("id", desc=True).limit(20))
        for election in elections:
            if election.get("status") in CLOSED_STATUSES:
                continue
            end = parse_dt(election.get("ends_at"))
            if end and utcnow() >= end:
                try:
                    _finalize_duma_election(0, int(election["id"]))
                except DKDError as exc:
                    log.warning("Duma election %s not finalized yet: %s", election.get("id"), exc.detail)
    except Exception as exc:  # noqa: BLE001
        log.warning("Automatic Duma election finalization skipped: %s", exc)


def admin_close_duma_election(actor_id: int, election_id: int) -> dict:
    return _finalize_duma_election(actor_id, election_id)



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
    _sync_duma_term()
    _maybe_finalize_duma_elections()
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
                closed_persisted = e.get("status") in CLOSED_STATUSES
                if not closed_persisted:
                    # Не ломаем загрузку списка выборов из-за сбоя сохранения итогов.
                    try:
                        execute(db().table(table).update({"status": "closed", "results": counts}).eq("id", int(e["id"])),
                                "Не удалось сохранить итоги завершившихся выборов.")
                        closed_persisted = True
                    except Exception:
                        log.exception("Не удалось сохранить итоги выборов %s/%s", kind, e.get("id"))
                if closed_persisted:
                    try:
                        _publish_election_results_news_once(0, kind, e, counts)
                    except Exception:
                        log.exception("Не удалось опубликовать итоги выборов %s/%s", kind, e.get("id"))
                    if kind == "presidential":
                        try:
                            _finalize_presidential_election(0, e, counts)
                        except Exception:
                            log.exception("Не удалось назначить победителя президентских выборов %s", e.get("id"))
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



def admin_cast_vote(actor_id: int, kind: str, election_id: int, voter_user_id: int, option: str) -> dict:
    """Администраторский голос от имени выбранного гражданина; повторный голос меняет выбор."""
    if kind not in ELECTION_TABLES:
        raise DKDError(404, "Неизвестный тип выборов.")
    voter = get_subject(int(voter_user_id))
    if not voter or not voter.get("is_citizen"):
        raise DKDError(404, "Указанный пользователь не найден или не является гражданином.")
    election = one(db().table(ELECTION_TABLES[kind]).select(ELECTION_COLS).eq("id", int(election_id)).limit(1))
    if not election:
        raise DKDError(404, "Выборы не найдены.")
    if effective_status(election) != "active":
        raise DKDError(409, "Можно голосовать от имени граждан только на открытых выборах.")
    if option not in normalize_options(election.get("options")):
        raise DKDError(400, "Выбранный кандидат отсутствует в списке вариантов.")
    existing = one(db().table("votes").select("id,option").eq("user_id", int(voter_user_id)).eq("election_type", kind).eq("election_id", int(election_id)).limit(1))
    if existing:
        execute(db().table("votes").update({"option": option}).eq("id", int(existing["id"])), "Не удалось изменить голос.")
        message = "Голос гражданина изменён."
        action = "admin_vote_changed"
    else:
        execute(db().table("votes").insert({"user_id": int(voter_user_id), "election_type": kind, "election_id": int(election_id), "option": option}), "Не удалось сохранить голос от имени гражданина.")
        message = "Голос от имени гражданина учтён."
        action = "admin_vote_cast"
    audit(actor_id, action, int(voter_user_id), f"{kind}:{int(election_id)};option={option}")
    return {"ok": True, "message": message, "voter_user_id": int(voter_user_id), "election_id": int(election_id), "kind": kind, "option": option}


def admin_reset_elections(actor_id: int) -> dict:
    """Полностью очищает бюллетени, кампании, исторические результаты и состав Думы."""
    existing_votes = fetch_all(lambda: db().table("votes").select("id").order("id"))
    deleted_votes = len(existing_votes)
    if existing_votes:
        execute(db().table("votes").delete().gt("id", 0), "Не удалось удалить голоса.")
    deleted_elections = 0
    for table in ELECTION_TABLES.values():
        old_rows = rows(db().table(table).select("id").order("id"))
        deleted_elections += len(old_rows)
        if old_rows:
            execute(db().table(table).delete().gt("id", 0), f"Не удалось очистить таблицу {table}.")
    for key in (DUMA_TERM_START_KEY, DUMA_TERM_END_KEY, DUMA_TERM_ELECTION_KEY, DUMA_TERM_STATUS_KEY):
        execute(db().table("app_settings").delete().eq("key", key), "Не удалось сбросить сроки Думы.")
    for prefix in (DUMA_START_KEY_PREFIX, DUMA_LISTS_KEY_PREFIX):
        execute(db().table("app_settings").delete().like("key", prefix + "%"), "Не удалось удалить настройки кампаний.")
    deputy = one(db().table("roles").select("id,name").eq("name", "Депутат").limit(1))
    if deputy:
        execute(db().table("subject_roles").delete().eq("role_id", int(deputy["id"])), "Не удалось снять полномочия депутатов.")
    try:
        initial = rows(db().table("duma_initial_roster").select("full_name").limit(1000))
        if initial:
            execute(db().table("duma_initial_roster").delete().gt("full_name", ""), "Не удалось очистить прежний состав Думы.")
    except Exception as exc:
        log.info("duma_initial_roster не очищен или отсутствует: %s", exc)
    audit(actor_id, "all_elections_reset", None, json.dumps({"deleted_votes": deleted_votes, "deleted_elections": deleted_elections}, ensure_ascii=False))
    return {"ok": True, "deleted_votes": deleted_votes, "deleted_elections": deleted_elections, "message": "Голоса, кампании, результаты и состав Думы очищены."}



def create_election(actor_id: int, kind: str, title: str, options: list[str], ends_at: str | None) -> dict:
    if kind not in ELECTION_TABLES:
        raise DKDError(404, "Неизвестный тип выборов.")
    title = clean_name(title)
    if not 3 <= len(title) <= 160:
        raise DKDError(422, "Название выборов: от 3 до 160 символов.")
    cleaned = [clean_name(x) for x in options if clean_name(x)]
    if kind == "presidential":
        approved = {clean_name(x.get("full_name")).casefold() for x in _presidential_candidate_applications()
                    if x.get("status") == "approved" and clean_name(x.get("full_name"))}
        if len(cleaned) < 2 or any(name.casefold() not in approved for name in cleaned):
            raise DKDError(409, "В президентский бюллетень можно включать только кандидатуры, утверждённые администратором.")
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
    try:
        citizens = rows(db().table("subjects").select("user_id").eq("is_citizen", True))
        election_label = "президентские выборы" if kind == "presidential" else "выборы в Государственную думу"
        for citizen in citizens:
            uid = int(citizen.get("user_id") or 0)
            if uid:
                create_notification(uid, "Началось голосование", f"Открыты {election_label}: «{title}». Перейдите в раздел «Выборы», чтобы ознакомиться с вариантами и проголосовать.")
    except Exception as exc:
        log.warning("Election-open notifications failed: %s", exc)
    return created[0]


def _finalize_presidential_election(actor_id: int, election: dict, counts: dict[str, int]) -> dict:
    """Назначает победителя закрытых президентских выборов; повторный вызов безопасен."""
    election_id = int(election["id"])
    marker_key = f"presidential_election_finalized:{election_id}"
    already = _setting_value(marker_key)
    if already:
        return {"appointed": True, "user_id": int(already), "already_finalized": True}

    numeric_counts = {
        clean_name(name): int(votes)
        for name, votes in counts.items()
        if isinstance(votes, (int, float)) and not isinstance(votes, bool)
    }
    if not numeric_counts:
        return {"appointed": False, "reason": "no_votes"}
    high_score = max(numeric_counts.values())
    if high_score <= 0:
        return {"appointed": False, "reason": "no_votes"}
    winners = [name for name, votes in numeric_counts.items() if votes == high_score]
    if len(winners) != 1:
        return {"appointed": False, "reason": "tie", "winners": winners}

    winning_name = winners[0]
    applications = [
        item for item in _presidential_candidate_applications()
        if item.get("status") == "approved"
        and clean_name(item.get("full_name")).casefold() == winning_name.casefold()
    ]
    if len(applications) != 1:
        log.error("Presidential election %s winner %r has %s approved application matches",
                  election_id, winning_name, len(applications))
        return {"appointed": False, "reason": "candidate_not_unique", "winner": winning_name}
    winner_uid = int(applications[0].get("user_id") or 0)
    winner = get_subject(winner_uid)
    if not winner or not winner.get("is_citizen"):
        log.error("Presidential election %s winner %r is no longer an available citizen",
                  election_id, winning_name)
        return {"appointed": False, "reason": "candidate_unavailable", "winner": winning_name}
    winner_subject_id = int(winner["id"])

    canonical_roles = _ensure_canonical_role_rows()
    president_role = next((r for r in canonical_roles if _role_key(r.get("name", "")) == "президент"), None)
    if not president_role:
        raise DKDError(500, "Не найдена системная роль Президента.")
    all_roles = rows(db().table("roles").select("id,name"))
    president_role_ids = [
        int(role["id"]) for role in all_roles
        if _role_key(role.get("name", "")) == "президент"
    ]
    old_links = []
    if president_role_ids:
        old_links = fetch_all(lambda: db().table("subject_roles").select("subject_id,role_id")
                              .in_("role_id", president_role_ids))
    subjects_by_id = {
        int(item["id"]): item
        for item in rows(db().table("subjects").select("id,user_id,full_name"))
    }
    old_user_ids = sorted({
        int(subjects_by_id[int(link["subject_id"])]["user_id"])
        for link in old_links
        if int(link.get("subject_id") or 0) in subjects_by_id
        and subjects_by_id[int(link["subject_id"])].get("user_id") is not None
        and int(subjects_by_id[int(link["subject_id"])]["user_id"]) != winner_uid
    })

    # Удаляем старые президентские назначения и создаём единственное актуальное.
    # Если запрос прервётся, следующий вызов повторит операцию до записи маркера.
    for role_id in president_role_ids:
        execute(db().table("subject_roles").delete().eq("role_id", role_id),
                "Не удалось завершить прежние президентские полномочия.")
    execute(db().table("subject_roles").insert({
        "subject_id": winner_subject_id, "role_id": int(president_role["id"])
    }), "Не удалось назначить победителя Президентом.")

    started = utcnow()
    ends = _add_calendar_months(started, 3)
    _save_setting(PRESIDENT_TERM_USER_KEY, str(winner_uid))
    _save_setting(PRESIDENT_TERM_START_KEY, started.isoformat())
    _save_setting(PRESIDENT_TERM_END_KEY, ends.isoformat())
    _save_setting(marker_key, str(winner_uid))

    audit(actor_id, "presidential_election_finalized", winner_uid, json.dumps({
        "election_id": election_id, "winner": winning_name,
        "votes": high_score, "term_start": started.isoformat(), "term_end": ends.isoformat()
    }, ensure_ascii=False))
    try:
        create_notification(winner_uid, "Вы избраны Президентом ДКД",
                            f"По итогам выборов вы назначены Президентом ДКД. Срок полномочий — до {ends.isoformat()}.")
    except Exception as exc:
        log.warning("Could not notify presidential election winner: %s", exc)
    for old_uid in old_user_ids:
        try:
            create_notification(old_uid, "Президентские полномочия завершены",
                                "По итогам новых выборов ваши полномочия Президента ДКД прекращены.")
        except Exception as exc:
            log.warning("Could not notify former president %s: %s", old_uid, exc)
    try:
        _ensure_president_certificate()
    except Exception as exc:
        log.warning("Could not create presidential certificate after election: %s", exc)
    try:
        publish_news(actor_id, f"👑 По итогам президентских выборов Президентом ДКД избран(а) {winning_name}. Срок полномочий — до {ends.isoformat()}.")
    except Exception as exc:
        log.warning("Could not publish presidential appointment news: %s", exc)
    return {"appointed": True, "user_id": winner_uid, "winner": winning_name,
            "votes": high_score, "term_start": started.isoformat(), "term_end": ends.isoformat()}


def close_election(actor_id: int, kind: str, election_id: int) -> dict:
    if kind not in ELECTION_TABLES:
        raise DKDError(404, "Неизвестный тип выборов.")
    table = ELECTION_TABLES[kind]
    election = one(db().table(table).select(ELECTION_COLS).eq("id", int(election_id)).limit(1))
    if not election:
        raise DKDError(404, "Выборы не найдены.")
    counts = tally(kind, election_id)
    if election.get("status") not in CLOSED_STATUSES:
        execute(db().table(table).update({"status": "closed", "results": counts}).eq("id", int(election_id)),
                "Не удалось завершить выборы.")
        audit(actor_id, "election_closed", None, f"{kind}:{election_id}")
    # Повторный вызов безопасен: позволяет восстановить новость, если предыдущая
    # попытка завершила выборы, но упала при публикации результатов.
    news_published = _publish_election_results_news_once(actor_id, kind, election, counts)
    president_result = _finalize_presidential_election(actor_id, election, counts) if kind == "presidential" else None
    if election.get("status") not in CLOSED_STATUSES:
        try:
            citizens = rows(db().table("subjects").select("user_id").eq("is_citizen", True))
            result_text = ", ".join(f"{name}: {count}" for name, count in counts.items()) or "результаты отсутствуют"
            for citizen in citizens:
                uid = int(citizen.get("user_id") or 0)
                if uid:
                    create_notification(uid, "Опубликованы результаты выборов", f"Выборы «{election.get('title', '')}» завершены. Результаты: {result_text}.")
        except Exception as exc:
            log.warning("Election-result notifications failed: %s", exc)
    response = {"ok": True, "results": counts, "news_published": news_published}
    if president_result is not None:
        response["president_result"] = president_result
    return response


# ----------------------------------------------------------------------------
# Партии
# ----------------------------------------------------------------------------

PARTY_COLS = "id,name,emoji,description,leader_username,leader_user_id,status,color"


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
        leader_uname = str(p.get("leader_username") or "").strip().lstrip("@").casefold()
        is_mine = any(m.get("user_id") is not None and int(m["user_id"]) == uid for m in ms)
        leader_uid = p.get("leader_user_id")
        is_leader = (leader_uid is not None and int(leader_uid) == uid) or (
            not leader_uid and leader_uname and
            str(user.get("username") or "").strip().lstrip("@").casefold() == leader_uname
        )
        if is_mine:
            my_party_id = p["id"]
        out.append({
            "id": p["id"], "name": p["name"], "emoji": p.get("emoji") or "🎭", "description": p.get("description"), "color": p.get("color") or "#4169e1",
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
            "can_manage": admin or is_leader,
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


def update_party_settings(user: dict, party_id: int, name: str, emoji: str = "🎭", description: str = "", color: str = "#4169e1", admin: bool = False) -> dict:
    uid = int(user["id"])
    party = one(db().table("parties").select(PARTY_COLS).eq("id", int(party_id)).limit(1))
    if not party or party.get("status") != "active":
        raise DKDError(404, "Партия не найдена.")
    if not admin and not _is_party_leader(party, user):
        raise DKDError(403, "Настройки партии может менять только её председатель или администратор.")
    name = clean_name(name)
    emoji = str(emoji or "🎭").strip()[:8] or "🎭"
    description = str(description or "").strip()
    color = str(color or "").strip()
    if not 2 <= len(name) <= 60:
        raise DKDError(422, "Название партии: от 2 до 60 символов.")
    if len(description) > 1000:
        raise DKDError(422, "Описание партии: не более 1000 символов.")
    if not re.fullmatch(r"#[0-9A-Fa-f]{6}", color):
        raise DKDError(422, "Выберите корректный цвет партии.")
    existing = rows(db().table("parties").select("id,name").eq("status", "active"))
    if any(int(p["id"]) != int(party_id) and clean_name(p["name"]).casefold() == name.casefold() for p in existing):
        raise DKDError(409, "Партия с таким названием уже существует.")

    updated = execute(
        db().table("parties").update({
            "name": name, "emoji": emoji, "description": description, "color": color
        }).eq("id", int(party_id)),
        "Не удалось сохранить настройки партии."
    )[0]

    # Синхронизируем исторический состав парламента с переименованной партией.
    # Цвет остаётся в таблице parties и подхватывается схемой при каждом запросе.
    old_name = str(party.get("name") or "")
    old_emoji = str(party.get("emoji") or "🎭")
    if old_name != name or old_emoji != emoji:
        try:
            db().table("duma_initial_roster").update({
                "party_name": name, "party_emoji": emoji
            }).eq("party_name", old_name).execute()
        except Exception as exc:  # roster table may be absent in installations without election history
            log.warning("Could not synchronize historical Duma party labels: %s", exc)
    audit(uid, "party_settings_updated", None, f"party:{party_id};name:{name};color:{color}")
    return updated

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
    create_notification(uid, "Вы вступили в партию", f"Вы стали участником партии «{party.get('name', 'партия')}».")
    leader_uid = int(party.get("leader_user_id") or 0)
    if leader_uid and leader_uid != uid:
        create_notification(leader_uid, "Новый участник партии", f"{clean_name(user.get('full_name') or user.get('username') or str(uid))} вступил(а) в партию «{party.get('name', 'партия')}».")
    return {"ok": True}


def leave_party(user: dict) -> dict:
    uid = int(user["id"])
    member, party = active_membership(uid)
    if not member:
        raise DKDError(404, "Вы не состоите в партии.")
    if _is_party_leader(party, user):
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
    if not (admin or _is_party_leader(party, actor)):
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



def admin_create_party(actor_id: int, name: str, emoji: str, description: str, color: str,
                       leader_telegram_id: int, leader_full_name: str) -> dict:
    """Создаёт партию от имени указанного Telegram-пользователя."""
    if int(leader_telegram_id) <= 0:
        raise DKDError(422, "Telegram ID председателя должен быть положительным числом.")
    name = clean_name(name)
    emoji = str(emoji or "🎭").strip()[:8] or "🎭"
    description = str(description or "").strip()
    color = str(color or "#4169e1").strip()
    leader_full_name = clean_name(leader_full_name)
    if not 2 <= len(name) <= 60:
        raise DKDError(422, "Название партии: от 2 до 60 символов.")
    if not 2 <= len(leader_full_name) <= 120:
        raise DKDError(422, "Укажите ФИО председателя.")
    if len(description) > 1000:
        raise DKDError(422, "Описание партии: не более 1000 символов.")
    if not re.fullmatch(r"#[0-9A-Fa-f]{6}", color):
        raise DKDError(422, "Выберите корректный цвет партии.")
    existing = rows(db().table("parties").select("id,name").eq("status", "active"))
    if any(clean_name(p["name"]).casefold() == name.casefold() for p in existing):
        raise DKDError(409, "Партия с таким названием уже существует.")
    subject = ensure_subject(int(leader_telegram_id), None, leader_full_name)
    if subject.get("full_name") != leader_full_name:
        execute(db().table("subjects").update({"full_name": leader_full_name}).eq("id", subject["id"]),
                "Не удалось обновить ФИО председателя.")
    party = execute(db().table("parties").insert({
        "name": name, "emoji": emoji, "description": description, "color": color,
        "leader_username": subject.get("username") or str(leader_telegram_id),
        "leader_user_id": int(leader_telegram_id), "status": "active",
    }), "Не удалось создать партию.")[0]
    try:
        execute(db().table("party_members").delete().eq("user_id", int(leader_telegram_id)))
        execute(db().table("party_members").insert({
            "party_id": party["id"], "username": subject.get("username") or str(leader_telegram_id),
            "user_id": int(leader_telegram_id),
        }), "Не удалось назначить председателя партии.")
    except Exception:
        try:
            db().table("parties").delete().eq("id", party["id"]).execute()
        except Exception:
            pass
        raise
    audit(actor_id, "admin_party_created", int(leader_telegram_id), f"party:{party['id']}")
    return {"ok": True, "party": party}


def admin_add_party_member(actor_id: int, party_id: int, telegram_id: int, full_name: str) -> dict:
    """Добавляет участника в выбранную партию, переводя его из прежней."""
    if int(telegram_id) <= 0:
        raise DKDError(422, "Telegram ID должен быть положительным числом.")
    full_name = clean_name(full_name)
    if not 2 <= len(full_name) <= 120:
        raise DKDError(422, "Укажите полное ФИО участника.")
    party = one(db().table("parties").select(PARTY_COLS).eq("id", int(party_id)).limit(1))
    if not party or party.get("status") != "active":
        raise DKDError(404, "Активная партия не найдена.")
    subject = ensure_subject(int(telegram_id), None, full_name)
    if subject.get("full_name") != full_name:
        execute(db().table("subjects").update({"full_name": full_name}).eq("id", subject["id"]),
                "Не удалось обновить ФИО участника.")
    existing = rows(db().table("party_members").select("id,party_id").eq("user_id", int(telegram_id)))
    if any(int(m["party_id"]) == int(party_id) for m in existing):
        return {"ok": True, "message": "Пользователь уже состоит в этой партии."}
    for member in existing:
        execute(db().table("party_members").delete().eq("id", member["id"]), "Не удалось перевести участника.")
    username = subject.get("username") or str(telegram_id)
    execute(db().table("party_members").insert({
        "party_id": int(party_id), "username": username, "user_id": int(telegram_id)
    }), "Не удалось добавить участника.")
    audit(actor_id, "admin_party_member_added", int(telegram_id), f"party:{party_id}")
    return {"ok": True}


def admin_set_party_leader(actor_id: int, party_id: int, telegram_id: int, full_name: str) -> dict:
    party = one(db().table("parties").select(PARTY_COLS).eq("id", int(party_id)).limit(1))
    if not party or party.get("status") != "active":
        raise DKDError(404, "Активная партия не найдена.")
    result = admin_add_party_member(actor_id, party_id, telegram_id, full_name)
    subject = get_subject(int(telegram_id))
    username = (subject or {}).get("username") or str(telegram_id)
    execute(db().table("parties").update({
        "leader_user_id": int(telegram_id), "leader_username": username
    }).eq("id", int(party_id)), "Не удалось назначить председателя.")
    audit(actor_id, "admin_party_leader_changed", int(telegram_id), f"party:{party_id}")
    return {"ok": True, "message": result.get("message", "Председатель назначен.")}


def _normal_election_kind(kind: str) -> str:
    kind = "presidential" if kind == "president" else kind
    if kind not in ELECTION_TABLES:
        raise DKDError(404, "Неизвестный тип выборов.")
    return kind


def admin_add_past_election(actor_id: int, kind: str, title: str, election_date: str,
                            results: list[dict], term_start: str | None = None,
                            term_end: str | None = None) -> dict:
    """Вносит завершённую кампанию без запуска голосования."""
    kind = _normal_election_kind(kind)
    title = clean_name(title)
    if not 3 <= len(title) <= 160:
        raise DKDError(422, "Название выборов: от 3 до 160 символов.")
    date_value = parse_dt(election_date)
    if not date_value:
        try:
            date_value = datetime.fromisoformat(election_date[:10]).replace(tzinfo=timezone.utc)
        except Exception as exc:
            raise DKDError(422, "Укажите дату завершения выборов в формате YYYY-MM-DD.") from exc
    cleaned_results = {}
    for item in results:
        option = clean_name(str(item.get("option", "")))
        try:
            votes = int(item.get("votes", 0))
        except (TypeError, ValueError) as exc:
            raise DKDError(422, "Количество голосов должно быть целым числом.") from exc
        if not option or len(option) > 200 or votes < 0:
            raise DKDError(422, "Проверьте названия участников и количество голосов.")
        if option in cleaned_results:
            raise DKDError(422, "Названия участников выборов должны быть уникальными.")
        cleaned_results[option] = votes
    if len(cleaned_results) < 2:
        raise DKDError(422, "Укажите минимум два участника/варианта с результатами.")
    table = ELECTION_TABLES[kind]
    row = execute(db().table(table).insert({
        "title": title,
        "options": list(cleaned_results.keys()),
        "ends_at": date_value.astimezone(timezone.utc).isoformat(),
        "status": "closed",
        "results": cleaned_results,
    }), "Не удалось сохранить результаты прошедших выборов.")[0]
    audit(actor_id, "past_election_added", None,
          f"{kind}:{row.get('id')};term_start={term_start or ''};term_end={term_end or ''}")
    return {"ok": True, "kind": kind, "election": row}


def admin_list_election_history() -> list[dict]:
    history = []
    for kind, table in ELECTION_TABLES.items():
        for row in rows(db().table(table).select(ELECTION_COLS).order("id", desc=True).limit(100)):
            history.append({
                "id": row.get("id"), "kind": kind, "title": row.get("title"),
                "date": row.get("ends_at"), "status": row.get("status"),
                "results": row.get("results") or {}, "options": row.get("options") or [],
                "created_at": row.get("created_at"),
            })
    history.sort(key=lambda x: str(x.get("date") or x.get("created_at") or ""), reverse=True)
    return history[:100]


# ----------------------------------------------------------------------------
# Уведомления: лента Mini App + личные сообщения Telegram
# ----------------------------------------------------------------------------


def send_telegram_message(user_id: int, message: str) -> bool:
    """Отправляет личное сообщение через BOT_TOKEN; ошибка Telegram не ломает основную операцию."""
    token = env("BOT_TOKEN")
    if not token or not user_id:
        return False
    payload = json.dumps({"chat_id": int(user_id), "text": str(message or "")[:4096]}).encode("utf-8")
    request = urllib.request.Request(
        f"https://api.telegram.org/bot{token}/sendMessage",
        data=payload, headers={"Content-Type": "application/json"}, method="POST"
    )
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            result = json.loads(response.read().decode("utf-8"))
        return bool(result.get("ok"))
    except (urllib.error.URLError, TimeoutError, ValueError, OSError) as exc:
        log.warning("Telegram notification to %s failed: %s", user_id, exc)
        return False


def create_notification(user_id: int, title: str, message: str, send_telegram: bool = True) -> dict:
    """Сохраняет персональное уведомление и, если включено, дублирует его в Telegram."""
    if not user_id:
        return {}
    row = execute(db().table("notifications").insert({
        "user_id": int(user_id), "title": clean_name(title)[:160],
        "message": str(message or "")[:4000], "is_read": False,
    }), "Не удалось создать уведомление.")[0]
    if send_telegram:
        send_telegram_message(int(user_id), f"🔔 {clean_name(title)}\n\n{str(message or '')[:3900]}")
    return row


def list_notifications(user: dict) -> dict:
    items = rows(db().table("notifications").select(
        "id,title,message,is_read,created_at,read_at"
    ).eq("user_id", int(user["id"])).order("id", desc=True).limit(100))
    return {"notifications": items, "unread_count": sum(1 for item in items if not item.get("is_read"))}


def mark_notification_read(user: dict, notification_id: int) -> dict:
    uid = int(user["id"])
    item = one(db().table("notifications").select("id,is_read")
               .eq("id", int(notification_id)).eq("user_id", uid).limit(1))
    if not item:
        raise DKDError(404, "Уведомление не найдено.")
    if not item.get("is_read"):
        execute(db().table("notifications").update({
            "is_read": True, "read_at": now_iso()
        }).eq("id", int(notification_id)).eq("user_id", uid),
            "Не удалось отметить уведомление прочитанным.")
    return {"ok": True}


def mark_all_notifications_read(user: dict) -> dict:
    execute(db().table("notifications").update({
        "is_read": True, "read_at": now_iso()
    }).eq("user_id", int(user["id"])).eq("is_read", False),
        "Не удалось отметить уведомления прочитанными.")
    return {"ok": True}


# ----------------------------------------------------------------------------
# Документы, новости, публикации
# ----------------------------------------------------------------------------


def _ensure_role_based_duma_mandates() -> None:
    """Выдаёт мандат каждому пользователю с действующей ролью «Депутат»."""
    _sync_duma_term()
    role = _deputy_role_row()
    links = fetch_all(lambda: db().table("subject_roles").select("subject_id").eq("role_id", int(role["id"])))
    subject_ids = sorted({int(x["subject_id"]) for x in links if x.get("subject_id")})
    if not subject_ids:
        return
    subjects = select_in("subjects", "id,user_id,full_name", "id", subject_ids)
    start_dt = parse_dt(_setting_get(DUMA_TERM_START_KEY) or "") or utcnow()
    end_dt = parse_dt(_setting_get(DUMA_TERM_END_KEY) or "")
    now = utcnow()
    if end_dt and now >= end_dt:
        return
    if not end_dt:
        end_dt = _add_months(start_dt, 3)
        _setting_put(DUMA_TERM_START_KEY, start_dt.isoformat())
        _setting_put(DUMA_TERM_END_KEY, end_dt.isoformat())
        _setting_put(DUMA_TERM_STATUS_KEY, "active")
    election_raw = _setting_get(DUMA_TERM_ELECTION_KEY)
    election_id = int(election_raw) if election_raw and election_raw.isdigit() else 0
    title = f"Мандат депутата Госдумы · созыв №{election_id}" if election_id else "Мандат депутата Госдумы"
    for subject in subjects:
        uid = int(subject.get("user_id") or 0)
        full_name = clean_name(subject.get("full_name") or "")
        if not uid or not full_name:
            continue
        # Один мандат на пользователя и текущий созыв; не отправлять повторное уведомление.
        existing = one(db().table("decrees").select("id")
                       .eq("title", title).eq("secret_for_user_id", uid).limit(1))
        if existing:
            continue
        body = "\n".join([
            "ДЕМОКРАТИЧЕСКАЯ КОНФЕДЕРАЦИЯ ДЕБИЛОВ",
            "ГОСУДАРСТВЕННАЯ ДУМА",
            "",
            "УДОСТОВЕРЕНИЕ / МАНДАТ ДЕПУТАТА",
            "",
            f"ФИО: {full_name}.",
            "Должность: депутат Государственной Думы.",
            f"Дата выдачи: {start_dt.isoformat()}.",
            f"Действителен до: {end_dt.isoformat()}.",
            "",
            "Мандат действует в течение трёхмесячного срока и только при наличии должности депутата.",
        ])
        execute(db().table("decrees").insert({
            "title": title, "text": body, "is_secret": True, "secret_for_user_id": uid,
        }), "Не удалось выдать мандат действующему депутату.")
        try:
            create_notification(uid, "Вам выдан депутатский мандат",
                f"Вам выдан мандат депутата Госдумы. Он действует до {end_dt.isoformat()}. Документ доступен в разделе «Документы».")
        except Exception as exc:
            log.warning("Mandate notification failed for user %s: %s", uid, exc)


def _ensure_president_certificate() -> None:
    """Выдаёт удостоверение человеку с назначенной ролью «Президент»."""
    roles = _ensure_canonical_role_rows()
    role = next((r for r in roles if _role_key(r.get("name", "")) == "президент"), None)
    if not role:
        return
    links = fetch_all(lambda: db().table("subject_roles").select("subject_id").eq("role_id", int(role["id"])))
    subject_ids = sorted({int(x["subject_id"]) for x in links if x.get("subject_id")})
    if not subject_ids:
        return
    subjects = select_in("subjects", "id,user_id,full_name", "id", subject_ids)
    for subject in subjects:
        uid = int(subject.get("user_id") or 0)
        full_name = clean_name(subject.get("full_name") or "")
        if not uid or not full_name:
            continue
        term = _ensure_president_term(uid)
        if term.get("expired"):
            continue
        title = "Удостоверение Президента ДКД"
        # Одно удостоверение на пользователя; повторная проверка не создаёт дубликат.
        existing = one(db().table("decrees").select("id").eq("title", title).eq("secret_for_user_id", uid).limit(1))
        if existing:
            continue
        body = "\n".join([
            "ДЕМОКРАТИЧЕСКАЯ КОНФЕДЕРАЦИЯ ДЕБИЛОВ",
            "АДМИНИСТРАЦИЯ ПРЕЗИДЕНТА",
            "",
            "УДОСТОВЕРЕНИЕ ПРЕЗИДЕНТА",
            "",
            f"ФИО: {full_name}.",
            "Должность: Президент ДКД.",
            f"Дата выдачи: {term['start_at']}.",
            f"Действительно до: {term['end_at']}.",
            "",
            "Удостоверение действует в течение трёхмесячного президентского срока.",
        ])
        execute(db().table("decrees").insert({
            "title": title, "text": body, "is_secret": True, "secret_for_user_id": uid,
        }), "Не удалось выдать удостоверение Президента.")
        try:
            create_notification(uid, "Вам выдано удостоверение Президента",
                f"Ваше удостоверение Президента ДКД действует до {term['end_at']}. Документ доступен в разделе «Документы».")
        except Exception as exc:
            log.warning("President certificate notification failed for user %s: %s", uid, exc)


def list_documents(user: dict, admin: bool) -> dict:
    uid = int(user["id"])
    # В публичном разделе документов показываем только законы, подписанные Президентом.
    # Проекты на рассмотрении, отклонённые и отправленные на вето документы не являются действующими законами.
    laws = rows(db().table("laws").select("id,title,description,status,created_at").eq("status", "signed").order("id", desc=True).limit(50))
    _ensure_role_based_duma_mandates()
    _ensure_president_certificate()
    decrees = rows(db().table("decrees").select("id,title,text,is_secret,secret_for_user_id,created_at").order("id", desc=True).limit(300))
    visible = [d for d in decrees if not d.get("is_secret") or int(d.get("secret_for_user_id") or 0) == uid]
    mandates, president_certificate, public_decrees = [], [], []
    now = utcnow()
    _sync_duma_term()
    subject = one(db().table("subjects").select("id").eq("user_id", uid).limit(1))
    subject_id = int(subject["id"]) if subject else int(user.get("subject_id") or user.get("profile_id") or 0)
    deputy_role = _deputy_role_row()
    deputy_link = one(db().table("subject_roles").select("subject_id").eq("role_id", int(deputy_role["id"])).eq("subject_id", subject_id).limit(1)) if subject_id else None
    is_current_deputy = bool(deputy_link)
    roles = _ensure_canonical_role_rows()
    president_role = next((r for r in roles if _role_key(r.get("name", "")) == "президент"), None)
    president_link = one(db().table("subject_roles").select("subject_id").eq("role_id", int(president_role["id"])).eq("subject_id", subject_id).limit(1)) if subject_id and president_role else None
    is_current_president = bool(president_link)
    term_end = parse_dt(_setting_get(DUMA_TERM_END_KEY) or "")
    duma_active = not term_end or now < term_end
    president_end = parse_dt(_setting_get(PRESIDENT_TERM_END_KEY) or "")
    president_active = not president_end or now < president_end
    mandate_added = False
    president_certificate_added = False
    for item in visible:
        title = str(item.get("title") or "")
        body = str(item.get("text") or "")
        if title.startswith("Мандат депутата Госдумы"):
            if not is_current_deputy or not duma_active or mandate_added:
                continue
            match = re.search(r"Действителен до: ([^\n]+)", body)
            expires = parse_dt(match.group(1).strip()) if match else None
            if expires and now >= expires:
                continue
            item["mandate_status"] = "active"
            item.pop("secret_for_user_id", None)
            mandates.append(item)
            mandate_added = True
        elif title == "Удостоверение Президента ДКД":
            if not is_current_president or not president_active or president_certificate_added:
                continue
            match = re.search(r"Действительно до: ([^\n]+)", body)
            expires = parse_dt(match.group(1).strip()) if match else None
            if expires and now >= expires:
                continue
            item["certificate_status"] = "active"
            item.pop("secret_for_user_id", None)
            president_certificate.append(item)
            president_certificate_added = True
        else:
            item.pop("secret_for_user_id", None)
            public_decrees.append(item)
    return {"laws": laws, "decrees": public_decrees, "mandates": mandates,
            "president_certificate": president_certificate}

def _election_results_news(kind: str, election: dict, counts: dict[str, int], extra: str = "") -> str:
    title = clean_name(election.get("title")) or ("Выборы в Госдуму" if kind == "duma" else "Президентские выборы")
    labels = sorted(counts.items(), key=lambda item: (-int(item[1]), str(item[0]).casefold()))
    total = sum(int(votes) for _, votes in labels)
    lines = ["🗳️ ИТОГИ ВЫБОРОВ", "", title, "", "Результаты голосования:"]
    lines.extend(f"• {name} — {int(votes)} голос(ов)" for name, votes in labels)
    lines.extend(["", f"Всего учтено голосов: {total}."])
    if labels and int(labels[0][1]) > 0:
        winners = [name for name, votes in labels if int(votes) == int(labels[0][1])]
        lines.extend(["", "Победитель: " + (", ".join(winners) if len(winners) == 1 else "ничья между " + ", ".join(winners)) + "."])
    if extra:
        lines.extend(["", extra])
    return "\n".join(lines)


def _presidential_candidate_applications() -> list[dict]:
    raw = _setting_get(PRESIDENTIAL_CANDIDATES_KEY)
    if not raw:
        return []
    try:
        data = json.loads(raw)
        return data if isinstance(data, list) else []
    except (json.JSONDecodeError, TypeError):
        return []


def submit_presidential_candidate(user: dict) -> dict:
    subject = get_subject(int(user["id"]))
    if not subject or not subject.get("is_citizen"):
        raise DKDError(403, "Подать кандидатуру может только гражданин ДКД.")
    full_name = clean_name(subject.get("full_name"))
    if not full_name:
        raise DKDError(422, "Сначала заполните ФИО в профиле.")
    applications = _presidential_candidate_applications()
    current = next((x for x in applications if int(x.get("user_id") or 0) == int(user["id"])), None)
    if current and current.get("status") == "approved":
        raise DKDError(409, "Ваша кандидатура уже утверждена администратором.")
    if current and current.get("status") == "pending":
        raise DKDError(409, "Ваша кандидатура уже отправлена администратору на утверждение.")
    entry = {"user_id": int(user["id"]), "full_name": full_name, "status": "pending", "submitted_at": utcnow().isoformat()}
    applications = [x for x in applications if int(x.get("user_id") or 0) != int(user["id"])]
    applications.append(entry)
    _setting_put(PRESIDENTIAL_CANDIDATES_KEY, json.dumps(applications, ensure_ascii=False))
    audit(int(user["id"]), "presidential_candidate_submitted", None, full_name)
    try:
        create_notification(ADMIN_ID, "Новая заявка кандидата в Президенты", f"{full_name} подал(а) заявку на участие в президентских выборах. Требуется рассмотрение.")
    except Exception as exc:
        log.warning("Could not notify admin about presidential candidate: %s", exc)
    create_notification(int(user["id"]), "Заявка на пост Президента отправлена", "Ваша кандидатура отправлена администратору на рассмотрение.")
    return {"ok": True, "candidate": entry, "message": "Кандидатура отправлена администратору."}


def list_presidential_candidates() -> list[dict]:
    return sorted(_presidential_candidate_applications(), key=lambda x: str(x.get("submitted_at") or ""), reverse=True)


def review_presidential_candidate(actor_id: int, candidate_user_id: int, status: str) -> dict:
    if status not in ("approved", "rejected"):
        raise DKDError(400, "Допустимые решения: approved или rejected.")
    applications = _presidential_candidate_applications()
    entry = next((x for x in applications if int(x.get("user_id") or 0) == int(candidate_user_id)), None)
    if not entry:
        raise DKDError(404, "Заявка кандидата не найдена.")
    if status == "approved":
        candidate_name = clean_name(entry.get("full_name")).casefold()
        duplicate = next((x for x in applications
                          if int(x.get("user_id") or 0) != int(candidate_user_id)
                          and x.get("status") == "approved"
                          and clean_name(x.get("full_name")).casefold() == candidate_name), None)
        if duplicate:
            raise DKDError(409, "Нельзя утвердить двух кандидатов с одинаковым ФИО: система не сможет однозначно определить победителя выборов.")
    entry["status"] = status
    entry["reviewed_at"] = utcnow().isoformat()
    _setting_put(PRESIDENTIAL_CANDIDATES_KEY, json.dumps(applications, ensure_ascii=False))
    audit(actor_id, "presidential_candidate_" + status, int(candidate_user_id), str(entry.get("full_name") or ""))
    decision = "утверждена" if status == "approved" else "отклонена"
    create_notification(int(candidate_user_id), "Решение по кандидатуре Президента", f"Ваша кандидатура {decision} администратором.")
    return {"ok": True, "candidate": entry}


def list_news() -> list:
    return rows(db().table("news").select("id,text,created_at").order("id", desc=True).limit(30))


def _publish_election_results_news_once(actor_id: int, kind: str, election: dict, counts: dict[str, int],
                                           extra: str = "") -> bool:
    """Публикует итоговую новость идемпотентно по типу и ID выборов."""
    marker = f"[election-results:{kind}:{int(election['id'])}]"
    existing = rows(db().table("news").select("id,text").ilike("text", f"%{marker}%").limit(1))
    if existing:
        return False
    numeric_counts = {str(k): int(v) for k, v in counts.items()
                      if isinstance(v, (int, float)) and not isinstance(v, bool)}
    text = marker + "\n" + _election_results_news(kind, election, numeric_counts, extra)
    publish_news(actor_id, text)
    return True


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
    subjects = fetch_all(lambda: db().table("subjects").select("id,user_id,username,full_name,is_citizen,reputation,created_at,citizenship_granted_at").order("id"))
    _ensure_canonical_role_rows()
    roles = [r for r in rows(db().table("roles").select("id,name,emoji").order("name")) if _role_key(r.get("name", "")) in ROLE_CANONICAL or _role_key(r.get("name", "")) == "главный администратор"]
    links = fetch_all(lambda: db().table("subject_roles").select("subject_id,role_id").order("subject_id").order("role_id"))
    passports = fetch_all(lambda: db().table("passports").select("user_id,number,status").order("id"))
    pmap = {p["user_id"]: p for p in passports}
    role_by_id = {r["id"]: r for r in roles}
    ids_by_subject: dict[int, list] = {}
    for link in links:
        ids_by_subject.setdefault(link["subject_id"], []).append(link["role_id"])
    party_members = fetch_all(lambda: db().table("party_members").select("user_id,party_id").order("id"))
    parties = rows(db().table("parties").select("id,name,status"))
    party_names = {int(p["id"]): p.get("name") for p in parties if p.get("status") == "active"}
    party_by_user = {}
    for member in party_members:
        name = party_names.get(int(member["party_id"]))
        if name:
            party_by_user[int(member["user_id"])] = name
    for s in subjects:
        s["roles"] = [role_by_id[i] for i in ids_by_subject.get(s["id"], []) if i in role_by_id]
        p = pmap.get(s["user_id"])
        s["passport_number"] = p["number"] if p else None
        s["passport_status"] = p["status"] if p else None
        s["party_name"] = party_by_user.get(int(s["user_id"]))
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
        # Срок Президента начинается в момент назначения, а не при первом открытии раздела.
        if key == _role_key(president_role_name()) and subject.get("user_id") is not None:
            _ensure_president_term(int(subject["user_id"]))
    elif not assigned and link:
        execute(db().table("subject_roles").delete().eq("subject_id", subject["id"]).eq("role_id", int(role_id)), "Не удалось снять роль.")
    audit(actor_id, "role_assigned" if assigned else "role_removed", subject["user_id"], f"role:{role_id}")
    action = "назначена" if assigned else "снята"
    create_notification(int(subject["user_id"]), "Изменение вашей должности", f"Вам {action} роль «{role['name']}».")
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
    label = "подписан Президентом" if status == "signed" else "отправлен Президентом на вето"
    _notify_current_deputies("Решение Президента по законопроекту", f"Законопроект «{law.get('title', '')}» {label}.")
    return updated[0] if updated else {"id": law_id, "status": status}


# ----------------------------------------------------------------------------
# Заседания Государственной думы и рассмотрение законопроектов
# Храним созывы и голоса в audit_log, чтобы не требовать новых таблиц Supabase.
# ----------------------------------------------------------------------------

def is_deputy(user: dict) -> bool:
    _sync_duma_term()
    if is_admin(user):
        return True
    subject = get_subject(int(user["id"]))
    if not subject:
        return False
    return any(str(r.get("name", "")).strip().casefold() == "депутат"
               for r in user_roles(int(subject["id"])))


def require_deputy(user: dict) -> None:
    if not is_deputy(user):
        raise DKDError(403, "Раздел Государственной думы доступен только депутатам и администратору.")


def _duma_events() -> list[dict]:
    return fetch_all(lambda: db().table("audit_log").select(
        "id,actor_user_id,action,target_user_id,details"
    ).in_("action", [
        "duma_session_opened", "duma_session_closed", "duma_law_submitted",
        "duma_law_vote", "duma_law_passed", "duma_law_failed"
    ]).order("id"))


PRESIDENTIAL_ELECTION_ACTION = "duma_presidential_election_called"
IMPEACHMENT_PROPOSED = "president_impeachment_proposed"
IMPEACHMENT_VOTE = "president_impeachment_vote"
IMPEACHMENT_COMPLETED = "president_impeachment_completed"


def _current_president_subject() -> dict | None:
    role = one(db().table("roles").select("id,name").eq("name", president_role_name()).limit(1))
    if not role:
        return None
    links = rows(db().table("subject_roles").select("subject_id").eq("role_id", int(role["id"])))
    for link in links:
        subject = one(db().table("subjects").select("id,user_id,full_name").eq("id", int(link["subject_id"])).limit(1))
        if subject:
            return subject
    return None


def _duma_impeachment_events() -> list[dict]:
    return fetch_all(lambda: db().table("audit_log").select("id,actor_user_id,action,details")
        .in_("action", [IMPEACHMENT_PROPOSED, IMPEACHMENT_VOTE, IMPEACHMENT_COMPLETED, PRESIDENTIAL_ELECTION_ACTION]).order("id"))


def _latest_impeachment() -> dict | None:
    latest = None
    for event in _duma_impeachment_events():
        if event.get("action") == IMPEACHMENT_PROPOSED:
            try: payload = json.loads(event.get("details") or "{}")
            except (TypeError, ValueError): payload = {}
            latest = {"id": int(event["id"]), "reason": payload.get("reason", ""), "created_at": payload.get("created_at"),
                      "votes_for": 0, "votes_against": 0, "status": "open", "voters": {}}
            continue
        if not latest: continue
        try: payload = json.loads(event.get("details") or "{}")
        except (TypeError, ValueError): payload = {}
        if event.get("action") == IMPEACHMENT_VOTE and int(payload.get("motion_id", 0)) == latest["id"]:
            latest["voters"][str(int(event["actor_user_id"]))] = {"vote": payload.get("vote"), "seats": int(payload.get("seats") or 1)}
        elif event.get("action") == IMPEACHMENT_COMPLETED and int(payload.get("motion_id", 0)) == latest["id"]:
            latest["status"] = payload.get("status", "closed")
    if latest:
        latest["votes_for"] = sum(v["seats"] for v in latest["voters"].values() if v["vote"] == "for")
        latest["votes_against"] = sum(v["seats"] for v in latest["voters"].values() if v["vote"] == "against")
        parliament = public_duma_roster()
        latest["seats_total"] = int(parliament.get("seats_total") or 0)
        latest["required_seats"] = max(1, (latest["seats_total"] * 2 + 2) // 3) if latest["seats_total"] else 1
    return latest


def duma_constitutional_status(user: dict) -> dict:
    require_deputy(user)
    _sync_duma_term()
    presidential_end = _setting_value(PRESIDENT_TERM_END_KEY)
    presidential_start = _setting_value(PRESIDENT_TERM_START_KEY)
    president_uid = _setting_value(PRESIDENT_TERM_USER_KEY)
    expired = False
    if presidential_end:
        dt = parse_dt(presidential_end)
        expired = bool(dt and utcnow() >= dt)
    else:
        current = _current_president_subject()
        if current:
            term = _ensure_president_term(int(current["user_id"]))
            presidential_start, presidential_end = term["start_at"], term["end_at"]
            expired = term["expired"]
            president_uid = str(current["user_id"])
    election_already_called = False
    for event in _duma_impeachment_events():
        if event.get("action") == PRESIDENTIAL_ELECTION_ACTION:
            try: payload = json.loads(event.get("details") or "{}")
            except (TypeError, ValueError): payload = {}
            if payload.get("term_end") == presidential_end:
                election_already_called = True
    return {"president_term_start": presidential_start, "president_term_end": presidential_end,
            "president_term_expired": expired, "presidential_election_called": election_already_called,
            "impeachment": _latest_impeachment()}


def duma_call_presidential_election(actor_id: int) -> dict:
    require_deputy({"id": actor_id})
    status = duma_constitutional_status({"id": actor_id})
    if not status["president_term_expired"]:
        raise DKDError(409, "Назначить президентские выборы через парламент можно после истечения срока действующего Президента.")
    if status["presidential_election_called"]:
        raise DKDError(409, "Выборы Президента на этот срок уже назначены.")
    subjects = rows(db().table("subjects").select("full_name,is_citizen").limit(1000))
    candidates = sorted({clean_name(x.get("full_name")) for x in subjects
                         if x.get("is_citizen") and clean_name(x.get("full_name"))}, key=str.casefold)
    if len(candidates) < 2:
        raise DKDError(409, "Для президентских выборов нужны минимум два гражданина с заполненным ФИО.")
    now = utcnow()
    end = now + timedelta(days=7)
    applications = _presidential_candidate_applications()
    candidates = sorted({clean_name(x.get("full_name")) for x in applications
                         if x.get("status") == "approved" and clean_name(x.get("full_name"))}, key=str.casefold)
    if len(candidates) < 2:
        raise DKDError(409, "Для назначения выборов нужно минимум два кандидата, утверждённых администратором.")
    election = create_election(actor_id, "presidential", "Выборы Президента ДКД", candidates, end.isoformat())
    audit(actor_id, PRESIDENTIAL_ELECTION_ACTION, None, json.dumps({"election_id": int(election["id"]), "term_end": status["president_term_end"], "created_at": now.isoformat(), "candidates": candidates}, ensure_ascii=False))
    publish_news(actor_id, "🗳️ Назначены выборы Президента ДКД.\n\nВ бюллетень включены утверждённые кандидаты:\n" + "\n".join("• " + name for name in candidates) + "\n\nОкончание голосования: " + end.isoformat())
    return {"ok": True, "election": election, "candidate_count": len(candidates), "ends_at": end.isoformat()}


def duma_propose_impeachment(actor_id: int, reason: str) -> dict:
    require_deputy({"id": actor_id})
    reason = str(reason or "").strip()
    if len(reason) < 10 or len(reason) > 3000:
        raise DKDError(422, "Укажите обоснование импичмента длиной от 10 до 3000 символов.")
    current = _latest_impeachment()
    if current and current["status"] == "open":
        raise DKDError(409, "Уже идёт голосование по импичменту.")
    president = _current_president_subject()
    if not president:
        raise DKDError(409, "В базе не найден действующий Президент.")
    event = audit(actor_id, IMPEACHMENT_PROPOSED, None, json.dumps({"reason": reason, "created_at": utcnow().isoformat()}, ensure_ascii=False))
    if not event or event.get("id") is None:
        raise DKDError(502, "Не удалось зарегистрировать голосование по импичменту. Повторите попытку позже.")
    return {"ok": True, "motion_id": int(event["id"]), "status": "open"}


def duma_vote_impeachment(actor_id: int, vote: str) -> dict:
    require_deputy({"id": actor_id})
    if vote not in ("for", "against"):
        raise DKDError(422, "Голос должен быть «за» или «против».")
    motion = _latest_impeachment()
    if not motion or motion["status"] != "open":
        raise DKDError(409, "Нет открытого голосования по импичменту.")
    if str(actor_id) in motion["voters"]:
        raise DKDError(409, "Вы уже проголосовали по этому вопросу.")
    subject = get_subject(actor_id)
    if not subject:
        raise DKDError(403, "Профиль депутата не найден.")
    roster = public_duma_roster()
    # Сопоставляем депутата по Telegram ID, а не по ФИО: у разных людей могут быть одинаковые имена.
    member = next((m for m in roster.get("members", [])
                   if int(m.get("telegram_user_id") or 0) == int(actor_id)), None)
    seats = int(member.get("seats") or 1) if member else 1
    vote_event = audit(actor_id, IMPEACHMENT_VOTE, None, json.dumps({"motion_id": motion["id"], "vote": vote, "seats": seats}, ensure_ascii=False))
    if not vote_event or vote_event.get("id") is None:
        raise DKDError(502, "Не удалось сохранить ваш голос по импичменту. Попробуйте ещё раз.")
    motion = _latest_impeachment()
    if motion["votes_for"] >= int(motion.get("required_seats") or 1):
        role = one(db().table("roles").select("id,name").eq("name", president_role_name()).limit(1))
        president = _current_president_subject()
        if role and president:
            execute(db().table("subject_roles").delete().eq("subject_id", int(president["id"])).eq("role_id", int(role["id"])),
                    "Не удалось прекратить президентские полномочия после импичмента.")
        audit(actor_id, IMPEACHMENT_COMPLETED, None, json.dumps({"motion_id": motion["id"], "status": "approved", "votes_for": motion["votes_for"], "votes_against": motion["votes_against"]}, ensure_ascii=False))
        motion["status"] = "approved"
    elif motion["votes_against"] >= int(motion.get("required_seats") or 1):
        audit(actor_id, IMPEACHMENT_COMPLETED, None, json.dumps({"motion_id": motion["id"], "status": "rejected", "votes_for": motion["votes_for"], "votes_against": motion["votes_against"]}, ensure_ascii=False))
        motion["status"] = "rejected"
    return {"ok": True, "impeachment": motion}


def duma_list(user: dict) -> dict:
    require_deputy(user)
    events = _duma_events()
    sessions = []
    for ev in events:
        if ev.get("action") == "duma_session_opened":
            try:
                data = json.loads(ev.get("details") or "{}")
            except (TypeError, ValueError):
                data = {}
            sessions.append({"id": int(ev["id"]), "title": data.get("title", "Заседание Государственной думы"),
                             "created_by": ev.get("actor_user_id"), "created_at": data.get("created_at"),
                             "closed": False, "laws": []})
        elif ev.get("action") == "duma_session_closed":
            try:
                data = json.loads(ev.get("details") or "{}")
            except (TypeError, ValueError):
                data = {}
            for s in sessions:
                if s["id"] == int(data.get("session_id", 0)):
                    s["closed"] = True
    by_session = {s["id"]: s for s in sessions}
    laws = rows(db().table("laws").select("id,title,description,status,created_at").order("id", desc=True).limit(100))
    law_events = [ev for ev in events if ev.get("action") in ("duma_law_submitted", "duma_law_vote", "duma_law_passed", "duma_law_failed")]
    submitted = {}
    votes = {}
    for ev in law_events:
        try:
            data = json.loads(ev.get("details") or "{}")
        except (TypeError, ValueError):
            continue
        lid = int(data.get("law_id", 0))
        if ev["action"] == "duma_law_submitted":
            submitted[lid] = {"session_id": int(data.get("session_id", 0)), "author_id": int(ev["actor_user_id"])}
        elif ev["action"] == "duma_law_vote":
            votes.setdefault(lid, {})[int(ev["actor_user_id"])] = data.get("vote")
    for law in laws:
        lid = int(law["id"])
        meta = submitted.get(lid)
        if not meta:
            continue
        law["session_id"] = meta["session_id"]
        law["author_id"] = meta["author_id"]
        law["votes_for"] = sum(v == "for" for v in votes.get(lid, {}).values())
        law["votes_against"] = sum(v == "against" for v in votes.get(lid, {}).values())
        law["has_voted"] = int(user["id"]) in votes.get(lid, {})
        law["can_vote"] = law["status"] == "under_duma_review" and not law["has_voted"]
        session = by_session.get(meta["session_id"])
        if session:
            session["laws"].append(law)
    return {"sessions": sessions[::-1], "laws": [law for law in laws if int(law["id"]) in submitted]}


def _notify_current_deputies(title: str, message: str, exclude_user_id: int | None = None) -> int:
    """Рассылает персональные уведомления всем пользователям с ролью «Депутат»."""
    try:
        role = _deputy_role_row()
        links = fetch_all(lambda: db().table("subject_roles").select("subject_id").eq("role_id", int(role["id"])))
        subject_ids = sorted({int(row["subject_id"]) for row in links if row.get("subject_id")})
        subjects = select_in("subjects", "id,user_id,full_name", "id", subject_ids)
    except Exception as exc:
        log.warning("Could not resolve deputies for notification: %s", exc)
        return 0
    sent = 0
    for subject in subjects:
        try:
            uid = int(subject.get("user_id") or 0)
            if not uid or (exclude_user_id is not None and uid == int(exclude_user_id)):
                continue
            create_notification(uid, title, message)
            sent += 1
        except Exception as exc:
            log.warning("Deputy notification failed: %s", exc)
    return sent

def duma_open_session(actor_id: int, title: str) -> dict:
    require_deputy({"id": int(actor_id)})
    title = clean_name(title)
    if not 2 <= len(title) <= 160:
        raise DKDError(422, "Название заседания должно содержать от 2 до 160 символов.")
    created_at = now_iso()
    row = execute(db().table("audit_log").insert({
        "actor_user_id": int(actor_id), "action": "duma_session_opened",
        "target_user_id": None, "details": json.dumps({"title": title, "created_at": created_at}, ensure_ascii=False)
    }), "Не удалось открыть заседание Государственной думы.")[0]
    _notify_current_deputies("Открыто заседание Госдумы", f"Началось заседание Государственной думы: «{title}». Откройте Mini App → раздел Госдумы.")
    return {"id": int(row["id"]), "title": title, "created_at": created_at, "closed": False, "laws": []}


def _is_duma_session_closed(events: list[dict], session_id: int) -> bool:
    """Сверяет точный session_id в JSON, а не ищет число подстрокой."""
    for event in events:
        if event.get("action") != "duma_session_closed":
            continue
        try:
            payload = json.loads(event.get("details") or "{}")
        except (TypeError, ValueError):
            continue
        try:
            if int(payload.get("session_id", 0)) == int(session_id):
                return True
        except (TypeError, ValueError):
            continue
    return False


def duma_close_session(actor_id: int, session_id: int) -> dict:
    require_deputy({"id": int(actor_id)})
    events = _duma_events()
    opened = next((e for e in events if e.get("action") == "duma_session_opened" and int(e["id"]) == int(session_id)), None)
    if not opened:
        raise DKDError(404, "Заседание не найдено.")
    if _is_duma_session_closed(events, session_id):
        raise DKDError(409, "Заседание уже закрыто.")
    close_event = audit(actor_id, "duma_session_closed", None, json.dumps({"session_id": int(session_id)}, ensure_ascii=False))
    if not close_event or close_event.get("id") is None:
        raise DKDError(502, "Не удалось сохранить закрытие заседания. Попробуйте ещё раз.")
    try:
        payload = json.loads(opened.get("details") or "{}")
    except (TypeError, ValueError):
        payload = {}
    session_title = payload.get("title", "Заседание Государственной думы")
    _notify_current_deputies("Заседание Госдумы завершено", f"Заседание «{session_title}» закрыто.")
    return {"ok": True, "session_id": int(session_id), "closed": True}


def duma_submit_law(actor_id: int, session_id: int, title: str, description: str) -> dict:
    title = clean_name(title)
    description = str(description or "").strip()
    if not 2 <= len(title) <= 200:
        raise DKDError(422, "Название законопроекта должно содержать от 2 до 200 символов.")
    if not description or len(description) > 20000:
        raise DKDError(422, "Текст законопроекта обязателен и не должен превышать 20000 символов.")
    listing = duma_list({"id": actor_id})
    session = next((s for s in listing["sessions"] if s["id"] == int(session_id)), None)
    if not session:
        raise DKDError(404, "Заседание не найдено.")
    if session["closed"]:
        raise DKDError(409, "Нельзя подать законопроект на закрытое заседание.")
    law = execute(db().table("laws").insert({"title": title, "description": description, "status": "under_duma_review"}),
                  "Не удалось подать законопроект.")[0]
    submission_event = audit(actor_id, "duma_law_submitted", None, json.dumps({"law_id": int(law["id"]), "session_id": int(session_id)}, ensure_ascii=False))
    if not submission_event or submission_event.get("id") is None:
        try:
            db().table("laws").delete().eq("id", int(law["id"])).execute()
        except Exception as cleanup_exc:  # noqa: BLE001
            log.error("Could not roll back law %s after submission audit failure: %s", law.get("id"), cleanup_exc)
        raise DKDError(502, "Не удалось зарегистрировать законопроект в заседании. Попробуйте ещё раз.")
    _notify_current_deputies("Началось голосование по законопроекту",
        f"На заседании «{session.get('title', 'Государственная Дума')}» внесён законопроект «{title}». Голосование открыто: выберите «За» или «Против» в разделе Госдумы.")
    return law


def duma_vote_law(actor_id: int, law_id: int, vote: str) -> dict:
    require_deputy({"id": int(actor_id)})
    if vote not in ("for", "against"):
        raise DKDError(422, "Выберите голос «за» или «против».")
    law = one(db().table("laws").select("id,title,status").eq("id", int(law_id)).limit(1))
    if not law or law.get("status") != "under_duma_review":
        raise DKDError(404, "Законопроект не найден или голосование уже завершено.")
    events = _duma_events()
    details = []
    for ev in events:
        if ev.get("action") in ("duma_law_submitted", "duma_law_vote"):
            try: details.append((ev, json.loads(ev.get("details") or "{}")))
            except (TypeError, ValueError): pass
    submitted = next((d for e,d in details if e.get("action") == "duma_law_submitted" and int(d.get("law_id",0)) == int(law_id)), None)
    if not submitted:
        raise DKDError(403, "Этот законопроект не относится к заседанию Государственной думы.")
    session = next((e for e in events if e.get("action") == "duma_session_opened" and int(e["id"]) == int(submitted.get("session_id",0))), None)
    if not session:
        raise DKDError(404, "Заседание не найдено.")
    session_closed = _is_duma_session_closed(events, int(submitted.get("session_id", 0)))
    if session_closed:
        raise DKDError(409, "Заседание закрыто.")
    if any(e.get("action") == "duma_law_vote" and int(d.get("law_id",0)) == int(law_id) and int(e["actor_user_id"]) == int(actor_id) for e,d in details):
        raise DKDError(409, "Вы уже голосовали по этому законопроекту.")
    vote_event = audit(actor_id, "duma_law_vote", None, json.dumps({"law_id": int(law_id), "vote": vote}, ensure_ascii=False))
    if not vote_event or vote_event.get("id") is None:
        raise DKDError(502, "Не удалось сохранить ваш голос. Попробуйте ещё раз.")
    votes = [d.get("vote") for e,d in details if e.get("action") == "duma_law_vote" and int(d.get("law_id",0)) == int(law_id)]
    votes.append(vote)
    yes, no = votes.count("for"), votes.count("against")
    total_deputies = fetch_all(lambda: db().table("roles").select("id,name").ilike("name", "Депутат"))
    deputy_role_ids = [int(r["id"]) for r in total_deputies]
    deputies = []
    for role_id in deputy_role_ids:
        links = fetch_all(lambda: db().table("subject_roles").select("subject_id").eq("role_id", role_id))
        deputies.extend(int(x["subject_id"]) for x in links)
    majority_reached = yes > no and yes > (len(set(deputies)) / 2)
    if majority_reached:
        execute(db().table("laws").update({"status": "awaiting_president"}).eq("id", int(law_id)), "Не удалось передать закон Президенту.")
        audit(actor_id, "duma_law_passed", None, json.dumps({"law_id": int(law_id), "votes_for": yes, "votes_against": no}, ensure_ascii=False))
        status = "awaiting_president"
    elif yes + no >= len(set(deputies)):
        execute(db().table("laws").update({"status": "rejected_by_duma"}).eq("id", int(law_id)), "Не удалось завершить голосование.")
        audit(actor_id, "duma_law_failed", None, json.dumps({"law_id": int(law_id), "votes_for": yes, "votes_against": no}, ensure_ascii=False))
        status = "rejected_by_duma"
    else:
        status = "under_duma_review"
    if status == "awaiting_president":
        _notify_current_deputies("Законопроект принят Госдумой",
            f"Законопроект «{law.get('title', '')}» принят большинством и направлен Президенту на рассмотрение. Результат: за — {yes}, против — {no}.")
    elif status == "rejected_by_duma":
        _notify_current_deputies("Законопроект отклонён",
            f"Голосование по законопроекту «{law.get('title', '')}» завершено. Законопроект отклонён. Результат: за — {yes}, против — {no}.")
    return {"ok": True, "status": status, "votes_for": yes, "votes_against": no, "deputies_total": len(set(deputies))}


# ----------------------------------------------------------------------------
# Самодиагностика схемы БД (только для администратора)
# ----------------------------------------------------------------------------

def public_duma_roster() -> dict:
    """Публичный состав парламента из последнего завершённого созыва.
    Сначала читаем elected_deputies из результатов выборов; legacy-таблица
    duma_initial_roster используется только для старых вручную внесённых данных.
    """
    if _setting_get(DUMA_TERM_STATUS_KEY) == "dissolved":
        return {"formed": False, "seats_total": 0, "seats_occupied": 0, "members": [], "parties": [], "term_start": _setting_get(DUMA_TERM_START_KEY), "term_end": _setting_get(DUMA_TERM_END_KEY), "election_completed_at": None, "dissolved": True}
    data = []
    term_start = term_end = completed_at = None
    stored_election_seats = 0
    # Основной источник — результаты настоящих выборов в Госдуму.
    try:
        elections = rows(db().table("duma_elections").select(ELECTION_COLS)
                         .order("id", desc=True).limit(30))
        for election in elections:
            result = election.get("results") if isinstance(election.get("results"), dict) else {}
            deputies = result.get("elected_deputies")
            if not isinstance(deputies, list) or not deputies:
                # Старые результаты могут содержать только количество голосов.
                # Восстанавливаем отображаемый состав из сохранённых партийных списков.
                lists = duma_party_lists(int(election["id"]))
                metadata_keys = {"parliament_seats", "parliament_term_start", "parliament_term_end",
                                 "elected_deputies", "parliament_term_status"}
                counts = {str(k): int(v) for k, v in result.items()
                          if str(k) not in metadata_keys and isinstance(v, (int, float)) and not isinstance(v, bool)}
                valid = [(name, votes, next((p for p in lists.values()
                          if str(p.get("party_name", "")).casefold() == name.casefold()), None))
                         for name, votes in counts.items()]
                # Место = один голос. Голоса партий без списка также входят в размер
                # парламента, но их места остаются вакантными.
                total = sum(max(0, v) for v in counts.values())
                if total <= 0:
                    continue
                parliament_seats = total
                valid = [(name, votes, party) for name, votes, party in valid
                         if party and party.get("candidates") and votes > 0]
                seat_map = {name: votes for name, votes, _ in valid}
                deputies = []
                for name, votes, party in valid:
                    candidates = party.get("candidates") or []
                    candidate_user_ids = party.get("candidate_user_ids") or []
                    leader_user_id = int(party.get("leader_user_id") or 0)
                    for idx, full_name in enumerate(candidates[:min(len(candidates), seat_map[name])]):
                        try:
                            candidate_uid = int(candidate_user_ids[idx]) if idx < len(candidate_user_ids) else 0
                        except (TypeError, ValueError):
                            candidate_uid = 0
                        deputies.append({"full_name": full_name, "party_id": party.get("party_id"),
                            "party_name": name, "party_emoji": party.get("party_emoji") or "🎭", "votes": votes,
                            "party_seats": seat_map[name], "seats": 1,
                            "is_party_leader": bool(candidate_uid and candidate_uid == leader_user_id),
                            "telegram_user_id": candidate_uid or None})
                if not deputies:
                    continue
            term_start = result.get("parliament_term_start")
            term_end = result.get("parliament_term_end")
            try:
                stored_election_seats = int(result.get("parliament_seats") or 0)
            except (TypeError, ValueError):
                stored_election_seats = 0
            if not stored_election_seats and not result.get("elected_deputies"):
                stored_election_seats = sum(max(0, v) for v in counts.values())
            completed_at = election.get("updated_at") or election.get("created_at")
            data = [{
                "full_name": x.get("full_name"),
                "party_id": int(x["party_id"]) if x.get("party_id") else None,
                "party_name": x.get("party_name") or "Без партии",
                "party_emoji": x.get("party_emoji") or "🎭",
                "party_votes": int(x.get("votes") or 0),
                "party_seats": int(x.get("party_seats") or x.get("votes") or 0),
                "party_vote_percent": 0,
                "elected_to_duma": True,
                "seats": 1,
                "is_party_leader": bool(x.get("is_party_leader")),
                "parliament_term_start": term_start,
                "parliament_term_end": term_end,
                "telegram_user_id": x.get("telegram_user_id"),
            } for x in deputies]
            break
    except Exception as exc:
        log.warning("Election-based parliament roster unavailable: %s", exc)

    # Восстановление старого результата, где сохранены только голоса,
    # а партийные списки для той кампании отсутствуют. Берём фактический
    # состав партий из party_members; действующего Президента исключаем.
    if not data:
        try:
            elections = rows(db().table("duma_elections").select(ELECTION_COLS)
                             .eq("status", "closed").order("id", desc=True).limit(30))
            election = next((e for e in elections
                             if isinstance(e.get("results"), dict)
                             and any(isinstance(v, (int, float)) and not isinstance(v, bool)
                                     for v in e["results"].values())), None)
            if election:
                result = election.get("results") or {}
                metadata_keys = {"parliament_seats", "parliament_term_start", "parliament_term_end",
                                 "elected_deputies", "parliament_term_status"}
                counts = {str(k): int(v) for k, v in result.items()
                          if str(k) not in metadata_keys and isinstance(v, (int, float)) and not isinstance(v, bool)}
                total_votes = sum(max(0, v) for v in counts.values())
                if total_votes > 0:
                    parties = rows(db().table("parties").select(PARTY_COLS).eq("status", "active"))
                    party_by_name = {clean_name(p.get("name")).casefold(): p for p in parties}
                    subjects = rows(db().table("subjects").select("id,user_id,full_name"))
                    subject_by_user = {int(s["user_id"]): s for s in subjects if s.get("user_id") is not None}
                    role_rows = rows(db().table("roles").select("id,name"))
                    president_role_ids = [int(r["id"]) for r in role_rows
                                           if _role_key(r.get("name", "")) == "президент"]
                    president_subject_ids = set()
                    if president_role_ids:
                        links = fetch_all(lambda: db().table("subject_roles").select("subject_id")
                                          .in_("role_id", president_role_ids))
                        president_subject_ids = {int(x["subject_id"]) for x in links}
                    members = fetch_all(lambda: db().table("party_members")
                                        .select("id,party_id,user_id,username").order("id"))
                    party_members = {}
                    for member in members:
                        subject = subject_by_user.get(int(member.get("user_id") or 0))
                        if not subject or int(subject["id"]) in president_subject_ids:
                            continue
                        party_members.setdefault(int(member["party_id"]), []).append(subject)
                    valid = []
                    for party_name, votes in counts.items():
                        party = party_by_name.get(party_name.casefold())
                        if party and votes > 0:
                            candidates = party_members.get(int(party["id"]), [])
                            if candidates:
                                valid.append((party_name, votes, party, candidates))
                    if valid:
                        parliament_seats = total_votes
                        # Каждый голос — место. Если список короче числа голосов,
                        # недостающие места остаются вакантными.
                        seat_map = {name: votes for name, votes, _, _ in valid}
                        for name, votes, party, candidates, _ in raw:
                            party_seats = seat_map[name]
                            count = min(len(candidates), party_seats)
                            if count <= 0:
                                continue
                            for subject in candidates[:count]:
                                data.append({
                                    "full_name": subject.get("full_name") or "Участник",
                                    "party_id": int(party["id"]),
                                    "party_name": name,
                                    "party_emoji": party.get("emoji") or "🎭",
                                    "party_votes": votes,
                                    "party_vote_percent": round(votes * 100 / total_votes, 2),
                                    "elected_to_duma": True,
                                    "seats": 1,
                                    "is_party_leader": int(subject.get("user_id") or 0) == int(party.get("leader_user_id") or -1),
                                    "telegram_user_id": int(subject["user_id"]),
                                    "parliament_term_start": result.get("parliament_term_start"),
                                    "parliament_term_end": result.get("parliament_term_end"),
                                })
                        term_start = result.get("parliament_term_start")
                        term_end = result.get("parliament_term_end")
                        stored_election_seats = total_votes
                        completed_at = election.get("updated_at") or election.get("created_at")
        except Exception as exc:
            log.warning("Could not rebuild legacy Duma roster from current party members: %s", exc)

    # Совместимость с ранее внесённым первым созывом.
    if not data:
        try:
            data = rows(db().table("duma_initial_roster").select(
                "full_name,party_name,party_emoji,party_votes,party_vote_percent,elected_to_duma,seats,is_party_leader,election_completed_at,parliament_term_start,parliament_term_end"
            ))
        except Exception as exc:
            log.warning("Initial parliament roster unavailable: %s", exc)
            data = []

    # Совместимость со старым составом: каждое лицо может занимать только одно место.
    # Повторные строки одного Telegram-пользователя/ФИО не создают лишние мандаты.
    normalized_data = []
    seen_members = set()
    for row in data:
        if not row.get("elected_to_duma", True):
            normalized_data.append(row)
            continue
        user_id = row.get("telegram_user_id")
        identity = ("uid", int(user_id)) if user_id else ("name", clean_name(row.get("full_name")).casefold())
        if identity[1] in ("", 0) or identity in seen_members:
            continue
        seen_members.add(identity)
        row["seats"] = 1
        normalized_data.append(row)
    data = normalized_data
    elected = [r for r in data if r.get("elected_to_duma", True)]
    total_party_votes = sum({str(r.get("party_name") or "Без партии"): int(r.get("party_votes") or 0)
                             for r in elected}.values())
    for row in elected:
        if row.get("party_votes") is not None:
            row["party_vote_percent"] = round(int(row.get("party_votes") or 0) * 100 / total_party_votes, 2) if total_party_votes else 0
    parties = {}
    for row in data:
        name = str(row.get("party_name") or "Без партии")
        p = parties.setdefault(name, {"name": name, "party_id": int(row["party_id"]) if row.get("party_id") else None,
            "emoji": row.get("party_emoji") or "🎭", "color": "#4169e1",
            "votes": int(row.get("party_votes") or 0),
            "vote_percent": float(row.get("party_vote_percent") or 0),
            "seats": int(row.get("party_seats") or row.get("party_votes") or row.get("seats") or 0), "members": []})
        try:
            party_query = db().table("parties").select("id,name,color,emoji")
            live_party = (one(party_query.eq("id", int(row["party_id"])).limit(1))
                          if row.get("party_id") else one(party_query.eq("name", name).limit(1)))
            if live_party:
                p["color"] = live_party.get("color") or p["color"]
                p["emoji"] = live_party.get("emoji") or p["emoji"]
        except Exception:
            pass
        if row.get("elected_to_duma", True):
            seats = int(row.get("seats") or 1)
            if not row.get("party_seats") and not row.get("party_votes"):
                p["seats"] += seats
            p["members"].append({"full_name": row.get("full_name"), "seats": seats,
                                 "is_party_leader": bool(row.get("is_party_leader"))})
    first = data[0] if data else {}
    total_occupied = sum(int(r.get("seats") or 0) for r in elected)
    election_seats = stored_election_seats
    seats_total = election_seats or total_party_votes or total_occupied
    return {"formed": bool(elected), "seats_total": seats_total, "seats_occupied": total_occupied,
        "members": [{"full_name": r.get("full_name"), "party_id": r.get("party_id"),
                     "party_name": r.get("party_name"), "seats": int(r.get("seats") or 0),
                     "telegram_user_id": int(r["telegram_user_id"]) if r.get("telegram_user_id") else None}
                    for r in elected],
        "parties": list(parties.values()),
        "term_start": term_start or first.get("parliament_term_start"),
        "term_end": term_end or first.get("parliament_term_end"),
        "election_completed_at": completed_at or first.get("election_completed_at")}


REQUIRED_SCHEMA = {
    # SUBJECT_COLS уже содержит avatar_emoji; не дублируем имя столбца,
    # иначе диагностика ошибочно помечает корректную таблицу как сломанную.
    "subjects": SUBJECT_COLS,
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
