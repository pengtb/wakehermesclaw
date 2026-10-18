package com.openclaw.assistant.speech

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.speech.SpeechRecognizer

/**
 * 系统语音识别到底能不能用。
 *
 * 不能用 SpeechRecognizer.isRecognitionAvailable()：它只查"有没有任何包声明了识别服务"，
 * 而本应用自己就带了一个空实现的 RecognitionService，所以在装了本应用的机器上它恒为真。
 * 这里改成看 Android 实际会去用的那个选择：安全设置 voice_recognition_service
 * （授予 SYSTEM_SPEECH_RECOGNIZER 角色时系统会写到这里），并要求它指向的服务
 * 真的存在、已启用、且不是本应用自带的那份空实现。
 */
object SystemRecognizerStatus {

    /** 当前被选定的系统识别器；没有选定则为 null */
    fun selectedComponent(context: Context): ComponentName? {
        // 这个 key 在 SDK 里是隐藏常量，只有字面量可用
        val raw = Settings.Secure.getString(
            context.contentResolver,
            "voice_recognition_service"
        )
        if (raw.isNullOrBlank()) return null
        return ComponentName.unflattenFromString(raw)
    }

    /** 有没有能真正干活儿的系统识别器 */
    fun isUsable(context: Context): Boolean {
        // 连一个声明了识别服务的包都没有，那肯定没戏
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return false
        // 没有选定识别器：调用时只会拿到错误，等于不可用
        val component = selectedComponent(context) ?: return false
        // 别把本应用自带的空实现当引擎
        if (component.packageName == context.packageName) return false
        // 选定的服务得真的存在且已启用
        return runCatching {
            context.packageManager.getServiceInfo(component, 0).enabled
        }.getOrDefault(false)
    }
}
