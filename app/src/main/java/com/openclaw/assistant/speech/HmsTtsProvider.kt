package com.openclaw.assistant.speech

import android.content.Context
import android.util.Log
import com.huawei.hms.mlsdk.common.MLApplication
import com.huawei.hms.mlsdk.common.MLApplicationSetting
import com.huawei.hms.mlsdk.tts.MLTtsCallback
import com.huawei.hms.mlsdk.tts.MLTtsConfig
import com.huawei.hms.mlsdk.tts.MLTtsConstants
import com.huawei.hms.mlsdk.tts.MLTtsEngine
import com.huawei.hms.mlsdk.tts.MLTtsError
import com.huawei.hms.mlsdk.tts.MLTtsWarn
import com.openclaw.assistant.BuildConfig
import com.openclaw.assistant.R
import com.openclaw.assistant.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val TAG = "HmsTts"

/**
 * 华为 HMS ML Kit 语音合成 —— **只走云端合成**（`TTS_ONLINE_MODE`）。
 *
 * 为什么不带设备端（离线）那套：华为官方 FAQ 写明「离线语音合成服务仅支持华为和荣耀手机」，
 * 非华为 ROM 拿不到 `huawei_module_mlkit_tts_eagle` 动态模块，模型查询直接回
 * `The model does not exist`。云端合成按 ML Kit《服务价格》：100 万字符/月以内免费，
 * 超出 50 元/百万字符。
 *
 * 云侧要求：联网 + AGC 的 apiKey（设置里可填，缺省用构建时带进来的那个）。单次 ≤500 字，
 * 长文本在本类里按句子切开逐段念。
 */
class HmsTtsProvider(private val context: Context) : TTSProvider {

    companion object {
        /** 云端合成单次上限 500 字（华为文档），留点余量 */
        private const val MAX_CHARS = 450
    }

    private val settings = SettingsRepository.getInstance(context)

    private var engine: MLTtsEngine? = null
    private var enginePerson: String? = null

    /** 当前这轮合成的收尾回调：SDK 播完 / 出错时 resume 它 */
    private var pending: kotlinx.coroutines.CancellableContinuation<Boolean>? = null

    private val callback = object : MLTtsCallback {
        override fun onError(taskId: String?, error: MLTtsError?) {
            Log.e(TAG, "onError task=$taskId id=${error?.errorId} msg=${error?.errorMsg}")
            finish(false)
        }

        override fun onWarn(taskId: String?, warn: MLTtsWarn?) {
            Log.w(TAG, "onWarn task=$taskId msg=${warn?.warnMsg}")
        }

        override fun onRangeStart(taskId: String?, start: Int, end: Int) = Unit

        override fun onAudioAvailable(
            taskId: String?,
            audioFragment: com.huawei.hms.mlsdk.tts.MLTtsAudioFragment?,
            offset: Int,
            range: android.util.Pair<Int, Int>?,
            extra: android.os.Bundle?
        ) = Unit

        override fun onEvent(taskId: String?, eventId: Int, extra: android.os.Bundle?) {
            // EVENT_SYNTHESIS_COMPLETE = 7（合成结束），EVENT_PLAY_STOP = 4（播放结束）
            if (eventId == MLTtsConstants.EVENT_SYNTHESIS_COMPLETE ||
                eventId == MLTtsConstants.EVENT_PLAY_STOP
            ) {
                Log.d(TAG, "onEvent task=$taskId event=$eventId")
                finish(true)
            }
        }
    }

    private fun finish(ok: Boolean) {
        val cont = pending
        pending = null
        if (cont != null && cont.isActive) cont.resume(ok)
    }

    /** 云端合成的 apiKey：只认设置里那个（debug 包默认已预填，正式包要用户自己申请） */
    private fun apiKey(): String = settings.ttsHmsApiKey.trim()

    /**
     * 和 ASR 一样要先把 MLApplication 初始化好（apiKey + appId + 包名 + 指纹，acceptHa 不能为 null）。
     * 这一步重复做也没关系：SDK 允许 initialize 多次，每次都会重建默认实例。
     */
    private fun ensureSdkInitialized(): Boolean = runCatching {
        // 以 manifest 里的 metadata 为底（MLApplicationSetting.fromResource），再覆盖我们自己的值：
        // 模型下载那条 HTTP 会带 X-Mlkit-Version 头，值取 setting.getMLSdkVersion()，自己 new 一个
        // Factory() 会让它是 null → 头部 value 为 null → Headers$Builder 直接 NPE，模型永远下不下来。
        val base = runCatching { MLApplicationSetting.fromResource(context) }.getOrNull()
        val factory = (if (base != null) MLApplicationSetting.Factory(base) else MLApplicationSetting.Factory())
            .setApiKey(apiKey())
            .setPackageName(context.packageName)
            .setAcceptHa(false)
        if (base?.getMLSdkVersion().isNullOrBlank()) {
            factory.setMLSdkVersion("ml-computer-voice-tts:3.17.2.300")
        }
        BuildConfig.HMS_APP_ID.takeIf { it.isNotBlank() }?.let { factory.setApplicationId(it) }
        BuildConfig.HMS_CERT_SHA256.takeIf { it.isNotBlank() }?.let { factory.setCertFingerprint(it) }
        MLApplication.initialize(context, factory.create())
        MLApplication.getInstance().setApiKey(apiKey())
        MLApplication.getInstance().setUserRegion(MLApplication.REGION_DR_CHINA)
        Log.i(TAG, "init: sdkVersion=" + MLApplication.getInstance().getAppSetting()?.getMLSdkVersion())
        true
    }.getOrElse {
        Log.e(TAG, "MLApplication 初始化失败：${it.message}")
        false
    }

    /** 当前语言标签（BCP-47），取设置里语音模式的语言，缺省 zh */
    private fun languageTag(): String =
        settings.speechLanguage.trim().ifBlank { "zh" }

    /** 语言 → 云端发音人（`Female-zh` 这类是华为云侧的音色编码） */
    private fun personForLanguage(tag: String): String {
        val lang = tag.substringBefore('-').lowercase()
        return when (lang) {
            "zh" -> MLTtsConstants.TTS_SPEAKER_FEMALE_ZH
            "en" -> MLTtsConstants.TTS_SPEAKER_FEMALE_EN
            "fr" -> MLTtsConstants.TTS_SPEAKER_FEMALE_FR
            "es" -> MLTtsConstants.TTS_SPEAKER_FEMALE_ES
            "de" -> MLTtsConstants.TTS_SPEAKER_FEMALE_DE
            "it" -> MLTtsConstants.TTS_SPEAKER_FEMALE_IT
            "ar" -> MLTtsConstants.TTS_SPEAKER_FEMALE_AR
            "ru" -> MLTtsConstants.TTS_SPEAKER_FEMALE_RU
            "th" -> MLTtsConstants.TTS_SPEAKER_FEMALE_TH
            "pl" -> MLTtsConstants.TTS_SPEAKER_FEMALE_PL
            "ms" -> MLTtsConstants.TTS_SPEAKER_FEMALE_MS
            "tr" -> MLTtsConstants.TTS_SPEAKER_FEMALE_TR
            else -> MLTtsConstants.TTS_SPEAKER_FEMALE_ZH
        }
    }

    private fun hmsLanguageFor(tag: String): String {
        val lang = tag.substringBefore('-').lowercase()
        return when (lang) {
            "zh" -> MLTtsConstants.TTS_ZH_HANS
            "en" -> MLTtsConstants.TTS_EN_US
            "fr" -> MLTtsConstants.TTS_LAN_FR_FR
            "es" -> MLTtsConstants.TTS_LAN_ES_ES
            "de" -> MLTtsConstants.TTS_LAN_DE_DE
            "it" -> MLTtsConstants.TTS_LAN_IT_IT
            "ar" -> MLTtsConstants.TTS_LAN_AR_AR
            "ru" -> MLTtsConstants.TTS_LAN_RU_RU
            "th" -> MLTtsConstants.TTS_LAN_TH_TH
            "pl" -> MLTtsConstants.TTS_LAN_PL_PL
            "ms" -> MLTtsConstants.TTS_LAN_MS_MS
            "tr" -> MLTtsConstants.TTS_LAN_TR_TR
            else -> MLTtsConstants.TTS_ZH_HANS
        }
    }

    private fun buildConfig(person: String): MLTtsConfig {
        val tag = languageTag()
        return MLTtsConfig()
            .setLanguage(hmsLanguageFor(tag))
            .setPerson(person)
            // 只有云端：设备端那套在非华为机型上用不了（见类注释）
            .setSynthesizeMode(MLTtsConstants.TTS_ONLINE_MODE)
            .setSpeed(settings.ttsSpeed.coerceIn(0.5f, 2.0f))
            .setVolume(1.0f)
    }

    private fun engineFor(person: String): MLTtsEngine {
        val existing = engine
        if (existing != null && enginePerson == person) return existing
        existing?.shutdown()
        val created = MLTtsEngine(buildConfig(person))
        created.setTtsCallback(callback)
        engine = created
        enginePerson = person
        return created
    }

    override suspend fun speak(text: String): Boolean = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext true
        if (!isConfigured()) {
            Log.e(TAG, "未配置：${getConfigurationError()}")
            return@withContext false
        }
        if (!ensureSdkInitialized()) return@withContext false

        val person = personForLanguage(languageTag())
        val tts = engineFor(person)
        runCatching { tts.updateConfig(buildConfig(person)) }

        // 云端单次 ≤500 字，长回答按句子切开逐段念
        for (chunk in splitText(text)) {
            if (!speakChunk(tts, chunk)) return@withContext false
        }
        true
    }

    /** 一段文字 → 引擎念完（或出错）为止 */
    private suspend fun speakChunk(tts: MLTtsEngine, chunk: String): Boolean =
        suspendCancellableCoroutine { cont ->
            pending = cont
            cont.invokeOnCancellation {
                runCatching { tts.stop() }
                pending = null
            }
            val taskId = runCatching { tts.speak(chunk, MLTtsEngine.QUEUE_FLUSH) }.getOrElse {
                Log.e(TAG, "speak 抛异常：${it.message}", it)
                null
            }
            if (taskId == null) finish(false) else Log.d(TAG, "speak task=$taskId len=${chunk.length}")
        }

    /** 按句子切分，每段 ≤ [MAX_CHARS]（云端模式文档要求单次 ≤500 字） */
    private fun splitText(text: String): List<String> {
        if (text.length <= MAX_CHARS) return listOf(text)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        val sentenceEnds = charArrayOf('。', '！', '？', '；', '\n', '.', '!', '?', ';')
        for (ch in text) {
            sb.append(ch)
            val isEnd = sentenceEnds.contains(ch)
            if ((isEnd && sb.length >= MAX_CHARS / 2) || sb.length >= MAX_CHARS) {
                out += sb.toString()
                sb.setLength(0)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    override fun stop() {
        runCatching { engine?.stop() }
        finish(false)
    }

    override fun shutdown() {
        runCatching { engine?.stop() }
        runCatching { engine?.shutdown() }
        engine = null
        enginePerson = null
        finish(false)
    }

    override fun isAvailable(): Boolean = runCatching {
        context.packageManager.getPackageInfo("com.huawei.hwid", 0)
        true
    }.getOrDefault(false)

    override fun getType(): String = TTSProviderType.HMS

    override fun getDisplayName(): String = context.getString(R.string.tts_provider_hms)

    override fun isConfigured(): Boolean = apiKey().isNotBlank()

    override fun getConfigurationError(): String? =
        if (apiKey().isBlank()) context.getString(R.string.tts_error_hms_no_key) else null

    override fun speakWithProgress(text: String): Flow<TTSState> = channelFlow {
        send(TTSState.Preparing)
        if (!isConfigured()) {
            send(TTSState.Error(getConfigurationError() ?: context.getString(R.string.tts_error_not_initialized)))
            return@channelFlow
        }
        if (!ensureSdkInitialized()) {
            send(TTSState.Error(context.getString(R.string.tts_error_not_initialized)))
            return@channelFlow
        }

        val person = personForLanguage(languageTag())
        val tts = engineFor(person)
        runCatching { tts.updateConfig(buildConfig(person)) }

        send(TTSState.Speaking)
        var ok = true
        for (chunk in splitText(text)) {
            if (!speakChunk(tts, chunk)) {
                ok = false
                break
            }
        }

        if (ok) send(TTSState.Done)
        else send(TTSState.Error(context.getString(R.string.tts_error_playback_failed)))
        awaitClose { }
    }
}
