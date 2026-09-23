package com.miniichat.watch

import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.miniichat.companion.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class PairCandidate(val base:String,val result:JSONObject,val profile:JSONObject,val previous:WatchBinding) {
    val binding=WatchBinding(base,result.getString("conversation_id"),profile.getString("persona_id"),profile.optString("name","陪伴"))
    val sameConnection:Boolean get()=previous.key==binding.key
}
internal object WatchConnection {
    fun binding(config:LinkConfig)=WatchBinding(config.base,config.conversationId,config.profile.optString("persona_id"),config.profile.optString("name","原会话"))
    fun pause(context:Context) {
        LinkConfig(context).enabled(false)
        context.stopService(Intent(context,SensingService::class.java))
        // enabled=false is the durable stop gate even if the scheduler has not initialized yet.
        try {WorkManager.getInstance(context).cancelUniqueWork("watch-recovery")}
        catch(_:IllegalStateException){android.util.Log.w("WatchConnection","Scheduler unavailable during pause; connection disabled")}
        WatchRuntime.status.value="已断开，未同步记录保留在手表"
        WatchRuntime.revision.value++
    }
    suspend fun resume(context:Context)=withContext(Dispatchers.IO) {WatchRuntime.connectionChange {
        val config=LinkConfig(context)
        check(config.base.isNotBlank() && config.conversationId.isNotBlank()){"请先输入手机生成的配对码"}
        LinkStore(context).use {check(ConnectionRecords(it).matches(binding(config))){"配对尚未完成，请重新输入手机配对码；原记录仍保留"}}
        config.token() // Validate local credentials without printing them.
        config.enabled(true)
        WatchRuntime.status.value="正在连接原手机…"
        WatchRuntime.schedule(context)
    }}
    /** Pairing authenticates only. Do not clear data or enable the candidate before user confirmation. */
    suspend fun prepare(context:Context,base:String,code:String,
        call:(String,String,String,JSONObject?)->JSONObject={host,token,path,body->CompanionTransport.call(context,host,token,path,body)}
    ):PairCandidate=withContext(Dispatchers.IO) {WatchRuntime.connectionChange {
        check(!LinkConfig(context).enabled){"请先断开当前连接"}
        require(code.matches(Regex("[0-9]{8}"))){"请输入手机生成的 8 位配对码"}
        BluetoothLink.validate(base)
        val previous=LinkStore(context).use{ConnectionRecords(it).current(binding(LinkConfig(context)))}
        val result=call(base,"","/v1/pair",JSONObject().put("code",code))
        val profile=call(base,result.getString("token"),"/v1/profile",null)
        require(result.getString("conversation_id").isNotBlank() && profile.getString("persona_id").isNotBlank()){"手机未返回完整会话，请重新选择后配对"}
        PairCandidate(base,result,profile,previous)
    }}
    suspend fun accept(context:Context,candidate:PairCandidate)=withContext(Dispatchers.IO) {WatchRuntime.connectionChange {
        val config=LinkConfig(context)
        check(!config.enabled){"连接状态已变化，请重新配对"}
        LinkStore(context).use {store->
            val records=ConnectionRecords(store)
            check(records.current(binding(config)).key==candidate.previous.key){"本地会话已变化，请重新配对"}
            // Commit the new credentials while paused. A crash before activation cannot send old data.
            config.save(candidate.base,candidate.result,enabled=false,profile=candidate.profile)
            records.activate(candidate.previous,candidate.binding)
        }
        config.enabled(true)
        WatchRuntime.status.value=if(candidate.sameConnection)"已恢复原连接，正在补传记录…" else "已连接，旧会话已单独保留"
        WatchRuntime.schedule(context)
    }}
}
