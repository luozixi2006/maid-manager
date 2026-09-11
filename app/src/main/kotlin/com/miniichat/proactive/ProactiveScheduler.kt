package com.miniichat.proactive

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.miniichat.data.AssistantStore
import com.miniichat.data.SettingsRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object ProactiveScheduler {
    private const val UNIQUE_WORK = "maid-manager-proactive-messages"

    suspend fun reconcile(context: Context, appendAfterCurrent: Boolean = false) {
        val appContext = context.applicationContext
        val settings = SettingsRepository(appContext).settings.first()
        val workManager = WorkManager.getInstance(appContext)
        if (!settings.proactiveMessagesEnabled) {
            workManager.cancelUniqueWork(UNIQUE_WORK)
            return
        }

        val now = System.currentTimeMillis()
        val assistantStore = AssistantStore(appContext)
        var assistants = assistantStore.snapshot()
        var assistantsChanged = false
        assistants = assistants.map { assistant ->
            if (assistant.proactiveEnabled && assistant.nextProactiveCheckAt <= 0L) {
                assistantsChanged = true
                assistant.copy(
                    nextProactiveCheckAt = now + ProactivePolicy.nextDelayMillis(
                        settings.proactiveFrequency,
                        Random.nextDouble()
                    )
                )
            } else assistant
        }
        if (assistantsChanged) assistantStore.save(assistants)

        val normalTimes = assistants.asSequence()
            .filter { it.proactiveEnabled }
            .map { it.nextProactiveCheckAt }
            .filter { it > 0L }
        val earliest = normalTimes.minOrNull()
        if (earliest == null) {
            workManager.cancelUniqueWork(UNIQUE_WORK)
            return
        }

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
