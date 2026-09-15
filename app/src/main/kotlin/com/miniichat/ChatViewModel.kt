package com.miniichat

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miniichat.api.ChatGateway
import com.miniichat.api.ChatMessage
import com.miniichat.api.ChatRouteException
import com.miniichat.api.LlmClient
import com.miniichat.api.ModelRoute
import com.miniichat.api.ModelRouter
import com.miniichat.api.OpenAiEndpointResolver
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
import com.miniichat.data.MessageDeliveryStatus
import com.miniichat.data.ProviderAuthMode
import com.miniichat.data.ProviderConfig
import com.miniichat.data.ProviderStore
import com.miniichat.data.SearchSourceStore
import com.miniichat.data.SettingsRepository
import com.miniichat.data.SourceReference
import com.miniichat.error.AppError
import com.miniichat.error.AppErrorClassifier
import com.miniichat.error.AppErrorContext
import com.miniichat.error.AppErrorStore
import com.miniichat.error.AppErrorType
import com.miniichat.error.ErrorArea
import com.miniichat.error.ErrorOperation
import com.miniichat.memory.LongTermMemory
import com.miniichat.memory.MemoryCategories
import com.miniichat.memory.MemoryRepository
import com.miniichat.proactive.ProactiveScheduler
import com.miniichat.search.SearchFailure
import com.miniichat.search.SearchFailureKind
import com.miniichat.search.SearchManager
import com.miniichat.tts.TtsConfig
import com.miniichat.tts.TtsManager
import com.miniichat.tts.TtsPlaybackState
import com.miniichat.util.PromptVars
import com.miniichat.util.newId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File

data class StreamingOverlay(
    val messageId: String,
    val content: String,
    val reasoning: String
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private companion object {
        const val TTS_TAG = "MaidManagerTts"
    }

    private val startupCutoff = System.currentTimeMillis()
    private val store = ConversationStore(app)
    val settingsRepo = SettingsRepository(app)
    val providerStore = ProviderStore(app)
    val assistantStore = AssistantStore(app)
    private val memoryRepository = MemoryRepository(app)
    private val client = LlmClient()
    private val chatGateway = ChatGateway(client)
    private val handoffGenerator = ConversationHandoffGenerator(client)
    private val searchManager = SearchManager()
    private val searchSourceStore = SearchSourceStore(app)
    private val errorStore = AppErrorStore(app)
    private val ttsManager = TtsManager(app)
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
    val searchSources = searchSourceStore.sourcesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchSourceStore.defaultSources())
    val errors = errorStore.errors
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _streamingOverlay = MutableStateFlow<StreamingOverlay?>(null)
    val streamingOverlay: StateFlow<StreamingOverlay?> = _streamingOverlay.asStateFlow()
    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()
    private val _error = MutableStateFlow<AppError?>(null)
    val error: StateFlow<AppError?> = _error.asStateFlow()
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    private val _fetchingModelsFor = MutableStateFlow<String?>(null)
    val fetchingModelsFor: StateFlow<String?> = _fetchingModelsFor.asStateFlow()
    private val _chatDataBusy = MutableStateFlow(false)
    val chatDataBusy: StateFlow<Boolean> = _chatDataBusy.asStateFlow()
    private val _chatDataPhase = MutableStateFlow<String?>(null)
    val chatDataPhase: StateFlow<String?> = _chatDataPhase.asStateFlow()

    private var streamingJob: Job? = null
    private var activeRequestToken: String? = null
    private var ttsJob: Job? = null

    init {
        viewModelScope.launch { store.recoverInterruptedMessages(startupCutoff) }
        viewModelScope.launch { memoryRepository.refresh() }
        viewModelScope.launch {
            val current = settingsRepo.settings.first()
            searchSourceStore.migrateLegacy(
                com.miniichat.search.SearchConfig(
                    provider = current.searchProvider,
                    baseUrl = current.searchBaseUrl,
                    apiKey = current.searchApiKey,
                    engine = current.searchEngine
                )
            )
        }
        viewModelScope.launch { ProactiveScheduler.reconcile(app) }
        viewModelScope.launch {
            ttsManager.errors.collect { error ->
                Log.e(
                    TTS_TAG,
                    "Asynchronous TTS playback failed type=${error.javaClass.simpleName}"
                )
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
        if (_activeId.value == id) stopStreaming()
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
        activeRequestToken = null
        streamingJob?.cancel()
        streamingJob = null
        _streamingOverlay.value = null
        _isStreaming.value = false
        stopTts()
    }

    fun clearError() { _error.value = null }
    fun clearToast() { _toast.value = null }
    fun deleteError(id: String) {
        viewModelScope.launch {
            runCatching { errorStore.delete(id) }.onFailure {
                Log.w("MaidManagerErrors", "Error deletion failed: ${it::class.java.name}")
            }
        }
    }
    fun clearErrorHistory() {
        viewModelScope.launch {
            runCatching { errorStore.clear() }.onFailure {
                Log.w("MaidManagerErrors", "Error history clear failed: ${it::class.java.name}")
            }
        }
    }

    fun saveImageAttachment(localPath: String, destination: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val input = if (localPath.startsWith("content://")) {
                        getApplication<Application>().contentResolver.openInputStream(Uri.parse(localPath))
                    } else {
                        File(localPath).takeIf(File::isFile)?.inputStream()
                    } ?: error("本地图片文件不存在")
                    val output = getApplication<Application>().contentResolver.openOutputStream(destination)
                        ?: error("无法打开保存位置")
                    input.use { source -> output.use { target -> source.copyTo(target) } }
                }
                _toast.value = "图片已保存"
            } catch (error: Exception) {
                presentThrowable(
                    error,
                    AppErrorContext(ErrorArea.STORAGE, ErrorOperation.EXPORT_DATA)
                )
            }
        }
    }
    fun activeProvider(): ProviderConfig? =
        providers.value.firstOrNull { it.id == settings.value.activeProviderId && it.enabled }
            ?: providers.value.filter { it.enabled }.minByOrNull { it.priority }

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
                    var applied = false
                    providerStore.update(providerId) { latest ->
                        if (latest.baseUrl == provider.baseUrl &&
                            latest.apiKey == provider.apiKey &&
                            latest.authMode == provider.authMode
                        ) {
                            applied = true
                            latest.copy(models = (latest.models + models).distinct().sorted())
                        } else {
                            latest
                        }
                    }
                    _toast.value = if (applied) {
                        "已获取 ${models.size} 个模型"
                    } else {
                        "服务配置已变化，请重新获取模型"
                    }
                }
            } catch (error: Exception) {
                presentThrowable(
                    error,
                    AppErrorContext(
                        area = ErrorArea.MODEL,
                        operation = ErrorOperation.LIST_MODELS,
                        providerBaseUrl = provider.baseUrl
                    )
                )
            } finally {
                _fetchingModelsFor.value = null
            }
        }
    }

    fun addManualModel(providerId: String, model: String) {
        viewModelScope.launch {
            val trimmed = model.trim()
            if (trimmed.isEmpty()) return@launch
            providerStore.update(providerId) { provider ->
                provider.copy(models = (provider.models + trimmed).distinct().sorted())
            }
        }
    }

    fun removeModel(providerId: String, model: String) {
        viewModelScope.launch {
            val updated = providerStore.update(providerId) { provider ->
                provider.copy(
                    models = provider.models - model,
                    fallbackModels = provider.fallbackModels - model
                )
            } ?: return@launch
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
                presentThrowable(
                    error,
                    AppErrorContext(ErrorArea.IMPORT_EXPORT, ErrorOperation.EXPORT_DATA)
                )
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
                presentThrowable(
                    error,
                    AppErrorContext(ErrorArea.IMPORT_EXPORT, ErrorOperation.IMPORT_DATA)
                )
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
                val route = ModelRouter.buildRoutes(
                    providers = providers.value,
                    activeProviderId = settings.value.activeProviderId,
                    activeModel = settings.value.activeModel,
                    preferredProviderId = persona?.preferredProviderId,
                    preferredModel = persona?.preferredModel,
                    automaticFallback = false
                ).firstOrNull() ?: error("请先完成模型服务配置")
                val handoff = handoffGenerator.generate(
                    conversation = conversation,
                    persona = persona,
                    memories = memories.value,
                    provider = route.provider,
                    modelId = route.modelId,
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
                presentThrowable(
                    error,
                    AppErrorContext(
                        area = ErrorArea.IMPORT_EXPORT,
                        operation = ErrorOperation.EXPORT_DATA
                    )
                )
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
                presentThrowable(
                    error,
                    AppErrorContext(ErrorArea.IMPORT_EXPORT, ErrorOperation.IMPORT_DATA)
                )
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
                Log.e(
                    TTS_TAG,
                    "TTS request/playback failed type=${error.javaClass.simpleName}"
                )
                _toast.value = ttsError(error)
            }
        }
    }

    fun stopTts() {
        ttsJob?.cancel()
        ttsJob = null
        ttsManager.stop()
    }

    fun reportPhotoFailure(reason: String) {
        viewModelScope.launch {
            presentThrowable(com.miniichat.data.ImageInputException(reason),
                AppErrorContext(ErrorArea.STORAGE, ErrorOperation.READ_LOCAL_DATA))
        }
    }

    fun sendMessage(
        text: String,
        attachments: List<Attachment> = emptyList(),
        webOverride: Boolean? = null,
        reasoningOverride: Boolean? = null
    ): Boolean {
        if (_isStreaming.value || streamingJob?.isActive == true) return false
        _error.value = null
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachments.isEmpty()) return false

        val current = settings.value
        val linkedPersonaId = _activeId.value
            ?.let { id -> conversations.value.firstOrNull { it.id == id } }
            ?.assistantId?.takeIf { it.isNotBlank() }
        val assistant = assistants.value.firstOrNull {
            it.id == (linkedPersonaId ?: current.activeAssistantId)
        }
        val requestedRoutes = ModelRouter.buildRoutes(
            providers = providers.value,
            activeProviderId = current.activeProviderId,
            activeModel = current.activeModel,
            preferredProviderId = assistant?.preferredProviderId,
            preferredModel = assistant?.preferredModel,
            automaticFallback = current.automaticModelFallbackEnabled
        )
        if (requestedRoutes.isEmpty()) {
            presentValidation(
                AppErrorType.CONFIGURATION_MISSING,
                AppErrorContext(ErrorArea.MODEL, ErrorOperation.SEND_MESSAGE),
                message = "请先添加模型服务并选择模型。"
            )
            return false
        }

        val skippedRoutes = requestedRoutes.filterNot(::isUsableRoute)
        val routes = requestedRoutes.filter(::isUsableRoute)
        if (routes.isEmpty()) {
            val first = requestedRoutes.first()
            val missingKey = first.provider.authMode == ProviderAuthMode.BEARER &&
                first.provider.apiKey.isBlank()
            presentValidation(
                if (missingKey) AppErrorType.CONFIGURATION_MISSING else AppErrorType.INVALID_ADDRESS,
                AppErrorContext(
                    ErrorArea.MODEL,
                    ErrorOperation.SEND_MESSAGE,
                    first.provider.baseUrl,
                    first.modelId
                ),
                message = if (missingKey) "请填写 ${first.provider.name} 的 API 密钥。"
                else "${first.provider.name} 的 Base URL 无法使用。"
            )
            return false
        }
        if (skippedRoutes.isNotEmpty()) {
            skippedRoutes.forEach { skipped ->
                recordSkippedRoute(skipped)
            }
        }

        val initialRoute = routes.first()
        val model = initialRoute.modelId
        val provider = initialRoute.provider

        val webEnabled = webOverride ?: current.webEnabled
        val reasoningEnabled = reasoningOverride ?: current.reasoningEnabled
        val temperature = assistant?.temperature ?: current.temperature
        val systemTemplate = assistant?.systemPrompt?.takeIf { it.isNotBlank() }
            ?: current.systemPrompt

        val requestToken = newId()
        activeRequestToken = requestToken
        _isStreaming.value = true
        val requestJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
          try {
            val activeId = _activeId.value ?: newId().also { _activeId.value = it }
            val title = trimmed.take(30).replace("\n", " ").ifBlank { "照片对话" }
            val personaId = assistant?.id ?: current.activeAssistantId
            val userMessage = Message(
                id = newId(),
                role = "user",
                content = trimmed,
                attachments = attachments,
                modelId = model,
                providerId = provider.id,
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
                providerId = provider.id,
                personaId = personaId,
                webEnabled = webEnabled,
                reasoningEnabled = reasoningEnabled,
                deliveryStatus = MessageDeliveryStatus.STREAMING
            )
            val updated = store.updateConversation(
                id = activeId,
                createIfMissing = {
                    Conversation(id = activeId, title = title, assistantId = personaId)
                }
            ) { conversation ->
                conversation.copy(
                    title = if (conversation.messages.isEmpty()) title else conversation.title,
                    messages = conversation.messages + userMessage + placeholder,
                    updatedAt = System.currentTimeMillis()
                )
            } ?: error("无法创建聊天")

            launch {
                val answer = StringBuilder()
                val reasoning = StringBuilder()
                var sources: List<SourceReference> = emptyList()
                var completedNormally = false
                var lastFlush = 0L
                var usedRoute = initialRoute
                var lastErrorId: String? = null
                var routeLabels: List<String> = emptyList()
                if (activeRequestToken == requestToken) {
                    _streamingOverlay.value = StreamingOverlay(answerId, "", "")
                }
                try {
                    val supportingMessages = mutableListOf<ChatMessage>()
                    if (updated.handoffContext.isNotBlank()) {
                        supportingMessages += ChatMessage("system", updated.handoffContext)
                    }

                    if (current.memoryEnabled) {
                        val enabledMemories = memoryRepository.enabled(50)
                        if (enabledMemories.isNotEmpty()) {
                            supportingMessages += ChatMessage("system", formatMemories(enabledMemories))
                        }
                    }

                    if (webEnabled) {
                        try {
                            val outcome = searchManager.search(searchSources.value, trimmed)
                            outcome.failures.forEach { recordSearchFailure(it) }
                            if (outcome.results.isNotEmpty()) {
                                sources = searchManager.asSources(outcome.results)
                                supportingMessages += ChatMessage(
                                    "system",
                                    searchManager.formatForModel(outcome.results)
                                )
                                updateAssistant(activeId, answerId, sources = sources)
                                if (outcome.isPartialSuccess) {
                                    _toast.value = "部分搜索源未完成，已合并其余结果"
                                }
                            } else if (searchManager.shouldSearch(trimmed)) {
                                _toast.value = if (outcome.failures.isEmpty()) {
                                    "联网搜索未返回结果，已使用模型自身知识回答"
                                } else {
                                    "联网搜索未完成，已使用模型自身知识回答；详情已记录"
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            persistErrorSafely(
                                AppErrorClassifier.classify(
                                    error,
                                    AppErrorContext(ErrorArea.SEARCH, ErrorOperation.WEB_SEARCH)
                                )
                            )
                            _toast.value = "联网搜索未完成，已使用模型自身知识回答；详情已记录"
                        }
                    }

                    val imageStore = com.miniichat.data.LocalImageStore(getApplication())
                    var imageRequestSize = 0L
                    val historyMessages = updated.messages
                        .filterNot {
                            it.id == answerId ||
                                (it.role == "assistant" &&
                                    it.deliveryStatus != MessageDeliveryStatus.SUCCEEDED) ||
                                (it.role == "assistant" && it.content.isBlank())
                        }
                        .map { message ->
                            val photos = if (message.role == "user") imageStore.dataUrls(message.attachments)
                                else emptyList()
                            imageRequestSize += photos.sumOf { it.length.toLong() }
                            if (imageRequestSize > 16L * 1024 * 1024) {
                                throw com.miniichat.data.ImageInputException("这段对话的照片较多，请新建对话后继续发送。")
                            }
                            ChatMessage(message.role, message.content, photos)
                        }

                    val (successfulRoute, attempts) = chatGateway.stream(
                        routes = routes,
                        settings = current.copy(temperature = temperature),
                        reasoningEnabled = reasoningEnabled,
                        reasoningEffort = current.reasoningEffort,
                        messagesForRoute = { route ->
                            buildList {
                                val renderedSystem = PromptVars.render(
                                    template = systemTemplate,
                                    model = route.modelId,
                                    provider = route.provider.name,
                                    assistant = assistant?.displayName ?: "女仆"
                                )
                                if (renderedSystem.isNotBlank()) {
                                    add(ChatMessage("system", renderedSystem))
                                }
                                addAll(supportingMessages)
                                assistant?.conversationName?.trim()?.takeIf { it.isNotBlank() }?.let { name ->
                                    add(ChatMessage("system", "用户为你设置的对话名字是「$name」，在当前对话中使用这个名字。"))
                                }
                                if (reasoningEnabled &&
                                    !client.supportsNativeReasoning(route.provider, route.modelId)
                                ) {
                                    add(
                                        ChatMessage(
                                            "system",
                                            "请先充分分析问题，再给出清晰、简洁的最终答案。不要展示隐藏推理链，只提供必要的高层次说明。"
                                        )
                                    )
                                }
                                addAll(historyMessages)
                            }
                        },
                        onAttempt = { route, _, _ ->
                            usedRoute = route
                            if (!route.primary) {
                                _toast.value = "正在尝试备用模型：${route.provider.name} · ${route.modelId}"
                            }
                            updateAssistant(
                                activeId,
                                answerId,
                                providerId = route.provider.id,
                                modelId = route.modelId
                            )
                        },
                        onFailure = { attempt, willContinue, error ->
                            val routeProvider = providers.value.firstOrNull { it.id == attempt.providerId }
                            val recorded = persistErrorSafely(
                                AppErrorClassifier.classify(
                                    error,
                                    AppErrorContext(
                                    area = ErrorArea.CHAT,
                                    operation = ErrorOperation.SEND_MESSAGE,
                                    providerBaseUrl = routeProvider?.baseUrl,
                                    modelId = attempt.modelId
                                    )
                                )
                            )
                            lastErrorId = recorded.id
                            if (!willContinue) _error.value = recorded
                        },
                        onEvent = { route, event ->
                            usedRoute = route
                            event.reasoning?.let(reasoning::append)
                            event.content?.let(answer::append)
                            if (activeRequestToken == requestToken) {
                                _streamingOverlay.value = StreamingOverlay(
                                    answerId,
                                    answer.toString(),
                                    reasoning.toString()
                                )
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastFlush >= 250L) {
                                updateAssistant(
                                    activeId,
                                    answerId,
                                    content = answer.toString(),
                                    reasoning = reasoning.toString(),
                                    sources = sources,
                                    providerId = route.provider.id,
                                    modelId = route.modelId
                                )
                                lastFlush = now
                            }
                        }
                    )
                    usedRoute = successfulRoute
                    routeLabels = attempts.map { it.displayName }
                    completedNormally = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: ChatRouteException) {
                    routeLabels = error.attempts.map { it.displayName }
                    if (_error.value == null) {
                        val recorded = presentThrowable(
                            error.cause ?: error,
                            AppErrorContext(
                                ErrorArea.CHAT,
                                ErrorOperation.SEND_MESSAGE,
                                usedRoute.provider.baseUrl,
                                usedRoute.modelId
                            )
                        )
                        lastErrorId = recorded.id
                    }
                } catch (error: Exception) {
                    val recorded = presentThrowable(
                        error,
                        AppErrorContext(
                            ErrorArea.CHAT,
                            ErrorOperation.SEND_MESSAGE,
                            usedRoute.provider.baseUrl,
                            usedRoute.modelId
                        )
                    )
                    lastErrorId = recorded.id
                } finally {
                    val summary = if (reasoningEnabled && reasoning.isEmpty() && answer.isNotEmpty()) {
                        "已完成深度思考"
                    } else ""
                    withContext(NonCancellable) {
                        if (answer.isNotEmpty() || reasoning.isNotEmpty()) {
                            updateAssistant(
                                activeId,
                                answerId,
                                content = answer.toString(),
                                reasoning = reasoning.toString(),
                                reasoningSummary = summary,
                                sources = sources,
                                providerId = usedRoute.provider.id,
                                modelId = usedRoute.modelId,
                                deliveryStatus = if (completedNormally) {
                                    MessageDeliveryStatus.SUCCEEDED
                                } else {
                                    MessageDeliveryStatus.PARTIAL_FAILED
                                },
                                errorReportId = lastErrorId,
                                attemptedRoutes = routeLabels
                            )
                        } else if (!completedNormally) {
                            removeMessage(activeId, answerId)
                        }
                    }
                    if (completedNormally && answer.isNotEmpty() &&
                        activeRequestToken == requestToken
                    ) {
                        if (current.ttsAutoRead) {
                            playMessage(
                                placeholder.copy(
                                    content = answer.toString(),
                                    reasoningText = reasoning.toString(),
                                    reasoningSummary = summary,
                                    sources = sources,
                                    providerId = usedRoute.provider.id,
                                    modelId = usedRoute.modelId,
                                    deliveryStatus = MessageDeliveryStatus.SUCCEEDED,
                                    attemptedRoutes = routeLabels
                                )
                            )
                        }
                        if (current.memoryEnabled && current.autoMemoryEnabled) {
                            scheduleMemoryExtraction(
                                trimmed,
                                answer.toString(),
                                usedRoute.provider,
                                usedRoute.modelId
                            )
                        }
                    }
                }
            }.join()
          } catch (cancelled: CancellationException) {
              throw cancelled
          } catch (error: Exception) {
              if (activeRequestToken == requestToken) {
                  presentThrowable(
                      error,
                      AppErrorContext(
                          ErrorArea.CHAT,
                          ErrorOperation.SEND_MESSAGE,
                          initialRoute.provider.baseUrl,
                          initialRoute.modelId
                      )
                  )
              }
          } finally {
              if (activeRequestToken == requestToken) {
                  activeRequestToken = null
                  _streamingOverlay.value = null
                  _isStreaming.value = false
                  streamingJob = null
              }
          }
        }
        streamingJob = requestJob
        requestJob.start()
        return true
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
        ttsCachePath: String? = null,
        providerId: String? = null,
        modelId: String? = null,
        deliveryStatus: MessageDeliveryStatus? = null,
        errorReportId: String? = null,
        attemptedRoutes: List<String>? = null
    ) {
        store.updateConversation(conversationId) { conversation ->
            val messages = conversation.messages.map { message ->
                if (message.id != messageId) message else message.copy(
                    content = content ?: message.content,
                    reasoningText = reasoning ?: message.reasoningText,
                    reasoningSummary = reasoningSummary ?: message.reasoningSummary,
                    sources = sources ?: message.sources,
                    ttsCachePath = ttsCachePath ?: message.ttsCachePath,
                    providerId = providerId ?: message.providerId,
                    modelId = modelId ?: message.modelId,
                    deliveryStatus = deliveryStatus ?: message.deliveryStatus,
                    errorReportId = errorReportId ?: message.errorReportId,
                    attemptedRoutes = attemptedRoutes ?: message.attemptedRoutes
                )
            }
            conversation.copy(messages = messages, updatedAt = System.currentTimeMillis())
        }
    }

    private suspend fun removeMessage(conversationId: String, messageId: String) {
        store.updateConversation(conversationId, keepPreviousSnapshot = false) { conversation ->
            conversation.copy(
                messages = conversation.messages.filterNot { it.id == messageId },
                updatedAt = System.currentTimeMillis()
            )
        }
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Automatic extraction is best-effort: record it without interrupting chat.
                persistErrorSafely(
                    AppErrorClassifier.classify(
                        error,
                        AppErrorContext(
                            ErrorArea.STORAGE,
                            ErrorOperation.EXTRACT_MEMORY,
                            provider.baseUrl,
                            model
                        )
                    )
                )
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
        if (_isStreaming.value) return
        viewModelScope.launch {
            val conversationId = _activeId.value ?: return@launch
            var userMessage: Message? = null
            var oldAnswer: Message? = null
            var removedMessages: List<Message> = emptyList()
            store.updateConversation(conversationId, keepPreviousSnapshot = false) { conversation ->
                val index = conversation.messages.indexOfLast { it.role == "user" }
                if (index < 0) return@updateConversation conversation
                userMessage = conversation.messages[index]
                oldAnswer = conversation.messages.drop(index + 1)
                    .firstOrNull { it.role == "assistant" }
                removedMessages = conversation.messages.drop(index)
                conversation.copy(
                    messages = conversation.messages.take(index),
                    updatedAt = System.currentTimeMillis()
                )
            }
            val source = userMessage ?: return@launch
            deleteGeneratedAttachments(removedMessages)
            sendMessage(
                source.content,
                source.attachments,
                webOverride = oldAnswer?.webEnabled ?: settings.value.webEnabled,
                reasoningOverride = oldAnswer?.reasoningEnabled ?: settings.value.reasoningEnabled
            )
        }
    }

    fun regenerateFrom(messageId: String) {
        if (_isStreaming.value) return
        viewModelScope.launch {
            val conversationId = _activeId.value ?: return@launch
            var userMessage: Message? = null
            var oldAnswer: Message? = null
            var removedMessages: List<Message> = emptyList()
            store.updateConversation(conversationId, keepPreviousSnapshot = false) { conversation ->
                val index = conversation.messages.indexOfFirst { it.id == messageId }
                if (index < 0 || conversation.messages[index].role != "user") {
                    return@updateConversation conversation
                }
                userMessage = conversation.messages[index]
                oldAnswer = conversation.messages.drop(index + 1)
                    .firstOrNull { it.role == "assistant" }
                removedMessages = conversation.messages.drop(index)
                conversation.copy(
                    messages = conversation.messages.take(index),
                    updatedAt = System.currentTimeMillis()
                )
            }
            val source = userMessage ?: return@launch
            deleteGeneratedAttachments(removedMessages)
            sendMessage(
                source.content,
                source.attachments,
                webOverride = oldAnswer?.webEnabled ?: settings.value.webEnabled,
                reasoningOverride = oldAnswer?.reasoningEnabled ?: settings.value.reasoningEnabled
            )
        }
    }

    fun deleteMessage(messageId: String) {
        if (_isStreaming.value) return
        viewModelScope.launch {
            val conversationId = _activeId.value ?: return@launch
            var removed: Message? = null
            store.updateConversation(conversationId, keepPreviousSnapshot = false) { conversation ->
                removed = conversation.messages.firstOrNull { it.id == messageId }
                conversation.copy(
                    messages = conversation.messages.filterNot { it.id == messageId },
                    updatedAt = System.currentTimeMillis()
                )
            }
            if (removed == null) return@launch
            ttsManager.deleteCache(messageId)
            deleteGeneratedAttachments(listOfNotNull(removed))
        }
    }

    fun editMessage(messageId: String, newContent: String) {
        if (_isStreaming.value) return
        viewModelScope.launch {
            val conversationId = _activeId.value ?: return@launch
            store.updateConversation(conversationId, keepPreviousSnapshot = false) { conversation ->
                val messages = conversation.messages.map {
                    if (it.id == messageId) it.copy(content = newContent) else it
                }
                conversation.copy(messages = messages, updatedAt = System.currentTimeMillis())
            }
            ttsManager.deleteCache(messageId)
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

    private fun isUsableRoute(route: ModelRoute): Boolean {
        if (route.provider.authMode == ProviderAuthMode.BEARER && route.provider.apiKey.isBlank()) {
            return false
        }
        return runCatching { OpenAiEndpointResolver.resolve(route.provider.baseUrl) }.isSuccess
    }

    private fun recordSkippedRoute(route: ModelRoute) {
        val missingKey = route.provider.authMode == ProviderAuthMode.BEARER &&
            route.provider.apiKey.isBlank()
        val error = AppErrorClassifier.validation(
            type = if (missingKey) AppErrorType.CONFIGURATION_MISSING else AppErrorType.INVALID_ADDRESS,
            context = AppErrorContext(
                ErrorArea.MODEL,
                ErrorOperation.SEND_MESSAGE,
                route.provider.baseUrl,
                route.modelId
            ),
            message = if (missingKey) {
                "${route.provider.name} 未填写 API 密钥，已跳过这条备用路由。"
            } else {
                "${route.provider.name} 的 Base URL 无法使用，已跳过这条备用路由。"
            }
        )
        viewModelScope.launch { persistErrorSafely(error) }
    }

    private suspend fun recordSearchFailure(failure: SearchFailure) {
        val context = AppErrorContext(
            ErrorArea.SEARCH,
            ErrorOperation.WEB_SEARCH,
            providerBaseUrl = failure.providerBaseUrl
        )
        val error = when (failure.kind) {
            SearchFailureKind.CONFIGURATION -> AppErrorClassifier.validation(
                AppErrorType.CONFIGURATION_MISSING,
                context,
                message = "${failure.sourceName} 的配置未完成。"
            )
            SearchFailureKind.HTTP -> AppErrorClassifier.http(failure.httpStatus ?: 500, context)
            SearchFailureKind.TIMEOUT -> AppErrorClassifier.classify(
                java.net.SocketTimeoutException(),
                context
            )
            SearchFailureKind.NETWORK -> AppErrorClassifier.classify(
                java.io.IOException(),
                context
            )
            SearchFailureKind.HOST_NOT_FOUND -> AppErrorClassifier.classify(
                java.net.UnknownHostException(),
                context
            )
            SearchFailureKind.CONNECTION -> AppErrorClassifier.classify(
                java.net.ConnectException(),
                context
            )
            SearchFailureKind.TLS -> AppErrorClassifier.classify(
                javax.net.ssl.SSLException(""),
                context
            )
            SearchFailureKind.CLEARTEXT -> AppErrorClassifier.classify(
                java.io.IOException("CLEARTEXT communication not permitted"),
                context
            )
            SearchFailureKind.INVALID_RESPONSE -> AppErrorClassifier.classify(
                com.miniichat.api.LlmProtocolException("search response"),
                context
            )
            SearchFailureKind.UNKNOWN -> AppErrorClassifier.classify(
                IllegalStateException(),
                context
            )
        }
        persistErrorSafely(
            error.copy(userMessage = "${failure.sourceName}：${error.userMessage}")
        )
    }

    private fun presentValidation(
        type: AppErrorType,
        context: AppErrorContext,
        message: String
    ) {
        val error = AppErrorClassifier.validation(type, context, message = message)
        _error.value = error
        viewModelScope.launch { persistErrorSafely(error) }
    }

    private suspend fun presentThrowable(
        throwable: Throwable,
        context: AppErrorContext
    ): AppError {
        val error = persistErrorSafely(AppErrorClassifier.classify(throwable, context))
        _error.value = error
        return error
    }

    /** Diagnostics are secondary: a storage failure must never cancel model fallback or chat. */
    private suspend fun persistErrorSafely(error: AppError): AppError {
        return try {
            errorStore.record(error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (storageError: Exception) {
            Log.w(
                "MaidManagerErrors",
                "Error report persistence failed: ${storageError::class.java.name}"
            )
            error
        }
    }

    private fun deleteGeneratedAttachments(messages: List<Message>) {
        val root = runCatching {
            File(getApplication<Application>().filesDir, "generated_images").canonicalFile
        }.getOrNull() ?: return
        messages.flatMap { it.attachments }
            .filter { it.type == "image" && it.generationId.isNotBlank() }
            .mapNotNull { runCatching { File(it.uri).canonicalFile }.getOrNull() }
            .filter { it.path.startsWith(root.path + File.separator) }
            .forEach { runCatching { it.delete() } }
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

    override fun onCleared() {
        super.onCleared()
        streamingJob?.cancel()
        ttsJob?.cancel()
        client.close()
        searchManager.close()
        ttsManager.close()
        memoryRepository.close()
    }
}
