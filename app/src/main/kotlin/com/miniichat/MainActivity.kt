package com.miniichat

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import com.miniichat.proactive.remote.RemotePushRuntime

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.miniichat.ui.AppRoot
import com.miniichat.ui.theme.MaidManagerTheme
import com.miniichat.proactive.AppVisibility
import com.miniichat.proactive.ProactiveDestination
import com.miniichat.proactive.ProactiveNavigation
import com.miniichat.proactive.ProactiveNotifications
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val vm: ChatViewModel by viewModels()

    override fun attachBaseContext(newBase: Context?) {
        if (newBase == null) { super.attachBaseContext(null); return }
        // Read language synchronously from a tiny SharedPreferences mirror written by SettingsRepository.
        val lang = newBase.getSharedPreferences("locale_cache", MODE_PRIVATE)
            .getString("language", "system") ?: "system"
        val ctx = if (lang == "system") newBase else applyLocale(newBase, lang)
        super.attachBaseContext(ctx)
    }

    private fun applyLocale(base: Context, lang: String): Context {
        val locale = when (lang) {
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "en" -> Locale.ENGLISH
            else -> Locale.getDefault()
        }
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        return base.createConfigurationContext(cfg)
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProactiveNotifications.ensureChannel(this)
        enableEdgeToEdge()
        setContent {
            val s by vm.settings.collectAsState()
            // Mirror language to SharedPreferences so attachBaseContext can pick it up next launch
            val prefs = getSharedPreferences("locale_cache", MODE_PRIVATE)
            if (prefs.getString("language", "system") != s.language) {
                prefs.edit().putString("language", s.language).apply()
            }
            MaidManagerTheme(themeMode = s.themeMode, dynamicColor = s.dynamicColor) {
                AppRoot(vm)
            }
        }
        handleProactiveIntent(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                merge(
                    com.miniichat.data.ConversationStore(this@MainActivity).conversationsFlow.map { Unit },
                    com.miniichat.data.AssistantStore(this@MainActivity).assistantsFlow.map { Unit },
                    com.miniichat.data.ProviderStore(this@MainActivity).providersFlow.map { Unit },
                    com.miniichat.data.SettingsRepository(this@MainActivity).settings.map { Unit }
                ).debounce(1500).collect { RemotePushRuntime.enqueue(this@MainActivity) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleProactiveIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.miniichat.tasks.TaskActions.recover(this@MainActivity) }.onFailure {
                com.miniichat.error.AppErrorStore(this@MainActivity).record(it,
                    com.miniichat.error.AppErrorContext(area = com.miniichat.error.ErrorArea.TASK,
                        operation = com.miniichat.error.ErrorOperation.READ_LOCAL_DATA))
            }
        }
        AppVisibility.isForeground = true
        com.miniichat.watchlink.PhoneLink.startIfEnabled(this)
        if (com.miniichat.proactive.remote.RemotePushConfig(this).enabled) {
            com.miniichat.proactive.remote.PushInboxWorker.enqueue(this)
        }
        if (com.miniichat.proactive.remote.RemotePushConfig(this).paired) RemotePushRuntime.schedule(this)
    }

    override fun onStop() {
        RemotePushRuntime.enqueue(this)
        AppVisibility.isForeground = false
        super.onStop()
    }

    private fun handleProactiveIntent(intent: Intent?) {
        com.miniichat.proactive.remote.PushIngress.takeIntentData(intent)?.let { data ->
            lifecycleScope.launch {
                runCatching { com.miniichat.proactive.remote.PushIngress.opened(this@MainActivity, data) }
                    .onSuccess { destination -> destination?.let(ProactiveNavigation::open) }
                    .onFailure { com.miniichat.proactive.remote.PushTrace.record(this@MainActivity, "notification_open_failed") }
            }
        }
        if (intent?.getBooleanExtra("phone_tasks", false) == true) {
            com.miniichat.tasks.TaskNavigation.selectedTask.value = intent.getStringExtra("work_task_id").orEmpty()
            com.miniichat.tasks.TaskNavigation.open.value = true
            intent.removeExtra("phone_tasks")
            intent.removeExtra("work_task_id")
        }
        val source = intent?.getStringExtra(ProactiveNotifications.EXTRA_SOURCE).orEmpty()
        if (source != "normal") return
        ProactiveNavigation.open(
            ProactiveDestination(
                sourceType = source,
                conversationId = intent?.getStringExtra(ProactiveNotifications.EXTRA_CONVERSATION).orEmpty()
            )
        )
        intent?.removeExtra(ProactiveNotifications.EXTRA_SOURCE)
    }
}
