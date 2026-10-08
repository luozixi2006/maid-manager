package com.miniichat.proactive.remote

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.Closeable

private const val DB_NAME = "remote_push_inbox.db"
private const val DB_VERSION = 2
private const val TABLE_PUSH_INBOX = "push_inbox"
private const val COL_DEVICE_ID = "device_id"
private const val COL_MESSAGE_ID = "message_id"
private const val COL_ENVELOPE = "envelope"
private const val COL_RECEIVED_AT = "received_at"
private const val COL_FINISHED = "finished"

private const val KEY_MESSAGE_ID = "message_id"
private const val KEY_CONVERSATION_ID = "conversation_id"
private const val KEY_PERSONA_ID = "persona_id"
private const val KEY_CONTENT = "content"
private const val KEY_CREATED_AT = "created_at"
private const val KEY_MODEL_ID = "model_id"
private const val KEY_PROVIDER_ID = "provider_id"

private const val ERR_INBOX_FULL = "push_inbox_full"
private const val ERR_MESSAGE_ID_CONFLICT = "message_id_conflict"
private const val ERR_INVALID_INBOX = "invalid_push_inbox"

private const val MAX_UNFINISHED = 1000
private const val RETENTION_MS = 28L * 24L * 60L * 60L * 1000L

private val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,128}")

/**
 * Durable, plaintext inbox for validated remote push envelopes.
 *
 * Synchronous on purpose: callers invoke these methods from an IO dispatcher.
 * Finished rows are retained for duplicate suppression and offline recovery.
 */
class PushInbox(context: Context) : Closeable {

    private val helper = PushInboxDbHelper(context.applicationContext)

    /**
     * Inserts a validated envelope once.
     *
     * @return true when newly stored, false when an identical envelope is already stored.
     * @throws IllegalArgumentException when the same message id exists with different content.
     * @throws IllegalStateException when the device already holds [MAX_UNFINISHED] unfinished rows.
     */
    fun offer(deviceId: String, envelope: PushEnvelope, receivedAt: Long, source: String = "legacy", allowAlert: Boolean = true): Boolean {
        validateDeviceId(deviceId)
        require(source in setOf("legacy", "fcm", "notification_tap", "sync"))
        if (receivedAt < 0L) throw IllegalArgumentException("invalid_received_at")

        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val existing = readEnvelope(db, deviceId, envelope.messageId)
            if (existing != null) {
                if (existing == envelope) {
                    db.setTransactionSuccessful()
                    return false
                }
                throw IllegalArgumentException(ERR_MESSAGE_ID_CONFLICT)
            }

            if (countUnfinished(db, deviceId) >= MAX_UNFINISHED) {
                throw IllegalStateException(ERR_INBOX_FULL)
            }

            pruneFinished(db, System.currentTimeMillis())

            val values = ContentValues().apply {
                put(COL_DEVICE_ID, deviceId)
                put(COL_MESSAGE_ID, envelope.messageId)
                put(COL_ENVELOPE, serialize(envelope))
                put(COL_RECEIVED_AT, receivedAt)
                put(COL_FINISHED, 0)
                put("source", source)
                put("allow_alert", if (allowAlert) 1 else 0)
            }
            db.insertOrThrow(TABLE_PUSH_INBOX, null, values)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /** Unfinished envelopes for [deviceId], oldest received first, capped to 1..100 rows. */
    fun pending(deviceId: String, limit: Int = 50): List<PushEnvelope> {
        validateDeviceId(deviceId)
        require(limit in 1..100) { "invalid_push_inbox_limit" }
        val effectiveLimit = limit

        val result = ArrayList<PushEnvelope>()
        helper.readableDatabase.query(
            TABLE_PUSH_INBOX,
            arrayOf(COL_ENVELOPE),
            "$COL_DEVICE_ID = ? AND $COL_FINISHED = 0",
            arrayOf(deviceId),
            null,
            null,
            "$COL_RECEIVED_AT ASC, rowid ASC",
            effectiveLimit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(deserialize(cursor.getString(0)))
            }
        }
        return result
    }

    /** Marks an envelope finished; idempotent, never deletes. Returns true if the row exists. */
    fun finish(deviceId: String, messageId: String, outcome: String = "saved_silent"): Boolean {
        validateDeviceId(deviceId)
        require(outcome in setOf("notified", "saved_silent", "blocked", "discarded"))
        val values = ContentValues().apply { put(COL_FINISHED, 1); put("outcome", outcome) }
        val updated = helper.writableDatabase.update(
            TABLE_PUSH_INBOX,
            values,
            "$COL_DEVICE_ID = ? AND $COL_MESSAGE_ID = ?",
            arrayOf(deviceId, messageId)
        )
        return updated > 0
    }

    /** Whether the stored envelope for [messageId] has been finished. */
    fun finished(deviceId: String, messageId: String): Boolean {
        validateDeviceId(deviceId)
        helper.readableDatabase.query(
            TABLE_PUSH_INBOX,
            arrayOf(COL_FINISHED),
            "$COL_DEVICE_ID = ? AND $COL_MESSAGE_ID = ?",
            arrayOf(deviceId, messageId),
            null,
            null,
            null
        ).use { cursor ->
            return cursor.moveToFirst() && cursor.getInt(0) != 0
        }
    }

    override fun close() {
        helper.close()
    }

    fun awaitingAck(deviceId: String): List<String> {
        validateDeviceId(deviceId)
        return helper.readableDatabase.rawQuery(
            "SELECT message_id FROM push_inbox WHERE device_id=? AND finished=1 AND acked=0 ORDER BY rowid LIMIT 100",
            arrayOf(deviceId)
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    }

    fun allowAlert(deviceId: String, messageId: String): Boolean = helper.readableDatabase.rawQuery(
        "SELECT allow_alert FROM push_inbox WHERE device_id=? AND message_id=?", arrayOf(deviceId, messageId)
    ).use { it.moveToFirst() && it.getInt(0) == 1 }

    fun receipts(deviceId: String): org.json.JSONArray {
        validateDeviceId(deviceId)
        return helper.readableDatabase.rawQuery(
            "SELECT message_id,source,outcome FROM push_inbox WHERE device_id=? AND finished=1 AND acked=0 ORDER BY rowid LIMIT 100",
            arrayOf(deviceId)
        ).use { cursor -> org.json.JSONArray().apply {
            while (cursor.moveToNext()) put(JSONObject().put("message_id", cursor.getString(0))
                .put("source", cursor.getString(1)).put("outcome", cursor.getString(2)))
        } }
    }

    fun acknowledged(deviceId: String, messageId: String) {
        validateDeviceId(deviceId)
        helper.writableDatabase.execSQL(
            "UPDATE push_inbox SET acked=1 WHERE device_id=? AND message_id=? AND finished=1", arrayOf(deviceId, messageId)
        )
    }

    private fun validateDeviceId(deviceId: String) {
        if (!DEVICE_ID_PATTERN.matches(deviceId)) {
            throw IllegalArgumentException("invalid_device_id")
        }
    }

    private fun countUnfinished(db: SQLiteDatabase, deviceId: String): Int {
        db.rawQuery(
            "SELECT COUNT(*) FROM $TABLE_PUSH_INBOX WHERE $COL_DEVICE_ID = ? AND $COL_FINISHED = 0",
            arrayOf(deviceId)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    /** Removes only finished rows older than the retention window; unfinished rows are kept. */
    private fun pruneFinished(db: SQLiteDatabase, now: Long) {
        val cutoff = if (now < RETENTION_MS) 0L else now - RETENTION_MS
        db.delete(
            TABLE_PUSH_INBOX,
            "$COL_FINISHED = 1 AND $COL_RECEIVED_AT < ?",
            arrayOf(cutoff.toString())
        )
    }

    private fun readEnvelope(db: SQLiteDatabase, deviceId: String, messageId: String): PushEnvelope? {
        db.query(
            TABLE_PUSH_INBOX,
            arrayOf(COL_ENVELOPE),
            "$COL_DEVICE_ID = ? AND $COL_MESSAGE_ID = ?",
            arrayOf(deviceId, messageId),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return deserialize(cursor.getString(0))
        }
    }

    private class PushInboxDbHelper(context: Context) :
        SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE_PUSH_INBOX (" +
                    "$COL_DEVICE_ID TEXT NOT NULL, " +
                    "$COL_MESSAGE_ID TEXT NOT NULL, " +
                    "$COL_ENVELOPE TEXT NOT NULL, " +
                    "$COL_RECEIVED_AT INTEGER NOT NULL, " +
                    "$COL_FINISHED INTEGER NOT NULL DEFAULT 0, " +
                    "acked INTEGER NOT NULL DEFAULT 0, " +
                    "source TEXT NOT NULL DEFAULT 'legacy', " +
                    "allow_alert INTEGER NOT NULL DEFAULT 1, " +
                    "outcome TEXT NOT NULL DEFAULT 'saved_silent', " +
                    "PRIMARY KEY ($COL_DEVICE_ID, $COL_MESSAGE_ID))"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE push_inbox ADD COLUMN source TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("ALTER TABLE push_inbox ADD COLUMN allow_alert INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE push_inbox ADD COLUMN outcome TEXT NOT NULL DEFAULT 'saved_silent'")
            }
        }
    }

    private companion object {
        private fun serialize(envelope: PushEnvelope): String {
            val obj = JSONObject()
            obj.put(KEY_MESSAGE_ID, envelope.messageId)
            obj.put(KEY_CONVERSATION_ID, envelope.conversationId)
            obj.put(KEY_PERSONA_ID, envelope.personaId)
            obj.put(KEY_CONTENT, envelope.content)
            obj.put(KEY_CREATED_AT, envelope.createdAt)
            obj.put(KEY_MODEL_ID, envelope.modelId)
            obj.put(KEY_PROVIDER_ID, envelope.providerId)
            return obj.toString()
        }

        /**
         * Rebuilds an envelope from stored JSON. Corrupt rows fail closed with a stable
         * code that never carries stored content or a cause.
         */
        private fun deserialize(json: String?): PushEnvelope {
            if (json == null) throw IllegalStateException(ERR_INVALID_INBOX)
            return try {
                val obj = JSONObject(json)
                PushEnvelope(
                    messageId = obj.getString(KEY_MESSAGE_ID),
                    conversationId = obj.getString(KEY_CONVERSATION_ID),
                    personaId = obj.getString(KEY_PERSONA_ID),
                    content = obj.getString(KEY_CONTENT),
                    createdAt = obj.getLong(KEY_CREATED_AT),
                    modelId = obj.getString(KEY_MODEL_ID),
                    providerId = obj.getString(KEY_PROVIDER_ID)
                )
            } catch (e: Exception) {
                throw IllegalStateException(ERR_INVALID_INBOX)
            }
        }
    }
}
