import threading
import unittest

from requests.exceptions import ConnectionError
from fcm_sender import FcmFailure, FcmSender, build_request
from push_crypto import encrypt


class Response:
    def __init__(self, status=200, body=None, headers=None):
        self.status_code = status
        self.body = body if body is not None else {"name": "projects/test-project/messages/test-only"}
        self.headers = headers or {}

    def json(self):
        return self.body


class Session:
    def __init__(self, response):
        self.response = response
        self.call = None

    def post(self, url, **kwargs):
        self.call = (url, kwargs)
        if isinstance(self.response, Exception):
            raise self.response
        return self.response


class FcmSenderTest(unittest.TestCase):
    def setUp(self):
        self.data = encrypt({"content": "test-only"}, bytes(range(32)), "test-device")

    def sender(self, response):
        sender = object.__new__(FcmSender)
        sender._url = "https://fcm.googleapis.com/v1/projects/test-project/messages:send"
        sender._session = Session(response)
        sender._lock = threading.Lock()
        return sender

    def test_data_only_package_bound_high_priority(self):
        request = build_request("test-only-token", self.data)
        message = request["message"]
        self.assertNotIn("notification", message)
        self.assertNotIn("content", message["data"])
        self.assertEqual("com.maidmanager.debug", message["android"]["restricted_package_name"])
        self.assertEqual("HIGH", message["android"]["priority"])
        self.assertFalse(request["validate_only"])

    def test_does_not_accept_plaintext(self):
        for data in ({"content": "secret"}, dict(self.data, type="character_message"), dict(self.data, ciphertext="a" * 4000)):
            with self.assertRaises(FcmFailure):
                build_request("test-only-token", data)

    def test_success_uses_bounded_timeout_and_no_redirect(self):
        sender = self.sender(Response())
        self.assertIn("messages/test-only", sender.send("test-only-token", self.data, validate_only=True))
        kwargs = sender._session.call[1]
        self.assertEqual(20, kwargs["timeout"])
        self.assertFalse(kwargs["allow_redirects"])
        self.assertTrue(kwargs["json"]["validate_only"])

    def test_failure_redacts_body_and_network_error(self):
        for status, code in ((401, "fcm_auth_failed"), (403, "fcm_permission_denied"), (429, "fcm_rate_limited"), (503, "fcm_unavailable")):
            with self.assertRaises(FcmFailure) as failure:
                self.sender(Response(status, {"error": {"message": "PRIVATE_CONTENT"}}, {"Retry-After": "90"})).send("test-only-token", self.data)
            self.assertEqual(code, str(failure.exception))
            self.assertEqual(90, failure.exception.retry_after)
        with self.assertRaises(FcmFailure) as failure:
            self.sender(ConnectionError("PRIVATE_TOKEN")).send("test-only-token", self.data)
        self.assertEqual("fcm_connection_failed", str(failure.exception))
        self.assertIsNone(failure.exception.__cause__)

    def test_unregistered_is_distinguished(self):
        response = Response(404, {"error": {"details": [{
            "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", "errorCode": "UNREGISTERED"
        }]}})
        with self.assertRaises(FcmFailure) as failure:
            self.sender(response).send("test-only-token", self.data)
        self.assertEqual("fcm_token_unregistered", failure.exception.code)

    def test_invalid_input_rejected_before_network(self):
        sender = self.sender(Response())
        with self.assertRaises(FcmFailure):
            sender.send("not a token", self.data)
        self.assertIsNone(sender._session.call)


if __name__ == "__main__":
    unittest.main()
