package com.miniichat.proactive.remote

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MaidMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        try {
            RemotePushConfig(this).fcmToken(token)
            PushTrace.record(this, "token_updated")
            // Keep token persistence separate from message delivery; never log it.
            PushInboxWorker.enqueue(this)
            RemotePushRuntime.enqueue(this)
        } catch (_: Exception) { Log.w("MaidPush", "Token persistence needs retry") }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val config = RemotePushConfig(this)
        if (!config.enabled || !config.paired) return
        try {
            PushTrace.record(this, "fcm_callback", detail = "priority=${message.priority},original=${message.originalPriority}")
            runBlocking(Dispatchers.IO) {
                withTimeout(4000) {
                    if (PushIngress.receive(applicationContext, message.data, "fcm") != null) PushDelivery.drain(applicationContext)
                }
            }
        } catch (_: TimeoutCancellationException) {
            Log.i("MaidPush", "Delivery saved for expedited recovery")
        } catch (_: Exception) {
            // Never print RemoteMessage, exception causes, tokens or decrypted private text.
            Log.w("MaidPush", "Push rejected or local persistence unavailable")
        }
    }

    override fun onDeletedMessages() {
        PushTrace.record(this, "fcm_deleted_backlog")
        RemotePushRuntime.enqueue(this)
    }
}

class PushInboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        PushDelivery.drain(applicationContext)
        RemotePushRuntime.enqueue(applicationContext)
        Result.success()
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (_: Exception) { Result.retry() }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("remote-push-inbox", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<PushInboxWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build())
        }
    }
}
