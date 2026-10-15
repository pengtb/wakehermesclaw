package com.openclaw.assistant.bridge.pairing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.assistant.R
import com.openclaw.assistant.bridge.MobileBridgeConfig
import com.openclaw.assistant.ui.setup.AgentVoiceCommands
import com.openclaw.assistant.ui.setup.DesktopOs
import com.openclaw.assistant.ui.setup.DesktopOsSelector

/**
 * Pairing screen. Generates a short-lived offer and
 * displays both the 6-character human-typeable code (for a `hermes-pair`
 * style CLI) and the full `agentvoice://pair?...` URL (for QR scanners that
 * can read it off the screen, or to paste into a desktop pairing tool).
 */
class BridgePairingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { BridgePairingScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BridgePairingScreen() {
    val context = LocalContext.current
    val cfg = remember { MobileBridgeConfig.getInstance(context) }
    var offer by remember { mutableStateOf(BridgePairing.currentOffer()) }
    val port by cfg.port.collectAsState()
    val bridgeUrl = remember(port) { "http://<device-ip>:$port" }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.av_pair_title)) }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.av_pair_intro), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(16.dp))
            if (offer == null) {
                Button(onClick = { offer = BridgePairing.createOffer(bridgeUrl) }) {
                    Text(stringResource(R.string.av_pair_generate))
                }
            } else {
                val o = offer!!
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.av_pair_code_label), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            o.code.chunked(3).joinToString(" "),
                            style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 48.sp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.av_pair_expires_in, ((o.expiresAtMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.av_pair_or_scan), style = MaterialTheme.typography.labelLarge)
                        Text(
                            o.qrPayload,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { copy(context, o.qrPayload) }) { Text(stringResource(R.string.av_pair_copy_url)) }
                            OutlinedButton(onClick = { copy(context, o.code) }) { Text(stringResource(R.string.av_pair_copy_code)) }
                            OutlinedButton(onClick = { BridgePairing.cancel(); offer = null }) { Text(stringResource(R.string.av_pair_cancel)) }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                var desktopOs by remember { mutableStateOf(DesktopOs.UNIX) }
                DesktopOsSelector(os = desktopOs, onChange = { desktopOs = it })
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.av_pair_desktop_command_label),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    AgentVoiceCommands.pairBridge(desktopOs, o.code),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.av_pair_desktop_fallback_label),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    AgentVoiceCommands.pairBridgeFallback(desktopOs, o.code, port),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { copy(context, AgentVoiceCommands.pairBridge(desktopOs, o.code)) }) {
                    Text(stringResource(R.string.av_setup_copy_command))
                }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("agent voice pairing", text))
}
