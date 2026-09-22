package com.miniichat.watch

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.miniichat.companion.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

class WatchActivity:ComponentActivity() {
    private lateinit var column:LinearLayout
    private lateinit var messages:LinearLayout
    private lateinit var status:TextView
    private lateinit var input:EditText
    private var headerName:TextView?=null
    private var headerAvatar:ImageView?=null
    private var headerProfile=""
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {startSensingIfAllowed()}
    // Nearby-device consent is a separate callback: granting it must never start body sensing.
    private val nearby=registerForActivityResult(ActivityResultContracts.RequestPermission()) {render()}
    override fun onCreate(state:Bundle?) {super.onCreate(state);render()
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {while(isActive){withContext(Dispatchers.IO){WatchRuntime.run(this@WatchActivity)};refresh();delay(5000)}}
            launch {WatchRuntime.revision.collect {if(LinkConfig(this@WatchActivity).enabled)refresh()}}
        } }
    }
    private fun label(text:String,size:Float=15f)=TextView(this).apply {this.text=text;textSize=size;setTextColor(0xffeeeeee.toInt());setPadding(8,8,8,8)}
    private fun button(text:String,action:()->Unit)=Button(this).apply {this.text=text;isAllCaps=false;textSize=13f;setOnClickListener{action()}}
    private fun render() {
        column=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(14,18,14,18);setBackgroundColor(0xff16171b.toInt())}
        val scroll=ScrollView(this).apply {addView(column)};setContentView(scroll)
        val config=LinkConfig(this)
        if(!config.enabled) {
            column.addView(label("与手机上的她连接",18f))
            column.addView(label("先在手机“手表与感知”里选原有会话，生成配对码。只同步你选中的会话。",12f))
            val allowed=BluetoothLink.allowed(this)
            val peers:List<android.bluetooth.BluetoothDevice> = if(allowed) runCatching{BluetoothLink.peers(this)}.getOrNull().orEmpty() else emptyList()
            val spinner=Spinner(this)
            when {
                !allowed -> {
                    column.addView(label("需要“附近的设备”权限才能读取已经和手表配对的手机。",12f))
                    column.addView(button("允许附近的设备权限") {if(Build.VERSION.SDK_INT>=31)nearby.launch(Manifest.permission.BLUETOOTH_CONNECT)})
                }
                peers.isEmpty() -> {
                    column.addView(label("没有可用的已配对手机：请先在手表与手机的系统蓝牙设置里把两台设备配好对，再回到这里重新读取。",12f))
                    column.addView(button("重新读取已配对设备") {render()})
                }
                else -> {
                    spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_item,peers.map{it.name?.takeIf{name->name.isNotBlank()}?:it.address})
                        .apply{setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)}
                    column.addView(label("已配对的手机",12f));column.addView(spinner)
                }
            }
            val code=EditText(this).apply{hint="一次性配对码";setTextColor(-1);setHintTextColor(0xff888888.toInt());setSingleLine(true)}
            column.addView(code);status=label("");column.addView(status)
            column.addView(button("连接同一会话") {lifecycleScope.launch {
                val device=peers.getOrNull(spinner.selectedItemPosition)
                if(device==null){status.text="请先允许“附近的设备”权限并选择已配对的手机";return@launch}
                val base="bluetooth://"+device.address
                try {withContext(Dispatchers.IO){BluetoothLink.validate(base)
                    check(LinkStore(this@WatchActivity).use{it.outgoing().length()==0 && it.thoughts().isEmpty()}){"仍有未同步内容，请先恢复原连接"}
                    val result=CompanionTransport.call(this@WatchActivity,base,"","/v1/pair",JSONObject().put("code",code.text.toString().trim()))
                    config.save(base,result);LinkStore(this@WatchActivity).use{it.clear();it.meta("bound",config.conversationId)};WatchRuntime.schedule(this@WatchActivity)}
                    render();withContext(Dispatchers.IO){WatchRuntime.run(this@WatchActivity)};refresh()
                } catch(e:Exception){status.text=e.message?:"配对失败"}
            }})
            if(config.conversationId.isNotBlank())column.addView(button("恢复原连接") {config.enabled(true);render();WatchRuntime.schedule(this)})
            column.addView(button("检查传感器") {startActivity(Intent(this,SensorCheckActivity::class.java))})
            return
        }
        val profile=config.profile
        val header=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        val avatar=ImageView(this).apply{scaleType=ImageView.ScaleType.CENTER_CROP}
        header.addView(avatar,LinearLayout.LayoutParams(56,56))
        val name=label(profile.optString("name","陪伴"),19f)
        header.addView(name);column.addView(header)
        headerAvatar=avatar;headerName=name;headerProfile=profile.toString();applyProfile(profile)
        status=label(WatchRuntime.status.value,12f);column.addView(status)
        val controls=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=android.view.View.GONE}
        controls.addView(button("开启身体感知") {
            val required=mutableListOf(Manifest.permission.BODY_SENSORS)
            if(Build.VERSION.SDK_INT>=29)required+=Manifest.permission.ACTIVITY_RECOGNITION
            if(Build.VERSION.SDK_INT>=33)required+=Manifest.permission.POST_NOTIFICATIONS
            permissions.launch(required.toTypedArray())
        })
        controls.addView(button("暂停感知") {stopService(Intent(this,SensingService::class.java));refresh()})
        if(Build.VERSION.SDK_INT>=33) controls.addView(button("后台心率权限") {
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.BODY_SENSORS)==0)
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))
            else Toast.makeText(this,"请先开启身体感知权限",Toast.LENGTH_SHORT).show()
        })
        messages=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};column.addView(messages)
        input=EditText(this).apply{hint="和她说句话…";setTextColor(-1);setHintTextColor(0xff999999.toInt());maxLines=4};column.addView(input)
        column.addView(button("发送") {send(input.text.toString())})
        val quick=LinearLayout(this)
        listOf("我在呢","晚点聊").forEach {text->quick.addView(button(text){send(text)},LinearLayout.LayoutParams(0,-2,1f))};column.addView(quick)
        column.addView(button("感知与连接") {controls.visibility=if(controls.visibility==android.view.View.VISIBLE)android.view.View.GONE else android.view.View.VISIBLE})
        column.addView(controls)
        column.addView(button("今天的记录") {val events=LinkStore(this).use{it.items("event").filterNot{it.optBoolean("deleted")}.takeLast(40)}
            android.app.AlertDialog.Builder(this).setTitle("最近事件（观察记录）").setMessage(events.joinToString("\n"){val b=it.getJSONObject("body");java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.getDefault()).format(java.util.Date(b.optLong("at")))+" "+b.optString("summary")+" ["+b.optString("confidence")+"]"}.ifBlank{"还没有记录"}).setPositiveButton("关闭",null).show()
        })
        controls.addView(button("检查传感器") {startActivity(Intent(this,SensorCheckActivity::class.java))})
        controls.addView(button("断开并暂停") {config.enabled(false);stopService(Intent(this,SensingService::class.java));render()})
        refresh()
    }
    private fun startSensingIfAllowed() {try {ContextCompat.startForegroundService(this,Intent(this,SensingService::class.java))}catch(_:Exception){WatchRuntime.status.value="请保持应用打开后开启感知"};refresh()}
    private fun send(text:String) {
        if(text.isBlank())return
        val id=UUID.randomUUID().toString()
        LinkStore(this).use {store->
            store.enqueue("message",id,JSONObject().put("id",id).put("role","user").put("content",text.take(4000)).put("createdAt",System.currentTimeMillis()).put("source","watch"))
            store.pending("reply-$id",JSONObject().put("id","reply-$id").put("message_id",id))
        }
        input.setText("");status.text="已保存，正在发送…"
        lifecycleScope.launch{withContext(Dispatchers.IO){WatchRuntime.run(this@WatchActivity)};refresh()}
    }
    private fun refresh() {
        if(!::messages.isInitialized || !LinkConfig(this).enabled)return
        status.text=WatchRuntime.status.value+" · "+when(LinkClient.presence(this)){"observing"->"陪着你";"awake"->"留意到了";"talking"->"想和你说话";"sleeping"->"安静陪伴";else->"我在"}
        val profile=LinkConfig(this).profile
        // A profile change only repaints the header; the rest of the form, including unsent input, is untouched.
        if(profile.toString()!=headerProfile){headerProfile=profile.toString();applyProfile(profile)}
        messages.removeAllViews()
        LinkStore(this).use {store->
            store.items("context").lastOrNull{it.getString("id")=="watch"}?.getJSONObject("body")?.let {b->
                messages.addView(label("记录步数 ${b.optLong("dailySteps")} · 心率 ${if(b.isNull("heartRate"))"未知" else b.optString("heartRate")}\n${if(WatchRuntime.sensing.value)"感知中" else "感知已暂停"}",12f))
            }
            // Confirmed items and still-queued outbox messages are merged by id so nothing looks lost while offline.
            val queued=store.outgoing().let {ops->(0 until ops.length()).map{ops.getJSONObject(it)}}.filter {it.optString("kind")=="message" && !it.optBoolean("deleted")}.map {it.getJSONObject("body")}
            val queuedIds=queued.map{messageKey(it)}.toSet()
            val merged=mutableMapOf<String,JSONObject>()
            (store.items("message").filterNot{it.optBoolean("deleted")}.map{it.getJSONObject("body")}+queued).forEach {body->merged[messageKey(body)]=body}
            merged.values.sortedBy{it.optLong("createdAt")}.takeLast(25).forEach {b->
                messages.addView(label((if(b.optString("role")=="user")"你" else profile.optString("name","她"))+"："+b.optString("content")+(if(messageKey(b) in queuedIds)"（待同步）" else "")))
            }
        }
    }
    private fun messageKey(body:JSONObject)=body.optString("id").ifBlank{body.toString()}
    private fun applyProfile(profile:JSONObject) {
        headerName?.text=profile.optString("name","陪伴")
        val bitmap=runCatching {val raw=Base64.decode(profile.optString("avatar"),Base64.DEFAULT);BitmapFactory.decodeByteArray(raw,0,raw.size)}.getOrNull()
        headerAvatar?.let {view->if(bitmap==null){view.setImageDrawable(null);view.visibility=android.view.View.GONE}else{view.setImageBitmap(bitmap);view.visibility=android.view.View.VISIBLE}}
    }
}
