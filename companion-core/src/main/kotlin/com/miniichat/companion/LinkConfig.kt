package com.miniichat.companion

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Device token stays in private storage encrypted with the app's Android Keystore key. */
class LinkConfig(context: Context) {
    private val prefs = context.getSharedPreferences("companion_link", Context.MODE_PRIVATE)
    val base: String get() = prefs.getString("base", "").orEmpty()
    val conversationId: String get() = prefs.getString("conversation", "").orEmpty()
    val deviceId: String get() = prefs.getString("device", "").orEmpty()
    val enabled: Boolean get() = prefs.getBoolean("enabled", false)
    fun enabled(value: Boolean) { prefs.edit().putBoolean("enabled",value).commit() }
    val profile: JSONObject get() = runCatching { JSONObject(prefs.getString("profile","{}")!!) }.getOrDefault(JSONObject())
    fun profile(value: JSONObject) { prefs.edit().putString("profile",value.toString()).commit() }
    fun save(base: String, result: JSONObject) {
        if(base.startsWith("bluetooth://")) BluetoothLink.validate(base) else CompanionHttp.validate(base)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val sealed=cipher.iv+cipher.doFinal(result.getString("token").toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("base",base.trimEnd('/')).putString("conversation",result.getString("conversation_id"))
            .putString("device",result.getString("device_id")).putString("token",Base64.encodeToString(sealed,Base64.NO_WRAP)).putBoolean("enabled",true).commit())
    }
    fun token(): String {
        val sealed=Base64.decode(prefs.getString("token","").orEmpty(),Base64.NO_WRAP)
        require(sealed.size>12) { "请重新配对设备" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,sealed.copyOfRange(0,12)))
            String(doFinal(sealed.copyOfRange(12,sealed.size)),Charsets.UTF_8)
        }
    }
    fun bindPhone(conversation:String,profile:JSONObject) {
        require(conversation.isNotBlank())
        check(prefs.edit().putString("base","local").putString("device","phone").putString("conversation",conversation)
            .putString("profile",profile.toString()).putBoolean("enabled",true).commit())
    }
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("companion_token",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("companion_token",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generateKey()
        }
    }
}
