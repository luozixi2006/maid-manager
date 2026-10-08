"""Private push-server state store (Python 3.11 stdlib only).

Owns pairing codes, device credentials, per-persona sync profiles and the
lease/throttle bookkeeping used to hand one unit of generation work to a
single active device. All persisted JSON is private; nothing here logs, and
raw secrets (pairing code, device token) are only ever stored hashed.
"""
from __future__ import annotations

import base64
import contextlib
import hashlib
import hmac
import json
import os
import re
import secrets
import sqlite3
import uuid

try:  # importable both as a top-level module and as package ``server``.
    from .outbox import Outbox
except ImportError:  # pragma: no cover - depends on invocation style
    from outbox import Outbox

# Largest timestamp accepted so derived timestamps stay inside signed 64-bit.
MAX_TIMESTAMP = 253402300799999

PAIRING_TTL_MS = 600000
MAX_PAIRING_ATTEMPTS = 5
MAX_DEVICES = 8
MAX_PROFILES = 32
MAX_PROFILE_FIELD = 128
SNAPSHOT_MAX_BYTES = 262144
PLAN_WINDOW_MS = 86400000
DEVICE_THROTTLE_MS = 900000
LEASE_MS = 180000

_CODE8_RE = re.compile(r"[0-9]{8}")
_TOKEN_RE = re.compile(r"[A-Za-z0-9_-]{32,256}")
_FCM_RE = re.compile(r"[A-Za-z0-9_:.\-]{8,4096}")
_STATUS_RE = re.compile(r"[a-z0-9_]{1,64}")

_SCHEMA = """
CREATE TABLE IF NOT EXISTS pairing (
    id        INTEGER PRIMARY KEY CHECK (id = 1),
    code_hash TEXT    NOT NULL,
    expires   INTEGER NOT NULL,
    attempts  INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS devices (
    device_id   TEXT PRIMARY KEY,
    token_hash  TEXT NOT NULL UNIQUE,
    payload_key TEXT NOT NULL,
    fcm_token   TEXT NOT NULL DEFAULT '',
    active      INTEGER NOT NULL DEFAULT 1,
    last_sent   INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS profiles (
    device_id   TEXT    NOT NULL,
    persona_id  TEXT    NOT NULL,
    body        TEXT    NOT NULL,
    revision    TEXT    NOT NULL,
    synced_at   INTEGER NOT NULL,
    next_due    INTEGER NOT NULL,
    lease_id    TEXT    NOT NULL DEFAULT '',
    lease_until INTEGER NOT NULL DEFAULT 0,
    status      TEXT    NOT NULL DEFAULT 'waiting',
    PRIMARY KEY (device_id, persona_id)
);
CREATE INDEX IF NOT EXISTS profiles_due
    ON profiles (next_due, lease_until, device_id);
"""


def _int(value, name, lo, hi=MAX_TIMESTAMP):
    """Validate an integer (rejecting bool) inside [lo, hi] or raise ValueError."""
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{name} must be an integer")
    if value < lo or value > hi:
        raise ValueError(f"{name} out of range")
    return value


def _text(value, name, max_len=128):
    """Validate a nonblank string of bounded length or raise ValueError."""
    if not isinstance(value, str) or not value.strip() or len(value) > max_len:
        raise ValueError(f"invalid {name}")
    return value


def _add(base, delta, name):
    total = base + delta
    if total > MAX_TIMESTAMP:
        raise ValueError(f"{name} overflow")
    return total


def _canon(obj) -> str:
    """Canonical (sorted, compact, no-NaN) UTF-8 JSON text."""
    return json.dumps(
        obj, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
    )


def _sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


class HubStore:
    """Concurrency-safe private state store over a single SQLite database."""

    def __init__(self, path: str):
        if not isinstance(path, str) or not path.strip():
            raise ValueError("path must be a non-empty string")
        self._path = path
        parent = os.path.dirname(os.path.abspath(path))
        if parent:
            os.makedirs(parent, exist_ok=True)
        self._outbox = Outbox(path)
        with self._connect() as conn:
            conn.executescript(_SCHEMA)
            columns = {row[1] for row in conn.execute('PRAGMA table_info(devices)')}
            for name, declaration in {
                'system_notification': 'INTEGER NOT NULL DEFAULT 0',
                'token_updated_at': 'INTEGER NOT NULL DEFAULT 0',
                'token_error': "TEXT NOT NULL DEFAULT ''",
                'invalid_token_hash': "TEXT NOT NULL DEFAULT ''",
            }.items():
                if name not in columns:
                    conn.execute(f'ALTER TABLE devices ADD COLUMN {name} {declaration}')

    # -- internals ---------------------------------------------------------
    @contextlib.contextmanager
    def _connect(self):
        conn = sqlite3.connect(self._path, timeout=5.0, isolation_level=None)
        try:
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA busy_timeout=5000")
            yield conn
        finally:
            conn.close()

    @contextlib.contextmanager
    def _tx(self, conn):
        conn.execute("BEGIN IMMEDIATE")
        try:
            yield conn
        except BaseException:
            conn.execute("ROLLBACK")
            raise
        else:
            conn.execute("COMMIT")

    # -- pairing / devices -------------------------------------------------
    def create_pairing(self, now: int) -> str:
        """Create the singleton pairing code (returned once, stored hashed)."""
        now = _int(now, "now", 1)
        code = f"{secrets.randbelow(100000000):08d}"
        expires = _add(now, PAIRING_TTL_MS, "expires")
        with self._connect() as conn:
            with self._tx(conn):
                conn.execute(
                    "INSERT INTO pairing (id, code_hash, expires, attempts)"
                    " VALUES (1,?,?,0)"
                    " ON CONFLICT(id) DO UPDATE SET code_hash=excluded.code_hash,"
                    " expires=excluded.expires, attempts=0",
                    (_sha256(code), expires),
                )
        return code

    def pair(self, code: str, now: int) -> dict:
        """Consume the pairing code once and return fresh device credentials."""
        now = _int(now, "now", 1)
        valid = isinstance(code, str) and _CODE8_RE.fullmatch(code) is not None
        result = None
        with self._connect() as conn:
            with self._tx(conn):
                row = conn.execute(
                    "SELECT code_hash, expires, attempts FROM pairing WHERE id=1"
                ).fetchone()
                if row is None:
                    pass
                elif row[2] >= MAX_PAIRING_ATTEMPTS or now >= row[1]:
                    pass
                elif not valid or not hmac.compare_digest(row[0], _sha256(code)):
                    # Wrong (or malformed) attempt: committed before we raise.
                    conn.execute("UPDATE pairing SET attempts=attempts+1 WHERE id=1")
                else:
                    used = conn.execute("SELECT COUNT(*) FROM devices").fetchone()[0]
                    if used >= MAX_DEVICES:
                        raise ValueError("pairing_failed")
                    device_id = uuid.uuid4().hex
                    token = secrets.token_urlsafe(32)
                    payload_key = base64.b64encode(os.urandom(32)).decode("ascii")
                    conn.execute(
                        "INSERT INTO devices (device_id, token_hash, payload_key)"
                        " VALUES (?,?,?)",
                        (device_id, _sha256(token), payload_key),
                    )
                    conn.execute("DELETE FROM pairing WHERE id=1")
                    result = {
                        "device_id": device_id,
                        "token": token,
                        "payload_key": payload_key,
                    }
        if result is None:
            raise ValueError("pairing_failed")
        return result

    def authenticate(self, token: str) -> str | None:
        """Return the device_id for a valid token, else None (paused still ok)."""
        if not isinstance(token, str) or _TOKEN_RE.fullmatch(token) is None:
            return None
        digest = _sha256(token)
        with self._connect() as conn:
            rows = conn.execute("SELECT device_id, token_hash FROM devices").fetchall()
        for device_id, token_hash in rows:
            if hmac.compare_digest(token_hash, digest):
                return device_id
        return None

    def device(self, device_id: str) -> dict | None:
        """Internal credentials for delivery; never expose this object in HTTP or logs."""
        if not isinstance(device_id, str) or not device_id:
            return None
        with self._connect() as conn:
            row = conn.execute(
                "SELECT device_id, payload_key, fcm_token, active, system_notification, token_error FROM devices"
                " WHERE device_id=?",
                (device_id,),
            ).fetchone()
        if row is None:
            return None
        return {
            "id": row[0],
            "payload_key": row[1],
            "fcm_token": row[2],
            "active": row[3],
            "system_notification": bool(row[4]),
            "token_error": row[5],
        }

    def register(self, device_id: str, token: str, *, system_notification=False, now=0) -> None:
        """Store a bounded FCM token for an existing device."""
        _text(device_id, "device_id")
        if not isinstance(token, str) or _FCM_RE.fullmatch(token) is None:
            raise ValueError("invalid token")
        if not isinstance(system_notification, bool):
            raise ValueError('invalid_transport')
        now = _int(now, 'now', 0)
        with self._connect() as conn:
            with self._tx(conn):
                cur = conn.execute(
                    "UPDATE devices SET system_notification=?, token_updated_at=?,"
                    " fcm_token=CASE WHEN invalid_token_hash=? THEN '' ELSE ? END,"
                    " token_error=CASE WHEN invalid_token_hash=? THEN token_error ELSE '' END"
                    " WHERE device_id=?",
                    (int(system_notification), now, _sha256(token), token, _sha256(token), device_id),
                )
                if cur.rowcount != 1:
                    raise ValueError("unknown_device")

    def invalidate_token(self, device_id: str, expected_token: str, code: str) -> bool:
        """CAS: a delayed failure must never erase a newer token registered concurrently."""
        if code not in ('fcm_token_unregistered', 'fcm_token_invalid'):
            raise ValueError('invalid_token_error')
        with self._connect() as conn:
            return conn.execute("UPDATE devices SET fcm_token='', invalid_token_hash=?, token_error=?"
                " WHERE device_id=? AND fcm_token=?", (_sha256(expected_token), code, device_id, expected_token)).rowcount == 1

    # -- profiles ----------------------------------------------------------
    def snapshot(self, device_id: str, profiles: list, now: int) -> None:
        """Replace one device's profile set inside a single transaction."""
        _text(device_id, "device_id")
        now = _int(now, "now", 1)
        if not isinstance(profiles, (list, tuple)):
            raise ValueError("invalid profiles")
        if len(profiles) > MAX_PROFILES:
            raise ValueError("too many profiles")
        prepared = []
        ordered = []
        seen = set()
        for profile in profiles:
            if not isinstance(profile, dict):
                raise ValueError("invalid profile")
            persona_id = _text(profile.get("persona_id"), "persona_id", MAX_PROFILE_FIELD)
            _text(profile.get("conversation_id"), "conversation_id", MAX_PROFILE_FIELD)
            next_at = _int(profile.get("next_at"), "next_at", 0)
            if persona_id in seen:
                raise ValueError("duplicate persona_id")
            seen.add(persona_id)
            ordered.append(persona_id)
            body = _canon(profile)
            prepared.append((persona_id, body, _sha256(body), next_at))
        if len(_canon(list(profiles)).encode("utf-8")) > SNAPSHOT_MAX_BYTES:
            raise ValueError("snapshot too large")

        with self._connect() as conn:
            with self._tx(conn):
                exists = conn.execute(
                    "SELECT 1 FROM devices WHERE device_id=?", (device_id,)
                ).fetchone()
                if exists is None:
                    raise ValueError("unknown_device")
                if ordered:
                    placeholders = ",".join("?" for _ in ordered)
                    conn.execute(
                        "DELETE FROM profiles WHERE device_id=?"
                        f" AND persona_id NOT IN ({placeholders})",
                        (device_id, *ordered),
                    )
                else:
                    conn.execute("DELETE FROM profiles WHERE device_id=?", (device_id,))
                for persona_id, body, revision, next_at in prepared:
                    conn.execute(
                        "INSERT INTO profiles (device_id, persona_id, body, revision,"
                        " synced_at, next_due, lease_id, lease_until, status)"
                        " VALUES (?,?,?,?,?,?,'',0,'waiting')"
                        " ON CONFLICT(device_id, persona_id) DO UPDATE SET"
                        " body=excluded.body, revision=excluded.revision,"
                        " synced_at=excluded.synced_at,"
                        " lease_id=CASE WHEN profiles.revision<>excluded.revision"
                        "   THEN '' ELSE profiles.lease_id END,"
                        " lease_until=CASE WHEN profiles.revision<>excluded.revision"
                        "   THEN 0 ELSE profiles.lease_until END",
                        (device_id, persona_id, body, revision, now, max(now, next_at)),
                    )
                conn.execute(
                    "UPDATE devices SET active=1 WHERE device_id=?", (device_id,)
                )

    def pause(self, device_id: str) -> None:
        """Deactivate a device, drop its FCM token and generated profiles."""
        _text(device_id, "device_id")
        with self._connect() as conn:
            with self._tx(conn):
                conn.execute(
                    "UPDATE devices SET active=0, fcm_token='' WHERE device_id=?",
                    (device_id,),
                )
                conn.execute("DELETE FROM profiles WHERE device_id=?", (device_id,))

    def status(self, device_id: str) -> list:
        """Return per-persona scheduling metadata (no bodies, keys or headers)."""
        _text(device_id, "device_id")
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT persona_id, next_due, status, synced_at FROM profiles"
                " WHERE device_id=? ORDER BY persona_id ASC",
                (device_id,),
            ).fetchall()
        return [
            {
                "persona_id": row[0],
                "next_due": row[1],
                "status": row[2],
                "synced_at": row[3],
            }
            for row in rows
        ]

    # -- work leases -------------------------------------------------------
    def claim(self, now: int) -> dict | None:
        """Lease one due profile for an active, non-throttled device."""
        now = _int(now, "now", 1)
        lease_until = _add(now, LEASE_MS, "lease_until")
        with self._connect() as conn:
            with self._tx(conn):
                row = conn.execute(
                    "SELECT p.device_id, p.persona_id, p.revision, p.body"
                    " FROM profiles p JOIN devices d ON d.device_id = p.device_id"
                    " WHERE p.next_due<=? AND p.lease_until<=? AND p.synced_at>?"
                    " AND d.active=1 AND d.fcm_token<>''"
                    " AND (d.last_sent=0 OR d.last_sent<=?)"
                    " ORDER BY p.next_due ASC, p.persona_id ASC LIMIT 1",
                    (now, now, now - PLAN_WINDOW_MS, now - DEVICE_THROTTLE_MS),
                ).fetchone()
                if row is None:
                    return None
                lease_id = uuid.uuid4().hex
                conn.execute(
                    "UPDATE profiles SET lease_id=?, lease_until=?"
                    " WHERE device_id=? AND persona_id=?",
                    (lease_id, lease_until, row[0], row[1]),
                )
                return {
                    "device_id": row[0],
                    "persona_id": row[1],
                    "revision": row[2],
                    "lease_id": lease_id,
                    "body": json.loads(row[3]),
                }

    def complete(
        self,
        claim: dict,
        now: int,
        next_due: int,
        status: str,
        envelope: dict | None = None,
    ) -> bool:
        """Close a live lease; optionally enqueue an outbound envelope atomically."""
        now = _int(now, "now", 1)
        next_due = _int(next_due, "next_due", 0)
        if next_due < now:
            raise ValueError("invalid next_due")
        if not isinstance(status, str) or _STATUS_RE.fullmatch(status) is None:
            raise ValueError("invalid status")
        if not isinstance(claim, dict):
            raise ValueError("invalid claim")
        device_id = _text(claim.get("device_id"), "device_id")
        persona_id = _text(claim.get("persona_id"), "persona_id")
        revision = _text(claim.get("revision"), "revision")
        lease_id = _text(claim.get("lease_id"), "lease_id")
        if envelope is not None and not isinstance(envelope, dict):
            raise ValueError("invalid envelope")

        with self._connect() as conn:
            with self._tx(conn):
                row = conn.execute(
                    "SELECT p.revision, p.lease_id, p.lease_until, p.body, d.active"
                    " FROM profiles p JOIN devices d ON d.device_id = p.device_id"
                    " WHERE p.device_id=? AND p.persona_id=?",
                    (device_id, persona_id),
                ).fetchone()
                if row is None or row[0] != revision or row[1] != lease_id:
                    return False
                if row[2] <= now or row[4] != 1:
                    return False
                if envelope is not None:
                    profile = json.loads(row[3])
                    if (
                        envelope.get("persona_id") != profile.get("persona_id")
                        or envelope.get("conversation_id")
                        != profile.get("conversation_id")
                    ):
                        raise ValueError("envelope_mismatch")
                    self._outbox.enqueue_transaction(conn, device_id, envelope, now)
                    conn.execute(
                        "UPDATE devices SET last_sent=? WHERE device_id=?",
                        (now, device_id),
                    )
                conn.execute(
                    "UPDATE profiles SET next_due=?, status=?, lease_id='',"
                    " lease_until=0 WHERE device_id=? AND persona_id=?",
                    (next_due, status, device_id, persona_id),
                )
                return True
