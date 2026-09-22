package com.miniichat.memory

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The legacy on-disk format must upgrade additively and degrade to unassigned, prompt-invisible rows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MemoryDatabaseSchemaTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun columns(db: SQLiteDatabase): List<String> = buildList {
        db.rawQuery("PRAGMA table_info(memories)", null).use { c -> while (c.moveToNext()) add(c.getString(1)) }
    }

    /** Writes exactly the shipped v1 file: six columns, no persona_id/detail, user_version=1. */
    private fun createLegacyV1Database(): String {
        val path = app.getDatabasePath("long_term_memory.db")
        path.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(path, null)
        db.execSQL("CREATE TABLE memories(id TEXT PRIMARY KEY NOT NULL,content TEXT NOT NULL,category TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,enabled INTEGER NOT NULL DEFAULT 1)")
        db.execSQL("INSERT INTO memories(id,content,category,created_at,updated_at,enabled) VALUES('legacy-1','早上喜欢散步','习惯',1000,2000,1)")
        db.execSQL("INSERT INTO memories(id,content,category,created_at,updated_at,enabled) VALUES('legacy-2','旧记录','其他',1500,1500,0)")
        db.version = 1
        db.close()
        return path.absolutePath
    }

    @Test fun legacySixColumnSchemaUpgradesWithoutLosingRecords() {
        val path = createLegacyV1Database()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            assertEquals(1, db.version)
            assertEquals(listOf("id", "content", "category", "created_at", "updated_at", "enabled"), columns(db))
        }
        MemoryDatabase(app).use { db ->
            val all = db.queryAll()
            assertEquals(2, all.size)
            assertTrue(all.all { it.personaId == "" })
            val kept = all.single { it.id == "legacy-1" }
            assertEquals("早上喜欢散步", kept.content)
            assertEquals("习惯", kept.category)
            assertEquals(1000L, kept.createdAt)
            assertEquals(2000L, kept.updatedAt)
            assertTrue(kept.enabled)
            assertFalse(all.single { it.id == "legacy-2" }.enabled)
        }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            assertEquals(2, db.version)
            assertEquals(listOf("id", "content", "category", "created_at", "updated_at", "enabled", "persona_id", "detail"), columns(db))
        }
        MemoryDatabase(app).use { db -> assertEquals(2, db.queryAll().size) }
    }

    @Test fun legacyRowsNeverEnterPersonaRetrieval() {
        createLegacyV1Database()
        MemoryDatabase(app).use { db ->
            val legacy = db.queryAll().first { it.id == "legacy-1" }
            db.upsert(LongTermMemory(id = "own-1", content = "早上喜欢散步", category = "习惯", personaId = "p1"))
            val picked = MemoryRetrieval.select("p1", "早上喜欢散步", db.queryAll(), 2_000L)
            assertEquals(listOf("own-1"), picked.map { it.id })
            assertTrue(MemoryRetrieval.select("p1", "早上喜欢散步", listOf(legacy), 2_000L).isEmpty())
        }
    }

    @Test fun manualUpsertSurvivesReopenWithAllMetadata() {
        val original = LongTermMemory(
            id = "m-full", content = "喜欢🐱", category = "偏好", createdAt = 1111, updatedAt = 2222, enabled = false,
            personaId = "p1", importance = 0.9, confidence = 0.75, lastConfirmedAt = 1234, lastUsedAt = 9,
            sources = listOf(MemorySource("s1", "user", 1000, "用户说喜欢🐱")), stable = true,
            embedding = listOf(0.5f, -0.25f), embeddingModel = "emb-1", status = "active",
            supersedes = listOf("old-1"), revision = 7, origin = "manual")
        MemoryDatabase(app).use { it.upsert(original) }
        MemoryDatabase(app).use { db -> assertEquals(listOf(original), db.queryAll()) }
    }

    @Test fun deleteTombstonePersistsAndBlocksRevival() {
        val memory = LongTermMemory(id = "m-del", content = "删除我", category = "其他", personaId = "p1")
        MemoryDatabase(app).use { db ->
            db.upsert(memory)
            db.delete("m-del")
            assertTrue(db.queryAll().isEmpty())
        }
        MemoryDatabase(app).use { db ->
            assertTrue(db.queryAll().isEmpty())
            assertThrows(IllegalStateException::class.java) { db.upsert(memory.copy(content = "复活尝试")) }
            assertTrue(db.queryAll().isEmpty())
        }
    }
}
