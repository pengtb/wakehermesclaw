package com.openclaw.assistant.service

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.openclaw.assistant.R

/**
 * "把这个开关加到状态栏快捷设置"的引导。
 *
 * `StatusBarManager.requestAddTileService()`（API 33+）会弹系统自己的确认框；如果磁贴
 * 已经在面板里，系统不会弹东西、直接回调 `TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED`，
 * 所以调用方不用自己判断"加没加过"。
 *
 * 前置条件：应用必须在前台（否则返回 TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND），
 * 所以只能在界面里调用，不能从后台服务里调。
 */
object QuickTilePrompt {

    private const val TAG = "QuickTilePrompt"

    /**
     * 在用户拨开唤醒词开关（助手开始常驻）时调用：没加过磁贴就弹"添加到快捷设置"。
     * 返回 true 表示请求已经发出（不代表用户同意）。
     */
    fun offerAddTile(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val statusBar = activity.getSystemService(StatusBarManager::class.java) ?: return false
        val component = ComponentName(activity, VoiceTileService::class.java)
        val icon = Icon.createWithResource(activity, R.drawable.ic_qs_voice)
        val label = activity.getString(R.string.qs_tile_label)
        return runCatching {
            statusBar.requestAddTileService(component, label, icon, activity.mainExecutor) { result ->
                Log.i(TAG, "requestAddTileService result=$result")
                if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
                    // 磁贴早就在列表里了，只是可能排到了后面几页——不说清楚就像"没加上"
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.qs_tile_already_added_hint),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            true
        }.getOrElse {
            Log.w(TAG, "requestAddTileService 失败: ${it.message}")
            false
        }
    }
}
