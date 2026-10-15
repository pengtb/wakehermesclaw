package com.openclaw.assistant.ui.settings

import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
