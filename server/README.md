# Private companion push

The existing Android streaming chat stays on the phone. Only enabled personas' background proactive checks can be delegated to this private Windows service. The user must opt in and pair the phone.

## Run on Windows

1. Create a Python 3.11 virtual environment in `server/.venv` and install `requirements.txt` there. No system Python modifications are needed.
2. Save the dedicated FCM service account JSON as `server/.secrets/fcm-service-account.json`. Restrict this directory to your Windows account, Administrators and SYSTEM. The project used by this installation is `maid-manager-b4c9e`; the sender needs only Firebase Cloud Messaging API Admin. Never commit this file or copy it into an APK.
3. Connect Tailscale; run `start-server.bat`. It binds only the actual Tailscale IPv4 on TCP 8787, never `0.0.0.0`. Allow this port only from your tailnet in Windows Firewall. Do not use public forwarding/Funnel.
4. Run `pair-phone.bat`. In phone Settings → message notifications, enter the computer's Tailscale URL and the one-use 8-digit code. Codes expire after 10 minutes and lock after five failed attempts. Read the disclosure and connect. Google Play services and connectivity to FCM are required.

The computer must be awake and the service running. This script does not alter power settings or silently install a system service. The phone's Tailscale connection is needed for pairing, context updates and missed-message recovery. FCM notification delivery uses Google's connection and is independently subject to phone network, system notification and battery settings.

## Privacy and delivery

- No analytics, crash upload, public history or cloud database. Only opted-in personas, recent chat and the selected model configuration/key are uploaded to this private computer. Body/screen/live sensor context and long-term memories are NOT included in this path.
- `server/.secrets` contains all durable SQLite state and credentials, excluded by Git. The server sends recent persona context to the user-configured AI endpoint, not to Firebase. FCM carries AES-256-GCM encrypted data only. Firebase still receives routing metadata and a device token. The client has its own encryption key in Android Keystore; the server service-account key never leaves this computer.
- Durable outbox, transport-independent message IDs, authenticated incremental recovery and device ACKs distinguish FCM acceptance from local persistence. Duplicate deliveries do not append duplicate chat records. Deleted chats/personas are never recreated by push.
- Current DND settings and frequency limits are respected. There is at least a 15-minute per-device contact gap. A model may choose silence. A snapshot older than 24 hours stops generation until resynced. Pausing locally suppresses delivery immediately; computer-side profile/key removal requires reaching the service and is retried. Until then a remote server cannot know that an offline phone changed its preferences.
- HIGH importance, vibration and private lock-screen previews are set when the notification channel is first created. Existing user choices are not overridden. Open system notification settings to allow vibration, banner and lock-screen visibility. Force-stop, missing Google connectivity and OEM restrictions can still prevent prompt delivery. No promise of bypassing Android restrictions.
- Standard Android message notifications allow watch bridging (`localOnly=false`); OPPO Health still needs notification access and this app enabled. No separate watch chat is created.

## Development

Android client config is `app/google-services.json` (ignored). CI supplies the `FIREBASE_ANDROID_CONFIG` secret and refuses push release builds without it. This is the Firebase Android client config, **not** the server private key. Open-source builds without a Firebase project retain local proactive messaging; computer-push activation is unavailable.

Run `server/.venv/Scripts/python.exe -m unittest discover -s server -p "test_*.py"` from the repository root. Tests use fake credentials, temporary databases and loopback HTTP only. Android tests under `proactive/remote` cover cipher compatibility, envelope validation, deduplication, durable inbox, consent and conversation-preserving delivery.
