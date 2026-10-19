package com.openclaw.assistant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 电池优化白名单（可选加固）。
 *
 * 为什么需要：语音服务、唤醒词都靠系统绑定存活，被系统判成"后台耗电"就会被回收，
 * 之后就是我们处理过的"半死"状态（binder 还在、onReady 不来）。加入白名单能明显降低频率，
 * 但**不是必需**——没加入也能用，只是更容易被回收，所以界面上标成"（可选）"。
 */
object BatteryOptimization {

    /** 已经不受电池优化限制（或系统版本太低没有这个概念） */
    fun isWhitelisted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(true)
    }

    /**
     * 打开系统的"是否允许后台不受限制"确认框；拉不起来就退到电池优化列表页。
     * 两个都拉不起来返回 false，调用方自己决定要不要提示。
     */
    fun requestWhitelist(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
            if (start(context, direct)) return true
        }
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return start(context, list)
    }

    private fun start(context: Context, intent: Intent): Boolean {
        val resolvable = runCatching {
            context.packageManager.resolveActivity(intent, 0) != null
        }.getOrDefault(false)
        if (!resolvable) return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }
}
