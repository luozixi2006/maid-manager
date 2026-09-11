package com.miniichat.proactive

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import com.miniichat.api.ChatMessage
import com.miniichat.api.LlmClient
import com.miniichat.data.AppSettings
import com.miniichat.data.Assistant
import com.miniichat.data.AssistantStore
import com.miniichat.data.ConversationStore
import com.miniichat.data.Message
import com.miniichat.data.ProviderConfig
import com.miniichat.data.ProviderStore
import com.miniichat.data.SettingsRepository
import com.miniichat.error.PrivacySanitizer
import com.miniichat.error.safeProviderHost
import com.miniichat.memory.MemoryRepository
import com.miniichat.util.newId
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

@Serializable
private data class ProactiveDecision(
    val action: String = "SKIP",
    val message: String = "",
    val topic_summary: String = "",
    val reason: String = "",
    val next_contact_tendency: Double = 0.5
)

private sealed interface DueCandidate {
    val dueAt: Long
    data class Normal(val assistant: Assistant) : DueCandidate {
        override val dueAt: Long = assistant.nextProactiveCheckAt
    }
}

class ProactiveMessageWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        val settingsRepository = SettingsRepository(applicationContext)
        val settings = settingsRepository.settings.first()
        if (!settings.proactiveMessagesEnabled) return Result.success()
        val now = System.currentTimeMillis()

        if (ProactivePolicy.isInDoNotDisturb(
                now,
                settings.proactiveDndStartMinutes,
                settings.proactiveDndEndMinutes
            )
        ) {
            val afterDnd = ProactivePolicy.endOfDoNotDisturbMillis(
                now,
                settings.proactiveDndEndMinutes
            ) + Random.nextLong(5L * 60_000L, 45L * 60_000L)
            ProactiveScheduler.scheduleAt(
                applicationContext,
                afterDnd,
                ExistingWorkPolicy.APPEND_OR_REPLACE
            )
            return Result.success()
        }

        val throttleEndsAt = settings.lastProactiveMessageAt + ProactivePolicy.GLOBAL_THROTTLE_MILLIS
        if (settings.lastProactiveMessageAt > 0L && now < throttleEndsAt) {
            ProactiveScheduler.scheduleAt(
                applicationContext,
                throttleEndsAt + Random.nextLong(5L * 60_000L, 35L * 60_000L),
                ExistingWorkPolicy.APPEND_OR_REPLACE
            )
            return Result.success()
        }

        val assistantStore = AssistantStore(applicationContext)
        val candidates = buildList {
            assistantStore.snapshot()
                .filter { it.proactiveEnabled && it.nextProactiveCheckAt > 0L && it.nextProactiveCheckAt <= now }
                .forEach { add(DueCandidate.Normal(it)) }
        }

        if (candidates.isEmpty()) {
            ProactiveScheduler.reconcile(applicationContext, appendAfterCurrent = true)
            return Result.success()
        }

        // Among characters that are already due, randomize the order so a large cast does not
        // always favor the first stored character.
        when (val candidate = candidates.shuffled().minByOrNull { it.dueAt + Random.nextLong(0, 30 * 60_000L) }) {
            is DueCandidate.Normal -> processNormal(candidate.assistant, settings, settingsRepository)
            null -> Unit
        }
        ProactiveScheduler.reconcile(applicationContext, appendAfterCurrent = true)
        return Result.success()
    }

    private suspend fun processNormal(
        assistant: Assistant,
        settings: AppSettings,
        settingsRepository: SettingsRepository
    ) {
        val now = System.currentTimeMillis()
        val assistantStore = AssistantStore(applicationContext)
        val conversationStore = ConversationStore(applicationContext)
        val conversation = conversationStore.snapshot()
            .asSequence()
            .filter { it.assistantId == assistant.id && it.messages.isNotEmpty() }
            .maxByOrNull { it.updatedAt }

        if (conversation == null || shouldWaitForUser(conversation.messages.map {
                HistoryMessage(it.role, it.content, it.createdAt, it.isProactive)
            }, now)
        ) {
            assistantStore.upsert(assistant.withNext(settings, contactTendency = 0.2))
            return
        }

        val provider = ProviderStore(applicationContext).snapshot().firstOrNull {
            it.id == (assistant.preferredProviderId ?: settings.activeProviderId)
        }
        val modelId = assistant.preferredModel?.takeIf { it.isNotBlank() } ?: settings.activeModel
        if (provider == null || provider.baseUrl.isBlank() || modelId.isBlank()) {
            assistantStore.upsert(assistant.afterFailure(settings))
            Log.w(TAG, "Normal proactive check skipped: provider/model not configured")
            return
        }
        val diagnosticProvider = safeProviderHost(provider.baseUrl) ?: "unknown-provider"
        val diagnosticModel = PrivacySanitizer.safeIdentifier(modelId) ?: "unknown-model"

        val memoryRepository = MemoryRepository(applicationContext)
        try {
            val memories = memoryRepository.enabled(30).joinToString("\n") { "- ${it.content}" }
            val recent = conversation.messages.takeLast(28).joinToString("\n") {
                "${if (it.role == "user") "User" else assistant.name}: ${it.content.take(900)}"
            }.takeLast(14_000)
            val prompt = """
                You decide whether ${assistant.name} has a meaningful reason to contact User now.
                Return one JSON object only:
                {"action":"SEND|SKIP","message":"","topic_summary":"","reason":"","next_contact_tendency":0.0}

                Rules:
                - Stay fully in character. Original personality and current personality both matter.
                - SEND only when there is a natural reason: an unfinished topic, a promise, an important recent event,
                  a fitting personal update, or relationship context. SKIP is a valid and preferred answer when not useful.
                - Do not repeatedly send generic phrases such as "are you there", "what are you doing", or "have you eaten".
                - Never invent User's real-world activity, mood, location, or facts not supported below.
                - Do not repeat any recent proactive topic.
                - message must contain only the message User should see, with no explanation or metadata.
                - next_contact_tendency expresses this character's current personality/relationship tendency to contact again,
                  from 0.0 (very reserved right now) to 1.0 (naturally proactive right now).

                Character system prompt:
                ${assistant.systemPrompt}

                Original personality:
                ${assistant.originalPersonality.ifBlank { "Not separately recorded; infer carefully from the system prompt." }}

                Current personality:
                ${assistant.currentPersonality.ifBlank { "No later personality change is recorded." }}

                Long-term memories:
                ${memories.ifBlank { "None recorded." }}

                Recent proactive topics (do not repeat):
                ${assistant.proactiveMessageSummaries.joinToString(" | ").ifBlank { "None" }}

                Recent conversation:
                $recent
            """.trimIndent()

            val decision = requestDecision(provider, modelId, assistant.temperature ?: settings.temperature, prompt)
            if (decision.action.equals("SEND", true) && decision.message.isNotBlank()) {
                val message = Message(
                    id = newId(),
                    role = "assistant",
                    content = decision.message.trim().take(1200),
                    modelId = modelId,
                    personaId = assistant.id,
                    isProactive = true,
                    createdAt = now
                )
                val saved = conversationStore.appendMessage(conversation.id, message) ?: return
                assistantStore.upsert(
                    assistant.afterSuccess(settings, decision, now)
                )
                settingsRepository.update { it.copy(lastProactiveMessageAt = now) }
                ProactiveNotifications.publish(
                    applicationContext,
                    assistant.name,
                    message.content,
                    assistant.avatarPath,
                    ProactiveDestination("normal", conversationId = saved.id)
                )
                Log.i(
                    TAG,
                    "Proactive normal message saved provider=$diagnosticProvider " +
                        "model=$diagnosticModel"
                )
            } else {
                assistantStore.upsert(assistant.withNext(settings, decision.next_contact_tendency))
                Log.i(
                    TAG,
                    "Proactive normal decision=SKIP provider=$diagnosticProvider " +
                        "model=$diagnosticModel"
                )
            }
        } catch (error: Throwable) {
            assistantStore.upsert(assistant.afterFailure(settings))
            Log.e(
                TAG,
                "Proactive normal request failed provider=$diagnosticProvider " +
                    "model=$diagnosticModel type=${error.javaClass.name}"
            )
        } finally {
            memoryRepository.close()
        }
    }

    private suspend fun requestDecision(
        provider: ProviderConfig,
        modelId: String,
        temperature: Float,
        prompt: String
    ): ProactiveDecision {
        val client = LlmClient()
        val result = try {
            client.completeDetailed(
                provider = provider,
                modelId = modelId,
                messages = listOf(
                    ChatMessage("system", "You are a cautious proactive-contact decision engine. Return valid JSON only."),
                    ChatMessage("user", prompt)
                ),
                temperature = temperature.coerceIn(0.1f, 1.2f),
                structuredJson = true,
                requestTimeoutMillis = 90_000L,
                maxOutputTokens = 500
            )
        } finally {
            client.close()
        }
        val content = result.content.trim()
        val objectText = content.substringAfter('{', "").takeIf { it.isNotBlank() }
            ?.let { "{" + it.substringBeforeLast('}', "") + "}" }
            ?: error("Proactive decision JSON missing")
        return json.decodeFromString(ProactiveDecision.serializer(), objectText)
    }

    private fun shouldWaitForUser(messages: List<HistoryMessage>, now: Long): Boolean {
        val last = messages.lastOrNull() ?: return false
        if (now - last.createdAt < ProactivePolicy.RECENT_USER_ACTIVITY_MILLIS) return true
        return last.role == "assistant" && last.isProactive &&
            messages.dropWhile { it.createdAt <= last.createdAt }.none { it.role == "user" }
    }

    private fun Assistant.withNext(settings: AppSettings, contactTendency: Double?) = copy(
        nextProactiveCheckAt = System.currentTimeMillis() + ProactivePolicy.nextDelayMillis(
            settings.proactiveFrequency,
            Random.nextDouble(),
            contactTendency
        ),
        proactiveFailureCount = 0
    )

    private fun Assistant.afterSuccess(
        settings: AppSettings,
        decision: ProactiveDecision,
        now: Long
    ) = withNext(settings, decision.next_contact_tendency).copy(
        lastProactiveMessageAt = now,
        proactiveMessageSummaries = (proactiveMessageSummaries +
            decision.topic_summary.ifBlank { decision.message.take(80) }).takeLast(8),
        proactiveFailureCount = 0
    )

    private fun Assistant.afterFailure(settings: AppSettings) = copy(
        nextProactiveCheckAt = System.currentTimeMillis() + ProactivePolicy.nextDelayMillis(
            settings.proactiveFrequency,
            Random.nextDouble(),
            failureCount = proactiveFailureCount + 1
        ),
        proactiveFailureCount = (proactiveFailureCount + 1).coerceAtMost(6)
    )

    private data class HistoryMessage(
        val role: String,
        val text: String,
        val createdAt: Long,
        val isProactive: Boolean
    )

    companion object {
        private const val TAG = "MaidProactive"
    }
}
