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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "FunAsrStreamAsr"

/** 每次发往服务端的 PCM 长度（毫秒） */
private const val CHUNK_MS = 600

/**
 * 人声判定：先量出环境噪声底（开头若干帧的平均），再要求"比噪声底高这么多"才算有人说话。
 * 不用固定阈值 —— 本机 VOICE_RECOGNITION 这条链路的噪声底高达 -21dB，
 * 固定 -45dB 就等于"一直有人在说话"，永远等不到静音。
 */
private const val VOICE_ABOVE_NOISE_DB = 8.0

/** 但真静音时也不能因为噪声底特别低就变得过于灵敏 */
private const val VOICE_ABS_FLOOR_DB = -50.0

/** 用开头这么多帧（每帧 100ms）估噪声底 */
private const val NOISE_FLOOR_FRAMES = 5

/** 单轮上限，兜底防挂死 */
private const val MAX_UTTERANCE_MS = 60_000L

/** 发完 STOP 后等待最终结果的时间 */
private const val FINAL_WAIT_MS = 8_000L

/** 一直没听到人声时的兜底：超过这个时长就按"识别超时"安静收尾 */
private const val NO_SPEECH_TIMEOUT_MS = 8_000L

/** 麦克风连续这么久读不到数据就报错收尾 */
private const val READ_FAIL_TIMEOUT_MS = 3_000L

/**
 * FunASR 流式后端（WebSocket）：边说边出字。
 * 协议对的是 PC 上 funasr 1.4.x 自带的实时服务 `python -m funasr.bin.realtime_ws`：
 *   客户端→服务端：文本帧 "START"，可选 "LANGUAGE:zh"；随后二进制 PCM(16k/mono/int16) 帧；最后 "STOP"
 *   服务端→客户端：{"event":"started"|"language_set"|"stopped"|"error"}
 *                以及结果 {"sentences":[...],"partial":"...","partial_start_ms":N,"duration_ms":N,"is_final":bool}
 * 自动断句：本地能量 VAD 判定"说完了"就发 STOP；一直没人说话也会超时收尾。
 */
class FunAsrStreamAsrEngine(private val context: Context) : AsrEngine {
    private val settings = SettingsRepository.getInstance(context)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)     // WebSocket 长连接，不设读超时
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var recording = false
    @Volatile private var cancelled = false

    override val kind = AsrEngineKind.FUNASR_STREAM

    private fun wsUrl(): String = settings.asrFunAsrUrl.trim()

    override fun isAvailable(): Boolean = wsUrl().isNotEmpty()

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
        runCatching { ws?.cancel() }
        ws = null
    }

    override fun startListening(language: String?, silenceTimeoutMs: Long): Flow<AsrEvent> =
        callbackFlow {
            val url = wsUrl()
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
            val lang = (language ?: settings.asrLanguage).trim()
            val endSilenceMs = settings.asrEndSilenceMs.coerceIn(300L, 10_000L)
            val silenceFloorDb = settings.asrSilenceThresholdDb.toDouble()
            val locked = StringBuilder()
            val finished = AtomicBoolean(false)

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("START")
                    if (lang.isNotEmpty()) webSocket.send("LANGUAGE:" + lang)
                    trySend(AsrEvent.Ready)
                    trySend(AsrEvent.Listening)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val ev = parseMessage(text, locked) ?: return
                    trySend(ev)
                    when (ev) {
                        is AsrEvent.Final -> if (finished.compareAndSet(false, true)) close()
                        is AsrEvent.Error -> if (finished.compareAndSet(false, true)) close()
                        else -> {}
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "ws failure: " + t.message)
                    if (!cancelled) {
                        trySend(AsrEvent.Error(context.getString(R.string.asr_error_ws, t.message ?: "")))
                    }
                    if (finished.compareAndSet(false, true)) close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (finished.compareAndSet(false, true)) close()
                }
            }
            ws = client.newWebSocket(Request.Builder().url(url).build(), listener)

            val job = launch(Dispatchers.IO) {
                rec.startRecording()
                val buf = ByteArray(AudioUtils.SAMPLE_RATE / 1000 * 2 * CHUNK_MS)
                var lastVoiceAt = System.currentTimeMillis()
                var lastDataAt = System.currentTimeMillis()
                var heardVoice = false
                var readFailed = false
                var noSpeech = false
                val startedAt = System.currentTimeMillis()
                val noSpeechDeadline = startedAt + maxOf(endSilenceMs, NO_SPEECH_TIMEOUT_MS)
                var peakDb = -100.0
                var lastLevelLog = startedAt
                val noiseSamples = mutableListOf<Double>()
                var noiseFloorDb = -60.0
                var voiceThresholdDb = VOICE_ABS_FLOOR_DB
                while (recording) {
                    var filled = 0
                    while (filled < buf.size && recording) {
                        val n = rec.read(buf, filled, buf.size - filled)
                        if (n <= 0) break
                        filled += n
                    }
                    val now = System.currentTimeMillis()
                    if (filled <= 0) {
                        if (now - lastDataAt > READ_FAIL_TIMEOUT_MS) {
                            readFailed = true
                            break
                        }
                        continue
                    }
                    lastDataAt = now
                    val db = AudioUtils.rmsDb(buf, filled)
                    if (db > peakDb) peakDb = db
                    if (noiseSamples.size < NOISE_FLOOR_FRAMES) {
                        noiseSamples.add(db)
                        noiseFloorDb = noiseSamples.min()
                        voiceThresholdDb = maxOf(noiseFloorDb + VOICE_ABOVE_NOISE_DB, silenceFloorDb)
                        Log.d(TAG, "估噪声底: " + String.format("%.1f", noiseFloorDb) +
                            "dB → 人声阈值 " + String.format("%.1f", voiceThresholdDb) + "dB")
                    }
                    if (now - lastLevelLog >= 1000) {
                        Log.d(TAG, "录音中: 每秒峰值=" + String.format("%.1f", peakDb) + "dB 达到人声阈值=" + heardVoice)
                        lastLevelLog = now
                    }
                    trySend(AsrEvent.Rms(db.toFloat()))
                    if (db > voiceThresholdDb) {
                        heardVoice = true
                        lastVoiceAt = now
                    }
                    if (!cancelled) ws?.send(buf.copyOf(filled).toByteString())
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
                    runCatching { ws?.send("STOP") }
                    runCatching { ws?.close(1000, "cancel") }
                    if (finished.compareAndSet(false, true)) close()
                    return@launch
                }
                if (readFailed) {
                    trySend(AsrEvent.Error(context.getString(R.string.asr_error_record), SpeechRecognizer.ERROR_AUDIO))
                    runCatching { ws?.close(1000, "read-fail") }
                    if (finished.compareAndSet(false, true)) close()
                    return@launch
                }
                if (noSpeech) {
                    Log.i(TAG, "判定: 没听到人声（峰值=" + String.format("%.1f", peakDb) + "dB）→ 收尾")
                    // 一句都没听到：安静收尾（不弹错误页），和系统识别器一致
                    trySend(
                        AsrEvent.Error(
                            context.getString(R.string.asr_error_empty),
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        )
                    )
                    runCatching { ws?.send("STOP") }
                    runCatching { ws?.close(1000, "no-speech") }
                    if (finished.compareAndSet(false, true)) close()
                    return@launch
                }
                // 正常收尾：告诉服务端说完了，等最终结果
                trySend(AsrEvent.Processing)   // 等服务端把最后一段吐完，界面显示"处理中"
                runCatching { ws?.send("STOP") }
                var waited = 0L
                while (waited < FINAL_WAIT_MS && !finished.get()) {
                    Thread.sleep(100)
                    waited += 100
                }
                if (finished.compareAndSet(false, true)) close()
            }

            awaitClose {
                recording = false
                job.cancel()
                runCatching { ws?.send("STOP") }
                runCatching { ws?.close(1000, "bye") }
                ws = null
            }
        }

    /**
     * 解析服务端消息。locked 累积已锁定的句子（服务端每轮回整段 sentences），
     * partial 是当前未定稿的增量文本。
     */
    private fun parseMessage(text: String, locked: StringBuilder): AsrEvent? {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null

        when (obj.optString("event")) {
            "error" -> return AsrEvent.Error(obj.optString("error"))
            "started", "stopped", "language_set", "hotwords_set" -> return null
            else -> {}
        }

        val sentences = obj.optJSONArray("sentences")
        if (sentences != null && sentences.length() > 0) {
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                when (val item = sentences.opt(i)) {
                    is JSONObject -> sb.append(item.optString("text"))
                    is String -> sb.append(item)
                    else -> {}
                }
            }
            if (sb.isNotBlank()) {
                locked.setLength(0)
                locked.append(sb)
            }
        }

        val partial = obj.optString("partial")
        val combined = (locked.toString() + partial).trim()
        val isFinal = obj.optBoolean("is_final", false)
        return when {
            isFinal && combined.isNotEmpty() -> AsrEvent.Final(combined)
            isFinal -> null
            combined.isNotEmpty() -> AsrEvent.Partial(combined)
            else -> null
        }
    }
}
