"""Local, bounded transport diagnostics. Never accepts message text or credentials."""
import hashlib
import json
import logging
from logging.handlers import RotatingFileHandler

logger = logging.getLogger('maid.delivery')

def configure(directory):
    directory.mkdir(exist_ok=True)
    handler = RotatingFileHandler(directory / 'delivery.jsonl', maxBytes=2_000_000, backupCount=2, encoding='utf-8')
    handler.setFormatter(logging.Formatter('%(asctime)s %(message)s'))
    logger.addHandler(handler)
    logger.setLevel(logging.INFO)

def event(stage, message_id='', code='', status=0):
    # All callers use fixed stage/code enums. No provider exceptions or tokens.
    safe = lambda value: ''.join(c for c in str(value)[:64] if c.isascii() and (c.isalnum() or c == '_'))
    logger.info(json.dumps({'stage': safe(stage),
        'id': hashlib.sha256(message_id.encode()).hexdigest()[:12] if message_id else '',
        'code': safe(code), 'status': int(status)}, separators=(',', ':')))
