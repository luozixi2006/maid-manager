package com.miniichat.proactive.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Separate from phone/watch pairing. Credentials are protected by Android Keystore. */
class RemotePushConfig(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("remote_push", Context.MODE_PRIVATE)
    val enabled get() = prefs.getBoolean("enabled", false)
    val base get() = prefs.getString("base", "").orEmpty()
    val deviceId get() = prefs.getString("device_id", "").orEmpty()
    val paired get() = deviceId.isNotBlank() && base.isNotBlank() && prefs.contains("credentials")
    val cursor get() = prefs.getLong("cursor", 0L)
    val status get() = prefs.getString("status", "尚未连接电脑推送服务").orEmpty()
    val shareModelKey get() = prefs.getBoolean("share_model_key", false)
    val pausePending get() = prefs.getBoolean("pause_pending", false)
    fun pausePending(value: Boolean) { check(prefs.edit().putBoolean("pause_pending", value).commit()) }
    fun enabled(value: Boolean) { check(prefs.edit().putBoolean("enabled", value).commit()) }
    fun cursor(value: Long) { require(value >= 0); check(prefs.edit().putLong("cursor", value).commit()) }
    fun status(value: String) { prefs.edit().putString("status", value.take(240)).apply() }
    fun shareModelKey(value: Boolean) { check(prefs.edit().putBoolean("share_model_key", value).commit()) }

    fun pair(base: String, result: JSONObject) {
        val checked = RemotePushAddress.validate(base)
        val id = result.getString("device_id")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "电脑返回的设备信息无效" }
        val token = result.getString("token")
        require(token.matches(Regex("[A-Za-z0-9_-]{32,256}"))) { "电脑返回的配对凭证无效" }
        val key = result.getString("payload_key")
        require(Base64.getDecoder().decode(key).size == 32) { "电脑返回的加密信息无效" }
        val credentials = seal(JSONObject().put("token", token).put("payload_key", key).toString())
        check(prefs.edit().putString("base", checked).putString("device_id", id)
            .putString("credentials", credentials).putLong("cursor", 0L).putBoolean("enabled", true).commit())
    }

    fun token(): String = credentials().getString("token")
    fun payloadKey(): ByteArray = Base64.getDecoder().decode(credentials().getString("payload_key"))
    fun fcmToken(): String = prefs.getString("fcm_token", null)?.let(::open).orEmpty()
    fun fcmToken(value: String) {
        require(value.isNotBlank() && value.length <= 4096)
        check(prefs.edit().putString("fcm_token", seal(value)).commit())
    }
    private fun credentials(): JSONObject = try {
        JSONObject(open(prefs.getString("credentials", "").orEmpty()))
    } catch (_: Exception) { throw IllegalStateException("推送凭证无法读取，请重新连接电脑") }

    private fun seal(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }
    private fun open(value: String): String {
        val bytes = Base64.getDecoder().decode(value)
        require(bytes.size >= 28)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    }
    private fun key(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    companion object {
        private const val KEY_ALIAS = "maid_remote_push_credentials_v1"
        private val KEY_LOCK = Any()
    }
}

object RemotePushAddress {
    /** Plain HTTP is only allowed inside encrypted Tailscale, or for local development. */
    fun validate(value: String): String {
        val address = value.trim().trimEnd('/')
        val uri = try { URI(address) } catch (_: Exception) { throw IllegalArgumentException("电脑地址格式不正确") }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty()) {
            "请填写电脑服务地址，不要附加路径、密钥或参数"
        }
        val host = uri.host.orEmpty().lowercase()
        val parts = host.split('.').map { it.toIntOrNull() }
        val tailscale = parts.size == 4 && parts.all { it != null && it in 0..255 } && parts[0] == 100 && parts[1] in 64..127
        val privateHost = tailscale || host == "127.0.0.1" || host == "localhost" || host.endsWith(".ts.net")
        require(privateHost && (uri.scheme == "https" || (uri.scheme == "http" && !host.endsWith(".ts.net")))) {
            "请使用电脑的 Tailscale IPv4 地址，或 HTTPS 的 Tailscale 域名"
        }
        require(uri.port == -1 || uri.port in 1..65535) { "端口不正确" }
        return address
    }
}
