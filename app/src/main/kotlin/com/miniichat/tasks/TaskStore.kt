package com.miniichat.tasks

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString

/** One process-wide DB and lock: workers, notification actions and overlay never overwrite each other. */
class TaskStore private constructor(context: Context) : SQLiteOpenHelper(context, "phone_tasks.db", null, 1) {
    val revision = MutableStateFlow(0L)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    @Synchronized fun all(): List<PhoneTask> = readableDatabase.rawQuery("SELECT payload FROM tasks", null).use { c ->
        buildList { while (c.moveToNext()) add(taskJson.decodeFromString<PhoneTask>(c.getString(0))) }
    }.sortedByDescending { it.createdAt }
    @Synchronized fun get(id: String): PhoneTask? = readableDatabase.rawQuery("SELECT payload FROM tasks WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) taskJson.decodeFromString<PhoneTask>(it.getString(0)) else null
    }
    @Synchronized fun put(task: PhoneTask) {
        writableDatabase.insertWithOnConflict("tasks", null, ContentValues().apply {
            put("id", task.id); put("payload", taskJson.encodeToString(task))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "任务状态保存失败，已停止操作" } }
        revision.value++
    }
    @Synchronized fun change(id: String, transform: (PhoneTask) -> PhoneTask): PhoneTask? = get(id)?.let {
        transform(it).copy(updatedAt = System.currentTimeMillis()).also(::put)
    }
    @Synchronized fun rules(): List<TriggerRule> = readableDatabase.rawQuery("SELECT payload FROM events", null).use { c ->
        buildList { while (c.moveToNext()) add(taskJson.decodeFromString<TriggerRule>(c.getString(0))) }
    }
    @Synchronized fun rule(rule: TriggerRule) {
        if (rules().firstOrNull { it.id == rule.id } == rule) return
        writableDatabase.insertWithOnConflict("events", null, ContentValues().apply {
            put("id", rule.id); put("payload", taskJson.encodeToString(rule))
        }, SQLiteDatabase.CONFLICT_REPLACE)
        revision.value++
    }
    @Synchronized fun deleteRule(id: String) { writableDatabase.delete("events", "id=?", arrayOf(id)); revision.value++ }
    companion object {
        @Volatile private var instance: TaskStore? = null
        fun of(context: Context): TaskStore = instance ?: synchronized(this) {
            instance ?: TaskStore(context.applicationContext).also { instance = it }
        }
    }
}
