package com.miniichat.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File

enum class TtsStatus { IDLE, LOADING, PLAYING, PAUSED }

data class TtsPlaybackState(
    val messageId: String? = null,
    val status: TtsStatus = TtsStatus.IDLE
)

class TtsManager(context: Context) {
    private companion object {
        const val TAG = "MaidManagerTts"
    }

    private val cacheDir = File(context.cacheDir, "tts").apply { mkdirs() }
    private val provider = CustomHttpTtsProvider()
    private val _state = MutableStateFlow(TtsPlaybackState())
    val state: StateFlow<TtsPlaybackState> = _state.asStateFlow()
    private val _errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 1)
    val errors: SharedFlow<Throwable> = _errors.asSharedFlow()
    private var player: MediaPlayer? = null

    suspend fun playOrToggle(messageId: String, rawText: String, config: TtsConfig): File? {
        val current = _state.value
        if (current.messageId == messageId && current.status == TtsStatus.PLAYING) {
            player?.pause()
            _state.value = current.copy(status = TtsStatus.PAUSED)
            return cacheFile(messageId).takeIf { it.exists() }
        }
        if (current.messageId == messageId && current.status == TtsStatus.PAUSED) {
            player?.start()
            _state.value = current.copy(status = TtsStatus.PLAYING)
            return cacheFile(messageId).takeIf { it.exists() }
        }

        stop()
        val text = sanitizeTextForTts(rawText)
        require(text.isNotBlank()) { "没有可朗读的文字" }
        require(config.baseUrl.isNotBlank()) { "请先填写 TTS Base URL" }
        require(config.model.isNotBlank()) { "请先填写 TTS Model" }
        require(config.voiceId.isNotBlank()) { "请先填写 Voice ID" }
        _state.value = TtsPlaybackState(messageId, TtsStatus.LOADING)
        val file = cacheFile(messageId)
        if (!file.exists() || file.length() == 0L) {
            val bytes = provider.synthesize(config, text)
            saveWave(file, bytes)
        } else {
            Log.d(TAG, "Using cached WAV messageId=$messageId path=${file.absolutePath} bytes=${file.length()}")
        }
        startPlayer(messageId, file)
        return file
    }

    private suspend fun saveWave(file: File, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        try {
            file.parentFile?.mkdirs()
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
            if (!file.exists() || file.length() == 0L) {
                throw IllegalStateException("写入后文件为空")
            }
            Log.d(TAG, "Saved WAV path=${file.absolutePath} bytes=${file.length()}")
        } catch (error: Throwable) {
            temporary.delete()
            file.delete()
            Log.e(TAG, "WAV save failed type=${error::class.java.name}")
            throw IllegalStateException("WAV 保存失败", error)
        }
    }

    private fun startPlayer(messageId: String, file: File) {
        val mediaPlayer = MediaPlayer()
        try {
            mediaPlayer.apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    it.release()
                    if (player === it) player = null
                    _state.value = TtsPlaybackState()
                }
                setOnErrorListener { failedPlayer, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra path=${file.absolutePath}")
                    failedPlayer.release()
                    if (player === failedPlayer) player = null
                    _state.value = TtsPlaybackState()
                    _errors.tryEmit(IllegalStateException("音频播放失败"))
                    true
                }
                prepare()
                start()
            }
            player = mediaPlayer
            _state.value = TtsPlaybackState(messageId, TtsStatus.PLAYING)
            Log.d(TAG, "Playing WAV messageId=$messageId path=${file.absolutePath}")
        } catch (error: Throwable) {
            mediaPlayer.release()
            file.delete()
            Log.e(TAG, "WAV decode failed type=${error::class.java.name}")
            throw IllegalStateException("音频解码失败", error)
        }
    }

    fun stop() {
        player?.runCatching { stop() }
        player?.release()
        player = null
        _state.value = TtsPlaybackState()
    }

    fun deleteCache(messageId: String) {
        if (_state.value.messageId == messageId) stop()
        cacheFile(messageId).delete()
        legacyCacheFile(messageId).delete()
    }

    fun deleteCaches(messageIds: Collection<String>) = messageIds.forEach(::deleteCache)

    private fun cacheFile(messageId: String): File {
        val safe = messageId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(cacheDir, "$safe.wav")
    }

    private fun legacyCacheFile(messageId: String): File {
        val safe = messageId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(cacheDir, "$safe.audio")
    }

    fun close() {
        stop()
        provider.close()
    }
}
