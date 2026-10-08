"""Targeted unittest coverage for server.outbox (stdlib only)."""
from __future__ import annotations

import json
import os
import tempfile
import threading
import unittest

from server.outbox import MAX_TIMESTAMP, Outbox


class OutboxTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self._tmp.name, "nested", "outbox.sqlite3")
        self.ob = Outbox(self.path)

    def tearDown(self):
        self._tmp.cleanup()

    def env(self, mid, body="hello"):
        return {"message_id": mid, "body": body}

    def test_restart_persistence(self):
        seq = self.ob.enqueue("dev1", self.env("m1"), 1000)
        reopened = Outbox(self.path)
        rows = reopened.list_after("dev1", 0)
        self.assertEqual([r["seq"] for r in rows], [seq])
        self.assertEqual(rows[0]["envelope"]["message_id"], "m1")

    def test_enqueue_idempotent_and_conflict_immutable(self):
        seq1 = self.ob.enqueue("dev1", self.env("m1"), 1000)
        seq2 = self.ob.enqueue("dev1", self.env("m1"), 2000)
        self.assertEqual(seq1, seq2)
        with self.assertRaisesRegex(ValueError, "message_id_conflict"):
            self.ob.enqueue("dev1", self.env("m1", body="changed"), 3000)
        rows = self.ob.list_after("dev1", 0)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["envelope"]["body"], "hello")

    def test_device_isolation_cursor_and_ack(self):
        a1 = self.ob.enqueue("devA", self.env("m1"), 1000)
        a2 = self.ob.enqueue("devA", self.env("m2"), 1001)
        self.ob.enqueue("devB", self.env("m3"), 1002)
        self.assertEqual([r["seq"] for r in self.ob.list_after("devA", 0)], [a1, a2])
        self.assertEqual([r["seq"] for r in self.ob.list_after("devA", a1)], [a2])
        self.assertEqual([r["seq"] for r in self.ob.list_after("devB", 0)], [3])
        self.assertFalse(self.ob.ack("devB", "m1"))
        self.assertFalse(self.ob.ack("devA", "missing"))
        self.assertTrue(self.ob.ack("devA", "m1"))
        self.assertTrue(self.ob.ack("devA", "m1"))

    def test_concurrent_claims_unique(self):
        total = 50
        for i in range(total):
            self.ob.enqueue("dev", self.env(f"m{i}"), 1000)
        claimed, failures, lock, barrier = [], [], threading.Lock(), threading.Barrier(8)

        def worker():
            try:
                barrier.wait(timeout=10)
                while True:
                    got = self.ob.claim_due(1000, limit=5, lease_ms=60000)
                    if not got:
                        return
                    with lock:
                        claimed.extend(g["seq"] for g in got)
            except Exception as error:
                with lock:
                    failures.append(type(error).__name__)

        threads = [threading.Thread(target=worker) for _ in range(8)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(failures, [])
        self.assertEqual(len(claimed), total)
        self.assertEqual(len(set(claimed)), total)

    def test_lease_expiry_and_stale_lease_rejected(self):
        seq = self.ob.enqueue("dev", self.env("m1"), 1000)
        first = self.ob.claim_due(1000, lease_ms=1000)[0]
        second = self.ob.claim_due(2001, lease_ms=1000)[0]
        self.assertEqual(second["seq"], seq)
        self.assertNotEqual(first["lease_id"], second["lease_id"])
        self.assertEqual(second["attempts"], 2)
        self.assertFalse(self.ob.sent(seq, first["lease_id"], 2001))
        self.assertTrue(self.ob.sent(seq, second["lease_id"], 2001))

    def test_ack_racing_claim_blocks_sent_and_retry(self):
        seq = self.ob.enqueue("dev", self.env("m1"), 1000)
        claimed = self.ob.claim_due(1000, lease_ms=60000)[0]
        self.assertTrue(self.ob.ack("dev", "m1"))
        self.assertFalse(self.ob.sent(seq, claimed["lease_id"], 1001))
        self.assertFalse(self.ob.failed(seq, claimed["lease_id"], 1001, "err"))
        self.assertEqual(self.ob.claim_due(999999999), [])

    def test_sent_remains_in_inbox_and_retries_after_five_minutes(self):
        seq = self.ob.enqueue("dev", self.env("m1"), 1000)
        claimed = self.ob.claim_due(1000, lease_ms=1000)[0]
        self.assertTrue(self.ob.sent(seq, claimed["lease_id"], 1000))
        self.assertEqual([r["seq"] for r in self.ob.list_after("dev", 0)], [seq])
        self.assertEqual(self.ob.claim_due(1000 + 299999), [])
        again = self.ob.claim_due(1000 + 300000, lease_ms=1000)
        self.assertEqual([g["seq"] for g in again], [seq])
        self.assertEqual(again[0]["attempts"], 2)

    def test_backoff_increases_and_expired_rows_stay_in_inbox(self):
        seq = self.ob.enqueue("dev", self.env("m1"), 1000)
        first = self.ob.claim_due(1000, lease_ms=1000)[0]
        self.assertTrue(self.ob.failed(seq, first["lease_id"], 1000, "net_err"))
        self.assertEqual(self.ob.claim_due(1000 + 29999), [])
        second = self.ob.claim_due(1000 + 30000, lease_ms=1000)[0]
        self.assertEqual(second["attempts"], 2)
        self.assertTrue(self.ob.failed(seq, second["lease_id"], 31000, "net_err"))
        self.assertEqual(self.ob.claim_due(31000 + 59999), [])
        self.assertEqual(len(self.ob.claim_due(31000 + 60000)), 1)

        expiring = self.ob.enqueue("dev", self.env("m2"), 1000)
        expiry = 1000 + 86400000
        self.assertEqual(self.ob.claim_due(expiry + 1), [])
        self.assertEqual(
            [r["seq"] for r in self.ob.list_after("dev", 0)], [seq, expiring]
        )

    def test_validation_rejects_malformed_arguments(self):
        for value in (float("nan"), float("inf"), -float("inf")):
            with self.assertRaises(ValueError):
                self.ob.enqueue("dev", {"message_id": "m", "value": value}, 1000)
        with self.assertRaises(ValueError):
            self.ob.enqueue("", self.env("m1"), 1000)
        with self.assertRaises(ValueError):
            self.ob.enqueue("d" * 129, self.env("m1"), 1000)
        with self.assertRaises(ValueError):
            self.ob.enqueue("dev", {"message_id": "  "}, 1000)
        with self.assertRaises(ValueError):
            self.ob.enqueue("dev", {"message_id": "m" * 129}, 1000)
        with self.assertRaises(ValueError):
            self.ob.enqueue("dev", ["not", "dict"], 1000)
        with self.assertRaises(ValueError):
            self.ob.enqueue("dev", self.env("m", "x" * 40000), 1000)
        with self.assertRaises(ValueError):
            self.ob.enqueue("dev", self.env("m1"), True)
        with self.assertRaises(ValueError):
            self.ob.enqueue("dev", self.env("m1"), 0)
        with self.assertRaises(ValueError):
            self.ob.enqueue("dev", self.env("m1"), MAX_TIMESTAMP)
        self.ob.enqueue("dev", self.env("m1"), 1000)
        with self.assertRaises(ValueError):
            self.ob.list_after("dev", -1)
        with self.assertRaises(ValueError):
            self.ob.list_after("dev", True)
        with self.assertRaises(ValueError):
            self.ob.list_after("dev", 0, limit=0)
        with self.assertRaises(ValueError):
            self.ob.list_after("dev", 0, limit=101)
        with self.assertRaises(ValueError):
            self.ob.claim_due(True)
        with self.assertRaises(ValueError):
            self.ob.claim_due(1000, lease_ms=999)
        with self.assertRaises(ValueError):
            self.ob.claim_due(1000, lease_ms=300001)
        with self.assertRaises(ValueError):
            self.ob.claim_due(1000, limit=0)
        with self.assertRaises(ValueError):
            self.ob.failed(1, "lease", 1000, "Bad-Code")
        with self.assertRaises(ValueError):
            self.ob.failed(1, "lease", 1000, "")
        with self.assertRaises(ValueError):
            self.ob.sent(1, "", 1000)
        with self.assertRaises(ValueError):
            self.ob.sent(1, "lease", -1)


if __name__ == "__main__":
    unittest.main()
