package com.openclaw.assistant.backend

import android.content.Context
import com.openclaw.assistant.OpenClawApplication
import com.openclaw.assistant.data.SettingsRepository

/**
 * 决定"这次语音对话走哪条路"。
 *
 * 为什么需要它：唤醒词有两个（OpenClaw 词 → 网关，Hermes 词 → HTTP），目标由**念的那个词**决定
 * （`HotwordService` 会带 `EXTRA_VOICE_TARGET`）。主页那个"语音对话"按钮没有唤醒词可依据，
 * 如果不显式给目标，就会落到 `wakewordConnectionType` 的默认值（网关），
 * 于是"本机明明只配了 Hermes 后端"却报"网关未连接"。
 *
 * 规则（够用且可解释）：
 *   1. 两边只有一个可用后端 → 用可用的那个；
 *   2. 两边都可用（或都不可用）→ 尊重设置里的"唤醒词连接方式"。
 */
object VoiceTargetResolver {

    fun defaultTarget(context: Context): String {
        val settings = SettingsRepository.getInstance(context)
        val backends: List<AgentBackendConfig> = runCatching {
            BackendRepository.getInstance(context).backends.value
        }.getOrDefault(emptyList())
        val gatewayHealthy = runCatching {
            (context.applicationContext as OpenClawApplication).nodeRuntime.chatHealthOk.value
        }.getOrDefault(false)

        val hermesOk = VoiceBackendSelector
            .selectBackendId(SettingsRepository.VOICE_TARGET_HERMES, backends, gatewayHealthy) != null
        val openClawOk = VoiceBackendSelector
            .selectBackendId(SettingsRepository.VOICE_TARGET_OPENCLAW, backends, gatewayHealthy) != null

        return when {
            hermesOk && !openClawOk -> SettingsRepository.VOICE_TARGET_HERMES
            openClawOk && !hermesOk -> SettingsRepository.VOICE_TARGET_OPENCLAW
            else -> if (settings.wakewordConnectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY) {
                SettingsRepository.VOICE_TARGET_OPENCLAW
            } else {
                SettingsRepository.VOICE_TARGET_HERMES
            }
        }
    }
}
