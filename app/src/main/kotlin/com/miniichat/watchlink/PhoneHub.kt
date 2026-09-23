package com.miniichat.watchlink

import android.content.Context
import com.miniichat.companion.*
import com.miniichat.data.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

object PhoneHub {
    private val mutex=Mutex()
    private fun prefs(context:Context)=context.getSharedPreferences("phone_watch_pairing",0)
    private fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    fun peer(context:Context)=prefs(context).getString("peer","").orEmpty()
    fun paired(context:Context)=prefs(context).getString("token_hash","").orEmpty().isNotBlank()
    fun code(context:Context,address:String):String {
        require(BluetoothLink.peers(context).any{it.address==address}) {"请先在系统蓝牙中配对这只手表"}
        val code=(10000000+SecureRandom().nextInt(90000000)).toString()
        prefs(context).edit().putString("peer",address).putString("code_hash",digest(code)).putLong("expires",System.currentTimeMillis()+600000).putInt("attempts",0).remove("token_hash").commit()
        return code
    }
    fun revoke(context:Context){prefs(context).edit().clear().commit()}
    fun channel(context:Context):String {val config=LinkConfig(context);return config.profile.optString("persona_id")+":"+config.conversationId}
    suspend fun handle(context:Context,address:String,request:JSONObject):JSONObject = mutex.withLock {
        val config=LinkConfig(context)
        if(!config.enabled || address!=peer(context))throw LinkFailure(403,"手机尚未允许这只手表")
        val path=request.getString("path");val body=request.optJSONObject("body")?:JSONObject()
        val prefs=prefs(context)
        if(path=="/v1/pair") {
            if(prefs.getLong("expires",0)<System.currentTimeMillis() || prefs.getInt("attempts",0)>=5)throw LinkFailure(403,"配对码已失效，请在手机重新生成")
            prefs.edit().putInt("attempts",prefs.getInt("attempts",0)+1).commit()
            if(!MessageDigest.isEqual(digest(body.optString("code")).toByteArray(),prefs.getString("code_hash","").orEmpty().toByteArray()))throw LinkFailure(403,"配对码不正确")
            val token=android.util.Base64.encodeToString(ByteArray(32).also{SecureRandom().nextBytes(it)},android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
            val device=UUID.randomUUID().toString()
            prefs.edit().putString("token_hash",digest(token)).putString("device",device).remove("code_hash").putLong("expires",0).commit()
            return@withLock JSONObject().put("token",token).put("device_id",device).put("conversation_id",config.conversationId)
        }
        if(!paired(context) || !MessageDigest.isEqual(digest(request.optString("token")).toByteArray(),prefs.getString("token_hash","").orEmpty().toByteArray()))throw LinkFailure(401,"手机已撤销连接，请重新配对")
        val conversation=ConversationStore(context).snapshot().firstOrNull{it.id==config.conversationId && it.assistantId==config.profile.optString("persona_id")}
            ?:throw LinkFailure(409,"原会话或人设已改变，请在手机重新选择")
        when(path) {
            "/v1/profile"->PhoneProfile.build(context,conversation.id)
            "/v1/sync"->{
                PhoneLink.mirror(context,conversation)
                PhoneHubStore(context).use{db->
                    val channel=channel(context);val ack=JSONArray();val revisions=JSONObject();val ops=body.optJSONArray("operations")?:JSONArray()
                    require(ops.length()<=20)
                    for(i in 0 until ops.length()) {
                        val op=ops.getJSONObject(i);val opId=op.getString("op_id");require(opId.length in 1..100)
                        val previous=db.acknowledged(channel,opId)
                        if(previous!=null){ack.put(opId);revisions.put(opId,previous);continue}
                        val kind=op.getString("kind");val id=op.getString("id");val value=op.getJSONObject("body")
                        require(id.length in 1..120 && !op.optBoolean("deleted"))
                        val known=db.item(channel,kind,id)
                        if(known?.optBoolean("deleted")==true)throw LinkFailure(409,"该内容已删除，不会重新恢复")
                        when(kind) {
                            "message"->{
                                require(value.optString("role")=="user" && value.optString("content").length in 1..4000)
                                val existing=ConversationStore(context).snapshot().first{it.id==conversation.id}.messages.firstOrNull{it.id==id}
                                if(existing!=null && (existing.role!="user" || existing.content!=value.getString("content")))throw LinkFailure(409,"消息已在手机修改，未覆盖手机内容")
                                if(existing==null)ConversationStore(context).updateConversation(conversation.id){current->
                                    if(current.messages.any{it.id==id})current else current.copy(messages=current.messages+Message(id,"user",value.getString("content"),personaId=conversation.assistantId,createdAt=value.optLong("createdAt").coerceIn(1,System.currentTimeMillis())),updatedAt=System.currentTimeMillis())
                                }
                                val saved=ConversationStore(context).snapshot().first{it.id==conversation.id}.messages.first{it.id==id}
                                val revision=db.put(channel,kind,id,PhoneLink.wire(saved));db.ack(channel,opId,revision);ack.put(opId);revisions.put(opId,revision)
                                // Queue atomically-by-id independently of the watch's subsequent think call.
                                db.queue(channel,"reply-$id",JSONObject().put("message_id",id))
                            }
                            "event","context"->{
                                require(value.toString().length<=16000 && (kind!="context" || id=="watch"))
                                if(kind=="event") require(value.optString("summary").length in 1..800 && value.optLong("at") in 1..System.currentTimeMillis()+300000)
                                if(kind=="event" && known!=null && known.getJSONObject("body").toString()!=value.toString())throw LinkFailure(409,"已保存的事件不可改写")
                                val revision=db.put(channel,kind,id,value);db.ack(channel,opId,revision);ack.put(opId);revisions.put(opId,revision)
                                if(kind=="context")db.meta("watch-received:$channel",System.currentTimeMillis().toString())
                                if(kind=="event")db.queue(channel,"event-$id",JSONObject().put("event_id",id))
                            }
                            else->throw LinkFailure(400,"手表无权修改这种数据")
                        }
                    }
                    db.page(channel,body.optLong("after").coerceAtLeast(0),ack,revisions)
                }
            }
            "/v1/think"->{
                val id=body.getString("id");require(id.length in 1..150)
                PhoneHubStore(context).use{db->
                    val field=if(body.has("message_id"))"message_id" else "event_id"
                    val kind=if(field=="message_id")"message" else "event"
                    require(id==(if(kind=="message")"reply-" else "event-")+body.getString(field)) {"回复任务编号不正确"}
                    check(db.items(channel(context),kind).any{it.getString("id")==body.optString(field) && !it.optBoolean("deleted") && (kind!="message" || it.getJSONObject("body").optString("role")=="user")}) {"消息或事件尚未同步"}
                    db.queue(channel(context),id,body)
                }
                JSONObject().put("queued",true)
            }
            else->throw LinkFailure(404,"不支持的同步请求")
        }
    }
}
