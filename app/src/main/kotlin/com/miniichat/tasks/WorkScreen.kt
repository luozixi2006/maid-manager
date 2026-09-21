package com.miniichat.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
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
    var routine by rememberSaveable { mutableStateOf(WorkDefaults.routine(context)) }
    var rememberRoutine by remember { mutableStateOf(WorkDefaults.routine(context)) }
    var confirmRoutine by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val externalDraft by TaskNavigation.chatDraft.collectAsState()
    val destination by TaskNavigation.selectedTask.collectAsState()
    LaunchedEffect(destination) { if (destination.isNotBlank()) {
        selected = destination
        drawer.close() // A notification/deep link must reveal the task, not a restored history drawer.
        TaskNavigation.selectedTask.value = ""
    } }
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
    if (confirmRoutine) AlertDialog(onDismissRequest = { confirmRoutine = false }, title = { Text("常规步骤交给她处理") },
        text = { Column {
            Text(RoutineApproval.explanation)
            PreferenceRow("以后新工作也采用此设置", "可随时在工作输入框上方关闭") { Checkbox(rememberRoutine, { rememberRoutine = it }) }
        } }, confirmButton = { TextButton({
            WorkDefaults.remember(context, rememberRoutine)
            if (task == null) routine = true else {
                store.change(task.id) { it.copy(autoAllowRoutine = true) }
                if (task.state == TaskState.APPROVAL) respond("allow_routine")
            }
            confirmRoutine = false
        }) { Text("允许常规操作") } }, dismissButton = { TextButton({ confirmRoutine = false }) { Text("取消") } })
    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(Modifier.fillMaxWidth(0.85f)) {
            Text("工作记录", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
            TextButton({ selected = null; draft = ""; files = false; routine = WorkDefaults.routine(context); scope.launch { drawer.close() } }) { Text("新工作") }
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
                    Text(if (task == null) "工作" else "与${task.characterName}一起完成", style = MaterialTheme.typography.titleMedium)
                    TextButton(onModel, contentPadding = PaddingValues(0.dp)) { Text((task?.model ?: model).ifBlank { "选择模型" }, maxLines = 1) }
                }
                IconButton(onSettings) { Icon(Icons.Outlined.Tune, "工作权限") }
                IconButton({ selected = null; draft = ""; files = false; routine = WorkDefaults.routine(context) }) { Icon(Icons.Outlined.Add, "新工作") }
            }
            val scroll = rememberLazyListState()
            LaunchedEffect(task?.id, task?.cursor, task?.state) { if (scroll.layoutInfo.totalItemsCount > 0) scroll.animateScrollToItem(scroll.layoutInfo.totalItemsCount - 1) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (task == null) item {
                    Spacer(Modifier.height(24.dp))
                    Text("告诉我目标，\n过程交给我", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(12.dp))
                    Text("我会分步处理，遇到重要决定再找你。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(24.dp))
                    WorkPanel {
                        listOf("应用内搜索" to "帮我在哔哩哔哩搜索", "整理文件" to "整理我选定的文件夹，先查看内容，再提出分类方案", "查找资料" to "帮我查找相关资料并整理成要点：").forEach { (title, example) ->
                            TextButton({ draft = example }, Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(title, Modifier.weight(1f)); Icon(Icons.Outlined.ArrowForward, "填入示例")
                                }
                            }
                        }
                    }
                } else {
                    item { WorkPanel { Column(Modifier.padding(16.dp)) {
                        Text("你的目标", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp)); Text(task.goal, style = MaterialTheme.typography.bodyLarge)
                    } } }
                    val completed = task.steps.take(task.cursor).filter { it.done }
                    if (completed.isNotEmpty()) item {
                        Disclosure("已完成 ${completed.size} 步") { completed.forEach { step ->
                            Text(AgentPolicy.specs[step.tool]?.title ?: step.tool, style = MaterialTheme.typography.labelLarge)
                            Text(step.reason.ifBlank { "已执行" }, style = MaterialTheme.typography.bodySmall)
                            Disclosure("执行记录") { Text(step.result.take(8000), style = MaterialTheme.typography.bodySmall) }
                        } }
                    }
                    items(task.answers) { answer -> WorkPanel { Text(answer, Modifier.padding(12.dp)) } }
                    item {
                        WorkPanel { Column(Modifier.padding(16.dp)) {
                        Text(if (TaskActions.active(task.state)) "正在处理" else if (task.state in setOf(TaskState.PERMISSION, TaskState.QUESTION, TaskState.APPROVAL)) "需要你决定" else task.state.label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Text(task.detail)
                        when (task.state) {
                            TaskState.APPROVAL -> {
                                Button({ respond("approve") }) { Text("允许这一步") }
                                TextButton({ confirmRoutine = true }) { Text("后续常规步骤交给她") }
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
                            TaskState.FAILED, TaskState.PAUSED -> Button({ respond("resume") }) { Text("继续原任务") }
                            else -> Unit
                        }
                        if (task.state !in setOf(TaskState.DONE, TaskState.CANCELLED)) {
                            Row { if (TaskActions.active(task.state)) TextButton({ respond("pause") }) { Text("暂停") }
                                TextButton({ respond("cancel") }) { Text("停止") } }
                        }
                        val folders = task.steps.filter { it.done && it.tool == "mkdir" }.map { it.resolvedTarget.ifBlank { it.destination } }.distinct()
                        folders.forEach { path -> TextButton({ folder = listOf(task.rootDirectory, task.scope, path).filter { it.isNotBlank() }.joinToString("/"); picking = true }) { Text("打开文件夹：$path") } }
                        } }
                    }
                }
                if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                Disclosure("${if (task?.autoAllowRoutine ?: routine) "常规步骤无需确认" else "每步先确认"} · 权限与文件范围") {
                    PreferenceRow("自动处理常规步骤", "删除、发送、付款仍会问你") { AppSwitch(task?.autoAllowRoutine ?: routine, { enabled ->
                        if (enabled) confirmRoutine = true else {
                            WorkDefaults.remember(context, false); rememberRoutine = false; routine = false
                            if (task != null) store.change(task.id) { it.copy(autoAllowRoutine = false) }
                        }
                    }) }
                    if (task == null) {
                        TextButton({ if (DownloadsTools(context, "").permitted()) picking = true else { TaskNavigation.permission.value = "files"; onSettings() } }) { Text("文件范围：${FolderSelection.label(folder)} · 更改") }
                        PreferenceRow("允许读取此目录", "必要文字交给当前模型；不办文件任务可以不选") { AppSwitch(files, { files = it }) }
                    }
                    TextButton(onSettings) { Text("选择可操作的应用 / 系统权限") }
                }
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    AppTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text(if (task == null) "你想完成什么？" else "补充要求，或回答她的问题…") }, maxLines = 4)
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

@Composable
private fun WorkPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(content = content)
    }
}
