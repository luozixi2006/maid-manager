package com.miniichat.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.miniichat.tasks.agent.AgentPolicy
import com.miniichat.tasks.agent.RoutineApproval
import com.miniichat.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** First-class work conversations, backed by the existing persistent task engine. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkScreen(onSettings: () -> Unit, onModel: () -> Unit, model: String) {
    val context = LocalContext.current
    val store = remember { TaskStore.of(context) }
    val revision by store.revision.collectAsState()
    var tasks by remember { mutableStateOf(emptyList<PhoneTask>()) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var folder by rememberSaveable { mutableStateOf(FolderSelection.saved(context)) }
    var files by rememberSaveable { mutableStateOf(false) }
    var routine by rememberSaveable { mutableStateOf(false) }
    var confirmRoutine by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val externalDraft by TaskNavigation.chatDraft.collectAsState()
    val destination by TaskNavigation.selectedTask.collectAsState()
    LaunchedEffect(destination) { if (destination.isNotBlank()) { selected = destination; TaskNavigation.selectedTask.value = "" } }
    LaunchedEffect(externalDraft) { if (externalDraft.isNotBlank()) { draft = externalDraft; TaskNavigation.chatDraft.value = "" } }
    LaunchedEffect(revision) { tasks = withContext(Dispatchers.IO) { store.all().filterNot { it.noticeOnly } } }
    val task = tasks.firstOrNull { it.id == selected }
    androidx.activity.compose.BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    fun respond(action: String, answer: String = "") { task?.let { current ->
        val step = current.steps.getOrNull(current.cursor)
        if (action in setOf("approve", "allow_routine", "resume") && step != null && AgentPolicy.specs[step.tool]?.permission == "accessibility") {
            runCatching {
                val intent = com.miniichat.tasks.agent.NativePhoneTools.intent(context, current, PhoneStep("open_app", arguments = mapOf("package" to step.arguments["package"].orEmpty())))
                context.startActivity(intent)
            }.onFailure { error = "请手动返回目标应用，再从悬浮窗或通知继续" }
        }
        TaskActions.respond(context, current.id, action, answer, current.approvalToken)
    } }
    if (picking) {
        FolderPicker(folder, { picking = false }) { folder = it; FolderSelection.remember(context, it); files = false; picking = false }
        return
    }
    if (confirmRoutine) AlertDialog(onDismissRequest = { confirmRoutine = false }, title = { Text("本任务内自动允许") },
        text = { Text(RoutineApproval.explanation) }, confirmButton = { TextButton({
            if (task == null) routine = true else {
                store.change(task.id) { it.copy(autoAllowRoutine = true) }
                if (task.state == TaskState.APPROVAL) respond("allow_routine")
            }
            confirmRoutine = false
        }) { Text("允许常规操作") } }, dismissButton = { TextButton({ confirmRoutine = false }) { Text("取消") } })
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(Modifier.fillMaxWidth(0.85f)) {
            Text("工作记录", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            TextButton({ selected = null; draft = ""; files = false; routine = false; scope.launch { drawer.close() } }) { Text("新工作") }
            LazyColumn(Modifier.weight(1f)) { items(tasks, key = { it.id }) { row ->
                NavigationDrawerItem(label = { Column { Text(row.goal, maxLines = 2); Text(row.state.label, style = MaterialTheme.typography.labelSmall) } },
                    selected = row.id == selected, onClick = { selected = row.id; scope.launch { drawer.close() } }, modifier = Modifier.padding(6.dp))
            } }
            TextButton(onSettings) { Text("权限与陪伴设置") }
        }
    }) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, "工作记录") }
                Column(Modifier.weight(1f)) {
                    Text(task?.characterName ?: "工作", style = MaterialTheme.typography.titleMedium)
                    TextButton(onModel, contentPadding = PaddingValues(0.dp)) { Text((task?.model ?: model).ifBlank { "选择模型" }, maxLines = 1) }
                }
                IconButton(onSettings) { Icon(Icons.Outlined.Tune, "工作权限") }
                IconButton({ selected = null; draft = ""; files = false; routine = false }) { Icon(Icons.Outlined.Add, "新工作") }
            }
            val scroll = rememberLazyListState()
            LaunchedEffect(task?.id, task?.cursor, task?.state) { if (scroll.layoutInfo.totalItemsCount > 0) scroll.animateScrollToItem(scroll.layoutInfo.totalItemsCount - 1) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (task == null) item {
                    Spacer(Modifier.height(50.dp))
                    Text("想完成什么？", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(12.dp))
                    Text("比如在 B 站搜索一位作者，整理文件，或查找应用中的内容。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    item { AppGroup { Text(task.goal, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) } }
                    items(task.steps.take(task.cursor).filter { it.done }) { step ->
                        Column {
                            Text(AgentPolicy.specs[step.tool]?.title ?: step.tool, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Text(step.reason.ifBlank { "已执行" }, style = MaterialTheme.typography.bodyMedium)
                            Disclosure("查看结果") { Text(step.result.take(8000), style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    items(task.answers) { answer -> AppGroup { Text(answer, Modifier.padding(12.dp)) } }
                    item {
                        Text(task.state.label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(task.detail)
                        when (task.state) {
                            TaskState.APPROVAL -> {
                                TextButton({ respond("approve") }) { Text("允许这一步") }
                                TextButton({ confirmRoutine = true }) { Text("本任务内自动允许常规操作") }
                                TextButton({ respond("skip") }) { Text("跳过") }
                            }
                            TaskState.PERMISSION -> TextButton({
                                if (task.neededPermission == "file_consent") respond("allow_files")
                                else { TaskNavigation.permission.value = task.neededPermission; onSettings() }
                            }) { Text(if (task.neededPermission == "file_consent") "允许读取此目录并继续" else "去授权并继续") }
                            TaskState.QUESTION -> {
                                if (task.neededPermission == "handoff") TextButton({ scope.launch { runCatching {
                                    com.miniichat.tasks.agent.AgentActions.launchSystem(context, task.id, task.approvalToken)
                                }.onFailure { error = it.message.orEmpty() } } }) { Text("打开系统确认") }
                                task.steps.getOrNull(task.cursor)?.options?.forEach { option -> TextButton({ respond("answer", option) }) { Text(option) } }
                            }
                            TaskState.FAILED, TaskState.PAUSED -> TextButton({ respond("resume") }) { Text("继续原任务") }
                            else -> Unit
                        }
                        if (task.state !in setOf(TaskState.DONE, TaskState.CANCELLED)) {
                            Row { if (TaskActions.active(task.state)) TextButton({ respond("pause") }) { Text("暂停") }
                                TextButton({ respond("cancel") }) { Text("停止") } }
                        }
                        val folders = task.steps.filter { it.done && it.tool == "mkdir" }.map { it.resolvedTarget.ifBlank { it.destination } }.distinct()
                        folders.forEach { path -> TextButton({ folder = listOf(task.rootDirectory, task.scope, path).filter { it.isNotBlank() }.joinToString("/"); picking = true }) { Text("打开文件夹：$path") } }
                    }
                }
                if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Disclosure("${if (task?.autoAllowRoutine ?: routine) "常规操作自动允许" else "逐步确认"} · ${FolderSelection.label(task?.rootDirectory ?: folder)}") {
                    PreferenceRow("本任务内自动允许", "仍保留高影响操作确认") { AppSwitch(task?.autoAllowRoutine ?: routine, { enabled ->
                        if (enabled) confirmRoutine = true else if (task == null) routine = false else store.change(task.id) { it.copy(autoAllowRoutine = false) }
                    }) }
                    if (task == null) {
                        TextButton({ if (DownloadsTools(context, "").permitted()) picking = true else { TaskNavigation.permission.value = "files"; onSettings() } }) { Text("选择文件夹") }
                        PreferenceRow("允许读取此目录", "必要文字交给当前模型；不办文件任务可以不选") { AppSwitch(files, { files = it }) }
                    }
                    TextButton(onSettings) { Text("选择可操作的应用 / 系统权限") }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    AppTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text(if (task == null) "描述你的工作…" else "补充要求或回答…") }, maxLines = 4)
                    IconButton(enabled = draft.isNotBlank() && !busy, onClick = {
                        val text = draft.trim(); busy = true; error = ""
                        scope.launch {
                            try {
                                if (task == null || task.state == TaskState.CANCELLED) {
                                    val created = withContext(Dispatchers.IO) { TaskActions.create(context, text, rootDirectory = folder, autoAllowRoutine = routine, fileConsent = files) }
                                    selected = created.id
                                } else if (task.state == TaskState.QUESTION) respond("answer", text)
                                else withContext(Dispatchers.IO) { TaskActions.addInstruction(context, task.id, text) }
                                draft = ""
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e
                            } catch (e: Exception) { error = e.message?.take(200) ?: "暂时无法提交" }
                            finally { busy = false }
                        }
                    }) { Icon(Icons.Outlined.ArrowUpward, "发送") }
                }
            }
        }
    }
}
