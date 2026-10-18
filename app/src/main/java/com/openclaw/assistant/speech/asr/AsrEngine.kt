package com.openclaw.assistant.speech.asr

import kotlinx.coroutines.flow.Flow

/** OpenAI 兼容识别服务的默认模型名（PC 上 funasr 的 serve 要求显式指定，发 whisper-1 会 400） */
const val DEFAULT_OPENAI_ASR_MODEL = "sensevoice"

/** 默认识别语言（SenseVoice 需要 zh 或 auto，落成 en 会把中文识别成乱码） */
const val DEFAULT_ASR_LANGUAGE = "zh"

/** 三种后端统一成这一套事件 */
sealed interface AsrEvent {
    object Ready : AsrEvent
    object Listening : AsrEvent
    /** 已经录完、正在上传/等服务端出结果（界面用它显示"处理中"，别干等） */
    object Processing : AsrEvent
    data class Rms(val db: Float) : AsrEvent
    data class Partial(val text: String) : AsrEvent
    data class Final(val text: String) : AsrEvent
    data class Error(val message: String, val code: Int? = null) : AsrEvent
}

/** 后端种类，与设置里的下拉一一对应 */
enum class AsrEngineKind(val id: String) {
    SYSTEM("system"),
    OPENAI_BATCH("openai_batch"),
    FUNASR_STREAM("funasr_stream"),
    HMS("hms");

    companion object {
        fun fromId(id: String?): AsrEngineKind =
            entries.firstOrNull { it.id == (id ?: "").trim().lowercase() } ?: SYSTEM
    }
}

/**
 * 语音识别后端。范式照 Skythinker616/gpt-assistant-android 的 AsrClientBase：
 * 统一的 start/stop/cancel + 事件流，具体协议各自实现
 * （系统识别器 / OpenAI 兼容批式上传 / FunASR WebSocket 流式）。
 */
interface AsrEngine {
    val kind: AsrEngineKind

    /** 当前配置/环境下能不能用（不能用时上层回落到系统识别） */
    fun isAvailable(): Boolean

    /** 开始一轮识别；事件流关闭即本轮结束 */
    fun startListening(language: String?, silenceTimeoutMs: Long): Flow<AsrEvent>

    /** 正常收尾：批式=停止录音并上传，流式=发结束标志等最终结果 */
    fun stop()

    /** 取消当前这轮（丢弃，不上传不出结果） */
    fun cancel()

    /** 释放资源 */
    fun release()
}
