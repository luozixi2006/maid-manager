import base64
import unittest

from push_crypto import decrypt, encrypt


class PushCryptoTest(unittest.TestCase):
    key = bytes(range(1, 33))
    device = "device-01_AB"

    def test_unicode_and_nonce(self):
        body = {"content": "你好\n我们继续聊。🙂", "message_id": "test-only"}
        a = encrypt(body, self.key, self.device)
        b = encrypt(body, self.key, self.device)
        self.assertEqual(body, decrypt(a, self.key, self.device))
        self.assertNotEqual(a["ciphertext"], b["ciphertext"])
        self.assertNotIn("你好", str(a))

    def test_authentication(self):
        original = encrypt({"a": "b"}, self.key, self.device)
        forged = dict(original, device_id="another-device")
        with self.assertRaisesRegex(ValueError, "^invalid_encrypted_push$"):
            decrypt(forged, self.key, "another-device")
        with self.assertRaises(ValueError):
            decrypt(original, b"x" * 32, self.device)
        blob = bytearray(base64.b64decode(original["ciphertext"]))
        blob[-1] ^= 1
        with self.assertRaises(ValueError):
            decrypt(dict(original, ciphertext=base64.b64encode(blob).decode()), self.key, self.device)

    def test_shape_and_limits(self):
        for body in ({"n": 1}, {"n": None}, {"n": {}}, {"n": "字" * 1300}):
            with self.assertRaises(ValueError):
                encrypt(body, self.key, self.device)
        valid = encrypt({"a": "b"}, self.key, self.device)
        for outer in (dict(valid, extra="x"), dict(valid, ciphertext="!"),
                      dict(valid, schema_version="2"), dict(valid, ciphertext="A" * 4000)):
            with self.assertRaises(ValueError):
                decrypt(outer, self.key, self.device)


if __name__ == "__main__":
    unittest.main()
