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
- `server/.secrets` contains all durable SQLite state and credentials, excluded by Git. The server sends recent persona context to the user-configured AI endpoint, not to Firebase. FCM carries AES-256-GCM encrypted message data plus a **fixed generic notification** for upgraded clients; no persona name, avatar or chat plaintext is included. Firebase still receives routing metadata and a device token. The client has its own encryption key in Android Keystore; the server service-account key never leaves this computer.
- Durable outbox, transport-independent message IDs, authenticated incremental recovery and device ACKs distinguish FCM acceptance from local persistence. Duplicate deliveries do not append duplicate chat records. Deleted chats/personas are never recreated by push.
- Current synced DND settings and frequency limits are respected during generation. There is at least a 15-minute per-device contact gap. A model may choose silence. A snapshot older than 24 hours stops generation until resynced. Pausing suppresses app-rendered delivery immediately; computer-side profile/key removal requires reaching the service and is retried. Already in-flight system notifications cannot be recalled from FCM. An offline server snapshot cannot know newly changed local preferences.
- HIGH importance, vibration and private lock-screen previews are set when the notification channel is first created. Existing user choices are not overridden. Open system notification settings to allow vibration, banner and lock-screen visibility. Force-stop, missing Google connectivity and OEM restrictions can still prevent prompt delivery. No promise of bypassing Android restrictions.
- Standard Android message notifications allow watch bridging (`localOnly=false`); OPPO Health still needs notification access and this app enabled. No separate watch chat is created.

## Development

Android client config is `app/google-services.json` (ignored). CI supplies the `FIREBASE_ANDROID_CONFIG` secret and refuses push release builds without it. This is the Firebase Android client config, **not** the server private key. Open-source builds without a Firebase project retain local proactive messaging; computer-push activation is unavailable.

Run `server/.venv/Scripts/python.exe -m unittest discover -s server -p "test_*.py"` from the repository root. Tests use fake credentials, temporary databases and loopback HTTP only. Android tests under `proactive/remote` cover cipher compatibility, envelope validation, deduplication, durable inbox, consent and conversation-preserving delivery.

## Delivery changes in 3.0.22

- Client announces `system_notification=true` after upgrade. Older clients keep the compatible encrypted data-only path until then. Open the upgraded app once with Tailscale connected to register this capability.
- Upgraded background delivery uses notification+encrypted data: the Firebase SDK/eligible Google Play services delegation can display a generic alert without waiting for our chat code, Tailscale sync, a model call or image download. Foreground delivery decrypts locally and retains persona name/avatar. Launcher extras are decrypted and validated before any notification-click navigation; both cold and warm intents use this path.
- System notifications are collapsible by FCM when offline. The durable outbox, not FCM, is the authoritative complete history. App-resume recovery is silent and incremental, so reconnecting does not play a burst of old alerts. Clicking a system notification also saves silently, then opens the original conversation. Duplicate message IDs do not add duplicate messages.
- FCM acceptance and the returned receipt name are persisted separately from client ACKs. Successfully accepted system alerts are not resent every five minutes while waiting for the user to open the app. Transient errors retry with backoff and Retry-After. UNREGISTERED/token-specific INVALID_ARGUMENT retire only the same failing token, never a newer token. Clients refresh tokens periodically and replace a token rejected by the server.
- Local logs: `server/logs/delivery.jsonl` (2 MB x 3 maximum) records generated_saved/fcm_request/fcm_accepted/fcm_failed/device_ack. `server/.venv/Scripts/python.exe server/diagnose_push.py` exports only metadata. The app has **Settings → 消息通知 → 导出推送诊断**; it reports permission/channel/delegation and fcm_callback/inbox_saved/history_committed/catchup_saved/ack_sent with hashed message IDs. No API keys, tokens, plaintext or analytics.
- `server/.venv/Scripts/python.exe server/diagnose_push.py --validate-system-notification` explicitly tests real FCM credentials/payload using `validate_only=true`. A 200 is **not** evidence of a delivered phone notification.

## Physical-device acceptance (not replaced by unit tests)

Use a signed upgrade, never uninstall/clear storage. Record exact send/receive times and message hash. Do not confuse a generic system alert with an app callback: background notification messages may have no callback until tapped.

1. Foreground: receive, local persona notification, original conversation, exactly one message.
2. Home + locked screen for 15+ minutes/Doze: actual vibration and lock-screen alert before opening. Check notification permission, channel, Google connectivity; compare server FCM receipt and phone system notification records.
3. Normal OS process reclamation (`am kill` after backgrounding, when allowed): check package is **not stopped**, send, verify system alert/cold click. Do not substitute `am force-stop` for this test.
4. Swipe recent task away: verify on the actual OEM phone, as behavior can differ. A swipe is not conceptually force-stop, but some vendors impose extra restrictions.
5. Force-stop from system settings: **no delivery guarantee or bypass**. Reopen explicitly, re-register/recover history silently, then test a new push.
6. Offline: reconnect, verify a wake-up alert where FCM permits and recovery of *all* message IDs without duplicates or a notification burst. Restart the server and verify durable outbox survives.
7. OPPO Watch: phone must first display the notification. Enable OHealth notification access and this app's forwarding option; `localOnly=false` enables OS bridging but does not prove it is configured or supported on the connected watch. Verify physically; no separate watch history is created.

References: [Android FCM receive behavior](https://firebase.google.com/docs/cloud-messaging/android/receive-messages), [priority/delegation](https://firebase.google.com/docs/cloud-messaging/android-message-priority), [token lifecycle](https://firebase.google.com/docs/cloud-messaging/manage-tokens), [offline collapsing](https://firebase.google.com/docs/cloud-messaging/customize-messages/collapsible-message-types).
