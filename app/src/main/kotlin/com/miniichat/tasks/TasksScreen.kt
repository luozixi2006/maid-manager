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
import com.miniichat.ui.SettingsTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TasksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { TaskStore.of(context) }
    val revision by store.revision.collectAsState()
    val draft by TaskNavigation.chatDraft.collectAsState()
    var tasks by remember { mutableStateOf(emptyList<PhoneTask>()) }
    var rules by remember { mutableStateOf(emptyList<TriggerRule>()) }
    var goal by rememberSaveable { mutableStateOf(draft) }
    var scopePath by rememberSaveable { mutableStateOf("") }
    var rootDirectory by rememberSaveable { mutableStateOf("Download") }
    var consent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
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
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding()) {
            SettingsTopBar("交给我做", onBack)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("任务", "权限与陪伴", "事件触发").forEachIndexed { index, text ->
                    TextButton(onClick = { tab = index }) { Text(if (tab == index) "• $text" else text) }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (message.isNotBlank()) { Text(message, color = MaterialTheme.colorScheme.error); TextButton(onClick = { message = "" }) { Text("知道了") } }
                when (tab) {
                    0 -> {
                        Text("说一件要办的事", style = MaterialTheme.typography.titleLarge)
                        Text("可以读写和整理普通文件、查资料、打开应用与网址、分享文件、添加日程，并操作你授权应用的页面。根据实际结果逐步调整，不再限定PDF。")
                        OutlinedTextField(goal, { goal = it }, label = { Text("例如：把学校 PDF 按课程整理，名字改清楚") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                        OutlinedTextField(rootDirectory, { rootDirectory = it }, label = { Text("手机共享存储目录，如 Download、Documents、DCIM") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(scopePath, { scopePath = it }, label = { Text("该目录内的子文件夹（可留空）") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row { Checkbox(consent, { consent = it }); Text("允许为完成此任务按需读取该目录，并把必要文字交给当前AI服务。页面和通知正文另行逐次确认；应用权限由你选择。", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall) }
                        Button(enabled = consent && goal.isNotBlank() && !busy, onClick = {
                            busy = true; scope.launch {
                                try { val task = withContext(Dispatchers.IO) { TaskActions.create(context, goal, scopePath.trim(), rootDirectory = rootDirectory.trim()) }
                                    selected = task.id; goal = ""; consent = false
                                } catch (e: Exception) { message = e.message?.take(200) ?: "任务创建失败" }
                                finally { busy = false }
                            }
                        }) { Text(if (busy) "正在保存…" else "交给我做") }
                        if (!filesGranted) TextButton(onClick = { tab = 1 }) { Text("尚无文件权限：任务会暂停等待，不会丢失") }
                        HorizontalDivider()
                        tasks.forEach { task ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(task.goal, style = MaterialTheme.typography.titleMedium)
                                    Text(if (task.noticeOnly) "主动消息 · 没有读取文件" else "${task.state.label} · ${task.cursor}/${task.steps.size} 步 · ${task.rootDirectory}/${task.scope}", style = MaterialTheme.typography.labelMedium)
                                    Text(task.detail)
                                    when (task.state) {
                                        TaskState.PERMISSION -> TextButton(onClick = { tab = 1 }) { Text("开启所需权限") }
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
                                            OutlinedTextField(answer, { answer = it }, label = { Text("也可以直接告诉我") })
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
                                    TextButton(onClick = { selected = if (selected == task.id) null else task.id }) { Text("执行记录") }
                                    if (selected == task.id) {
                                        Text("模型：${task.model}\n服务在任务创建时锁定。", style = MaterialTheme.typography.bodySmall)
                                        Text(task.history.joinToString("\n").ifBlank { "还没有执行文件操作" }, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        Text("权限由你掌握", style = MaterialTheme.typography.titleLarge)
                        Text("系统文件权限只用于你在任务中指定的共享目录。不访问其他应用私有目录，不获取Root。普通文件可复制、移动、改名；删除改为可恢复区，每次确认。")
                        Button(onClick = {
                            if (Build.VERSION.SDK_INT >= 30) settings(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, true)
                            else runtime.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                        }) { Text(if (filesGranted) "文件权限已开启 · 管理" else "开启文件权限") }
                        TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= 33) runtime.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                            else settings(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        }) { Text("允许任务和角色通知") }
                        HorizontalDivider()
                        Text("屏幕边缘陪伴", style = MaterialTheme.typography.titleMedium)
                        Text("拖动头像；点击快速聊天、回复确认，长按快捷操作。快速聊天是临时会话，关闭头像后不保留；任务持久保存。页面操作需要单独开启下面的服务及应用名单。")
                        Button(onClick = {
                            if (!overlayGranted) settings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true)
                            else runCatching { ContextCompat.startForegroundService(context, Intent(context, PetOverlayService::class.java)) }
                                .onFailure { message = "请保持应用在前台，然后重新启动悬浮头像" }
                        }) { Text(if (overlayGranted) "显示悬浮头像" else "开启悬浮窗权限") }
                        TextButton(onClick = { context.stopService(Intent(context, PetOverlayService::class.java)) }) { Text("关闭头像（不取消任务）") }
                        Text("强行停止后需重新打开应用才能恢复。重启后打开应用可恢复任务，头像需主动开启。省电限制可能延后执行和提醒。")
                        HorizontalDivider()
                        Text("操作自动批准", style = MaterialTheme.typography.titleMedium)
                        val approvals = remember(revision, refresh) { TaskActions.preferences(context).all.filter { (it.key.startsWith("approve:") || it.key.startsWith("agent:")) && it.value == true }.keys }
                        Text(approvals.joinToString("\n") { it.removePrefix("approve:") }.ifBlank { "暂无；首次移动、改名和建文件夹都会询问。" })
                        TextButton(onClick = { TaskActions.preferences(context).edit().apply { approvals.forEach { remove(it) } }.commit(); refresh++ }) { Text("撤销所有自动批准") }
                        TextButton(onClick = { settings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }) { Text("通知触发：管理通知访问") }
                        Text("页面与通知操作授权", style = MaterialTheme.typography.titleMedium)
                        Text("无障碍可读取页面文字并点击、滚动、输入。只有下面选中的应用可被任务使用，读取/点击/输入每次确认。密码页面和系统授权页面不操作。可随时关闭服务或撤销名单。")
                        TextButton(onClick = { settings(Settings.ACTION_ACCESSIBILITY_SETTINGS) }) { Text("管理无障碍任务服务") }
                        var allowedApps by remember(refresh) { mutableStateOf(TaskActions.preferences(context).getStringSet("agent_apps", emptySet()).orEmpty().toSet()) }
                        var appList by remember { mutableStateOf(emptyMap<String, String>()) }
                        LaunchedEffect(Unit) { appList = withContext(Dispatchers.IO) { com.miniichat.tasks.agent.NativePhoneTools.apps(context).filterKeys(com.miniichat.tasks.agent.AgentPolicy::validPackage) } }
                        appList.toList().sortedBy { it.second }.forEach { (pkg, label) ->
                            Row { Checkbox(pkg in allowedApps, { yes ->
                                allowedApps = if (yes) allowedApps + pkg else allowedApps - pkg
                                TaskActions.preferences(context).edit().putStringSet("agent_apps", allowedApps).apply()
                            }); Text(label, Modifier.padding(top = 12.dp)) }
                        }
                        TextButton(onClick = { settings(Settings.ACTION_USAGE_ACCESS_SETTINGS) }) { Text("打开应用触发：管理使用情况访问") }
                        TextButton(onClick = { runtime.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("识别 Wi-Fi 名称：允许精确位置") }
                        TextButton(onClick = { settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) { Text("系统权限：可选设为始终允许位置") }
                        Text("只在匹配指定 Wi-Fi 名称时需要精确位置、后台位置和系统定位；留空匹配任意 Wi-Fi 不需要位置权限。")
                    }
                    2 -> {
                        Text("发生什么时来找你", style = MaterialTheme.typography.titleLarge)
                        Text("规则会把事件类型和规则文字交给当前 AI 服务判断，不上传通知正文。普通提醒不会读取文件；整理文件的建议需确认后才扫描。每条规则至少间隔一小时。")
                        Text("头像开启时约每 15 秒检查；关闭后系统检查至少间隔 15 分钟，可能延迟或漏掉短暂事件。通知监听单独工作。时间触发不是精确闹钟。")
                        var kind by rememberSaveable { mutableStateOf(TriggerKind.FILES) }
                        var match by rememberSaveable { mutableStateOf("") }
                        var eventGoal by rememberSaveable { mutableStateOf("") }
                        var eventScope by rememberSaveable { mutableStateOf("") }
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
                        Box { OutlinedButton(onClick = { expanded = true }) { Text(kind.label) }
                            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                TriggerKind.entries.forEach { k -> DropdownMenuItem(text = { Text(k.label) }, onClick = { kind = k; expanded = false; match = "" }) }
                            }
                        }
                        if (kind in setOf(TriggerKind.NOTIFICATION, TriggerKind.APP)) {
                            Box {
                                OutlinedButton(onClick = { appsExpanded = true }) { Text(apps.firstOrNull { it.first == match }?.second ?: "选择应用") }
                                DropdownMenu(appsExpanded, onDismissRequest = { appsExpanded = false }) {
                                    apps.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { match = id; appsExpanded = false }) }
                                }
                            }
                        }
                        if (kind in setOf(TriggerKind.WIFI, TriggerKind.TIME)) {
                            OutlinedTextField(match, { match = it }, modifier = Modifier.fillMaxWidth(), label = { Text(when (kind) {
                                TriggerKind.TIME -> "每天时间，如 18:30"
                                TriggerKind.WIFI -> "Wi-Fi 名称；留空匹配任意 Wi-Fi"
                                else -> "应用包名，如 com.tencent.mm"
                            }) })
                        }
                        OutlinedTextField(eventGoal, { eventGoal = it }, label = { Text("希望角色考虑做什么") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                        OutlinedTextField(eventScope, { eventScope = it }, label = { Text("下载目录内的子文件夹（可留空）") }, modifier = Modifier.fillMaxWidth())
                        Text("文件触发观察此目录直属 PDF，首次只建立基线。确认建议代表同意向当前服务发送该范围 PDF 摘要。", style = MaterialTheme.typography.bodySmall)
                        Button(enabled = eventGoal.isNotBlank(), onClick = {
                            runCatching {
                                ToolPolicy.relative(eventScope.trim(), true); require(eventGoal.length <= 4000)
                                if (kind == TriggerKind.TIME) java.time.LocalTime.parse(match)
                                if (kind in setOf(TriggerKind.APP, TriggerKind.NOTIFICATION)) require(match.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
                                store.rule(TriggerRule(kind = kind, match = match.trim(), goal = eventGoal.trim(), scope = eventScope.trim()))
                                TriggerEngine.schedule(context); eventGoal = ""
                            }.onFailure { message = "无法保存规则：请检查时间格式、包名或目录" }
                        }) { Text("同意并开启此规则") }
                        rules.forEach { rule -> OutlinedCard {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Row { Text(rule.kind.label, Modifier.weight(1f)); Switch(rule.enabled, { store.rule(rule.copy(enabled = it, signature = "")); TriggerEngine.schedule(context) }) }
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
