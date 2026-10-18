package com.openclaw.assistant.speech.asr

import android.content.Context
import android.util.Log
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.speech.SpeechResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val TAG = "AsrManager"

/**
 * 识别入口：按设置挑后端，并把事件翻回 SpeechResult（这样会话流程不用改）。
 * 选中的后端不可用（没填地址等）时回落到系统识别，保证助手仍然能用。
 */
class AsrManager(private val context: Context) {
    private val settings = SettingsRepository.getInstance(context)
    private var cached: AsrEngine? = null

    private fun create(kind: AsrEngineKind): AsrEngine = when (kind) {
        AsrEngineKind.OPENAI_BATCH -> OpenAiBatchAsrEngine(context)
        AsrEngineKind.FUNASR_STREAM -> FunAsrStreamAsrEngine(context)
        AsrEngineKind.HMS -> HmsAsrEngine(context)
        AsrEngineKind.SYSTEM -> SystemAsrEngine(context)
    }

    private fun effective(): AsrEngine {
        val kind = AsrEngineKind.fromId(settings.asrEngine)
        val current = cached
        if (current != null && current.kind == kind) {
            if (current.kind == AsrEngineKind.SYSTEM || current.isAvailable()) return current
            Log.w(TAG, kind.id + " 未配置，回落到系统识别")
            current.release()
            cached = null
        }
        if (cached == null) cached = create(kind)
        val engine = cached!!
        if (engine.kind != AsrEngineKind.SYSTEM && !engine.isAvailable()) {
            Log.w(TAG, kind.id + " 未配置，回落到系统识别")
            engine.release()
            cached = create(AsrEngineKind.SYSTEM)
        }
        return cached!!
    }

    fun isAvailable(): Boolean = effective().isAvailable()

    fun startListening(language: String? = null, silenceTimeoutMs: Long = 2500L): Flow<SpeechResult> {
        val engine = effective()
        Log.i(TAG, "本轮识别后端=" + engine.kind.id + " language=" + language +
            " 静音超时=" + silenceTimeoutMs + "ms")
        return engine.startListening(language, silenceTimeoutMs).map { ev ->
            when (ev) {
                is AsrEvent.Ready -> SpeechResult.Ready
                is AsrEvent.Listening -> SpeechResult.Listening
                is AsrEvent.Processing -> SpeechResult.Processing
                is AsrEvent.Rms -> SpeechResult.RmsChanged(ev.db)
                is AsrEvent.Partial -> SpeechResult.PartialResult(ev.text)
                is AsrEvent.Final -> SpeechResult.Result(ev.text, 1.0f, listOf(ev.text))
                is AsrEvent.Error -> SpeechResult.Error(ev.message, ev.code)
            }
        }
    }

    /** 正常收尾：批式的意思是"停止录音并上传"，流式是发结束标志等最终结果 */
    fun stopListening() {
        cached?.stop()
    }

    /** 取消当前这轮：不上传、不出结果 */
    fun cancelListening() {
        cached?.cancel()
    }

    fun destroy() {
        cached?.release()
        cached = null
    }

    fun attachForegroundContext(c: Context) {
        (cached as? SystemAsrEngine)?.attachForegroundContext(c)
    }

    fun clearForegroundContext(c: Context? = null) {
        (cached as? SystemAsrEngine)?.clearForegroundContext(c)
    }
}
