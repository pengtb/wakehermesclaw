package com.openclaw.assistant.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme

/**
 * 单独承载首启设置向导（SetupGuideScreen），供"设置 → 连接"里的"重新运行设置向导"按钮启动。
 *
 * 主界面 MainActivity 只在"没配任何后端且未完成过向导"时展示该向导，配过之后就再也进不去；
 * 这个 Activity 让用户随时能重跑一遍（改连接方式、重扫配对码、复查权限等），
 * 完成或跳过即 finish()，返回设置页。
 */
class SetupGuideActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = SettingsRepository.getInstance(this)
        setContent {
            OpenClawAssistantTheme {
                SetupGuideScreen(
                    settings = settings,
                    onComplete = { finish() },
                )
            }
        }
    }
}
