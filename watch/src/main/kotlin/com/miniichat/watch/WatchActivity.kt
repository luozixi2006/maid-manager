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
    private var showPairing=false
    private var busy=false
    private var pendingInfo:TextView?=null
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
    private fun button(text:String,action:()->Unit)=Button(this).apply {this.text=text;isAllCaps=false;textSize=13f;isEnabled=!busy;setOnClickListener{action()}}
    private fun render() {
        column=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(14,18,14,18);setBackgroundColor(0xff16171b.toInt())}
        val scroll=ScrollView(this).apply {addView(column)};setContentView(scroll)
        val config=LinkConfig(this)
        if(!config.enabled || showPairing) {
            disconnected(config)
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
        val connectionButtons=LinearLayout(this)
        connectionButtons.addView(button("立即同步"){syncNow()},LinearLayout.LayoutParams(0,-2,1f))
        connectionButtons.addView(button("断开"){WatchConnection.pause(this);showPairing=false;render()},LinearLayout.LayoutParams(0,-2,1f))
        column.addView(connectionButtons)
        val reconnectButtons=LinearLayout(this)
        reconnectButtons.addView(button("重新配对"){openPairing()},LinearLayout.LayoutParams(0,-2,1f))
        reconnectButtons.addView(button("旧会话"){showBackups()},LinearLayout.LayoutParams(0,-2,1f))
        column.addView(reconnectButtons)
        pendingInfo=label("",12f).apply{setOnClickListener{showPending()}};column.addView(pendingInfo)
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
        refresh()
    }
    private fun disconnected(config:LinkConfig) {
        column.addView(label("连接手机",18f))
        status=label(if(config.conversationId.isBlank())"尚未配对" else WatchRuntime.status.value,12f);column.addView(status)
        if(config.conversationId.isNotBlank()) {
            column.addView(label("已保存：${config.profile.optString("name","原会话")}",14f))
            column.addView(button("连接原手机"){syncNow()})
            if(!showPairing)column.addView(button("重新配对"){openPairing()})
        }
        val counts=LinkStore(this).use{ConnectionRecords(it).pending()}
        column.addView(label(counts.describe(),12f))
        column.addView(button("待同步记录 / 处理办法"){showPending()})
        column.addView(button("保留的旧会话"){showBackups()})
        if(showPairing || config.conversationId.isBlank())pairingForm(config)
        else column.addView(label("断开会暂停感知和同步，不删除记录。连接后可在“感知与连接”里重新开启感知。",12f))
        column.addView(button("检查传感器"){startActivity(Intent(this,SensorCheckActivity::class.java))})
    }
    private fun pairingForm(config:LinkConfig) {
        column.addView(label("手机 → 手表与感知 → 选择原会话 → 生成新配对码。同一会话会继续补传，不要求先清空待同步内容。",12f))
        val peers=if(BluetoothLink.allowed(this))runCatching{BluetoothLink.peers(this)}.getOrNull().orEmpty() else emptyList()
        val spinner=Spinner(this)
        if(!BluetoothLink.allowed(this))column.addView(button("允许附近设备权限"){if(Build.VERSION.SDK_INT>=31)nearby.launch(Manifest.permission.BLUETOOTH_CONNECT)})
        else if(peers.isEmpty()) {
            column.addView(label("没有已配对手机。请先在系统蓝牙设置中配对，再重新读取。",12f))
            column.addView(button("打开蓝牙设置"){startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))})
            column.addView(button("重新读取设备"){render()})
        } else {
            spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_item,peers.map{it.name?.takeIf{name->name.isNotBlank()}?:it.address}).apply{setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)}
            val current=peers.indexOfFirst{"bluetooth://"+it.address==config.base}
            if(current>=0)spinner.setSelection(current)
            column.addView(spinner)
        }
        val code=EditText(this).apply{hint="8 位配对码";setTextColor(-1);setHintTextColor(0xff999999.toInt());setSingleLine();inputType=android.text.InputType.TYPE_CLASS_NUMBER}
        column.addView(code)
        column.addView(button("验证并连接") {
            val device=peers.getOrNull(spinner.selectedItemPosition)
            if(device==null){status.text="请先选择已配对手机";return@button}
            val value=code.text.toString().trim()
            work("正在验证配对…") {
                val candidate=WatchConnection.prepare(this@WatchActivity,"bluetooth://"+device.address,value)
                if(candidate.sameConnection || candidate.previous.conversation.isBlank())finishPairing(candidate)
                else android.app.AlertDialog.Builder(this@WatchActivity).setTitle("这是另一段会话")
                    .setMessage("原会话：${candidate.previous.name}\n新会话：${candidate.binding.name}\n\n旧消息、待同步内容和事件将保留在本表，不会发给新会话。以后重新配对原会话可继续补传。")
                    .setPositiveButton("保留旧记录并切换"){_,_->work("正在保留记录并连接…"){finishPairing(candidate)}}
                    .setNegativeButton("取消"){_,_->status.text="未切换，原记录保留。下次配对请在手机生成新码。"}
                    .setOnCancelListener{status.text="未切换，原记录保留。下次配对请在手机生成新码。"}.show()
            }
        })
        if(config.conversationId.isNotBlank())column.addView(button("返回连接页面"){showPairing=false;render()})
    }
    private fun openPairing() {WatchConnection.pause(this);showPairing=true;render()}
    private suspend fun finishPairing(candidate:PairCandidate) {
        WatchConnection.accept(this,candidate);showPairing=false;render()
        withContext(Dispatchers.IO){WatchRuntime.run(this@WatchActivity)};refresh()
    }
    private fun syncNow()=work("正在连接并同步…") {
        if(!LinkConfig(this@WatchActivity).enabled){WatchConnection.resume(this@WatchActivity);showPairing=false;render()}
        withContext(Dispatchers.IO){WatchRuntime.run(this@WatchActivity)};refresh()
    }
    private fun work(progress:String,action:suspend ()->Unit) {
        if(busy)return
        busy=true;setButtons(column,false);status.text=progress
        lifecycleScope.launch {
            try {action()} catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception) {
                status.text=if(error is LinkFailure || error is IllegalStateException || error is IllegalArgumentException)error.message?.take(120)?:"连接失败，记录未删除" else "未能连接手机。请打开手机“手表与感知”，保持蓝牙开启后重试。记录仍保留。"
            } finally {busy=false;setButtons(column,true)}
        }
    }
    private fun setButtons(view:android.view.View,enabled:Boolean) {
        if(view is Button)view.isEnabled=enabled
        if(view is android.view.ViewGroup)for(i in 0 until view.childCount)setButtons(view.getChildAt(i),enabled)
    }
    private fun showPending() {
        val text=LinkStore(this).use{val records=ConnectionRecords(it);records.pending().describe()+"\n\n"+records.preview()}
        android.app.AlertDialog.Builder(this).setTitle("内容仍在手表")
            .setMessage(text+"\n\n原连接还能用：点“立即补传”。手机生成过新码：点“重新配对”，选择原来的会话即可保留并补传。不要卸载应用。")
            .setPositiveButton("立即补传"){_,_->syncNow()}.setNeutralButton("重新配对"){_,_->openPairing()}.setNegativeButton("关闭",null).show()
    }
    private fun showBackups() {
        val backups=LinkStore(this).use{ConnectionRecords(it).backups()}
        if(backups.isEmpty()){android.app.AlertDialog.Builder(this).setMessage("没有旧会话备份；当前内容仍在当前会话。只有切换到其他会话时才会单独保留。").setPositiveButton("关闭",null).show();return}
        android.app.AlertDialog.Builder(this).setTitle("保留的旧会话").setItems(backups.map{it.binding.name+" · "+java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.getDefault()).format(java.util.Date(it.savedAt))}.toTypedArray()){_,index->
            val backup=backups[index]
            val text=LinkStore(this).use{ConnectionRecords(it).preview(backup.binding)}
            android.app.AlertDialog.Builder(this).setTitle(backup.binding.name).setMessage(backup.pending.describe()+"\n\n"+text+"\n\n恢复方法：在原手机选择这段原会话，生成新码后点下面的“重新配对”。配对成功会自动恢复这些记录并补传，不会并入其他人设。")
                .setPositiveButton("重新配对"){_,_->openPairing()}.setNegativeButton("关闭",null).show()
        }.setNegativeButton("关闭",null).show()
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
        if(!::messages.isInitialized || !LinkConfig(this).enabled || showPairing)return
        status.text=WatchRuntime.status.value+" · "+when(LinkClient.presence(this)){"observing"->"陪着你";"awake"->"留意到了";"talking"->"想和你说话";"sleeping"->"安静陪伴";else->"我在"}
        val profile=LinkConfig(this).profile
        // A profile change only repaints the header; the rest of the form, including unsent input, is untouched.
        if(profile.toString()!=headerProfile){headerProfile=profile.toString();applyProfile(profile)}
        messages.removeAllViews()
        LinkStore(this).use {store->
            pendingInfo?.text=ConnectionRecords(store).pending().describe()+"\n点此查看记录和处理办法"
            val confirmed=store.items("context").lastOrNull{it.getString("id")=="watch" && !it.optBoolean("deleted")}?.getJSONObject("body")
            val local=store.writableDatabase.rawQuery("SELECT body FROM outbox WHERE kind='context' AND id='watch'",null).use{cursor->if(cursor.moveToFirst())JSONObject(cursor.getString(0)) else null}
            val latest=local?:confirmed
            messages.addView(label(DeviceContextText.watch(latest,System.currentTimeMillis())+"\n"+
                (if(WatchRuntime.sensing.value)"感知中" else "感知已暂停")+"\n"+
                (if(local!=null)"新身体记录待同步到手机" else if(confirmed!=null)"身体记录已由手机同步确认" else "尚无身体采集记录"),12f))
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
