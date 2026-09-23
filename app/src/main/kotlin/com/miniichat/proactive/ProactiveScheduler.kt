package com.miniichat.proactive

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.workDataOf
import com.miniichat.data.AssistantStore
import com.miniichat.data.SettingsRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object ProactiveScheduler {
    private const val UNIQUE_WORK = "maid-manager-proactive-messages"

    suspend fun testNow(context:Context,assistantId:String):String {
        val settings=SettingsRepository(context).settings.first()
        val assistant=AssistantStore(context).snapshot().firstOrNull{it.id==assistantId}
        check(assistant?.canContact(settings.proactiveMessagesEnabled)==true){"请先保存并开启此人设的主动联系"}
        val request=OneTimeWorkRequestBuilder<ProactiveMessageWorker>()
            .setInputData(workDataOf("manual" to true,"assistant_id" to assistantId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        ProactiveDiagnostics.record(context,assistantId,"主动消息测试已排队；联网后执行，结果在此更新")
        WorkManager.getInstance(context).enqueueUniqueWork("proactive-test-$assistantId",ExistingWorkPolicy.KEEP,request)
        return "已提交实际后台流程；将保存一条问候并尝试通知。请查看下方诊断。"
    }

    suspend fun reconcile(context: Context, appendAfterCurrent: Boolean = false) {
        val appContext = context.applicationContext
        val settings = SettingsRepository(appContext).settings.first()
        val workManager = WorkManager.getInstance(appContext)

        val now = System.currentTimeMillis()
        val assistantStore = AssistantStore(appContext)
        assistantStore.transformAll { current -> current.map { assistant ->
            if (assistant.canContact(settings.proactiveMessagesEnabled) && assistant.nextProactiveCheckAt <= 0L) {
                assistant.copy(
                    nextProactiveCheckAt = now + if (assistant.proactiveTiming == "persona") ProactivePolicy.initialDelayMillis(Random.nextDouble())
                        else ProactivePolicy.personaDelay(assistant, Random.nextDouble())
                )
            } else assistant
        } }
        val assistants = assistantStore.snapshot()

        val normalTimes = assistants.asSequence()
            .filter { it.canContact(settings.proactiveMessagesEnabled) }
            .map { it.nextProactiveCheckAt }
            .filter { it > 0L }
        val earliest = normalTimes.minOrNull()
        if (earliest == null) {
            workManager.cancelUniqueWork(UNIQUE_WORK)
            workManager.cancelUniqueWork("proactive-recovery")
            return
        }
        // A separate durable check repairs a one-off chain cancelled during settings changes.
        workManager.enqueueUniquePeriodicWork("proactive-recovery",ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ProactiveMessageWorker>(15,TimeUnit.MINUTES)
                .setInputData(workDataOf("recovery" to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())

        scheduleAt(
            appContext,
            earliest,
            if (appendAfterCurrent) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        )
    }

    fun scheduleAt(
        context: Context,
        timestamp: Long,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE
    ) {
        val delay = (timestamp - System.currentTimeMillis()).coerceAtLeast(60_000L)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<ProactiveMessageWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .addTag(UNIQUE_WORK)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK, policy, request)
    }
}
