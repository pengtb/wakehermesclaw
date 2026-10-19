package com.openclaw.assistant

import android.app.Activity
import android.os.Bundle
import com.openclaw.assistant.service.VoiceRecovery

/**
 * 快捷设置磁贴点一下之后被系统拉起的"跳板"Activity。
 *
 * 它没有任何界面（manifest 里用 Theme.NoDisplay），存在的唯一理由是两个：
 * 1. 磁贴必须通过"启动 Activity"才能让系统把通知栏面板收起来，否则语音面板会被面板盖住；
 * 2. 录音/语音会话需要一个前台的应用进程，从 TileService 直接 startService 在部分 ROM 上会被挡。
 *
 * 真正的重连、失败提示都在 [VoiceRecovery.launchDetached] 里跑，所以这里可以立刻 finish。
 */
class VoiceLaunchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VoiceRecovery.launchDetached(this)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
