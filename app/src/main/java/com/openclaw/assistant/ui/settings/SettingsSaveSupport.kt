package com.openclaw.assistant.ui.settings

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.openclaw.assistant.R
import com.openclaw.assistant.bridge.MobileBridgeService
import com.openclaw.assistant.ui.LocaleAwareActivity

/**
 * 设置页保存后的公共善后逻辑。
 *
 * 之所以单独放这里：设置界面有**两个宿主**——`MainActivity` 底部导航的「设置」标签页（`MainNavHost`），
 * 以及独立的 `SettingsActivity`。以前把「语言改动弹窗」「移动桥接按新配置重启」只写在其中一个宿主的
 * onSave 里，结果走另一个入口时这些逻辑压根不执行（界面上表现为"保存了但什么都没发生"）。
 * 现在两个宿主都只调这里，不会再出现只改一处的情况。
 */
object SettingsSaveSupport {

    /**
     * 保存后统一收尾：桥在运行就用新配置重启一次 + 提示。
     *
     * @param languageChanged SettingsScreen 传上来的"下拉里的语言和本屏打开时不同"
     * @return 语言是否真的变了（调用方据此决定要不要弹「是否立即重启」）
     */
    fun afterSave(activity: LocaleAwareActivity, languageChanged: Boolean): Boolean {
        val saved = SettingsRepositoryHolder.language(activity)
        val changed = languageChanged || saved != activity.appliedLanguageTag
        LocaleAwareActivity.langDiag(
            activity,
            "afterSave(${activity.javaClass.simpleName}): composeChanged=$languageChanged " +
                "saved='$saved' applied='${activity.appliedLanguageTag}' → 语言有变化=$changed"
        )

        // 桥开着就用新配置重启（bindMode/port 只在 server start() 时才读）
        val restarted = runCatching { MobileBridgeService.restartIfRunning(activity) }
            .onFailure { LocaleAwareActivity.langDiag(activity, "桥重启失败（不影响保存）：$it") }
            .getOrDefault(false)
        val messageRes = if (restarted) R.string.saved_bridge_restarted else R.string.saved
        Toast.makeText(activity, activity.getString(messageRes), Toast.LENGTH_SHORT).show()
        return changed
    }
}

/** 读一下已保存的显示语言；读失败当"跟随系统"。抽出来是为了两个宿主共用同一份判断。 */
private object SettingsRepositoryHolder {
    fun language(context: android.content.Context): String = runCatching {
        com.openclaw.assistant.data.SettingsRepository.getInstance(context).appLanguage.trim()
    }.getOrDefault("")
}

/**
 * 「本地 TTS 但系统里没有 TTS 引擎」的提示弹窗。
 *
 * 触发点：设置页保存时，TTS 提供商选了「本地」、而系统里 `android.intent.action.TTS_SERVICE`
 * 一个都没查到（`TTSEngineUtils.getAvailableEngines()` 为空）。只说明去哪装、装完回来自动可选，
 * 不做安装跳转、也不记「不再提示」——装好引擎后这条自然不会再出现。
 */
@Composable
fun TtsEngineHintDialog(visible: MutableState<Boolean>) {
    if (!visible.value) return
    val message = stringResource(R.string.tts_engine_hint_message)
    val linkColor = MaterialTheme.colorScheme.primary
    // 正文里的链接要能直接点：按 URL 正则标注成超链接（各语言文案里链接位置不同，不写死下标）
    val body = remember(message, linkColor) { linkifyUrls(message, linkColor) }
    AlertDialog(
        onDismissRequest = { visible.value = false },
        title = { Text(stringResource(R.string.tts_engine_hint_title)) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = { visible.value = false }) {
                Text(stringResource(R.string.tts_engine_hint_ok))
            }
        }
    )
}

/** 匹配正文里的 http(s) 链接；排除中英文标点和括号，避免把句末标点吞进链接。 */
private val URL_PATTERN = Regex("""https?://[^\s，。；、（）()「」"']+""")

/** 把文本里的链接标成可点超链接（点击由系统按 ACTION_VIEW 打开）。 */
private fun linkifyUrls(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    URL_PATTERN.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        val url = match.value
        withLink(
            LinkAnnotation.Url(
                url = url,
                styles = TextLinkStyles(
                    style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                )
            )
        ) {
            append(url)
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

/**
 * 「语言已改，是否立即重启」确认弹窗。两个宿主都渲染这一个，避免再出现只有一处有弹窗的情况。
 *
 * @param onRestarted 点「立即重启」后的额外动作（SettingsActivity 会顺手 finish()）
 */
@Composable
fun LanguageRestartDialog(visible: MutableState<Boolean>, onRestarted: () -> Unit = {}) {
    if (!visible.value) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(R.string.language_restart_title)) },
        text = { Text(stringResource(R.string.language_restart_message)) },
        confirmButton = {
            TextButton(onClick = {
                LocaleAwareActivity.langDiag(context, "弹窗：点了立即重启")
                visible.value = false
                LocaleAwareActivity.restartIntoMain(context)
                onRestarted()
            }) { Text(stringResource(R.string.language_restart_now)) }
        },
        dismissButton = {
            TextButton(onClick = {
                LocaleAwareActivity.langDiag(context, "弹窗：点了稍后")
                visible.value = false
                onRestarted()
            }) { Text(stringResource(R.string.language_restart_later)) }
        }
    )
}
