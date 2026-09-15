package com.miniichat.tasks

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.miniichat.ui.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TasksScreen(onBack: () -> Unit, onPersona: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { TaskStore.of(context) }
    val revision by store.revision.collectAsState()
    val draft by TaskNavigation.chatDraft.collectAsState()
    var tasks by remember { mutableStateOf(emptyList<PhoneTask>()) }
    var rules by remember { mutableStateOf(emptyList<TriggerRule>()) }
    var goal by rememberSaveable { mutableStateOf(draft) }
    var scopePath by rememberSaveable { mutableStateOf("") }
    var rootDirectory by rememberSaveable { mutableStateOf(FolderSelection.saved(context)) }
    var choosingFolder by remember { mutableStateOf(false) }
    var browsingPath by remember { mutableStateOf(rootDirectory) }
    var eventFolder by rememberSaveable { mutableStateOf(FolderSelection.saved(context)) }
    var choosingEventFolder by remember { mutableStateOf(false) }
    var consent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    val openCompanion by TaskNavigation.companion.collectAsState()
    LaunchedEffect(openCompanion) { if (openCompanion) { tab = 1; TaskNavigation.companion.value = false } }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(revision) { withContext(Dispatchers.IO) { store.all() to store.rules() }.let { tasks = it.first; rules = it.second } }
    LaunchedEffect(draft) { if (draft.isNotBlank()) { goal = draft; TaskNavigation.chatDraft.value = "" } }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            refresh++; scope.launch(Dispatchers.IO) { TaskActions.recover(context) }
        } }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    fun settings(action: String, packageUri: Boolean = false) {
        runCatching { context.startActivity(Intent(action).apply {
            if (packageUri) data = Uri.parse("package:${context.packageName}")
            if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }) }.onFailure { message = "请在系统应用设置中手动开启此权限" }
    }
    fun grantTaskPermission(kind: String) {
        when (kind) {
            "files" -> if (Build.VERSION.SDK_INT >= 30) settings(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, true)
                else runtime.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            "accessibility" -> settings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notifications" -> settings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            else -> settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
        }
    }
    val requestedPermission by TaskNavigation.permission.collectAsState()
    LaunchedEffect(requestedPermission) { if (requestedPermission.isNotBlank()) {
        grantTaskPermission(requestedPermission); TaskNavigation.permission.value = ""
    } }
    fun returnToApprovedApp(task: PhoneTask) {
        val step = task.steps.getOrNull(task.cursor) ?: return
        if (task.engineVersion < 2 || com.miniichat.tasks.agent.AgentPolicy.specs[step.tool]?.permission != "accessibility") return
        val pkg = step.arguments["package"].orEmpty()
        if (!com.miniichat.tasks.agent.AgentPolicy.validPackage(pkg) || pkg !in task.allowedPackages ||
            pkg !in TaskActions.preferences(context).getStringSet("agent_apps", emptySet()).orEmpty()) return
        runCatching { context.packageManager.getLaunchIntentForPackage(pkg)?.let(context::startActivity) }
            .onFailure { message = "请手动切回目标应用，再从任务通知继续" }
    }
    val filesGranted = remember(refresh) { DownloadsTools(context, "").permitted() }
    val overlayGranted = remember(refresh) { Settings.canDrawOverlays(context) }
    fun chooseFolder(forEvent: Boolean = false, initialPath: String = rootDirectory) {
        if (!filesGranted) {
            message = "请先在系统中允许文件访问，回来后点“选择文件夹”。不会扩大已创建任务的范围。"
            if (Build.VERSION.SDK_INT >= 30) settings(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, true)
            else runtime.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        } else if (forEvent) choosingEventFolder = true else { browsingPath = initialPath; choosingFolder = true }
    }
    if (choosingFolder || choosingEventFolder) {
        FolderPicker(if (choosingEventFolder) eventFolder else browsingPath,
        { choosingFolder = false; choosingEventFolder = false }, { folder ->
            if (choosingEventFolder) eventFolder = folder else { rootDirectory = folder; scopePath = ""; consent = false; FolderSelection.remember(context, folder) }
            choosingFolder = false; choosingEventFolder = false
        })
        return
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding()) {
            SettingsTopBar("任务与陪伴", onBack)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(14.dp)).padding(4.dp)) {
                listOf("帮我办事", "屏幕陪伴", "事件提醒").forEachIndexed { index, text ->
                    TextButton(onClick = { tab = index }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.textButtonColors(containerColor = if (tab == index) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = if (tab == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)) { Text(text) }
                }
            }
            key(tab) { Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (message.isNotBlank()) { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = { message = "" }) { Text("知道了") } }
                when (tab) {
                    0 -> {
                        SectionHeading("有什么需要我做？")
                        Text("整理文件、查资料、写文本或准备日程。需要你确认时会来问你。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AppTextField(goal, { goal = it }, label = { Text("告诉我想做什么") }, placeholder = { Text("例如：按类型整理下载内容，保留所有原文件") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                        AppGroup {
                            PreferenceRow("文件夹 · ${FolderSelection.label(rootDirectory)}", "自动记住选择 · 不需要手填英文路径") {
                                TextButton({ chooseFolder() }) { Text("选择文件夹") }
                            }
                            Text("实际位置：$rootDirectory", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row { Checkbox(consent, { consent = it }); Text("允许读取所选目录，并将必要文字交给当前模型。页面与通知正文另行确认。", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Button(modifier = Modifier.fillMaxWidth(), enabled = consent && goal.isNotBlank() && !busy, onClick = {
                            busy = true; scope.launch {
                                try { val task = withContext(Dispatchers.IO) { TaskActions.create(context, goal, scopePath.trim(), rootDirectory = rootDirectory.trim()) }
                                    selected = task.id; goal = ""; consent = false
                                } catch (e: Exception) { message = e.message?.take(200) ?: "任务创建失败" }
                                finally { busy = false }
                            }
                        }) { Text(if (busy) "正在保存…" else "交给我做") }
                        SectionHeading("最近任务")
                        if (tasks.isEmpty()) Text("还没有任务，交给我第一件事吧。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        tasks.forEach { task ->
                            AppGroup {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text(task.goal, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                        TextButton(onClick = { selected = if (selected == task.id) null else task.id }) { Text(if (selected == task.id) "收起" else "查看") }
                                    }
                                    Text(if (task.noticeOnly) "事件提醒 · 没有读取文件" else "${task.state.label} · 已完成 ${task.cursor} 步 · ${FolderSelection.label(task.rootDirectory + if (task.scope.isBlank()) "" else "/${task.scope}")}", style = MaterialTheme.typography.labelMedium)
                                    val createdFolders = task.steps.filter { it.tool == "mkdir" && it.done }.map { it.resolvedTarget.ifBlank { it.destination } }.filter { it.isNotBlank() }.distinct()
                                    if (createdFolders.isNotEmpty()) Disclosure("打开本次创建的文件夹 · ${createdFolders.size}") {
                                        createdFolders.forEach { relative ->
                                            val actual = listOf(task.rootDirectory, task.scope, relative).filter { it.isNotBlank() }.joinToString("/")
                                            TextButton({ chooseFolder(initialPath = actual) }) { Text("打开：${FolderSelection.label(actual)}") }
                                        }
                                    }
                                    if (selected != task.id) Text(task.detail, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                    if (selected == task.id) {
                                    if (task.state in setOf(TaskState.APPROVAL, TaskState.QUESTION, TaskState.PERMISSION, TaskState.FAILED)) Text(task.detail, style = MaterialTheme.typography.bodyMedium)
                                    else Text(task.detail, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                    when (task.state) {
                                        TaskState.PERMISSION -> TextButton(onClick = { grantTaskPermission(task.neededPermission) }) { Text("开启所需权限") }
                                        TaskState.APPROVAL -> {
                                            TextButton(onClick = {
                                                TaskActions.respond(context, task.id, "approve", token = task.approvalToken)
                                                returnToApprovedApp(task)
                                            }) { Text("允许一次") }
                                            if (task.approvedSuggestion) {
                                                if (task.engineVersion < 2 || task.steps.getOrNull(task.cursor)?.let { com.miniichat.tasks.agent.AgentPolicy.canRemember(it) } == true)
                                                    TextButton(onClick = { TaskActions.respond(context, task.id, "always", token = task.approvalToken); returnToApprovedApp(task) }) { Text("此范围内同类操作以后自动允许") }
                                                TextButton(onClick = { TaskActions.respond(context, task.id, "skip", token = task.approvalToken) }) { Text("跳过此步") }
                                            }
                                        }
                                        TaskState.QUESTION -> {
                                            if (task.engineVersion >= 2 && task.neededPermission == "handoff") TextButton(onClick = {
                                                scope.launch { runCatching { com.miniichat.tasks.agent.AgentActions.launchSystem(context, task.id, task.approvalToken) }
                                                    .onFailure { message = it.message ?: "无法打开系统操作" } }
                                            }) { Text("打开系统操作") }
                                            task.steps.getOrNull(task.cursor)?.options?.forEach { option ->
                                                TextButton(onClick = { TaskActions.respond(context, task.id, "answer", option, task.approvalToken) }) { Text(option) }
                                            }
                                            var answer by rememberSaveable(task.id) { mutableStateOf("") }
                                            AppTextField(answer, { answer = it }, label = { Text("也可以直接告诉我") })
                                            TextButton(enabled = answer.isNotBlank(), onClick = { TaskActions.respond(context, task.id, "answer", answer, task.approvalToken) }) { Text("回答并继续") }
                                        }
                                        TaskState.FAILED, TaskState.PAUSED -> {
                                            TextButton(onClick = { TaskActions.respond(context, task.id, "resume") }) { Text("从原步骤继续") }
                                            if (task.state == TaskState.FAILED) TextButton(onClick = { TaskActions.respond(context, task.id, "skip", token = task.approvalToken) }) { Text("跳过未执行的这一步") }
                                        }
                                        else -> if (TaskActions.active(task.state)) TextButton(onClick = { TaskActions.respond(context, task.id, "pause") }) { Text("暂停") }
                                    }
                                    if (task.state !in setOf(TaskState.DONE, TaskState.CANCELLED)) TextButton(onClick = { TaskActions.respond(context, task.id, "cancel") }) { Text("取消后续操作") }
                                    if (task.engineVersion >= 2 && task.state in setOf(TaskState.PERMISSION, TaskState.FAILED, TaskState.PAUSED)) TextButton(onClick = {
                                        store.change(task.id) { it.copy(allowedPackages = TaskActions.preferences(context).getStringSet("agent_apps", emptySet()).orEmpty().toList()) }
                                        message = "已把你在权限页选定的应用授权给此任务，可继续原任务"
                                    }) { Text("更新此任务的应用授权") }
                                    Disclosure("执行记录") {
                                        Text(task.detail, style = MaterialTheme.typography.bodyMedium)
                                        Text("模型：${task.model}\n服务在任务创建时锁定。", style = MaterialTheme.typography.bodySmall)
                                        Text(task.history.joinToString("\n").ifBlank { "还没有执行操作" }, style = MaterialTheme.typography.bodySmall)
                                    }
                                    }
                                }
                            }
                        }
                        Disclosure("办事权限 · 需要时再开启") {
                        SectionHeading("访问权限")
                        AppGroup {
                            PreferenceRow("文件访问", if (filesGranted) "已允许" else "需要时再开启") {
                                TextButton(onClick = {
                                    if (Build.VERSION.SDK_INT >= 30) settings(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, true)
                                    else runtime.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                                }) { Text("管理") }
                            }
                            PreferenceRow("任务通知", "接收进度与确认") {
                                TextButton(onClick = { if (Build.VERSION.SDK_INT >= 33) runtime.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) else settings(Settings.ACTION_APP_NOTIFICATION_SETTINGS) }) { Text("管理") }
                            }
                            PreferenceRow("页面操作", "仅限你授权的应用") {
                                TextButton(onClick = { settings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }) { Text("管理") }
                            }
                            PreferenceRow("通知访问") { TextButton(onClick = { settings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }) { Text("管理") } }
                        }
                        Disclosure("页面操作与数据使用") {
                            Text("无障碍可读取页面、点击、滚动和输入。仅限下面选中的应用；页面和通知正文交给当前模型前需确认。密码页及系统授权页不操作，可随时撤销。", style = MaterialTheme.typography.bodySmall)
                        }
                        var allowedApps by remember(refresh) { mutableStateOf(TaskActions.preferences(context).getStringSet("agent_apps", emptySet()).orEmpty().toSet()) }
                        var appList by remember { mutableStateOf(emptyMap<String, String>()) }
                        LaunchedEffect(Unit) { appList = withContext(Dispatchers.IO) { com.miniichat.tasks.agent.NativePhoneTools.apps(context).filterKeys(com.miniichat.tasks.agent.AgentPolicy::validPackage) } }
                        Disclosure("允许操作的应用 · ${allowedApps.size}") {
                            AppGroup {
                                appList.toList().sortedBy { it.second }.forEach { (pkg, label) ->
                                    PreferenceRow(label) { Checkbox(pkg in allowedApps, { yes ->
                                        allowedApps = if (yes) allowedApps + pkg else allowedApps - pkg
                                        TaskActions.preferences(context).edit().putStringSet("agent_apps", allowedApps).apply()
                                    }) }
                                }
                            }
                        }
                        val approvals = remember(revision, refresh) { TaskActions.preferences(context).all.filter { (it.key.startsWith("approve:") || it.key.startsWith("agent:")) && it.value == true }.keys }
                        Disclosure("自动批准 · ${approvals.size}") {
                            Text(approvals.joinToString("\n") { it.removePrefix("approve:").removePrefix("agent:") }.ifBlank { "还没有自动批准的操作" }, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { TaskActions.preferences(context).edit().apply { approvals.forEach { remove(it) } }.commit(); refresh++ }) { Text("全部撤销") }
                        }
                        Disclosure("事件提醒所需权限") {
                            PreferenceRow("使用情况访问") { TextButton(onClick = { settings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }) { Text("管理") } }
                            PreferenceRow("识别 Wi-Fi") { TextButton(onClick = { runtime.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("授权") } }
                            TextButton(onClick = { settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) { Text("系统应用设置") }
                            Text("指定 Wi-Fi 名称需要精确位置、后台位置和系统定位。匹配任意 Wi-Fi 不需要位置权限。", style = MaterialTheme.typography.bodySmall)
                        }
                        }
                    }
                    1 -> {
                        CompanionSettings(refresh, { settings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) }, { message = it }, onPersona)
                        AppGroup {
                            PreferenceRow("角色消息通知", "离开应用后也能收到问候") {
                                TextButton(onClick = { if (Build.VERSION.SDK_INT >= 33) runtime.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) else settings(Settings.ACTION_APP_NOTIFICATION_SETTINGS) }) { Text("允许通知") }
                            }
                        }
                    }
                    2 -> {
                        var creatingRule by rememberSaveable { mutableStateOf(false) }
                        SectionHeading("发生这件事时提醒我") { TextButton(onClick = { creatingRule = !creatingRule }) { Text(if (creatingRule) "取消" else "添加") } }
                        Text("这是可选的手机事件提醒，不是日常陪伴开关。问候和闲聊跟随人设。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onPersona) { Text("去人设设置主动聊天") }
                        if (rules.isEmpty() && !creatingRule) Text("例如：出现新文件时问我要不要整理，或充电时提醒休息。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Disclosure("运行方式与隐私") {
                            Text("事件类型和规则文字交给当前模型判断，不上传通知正文。普通提醒不读文件，整理建议先询问。每条规则至少间隔一小时。", style = MaterialTheme.typography.bodySmall)
                            Text("悬浮头像开启时约15秒检查；关闭后由系统调度，可能延迟或漏检。时间触发不是精确闹钟。", style = MaterialTheme.typography.bodySmall)
                        }
                        if (creatingRule) {
                        var kind by rememberSaveable { mutableStateOf(TriggerKind.FILES) }
                        var match by rememberSaveable { mutableStateOf("") }
                        var eventGoal by rememberSaveable { mutableStateOf("") }
                        var expanded by remember { mutableStateOf(false) }
                        var appsExpanded by remember { mutableStateOf(false) }
                        var apps by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
                        LaunchedEffect(kind) {
                            if (kind in setOf(TriggerKind.APP, TriggerKind.NOTIFICATION)) apps = withContext(Dispatchers.IO) {
                                @Suppress("DEPRECATION")
                                context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                                    .map { it.activityInfo.packageName to it.loadLabel(context.packageManager).toString() }
                                    .distinctBy { it.first }.sortedBy { it.second }
                            }
                        }
                        Box { FilledTonalButton(onClick = { expanded = true }) { Text(kind.label) }
                            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                TriggerKind.entries.forEach { k -> DropdownMenuItem(text = { Text(k.label) }, onClick = { kind = k; expanded = false; match = "" }) }
                            }
                        }
                        if (kind in setOf(TriggerKind.NOTIFICATION, TriggerKind.APP)) {
                            Box {
                                FilledTonalButton(onClick = { appsExpanded = true }) { Text(apps.firstOrNull { it.first == match }?.second ?: "选择应用") }
                                DropdownMenu(appsExpanded, onDismissRequest = { appsExpanded = false }) {
                                    apps.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { match = id; appsExpanded = false }) }
                                }
                            }
                        }
                        if (kind in setOf(TriggerKind.WIFI, TriggerKind.TIME)) {
                            AppTextField(match, { match = it }, modifier = Modifier.fillMaxWidth(), label = { Text(when (kind) {
                                TriggerKind.TIME -> "每天时间，如 18:30"
                                TriggerKind.WIFI -> "Wi-Fi 名称；留空匹配任意 Wi-Fi"
                                else -> "应用包名，如 com.tencent.mm"
                            }) })
                        }
                        AppTextField(eventGoal, { eventGoal = it }, label = { Text("发生时怎么提醒我") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        if (kind == TriggerKind.FILES) {
                            TextButton({ chooseFolder(true) }) { Text("观察目录：${FolderSelection.label(eventFolder)} · 更换") }
                            Text("观察目录内新增文件，不限格式；首次只记录基线，不读取正文。", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("开启后，事件类型和此规则会交给当前模型判断；通知正文不会上传。", style = MaterialTheme.typography.bodySmall)
                        Button(enabled = eventGoal.isNotBlank(), onClick = {
                            runCatching {
                                ToolPolicy.relative(eventFolder); require(eventGoal.length <= 4000)
                                if (kind == TriggerKind.TIME) java.time.LocalTime.parse(match)
                                if (kind in setOf(TriggerKind.APP, TriggerKind.NOTIFICATION)) require(match.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
                                store.rule(TriggerRule(kind = kind, match = match.trim(), goal = eventGoal.trim(), rootDirectory = eventFolder))
                                TriggerEngine.schedule(context); eventGoal = ""; creatingRule = false
                            }.onFailure { message = "无法保存规则：请检查时间格式、包名或目录" }
                        }) { Text("同意并开启此规则") }
                        }
                        rules.forEach { rule -> AppGroup {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Row { Text(rule.kind.label, Modifier.weight(1f)); AppSwitch(rule.enabled, { store.rule(rule.copy(enabled = it, signature = "")); TriggerEngine.schedule(context) }) }
                                Text(rule.goal); Text(rule.status, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { store.deleteRule(rule.id); TriggerEngine.schedule(context) }) { Text("删除规则") }
                            }
                        } }
                    }
                }
            }
            }
        }
    }
}
