package com.miniichat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miniichat.ChatViewModel
import com.miniichat.R
import com.miniichat.chatdata.ChatDataCodec
import com.miniichat.chatdata.RpHandoffBuilder
import com.miniichat.data.ProviderConfig
import com.miniichat.proactive.ProactiveNavigation
import com.miniichat.rp.RpAiServices
import com.miniichat.rp.RpCreationRecovery
import com.miniichat.rp.RpViewModel
import com.miniichat.ui.rp.RpAiModelScreen
import com.miniichat.ui.rp.RpCharacterScreen
import com.miniichat.ui.rp.RpCreateWorldScreen
import com.miniichat.ui.rp.RpDialogueScreen
import com.miniichat.ui.rp.RpDataManagementScreen
import com.miniichat.ui.rp.RpMapScreen
import com.miniichat.ui.rp.RpModelCustomScreen
import com.miniichat.ui.rp.RpModelParametersScreen
import com.miniichat.ui.rp.RpPortraitSettingsScreen
import com.miniichat.ui.rp.RpStatesScreen
import com.miniichat.ui.rp.RpWorldSettingsScreen
import com.miniichat.ui.rp.RpWorldScreen
import com.miniichat.ui.rp.RpWorldSetupScreen
import com.miniichat.ui.rp.RpWorldsScreen
import kotlinx.coroutines.launch

private enum class Screen {
    Chat, Settings, Providers, ProviderEdit, Assistants, Memories, TtsSettings, SearchSettings,
    Appearance, About, ChatData, HandoffEditor, ProactiveMessages, ImageGeneration, ImageGenerationSettings,
    RpWorlds, RpCreate, RpSetup, RpWorld, RpStates, RpCharacter, RpDialogue, RpAiModel, RpModelCustom,
    RpWorldSettings, RpMap, RpModelParameters, RpPortraitSettings, RpData
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: ChatViewModel) {
    val rpVm: RpViewModel = viewModel()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(Screen.Chat) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var selectedRpWorldId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRpCharacterId by rememberSaveable { mutableStateOf<String?>(null) }
    var characterReturnScreen by rememberSaveable { mutableStateOf(Screen.RpWorld) }
    var providersReturnScreen by rememberSaveable { mutableStateOf(Screen.Settings) }
    var rpAiReturnScreen by rememberSaveable { mutableStateOf(Screen.RpWorlds) }
    var rpModelCustomReturnScreen by rememberSaveable { mutableStateOf(Screen.RpAiModel) }
    var rpSetupReturnScreen by rememberSaveable { mutableStateOf(Screen.RpWorlds) }
    var rpStatesReturnScreen by rememberSaveable { mutableStateOf(Screen.RpWorld) }
    var handoffFileName by remember { mutableStateOf("女仆管理器-交接包.json") }
    var handoffDraft by remember { mutableStateOf("") }
    var handoffReturnScreen by rememberSaveable { mutableStateOf(Screen.ChatData) }
    var imageSettingsReturnScreen by rememberSaveable { mutableStateOf(Screen.Settings) }

    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    androidx.activity.compose.BackHandler(enabled = showModelPicker) {
        showModelPicker = false
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Settings) {
        screen = Screen.Chat
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Providers) {
        screen = providersReturnScreen
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.ProviderEdit) {
        screen = Screen.Providers
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Assistants) {
        screen = Screen.Settings
    }
    androidx.activity.compose.BackHandler(
        enabled = screen in setOf(
            Screen.Memories, Screen.TtsSettings, Screen.SearchSettings, Screen.Appearance, Screen.About,
            Screen.ChatData, Screen.ProactiveMessages
        )
    ) { screen = Screen.Settings }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.ImageGeneration) {
        screen = Screen.Chat
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.ImageGenerationSettings) {
        screen = imageSettingsReturnScreen
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.HandoffEditor) {
        screen = handoffReturnScreen
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.RpWorlds) { screen = Screen.Chat }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.RpCreate) {
        if (rpVm.busy.value) rpVm.cancelCreation() else screen = Screen.RpWorlds
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.RpAiModel) { screen = rpAiReturnScreen }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.RpModelCustom) { screen = rpModelCustomReturnScreen }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.RpSetup) {
        val world = rpVm.worlds.value.firstOrNull { it.id == selectedRpWorldId }
        screen = if (world?.setupComplete == true) rpSetupReturnScreen else Screen.RpWorlds
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.RpStates) {
        screen = rpStatesReturnScreen
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.RpWorld) { screen = Screen.RpWorlds }
    androidx.activity.compose.BackHandler(enabled = screen in setOf(Screen.RpWorldSettings, Screen.RpMap)) {
        screen = Screen.RpWorld
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.RpModelParameters) {
        screen = Screen.Settings
    }
    androidx.activity.compose.BackHandler(
        enabled = screen in setOf(Screen.RpPortraitSettings, Screen.RpData)
    ) { screen = Screen.RpWorldSettings }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.RpCharacter) {
        screen = characterReturnScreen
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.RpDialogue) {
        selectedRpWorldId?.let { rpVm.endDialogue(it) }
        screen = Screen.RpWorld
    }

    val conversations by vm.conversations.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val settings by vm.settings.collectAsState()
    val providers by vm.providers.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val streamingOverlay by vm.streamingOverlay.collectAsState()
    val error by vm.error.collectAsState()
    val toast by vm.toast.collectAsState()
    val fetchingId by vm.fetchingModelsFor.collectAsState()
    val memories by vm.memories.collectAsState()
    val ttsState by vm.ttsState.collectAsState()
    val chatDataBusy by vm.chatDataBusy.collectAsState()
    val chatDataPhase by vm.chatDataPhase.collectAsState()
    val imageGenerationState by vm.imageGeneration.collectAsState()
    val imageConnectionMessage by vm.imageConnectionMessage.collectAsState()
    val testingImageConnection by vm.testingImageConnection.collectAsState()
    val rpWorlds by rpVm.worlds.collectAsState()
    val rpSettings by rpVm.settings.collectAsState()
    val rpProviders by rpVm.providers.collectAsState()
    val rpModelSettings by rpVm.rpModelSettings.collectAsState()
    val rpBusy by rpVm.busy.collectAsState()
    val rpError by rpVm.error.collectAsState()
    val rpCreationError by rpVm.creationError.collectAsState()
    val rpCreationPhase by rpVm.creationPhase.collectAsState()
    val rpCreationDraft by rpVm.creationDraft.collectAsState()
    val canRetryRpWorldResponse by rpVm.canRetryWorldResponse.collectAsState()
    val rpToast by rpVm.toast.collectAsState()
    val proactiveDestination by ProactiveNavigation.destination.collectAsState()
    val proactiveNotice by ProactiveNavigation.foregroundNotice.collectAsState()

    val activeConv = conversations.firstOrNull { it.id == activeId }
    val activeProvider = providers.firstOrNull { it.id == settings.activeProviderId }
    val assistants by vm.assistants.collectAsState()
    val conversationAssistant = assistants.firstOrNull { it.id == activeConv?.assistantId }
        ?: assistants.firstOrNull { it.id == settings.activeAssistantId }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it, duration = SnackbarDuration.Short)
            vm.clearToast()
        }
    }
    LaunchedEffect(rpToast) {
        rpToast?.let {
            snackbar.showSnackbar(it, duration = SnackbarDuration.Short)
            rpVm.clearToast()
        }
    }
    LaunchedEffect(proactiveNotice) {
        proactiveNotice?.let {
            snackbar.showSnackbar(it, duration = SnackbarDuration.Short)
            ProactiveNavigation.clearForegroundNotice()
        }
    }
    LaunchedEffect(proactiveDestination, rpWorlds) {
        proactiveDestination?.let { destination ->
            if (destination.sourceType == "normal" && destination.conversationId.isNotBlank()) {
                vm.selectConversation(destination.conversationId)
                screen = Screen.Chat
                ProactiveNavigation.clearDestination()
            } else if (
                destination.sourceType == "rp" &&
                destination.worldId.isNotBlank() &&
                destination.characterId.isNotBlank() &&
                rpWorlds.any { it.id == destination.worldId }
            ) {
                selectedRpWorldId = destination.worldId
                selectedRpCharacterId = destination.characterId
                rpVm.startDialogue(destination.worldId, destination.characterId)
                screen = Screen.RpDialogue
                ProactiveNavigation.clearDestination()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (screen) {
            Screen.Chat -> {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        GlassDrawer(
                            conversations = conversations,
                            activeId = activeId,
                            onSelect = { id ->
                                vm.selectConversation(id)
                                scope.launch { drawerState.close() }
                            },
                            onNew = {
                                vm.newConversation()
                                scope.launch { drawerState.close() }
                            },
                            onDelete = { vm.deleteConversation(it) },
                            onRename = { id, t -> vm.renameConversation(id, t) },
                            onOpenRpWorlds = {
                                screen = Screen.RpWorlds
                                scope.launch { drawerState.close() }
                            },
                            onOpenSettings = {
                                screen = Screen.Settings
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                ) {
                    ChatScreen(
                        conversation = activeConv,
                        settings = settings,
                        activeProvider = activeProvider,
                        assistantName = conversationAssistant?.name ?: "女仆",
                        isStreaming = isStreaming,
                        streamingOverlay = streamingOverlay,
                        ttsState = ttsState,
                        supportsReasoning = vm.supportsNativeReasoning(
                            activeProvider,
                            settings.activeModel
                        ),
                        imageGenerationState = imageGenerationState,
                        onMenu = { scope.launch { drawerState.open() } },
                        onSend = { text, atts -> vm.sendMessage(text, atts) },
                        onStop = { vm.stopStreaming() },
                        onRegenerate = { vm.regenerate() },
                        onRegenerateFrom = { msgId -> vm.regenerateFrom(msgId) },
                        onDeleteMessage = { msgId -> vm.deleteMessage(msgId) },
                        onEditMessage = { msgId, newText -> vm.editMessage(msgId, newText) },
                        onPlayMessage = { vm.playMessage(it) },
                        onWebEnabledChange = { vm.setWebEnabled(it) },
                        onReasoningEnabledChange = { vm.setReasoningEnabled(it) },
                        onReasoningEffortChange = { vm.setReasoningEffort(it) },
                        onGenerateImage = { vm.generateImageForChat(it) },
                        onCancelImage = { vm.cancelImageGeneration() },
                        onSaveImage = { path, uri -> vm.saveGeneratedImage(path, uri) },
                        onNew = { vm.newConversation() },
                        onOpenSettings = { screen = Screen.Settings },
                        onOpenImageGeneration = { screen = Screen.ImageGeneration },
                        onPickModel = {
                            if (providers.isEmpty()) {
                                editingProvider = null
                                screen = Screen.ProviderEdit
                            } else showModelPicker = true
                        }
                    )
                }
            }
            Screen.Settings -> {
                val selectedRpProvider = rpProviders.firstOrNull {
                    it.id == rpModelSettings.activeProviderId
                }
                val rpProviderName = selectedRpProvider?.name
                    ?: RpAiServices.definition(rpModelSettings.activeProviderId).name
                SettingsScreen(
                    settings = settings,
                    providers = providers,
                    assistants = assistants,
                    memoryCount = memories.size,
                    rpProviderName = rpProviderName,
                    rpProviderConfigured = selectedRpProvider?.let {
                        it.baseUrl.isNotBlank() && (
                            it.apiKey.isNotBlank() ||
                                rpModelSettings.activeProviderId == RpAiServices.CUSTOM
                            )
                    } == true,
                    onBack = { screen = Screen.Chat },
                    onOpenProviders = {
                        providersReturnScreen = Screen.Settings
                        screen = Screen.Providers
                    },
                    onOpenAssistants = { screen = Screen.Assistants },
                    onOpenMemories = { screen = Screen.Memories },
                    onOpenTts = { screen = Screen.TtsSettings },
                    onOpenSearch = { screen = Screen.SearchSettings },
                    onOpenImageGeneration = {
                        imageSettingsReturnScreen = Screen.Settings
                        screen = Screen.ImageGenerationSettings
                    },
                    onOpenChatData = { screen = Screen.ChatData },
                    onOpenProactiveMessages = { screen = Screen.ProactiveMessages },
                    onOpenRpModels = {
                        rpAiReturnScreen = Screen.Settings
                        screen = Screen.RpAiModel
                    },
                    onOpenRpParameters = { screen = Screen.RpModelParameters },
                    onOpenAppearance = { screen = Screen.Appearance },
                    onOpenAbout = { screen = Screen.About }
                )
            }
            Screen.ImageGeneration -> {
                ImageGenerationScreen(
                    settings = settings,
                    state = imageGenerationState,
                    onBack = { screen = Screen.Chat },
                    onOpenSettings = {
                        imageSettingsReturnScreen = Screen.ImageGeneration
                        screen = Screen.ImageGenerationSettings
                    },
                    onGenerate = { vm.generateStandaloneImage(it) },
                    onCancel = { vm.cancelImageGeneration() },
                    onSave = { path, uri -> vm.saveGeneratedImage(path, uri) }
                )
            }
            Screen.ImageGenerationSettings -> {
                ImageGenerationSettingsScreen(
                    settings = settings,
                    connectionMessage = imageConnectionMessage,
                    testingConnection = testingImageConnection,
                    onBack = { screen = imageSettingsReturnScreen },
                    onSave = { next ->
                        vm.updateSettings { next }
                        screen = imageSettingsReturnScreen
                    },
                    onTestConnection = { vm.testImageConnection(it) }
                )
            }
            Screen.Assistants -> {
                AssistantsScreen(
                    assistants = assistants,
                    activeId = settings.activeAssistantId,
                    onBack = { screen = Screen.Settings },
                    onSelect = { vm.selectAssistant(it) },
                    onUpsert = { vm.upsertAssistant(it) },
                    onDelete = { vm.deleteAssistant(it) }
                )
            }
            Screen.Providers -> {
                ProvidersScreen(
                    providers = providers,
                    fetchingId = fetchingId,
                    activeProviderId = settings.activeProviderId,
                    activeModel = settings.activeModel,
                    onBack = { screen = providersReturnScreen },
                    onCreate = {
                        editingProvider = null
                        screen = Screen.ProviderEdit
                    },
                    onEdit = { p ->
                        editingProvider = p
                        screen = Screen.ProviderEdit
                    },
                    onDelete = { vm.deleteProvider(it) },
                    onFetchModels = { vm.fetchModels(it) },
                    onAddManualModel = { id, m -> vm.addManualModel(id, m) },
                    onRemoveModel = { id, m -> vm.removeModel(id, m) },
                    onSelectModel = { id, m -> vm.selectModel(id, m) }
                )
            }
            Screen.ProviderEdit -> {
                ProviderEditorScreen(
                    initial = editingProvider,
                    onCancel = { screen = Screen.Providers },
                    onSave = { p ->
                        vm.upsertProvider(p)
                        p.models.firstOrNull()?.let { vm.selectModel(p.id, it) }
                        screen = Screen.Providers
                    }
                )
            }
            Screen.Memories -> {
                MemoryScreen(
                    memories = memories,
                    memoryEnabled = settings.memoryEnabled,
                    autoMemoryEnabled = settings.autoMemoryEnabled,
                    onBack = { screen = Screen.Settings },
                    onMemoryEnabledChange = { value ->
                        vm.updateSettings { it.copy(memoryEnabled = value) }
                    },
                    onAutoMemoryChange = { value ->
                        vm.updateSettings { it.copy(autoMemoryEnabled = value) }
                    },
                    onUpsert = { vm.upsertMemory(it) },
                    onDelete = { vm.deleteMemory(it) },
                    onItemEnabledChange = { id, enabled -> vm.setMemoryEnabled(id, enabled) }
                )
            }
            Screen.TtsSettings -> {
                TtsSettingsScreen(
                    settings = settings,
                    onBack = { screen = Screen.Settings },
                    onSave = { value ->
                        vm.updateSettings { value }
                        screen = Screen.Settings
                    }
                )
            }
            Screen.SearchSettings -> {
                SearchSettingsScreen(
                    settings = settings,
                    onBack = { screen = Screen.Settings },
                    onSave = { value ->
                        vm.updateSettings { value }
                        screen = Screen.Settings
                    }
                )
            }
            Screen.ChatData -> {
                ChatDataScreen(
                    activeConversationTitle = activeConv?.title,
                    busy = chatDataBusy,
                    phase = chatDataPhase,
                    onBack = { screen = Screen.Settings },
                    onPrepareArchive = vm::prepareChatArchive,
                    onImportArchive = vm::importChatArchive,
                    onGenerateHandoff = vm::generateHandoff,
                    onImportHandoff = vm::importHandoff,
                    onOpenEditor = { fileName, content ->
                        handoffFileName = fileName
                        handoffDraft = content
                        handoffReturnScreen = Screen.ChatData
                        screen = Screen.HandoffEditor
                    },
                    onImportedHandoff = { screen = Screen.Chat }
                )
            }
            Screen.HandoffEditor -> {
                HandoffEditorScreen(
                    initialFileName = handoffFileName,
                    initialJson = handoffDraft,
                    onBack = { screen = handoffReturnScreen },
                    onUseInNewChat = { raw ->
                        vm.importHandoff(raw) { screen = Screen.Chat }
                    }
                )
            }
            Screen.ProactiveMessages -> {
                ProactiveMessagesScreen(
                    settings = settings,
                    onBack = { screen = Screen.Settings },
                    onSave = { next -> vm.updateSettings { next } }
                )
            }
            Screen.Appearance -> {
                AppearanceSettingsScreen(
                    settings = settings,
                    onBack = { screen = Screen.Settings },
                    onChange = { value -> vm.updateSettings { value } }
                )
            }
            Screen.About -> {
                AboutScreen(
                    onBack = { screen = Screen.Settings }
                )
            }
            Screen.RpWorlds -> {
                RpWorldsScreen(
                    worlds = rpWorlds,
                    busy = rpBusy,
                    onBack = { screen = Screen.Chat },
                    onCreate = { screen = Screen.RpCreate },
                    onOpenWorld = { id ->
                        selectedRpWorldId = id
                        screen = if (rpWorlds.firstOrNull { it.id == id }?.setupComplete == true) {
                            Screen.RpWorld
                        } else {
                            rpSetupReturnScreen = Screen.RpWorlds
                            Screen.RpSetup
                        }
                    },
                    onDeleteWorld = rpVm::deleteWorld
                )
            }
            Screen.RpCreate -> {
                RpCreateWorldScreen(
                    busy = rpBusy,
                    phase = rpCreationPhase,
                    initialName = rpCreationDraft.nameHint,
                    initialIdea = rpCreationDraft.idea,
                    onBack = {
                        if (rpBusy) rpVm.cancelCreation() else screen = Screen.RpWorlds
                    },
                    onCancel = rpVm::cancelCreation,
                    onGenerate = { name, idea ->
                        rpVm.generateWorld(name, idea) { id ->
                            selectedRpWorldId = id
                            rpSetupReturnScreen = Screen.RpWorlds
                            screen = Screen.RpSetup
                        }
                    },
                    onBlank = { name, idea ->
                        rpVm.createBlankWorld(name, idea) { id ->
                            selectedRpWorldId = id
                            rpSetupReturnScreen = Screen.RpWorlds
                            screen = Screen.RpSetup
                        }
                    }
                )
            }
            Screen.RpAiModel -> {
                RpAiModelScreen(
                    settings = rpModelSettings,
                    providers = rpProviders,
                    busy = rpBusy,
                    onBack = { screen = rpAiReturnScreen },
                    onSelectProvider = rpVm::selectRpProvider,
                    onSaveProvider = rpVm::saveRpProvider,
                    onTest = rpVm::testRpProvider,
                    onRefresh = rpVm::refreshRpModels,
                    onAddManualModel = rpVm::addManualRpModel,
                    onProfile = rpVm::selectRpProfile,
                    onApplyPending = rpVm::applyPendingRecommendations,
                    onKeepCurrent = rpVm::keepCurrentRpConfiguration,
                    onCustom = {
                        rpModelCustomReturnScreen = Screen.RpAiModel
                        screen = Screen.RpModelCustom
                    }
                )
            }
            Screen.RpModelCustom -> {
                RpModelCustomScreen(
                    settings = rpModelSettings,
                    busy = rpBusy,
                    onBack = { screen = rpModelCustomReturnScreen },
                    onRefresh = {
                        val definition = RpAiServices.definition(rpModelSettings.activeProviderId)
                        val provider = rpProviders.firstOrNull { it.id == rpModelSettings.activeProviderId }
                        rpVm.refreshRpModels(
                            rpModelSettings.activeProviderId,
                            provider?.name ?: definition.name,
                            provider?.baseUrl ?: definition.defaultBaseUrl,
                            provider?.apiKey.orEmpty()
                        )
                    },
                    onUpdateMyModels = rpVm::updateMyModels,
                    onUpdateTask = rpVm::updateRpTaskModels
                )
            }
            Screen.RpSetup -> {
                val world = rpWorlds.firstOrNull { it.id == selectedRpWorldId }
                if (world == null) {
                    LaunchedEffect(Unit) { screen = Screen.RpWorlds }
                } else {
                    RpWorldSetupScreen(
                        world = world,
                        onBack = {
                            screen = if (world.setupComplete) rpSetupReturnScreen else Screen.RpWorlds
                        },
                        onSaveBasics = { name, summary, publicBg, privateBg, rules, style, timeRules, weather ->
                            rpVm.updateWorldBasics(
                                world.id, name, summary, publicBg, privateBg,
                                rules, style, timeRules, weather
                            )
                        },
                        onUpdateStructure = { mode, modules ->
                            rpVm.updateProjectStructure(world.id, mode, modules)
                        },
                        onUpsertLocation = { rpVm.upsertLocation(world.id, it) },
                        onDeleteLocation = { rpVm.deleteLocation(world.id, it) },
                        onUpsertCharacter = { rpVm.upsertCharacter(world.id, it) },
                        onDeleteCharacter = { rpVm.deleteCharacter(world.id, it) },
                        onUpsertScene = { rpVm.upsertScene(world.id, it) },
                        onDeleteScene = { rpVm.deleteScene(world.id, it) },
                        onUpsertStoryArc = { rpVm.upsertStoryArc(world.id, it) },
                        onDeleteStoryArc = { rpVm.deleteStoryArc(world.id, it) },
                        onUpsertStoryEvent = { rpVm.upsertStoryEvent(world.id, it) },
                        onDeleteStoryEvent = { rpVm.deleteStoryEvent(world.id, it) },
                        onUpsertState = { rpVm.upsertStateDefinition(world.id, it) },
                        onDeleteState = { rpVm.deleteStateDefinition(world.id, it) },
                        onUpsertEventRule = { rpVm.upsertEventRule(world.id, it) },
                        onDeleteEventRule = { rpVm.deleteEventRule(world.id, it) },
                        onStart = { name, summary, publicBg, privateBg, rules, style, timeRules, weather ->
                            rpVm.saveAndStartWorld(
                                world.id, name, summary, publicBg, privateBg,
                                rules, style, timeRules, weather
                            )
                            screen = Screen.RpWorld
                        }
                    )
                }
            }
            Screen.RpWorld -> {
                val world = rpWorlds.firstOrNull { it.id == selectedRpWorldId }
                if (world == null) {
                    LaunchedEffect(Unit) { screen = Screen.RpWorlds }
                } else {
                    RpWorldScreen(
                        world = world,
                        busy = rpBusy,
                        onBack = { screen = Screen.RpWorlds },
                        onOpenSettings = { screen = Screen.RpWorldSettings },
                        onOpenMap = { screen = Screen.RpMap },
                        onOpenStates = {
                            rpStatesReturnScreen = Screen.RpWorld
                            screen = Screen.RpStates
                        },
                        onOpenCharacter = { id ->
                            selectedRpCharacterId = id
                            characterReturnScreen = Screen.RpWorld
                            screen = Screen.RpCharacter
                        },
                        onStartDialogue = { id ->
                            selectedRpCharacterId = id
                            rpVm.startDialogue(world.id, id)
                            screen = Screen.RpDialogue
                        },
                        onTravel = { rpVm.travel(world.id, it) },
                        onSubmit = { text, expand -> rpVm.submitWorldAction(world.id, text, expand) }
                    )
                }
            }
            Screen.RpWorldSettings -> {
                val world = rpWorlds.firstOrNull { it.id == selectedRpWorldId }
                if (world == null) {
                    LaunchedEffect(Unit) { screen = Screen.RpWorlds }
                } else {
                    RpWorldSettingsScreen(
                        world = world,
                        onBack = { screen = Screen.RpWorld },
                        onEditWorld = {
                            rpSetupReturnScreen = Screen.RpWorldSettings
                            screen = Screen.RpSetup
                        },
                        onOpenMap = { screen = Screen.RpMap },
                        onOpenStates = {
                            rpSetupReturnScreen = Screen.RpWorldSettings
                            screen = Screen.RpSetup
                        },
                        onOpenCharacters = {
                            rpSetupReturnScreen = Screen.RpWorldSettings
                            screen = Screen.RpSetup
                        },
                        onOpenPortraits = { screen = Screen.RpPortraitSettings },
                        onOpenData = { screen = Screen.RpData }
                    )
                }
            }
            Screen.RpMap -> {
                val world = rpWorlds.firstOrNull { it.id == selectedRpWorldId }
                if (world == null) {
                    LaunchedEffect(Unit) { screen = Screen.RpWorlds }
                } else {
                    RpMapScreen(
                        world = world,
                        busy = rpBusy,
                        onBack = { screen = Screen.RpWorld },
                        onTravel = {
                            rpVm.travel(world.id, it)
                            screen = Screen.RpWorld
                        },
                        onManage = {
                            rpSetupReturnScreen = Screen.RpMap
                            screen = Screen.RpSetup
                        }
                    )
                }
            }
            Screen.RpModelParameters -> {
                RpModelParametersScreen(
                    settings = rpModelSettings,
                    onBack = { screen = Screen.Settings },
                    onRecommendedChanged = rpVm::setUseRecommendedParameters,
                    onTemperatureChanged = rpVm::setRpTemperature,
                    onCreationTimeoutChanged = rpVm::setCreationTimeoutSeconds,
                    onCreationMaxOutputChanged = rpVm::setCreationMaxOutputTokens
                )
            }
            Screen.RpPortraitSettings -> {
                val world = rpWorlds.firstOrNull { it.id == selectedRpWorldId }
                if (world == null) {
                    LaunchedEffect(Unit) { screen = Screen.RpWorlds }
                } else {
                    RpPortraitSettingsScreen(
                        world = world,
                        settings = rpModelSettings,
                        providers = rpProviders,
                        onBack = { screen = Screen.RpWorldSettings },
                        onEnabledChanged = { rpVm.setPortraitGenerationEnabled(world.id, it) },
                        onSave = { providerId, modelId, visualStyle ->
                            rpVm.savePortraitSettings(world.id, providerId, modelId, visualStyle)
                        }
                    )
                }
            }
            Screen.RpData -> {
                val world = rpWorlds.firstOrNull { it.id == selectedRpWorldId }
                if (world == null) {
                    LaunchedEffect(Unit) { screen = Screen.RpWorlds }
                } else {
                    RpDataManagementScreen(
                        world = world,
                        onBack = { screen = Screen.RpWorldSettings },
                        onExportHandoff = {
                            handoffFileName = "${world.name}-RP交接包.json"
                            handoffDraft = ChatDataCodec.encodeHandoff(RpHandoffBuilder.build(world))
                            handoffReturnScreen = Screen.RpData
                            screen = Screen.HandoffEditor
                        },
                        onProactiveChanged = { rpVm.setProactiveMessagesEnabled(world.id, it) },
                        onDeleteWorld = {
                            rpVm.deleteWorld(world.id)
                            selectedRpWorldId = null
                            screen = Screen.RpWorlds
                        }
                    )
                }
            }
            Screen.RpStates -> {
                val world = rpWorlds.firstOrNull { it.id == selectedRpWorldId }
                if (world == null) {
                    LaunchedEffect(Unit) { screen = Screen.RpWorlds }
                } else {
                    RpStatesScreen(
                        world = world,
                        onBack = { screen = rpStatesReturnScreen },
                        onUpdateValue = { definitionId, targetId, value ->
                            rpVm.updateStateValue(world.id, definitionId, targetId, value)
                        }
                    )
                }
            }
            Screen.RpCharacter -> {
                val world = rpWorlds.firstOrNull { it.id == selectedRpWorldId }
                val character = world?.characters?.firstOrNull { it.id == selectedRpCharacterId }
                if (world == null || character == null || !character.hasAppeared) {
                    LaunchedEffect(Unit) { screen = Screen.RpWorld }
                } else {
                    RpCharacterScreen(
                        world = world,
                        character = character,
                        busy = rpBusy,
                        onBack = { screen = characterReturnScreen },
                        onStartDialogue = {
                            if (world.activeDialogue?.characterId != character.id) {
                                rpVm.startDialogue(world.id, character.id)
                            }
                            screen = Screen.RpDialogue
                        },
                        onUpdateCharacter = { rpVm.upsertCharacter(world.id, it) },
                        onDelete = {
                            rpVm.deleteCharacter(world.id, character.id)
                            screen = Screen.RpWorld
                        },
                        onRegeneratePortrait = { rpVm.regeneratePortrait(world.id, character.id) }
                    )
                }
            }
            Screen.RpDialogue -> {
                val world = rpWorlds.firstOrNull { it.id == selectedRpWorldId }
                val characterId = world?.activeDialogue?.characterId ?: selectedRpCharacterId
                val character = world?.characters?.firstOrNull { it.id == characterId }
                if (world == null || character == null) {
                    LaunchedEffect(Unit) { screen = Screen.RpWorld }
                } else {
                    RpDialogueScreen(
                        world = world,
                        character = character,
                        busy = rpBusy,
                        onBack = {
                            rpVm.endDialogue(world.id)
                            screen = Screen.RpWorld
                        },
                        onOpenCharacter = {
                            selectedRpCharacterId = character.id
                            characterReturnScreen = Screen.RpDialogue
                            screen = Screen.RpCharacter
                        },
                        onSend = { rpVm.sendDialogueMessage(world.id, it) },
                        onEndDialogue = {
                            rpVm.endDialogue(world.id)
                            screen = Screen.RpWorld
                        }
                    )
                }
            }
        }

        SnackbarHost(hostState = snackbar, modifier = Modifier.fillMaxSize())
    }

    if (showModelPicker) {
        ModelPickerSheet(
            providers = providers,
            activeProviderId = settings.activeProviderId,
            activeModel = settings.activeModel,
            onPick = { pid, m -> vm.selectModel(pid, m) },
            onDismiss = { showModelPicker = false }
        )
    }

    error?.let { msg ->
        val needsProviderFix = msg.contains("provider", ignoreCase = true)
            || msg.contains("api key", ignoreCase = true)
            || msg.contains("model", ignoreCase = true)
            || msg.contains("API")
            || msg.contains("模型")
            || msg.contains("Base URL")
            || msg.contains("401")
            || msg.contains("403")
        AlertDialog(
            onDismissRequest = { vm.clearError() },
            confirmButton = {
                if (needsProviderFix) {
                    TextButton(onClick = {
                        vm.clearError()
                        screen = Screen.Providers
                    }) { Text(stringResource(R.string.error_open_providers)) }
                } else {
                    TextButton(onClick = { vm.clearError() }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            },
            dismissButton = if (needsProviderFix) {
                {
                    TextButton(onClick = { vm.clearError() }) {
                        Text(stringResource(R.string.ok))
                    }
                }
            } else null,
            title = { Text(stringResource(R.string.error_dialog_title)) },
            text = { Text(msg) }
        )
    }
    rpCreationError?.let { failure ->
        var showDetails by remember(failure.kind, failure.detail) { mutableStateOf(false) }
        val recover: (RpCreationRecovery) -> Unit = { action ->
            when (action) {
                RpCreationRecovery.RETRY -> rpVm.retryCreation()
                RpCreationRecovery.AI_SETTINGS,
                RpCreationRecovery.REFRESH_MODELS -> {
                    rpVm.clearCreationError()
                    rpAiReturnScreen = Screen.RpCreate
                    screen = Screen.RpAiModel
                }
                RpCreationRecovery.CHANGE_MODEL -> {
                    rpVm.clearCreationError()
                    rpModelCustomReturnScreen = Screen.RpCreate
                    screen = Screen.RpModelCustom
                }
            }
        }
        AlertDialog(
            onDismissRequest = rpVm::clearCreationError,
            confirmButton = {
                val action = failure.primaryAction
                TextButton(onClick = {
                    if (action == null) rpVm.clearCreationError() else recover(action)
                }) { Text(action?.let(failure::actionLabel) ?: stringResource(R.string.ok)) }
            },
            dismissButton = {
                Column {
                    failure.secondaryAction?.let { action ->
                        TextButton(onClick = { recover(action) }) {
                            Text(failure.actionLabel(action))
                        }
                    }
                    TextButton(onClick = rpVm::clearCreationError) { Text("取消") }
                }
            },
            title = { Text(failure.title) },
            text = {
                Column {
                    Text(failure.message)
                    TextButton(onClick = { showDetails = !showDetails }) {
                        Text(if (showDetails) "收起详情" else "查看详情")
                    }
                    if (showDetails) Text(failure.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        )
    }
    rpError?.let { msg ->
        AlertDialog(
            onDismissRequest = { rpVm.clearError() },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (canRetryRpWorldResponse) rpVm.retryLastWorldAction()
                        else rpVm.clearError()
                    }
                ) {
                    Text(if (canRetryRpWorldResponse) "重新生成" else stringResource(R.string.ok))
                }
            },
            dismissButton = if (canRetryRpWorldResponse) {
                {
                    TextButton(onClick = { rpVm.clearError() }) { Text("取消") }
                }
            } else null,
            title = { Text("RP 项目") },
            text = { Text(msg) }
        )
    }
}
