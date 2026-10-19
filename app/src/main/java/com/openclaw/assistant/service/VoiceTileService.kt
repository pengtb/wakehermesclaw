package com.openclaw.assistant.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.openclaw.assistant.R
import com.openclaw.assistant.VoiceLaunchActivity
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.speech.AssistantRoleStatus

/**
 * 状态栏快捷开关：点一下 = 打开语音对话。
 *
 * 两个坑（都是官方文档/API 里写明的）：
 * 1. `startActivityAndCollapse(Intent)` 在 Android 14（API 34）及以上**会抛**
 *    UnsupportedOperationException，只能用 PendingIntent 重载。
 * 2. 面板不收起的话，语音会话窗口会被通知栏盖住，看着像没反应。
 *
 * 所以走的是"让系统收起面板 + 拉起一个无界面 Activity"：
 * - API 33+：`Tile.setActivityLaunchForClick(PendingIntent)`，系统在点击时自己处理，
 *   `onClick()` 不会被调用；
 * - API 26–32：`onClick()` 里 `startActivityAndCollapse(Intent)`（那时还没弃用）。
 */
class VoiceTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { launchVoice() }
        } else {
            launchVoice()
        }
    }

    /** 副标题反映当前状态：开关关掉 / 没被设成数字助理时直接标成不可用，别让人点了没反应 */
    private fun refreshTile() {
        val tile = qsTile ?: return
        val switchedOn = SettingsRepository.getInstance(this).statusBarShortcut
        val isAssistant = AssistantRoleStatus.isDefaultAssistant(this)
        val usable = switchedOn && isAssistant
        tile.state = if (usable) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
        val subtitle = getString(
            when {
                !switchedOn -> R.string.qs_tile_subtitle_disabled
                !isAssistant -> R.string.qs_tile_subtitle_not_assistant
                else -> R.string.qs_tile_subtitle_ready
            }
        )
        // 线稿版图标（app 图标缩到 24dp 会糊成一团白方块，见 ic_qs_voice.xml）
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_voice)
        tile.subtitle = subtitle
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.stateDescription = subtitle
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && usable) {
            tile.setActivityLaunchForClick(launchPendingIntent())
        }
        tile.updateTile()
    }

    private fun launchPendingIntent(): PendingIntent {
        val intent = Intent(this, VoiceLaunchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    /** 只有 API < 33 会走到这里（33+ 由系统按上面的 PendingIntent 直接启动） */
    @Suppress("DEPRECATION")
    private fun launchVoice() {
        val intent = Intent(this, VoiceLaunchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startActivityAndCollapse(intent) }
            .onFailure {
                // 极端情况（例如系统版本升级到 34+ 但 ROM 没改行为）：至少把 Activity 起着
                runCatching { startActivity(intent) }
            }
    }
}
