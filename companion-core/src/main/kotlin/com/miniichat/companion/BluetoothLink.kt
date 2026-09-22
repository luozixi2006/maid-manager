package com.miniichat.companion

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Encrypted, system-bonded RFCOMM. No discovery, insecure sockets or public network listener. */
object BluetoothLink {
    val SERVICE_UUID:UUID=UUID.fromString("c7ad598e-fdea-4db5-a4ed-76f67c2426b9")
    const val MAX_FRAME=2*1024*1024
    private val timeout=Executors.newSingleThreadScheduledExecutor{r->Thread(r,"companion-socket-timeout").apply{isDaemon=true}}
    fun allowed(context:Context)=Build.VERSION.SDK_INT<31 || ContextCompat.checkSelfPermission(context,Manifest.permission.BLUETOOTH_CONNECT)==0
    fun adapter(context:Context)=context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("这台设备不支持蓝牙数据连接")
    fun peers(context:Context):List<BluetoothDevice> {
        check(allowed(context)){"请允许附近设备权限"}
        return adapter(context).bondedDevices.toList().sortedBy{it.name.orEmpty()}
    }
    fun validate(base:String):String {
        val address=base.removePrefix("bluetooth://")
        require(base.startsWith("bluetooth://") && address.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) {"请选择已配对的手机"}
        return address.uppercase()
    }
    fun read(input:InputStream):JSONObject {
        val stream=DataInputStream(input);val size=stream.readInt()
        require(size in 1..MAX_FRAME){"同步数据大小不正确"}
        val bytes=ByteArray(size);stream.readFully(bytes)
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }
    fun write(output:OutputStream,body:JSONObject) {
        val bytes=body.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_FRAME){"同步数据过大"}
        DataOutputStream(output).apply{writeInt(bytes.size);write(bytes);flush()}
    }
    fun call(context:Context,base:String,token:String,path:String,body:JSONObject?=null):JSONObject {
        check(allowed(context)){"请允许附近设备权限"}
        val adapter=adapter(context);check(adapter.isEnabled){"请开启蓝牙"}
        val device=adapter.getRemoteDevice(validate(base))
        check(device.bondState==BluetoothDevice.BOND_BONDED){"请先在系统中把手表与手机配对"}
        device.createRfcommSocketToServiceRecord(SERVICE_UUID).use {socket->
            val cancel=timeout.schedule({runCatching{socket.close()}},30,TimeUnit.SECONDS)
            try {
                socket.connect()
                write(socket.outputStream,JSONObject().put("path",path).put("token",token).put("body",body?:JSONObject()))
                val result=read(socket.inputStream)
                val status=result.optInt("status",500)
                if(status!=200)throw LinkFailure(status,result.optString("error","手机未完成请求"))
                return result.getJSONObject("body")
            }finally{cancel.cancel(false)}
        }
    }
}

object CompanionTransport {
    fun call(context:Context,base:String,token:String,path:String,body:JSONObject?=null):JSONObject =
        if(base.startsWith("bluetooth://")) BluetoothLink.call(context,base,token,path,body)
        else CompanionHttp.call(base,token,path,body)
}
