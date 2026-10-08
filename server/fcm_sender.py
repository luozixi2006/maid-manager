"""FCM HTTP v1 sender. This module does not generate messages or store model keys."""
from __future__ import annotations

import json
import re
import threading
from pathlib import Path

from google.auth.exceptions import GoogleAuthError
from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account
from requests.exceptions import RequestException

_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
_TOKEN_URI = "https://oauth2.googleapis.com/token"
_PROJECT = re.compile(r"[a-z][a-z0-9-]{4,61}[a-z0-9]")
_TOKEN = re.compile(r"[A-Za-z0-9_:.-]{8,4096}")
_DEVICE = re.compile(r"[A-Za-z0-9_-]{1,128}")
_FIELDS = {"schema_version", "type", "device_id", "ciphertext"}


class FcmFailure(Exception):
    """Only safe machine codes; provider response text, tokens and keys are excluded."""
    def __init__(self, code: str, status: int = 0, retry_after: int = 60):
        super().__init__(code)
        self.code = code
        self.status = status
        self.retry_after = max(1, min(retry_after, 3600))


def build_request(token: str, data: dict[str, str], validate_only: bool = False) -> dict:
    if not isinstance(token, str) or not _TOKEN.fullmatch(token):
        raise FcmFailure("invalid_fcm_token")
    if not isinstance(data, dict) or set(data) != _FIELDS or any(not isinstance(v, str) for v in data.values()):
        raise FcmFailure("invalid_push_payload")
    if (data["schema_version"] != "1" or data["type"] != "encrypted_character_message"
            or not _DEVICE.fullmatch(data["device_id"]) or not data["ciphertext"]):
        raise FcmFailure("invalid_push_payload")
    if len(json.dumps(data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) > 3900:
        raise FcmFailure("push_payload_too_large")
    if not isinstance(validate_only, bool):
        raise FcmFailure("invalid_push_payload")
    return {
        "validate_only": validate_only,
        "message": {
            "token": token,
            "data": dict(data),
            # Only real, user-visible character messages belong on this high-priority path.
            # No notification payload: the app decrypts, persists and shows its own persona avatar.
            "android": {
                "priority": "HIGH",
                "ttl": "86400s",
                "restricted_package_name": "com.maidmanager.debug",
            },
        },
    }


class FcmSender:
    def __init__(self, credential_path: str | Path, project_id: str):
        if not isinstance(project_id, str) or not _PROJECT.fullmatch(project_id):
            raise FcmFailure("invalid_firebase_project")
        try:
            info = json.loads(Path(credential_path).read_text(encoding="utf-8-sig"))
            # Pin the official OAuth destination; never honor arbitrary credential-file URLs.
            if (not isinstance(info, dict) or info.get("type") != "service_account"
                    or info.get("project_id") != project_id or info.get("token_uri") != _TOKEN_URI
                    or not str(info.get("client_email", "")).endswith("@" + project_id + ".iam.gserviceaccount.com")):
                raise ValueError()
            credentials = service_account.Credentials.from_service_account_info(info, scopes=[_SCOPE])
            self._session = AuthorizedSession(credentials, refresh_timeout=20, max_refresh_attempts=1)
        except (OSError, ValueError, TypeError, GoogleAuthError):
            raise FcmFailure("invalid_fcm_credentials") from None
        self._url = "https://fcm.googleapis.com/v1/projects/" + project_id + "/messages:send"
        self._lock = threading.Lock()

    def close(self):
        self._session.close()

    def send(self, token: str, data: dict[str, str], *, validate_only: bool = False) -> str:
        payload = build_request(token, data, validate_only)
        try:
            with self._lock:
                response = self._session.post(
                    self._url, json=payload, timeout=20, max_allowed_time=45, allow_redirects=False
                )
        except (GoogleAuthError, RequestException):
            raise FcmFailure("fcm_connection_failed") from None
        try:
            body = response.json()
        except ValueError:
            body = {}
        if not isinstance(body, dict):
            body = {}
        if response.status_code == 200:
            name = body.get("name")
            if not isinstance(name, str) or not name.startswith("projects/") or len(name) > 512:
                raise FcmFailure("invalid_fcm_response", 200)
            # Acceptance is NOT acknowledgement by the phone. Keep durable outbox until device ACK.
            return name
        detail = body.get("error", {})
        details = detail.get("details", []) if isinstance(detail, dict) else []
        codes = {
            d.get("errorCode") for d in details if isinstance(d, dict)
            and d.get("@type") == "type.googleapis.com/google.firebase.fcm.v1.FcmError"
            and isinstance(d.get("errorCode"), str)
        } if isinstance(details, list) else set()
        if "UNREGISTERED" in codes:
            raise FcmFailure("fcm_token_unregistered", response.status_code)
        status_codes = {400: "fcm_bad_request", 401: "fcm_auth_failed", 403: "fcm_permission_denied",
                        404: "fcm_not_found", 429: "fcm_rate_limited", 500: "fcm_unavailable", 503: "fcm_unavailable"}
        retry = response.headers.get("Retry-After", "60")
        retry_after = int(retry) if isinstance(retry, str) and retry.isdecimal() and len(retry) <= 6 else 60
        raise FcmFailure(status_codes.get(response.status_code, "fcm_request_failed"), response.status_code, retry_after)
