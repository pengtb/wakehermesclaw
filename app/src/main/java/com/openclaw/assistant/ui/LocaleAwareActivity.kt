package com.openclaw.assistant.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import com.openclaw.assistant.MainActivity
import com.openclaw.assistant.data.SettingsRepository
import java.util.Locale

/**
 * 所有 Activity 的基类：把「设置 → 显示语言」应用到这个 Activity 自己的 Context 上。
 *
 * 为什么需要它：本 App 的 Activity 都是 `ComponentActivity`（主题是 Material3，不是 AppCompat 主题），
 * 而 `AppCompatDelegate.setApplicationLocales()` 只对 AppCompat 的 Context 生效，
 * 对 ComponentActivity 是空操作——所以以前只有手写了 `attachBaseContext` 的
 * MainActivity / SettingsActivity 跟着设置走，其他界面恒定跟系统语言，看起来就是"语言设置没生效"。
 *
 * 规则：设置里为空 = 跟随系统（原样返回 base，不做任何覆盖）；否则按 BCP-47 标签覆盖。
 *
 * 刷新：语言的改动只在界面重新创建时生效，所以设置页保存后由用户确认"立即重启"（见 SettingsActivity）。
 */
open class LocaleAwareActivity : ComponentActivity() {

    /** 本界面创建时实际生效的显示语言标签（空字符串 = 跟随系统）。 */
    var appliedLanguageTag: String = ""
        private set

    override fun attachBaseContext(newBase: Context) {
        appliedLanguageTag = savedLanguageTag(newBase)
        super.attachBaseContext(wrapWithLocale(newBase, appliedLanguageTag))
    }

    companion object {
        /** 读设置里的显示语言标签；读失败一律当"跟随系统"，不让一个异常变成崩溃或重复重建。 */
        fun savedLanguageTag(base: Context): String = runCatching {
            SettingsRepository.getInstance(base).appLanguage.trim()
        }.getOrDefault("")

        /** 按保存的语言设置包装一个 Context；设置为空时原样返回（跟随系统）。 */
        fun wrapWithSavedLocale(base: Context): Context =
            wrapWithLocale(base, savedLanguageTag(base))

        /** 纯映射：把 BCP-47 标签应用到 Context；空标签 = 跟随系统。抽出来便于单测。 */
        internal fun wrapWithLocale(base: Context, languageTag: String): Context {
            val tag = languageTag.trim()
            if (tag.isBlank()) return base
            val locale = Locale.forLanguageTag(tag)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            return base.createConfigurationContext(config)
        }

        /** 语言变了以后把应用重置到主界面，让变化立刻可见（栈里其它界面会在重新显示时自检重建）。 */
        fun restartIntoMain(context: Context) {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                )
            )
        }

        /**
         * 语言流程诊断：写 logcat（有些 ROM 会把 app 日志丢掉）**和**应用私有目录的 langdiag.log，
         * 后者可以用 `adb shell run-as <pkg> cat files/langdiag.log` 读出来。
         *
         * 存在的理由：设置界面有两个宿主（MainActivity 的"设置"标签页、SettingsActivity），
         * 只改一处就会出现"代码看起来对但界面上没反应"。日志能直接指出是哪一处被调用。
         */
        fun langDiag(context: Context, line: String) {
            android.util.Log.i("WHClawLang", line)
            runCatching {
                val f = java.io.File(context.filesDir, "langdiag.log")
                val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                    .format(java.util.Date())
                f.appendText("$stamp $line\n")
                val lines = f.readLines()
                if (lines.size > 200) f.writeText(lines.takeLast(200).joinToString("\n") + "\n")
            }
        }
    }
}
