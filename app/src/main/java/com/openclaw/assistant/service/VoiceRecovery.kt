package com.openclaw.assistant.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.Toast
import com.openclaw.assistant.R
import com.openclaw.assistant.backend.VoiceTargetResolver
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.speech.AssistantRoleStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 点「语音对话」时的一键自愈。
 *
 * 背景：装完新包 / 进程被系统回收之后，系统侧可能停在"绑定标记还在、连接已经死了"
 * 的半死状态（`isActiveService()` 仍为 true、`mBound=true`，但 `onReady` 永远不来），
 * 以前的表现就是点下去 3 秒后弹"语音服务和系统断开了，去重启手机"。
 *
 * 这里把"重启手机/强制停止再打开"自动化成三步：
 * 1. 确认本应用还是系统里的数字助理——不是的话不折腾（否则发 ACTION_ASSIST 会把手机
 *    自带的助手叫出来），直接让调用方引导用户去设置。
 * 2. 重新 startService 发一次 ACTION_SHOW_ASSISTANT，等 onReady（服务内部也会敲一次系统）。
 * 3. 还没好就显式发 ACTION_ASSIST 让系统重新 bind 一次，再等一轮。
 * 4. 仍然不行 = 服务实例本身挂在一个已经死掉、又摘不掉的系统绑定上（实测重设数字助理无用，
 *    只有"杀进程再打开"能救）——那就自己重启进程：落一个 1.5s 后的 Alarm PendingIntent
 *    （由系统代发，绕过后台启动限制）把助理服务重新拉起，同时结束本进程。
 */
object VoiceRecovery {

    private const val TAG = "VoiceRecovery"
    private const val FIRST_WAIT_MS = 8_000L
    private const val SECOND_WAIT_MS = 6_000L

    /** 自愈重启的冷却时间：别在真的坏了的时候反复杀进程 */
    private const val RESTART_COOLDOWN_MS = 60_000L
    private const val RESTART_DELAY_MS = 1_500L
    private const val SELF_KILL_DELAY_MS = 900L

    enum class Outcome {
        /** 系统已绑上，会话会自动弹出（showSession 由服务排队补发） */
        READY,

        /** 本应用不是系统里的数字助理，重试没意义，需要用户去设置里选一次 */
        NOT_ASSISTANT,

        /** 已经在重启进程了（界面马上会消失，随后语音会话由新进程拉起） */
        RESTARTING,

        /** 重启也失败 / 60 秒内已经重启过一次，交给界面提示 */
        FAILED
    }

    suspend fun ensureReady(context: Context, voiceTarget: String? = null): Outcome {
        if (!AssistantRoleStatus.isDefaultAssistant(context)) {
            Log.w(TAG, "本应用不是数字助理，跳过重试，交给用户去设置")
            return Outcome.NOT_ASSISTANT
        }

        if (startAssistant(context, voiceTarget) && awaitReady(FIRST_WAIT_MS)) {
            Log.i(TAG, "第一次 startService 后 onReady 到达")
            return Outcome.READY
        }

        if (OpenClawAssistantService.ready.value) return Outcome.READY

        Log.w(TAG, "第一次没等到 onReady，发 ACTION_ASSIST 让系统重新 bind")
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "ACTION_ASSIST 失败: ${it.message}") }

        if (awaitReady(SECOND_WAIT_MS)) {
            Log.i(TAG, "重新 bind 后 onReady 到达")
            return Outcome.READY
        }

        Log.w(TAG, "两轮重连都没成功：进程里的服务实例还挂在死掉的系统绑定上，重启进程")
        if (restartProcess(context, voiceTarget)) return Outcome.RESTARTING

        Log.e(TAG, "冷却期内不能再重启，交给界面提示")
        return Outcome.FAILED
    }

    /**
     * 结束本进程，并让系统在 1.5 秒后替我们把助理服务重新拉起（相当于手动"杀后台再打开"）。
     * 返回 false 表示冷却中，不能再来一次。
     */
    fun restartProcess(context: Context, voiceTarget: String? = null): Boolean {
        val settings = SettingsRepository.getInstance(context)
        val now = System.currentTimeMillis()
        if (now - settings.voiceRestartAt < RESTART_COOLDOWN_MS) {
            Log.w(TAG, "距离上次自愈重启不足 ${RESTART_COOLDOWN_MS / 1000}s，跳过")
            return false
        }
        settings.voiceRestartAt = now

        val intent = Intent(context, OpenClawAssistantService::class.java)
            .setAction(OpenClawAssistantService.ACTION_SHOW_ASSISTANT)
        voiceTarget?.takeIf { it.isNotBlank() }?.let {
            intent.putExtra(OpenClawAssistantService.EXTRA_VOICE_TARGET, it)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getService(context, 1, intent, flags)
        val alarm = context.getSystemService(AlarmManager::class.java)
        if (alarm != null) {
            // setExact 要 SCHEDULE_EXACT_ALARM 权限，这里用普通 set：由系统代发的 PendingIntent
            // 不受后台启动限制，而且通常一秒内就到。
            runCatching {
                alarm.set(AlarmManager.RTC, now + RESTART_DELAY_MS, pending)
            }.onFailure { Log.e(TAG, "排重启闹钟失败: ${it.message}") }
        }

        // 让界面有机会把提示画出来再死
        Handler(Looper.getMainLooper()).postDelayed({
            Log.e(TAG, "结束进程以重新建立语音服务绑定")
            Process.killProcess(Process.myPid())
        }, SELF_KILL_DELAY_MS)
        return true
    }

    private fun startAssistant(context: Context, voiceTarget: String?): Boolean {
        val intent = Intent(context, OpenClawAssistantService::class.java)
            .setAction(OpenClawAssistantService.ACTION_SHOW_ASSISTANT)
        voiceTarget?.takeIf { it.isNotBlank() }?.let {
            intent.putExtra(OpenClawAssistantService.EXTRA_VOICE_TARGET, it)
        }
        return runCatching { context.startService(intent) }.isSuccess
    }

    private suspend fun awaitReady(timeoutMs: Long): Boolean {
        if (OpenClawAssistantService.ready.value) return true
        val seen = withTimeoutOrNull(timeoutMs) {
            OpenClawAssistantService.ready.first { it }
        }
        return seen == true
    }

    /**
     * 发后不管版本：给"没有界面等你结果"的入口用（快捷设置磁贴的跳板 Activity）。
     * 自己在应用级 scope 里跑完那一套重连，失败就 Toast 说明原因。
     */
    private val detachedScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    fun launchDetached(context: Context) {
        val app = context.applicationContext
        detachedScope.launch {
            val target = runCatching { VoiceTargetResolver.defaultTarget(app) }.getOrNull()
            val outcome = ensureReady(app, target)
            val messageRes = when (outcome) {
                Outcome.READY -> null
                Outcome.NOT_ASSISTANT -> R.string.preflight_not_assistant
                Outcome.RESTARTING -> R.string.voice_restarting
                Outcome.FAILED -> R.string.preflight_voice_not_bound
            }
            if (messageRes != null) {
                Toast.makeText(app, app.getString(messageRes), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** SDK < Q 上还有旧的 isActiveService 语义，这里只做一个可读的判断给调用方用 */
    fun isBound(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return OpenClawAssistantService.ready.value
        val comp = android.content.ComponentName(
            context, OpenClawAssistantService::class.java
        )
        return android.service.voice.VoiceInteractionService.isActiveService(context, comp)
    }
}
