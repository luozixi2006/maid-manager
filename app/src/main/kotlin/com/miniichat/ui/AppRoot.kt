package com.miniichat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miniichat.ChatViewModel
import com.miniichat.data.ProviderConfig
import com.miniichat.proactive.ProactiveNavigation
import com.miniichat.update.UpdateViewModel
import kotlinx.coroutines.launch

private enum class Screen {
    Chat, Settings, Providers, ProviderEdit, Assistants, Memories, TtsSettings, SearchSettings,
    Appearance, About, ChatData, HandoffEditor, ProactiveMessages, ErrorCenter, Updates, Tasks, Work
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: ChatViewModel) {
    val updateVm: UpdateViewModel = viewModel()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(Screen.Chat) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderConfig?>(null) }
    var providersReturnScreen by rememberSaveable { mutableStateOf(Screen.Settings) }
    var personaReturnScreen by rememberSaveable { mutableStateOf(Screen.Settings) }
    var personaToEdit by rememberSaveable { mutableStateOf<String?>(null) }
    val screenState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var handoffFileName by remember { mutableStateOf("女仆管理器-交接包.json") }
    var handoffDraft by remember { mutableStateOf("") }
    var handoffReturnScreen by rememberSaveable { mutableStateOf(Screen.ChatData) }

    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
    androidx.activity.compose.BackHandler(enabled = showModelPicker) {
        showModelPicker = false
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Settings) {
        screen = Screen.Chat
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Work) { screen = Screen.Chat }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Providers) {
        screen = providersReturnScreen
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.ProviderEdit) {
        screen = Screen.Providers
    }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Assistants) {
        screen = personaReturnScreen
    }
    androidx.activity.compose.BackHandler(
        enabled = screen in setOf(
            Screen.Memories, Screen.TtsSettings, Screen.SearchSettings, Screen.Appearance, Screen.About,
            Screen.ChatData, Screen.ProactiveMessages, Screen.ErrorCenter, Screen.Updates
        )
    ) { screen = Screen.Settings }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.Tasks) { screen = Screen.Work }
    androidx.activity.compose.BackHandler(enabled = screen == Screen.HandoffEditor) {
        screen = handoffReturnScreen
    }

    val conversations by vm.conversations.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val settings by vm.settings.collectAsState()
    val providers by vm.providers.collectAsState()
    val isStreaming by vm.isStreaming.collectAsState()
    val streamingOverlay by vm.streamingOverlay.collectAsState()
    val error by vm.error.collectAsState()
    val errors by vm.errors.collectAsState()
    val updateState by updateVm.state.collectAsState()
    val toast by vm.toast.collectAsState()
    val fetchingId by vm.fetchingModelsFor.collectAsState()
    val memories by vm.memories.collectAsState()
    val ttsState by vm.ttsState.collectAsState()
    val chatDataBusy by vm.chatDataBusy.collectAsState()
    val chatDataPhase by vm.chatDataPhase.collectAsState()
    val proactiveDestination by ProactiveNavigation.destination.collectAsState()
    val proactiveNotice by ProactiveNavigation.foregroundNotice.collectAsState()
    val openTasks by com.miniichat.tasks.TaskNavigation.open.collectAsState()
    LaunchedEffect(openTasks) {
        if (openTasks) { screen = if (com.miniichat.tasks.TaskNavigation.permission.value.isNotBlank() || com.miniichat.tasks.TaskNavigation.companion.value) Screen.Tasks else Screen.Work; com.miniichat.tasks.TaskNavigation.open.value = false }
    }

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
    LaunchedEffect(proactiveNotice) {
        proactiveNotice?.let {
            snackbar.showSnackbar(it, duration = SnackbarDuration.Short)
            ProactiveNavigation.clearForegroundNotice()
        }
    }
    LaunchedEffect(proactiveDestination) {
        proactiveDestination?.let { destination ->
            if (destination.sourceType == "normal" && destination.conversationId.isNotBlank()) {
                vm.selectConversation(destination.conversationId)
                screen = Screen.Chat
                ProactiveNavigation.clearDestination()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .then(if (screen != Screen.Chat) Modifier.navigationBarsPadding() else Modifier)
    ) {
        Column(Modifier.fillMaxSize()) {
        if (screen == Screen.Chat || screen == Screen.Work) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = androidx.compose.ui.unit.Dp(20f))) {
                listOf(Screen.Chat to "聊天", Screen.Work to "工作").forEach { (destination, label) ->
                    TextButton(onClick = { screen = destination }, modifier = Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.titleMedium,
                            color = if (screen == destination) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Box(Modifier.weight(1f).then(if (screen == Screen.Chat || screen == Screen.Work) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)) {
        androidx.compose.animation.Crossfade(targetState = screen,
            animationSpec = androidx.compose.animation.core.tween(160), label = "page") { currentScreen ->
        screenState.SaveableStateProvider(currentScreen.name) {
        when (currentScreen) {
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
                        assistantName = conversationAssistant?.displayName ?: "女仆",
                        assistantAvatar = conversationAssistant?.avatarPath,
                        isStreaming = isStreaming,
                        streamingOverlay = streamingOverlay,
                        ttsState = ttsState,
                        supportsReasoning = vm.supportsNativeReasoning(
                            activeProvider,
                            settings.activeModel
                        ),
                        onMenu = { scope.launch { drawerState.open() } },
                        onSend = { text, photos -> vm.sendMessage(text, photos) },
                        onStop = { vm.stopStreaming() },
                        onRegenerate = { vm.regenerate() },
                        onRegenerateFrom = { msgId -> vm.regenerateFrom(msgId) },
                        onDeleteMessage = { msgId -> vm.deleteMessage(msgId) },
                        onEditMessage = { msgId, newText -> vm.editMessage(msgId, newText) },
                        onPlayMessage = { vm.playMessage(it) },
                        onWebEnabledChange = { vm.setWebEnabled(it) },
                        onReasoningEnabledChange = { vm.setReasoningEnabled(it) },
                        onReasoningEffortChange = { vm.setReasoningEffort(it) },
                        onSaveImage = { path, uri -> vm.saveImageAttachment(path, uri) },
                        onOpenErrors = { screen = Screen.ErrorCenter },
                        onPhotoError = vm::reportPhotoFailure,
                        onNew = { vm.newConversation() },
                        onOpenSettings = { screen = Screen.Settings },
                        onOpenTasks = { draft ->
                            com.miniichat.tasks.TaskNavigation.chatDraft.value = draft
                            screen = Screen.Work
                        },
                        onEditPersona = {
                            personaReturnScreen = Screen.Chat
                            personaToEdit = conversationAssistant?.id
                            screen = Screen.Assistants
                        },
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
                SettingsScreen(
                    settings = settings,
                    providers = providers,
                    assistants = assistants,
                    memoryCount = memories.size,
                    errorCount = errors.size,
                    onBack = { screen = Screen.Chat },
                    onOpenProviders = {
                        providersReturnScreen = Screen.Settings
                        screen = Screen.Providers
                    },
                    onOpenAssistants = {
                        personaReturnScreen = Screen.Settings
                        personaToEdit = null
                        screen = Screen.Assistants
                    },
                    onOpenMemories = { screen = Screen.Memories },
                    onOpenTts = { screen = Screen.TtsSettings },
                    onOpenSearch = { screen = Screen.SearchSettings },
                    onOpenChatData = { screen = Screen.ChatData },
                    onOpenProactiveMessages = { screen = Screen.ProactiveMessages },
                    onOpenTasks = { screen = Screen.Tasks },
                    onOpenErrors = { screen = Screen.ErrorCenter },
                    onOpenUpdates = { screen = Screen.Updates },
                    onOpenAppearance = { screen = Screen.Appearance },
                    onOpenAbout = { screen = Screen.About }
                )
            }
            Screen.Assistants -> {
                AssistantsScreen(
                    assistants = assistants,
                    editOnOpenId = personaToEdit,
                    activeId = settings.activeAssistantId,
                    onBack = { screen = personaReturnScreen },
                    onSelect = { vm.selectAssistant(it) },
                    onUpsert = { vm.upsertAssistant(it) },
                    onPhotoError = vm::reportPhotoFailure,
                    onDelete = { vm.deleteAssistant(it) },
                    legacyProactiveEnabled = settings.proactiveMessagesEnabled,
                    onQuietHours = { screen = Screen.ProactiveMessages }
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
                    onBack = { screen = Screen.Assistants },
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
            Screen.ErrorCenter -> {
                ErrorCenterScreen(
                    errors = errors,
                    onBack = { screen = Screen.Settings },
                    onDelete = vm::deleteError,
                    onClear = vm::clearErrorHistory
                )
            }
            Screen.Updates -> {
                UpdateScreen(
                    state = updateState,
                    onBack = { screen = Screen.Settings },
                    onCheck = updateVm::check,
                    onDownload = updateVm::download,
                    onInstall = updateVm::install
                )
            }
            Screen.Work -> com.miniichat.tasks.WorkScreen(onSettings = { screen = Screen.Tasks }, onModel = {
                if (providers.isEmpty()) { providersReturnScreen = Screen.Work; editingProvider = null; screen = Screen.ProviderEdit }
                else showModelPicker = true
            }, model = settings.activeModel)
            Screen.Tasks -> com.miniichat.tasks.TasksScreen(onBack = { screen = Screen.Work }, onPersona = {
                personaReturnScreen = Screen.Tasks; personaToEdit = settings.activeAssistantId; screen = Screen.Assistants
            })
        }

        }
        }
        }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
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

    error?.let { currentError ->
        AppErrorDialog(
            error = currentError,
            onDismiss = vm::clearError,
            onOpenDetails = {
                vm.clearError()
                screen = Screen.ErrorCenter
            }
        )
    }
}
