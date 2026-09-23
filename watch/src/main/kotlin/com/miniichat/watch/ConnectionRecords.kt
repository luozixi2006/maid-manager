package com.miniichat.watch

import android.content.ContentValues
import com.miniichat.companion.LinkStore
import org.json.JSONArray
import org.json.JSONObject

/** Backups contain conversation data only: no pairing codes, tokens or API credentials. */
internal data class WatchBinding(val base:String,val conversation:String,val persona:String,val name:String) {
    val key:String get()=JSONArray().put(base.trimEnd('/').let{if(it.startsWith("bluetooth://"))it.lowercase() else it}).put(conversation).put(persona).toString()
    fun json()=JSONObject().put("base",base).put("conversation",conversation).put("persona",persona).put("name",name)
    companion object {
        fun read(value:JSONObject)=WatchBinding(value.optString("base"),value.optString("conversation"),value.optString("persona"),value.optString("name","原会话"))
    }
}
internal data class PendingRecords(val messages:Int,val events:Int,val contexts:Int,val replies:Int) {
    val total:Int get()=messages+events+contexts+replies
    fun describe()="待同步：$messages 条消息 · $events 条事件 · $contexts 份状态\n待确认回复：$replies 项"
}
internal data class ConnectionBackup(val binding:WatchBinding,val savedAt:Long,val pending:PendingRecords)

/** Uses the existing SQLite transaction so switching never clears the only copy of old records. */
internal class ConnectionRecords(private val store:LinkStore) {
    private val db get()=store.writableDatabase
    init { db.execSQL("CREATE TABLE IF NOT EXISTS watch_connection_backups(binding TEXT PRIMARY KEY,info TEXT NOT NULL,snapshot TEXT NOT NULL,saved_at INTEGER NOT NULL)") }
    fun current(fallback:WatchBinding):WatchBinding {
        val saved=store.meta("watch_binding")
        if(saved.isNotBlank())return WatchBinding.read(JSONObject(saved))
        val migrated=fallback.copy(conversation=store.meta("bound").ifBlank{fallback.conversation})
        store.meta("watch_binding",migrated.json().toString())
        return migrated
    }
    fun matches(binding:WatchBinding)=current(binding).key==binding.key && store.meta("bound")==binding.conversation
    fun pending():PendingRecords {
        fun count(table:String,where:String="")=db.rawQuery("SELECT COUNT(*) FROM $table $where",null).use{it.moveToFirst();it.getInt(0)}
        return PendingRecords(count("outbox","WHERE kind='message'"),count("outbox","WHERE kind='event'"),count("outbox","WHERE kind='context'"),count("pending_thought"))
    }
    private val tables=listOf("items","outbox","metadata","pending_thought")
    private fun snapshot():JSONObject=JSONObject().apply {
        for(table in tables)put(table,db.rawQuery("SELECT * FROM $table",null).use{cursor->JSONArray().apply{
            while(cursor.moveToNext())put(JSONObject().apply{cursor.columnNames.forEachIndexed{i,column->put(column,cursor.getString(i))}})
        }})
    }
    private fun restore(snapshot:JSONObject) {
        for(table in tables) {
            val rows=snapshot.getJSONArray(table)
            for(i in 0 until rows.length()) {
                val row=rows.getJSONObject(i)
                val values=ContentValues().apply{row.keys().forEach{column->put(column,row.getString(column))}}
                db.insertOrThrow(table,null,values)
            }
        }
    }
    /** Caller serializes pairing with sync, pauses sensing, and explicitly confirms different bindings. */
    fun activate(previous:WatchBinding,next:WatchBinding) {
        db.beginTransaction()
        try {
            val actual=current(previous)
            if(actual.key!=next.key) {
                val old=snapshot()
                if(tables.filter{it!="metadata"}.any{old.getJSONArray(it).length()>0}) {
                    val counts=pending()
                    val info=actual.json().put("messages",counts.messages).put("events",counts.events).put("contexts",counts.contexts).put("replies",counts.replies)
                    db.insertWithOnConflict("watch_connection_backups",null,ContentValues().apply {
                        put("binding",actual.key);put("info",info.toString());put("snapshot",old.toString());put("saved_at",System.currentTimeMillis())
                    },android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE).also{check(it!=-1L){"旧记录备份失败，未切换会话"}}
                }
                val archived=db.rawQuery("SELECT snapshot FROM watch_connection_backups WHERE binding=?",arrayOf(next.key)).use{if(it.moveToFirst())JSONObject(it.getString(0)) else null}
                store.clear()
                if(archived!=null)restore(archived)
                // The restored data is now the active copy. A later switch archives its updated state.
                db.delete("watch_connection_backups","binding=?",arrayOf(next.key))
            }
            store.meta("bound",next.conversation)
            store.meta("watch_binding",next.json().toString())
            // Fetch the phone history again; message/op IDs still make retrying local writes idempotent.
            store.meta("cursor","");store.meta("inflight","[]")
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    fun backups():List<ConnectionBackup> = db.rawQuery("SELECT info,saved_at FROM watch_connection_backups ORDER BY saved_at DESC",null).use{cursor->buildList{
        while(cursor.moveToNext()) {
            val info=JSONObject(cursor.getString(0))
            add(ConnectionBackup(WatchBinding.read(info),cursor.getLong(1),PendingRecords(info.optInt("messages"),info.optInt("events"),info.optInt("contexts"),info.optInt("replies"))))
        }
    }}
    fun preview(binding:WatchBinding?=null):String {
        val data=if(binding==null)snapshot() else db.rawQuery("SELECT snapshot FROM watch_connection_backups WHERE binding=?",arrayOf(binding.key)).use{if(it.moveToFirst())JSONObject(it.getString(0)) else null}?:return "没有保留记录"
        val messages=linkedMapOf<String,String>()
        for(table in listOf("items","outbox")) {
            val rows=data.getJSONArray(table)
            for(i in 0 until rows.length()) {
                val row=rows.getJSONObject(i)
                if(row.optString("kind")=="message" && row.optString("deleted")!="1") {
                    val body=JSONObject(row.getString("body"))
                    messages[row.getString("id")]=(if(body.optString("role")=="user")"你：" else "角色：")+body.optString("content")
                }
            }
        }
        return messages.values.toList().takeLast(30).joinToString("\n\n").ifBlank{"没有聊天消息，身体状态和事件记录仍已保留。"}
    }
}
