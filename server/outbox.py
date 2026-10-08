"""Bounded durable server outbox backed by SQLite (Python 3.11 stdlib only).

Encapsulates storage/validation for per-device message envelopes. Encryption,
HTTP, auth and FCM concerns are intentionally out of scope; this module only
durably records, pages, acknowledges and leases envelopes.
"""
from __future__ import annotations

import contextlib
import json
import os
import re
import sqlite3
import time
import uuid

# Largest timestamp we accept so that derived timestamps cannot overflow the
# 64-bit integer range SQLite stores (year 9999 in milliseconds).
MAX_TIMESTAMP = 253402300799999

_MAX_ENVELOPE_BYTES = 32768
_TTL_MS = 86400000
_SENT_BACKOFF_MS = 300000
_BASE_BACKOFF_MS = 30000
_MAX_BACKOFF_MS = 3600000
_MAX_BACKOFF_SHIFT = 7

_CODE_RE = re.compile(r"[a-z0-9_]{1,64}")

_SCHEMA = """
CREATE TABLE IF NOT EXISTS outbox (
    seq         INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id   TEXT    NOT NULL,
    message_id  TEXT    NOT NULL,
    envelope    TEXT    NOT NULL,
    created     INTEGER NOT NULL,
    next_attempt INTEGER NOT NULL,
    expires     INTEGER NOT NULL,
    acked       INTEGER NOT NULL DEFAULT 0,
    attempts    INTEGER NOT NULL DEFAULT 0,
    lease_id    TEXT    NOT NULL DEFAULT '',
    lease_until INTEGER NOT NULL DEFAULT 0,
    last_error  TEXT    NOT NULL DEFAULT '',
    UNIQUE (device_id, message_id)
);
CREATE INDEX IF NOT EXISTS outbox_due
    ON outbox (acked, expires, next_attempt, lease_until, seq);
CREATE INDEX IF NOT EXISTS outbox_device ON outbox (device_id, seq);
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
    """Return base+delta, rejecting timestamps beyond MAX_TIMESTAMP."""
    total = base + delta
    if total > MAX_TIMESTAMP:
        raise ValueError(f"{name} overflow")
    return total


class Outbox:
    """Durable, concurrency-safe outbox over a single SQLite database file."""

    def __init__(self, path: str):
        if not isinstance(path, str) or not path.strip():
            raise ValueError("path must be a non-empty string")
        self._path = path
        parent = os.path.dirname(os.path.abspath(path))
        if parent:
            os.makedirs(parent, exist_ok=True)
        with self._connect() as conn:
            conn.executescript(_SCHEMA)
            columns = {row[1] for row in conn.execute('PRAGMA table_info(outbox)')}
            for name, declaration in {
                'first_fcm_at': 'INTEGER NOT NULL DEFAULT 0',
                'last_fcm_at': 'INTEGER NOT NULL DEFAULT 0',
                'acked_at': 'INTEGER NOT NULL DEFAULT 0',
                'fcm_receipt': "TEXT NOT NULL DEFAULT ''",
                'transport': "TEXT NOT NULL DEFAULT 'data'",
                'received_via': "TEXT NOT NULL DEFAULT ''",
                'delivery_outcome': "TEXT NOT NULL DEFAULT ''",
            }.items():
                if name not in columns:
                    conn.execute(f'ALTER TABLE outbox ADD COLUMN {name} {declaration}')

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

    # -- public API --------------------------------------------------------
    def enqueue(self, device_id: str, envelope: dict, now: int) -> int:
        with self._connect() as conn:
            with self._tx(conn):
                return self.enqueue_transaction(conn, device_id, envelope, now)

    @staticmethod
    def enqueue_transaction(conn, device_id: str, envelope: dict, now: int) -> int:
        """Enqueue using an already-open write transaction; never commits or opens another DB.

        Allows the scheduler's state update and durable delivery to commit atomically.
        """
        if not conn.in_transaction:
            raise ValueError("outbox_requires_transaction")
        _text(device_id, "device_id")
        now = _int(now, "now", 1)
        if not isinstance(envelope, dict):
            raise ValueError("envelope must be a dict")
        message_id = _text(envelope.get("message_id"), "message_id")
        body = json.dumps(
            envelope, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False
        )
        if len(body.encode("utf-8")) > _MAX_ENVELOPE_BYTES:
            raise ValueError("envelope too large")
        expires = _add(now, _TTL_MS, "expires")

        row = conn.execute(
            "SELECT seq, envelope FROM outbox WHERE device_id=? AND message_id=?",
            (device_id, message_id),
        ).fetchone()
        if row is not None:
            if row[1] != body:
                raise ValueError("message_id_conflict")
            return row[0]
        cur = conn.execute(
            "INSERT INTO outbox (device_id, message_id, envelope,"
            " created, next_attempt, expires) VALUES (?,?,?,?,?,?)",
            (device_id, message_id, body, now, now, expires),
        )
        return cur.lastrowid

    def list_after(self, device_id: str, cursor: int, limit: int = 100) -> list:
        _text(device_id, "device_id")
        cursor = _int(cursor, "cursor", 0)
        limit = _int(limit, "limit", 1, 100)
        with self._connect() as conn:
            rows = conn.execute(
                "SELECT seq, envelope FROM outbox WHERE device_id=? AND seq>?"
                " ORDER BY seq ASC LIMIT ?",
                (device_id, cursor, limit),
            ).fetchall()
        return [{"seq": r[0], "envelope": json.loads(r[1])} for r in rows]

    def ack(self, device_id: str, message_id: str, *, source='', outcome='') -> bool:
        _text(device_id, "device_id")
        _text(message_id, "message_id")
        if source not in ('', 'legacy', 'fcm', 'notification_tap', 'sync') or outcome not in ('', 'notified', 'saved_silent', 'blocked', 'discarded'):
            raise ValueError('invalid_receipt')
        with self._connect() as conn:
            with self._tx(conn):
                cur = conn.execute(
                    "UPDATE outbox SET acked=1, lease_id='', lease_until=0,"
                    " acked_at=CASE WHEN acked_at=0 THEN ? ELSE acked_at END,"
                    " received_via=CASE WHEN received_via='' THEN ? ELSE received_via END,"
                    " delivery_outcome=CASE WHEN delivery_outcome='' THEN ? ELSE delivery_outcome END"
                    " WHERE device_id=? AND message_id=?",
                    (int(time.time()*1000), source, outcome, device_id, message_id),
                )
                return cur.rowcount == 1

    def claim_due(self, now: int, limit: int = 20, lease_ms: int = 60000) -> list:
        now = _int(now, "now", 0)
        limit = _int(limit, "limit", 1, 100)
        lease_ms = _int(lease_ms, "lease_ms", 1000, 300000)
        lease_until = _add(now, lease_ms, "lease_until")
        with self._connect() as conn:
            with self._tx(conn):
                seqs = [
                    r[0]
                    for r in conn.execute(
                        "SELECT seq FROM outbox WHERE acked=0 AND expires>?"
                        " AND next_attempt<=? AND lease_until<=?"
                        " ORDER BY seq ASC LIMIT ?",
                        (now, now, now, limit),
                    ).fetchall()
                ]
                claimed = []
                for seq in seqs:
                    lease_id = str(uuid.uuid4())
                    conn.execute(
                        "UPDATE outbox SET lease_id=?, lease_until=?,"
                        " attempts=attempts+1 WHERE seq=?",
                        (lease_id, lease_until, seq),
                    )
                    row = conn.execute(
                        "SELECT device_id, envelope, attempts FROM outbox"
                        " WHERE seq=?",
                        (seq,),
                    ).fetchone()
                    claimed.append(
                        {
                            "seq": seq,
                            "device_id": row[0],
                            "envelope": json.loads(row[1]),
                            "message_id": json.loads(row[1])['message_id'],
                            "lease_id": lease_id,
                            "attempts": row[2],
                        }
                    )
                return claimed

    def sent(self, seq: int, lease_id: str, now: int, *, receipt='', transport='data') -> bool:
        seq = _int(seq, "seq", 0)
        _text(lease_id, "lease_id")
        now = _int(now, "now", 0)
        if transport not in ('data', 'system') or not isinstance(receipt, str) or len(receipt) > 512:
            raise ValueError('invalid_transport_receipt')
        next_attempt = _add(now, _SENT_BACKOFF_MS, "next_attempt")
        with self._connect() as conn:
            with self._tx(conn):
                cur = conn.execute(
                    "UPDATE outbox SET next_attempt=CASE WHEN ?='system' THEN expires ELSE ? END, lease_id='',"
                    " lease_until=0, last_error='', first_fcm_at=CASE WHEN first_fcm_at=0 THEN ? ELSE first_fcm_at END,"
                    " last_fcm_at=?, fcm_receipt=?, transport=?"
                    " WHERE seq=? AND lease_id=? AND acked=0 AND lease_until>?",
                    (transport, next_attempt, now, now, receipt, transport, seq, lease_id, now),
                )
                return cur.rowcount == 1

    def failed(self, seq: int, lease_id: str, now: int, code: str, *, retry_after=0) -> bool:
        seq = _int(seq, "seq", 0)
        _text(lease_id, "lease_id")
        now = _int(now, "now", 0)
        if not isinstance(code, str) or _CODE_RE.fullmatch(code) is None:
            raise ValueError("invalid code")
        retry_after = _int(retry_after, 'retry_after', 0, 3600)
        with self._connect() as conn:
            with self._tx(conn):
                row = conn.execute(
                    "SELECT attempts FROM outbox WHERE seq=? AND lease_id=?"
                    " AND acked=0 AND lease_until>?",
                    (seq, lease_id, now),
                ).fetchone()
                if row is None:
                    return False
                shift = min(max(row[0] - 1, 0), _MAX_BACKOFF_SHIFT)
                delay = max(retry_after * 1000, min(_MAX_BACKOFF_MS, _BASE_BACKOFF_MS * (2 ** shift)))
                next_attempt = _add(now, delay, "next_attempt")
                conn.execute(
                    "UPDATE outbox SET next_attempt=?, lease_id='',"
                    " lease_until=0, last_error=? WHERE seq=?",
                    (next_attempt, code, seq),
                )
                return True
