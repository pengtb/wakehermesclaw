package com.openclaw.assistant.ui.bridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.R
import com.openclaw.assistant.backend.BackendRepository
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.bridge.MobileBridgeConfig
import com.openclaw.assistant.bridge.MobileBridgeService

/**
 * Mobile Bridge on/off, on the home screen right under 连接状态.
 *
 * It lives here instead of in Settings because the switch is a *control*, not a preference: what
 * matters is whether the bridge is listening right now. Flipping it starts/stops the service
 * immediately — no Save button involved — and the switch mirrors the real bound state
 * (`serverRunning`), not the persisted `enabled` flag, so it cannot claim to be on while nothing
 * is listening.
 *
 * Port / bind mode / approval mode / token stay on the Mobile Bridge settings screen.
 */
@Composable
fun MobileBridgeSwitchCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cfg = remember { MobileBridgeConfig.getInstance(context) }
    val serverRunning by cfg.serverRunning.collectAsState()
    val actuallyRunning = serverRunning != null

    // 主后端是 OpenClaw 网关时，网关协议本身就能下发手机能力，桥接是多余的第二条入站通道
    var showGatewayRedundantHint by remember { mutableStateOf(false) }
    val primaryIsGateway = remember {
        runCatching {
            BackendRepository.getInstance(context).primary?.type == BackendType.OPENCLAW_GATEWAY
        }.getOrDefault(false)
    }

    fun turnOn() {
        cfg.setEnabled(true)
        cfg.getOrCreateToken()
        MobileBridgeService.start(context)
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.mobile_bridge_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = serverRunning
                            ?.let { stringResource(R.string.av_bridge_now_running, it.port, modeLabel(it.bindMode)) }
                            ?: stringResource(R.string.av_bridge_now_stopped),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = actuallyRunning,
                    onCheckedChange = { on ->
                        when {
                            !on -> {
                                cfg.setEnabled(false)
                                MobileBridgeService.stop(context)
                            }
                            primaryIsGateway -> showGatewayRedundantHint = true
                            else -> turnOn()
                        }
                    },
                )
            }
            Text(
                text = stringResource(R.string.bridge_http_only_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    if (showGatewayRedundantHint) {
        AlertDialog(
            onDismissRequest = { showGatewayRedundantHint = false },
            title = { Text(stringResource(R.string.bridge_gateway_redundant_title)) },
            text = { Text(stringResource(R.string.bridge_gateway_redundant_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showGatewayRedundantHint = false
                    turnOn()
                }) { Text(stringResource(R.string.bridge_gateway_redundant_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showGatewayRedundantHint = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
