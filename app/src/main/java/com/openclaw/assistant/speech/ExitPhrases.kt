package com.openclaw.assistant.speech

import android.content.Context
import com.openclaw.assistant.data.SettingsRepository

/**
 * 「退出词」：用户在语音对话里说的话如果就是配置的退出词（"退出" / "exit" 之类），
 * 直接结束当前这轮语音对话，不再送给后端 —— 免得为了关个窗口还得去戳按钮。
 *
 * 匹配口径（故意保守，宁可漏一次也不能误杀正常句子）：
 *  - 归一化：小写、去掉空白与常见标点、去掉句尾语气词（吧 / 了 / 啊 / 呀 / 呗）；
 *  - 归一化后**整句等于**某个退出词才算命中。
 *    所以"退出这个应用怎么弄"不会被当成退出，而"退出。"、"退出吧" 会。
 */
object ExitPhrases {

    /** 逗号 / 中文逗号 / 换行都能当分隔符 */
    private val SEPARATORS = Regex("[,，、\\n]")
    private val PUNCTUATION = Regex("[\\s,.!?;:，。！？；：、…~～\"'“”‘’（）()]")
    private val TRAILING_PARTICLES = listOf("吧", "了", "啊", "呀", "呗", "呢")

    fun normalize(raw: String): String {
        var text = PUNCTUATION.replace(raw.lowercase(), "")
        do {
            val before = text
            for (p in TRAILING_PARTICLES) {
                if (text.length > 1 && text.endsWith(p)) text = text.dropLast(p.length)
            }
        } while (text != before)
        return text
    }

    fun parse(raw: String): List<String> = raw.split(SEPARATORS)
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    fun matches(text: String, phrases: List<String>): Boolean {
        val target = normalize(text)
        if (target.isEmpty()) return false
        return phrases.any { it.isNotBlank() && normalize(it) == target }
    }

    fun isExit(context: Context, text: String): Boolean {
        val phrases = runCatching {
            SettingsRepository.getInstance(context).exitPhrases
        }.getOrDefault(emptyList())
        return matches(text, phrases)
    }
}
