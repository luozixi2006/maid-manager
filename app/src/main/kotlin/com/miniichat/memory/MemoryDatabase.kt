package com.miniichat.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Additive migration preserves legacy records; unassigned old rows stay out of every persona prompt. */
class MemoryDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext,"long_term_memory.db",null,2), java.io.Closeable {
    private val json=Json { ignoreUnknownKeys=true; encodeDefaults=true }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE memories(id TEXT PRIMARY KEY NOT NULL,content TEXT NOT NULL,category TEXT NOT NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,enabled INTEGER NOT NULL DEFAULT 1,persona_id TEXT NOT NULL DEFAULT '',detail TEXT NOT NULL DEFAULT '')")
        extras(db)
    }
    private fun extras(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memories_persona ON memories(persona_id,updated_at DESC)")
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_jobs(id TEXT PRIMARY KEY,persona TEXT NOT NULL,body TEXT NOT NULL,attempts INTEGER DEFAULT 0,error TEXT DEFAULT '',next_at INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_meta(k TEXT PRIMARY KEY,v TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_deleted(id TEXT PRIMARY KEY,persona TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        if(oldVersion<2) {
            db.execSQL("ALTER TABLE memories ADD COLUMN persona_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE memories ADD COLUMN detail TEXT NOT NULL DEFAULT ''")
            extras(db)
        }
    }
    fun queryAll():List<LongTermMemory> = readableDatabase.rawQuery("SELECT id,content,category,created_at,updated_at,enabled,persona_id,detail FROM memories ORDER BY updated_at DESC",null).use {c->buildList {
        while(c.moveToNext()) {
            val detail=c.getString(7)
            add(if(detail.isNotBlank()) json.decodeFromString<LongTermMemory>(detail) else LongTermMemory(c.getString(0),c.getString(1),c.getString(2),c.getLong(3),c.getLong(4),c.getInt(5)!=0,personaId=c.getString(6)))
        }
    } }
    fun upsert(memory:LongTermMemory) = transaction { upsert(it,memory) }
    fun upsertAll(memories:List<LongTermMemory>) = transaction {db->memories.forEach{upsert(db,it)} }
    private fun upsert(db:SQLiteDatabase,memory:LongTermMemory) {
        require(memory.content.isNotBlank() && memory.content.length<=4000) { "记忆内容请填写 1–4000 个字" }
        require(memory.importance in 0.0..1.0 && memory.confidence in 0.0..1.0)
        check(!db.rawQuery("SELECT 1 FROM memory_deleted WHERE id=?",arrayOf(memory.id)).use{it.moveToFirst()}) {"这条记忆已删除，请新建记忆"}
        val values=ContentValues().apply {
            put("id",memory.id);put("content",memory.content);put("category",memory.category);put("created_at",memory.createdAt);put("updated_at",memory.updatedAt)
            put("enabled",if(memory.enabled)1 else 0);put("persona_id",memory.personaId);put("detail",json.encodeToString(memory))
        }
        check(db.insertWithOnConflict("memories",null,values,SQLiteDatabase.CONFLICT_REPLACE)!=-1L) {"记忆未能保存，请检查手机剩余空间"}
    }
    fun delete(id:String) = transaction {db->
        val old=queryAll().firstOrNull{it.id==id}?:return@transaction
        db.insertWithOnConflict("memory_deleted",null,ContentValues().apply{put("id",id);put("persona",old.personaId)},SQLiteDatabase.CONFLICT_IGNORE)
        db.delete("memories","id=?",arrayOf(id))
    }
    fun setEnabled(id:String,enabled:Boolean) = transaction {db->queryAll().firstOrNull{it.id==id}?.let{upsert(db,it.copy(enabled=enabled,revision=it.revision+1))} }
    fun touch(ids:List<String>,at:Long) = transaction {db-> queryAll().filter{it.id in ids}.forEach{upsert(db,it.copy(lastUsedAt=at))} }
    /** CAS and persona boundary are enforced even if a model returns malicious or stale ids. */
    fun replace(persona:String,expected:List<LongTermMemory>,replacement:LongTermMemory):Boolean = transaction {db->
        require(persona.isNotBlank() && replacement.personaId==persona)
        val all=queryAll()
        if(expected.any{old->old.personaId!=persona || all.none{it.id==old.id && it.personaId==persona && it.revision==old.revision && it.enabled && it.status in setOf("active","conflict")}}) return@transaction false
        if(all.any{it.id==replacement.id && expected.none{old->old.id==it.id}}) return@transaction false
        expected.filter{it.id!=replacement.id}.forEach {upsert(db,it.copy(status="superseded",revision=it.revision+1,updatedAt=replacement.updatedAt))}
        upsert(db,replacement);true
    }
    fun meta(key:String):String=readableDatabase.rawQuery("SELECT v FROM memory_meta WHERE k=?",arrayOf(key)).use{if(it.moveToFirst())it.getString(0) else ""}
    fun meta(key:String,value:String) {writableDatabase.insertWithOnConflict("memory_meta",null,ContentValues().apply{put("k",key);put("v",value)},SQLiteDatabase.CONFLICT_REPLACE)}
    fun enqueue(id:String,persona:String,body:String) {
        require(persona.isNotBlank())
        if(meta("done:$id").isNotBlank())return
        writableDatabase.insertWithOnConflict("memory_jobs",null,ContentValues().apply{put("id",id);put("persona",persona);put("body",body)},SQLiteDatabase.CONFLICT_IGNORE)
    }
    data class Job(val id:String,val persona:String,val body:String,val attempts:Int)
    fun jobs(now:Long):List<Job> = readableDatabase.rawQuery("SELECT id,persona,body,attempts FROM memory_jobs WHERE next_at<=? AND attempts<6 ORDER BY rowid LIMIT 8",arrayOf(now.toString())).use{c->buildList{while(c.moveToNext())add(Job(c.getString(0),c.getString(1),c.getString(2),c.getInt(3)))}}
    fun done(id:String)=transaction {db->meta("done:$id",System.currentTimeMillis().toString());db.delete("memory_jobs","id=?",arrayOf(id))}
    fun failed(job:Job,reason:String) {writableDatabase.execSQL("UPDATE memory_jobs SET attempts=attempts+1,error=?,next_at=? WHERE id=?",arrayOf(reason.take(160),System.currentTimeMillis()+minOf(3600000L,60000L*(1L shl job.attempts)),job.id))}
    fun retryFailures(){writableDatabase.execSQL("UPDATE memory_jobs SET attempts=0,next_at=0,error=''")}
    fun queueStatus():String=readableDatabase.rawQuery("SELECT COUNT(*),SUM(CASE WHEN error!='' THEN 1 ELSE 0 END) FROM memory_jobs",null).use{it.moveToFirst();"待整理 ${it.getInt(0)} 条 · 需重试 ${it.getInt(1)} 条"}
    internal fun <T> atomic(block:()->T):T = transaction { block() }
    private fun <T> transaction(block:(SQLiteDatabase)->T):T {val db=writableDatabase;db.beginTransaction();return try{block(db).also{db.setTransactionSuccessful()}}finally{db.endTransaction()}}
}
