package com.miniichat.companion

import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONObject

class LinkFailure(val status: Int, message: String): Exception(message)
object CompanionHttp {
    /** Cleartext is allowed only over numeric Tailscale IPs (or loopback in local tests). */
    fun validate(base: String) {
        val uri=URI(base)
        require(uri.userInfo==null && uri.rawQuery==null && uri.rawFragment==null && uri.path.orEmpty().isBlank()) { "请填写服务根地址" }
        val host=uri.host.orEmpty();val parts=host.split('.').map { it.toIntOrNull() }
        val tailscale=parts.size==4 && parts[0]==100 && parts[1] in 64..127 && parts.all { it!=null && it in 0..255 }
        require(uri.scheme=="https" || (uri.scheme=="http" && (tailscale || host=="127.0.0.1"))) { "仅允许 HTTPS 或 Tailscale 私网地址" }
    }
    fun call(base: String, token: String, path: String, body: JSONObject?=null): JSONObject {
        validate(base)
        val connection=URI(base.trimEnd('/')+path).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects=false
            connection.connectTimeout=15000; connection.readTimeout=120000
            connection.setRequestProperty("Accept","application/json")
            if(token.isNotBlank()) connection.setRequestProperty("Authorization","Bearer $token")
            if(body!=null) {
                val bytes=body.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size<=2*1024*1024) { "同步批次过大" }
                connection.requestMethod="POST";connection.doOutput=true
                connection.setRequestProperty("Content-Type","application/json; charset=utf-8")
                connection.setFixedLengthStreamingMode(bytes.size);connection.outputStream.use { it.write(bytes) }
            }
            val status=connection.responseCode
            val stream=if(status in 200..299) connection.inputStream else connection.errorStream
            val raw=stream?.use { input ->
                val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
                while(out.size()<=4*1024*1024) { val count=input.read(buffer); if(count<0) break;out.write(buffer,0,count) }
                out.toByteArray()
            } ?: byteArrayOf()
            require(raw.size<=4*1024*1024) { "响应过大" }
            val result=runCatching { JSONObject(String(raw,Charsets.UTF_8)) }.getOrDefault(JSONObject())
            if(status !in 200..299) throw LinkFailure(status,result.optString("error","同步服务 HTTP $status"))
            return result
        } finally { connection.disconnect() }
    }
}
