package com.openclaw.assistant.ui.setup

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.google.mlkit.common.MlKitException
import com.openclaw.assistant.R

/**
 * App 内扫码依赖 Google Play 服务的 ML Kit 扫码模块（play-services-code-scanner，**非捆绑**版）。
 * 该模块由 Play 服务按包名按需下载：官方包 com.openclaw.assistant 早就下过，
 * 而自编译包（com.openclaw.assistant.debug）是全新包名，模块没下载过、国内网络也补装不上，
 * 于是 ModuleInstall.areModulesAvailable() 返回 false 或 startScan() 直接失败。
 *
 * 原来这两种情况都只弹一句 "QR scanning is unavailable. Please update Google Play Services."，
 * 用户无从下手。这里换成可操作的提示：用手机相机扫深链二维码。
 */
enum class ScanIssue { MODULE_MISSING, CAMERA_PERMISSION, UNKNOWN_PAYLOAD }

/** 把扫码异常映射成提示类型；返回 null 表示用户主动取消，不该打扰。 */
fun scanIssueOf(error: Throwable?): ScanIssue? {
    val code = (error as? MlKitException)?.errorCode
    return when (code) {
        MlKitException.CODE_SCANNER_CANCELLED -> null
        MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED -> ScanIssue.CAMERA_PERMISSION
        else -> ScanIssue.MODULE_MISSING
    }
}

@Composable
fun ScanHelpDialog(issue: ScanIssue, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qr_scan_help_title)) },
        text = {
            Text(
                when (issue) {
                    ScanIssue.CAMERA_PERMISSION -> stringResource(R.string.qr_scan_help_permission)
                    ScanIssue.UNKNOWN_PAYLOAD -> stringResource(R.string.qr_scan_help_unknown)
                    ScanIssue.MODULE_MISSING -> stringResource(R.string.qr_scan_help_module)
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.qr_scan_help_ok)) }
        },
    )
}
