package com.miniichat.watchlink

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import com.miniichat.data.*
import com.miniichat.memory.MemoryRepository
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

object PhoneProfile {
    fun hash(profile:JSONObject)=MessageDigest.getInstance("SHA-256").digest(profile.toString().toByteArray()).joinToString(""){"%02x".format(it)}
    suspend fun build(context:Context,conversation:String):JSONObject {
        val chat=ConversationStore(context).snapshot().firstOrNull{it.id==conversation}?:error("原会话已删除")
        val settings=SettingsRepository(context).settings.first()
        val persona=AssistantStore(context).snapshot().firstOrNull{it.id==chat.assistantId}?:error("找不到这段聊天的人设")
        val avatar=persona.avatarPath?.let{path->runCatching {
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(path,bounds)
            val options=BitmapFactory.Options();while(maxOf(bounds.outWidth,bounds.outHeight)/options.inSampleSize>192)options.inSampleSize*=2
            BitmapFactory.decodeFile(path,options)?.let{bitmap->ByteArrayOutputStream().use{out->bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);bitmap.recycle();Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)}}
        }.getOrNull()}.orEmpty()
        return JSONObject().put("persona_id",persona.id).put("name",persona.displayName)
            .put("avatar",avatar).put("proactive",persona.canContact(settings.proactiveMessagesEnabled))
            .put("dnd_start",settings.proactiveDndStartMinutes/60).put("dnd_end",settings.proactiveDndEndMinutes/60)
    }
}
