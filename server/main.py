"""Private Tailscale companion push service. No public listener, analytics, or cloud history."""
from __future__ import annotations

import argparse
import base64
import hashlib
import ipaddress
import json
import math
import os
from pathlib import Path
import random
import re
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

import requests

from fcm_sender import FcmFailure, FcmSender
from hub_store import HubStore
from outbox import Outbox
from push_crypto import encrypt
from delivery_log import event, configure as configure_delivery_log

ROOT = Path(__file__).resolve().parent
PRIVATE = ROOT / ".secrets"
PROJECT = "maid-manager-b4c9e"
MAX_BODY = 262144
TAILNET = ipaddress.ip_network("100.64.0.0/10")


def now_ms():
    return int(time.time() * 1000)


def text(value, limit, *, blank=False):
    if not isinstance(value, str) or len(value) > limit or (not blank and not value.strip()):
        raise ValueError("invalid_profile")
    return value


def number(value, lower, upper):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not lower <= value <= upper:
        raise ValueError("invalid_profile")
    return value


def validate_profile(p):
    if not isinstance(p, dict):
        raise ValueError("invalid_profile")
    for field in ("persona_id", "conversation_id", "provider_id"):
        value = text(p.get(field), 128)
        if value != value.strip() or any(ord(c) < 32 for c in value):
            raise ValueError("invalid_profile")
    text(p.get("model_id"), 200)
    text(p.get("prompt"), 12000)
    url = urlsplit(text(p.get("chat_url"), 2048))
    if not url.hostname or url.username or url.password or url.query or url.fragment or url.scheme not in ("https", "http"):
        raise ValueError("invalid_model_url")
    if url.scheme == "http":
        try:
            host = ipaddress.ip_address(url.hostname)
            private = host.is_private or host.is_loopback or host in TAILNET
        except ValueError:
            private = url.hostname == "localhost"
        if not private:
            raise ValueError("public_model_http_rejected")
    headers = p.get("headers", {})
    if not isinstance(headers, dict) or len(headers) > 24:
        raise ValueError("invalid_model_headers")
    for name, value in headers.items():
        if (not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", name)
                or name.lower() in ("host", "content-length", "transfer-encoding", "connection", "cookie")):
            raise ValueError("invalid_model_headers")
        if any(c in text(value, 8192, blank=True) for c in ("\r", "\n", "\x00")):
            raise ValueError("invalid_model_headers")
    for field, lo, hi in (("temperature", 0, 1.2), ("min_minutes", 15, 1440), ("max_minutes", 15, 1440),
                          ("dnd_start", 0, 1439), ("dnd_end", 0, 1439), ("utc_offset", -840, 840),
                          ("next_at", 0, 253402300799999), ("last_chat_at", 0, 253402300799999)):
        number(p.get(field), lo, hi)
    if p["max_minutes"] < p["min_minutes"] or not isinstance(p.get("busy"), bool):
        raise ValueError("invalid_profile")
    number(p.get("global_last_contact", 0), 0, 253402300799999)
    return p


def quiet_until(p, now):
    last_contact = p.get("global_last_contact", 0)
    if now - 900000 < last_contact <= now + 300000:
        return int(last_contact) + 900000
    if p["busy"]:
        return now + 60000
    start, end = int(p["dnd_start"]), int(p["dnd_end"])
    minute = (now // 60000 + int(p["utc_offset"])) % 1440
    quiet = start != end and (start <= minute < end if start < end else minute >= start or minute < end)
    if quiet:
        return now + ((end - minute) % 1440) * 60000 + 60000
    last_at = p["last_chat_at"]
    if now - 180000 < last_at <= now + 300000:
        return now + 180000
    return now


def model_decision(profile, transport=requests):
    deadline = time.monotonic() + 120
    prompt = ("现在是 UTC 时间 " + time.strftime("%Y-%m-%d %H:%M:%S", time.gmtime()) + "。\n"
        "你根据下列已保存的人设与近期对话，判断是否值得主动联系用户。保存的聊天不代表用户当前正在做什么。"
        "本请求没有身体、屏幕或实时位置数据，不得假装看到了。不要执行工具或动作。"
        "只输出 JSON：{\"action\":\"SEND|SKIP\",\"message\":\"简短自然的人设消息\"}。"
        "没有值得说的就 SKIP；不要机械提醒、反复问在不在或推销功能。SEND 正文不超过450字。\n\n" + profile["prompt"])
    payload = {"model": profile["model_id"], "messages": [{"role": "system", "content": prompt}],
               "temperature": profile["temperature"], "stream": False, "max_tokens": 1800}
    with transport.post(profile["chat_url"], headers=profile.get("headers", {}), json=payload,
                        timeout=(10, 90), allow_redirects=False, stream=True) as response:
        if response.status_code != 200:
            raise FcmFailure("model_http_" + str(response.status_code))
        chunks, size = [], 0
        for chunk in response.iter_content(8192):
            if time.monotonic() > deadline:
                raise FcmFailure("model_timeout")
            size += len(chunk)
            if size > MAX_BODY:
                raise FcmFailure("model_response_too_large")
            chunks.append(chunk)
        parsed = json.loads(b"".join(chunks).decode("utf-8"))
    content = parsed["choices"][0]["message"]["content"]
    if not isinstance(content, str):
        raise ValueError("invalid_model_response")
    content = content.strip().removeprefix("```json").removeprefix("```").removesuffix("```").strip()
    decision = json.loads(content)
    if not isinstance(decision, dict) or decision.get("action") not in ("SEND", "SKIP"):
        raise ValueError("invalid_model_decision")
    if decision["action"] == "SKIP":
        return None
    answer = text(decision.get("message"), 8000).strip()[:450]
    if any(ord(c) < 32 and c not in "\n\t\r" for c in answer):
        raise ValueError("invalid_model_decision")
    return answer


class Runtime:
    def __init__(self, database, sender):
        self.store = HubStore(str(database))
        self.outbox = Outbox(str(database))
        self.sender = sender
        self.stopping = threading.Event()

    def generate_once(self):
        claim = self.store.claim(now_ms())
        if not claim:
            return
        profile = claim["body"]
        now = now_ms()
        try:
            at = quiet_until(profile, now)
            if at > now:
                self.store.complete(claim, now, at, "quiet")
                return
            answer = model_decision(profile)
            now = now_ms()
            envelope = None
            if answer:
                envelope = {"schema_version": "1", "type": "character_message", "message_id": str(uuid.uuid4()),
                    "conversation_id": profile["conversation_id"], "persona_id": profile["persona_id"],
                    "content": answer, "created_at": str(now), "provider_id": profile["provider_id"], "model_id": profile["model_id"]}
                device = self.store.device(claim["device_id"])
                if device is None or not device["active"]:
                    return
                # Validate final encrypted size before committing this exact content to history.
                encrypt(envelope, base64.b64decode(device["payload_key"]), device["id"])
            delay = random.uniform(profile["min_minutes"], profile["max_minutes"]) * 60000
            committed = self.store.complete(claim, now, now + int(delay), "sent" if answer else "chose_silence", envelope)
            if committed and envelope:
                event('generated_saved', envelope['message_id'])
        except Exception as error:
            code = error.code if isinstance(error, FcmFailure) else "generation_failed"
            now = now_ms()
            self.store.complete(claim, now, now + 300000, code)
            event('generation_failed', code=code)

    def deliver_once(self):
        for item in self.outbox.claim_due(now_ms(), limit=1, lease_ms=90000):
            now = now_ms()
            device = None
            try:
                device = self.store.device(item["device_id"])
                if not device or not device["active"] or not device["fcm_token"]:
                    code = 'device_paused' if not device or not device['active'] else device['token_error'] or 'fcm_token_missing'
                    self.outbox.failed(item["seq"], item["lease_id"], now, code)
                    event('delivery_waiting', item['message_id'], code)
                    continue
                data = encrypt(item["envelope"], base64.b64decode(device["payload_key"]), device["id"])
                mode = 'system' if device['system_notification'] else 'data'
                options = {'notification_tag': 'maid-remote:' + hashlib.sha256(item['message_id'].encode()).hexdigest()} if mode == 'system' else {}
                event('fcm_request', item['message_id'], mode)
                receipt = self.sender.send(device["fcm_token"], data, **options)
                self.outbox.sent(item["seq"], item["lease_id"], now_ms(), receipt=receipt, transport=mode)
                event('fcm_accepted', item['message_id'], mode, 200)
            except Exception as error:
                code = error.code if isinstance(error, FcmFailure) else "delivery_failed"
                if code in ('fcm_token_unregistered', 'fcm_token_invalid') and device:
                    self.store.invalidate_token(item['device_id'], device['fcm_token'], code)
                self.outbox.failed(item["seq"], item["lease_id"], now_ms(), code,
                    retry_after=error.retry_after if isinstance(error, FcmFailure) else 0)
                event('fcm_failed', item['message_id'], code, error.status if isinstance(error, FcmFailure) else 0)

    def loop(self, operation):
        while not self.stopping.is_set():
            try:
                operation()
            except Exception:
                print("后台处理暂未完成，将自动重试。", flush=True)
            self.stopping.wait(5)


def handler(runtime):
    class Handler(BaseHTTPRequestHandler):
        server_version = "MaidPrivatePush"
        def setup(self):
            super().setup()
            self.connection.settimeout(10)

        def log_message(self, *_args):
            pass  # No request paths, tokens, headers or chat content in logs.

        def respond(self, status, body):
            data = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(data)
            self.close_connection = True

        def allowed(self):
            address = ipaddress.ip_address(self.client_address[0])
            return (address.is_loopback or address in TAILNET) and self.headers.get("Origin") is None

        def do_GET(self):
            if not self.allowed():
                return self.respond(403, {"error": "private_network_required"})
            if self.path != "/health":
                return self.respond(404, {"error": "not_found"})
            self.respond(200, {"service": "maid-private-push", "version": 1, "ready": True})

        def do_POST(self):
            if not self.allowed():
                return self.respond(403, {"error": "private_network_required"})
            length = self.headers.get("Content-Length", "")
            if self.headers.get("Transfer-Encoding") or not length.isdecimal() or not 0 < int(length) <= MAX_BODY:
                return self.respond(413, {"error": "invalid_body_size"})
            if self.headers.get_content_type() != "application/json":
                return self.respond(415, {"error": "json_required"})
            try:
                body = json.loads(self.rfile.read(int(length)).decode("utf-8"))
                if not isinstance(body, dict):
                    raise ValueError()
                if self.path == "/v1/pair":
                    return self.respond(200, runtime.store.pair(body.get("code"), now_ms()))
                authorization = self.headers.get("Authorization", "")
                device_id = runtime.store.authenticate(authorization[7:] if authorization.startswith("Bearer ") else "")
                if device_id is None:
                    return self.respond(401, {"error": "pairing_required"})
                receipts = body.get('receipts', [])
                if not isinstance(receipts, list) or len(receipts) > 100:
                    raise ValueError('invalid_receipts')
                receipt_map = {}
                for receipt in receipts:
                    if (not isinstance(receipt, dict) or receipt.get('source') not in ('legacy', 'fcm', 'notification_tap', 'sync')
                            or receipt.get('outcome') not in ('notified', 'saved_silent', 'blocked', 'discarded')):
                        raise ValueError('invalid_receipt')
                    receipt_map[text(receipt.get('message_id'), 128)] = receipt
                def acknowledge(mid):
                    receipt = receipt_map.get(mid, {})
                    if runtime.outbox.ack(device_id, mid, source=receipt.get('source', ''), outcome=receipt.get('outcome', '')):
                        event('device_ack', mid, receipt.get('source', 'legacy'))
                if self.path == "/v1/pause":
                    runtime.store.pause(device_id)
                    return self.respond(200, {"paused": True})
                if self.path == "/v1/ack":
                    acks = body.get("acks")
                    if not isinstance(acks, list) or len(acks) > 100:
                        raise ValueError()
                    for mid in acks:
                        text(mid, 128)
                    for mid in acks:
                        acknowledge(mid)
                    return self.respond(200, {"acked": acks})
                if self.path != "/v1/sync":
                    return self.respond(404, {"error": "not_found"})
                profiles = body.get("profiles")
                if not isinstance(profiles, list) or len(profiles) > 32:
                    raise ValueError()
                for p in profiles:
                    validate_profile(p)
                after = body.get("after", 0)
                if not isinstance(after, int) or isinstance(after, bool) or not 0 <= after < 2**63 - 1:
                    raise ValueError()
                acks = body.get("acks", [])
                if not isinstance(acks, list) or len(acks) > 100:
                    raise ValueError()
                for mid in acks:
                    text(mid, 128)
                runtime.store.register(device_id, body.get("fcm_token"), system_notification=body.get('system_notification', False), now=now_ms())
                runtime.store.snapshot(device_id, profiles, now_ms())
                for mid in acks:
                    acknowledge(mid)
                device = runtime.store.device(device_id)
                items = [{"seq": item["seq"], "data": encrypt(item["envelope"], base64.b64decode(device["payload_key"]), device_id)}
                         for item in runtime.outbox.list_after(device_id, after, 50)]
                self.respond(200, {"items": items, "acked": acks, "profiles": runtime.store.status(device_id),
                    'token_error': device['token_error'], 'system_notification': device['system_notification']})
            except (ValueError, TypeError, KeyError):
                self.respond(400, {"error": "invalid_request_or_pairing_code"})
            except Exception:
                self.respond(503, {"error": "server_retry_later"})
    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--pair", action="store_true")
    args = parser.parse_args()
    address = ipaddress.ip_address(args.host)
    if not (address.is_loopback or address in TAILNET) or not 1 <= args.port <= 65535:
        parser.error("Only loopback or an actual Tailscale IPv4 may be used")
    PRIVATE.mkdir(exist_ok=True)
    configure_delivery_log(ROOT / 'logs')
    database = PRIVATE / "hub.sqlite3"
    if args.pair:
        code = HubStore(str(database)).create_pairing(now_ms())
        (PRIVATE / "pairing-code.txt").write_text(code, encoding="utf-8")
        print("配对码已写入 server/.secrets/pairing-code.txt，10分钟有效，不要上传或公开。")
        return
    sender = FcmSender(PRIVATE / "fcm-service-account.json", PROJECT)
    runtime = Runtime(database, sender)
    server = ThreadingHTTPServer((args.host, args.port), handler(runtime))
    server.daemon_threads = True
    threading.Thread(target=runtime.loop, args=(runtime.generate_once,), daemon=True).start()
    threading.Thread(target=runtime.loop, args=(runtime.deliver_once,), daemon=True).start()
    print(f"女仆管理器私有推送服务：http://{args.host}:{args.port}，仅限本机或 Tailscale。", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        runtime.stopping.set()
        server.server_close()
        sender.close()


if __name__ == "__main__":
    main()
