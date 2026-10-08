"""Tests for the private push-server state store (stdlib unittest)."""
from __future__ import annotations

import os
import contextlib
import sqlite3
import tempfile
import unittest

try:  # importable both as a top-level module and as package ``server``.
    from server.hub_store import HubStore
    from server.outbox import Outbox
except ImportError:  # pragma: no cover - depends on invocation style
    from hub_store import HubStore
    from outbox import Outbox

NOW = 1_700_000_000_000  # ms, well above 1e12
FCM = "FCMTOKEN_1234567"


def _outbox_ready() -> bool:
    return hasattr(Outbox, "enqueue_transaction")


class HubStoreTestCase(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self._tmp.name, "hub.db")
        self.store = HubStore(self.path)

    def tearDown(self):
        self._tmp.cleanup()

    # -- helpers -----------------------------------------------------------
    def pair_device(self, now=NOW) -> dict:
        code = self.store.create_pairing(now)
        return self.store.pair(code, now)

    def prof(self, persona_id, now_ts=NOW, next_at=None, extra=None) -> dict:
        profile = {
            "persona_id": persona_id,
            "conversation_id": "c-" + persona_id,
            "next_at": now_ts if next_at is None else next_at,
        }
        if extra:
            profile.update(extra)
        return profile

    @staticmethod
    def _wrong(code: str) -> str:
        return "00000000" if code != "00000000" else "00000001"


class PairingTests(HubStoreTestCase):
    def test_wrong_attempts_lock_out(self):
        code = self.store.create_pairing(NOW)
        for _ in range(5):
            with self.assertRaises(ValueError):
                self.store.pair(self._wrong(code), NOW)
        with self.assertRaises(ValueError):
            self.store.pair(code, NOW)

    def test_malformed_code_counts_attempt(self):
        code = self.store.create_pairing(NOW)
        for _ in range(5):
            with self.assertRaises(ValueError):
                self.store.pair("not-a-code", NOW)
        with self.assertRaises(ValueError):
            self.store.pair(code, NOW)

    def test_ttl_expiry(self):
        code = self.store.create_pairing(NOW)
        with self.assertRaises(ValueError):
            self.store.pair(code, NOW + 600000)
        fresh = self.store.create_pairing(NOW)
        dev = self.store.pair(fresh, NOW + 599999)
        self.assertEqual(len(dev["device_id"]), 32)

    def test_unknown_and_single_use(self):
        with self.assertRaises(ValueError):
            self.store.pair("12345678", NOW)
        code = self.store.create_pairing(NOW)
        dev = self.store.pair(code, NOW)
        self.assertEqual(len(dev["token"]), 43)
        with self.assertRaises(ValueError):
            self.store.pair(code, NOW)

    def test_device_cap_does_not_evict(self):
        for _ in range(8):
            self.pair_device()
        code = self.store.create_pairing(NOW)
        with self.assertRaises(ValueError):
            self.store.pair(code, NOW)
        with self.assertRaises(ValueError):  # still rejected, not consumed
            self.store.pair(code, NOW)


class AuthTests(HubStoreTestCase):
    def test_authenticate_unknown_and_valid(self):
        self.assertIsNone(self.store.authenticate("x" * 40))
        self.assertIsNone(self.store.authenticate("short"))
        self.assertIsNone(self.store.authenticate(12345))
        dev = self.pair_device()
        self.assertEqual(self.store.authenticate(dev["token"]), dev["device_id"])

    def test_device_view_hides_token_hash(self):
        dev = self.pair_device()
        info = self.store.device(dev["device_id"])
        self.assertEqual(
            set(info.keys()), {"id", "payload_key", "fcm_token", "active"}
        )
        self.assertIsNone(self.store.device("missing"))

    def test_register_validates_and_requires_device(self):
        dev = self.pair_device()
        self.store.register(dev["device_id"], FCM)
        self.assertEqual(self.store.device(dev["device_id"])["fcm_token"], FCM)
        with self.assertRaises(ValueError):
            self.store.register(dev["device_id"], "short")
        with self.assertRaises(ValueError):
            self.store.register(dev["device_id"], "bad token!")
        with self.assertRaises(ValueError):
            self.store.register("0" * 32, FCM)

    def test_pause_and_resume(self):
        dev = self.pair_device()
        device_id = dev["device_id"]
        self.store.register(device_id, FCM)
        self.store.snapshot(device_id, [self.prof("p1")], NOW)
        self.store.pause(device_id)
        info = self.store.device(device_id)
        self.assertEqual(info["active"], 0)
        self.assertEqual(info["fcm_token"], "")
        self.assertEqual(self.store.status(device_id), [])
        # auth credential survives pause; snapshot resumes the device
        self.assertEqual(self.store.authenticate(dev["token"]), device_id)
        self.store.snapshot(device_id, [self.prof("p1")], NOW)
        self.assertEqual(self.store.device(device_id)["active"], 1)


class SnapshotTests(HubStoreTestCase):
    def test_per_device_isolation_and_deletion(self):
        a = self.pair_device()["device_id"]
        b = self.pair_device()["device_id"]
        self.store.snapshot(a, [self.prof("p1"), self.prof("p2")], NOW)
        self.store.snapshot(b, [self.prof("p9")], NOW)
        self.assertEqual(
            [p["persona_id"] for p in self.store.status(a)], ["p1", "p2"]
        )
        self.assertEqual([p["persona_id"] for p in self.store.status(b)], ["p9"])
        self.store.snapshot(a, [self.prof("p1")], NOW)
        self.assertEqual([p["persona_id"] for p in self.store.status(a)], ["p1"])
        self.assertEqual([p["persona_id"] for p in self.store.status(b)], ["p9"])
        self.store.snapshot(a, [], NOW)
        self.assertEqual(self.store.status(a), [])
        self.assertEqual([p["persona_id"] for p in self.store.status(b)], ["p9"])

    def test_unchanged_sync_preserves_next_due_and_refreshes_synced_at(self):
        device_id = self.pair_device()["device_id"]
        self.store.snapshot(device_id, [self.prof("p1")], NOW)
        first = self.store.status(device_id)[0]
        later = NOW + 5000
        self.store.snapshot(device_id, [self.prof("p1")], later)
        after = self.store.status(device_id)[0]
        self.assertEqual(after["next_due"], first["next_due"])
        self.assertEqual(after["synced_at"], later)

    def test_snapshot_validation(self):
        device_id = self.pair_device()["device_id"]
        with self.assertRaises(ValueError):
            self.store.snapshot("missing", [self.prof("p1")], NOW)
        with self.assertRaises(ValueError):
            self.store.snapshot(
                device_id, [self.prof(f"p{i}") for i in range(33)], NOW
            )
        with self.assertRaises(ValueError):
            self.store.snapshot(device_id, [self.prof("p1"), self.prof("p1")], NOW)
        with self.assertRaises(ValueError):
            self.store.snapshot(device_id, [{"persona_id": "p1"}], NOW)
        with self.assertRaises(ValueError):
            self.store.snapshot(
                device_id, [self.prof("p1", extra={"next_at": True})], NOW
            )
        with self.assertRaises(ValueError):
            self.store.snapshot(
                device_id, [self.prof("p1", extra={"blob": "x" * 300000})], NOW
            )


class ClaimCompleteTests(HubStoreTestCase):
    def _ready_device(self, persona="p1"):
        device_id = self.pair_device()["device_id"]
        self.store.register(device_id, FCM)
        self.store.snapshot(device_id, [self.prof(persona)], NOW)
        return device_id

    def test_claim_lease_not_stolen_until_expiry(self):
        device_id = self._ready_device()
        first = self.store.claim(NOW)
        self.assertEqual(first["device_id"], device_id)
        self.assertEqual(first["persona_id"], "p1")
        self.assertEqual(first["body"]["persona_id"], "p1")
        self.assertIsNone(self.store.claim(NOW + 1))
        self.assertIsNone(self.store.claim(NOW + 179999))
        again = self.store.claim(NOW + 180000)
        self.assertIsNotNone(again)
        self.assertNotEqual(again["lease_id"], first["lease_id"])

    def test_claim_requires_active_token_and_not_stale_sync(self):
        device_id = self._ready_device()
        self.store.pause(device_id)
        self.assertIsNone(self.store.claim(NOW))
        device_id = self._ready_device()
        self.store.snapshot(device_id, [self.prof("p1")], NOW)
        self.assertIsNone(self.store.claim(NOW + 86400000))  # synced_at out of window

    def test_complete_invalidated_on_revision_change(self):
        device_id = self._ready_device()
        claim = self.store.claim(NOW)
        self.store.snapshot(device_id, [self.prof("p1", extra={"v": 2})], NOW + 1)
        self.assertFalse(self.store.complete(claim, NOW + 2, NOW + 1000, "sent"))

    def test_complete_invalidated_on_pause_and_deletion(self):
        device_id = self._ready_device()
        claim = self.store.claim(NOW)
        self.store.pause(device_id)
        self.assertFalse(self.store.complete(claim, NOW + 1, NOW + 1000, "sent"))

        device_id = self._ready_device()
        claim = self.store.claim(NOW)
        self.store.snapshot(device_id, [], NOW + 1)
        self.assertFalse(self.store.complete(claim, NOW + 2, NOW + 1000, "waiting"))

    def test_complete_invalidated_on_expired_lease(self):
        device_id = self._ready_device()
        claim = self.store.claim(NOW)
        self.assertFalse(
            self.store.complete(claim, NOW + 180000, NOW + 200000, "sent")
        )
        self.assertEqual(self.store.status(device_id)[0]["status"], "waiting")

    def test_complete_validation(self):
        device_id = self._ready_device()
        claim = self.store.claim(NOW)
        with self.assertRaises(ValueError):
            self.store.complete(claim, NOW, NOW - 1, "sent")
        with self.assertRaises(ValueError):
            self.store.complete(claim, NOW, NOW + 1, "BAD STATUS")
        self.assertTrue(self.store.complete(claim, NOW, NOW + 60000, "skipped"))

    @unittest.skipUnless(_outbox_ready(), "Outbox.enqueue_transaction unavailable")
    def test_complete_success_enqueues_one_row_and_sets_throttle(self):
        device_id = self._ready_device()
        claim = self.store.claim(NOW)
        envelope = {
            "message_id": "m-1",
            "persona_id": "p1",
            "conversation_id": "c-p1",
            "body": "hello",
        }
        self.assertTrue(
            self.store.complete(claim, NOW, NOW, "sent", envelope)
        )
        state = self.store.status(device_id)[0]
        self.assertEqual(state["next_due"], NOW)
        self.assertEqual(state["status"], "sent")
        with contextlib.closing(sqlite3.connect(self.path)) as conn:
            count = conn.execute("SELECT COUNT(*) FROM outbox").fetchone()[0]
        self.assertEqual(count, 1)
        # device throttle now blocks further claims for this device
        self.assertIsNone(self.store.claim(NOW + 1))

    @unittest.skipUnless(_outbox_ready(), "Outbox.enqueue_transaction unavailable")
    def test_complete_rejects_mismatched_envelope(self):
        self._ready_device()
        claim = self.store.claim(NOW)
        envelope = {
            "message_id": "m-1",
            "persona_id": "other",
            "conversation_id": "c-p1",
        }
        with self.assertRaises(ValueError):
            self.store.complete(claim, NOW, NOW, "sent", envelope)

    @unittest.skipUnless(_outbox_ready(), "Outbox.enqueue_transaction unavailable")
    def test_throttle_is_per_device_not_global(self):
        a = self.pair_device()["device_id"]
        b = self.pair_device()["device_id"]
        self.store.register(a, FCM)
        self.store.register(b, FCM)
        self.store.snapshot(a, [self.prof("pa")], NOW)
        self.store.snapshot(b, [self.prof("pb")], NOW)
        claim_a = self.store.claim(NOW)
        self.assertEqual(claim_a["device_id"], a)
        envelope = {
            "message_id": "m-a",
            "persona_id": "pa",
            "conversation_id": "c-pa",
        }
        self.assertTrue(self.store.complete(claim_a, NOW, NOW, "sent", envelope))
        # a is throttled, b must still be claimable
        claim_b = self.store.claim(NOW + 1)
        self.assertIsNotNone(claim_b)
        self.assertEqual(claim_b["device_id"], b)


if __name__ == "__main__":
    unittest.main()
