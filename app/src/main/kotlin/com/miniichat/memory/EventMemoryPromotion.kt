package com.miniichat.memory

import android.content.Context
import com.miniichat.data.SettingsRepository
import com.miniichat.watchlink.PhoneHubStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/** Raw samples never reach this layer. Promote only recurring multi-day observations, as hypotheses. */
object EventMemoryPromotion {
    fun patterns(events:List<JSONObject>,now:Long):List<MemoryEvidence> {
        val zone=ZoneId.systemDefault()
        return events.filter {now-it.optLong("at") in 0..30L*86400000 && it.optString("type") in setOf("started_moving","walking","running","stopped_moving","sedentary","possible_woke")}
            .groupBy {event->val hour=Instant.ofEpochMilli(event.optLong("at")).atZone(zone).hour
                event.optString("type")+":"+when(hour){in 5..11->"上午";in 12..17->"下午";in 18..23->"晚上";else->"凌晨"}}
            .mapNotNull {(key,values)->
                val days=values.map{Instant.ofEpochMilli(it.optLong("at")).atZone(zone).toLocalDate()}.distinct()
                if(days.size<3)null else MemoryEvidence("pattern:$key:${days.max()}","event_pattern",values.maxOf{it.optLong("at")},
                    "最近 30 天中有 ${days.size} 个不同日期，在${key.substringAfter(':')}记录到“${values.first().optString("summary")}”。这是有限观察形成的可能习惯，不是确定活动或医学结论。来源事件：${values.takeLast(12).joinToString{it.optString("id")}}")
            }
    }
    suspend fun check(context:Context,persona:String,channel:String) {
        val settings=SettingsRepository(context).settings.first()
        if(!settings.memoryEnabled || !settings.autoMemoryEnabled)return
        MemoryDatabase(context).use {memory->
            val now=System.currentTimeMillis()
            if(now-(memory.meta("patterns:$persona").toLongOrNull()?:0)<86400000)return
            val evidence=PhoneHubStore(context).use {db->patterns(db.items(channel,"event").filter{!it.optBoolean("deleted")}.map{it.getJSONObject("body")},now)}
            evidence.forEach{item->memory.enqueue(item.id+":"+persona,persona,JSONObject().put("evidence",MemoryLearning.json.encodeToString(listOf(item))).toString())}
            memory.meta("patterns:$persona",now.toString())
            if(evidence.isNotEmpty())MemoryWork.schedule(context)
        }
    }
}
