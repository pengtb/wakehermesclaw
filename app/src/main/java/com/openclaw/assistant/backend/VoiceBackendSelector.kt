package com.openclaw.assistant.backend

import com.openclaw.assistant.data.SettingsRepository

object VoiceBackendSelector {
    /**
     * @param connectionMode 语音会话连接方式（OpenClaw 网关 / OpenClaw HTTP / Hermes HTTP）。
     *   传 null 时退回旧启发式（网关健康优先网关，否则退回 HTTP 后端）。
     */
    fun selectBackendId(
        voiceTarget: String,
        backends: List<AgentBackendConfig>,
        gatewayHealthy: Boolean,
        connectionMode: String? = null,
    ): String? {
        val enabled = backends.filter { it.enabled }
        return if (voiceTarget == SettingsRepository.VOICE_TARGET_OPENCLAW) {
            selectOpenClawBackend(enabled, gatewayHealthy, connectionMode)?.id
        } else {
            enabled.firstOrNull {
                it.isPrimary && it.type == BackendType.HERMES_API_SERVER
            }?.id
                ?: enabled.firstOrNull { it.type == BackendType.HERMES_API_SERVER }?.id
        }
    }

    private fun selectOpenClawBackend(
        enabled: List<AgentBackendConfig>,
        gatewayHealthy: Boolean,
        connectionMode: String?,
    ): AgentBackendConfig? {
        when (connectionMode) {
            // 显式选了 OpenClaw HTTP：只认 HTTP 后端。找不到就让上层报"所选后端未配置"，
            // 不要偷偷换成网关 —— 那会让用户以为自己走的是 HTTP。
            SettingsRepository.CONNECTION_TYPE_OPENCLAW_HTTP ->
                return enabled.firstOrNull { it.type == BackendType.OPENCLAW_HTTP && it.isPrimary }
                    ?: enabled.firstOrNull { it.type == BackendType.OPENCLAW_HTTP }

            SettingsRepository.CONNECTION_TYPE_GATEWAY ->
                return enabled.firstOrNull { it.type == BackendType.OPENCLAW_GATEWAY && it.isPrimary }
                    ?: enabled.firstOrNull { it.type == BackendType.OPENCLAW_GATEWAY }
        }

        val primaryOpenClaw = enabled.firstOrNull {
            it.isPrimary && (it.type == BackendType.OPENCLAW_GATEWAY || it.type == BackendType.OPENCLAW_HTTP)
        }
        if (primaryOpenClaw?.type == BackendType.OPENCLAW_HTTP) return primaryOpenClaw
        if (primaryOpenClaw?.type == BackendType.OPENCLAW_GATEWAY && gatewayHealthy) return primaryOpenClaw

        val http = enabled.firstOrNull { it.type == BackendType.OPENCLAW_HTTP }
        if (http != null) return http

        val gateway = enabled.firstOrNull { it.type == BackendType.OPENCLAW_GATEWAY }
        return if (gatewayHealthy) gateway else primaryOpenClaw ?: gateway
    }
}
