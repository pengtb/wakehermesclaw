package com.openclaw.assistant.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.voice.VoiceInteractionService
import androidx.core.content.ContextCompat
import com.openclaw.assistant.OpenClawApplication
import com.openclaw.assistant.R
import com.openclaw.assistant.backend.AgentBackendConfig
import com.openclaw.assistant.backend.BackendRepository
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.backend.VoiceBackendSelector
import com.openclaw.assistant.backend.VoiceTargetResolver
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.speech.SystemRecognizerStatus
import com.openclaw.assistant.speech.asr.AsrEngineKind

/** 缺什么，去哪个入口补 */
enum class PreflightFix {
    MIC_PERMISSION,
    ASSISTANT_SETTINGS,
    VOICE_SETTINGS,
    BACKEND_SETTINGS,

    /** 语音服务卡在坏掉的系统绑定上：重启本应用进程（自动自愈也走这条路） */
    RESTART_APP,

    /** 电池优化白名单：只作为"（可选）"建议，不拦操作 */
    BATTERY_OPTIMIZATION,

    /** 无障碍服务：手机控制（读屏/点按）靠它，语音对话不需要 —— 同样是可选建议 */
    ACCESSIBILITY_SETTINGS
}

/** 一条"还不能开始"的原因 + 一处可点的去处 */
data class PreflightProblem(
    val message: String,
    val fix: PreflightFix,
    /** true = 建议项，只在提示里出现，不阻止操作 */
    val optional: Boolean = false
)

/**
 * 主页两个按钮（「语音对话」/「打开聊天」）点下去之前要过的检查。
 *
 * 为什么要先查：这些前提下界面表现都是"点了没反应"或"弹一句看不懂的错误码"——
 * 语音服务没被系统绑定（刚更新安装后常见）、麦克风没授权、识别后端没填地址、
 * 一个可用后端都没有。点之前把原因说出来，比让用户去猜 logcat 强。
 *
 * 注意：移动桥接（Mobile Bridge）**不在这两条链路上**——桥是 Hermes 主动调手机的
 * 反向通道（手机监听 8787 等 Hermes 来连）；语音/聊天都是手机主动往外连
 * （识别服务、Hermes API Server 或网关）。所以这里不查桥，查了只会误报。
 */
object HomePreflight {

    /** 语音对话：麦克风权限 → 语音服务绑定 → 识别后端 → 回答后端 */
    fun voiceProblems(context: Context): List<PreflightProblem> {
        val problems = mutableListOf<PreflightProblem>()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            problems += PreflightProblem(
                context.getString(R.string.preflight_mic_permission),
                PreflightFix.MIC_PERMISSION
            )
        }

        // 设置里是默认助理 ≠ 系统已经把它绑上了：包更新后这个绑定会掉，掉了 showSession() 就是空操作
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !VoiceInteractionService.isActiveService(
                context,
                ComponentName(context, com.openclaw.assistant.service.OpenClawAssistantService::class.java)
            )
        ) {
            problems += PreflightProblem(
                context.getString(R.string.preflight_voice_service),
                PreflightFix.ASSISTANT_SETTINGS
            )
        }

        problems += asrProblems(context)

        if (resolvedVoiceBackendId(context) == null) {
            problems += PreflightProblem(
                context.getString(R.string.preflight_no_backend),
                PreflightFix.BACKEND_SETTINGS
            )
        }
        return problems
    }

    /**
     * 语音链路的"（可选）"建议：电池优化白名单。
     * 不加入也能用，只是语音服务/唤醒词更容易被系统回收（回收后就是"语音服务和系统断开了"），
     * 所以只提示、不拦操作。
     */
    fun voiceOptionalTips(context: Context): List<PreflightProblem> {
        val tips = mutableListOf<PreflightProblem>()
        if (!BatteryOptimization.isWhitelisted(context)) {
            tips += PreflightProblem(
                context.getString(R.string.preflight_battery_optional),
                PreflightFix.BATTERY_OPTIMIZATION,
                optional = true
            )
        }
        // 无障碍服务：不开的话「读屏/点按/滑动」这批手机控制能力用不了，语音对话本身不受影响
        if (!runCatching { com.openclaw.assistant.bridge.accessibility.AgentVoiceAccessibilityService.isRunning() }
                .getOrDefault(false)) {
            tips += PreflightProblem(
                context.getString(R.string.preflight_accessibility_optional),
                PreflightFix.ACCESSIBILITY_SETTINGS,
                optional = true
            )
        }
        return tips
    }

    /**
     * 主后端是 Hermes（只支持 HTTP），但"语音会话连接"设成了网关 —— 这种组合下语音必然发不出去：
     * VoiceTargetResolver 会按网关给目标，Hermes 那条 HTTP 链路根本收不到请求，最后表现成
     * "网关未连接"或干脆超时。点语音前拦一下，比让用户等一轮失败再猜强。
     */
    fun hermesOnGatewayConflict(context: Context): Boolean {
        val settings = SettingsRepository.getInstance(context)
        if (settings.wakewordConnectionType != SettingsRepository.CONNECTION_TYPE_GATEWAY) return false
        val primary = runCatching {
            BackendRepository.getInstance(context).backends.value
                .firstOrNull { it.enabled && it.isPrimary }
        }.getOrNull() ?: return false
        return primary.type == BackendType.HERMES_API_SERVER
    }

    /** 打开聊天：只需要一个启用的后端（聊天不走桥，也不需要运行时权限） */
    fun chatProblems(context: Context): List<PreflightProblem> {
        val problems = mutableListOf<PreflightProblem>()
        if (enabledBackends(context).isEmpty()) {
            problems += PreflightProblem(
                context.getString(R.string.preflight_chat_no_backend),
                PreflightFix.BACKEND_SETTINGS
            )
        }
        return problems
    }

    /** 选中的识别后端到底能不能干活；不能就说明缺哪一项 */
    private fun asrProblems(context: Context): List<PreflightProblem> {
        val settings = SettingsRepository.getInstance(context)
        val problems = mutableListOf<PreflightProblem>()
        val needConfig = { nameRes: Int ->
            problems += PreflightProblem(
                context.getString(R.string.preflight_asr_not_configured, context.getString(nameRes)),
                PreflightFix.VOICE_SETTINGS
            )
        }
        when (AsrEngineKind.fromId(settings.asrEngine)) {
            AsrEngineKind.SYSTEM -> if (!SystemRecognizerStatus.isUsable(context)) {
                problems += PreflightProblem(
                    context.getString(R.string.preflight_asr_system_unusable),
                    PreflightFix.VOICE_SETTINGS
                )
            }
            AsrEngineKind.OPENAI_BATCH -> if (settings.asrOpenAiUrl.isBlank()) needConfig(R.string.asr_engine_openai)
            AsrEngineKind.FUNASR_STREAM -> if (settings.asrFunAsrUrl.isBlank()) needConfig(R.string.asr_engine_funasr)
            AsrEngineKind.HMS -> if (settings.asrHmsApiKey.isBlank()) needConfig(R.string.asr_engine_hms)
        }
        return problems
    }

    private fun enabledBackends(context: Context): List<AgentBackendConfig> = runCatching {
        BackendRepository.getInstance(context).backends.value
    }.getOrDefault(emptyList()).filter { it.enabled }

    /**
     * 主页那两个 chip 选的那一类后端，到底配了没有。
     * 只看"有没有这类后端条目"（或旧的单条配置），不判断连不连得上——连不上是另一类问题，
     * 交给 [voiceProblems] 的回复后端检查。
     */
    fun voiceTargetConfigured(context: Context): Boolean {
        val settings = SettingsRepository.getInstance(context)
        return when (settings.wakewordConnectionType) {
            SettingsRepository.CONNECTION_TYPE_GATEWAY ->
                enabledBackends(context).any { it.type == BackendType.OPENCLAW_GATEWAY } ||
                    runCatching {
                        (context.applicationContext as OpenClawApplication).nodeRuntime.manualHost.value.isNotBlank()
                    }.getOrDefault(false)

            SettingsRepository.CONNECTION_TYPE_OPENCLAW_HTTP ->
                enabledBackends(context).any { it.type == BackendType.OPENCLAW_HTTP }

            else ->
                enabledBackends(context).any { it.type == BackendType.HERMES_API_SERVER } ||
                    settings.httpUrl.isNotBlank()
        }
    }

    /** 这一轮会走哪条路、那条路上有没有可用后端（规则与 VoiceTargetResolver 完全一致） */
    private fun resolvedVoiceBackendId(context: Context): String? {
        val gatewayHealthy = runCatching {
            (context.applicationContext as OpenClawApplication).nodeRuntime.chatHealthOk.value
        }.getOrDefault(false)
        return VoiceBackendSelector.selectBackendId(
            VoiceTargetResolver.defaultTarget(context),
            enabledBackends(context),
            gatewayHealthy,
            SettingsRepository.getInstance(context).wakewordConnectionType,
        )
    }
}
