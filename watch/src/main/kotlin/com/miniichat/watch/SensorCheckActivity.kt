package com.miniichat.watch

import android.Manifest
import android.hardware.*
import android.os.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Visible diagnostic session only; readings never leave this Activity or go to the model. */
class SensorCheckActivity:ComponentActivity(),SensorEventListener {
    private lateinit var sensors:SensorManager
    private lateinit var text:TextView
    private val values=linkedMapOf<Int,String>()
    private val types=listOf(Sensor.TYPE_STEP_COUNTER,Sensor.TYPE_HEART_RATE,Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT,Sensor.TYPE_ACCELEROMETER)
    private val names=listOf("计步器","心率","佩戴状态","加速度")
    private val handler=Handler(Looper.getMainLooper())
    private val stop=Runnable{sensors.unregisterListener(this);text.append("\n检查已结束，停止采样")}
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){sample()}
    override fun onCreate(state:Bundle?) {super.onCreate(state);sensors=getSystemService(SensorManager::class.java)
        val column=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(18,24,18,24)}
        text=TextView(this).apply{textSize=14f;setTextColor(-1)};column.addView(text)
        column.addView(Button(this).apply{this.text="允许并检查（20秒）";setOnClickListener{
            permissions.launch((listOf(Manifest.permission.BODY_SENSORS)+if(Build.VERSION.SDK_INT>=29)listOf(Manifest.permission.ACTIVITY_RECOGNITION)else emptyList()).toTypedArray())}})
        column.addView(Button(this).apply{this.text="返回";setOnClickListener{finish()}})
        setContentView(ScrollView(this).apply{addView(column)});show()
    }
    private fun sample(){handler.removeCallbacks(stop);sensors.unregisterListener(this)
        for(type in types)sensors.getDefaultSensor(type)?.let{sensor->runCatching{sensors.registerListener(this,sensor,SensorManager.SENSOR_DELAY_NORMAL)}.onFailure{values[type]="权限未允许"}}
        handler.postDelayed(stop,20000);show()
    }
    override fun onSensorChanged(event:SensorEvent){values[event.sensor.type]=if(event.sensor.type==Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT){if(event.values[0]>0)"佩戴中" else "未佩戴"} else event.values.take(3).joinToString{String.format("%.1f",it)};show()}
    private fun show(){text.text="本机传感器检查\n仅本地显示，不上传\n\n"+types.mapIndexed{i,type->names[i]+"："+(if(sensors.getDefaultSensor(type)==null)"设备未开放" else values[type]?:"可用，等待授权/读数")}.joinToString("\n")}
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int)=Unit
    override fun onStop(){handler.removeCallbacks(stop);sensors.unregisterListener(this);super.onStop()}
}
