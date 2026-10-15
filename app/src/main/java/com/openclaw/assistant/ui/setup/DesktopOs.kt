package com.openclaw.assistant.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.R

/**
 * 桌面端操作系统。决定界面上展示哪一套命令：Windows 走 PowerShell 版，其余走 bash 版。
 * 两个版本都在 integrations/agentvoice-pair/ 下，行为一致（生成配对二维码 / 用配对码换 Mobile Bridge token）。
 */
enum class DesktopOs { WINDOWS, UNIX }

/**
 * 界面上展示的命令，集中在这里，避免各屏幕各写一份导致口径不一致。
 * 注意：Windows 的参数用 PowerShell 风格（-Open / -Code），bash 版是 --open / --code。
 */
object AgentVoiceCommands {
    const val RAW_BASE =
        "https://raw.githubusercontent.com/yuga-hashimoto/openclaw-assistant/main/integrations/agentvoice-pair"

    /** 安装：Windows 装 agentvoice-pair.ps1 + cmd 包装；macOS/Linux 装 bash 版 agentvoice-pair。 */
    fun install(os: DesktopOs): String = when (os) {
        DesktopOs.WINDOWS -> "iwr -useb $RAW_BASE/install.ps1 | iex"
        DesktopOs.UNIX -> "curl -fsSL $RAW_BASE/install.sh | bash"
    }

    /** 运行：生成配对二维码并打开图片。默认走深链形态——系统相机能直接扫（App 内扫码需要 Play 服务模块）。 */
    fun run(os: DesktopOs): String = when (os) {
        DesktopOs.WINDOWS -> "agentvoice-pair -DeepLinkQr -Open"
        DesktopOs.UNIX -> "agentvoice-pair --deeplink-qr --open"
    }

    /** 用手机 App 上的 6 位配对码换 Mobile Bridge token。 */
    fun pairBridge(os: DesktopOs, code: String): String = when (os) {
        DesktopOs.WINDOWS -> "agentvoice-pair -Code $code"
        DesktopOs.UNIX -> "agentvoice-pair --code $code"
    }

    /** 直接 POST /pair 的兜底写法（不装工具也能配对）。 */
    fun pairBridgeFallback(os: DesktopOs, code: String, port: Int): String = when (os) {
        DesktopOs.WINDOWS ->
            "Invoke-RestMethod -Method Post -ContentType application/json -Body '{\"code\":\"$code\"}' http://<device-ip>:$port/pair"
        DesktopOs.UNIX ->
            "curl -s -X POST -H 'Content-Type: application/json' -d '{\"code\":\"$code\"}' http://<device-ip>:$port/pair"
    }
}

/** Windows / Linux·macOS 的切换控件（不带标题，两个 chip 等宽一行放下，避免换行）。 */
@Composable
fun DesktopOsSelector(
    os: DesktopOs,
    onChange: (DesktopOs) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = os == DesktopOs.WINDOWS,
            onClick = { onChange(DesktopOs.WINDOWS) },
            label = { Text(stringResource(R.string.av_desktop_os_windows), maxLines = 1) },
            modifier = Modifier.weight(1f),
        )
        FilterChip(
            selected = os == DesktopOs.UNIX,
            onClick = { onChange(DesktopOs.UNIX) },
            label = { Text(stringResource(R.string.av_desktop_os_unix), maxLines = 1) },
            modifier = Modifier.weight(1f),
        )
    }
}
