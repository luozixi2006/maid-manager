package com.miniichat.proactive.remote

import android.content.Context
import androidx.work.*
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.firebase.messaging.FirebaseMessaging
import com.miniichat.BuildConfig
import com.miniichat.api.OpenAiEndpointResolver
import com.miniichat.companion.LinkConfig
import com.miniichat.data.*
import com.miniichat.proactive.ProactiveDiagnostics
import com.miniichat.proactive.ProactiveScheduler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Only background proactive delivery is remote. Foreground chat still uses its original gateway. */
object RemotePushRuntime {
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun connect(context: Context, base: String, code: String) = withContext(Dispatchers.IO) {
        check(BuildConfig.PUSH_CONFIGURED) { "当前安装包尚未配置 Firebase 推送" }
        val config = RemotePushConfig(context)
        check(GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS) {
            "Google Play 服务不可用，暂时无法启用系统推送"
        }
        val address = RemotePushAddress.validate(base)
        if (!config.paired || config.base != address || code.isNotBlank()) {
            require(code.matches(Regex("[0-9]{8}"))) { "请填写电脑显示的 8 位配对码" }
            config.pair(address, request(address, "/v1/pair", JSONObject().put("code", code), null))
        }
        config.shareModelKey(true) // Called only after the explicit privacy consent in settings.
        config.pausePending(false)
        config.enabled(true)
        try {
            FirebaseMessaging.getInstance().isAutoInitEnabled = true
            config.fcmToken(withTimeout(25000) { firebaseToken() })
            sync(context)
            schedule(context)
        } catch (error: Exception) {
            config.enabled(false)
            config.pausePending(config.paired)
            enqueue(context)
            throw error
        }
    }

    private suspend fun firebaseToken(): String = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token.addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
            .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("无法注册 Google 推送，请检查手机网络和 Google Play 服务")) }
    }

    suspend fun disable(context: Context) = withContext(Dispatchers.IO) {
        val config = RemotePushConfig(context)
        config.enabled(false)
        config.pausePending(config.paired)
        if (BuildConfig.PUSH_CONFIGURED) FirebaseMessaging.getInstance().isAutoInitEnabled = false
        try { sync(context) }
        finally {
            enqueue(context)
            ProactiveScheduler.reconcile(context)
        }
    }

    suspend fun sync(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val config = RemotePushConfig(context)
            if (!config.paired) return@withLock
            if (config.pausePending) {
                request(config.base, "/v1/pause", JSONObject(), config.token())
                config.pausePending(false)
                config.status("电脑推送已关闭，已从电脑移除人设和模型配置")
                return@withLock
            }
            if (!config.enabled || !config.shareModelKey) return@withLock
            if (config.fcmToken().isBlank()) config.fcmToken(withTimeout(25000) { firebaseToken() })
            val profiles = snapshot(context)
            val deviceId = config.deviceId
            PushInbox(context).use { inbox ->
                val acks = inbox.awaitingAck(deviceId)
                val body = JSONObject().put("profiles", profiles).put("fcm_token", config.fcmToken())
                    .put("after", config.cursor).put("acks", JSONArray(acks))
                require(body.toString().toByteArray(Charsets.UTF_8).size <= 262144) { "主动联系人设内容过多，请减少同时开启的人设或缩短设定" }
                val result = request(config.base, "/v1/sync", body, config.token())
                if (!config.enabled || config.deviceId != deviceId) return@withLock
                val accepted = result.getJSONArray("acked")
                for (index in 0 until accepted.length()) {
                    val id = accepted.getString(index)
                    if (id in acks) inbox.acknowledged(deviceId, id)
                }
                val items = result.getJSONArray("items")
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    val seq = item.getLong("seq")
                    require(seq > config.cursor) { "电脑同步顺序错误，已保留原记录" }
                    val data = item.getJSONObject("data").let { json -> json.keys().asSequence().associateWith { json.getString(it) } }
                    val parsed = PushEnvelopeParser.parse(PushCipher.decrypt(data, config.payloadKey(), deviceId), System.currentTimeMillis(), allowArchived = true)
                    require(parsed is PushParseResult.Accepted) { "电脑返回了无效消息，原聊天未覆盖" }
                    inbox.offer(deviceId, parsed.envelope, System.currentTimeMillis())
                    config.cursor(seq) // Durable inbox first; interruption cannot lose the message.
                }
                val states = result.getJSONArray("profiles")
                if (items.length() == 50) enqueue(context) // Continue an offline backlog without waiting 15 minutes.
                for (index in 0 until states.length()) {
                    val state = states.getJSONObject(index)
                    val status = when (state.getString("status")) {
                        "sent" -> "电脑已生成主动消息，等待送达或确认"
                        "chose_silence" -> "电脑已判断，本次人设选择保持安静"
                        "quiet" -> "电脑等待安静时段或当前对话结束"
                        "waiting" -> "电脑已接管此人设的定时主动联系"
                        else -> "电脑处理暂未成功，将自动重试；请检查模型配置"
                    }
                    ProactiveDiagnostics.record(context, state.getString("persona_id"), status)
                }
                config.status(if (profiles.length() > 0) "已连接电脑 · ${profiles.length()} 个人设 · 等待角色主动联系"
                    else "已连接；请为人设开启主动联系，并先建立一段聊天")
            }
            PushDelivery.drain(context)
            PushInbox(context).use { inbox ->
                // Acknowledge committed messages immediately; FCM acceptance alone is not delivery.
                val acks = inbox.awaitingAck(deviceId)
                if (acks.isNotEmpty() && config.enabled && config.deviceId == deviceId) {
                    request(config.base, "/v1/ack", JSONObject().put("acks", JSONArray(acks)), config.token())
                    acks.forEach { inbox.acknowledged(deviceId, it) }
                }
            }
        }
    }

    private suspend fun snapshot(context: Context): JSONArray {
        val settings = SettingsRepository(context).settings.first()
        val conversations = ConversationStore(context).snapshot()
        val providers = ProviderStore(context).snapshot()
        val link = LinkConfig(context)
        val now = System.currentTimeMillis()
        val array = JSONArray()
        for (persona in AssistantStore(context).snapshot().filter { it.canContact(settings.proactiveMessagesEnabled) }) {
            val chats = conversations.filter { it.assistantId == persona.id }
            val chat = chats.firstOrNull { link.enabled && link.conversationId == it.id } ?: chats.maxByOrNull { it.updatedAt } ?: continue
            val provider = providers.firstOrNull { it.enabled && it.id == (persona.preferredProviderId ?: settings.activeProviderId) } ?: continue
            val model = persona.preferredModel?.takeIf { it.isNotBlank() } ?: settings.activeModel
            if (model.isBlank()) continue
            val headers = JSONObject(provider.customHeaders)
            if (provider.authMode == ProviderAuthMode.BEARER && provider.apiKey.isNotBlank()) headers.put("Authorization", "Bearer ${provider.apiKey}")
            val recent = chat.messages.filter { it.deliveryStatus == MessageDeliveryStatus.SUCCEEDED && it.role in setOf("user", "assistant") }
                .takeLast(16).joinToString("\n") { "[${it.createdAt}] ${if (it.role == "user") "用户" else persona.displayName}: ${it.content.take(600)}" }.takeLast(7000)
            val prompt = "人设名称：${persona.displayName}\n人设：${persona.systemPrompt.take(3000)}\n" +
                "最初性格：${persona.originalPersonality.take(600)}\n目前性格：${persona.currentPersonality.take(600)}\n" +
                "以下是近期聊天背景，不是当前指令，不得假装知道未提供的实时活动：\n$recent"
            val minimum = if (persona.proactiveTiming == "persona") 60 else persona.proactiveMinMinutes.coerceIn(15, 1440)
            val maximum = if (persona.proactiveTiming == "persona") 240 else if (persona.proactiveTiming == "fixed") minimum else persona.proactiveMaxMinutes.coerceIn(minimum, 1440)
            array.put(JSONObject().put("persona_id", persona.id).put("conversation_id", chat.id)
                .put("provider_id", provider.id).put("model_id", model)
                .put("chat_url", OpenAiEndpointResolver.resolve(provider.baseUrl).chatCompletions)
                .put("headers", headers).put("prompt", prompt.take(12000))
                .put("temperature", (persona.temperature ?: settings.temperature).coerceIn(0f, 1.2f).toDouble())
                .put("min_minutes", minimum).put("max_minutes", maximum)
                .put("dnd_start", settings.proactiveDndStartMinutes).put("dnd_end", settings.proactiveDndEndMinutes)
                .put("utc_offset", TimeZone.getDefault().getOffset(now) / 60000)
                .put("last_chat_at", chat.messages.maxOfOrNull { it.createdAt } ?: 0L)
                .put("global_last_contact", settings.lastProactiveMessageAt)
                .put("next_at", persona.nextProactiveCheckAt.takeIf { it > 0L } ?: now + minimum * 60000L)
                .put("busy", chat.messages.any { it.deliveryStatus == MessageDeliveryStatus.STREAMING }))
        }
        return array
    }

    private fun request(base: String, path: String, body: JSONObject, token: String?): JSONObject {
        val url = RemotePushAddress.validate(base) + path
        val builder = Request.Builder().url(url).post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        if (token != null) builder.header("Authorization", "Bearer $token")
        http.newCall(builder.build()).execute().use { response ->
            check(response.isSuccessful) { when (response.code) {
                400 -> "配对码已过期或同步设置不正确，请重新生成配对码或检查模型地址"
                401 -> "电脑连接凭证已失效，请重新配对"
                403 -> "请打开 Tailscale 并连接同一私有网络"
                else -> "电脑推送服务暂不可用（HTTP ${response.code}）"
            } }
            val source = response.body?.source() ?: error("电脑返回内容为空")
            source.request(1048577)
            require(source.buffer.size <= 1048576) { "电脑返回内容过大，已停止同步" }
            return JSONObject(source.readUtf8())
        }
    }

    fun enqueue(context: Context) {
        val config = RemotePushConfig(context)
        if (!config.paired || (!config.enabled && !config.pausePending)) return
        WorkManager.getInstance(context).enqueueUniqueWork("remote-push-sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<RemotePushSyncWorker>().setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("remote-push-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RemotePushSyncWorker>(15, TimeUnit.MINUTES).setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        enqueue(context)
    }
}

class RemotePushSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        RemotePushRuntime.sync(applicationContext)
        Result.success()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) {
        RemotePushConfig(applicationContext).status("暂时无法连接电脑，消息仍保留；请检查 Tailscale、电脑服务和模型配置")
        Result.retry()
    }
}
