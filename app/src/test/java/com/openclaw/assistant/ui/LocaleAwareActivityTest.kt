package com.openclaw.assistant.ui

import android.content.Context
import android.content.Intent
import com.openclaw.assistant.MainActivity
import com.openclaw.assistant.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * 显示语言设置的验证：空 = 跟随系统；指定语言 = 覆盖，并且资源真的换成对应翻译。
 *
 * 说明：这里测的是 [LocaleAwareActivity.wrapWithLocale]（纯映射）。没走 SettingsRepository 是因为
 * 它初始化时会碰 AndroidKeyStore（Robolectric 下没有该 Provider），读取设置那一步只是取出字符串。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class LocaleAwareActivityTest {

    private val base: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `blank setting keeps the system locale`() {
        val systemLocale = base.resources.configuration.locales[0]
        val wrapped = LocaleAwareActivity.wrapWithLocale(base, "")
        assertSame("空设置应当原样返回，不做任何覆盖", base, wrapped)
        assertEquals(systemLocale, wrapped.resources.configuration.locales[0])
        // 系统默认是英文（Robolectric 默认 en-US），取到的应当是英文文案
        assertEquals("Settings", wrapped.getString(R.string.settings_title))
    }

    @Test
    fun `japanese setting overrides locale and resolves japanese strings`() {
        val wrapped = LocaleAwareActivity.wrapWithLocale(base, "ja-JP")
        assertEquals("ja", wrapped.resources.configuration.locales[0].language)
        assertEquals("設定", wrapped.getString(R.string.settings_title))
        assertEquals("表示言語", wrapped.getString(R.string.display_language_label))
    }

    @Test
    fun `simplified chinese setting resolves chinese strings`() {
        val wrapped = LocaleAwareActivity.wrapWithLocale(base, "zh-CN")
        assertEquals("zh", wrapped.resources.configuration.locales[0].language)
        assertEquals("设置", wrapped.getString(R.string.settings_title))
    }

    @Test
    fun `switching back to system default restores the default locale`() {
        val japanese = LocaleAwareActivity.wrapWithLocale(base, "ja-JP")
        assertEquals("設定", japanese.getString(R.string.settings_title))

        val backToSystem = LocaleAwareActivity.wrapWithLocale(base, "")
        assertEquals("Settings", backToSystem.getString(R.string.settings_title))
    }

    @Test
    fun `language without translation falls back to english`() {
        // values- 里没有意大利语资源，应当回落到默认（英文），而不是崩溃或显示空白
        val wrapped = LocaleAwareActivity.wrapWithLocale(base, "it-IT")
        assertEquals("it", wrapped.resources.configuration.locales[0].language)
        assertEquals("Settings", wrapped.getString(R.string.settings_title))
        assertTrue(wrapped.getString(R.string.settings_title).isNotBlank())
    }

    @Test
    fun `restartIntoMain targets MainActivity with a cleared task`() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        LocaleAwareActivity.restartIntoMain(activity)
        val intent = Shadows.shadowOf(activity).nextStartedActivity
        assertNotNull("应当发起一个 Activity 启动", intent)
        assertEquals(MainActivity::class.java.name, intent!!.component?.className)
        assertTrue("需要 NEW_TASK", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue("需要 CLEAR_TASK（清掉栈里旧语言的界面）", intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK != 0)
    }
}
