"""Local-only status export; never reads message text, model keys or raw device tokens."""
import argparse
import hashlib
from contextlib import closing
import json
from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parent

def report(database):
    with closing(sqlite3.connect(Path(database).resolve().as_uri() + '?mode=ro', uri=True)) as db:
        db.row_factory = sqlite3.Row
        devices = [dict(row) for row in db.execute(
            "SELECT active,system_notification,token_updated_at,token_error,length(fcm_token)>0 AS token_present FROM devices")]
        messages = [dict(row) for row in db.execute(
            "SELECT message_id,created,attempts,last_error,transport,first_fcm_at,last_fcm_at,fcm_receipt,acked,acked_at,received_via,delivery_outcome"
            " FROM outbox ORDER BY seq DESC LIMIT 30")]
        for item in messages:
            item['id'] = hashlib.sha256(item.pop('message_id').encode()).hexdigest()[:12]
        return {'devices': devices, 'messages': messages,
                'note': 'FCM acceptance is not device receipt. System notifications may only ACK after a tap or sync.'}

def validate_only():
    # This explicit operator option validates a fixed synthetic encrypted envelope at real FCM.
    # It never delivers a notification or reads a persona/chat/model credential.
    import base64
    import hashlib
    from fcm_sender import FcmSender
    from push_crypto import encrypt
    with closing(sqlite3.connect(ROOT / '.secrets' / 'hub.sqlite3')) as db:
        row = db.execute("SELECT device_id,payload_key,fcm_token FROM devices WHERE active=1 AND fcm_token<>'' LIMIT 1").fetchone()
    if not row:
        raise ValueError('no_registered_device')
    sender = FcmSender(ROOT / '.secrets' / 'fcm-service-account.json', 'maid-manager-b4c9e')
    try:
        sender.send(row[2], encrypt({'test': 'transport_validation_only'}, base64.b64decode(row[1]), row[0]),
                    validate_only=True, notification_tag='maid-remote:' + hashlib.sha256(b'transport-validation-only').hexdigest())
        return {'fcm_http_status': 200, 'result': 'validated_without_delivery'}
    finally:
        sender.close()

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--validate-system-notification', action='store_true')
    args = parser.parse_args()
    try:
        print(json.dumps(validate_only() if args.validate_system_notification else report(ROOT / '.secrets' / 'hub.sqlite3'), ensure_ascii=True))
    except Exception as error:
        from fcm_sender import FcmFailure
        print(json.dumps({'error': error.code if isinstance(error, FcmFailure) else type(error).__name__}))
        raise SystemExit(1)
