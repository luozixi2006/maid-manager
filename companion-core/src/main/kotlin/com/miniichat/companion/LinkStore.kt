package com.miniichat.companion

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** All cursors, pending writes and request ids survive process death. */
class LinkStore(context: Context): SQLiteOpenHelper(context,"companion.sqlite",null,1), java.io.Closeable {
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE items(kind TEXT,id TEXT,body TEXT,revision INTEGER,deleted INTEGER DEFAULT 0,PRIMARY KEY(kind,id))")
        db.execSQL("CREATE TABLE outbox(op TEXT PRIMARY KEY,kind TEXT,id TEXT,body TEXT,revision INTEGER,deleted INTEGER DEFAULT 0,UNIQUE(kind,id))")
        db.execSQL("CREATE TABLE metadata(k TEXT PRIMARY KEY,v TEXT)")
        db.execSQL("CREATE TABLE pending_thought(id TEXT PRIMARY KEY,body TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase,oldVersion:Int,newVersion:Int) = Unit
    fun meta(key:String):String = readableDatabase.rawQuery("SELECT v FROM metadata WHERE k=?",arrayOf(key)).use { if(it.moveToFirst()) it.getString(0) else "" }
    fun meta(key:String,value:String) { writableDatabase.insertWithOnConflict("metadata",null,ContentValues().apply {put("k",key);put("v",value)},SQLiteDatabase.CONFLICT_REPLACE) }
    fun items(kind:String):List<JSONObject> = readableDatabase.rawQuery("SELECT id,body,revision,deleted FROM items WHERE kind=? ORDER BY rowid",arrayOf(kind)).use { c-> buildList {
        while(c.moveToNext()) add(JSONObject().put("id",c.getString(0)).put("body",JSONObject(c.getString(1))).put("revision",c.getInt(2)).put("deleted",c.getInt(3)!=0))
    } }
    fun hasPending(kind:String,id:String):Boolean = readableDatabase.rawQuery("SELECT 1 FROM outbox WHERE kind=? AND id=?",arrayOf(kind,id)).use{it.moveToFirst()}
    @Synchronized fun enqueue(kind:String,id:String,body:JSONObject,deleted:Boolean=false) {
        val known=items(kind).firstOrNull {it.getString("id")==id}
        if(known?.optBoolean("deleted")==true) return // tombstones are permanent
        if(known?.getJSONObject("body")?.toString()==body.toString() && !deleted) return
        val previous=readableDatabase.rawQuery("SELECT body,deleted FROM outbox WHERE kind=? AND id=?",arrayOf(kind,id)).use {
            it.moveToFirst() && it.getString(0)==body.toString() && (it.getInt(1)!=0)==deleted
        }
        if(previous) return
        writableDatabase.insertWithOnConflict("outbox",null,ContentValues().apply {
            put("op",UUID.randomUUID().toString());put("kind",kind);put("id",id);put("body",body.toString());put("revision",known?.optInt("revision")?:0);put("deleted",if(deleted)1 else 0)
        },SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun outgoing():JSONArray = readableDatabase.rawQuery("SELECT op,kind,id,body,revision,deleted FROM outbox ORDER BY rowid LIMIT 20",null).use { c-> JSONArray().apply {
        while(c.moveToNext()) put(JSONObject().put("op_id",c.getString(0)).put("kind",c.getString(1)).put("id",c.getString(2)).put("body",JSONObject(c.getString(3))).put("revision",c.getInt(4)).put("deleted",c.getInt(5)!=0))
    } }
    @Synchronized fun apply(result:JSONObject):List<JSONObject> {
        val db=writableDatabase; val received=mutableListOf<JSONObject>();db.beginTransaction()
        try {
            val acked=result.getJSONArray("acked")
            val sent=runCatching{JSONArray(meta("inflight"))}.getOrDefault(JSONArray())
            val acknowledged=(0 until sent.length()).map{sent.getJSONObject(it)}.filter {op->(0 until acked.length()).any{acked.getString(it)==op.getString("op_id")}}
            for(i in 0 until acked.length()) db.delete("outbox","op=?",arrayOf(acked.getString(i)))
            // Acknowledged changes may appear on a later page. Use the server's explicit revision,
            // not an assumption that the matching change is already in this page.
            val confirmed=result.optJSONObject("acked_revisions")
            acknowledged.forEach{op->confirmed?.optInt(op.getString("op_id"),-1)?.takeIf{it>=0}?.let{revision->
                db.execSQL("UPDATE outbox SET revision=? WHERE kind=? AND id=? AND revision=?",arrayOf(revision,op.getString("kind"),op.getString("id"),op.optInt("revision")))
            }}
            val changes=result.getJSONArray("changes")
            for(i in 0 until changes.length()) {
                val change=changes.getJSONObject(i); val kind=change.getString("kind");val id=change.getString("id")
                val prior=items(kind).firstOrNull {it.getString("id")==id}
                // A local edit may replace an outbox item while its older value is in flight.
                // Rebase only our acknowledged write, never an unrelated remote edit.
                acknowledged.firstOrNull{it.getString("kind")==kind && it.getString("id")==id && it.optInt("revision")+1==change.getInt("revision")}?.let {sentOp->
                    db.execSQL("UPDATE outbox SET revision=? WHERE kind=? AND id=? AND revision=?",arrayOf(change.getInt("revision"),kind,id,sentOp.optInt("revision")))
                }
                db.insertWithOnConflict("items",null,ContentValues().apply {
                    put("kind",kind);put("id",id);put("body",change.getJSONObject("body").toString());put("revision",change.getInt("revision"));put("deleted",if(change.getBoolean("deleted"))1 else 0)
                },SQLiteDatabase.CONFLICT_REPLACE)
                if(prior==null) received.add(change)
            }
            meta("cursor",result.getLong("cursor").toString());meta("inflight", "[]");db.setTransactionSuccessful()
        } finally {db.endTransaction()}
        return received
    }
    fun pending(id:String,body:JSONObject) { writableDatabase.insertWithOnConflict("pending_thought",null,ContentValues().apply {put("id",id);put("body",body.toString())},SQLiteDatabase.CONFLICT_IGNORE) }
    fun thoughts():List<Pair<String,JSONObject>> = readableDatabase.rawQuery("SELECT id,body FROM pending_thought ORDER BY CASE WHEN instr(body,'message_id')>0 THEN 0 ELSE 1 END,rowid LIMIT 5",null).use {c->buildList {while(c.moveToNext()) add(c.getString(0) to JSONObject(c.getString(1)))}}
    fun done(id:String) {writableDatabase.delete("pending_thought","id=?",arrayOf(id))}
    fun clear() { writableDatabase.execSQL("DELETE FROM items");writableDatabase.execSQL("DELETE FROM outbox");writableDatabase.execSQL("DELETE FROM metadata");writableDatabase.execSQL("DELETE FROM pending_thought") }
}
