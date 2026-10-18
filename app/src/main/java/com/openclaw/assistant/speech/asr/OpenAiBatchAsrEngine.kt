package com.openclaw.assistant.speech.asr

import android.content.Context
import android.speech.SpeechRecognizer
import android.util.Log
import com.openclaw.assistant.R
import com.openclaw.assistant.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "OpenAiBatchAsr"

/**
 * 人声判定：先量出环境噪声底（开头若干帧的平均），再要求"比噪声底高 8dB"才算有人说话。
 * 不用固定阈值 —— 本机 VOICE_RECOGNITION 这条链路的噪声底高达 -21dB，
 * 固定 -45dB 就等于"一直有人在说话"，于是永远等不到静音、永远不上传。
 */
private const val VOICE_ABOVE_NOISE_DB = 8.0

/** 真静音时也不能因为噪声底特别低而过度灵敏 */
private const val VOICE_ABS_FLOOR_DB = -50.0

/** 用开头这么多帧（每帧 100ms）估噪声底 */
private const val NOISE_FLOOR_FRAMES = 5

/** 单轮录音上限，兜底防挂死 */
private const val MAX_UTTERANCE_MS = 30_000L

/** 一直没听到人声时的兜底：超过这个时长就按"识别超时"安静收尾（留够反应时间） */
private const val NO_SPEECH_TIMEOUT_MS = 8_000L

/** 麦克风连续这么久读不到数据（被占用/隐私开关等）就报错收尾 */
private const val READ_FAIL_TIMEOUT_MS = 3_000L

/**
 * OpenAI 兼容「批式」后端：录一整段 → 上传 → 拿整段文本。
 * 与 PC 上现成的 funasr OpenAI 兼容服务（POST /v1/audio/transcriptions）配套：
 *   multipart 字段 file + model + language + response_format=json
 * 录音用 AudioRecord 直接取 16k/mono/PCM16，写标准 WAV 再上传（对服务端最省事）。
 * 自带能量 VAD：听到人声后，静音超过 silenceTimeoutMs 就自动收尾；一直没人说话也会超时收尾。
 * 协议决定它没有增量结果：说完才出文字。
 */
class OpenAiBatchAsrEngine(private val context: Context) : AsrEngine {
    private val settings = SettingsRepository.getInstance(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)   // 懒加载的服务首个请求可能要十几秒
        .build()

    @Volatile private var recording = false
    @Volatile private var cancelled = false

    override val kind = AsrEngineKind.OPENAI_BATCH

    private fun endpoint(): String = settings.asrOpenAiUrl.trim()

    override fun isAvailable(): Boolean = endpoint().isNotEmpty()

    override fun stop() {
        recording = false
    }

    override fun cancel() {
        cancelled = true
        recording = false
    }

    override fun release() {
        cancelled = true
        recording = false
    }

    override fun startListening(language: String?, silenceTimeoutMs: Long): Flow<AsrEvent> =
        callbackFlow {
            val url = endpoint()
            if (url.isEmpty()) {
                trySend(AsrEvent.Error(context.getString(R.string.asr_error_no_url)))
                close()
                return@callbackFlow
            }
            val rec = AudioUtils.openRecorder()
            if (rec == null) {
                trySend(AsrEvent.Error(context.getString(R.string.asr_error_record)))
                close()
                return@callbackFlow
            }

            cancelled = false
            recording = true
            val pcm = ByteArrayOutputStream()
            val model = settings.asrOpenAiModel.trim().ifBlank { DEFAULT_OPENAI_ASR_MODEL }
            val lang = (language ?: settings.asrLanguage).trim().ifBlank { DEFAULT_ASR_LANGUAGE }
            // 收尾条件与静音阈值都取设置里的值（系统识别器那条不走这里）
            val endSilenceMs = settings.asrEndSilenceMs.coerceIn(300L, 10_000L)
            val silenceFloorDb = settings.asrSilenceThresholdDb.toDouble()

            Log.i(TAG, "开始录音 url=" + url + " model=" + model + " lang=" + lang +
                " 收尾静音=" + endSilenceMs + "ms 静音阈值=" + silenceFloorDb + "dB")
            trySend(AsrEvent.Ready)
            trySend(AsrEvent.Listening)
            rec.startRecording()

            val job = launch(Dispatchers.IO) {
                val buf = ByteArray(AudioUtils.SAMPLE_RATE / 10 * 2)   // 100ms
                var lastVoiceAt = System.currentTimeMillis()
                var lastDataAt = System.currentTimeMillis()
                var heardVoice = false
                var readFailed = false
                var noSpeech = false
                val startedAt = System.currentTimeMillis()
                val noSpeechDeadline = startedAt + maxOf(endSilenceMs, NO_SPEECH_TIMEOUT_MS)
                var peakDb = -100.0
                var frames = 0
                var lastLevelLog = startedAt
                val noiseSamples = mutableListOf<Double>()
                var noiseFloorDb = -60.0
                var voiceThresholdDb = VOICE_ABS_FLOOR_DB
                while (recording) {
                    val n = rec.read(buf, 0, buf.size)
                    val now = System.currentTimeMillis()
                    if (n <= 0) {
                        if (now - lastDataAt > READ_FAIL_TIMEOUT_MS) {
                            readFailed = true
                            break
                        }
                        continue
                    }
                    lastDataAt = now
                    pcm.write(buf, 0, n)
                    val db = AudioUtils.rmsDb(buf, n)
                    if (db > peakDb) peakDb = db
                    if (noiseSamples.size < NOISE_FLOOR_FRAMES) {
                        noiseSamples.add(db)
                        noiseFloorDb = noiseSamples.min()
                        voiceThresholdDb = maxOf(noiseFloorDb + VOICE_ABOVE_NOISE_DB, silenceFloorDb)
                        Log.d(TAG, "估噪声底: " + String.format("%.1f", noiseFloorDb) +
                            "dB → 人声阈值 " + String.format("%.1f", voiceThresholdDb) + "dB（第 " +
                            noiseSamples.size + " 帧）")
                    }
                    frames++
                    if (now - lastLevelLog >= 1000) {
                        Log.d(TAG, "录音中: 每秒峰值=" + String.format("%.1f", peakDb) + "dB 本次电平=" +
                            String.format("%.1f", db) + "dB 阈值=" + String.format("%.1f", voiceThresholdDb) +
                            "dB 达到人声阈值=" + heardVoice + " 帧数=" + frames)
                        lastLevelLog = now
                    }
                    trySend(AsrEvent.Rms(db.toFloat()))
                    if (db > voiceThresholdDb) {
                        heardVoice = true
                        lastVoiceAt = now
                    }
                    if (heardVoice && now - lastVoiceAt > endSilenceMs) break
                    if (!heardVoice && now > noSpeechDeadline) {
                        noSpeech = true
                        break
                    }
                    if (now - startedAt > MAX_UTTERANCE_MS) break
                }
                runCatching { rec.stop() }
                runCatching { rec.release() }
                if (cancelled) {
                    close()
                    return@launch
                }
                if (readFailed) {
                    trySend(AsrEvent.Error(context.getString(R.string.asr_error_record), SpeechRecognizer.ERROR_AUDIO))
                    close()
                    return@launch
                }
                val data = pcm.toByteArray()
                if (noSpeech || data.size < AudioUtils.SAMPLE_RATE / 2) {
                    Log.i(TAG, "判定: 没听到人声（峰值=" + String.format("%.1f", peakDb) + "dB, noSpeech=" +
                        noSpeech + ", 采样=" + data.size + " 字节）→ 按识别超时收尾")
                    // 一句都没听到：按"识别超时"报，会话会安静收尾（不弹错误页），和系统识别器一致
                    trySend(
                        AsrEvent.Error(
                            context.getString(R.string.asr_error_empty),
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        )
                    )
                    close()
                    return@launch
                }
                Log.i(TAG, "录音结束: 峰值=" + String.format("%.1f", peakDb) + "dB 听到人声=" + heardVoice +
                    " 时长=" + data.size * 1000L / (AudioUtils.SAMPLE_RATE * 2) + "ms → 上传")
                trySend(AsrEvent.Processing)   // 界面切到"处理中"，上传慢的时候用户知道在跑
                transcribe(url, model, lang, data).fold(
                    onSuccess = { text ->
                        Log.i(TAG, "识别结果: " + text)
                        trySend(AsrEvent.Final(text))
                    },
                    onFailure = { e ->
                        Log.w(TAG, "上传失败: " + e.message)
                        trySend(AsrEvent.Error(uploadError(e)))
                    }
                )
                close()
            }

            awaitClose {
                recording = false
                job.cancel()
            }
        }

    private fun uploadError(e: Throwable): String =
        context.getString(R.string.asr_error_upload_detail, e.message ?: e.javaClass.simpleName)

    /** 上传并取文本；失败时抛出带原因的异常，好让提示里能看到到底是哪一步出错 */
    private fun transcribe(url: String, model: String, lang: String, pcm: ByteArray): Result<String> =
        runCatching {
            val file = File(context.cacheDir, "asr_" + System.currentTimeMillis() + ".wav")
            AudioUtils.writeWav(pcm, file)
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType()))
                    .addFormDataPart("model", model)
                    .addFormDataPart("language", lang)
                    .addFormDataPart("response_format", "json")
                    .build()
                client.newCall(Request.Builder().url(url).post(body).build()).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("HTTP " + resp.code + " " + raw.take(120))
                    }
                    val text = parseText(raw)
                    if (text.isNullOrBlank()) throw IllegalStateException("空响应 " + raw.take(80))
                    text
                }
            } finally {
                file.delete()
            }
        }

    /** funasr 的 /v1/audio/transcriptions 回 {"text": "..."}；顺带兼容纯文本与 verbose_json */
    private fun parseText(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("{")) return trimmed
        val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        val text = obj.optString("text")
        if (text.isNotBlank()) return text
        val segs = obj.optJSONArray("segments") ?: return null
        val sb = StringBuilder()
        for (i in 0 until segs.length()) sb.append(segs.optJSONObject(i)?.optString("text").orEmpty())
        return sb.toString().ifBlank { null }
    }
}
