"""Runnable transport integration: loopback HTTP + real encryption, fake model/FCM.
No user credentials or production database are accessed.
"""
import base64
from contextlib import closing
import hashlib
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from test_main import ServerHTTPTests, RuntimeTestBase, valid_profile, character_envelope, FCM, main
from fcm_sender import FcmFailure, build_request
from hub_store import HubStore
from outbox import Outbox
from push_crypto import decrypt


class Sender:
    def __init__(self):
        self.requests = []
        self.failure = None
    def send(self, token, data, **options):
        self.requests.append(build_request(token, data, **options))
        if self.failure:
            raise self.failure
        return 'projects/fake/messages/receipt-1'


class TransportEndToEnd(ServerHTTPTests):
    def test_generation_without_client_then_fcm_and_silent_recovery_ack(self):
        cred = self.pair_device()
        auth = {'Authorization': 'Bearer ' + cred['token']}
        body = {'fcm_token': FCM, 'profiles': [valid_profile()], 'after': 0, 'acks': [], 'system_notification': True}
        status, result = self.post_json('/v1/sync', body, auth)
        self.assertEqual(200, status)
        self.assertTrue(result['system_notification'])
        sender = Sender()
        self.runtime.sender = sender
        # There is NO client request between generation and FCM delivery.
        with mock.patch.object(main, 'model_decision', return_value='PRIVATE_GENERATED_GREETING'):
            self.runtime.generate_once()
        self.runtime.deliver_once()
        request = sender.requests[0]['message']
        self.assertEqual('character_messages', request['android']['notification']['channel_id'])
        self.assertNotIn('PRIVATE_GENERATED_GREETING', json.dumps(request))
        envelope = decrypt(request['data'], base64.b64decode(cred['payload_key']), cred['device_id'])
        self.assertEqual('PRIVATE_GENERATED_GREETING', envelope['content'])
        mid = envelope['message_id']
        self.assertEqual('maid-remote:' + hashlib.sha256(mid.encode()).hexdigest(), request['android']['notification']['tag'])
        row = self._query_one('SELECT * FROM outbox WHERE message_id=?', (mid,))
        self.assertEqual(0, row['acked'])  # Google accepted, phone has not acknowledged.
        self.assertEqual('projects/fake/messages/receipt-1', row['fcm_receipt'])
        self.assertGreater(row['first_fcm_at'], 0)
        with mock.patch.object(main, 'now_ms', return_value=main.now_ms() + 600000):
            self.runtime.deliver_once()
        self.assertEqual(1, len(sender.requests))  # No repeated system buzz every 5 min.
        status, result = self.post_json('/v1/sync', body, auth)
        self.assertEqual(200, status)
        self.assertEqual(1, len(result['items']))
        self.assertEqual(mid, decrypt(result['items'][0]['data'], base64.b64decode(cred['payload_key']), cred['device_id'])['message_id'])
        receipt = {'message_id': mid, 'source': 'sync', 'outcome': 'saved_silent'}
        status, _ = self.post_json('/v1/ack', {'acks': [mid], 'receipts': [receipt]}, auth)
        self.assertEqual(200, status)
        row = self._query_one('SELECT * FROM outbox WHERE message_id=?', (mid,))
        self.assertEqual((1, 'sync', 'saved_silent'), (row['acked'], row['received_via'], row['delivery_outcome']))
        self.assertGreater(row['acked_at'], 0)

    def test_invalid_receipt_does_not_ack(self):
        cred = self.pair_device()
        self.runtime.outbox.enqueue(cred['device_id'], character_envelope(), main.now_ms())
        status, _ = self.post_json('/v1/ack', {'acks': ['msg-1'], 'receipts': [
            {'message_id': 'msg-1', 'source': 'fake_status', 'outcome': 'notified'}
        ]}, {'Authorization': 'Bearer ' + cred['token']})
        self.assertEqual(400, status)
        self.assertEqual(0, self.outbox_row('msg-1')['acked'])


class TokenLifecycle(RuntimeTestBase):
    def test_unregistered_cleared_and_old_token_cannot_reactivate(self):
        cred = self.make_device()
        sender = Sender()
        sender.failure = FcmFailure('fcm_token_unregistered', 404)
        self.runtime.sender = sender
        self.runtime.outbox.enqueue(cred['device_id'], character_envelope(), main.now_ms())
        self.runtime.deliver_once()
        self.runtime.store.register(cred['device_id'], FCM, system_notification=True, now=main.now_ms())
        device = self.runtime.store.device(cred['device_id'])
        self.assertEqual('', device['fcm_token'])
        self.assertEqual('fcm_token_unregistered', device['token_error'])
        self.runtime.store.register(cred['device_id'], 'new-fake-fcm-token', system_notification=True, now=main.now_ms())
        device = self.runtime.store.device(cred['device_id'])
        self.assertEqual('new-fake-fcm-token', device['fcm_token'])
        self.assertEqual('', device['token_error'])
        self.assertTrue(device['system_notification'])

    def test_old_failure_does_not_clear_newly_registered_token(self):
        cred = self.make_device()
        self.runtime.store.register(cred['device_id'], 'new-fake-fcm-token')
        self.assertFalse(self.runtime.store.invalidate_token(cred['device_id'], FCM, 'fcm_token_unregistered'))
        self.assertEqual('new-fake-fcm-token', self.runtime.store.device(cred['device_id'])['fcm_token'])

    def test_server_retry_after_is_respected_and_no_false_ack(self):
        cred = self.make_device()
        sender = Sender()
        sender.failure = FcmFailure('fcm_rate_limited', 429, retry_after=900)
        self.runtime.sender = sender
        now = main.now_ms()
        self.runtime.outbox.enqueue(cred['device_id'], character_envelope(), now)
        self.runtime.deliver_once()
        row = self.outbox_row('msg-1')
        self.assertGreaterEqual(row['next_attempt'], now + 900000)
        self.assertEqual(0, row['acked'])
        self.assertEqual(FCM, self.runtime.store.device(cred['device_id'])['fcm_token'])


class MigrationTests(unittest.TestCase):
    def test_existing_database_is_migrated_without_data_loss(self):
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / 'old.db')
            with closing(sqlite3.connect(path)) as c:
                import hub_store, outbox
                c.executescript(hub_store._SCHEMA + outbox._SCHEMA)
                c.execute("INSERT INTO devices(device_id,token_hash,payload_key,fcm_token) VALUES ('dev','hash','key','fake-token')")
                c.execute("INSERT INTO outbox(device_id,message_id,envelope,created,next_attempt,expires) VALUES ('dev','mid','{}',1,1,2)")
                c.commit()
            store = HubStore(path)
            self.assertEqual('fake-token', store.device('dev')['fcm_token'])
            self.assertFalse(store.device('dev')['system_notification'])
            with closing(sqlite3.connect(path)) as c:
                self.assertEqual(('mid', 0, ''), c.execute('SELECT message_id,first_fcm_at,received_via FROM outbox').fetchone())

def load_tests(loader, tests, pattern):
    suite = unittest.TestSuite()
    for cls in (TransportEndToEnd, TokenLifecycle, MigrationTests):
        for name in cls.__dict__:
            if name.startswith('test_'):
                suite.addTest(cls(name))
    return suite

if __name__ == '__main__':
    unittest.main()
