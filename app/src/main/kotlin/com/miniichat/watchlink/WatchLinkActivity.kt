package com.miniichat.watchlink

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.miniichat.companion.*
import com.miniichat.data.*
import com.miniichat.ui.SettingsTopBar
import com.miniichat.ui.AppGroup
import com.miniichat.ui.theme.MaidManagerTheme
import kotlinx.coroutines.*

class WatchLinkActivity:ComponentActivity() {
    private var permissionRevision by mutableIntStateOf(0)
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){permissionRevision++}
    override fun onCreate(state:Bundle?){super.onCreate(state);setContent{
        val settings by SettingsRepository(this).settings.collectAsState(initial=AppSettings())
        MaidManagerTheme(settings.themeMode,settings.dynamicColor){Surface(Modifier.fillMaxSize()){Content()}}
    }}
    @Composable private fun Content() {
        val config=remember{LinkConfig(this)}
        var enabled by remember{mutableStateOf(config.enabled)}
        var selected by remember{mutableStateOf(config.conversationId)}
        var address by remember{mutableStateOf(PhoneHub.peer(this))}
        var chats by remember{mutableStateOf(emptyList<Conversation>())}
        var consent by remember{mutableStateOf(enabled)}
        var code by remember{mutableStateOf("")}
        var error by remember{mutableStateOf("")}
        var busy by remember{mutableStateOf(false)}
        var peers by remember{mutableStateOf(emptyList<android.bluetooth.BluetoothDevice>())}
        val status by PhoneLink.status.collectAsState()
        val prefs=remember{getSharedPreferences("watch_link_ui",0)}
        var digital by remember{mutableStateOf(prefs.getBoolean("digital_context",false))}
        LaunchedEffect(permissionRevision){chats=ConversationStore(this@WatchLinkActivity).snapshot();peers=runCatching{BluetoothLink.peers(this@WatchLinkActivity)}.getOrDefault(emptyList())}
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            SettingsTopBar("手表与感知",{finish()})
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text("一个她，陪在两端",style=MaterialTheme.typography.headlineSmall)
                Text("手表记录身体与活动，手机保存同一段聊天、人设和记忆。无需电脑，也无需手表登录 Tailscale。")
                AppGroup {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    var chatMenu by remember{mutableStateOf(false)}
                    Text("共享的聊天",style=MaterialTheme.typography.labelLarge)
                    Box{OutlinedButton({chatMenu=true},enabled=!enabled && !busy){Text(chats.firstOrNull{it.id==selected}?.title?:"选择原有聊天")}
                        DropdownMenu(chatMenu,{chatMenu=false}){chats.forEach{chat->DropdownMenuItem({Text(chat.title)},{selected=chat.id;chatMenu=false})}}}
                    Text("连接的手表",style=MaterialTheme.typography.labelLarge)
                    var deviceMenu by remember{mutableStateOf(false)}
                    if(!BluetoothLink.allowed(this@WatchLinkActivity))Button({if(Build.VERSION.SDK_INT>=31)permission.launch(Manifest.permission.BLUETOOTH_CONNECT)}){Text("允许附近设备")}
                    else Box{OutlinedButton({deviceMenu=true},enabled=!enabled && !busy){Text(peers.firstOrNull{it.address==address}?.name?:"选择已配对手表")}
                        DropdownMenu(deviceMenu,{deviceMenu=false}){peers.forEach{device->DropdownMenuItem({Text(device.name?:device.address)},{address=device.address;deviceMenu=false})}}}
                    if(peers.isEmpty()) Text("先在手机系统中连接手表，再点刷新。",style=MaterialTheme.typography.bodySmall)
                    TextButton({permissionRevision++}){Text("刷新设备")}
                } }
                Row {Checkbox(consent,{consent=it},enabled=!enabled);Text("允许这只手表同步所选聊天与角色资料，并把身体摘要和事件提供给此人设。模型密钥只留在手机。",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)}
                Row {Switch(digital,{digital=it;prefs.edit().putBoolean("digital_context",it).apply()});Text("同时结合手机屏幕状态、媒体、耳机与网络；当前应用仅在页面陪伴已授权时使用。",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)}
                Button(onClick={busy=true;error="";lifecycleScope.launch{
                    try{
                        val profile=withContext(Dispatchers.IO){PhoneProfile.build(this@WatchLinkActivity,selected)}
                        code=PhoneHub.code(this@WatchLinkActivity,address)
                        config.bindPhone(selected,profile)
                        enabled=true;PhoneLink.startIfEnabled(this@WatchLinkActivity)
                    }catch(e:Exception){error=e.message?:"暂时无法配对"}finally{busy=false}
                }},enabled=consent && selected.isNotBlank() && address.isNotBlank() && !busy && BluetoothLink.allowed(this@WatchLinkActivity)) {Text(if(busy)"正在准备…" else if(enabled)"重新配对这只手表" else "开启并生成配对码")}
                if(code.isNotBlank()) {Text("在手表上选择这台手机，输入配对码：",style=MaterialTheme.typography.bodySmall)
                    androidx.compose.foundation.text.selection.SelectionContainer {Text(code,style=MaterialTheme.typography.headlineMedium)}
                    Text("10 分钟内有效，仅一次。重新生成会撤销旧连接。",style=MaterialTheme.typography.bodySmall)
                }
                Text(status,style=MaterialTheme.typography.bodyMedium)
                if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
                if(enabled) {
                    TextButton({config.enabled(false);enabled=false;code="";stopService(Intent(this@WatchLinkActivity,PhoneLinkService::class.java))}){Text("暂停连接")}
                    TextButton({PhoneHub.revoke(this@WatchLinkActivity);config.enabled(false);enabled=false;code="";stopService(Intent(this@WatchLinkActivity,PhoneLinkService::class.java))}){Text("撤销手表访问")}
                    TextButton({lifecycleScope.launch(Dispatchers.IO){PhoneHubStore(this@WatchLinkActivity).use{it.retry(PhoneHub.channel(this@WatchLinkActivity))};PhoneLink.run(this@WatchLinkActivity)}}){Text("重试未完成回复")}
                } else if(config.conversationId.isNotBlank() && PhoneHub.paired(this@WatchLinkActivity))TextButton({config.enabled(true);enabled=true;PhoneLink.startIfEnabled(this@WatchLinkActivity)}){Text("恢复原连接")}
                Text("离线时各端保留待同步内容。系统若强制停止后台，重新打开应用可恢复；不绕过系统权限。",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}
