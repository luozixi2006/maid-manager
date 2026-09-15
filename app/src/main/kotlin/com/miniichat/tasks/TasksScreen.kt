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
    var tasks by remember { mutableStateOf(emptyList<PhoneTask>()) }
    var rules by remember { mutableStateOf(emptyList<TriggerRule>()) }
    var eventFolder by rememberSaveable { mutableStateOf(FolderSelection.saved(context)) }
    var choosingEventFolder by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    val openCompanion by TaskNavigation.companion.collectAsState()
    LaunchedEffect(openCompanion) { if (openCompanion) { tab = 1; TaskNavigation.companion.value = false } }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(revision) { withContext(Dispatchers.IO) { store.all() to store.rules() }.let { tasks = it.first; rules = it.second } }
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
            "apps" -> { tab = 0; message = "请在“允许操作的应用”里选择应用；已有任务可点“应用到未完成任务”。" }
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
    val filesGranted = remember(refresh) { DownloadsTools(context, "").permitted() }
    fun chooseEventFolder() {
        if (!filesGranted) grantTaskPermission("files") else choosingEventFolder = true
    }
    if (choosingEventFolder) {
        FolderPicker(eventFolder, { choosingEventFolder = false }) { eventFolder = it; choosingEventFolder = false }
        return
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding()) {
            SettingsTopBar("工作与陪伴设置", onBack)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(14.dp)).padding(4.dp)) {
                listOf("工作权限", "屏幕陪伴", "事件提醒").forEachIndexed { index, text ->
                    TextButton(onClick = { tab = index }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.textButtonColors(containerColor = if (tab == index) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent,
                            contentColor = if (tab == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)) { Text(text) }
                }
            }
            key(tab) { Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (message.isNotBlank()) { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = { message = "" }) { Text("知道了") } }
                when (tab) {
                    0 -> {
                        Text("工作在主页面右侧。这里只管理可使用的应用与系统权限。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            Text("无障碍可读取页面、点击、滚动和输入。仅限下面选中的应用；每次确认或本任务自动允许后，必要页面文字交给当前模型。通知正文另行确认。密码页及系统授权页不操作，可随时撤销。", style = MaterialTheme.typography.bodySmall)
                        }
                        var allowedApps by remember(refresh) { mutableStateOf(TaskActions.preferences(context).getStringSet("agent_apps", emptySet()).orEmpty().toSet()) }
                        var appList by remember { mutableStateOf(emptyMap<String, String>()) }
                        LaunchedEffect(Unit) { appList = withContext(Dispatchers.IO) { com.miniichat.tasks.agent.NativePhoneTools.apps(context).filterKeys(com.miniichat.tasks.agent.AgentPolicy::validPackage) } }
                        var confirmAllApps by remember { mutableStateOf(false) }
                        if (confirmAllApps) AlertDialog(onDismissRequest = { confirmAllApps = false }, title = { Text("选择全部可操作应用？") },
                            text = { Text("只选择可启动且非系统权限的应用。任务仍需要单独授权，系统权限不会自动开启。") },
                            confirmButton = { TextButton({
                                allowedApps = appList.keys.toSet()
                                TaskActions.preferences(context).edit().putStringSet("agent_apps", allowedApps).apply()
                                confirmAllApps = false
                            }) { Text("全选") } }, dismissButton = { TextButton({ confirmAllApps = false }) { Text("取消") } })
                        Disclosure("允许操作的应用 · ${allowedApps.size}") {
                            Row {
                                TextButton({ confirmAllApps = true }, enabled = appList.isNotEmpty()) { Text("全选") }
                                TextButton({ allowedApps = emptySet(); TaskActions.preferences(context).edit().putStringSet("agent_apps", emptySet()).apply() }) { Text("清空") }
                            }
                            Text("变更只影响新任务；撤销立即生效。旧任务要新增应用，请在下面更新授权。", style = MaterialTheme.typography.bodySmall)
                            TextButton({
                                tasks.filter { it.engineVersion >= 2 && it.state !in setOf(TaskState.DONE, TaskState.CANCELLED) }.forEach { task ->
                                    store.change(task.id) { it.copy(allowedPackages = allowedApps.toList()) }
                                }
                                message = "已将当前选择应用更新到未完成的任务"
                            }) { Text("应用到未完成任务") }
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
                            TextButton({ chooseEventFolder() }) { Text("观察目录：${FolderSelection.label(eventFolder)} · 更换") }
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
