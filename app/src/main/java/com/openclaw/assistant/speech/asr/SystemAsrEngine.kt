package com.openclaw.assistant.speech.asr

import android.content.Context
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.SpeechResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 默认后端：包住现有的 SpeechRecognizerManager，行为与改造前完全一致（系统识别器，边说边出）。 */
class SystemAsrEngine(private val context: Context) : AsrEngine {
    private val manager = SpeechRecognizerManager(context)

    override val kind = AsrEngineKind.SYSTEM

    override fun isAvailable(): Boolean = manager.isAvailable()

    override fun startListening(language: String?, silenceTimeoutMs: Long): Flow<AsrEvent> =
        manager.startListening(language, silenceTimeoutMs).map { r ->
            when (r) {
                is SpeechResult.Ready -> AsrEvent.Ready
                is SpeechResult.Listening -> AsrEvent.Listening
                is SpeechResult.Processing -> AsrEvent.Listening
                is SpeechResult.RmsChanged -> AsrEvent.Rms(r.rmsdB)
                is SpeechResult.PartialResult -> AsrEvent.Partial(r.text)
                is SpeechResult.Result -> AsrEvent.Final(r.text)
                is SpeechResult.Error -> AsrEvent.Error(r.message, r.code)
            }
        }

    override fun stop() = manager.stopListening()

    override fun cancel() = manager.stopListening()

    override fun release() = manager.destroy()

    fun attachForegroundContext(c: Context) = manager.attachForegroundContext(c)

    fun clearForegroundContext(c: Context? = null) = manager.clearForegroundContext(c)
}
