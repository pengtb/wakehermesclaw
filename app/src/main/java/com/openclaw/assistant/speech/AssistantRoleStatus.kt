package com.openclaw.assistant.speech

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * 本应用有没有被设成"数字助理"（默认助理 / 语音交互应用）。
 * 没设的话唤醒词只会响一声，起不来语音对话 —— 所以诊断和首次引导都要检查这一项。
 */
object AssistantRoleStatus {

    /** 当前是不是把本应用当作数字助理 */
    fun isDefaultAssistant(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
                rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
            ) {
                return true
            }
        }
        // 兜底：系统把选中的助理组件名记在安全设置 assistant 里
        val current = Settings.Secure.getString(context.contentResolver, "assistant")
        return !current.isNullOrBlank() && current.startsWith(context.packageName)
    }

    /**
     * 打开系统里管"数字助理"的地方。
     *
     * 顺序是实测定的：
     * - 角色请求 `REQUEST_ROLE`：startActivity 会返回成功，但在部分 ROM（含本机 nubia/ZTE）上界面
     *   **根本不出现**（RequestRoleActivity 起完即退，前台仍停在本应用），所以不能排第一 ——
     *   排第一的表现就是"点了没反应"。它只留作最后兜底，在正常 ROM 上仍是更好的体验。
     * - `VOICE_INPUT_SETTINGS`：实测能真正打开 `Settings$ManageAssistActivity`，
     *   而"数字助理"就是在这页选的，所以放第一。
     */
    fun openAssistantSettings(context: Context) {
        val fallbacks = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:" + context.packageName))
        )
        for (intent in fallbacks) {
            if (tryStart(context, intent)) return
        }

        // 兜底：能弹的 ROM 上直接问"是否允许成为数字助理"
        if (!isDefaultAssistant(context) && startRoleRequest(context)) return

        runCatching {
            android.widget.Toast.makeText(
                context,
                context.getString(com.openclaw.assistant.R.string.state_error),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 角色请求：两种构造都试，成功（没抛异常）返回 true */
    private fun startRoleRequest(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
        val viaManager = rm?.let {
            runCatching { it.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT) }.getOrNull()
        }
        if (viaManager != null && tryStart(context, viaManager, requireResolve = false)) return true
        // 这两个常量在 SDK 里是隐藏的，只有字面量可用
        val manual = Intent("android.app.role.action.REQUEST_ROLE")
            .putExtra("android.app.role.extra.ROLE_NAME", RoleManager.ROLE_ASSISTANT)
        return tryStart(context, manual, requireResolve = false)
    }

    /** 解析得到（可选）就真发一次；发得出去算成功 */
    private fun tryStart(context: Context, intent: Intent, requireResolve: Boolean = true): Boolean {
        if (requireResolve) {
            val resolvable = runCatching {
                context.packageManager.resolveActivity(intent, 0) != null
            }.getOrDefault(false)
            if (!resolvable) return false
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /**
     * 一键去把自己设成数字助理：优先用系统角色请求（弹出的就是"是否允许成为数字助理"），
     * 拿不到角色就用系统的助理/语音输入设置页兜底。
     */
    fun requestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                return runCatching { rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT) }.getOrNull()
            }
        }
        return Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }
}
