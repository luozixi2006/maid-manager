"""Tests for the parent-owned private push server API (server/main.py).

Every fixture is fake: databases live in a TemporaryDirectory, all model and
FCM outbound calls are replaced with in-process fakes, and only loopback HTTP
is used. No real device token, key, credential or secret is read or logged.
"""
from __future__ import annotations

import base64
import http.client
import json
import os
import sqlite3
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from unittest import mock

try:  # importable both as a top-level module and as package ``server``.
    from server import main
    from server.push_crypto import decrypt
except ImportError:  # pragma: no cover - depends on invocation style
    import main
    from push_crypto import decrypt

# Bind to the exact class object main uses: importing ``server.fcm_sender``
# would create a distinct class identity from main's top-level ``fcm_sender``.
FcmFailure = main.FcmFailure

NOW = 1_700_000_000_000  # ms, well above 1e12
FCM = "fake-fcm-token-123"


def valid_profile(**overrides):
    profile = {
        "persona_id": "persona-1",
        "conversation_id": "conv-1",
        "provider_id": "provider-1",
        "model_id": "model-1",
        "prompt": "stay in character",
        "chat_url": "https://api.example.com/v1/chat",
        "headers": {},
        "temperature": 0.7,
        "min_minutes": 30,
        "max_minutes": 60,
        "dnd_start": 0,
        "dnd_end": 0,
        "utc_offset": 0,
        "next_at": 0,
        "last_chat_at": 0,
        "busy": False,
    }
    profile.update(overrides)
    return profile


def character_envelope(message_id="msg-1"):
    return {
        "schema_version": "1",
        "type": "character_message",
        "message_id": message_id,
        "conversation_id": "conv-1",
        "persona_id": "persona-1",
        "content": "hi",
        "created_at": str(NOW),
        "provider_id": "provider-1",
        "model_id": "model-1",
    }


class FakeSender:
    """Records outbound pushes; never touches FCM or the network."""

    def __init__(self, fail=None):
        self.calls = []
        self.fail = fail

    def send(self, token, data, *, validate_only=False):
        self.calls.append((token, data))
        if self.fail is not None:
            raise self.fail
        return "projects/fake/messages/1"

    def close(self):
        pass


class FakeResponse:
    def __init__(self, *, status=200, payload=None, chunks=None):
        self.status_code = status
        if chunks is None:
            self._chunks = None
            self._raw = b"" if payload is None else json.dumps(payload).encode("utf-8")
        else:
            self._chunks = chunks
            self._raw = b""

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        return False

    def iter_content(self, size):
        if self._chunks is not None:
            yield from self._chunks
            return
        for start in range(0, len(self._raw), size):
            yield self._raw[start:start + size]


class FakeTransport:
    """Stand-in for ``requests``; records calls, performs no real I/O."""

    def __init__(self, response):
        self.response = response
        self.calls = []

    def post(self, url, **kwargs):
        self.calls.append((url, kwargs))
        return self.response


class RuntimeTestBase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.db = os.path.join(self._tmp.name, "hub.sqlite3")
        self.sender = FakeSender()
        self.runtime = main.Runtime(self.db, self.sender)

    def make_device(self, fcm=FCM):
        store = self.runtime.store
        code = store.create_pairing(main.now_ms())
        cred = store.pair(code, main.now_ms())
        if fcm is not None:
            store.register(cred["device_id"], fcm)
        return cred

    def _query_one(self, sql, params=()):
        connection = sqlite3.connect(self.db)
        try:
            connection.row_factory = sqlite3.Row
            row = connection.execute(sql, params).fetchone()
            return dict(row) if row is not None else None
        finally:
            connection.close()

    def pairing_attempts(self):
        row = self._query_one("SELECT attempts FROM pairing WHERE id=1")
        return row["attempts"] if row else None

    def outbox_row(self, message_id):
        return self._query_one(
            "SELECT acked, attempts, next_attempt, last_error FROM outbox"
            " WHERE message_id=?",
            (message_id,),
        )


class ServerHTTPTests(RuntimeTestBase):
    def test_ack_requires_auth_and_is_device_scoped(self):
        first = self.make_device()
        second = self.make_device()
        self.runtime.outbox.enqueue(first["device_id"], character_envelope(), main.now_ms())
        status, _ = self.post_json("/v1/ack", {"acks": ["msg-1"]})
        self.assertEqual(status, 401)
        status, _ = self.post_json("/v1/ack", {"acks": ["msg-1"]}, {"Authorization": "Bearer " + second["token"]})
        self.assertEqual(status, 200)
        self.assertEqual(self.outbox_row("msg-1")["acked"], 0)
        status, _ = self.post_json("/v1/ack", {"acks": ["msg-1"]}, {"Authorization": "Bearer " + first["token"]})
        self.assertEqual(status, 200)
        self.assertEqual(self.outbox_row("msg-1")["acked"], 1)

    def setUp(self):
        super().setUp()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), main.handler(self.runtime))
        self.server.daemon_threads = True
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def request(self, method, path, *, body=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            payload = response.read()
            return response.status, dict(response.getheaders()), payload
        finally:
            connection.close()

    def post_json(self, path, body, headers=None):
        merged = {"Content-Type": "application/json"}
        if headers:
            merged.update(headers)
        raw = json.dumps(body).encode("utf-8")
        status, _headers, payload = self.request("POST", path, body=raw, headers=merged)
        return status, (json.loads(payload) if payload else None)

    def pair_device(self):
        code = self.runtime.store.create_pairing(main.now_ms())
        status, data = self.post_json("/v1/pair", {"code": code})
        self.assertEqual(status, 200)
        return data

    def test_health_ok(self):
        status, _headers, payload = self.request("GET", "/health")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(payload)["service"], "maid-private-push")

    def test_unauthorized_sync_is_401(self):
        body = {"fcm_token": FCM, "profiles": [], "after": 0, "acks": []}
        status, data = self.post_json("/v1/sync", body)
        self.assertEqual(status, 401)
        self.assertEqual(data["error"], "pairing_required")
        status, _data = self.post_json(
            "/v1/sync", body, headers={"Authorization": "Bearer not-a-real-token"}
        )
        self.assertEqual(status, 401)

    def test_origin_header_is_forbidden(self):
        status, _headers, _payload = self.request(
            "GET", "/health", headers={"Origin": "https://evil.example"}
        )
        self.assertEqual(status, 403)
        status, _data = self.post_json(
            "/v1/pair", {"code": "12345678"}, headers={"Origin": "https://evil.example"}
        )
        self.assertEqual(status, 403)

    def test_wrong_pairing_code_400_and_attempts(self):
        code = self.runtime.store.create_pairing(main.now_ms())
        wrong = "00000000" if code != "00000000" else "99999999"
        for _ in range(5):
            status, data = self.post_json("/v1/pair", {"code": wrong})
            self.assertEqual(status, 400)
            self.assertEqual(data["error"], "invalid_request_or_pairing_code")
        self.assertEqual(self.pairing_attempts(), 5)
        status, _data = self.post_json("/v1/pair", {"code": code})
        self.assertEqual(status, 400)

    def test_valid_pairing_is_single_use(self):
        code = self.runtime.store.create_pairing(main.now_ms())
        status, data = self.post_json("/v1/pair", {"code": code})
        self.assertEqual(status, 200)
        self.assertTrue(data["device_id"])
        self.assertTrue(data["token"])
        self.assertTrue(data["payload_key"])
        status, _data = self.post_json("/v1/pair", {"code": code})
        self.assertEqual(status, 400)

    def test_body_too_large_is_413(self):
        status, _headers, _payload = self.request(
            "POST",
            "/v1/sync",
            body=b"{}",
            headers={
                "Content-Type": "application/json",
                "Content-Length": str(main.MAX_BODY + 1),
            },
        )
        self.assertEqual(status, 413)

    def test_non_json_content_type_is_415(self):
        status, _headers, _payload = self.request(
            "POST", "/v1/sync", body=b"{}", headers={"Content-Type": "text/plain"}
        )
        self.assertEqual(status, 415)

    def test_sync_accepts_only_exact_device_token(self):
        cred = self.pair_device()
        token = cred["token"]
        body = {"fcm_token": FCM, "profiles": [], "after": 0, "acks": []}
        altered = token[:-1] + ("A" if token[-1] != "A" else "B")
        status, _data = self.post_json(
            "/v1/sync", body, headers={"Authorization": "Bearer " + altered}
        )
        self.assertEqual(status, 401)
        status, data = self.post_json(
            "/v1/sync", body, headers={"Authorization": "Bearer " + token}
        )
        self.assertEqual(status, 200)
        self.assertEqual(data["profiles"], [])

    def test_sync_rejects_malformed_profiles(self):
        cred = self.pair_device()
        auth = {"Authorization": "Bearer " + cred["token"]}
        bad_sets = [
            ["not-a-dict"],
            [{"persona_id": "p"}],
            {"profiles": 1},
            [valid_profile()] * 33,
        ]
        for profiles in bad_sets:
            status, data = self.post_json(
                "/v1/sync",
                {"fcm_token": FCM, "profiles": profiles, "after": 0, "acks": []},
                headers=auth,
            )
            self.assertEqual(status, 400)
            self.assertEqual(data["error"], "invalid_request_or_pairing_code")
        status, _data = self.post_json(
            "/v1/sync",
            {"fcm_token": FCM, "profiles": [valid_profile()], "after": 0, "acks": []},
            headers=auth,
        )
        self.assertEqual(status, 200)

    def test_pause_clears_profiles_and_deactivates(self):
        cred = self.pair_device()
        device_id = cred["device_id"]
        auth = {"Authorization": "Bearer " + cred["token"]}
        status, _data = self.post_json(
            "/v1/sync",
            {"fcm_token": FCM, "profiles": [valid_profile()], "after": 0, "acks": []},
            headers=auth,
        )
        self.assertEqual(status, 200)
        self.assertEqual(len(self.runtime.store.status(device_id)), 1)
        status, data = self.post_json("/v1/pause", {}, headers=auth)
        self.assertEqual(status, 200)
        self.assertTrue(data["paused"])
        device = self.runtime.store.device(device_id)
        self.assertEqual(device["active"], 0)
        self.assertEqual(device["fcm_token"], "")
        self.assertEqual(self.runtime.store.status(device_id), [])

    def test_sync_returns_decryptable_outbox_and_acks(self):
        cred = self.pair_device()
        device_id = cred["device_id"]
        auth = {"Authorization": "Bearer " + cred["token"]}
        envelope = character_envelope("msg-1")
        self.runtime.outbox.enqueue(device_id, envelope, main.now_ms())
        status, data = self.post_json(
            "/v1/sync",
            {"fcm_token": FCM, "profiles": [], "after": 0, "acks": ["msg-1"]},
            headers=auth,
        )
        self.assertEqual(status, 200)
        self.assertEqual(data["acked"], ["msg-1"])
        self.assertEqual(len(data["items"]), 1)
        key = base64.b64decode(cred["payload_key"])
        self.assertEqual(decrypt(data["items"][0]["data"], key, device_id), envelope)
        self.assertEqual(self.outbox_row("msg-1")["acked"], 1)


class RuntimeGenerateTests(RuntimeTestBase):
    def _prepare(self):
        cred = self.make_device()
        self.runtime.store.snapshot(cred["device_id"], [valid_profile()], main.now_ms())
        return cred

    def test_generate_once_queues_one_encryptable_outbox(self):
        cred = self._prepare()
        with mock.patch.object(main, "model_decision", lambda profile: "hello there"):
            self.runtime.generate_once()
        items = self.runtime.outbox.list_after(cred["device_id"], 0)
        self.assertEqual(len(items), 1)
        envelope = items[0]["envelope"]
        self.assertEqual(envelope["content"], "hello there")
        self.assertEqual(envelope["persona_id"], "persona-1")
        self.assertEqual(envelope["conversation_id"], "conv-1")
        row = self.outbox_row(envelope["message_id"])
        self.assertIsNotNone(row)
        self.assertEqual(row["acked"], 0)
        status = self.runtime.store.status(cred["device_id"])[0]
        self.assertEqual(status["status"], "sent")
        self.assertGreater(status["next_due"], main.now_ms())

    def test_generate_once_skip_queues_nothing(self):
        cred = self._prepare()
        with mock.patch.object(main, "model_decision", lambda profile: None):
            self.runtime.generate_once()
        self.assertEqual(self.runtime.outbox.list_after(cred["device_id"], 0), [])
        status = self.runtime.store.status(cred["device_id"])[0]
        self.assertEqual(status["status"], "chose_silence")
        self.assertGreater(status["next_due"], main.now_ms())

    def test_generate_once_model_error_records_retry(self):
        cred = self._prepare()

        def boom(profile):
            raise FcmFailure("model_http_500")

        before = main.now_ms()
        with mock.patch.object(main, "model_decision", boom):
            self.runtime.generate_once()
        self.assertEqual(self.runtime.outbox.list_after(cred["device_id"], 0), [])
        status = self.runtime.store.status(cred["device_id"])[0]
        self.assertEqual(status["status"], "model_http_500")
        self.assertGreaterEqual(status["next_due"] - before, 250000)


class RuntimeDeliverTests(RuntimeTestBase):
    def test_deliver_once_sends_encrypted_and_waits_for_ack(self):
        cred = self.make_device()
        envelope = character_envelope("msg-1")
        self.runtime.outbox.enqueue(cred["device_id"], envelope, main.now_ms())
        self.runtime.deliver_once()
        self.assertEqual(len(self.sender.calls), 1)
        token, data = self.sender.calls[0]
        self.assertEqual(token, FCM)
        key = base64.b64decode(cred["payload_key"])
        self.assertEqual(decrypt(data, key, cred["device_id"]), envelope)
        self.assertEqual(self.outbox_row("msg-1")["acked"], 0)
        self.assertTrue(self.runtime.outbox.ack(cred["device_id"], "msg-1"))
        self.assertEqual(self.outbox_row("msg-1")["acked"], 1)

    def test_deliver_once_fcm_failure_retries_with_backoff(self):
        cred = self.make_device()
        self.sender.fail = FcmFailure("fcm_unavailable")
        self.runtime.outbox.enqueue(cred["device_id"], character_envelope("msg-1"), main.now_ms())
        before = main.now_ms()
        self.runtime.deliver_once()
        row = self.outbox_row("msg-1")
        self.assertEqual(row["acked"], 0)
        self.assertEqual(row["last_error"], "fcm_unavailable")
        self.assertEqual(row["attempts"], 1)
        self.assertGreater(row["next_attempt"], before)


class ProfileValidationTests(unittest.TestCase):
    def test_valid_profile_is_returned(self):
        profile = valid_profile()
        self.assertEqual(main.validate_profile(profile), profile)

    def test_public_http_model_url_rejected(self):
        with self.assertRaises(ValueError):
            main.validate_profile(valid_profile(chat_url="http://93.184.216.34/v1/chat"))

    def test_url_userinfo_rejected(self):
        with self.assertRaises(ValueError):
            main.validate_profile(
                valid_profile(chat_url="https://user:pass@api.example.com/v1/chat")
            )

    def test_header_crlf_rejected(self):
        with self.assertRaises(ValueError):
            main.validate_profile(
                valid_profile(headers={"X-Test": "ok\r\nX-Injected: 1"})
            )

    def test_host_header_rejected(self):
        with self.assertRaises(ValueError):
            main.validate_profile(valid_profile(headers={"Host": "evil.example"}))

    def test_nonfinite_limits_rejected(self):
        for override in (
            {"temperature": float("inf")},
            {"temperature": float("nan")},
            {"min_minutes": float("nan")},
        ):
            with self.assertRaises(ValueError):
                main.validate_profile(valid_profile(**override))


class QuietUntilTests(unittest.TestCase):
    def test_global_contact_throttle(self):
        self.assertEqual(main.quiet_until(valid_profile(global_last_contact=NOW - 60000), NOW), NOW + 840000)

    def test_busy_defers_one_minute(self):
        now = NOW
        self.assertEqual(main.quiet_until(valid_profile(busy=True), now), now + 60000)

    def test_disabled_dnd_is_not_quiet(self):
        now = NOW
        self.assertEqual(
            main.quiet_until(valid_profile(dnd_start=0, dnd_end=0, last_chat_at=0), now),
            now,
        )

    def test_recent_chat_defers_three_minutes(self):
        now = NOW
        profile = valid_profile(last_chat_at=now - 60000)
        self.assertEqual(main.quiet_until(profile, now), now + 180000)

    def test_overnight_dnd_wraps_midnight(self):
        now = 1400 * 60000  # 23:20 with utc_offset 0
        profile = valid_profile(dnd_start=1380, dnd_end=420, utc_offset=0, last_chat_at=0)
        expected = now + ((420 - 1400) % 1440) * 60000 + 60000
        self.assertEqual(main.quiet_until(profile, now), expected)
        self.assertGreater(expected, now)

    def test_dnd_honours_utc_offset(self):
        now = 600 * 60000  # 10:00 UTC
        profile = valid_profile(dnd_start=1380, dnd_end=420, utc_offset=800, last_chat_at=0)
        minute = (600 + 800) % 1440
        expected = now + ((420 - minute) % 1440) * 60000 + 60000
        self.assertEqual(main.quiet_until(profile, now), expected)

    def test_outside_dnd_window_is_not_quiet(self):
        now = 600 * 60000
        profile = valid_profile(dnd_start=1380, dnd_end=420, utc_offset=0, last_chat_at=0)
        self.assertEqual(main.quiet_until(profile, now), now)


class ModelDecisionTests(unittest.TestCase):
    def _decide(self, content=None, *, status=200, chunks=None, transport=None):
        payload = None
        if content is not None:
            payload = {"choices": [{"message": {"content": content}}]}
        response = FakeResponse(status=status, payload=payload, chunks=chunks)
        used = transport or FakeTransport(response)
        return main.model_decision(valid_profile(), transport=used), used

    def test_send_returns_message_and_makes_one_local_call(self):
        content = json.dumps({"action": "SEND", "message": "hi 你好"})
        result, transport = self._decide(content)
        self.assertEqual(result, "hi 你好")
        self.assertEqual(len(transport.calls), 1)
        url, kwargs = transport.calls[0]
        self.assertEqual(url, "https://api.example.com/v1/chat")
        self.assertFalse(kwargs["allow_redirects"])
        self.assertTrue(kwargs["stream"])
        self.assertEqual(kwargs["json"]["model"], "model-1")

    def test_skip_returns_none(self):
        result, transport = self._decide(json.dumps({"action": "SKIP"}))
        self.assertIsNone(result)
        self.assertEqual(len(transport.calls), 1)

    def test_rejected_contents(self):
        cases = [
            "not json",
            "",
            "<html>oops</html>",
            json.dumps({"action": "MAYBE"}),
            json.dumps({"action": "SEND"}),
        ]
        for content in cases:
            with self.assertRaises(ValueError):
                self._decide(content)

    def test_http_error_is_rejected(self):
        transport = FakeTransport(FakeResponse(status=401))
        with self.assertRaises(FcmFailure) as caught:
            main.model_decision(valid_profile(), transport=transport)
        self.assertEqual(caught.exception.code, "model_http_401")
        self.assertEqual(len(transport.calls), 1)

    def test_oversized_response_is_rejected(self):
        chunks = [b"x" * 8192] * 33  # 270336 bytes > MAX_BODY (262144)
        transport = FakeTransport(FakeResponse(chunks=chunks))
        with self.assertRaises(FcmFailure) as caught:
            main.model_decision(valid_profile(), transport=transport)
        self.assertEqual(caught.exception.code, "model_response_too_large")
        self.assertEqual(len(transport.calls), 1)


if __name__ == "__main__":
    unittest.main()
