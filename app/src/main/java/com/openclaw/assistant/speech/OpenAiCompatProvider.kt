package com.openclaw.assistant.speech

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.openclaw.assistant.R
import com.openclaw.assistant.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

private const val TAG = "OpenAiCompatProvider"

/**
 * 「OpenAI 兼容」TTS provider。
 *
 * 和内置的 OpenAI provider（地址硬编码 `api.openai.com`）不同，这个的**服务器地址、模型、音色都可配，
 * API key 可以留空**，用来接任何会讲 OpenAI `/v1/audio/speech` 协议的服务：本机跑
 * Qwen3-TTS / Kokoro / GPT-SoVITS-API，局域网里那台 GPU 主机，或别人搭的兼容网关。
 *
 * 协议（请求体字段都是 OpenAI 规范里固有的，没有自造参数）：
 *
 *     POST {baseUrl}/v1/audio/speech
 *     {"model": "...", "input": "<文本>", "voice": "...", "response_format": "mp3", "speed": 1.0}
 *     → 音频字节（mp3 / wav / ogg ...）
 *
 * 音频落盘的扩展名按响应的 Content-Type 决定，所以服务端回 wav 也能播。
 */
class OpenAiCompatProvider(private val context: Context) : TTSProvider {

    private val settings = SettingsRepository.getInstance(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var mediaPlayer: MediaPlayer? = null

    /** 音频字节 + 该给临时文件用什么后缀 */
    private data class SynthResult(val bytes: ByteArray, val extension: String)

    private fun speechUrl(): String {
        val base = settings.openAiCompatUrl.trim().trimEnd('/')
        return when {
            base.isEmpty() -> ""
            base.endsWith("/audio/speech") -> base
            base.endsWith("/v1") -> "$base/audio/speech"
            else -> "$base/v1/audio/speech"
        }
    }

    private fun extensionFor(contentType: String?): String = when {
        contentType == null -> ".mp3"
        contentType.contains("wav", ignoreCase = true) -> ".wav"
        contentType.contains("ogg", ignoreCase = true) || contentType.contains("opus", ignoreCase = true) -> ".ogg"
        contentType.contains("mpeg", ignoreCase = true) || contentType.contains("mp3", ignoreCase = true) -> ".mp3"
        contentType.contains("aac", ignoreCase = true) -> ".aac"
        contentType.contains("flac", ignoreCase = true) -> ".flac"
        else -> ".mp3"
    }

    override suspend fun speak(text: String): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            Log.e(TAG, "Not configured: ${getConfigurationError()}")
            return@withContext false
        }

        try {
            val audio = synthesizeSpeech(text)
            if (audio == null) {
                Log.e(TAG, "Failed to synthesize speech")
                return@withContext false
            }

            val tempFile = File.createTempFile("openai_compat_", audio.extension, context.cacheDir)
            FileOutputStream(tempFile).use { it.write(audio.bytes) }
            try {
                playAudioFile(tempFile)
            } finally {
                tempFile.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error speaking: ${e.message}", e)
            false
        }
    }

    private suspend fun synthesizeSpeech(text: String): SynthResult? = withContext(Dispatchers.IO) {
        val url = speechUrl()
        if (url.isEmpty()) {
            Log.e(TAG, "Server URL not set")
            return@withContext null
        }
        val apiKey = settings.openAiCompatApiKey.trim()
        val model = settings.openAiCompatModel.trim().ifBlank { "tts-1" }
        val voice = settings.openAiCompatVoice.trim().ifBlank { "alloy" }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("input", text)
            put("voice", voice)
            put("response_format", "mp3")
            put("speed", settings.ttsSpeed.toDouble())
        }.toString()

        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
        // 本地服务通常不校验 key —— 没填就不发 Authorization 头，免得有些服务对空 Bearer 报 401
        if (apiKey.isNotEmpty()) {
            builder.header("Authorization", "Bearer $apiKey")
        }

        try {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "API error: ${response.code} ${response.message}")
                    return@withContext null
                }
                val bytes = response.body?.bytes() ?: return@withContext null
                SynthResult(bytes, extensionFor(response.header("Content-Type")))
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error: ${e.message}", e)
            null
        }
    }

    private suspend fun playAudioFile(file: File, onStarted: (() -> Unit)? = null): Boolean = suspendCancellableCoroutine { continuation ->
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener {
                    start()
                    onStarted?.invoke()
                }
                setOnCompletionListener {
                    continuation.resume(true)
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: $what, $extra")
                    continuation.resume(false)
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio: ${e.message}", e)
            continuation.resume(false)
        }

        continuation.invokeOnCancellation {
            try {
                mediaPlayer?.stop()
                mediaPlayer?.release()
            } catch (e: Exception) {
                mediaPlayer?.release()
            }
            mediaPlayer = null
        }
    }

    override fun stop() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping: ${e.message}")
        }
    }

    override fun shutdown() {
        stop()
    }

    override fun isAvailable(): Boolean = true // 配好地址就可用

    override fun getType(): String = TTSProviderType.OPENAI_COMPAT

    override fun getDisplayName(): String = context.getString(R.string.tts_provider_openai_compat)

    override fun isConfigured(): Boolean = settings.openAiCompatUrl.isNotBlank()

    override fun getConfigurationError(): String? {
        return if (settings.openAiCompatUrl.isBlank()) {
            context.getString(R.string.tts_error_openai_compat_no_url)
        } else null
    }

    override fun speakWithProgress(text: String): Flow<TTSState> = channelFlow {
        send(TTSState.Preparing)

        if (!isConfigured()) {
            send(TTSState.Error(getConfigurationError() ?: context.getString(R.string.tts_error_not_initialized)))
            return@channelFlow
        }

        val audio = try {
            synthesizeSpeech(text)
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis error", e)
            null
        }

        if (audio == null) {
            send(TTSState.Error(context.getString(R.string.tts_error_synthesis_failed)))
            return@channelFlow
        }

        val tempFile = try {
            File.createTempFile("openai_compat_", audio.extension, context.cacheDir).apply {
                FileOutputStream(this).use { it.write(audio.bytes) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save audio", e)
            send(TTSState.Error(context.getString(R.string.tts_error_save_failed)))
            return@channelFlow
        }

        val success = playAudioFile(tempFile) {
            trySend(TTSState.Speaking)
        }
        tempFile.delete()

        if (success) {
            send(TTSState.Done)
        } else {
            send(TTSState.Error(context.getString(R.string.tts_error_playback_failed)))
        }
        awaitClose { }
    }
}
