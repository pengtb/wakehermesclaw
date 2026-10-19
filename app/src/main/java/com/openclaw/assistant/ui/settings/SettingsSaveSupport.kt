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
     * 保存后统一收尾：按需重启移动桥接 + 提示。
     *
     * 重启**只在这轮保存来自"移动桥接"设置时才做**（`restartBridge = true`）：
     * 桥的 bindMode/port 之类只有 server.start() 时才读，所以在桥设置页保存需要重启；
     * 而改语言、语音、唤醒词等跟桥无关的设置时顺手重启一次，会把正在用的外部客户端踢掉，没必要。
     *
     * @param languageChanged SettingsScreen 传上来的"下拉里的语言和本屏打开时不同"
     * @param restartBridge   本轮保存是否来自移动桥接设置（SettingsScreen 按当前分类判断）
     * @return 语言是否真的变了（调用方据此决定要不要弹「是否立即重启」）
     */
    fun afterSave(
        activity: LocaleAwareActivity,
        languageChanged: Boolean,
        restartBridge: Boolean = false
    ): Boolean {
        val saved = SettingsRepositoryHolder.language(activity)
        val changed = languageChanged || saved != activity.appliedLanguageTag
        LocaleAwareActivity.langDiag(
            activity,
            "afterSave(${activity.javaClass.simpleName}): composeChanged=$languageChanged " +
                "saved='$saved' applied='${activity.appliedLanguageTag}' → 语言有变化=$changed"
        )

        // 只在"移动桥接"设置里保存时重启（bindMode/port 只在 server start() 时才读）
        val restarted = if (restartBridge) {
            runCatching { MobileBridgeService.restartIfRunning(activity) }
                .onFailure { LocaleAwareActivity.langDiag(activity, "桥重启失败（不影响保存）：$it") }
                .getOrDefault(false)
        } else {
            false
        }
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
 * 一个都没查到（`TTSEngineUtils.getAvailableEngines()` 为空）。给两条路：
 * 装 Google 官方引擎（Play 商店），或装 sherpa-onnx 的离线开源引擎。
 */
@Composable
fun TtsEngineHintDialog(visible: MutableState<Boolean>) {
    if (!visible.value) return
    val context = LocalContext.current
    val message = stringResource(R.string.tts_engine_hint_message)
    val ossUrl = stringResource(R.string.tts_engine_hint_oss_url)
    val linkColor = MaterialTheme.colorScheme.primary
    val body = remember(message, linkColor) { linkifyUrls(message, linkColor) }

    fun open(url: String) {
        val ok = runCatching {
            context.startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrDefault(false)
        if (ok) visible.value = false
    }

    AlertDialog(
        onDismissRequest = { visible.value = false },
        title = { Text(stringResource(R.string.tts_engine_hint_title)) },
        text = { Text(body) },
        confirmButton = {
            // 优先推荐 Google 官方引擎（中文语音包在引擎设置里下载）
            TextButton(onClick = {
                val market = "market://details?id=${com.openclaw.assistant.speech.TTSEngineUtils.GOOGLE_TTS_PACKAGE}"
                val web = "https://play.google.com/store/apps/details?id=${com.openclaw.assistant.speech.TTSEngineUtils.GOOGLE_TTS_PACKAGE}"
                // 没装商店时会抛 ActivityNotFoundException，这里先试 market，失败再走网页
                val opened = runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(market))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    true
                }.getOrDefault(false)
                if (opened) visible.value = false else open(web)
            }) {
                Text(stringResource(R.string.tts_engine_hint_google))
            }
        },
        dismissButton = {
            TextButton(onClick = { open(ossUrl) }) {
                Text(stringResource(R.string.tts_engine_hint_oss))
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
