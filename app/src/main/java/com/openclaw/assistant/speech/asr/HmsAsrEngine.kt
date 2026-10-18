package com.openclaw.assistant.speech.asr

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.huawei.hms.mlsdk.asr.MLAsrConstants
import com.huawei.hms.mlsdk.asr.MLAsrListener
import com.huawei.hms.mlsdk.asr.MLAsrRecognizer
import com.huawei.hms.mlsdk.common.MLApplication
import com.openclaw.assistant.R
import com.openclaw.assistant.data.SettingsRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "HmsAsr"

/**
 * 华为 HMS ML Kit 语音识别：把声音交给系统的 HMS 识别服务，边说边出字（onRecognizingResults 是增量）。
 * 需要：
 *   1) 手机上有 HMS Core（非华为机型要自行安装，否则会报服务不可用/鉴权错误）
 *   2) 自己申请一个 AGC 应用的 API Key（设置里填，不内置任何人的 key）
 * arm64-v8a 以外（x86 模拟器）不支持，和上游 gpt-assistant 的处理一致。
 */
class HmsAsrEngine(private val context: Context) : AsrEngine {
    private val settings = SettingsRepository.getInstance(context)

    override val kind = AsrEngineKind.HMS

    private fun apiKey(): String = settings.asrHmsApiKey.trim()

    override fun isAvailable(): Boolean =
        apiKey().isNotEmpty() && !Build.CPU_ABI.contains("x86")

    private var recognizer: MLAsrRecognizer? = null

    /** 最后一帧增量文字：HMS 没有"停止识别"的 API，destroy 不会回调 onResults，收尾只能拿它顶上 */
    @Volatile private var lastPartial: String = ""

    /** 由 startListening 里的 callbackFlow 注册，供 stop() 主动收尾 */
    @Volatile private var finalizer: ((String) -> Unit)? = null

    override fun stop() {
        val text = lastPartial.trim()
        val finish = finalizer
        finalizer = null
        Log.i(TAG, "stop(): 主动收尾，最后一帧字数=" + text.length + " finalizerSet=" + (finish != null))
        runCatching { recognizer?.destroy() }
        recognizer = null
        // 对讲模式点「结束说话」走这里：SDK 不会自己给最终结果，得把已有的文字交出去
        finish?.invoke(text)
    }

    override fun cancel() {
        finalizer = null
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    override fun release() = cancel()

    companion object {
        /** HMS Network Kit 每个进程只需 init 一次 */
        @Volatile private var networkKitReady = false
    }

    override fun startListening(language: String?, silenceTimeoutMs: Long): Flow<AsrEvent> =
        callbackFlow {
            val key = apiKey()
            if (key.isEmpty()) {
                trySend(AsrEvent.Error(context.getString(R.string.asr_error_no_key)))
                close()
                return@callbackFlow
            }
            if (Build.CPU_ABI.contains("x86")) {
                trySend(AsrEvent.Error(context.getString(R.string.asr_error_hms_unsupported)))
                close()
                return@callbackFlow
            }

            // HMS 云侧识别除了 apiKey 还要 App ID：不带 agconnect-services.json 时，
            // SDK 拿不到应用身份 → 服务端直接以 `appId is invalid` 关连接（code 3002 / 11203）。
            // 所以这里用 MLApplicationSetting 在运行时把 appId / apiKey / 包名 / 签名指纹喂进去。
            val initialized = runCatching {
                val factory = com.huawei.hms.mlsdk.common.MLApplicationSetting.Factory()
                    .setApiKey(key)
                    .setPackageName(context.packageName)
                    // 必须给 acceptHa 一个非 null：SDK 的 MLApplication.toBundle() 直接
                    // getAcceptHa().booleanValue()，为 null 就 NPE（这行 NPE 发生在 SDK 的网络线程上，
                    // 我们抓不到 → 整个进程被杀，界面上就是"已停止运行"）。false = 不上报 HA 统计
                    .setAcceptHa(false)
                com.openclaw.assistant.BuildConfig.HMS_APP_ID
                    .takeIf { it.isNotBlank() }?.let { factory.setApplicationId(it) }
                com.openclaw.assistant.BuildConfig.HMS_CERT_SHA256
                    .takeIf { it.isNotBlank() }?.let { factory.setCertFingerprint(it) }
                MLApplication.initialize(context, factory.create())
                // 关键：initialize() 之后实例是新造的，setting 里的 apiKey 不一定会落到实例上
                // （实测只 initialize 会报 "please set your app apiKey"），所以要在默认实例上再设一次
                MLApplication.getInstance().setApiKey(key)
                MLApplication.getInstance().setUserRegion(MLApplication.REGION_DR_CHINA)
                true
            }.getOrElse {
                Log.w(TAG, "MLApplication 初始化失败：" + it.message)
                false
            }
            val appId = com.openclaw.assistant.BuildConfig.HMS_APP_ID
            val keySet = runCatching {
                !MLApplication.getInstance().getApiKey().isNullOrEmpty()
            }.getOrDefault(false)
            Log.i(TAG, "init=" + initialized + " appId=" +
                (if (appId.isBlank()) "（空）" else appId) + " apiKeySet=" + keySet)

            // 3) HMS Network Kit 必须先 init：ASR 的 WebSocket 走它，没初始化时 startRecognizing 会直接报
            //    "you must call init(final Context context, final Callback callback) … before all Method"
            //    （这句是 com.huawei.hms.network.NetworkKit 抛的，从 SDK 字节码里挖出来的）
            if (!networkKitReady) {
                val done = withTimeoutOrNull(3000L) {
                    suspendCancellableCoroutine<Boolean> { cont ->
                        runCatching {
                            com.huawei.hms.network.NetworkKit.init(
                                context.applicationContext,
                                object : com.huawei.hms.network.NetworkKit.Callback() {
                                    override fun onResult(success: Boolean) {
                                        Log.i(TAG, "NetworkKit.init onResult=" + success)
                                        if (success) networkKitReady = true
                                        if (cont.isActive) cont.resume(success)
                                    }
                                }
                            )
                        }.onFailure {
                            Log.w(TAG, "NetworkKit.init 异常：" + it.message)
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                }
                Log.i(TAG, "NetworkKit init 等待结果=" + done + " ready=" + networkKitReady)
            }

            val asr = runCatching { MLAsrRecognizer.createAsrRecognizer(context) }.getOrNull()
            if (asr == null) {
                // HMS Core 缺失时 create 会返回 null/抛异常——给一句能落地的话，别让它崩
                Log.w(TAG, "createAsrRecognizer 返回 null（HMS Core 未就绪？）")
                trySend(AsrEvent.Error(context.getString(R.string.asr_error_hms_start, "HMS Core"), null))
                close()
                return@callbackFlow
            }
            recognizer = asr
            lastPartial = ""
            val finished = java.util.concurrent.atomic.AtomicBoolean(false)

            var watchdogJob: kotlinx.coroutines.Job? = null

            // 只收一次尾：trySend + close 都在这里，避免多条回调同时关流
            fun closeWith(event: AsrEvent?) {
                if (!finished.compareAndSet(false, true)) return
                finalizer = null
                watchdogJob?.cancel()
                if (event != null) trySend(event)
                close()
            }

            // 自己兜底判"说完了"：服务端其实会回 isFinal，但 3.12.0.301 的 SDK 在这条路径上会抛 NPE
            // （日志：MLASR_AsrProcessor: onFailure socket error … NullPointerException），我们的
            // onResults 永远收不到。所以按"多久没有新的增量文字"来收尾：起始静音 → 安静超时关闭；
            // 说话中途停顿 endSilenceMs → 当成说完，把已有文字当最终结果发出去。
            val endSilenceMs = settings.asrEndSilenceMs.coerceIn(300L, 10_000L)
            fun armWatchdog(ms: Long, label: String) {
                watchdogJob?.cancel()
                watchdogJob = launch {
                    delay(ms)
                    Log.i(TAG, "watchdog($label) 到点，主动收尾")
                    stop()
                }
            }

            asr.setAsrListener(object : MLAsrListener {
                override fun onResults(bundle: Bundle?) {
                    val text = bundle?.getString(MLAsrRecognizer.RESULTS_RECOGNIZED)
                        ?: bundle?.getString("results_recognizing").orEmpty()
                    Log.d(TAG, "onResults: [" + text + "]")
                    if (text.isNotBlank()) {
                        lastPartial = text
                        closeWith(AsrEvent.Final(text))
                    } else {
                        // 空 final = 一直没听到人声（HMS 自己的 VAD 起始静音到点）。
                        // 不能当成普通错误弹"没有听到声音"，要交给会话按超时安静关闭。
                        Log.i(TAG, "onResults 空结果：按超时收尾")
                        closeWith(AsrEvent.Error("", android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
                    }
                }

                override fun onRecognizingResults(bundle: Bundle?) {
                    val text = bundle?.getString("results_recognizing").orEmpty()
                    if (text.isNotBlank()) {
                        lastPartial = text
                        trySend(AsrEvent.Partial(text))
                        // 有新文字 → 重置"说完"计时器
                        armWatchdog(endSilenceMs, "说完静音")
                    }
                }

                override fun onError(code: Int, msg: String?) {
                    var message = "code=" + code + "  " + (msg ?: "")
                    // 11203/11219：鉴权/配额类错误，给一句能落地的提示
                    if (code == 11203 || code == 11219) {
                        message += context.getString(R.string.asr_error_hms_auth)
                    }
                    Log.w(TAG, "onError: " + message)
                    closeWith(AsrEvent.Error(message, code))
                }

                override fun onStartListening() {
                    trySend(AsrEvent.Ready)
                    trySend(AsrEvent.Listening)
                }

                override fun onStartingOfSpeech() {}

                override fun onVoiceDataReceived(bytes: ByteArray?, volume: Float, bundle: Bundle?) {}

                override fun onState(state: Int, bundle: Bundle?) {}
            })

            // 对讲模式/面板上的「结束说话」调 stop()：SDK 不会回调最终结果，用最后一帧增量文字收尾
            finalizer = { text ->
                if (text.isBlank()) {
                    // 一个字都没有 = 没听到人声：用 ERROR_SPEECH_TIMEOUT 让会话安静关闭（不弹错误框）
                    closeWith(AsrEvent.Error("", android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
                } else {
                    closeWith(AsrEvent.Final(text))
                }
            }
            // 一直没人说话：超过 10 秒再收尾。会话那边对"10 秒内的超时"会重试继续听，
            // 这里故意压到 10.5s 之后，让它走"安静关闭、不弹错误框"的那一支
            armWatchdog(maxOf(endSilenceMs, 10_500L), "起始静音")

            val intent = Intent(MLAsrConstants.ACTION_HMS_ASR_SPEECH).apply {
                putExtra(MLAsrConstants.LANGUAGE, hmsLanguage(settings.asrLanguage))
                putExtra(MLAsrConstants.FEATURE, MLAsrConstants.FEATURE_WORDFLUX)
                // 端点时长跟设置里的"收尾条件"走（HMS 自己带 VAD，只能给它时间参数）
                putExtra(MLAsrConstants.VAD_END_MUTE_DURATION, settings.asrEndSilenceMs.coerceIn(300L, 10_000L).toInt())
                // 一直没人说话时别干等：给到 11 秒，让我们自己的 watchdog(10.5s) 先说话，
                // 这样会话按"超过 10 秒的超时"走安静关闭；HMS 若把这个值夹到 10s，空 final
                // 也会被上面的 onResults 当成超时处理
                putExtra(MLAsrConstants.VAD_START_MUTE_DURATION, maxOf(settings.asrEndSilenceMs, 11_000L).toInt())
                putExtra(MLAsrConstants.PUNCTUATION_ENABLE, true)
            }
            try {
                asr.startRecognizing(intent)
            } catch (e: Exception) {
                Log.w(TAG, "startRecognizing failed: " + e.message)
                trySend(AsrEvent.Error(context.getString(R.string.asr_error_hms_start, e.message ?: "")))
                close()
            }

            awaitClose {
                finalizer = null
                watchdogJob?.cancel()
                runCatching { asr.destroy() }
                if (recognizer === asr) recognizer = null
            }
        }

    /** 设置里的语言码 → HMS 要的写法 */
    private fun hmsLanguage(setting: String): String = when (setting.trim().lowercase()) {
        "en", "en-us" -> "en-US"
        "zh", "zh-cn" -> "zh-CN"
        "zh-tw", "zh-hk" -> "zh-TW"
        else -> "zh-CN"
    }
}
