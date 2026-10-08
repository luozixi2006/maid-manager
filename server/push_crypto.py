"""Versioned, authenticated FCM data envelope; never sends plaintext chat to FCM."""
from __future__ import annotations

import base64
import binascii
import json
import os
import re

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAX_WIRE_BYTES = 3800
_DEVICE = re.compile(r"[A-Za-z0-9_-]{1,128}")
_FIELDS = {"schema_version", "type", "device_id", "ciphertext"}


def _check_identity(key: bytes, device_id: str) -> None:
    if not isinstance(key, bytes) or len(key) != 32:
        raise ValueError("invalid_encrypted_push")
    if not isinstance(device_id, str) or not _DEVICE.fullmatch(device_id):
        raise ValueError("invalid_encrypted_push")


def _check_strings(payload: dict) -> None:
    if not isinstance(payload, dict):
        raise ValueError("invalid_encrypted_push")
    for field, value in payload.items():
        if (not isinstance(field, str) or len(field) > 64
                or not isinstance(value, str) or len(value.encode("utf-8")) > 3800):
            raise ValueError("invalid_encrypted_push")


def _size(data: dict) -> int:
    if not isinstance(data, dict) or any(
        not isinstance(k, str) or not isinstance(v, str) for k, v in data.items()
    ):
        raise ValueError("invalid_encrypted_push")
    return sum(len(k.encode("utf-8")) + len(v.encode("utf-8")) for k, v in data.items())


def encrypt(payload: dict[str, str], key: bytes, device_id: str) -> dict[str, str]:
    """Fresh nonce per delivery attempt. The device ID is authenticated as AAD."""
    try:
        _check_identity(key, device_id)
        _check_strings(payload)
        raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
        if len(raw) > 3800:
            raise ValueError()
        nonce = os.urandom(12)
        sealed = AESGCM(key).encrypt(nonce, raw, ("maid-push:v1:" + device_id).encode("utf-8"))
        result = {
            "schema_version": "1",
            "type": "encrypted_character_message",
            "device_id": device_id,
            "ciphertext": base64.b64encode(nonce + sealed).decode("ascii"),
        }
        if _size(result) > MAX_WIRE_BYTES:
            raise ValueError()
        return result
    except (ValueError, TypeError, UnicodeError):
        raise ValueError("invalid_encrypted_push") from None


def decrypt(data: dict[str, str], key: bytes, device_id: str) -> dict[str, str]:
    """Used for cross-language verification; the server only needs encrypt to send."""
    try:
        _check_identity(key, device_id)
        if _size(data) > MAX_WIRE_BYTES or set(data) != _FIELDS:
            raise ValueError()
        if (data["schema_version"] != "1" or data["type"] != "encrypted_character_message"
                or data["device_id"] != device_id):
            raise ValueError()
        blob = base64.b64decode(data["ciphertext"], validate=True)
        if len(blob) < 28:
            raise ValueError()
        raw = AESGCM(key).decrypt(blob[:12], blob[12:], ("maid-push:v1:" + device_id).encode("utf-8"))
        if len(raw) > 3800:
            raise ValueError()
        result = json.loads(raw.decode("utf-8"))
        _check_strings(result)
        return result
    except (ValueError, TypeError, KeyError, UnicodeError, binascii.Error, InvalidTag):
        raise ValueError("invalid_encrypted_push") from None
