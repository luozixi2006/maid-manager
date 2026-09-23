package com.miniichat.watch

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.miniichat.companion.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.sqrt

/** User-started, low duty-cycle sensing. No wake lock and no continuous raw-sensor storage. */
class SensingService:Service(),SensorEventListener {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var sensors:SensorManager
    private lateinit var reducer:ActivityReducer
    private var steps:Long?=null;private var worn:Boolean?=null;private var hr:Float?=null
    private var moving:Boolean?=null;private var acceleration:Float?=null
    private var heartStatus="not_started"
    private var heartAttemptAt=0L
    private var stepRegistered=false
    private val recordLock=kotlinx.coroutines.sync.Mutex()
    private val significant=object:TriggerEventListener(){override fun onTrigger(event:TriggerEvent){moving=true;armMotion();scope.launch{record()}}}
    private fun armMotion(){sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)?.let{runCatching{sensors.requestTriggerSensor(significant,it)}}}
    private val prefs by lazy {getSharedPreferences("watch_sensing",MODE_PRIVATE)}
    override fun onBind(intent:Intent?)=null
    override fun onCreate() {
        super.onCreate();sensors=getSystemService(SensorManager::class.java)
        val saved=LinkStore(this).use{it.meta("sensor_state")}.ifBlank{prefs.getString("state","{}").orEmpty()}
        reducer=ActivityReducer(runCatching {Json.decodeFromString<State>(saved)}.getOrDefault(State()).copy(worn=null,heartRate=null))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action=="stop") {prefs.edit().putBoolean("wanted",false).apply();stopSelf();return START_NOT_STICKY}
        if(WatchRuntime.sensing.value) return START_NOT_STICKY
        val body=ContextCompat.checkSelfPermission(this,android.Manifest.permission.BODY_SENSORS)==0
        val activity=Build.VERSION.SDK_INT<29 || ContextCompat.checkSelfPermission(this,android.Manifest.permission.ACTIVITY_RECOGNITION)==0
        if(!body && !activity) {WatchRuntime.status.value="请先允许身体传感器或活动识别";stopSelf();return START_NOT_STICKY}
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("sensing","本地轻量感知",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,1,Intent(this,WatchActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,2,Intent(this,SensingService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
        val notice=NotificationCompat.Builder(this,"sensing").setSmallIcon(R.drawable.ic_watch).setContentTitle("身体感知已开启")
            .setContentText("轻量采样 · 随时暂停").setContentIntent(open).setOngoing(true).addAction(0,"暂停",stop).build()
        try {
            if(Build.VERSION.SDK_INT>=34) startForeground(8601,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH) else startForeground(8601,notice)
        } catch(_:SecurityException) {WatchRuntime.status.value="系统未允许后台感知，请在应用内开启";stopSelf();return START_NOT_STICKY}
        WatchRuntime.sensing.value=true;prefs.edit().putBoolean("wanted",true).apply()
        ensureStepSensor()
        register(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT,0)
        register(Sensor.TYPE_MOTION_DETECT,0)
        armMotion()
        // Local sensing must not wait for a Bluetooth timeout or a long synchronization backlog.
        scope.launch { while(isActive) { ensureStepSensor();record();delay(30000) } }
        scope.launch { while(isActive) { withContext(Dispatchers.IO) {WatchRuntime.run(this@SensingService)};delay(30000) } }
        scope.launch { while(isActive) {
            if(sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!=null)moving=false
            register(Sensor.TYPE_ACCELEROMETER,0)
            delay(10000)
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let{sensors.unregisterListener(this@SensingService,it)}
            delay(290000)
        } }
        scope.launch { while(isActive) {
            // Permissions can be granted from the diagnostic page after this service started.
            // Never freeze the initial permission snapshot for the lifetime of sensing.
            if(ContextCompat.checkSelfPermission(this@SensingService,android.Manifest.permission.BODY_SENSORS)!=0) {
                heartStatus="permission_missing";delay(5000);continue
            }
            if(sensors.getDefaultSensor(Sensor.TYPE_HEART_RATE)==null) {
                heartStatus="unavailable";delay(300000);continue
            }
            // A short warm-up may not produce a reliable optical reading. Allow up to 30s,
            // stop early on a valid reading, then stay off. Resample before the 10min expiry.
            val before=reducer.state.heartRateAt
            heartAttemptAt=System.currentTimeMillis();heartStatus="warming_up"
            if(register(Sensor.TYPE_HEART_RATE,0)) {
                val deadline=SystemClock.elapsedRealtime()+30000
                while(isActive && reducer.state.heartRateAt<=before && SystemClock.elapsedRealtime()<deadline)delay(250)
                sensors.getDefaultSensor(Sensor.TYPE_HEART_RATE)?.let{sensors.unregisterListener(this@SensingService,it)}
                if(heartStatus=="warming_up")heartStatus="no_reading"
            }else heartStatus="registration_failed"
            record()
            delay(300000)
        } }
        return START_NOT_STICKY
    }
    private fun register(type:Int,batch:Int):Boolean {
        val sensor=sensors.getDefaultSensor(type)?:return false
        return runCatching {sensors.registerListener(this,sensor,SensorManager.SENSOR_DELAY_NORMAL,batch)}
            .getOrDefault(false).also {if(!it)WatchRuntime.status.value="部分传感器未开放或权限不足"}
    }
    private fun ensureStepSensor() {
        val allowed=Build.VERSION.SDK_INT<29 || ContextCompat.checkSelfPermission(this,android.Manifest.permission.ACTIVITY_RECOGNITION)==0
        if(!stepRegistered && allowed)stepRegistered=register(Sensor.TYPE_STEP_COUNTER,60000000)
    }
    override fun onSensorChanged(event:SensorEvent) {
        when(event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER->steps=event.values[0].toLong()
            Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT->worn=event.values[0]>0
            Sensor.TYPE_HEART_RATE->{
                heartStatus=when {
                    event.accuracy==SensorManager.SENSOR_STATUS_UNRELIABLE->"unreliable"
                    event.values[0] !in 30f..220f->"invalid"
                    else->"measured"
                }
                if(heartStatus=="measured") {
                    hr=event.values[0]
                    scope.launch { record() }
                }
            }
            Sensor.TYPE_MOTION_DETECT->{moving=true;register(Sensor.TYPE_MOTION_DETECT,0)}
            Sensor.TYPE_ACCELEROMETER->{ val magnitude=sqrt(event.values.take(3).sumOf {(it*it).toDouble()}).toFloat()
                if(acceleration!=null && kotlin.math.abs(magnitude-acceleration!!)>1.5f)moving=true
                acceleration=magnitude
            }
        }
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int)=Unit
    private suspend fun record() {
        val now=System.currentTimeMillis()
        val sample=Sample(now,LocalDate.now().toString(),steps,hr,worn,moving,getSystemService(PowerManager::class.java).isInteractive)
        moving=null;hr=null
        recordLock.lock()
        try {withContext(Dispatchers.IO) {
        val events=reducer.accept(sample)
        LinkStore(this@SensingService).use {store->
            val db=store.writableDatabase;db.beginTransaction()
            try {
            for(event in events) {
                val body=JSONObject(Json.encodeToString(event)).put("source","watch")
                store.enqueue("event",event.id,body)
                store.pending("event-${event.id}",JSONObject().put("id","event-${event.id}").put("event_id",event.id))
            }
            val state=JSONObject(Json.encodeToString(reducer.state)).put("at",now).put("source","watch").put("steps_scope","今日开始感知后记录到的步数，不是未接入前的全天总数")
            state.put("heart_rate_status",heartStatus).put("heart_rate_attempt_at",heartAttemptAt)
            val available=sensors.getSensorList(Sensor.TYPE_ALL).map{it.type}.toSet()
            state.put("available_sensors",org.json.JSONArray(available.toList()))
            store.enqueue("context","watch",state)
            store.meta("sensor_state",Json.encodeToString(reducer.state))
            db.setTransactionSuccessful()
            }finally{db.endTransaction()}
        }
        WatchRuntime.revision.value++
        }}finally{recordLock.unlock()}
    }
    override fun onDestroy() {scope.cancel();sensors.unregisterListener(this);sensors.cancelTriggerSensor(significant,null);WatchRuntime.sensing.value=false;super.onDestroy()}
}
