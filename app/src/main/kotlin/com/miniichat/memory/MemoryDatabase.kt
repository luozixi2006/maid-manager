package com.miniichat.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class MemoryDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    "long_term_memory.db",
    null,
    1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE memories (
                id TEXT PRIMARY KEY NOT NULL,
                content TEXT NOT NULL,
                category TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_memories_updated_at ON memories(updated_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun queryAll(): List<LongTermMemory> = readableDatabase.query(
        "memories",
        arrayOf("id", "content", "category", "created_at", "updated_at", "enabled"),
        null,
        null,
        null,
        null,
        "updated_at DESC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    LongTermMemory(
                        id = cursor.getString(0),
                        content = cursor.getString(1),
                        category = cursor.getString(2),
                        createdAt = cursor.getLong(3),
                        updatedAt = cursor.getLong(4),
                        enabled = cursor.getInt(5) != 0
                    )
                )
            }
        }
    }

    fun upsert(memory: LongTermMemory) {
        upsert(writableDatabase, memory)
    }

    fun upsertAll(memories: List<LongTermMemory>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            memories.forEach { upsert(db, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun upsert(db: SQLiteDatabase, memory: LongTermMemory) {
        val values = ContentValues().apply {
            put("id", memory.id)
            put("content", memory.content)
            put("category", memory.category)
            put("created_at", memory.createdAt)
            put("updated_at", memory.updatedAt)
            put("enabled", if (memory.enabled) 1 else 0)
        }
        db.insertWithOnConflict(
            "memories",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun delete(id: String) {
        writableDatabase.delete("memories", "id = ?", arrayOf(id))
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val values = ContentValues().apply { put("enabled", if (enabled) 1 else 0) }
        writableDatabase.update("memories", values, "id = ?", arrayOf(id))
    }
}
