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
            // Keep token persistence separate from message delivery; never log it.
            PushInboxWorker.enqueue(this)
            RemotePushRuntime.enqueue(this)
        } catch (_: Exception) { Log.w("MaidPush", "Token persistence needs retry") }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val config = RemotePushConfig(this)
        if (!config.enabled || !config.paired) return
        try {
            val plain = PushCipher.decrypt(message.data, config.payloadKey(), config.deviceId)
            val parsed = PushEnvelopeParser.parse(plain, System.currentTimeMillis())
            if (parsed !is PushParseResult.Accepted) {
                Log.w("MaidPush", "Rejected invalid message envelope")
                return
            }
            PushInbox(this).use { it.offer(config.deviceId, parsed.envelope, System.currentTimeMillis()) }
            // Persist first. Expedited recovery is already queued if the process is killed mid-delivery.
            PushInboxWorker.enqueue(this)
            runBlocking(Dispatchers.IO) { withTimeout(4000) { PushDelivery.drain(applicationContext) } }
        } catch (_: TimeoutCancellationException) {
            Log.i("MaidPush", "Delivery saved for expedited recovery")
        } catch (_: Exception) {
            // Never print RemoteMessage, exception causes, tokens or decrypted private text.
            Log.w("MaidPush", "Push rejected or local persistence unavailable")
        }
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
