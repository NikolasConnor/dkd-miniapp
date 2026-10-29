import asyncio
import json
from html import escape as html_escape
import logging
import os
import re
from datetime import datetime, timedelta, timezone
from difflib import SequenceMatcher
from typing import Any

import asyncpg
from aiogram import Bot, Dispatcher, F
from aiogram.client.default import DefaultBotProperties
from aiogram.enums import ParseMode
from aiogram.exceptions import TelegramAPIError
from aiogram.filters import Command, StateFilter
from aiogram.fsm.context import FSMContext
from aiogram.fsm.state import State, StatesGroup
from aiogram.types import CallbackQuery, InlineKeyboardButton, InlineKeyboardMarkup, Message
from aiogram.utils.keyboard import InlineKeyboardBuilder

BOT_TOKEN = os.getenv("BOT_TOKEN", "").strip()
DATABASE_URL = os.getenv("DATABASE_URL", "").strip()
ADMIN_ID = 7934244888
MSK = timezone(timedelta(hours=3), name="MSK")
VIDEO_FILE_ID = "BAACAgIAAxkBAANLasIfw2IcWeZwXgiPqm4Ne1fHeRAAAsapAAKmCxFKxM0YkvpfMeA9BA"
SECRET_WORD = "Бог Эфиопии"
REMINDER_INTERVAL_SECONDS = 6 * 60 * 60
CANDIDATES = {
    "1": "Блошихин Кирилл Вадимович",
    "2": "Косарев Сергей Сергеевич",
    "3": "Проселков Кирилл Романович",
    "4": "Горохов Никита Александрович (Доп)",
    "5": "ПРОТИВ ВСЕХ",
}
CANDIDATE_FULL_NAMES = {
    "1": "Блошихин Кирилл Вадимович",
    "2": "Косарев Сергей Сергеевич",
    "3": "Проселков Кирилл Романович",
    "4": "Горохов Никита Александрович",
}

DEFAULT_ROLES = [
    ("president", "Президент", "👑", 1),
    ("deputy", "Депутат ГосДумы", "🏛", 100),
    ("minister", "Министр", "📌", 20),
    ("deputy_prime_minister", "Вице-премьер", "🏛", 1),
    ("prime_minister", "Премьер-министр", "🏛", 1),
    ("central_bank_head", "Глава Центрального банка", "🏦", 1),
    ("presidential_candidate", "Кандидат в Президенты", "🗳", 5),
    ("party_leader", "Лидер партии", "🎖", 100),
    ("citizen", "Гражданин", "👤", 100000),
]


def to_db_dt(dt: datetime) -> datetime:
    """Convert aware datetime to naive UTC for TIMESTAMP WITHOUT TIME ZONE columns."""
    if dt.tzinfo is None:
        return dt
    return dt.astimezone(timezone.utc).replace(tzinfo=None)


def from_db_dt(dt: datetime | None) -> datetime | None:
    """Read naive UTC database datetime and return it in Moscow time."""
    if dt is None:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(MSK)


def normalize_username(username: str | None, fallback_id: int | None = None) -> str:
    value = (username or (f"id{fallback_id}" if fallback_id is not None else "")).strip()
    return value.lstrip("@").lower()


def keyboard(rows: list[list[tuple[str, str]]]) -> InlineKeyboardMarkup:
    builder = InlineKeyboardBuilder()
    for row in rows:
        builder.row(*(InlineKeyboardButton(text=label, callback_data=data) for label, data in row))
    return builder.as_markup()


class Form(StatesGroup):
    registration_word = State()
    registration_name = State()
    change_name = State()
    create_party_name = State()
    create_party_emoji = State()
    create_party_description = State()
    join_party = State()
    party_list = State()
    law_title = State()
    law_description = State()
    decree_title = State()
    decree_text = State()
    decree_secret = State()
    decree_recipient = State()
    address_text = State()
    minister_username = State()
    election_duration = State()
    duma_duration = State()
    meeting_text = State()
    broadcast_text = State()
    admin_application_decision = State()
    admin_role_target = State()
    admin_reputation_target = State()
    admin_reputation_value = State()
    admin_leader_party = State()
    admin_leader_user = State()


async def init_db(pool: asyncpg.Pool) -> None:
    """Create all application tables. Datetimes are stored as naive UTC timestamps."""
    ddl = [
        """CREATE TABLE IF NOT EXISTS subjects (
            id BIGSERIAL PRIMARY KEY, user_id BIGINT UNIQUE NOT NULL,
            username TEXT NOT NULL, full_name TEXT NOT NULL,
            is_citizen BOOLEAN NOT NULL DEFAULT FALSE, reputation INTEGER NOT NULL DEFAULT 0,
            created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'))""",
        """CREATE TABLE IF NOT EXISTS admin_sessions (
            user_id BIGINT PRIMARY KEY, is_active BOOLEAN NOT NULL DEFAULT FALSE)""",
        """CREATE TABLE IF NOT EXISTS roles (
            id BIGSERIAL PRIMARY KEY, code TEXT UNIQUE NOT NULL, name TEXT NOT NULL,
            emoji TEXT NOT NULL DEFAULT '', max_holders INTEGER NOT NULL DEFAULT 0)""",
        """CREATE TABLE IF NOT EXISTS subject_roles (
            subject_id BIGINT NOT NULL REFERENCES subjects(id) ON DELETE CASCADE,
            role_id BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
            PRIMARY KEY(subject_id, role_id))""",
        """CREATE TABLE IF NOT EXISTS parties (
            id BIGSERIAL PRIMARY KEY, name TEXT UNIQUE NOT NULL, emoji TEXT NOT NULL DEFAULT '🎭',
            description TEXT NOT NULL DEFAULT '', leader_username TEXT, leader_user_id BIGINT, status TEXT NOT NULL DEFAULT 'active',
            created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'))""",
        """CREATE TABLE IF NOT EXISTS party_members (
            party_id BIGINT NOT NULL REFERENCES parties(id) ON DELETE CASCADE,
            username TEXT PRIMARY KEY, user_id BIGINT, joined_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'))""",
        """CREATE TABLE IF NOT EXISTS party_lists (
            party_id BIGINT NOT NULL REFERENCES parties(id) ON DELETE CASCADE,
            election_id BIGINT NOT NULL, username TEXT NOT NULL, user_id BIGINT,
            PRIMARY KEY(party_id, election_id, username))""",
        """CREATE TABLE IF NOT EXISTS applications (
            id BIGSERIAL PRIMARY KEY, type TEXT NOT NULL, author_username TEXT NOT NULL, author_user_id BIGINT,
            data JSONB NOT NULL DEFAULT '{}'::jsonb, status TEXT NOT NULL DEFAULT 'pending',
            created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'),
            decided_at TIMESTAMP WITHOUT TIME ZONE)""",
        """CREATE TABLE IF NOT EXISTS laws (
            id BIGSERIAL PRIMARY KEY, title TEXT NOT NULL, description TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'duma', created_by BIGINT, created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'))""",
        """CREATE TABLE IF NOT EXISTS law_votes (
            law_id BIGINT NOT NULL REFERENCES laws(id) ON DELETE CASCADE,
            user_id BIGINT NOT NULL, vote BOOLEAN NOT NULL,
            PRIMARY KEY(law_id, user_id))""",
        """CREATE TABLE IF NOT EXISTS decrees (
            id BIGSERIAL PRIMARY KEY, title TEXT NOT NULL, text TEXT NOT NULL,
            is_secret BOOLEAN NOT NULL DEFAULT FALSE, secret_for TEXT, secret_for_user_id BIGINT,
            created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'))""",
        """CREATE TABLE IF NOT EXISTS news (
            id BIGSERIAL PRIMARY KEY, text TEXT NOT NULL,
            created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'))""",
        """CREATE TABLE IF NOT EXISTS duma_elections (
            id BIGSERIAL PRIMARY KEY, ends_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
            status TEXT NOT NULL DEFAULT 'active', results JSONB,
            created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'))""",
        """CREATE TABLE IF NOT EXISTS duma_votes (
            id BIGSERIAL PRIMARY KEY, election_id BIGINT NOT NULL REFERENCES duma_elections(id) ON DELETE CASCADE,
            voter_username TEXT NOT NULL, party_id BIGINT NOT NULL REFERENCES parties(id) ON DELETE CASCADE,
            created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'),
            UNIQUE(election_id, voter_username))""",
        """CREATE TABLE IF NOT EXISTS party_list_reminders (
            party_id BIGINT NOT NULL REFERENCES parties(id) ON DELETE CASCADE,
            election_id BIGINT NOT NULL REFERENCES duma_elections(id) ON DELETE CASCADE,
            last_reminded_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
            PRIMARY KEY(party_id,election_id))""",
        """CREATE TABLE IF NOT EXISTS law_stage_votes (
            law_id BIGINT NOT NULL REFERENCES laws(id) ON DELETE CASCADE,
            user_id BIGINT NOT NULL,
            stage TEXT NOT NULL,
            vote BOOLEAN NOT NULL,
            created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'),
            PRIMARY KEY(law_id,user_id,stage))""",
        """CREATE TABLE IF NOT EXISTS role_terms (
            role_code TEXT NOT NULL,
            user_id BIGINT NOT NULL REFERENCES subjects(user_id) ON DELETE CASCADE,
            ends_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
            PRIMARY KEY(role_code,user_id))""",
        """CREATE TABLE IF NOT EXISTS pres_elections (
            id BIGSERIAL PRIMARY KEY, ends_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
            status TEXT NOT NULL DEFAULT 'active', results JSONB,
            created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'))""",
        """CREATE TABLE IF NOT EXISTS votes (
            id BIGSERIAL PRIMARY KEY, election_id BIGINT NOT NULL REFERENCES pres_elections(id) ON DELETE CASCADE,
            user_id BIGINT NOT NULL, username TEXT NOT NULL, voter_name TEXT NOT NULL,
            candidate_id TEXT NOT NULL, created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'),
            UNIQUE(election_id, user_id))""",
        """CREATE TABLE IF NOT EXISTS audit_log (
            id BIGSERIAL PRIMARY KEY, actor_id BIGINT NOT NULL, action TEXT NOT NULL,
            target TEXT NOT NULL DEFAULT '', details JSONB NOT NULL DEFAULT '{}'::jsonb,
            created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'))""",
        """CREATE TABLE IF NOT EXISTS duma_meetings (
            id BIGSERIAL PRIMARY KEY, created_by BIGINT NOT NULL, agenda TEXT NOT NULL,
            deadline TIMESTAMP WITHOUT TIME ZONE NOT NULL, status TEXT NOT NULL DEFAULT 'open',
            created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC'))""",
    ]
    async with pool.acquire() as conn:
        async with conn.transaction():
            for statement in ddl:
                await conn.execute(statement)
            # Stable party-leader identity: usernames can change or be absent, Telegram IDs do not.
            await conn.execute("ALTER TABLE parties ADD COLUMN IF NOT EXISTS leader_user_id BIGINT")
            await conn.execute("ALTER TABLE party_members ADD COLUMN IF NOT EXISTS user_id BIGINT")
            await conn.execute("ALTER TABLE party_lists ADD COLUMN IF NOT EXISTS user_id BIGINT")
            await conn.execute("ALTER TABLE applications ADD COLUMN IF NOT EXISTS author_user_id BIGINT")
            await conn.execute("ALTER TABLE decrees ADD COLUMN IF NOT EXISTS secret_for_user_id BIGINT")
            await conn.execute("UPDATE party_members pm SET user_id=s.user_id FROM subjects s WHERE pm.user_id IS NULL AND LOWER(pm.username)=LOWER(s.username)")
            await conn.execute("UPDATE party_lists pl SET user_id=s.user_id FROM subjects s WHERE pl.user_id IS NULL AND LOWER(pl.username)=LOWER(s.username)")
            await conn.execute("UPDATE applications a SET author_user_id=s.user_id FROM subjects s WHERE a.author_user_id IS NULL AND LOWER(a.author_username)=LOWER(s.username)")
            await conn.execute("UPDATE decrees d SET secret_for_user_id=s.user_id FROM subjects s WHERE d.is_secret=TRUE AND d.secret_for_user_id IS NULL AND LOWER(COALESCE(d.secret_for,''))=LOWER(s.username)")
            await conn.execute("DELETE FROM party_members a USING party_members b WHERE a.user_id IS NOT NULL AND a.user_id=b.user_id AND a.ctid>b.ctid")
            await conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS party_members_user_id_uq ON party_members(user_id) WHERE user_id IS NOT NULL")
            await conn.execute("CREATE INDEX IF NOT EXISTS party_lists_election_user_idx ON party_lists(election_id,user_id)")
            await conn.execute("CREATE INDEX IF NOT EXISTS applications_author_user_idx ON applications(author_user_id,status)")
            # Backfill legacy parties from the current registered username.
            await conn.execute("""UPDATE parties p SET leader_user_id=s.user_id
                FROM subjects s WHERE p.leader_user_id IS NULL
                  AND p.leader_username IS NOT NULL AND LOWER(s.username)=LOWER(p.leader_username)""")
            # Recover legacy parties from membership where the leader username was refreshed earlier.
            await conn.execute("""UPDATE parties p SET leader_user_id=s.user_id
                FROM subjects s JOIN party_members pm ON LOWER(pm.username)=LOWER(s.username)
                WHERE p.leader_user_id IS NULL AND pm.party_id=p.id
                  AND EXISTS (SELECT 1 FROM subject_roles sr JOIN roles r ON r.id=sr.role_id
                              WHERE sr.subject_id=s.id AND r.code='party_leader')""")
            # Keep only one pending citizenship application per Telegram user before adding the guard index.
            await conn.execute("""DELETE FROM applications older USING applications newer
                WHERE older.type='citizenship' AND newer.type='citizenship'
                  AND older.status='pending' AND newer.status='pending'
                  AND older.data ? 'user_id' AND newer.data ? 'user_id'
                  AND older.data->>'user_id'=newer.data->>'user_id' AND older.id<newer.id""")
            await conn.execute("""CREATE UNIQUE INDEX IF NOT EXISTS one_pending_citizenship_per_user
                ON applications ((data->>'user_id')) WHERE type='citizenship' AND status='pending' AND data ? 'user_id'""")
            # Idempotent migration for databases created by older versions of this bot.
            await conn.execute("ALTER TABLE duma_votes ADD COLUMN IF NOT EXISTS voter_user_id BIGINT")
            await conn.execute("ALTER TABLE votes ADD COLUMN IF NOT EXISTS election_id BIGINT")
            await conn.execute("ALTER TABLE votes ADD COLUMN IF NOT EXISTS username TEXT NOT NULL DEFAULT ''")
            await conn.execute("ALTER TABLE votes ADD COLUMN IF NOT EXISTS voter_name TEXT NOT NULL DEFAULT ''")
            await conn.execute("ALTER TABLE votes ADD COLUMN IF NOT EXISTS candidate_id TEXT NOT NULL DEFAULT '5'")

            # Archive legacy votes without an election in a finished election.
            legacy_votes = await conn.fetchval("SELECT COUNT(*) FROM votes WHERE election_id IS NULL")
            if legacy_votes:
                legacy_election = await conn.fetchrow(
                    "INSERT INTO pres_elections(ends_at,status) VALUES($1,'finished') RETURNING id",
                    to_db_dt(datetime.now(timezone.utc)),
                )
                await conn.execute(
                    "UPDATE votes SET election_id=$1 WHERE election_id IS NULL",
                    legacy_election['id'],
                )

            # Old versions could write placeholder Telegram IDs (-1), including duplicates.
            # Remove invalid IDs and deduplicate old records before creating unique indexes.
            await conn.execute("DELETE FROM votes WHERE user_id <= 0 OR election_id IS NULL")
            await conn.execute("""
                DELETE FROM votes older
                USING votes newer
                WHERE older.election_id = newer.election_id
                  AND older.user_id = newer.user_id
                  AND older.ctid < newer.ctid
            """)
            await conn.execute("ALTER TABLE votes ALTER COLUMN election_id SET NOT NULL")
            await conn.execute("ALTER TABLE votes DROP CONSTRAINT IF EXISTS votes_user_id_key")
            await conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS votes_election_user_uq ON votes(election_id,user_id)")
            # Convert legacy TIMESTAMPTZ election deadlines to naive UTC timestamps.
            await conn.execute("""DO $$ BEGIN
                IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='duma_elections' AND column_name='ends_at' AND data_type='timestamp with time zone') THEN
                    ALTER TABLE duma_elections ALTER COLUMN ends_at TYPE TIMESTAMP WITHOUT TIME ZONE USING ends_at AT TIME ZONE 'UTC';
                END IF;
                IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_name='pres_elections' AND column_name='ends_at' AND data_type='timestamp with time zone') THEN
                    ALTER TABLE pres_elections ALTER COLUMN ends_at TYPE TIMESTAMP WITHOUT TIME ZONE USING ends_at AT TIME ZONE 'UTC';
                END IF;
            END $$""")
            # Repair old case-sensitive duplicates before adding case-insensitive constraints.
            await conn.execute("""
                DELETE FROM party_members older
                USING party_members newer
                WHERE LOWER(older.username) = LOWER(newer.username)
                  AND older.ctid > newer.ctid
            """)
            await conn.execute("""
                DELETE FROM duma_votes older
                USING duma_votes newer
                WHERE older.election_id = newer.election_id
                  AND LOWER(older.voter_username) = LOWER(newer.voter_username)
                  AND older.ctid < newer.ctid
            """)
            await conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS party_members_username_lower_uq ON party_members (LOWER(username))")
            await conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS duma_votes_election_voter_uq ON duma_votes (election_id, LOWER(voter_username))")
            # Backfill stable voter IDs only after case-insensitive username duplicates were removed.
            await conn.execute("""UPDATE duma_votes dv SET voter_user_id=s.user_id FROM subjects s
                WHERE dv.voter_user_id IS NULL AND LOWER(dv.voter_username)=LOWER(s.username)""")
            await conn.execute("CREATE UNIQUE INDEX IF NOT EXISTS duma_votes_election_user_uq ON duma_votes(election_id,voter_user_id) WHERE voter_user_id IS NOT NULL")
            for code, name, emoji, max_holders in DEFAULT_ROLES:
                await conn.execute("""INSERT INTO roles(code,name,emoji,max_holders) VALUES($1,$2,$3,$4)
                    ON CONFLICT(code) DO UPDATE SET name=EXCLUDED.name, emoji=EXCLUDED.emoji, max_holders=EXCLUDED.max_holders""",
                    code, name, emoji, max_holders)
            await conn.execute("INSERT INTO admin_sessions(user_id,is_active) VALUES($1,FALSE) ON CONFLICT(user_id) DO NOTHING", ADMIN_ID)
            await conn.execute("""INSERT INTO subjects(user_id,username,full_name,is_citizen,reputation)
                VALUES($1,'admin','Главный администратор',TRUE,100) ON CONFLICT(user_id) DO NOTHING""", ADMIN_ID)
            row = await conn.fetchrow("SELECT id FROM subjects WHERE user_id=$1", ADMIN_ID)
            role = await conn.fetchrow("SELECT id FROM roles WHERE code='citizen'")
            if row and role:
                await conn.execute("INSERT INTO subject_roles(subject_id,role_id) VALUES($1,$2) ON CONFLICT DO NOTHING", row['id'], role['id'])
            # Persist candidate status so a candidate cannot evade the voting restriction by changing their profile name.
            candidate_names = [name.casefold() for name in CANDIDATE_FULL_NAMES.values()]
            await conn.execute("""INSERT INTO subject_roles(subject_id,role_id)
                SELECT s.id,r.id FROM subjects s CROSS JOIN roles r
                WHERE s.is_citizen=TRUE AND LOWER(s.full_name)=ANY($1::text[])
                  AND r.code='presidential_candidate' ON CONFLICT DO NOTHING""", candidate_names)


async def subject(pool: asyncpg.Pool, user_id: int):
    async with pool.acquire() as conn:
        return await conn.fetchrow("SELECT * FROM subjects WHERE user_id=$1", user_id)


async def subject_by_username(pool: asyncpg.Pool, username: str):
    username = normalize_username(username)
    async with pool.acquire() as conn:
        return await conn.fetchrow("SELECT * FROM subjects WHERE LOWER(username)=LOWER($1)", username)


async def roles_for(pool: asyncpg.Pool, user_id: int) -> set[str]:
    async with pool.acquire() as conn:
        rows = await conn.fetch("""SELECT r.code FROM roles r JOIN subject_roles sr ON sr.role_id=r.id
            JOIN subjects s ON s.id=sr.subject_id WHERE s.user_id=$1""", user_id)
    return {r['code'] for r in rows}


async def is_admin(pool: asyncpg.Pool, user_id: int) -> bool:
    if user_id != ADMIN_ID:
        return False
    async with pool.acquire() as conn:
        row = await conn.fetchrow("SELECT is_active FROM admin_sessions WHERE user_id=$1", user_id)
    return bool(row and row['is_active'])


async def is_duma(pool: asyncpg.Pool, user_id: int) -> bool:
    return user_id == ADMIN_ID or 'deputy' in await roles_for(pool, user_id)


async def is_president(pool: asyncpg.Pool, user_id: int) -> bool:
    return user_id == ADMIN_ID or 'president' in await roles_for(pool, user_id)


async def add_role(pool: asyncpg.Pool, user_id: int, code: str) -> bool:
    async with pool.acquire() as conn:
        async with conn.transaction():
            person = await conn.fetchrow("SELECT id FROM subjects WHERE user_id=$1 AND is_citizen=TRUE", user_id)
            role = await conn.fetchrow("SELECT id,max_holders FROM roles WHERE code=$1", code)
            if not person or not role:
                return False
            exists = await conn.fetchval("SELECT 1 FROM subject_roles WHERE subject_id=$1 AND role_id=$2", person['id'], role['id'])
            if not exists and role['max_holders'] > 0:
                count = await conn.fetchval("SELECT COUNT(*) FROM subject_roles WHERE role_id=$1", role['id'])
                if count >= role['max_holders']:
                    return False
            await conn.execute("INSERT INTO subject_roles(subject_id,role_id) VALUES($1,$2) ON CONFLICT DO NOTHING", person['id'], role['id'])
            return True


async def remove_role(pool: asyncpg.Pool, user_id: int, code: str) -> None:
    async with pool.acquire() as conn:
        await conn.execute("""DELETE FROM subject_roles sr USING roles r,subjects s
            WHERE sr.role_id=r.id AND sr.subject_id=s.id AND s.user_id=$1 AND r.code=$2""", user_id, code)


async def notify_user(bot: Bot, pool: asyncpg.Pool, username: str, text: str) -> None:
    person = await subject_by_username(pool, username)
    if person:
        try:
            await bot.send_message(person['user_id'], text)
        except TelegramAPIError:
            logging.exception("Не удалось отправить сообщение пользователю %s", username)


async def broadcast(pool: asyncpg.Pool, bot: Bot, text: str) -> tuple[int, int]:
    """Send a broadcast resiliently; malformed legacy user IDs must not stop the batch."""
    async with pool.acquire() as conn:
        # Older database rows may contain NULL / invalid user_id values.
        users = await conn.fetch(
            "SELECT DISTINCT user_id FROM subjects "
            "WHERE is_citizen=TRUE AND user_id IS NOT NULL AND user_id > 0"
        )
    ok = failed = 0
    for row in users:
        user_id = row['user_id']
        # Defend against unexpected data types in a legacy/corrupt database.
        if not isinstance(user_id, int) or user_id <= 0:
            failed += 1
            logging.error("Пропущен некорректный user_id в рассылке: %r", user_id)
            continue
        try:
            await bot.send_message(chat_id=user_id, text=text)
            ok += 1
        except Exception:
            # Aiogram can raise Pydantic ValidationError before a TelegramAPIError
            # is created (e.g. chat_id=None). One bad row must not abort the batch.
            failed += 1
            logging.exception("Рассылка не доставлена user_id=%r", user_id)
        await asyncio.sleep(0.04)
    return ok, failed


async def audit(pool: asyncpg.Pool, actor_id: int, action: str, target: str = '', details: dict[str, Any] | None = None) -> None:
    async with pool.acquire() as conn:
        await conn.execute("INSERT INTO audit_log(actor_id,action,target,details) VALUES($1,$2,$3,$4::jsonb)",
                           actor_id, action, target, json.dumps(details or {}, ensure_ascii=False))


async def election_results_text(pool: asyncpg.Pool) -> str:
    async with pool.acquire() as conn:
        pres = await conn.fetch("SELECT id,status,results FROM pres_elections ORDER BY id DESC LIMIT 5")
        duma = await conn.fetch("SELECT id,status,results FROM duma_elections ORDER BY id DESC LIMIT 5")
        parties = await conn.fetch("SELECT id,name FROM parties")
    party_names = {str(r['id']): r['name'] for r in parties}
    def unpack(value):
        return {} if not value else (value if isinstance(value, dict) else json.loads(value))
    lines = ['📊 <b>Последние выборы</b>', '', '<b>Президентские</b>']
    for row in pres:
        result = unpack(row['results']); votes = result.get('votes', {}); winner = result.get('winner')
        winner_name = CANDIDATES.get(str(winner), 'нет победителя') if winner else ('ничья' if result.get('tie') else 'нет победителя')
        lines.append(f"№{row['id']} · {row['status']} · {winner_name} · голосов: {sum(votes.values()) if votes else 0}")
    lines += ['', '<b>Госдума</b>']
    for row in duma:
        result = unpack(row['results']); votes = result.get('votes', {}); winner = result.get('winner_party_id')
        lines.append(f"№{row['id']} · {row['status']} · {party_names.get(str(winner), 'нет победителя') if winner else 'нет победителя'} · голосов: {sum(votes.values()) if votes else 0}")
    return '\n'.join(lines)


def main_menu(is_admin_user: bool, president: bool, deputy: bool) -> InlineKeyboardMarkup:
    rows = [
        [("👤 Профиль", "profile"), ("🗳 Голосования", "elections")],
        [("🎭 Партии", "parties"), ("📜 Документы", "documents")],
        [("📰 Газета", "news"), ("📊 Результаты", "results")],
        [("⚙️ Настройки", "settings"), ("❓ Помощь", "help")],
    ]
    if deputy:
        rows.append([("🏛 Панель Госдумы", "duma_panel")])
    if president:
        rows.append([("👑 Панель Президента", "president_panel")])
    if is_admin_user:
        rows.append([("🛠 Админка", "admin_panel")])
    return keyboard(rows)


async def show_menu(message: Message, pool: asyncpg.Pool, user_id: int, text: str = "Главное меню") -> None:
    role_set = await roles_for(pool, user_id)
    await message.answer(text, reply_markup=main_menu(user_id == ADMIN_ID, 'president' in role_set, 'deputy' in role_set))


async def active_election(pool: asyncpg.Pool, table: str):
    if table not in {'pres_elections', 'duma_elections'}:
        raise ValueError('Invalid election table')
    async with pool.acquire() as conn:
        return await conn.fetchrow(f"SELECT * FROM {table} WHERE status='active' ORDER BY id DESC LIMIT 1")


async def finish_duma_election(pool: asyncpg.Pool, election_id: int) -> dict[str, Any]:
    async with pool.acquire() as conn:
        votes = await conn.fetch("""SELECT party_id,COUNT(*) AS votes FROM duma_votes WHERE election_id=$1
            GROUP BY party_id ORDER BY votes DESC,party_id""", election_id)
        results = {str(row['party_id']): row['votes'] for row in votes}
        winner = votes[0]['party_id'] if votes else None
        await conn.execute("DELETE FROM subject_roles sr USING roles r WHERE sr.role_id=r.id AND r.code='deputy'")
        await conn.execute("DELETE FROM role_terms WHERE role_code='deputy'")
        if winner is not None:
            candidates = await conn.fetch("SELECT username,user_id FROM party_lists WHERE election_id=$1 AND party_id=$2 ORDER BY username LIMIT 3", election_id, winner)
            for item in candidates:
                person = await conn.fetchrow("SELECT user_id FROM subjects WHERE user_id=$1 AND is_citizen=TRUE", item['user_id']) if item['user_id'] else await conn.fetchrow("SELECT user_id FROM subjects WHERE LOWER(username)=LOWER($1) AND is_citizen=TRUE", item['username'])
                if person:
                    await conn.execute("""INSERT INTO subject_roles(subject_id,role_id)
                        SELECT s.id,r.id FROM subjects s,roles r WHERE s.user_id=$1 AND r.code='deputy'
                        ON CONFLICT DO NOTHING""", person['user_id'])
                    await conn.execute("""INSERT INTO role_terms(role_code,user_id,ends_at) VALUES('deputy',$1,$2)
                        ON CONFLICT(role_code,user_id) DO UPDATE SET ends_at=EXCLUDED.ends_at""", person['user_id'], to_db_dt(datetime.now(timezone.utc) + timedelta(days=90)))
        await conn.execute("UPDATE duma_elections SET status='finished',results=$1::jsonb WHERE id=$2", json.dumps({'votes': results, 'winner_party_id': winner}), election_id)
        return {'votes': results, 'winner_party_id': winner}


async def finish_pres_election(pool: asyncpg.Pool, election_id: int) -> dict[str, Any]:
    async with pool.acquire() as conn:
        rows = await conn.fetch("SELECT candidate_id,COUNT(*) AS votes FROM votes WHERE election_id=$1 GROUP BY candidate_id ORDER BY votes DESC,candidate_id", election_id)
        results = {str(r['candidate_id']): r['votes'] for r in rows}
        top_votes = rows[0]['votes'] if rows else 0
        tied = [r['candidate_id'] for r in rows if r['votes'] == top_votes] if rows else []
        winner = rows[0]['candidate_id'] if len(tied) == 1 else None
        await conn.execute("UPDATE pres_elections SET status='finished',results=$1::jsonb WHERE id=$2", json.dumps({'votes': results, 'winner': winner, 'tie': tied if len(tied) > 1 else []}), election_id)
        if winner is not None:
            await conn.execute("DELETE FROM subject_roles sr USING roles r WHERE sr.role_id=r.id AND r.code='president'")
            await conn.execute("DELETE FROM role_terms WHERE role_code='president'")
        if winner and winner != '5':
            candidate_name = CANDIDATE_FULL_NAMES.get(winner)
            if candidate_name:
                person = await conn.fetchrow("SELECT user_id FROM subjects WHERE LOWER(full_name)=LOWER($1) AND is_citizen=TRUE LIMIT 1", candidate_name)
                if person:
                    await conn.execute("""INSERT INTO subject_roles(subject_id,role_id)
                        SELECT s.id,r.id FROM subjects s,roles r WHERE s.user_id=$1 AND r.code='president'
                        ON CONFLICT DO NOTHING""", person['user_id'])
                    await conn.execute("""INSERT INTO role_terms(role_code,user_id,ends_at) VALUES('president',$1,$2)
                        ON CONFLICT(role_code,user_id) DO UPDATE SET ends_at=EXCLUDED.ends_at""", person['user_id'], to_db_dt(datetime.now(timezone.utc) + timedelta(days=90)))
        return {'votes': results, 'winner': winner}


def register_handlers(dp: Dispatcher, pool: asyncpg.Pool, bot: Bot) -> None:
    @dp.message(Command('cancel'))
    async def cancel_command(message: Message, state: FSMContext):
        await state.clear(); await message.answer('Текущий ввод отменён. Используйте /menu для возврата.')

    @dp.message(Command('id'))
    async def id_command(message: Message):
        if message.from_user: await message.answer(f'Ваш Telegram ID: <code>{message.from_user.id}</code>')

    @dp.message(Command('start'))
    async def start(message: Message, state: FSMContext):
        await state.clear()
        user = message.from_user
        if not user:
            return
        person = await subject(pool, user.id)
        if person and person['is_citizen']:
            new_username = normalize_username(user.username, user.id)
            async with pool.acquire() as conn:
                await conn.execute('UPDATE subjects SET username=$1 WHERE user_id=$2', new_username, user.id)
            await show_menu(message, pool, user.id, f"🏛 Добро пожаловать, {html_escape(person['full_name'])}!")
            return
        async with pool.acquire() as conn:
            pending = await conn.fetchval("SELECT 1 FROM applications WHERE type='citizenship' AND status='pending' AND data->>'user_id'=$1 LIMIT 1", str(user.id))
        if pending:
            await message.answer("Ваша заявка на гражданство уже ожидает решения. Повторно отправлять её не нужно.")
            return
        await state.set_state(Form.registration_word)
        await message.answer("Для регистрации введите секретное слово.")

    @dp.message(Command('menu'))
    async def menu_command(message: Message, state: FSMContext):
        await state.clear()
        if message.from_user:
            person = await subject(pool, message.from_user.id)
            if person and person['is_citizen']:
                await show_menu(message, pool, message.from_user.id)
            else:
                async with pool.acquire() as conn:
                    pending = await conn.fetchval("SELECT 1 FROM applications WHERE type='citizenship' AND status='pending' AND data->>'user_id'=$1 LIMIT 1", str(message.from_user.id))
                if pending:
                    await message.answer('Ваша заявка уже ожидает решения Президента или администратора.')
                else:
                    await state.set_state(Form.registration_word)
                    await message.answer("Для регистрации введите секретное слово.")

    @dp.message(StateFilter(None), F.text)
    async def handle_text(message: Message):
        # Deliberately does not intercept messages while another FSM state is active.
        return

    @dp.message(Form.registration_word)
    async def registration_word(message: Message, state: FSMContext):
        raw = (message.text or '').strip().casefold()
        target = SECRET_WORD.casefold()
        compact = re.sub(r'\s+', ' ', raw)
        # Accept minor letter typos, but require the same words and grammatical forms.
        words = compact.split()
        expected = target.split()
        correct = len(words) == len(expected) and all(SequenceMatcher(None, a, b).ratio() >= 0.78 for a, b in zip(words, expected))
        if not correct:
            await message.answer("Секретное слово не подошло. Попробуйте ещё раз.")
            return
        await state.set_state(Form.registration_name)
        await message.answer("Введите ФИО полностью.")

    @dp.message(Form.registration_name)
    async def registration_name(message: Message, state: FSMContext):
        user = message.from_user
        name = (message.text or '').strip()
        if not user or len(name) < 5 or len(name) > 150:
            await message.answer("Введите корректное ФИО (от 5 до 150 символов).")
            return
        username = normalize_username(user.username, user.id)
        async with pool.acquire() as conn:
            pending = await conn.fetchval("SELECT 1 FROM applications WHERE type='citizenship' AND status='pending' AND data->>'user_id'=$1 LIMIT 1", str(user.id))
            if pending:
                await state.clear()
                await message.answer('Ваша заявка уже ожидает решения. Повторная заявка не создана.')
                return
            await conn.execute("""INSERT INTO subjects(user_id,username,full_name,is_citizen,reputation)
                VALUES($1,$2,$3,FALSE,0) ON CONFLICT(user_id) DO UPDATE SET username=EXCLUDED.username,full_name=EXCLUDED.full_name""", user.id, username, name)
            data = json.dumps({'user_id': user.id, 'full_name': name})
            app = await conn.fetchrow("""INSERT INTO applications(type,author_username,author_user_id,data,status)
                VALUES('citizenship',$1,$2,$3::jsonb,'pending') RETURNING id""", username, user.id, data)
            presidents = await conn.fetch("""SELECT s.user_id FROM subjects s JOIN subject_roles sr ON sr.subject_id=s.id
                JOIN roles r ON r.id=sr.role_id WHERE r.code='president'""")
        await state.clear()
        await message.answer("Заявка на гражданство отправлена. Ожидайте решения Президента или администратора.")
        for p in presidents:
            try:
                await bot.send_message(p['user_id'], f"Новая заявка на гражданство №{app['id']} от {html_escape(name)} (@{html_escape(username)}).")
            except TelegramAPIError:
                logging.warning("Не удалось уведомить президента user_id=%s", p['user_id'])
        try:
            await bot.send_message(ADMIN_ID, f"Новая заявка на гражданство №{app['id']} от {html_escape(name)} (@{html_escape(username)}).")
        except TelegramAPIError:
            pass

    @dp.message(Form.change_name)
    async def change_name(message: Message, state: FSMContext):
        user = message.from_user
        name = (message.text or '').strip()
        if not user or len(name) < 5 or len(name) > 150:
            await message.answer("Введите ФИО от 5 до 150 символов.")
            return
        async with pool.acquire() as conn:
            await conn.execute("UPDATE subjects SET full_name=$1 WHERE user_id=$2 AND is_citizen=TRUE", name, user.id)
        await state.clear()
        await show_menu(message, pool, user.id, "ФИО обновлено.")

    @dp.message(Form.create_party_name)
    async def create_party_name(message: Message, state: FSMContext):
        name = (message.text or '').strip()
        if not 3 <= len(name) <= 80:
            await message.answer("Название должно содержать от 3 до 80 символов.")
            return
        async with pool.acquire() as conn:
            duplicate = await conn.fetchval("SELECT 1 FROM parties WHERE LOWER(name)=LOWER($1) AND status='active' UNION ALL SELECT 1 FROM applications WHERE type='create_party' AND status='pending' AND LOWER(data->>'name')=LOWER($1) LIMIT 1", name)
        if duplicate:
            await message.answer("Активная партия или заявка с таким названием уже существует. Введите другое название.")
            return
        await state.update_data(party_name=name)
        await state.set_state(Form.create_party_emoji)
        await message.answer("Введите эмодзи партии (или отправьте - для 🎭).")

    @dp.message(Form.create_party_emoji)
    async def create_party_emoji(message: Message, state: FSMContext):
        emoji = (message.text or '').strip()
        await state.update_data(party_emoji='🎭' if emoji == '-' or not emoji else emoji[:16])
        await state.set_state(Form.create_party_description)
        await message.answer("Введите описание партии.")

    @dp.message(Form.create_party_description)
    async def create_party_description(message: Message, state: FSMContext):
        user = message.from_user
        desc = (message.text or '').strip()
        data = await state.get_data()
        if not user or not desc or len(desc) > 1000:
            await message.answer("Описание не должно быть пустым или длиннее 1000 символов.")
            return
        username = normalize_username(user.username, user.id)
        payload = {'name': data['party_name'], 'emoji': data['party_emoji'], 'description': desc, 'leader_username': username, 'leader_user_id': user.id}
        async with pool.acquire() as conn:
            app = await conn.fetchrow("INSERT INTO applications(type,author_username,author_user_id,data) VALUES('create_party',$1,$2,$3::jsonb) RETURNING id", username, user.id, json.dumps(payload))
        await state.clear()
        await message.answer(f"Заявка на создание партии №{app['id']} отправлена на рассмотрение.")
        try:
            await bot.send_message(ADMIN_ID, f"Заявка на создание партии №{app['id']}. Откройте 🛠 Админка → 📋 Заявки.")
        except TelegramAPIError:
            pass
        async with pool.acquire() as conn:
            presidents = await conn.fetch("SELECT s.user_id FROM subjects s JOIN subject_roles sr ON sr.subject_id=s.id JOIN roles r ON r.id=sr.role_id WHERE r.code='president'")
        for row in presidents:
            try:
                await bot.send_message(row['user_id'], f"Поступила заявка на создание партии №{app['id']} от @{username}. Решение можно принять в панели Президента.")
            except TelegramAPIError:
                pass

    @dp.message(Form.join_party)
    async def join_party_name(message: Message, state: FSMContext):
        user = message.from_user
        party_name = (message.text or '').strip()
        if not user:
            return
        username = normalize_username(user.username, user.id)
        async with pool.acquire() as conn:
            party = await conn.fetchrow("SELECT id,name FROM parties WHERE LOWER(name)=LOWER($1) AND status='active'", party_name)
            if not party:
                await message.answer("Активная партия с таким названием не найдена.")
                return
            existing = await conn.fetchrow("SELECT party_id FROM party_members WHERE user_id=$2 OR LOWER(username)=LOWER($1)", username, user.id)
            if existing:
                await message.answer("Вы уже состоите в партии. Один человек может быть членом только одной партии.")
                await state.clear()
                return
            app = await conn.fetchrow("INSERT INTO applications(type,author_username,author_user_id,data) VALUES('join_party',$1,$2,$3::jsonb) RETURNING id", username, user.id, json.dumps({'party_id': party['id'], 'party_name': party['name'], 'user_id': user.id}))
        await state.clear()
        await message.answer(f"Заявка №{app['id']} на вступление в партию «{html_escape(party['name'])}» отправлена лидеру партии.")
        async with pool.acquire() as conn:
            leader_id = await conn.fetchval("SELECT leader_user_id FROM parties WHERE id=$1", party['id'])
        if leader_id:
            try:
                await bot.send_message(leader_id, f"Заявка №{app['id']} от @{html_escape(username)} на вступление в партию «{html_escape(party['name'])}». Откройте 🎭 Партии → 👥 Моя партия → 📋 Заявки на вступление.")
            except TelegramAPIError:
                logging.info("Не удалось уведомить лидера партии %s", party['id'])
        try:
            await bot.send_message(ADMIN_ID, f"Новая заявка на вступление в партию №{app['id']} (партия {party['name']}).")
        except TelegramAPIError:
            pass

    @dp.message(Form.party_list)
    async def save_party_list(message: Message, state: FSMContext):
        user = message.from_user
        if not user:
            return
        usernames = [normalize_username(x) for x in (message.text or '').replace(',', '\n').splitlines() if x.strip()]
        usernames = list(dict.fromkeys(usernames))
        if not 1 <= len(usernames) <= 3:
            await message.answer("Укажите от 1 до 3 username, каждый с новой строки. Например: @user1\n@user2")
            return
        data = await state.get_data()
        election_id = data.get('election_id')
        leader = normalize_username(user.username, user.id)
        async with pool.acquire() as conn:
            party = await conn.fetchrow("""SELECT id FROM parties
                WHERE id=$1 AND status='active'
                  AND (leader_user_id=$2 OR LOWER(COALESCE(leader_username,''))=LOWER($3))""",
                data.get('party_id'), user.id, leader)
            if not party:
                # Repair older rows lazily when the legacy username still matches this account.
                party = await conn.fetchrow("""UPDATE parties SET leader_user_id=$2
                    WHERE id=$1 AND status='active' AND leader_user_id IS NULL
                      AND LOWER(COALESCE(leader_username,''))=LOWER($3) RETURNING id""",
                    data.get('party_id'), user.id, leader)
            if not party:
                await message.answer("Не удалось подтвердить лидерство этой партии. Напишите администратору, чтобы он восстановил владельца партии.")
                await state.clear()
                return
            await conn.execute("UPDATE parties SET leader_user_id=$2,leader_username=$3 WHERE id=$1", party['id'], user.id, leader)
            candidates = []
            for candidate_username in usernames:
                found = await conn.fetchrow("SELECT user_id,username FROM subjects WHERE LOWER(username)=LOWER($1) AND is_citizen=TRUE", candidate_username)
                if not found:
                    await message.answer(f"@{candidate_username} не найден среди граждан. Проверьте username и отправьте список заново.")
                    return
                candidates.append((found['username'], found['user_id']))
            if len({candidate_id for _, candidate_id in candidates}) != len(candidates):
                await message.answer("Один человек не может занимать несколько мест в партийном списке.")
                return
            await conn.execute("DELETE FROM party_lists WHERE party_id=$1 AND election_id=$2", party['id'], election_id)
            for candidate_username, candidate_user_id in candidates:
                await conn.execute("INSERT INTO party_lists(party_id,election_id,username,user_id) VALUES($1,$2,$3,$4) ON CONFLICT DO NOTHING", party['id'], election_id, candidate_username, candidate_user_id)
        await state.clear()
        await message.answer("Список партии сохранён.")

    @dp.message(Form.law_title)
    async def law_title(message: Message, state: FSMContext):
        if not message.from_user or not await is_duma(pool, message.from_user.id):
            await state.clear(); await message.answer('Вносить законопроекты могут только депутаты.'); return
        title = (message.text or '').strip()
        if not title or len(title) > 200:
            await message.answer("Название закона должно быть до 200 символов.")
            return
        await state.update_data(law_title=title)
        await state.set_state(Form.law_description)
        await message.answer("Введите описание законопроекта.")

    @dp.message(Form.law_description)
    async def law_description(message: Message, state: FSMContext):
        if not message.from_user or not await is_duma(pool, message.from_user.id):
            await state.clear(); await message.answer('Вносить законопроекты могут только депутаты.'); return
        user = message.from_user
        description = (message.text or '').strip()
        data = await state.get_data()
        if not user or not description or len(description) > 3000:
            await message.answer("Описание должно быть непустым и не длиннее 3000 символов.")
            return
        async with pool.acquire() as conn:
            row = await conn.fetchrow("INSERT INTO laws(title,description,status,created_by) VALUES($1,$2,'duma',$3) RETURNING id", data['law_title'], description, user.id)
            await conn.execute("INSERT INTO news(text) VALUES($1)", f"📜 В ГосДуму внесён законопроект №{row['id']}: {data['law_title']}")
        await state.clear()
        await message.answer(f"Законопроект №{row['id']} внесён.")

    @dp.message(Form.decree_title)
    async def decree_title(message: Message, state: FSMContext):
        if not message.from_user or not await is_president(pool, message.from_user.id):
            await state.clear(); await message.answer('Указы может создавать только Президент.'); return
        title = (message.text or '').strip()
        if not title or len(title) > 200:
            await message.answer("Заголовок должен быть до 200 символов.")
            return
        await state.update_data(decree_title=title)
        await state.set_state(Form.decree_text)
        await message.answer("Введите текст указа.")

    @dp.message(Form.decree_text)
    async def decree_text(message: Message, state: FSMContext):
        if not message.from_user or not await is_president(pool, message.from_user.id):
            await state.clear(); await message.answer('Указы может создавать только Президент.'); return
        text = (message.text or '').strip()
        if not text or len(text) > 3500:
            await message.answer("Текст должен быть непустым и не длиннее 3500 символов.")
            return
        await state.update_data(decree_text=text)
        await state.set_state(Form.decree_secret)
        await message.answer("Указ секретный? Ответьте: да или нет.")

    @dp.message(Form.decree_secret)
    async def decree_secret(message: Message, state: FSMContext):
        if not message.from_user or not await is_president(pool, message.from_user.id):
            await state.clear(); await message.answer('Указы может создавать только Президент.'); return
        answer = (message.text or '').strip().casefold()
        if answer not in {'да', 'нет', 'д', 'н'}:
            await message.answer("Ответьте «да» или «нет».")
            return
        if answer in {'да', 'д'}:
            await state.update_data(decree_secret=True)
            await state.set_state(Form.decree_recipient)
            await message.answer("Введите username получателя, например @username.")
        else:
            data = await state.get_data()
            async with pool.acquire() as conn:
                await conn.execute("INSERT INTO decrees(title,text,is_secret) VALUES($1,$2,FALSE)", data['decree_title'], data['decree_text'])
                await conn.execute("INSERT INTO news(text) VALUES($1)", f"📢 Президентский указ: {html_escape(data['decree_title'])}\n\n{html_escape(data['decree_text'])}")
            await state.clear()
            await broadcast(pool, bot, f"📢 Президентский указ: {html_escape(data['decree_title'])}\n\n{html_escape(data['decree_text'])}")
            await message.answer("Обычный указ опубликован и отправлен гражданам.")

    @dp.message(Form.decree_recipient)
    async def decree_recipient(message: Message, state: FSMContext):
        if not message.from_user or not await is_president(pool, message.from_user.id):
            await state.clear(); await message.answer('Указы может создавать только Президент.'); return
        username = normalize_username(message.text)
        recipient = await subject_by_username(pool, username)
        if not recipient or not recipient['is_citizen']:
            await message.answer("Гражданин с таким username не найден. Попробуйте снова.")
            return
        data = await state.get_data()
        async with pool.acquire() as conn:
            recipient_id = await conn.fetchval("SELECT user_id FROM subjects WHERE LOWER(username)=LOWER($1) AND is_citizen=TRUE", username)
            if not recipient_id:
                await message.answer('Получатель не найден среди граждан. Укажите точный username гражданина.')
                return
            await conn.execute("INSERT INTO decrees(title,text,is_secret,secret_for,secret_for_user_id) VALUES($1,$2,TRUE,$3,$4)", data['decree_title'], data['decree_text'], username, recipient_id)
        await state.clear()
        try:
            await bot.send_message(recipient['user_id'], f"🔒 <b>Секретный указ</b>\n{html_escape(data['decree_title'])}\n\n{html_escape(data['decree_text'])}")
        except TelegramAPIError:
            await message.answer("Указ сохранён, но отправить его получателю не удалось.")
            return
        await message.answer("Секретный указ отправлен только указанному получателю.")

    @dp.message(Form.address_text)
    async def address_text(message: Message, state: FSMContext):
        if not message.from_user or not await is_president(pool, message.from_user.id):
            await state.clear(); await message.answer('Обращение может публиковать только Президент.'); return
        text = (message.text or '').strip()
        if not text or len(text) > 3500:
            await message.answer("Текст обращения должен быть непустым и не длиннее 3500 символов.")
            return
        await state.clear()
        ok, failed = await broadcast(pool, bot, f"📣 <b>Обращение к народу</b>\n\n{html_escape(text)}")
        async with pool.acquire() as conn:
            await conn.execute("INSERT INTO news(text) VALUES($1)", f"📣 Обращение к народу\n\n{text}")
        await message.answer(f"Обращение отправлено. Доставлено: {ok}, ошибок доставки: {failed}.")

    @dp.message(Form.minister_username)
    async def minister_username(message: Message, state: FSMContext):
        if not message.from_user or not await is_president(pool, message.from_user.id):
            await state.clear(); await message.answer('Назначать должностных лиц может только Президент.'); return
        user = message.from_user
        username = normalize_username(message.text)
        person = await subject_by_username(pool, username)
        if not person or not person['is_citizen']:
            await message.answer("Гражданин не найден. Введите username ещё раз.")
            return
        data = await state.get_data()
        role_code = data['role_code']
        removed_usernames = []
        if role_code in {'prime_minister', 'deputy_prime_minister', 'central_bank_head'}:
            async with pool.acquire() as conn:
                removed_usernames = await conn.fetch("SELECT s.username FROM subjects s JOIN subject_roles sr ON sr.subject_id=s.id JOIN roles r ON r.id=sr.role_id WHERE r.code=$1 AND s.user_id<>$2", role_code, person['user_id'])
                await conn.execute("DELETE FROM subject_roles sr USING subjects s,roles r WHERE sr.subject_id=s.id AND sr.role_id=r.id AND r.code=$1 AND s.user_id<>$2", role_code, person['user_id'])
        ok = await add_role(pool, person['user_id'], role_code)
        await state.clear()
        if not ok:
            await message.answer("Не удалось назначить роль: проверьте гражданство и лимит должности.")
            return
        for old in removed_usernames:
            await notify_user(bot, pool, old['username'], f"Ваши полномочия «{data['role_name']}» переданы другому гражданину.")
        await notify_user(bot, pool, username, f"Вам назначена должность: {data['role_name']}.")
        async with pool.acquire() as conn:
            await conn.execute('INSERT INTO news(text) VALUES($1)', f"Президент назначил {person['full_name']} на должность: {data['role_name']}.")
        await message.answer(f"{person['full_name']} назначен(а): {data['role_name']}.")

    @dp.message(Form.election_duration)
    async def election_duration(message: Message, state: FSMContext):
        if not message.from_user or not await is_duma(pool, message.from_user.id):
            await state.clear(); await message.answer('Назначать президентские выборы может только Госдума.'); return
        await create_election_from_duration(message, state, pool, 'pres_elections', 'president')

    @dp.message(Form.duma_duration)
    async def duma_duration(message: Message, state: FSMContext):
        if not message.from_user or not await is_president(pool, message.from_user.id):
            await state.clear(); await message.answer('Назначать выборы Госдумы может только Президент.'); return
        await create_election_from_duration(message, state, pool, 'duma_elections', 'ГосДумы')

    @dp.message(Form.meeting_text)
    async def meeting_text(message: Message, state: FSMContext):
        if not message.from_user or not await is_duma(pool, message.from_user.id):
            await state.clear(); await message.answer('Созывать собрания может только Госдума.'); return
        text = (message.text or '').strip()
        if not text or len(text) > 4000:
            await message.answer("Повестка должна быть непустой и не длиннее 4000 символов.")
            return
        async with pool.acquire() as conn:
            deputies = await conn.fetch("""SELECT DISTINCT s.user_id FROM subjects s JOIN subject_roles sr ON sr.subject_id=s.id
                JOIN roles r ON r.id=sr.role_id WHERE r.code='deputy' AND s.is_citizen=TRUE""")
            deadline = to_db_dt(datetime.now(timezone.utc) + timedelta(days=1))
            await conn.execute("INSERT INTO duma_meetings(created_by,agenda,deadline) VALUES($1,$2,$3)", message.from_user.id, text, deadline)
            await conn.execute("INSERT INTO news(text) VALUES($1)", f"🏛 Собрание ГосДумы\n\n{text}\n\nДедлайн: {from_db_dt(deadline).strftime('%d.%m.%Y %H:%M МСК')}")
        sent = 0
        for row in deputies:
            try:
                await bot.send_message(row['user_id'], f"🏛 <b>Собрание ГосДумы</b>\n\n{html_escape(text)}\n\nСрок: 1 день — до {from_db_dt(deadline).strftime('%d.%m.%Y %H:%M МСК')}")
                sent += 1
            except TelegramAPIError:
                pass
        await state.clear()
        await message.answer(f"Повестка отправлена депутатам: {sent}.")

    @dp.message(Form.broadcast_text)
    async def broadcast_text(message: Message, state: FSMContext):
        if not message.from_user or message.from_user.id != ADMIN_ID or not await is_admin(pool, ADMIN_ID):
            await state.clear(); await message.answer('Нет доступа или админка выключена.'); return
        text = (message.text or '').strip()
        if not text or len(text) > 3500:
            await message.answer("Текст должен быть непустым и не длиннее 3500 символов.")
            return
        ok, failed = await broadcast(pool, bot, html_escape(text))
        async with pool.acquire() as conn:
            await conn.execute("INSERT INTO news(text) VALUES($1)", text)
        await state.clear()
        await message.answer(f"Рассылка завершена. Доставлено: {ok}, не доставлено: {failed}.")

    async def create_election_from_duration(message: Message, state: FSMContext, pool: asyncpg.Pool, table: str, label: str):
        raw = (message.text or '').strip()
        try:
            if re.fullmatch(r'\d{2}\.\d{2}\.\d{4}\s+\d{2}:\d{2}', raw):
                local_end = datetime.strptime(raw, '%d.%m.%Y %H:%M').replace(tzinfo=MSK)
                if local_end <= datetime.now(MSK):
                    raise ValueError
                end = to_db_dt(local_end)
                duration_label = f"до {local_end.strftime('%d.%m.%Y %H:%M МСК')}"
            else:
                hours = float(raw.replace(',', '.'))
                if not 0.1 <= hours <= 720:
                    raise ValueError
                end = to_db_dt(datetime.now(timezone.utc) + timedelta(hours=hours))
                duration_label = f"на {hours:g} ч."
        except ValueError:
            await message.answer("Введите дату ДД.ММ.ГГГГ ЧЧ:ММ по МСК или длительность в часах от 0,1 до 720.")
            return
        async with pool.acquire() as conn:
            await conn.execute(f"UPDATE {table} SET status='finished' WHERE status='active'")
            election = await conn.fetchrow(f"INSERT INTO {table}(ends_at,status) VALUES($1,'active') RETURNING id", end)
            await conn.execute("INSERT INTO news(text) VALUES($1)", f"Начались выборы {label}: {duration_label}.")
        await state.clear()
        await message.answer(f"Выборы {label} №{election['id']} начались: {duration_label}.")
        await broadcast(pool, bot, f"🗳 Начались выборы {label}. Откройте раздел «Голосования».")

    @dp.callback_query()
    async def all_callbacks(callback: CallbackQuery, state: FSMContext):
        """Single guarded callback dispatcher: every callback is caught and answered in finally."""
        try:
            data = callback.data or ''
            uid = callback.from_user.id
            person = await subject(pool, uid)
            citizen = bool(person and person['is_citizen'])
            if citizen and callback.from_user:
                refreshed_username = normalize_username(callback.from_user.username, uid)
                if refreshed_username != person['username']:
                    old_username = person['username']
                    async with pool.acquire() as conn:
                        async with conn.transaction():
                            await conn.execute('UPDATE subjects SET username=$1 WHERE user_id=$2', refreshed_username, uid)
                            await conn.execute('UPDATE party_members SET username=$1 WHERE LOWER(username)=LOWER($2) AND NOT EXISTS (SELECT 1 FROM party_members other WHERE LOWER(other.username)=LOWER($1))', refreshed_username, old_username)
                            await conn.execute('UPDATE parties SET leader_username=$1, leader_user_id=$3 WHERE LOWER(leader_username)=LOWER($2)', refreshed_username, old_username, uid)
                            await conn.execute('UPDATE party_lists SET username=$1 WHERE LOWER(username)=LOWER($2)', refreshed_username, old_username)
                            await conn.execute('UPDATE duma_votes SET voter_username=$1 WHERE LOWER(voter_username)=LOWER($2)', refreshed_username, old_username)
                    person = await subject(pool, uid)
            role_set = await roles_for(pool, uid) if citizen else set()
            admin = uid == ADMIN_ID
            admin_active = await is_admin(pool, uid) if admin else False
            president = admin or 'president' in role_set
            deputy = admin or 'deputy' in role_set
            msg = callback.message
            if data.startswith('admin_') and data not in {'admin_panel','admin_activate','admin_deactivate'} and admin and not admin_active:
                await callback.answer('Сначала активируйте админку кнопкой 🔓 Активировать.', show_alert=True)
                return

            async def edit(text: str, rows: list[list[tuple[str, str]]] | None = None):
                if msg:
                    await msg.edit_text(text, reply_markup=keyboard(rows or [[('⬅️ Назад', 'main')]]))

            if data == 'main':
                await edit('Главное меню', [[('👤 Профиль','profile'),('🗳 Голосования','elections')],[('🎭 Партии','parties'),('📜 Документы','documents')],[('📰 Газета','news'),('📊 Результаты','results')],[('⚙️ Настройки','settings'),('❓ Помощь','help')]] + ([[('🏛 Панель Госдумы','duma_panel')]] if deputy else []) + ([[('👑 Панель Президента','president_panel')]] if president else []) + ([[('🛠 Админка','admin_panel')]] if admin else []))
            elif not citizen and not admin:
                await callback.answer('Сначала зарегистрируйтесь через /start.', show_alert=True)
                return
            elif data == 'profile':
                async with pool.acquire() as conn:
                    role_rows = await conn.fetch("""SELECT r.name,r.emoji FROM roles r JOIN subject_roles sr ON sr.role_id=r.id JOIN subjects s ON s.id=sr.subject_id WHERE s.user_id=$1 ORDER BY r.name""", uid)
                role_text = ', '.join(f"{r['emoji']} {r['name']}" for r in role_rows) or 'Нет'
                await edit(f"👤 <b>Профиль</b>\nФИО: {html_escape(person['full_name'])}\nUsername: @{html_escape(person['username'])}\nРепутация: {person['reputation']}\nРоли: {html_escape(role_text)}", [[('✍️ Изменить ФИО','change_name')],[('⬅️ Назад','main')]])
            elif data in {'change_name','settings'}:
                await state.set_state(Form.change_name)
                if msg: await msg.answer('Введите новое ФИО:')
            elif data == 'help':
                await edit('❓ Помощь\n/start — открыть меню. Используйте кнопки для навигации. Заявки и голосования доступны гражданам.', [[('⬅️ Назад','main')]])
            elif data == 'documents':
                await edit('📜 Документы', [[('📚 Законы','laws'),('📖 Конституция','constitution')],[('⬅️ Назад','main')]])
            elif data == 'constitution':
                await edit('📖 <b>Основы Конституции</b>\n\n1. Суверенитет принадлежит гражданам государства.\n2. Гражданство предоставляется после одобрения заявки Президентом или главным администратором.\n3. Президент руководит исполнительной властью, издаёт указы и назначает членов Правительства. Срок полномочий — 90 дней.\n4. Госдума состоит из депутатов, избранных по партийным спискам, принимает законы и может назначать президентские выборы. Срок полномочий Думы — 90 дней.\n5. Закон проходит три этапа: голосование Госдумы, рассмотрение Правительством и подписание Президентом.\n6. Обычные указы публикуются в газете и рассылаются гражданам. Секретные указы доступны только адресату.\n7. Администратор обеспечивает работу системы и может применять предусмотренные административные полномочия.', [[('⬅️ Назад','documents')]])
            elif data == 'laws':
                async with pool.acquire() as conn:
                    rows = await conn.fetch("SELECT id,title,status FROM laws ORDER BY id DESC LIMIT 10")
                text = '📚 <b>Законы и законопроекты</b>\n\n' + ('\n'.join(f"№{r['id']} — {html_escape(r['title'])} · {html_escape(r['status'])}" for r in rows[:10]) if rows else 'Пока нет законопроектов.')
                buttons = [[(f"📄 №{r['id']} · {r['title'][:30]}", f"law_view:{r['id']}")] for r in rows]
                buttons.append([('⬅️ Назад','documents')])
                await edit(text, buttons)
            elif data == 'news':
                async with pool.acquire() as conn: rows = await conn.fetch('SELECT id,text,created_at FROM news ORDER BY id DESC LIMIT 6')
                text = '📰 <b>Газета</b>\n\n' + ('\n\n'.join(f"#{r['id']}\n{html_escape((r['text'] or '')[:350])}" for r in rows[:6]) if rows else 'Новостей пока нет.')
                await edit(text, [[('⬅️ Назад','main')]])
            elif data == 'results':
                await edit(await election_results_text(pool), [[('⬅️ Назад','main')]])
            elif data == 'my_party':
                username = normalize_username(callback.from_user.username, uid)
                async with pool.acquire() as conn:
                    member = await conn.fetchrow("SELECT p.id,p.name,p.emoji,p.description,p.leader_username,p.leader_user_id FROM party_members pm JOIN parties p ON p.id=pm.party_id WHERE (pm.user_id=$2 OR LOWER(pm.username)=LOWER($1)) AND p.status='active' ORDER BY CASE WHEN pm.user_id=$2 THEN 0 ELSE 1 END LIMIT 1", username, uid)
                if not member:
                    async with pool.acquire() as conn:
                        has_role = await conn.fetchval("SELECT 1 FROM subjects s JOIN subject_roles sr ON sr.subject_id=s.id JOIN roles r ON r.id=sr.role_id WHERE s.user_id=$1 AND r.code='party_leader'", uid)
                        memberships = await conn.fetch("SELECT p.id FROM party_members pm JOIN parties p ON p.id=pm.party_id WHERE p.status='active' AND (pm.user_id=$1 OR LOWER(pm.username)=LOWER($2))", uid, username)
                        if has_role and len(memberships)==1:
                            await conn.execute("UPDATE parties SET leader_user_id=$1,leader_username=$2 WHERE id=$3 AND status='active'", uid, username, memberships[0]['id'])
                            member = await conn.fetchrow("SELECT p.id,p.name,p.emoji,p.description,p.leader_username,p.leader_user_id FROM parties p WHERE p.id=$1", memberships[0]['id'])
                    if not member:
                        await edit('Вы не состоите в активной партии.', [[('⬅️ К партиям','parties')]])
                if member:
                    async with pool.acquire() as conn:
                        has_leader_role = await conn.fetchval("SELECT 1 FROM subjects s JOIN subject_roles sr ON sr.subject_id=s.id JOIN roles r ON r.id=sr.role_id WHERE s.user_id=$1 AND r.code='party_leader'", uid)
                        member_party_count = await conn.fetchval("SELECT COUNT(*) FROM party_members pm JOIN parties p ON p.id=pm.party_id WHERE p.status='active' AND (pm.user_id=$1 OR LOWER(pm.username)=LOWER($2))", uid, username)
                        if has_leader_role and member_party_count == 1:
                            await conn.execute("UPDATE parties SET leader_user_id=$1,leader_username=$2 WHERE id=$3 AND status='active'", uid, username, member['id'])
                            member = await conn.fetchrow("SELECT id,name,emoji,description,leader_username,leader_user_id FROM parties WHERE id=$1", member['id'])
                    is_leader = member['leader_user_id'] == uid or normalize_username(member['leader_username']) == username
                    buttons = [[('🚪 Покинуть партию','party_leave')]] if not is_leader else [[('📋 Заявки на вступление','party_join_apps')]]
                    buttons.append([('⬅️ К партиям','parties')])
                    await edit(f"{member['emoji']} <b>{html_escape(member['name'])}</b>\n{html_escape(member['description'][:800])}\nЛидер: @{html_escape(member['leader_username'] or '')}", buttons)
            elif data == 'party_join_apps':
                username = normalize_username(callback.from_user.username, uid)
                async with pool.acquire() as conn:
                    party = await conn.fetchrow("SELECT id,name FROM parties WHERE status='active' AND (leader_user_id=$1 OR LOWER(COALESCE(leader_username,''))=LOWER($2)) ORDER BY CASE WHEN leader_user_id=$1 THEN 0 ELSE 1 END LIMIT 1", uid, username)
                    if not party:
                        await callback.answer('Вы не являетесь лидером активной партии.', show_alert=True); return
                    apps = await conn.fetch("SELECT id,author_username,author_user_id FROM applications WHERE type='join_party' AND status='pending' AND data->>'party_id'=$1 ORDER BY id LIMIT 10", str(party['id']))
                buttons = []
                for item in apps:
                    buttons.append([(f"✅ Принять №{item['id']} · @{item['author_username']}", f"party_join_approve:{item['id']}"),(f"❌ Отклонить №{item['id']}",f"party_join_reject:{item['id']}")])
                buttons.append([('⬅️ В мою партию','my_party')])
                body = '\n'.join(f"№{x['id']} · @{html_escape(x['author_username'])}" for x in apps) if apps else 'Ожидающих заявок нет.'
                await edit('📋 <b>Заявки на вступление</b> в партию «'+html_escape(party['name'])+'»\n\n'+body, buttons)
            elif data.startswith('party_join_approve:') or data.startswith('party_join_reject:'):
                approve = data.startswith('party_join_approve:')
                app_id = int(data.split(':',1)[1])
                username = normalize_username(callback.from_user.username, uid)
                async with pool.acquire() as conn:
                    async with conn.transaction():
                        app = await conn.fetchrow("SELECT * FROM applications WHERE id=$1 AND type='join_party' AND status='pending' FOR UPDATE", app_id)
                        if not app:
                            await callback.answer('Заявка уже обработана или не найдена.', show_alert=True); return
                        payload = app['data'] if isinstance(app['data'],dict) else json.loads(app['data'])
                        party = await conn.fetchrow("SELECT id,name,leader_user_id,leader_username,status FROM parties WHERE id=$1", int(payload['party_id']))
                        if not party or party['status'] != 'active' or not (party['leader_user_id']==uid or normalize_username(party['leader_username'])==username or admin_active):
                            await callback.answer('Только лидер этой партии или администратор может обработать заявку.', show_alert=True); return
                        if approve:
                            author_id = app['author_user_id'] or payload.get('user_id')
                            author_name = normalize_username(app['author_username'], author_id)
                            existing = await conn.fetchval("SELECT 1 FROM party_members WHERE (user_id=$1 AND $1 IS NOT NULL) OR LOWER(username)=LOWER($2)", author_id, author_name)
                            if existing:
                                raise ValueError('Этот человек уже состоит в партии.')
                            await conn.execute("INSERT INTO party_members(party_id,username,user_id) VALUES($1,$2,$3)", party['id'], author_name, author_id)
                            new_status='approved'
                            await conn.execute("INSERT INTO news(text) VALUES($1)", f"Гражданин @{author_name} вступил в партию «{party['name']}».")
                        else:
                            new_status='rejected'
                        await conn.execute("UPDATE applications SET status=$1,decided_at=$2 WHERE id=$3",new_status,to_db_dt(datetime.now(timezone.utc)),app_id)
                app_user_id = app['author_user_id']
                if app_user_id:
                    try: await bot.send_message(app_user_id, '✅ Ты принят в партию.' if approve else '❌ Заявка на вступление в партию отклонена.')
                    except TelegramAPIError: pass
                await edit(('Заявка одобрена.' if approve else 'Заявка отклонена.'), [[('📋 К заявкам','party_join_apps'),('👥 Моя партия','my_party')]])
            elif data == 'party_leave':
                username = normalize_username(callback.from_user.username, uid)
                async with pool.acquire() as conn:
                    is_leader = await conn.fetchval("SELECT 1 FROM parties WHERE status='active' AND (leader_user_id=$2 OR LOWER(leader_username)=LOWER($1)) LIMIT 1", username, uid)
                    if is_leader:
                        await callback.answer('Лидер не может выйти без передачи лидерства. Обратитесь к администратору.', show_alert=True); return
                    removed = await conn.fetchrow('DELETE FROM party_members WHERE user_id=$2 OR LOWER(username)=LOWER($1) RETURNING party_id', username, uid)
                    if removed: await conn.execute('INSERT INTO news(text) VALUES($1)', f'Гражданин @{username} покинул партию.')
                await edit('Вы вышли из партии.' if removed else 'Вы не состоите в партии.', [[('⬅️ К партиям','parties')]])
            elif data == 'parties':
                # Party directory: member counts and a separate details page for every party.
                async with pool.acquire() as conn:
                    rows = await conn.fetch("""SELECT p.id,p.name,p.emoji,p.description,p.leader_username,p.leader_user_id,
                        (SELECT COUNT(*) FROM party_members pm WHERE pm.party_id=p.id) AS member_count
                        FROM parties p WHERE p.status='active' ORDER BY p.name""")
                    my_membership = await conn.fetchrow("""SELECT p.id,p.name FROM party_members pm JOIN parties p ON p.id=pm.party_id
                        WHERE p.status='active' AND (pm.user_id=$1 OR LOWER(pm.username)=LOWER($2)) LIMIT 1""", uid, normalize_username(callback.from_user.username, uid))
                body = 'Выберите партию, чтобы посмотреть её состав, лидера и подать заявку на вступление.\n\n'
                if rows:
                    body += '\n'.join(f"{r['emoji']} <b>{html_escape(r['name'])}</b> · 👥 {r['member_count']} чел." for r in rows[:25])
                    if len(rows) > 25:
                        body += f'\n\nПоказаны первые 25 из {len(rows)} партий.'
                else:
                    body += 'Активных партий пока нет. Вы можете предложить создать первую.'
                buttons = [[(f"{r['emoji']} {r['name'][:28]} · {r['member_count']}", f"party_view:{r['id']}")] for r in rows[:25]]
                buttons += [[('➕ Подать заявку на создание','party_create')], [('👥 Моя партия','my_party')], [('🧾 Список кандидатов','party_my_list')], [('⬅️ Назад','main')]]
                if my_membership:
                    body += f'\n\n<b>Ваша партия:</b> {html_escape(my_membership["name"])}'
                await edit('🎭 <b>Партии государства</b>\n\n' + body, buttons)
            elif data.startswith('party_view:'):
                party_id = int(data.split(':',1)[1])
                async with pool.acquire() as conn:
                    party = await conn.fetchrow("""SELECT p.id,p.name,p.emoji,p.description,p.leader_username,p.leader_user_id,
                        (SELECT COUNT(*) FROM party_members pm WHERE pm.party_id=p.id) AS member_count
                        FROM parties p WHERE p.id=$1 AND p.status='active'""", party_id)
                    members = await conn.fetch("""SELECT pm.user_id,pm.username,pm.joined_at,s.full_name,
                        CASE WHEN pm.user_id=p.leader_user_id OR LOWER(pm.username)=LOWER(COALESCE(p.leader_username,'')) THEN TRUE ELSE FALSE END AS is_leader
                        FROM party_members pm JOIN parties p ON p.id=pm.party_id
                        LEFT JOIN subjects s ON s.user_id=pm.user_id OR LOWER(s.username)=LOWER(pm.username)
                        WHERE pm.party_id=$1 ORDER BY is_leader DESC,COALESCE(s.full_name,pm.username)""", party_id)
                    already_member = await conn.fetchval("SELECT 1 FROM party_members WHERE (user_id=$1) OR LOWER(username)=LOWER($2) LIMIT 1", uid, normalize_username(callback.from_user.username, uid))
                    pending = await conn.fetchval("SELECT 1 FROM applications WHERE type='join_party' AND status='pending' AND author_user_id=$1 AND data->>'party_id'=$2 LIMIT 1", uid, str(party_id))
                if not party:
                    await edit('Эта партия не найдена или уже распущена.', [[('⬅️ К списку партий','parties')]])
                else:
                    leader_name = 'не назначен'
                    if party['leader_user_id']:
                        leader_person = await subject(pool, party['leader_user_id'])
                        if leader_person:
                            leader_name = f"{leader_person['full_name']} (@{leader_person['username']})"
                    elif party['leader_username']:
                        leader_name = '@' + party['leader_username']
                    roster = []
                    for m in members:
                        full = m['full_name'] or ('@' + m['username'])
                        marker = '👑 ' if m['is_leader'] else '• '
                        handle = f" (@{m['username']})" if m['username'] and not m['username'].startswith('id') else ''
                        roster.append(marker + html_escape(full) + html_escape(handle))
                    details = f"{party['emoji']} <b>{html_escape(party['name'])}</b>\n{html_escape(party['description'] or 'Описание не добавлено.')}\n\n👑 <b>Лидер:</b> {html_escape(leader_name)}\n👥 <b>Участников:</b> {party['member_count']}\n\n<b>Состав партии:</b>\n" + ('\n'.join(roster[:70]) if roster else 'В списке участников пока никого нет.')
                    if len(roster) > 70:
                        details += f'\n… и ещё {len(roster)-70} участников.'
                    buttons = []
                    if not already_member and not pending:
                        buttons.append([('🤝 Подать заявку на вступление', f'party_apply:{party_id}')])
                    elif pending:
                        details += '\n\n🟡 Ваша заявка на вступление ожидает решения.'
                    elif already_member:
                        details += '\n\n✅ Вы уже состоите в партии.'
                    buttons.append([('⬅️ К списку партий','parties')])
                    await edit(details, buttons)
            elif data.startswith('party_apply:'):
                party_id = int(data.split(':',1)[1])
                username = normalize_username(callback.from_user.username, uid)
                async with pool.acquire() as conn:
                    async with conn.transaction():
                        party = await conn.fetchrow("SELECT id,name,leader_user_id,leader_username FROM parties WHERE id=$1 AND status='active' FOR UPDATE", party_id)
                        if not party:
                            raise ValueError('Партия больше не активна.')
                        existing = await conn.fetchval("SELECT 1 FROM party_members WHERE user_id=$1 OR LOWER(username)=LOWER($2) LIMIT 1", uid, username)
                        pending = await conn.fetchval("SELECT 1 FROM applications WHERE type='join_party' AND status='pending' AND author_user_id=$1 AND data->>'party_id'=$2 LIMIT 1", uid, str(party_id))
                        if existing:
                            raise ValueError('Вы уже состоите в партии.')
                        if pending:
                            raise ValueError('Ваша заявка уже ожидает решения.')
                        app = await conn.fetchrow("""INSERT INTO applications(type,author_username,author_user_id,data,status)
                            VALUES('join_party',$1,$2,$3::jsonb,'pending') RETURNING id""", username, uid,
                            json.dumps({'party_id': party_id, 'party_name': party['name'], 'user_id': uid}))
                await edit(f"🟡 Заявка №{app['id']} на вступление в партию «{html_escape(party['name'])}» отправлена лидеру.", [[('⬅️ К партии',f'party_view:{party_id}'),('🎭 Все партии','parties')]])
                leader_id = party['leader_user_id']
                if leader_id:
                    try:
                        await bot.send_message(leader_id, f"📋 Новая заявка №{app['id']} на вступление в партию «{html_escape(party['name'])}» от @{html_escape(username)}. Откройте 🎭 Партии → 👥 Моя партия → 📋 Заявки на вступление.")
                    except TelegramAPIError:
                        pass
                try:
                    await bot.send_message(ADMIN_ID, f"Новая заявка на вступление №{app['id']} в партию «{html_escape(party['name'])}».")
                except TelegramAPIError:
                    pass
            elif data == 'party_create':
                await state.set_state(Form.create_party_name)
                if msg: await msg.answer('Введите название партии:')
            elif data == 'party_join':
                await state.set_state(Form.join_party)
                if msg: await msg.answer('Введите точное название партии:')
            elif data == 'party_my_list':
                async with pool.acquire() as conn:
                    election = await conn.fetchrow("SELECT id FROM duma_elections WHERE status='active' AND ends_at > $1 ORDER BY id DESC LIMIT 1", to_db_dt(datetime.now(timezone.utc)))
                    party = await conn.fetchrow("""SELECT id,name FROM parties WHERE status='active'
                        AND (leader_user_id=$1 OR LOWER(COALESCE(leader_username,''))=LOWER($2))
                        ORDER BY CASE WHEN leader_user_id=$1 THEN 0 ELSE 1 END,id LIMIT 1""", uid, normalize_username(callback.from_user.username, uid))
                if not election:
                    await callback.answer('Сейчас нет активных выборов в ГосДуму.', show_alert=True); return
                if not party:
                    # Recover a legacy party if the account still has the leader role and is its only member.
                    async with pool.acquire() as conn:
                        has_role = await conn.fetchval("""SELECT 1 FROM subjects s JOIN subject_roles sr ON sr.subject_id=s.id
                            JOIN roles r ON r.id=sr.role_id WHERE s.user_id=$1 AND r.code='party_leader'""", uid)
                        memberships = await conn.fetch("""SELECT p.id,p.name FROM party_members pm JOIN parties p ON p.id=pm.party_id
                            WHERE p.status='active' AND (pm.user_id=$1 OR LOWER(pm.username)=LOWER($2))""", uid, normalize_username(callback.from_user.username, uid))
                        if has_role and len(memberships) == 1:
                            party = await conn.fetchrow("UPDATE parties SET leader_user_id=$2,leader_username=$3 WHERE id=$1 AND status='active' RETURNING id,name", memberships[0]['id'], uid, normalize_username(callback.from_user.username, uid))
                if not party:
                    await callback.answer('Не удалось подтвердить лидерство. Напишите администратору: нужно привязать Telegram ID к партии.', show_alert=True); return
                async with pool.acquire() as conn:
                    await conn.execute("UPDATE parties SET leader_user_id=$2,leader_username=$3 WHERE id=$1", party['id'], uid, normalize_username(callback.from_user.username, uid))
                await state.update_data(party_id=party['id'], election_id=election['id'])
                await state.set_state(Form.party_list)
                if msg: await msg.answer('Введите от 1 до 3 username кандидатов, по одному на строку. Только граждане.')
            elif data == 'elections':
                async with pool.acquire() as conn:
                    pe = await conn.fetchrow("SELECT id,ends_at FROM pres_elections WHERE status='active' ORDER BY id DESC LIMIT 1")
                    de = await conn.fetchrow("SELECT id,ends_at FROM duma_elections WHERE status='active' ORDER BY id DESC LIMIT 1")
                rows = []
                details = []
                now_db = to_db_dt(datetime.now(timezone.utc))
                if pe and pe['ends_at'] > now_db:
                    rows.append([('🗳 Выборы Президента','vote_pres')])
                    deadline = from_db_dt(pe['ends_at'])
                    details.append('Президентские выборы завершатся: ' + deadline.strftime('%d.%m.%Y %H:%M МСК'))
                if de and de['ends_at'] > now_db:
                    rows.append([('🏛 Выборы Госдумы','vote_duma')])
                    deadline = from_db_dt(de['ends_at'])
                    details.append('Выборы Госдумы завершатся: ' + deadline.strftime('%d.%m.%Y %H:%M МСК'))
                rows.append([('⬅️ Назад','main')])
                await edit('🗳 Активные голосования:\n' + ('\n'.join(details) if details else 'Сейчас активных голосований нет.'), rows)
            elif data == 'vote_pres':
                async with pool.acquire() as conn:
                    election = await conn.fetchrow("SELECT id,ends_at FROM pres_elections WHERE status='active' ORDER BY id DESC LIMIT 1")
                    already = await conn.fetchval('SELECT 1 FROM votes WHERE election_id=$1 AND user_id=$2', election['id'], uid) if election else None
                if not election or election['ends_at'] <= to_db_dt(datetime.now(timezone.utc)):
                    await callback.answer('Активных президентских выборов нет.', show_alert=True); return
                if any(person and person['full_name'].casefold() == n.casefold() for n in CANDIDATE_FULL_NAMES.values()) or 'presidential_candidate' in role_set:
                    await callback.answer('Кандидаты в Президенты не голосуют.', show_alert=True); return
                if already:
                    await callback.answer('Вы уже проголосовали.', show_alert=True); return
                await edit('Выберите кандидата:', [[(f'{key}. {name}',f'pres_vote:{election["id"]}:{key}')] for key,name in CANDIDATES.items()] + [[('⬅️ Назад','elections')]])
            elif data.startswith('pres_vote:'):
                _, eid, candidate = data.split(':',2); eid = int(eid)
                if candidate not in CANDIDATES: raise ValueError('Unknown candidate')
                if person['full_name'].casefold() in {n.casefold() for n in CANDIDATE_FULL_NAMES.values()} or 'presidential_candidate' in role_set:
                    await callback.answer('Кандидаты не могут голосовать.', show_alert=True); return
                async with pool.acquire() as conn:
                    election = await conn.fetchrow("SELECT id,ends_at FROM pres_elections WHERE id=$1 AND status='active'", eid)
                    if not election or election['ends_at'] <= to_db_dt(datetime.now(timezone.utc)):
                        await callback.answer('Выборы завершены.', show_alert=True); return
                    await conn.execute("INSERT INTO votes(election_id,user_id,username,voter_name,candidate_id) VALUES($1,$2,$3,$4,$5)", eid, uid, normalize_username(callback.from_user.username,uid), person['full_name'], candidate)
                if msg:
                    try: await msg.answer_video(VIDEO_FILE_ID, caption='Ваш голос учтён.')
                    except TelegramAPIError: await msg.answer('Ваш голос учтён, но видео отправить не удалось.')
                await edit('Спасибо за участие!', [[('⬅️ В меню','main')]])
            elif data == 'vote_duma':
                username = normalize_username(callback.from_user.username,uid)
                async with pool.acquire() as conn:
                    election = await conn.fetchrow("SELECT id,ends_at FROM duma_elections WHERE status='active' ORDER BY id DESC LIMIT 1")
                    leader = await conn.fetchval("SELECT 1 FROM parties WHERE status='active' AND (leader_user_id=$1 OR LOWER(COALESCE(leader_username,''))=LOWER($2))", uid, username)
                    existing = await conn.fetchval('SELECT 1 FROM duma_votes WHERE election_id=$1 AND voter_user_id=$2', election['id'],uid) if election else None
                    parties = await conn.fetch("""SELECT p.id,p.name,p.emoji FROM parties p WHERE p.status='active' AND EXISTS
                        (SELECT 1 FROM party_lists pl WHERE pl.party_id=p.id AND pl.election_id=$1) ORDER BY p.id""", election['id']) if election else []
                if not election or election['ends_at'] <= to_db_dt(datetime.now(timezone.utc)):
                    await callback.answer('Активных выборов Госдумы нет.', show_alert=True); return
                if leader:
                    await callback.answer('Лидеры партий не голосуют на выборах Госдумы.', show_alert=True); return
                if existing:
                    await callback.answer('Вы уже проголосовали.', show_alert=True); return
                await edit('Выберите партию:', [[(f"{p['emoji']} {p['name']}",f"duma_vote:{election['id']}:{p['id']}")] for p in parties] + [[('⬅️ Назад','elections')]])
            elif data.startswith('duma_vote:'):
                _, eid, pid = data.split(':',2); eid=int(eid); pid=int(pid)
                username = normalize_username(callback.from_user.username,uid)
                async with pool.acquire() as conn:
                    election = await conn.fetchrow("SELECT id,ends_at FROM duma_elections WHERE id=$1 AND status='active'",eid)
                    leader = await conn.fetchval("SELECT 1 FROM parties WHERE status='active' AND (leader_user_id=$1 OR LOWER(COALESCE(leader_username,''))=LOWER($2))",uid,username)
                    eligible = await conn.fetchval('SELECT 1 FROM party_lists WHERE election_id=$1 AND party_id=$2',eid,pid)
                    if not election or election['ends_at'] <= to_db_dt(datetime.now(timezone.utc)) or not eligible:
                        await callback.answer('Выборы завершены или партия не участвует.',show_alert=True); return
                    if leader:
                        await callback.answer('Лидеры партий не голосуют.',show_alert=True); return
                    await conn.execute('INSERT INTO duma_votes(election_id,voter_username,voter_user_id,party_id) VALUES($1,$2,$3,$4)',eid,username,uid,pid)
                if msg:
                    try: await msg.answer_video(VIDEO_FILE_ID,caption='Ваш голос за партию учтён.')
                    except TelegramAPIError: await msg.answer('Ваш голос учтён, но видео отправить не удалось.')
                await edit('Спасибо за участие!',[[('⬅️ В меню','main')]])
            elif data.startswith('law_view:'):
                law_id = int(data.split(':', 1)[1])
                async with pool.acquire() as conn:
                    law = await conn.fetchrow('SELECT * FROM laws WHERE id=$1', law_id)
                    counts = await conn.fetchrow('SELECT COUNT(*) FILTER (WHERE vote) AS yes, COUNT(*) FILTER (WHERE NOT vote) AS no FROM law_votes WHERE law_id=$1', law_id)
                    my_vote = await conn.fetchval('SELECT vote FROM law_votes WHERE law_id=$1 AND user_id=$2', law_id, uid)
                if not law:
                    await callback.answer('Законопроект не найден.', show_alert=True); return
                body = f"📜 <b>Законопроект №{law_id}</b>\n<b>{html_escape(law['title'])}</b>\n\n{html_escape(law['description'][:3000])}\n\nСтатус: <b>{html_escape(law['status'])}</b>\nВ Думе: за {counts['yes']}, против {counts['no']}"
                buttons = []
                if law['status'] == 'duma' and deputy and my_vote is None:
                    buttons += [[('✅ За','law_vote:'+str(law_id)+':1'),('❌ Против','law_vote:'+str(law_id)+':0')]]
                if law['status'] == 'government' and ('minister' in role_set or 'prime_minister' in role_set or 'deputy_prime_minister' in role_set or admin):
                    buttons += [[('✅ Правительство одобряет',f'law_stage:{law_id}:government:1'),('❌ Отклонить',f'law_stage:{law_id}:government:0')]]
                if law['status'] == 'president' and president:
                    buttons += [[('✍️ Подписать закон',f'law_sign:{law_id}'),('❌ Отклонить',f'law_sign_reject:{law_id}')]]
                buttons.append([('⬅️ К законам','laws')])
                await edit(body, buttons)
            elif data.startswith('law_vote:'):
                _, raw_id, raw_vote = data.split(':', 2)
                law_id, vote = int(raw_id), raw_vote == '1'
                if not deputy:
                    await callback.answer('Голосовать по законопроектам могут только депутаты.', show_alert=True); return
                async with pool.acquire() as conn:
                    async with conn.transaction():
                        law = await conn.fetchrow("SELECT id,status FROM laws WHERE id=$1 FOR UPDATE", law_id)
                        if not law or law['status'] != 'duma':
                            await callback.answer('Этот законопроект уже не голосуется в Думе.', show_alert=True); return
                        await conn.execute('INSERT INTO law_votes(law_id,user_id,vote) VALUES($1,$2,$3)', law_id, uid, vote)
                        total = await conn.fetchval("SELECT COUNT(DISTINCT sr.subject_id) FROM subject_roles sr JOIN roles r ON r.id=sr.role_id WHERE r.code='deputy'")
                        cast = await conn.fetchval('SELECT COUNT(*) FROM law_votes WHERE law_id=$1', law_id)
                        quorum = max(1, total // 2 + 1)
                        if total > 0 and cast >= quorum:
                            yes = await conn.fetchval('SELECT COUNT(*) FROM law_votes WHERE law_id=$1 AND vote=TRUE', law_id)
                            await conn.execute("UPDATE laws SET status=$1 WHERE id=$2", 'government' if yes > cast / 2 else 'rejected', law_id)
                            if yes > cast / 2:
                                await conn.execute("INSERT INTO news(text) VALUES($1)", f"Законопроект №{law_id} «{(await conn.fetchval('SELECT title FROM laws WHERE id=$1',law_id))}» одобрен Госдумой и направлен в Правительство.")
                await callback.answer('Голос учтён.')
                await edit('Ваш голос по законопроекту учтён.', [[('📚 К законам','laws')]])
            elif data.startswith('law_stage:'):
                _, raw_id, stage, raw_vote = data.split(':', 3)
                law_id, vote = int(raw_id), raw_vote == '1'
                if not (('minister' in role_set) or ('prime_minister' in role_set) or ('deputy_prime_minister' in role_set) or ('central_bank_head' in role_set) or admin):
                    await callback.answer('Доступно только Правительству.', show_alert=True); return
                async with pool.acquire() as conn:
                    async with conn.transaction():
                        law = await conn.fetchrow("SELECT id,status FROM laws WHERE id=$1 FOR UPDATE", law_id)
                        if not law or law['status'] != 'government':
                            await callback.answer('Законопроект уже обработан Правительством.', show_alert=True); return
                        await conn.execute('INSERT INTO law_stage_votes(law_id,user_id,stage,vote) VALUES($1,$2,$3,$4)', law_id, uid, stage, vote)
                        total = await conn.fetchval("SELECT COUNT(DISTINCT sr.subject_id) FROM subject_roles sr JOIN roles r ON r.id=sr.role_id WHERE r.code IN ('minister','prime_minister','deputy_prime_minister','central_bank_head')")
                        cast = await conn.fetchval("SELECT COUNT(*) FROM law_stage_votes WHERE law_id=$1 AND stage='government'", law_id)
                        if total == 0:
                            total = 1
                        quorum = max(1, total // 2 + 1)
                        if cast >= quorum:
                            yes = await conn.fetchval("SELECT COUNT(*) FROM law_stage_votes WHERE law_id=$1 AND stage='government' AND vote=TRUE", law_id)
                            new_status = 'president' if yes > cast / 2 else 'rejected'
                            await conn.execute('UPDATE laws SET status=$1 WHERE id=$2', new_status, law_id)
                            if new_status == 'president':
                                await conn.execute("INSERT INTO news(text) VALUES($1)", f"Законопроект №{law_id} прошёл Правительство и направлен Президенту на подпись.")
                await callback.answer('Решение Правительства учтено.')
                await edit('Решение по законопроекту сохранено.', [[('📚 К законам','laws')]])
            elif data.startswith('law_sign:') or data.startswith('law_sign_reject:'):
                if not president:
                    await callback.answer('Подписать закон может только Президент.', show_alert=True); return
                reject = data.startswith('law_sign_reject:')
                law_id = int(data.split(':', 1)[1])
                async with pool.acquire() as conn:
                    row = await conn.fetchrow("UPDATE laws SET status=$1 WHERE id=$2 AND status='president' RETURNING title", 'rejected' if reject else 'enacted', law_id)
                    if row:
                        await conn.execute('INSERT INTO news(text) VALUES($1)', f"{'Президент отклонил' if reject else 'Президент подписал'} закон №{law_id}: {row['title']}")
                if row and not reject:
                    await broadcast(pool, bot, f"📜 Закон подписан Президентом: {html_escape(row['title'])} (№{law_id}).")
                await edit(('Закон отклонён.' if reject else 'Закон подписан и опубликован.') if row else 'Закон не найден на подписи.', [[('📚 К законам','laws')]])
            elif data == 'pres_laws':
                if not president:
                    await callback.answer('Нет доступа.', show_alert=True); return
                async with pool.acquire() as conn:
                    rows = await conn.fetch("SELECT id,title,status FROM laws WHERE status='president' ORDER BY id")
                buttons = [[(f"№{r['id']} · {r['title'][:35]}",f"law_view:{r['id']}")] for r in rows]
                buttons.append([('⬅️ Назад','president_panel')])
                await edit('📑 Законы, ожидающие подписи Президента:', buttons)
            elif data == 'appoint_menu':
                if not president:
                    await callback.answer('Нет доступа.', show_alert=True); return
                await edit('👔 <b>Назначение должностных лиц</b>', [[('Министр','appoint:minister:Министр'),('Вице-премьер','appoint:deputy_prime_minister:Вице-премьер')],[('Премьер-министр','appoint:prime_minister:Премьер-министр'),('Глава ЦБ','appoint:central_bank_head:Глава ЦБ')],[('⬅️ Назад','president_panel')]])
            elif data.startswith('appoint:'):
                if not president:
                    await callback.answer('Нет доступа.', show_alert=True); return
                _, role_code, role_name = data.split(':', 2)
                await state.update_data(role_code=role_code, role_name=role_name)
                await state.set_state(Form.minister_username)
                if msg: await msg.answer('Введите username гражданина для назначения. Отправьте /menu, чтобы отменить ввод.')
            elif data == 'pres_duma_start':
                if not president:
                    await callback.answer('Нет доступа.', show_alert=True); return
                await state.set_state(Form.duma_duration)
                if msg: await msg.answer('Введите окончание выборов по МСК, например 31.12.2026 23:59, или длительность в часах (0,1–720).')
            elif data == 'duma_panel':
                if not deputy: await callback.answer('Нет доступа.',show_alert=True); return
                await edit('🏛 Панель Госдумы', [[('📜 Внести закон','law_new')],[('🏛 Созвать собрание','meeting_new')],[('🗳 Назначить выборы Президента','pres_election_new')],[('⬅️ Назад','main')]])
            elif data == 'law_new':
                if not deputy: await callback.answer('Нет доступа.',show_alert=True); return
                await state.set_state(Form.law_title)
                if msg: await msg.answer('Введите название законопроекта:')
            elif data == 'meeting_new':
                if not deputy: await callback.answer('Нет доступа.',show_alert=True); return
                await state.set_state(Form.meeting_text)
                if msg: await msg.answer('Введите повестку собрания:')
            elif data == 'pres_election_new':
                if not deputy: await callback.answer('Нет доступа.',show_alert=True); return
                await state.set_state(Form.election_duration)
                if msg: await msg.answer('Введите окончание выборов по МСК, например 31.12.2026 23:59, или длительность в часах (0,1–720).')
            elif data == 'president_panel':
                if not president: await callback.answer('Нет доступа.',show_alert=True); return
                await edit('👑 Панель Президента', [[('📢 Создать указ','decree_new')],[('📜 Обращение к народу','address_new')],[('📣 Призыв вступать в партии','address_parties')],[('👔 Назначить должностное лицо','appoint_menu')],[('🗳 Назначить выборы в Госдуму','pres_duma_start')],[('⚖️ Список правительства','government')],[('📑 Законы на подпись','pres_laws')],[('📋 Заявки на гражданство','president_apps')],[('🎭 Заявки на партии','president_party_apps')],[('⬅️ Назад','main')]])
            elif data == 'president_apps':
                if not president: await callback.answer('Нет доступа.',show_alert=True); return
                async with pool.acquire() as conn:
                    rows = await conn.fetch("SELECT id,type,author_username,data FROM applications WHERE type IN ('citizenship','create_party') AND status='pending' ORDER BY id LIMIT 20")
                lines = []
                for row in rows:
                    payload = row['data'] if isinstance(row['data'], dict) else json.loads(row['data'])
                    lines.append(f"№{row['id']} — {'Гражданство: ' + payload.get('full_name', 'ФИО не указано') if row['type']=='citizenship' else 'Создание партии: ' + payload.get('name', 'без названия')} (@{row['author_username']})")
                buttons = [[(f"✅ Одобрить №{row['id']}", f"app_approve:{row['id']}"), (f"❌ Отклонить №{row['id']}", f"pres_reject:{row['id']}")] for row in rows]
                buttons.append([('⬅️ Назад','president_panel')])
                await edit('📋 <b>Заявки Президента</b>\nЗдесь заявки на гражданство и создание партий.\n\n' + ('\n'.join(lines) if lines else 'Ожидающих заявок нет.'), buttons)
            elif data == 'president_party_apps':
                if not president:
                    await callback.answer('Нет доступа.', show_alert=True); return
                async with pool.acquire() as conn:
                    rows = await conn.fetch("SELECT id,author_username,data FROM applications WHERE type='create_party' AND status='pending' ORDER BY id LIMIT 5")
                lines=[]; buttons=[]
                for row in rows:
                    payload = row['data'] if isinstance(row['data'], dict) else json.loads(row['data'])
                    lines.append(f"№{row['id']} · {html_escape(payload.get('name','Без названия'))} · лидер @{html_escape(payload.get('leader_username', row['author_username']))}\n{html_escape(payload.get('description','')[:200])}")
                    buttons.append([(f"✅ Одобрить №{row['id']}", f"app_approve:{row['id']}"), (f"❌ Отклонить №{row['id']}", f"pres_reject:{row['id']}")])
                buttons.append([('⬅️ Назад','president_panel')])
                await edit('🎭 <b>Заявки на создание партий</b>\n\n' + ('\n\n'.join(lines) if lines else 'Заявок нет.'), buttons)
            elif data == 'decree_new':
                if not president: await callback.answer('Нет доступа.',show_alert=True); return
                await state.set_state(Form.decree_title)
                if msg: await msg.answer('Введите заголовок указа:')
            elif data in {'address_new', 'address_parties'}:
                if not president: await callback.answer('Нет доступа.',show_alert=True); return
                await state.set_state(Form.address_text)
                if msg:
                    if data == 'address_parties':
                        await msg.answer('Введите призыв к гражданам вступать в партии. Сообщение будет отправлено всем гражданам и опубликовано в газете. Например: «Граждане! Ознакомьтесь с программами партий и подавайте заявки на вступление через меню 🎭 Партии».')
                    else:
                        await msg.answer('Введите текст обращения к народу. Оно будет отправлено всем гражданам и опубликовано в газете:')
            elif data == 'minister_new':
                if not president: await callback.answer('Нет доступа.',show_alert=True); return
                async with pool.acquire() as conn: citizens = await conn.fetch("SELECT user_id,username,full_name FROM subjects WHERE is_citizen=TRUE ORDER BY full_name LIMIT 50")
                await state.update_data(role_code='minister',role_name='Министр')
                await state.set_state(Form.minister_username)
                if msg: await msg.answer('Введите username гражданина для назначения министром:\n'+'\n'.join(f"{x['full_name']} — @{x['username']}" for x in citizens))
            elif data == 'government':
                if not president: await callback.answer('Нет доступа.',show_alert=True); return
                async with pool.acquire() as conn:
                    rows = await conn.fetch("""SELECT s.full_name,s.username,r.name,r.emoji FROM subjects s JOIN subject_roles sr ON sr.subject_id=s.id JOIN roles r ON r.id=sr.role_id WHERE r.code IN ('minister','deputy_prime_minister','prime_minister','central_bank_head') ORDER BY r.name,s.full_name""")
                await edit('⚖️ <b>Правительство</b>\n'+('\n'.join(f"{r['emoji']} {html_escape(r['name'])}: {html_escape(r['full_name'])} (@{html_escape(r['username'])})" for r in rows) if rows else 'Назначенных министров пока нет.'),[[('⬅️ Назад','president_panel')]])
            elif data == 'admin_panel':
                if not admin: await callback.answer('Доступ только главному администратору.',show_alert=True); return
                active = await is_admin(pool,uid)
                await edit('🛠 Админка '+('активна' if active else 'заблокирована'), [[('🔓 Активировать админку','admin_activate')],[('🔒 Деактивировать админку','admin_deactivate')],[('👥 Субъекты','admin_subjects')],[('🎭 Партии','admin_parties')],[('📋 Заявки','admin_apps')],[('🗂 История заявок','admin_app_history')],[('🧑‍⚖️ Управление ролями','admin_role_menu')],[('⭐ Изменить репутацию','admin_reputation')],[('📚 Журнал действий','admin_audit')],[('📊 Статистика выборов','admin_results')],[('📢 Опубликовать всем','admin_broadcast')],[('🗳 Начать выборы Госдумы','admin_duma_start')],[('⏹ Завершить выборы Госдумы','admin_duma_finish')],[('👑 Снять Президента','admin_remove_president')],[('🔄 Сбросить голоса','admin_reset_votes')],[('⬅️ Назад','main')]])
            elif data == 'admin_activate' or data == 'admin_deactivate':
                if not admin: await callback.answer('Нет доступа.',show_alert=True); return
                async with pool.acquire() as conn: await conn.execute('UPDATE admin_sessions SET is_active=$1 WHERE user_id=$2',data=='admin_activate',uid)
                await edit('Админка '+('активирована.' if data=='admin_activate' else 'деактивирована.'),[[('⬅️ В админку','admin_panel')]])
            elif data.startswith('admin_') and not admin:
                await callback.answer('Доступ только главному администратору.',show_alert=True); return
            elif data == 'admin_role_menu':
                if not admin or not admin_active:
                    await callback.answer('Сначала активируйте админку.', show_alert=True); return
                rows = [[('➕ ' + name, 'admin_role_set:' + code)] for code,name,_,_ in DEFAULT_ROLES if code != 'citizen']
                rows += [[('➖ ' + name, 'admin_role_remove:' + code)] for code,name,_,_ in DEFAULT_ROLES if code != 'citizen']
                rows.append([('⬅️ Назад','admin_panel')])
                await edit('🧑‍⚖️ Выберите роль, затем отправьте username в Telegram, например @Nikolas_Connor. Telegram ID тоже поддерживается. Отмена: /cancel', rows)
            elif data.startswith('admin_role_set:') or data.startswith('admin_role_remove:'):
                if not admin or not admin_active:
                    await callback.answer('Сначала активируйте админку.', show_alert=True); return
                action, code = data.split(':', 1)
                if code not in {r[0] for r in DEFAULT_ROLES} or code == 'citizen':
                    await callback.answer('Недопустимая роль.', show_alert=True); return
                await state.update_data(role_code=code, role_action='set' if action == 'admin_role_set' else 'remove')
                await state.set_state(Form.admin_role_target)
                if msg: await msg.answer('Введите username гражданина, например @Nikolas_Connor. Можно также указать Telegram ID. Отмена: /cancel')
            elif data == 'admin_reputation':
                if not admin or not admin_active:
                    await callback.answer('Сначала активируйте админку.', show_alert=True); return
                await state.set_state(Form.admin_reputation_target)
                if msg: await msg.answer('Введите Telegram ID субъекта. Отмена: /cancel')
            elif data == 'admin_audit':
                if not admin or not admin_active:
                    await callback.answer('Сначала активируйте админку.', show_alert=True); return
                async with pool.acquire() as conn:
                    rows = await conn.fetch('SELECT actor_id,action,target,created_at FROM audit_log ORDER BY id DESC LIMIT 20')
                body = '\n'.join(f"{r['created_at']:%d.%m %H:%M} · {r['action']} · {r['target']} · {r['actor_id']}" for r in rows)
                await edit('📚 <b>Журнал действий</b>\n\n' + (body or 'Записей пока нет.'), [[('⬅️ Назад','admin_panel')]])
            elif data == 'admin_results':
                if not admin or not admin_active:
                    await callback.answer('Сначала активируйте админку.', show_alert=True); return
                await edit(await election_results_text(pool), [[('⬅️ Назад','admin_panel')]])
            elif data == 'admin_subjects':
                async with pool.acquire() as conn: rows = await conn.fetch('SELECT user_id,full_name,username,is_citizen,reputation FROM subjects ORDER BY id DESC LIMIT 30')
                await edit('👥 <b>Субъекты</b>\n'+('\n'.join(f"{html_escape(r['full_name'])} (@{html_escape(r['username'])}) — ID {r['user_id']}, гражданин: {r['is_citizen']}, репутация: {r['reputation']}" for r in rows) if rows else 'Субъектов нет.'),[[('⬅️ Назад','admin_panel')]])
            elif data == 'admin_parties':
                async with pool.acquire() as conn: rows = await conn.fetch("SELECT id,name,status FROM parties ORDER BY id DESC LIMIT 30")
                await edit('🎭 <b>Партии</b>\n'+('\n'.join(f"#{r['id']} {html_escape(r['name'])} — {html_escape(r['status'])}" for r in rows) if rows else 'Партий нет.'),[[('⚰️ Роспуск партии по ID','admin_dissolve_prompt')],[('👑 Сменить лидера партии','admin_leader_prompt')],[('⚰️ Кладбище партий','admin_cemetery')],[('⬅️ Назад','admin_panel')]])
            elif data == 'admin_dissolve_prompt':
                await state.update_data(admin_action='dissolve_party')
                await state.set_state(Form.admin_application_decision)
                if msg: await msg.answer('Введите ID партии для роспуска:')
            elif data == 'admin_leader_prompt':
                if not admin or not admin_active:
                    await callback.answer('Сначала активируйте админку.', show_alert=True); return
                await state.set_state(Form.admin_leader_party)
                if msg: await msg.answer('Введите ID активной партии, лидер которой меняется. Отмена: /cancel')
            elif data == 'admin_cemetery':
                async with pool.acquire() as conn: rows = await conn.fetch("SELECT id,name FROM parties WHERE status='dissolved' ORDER BY id DESC")
                await edit('⚰️ <b>Кладбище партий</b>\n'+('\n'.join(f"#{r['id']} {r['name']}" for r in rows) if rows else 'Распущенных партий нет.'),[[('⬅️ Назад','admin_panel')]])
            elif data == 'admin_app_history':
                if not admin or not admin_active:
                    await callback.answer('Сначала активируйте админку.', show_alert=True); return
                async with pool.acquire() as conn:
                    rows = await conn.fetch("SELECT id,type,author_username,status,created_at,decided_at FROM applications WHERE status<>'pending' ORDER BY id DESC LIMIT 20")
                body = '\n'.join(f"№{r['id']} · {r['type']} · {r['status']} · @{html_escape(r['author_username'])}" for r in rows)
                await edit('🗂 <b>История заявок</b>\n\n'+(body or 'История пока пуста.'), [[('⬅️ Назад','admin_panel')]])
            elif data == 'admin_apps':
                async with pool.acquire() as conn: rows = await conn.fetch("SELECT id,type,author_username,status,data FROM applications WHERE status='pending' ORDER BY id LIMIT 10")
                lines=[]
                for r in rows: lines.append(f"#{r['id']} {r['type']} от @{r['author_username']}\n{html_escape(json.dumps(r['data'],ensure_ascii=False)[:120])}")
                await edit('📋 <b>Ожидающие заявки</b>\n\n'+('\n\n'.join(lines) if lines else 'Заявок нет.'),[[('Одобрить заявку №'+str(r['id']),f"app_approve:{r['id']}")] for r in rows]+[[('Отклонить заявку по ID','admin_reject_prompt')],[('⬅️ Назад','admin_panel')]])
            elif data.startswith('app_approve:'):
                app_id=int(data.split(':')[1])
                async with pool.acquire() as conn:
                    app=await conn.fetchrow("SELECT * FROM applications WHERE id=$1 AND status='pending'",app_id)
                    if not app: await callback.answer('Заявка уже обработана или не найдена.',show_alert=True); return
                    # The President may approve citizenship applications; only the main admin
                    # may approve party creation and party-joining applications.
                    if admin and not admin_active:
                        await callback.answer('Сначала активируйте админку.', show_alert=True); return
                    if not admin and not (president and app['type'] in {'citizenship', 'create_party'}):
                        await callback.answer('Нет прав для обработки этой заявки.', show_alert=True); return
                    payload=app['data'] if isinstance(app['data'],dict) else json.loads(app['data'])
                    if app['type']=='citizenship':
                        target_id=int(payload['user_id'])
                        await conn.execute('UPDATE subjects SET is_citizen=TRUE WHERE user_id=$1',target_id)
                        srow=await conn.fetchrow('SELECT id FROM subjects WHERE user_id=$1',target_id)
                        role=await conn.fetchrow("SELECT id FROM roles WHERE code='citizen'")
                        if srow and role: await conn.execute('INSERT INTO subject_roles(subject_id,role_id) VALUES($1,$2) ON CONFLICT DO NOTHING',srow['id'],role['id'])
                        if str(payload.get('full_name', '')).casefold() in {n.casefold() for n in CANDIDATE_FULL_NAMES.values()}:
                            await conn.execute("INSERT INTO subject_roles(subject_id,role_id) SELECT s.id,r.id FROM subjects s CROSS JOIN roles r WHERE s.user_id=$1 AND r.code='presidential_candidate' ON CONFLICT DO NOTHING", target_id)
                        await conn.execute('INSERT INTO news(text) VALUES($1)', f"Новый гражданин: {payload.get('full_name', 'Гражданин')}")
                    elif app['type']=='create_party':
                        leader=payload['leader_username']
                        leader_user_id = payload.get('leader_user_id')
                        if not leader_user_id:
                            leader_user_id = await conn.fetchval("SELECT user_id FROM subjects WHERE LOWER(username)=LOWER($1)", leader)
                        if not leader_user_id:
                            raise ValueError('Не удалось определить Telegram ID лидера партии. Восстановите профиль заявителя и повторите.')
                        party=await conn.fetchrow("INSERT INTO parties(name,emoji,description,leader_username,leader_user_id,status) VALUES($1,$2,$3,$4,$5,'active') RETURNING id",payload['name'],payload['emoji'],payload['description'],leader,leader_user_id)
                        await conn.execute('INSERT INTO party_members(party_id,username,user_id) VALUES($1,$2,$3) ON CONFLICT DO NOTHING',party['id'],leader,leader_user_id)
                        await conn.execute("INSERT INTO subject_roles(subject_id,role_id) SELECT s.id,r.id FROM subjects s CROSS JOIN roles r WHERE s.user_id=$1 AND s.is_citizen=TRUE AND r.code='party_leader' ON CONFLICT DO NOTHING", int(leader_user_id))
                        await conn.execute('INSERT INTO news(text) VALUES($1)', f"Создана партия «{payload['name']}» во главе с @{leader}.")
                    elif app['type']=='join_party':
                        pid=int(payload['party_id']); username=normalize_username(app['author_username'], app['author_user_id'])
                        author_user_id = app['author_user_id'] or payload.get('user_id') or await conn.fetchval("SELECT user_id FROM subjects WHERE LOWER(username)=LOWER($1)", username)
                        party_active = await conn.fetchval("SELECT 1 FROM parties WHERE id=$1 AND status='active'", pid)
                        existing=await conn.fetchval('SELECT 1 FROM party_members WHERE (user_id=$1 AND $1 IS NOT NULL) OR LOWER(username)=LOWER($2)',author_user_id,username)
                        if not party_active:
                            raise ValueError('Партия больше не активна')
                        if existing:
                            raise ValueError('Пользователь уже состоит в партии')
                        await conn.execute('INSERT INTO party_members(party_id,username,user_id) VALUES($1,$2,$3)',pid,username,author_user_id)
                        await conn.execute('INSERT INTO news(text) VALUES($1)', f"Гражданин @{username} вступил в партию «{payload.get('party_name', pid)}».")
                    await conn.execute("UPDATE applications SET status='approved',decided_at=$1 WHERE id=$2",to_db_dt(datetime.now(timezone.utc)),app_id)
                await audit(pool, uid, 'application_approved', str(app_id), {'type': app['type']})
                try:
                    if app['author_user_id']:
                        await bot.send_message(app['author_user_id'], '✅ Гражданство одобрено.' if app['type']=='citizenship' else '✅ Партия создана.' if app['type']=='create_party' else '✅ Ты принят в партию.')
                    else:
                        await notify_user(bot,pool,app['author_username'],f'Ваша заявка №{app_id} одобрена.')
                except TelegramAPIError:
                    pass
                await edit(f'Заявка №{app_id} одобрена.',[[('⬅️ К заявкам','admin_apps')]])
            elif data.startswith('pres_reject:'):
                if not president: await callback.answer('Нет доступа.', show_alert=True); return
                app_id = int(data.split(':', 1)[1])
                async with pool.acquire() as conn:
                    row = await conn.fetchrow("UPDATE applications SET status='rejected',decided_at=$1 WHERE id=$2 AND type IN ('citizenship','create_party') AND status='pending' RETURNING author_username,author_user_id", to_db_dt(datetime.now(timezone.utc)), app_id)
                if row:
                    await audit(pool, uid, 'application_rejected_by_president', str(app_id), {'author': row['author_username']})
                    try:
                        if row['author_user_id']: await bot.send_message(row['author_user_id'], f'Ваша заявка №{app_id} отклонена Президентом.')
                        else: await notify_user(bot, pool, row['author_username'], f'Ваша заявка №{app_id} отклонена Президентом.')
                    except TelegramAPIError: pass
                    await edit(f'Заявка №{app_id} отклонена.', [[('⬅️ К заявкам','president_apps')]])
                else:
                    await callback.answer('Заявка не найдена или уже обработана.', show_alert=True)
            elif data == 'admin_reject_prompt':
                await state.update_data(admin_action='reject_application')
                await state.set_state(Form.admin_application_decision)
                if msg: await msg.answer('Введите ID заявки для отклонения:')
            elif data == 'admin_broadcast':
                await state.set_state(Form.broadcast_text)
                if msg: await msg.answer('Введите текст рассылки всем гражданам:')
            elif data == 'admin_duma_start':
                await state.set_state(Form.duma_duration)
                if msg: await msg.answer('Введите окончание выборов по МСК, например 31.12.2026 23:59, или длительность в часах (0,1–720).')
            elif data == 'admin_duma_finish':
                election=await active_election(pool,'duma_elections')
                if not election: await callback.answer('Активных выборов нет.',show_alert=True); return
                result=await finish_duma_election(pool,election['id'])
                await audit(pool, uid, 'duma_election_finished_early', str(election['id']), result)
                await edit('Выборы Госдумы завершены.\nРезультаты: '+json.dumps(result,ensure_ascii=False),[[('⬅️ В админку','admin_panel')]])
            elif data == 'admin_remove_president':
                async with pool.acquire() as conn:
                    async with conn.transaction():
                        await conn.execute("DELETE FROM subject_roles sr USING roles r WHERE sr.role_id=r.id AND r.code='president'")
                        await conn.execute("DELETE FROM role_terms WHERE role_code='president'")
                        await conn.execute("UPDATE pres_elections SET status='finished' WHERE status='active'")
                await audit(pool, uid, 'president_removed')
                await edit('Полномочия Президента сняты, активные выборы завершены.',[[('⬅️ В админку','admin_panel')]])
            elif data == 'admin_reset_votes':
                async with pool.acquire() as conn:
                    await conn.execute('DELETE FROM votes'); await conn.execute('DELETE FROM duma_votes'); await conn.execute('DELETE FROM law_votes'); await conn.execute('DELETE FROM law_stage_votes')
                await edit('Голоса всех текущих и прошлых выборов удалены.',[[('⬅️ В админку','admin_panel')]])
            else:
                await callback.answer('Неизвестная кнопка или устаревшее меню.',show_alert=True)
        except asyncpg.UniqueViolationError:
            if callback.message:
                await callback.message.answer('Ваше действие уже было зарегистрировано. Повторять его нельзя.')
        except Exception:
            logging.exception('Ошибка обработки callback data=%r user_id=%s', callback.data, callback.from_user.id)
            if callback.message:
                try: await callback.message.answer('Произошла ошибка при обработке действия. Попробуйте снова.')
                except TelegramAPIError: pass
        finally:
            try:
                await callback.answer()
            except TelegramAPIError:
                pass

    @dp.message(Form.admin_role_target)
    async def admin_role_target(message: Message, state: FSMContext):
        if not message.from_user or message.from_user.id != ADMIN_ID or not await is_admin(pool, ADMIN_ID):
            await state.clear(); await message.answer('Нет доступа или админка выключена.'); return
        raw_target = (message.text or '').strip()
        if not raw_target:
            await message.answer('Введите username, например @Nikolas_Connor, или Telegram ID.'); return
        target = None
        target_id = None
        # Поддерживаем и username, и старый способ по Telegram ID.
        if re.fullmatch(r'\d+', raw_target):
            target_id = int(raw_target)
            if target_id > 0:
                target = await subject(pool, target_id)
        else:
            normalized = raw_target.lstrip('@').strip().casefold()
            if not re.fullmatch(r'[a-z0-9_]{5,32}', normalized):
                await message.answer('Некорректный username. Укажите, например @Nikolas_Connor, или Telegram ID.'); return
            async with pool.acquire() as conn:
                target = await conn.fetchrow("""SELECT * FROM subjects
                    WHERE LOWER(LTRIM(COALESCE(username,''), '@')) = $1
                    ORDER BY is_citizen DESC, id ASC LIMIT 1""", normalized)
            if target:
                target_id = target['user_id']
        if not target or not target['is_citizen'] or not target_id:
            await message.answer('Гражданин с таким username не найден или гражданство ещё не одобрено. Проверьте username в Telegram.'); return
        # FSM data must be read from the state; local variable `data` exists
        # only in the callback handler and is not available in this message handler.
        data = await state.get_data()
        code = data.get('role_code')
        action = data.get('role_action')
        if code not in {r[0] for r in DEFAULT_ROLES} or code == 'citizen' or action not in {'set', 'remove'}:
            await state.clear()
            await message.answer('Не удалось определить выбранную роль. Откройте управление ролями и выберите роль заново.')
            return
        if action == 'set':
            if code in {'president','prime_minister','deputy_prime_minister','central_bank_head'}:
                async with pool.acquire() as conn:
                    await conn.execute('DELETE FROM subject_roles sr USING roles r WHERE sr.role_id=r.id AND r.code=$1', code)
                    await conn.execute('DELETE FROM role_terms WHERE role_code=$1', code)
            ok = await add_role(pool, target_id, code)
            if not ok:
                await message.answer('Не удалось назначить роль: возможно, достигнут лимит.'); return
            async with pool.acquire() as conn:
                await conn.execute('INSERT INTO news(text) VALUES($1)', f"Администратор назначил {target['full_name']} на роль {code}.")
            await audit(pool, message.from_user.id, 'role_assigned', str(target_id), {'role': code})
            try: await bot.send_message(target_id, f'Вам назначена роль: {code}.')
            except TelegramAPIError: pass
            result = f"Роль {code} назначена: {target['full_name']}."
        else:
            await remove_role(pool, target_id, code)
            async with pool.acquire() as conn:
                await conn.execute('DELETE FROM role_terms WHERE role_code=$1 AND user_id=$2', code, target_id)
            await audit(pool, message.from_user.id, 'role_removed', str(target_id), {'role': code})
            result = f"Роль {code} снята: {target['full_name']}."
        await state.clear(); await message.answer(result)

    @dp.message(Form.admin_leader_party)
    async def admin_leader_party(message: Message, state: FSMContext):
        if not message.from_user or message.from_user.id != ADMIN_ID or not await is_admin(pool, ADMIN_ID):
            await state.clear(); await message.answer('Нет доступа или админка выключена.'); return
        try:
            party_id = int((message.text or '').strip())
        except ValueError:
            await message.answer('Введите числовой ID партии или /cancel.'); return
        async with pool.acquire() as conn:
            party = await conn.fetchrow("SELECT id,name FROM parties WHERE id=$1 AND status='active'", party_id)
        if not party:
            await message.answer('Активная партия с таким ID не найдена.'); return
        await state.update_data(leader_party_id=party_id)
        await state.set_state(Form.admin_leader_user)
        await message.answer(f"Партия «{html_escape(party['name'])}». Введите Telegram ID нового лидера. Он должен быть гражданином.")

    @dp.message(Form.admin_leader_user)
    async def admin_leader_user(message: Message, state: FSMContext):
        if not message.from_user or message.from_user.id != ADMIN_ID or not await is_admin(pool, ADMIN_ID):
            await state.clear(); await message.answer('Нет доступа или админка выключена.'); return
        try:
            target_id = int((message.text or '').strip())
        except ValueError:
            await message.answer('Введите числовой Telegram ID или /cancel.'); return
        data = await state.get_data()
        async with pool.acquire() as conn:
            async with conn.transaction():
                target = await conn.fetchrow("SELECT user_id,username,full_name FROM subjects WHERE user_id=$1 AND is_citizen=TRUE", target_id)
                party = await conn.fetchrow("SELECT id,name,leader_user_id FROM parties WHERE id=$1 AND status='active' FOR UPDATE", data.get('leader_party_id'))
                if not target or not party:
                    await message.answer('Гражданин или активная партия не найдены.'); return
                old_leader_id = party['leader_user_id']
                await conn.execute("UPDATE parties SET leader_user_id=$1,leader_username=$2 WHERE id=$3", target['user_id'], target['username'], party['id'])
                await conn.execute("DELETE FROM subject_roles sr USING roles r,subjects s WHERE sr.role_id=r.id AND sr.subject_id=s.id AND r.code='party_leader' AND s.user_id<>$1 AND s.user_id=$2", target_id, old_leader_id or -1)
                await conn.execute("DELETE FROM party_members WHERE user_id=$1 OR LOWER(username)=LOWER($2)", target['user_id'], target['username'])
                await conn.execute("INSERT INTO party_members(party_id,username,user_id) VALUES($1,$2,$3)", party['id'], target['username'], target['user_id'])
                await conn.execute("INSERT INTO subject_roles(subject_id,role_id) SELECT s.id,r.id FROM subjects s CROSS JOIN roles r WHERE s.user_id=$1 AND r.code='party_leader' ON CONFLICT DO NOTHING", target_id)
                if old_leader_id and old_leader_id != target_id:
                    await conn.execute("DELETE FROM subject_roles sr USING roles r,subjects s WHERE sr.role_id=r.id AND sr.subject_id=s.id AND r.code='party_leader' AND s.user_id=$1 AND NOT EXISTS (SELECT 1 FROM parties p WHERE p.status='active' AND p.leader_user_id=$1)", old_leader_id)
                await conn.execute("INSERT INTO news(text) VALUES($1)", f"Администратор сменил лидера партии «{party['name']}». Новый лидер: {target['full_name']}.")
        await audit(pool, message.from_user.id, 'party_leader_changed', str(data.get('leader_party_id')), {'new_leader_id': target_id})
        try: await bot.send_message(target_id, f"Вы назначены лидером партии «{html_escape(party['name'])}».")
        except TelegramAPIError: pass
        await state.clear(); await message.answer(f"Лидер партии «{html_escape(party['name'])}» изменён на {html_escape(target['full_name'])}.")

    @dp.message(Form.admin_reputation_target)
    async def admin_reputation_target(message: Message, state: FSMContext):
        if not message.from_user or message.from_user.id != ADMIN_ID or not await is_admin(pool, ADMIN_ID):
            await state.clear(); await message.answer('Нет доступа или админка выключена.'); return
        try: target_id = int((message.text or '').strip())
        except ValueError: await message.answer('Введите числовой Telegram ID или /cancel.'); return
        target = await subject(pool, target_id)
        if not target: await message.answer('Субъект не найден.'); return
        await state.update_data(reputation_target_id=target_id); await state.set_state(Form.admin_reputation_value)
        await message.answer(f"Репутация сейчас {target['reputation']}. Введите новое число от -100000 до 100000.")

    @dp.message(Form.admin_reputation_value)
    async def admin_reputation_value(message: Message, state: FSMContext):
        if not message.from_user or message.from_user.id != ADMIN_ID or not await is_admin(pool, ADMIN_ID):
            await state.clear(); await message.answer('Нет доступа или админка выключена.'); return
        try:
            value = int((message.text or '').strip())
            if not -100000 <= value <= 100000: raise ValueError
        except ValueError: await message.answer('Введите целое число от -100000 до 100000 или /cancel.'); return
        data = await state.get_data()
        async with pool.acquire() as conn:
            row = await conn.fetchrow('UPDATE subjects SET reputation=$1 WHERE user_id=$2 RETURNING full_name', value, data['reputation_target_id'])
        await audit(pool, message.from_user.id, 'reputation_changed', str(data['reputation_target_id']), {'value': value})
        await state.clear(); await message.answer(f"Репутация {row['full_name']} теперь {value}.")

    @dp.message(Form.admin_application_decision)
    async def admin_application_decision(message: Message, state: FSMContext):
        if not message.from_user or message.from_user.id != ADMIN_ID or not await is_admin(pool, ADMIN_ID):
            await state.clear(); await message.answer('Нет доступа или админка выключена.'); return
        data = await state.get_data()
        try:
            item_id = int((message.text or '').strip())
        except ValueError:
            await message.answer('Введите числовой ID.')
            return
        action = data.get('admin_action')
        async with pool.acquire() as conn:
            if action == 'dissolve_party':
                row = await conn.fetchrow("UPDATE parties SET status='dissolved' WHERE id=$1 AND status='active' RETURNING id,name,leader_username,leader_user_id",item_id)
                answer = f"Партия «{row['name']}» распущена." if row else 'Активная партия с таким ID не найдена.'
                if row:
                    await conn.execute("DELETE FROM party_members WHERE party_id=$1", row['id'])
                    if row['leader_user_id']:
                        await conn.execute("""DELETE FROM subject_roles sr USING roles r,subjects s
                            WHERE sr.role_id=r.id AND sr.subject_id=s.id AND r.code='party_leader' AND s.user_id=$1
                              AND NOT EXISTS (SELECT 1 FROM parties p WHERE p.status='active' AND p.leader_user_id=$1)""", row['leader_user_id'])
            else:
                row = await conn.fetchrow("UPDATE applications SET status='rejected',decided_at=$1 WHERE id=$2 AND status='pending' RETURNING author_username,author_user_id",to_db_dt(datetime.now(timezone.utc)),item_id)
                answer = f'Заявка №{item_id} отклонена.' if row else 'Ожидающая заявка не найдена.'
        if action != 'dissolve_party' and row:
            try:
                if row['author_user_id']: await bot.send_message(row['author_user_id'], f'Ваша заявка №{item_id} отклонена.')
                else: await notify_user(bot,pool,row['author_username'],f'Ваша заявка №{item_id} отклонена.')
            except TelegramAPIError: pass
        await state.clear()
        await message.answer(answer)


    @dp.errors()
    async def global_error_handler(event):
        exception = getattr(event, 'exception', None)
        logging.error('Необработанная ошибка Telegram update: %r', exception, exc_info=(type(exception), exception, exception.__traceback__) if exception else None)
        update = getattr(event, 'update', None)
        message = getattr(update, 'message', None) if update else None
        if message:
            try: await message.answer('Произошла ошибка. Попробуйте ещё раз или обратитесь к администратору.')
            except Exception: pass
        return True


async def election_monitor(pool: asyncpg.Pool, bot: Bot) -> None:
    """Automatically finish expired elections and remind party leaders every six hours."""
    while True:
        try:
            now = to_db_dt(datetime.now(timezone.utc))
            archived_apps = []
            async with pool.acquire() as conn:
                async with conn.transaction():
                    old_apps = await conn.fetch("SELECT id,type,author_username,author_user_id,data FROM applications WHERE status='pending' AND created_at <= $1 FOR UPDATE", to_db_dt(datetime.now(timezone.utc)-timedelta(days=5)))
                    for app in old_apps:
                        payload = app['data'] if isinstance(app['data'],dict) else json.loads(app['data'])
                        await conn.execute("UPDATE applications SET status='archived',decided_at=$1 WHERE id=$2 AND status='pending'", now, app['id'])
                        if app['author_user_id']:
                            await conn.execute("UPDATE subjects SET reputation=reputation-5 WHERE user_id=$1", app['author_user_id'])
                        elif app['author_username']:
                            await conn.execute("UPDATE subjects SET reputation=reputation-5 WHERE LOWER(username)=LOWER($1)", app['author_username'])
                        if app['type'] == 'create_party':
                            leader_id = payload.get('leader_user_id') or app['author_user_id']
                            if leader_id: await conn.execute("UPDATE subjects SET reputation=reputation-10 WHERE user_id=$1", int(leader_id))
                        elif app['type'] == 'join_party':
                            party_id = payload.get('party_id')
                            if party_id: await conn.execute("UPDATE subjects s SET reputation=reputation-10 FROM parties p WHERE p.id=$1 AND p.leader_user_id=s.user_id", int(party_id))
                        archived_apps.append((app['author_user_id'], app['author_username'], app['id']))
                expired_terms = await conn.fetch("SELECT role_code,user_id FROM role_terms WHERE ends_at <= $1", now)
                for term in expired_terms:
                    await conn.execute("DELETE FROM subject_roles sr USING roles r,subjects s WHERE sr.role_id=r.id AND sr.subject_id=s.id AND r.code=$1 AND s.user_id=$2", term['role_code'], term['user_id'])
                    await conn.execute("DELETE FROM role_terms WHERE role_code=$1 AND user_id=$2", term['role_code'], term['user_id'])
                pres = await conn.fetch("SELECT id FROM pres_elections WHERE status='active' AND ends_at <= $1",now)
                duma = await conn.fetch("SELECT id FROM duma_elections WHERE status='active' AND ends_at <= $1",now)
                active_duma = await conn.fetchrow("SELECT id FROM duma_elections WHERE status='active' AND ends_at > $1 ORDER BY id DESC LIMIT 1",now)
                parties = await conn.fetch("SELECT id,name,leader_username,leader_user_id FROM parties WHERE status='active' AND (leader_user_id IS NOT NULL OR leader_username IS NOT NULL)")
            for author_id, author_username, app_id in archived_apps:
                try:
                    if author_id: await bot.send_message(author_id, f"⌛ Заявка №{app_id} автоматически отправлена в архив через 5 дней. Репутация снижена на 5.")
                    else: await notify_user(bot,pool,author_username, f"⌛ Заявка №{app_id} автоматически отправлена в архив через 5 дней. Репутация снижена на 5.")
                except TelegramAPIError: pass
            for e in pres:
                result=await finish_pres_election(pool,e['id'])
                await broadcast(pool,bot,'🗳 Президентские выборы завершены. Результаты: '+json.dumps(result,ensure_ascii=False))
                async with pool.acquire() as conn: await conn.execute('INSERT INTO news(text) VALUES($1)','Президентские выборы завершены: '+json.dumps(result,ensure_ascii=False))
            for e in duma:
                result=await finish_duma_election(pool,e['id'])
                await broadcast(pool,bot,'🏛 Выборы Госдумы завершены. Результаты: '+json.dumps(result,ensure_ascii=False))
                async with pool.acquire() as conn: await conn.execute('INSERT INTO news(text) VALUES($1)','Выборы Госдумы завершены: '+json.dumps(result,ensure_ascii=False))
            if any(term['role_code'] in {'deputy', 'president'} for term in expired_terms):
                async with pool.acquire() as conn:
                    remaining_deputies = await conn.fetchval("SELECT COUNT(*) FROM subject_roles sr JOIN roles r ON r.id=sr.role_id WHERE r.code='deputy'")
                    active_pres = await conn.fetchval("SELECT 1 FROM pres_elections WHERE status='active' AND ends_at>$1 LIMIT 1", now)
                    president_expired = any(term['role_code'] == 'president' for term in expired_terms)
                    if (remaining_deputies == 0 or president_expired) and not active_pres:
                        end = to_db_dt(datetime.now(timezone.utc) + timedelta(hours=24))
                        new_election = await conn.fetchrow("INSERT INTO pres_elections(ends_at,status) VALUES($1,'active') RETURNING id", end)
                        await conn.execute("INSERT INTO news(text) VALUES($1)", f"Срок полномочий истёк. Автоматически назначены президентские выборы №{new_election['id']} на 24 часа.")
                        await broadcast(pool, bot, f"🗳 Срок полномочий истёк. Назначены президентские выборы №{new_election['id']} на 24 часа.")
            if active_duma:
                for party in parties:
                    count = await pool.fetchval('SELECT COUNT(*) FROM party_lists WHERE party_id=$1 AND election_id=$2', party['id'], active_duma['id'])
                    if count < 1:
                        last = await pool.fetchval('SELECT last_reminded_at FROM party_list_reminders WHERE party_id=$1 AND election_id=$2', party['id'], active_duma['id'])
                        if last is None or (now - last).total_seconds() >= REMINDER_INTERVAL_SECONDS:
                            async with pool.acquire() as conn:
                                await conn.execute('UPDATE subjects SET reputation=reputation-10 WHERE user_id=$1 OR ($1 IS NULL AND LOWER(username)=LOWER($2))', party['leader_user_id'], party['leader_username'])
                                await conn.execute('''INSERT INTO party_list_reminders(party_id,election_id,last_reminded_at) VALUES($1,$2,$3)
                                    ON CONFLICT(party_id,election_id) DO UPDATE SET last_reminded_at=EXCLUDED.last_reminded_at''', party['id'], active_duma['id'], now)
                            try:
                                if party['leader_user_id']: await bot.send_message(party['leader_user_id'], f"Напоминание: у партии «{html_escape(party['name'])}» нет списка кандидатов. Репутация снижена на 10.")
                                else: await notify_user(bot,pool,party['leader_username'],f"Напоминание: у партии «{party['name']}» нет списка кандидатов. Репутация снижена на 10.")
                            except TelegramAPIError: pass
        except asyncio.CancelledError:
            raise
        except Exception:
            logging.exception('Ошибка фонового контроля выборов')
        await asyncio.sleep(30)


async def main() -> None:
    logging.basicConfig(level=logging.INFO, format='%(asctime)s %(levelname)s %(name)s: %(message)s')
    if not BOT_TOKEN:
        raise RuntimeError('Не задана переменная окружения BOT_TOKEN.')
    database_url = DATABASE_URL
    if not database_url:
        raise RuntimeError('Не задана переменная окружения DATABASE_URL.')
    if database_url.startswith('postgres://'):
        database_url = 'postgresql://' + database_url[len('postgres://'):]
    pool = await asyncpg.create_pool(database_url, min_size=1, max_size=10, command_timeout=60)
    bot: Bot | None = None
    task: asyncio.Task | None = None
    try:
        await init_db(pool)
        bot = Bot(BOT_TOKEN, default=DefaultBotProperties(parse_mode=ParseMode.HTML))
        dp = Dispatcher()
        register_handlers(dp,pool,bot)
        task = asyncio.create_task(election_monitor(pool,bot),name='election-monitor')
        await dp.start_polling(bot, allowed_updates=dp.resolve_used_update_types())
    finally:
        if task:
            task.cancel()
            try: await task
            except asyncio.CancelledError: pass
        if bot:
            await bot.session.close()
        await pool.close()


if __name__ == '__main__':
    asyncio.run(main())
