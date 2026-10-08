package com.miniichat.proactive.remote

import android.app.Application
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PushTransportTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun notificationLauncherExtrasAreConsumedOnlyOnceAndDoNotTrustConversationExtras() {
        val intent = Intent().putExtra("schema_version", "1").putExtra("type", "encrypted_character_message")
            .putExtra("device_id", "test-device").putExtra("ciphertext", "fake-not-authenticated")
            .putExtra("proactive_conversation_id", "attacker-chosen")
        val data = PushIngress.takeIntentData(intent)!!
        assertEquals(setOf("schema_version", "type", "device_id", "ciphertext"), data.keys)
        assertFalse(data.containsValue("attacker-chosen"))
        assertNull(PushIngress.takeIntentData(intent))
    }

    @Test fun existingInboxMigratesWithoutLosingPendingOrAcknowledgedRecords() {
        app.deleteDatabase("remote_push_inbox.db")
        val file = app.getDatabasePath("remote_push_inbox.db")
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE push_inbox(device_id TEXT NOT NULL,message_id TEXT NOT NULL,envelope TEXT NOT NULL,received_at INTEGER NOT NULL,finished INTEGER NOT NULL DEFAULT 0,acked INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(device_id,message_id))")
            val json = """{"message_id":"mid","conversation_id":"chat","persona_id":"persona","content":"existing","created_at":1,"model_id":"m","provider_id":"p"}"""
            db.execSQL("INSERT INTO push_inbox VALUES(?,?,?,?,0,0)", arrayOf("device", "mid", json, 1))
            db.execSQL("INSERT INTO push_inbox VALUES(?,?,?,?,1,1)", arrayOf("device", "old", json, 1))
            db.version = 1
        }
        PushInbox(app).use { inbox ->
            assertEquals("existing", inbox.pending("device").single().content)
            assertTrue(inbox.allowAlert("device", "mid"))
            assertTrue(inbox.finished("device", "old"))
            assertTrue(inbox.awaitingAck("device").isEmpty())
            inbox.finish("device", "mid", "notified")
            assertEquals("legacy", inbox.receipts("device").getJSONObject(0).getString("source"))
        }
    }

    @Test fun traceIsBoundedAndRejectsUnsafeDetails() {
        app.getSharedPreferences("push_diagnostics", 0).edit().clear().commit()
        PushTrace.record(app, "test", "private-message-id", "Bearer: private/token")
        repeat(130) { PushTrace.record(app, "fcm_callback", "mid-$it", "priority=1") }
        val raw = app.getSharedPreferences("push_diagnostics", 0).getString("events", "[]")!!
        val rows = org.json.JSONArray(raw)
        assertEquals(120, rows.length())
        assertFalse(raw.contains("private/token"))
        assertFalse(raw.contains("private-message-id"))
        assertFalse(raw.contains("mid-129"))
    }

    @Test fun systemNotificationTagIsStableAndMessageScoped() {
        assertEquals("maid-remote:" + PushTrace.id("message-1"), PushTrace.notificationTag("message-1"))
        assertNotEquals(PushTrace.notificationTag("message-1"), PushTrace.notificationTag("message-2"))
        assertEquals(76, PushTrace.notificationTag("message-1").length)
    }

    @Test @Config(sdk = [33]) fun android13PermissionDenialIsExplicitlyReported() {
        org.robolectric.Shadows.shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertEquals("通知权限未开启", com.miniichat.proactive.ProactiveNotifications.blockedReason(app))
    }
}
