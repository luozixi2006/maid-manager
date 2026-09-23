package com.miniichat.watchlink

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** Phone is the single authority. Incremental, paginated replication is separate from original chat. */
class PhoneHubStore(context:Context):SQLiteOpenHelper(context,"phone_companion_hub.db",null,1),java.io.Closeable {
    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE records(channel TEXT,kind TEXT,id TEXT,body TEXT,revision INTEGER,deleted INTEGER DEFAULT 0,PRIMARY KEY(channel,kind,id))")
        db.execSQL("CREATE TABLE changes(seq INTEGER PRIMARY KEY AUTOINCREMENT,channel TEXT,kind TEXT,id TEXT,body TEXT,revision INTEGER,deleted INTEGER)")
        db.execSQL("CREATE INDEX change_channel ON changes(channel,seq)")
        db.execSQL("CREATE TABLE operations(channel TEXT,op TEXT,revision INTEGER,PRIMARY KEY(channel,op))")
        db.execSQL("CREATE TABLE turns(channel TEXT,id TEXT,body TEXT,status TEXT DEFAULT 'pending',attempts INTEGER DEFAULT 0,next_at INTEGER DEFAULT 0,PRIMARY KEY(channel,id))")
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY,v TEXT)")
    }
    override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int)=Unit
    fun meta(key:String)=readableDatabase.rawQuery("SELECT v FROM meta WHERE k=?",arrayOf(key)).use{if(it.moveToFirst())it.getString(0) else ""}
    fun meta(key:String,value:String){writableDatabase.insertWithOnConflict("meta",null,ContentValues().apply{put("k",key);put("v",value)},SQLiteDatabase.CONFLICT_REPLACE)}
    fun items(channel:String,kind:String):List<JSONObject> = readableDatabase.rawQuery("SELECT id,body,revision,deleted FROM records WHERE channel=? AND kind=? ORDER BY rowid",arrayOf(channel,kind)).use{c->buildList{while(c.moveToNext())add(JSONObject().put("id",c.getString(0)).put("body",JSONObject(c.getString(1))).put("revision",c.getInt(2)).put("deleted",c.getInt(3)!=0))}}
    fun item(channel:String,kind:String,id:String):JSONObject? = readableDatabase.rawQuery("SELECT body,revision,deleted FROM records WHERE channel=? AND kind=? AND id=?",arrayOf(channel,kind,id)).use{c->
        if(c.moveToFirst())JSONObject().put("id",id).put("body",JSONObject(c.getString(0))).put("revision",c.getInt(1)).put("deleted",c.getInt(2)!=0) else null
    }
    fun put(channel:String,kind:String,id:String,body:JSONObject,deleted:Boolean=false):Int = transaction {db->
        val previous=item(channel,kind,id)
        if(previous!=null && previous.optBoolean("deleted"))return@transaction previous.getInt("revision")
        if(previous!=null && previous.getJSONObject("body").toString()==body.toString() && !deleted)return@transaction previous.getInt("revision")
        val revision=(previous?.optInt("revision")?:0)+1
        val values=ContentValues().apply{put("channel",channel);put("kind",kind);put("id",id);put("body",body.toString());put("revision",revision);put("deleted",if(deleted)1 else 0)}
        check(db.insertWithOnConflict("records",null,values,SQLiteDatabase.CONFLICT_REPLACE)!=-1L)
        val sequence=db.insertOrThrow("changes",null,values)
        // Replicas need the latest state, not every 30-second Live Context revision.
        // Retain the newest sequence (including tombstones), so any old cursor can catch up.
        db.delete("changes","channel=? AND kind=? AND id=? AND seq<?",arrayOf(channel,kind,id,sequence.toString()))
        revision
    }
    fun ack(channel:String,op:String,revision:Int){writableDatabase.insertWithOnConflict("operations",null,ContentValues().apply{put("channel",channel);put("op",op);put("revision",revision)},SQLiteDatabase.CONFLICT_IGNORE)}
    fun acknowledged(channel:String,op:String):Int?=readableDatabase.rawQuery("SELECT revision FROM operations WHERE channel=? AND op=?",arrayOf(channel,op)).use{if(it.moveToFirst())it.getInt(0) else null}
    fun page(channel:String,after:Long,acked:JSONArray,revisions:JSONObject):JSONObject {
        val changes=JSONArray();var cursor=after
        readableDatabase.rawQuery("SELECT seq,kind,id,body,revision,deleted FROM changes WHERE channel=? AND seq>? ORDER BY seq LIMIT 30",arrayOf(channel,after.toString())).use{c->
            while(c.moveToNext()){cursor=c.getLong(0);changes.put(JSONObject().put("kind",c.getString(1)).put("id",c.getString(2)).put("body",JSONObject(c.getString(3))).put("revision",c.getInt(4)).put("deleted",c.getInt(5)!=0))}
        }
        val more=readableDatabase.rawQuery("SELECT 1 FROM changes WHERE channel=? AND seq>? LIMIT 1",arrayOf(channel,cursor.toString())).use{it.moveToFirst()}
        return JSONObject().put("changes",changes).put("cursor",cursor).put("more",more).put("acked",acked).put("acked_revisions",revisions)
    }
    fun queue(channel:String,id:String,body:JSONObject){writableDatabase.insertWithOnConflict("turns",null,ContentValues().apply{put("channel",channel);put("id",id);put("body",body.toString())},SQLiteDatabase.CONFLICT_IGNORE)}
    fun pending(channel:String):List<JSONObject> = readableDatabase.rawQuery("SELECT id,body FROM turns WHERE channel=? AND status='pending' AND attempts<5 AND next_at<=? ORDER BY CASE WHEN instr(body,'message_id')>0 THEN 0 ELSE 1 END,rowid LIMIT 3",arrayOf(channel,System.currentTimeMillis().toString())).use{c->buildList{while(c.moveToNext())add(JSONObject(c.getString(1)).put("id",c.getString(0)))}}
    fun finish(channel:String,id:String){writableDatabase.execSQL("UPDATE turns SET status='done' WHERE channel=? AND id=?",arrayOf(channel,id))}
    fun fail(channel:String,id:String){writableDatabase.execSQL("UPDATE turns SET attempts=attempts+1,next_at=? WHERE channel=? AND id=?",arrayOf(System.currentTimeMillis()+60000,channel,id))}
    fun defer(channel:String,id:String,until:Long){writableDatabase.execSQL("UPDATE turns SET next_at=? WHERE channel=? AND id=? AND status='pending'",arrayOf(until,channel,id))}
    fun retry(channel:String){writableDatabase.execSQL("UPDATE turns SET attempts=0,next_at=0 WHERE channel=? AND status='pending'",arrayOf(channel))}
    private fun <T> transaction(action:(SQLiteDatabase)->T):T {val db=writableDatabase;db.beginTransaction();return try{action(db).also{db.setTransactionSuccessful()}}finally{db.endTransaction()}}
}
