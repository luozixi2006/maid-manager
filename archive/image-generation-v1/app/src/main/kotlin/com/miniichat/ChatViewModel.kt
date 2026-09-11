package com.miniichat

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miniichat.api.ChatMessage
import com.miniichat.api.LlmClient
import com.miniichat.chatdata.ChatDataCodec
import com.miniichat.chatdata.ConversationHandoffGenerator
import com.miniichat.chatdata.HandoffMode
import com.miniichat.data.AppSettings
import com.miniichat.data.Assistant
import com.miniichat.data.AssistantStore
import com.miniichat.data.Attachment
import com.miniichat.data.Conversation
import com.miniichat.data.ConversationStore
import com.miniichat.data.Message
import com.miniichat.data.ProviderConfig
import com.miniichat.data.ProviderStore
import com.miniichat.data.SettingsRepository
import com.miniichat.data.SourceReference
import com.miniichat.memory.LongTermMemory
import com.miniichat.memory.MemoryCategories
import com.miniichat.memory.MemoryRepository
import com.miniichat.image.ImageGenerationException
import com.miniichat.image.ImageGenerationService
import com.miniichat.image.ImageGenerationStatus
import com.miniichat.image.ImageGenerationUiState
import com.miniichat.image.imageGenerationConfig
import com.miniichat.proactive.ProactiveScheduler
import com.miniichat.search.SearchConfig
import com.miniichat.search.SearchManager
import com.miniichat.tts.TtsConfig
import com.miniichat.tts.TtsManager
import com.miniichat.tts.TtsPlaybackState
import com.miniichat.util.PromptVars
import com.miniichat.util.newId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

data class StreamingOverlay(
    val messageId: String,
    val content: String,
    val reasoning: String
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private companion object {
        const val TTS_TAG = "MaidManagerTts"
    }

    private val store = ConversationStore(app)
    val settingsRepo = SettingsRepository(app)
    val providerStore = ProviderStore(app)
    val assistantStore = AssistantStore(app)
    private val memoryRepository = MemoryRepository(app)
    private val client = LlmClient()
    private val handoffGenerator = ConversationHandoffGenerator(client)
    private val searchManager = SearchManager()
    private val ttsManager = TtsManager(app)
    private val imageGenerationService = ImageGenerationService(app)
    private val json = Json { ignoreUnknownKeys = true }

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val providers: StateFlow<List<ProviderConfig>> = providerStore.providersFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val assistants: StateFlow<List<Assistant>> = assistantStore.assistantsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val conversations: StateFlow<List<Conversation>> = store.conversationsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val memories: StateFlow<List<LongTermMemory>> = memoryRepository.memories
    val ttsState: StateFlow<TtsPlaybackState> = ttsManager.state

    private val _streamingOverlay = MutableStateFlow<StreamingOverlay?>(null)
    val streamingOverlay: StateFlow<StreamingOverlay?> = _streamingOverlay.asStateFlow()
    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    private val _fetchingModelsFor = MutableStateFlow<String?>(null)
    val fetchingModelsFor: StateFlow<String?> = _fetchingModelsFor.asStateFlow()
    private val _chatDataBusy = MutableStateFlow(false)
    val chatDataBusy: StateFlow<Boolean> = _chatDataBusy.asStateFlow()
    private val _chatDataPhase = MutableStateFlow<String?>(null)
    val chatDataPhase: StateFlow<String?> = _chatDataPhase.asStateFlow()
    private val _imageGeneration = MutableStateFlow(ImageGenerationUiState())
    val imageGeneration: StateFlow<ImageGenerationUiState> = _imageGeneration.asStateFlow()
    private val _imageConnectionMessage = MutableStateFlow<String?>(null)
    val imageConnectionMessage: StateFlow<String?> = _imageConnectionMessage.asStateFlow()
    private val _testingImageConnection = MutableStateFlow(false)
    val testingImageConnection: StateFlow<Boolean> = _testingImageConnection.asStateFlow()

    private var streamingJob: Job? = null
    private var ttsJob: Job? = null
    private var imageGenerationJob: Job? = null

    init {
        viewModelScope.launch { memoryRepository.refresh() }
        viewModelScope.launch { ProactiveScheduler.reconcile(app) }
        viewModelScope.launch {
            ttsManager.errors.collect { error ->
                Log.e(TTS_TAG, "Asynchronous TTS playback failed", error)
                _toast.value = ttsError(error)
            }
        }
    }

    fun selectConversation(id: String?) {
        _activeId.value = id
        stopTts()
        if (id != null) {
            viewModelScope.launch {
                val linkedPersona = store.snapshot().firstOrNull { it.id == id }
                    ?.assistantId?.takeIf { it.isNotBlank() }
                if (linkedPersona != null && assistants.value.any { it.id == linkedPersona }) {
                    settingsRepo.update { it.copy(activeAssistantId = linkedPersona) }
                }
            }
        }
    }

    fun newConversation(): String {
        stopStreaming()
        val id = newId()
        _activeId.value = id
        return id
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            val conversation = store.snapshot().firstOrNull { it.id == id }
            conversation?.messages?.map { it.id }?.let(ttsManager::deleteCaches)
            conversation?.messages?.let(::deleteGeneratedAttachments)
            store.delete(id)
            if (_activeId.value == id) _activeId.value = null
        }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch { store.rename(id, title) }
    }

    fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        _streamingOverlay.value = null
        _isStreaming.value = false
        stopTts()
    }

    fun clearError() { _error.value = null }
    fun clearToast() { _toast.value = null }

    fun testImageConnection(baseUrl: String) {
        if (_testingImageConnection.value) return
        _testingImageConnection.value = true
        _imageConnectionMessage.value = null
        viewModelScope.launch {
            try {
                val health = imageGenerationService.testConnection(
                    settings.value.imageGenerationConfig().copy(enabled = true, baseUrl = baseUrl)
                )
                _imageConnectionMessage.value = when (health.modelStatus) {
                    "ready" -> buildString {
                        append("连接成功 · ${health.model}")
                        health.gpuName?.let { append(" · $it") }
                        health.freeVramMb?.let { append(" · 空闲显存 ${it}MB") }
                    }
                    "loading" -> "PC 服务已连接，模型正在加载"
                    "not_loaded" -> "PC 服务已连接，但模型尚未加载"
                    "error" -> "PC 服务已连接，但模型加载失败：${health.modelError.orEmpty()}"
                    else -> "PC 服务已连接，模型状态：${health.modelStatus}"
                }
            } catch (error: ImageGenerationException) {
                _imageConnectionMessage.value = imageError(error)
            } finally {
                _testingImageConnection.value = false
            }
        }
    }

    fun generateStandaloneImage(prompt: String) {
        startImageGeneration(prompt, attachToChat = false)
    }

    fun generateImageForChat(prompt: String) {
        if (_isStreaming.value) {
            _error.value = "文字回复进行中，请结束后再生成图片"
            return
        }
        startImageGeneration(prompt, attachToChat = true)
    }

    private fun startImageGeneration(prompt: String, attachToChat: Boolean) {
        val originalPrompt = prompt.trim()
        if (originalPrompt.isBlank() || imageGenerationJob?.isActive == true) return
        val config = settings.value.imageGenerationConfig()
        if (!config.enabled) {
            _error.value = "请先在设置中启用 AI 生图"
            return
        }
        if (config.baseUrl.isBlank()) {
            _error.value = "请先填写 PC 生图服务地址"
            return
        }
        imageGenerationJob = viewModelScope.launch {
            var conversationId: String? = null
            if (attachToChat) {
                conversationId = _activeId.value ?: newId().also { _activeId.value = it }
                val existing = store.snapshot().firstOrNull { it.id == conversationId }
                val promptMessage = Message(
                    id = newId(),
                    role = "user",
                    content = "生成图片：$originalPrompt",
                    modelId = config.model
                )
                val updated = (existing ?: Conversation(
                    id = conversationId,
                    title = originalPrompt.take(30).replace("\n", " "),
                    assistantId = settings.value.activeAssistantId
                )).let { conversation ->
                    conversation.copy(
                        messages = conversation.messages + promptMessage,
                        updatedAt = System.currentTimeMillis()
                    )
                }
                store.upsert(updated)
            }
            _imageGeneration.value = ImageGenerationUiState(
                status = ImageGenerationStatus.WAITING,
                originalPrompt = originalPrompt,
                effectivePrompt = originalPrompt,
                model = config.model,
                width = config.width,
                height = config.height
            )
            try {
                val result = imageGenerationService.generate(
                    config = config,
                    originalPrompt = originalPrompt,
                    collection = conversationId ?: "standalone"
                ) { jobId, status ->
                    _imageGeneration.value = _imageGeneration.value.copy(
                        jobId = jobId,
                        status = status
                    )
                }
                _imageGeneration.value = ImageGenerationUiState(
                    status = ImageGenerationStatus.SUCCEEDED,
                    jobId = result.jobId,
                    originalPrompt = result.originalPrompt,
                    effectivePrompt = result.effectivePrompt,
                    localPath = result.localPath,
                    model = result.model,
                    width = result.width,
                    height = result.height,
                    seed = result.seed
                )
                if (conversationId != null) {
                    val file = java.io.File(result.localPath)
                    appendImageMessage(
                        conversationId,
                        Message(
                            id = newId(),
                            role = "assistant",
                            content = "图片已生成",
                            attachments = listOf(
                                Attachment(
                                    type = "image",
                                    uri = result.localPath,
                                    mimeType = "image/png",
                                    name = file.name,
                                    sizeBytes = file.length(),
                                    originalPrompt = result.originalPrompt,
                                    effectivePrompt = result.effectivePrompt,
                                    generationModel = result.model,
                                    width = result.width,
                                    height = result.height,
                                    seed = result.seed,
                                    generationId = result.jobId
                                )
                            ),
                            modelId = result.model
                        )
                    )
                }
            } catch (cancelled: CancellationException) {
                _imageGeneration.value = _imageGeneration.value.copy(
                    status = ImageGenerationStatus.CANCELLED,
                    errorCode = "CANCELLED",
                    errorMessage = "图片生成已取消"
                )
            } catch (error: ImageGenerationException) {
                val friendly = imageError(error)
                _imageGeneration.value = _imageGeneration.value.copy(
                    status = if (error.code == "CANCELLED") ImageGenerationStatus.CANCELLED
                    else ImageGenerationStatus.FAILED,
                    errorCode = error.code,
                    errorMessage = friendly
                )
                _error.value = friendly
                if (conversationId != null) {
                    appendImageMessage(
                        conversationId,
                        Message(newId(), "assistant", "图片生成失败：$friendly", modelId = config.model)
                    )
                }
            } finally {
                imageGenerationJob = null
            }
        }
    }

    private suspend fun appendImageMessage(conversationId: String, message: Message) {
        val conversation = store.snapshot().firstOrNull { it.id == conversationId } ?: return
        store.upsert(
            conversation.copy(
                messages = conversation.messages + message,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun cancelImageGeneration() {
        imageGenerationJob?.cancel()
    }

    fun saveGeneratedImage(localPath: String, destination: Uri) {
        viewModelScope.launch {
            try {
                imageGenerationService.copyToUri(localPath, destination)
                _toast.value = "图片已保存"
            } catch (error: Exception) {
                _error.value = if (error is ImageGenerationException) imageError(error) else "图片保存失败"
            }
        }
    }

    fun activeProvider(): ProviderConfig? =
        providers.value.firstOrNull { it.id == settings.value.activeProviderId }

    fun activeAssistant(): Assistant? =
        assistants.value.firstOrNull { it.id == settings.value.activeAssistantId }

    fun supportsNativeReasoning(provider: ProviderConfig?, modelId: String): Boolean =
        provider != null && client.supportsNativeReasoning(provider, modelId)

    fun selectModel(providerId: String, model: String) {
        viewModelScope.launch {
            settingsRepo.update { it.copy(activeProviderId = providerId, activeModel = model) }
        }
    }

    fun selectAssistant(id: String) {
        viewModelScope.launch { settingsRepo.update { it.copy(activeAssistantId = id) } }
    }

    fun upsertAssistant(assistant: Assistant) {
        viewModelScope.launch {
            assistantStore.upsert(assistant)
            ProactiveScheduler.reconcile(getApplication())
        }
    }

    fun deleteAssistant(id: String) {
        viewModelScope.launch {
            assistantStore.delete(id)
            if (settings.value.activeAssistantId == id) {
                val remaining = assistantStore.snapshot()
                settingsRepo.update {
                    it.copy(activeAssistantId = remaining.firstOrNull()?.id ?: "default")
                }
            }
            ProactiveScheduler.reconcile(getApplication())
        }
    }

    fun upsertProvider(provider: ProviderConfig) {
        viewModelScope.launch {
            providerStore.upsert(provider)
            val all = providerStore.snapshot()
            if (settings.value.activeProviderId.isBlank() && all.isNotEmpty()) {
                val target = all.firstOrNull { it.id == provider.id } ?: all.first()
                settingsRepo.update {
                    it.copy(
                        activeProviderId = target.id,
                        activeModel = target.models.firstOrNull() ?: ""
                    )
                }
            }
        }
    }

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            providerStore.delete(id)
            if (settings.value.activeProviderId == id) {
                val next = providerStore.snapshot().firstOrNull()
                settingsRepo.update {
                    it.copy(
                        activeProviderId = next?.id ?: "",
                        activeModel = next?.models?.firstOrNull() ?: ""
                    )
                }
            }
        }
    }

    fun fetchModels(providerId: String) {
        viewModelScope.launch {
            val provider = providerStore.snapshot().firstOrNull { it.id == providerId } ?: return@launch
            _fetchingModelsFor.value = providerId
            try {
                val models = client.listModels(provider)
                if (models.isEmpty()) _toast.value = "API 未返回可用模型"
                else {
                    providerStore.upsert(provider.copy(models = (provider.models + models).distinct().sorted()))
                    _toast.value = "已获取 ${models.size} 个模型"
                }
            } catch (error: Exception) {
                _error.value = userFacingError(error)
            } finally {
                _fetchingModelsFor.value = null
            }
        }
    }

    fun addManualModel(providerId: String, model: String) {
        viewModelScope.launch {
            val trimmed = model.trim()
            if (trimmed.isEmpty()) return@launch
            val provider = providerStore.snapshot().firstOrNull { it.id == providerId } ?: return@launch
            providerStore.upsert(provider.copy(models = (provider.models + trimmed).distinct().sorted()))
        }
    }

    fun removeModel(providerId: String, model: String) {
        viewModelScope.launch {
            val provider = providerStore.snapshot().firstOrNull { it.id == providerId } ?: return@launch
            val updated = provider.copy(models = provider.models - model)
            providerStore.upsert(updated)
            if (settings.value.activeProviderId == providerId && settings.value.activeModel == model) {
                settingsRepo.update { it.copy(activeModel = updated.models.firstOrNull() ?: "") }
            }
        }
    }

    fun setWebEnabled(enabled: Boolean) = updateSettings { it.copy(webEnabled = enabled) }
    fun setReasoningEnabled(enabled: Boolean) = updateSettings { it.copy(reasoningEnabled = enabled) }
    fun setReasoningEffort(effort: String) = updateSettings { it.copy(reasoningEffort = effort) }

    fun upsertMemory(memory: LongTermMemory) {
        viewModelScope.launch { memoryRepository.upsert(memory) }
    }

    fun deleteMemory(id: String) {
        viewModelScope.launch { memoryRepository.delete(id) }
    }

    fun setMemoryEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { memoryRepository.setEnabled(id, enabled) }
    }

    fun prepareChatArchive(onReady: (fileName: String, content: String) -> Unit) {
        viewModelScope.launch {
            try {
                _chatDataBusy.value = true
                _chatDataPhase.value = "正在整理完整聊天"
                val snapshot = store.snapshot()
                require(snapshot.isNotEmpty()) { "还没有可以导出的聊天" }
                val app = getApplication<Application>()
                val version = runCatching {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                }.getOrNull().orEmpty()
                val content = ChatDataCodec.encodeArchive(
                    conversations = snapshot,
                    personas = assistantStore.snapshot(),
                    memories = memoryRepository.memories.value,
                    appVersion = version
                )
                val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(java.util.Date())
                onReady("女仆管理器-全部聊天-$date.json", content)
            } catch (error: Exception) {
                _error.value = error.message ?: "完整聊天导出失败"
            } finally {
                _chatDataBusy.value = false
                _chatDataPhase.value = null
            }
        }
    }

    fun importChatArchive(raw: String) {
        viewModelScope.launch {
            try {
                _chatDataBusy.value = true
                _chatDataPhase.value = "正在导入完整聊天"
                val archive = ChatDataCodec.decodeArchive(raw)
                val result = ChatDataCodec.mergeArchive(
                    archive = archive,
                    existingConversations = store.snapshot(),
                    existingPersonas = assistantStore.snapshot(),
                    existingMemories = memoryRepository.memories.value
                )
                assistantStore.save(result.personas)
                memoryRepository.importAll(result.memories)
                store.save(result.conversations)
                ProactiveScheduler.reconcile(getApplication())
                _toast.value = buildString {
                    append("已导入 ${result.importedConversations} 个聊天")
                    if (result.duplicatedConversations > 0) {
                        append("，${result.duplicatedConversations} 个重名聊天保留为副本")
                    }
                }
            } catch (error: Exception) {
                _error.value = error.message ?: "完整聊天导入失败"
            } finally {
                _chatDataBusy.value = false
                _chatDataPhase.value = null
            }
        }
    }

    fun generateHandoff(
        mode: HandoffMode,
        onReady: (fileName: String, content: String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                _chatDataBusy.value = true
                val conversation = _activeId.value?.let { id ->
                    store.snapshot().firstOrNull { it.id == id }
                } ?: error("请先打开一个聊天")
                val persona = assistants.value.firstOrNull { it.id == conversation.assistantId }
                    ?: activeAssistant()
                val provider = persona?.preferredProviderId?.let { preferred ->
                    providers.value.firstOrNull { it.id == preferred }
                } ?: activeProvider() ?: error("请先完成 API 配置")
                val model = persona?.preferredModel?.takeIf { it.isNotBlank() }
                    ?: settings.value.activeModel
                require(model.isNotBlank()) { "请先选择模型" }
                val handoff = handoffGenerator.generate(
                    conversation = conversation,
                    persona = persona,
                    memories = memories.value,
                    provider = provider,
                    modelId = model,
                    mode = mode,
                    onProgress = { _chatDataPhase.value = it }
                )
                val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(java.util.Date())
                val name = ChatDataCodec.safeFilePart(conversation.title)
                onReady("$name-交接包-$date.json", ChatDataCodec.encodeHandoff(handoff))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _error.value = userFacingError(error)
            } finally {
                _chatDataBusy.value = false
                _chatDataPhase.value = null
            }
        }
    }

    fun importHandoff(raw: String, onImported: () -> Unit) {
        viewModelScope.launch {
            try {
                val handoff = ChatDataCodec.decodeHandoff(raw)
                val conversation = ChatDataCodec.conversationFromHandoff(
                    handoff = handoff,
                    assistantId = settings.value.activeAssistantId
                )
                store.upsert(conversation)
                _activeId.value = conversation.id
                _toast.value = "已创建接续聊天"
                onImported()
            } catch (error: Exception) {
                _error.value = error.message ?: "AI 交接包导入失败"
            }
        }
    }

    fun playMessage(message: Message) {
        if (message.role != "assistant" || message.content.isBlank()) return
        val currentState = ttsState.value
        if (currentState.messageId == message.id &&
            currentState.status == com.miniichat.tts.TtsStatus.LOADING
        ) return
        if (currentState.messageId != message.id) {
            ttsJob?.cancel()
            ttsManager.stop()
        }
        ttsJob = viewModelScope.launch {
            try {
                val file = ttsManager.playOrToggle(message.id, message.content, ttsConfig(settings.value))
                if (file != null) updateMessageCachePath(message.id, file.absolutePath)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ttsManager.stop()
                Log.e(TTS_TAG, "TTS request/playback failed", error)
                _toast.value = ttsError(error)
            }
        }
    }

    fun stopTts() {
        ttsJob?.cancel()
        ttsJob = null
        ttsManager.stop()
    }

    fun sendMessage(
        text: String,
        attachments: List<Attachment> = emptyList(),
        webOverride: Boolean? = null,
        reasoningOverride: Boolean? = null
    ) {
        if (_isStreaming.value || streamingJob?.isActive == true) return
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return

        val current = settings.value
        val linkedPersonaId = _activeId.value
            ?.let { id -> conversations.value.firstOrNull { it.id == id } }
            ?.assistantId?.takeIf { it.isNotBlank() }
        val assistant = assistants.value.firstOrNull {
            it.id == (linkedPersonaId ?: current.activeAssistantId)
        }
        val provider = assistant?.preferredProviderId
            ?.let { id -> providers.value.firstOrNull { it.id == id } }
            ?: activeProvider()
        if (provider == null) {
            _error.value = "请先完成 API 配置"
            return
        }
        val model = assistant?.preferredModel?.takeIf { it.isNotBlank() } ?: current.activeModel
        if (model.isBlank()) {
            _error.value = "请先选择模型"
            return
        }
        if (provider.apiKey.isBlank() &&
            !provider.baseUrl.contains("localhost") &&
            !provider.baseUrl.contains("10.0.2.2")
        ) {
            _error.value = "请先填写 API Key"
            return
        }
        val uri = runCatching { java.net.URI(provider.baseUrl) }.getOrNull()
        if (uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
            _error.value = "Base URL 格式错误"
            return
        }

        val webEnabled = webOverride ?: current.webEnabled
        val reasoningEnabled = reasoningOverride ?: current.reasoningEnabled
        val temperature = assistant?.temperature ?: current.temperature
        val systemPrompt = PromptVars.render(
            template = assistant?.systemPrompt?.takeIf { it.isNotBlank() } ?: current.systemPrompt,
            model = model,
            provider = provider.name,
            assistant = assistant?.name ?: "女仆"
        )

        viewModelScope.launch {
            val activeId = _activeId.value ?: newId().also { _activeId.value = it }
            val existing = store.snapshot().firstOrNull { it.id == activeId }
            val title = trimmed.take(30).replace("\n", " ")
            val personaId = assistant?.id ?: current.activeAssistantId
            val userMessage = Message(
                id = newId(),
                role = "user",
                content = trimmed,
                attachments = attachments,
                modelId = model,
                personaId = personaId,
                webEnabled = webEnabled,
                reasoningEnabled = reasoningEnabled
            )
            val answerId = newId()
            val placeholder = Message(
                id = answerId,
                role = "assistant",
                content = "",
                modelId = model,
                personaId = personaId,
                webEnabled = webEnabled,
                reasoningEnabled = reasoningEnabled
            )
            val updated = (existing ?: Conversation(
                id = activeId,
                title = title,
                assistantId = personaId
            )).let { conversation ->
                conversation.copy(
                    title = if (conversation.messages.isEmpty()) title else conversation.title,
                    messages = conversation.messages + userMessage + placeholder,
                    updatedAt = System.currentTimeMillis()
                )
            }
            store.upsert(updated)
            _isStreaming.value = true

            streamingJob = launch {
                val answer = StringBuilder()
                val reasoning = StringBuilder()
                var sources: List<SourceReference> = emptyList()
                var completedNormally = false
                var lastFlush = 0L
                _streamingOverlay.value = StreamingOverlay(answerId, "", "")
                try {
                    val apiMessages = mutableListOf<ChatMessage>()
                    if (systemPrompt.isNotBlank()) apiMessages += ChatMessage("system", systemPrompt)
                    if (updated.handoffContext.isNotBlank()) {
                        apiMessages += ChatMessage("system", updated.handoffContext)
                    }

                    if (current.memoryEnabled) {
                        val enabledMemories = memoryRepository.enabled(50)
                        if (enabledMemories.isNotEmpty()) {
                            apiMessages += ChatMessage("system", formatMemories(enabledMemories))
                        }
                    }

                    if (reasoningEnabled && !client.supportsNativeReasoning(provider, model)) {
                        apiMessages += ChatMessage(
                            "system",
                            "请先充分分析问题，再给出清晰、简洁的最终答案。不要展示隐藏推理链，只提供必要的高层次说明。"
                        )
                    }

                    if (webEnabled) {
                        try {
                            val searchResults = searchManager.search(searchConfig(current), trimmed)
                            if (searchResults.isNotEmpty()) {
                                sources = searchManager.asSources(searchResults)
                                apiMessages += ChatMessage("system", searchManager.formatForModel(searchResults))
                                updateAssistant(activeId, answerId, sources = sources)
                            } else if (searchManager.shouldSearch(trimmed)) {
                                _toast.value = "联网搜索未返回结果，已使用模型自身知识回答"
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            _toast.value = "联网搜索失败，已使用模型自身知识回答"
                        }
                    }

                    updated.messages
                        .filterNot { it.id == answerId || (it.role == "assistant" && it.content.isBlank()) }
                        .forEach { message -> apiMessages += ChatMessage(message.role, message.content) }

                    client.chatStream(
                        provider = provider,
                        settings = current.copy(temperature = temperature),
                        modelId = model,
                        messages = apiMessages,
                        reasoningEnabled = reasoningEnabled,
                        reasoningEffort = current.reasoningEffort
                    ).collect { event ->
                        event.reasoning?.let(reasoning::append)
                        event.content?.let(answer::append)
                        _streamingOverlay.value = StreamingOverlay(
                            answerId,
                            answer.toString(),
                            reasoning.toString()
                        )
                        val now = System.currentTimeMillis()
                        if (now - lastFlush >= 250L) {
                            updateAssistant(
                                activeId,
                                answerId,
                                content = answer.toString(),
                                reasoning = reasoning.toString(),
                                sources = sources
                            )
                            lastFlush = now
                        }
                    }
                    completedNormally = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val friendly = userFacingError(error)
                    _error.value = friendly
                    if (answer.isEmpty()) answer.append("请求失败：$friendly")
                } finally {
                    val summary = if (reasoningEnabled && reasoning.isEmpty() && answer.isNotEmpty()) {
                        "已完成深度思考"
                    } else ""
                    if (answer.isNotEmpty() || reasoning.isNotEmpty()) {
                        updateAssistant(
                            activeId,
                            answerId,
                            content = answer.toString(),
                            reasoning = reasoning.toString(),
                            reasoningSummary = summary,
                            sources = sources
                        )
                    }
                    _streamingOverlay.value = null
                    _isStreaming.value = false
                    streamingJob = null

                    if (completedNormally && answer.isNotEmpty()) {
                        if (current.ttsAutoRead) {
                            playMessage(
                                placeholder.copy(
                                    content = answer.toString(),
                                    reasoningText = reasoning.toString(),
                                    reasoningSummary = summary,
                                    sources = sources
                                )
                            )
                        }
                        if (current.memoryEnabled && current.autoMemoryEnabled) {
                            scheduleMemoryExtraction(trimmed, answer.toString(), provider, model)
                        }
                    }
                }
            }
        }
    }

    private fun formatMemories(memories: List<LongTermMemory>): String = buildString {
        appendLine("以下内容是关于用户的长期记忆。仅在与当前问题相关时自然使用，不要主动逐条复述。")
        memories.forEach { appendLine("- [${it.category}] ${it.content}") }
    }.trim()

    private suspend fun updateAssistant(
        conversationId: String,
        messageId: String,
        content: String? = null,
        reasoning: String? = null,
        reasoningSummary: String? = null,
        sources: List<SourceReference>? = null,
        ttsCachePath: String? = null
    ) {
        val conversation = store.snapshot().firstOrNull { it.id == conversationId } ?: return
        val messages = conversation.messages.map { message ->
            if (message.id != messageId) message else message.copy(
                content = content ?: message.content,
                reasoningText = reasoning ?: message.reasoningText,
                reasoningSummary = reasoningSummary ?: message.reasoningSummary,
                sources = sources ?: message.sources,
                ttsCachePath = ttsCachePath ?: message.ttsCachePath
            )
        }
        store.upsert(conversation.copy(messages = messages, updatedAt = System.currentTimeMillis()))
    }

    private suspend fun updateMessageCachePath(messageId: String, path: String) {
        val conversation = store.snapshot().firstOrNull { it.messages.any { message -> message.id == messageId } }
            ?: return
        updateAssistant(conversation.id, messageId, ttsCachePath = path)
    }

    private fun scheduleMemoryExtraction(
        userText: String,
        assistantText: String,
        provider: ProviderConfig,
        model: String
    ) {
        if (userText.length < 8 || userText.trim().lowercase() in setOf("你好", "谢谢", "hi", "hello")) return
        viewModelScope.launch {
            try {
                val existing = memoryRepository.memories.value.take(30)
                val existingText = existing.joinToString("\n") {
                    "${it.id} | ${it.category} | ${it.content}"
                }.ifBlank { "无" }
                val prompt = """
                    你是本地长期记忆筛选器。只保存稳定偏好、长期目标、重要经历、聊天偏好、长期项目或稳定事实。
                    不保存寒暄、临时问题、一次性信息、普通知识问答和无意义内容。
                    已有记忆：
                    $existingText

                    本轮用户：$userText
                    本轮助手：${assistantText.take(1500)}

                    只输出一个 JSON 对象，不要 Markdown。格式之一：
                    {"action":"ADD","category":"偏好","content":"用户喜欢深烘无糖咖啡。"}
                    {"action":"UPDATE","id":"已有记忆ID","category":"偏好","content":"更新后的内容"}
                    {"action":"DELETE","id":"已有记忆ID"}
                    {"action":"NONE"}
                    category 只能是：${MemoryCategories.all.joinToString("、")}。
                """.trimIndent()
                val response = StringBuilder()
                client.chatStream(
                    provider = provider,
                    settings = settings.value.copy(stream = false, temperature = 0.1f),
                    modelId = model,
                    messages = listOf(ChatMessage("user", prompt)),
                    reasoningEnabled = false
                ).collect { event -> event.content?.let(response::append) }
                applyMemoryAction(response.toString())
            } catch (_: CancellationException) {
            } catch (_: Exception) {
                // Automatic extraction is best-effort and never interrupts chat.
            }
        }
    }

    private suspend fun applyMemoryAction(raw: String) {
        val objectText = Regex("\\{[\\s\\S]*}").find(raw)?.value ?: return
        val obj = runCatching { json.parseToJsonElement(objectText).jsonObject }.getOrNull() ?: return
        val action = obj.string("action")?.uppercase() ?: return
        val id = obj.string("id")
        val content = obj.string("content")?.trim().orEmpty()
        val category = obj.string("category")?.takeIf { it in MemoryCategories.all } ?: "其他"
        val now = System.currentTimeMillis()
        when (action) {
            "ADD" -> if (content.isNotBlank() && memoryRepository.memories.value.none {
                    it.content.equals(content, ignoreCase = true)
                }) {
                memoryRepository.upsert(LongTermMemory(newId(), content, category, now, now, true))
            }
            "UPDATE" -> {
                val old = memoryRepository.memories.value.firstOrNull { it.id == id } ?: return
                if (content.isNotBlank()) memoryRepository.upsert(
                    old.copy(content = content, category = category, updatedAt = now)
                )
            }
            "DELETE" -> if (!id.isNullOrBlank()) memoryRepository.delete(id)
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    fun regenerate() {
        viewModelScope.launch {
            val conversationId = _activeId.value ?: return@launch
            val conversation = store.snapshot().firstOrNull { it.id == conversationId } ?: return@launch
            val index = conversation.messages.indexOfLast { it.role == "user" }
            if (index < 0) return@launch
            val userMessage = conversation.messages[index]
            val oldAnswer = conversation.messages.drop(index + 1).firstOrNull { it.role == "assistant" }
            deleteGeneratedAttachments(conversation.messages.drop(index))
            store.upsert(
                conversation.copy(
                    messages = conversation.messages.subList(0, index),
                    updatedAt = System.currentTimeMillis()
                )
            )
            sendMessage(
                userMessage.content,
                userMessage.attachments,
                webOverride = oldAnswer?.webEnabled ?: settings.value.webEnabled,
                reasoningOverride = oldAnswer?.reasoningEnabled ?: settings.value.reasoningEnabled
            )
        }
    }

    fun regenerateFrom(messageId: String) {
        viewModelScope.launch {
            val conversationId = _activeId.value ?: return@launch
            val conversation = store.snapshot().firstOrNull { it.id == conversationId } ?: return@launch
            val index = conversation.messages.indexOfFirst { it.id == messageId }
            if (index < 0 || conversation.messages[index].role != "user") return@launch
            val userMessage = conversation.messages[index]
            val oldAnswer = conversation.messages.drop(index + 1).firstOrNull { it.role == "assistant" }
            deleteGeneratedAttachments(conversation.messages.drop(index))
            store.upsert(
                conversation.copy(
                    messages = conversation.messages.subList(0, index),
                    updatedAt = System.currentTimeMillis()
                )
            )
            sendMessage(
                userMessage.content,
                userMessage.attachments,
                webOverride = oldAnswer?.webEnabled ?: settings.value.webEnabled,
                reasoningOverride = oldAnswer?.reasoningEnabled ?: settings.value.reasoningEnabled
            )
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            val conversationId = _activeId.value ?: return@launch
            val conversation = store.snapshot().firstOrNull { it.id == conversationId } ?: return@launch
            ttsManager.deleteCache(messageId)
            conversation.messages.firstOrNull { it.id == messageId }?.let {
                deleteGeneratedAttachments(listOf(it))
            }
            store.upsert(
                conversation.copy(
                    messages = conversation.messages.filterNot { it.id == messageId },
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun editMessage(messageId: String, newContent: String) {
        viewModelScope.launch {
            val conversationId = _activeId.value ?: return@launch
            val conversation = store.snapshot().firstOrNull { it.id == conversationId } ?: return@launch
            val messages = conversation.messages.map {
                if (it.id == messageId) it.copy(content = newContent) else it
            }
            ttsManager.deleteCache(messageId)
            store.upsert(conversation.copy(messages = messages, updatedAt = System.currentTimeMillis()))
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settingsRepo.update(transform)
            ProactiveScheduler.reconcile(getApplication())
        }
    }

    private fun ttsConfig(value: AppSettings) = TtsConfig(
        provider = value.ttsProvider,
        baseUrl = value.ttsBaseUrl,
        apiKey = value.ttsApiKey,
        model = value.ttsModel,
        voiceId = value.ttsVoiceId
    )

    private fun searchConfig(value: AppSettings) = SearchConfig(
        provider = value.searchProvider,
        baseUrl = value.searchBaseUrl,
        apiKey = value.searchApiKey,
        engine = value.searchEngine
    )

    private fun deleteGeneratedAttachments(messages: List<Message>) {
        messages.flatMap { it.attachments }
            .filter { it.type == "image" && it.generationId.isNotBlank() }
            .forEach { imageGenerationService.delete(it.uri) }
    }

    private fun imageError(error: ImageGenerationException): String = when (error.code) {
        "PC_UNREACHABLE" -> "无法连接 PC；请检查电脑地址、局域网和防火墙"
        "ADDRESS_MISSING", "ADDRESS_INVALID" -> error.message
        "FEATURE_DISABLED" -> "AI 生图功能尚未启用"
        "MODEL_FILES_MISSING", "MODEL_NOT_LOADED" -> "PC 服务已启动，但模型没有正确加载"
        "GPU_OUT_OF_MEMORY" -> "GPU 显存不足；请降低图片尺寸后重试"
        "TIMEOUT" -> "图片生成超时"
        "IMAGE_SAVE_FAILED", "IMAGE_FILE_MISSING" -> "图片保存失败"
        "CANCELLED" -> "图片生成已取消"
        "INVALID_RESPONSE", "INVALID_IMAGE" -> "生图服务返回数据异常"
        "INFERENCE_FAILED" -> "模型推理失败：${error.message}"
        else -> error.message
    }

    private fun ttsError(error: Throwable): String {
        val message = error.message.orEmpty()
        val causes = generateSequence(error as Throwable?) { it.cause }.toList()
        return when {
            message.contains("CLEARTEXT", ignoreCase = true) -> "Android 阻止 HTTP 请求"
            message.contains("TTS HTTP 400") -> "TTS HTTP 400"
            message.contains("TTS HTTP 401") -> "TTS API Key 无效"
            message.contains("TTS HTTP 403") -> "TTS API 无权限"
            message.contains("TTS HTTP 404") -> "TTS HTTP 404"
            message.contains("TTS HTTP 429") -> "TTS 请求过于频繁"
            Regex("TTS HTTP 5\\d\\d").containsMatchIn(message) ->
                Regex("TTS HTTP 5\\d\\d").find(message)?.value ?: "TTS 服务错误"
            message.contains("返回格式错误") || message.contains("返回音频为空") ->
                "TTS 返回格式错误"
            message.contains("WAV 保存失败") -> "WAV 保存失败"
            message.contains("音频解码失败") -> "音频解码失败"
            message.contains("音频播放失败") -> "音频播放失败"
            causes.any { it is io.ktor.client.plugins.HttpRequestTimeoutException } ||
                message.contains("timeout", ignoreCase = true) -> "TTS 请求超时"
            causes.any {
                it is java.net.UnknownHostException ||
                    it is java.net.ConnectException ||
                    it is java.net.NoRouteToHostException ||
                    it is java.net.SocketException
            } -> "无法连接 TTS 服务"
            message.startsWith("请先") || message.contains("格式错误") ||
                message.contains("可朗读") -> message
            else -> "音频播放失败"
        }
    }

    private fun userFacingError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("EMPTY_RESPONSE") -> "API 返回内容为空"
            message.contains("HTTP 401") -> "API Key 无效"
            message.contains("HTTP 403") -> "API Key 无权限访问当前模型"
            message.contains("HTTP 404") -> "接口地址或模型不存在，请检查 Base URL 和 Model ID"
            message.contains("HTTP 429") -> "请求过于频繁，请稍后重试"
            Regex("HTTP 5\\d\\d").containsMatchIn(message) -> "API 服务暂时不可用，请稍后重试"
            error is io.ktor.client.plugins.HttpRequestTimeoutException ||
                message.contains("timeout", ignoreCase = true) -> "请求超时，请稍后重试"
            error is java.net.UnknownHostException || error is java.net.ConnectException ||
                error is java.net.SocketException -> "网络连接失败"
            message.contains("URL", ignoreCase = true) || error is IllegalArgumentException ->
                "Base URL 格式错误"
            else -> "请求失败，请检查网络和 API 配置"
        }
    }

    override fun onCleared() {
        super.onCleared()
        streamingJob?.cancel()
        ttsJob?.cancel()
        imageGenerationJob?.cancel()
        client.close()
        searchManager.close()
        ttsManager.close()
        memoryRepository.close()
        imageGenerationService.close()
    }
}
