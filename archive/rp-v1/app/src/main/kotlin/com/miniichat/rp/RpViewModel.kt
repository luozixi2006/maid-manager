package com.miniichat.rp

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.miniichat.api.ChatMessage
import com.miniichat.api.LlmClient
import com.miniichat.api.LlmHttpException
import com.miniichat.data.AppSettings
import com.miniichat.data.ProviderConfig
import com.miniichat.data.ProviderStore
import com.miniichat.data.SettingsRepository
import com.miniichat.proactive.ProactiveScheduler
import com.miniichat.util.newId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private data class PendingWorldAction(
    val worldId: String,
    val action: String,
    val expandUserPlot: Boolean,
    val forcedLocationId: String?
)

private data class PendingCreation(
    val nameHint: String,
    val idea: String,
    val onCreated: (String) -> Unit
)

data class RpCreationDraft(val nameHint: String = "", val idea: String = "")

class RpViewModel(app: Application) : AndroidViewModel(app) {
    private companion object {
        const val TAG = "MaidManagerRP"
    }

    private val store = RpWorldStore(app)
    private val settingsRepository = SettingsRepository(app)
    private val providerStore = ProviderStore(app)
    private val modelStore = RpModelStore(app)
    private val catalogSources: Map<String, RpModelCatalogSource> = mapOf(
        RpAiServices.SILICONFLOW to SiliconFlowCatalogSource(),
        RpAiServices.SILICONFLOW_INTERNATIONAL to SiliconFlowCatalogSource(),
        RpAiServices.OPENROUTER to OpenRouterCatalogSource(),
        RpAiServices.CUSTOM to CustomCompatibleCatalogSource()
    )
    private val portraitService = RpPortraitService(app)
    private val portraitJobs = mutableSetOf<String>()
    private val client = LlmClient()

    val worlds: StateFlow<List<RpWorld>> = store.worldsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val providers: StateFlow<List<ProviderConfig>> = providerStore.providersFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val rpModelSettings: StateFlow<RpModelSettings> = modelStore.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, RpModelSettings())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _canRetryWorldResponse = MutableStateFlow(false)
    val canRetryWorldResponse: StateFlow<Boolean> = _canRetryWorldResponse.asStateFlow()
    private var pendingWorldAction: PendingWorldAction? = null
    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()
    private val _creationPhase = MutableStateFlow<String?>(null)
    val creationPhase: StateFlow<String?> = _creationPhase.asStateFlow()
    private val _creationError = MutableStateFlow<RpCreationError?>(null)
    val creationError: StateFlow<RpCreationError?> = _creationError.asStateFlow()
    private val _creationDraft = MutableStateFlow(RpCreationDraft())
    val creationDraft: StateFlow<RpCreationDraft> = _creationDraft.asStateFlow()
    private var creationJob: Job? = null
    private var pendingCreation: PendingCreation? = null

    init {
        viewModelScope.launch {
            val migratedLegacySiliconFlow = ensureRpProviders()
            if (migratedLegacySiliconFlow) migrateLegacySiliconFlowModelState()
            val current = modelStore.snapshot()
            if (!current.providerIsolationInitialized) {
                val hasLegacyOpenRouterState = current.catalog.isNotEmpty() || current.tasks.isNotEmpty()
                val initialProvider = if (hasLegacyOpenRouterState) {
                    RpAiServices.OPENROUTER
                } else {
                    RpAiServices.SILICONFLOW
                }
                val states = if (hasLegacyOpenRouterState) {
                    current.providerStates + (RpAiServices.OPENROUTER to current.toProviderState())
                } else current.providerStates
                val state = states[initialProvider] ?: RpProviderModelState()
                modelStore.save(
                    current.copy(
                        providerStates = states,
                        providerIsolationInitialized = true
                    ).withProviderState(initialProvider, state)
                )
            }
            ensureCreationTask()
            ProactiveScheduler.reconcile(app)
        }
    }

    fun clearError() {
        _error.value = null
        _canRetryWorldResponse.value = false
        pendingWorldAction = null
    }
    fun clearToast() { _toast.value = null }

    fun clearCreationError() { _creationError.value = null }

    fun retryCreation() {
        val pending = pendingCreation ?: return
        _creationError.value = null
        generateWorld(pending.nameHint, pending.idea, pending.onCreated)
    }

    fun cancelCreation() {
        if (creationJob?.isActive == true) {
            creationJob?.cancel()
            _toast.value = "已取消创建 RP"
        }
    }

    private suspend fun ensureCreationTask() {
        val current = modelStore.snapshot()
        if (current.catalog.isEmpty() ||
            !current.tasks[RpModelTask.CREATION]?.defaultModel.isNullOrBlank()
        ) return
        val analyzed = RpModelAnalyzer.analyze(current.catalog, current.modelReliability)
        val profile = current.profile.takeUnless { it == RpModelProfile.CUSTOM }
            ?: RpModelProfile.BALANCED
        val creation = analyzed[profile]?.get(RpModelTask.CREATION) ?: return
        saveActiveRpSettings(current.copy(
            recommendations = analyzed,
            tasks = current.tasks + (RpModelTask.CREATION to creation),
            myModels = (current.myModels + creation.candidates).distinct()
        ))
    }

    fun generateWorld(nameHint: String, idea: String, onCreated: (String) -> Unit) {
        if (idea.isBlank() || _busy.value) return
        pendingCreation = PendingCreation(nameHint, idea, onCreated)
        _creationDraft.value = RpCreationDraft(nameHint, idea)
        _creationError.value = null
        creationJob = viewModelScope.launch {
            _busy.value = true
            _creationPhase.value = "正在连接 AI 服务"
            var providerName = rpModelSettings.value.activeProviderId
            var modelId = rpModelSettings.value.tasks[RpModelTask.CREATION]?.defaultModel.orEmpty()
            val phaseJob = launch {
                delay(8_000)
                _creationPhase.value = "正在生成设定、人物与状态"
                delay(15_000)
                _creationPhase.value = "这个模型响应较慢，请稍候……"
            }
            try {
                val (system, user) = RpEngine.worldGenerationPrompt(nameHint, idea)
                val (provider, model) = resolveModel(RpModelTask.CREATION)
                providerName = provider.name
                modelId = model
                val localEndpoint = provider.baseUrl.contains("localhost", true) ||
                    provider.baseUrl.contains("127.0.0.1") || provider.baseUrl.contains("10.0.2.2")
                if (provider.apiKey.isBlank() && provider.id != RpAiServices.CUSTOM && !localEndpoint) {
                    throw RpCreationMissingKeyException()
                }
                val modelInfo = rpModelSettings.value.catalog.firstOrNull { it.id == model }
                val supportsStructuredJson = modelInfo?.supportsStructuredOutput == true
                val timeoutMillis = rpModelSettings.value.creationTimeoutSeconds
                    .coerceIn(120, 600) * 1_000L
                val maxOutputTokens = rpModelSettings.value.creationMaxOutputTokens
                    .coerceIn(2_000, 16_000)
                debugLog(
                    "RP creation request",
                    "provider=${provider.name} model=$model timeoutMs=$timeoutMillis " +
                        "maxOutputTokens=$maxOutputTokens structured=$supportsStructuredJson"
                )
                val response = client.completeDetailed(
                    provider = provider,
                    modelId = model,
                    messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
                    temperature = if (rpModelSettings.value.useRecommendedParameters) {
                        settings.value.temperature
                    } else rpModelSettings.value.temperature,
                    structuredJson = supportsStructuredJson,
                    requestTimeoutMillis = timeoutMillis,
                    maxOutputTokens = maxOutputTokens
                )
                debugLog(
                    "RP creation response",
                    "provider=${provider.name} model=$model http=${response.httpStatus} " +
                        "elapsedMs=${response.elapsedMillis} finishReason=${response.finishReason ?: "unknown"} " +
                        "chars=${response.content.length} structured=${response.structuredModeUsed}"
                )
                if (response.finishReason.equals("length", true) ||
                    response.finishReason.equals("max_tokens", true)
                ) throw RpCreationOutputTruncatedException(response.finishReason)
                _creationPhase.value = "正在校验 RP 数据"
                val generated = RpEngine.parse<GeneratedWorld>(response.content)
                    ?: throw RpCreationFormatException(
                        response.content.length,
                        RpEngine.looksLikeTruncatedJson(response.content)
                    )
                val issues = RpEngine.validateGeneratedWorld(generated, nameHint)
                if (issues.isNotEmpty()) throw RpCreationSchemaException(issues)
                val world = generated.toWorld(nameHint, idea)
                _creationPhase.value = "正在保存项目配置"
                store.upsert(world)
                recordCreationResult(model, success = true)
                debugLog("RP creation parser", "result=success worldId=${world.id}")
                pendingCreation = null
                _creationDraft.value = RpCreationDraft()
                scheduleMissingPortraits(world)
                onCreated(world.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val previousFailures = rpModelSettings.value.modelReliability[modelId]
                    ?.creationFormatFailureCount ?: 0
                val classified = RpCreationErrorClassifier.classify(
                    error, providerName, modelId.ifBlank { "未配置" }, previousFailures
                )
                recordCreationResult(
                    modelId,
                    success = false,
                    formatFailure = classified.kind in setOf(
                        RpCreationErrorKind.FORMAT,
                        RpCreationErrorKind.MODEL_UNSUITABLE,
                        RpCreationErrorKind.OUTPUT_TRUNCATED
                    ),
                    timeout = classified.kind == RpCreationErrorKind.TIMEOUT
                )
                Log.e(TAG, "RP creation failed kind=${classified.kind} provider=$providerName model=$modelId", error)
                debugLog("RP creation parser", "result=failure kind=${classified.kind}")
                _creationError.value = classified
            } finally {
                phaseJob.cancel()
                _busy.value = false
                _creationPhase.value = null
                creationJob = null
            }
        }
    }

    fun createBlankWorld(nameHint: String, idea: String, onCreated: (String) -> Unit) {
        val id = newId()
        val world = RpWorld(
            id = id,
            name = nameHint.trim().ifBlank { "未命名 RP" },
            idea = idea.trim(),
            summary = idea.trim(),
            publicBackground = idea.trim(),
            projectMode = RpProjectMode.HYBRID,
            modules = RpProjectModules(
                locationsEnabled = false,
                timelineEnabled = false,
                randomEventsEnabled = false,
                storyEnabled = false,
                npcSimulationEnabled = false
            ),
            currentLocationId = ""
        )
        viewModelScope.launch {
            store.upsert(world)
            onCreated(id)
        }
    }

    fun deleteWorld(worldId: String) {
        viewModelScope.launch {
            store.delete(worldId)
            ProactiveScheduler.reconcile(getApplication())
        }
    }

    fun setProactiveMessagesEnabled(worldId: String, enabled: Boolean) = updateWorld(worldId) {
        it.copy(proactiveMessagesEnabled = enabled)
    }

    fun updateWorldBasics(
        worldId: String,
        name: String,
        summary: String,
        publicBackground: String,
        privateBackground: String,
        rules: String,
        visualStyle: String,
        timeRules: String,
        weather: String
    ) = updateWorld(worldId) {
        it.copy(
            name = name.ifBlank { "未命名 RP" },
            summary = summary,
            publicBackground = publicBackground,
            privateBackground = privateBackground,
            rules = rules,
            visualStyle = visualStyle,
            timeRules = timeRules,
            weather = weather
        )
    }

    fun updateProjectStructure(
        worldId: String,
        mode: RpProjectMode,
        modules: RpProjectModules
    ) = updateWorld(worldId) { world ->
        world.copy(
            projectMode = mode,
            modules = modules,
            currentLocationId = if (modules.locationsEnabled) world.currentLocationId else ""
        )
    }

    fun upsertLocation(worldId: String, location: RpLocation) = updateWorld(worldId) { world ->
        val locations = world.locations.map { other ->
            if (other.id == location.id) other
            else if (other.id in location.connectedLocationIds) {
                other.copy(connectedLocationIds = (other.connectedLocationIds + location.id).distinct())
            } else {
                other.copy(connectedLocationIds = other.connectedLocationIds.filterNot { it == location.id })
            }
        }.toMutableList()
        val index = locations.indexOfFirst { it.id == location.id }
        if (index >= 0) locations[index] = location else locations += location
        world.copy(
            locations = locations,
            currentLocationId = world.currentLocationId.ifBlank { location.id }
        )
    }

    fun deleteLocation(worldId: String, locationId: String) = updateWorld(worldId) { world ->
        val locations = world.locations.filterNot { it.id == locationId }.map {
            it.copy(connectedLocationIds = it.connectedLocationIds.filterNot { id -> id == locationId })
        }
        world.copy(
            locations = locations,
            currentLocationId = if (world.currentLocationId == locationId) {
                locations.firstOrNull { it.enterable }?.id.orEmpty()
            } else world.currentLocationId,
            characters = world.characters.map {
                if (it.currentLocationId == locationId) it.copy(currentLocationId = "") else it
            }
        )
    }

    fun upsertCharacter(worldId: String, character: RpCharacter) = updateWorld(worldId) { world ->
        val prepared = character.copy(portraitPrompt = buildRpPortraitPrompt(world, character))
        val characters = world.characters.toMutableList()
        val index = characters.indexOfFirst { it.id == prepared.id }
        if (index >= 0) characters[index] = prepared else characters += prepared
        val expanded = world.copy(characters = characters)
        expanded.copy(stateValues = (expanded.stateValues + expanded.stateDefinitions.flatMap {
            defaultValues(expanded, it)
        }).distinctBy { it.definitionId + "|" + it.targetId })
    }

    fun deleteCharacter(worldId: String, characterId: String) = updateWorld(worldId) { world ->
        val removedDefinitions = world.stateDefinitions.filter {
            it.owner == RpStateOwner.CHARACTER && it.ownerTargetId == characterId
        }.map { it.id }.toSet()
        world.copy(
            characters = world.characters.filterNot { it.id == characterId },
            presentCharacterIds = world.presentCharacterIds.filterNot { it == characterId },
            stateDefinitions = world.stateDefinitions.filterNot { it.id in removedDefinitions },
            stateValues = world.stateValues.filterNot {
                it.targetId == characterId || it.definitionId in removedDefinitions
            },
            storyEvents = world.storyEvents.map {
                it.copy(characterIds = it.characterIds.filterNot { id -> id == characterId })
            },
            characterDialogues = world.characterDialogues - characterId,
            activeDialogue = world.activeDialogue?.takeUnless { it.characterId == characterId }
        )
    }

    fun upsertStateDefinition(worldId: String, definition: RpStateDefinition) =
        updateWorld(worldId) { world ->
            val definitions = world.stateDefinitions.toMutableList()
            val index = definitions.indexOfFirst { it.id == definition.id }
            if (index >= 0) definitions[index] = definition else definitions += definition
            val defaults = defaultValues(world, definition)
            val validTargets = defaults.map { it.targetId }.toSet()
            val values = world.stateValues.filterNot {
                it.definitionId == definition.id && it.targetId !in validTargets
            }.toMutableList()
            values += defaults.filter { default ->
                values.none {
                    it.definitionId == default.definitionId && it.targetId == default.targetId
                }
            }
            world.copy(stateDefinitions = definitions, stateValues = values.distinctBy {
                it.definitionId + "|" + it.targetId
            })
        }

    fun upsertScene(worldId: String, scene: RpScene) = updateWorld(worldId) { world ->
        val scenes = world.scenes.toMutableList()
        val index = scenes.indexOfFirst { it.id == scene.id }
        if (index >= 0) scenes[index] = scene else scenes += scene
        val expanded = world.copy(
            scenes = scenes,
            currentSceneId = world.currentSceneId.ifBlank { scene.id }
        )
        expanded.copy(stateValues = (expanded.stateValues + expanded.stateDefinitions.flatMap {
            defaultValues(expanded, it)
        }).distinctBy { it.definitionId + "|" + it.targetId })
    }

    fun deleteScene(worldId: String, sceneId: String) = updateWorld(worldId) { world ->
        val scenes = world.scenes.filterNot { it.id == sceneId }
        world.copy(
            scenes = scenes,
            currentSceneId = if (world.currentSceneId == sceneId) scenes.firstOrNull()?.id.orEmpty()
            else world.currentSceneId,
            storyEvents = world.storyEvents.map { event ->
                event.copy(sceneIds = event.sceneIds.filterNot { it == sceneId })
            },
            stateValues = world.stateValues.filterNot { value ->
                world.stateDefinitions.any {
                    it.id == value.definitionId && it.owner == RpStateOwner.SCENE && value.targetId == sceneId
                }
            }
        )
    }

    fun upsertStoryArc(worldId: String, arc: RpStoryArc) = updateWorld(worldId) { world ->
        val arcs = world.storyArcs.toMutableList()
        val index = arcs.indexOfFirst { it.id == arc.id }
        if (index >= 0) arcs[index] = arc else arcs += arc
        world.copy(storyArcs = arcs.sortedBy { it.order })
    }

    fun deleteStoryArc(worldId: String, arcId: String) = updateWorld(worldId) { world ->
        world.copy(storyArcs = world.storyArcs.filterNot { it.id == arcId })
    }

    fun upsertStoryEvent(worldId: String, event: RpStoryEvent) = updateWorld(worldId) { world ->
        val events = world.storyEvents.toMutableList()
        val index = events.indexOfFirst { it.id == event.id }
        if (index >= 0) events[index] = event else events += event
        world.copy(storyEvents = events)
    }

    fun deleteStoryEvent(worldId: String, eventId: String) = updateWorld(worldId) { world ->
        world.copy(
            storyEvents = world.storyEvents.filterNot { it.id == eventId }.map {
                it.copy(prerequisiteEventIds = it.prerequisiteEventIds.filterNot { id -> id == eventId })
            }
        )
    }

    fun deleteStateDefinition(worldId: String, definitionId: String) = updateWorld(worldId) { world ->
        world.copy(
            stateDefinitions = world.stateDefinitions.filterNot { it.id == definitionId },
            stateValues = world.stateValues.filterNot { it.definitionId == definitionId }
        )
    }

    fun updateStateValue(worldId: String, definitionId: String, targetId: String, value: String) =
        updateWorld(worldId) { world ->
            world.copy(stateValues = setStateValue(world.stateValues, definitionId, targetId, value))
        }

    fun upsertEventRule(worldId: String, rule: RpEventRule) = updateWorld(worldId) { world ->
        val rules = world.eventRules.toMutableList()
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) rules[index] = rule else rules += rule
        world.copy(eventRules = rules)
    }

    fun deleteEventRule(worldId: String, ruleId: String) = updateWorld(worldId) { world ->
        world.copy(eventRules = world.eventRules.filterNot { it.id == ruleId })
    }

    fun setPortraitGenerationEnabled(worldId: String, enabled: Boolean) = updateWorld(worldId) {
        it.copy(portraitGenerationEnabled = enabled)
    }

    fun savePortraitSettings(
        worldId: String,
        providerId: String,
        modelId: String,
        visualStyle: String
    ) {
        viewModelScope.launch {
            val provider = providerStore.snapshot().firstOrNull { it.id == providerId }
            if (providerId.isNotBlank() && provider == null) {
                _error.value = "所选生图服务不存在"
                return@launch
            }
            val currentSettings = modelStore.snapshot()
            modelStore.save(currentSettings.copy(
                imageGeneration = RpImageGenerationSettings(providerId, modelId.trim())
            ))
            val world = store.snapshot().firstOrNull { it.id == worldId } ?: return@launch
            val updatedWorld = world.copy(
                visualStyle = visualStyle.trim(),
                characters = world.characters.map { character ->
                    character.copy(portraitPrompt = buildRpPortraitPrompt(
                        world.copy(visualStyle = visualStyle.trim()), character
                    ))
                },
                updatedAt = System.currentTimeMillis()
            )
            store.upsert(updatedWorld)
            _toast.value = if (providerId.isBlank() || modelId.isBlank()) {
                "人物头像设置已保存；未配置生图服务时使用文字头像"
            } else {
                "人物头像设置已保存"
            }
            scheduleMissingPortraits(updatedWorld)
        }
    }

    fun regeneratePortrait(worldId: String, characterId: String) {
        viewModelScope.launch {
            val world = store.snapshot().firstOrNull { it.id == worldId } ?: return@launch
            val character = world.characters.firstOrNull { it.id == characterId } ?: return@launch
            if (!world.portraitGenerationEnabled) {
                _toast.value = "当前世界已关闭人物头像生成"
                return@launch
            }
            val provider = configuredPortraitProvider()
            if (!portraitService.isConfigured(provider)) {
                _toast.value = "尚未配置生图服务，已保留人物头像提示词"
                return@launch
            }
            _busy.value = true
            try {
                val path = portraitService.generate(world, character, requireNotNull(provider))
                    ?: error("头像生成失败")
                store.upsert(world.copy(
                    characters = world.characters.map {
                        if (it.id == characterId) it.copy(
                            avatarPath = path,
                            portraitPrompt = buildRpPortraitPrompt(world, it)
                        ) else it
                    },
                    updatedAt = System.currentTimeMillis()
                ))
                _toast.value = "人物头像已更新"
            } catch (error: Exception) {
                Log.e(TAG, "Portrait generation failed", error)
                _error.value = "人物头像生成失败"
            } finally {
                _busy.value = false
            }
        }
    }

    fun startWorld(worldId: String) {
        viewModelScope.launch {
            val world = store.snapshot().firstOrNull { it.id == worldId } ?: return@launch
            val firstLocation = world.locations.firstOrNull { it.enterable }
            val opening = if (world.sceneHistory.isEmpty()) {
                listOf(
                    RpSceneEntry(
                        newId(), "narrator",
                        world.summary.ifBlank { "RP 已经准备好。你可以决定接下来做什么。" },
                        world.worldMinute
                    )
                )
            } else world.sceneHistory
            store.upsert(
                world.copy(
                    setupComplete = true,
                    currentLocationId = if (!world.modules.locationsEnabled) "" else world.currentLocationId.takeIf { id ->
                        world.locations.any { it.id == id && it.enterable }
                    } ?: firstLocation?.id.orEmpty(),
                    currentSceneId = world.currentSceneId.ifBlank { world.scenes.firstOrNull()?.id.orEmpty() },
                    sceneHistory = opening,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun saveAndStartWorld(
        worldId: String,
        name: String,
        summary: String,
        publicBackground: String,
        privateBackground: String,
        rules: String,
        visualStyle: String,
        timeRules: String,
        weather: String
    ) {
        viewModelScope.launch {
            val world = store.snapshot().firstOrNull { it.id == worldId } ?: return@launch
            val firstLocation = world.locations.firstOrNull { it.enterable }
            val opening = world.sceneHistory.ifEmpty {
                listOf(RpSceneEntry(
                    newId(), "narrator",
                    summary.ifBlank { "RP 已经准备好。你可以决定接下来做什么。" },
                    world.worldMinute
                ))
            }
            store.upsert(world.copy(
                name = name.ifBlank { "未命名 RP" },
                summary = summary,
                publicBackground = publicBackground,
                privateBackground = privateBackground,
                rules = rules,
                visualStyle = visualStyle,
                timeRules = timeRules,
                weather = weather,
                setupComplete = true,
                currentLocationId = if (!world.modules.locationsEnabled) "" else world.currentLocationId.takeIf { id ->
                    world.locations.any { it.id == id && it.enterable }
                } ?: firstLocation?.id.orEmpty(),
                currentSceneId = world.currentSceneId.ifBlank { world.scenes.firstOrNull()?.id.orEmpty() },
                sceneHistory = opening,
                updatedAt = System.currentTimeMillis()
            ))
        }
    }

    fun submitWorldAction(
        worldId: String,
        action: String,
        expandUserPlot: Boolean,
        forcedLocationId: String? = null
    ) {
        pendingWorldAction = null
        _canRetryWorldResponse.value = false
        runWorldAction(worldId, action, expandUserPlot, forcedLocationId, appendUserEntry = true)
    }

    fun retryLastWorldAction() {
        val pending = pendingWorldAction ?: return
        _error.value = null
        _canRetryWorldResponse.value = false
        pendingWorldAction = null
        runWorldAction(
            pending.worldId,
            pending.action,
            pending.expandUserPlot,
            pending.forcedLocationId,
            appendUserEntry = false
        )
    }

    private fun runWorldAction(
        worldId: String,
        action: String,
        expandUserPlot: Boolean,
        forcedLocationId: String?,
        appendUserEntry: Boolean
    ) {
        if (action.isBlank() || _busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val world = store.snapshot().firstOrNull { it.id == worldId } ?: return@launch
                val preparedWorld = world.copy(
                    currentLocationId = forcedLocationId ?: world.currentLocationId,
                    presentCharacterIds = if (forcedLocationId != null && appendUserEntry) {
                        emptyList()
                    } else world.presentCharacterIds,
                    updatedAt = System.currentTimeMillis()
                )
                val withUser = if (appendUserEntry) preparedWorld.copy(
                    sceneHistory = (preparedWorld.sceneHistory + RpSceneEntry(
                        newId(), "user", action.trim(), preparedWorld.worldMinute
                    )).takeLast(80)
                ) else preparedWorld
                store.upsert(withUser)
                val (system, user) = RpEngine.scenePrompt(withUser, action.trim(), expandUserPlot)
                val raw = complete(system, user, RpModelTask.WORLD)
                debugLog("world raw response", raw)
                val parsedResult = RpEngine.parseSceneResult(raw)
                if (parsedResult == null) {
                    pendingWorldAction = PendingWorldAction(
                        worldId, action.trim(), expandUserPlot, forcedLocationId
                    )
                    _canRetryWorldResponse.value = true
                    _error.value = "本次世界响应解析失败"
                    return@launch
                }
                var result = parsedResult
                debugLog(
                    "parsed scene result",
                    RpEngine.json.encodeToString(SceneResult.serializer(), result)
                )
                val worldModel = resolveModel(RpModelTask.WORLD).second
                if (resolveModel(RpModelTask.STATE).second != worldModel && withUser.stateDefinitions.isNotEmpty()) {
                    val (stateSystem, stateUser) = RpEngine.statePrompt(withUser, action.trim(), result)
                    val stateResult = RpEngine.parse<SceneStateResult>(
                        complete(stateSystem, stateUser, RpModelTask.STATE)
                    )
                    if (stateResult != null) result = result.copy(stateUpdates = stateResult.stateUpdates)
                }
                if (resolveModel(RpModelTask.NARRATION).second != worldModel) {
                    val (narrationSystem, narrationUser) = RpEngine.narrationPrompt(
                        withUser, action.trim(), result, expandUserPlot
                    )
                    val narration = RpEngine.parse<NarrationResult>(
                        complete(narrationSystem, narrationUser, RpModelTask.NARRATION)
                    )?.narration
                    if (!narration.isNullOrBlank()) result = result.copy(narration = narration)
                }
                val updated = applySceneResult(withUser, result)
                store.upsert(updated)
                debugLog("applied world", RpEngine.json.encodeToString(RpWorld.serializer(), updated))
                pendingWorldAction = null
                _canRetryWorldResponse.value = false
                if (result.majorEventCompleted) runRuleCheckInternal(updated)
                val latest = store.snapshot().firstOrNull { it.id == worldId } ?: updated
                scheduleMissingPortraits(latest)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Scene advance failed", error)
                _error.value = friendlyError(error)
            } finally {
                _busy.value = false
            }
        }
    }

    fun travel(worldId: String, locationId: String) {
        val world = worlds.value.firstOrNull { it.id == worldId } ?: return
        val location = world.locations.firstOrNull {
            it.id == locationId && it.discovered && it.enterable
        } ?: return
        submitWorldAction(worldId, "我前往${location.name}。", false, location.id)
    }

    fun startDialogue(worldId: String, characterId: String) = updateWorld(worldId) { world ->
        val history = world.characterDialogues[characterId]
                ?: world.activeDialogue?.takeIf { it.characterId == characterId }
                ?: RpDialogue(characterId)
        val openedFromProactiveContact = history.messages.lastOrNull()?.isProactive == true
        if (characterId !in world.presentCharacterIds && !openedFromProactiveContact) world
        else {
            val active = history.copy(
                startedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                sessionStartIndex = history.messages.size
            )
            debugLog("loaded visible messages", "world=$worldId character=$characterId count=${active.messages.size}")
            val character = world.characters.firstOrNull { it.id == characterId }
            debugLog("character avatar uri", character?.avatarPath.orEmpty().ifBlank { "<fallback>" })
            debugLog("memory entries", (character?.memories?.size ?: 0).toString())
            world.copy(
                activeDialogue = active,
                characterDialogues = world.characterDialogues + (characterId to active)
            )
        }
    }

    fun sendDialogueMessage(worldId: String, text: String) {
        if (text.isBlank() || _busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val world = store.snapshot().firstOrNull { it.id == worldId } ?: return@launch
                val dialogue = world.activeDialogue ?: return@launch
                val character = world.characters.firstOrNull { it.id == dialogue.characterId }
                    ?: return@launch
                val withUserDialogue = dialogue.appendVisibleMessage(worldId, "user", text.trim())
                val withUser = world.copy(
                    activeDialogue = withUserDialogue,
                    characterDialogues = world.characterDialogues +
                        (dialogue.characterId to withUserDialogue)
                )
                store.upsert(withUser)
                val (system, user) = RpEngine.dialoguePrompt(withUser, character, withUserDialogue.messages)
                val raw = complete(system, user, RpModelTask.CHARACTER)
                debugLog("dialogue raw response", raw)
                val result = RpEngine.parse<DialogueResult>(raw)
                    ?.takeIf { it.reply.isNotBlank() }
                    ?: error("本次人物响应解析失败，请重试")
                val withAssistantDialogue = withUserDialogue.appendVisibleMessage(
                    worldId, "assistant", result.reply
                )
                var updatedCharacter = character.copy(
                    mood = result.mood ?: character.mood,
                    currentAction = result.action ?: character.currentAction,
                    currentGoal = result.goal ?: character.currentGoal,
                    relationToUser = result.relationToUser ?: character.relationToUser,
                    memories = (character.memories + result.memoryCandidates.map {
                        RpMemory(newId(), it, 1)
                    }).takeLast(60)
                )
                var values = withUser.stateValues
                result.stateUpdates.forEach { update ->
                    values = applyStateUpdate(withUser, values, update)
                }
                val updatedCharacters = withUser.characters.map {
                    if (it.id == updatedCharacter.id) updatedCharacter else it
                }
                store.upsert(withUser.copy(
                    characters = updatedCharacters,
                    stateValues = values,
                    activeDialogue = withAssistantDialogue,
                    characterDialogues = withUser.characterDialogues +
                        (dialogue.characterId to withAssistantDialogue),
                    updatedAt = System.currentTimeMillis()
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "Dialogue failed", error)
                _error.value = friendlyError(error)
            } finally {
                _busy.value = false
            }
        }
    }

    fun endDialogue(worldId: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val world = store.snapshot().firstOrNull { it.id == worldId } ?: return@launch
                val dialogue = world.activeDialogue ?: return@launch
                val character = world.characters.firstOrNull { it.id == dialogue.characterId }
                    ?: return@launch
                val sessionMessages = dialogue.messages.drop(dialogue.sessionStartIndex.coerceIn(
                    0, dialogue.messages.size
                ))
                if (sessionMessages.isEmpty()) {
                    store.upsert(world.copy(
                        characterDialogues = world.characterDialogues +
                            (dialogue.characterId to dialogue),
                        activeDialogue = null,
                        updatedAt = System.currentTimeMillis()
                    ))
                    return@launch
                }
                val sessionDialogue = dialogue.copy(messages = sessionMessages, sessionStartIndex = 0)
                val summaryResult = runCatching {
                    val (system, user) = RpEngine.dialogueSummaryPrompt(world, character, sessionDialogue)
                    RpEngine.parse<DialogueSummaryResult>(complete(system, user, RpModelTask.MEMORY))
                }.getOrNull()
                val fallbackSummary = sessionMessages.takeLast(8).joinToString("；") {
                    "${if (it.role == "user") "user" else character.name}:${it.text.take(80)}"
                }
                val summary = summaryResult?.summary?.takeIf { it.isNotBlank() } ?: fallbackSummary
                val memories = (character.memories + summaryResult.orEmptyMemories()).takeLast(80)
                val updatedCharacter = character.copy(
                    relationToUser = summaryResult?.updatedUnderstandingOfUser
                        ?: character.relationToUser,
                    memories = memories,
                    conversationSummaries = (character.conversationSummaries + summary).takeLast(30)
                )
                var values = world.stateValues
                summaryResult?.stateUpdates?.forEach { update ->
                    values = applyStateUpdate(world, values, update)
                }
                store.upsert(world.copy(
                    characters = world.characters.map {
                        if (it.id == character.id) updatedCharacter else it
                    },
                    stateValues = values,
                    characterDialogues = world.characterDialogues +
                        (dialogue.characterId to dialogue.copy(updatedAt = System.currentTimeMillis())),
                    activeDialogue = null,
                    updatedAt = System.currentTimeMillis()
                ))
            } catch (error: Exception) {
                Log.e(TAG, "Dialogue summary failed", error)
                _error.value = friendlyError(error)
            } finally {
                _busy.value = false
            }
        }
    }

    fun runRuleCheck(worldId: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val world = store.snapshot().firstOrNull { it.id == worldId } ?: return@launch
                runRuleCheckInternal(world)
                _toast.value = "世界一致性检查已完成"
            } catch (error: Exception) {
                Log.e(TAG, "Rule check failed", error)
                _error.value = friendlyError(error)
            } finally {
                _busy.value = false
            }
        }
    }

    fun selectRpProvider(providerId: String) {
        if (providerId !in RpAiServices.all.map { it.id }) return
        viewModelScope.launch {
            val current = modelStore.snapshot()
            if (current.activeProviderId == providerId) return@launch
            val states = current.providerStates + (current.activeProviderId to current.toProviderState())
            val target = states[providerId] ?: RpProviderModelState()
            modelStore.save(current.copy(providerStates = states).withProviderState(providerId, target))
        }
    }

    fun saveRpProvider(
        providerId: String,
        serviceName: String,
        baseUrl: String,
        apiKey: String
    ) {
        viewModelScope.launch {
            upsertRpProvider(providerId, serviceName, baseUrl, apiKey)
            _toast.value = "${RpAiServices.definition(providerId).name}配置已保存在本机"
        }
    }

    fun testRpProvider(
        providerId: String,
        serviceName: String,
        baseUrl: String,
        apiKey: String
    ) = loadRpCatalog(providerId, serviceName, baseUrl, apiKey, "连接成功")

    fun refreshRpModels(
        providerId: String,
        serviceName: String,
        baseUrl: String,
        apiKey: String
    ) = loadRpCatalog(providerId, serviceName, baseUrl, apiKey, "检测完成")

    fun addManualRpModel(modelId: String) {
        val cleanId = modelId.trim()
        if (cleanId.isBlank()) return
        viewModelScope.launch {
            val current = modelStore.snapshot()
            val catalog = (current.catalog + RpCatalogModel(
                id = cleanId,
                name = cleanId,
                provider = RpAiServices.definition(current.activeProviderId).name
            )).distinctBy { it.id }
            val analyzed = RpModelAnalyzer.analyze(catalog, current.modelReliability)
            debugLog(
                "RP model analysis",
                RpModelAnalyzer.debugReport(catalog, analyzed, current.modelReliability)
            )
            val profile = current.profile.takeUnless { it == RpModelProfile.CUSTOM }
                ?: RpModelProfile.BALANCED
            val recommended = analyzed[profile].orEmpty()
            val tasks = if (current.tasks.isEmpty()) recommended else current.tasks + listOfNotNull(
                recommended[RpModelTask.CREATION]?.takeIf {
                    current.tasks[RpModelTask.CREATION]?.defaultModel.isNullOrBlank()
                }?.let { RpModelTask.CREATION to it }
            ).toMap()
            saveActiveRpSettings(current.copy(
                catalog = catalog,
                catalogUpdatedAt = System.currentTimeMillis(),
                recommendations = analyzed,
                myModels = (current.myModels + cleanId).distinct(),
                tasks = tasks
            ))
            val provider = activeRpProvider(current.activeProviderId)
            if (provider != null) providerStore.upsert(provider.copy(models = (provider.models + cleanId).distinct()))
            _toast.value = "模型 ID 已加入当前 AI 服务"
        }
    }

    fun selectRpProfile(profile: RpModelProfile) {
        if (profile == RpModelProfile.CUSTOM) {
            viewModelScope.launch { saveActiveRpSettings(rpModelSettings.value.copy(profile = profile)) }
            return
        }
        viewModelScope.launch {
            val current = modelStore.snapshot()
            if (current.catalog.isEmpty()) {
                _error.value = "请先检测当前 AI 服务的模型列表"
                return@launch
            }
            val recommendations = current.recommendations.ifEmpty {
                RpModelAnalyzer.analyze(current.catalog, current.modelReliability)
            }
            val tasks = recommendations[profile].orEmpty()
            saveActiveRpSettings(current.copy(
                profile = profile,
                myModels = (current.myModels + tasks.values.flatMap { it.candidates }).distinct(),
                recommendations = recommendations,
                tasks = tasks
            ))
            val missing = tasks.filterValues { it.defaultModel.isBlank() }.keys
            _toast.value = if (missing.isEmpty()) "已选择${profile.label()}方案"
            else "当前策略没有足够的可用文本模型，请在高级设置中选择"
        }
    }

    fun applyPendingRecommendations() {
        viewModelScope.launch {
            val current = modelStore.snapshot()
            if (current.pendingRecommendations.isEmpty()) return@launch
            val targetProfile = current.profile.takeUnless { it == RpModelProfile.CUSTOM }
                ?: RpModelProfile.BALANCED
            val tasks = current.pendingRecommendations[targetProfile].orEmpty()
            saveActiveRpSettings(current.copy(
                profile = targetProfile,
                recommendations = current.pendingRecommendations,
                pendingRecommendations = emptyMap(),
                recommendationUpdatedAt = System.currentTimeMillis(),
                tasks = tasks,
                myModels = (current.myModels + tasks.values.flatMap { it.candidates }).distinct()
            ))
            _toast.value = "已应用新的推荐配置"
        }
    }

    fun keepCurrentRpConfiguration() {
        viewModelScope.launch {
            val current = modelStore.snapshot()
            if (current.pendingRecommendations.isEmpty()) return@launch
            saveActiveRpSettings(current.copy(
                recommendations = current.pendingRecommendations,
                pendingRecommendations = emptyMap(),
                recommendationUpdatedAt = System.currentTimeMillis()
            ))
            _toast.value = "已保留当前模型配置"
        }
    }

    fun updateRpTaskModels(task: RpModelTask, candidates: List<String>, defaultModel: String) {
        viewModelScope.launch {
            val current = modelStore.snapshot()
            val valid = candidates.distinct().filter { id -> current.catalog.any { it.id == id } }
            val selectedDefault = defaultModel.takeIf { it in valid } ?: valid.firstOrNull().orEmpty()
            saveActiveRpSettings(current.copy(
                profile = RpModelProfile.CUSTOM,
                myModels = (current.myModels + valid).distinct(),
                tasks = current.tasks + (task to RpTaskModels(valid, selectedDefault))
            ))
        }
    }

    fun updateMyModels(models: List<String>) {
        viewModelScope.launch {
            val current = modelStore.snapshot()
            val valid = models.distinct().filter { id -> current.catalog.any { it.id == id } }
            saveActiveRpSettings(current.copy(myModels = valid))
        }
    }

    fun setUseRecommendedParameters(enabled: Boolean) {
        viewModelScope.launch {
            saveActiveRpSettings(modelStore.snapshot().copy(useRecommendedParameters = enabled))
        }
    }

    fun setRpTemperature(value: Float) {
        viewModelScope.launch {
            saveActiveRpSettings(modelStore.snapshot().copy(temperature = value.coerceIn(0f, 2f)))
        }
    }

    fun setCreationTimeoutSeconds(value: Int) {
        viewModelScope.launch {
            saveActiveRpSettings(modelStore.snapshot().copy(
                creationTimeoutSeconds = value.coerceIn(120, 600)
            ))
        }
    }

    fun setCreationMaxOutputTokens(value: Int) {
        viewModelScope.launch {
            saveActiveRpSettings(modelStore.snapshot().copy(
                creationMaxOutputTokens = value.coerceIn(2_000, 16_000)
            ))
        }
    }

    private fun loadRpCatalog(
        providerId: String,
        serviceName: String,
        baseUrl: String,
        apiKey: String,
        successMessage: String
    ) {
        if (_busy.value) return
        val definition = RpAiServices.definition(providerId)
        if (providerId != RpAiServices.CUSTOM && apiKey.isBlank()) {
            _error.value = "请先填写 API 密钥"
            return
        }
        if (providerId == RpAiServices.CUSTOM && baseUrl.isBlank()) {
            _error.value = "请先填写 Base URL"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            try {
                if (modelStore.snapshot().activeProviderId != providerId) selectRpProviderNow(providerId)
                val provider = upsertRpProvider(providerId, serviceName, baseUrl, apiKey)
                val source = catalogSources[providerId] ?: error("当前 AI 服务不支持模型检测")
                val catalog = source.load(provider)
                if (catalog.isEmpty()) error("没有返回可用文本模型")
                providerStore.upsert(provider.copy(models = catalog.map { it.id }))
                val current = modelStore.snapshot()
                val analyzed = RpModelAnalyzer.analyze(catalog, current.modelReliability)
                debugLog(
                    "RP model analysis",
                    RpModelAnalyzer.debugReport(catalog, analyzed, current.modelReliability)
                )
                val firstDetection = current.catalog.isEmpty() || current.tasks.isEmpty()
                val selectedProfile = current.profile.takeUnless { it == RpModelProfile.CUSTOM }
                    ?: RpModelProfile.BALANCED
                val initialTasks = analyzed[selectedProfile].orEmpty()
                val preservedTasks = if (firstDetection) initialTasks else current.tasks + listOfNotNull(
                    initialTasks[RpModelTask.CREATION]?.takeIf {
                        current.tasks[RpModelTask.CREATION]?.defaultModel.isNullOrBlank()
                    }?.let { RpModelTask.CREATION to it }
                ).toMap()
                saveActiveRpSettings(current.copy(
                    catalog = catalog,
                    catalogUpdatedAt = System.currentTimeMillis(),
                    myModels = (current.myModels.filter { id -> catalog.any { it.id == id } } +
                        if (firstDetection) initialTasks.values.flatMap { it.candidates }
                        else emptyList()).distinct(),
                    recommendations = if (firstDetection) analyzed else current.recommendations,
                    pendingRecommendations = if (firstDetection) emptyMap() else analyzed,
                    recommendationUpdatedAt = if (firstDetection) System.currentTimeMillis()
                        else current.recommendationUpdatedAt,
                    profile = if (firstDetection && current.profile == RpModelProfile.CUSTOM)
                        RpModelProfile.BALANCED else current.profile,
                    tasks = preservedTasks
                ))
                _toast.value = if (firstDetection) {
                    "$successMessage：检测到 ${catalog.size} 个文本模型，已完成自动配置"
                } else {
                    "$successMessage：检测到 ${catalog.size} 个文本模型，发现新的推荐配置"
                }
            } catch (error: Exception) {
                Log.e(TAG, "RP provider catalog failed: ${definition.id}", error)
                if (modelStore.snapshot().catalog.isNotEmpty()) {
                    _toast.value = "模型列表获取失败，当前使用缓存模型列表"
                } else {
                    _error.value = when {
                        error.message.orEmpty().contains("401") -> "API 密钥无效"
                        error.message.orEmpty().contains("403") -> "当前服务拒绝访问"
                        error.message.orEmpty().contains("404") && providerId == RpAiServices.CUSTOM ->
                            "当前服务不支持模型列表，请手动填写模型 ID"
                        else -> "当前服务暂时无法连接"
                    }
                }
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun ensureRpProviders(): Boolean {
        val beforeMigration = providerStore.snapshot()
        val legacy = beforeMigration.firstOrNull { it.id == RpAiServices.SILICONFLOW }
        val shouldMigrateLegacyInternational = legacy?.baseUrl
            ?.contains("api.siliconflow.com", ignoreCase = true) == true &&
            beforeMigration.none { it.id == RpAiServices.SILICONFLOW_INTERNATIONAL }
        if (shouldMigrateLegacyInternational) {
            providerStore.upsert(legacy.copy(
                id = RpAiServices.SILICONFLOW_INTERNATIONAL,
                name = RpAiServices.definition(RpAiServices.SILICONFLOW_INTERNATIONAL).name,
                baseUrl = RpAiServices.definition(RpAiServices.SILICONFLOW_INTERNATIONAL).defaultBaseUrl
            ))
            providerStore.upsert(legacy.copy(
                id = RpAiServices.SILICONFLOW,
                name = RpAiServices.definition(RpAiServices.SILICONFLOW).name,
                baseUrl = RpAiServices.definition(RpAiServices.SILICONFLOW).defaultBaseUrl,
                apiKey = "",
                models = emptyList()
            ))
        }

        val existing = providerStore.snapshot()
        RpAiServices.all.forEach { definition ->
            val old = existing.firstOrNull { it.id == definition.id }
            if (old == null) {
                providerStore.upsert(ProviderConfig(
                    id = definition.id,
                    name = definition.name,
                    baseUrl = definition.defaultBaseUrl,
                    apiKey = ""
                ))
            } else if (!definition.custom && old.baseUrl != definition.defaultBaseUrl) {
                providerStore.upsert(old.copy(name = definition.name, baseUrl = definition.defaultBaseUrl))
            }
        }
        return shouldMigrateLegacyInternational
    }

    private suspend fun migrateLegacySiliconFlowModelState() {
        val current = modelStore.snapshot()
        val legacyState = current.providerStates[RpAiServices.SILICONFLOW]
            ?: current.takeIf { it.activeProviderId == RpAiServices.SILICONFLOW }?.toProviderState()
            ?: RpProviderModelState()
        val states = current.providerStates +
            (RpAiServices.SILICONFLOW_INTERNATIONAL to legacyState) +
            (RpAiServices.SILICONFLOW to RpProviderModelState())
        val active = if (current.activeProviderId == RpAiServices.SILICONFLOW) {
            RpAiServices.SILICONFLOW_INTERNATIONAL
        } else current.activeProviderId
        val target = states[active] ?: RpProviderModelState()
        modelStore.save(
            current.copy(
                providerStates = states,
                providerIsolationInitialized = true
            ).withProviderState(active, target)
        )
    }

    private suspend fun upsertRpProvider(
        providerId: String,
        serviceName: String,
        baseUrl: String,
        apiKey: String
    ): ProviderConfig {
        val definition = RpAiServices.definition(providerId)
        val old = providerStore.snapshot().firstOrNull { it.id == providerId }
        val provider = ProviderConfig(
            id = providerId,
            name = if (definition.custom) serviceName.trim().ifBlank { definition.name } else definition.name,
            baseUrl = if (definition.custom) baseUrl.trim().trimEnd('/') else definition.defaultBaseUrl,
            apiKey = apiKey.trim(),
            models = old?.models.orEmpty(),
            customHeaders = old?.customHeaders.orEmpty(),
            extraBody = old?.extraBody.orEmpty(),
            createdAt = old?.createdAt ?: System.currentTimeMillis()
        )
        providerStore.upsert(provider)
        return provider
    }

    private suspend fun activeRpProvider(providerId: String): ProviderConfig? =
        providerStore.snapshot().firstOrNull { it.id == providerId }

    private suspend fun selectRpProviderNow(providerId: String) {
        val current = modelStore.snapshot()
        if (current.activeProviderId == providerId) return
        val states = current.providerStates + (current.activeProviderId to current.toProviderState())
        val target = states[providerId] ?: RpProviderModelState()
        modelStore.save(current.copy(providerStates = states).withProviderState(providerId, target))
    }

    private suspend fun saveActiveRpSettings(value: RpModelSettings) {
        modelStore.save(value.copy(
            providerStates = value.providerStates + (value.activeProviderId to value.toProviderState()),
            providerIsolationInitialized = true
        ))
    }

    fun saveApiKey(providerId: String, key: String) {
        viewModelScope.launch {
            val provider = providerStore.snapshot().firstOrNull { it.id == providerId } ?: return@launch
            providerStore.upsert(provider.copy(apiKey = key.trim()))
            _toast.value = "API 密钥已保存在本机"
        }
    }

    fun selectModel(providerId: String, model: String) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(activeProviderId = providerId, activeModel = model) }
        }
    }

    fun testConnection(providerId: String) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val provider = providerStore.snapshot().firstOrNull { it.id == providerId }
                    ?: error("请先选择 AI 服务")
                val models = client.listModels(provider)
                _toast.value = "连接成功，可用模型 ${models.size} 个"
            } catch (error: Exception) {
                _error.value = "连接失败：${friendlyError(error)}"
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun recordCreationResult(
        modelId: String,
        success: Boolean,
        formatFailure: Boolean = false,
        timeout: Boolean = false
    ) {
        if (modelId.isBlank()) return
        val current = modelStore.snapshot()
        val old = current.modelReliability[modelId] ?: RpModelReliability()
        val updatedReliability = current.modelReliability + (modelId to old.copy(
            creationSuccessCount = old.creationSuccessCount + if (success) 1 else 0,
            creationFormatFailureCount = old.creationFormatFailureCount + if (formatFailure) 1 else 0,
            creationTimeoutCount = old.creationTimeoutCount + if (timeout) 1 else 0,
            lastCreationAt = System.currentTimeMillis()
        ))
        val analyzed = RpModelAnalyzer.analyze(current.catalog, updatedReliability)
        saveActiveRpSettings(current.copy(
            modelReliability = updatedReliability,
            recommendations = analyzed
        ))
    }

    private suspend fun complete(system: String, user: String, task: RpModelTask): String {
        val (provider, model) = resolveModel(task)
        val current = settings.value
        val supportsStructuredJson = rpModelSettings.value.catalog
            .firstOrNull { it.id == model }
            ?.supportsStructuredOutput == true
        if (provider.apiKey.isBlank() && !provider.baseUrl.contains("localhost") &&
            !provider.baseUrl.contains("10.0.2.2")
        ) error("请先填写 API 密钥")
        val answer = StringBuilder()
        client.chatStream(
            provider = provider,
            settings = current.copy(
                stream = false,
                temperature = if (rpModelSettings.value.useRecommendedParameters) {
                    current.temperature
                } else rpModelSettings.value.temperature
            ),
            modelId = model,
            messages = listOf(ChatMessage("system", system), ChatMessage("user", user)),
            reasoningEnabled = false,
            structuredJson = supportsStructuredJson
        ).collect { event -> event.content?.let(answer::append) }
        return answer.toString().trim()
    }

    private fun debugLog(label: String, value: String) {
        val flags = getApplication<Application>().applicationInfo.flags
        if (flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        if (value.isEmpty()) {
            Log.d(TAG, "$label: <empty>")
            return
        }
        value.chunked(3000).forEachIndexed { index, part ->
            Log.d(TAG, "$label[${index + 1}]: $part")
        }
    }

    private fun scheduleMissingPortraits(world: RpWorld) {
        viewModelScope.launch { generateMissingPortraits(world) }
    }

    private suspend fun generateMissingPortraits(world: RpWorld) {
        if (!world.portraitGenerationEnabled) return
        val provider = configuredPortraitProvider()
        if (!portraitService.isConfigured(provider)) return
        world.characters.filter {
            it.hasAppeared && it.avatarPath.isNullOrBlank() && it.hasPortraitFacts()
        }.forEach { character ->
            val jobKey = "${world.id}|${character.id}"
            if (!portraitJobs.add(jobKey)) return@forEach
            try {
                val path = runCatching {
                    portraitService.generate(world, character, requireNotNull(provider))
                }.onFailure { error ->
                    Log.w(TAG, "Portrait generation failed for ${character.id}", error)
                }.getOrNull()
                if (!path.isNullOrBlank()) {
                    val latest = store.snapshot().firstOrNull { it.id == world.id } ?: return@forEach
                    if (latest.characters.none { it.id == character.id }) return@forEach
                    store.upsert(latest.copy(
                        characters = latest.characters.map {
                            if (it.id == character.id) it.copy(
                                avatarPath = path,
                                portraitPrompt = buildRpPortraitPrompt(latest, it)
                            ) else it
                        },
                        updatedAt = System.currentTimeMillis()
                    ))
                }
            } finally {
                portraitJobs.remove(jobKey)
            }
        }
    }

    private suspend fun configuredPortraitProvider(): RpPortraitProvider? {
        val global = settings.value
        if (
            global.imageGenerationEnabled &&
            global.imageGenerationBaseUrl.isNotBlank() &&
            global.imageGenerationModel.isNotBlank()
        ) {
            return RpPortraitProvider(
                baseUrl = global.imageGenerationBaseUrl,
                apiKey = "",
                modelId = global.imageGenerationModel,
                useUnifiedService = true,
                width = global.imageGenerationWidth,
                height = global.imageGenerationHeight,
                steps = global.imageGenerationSteps,
                guidanceScale = global.imageGenerationGuidance,
                timeoutSeconds = global.imageGenerationTimeoutSeconds
            )
        }
        val image = modelStore.snapshot().imageGeneration
        if (image.providerId.isBlank() || image.modelId.isBlank()) return null
        val provider = providerStore.snapshot().firstOrNull { it.id == image.providerId } ?: return null
        if (provider.baseUrl.isBlank()) return null
        return RpPortraitProvider(
            baseUrl = provider.baseUrl,
            apiKey = provider.apiKey,
            modelId = image.modelId,
            customHeaders = provider.customHeaders
        )
    }

    private fun resolveModel(task: RpModelTask): Pair<ProviderConfig, String> {
        val modelSettings = rpModelSettings.value
        val rpChoice = modelSettings.tasks[task]?.defaultModel.orEmpty()
        val provider = providers.value.firstOrNull { it.id == modelSettings.activeProviderId }
            ?: error("请先配置当前 RP AI 服务")
        if (provider.baseUrl.isBlank()) error("请先填写当前 AI 服务的 Base URL")
        if (rpChoice.isBlank() || modelSettings.catalog.none { it.id == rpChoice }) {
            val alternatives = modelSettings.tasks[task]?.candidates.orEmpty().count { id ->
                id != rpChoice && modelSettings.catalog.any { it.id == id }
            }
            error(
                if (alternatives > 0) {
                    "当前${task.label()}模型已不可用；发现 $alternatives 个可替代模型，请在模型分工中确认切换"
                } else {
                    "当前${task.label()}模型未配置，请检测模型或手动填写模型 ID"
                }
            )
        }
        return provider to rpChoice
    }

    private suspend fun runRuleCheckInternal(world: RpWorld) {
        val (system, user) = RpEngine.ruleCheckPrompt(world)
        val result = RpEngine.parse<RuleCheckResult>(complete(system, user, RpModelTask.RULE)) ?: return
        val corrections = (world.ruleCorrections + result.issues.map {
            RpRuleCorrection(newId(), it.issue, it.correctFact)
        }).takeLast(50)
        val events = world.recentEvents.map {
            if (it.major && it.completed && !it.ruleChecked) it.copy(ruleChecked = true) else it
        }
        store.upsert(world.copy(
            ruleCorrections = corrections,
            recentEvents = events,
            updatedAt = System.currentTimeMillis()
        ))
    }

    private fun applySceneResult(world: RpWorld, result: SceneResult): RpWorld {
        var locations = world.locations
        val newLocationIds = mutableMapOf<String, String>()
        result.newLocations.takeIf { world.modules.locationsEnabled }.orEmpty().forEach { generated ->
            val existing = locations.firstOrNull { it.name.equals(generated.name, true) }
            val id = existing?.id ?: newId()
            newLocationIds[generated.name] = id
            val location = generated.toLocation(id)
            locations = if (existing == null) locations + location else locations.map {
                if (it.id == id) location.copy(connectedLocationIds = it.connectedLocationIds) else it
            }
        }
        if (newLocationIds.isNotEmpty()) {
            locations = locations.map { location ->
                val generated = result.newLocations.firstOrNull { it.name == location.name }
                    ?: return@map location
                location.copy(connectedLocationIds = generated.connections.mapNotNull { name ->
                    locations.firstOrNull { it.name.equals(name, true) }?.id
                }.distinct())
            }
        }
        locations = locations.map { location ->
            val reverse = locations.filter { location.id in it.connectedLocationIds }.map { it.id }
            location.copy(connectedLocationIds = (location.connectedLocationIds + reverse).distinct())
        }

        var scenes = world.scenes
        result.newScenes.forEach { generated ->
            val existing = scenes.firstOrNull { it.name.equals(generated.name, true) }
            val scene = RpScene(
                id = existing?.id ?: newId(),
                name = generated.name.ifBlank { "未命名场景" },
                description = generated.description,
                currentStatus = generated.currentStatus,
                playerVisible = generated.playerVisible
            )
            scenes = if (existing == null) scenes + scene else scenes.map {
                if (it.id == existing.id) scene else it
            }
        }

        var characters = world.characters
        result.newCharacters.forEach { generated ->
            if (characters.none { it.name.equals(generated.name, true) }) {
                characters = characters + generated.toCharacter(world, locations)
            }
        }
        result.characterUpdates.forEach { update ->
            val existing = characters.firstOrNull { it.name.equals(update.name, true) }
            if (existing != null) {
                characters = characters.map { character ->
                    if (character.id != existing.id) character else character.copy(
                        hasAppeared = update.hasAppeared ?: character.hasAppeared,
                        firstAppearedAt = if ((update.hasAppeared == true) && character.firstAppearedAt == null)
                            System.currentTimeMillis() else character.firstAppearedAt,
                        knownIdentity = update.knownIdentity ?: character.knownIdentity,
                        knownDescription = update.knownDescription ?: character.knownDescription,
                        privateProfile = update.privateProfile ?: character.privateProfile,
                        currentLocationId = update.location?.let { name ->
                            locations.firstOrNull { it.name.equals(name, true) }?.id
                        } ?: character.currentLocationId,
                        currentAction = update.action ?: character.currentAction,
                        currentGoal = update.goal ?: character.currentGoal,
                        currentPlan = update.plan ?: character.currentPlan,
                        mood = update.mood ?: character.mood,
                        relationToUser = update.relationToUser ?: character.relationToUser
                    )
                }
            }
        }
        result.presentCharacters.forEach { name ->
            if (characters.none { it.name.equals(name, true) }) {
                characters = characters + GeneratedCharacter(name = name, hasAppeared = true)
                    .toCharacter(world, locations)
            }
        }
        characters = characters.map { character ->
            if (result.presentCharacters.any { it.equals(character.name, true) }) {
                character.copy(
                    hasAppeared = true,
                    firstAppearedAt = character.firstAppearedAt ?: System.currentTimeMillis()
                )
            } else character
        }
        val presentIds = characters.filter { character ->
            result.presentCharacters.any { it.equals(character.name, true) }
        }.map { it.id }
        val expandedWorld = world.copy(locations = locations, scenes = scenes, characters = characters)
        var values = (world.stateValues + world.stateDefinitions.flatMap { definition ->
            defaultValues(expandedWorld, definition)
        }).distinctBy { it.definitionId + "|" + it.targetId }
        result.stateUpdates.forEach { update -> values = applyStateUpdate(expandedWorld, values, update) }
        val advance = if (world.modules.timelineEnabled) {
            result.timeAdvanceMinutes.coerceIn(0, 24 * 60)
        } else 0L
        val events = (world.recentEvents + result.events.map { event ->
            RpWorldEvent(
                id = newId(), title = event.title, summary = event.summary,
                locationId = locations.firstOrNull { it.name.equals(event.location, true) }?.id.orEmpty(),
                worldMinute = world.worldMinute + advance,
                knownToPlayer = event.knownToPlayer, major = event.major, completed = event.completed
            )
        }).takeLast(40)
        val currentLocation = if (!world.modules.locationsEnabled) "" else result.currentLocation?.let { name ->
            locations.firstOrNull { it.name.equals(name, true) && it.enterable }?.id
        } ?: world.currentLocationId
        val currentScene = result.currentScene?.let { name ->
            scenes.firstOrNull { it.name.equals(name, true) }?.id
        } ?: world.currentSceneId.ifBlank { scenes.firstOrNull()?.id.orEmpty() }
        var arcs = world.storyArcs
        if (world.modules.storyEnabled && !result.activeStoryArc.isNullOrBlank()) {
            arcs = arcs.map { arc ->
                arc.copy(active = arc.name.equals(result.activeStoryArc, true) && !arc.completed)
            }
        }
        val storyEvents = world.storyEvents.map { event ->
            if (world.modules.storyEnabled && result.completedStoryEvents.any {
                    it.equals(event.name, true)
                }) event.copy(completed = true) else event
        }
        return world.copy(
            worldMinute = world.worldMinute + advance,
            weather = if (world.modules.timelineEnabled) result.weather ?: world.weather else world.weather,
            currentLocationId = currentLocation,
            locations = locations,
            currentSceneId = currentScene,
            scenes = scenes,
            storyArcs = arcs,
            storyEvents = storyEvents,
            characters = characters,
            presentCharacterIds = presentIds,
            stateValues = values,
            recentEvents = events,
            sceneHistory = (world.sceneHistory + RpSceneEntry(
                newId(), "narrator", result.narration.ifBlank { "没有发生特别的事情。" },
                world.worldMinute + advance
            )).takeLast(80),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun applyStateUpdate(
        world: RpWorld,
        values: List<RpStateValue>,
        update: SceneStateUpdate
    ): List<RpStateValue> {
        val definition = world.stateDefinitions.firstOrNull {
            it.name.equals(update.stateName, true)
        } ?: return values
        val targetId = when (definition.owner) {
            RpStateOwner.WORLD -> world.id
            RpStateOwner.USER -> "user"
            RpStateOwner.CHARACTER -> definition.ownerTargetId.takeIf { it.isNotBlank() }
                ?: world.characters.firstOrNull { it.name.equals(update.targetName, true) }?.id
                ?: return values
            RpStateOwner.CHARACTER_TEMPLATE -> world.characters.firstOrNull {
                it.name.equals(update.targetName, true)
            }?.id ?: return values
            RpStateOwner.LOCATION -> definition.ownerTargetId.takeIf { it.isNotBlank() }
                ?: world.locations.firstOrNull { it.name.equals(update.targetName, true) }?.id
                ?: return values
            RpStateOwner.SCENE -> definition.ownerTargetId.takeIf { it.isNotBlank() }
                ?: world.scenes.firstOrNull { it.name.equals(update.targetName, true) }?.id
                ?: return values
            RpStateOwner.CUSTOM -> definition.ownerTargetId.ifBlank {
                update.targetName.ifBlank { definition.ownerTargetName }
            }.ifBlank { return values }
        }
        return setStateValue(values, definition.id, targetId, update.value)
    }

    private fun updateWorld(worldId: String, transform: (RpWorld) -> RpWorld) {
        viewModelScope.launch {
            val world = store.snapshot().firstOrNull { it.id == worldId } ?: return@launch
            store.upsert(transform(world).copy(updatedAt = System.currentTimeMillis()))
            ProactiveScheduler.reconcile(getApplication())
        }
    }

    private fun friendlyError(error: Throwable): String {
        val chain = generateSequence(error as Throwable?) { it.cause }.toList()
        val message = chain.mapNotNull { it.message }.joinToString(" | ")
        val http = chain.filterIsInstance<LlmHttpException>().firstOrNull()
        val httpBody = http?.responseBody.orEmpty()
        return when {
            httpBody.contains("insufficient", true) &&
                httpBody.contains(Regex("balance|credit|quota", RegexOption.IGNORE_CASE)) ->
                "API 余额或额度不足"
            http?.statusCode in setOf(401, 403) -> "API 密钥无效或已失效"
            http?.statusCode == 404 -> "当前模型已不可用"
            http?.statusCode == 429 -> "请求过于频繁，请稍后再试"
            chain.any { it is java.net.UnknownHostException || it is java.net.NoRouteToHostException } ->
                "无法连接 AI 服务，请检查网络连接"
            chain.any { it is java.net.ConnectException } -> "当前 AI 服务暂时无法连接"
            message.contains("timeout", true) -> "AI 响应时间过长"
            message.contains("配置") || message.contains("选择模型") || message.contains("密钥") ->
                error.message ?: "请检查 AI 模型设置"
            else -> error.message?.take(160) ?: "AI 请求失败"
        }
    }

    override fun onCleared() {
        catalogSources.values.forEach { it.close() }
        portraitService.close()
        client.close()
        super.onCleared()
    }
}

internal fun GeneratedWorld.toWorld(nameHint: String, idea: String): RpWorld {
    val worldId = newId()
    val locationMap = locations.associate { it.name to newId() }
    val directedLocations = locations.map { generated ->
        generated.toLocation(locationMap.getValue(generated.name)).copy(
            connectedLocationIds = generated.connections.mapNotNull { name -> locationMap[name] }.distinct()
        )
    }
    val rpLocations = directedLocations.map { location ->
        val reverse = directedLocations.filter { location.id in it.connectedLocationIds }.map { it.id }
        location.copy(connectedLocationIds = (location.connectedLocationIds + reverse).distinct())
    }
    val rpScenes = scenes.map { generated ->
        RpScene(
            id = newId(),
            name = generated.name.ifBlank { "未命名场景" },
            description = generated.description,
            currentStatus = generated.currentStatus,
            playerVisible = generated.playerVisible
        )
    }
    val characterShells = characters.map { it.toCharacterShell(worldId, visualStyle, rpLocations) }
    val arcShells = storyArcs.mapIndexed { index, generated ->
        RpStoryArc(
            id = newId(), name = generated.name.ifBlank { "未命名阶段" },
            description = generated.description, order = index, active = generated.active
        )
    }.let { arcs ->
        if (arcs.isNotEmpty() && arcs.none { it.active }) arcs.mapIndexed { index, arc ->
            arc.copy(active = index == 0)
        } else arcs
    }
    val eventIds = storyEvents.associate { it.name to newId() }
    val storyEventShells = storyEvents.map { generated ->
        RpStoryEvent(
            id = eventIds.getValue(generated.name),
            name = generated.name.ifBlank { "未命名剧情事件" },
            description = generated.description,
            triggerCondition = generated.triggerCondition,
            characterIds = generated.characters.mapNotNull { name ->
                characterShells.firstOrNull { it.name.equals(name, true) }?.id
            },
            sceneIds = generated.scenes.mapNotNull { name ->
                rpScenes.firstOrNull { it.name.equals(name, true) }?.id
            },
            prerequisiteEventIds = generated.prerequisites.mapNotNull { eventIds[it] }
        )
    }
    val world = RpWorld(
        id = worldId,
        name = nameHint.trim().ifBlank { name.ifBlank { "未命名 RP" } },
        idea = idea,
        summary = summary,
        publicBackground = publicBackground,
        privateBackground = privateBackground,
        rules = rules,
        visualStyle = visualStyle,
        projectMode = runCatching {
            RpProjectMode.valueOf(projectMode.uppercase())
        }.getOrDefault(RpProjectMode.HYBRID),
        modules = RpProjectModules(
            locationsEnabled = locationsEnabled,
            timelineEnabled = timelineEnabled,
            randomEventsEnabled = randomEventsEnabled,
            storyEnabled = storyEnabled,
            npcSimulationEnabled = npcSimulationEnabled
        ),
        timeRules = timeRules,
        worldMinute = initialTimeMinutes.coerceAtLeast(0),
        weather = weather,
        currentLocationId = locationMap[initialLocation]
            ?: rpLocations.firstOrNull { it.enterable }?.id.orEmpty(),
        locations = rpLocations,
        currentSceneId = rpScenes.firstOrNull { it.name.equals(currentScene, true) }?.id
            ?: rpScenes.firstOrNull()?.id.orEmpty(),
        scenes = rpScenes,
        storyArcs = arcShells,
        storyEvents = storyEventShells,
        characters = characterShells,
        stateDefinitions = states.map { state ->
            state.toDefinition(characterShells, rpLocations, rpScenes)
        },
        eventRules = eventRules.map { RpEventRule(newId(), it.name, it.description, true, it.frequencyHint) },
        sceneHistory = openingNarration.takeIf { it.isNotBlank() }?.let {
            listOf(RpSceneEntry(newId(), "narrator", it, initialTimeMinutes))
        } ?: emptyList()
    )
    return world.copy(stateValues = world.stateDefinitions.flatMap { definition ->
        defaultValues(world, definition)
    })
}

private fun GeneratedLocation.toLocation(id: String) = RpLocation(
    id = id,
    name = name.ifBlank { "未命名地点" },
    description = description,
    type = type,
    background = background,
    currentStatus = currentStatus,
    discovered = discovered,
    enterable = enterable
)

private fun GeneratedCharacter.toCharacter(world: RpWorld, locations: List<RpLocation>): RpCharacter =
    toCharacterShell(world.id, world.visualStyle, locations)

private fun GeneratedCharacter.toCharacterShell(
    worldId: String,
    visualStyle: String,
    locations: List<RpLocation>
) = RpCharacter(
    id = newId(), name = name.ifBlank { "未命名角色" }, knownIdentity = knownIdentity,
    knownDescription = knownDescription, privateProfile = privateProfile, species = species,
    gender = gender, age = age, hair = hair, appearance = appearance, clothing = clothing,
    hasAppeared = hasAppeared,
    currentLocationId = locations.firstOrNull { it.name.equals(initialLocation, true) }?.id.orEmpty(),
    mood = mood,
    portraitPrompt = "世界:$worldId；风格:$visualStyle；种族:$species；年龄:$age；性别:$gender；发型:$hair；外貌:$appearance；穿着:$clothing；身份:$knownIdentity",
    firstAppearedAt = if (hasAppeared) System.currentTimeMillis() else null
)

private fun GeneratedState.toDefinition(
    characters: List<RpCharacter>,
    locations: List<RpLocation>,
    scenes: List<RpScene>
): RpStateDefinition {
    val parsedOwner = runCatching {
        RpStateOwner.valueOf(owner.uppercase())
    }.getOrDefault(RpStateOwner.WORLD)
    val targetId = when (parsedOwner) {
        RpStateOwner.CHARACTER -> characters.firstOrNull {
            it.name.equals(ownerTargetName, true)
        }?.id.orEmpty()
        RpStateOwner.LOCATION -> locations.firstOrNull {
            it.name.equals(ownerTargetName, true)
        }?.id.orEmpty()
        RpStateOwner.SCENE -> scenes.firstOrNull {
            it.name.equals(ownerTargetName, true)
        }?.id.orEmpty()
        RpStateOwner.CUSTOM -> ownerTargetName
        else -> ""
    }
    return RpStateDefinition(
    id = newId(), name = name.ifBlank { "未命名状态" }, icon = icon.ifBlank { "•" },
    type = runCatching { RpStateType.valueOf(type.uppercase()) }.getOrDefault(RpStateType.TEXT),
    owner = parsedOwner, ownerTargetId = targetId, ownerTargetName = ownerTargetName,
    playerVisible = playerVisible, defaultValue = defaultValue, options = options, maxValue = maxValue
    )
}

private fun defaultValues(world: RpWorld, definition: RpStateDefinition): List<RpStateValue> {
    val targets = when (definition.owner) {
        RpStateOwner.WORLD -> listOf(world.id)
        RpStateOwner.USER -> listOf("user")
        RpStateOwner.CHARACTER -> definition.ownerTargetId.takeIf { it.isNotBlank() }
            ?.let(::listOf) ?: world.characters.map { it.id } // compatibility with old saves
        RpStateOwner.CHARACTER_TEMPLATE -> world.characters.map { it.id }
        RpStateOwner.LOCATION -> definition.ownerTargetId.takeIf { it.isNotBlank() }
            ?.let(::listOf) ?: world.locations.map { it.id }
        RpStateOwner.SCENE -> definition.ownerTargetId.takeIf { it.isNotBlank() }
            ?.let(::listOf) ?: world.scenes.map { it.id }
        RpStateOwner.CUSTOM -> definition.ownerTargetId.ifBlank { definition.ownerTargetName }
            .takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
    }
    return targets.map { RpStateValue(definition.id, it, definition.defaultValue) }
}

private fun setStateValue(
    values: List<RpStateValue>,
    definitionId: String,
    targetId: String,
    value: String
): List<RpStateValue> {
    val updated = values.toMutableList()
    val index = updated.indexOfFirst { it.definitionId == definitionId && it.targetId == targetId }
    val item = RpStateValue(definitionId, targetId, value)
    if (index >= 0) updated[index] = item else updated += item
    return updated
}

private fun RpDialogue.appendVisibleMessage(
    worldId: String,
    role: String,
    content: String
): RpDialogue {
    val nextSequence = (messages.maxOfOrNull { it.sequence } ?: 0L) + 1L
    return copy(
        messages = messages + RpDialogueMessage(
            id = newId(),
            role = role,
            text = content,
            worldId = worldId,
            characterId = characterId,
            messageType = "text",
            sequence = nextSequence
        ),
        updatedAt = System.currentTimeMillis()
    )
}

private fun RpCharacter.hasPortraitFacts(): Boolean = listOf(
    knownIdentity, knownDescription, species, gender, age, hair, appearance, clothing
).any { it.isNotBlank() }

private fun DialogueSummaryResult?.orEmptyMemories(): List<RpMemory> =
    this?.importantMemories.orEmpty().map { RpMemory(newId(), it, 2) }
