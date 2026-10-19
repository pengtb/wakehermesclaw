package com.openclaw.assistant.ui.bridge

import com.openclaw.assistant.ui.LocaleAwareActivity
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SettingsAccessibility
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.R
import com.openclaw.assistant.bridge.BridgeActivityLog
import com.openclaw.assistant.bridge.BridgeApprovalMode
import com.openclaw.assistant.bridge.BridgeBindMode
import com.openclaw.assistant.bridge.MobileBridgeConfig

class MobileBridgeSettingsActivity : LocaleAwareActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { MobileBridgeSettingsScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileBridgeSettingsScreen(embedded: Boolean = false) {
    if (embedded) {
        MobileBridgeSettingsContent(
            modifier = Modifier.fillMaxWidth(),
            scrollable = false,
        )
        return
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.mobile_bridge_title)) }) }) { padding ->
        MobileBridgeSettingsContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            scrollable = true,
        )
    }
}

@Composable
private fun MobileBridgeSettingsContent(
    modifier: Modifier = Modifier,
    scrollable: Boolean,
) {
    val context = LocalContext.current
    val cfg = remember { MobileBridgeConfig.getInstance(context) }
    val enabled by cfg.enabled.collectAsState()
    val port by cfg.port.collectAsState()
    val bindMode by cfg.bindMode.collectAsState()
    val approvalMode by cfg.approvalMode.collectAsState()
    val serverRunning by cfg.serverRunning.collectAsState()
    val allowedGroups by cfg.allowedCapabilityGroups.collectAsState()
    LaunchedEffect(Unit) { BridgeActivityLog.initialize(context) }
    val activityEntries by BridgeActivityLog.entries.collectAsState()
    var portText by remember(port) { mutableStateOf(port.toString()) }
    var showToken by remember { mutableStateOf(false) }
    var tokenRevision by remember { mutableStateOf(0) }
    val token = remember(tokenRevision, enabled) { cfg.tokenOrNull() ?: "" }
    val a11yEnabled = com.openclaw.assistant.bridge.accessibility.AgentVoiceAccessibilityService.isRunning()
    val scrollModifier = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier

    Column(
        modifier = modifier
            .then(scrollModifier)
            .padding(if (scrollable) 16.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
            BridgeStatusCard(
                port = port,
                bindMode = bindMode,
                running = serverRunning,
                tokenPresent = token.isNotBlank(),
            )

            SettingsCard(title = stringResource(R.string.av_bridge_connection_section), icon = Icons.Default.Link) {
                Text(
                    stringResource(R.string.bridge_http_only_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = portText,
                    onValueChange = {
                        portText = it.filter(Char::isDigit)
                        portText.toIntOrNull()?.let(cfg::setPort)
                    },
                    label = { Text(stringResource(R.string.av_bridge_port)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.av_bridge_bind_mode), style = MaterialTheme.typography.labelLarge)
                FlowChipRow {
                    BridgeBindMode.values().forEach { mode ->
                        FilterChip(
                            selected = bindMode == mode,
                            onClick = { cfg.setBindMode(mode) },
                            label = { Text(modeLabel(mode)) },
                        )
                    }
                }
                if (bindMode == BridgeBindMode.LAN) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.av_bridge_lan_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                AssistChip(onClick = {}, label = { Text("http://<device-ip>:$port") })
            }

            SettingsCard(title = stringResource(R.string.av_bridge_security_section), icon = Icons.Default.Security) {
                Text(stringResource(R.string.av_bridge_approval_mode), style = MaterialTheme.typography.labelLarge)
                FlowChipRow {
                    BridgeApprovalMode.values().forEach { mode ->
                        FilterChip(
                            selected = approvalMode == mode,
                            onClick = { cfg.setApprovalMode(mode) },
                        label = { Text(approvalLabel(mode)) },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.bridge_token), style = MaterialTheme.typography.labelLarge)
                        Text(
                            when {
                                token.isBlank() -> stringResource(R.string.av_bridge_token_not_generated)
                                showToken -> token
                                else -> "•".repeat(token.length.coerceAtMost(32))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                        )
                    }
                    IconButton(onClick = { showToken = !showToken }, enabled = token.isNotBlank()) {
                        Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                    }
                    IconButton(onClick = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("bridge token", cfg.tokenOrNull() ?: ""))
                    }, enabled = token.isNotBlank()) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { cfg.rotateToken(); tokenRevision++; showToken = true }) {
                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.av_bridge_rotate_token))
                }
            }

            SettingsCard(title = stringResource(R.string.av_bridge_capabilities_section), icon = Icons.Default.Devices) {
                Text(
                    stringResource(R.string.av_bridge_capabilities_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                FlowChipRow {
                    listOf("device", "apps", "accessibility", "screen", "clipboard.read", "clipboard.write", "media", "notifications", "sms", "contacts", "calendar", "camera", "location").forEach { group ->
                        FilterChip(
                            selected = group in allowedGroups,
                            onClick = {
                                val next = if (group in allowedGroups) allowedGroups - group else allowedGroups + group
                                cfg.setAllowedCapabilityGroups(next)
                            },
                            label = { Text(group) },
            )
        }
    }

            SettingsCard(title = stringResource(R.string.av_bridge_a11y_section), icon = Icons.Default.SettingsAccessibility) {
                StatusLine(
                    label = if (a11yEnabled) stringResource(R.string.av_bridge_service_enabled) else stringResource(R.string.av_bridge_enable_a11y_settings),
                    active = a11yEnabled,
                )
                Spacer(Modifier.height(10.dp))
                Button(onClick = {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(stringResource(R.string.av_bridge_open_a11y_settings)) }
            }

            SettingsCard(title = stringResource(R.string.av_bridge_pairing_checks), icon = Icons.Default.CheckCircle) {
                Text(
                    stringResource(R.string.av_bridge_pairing_checks_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        context.startActivity(android.content.Intent(context, com.openclaw.assistant.bridge.pairing.BridgePairingActivity::class.java))
                    }) { Text(stringResource(R.string.av_bridge_pair_remote)) }
                    OutlinedButton(onClick = {
                        context.startActivity(android.content.Intent(context, com.openclaw.assistant.ui.diag.SelfCheckActivity::class.java))
                    }) { Text(stringResource(R.string.av_bridge_self_check)) }
                }
                val grants = com.openclaw.assistant.bridge.grants.BridgeGrants.snapshot()
                Spacer(Modifier.height(14.dp))
                HorizontalDivider()
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.av_bridge_active_grants), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                if (grants.isEmpty()) {
                    Text(stringResource(R.string.av_bridge_no_grants), style = MaterialTheme.typography.bodySmall)
                } else {
                    grants.forEach { grant ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(grant.capability, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { com.openclaw.assistant.bridge.grants.BridgeGrants.revoke(grant.capability) }) {
                                Text(stringResource(R.string.av_bridge_revoke))
                            }
                        }
                    }
                    OutlinedButton(onClick = { com.openclaw.assistant.bridge.grants.BridgeGrants.revokeAll() }) {
                        Text(stringResource(R.string.av_bridge_revoke_all))
                    }
                }
            }

            SettingsCard(title = stringResource(R.string.av_bridge_activity_title), icon = Icons.Default.CheckCircle) {
                Text(
                    stringResource(R.string.av_bridge_activity_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                if (activityEntries.isEmpty()) {
                    Text(stringResource(R.string.av_bridge_activity_empty), style = MaterialTheme.typography.bodySmall)
                } else {
                    activityEntries.take(8).forEach { entry ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(entry.capability, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                AssistChip(onClick = {}, label = { Text(entry.status) })
                            }
                            val detail = listOf(entry.riskLevel.takeIf { it.isNotBlank() }, entry.message).filterNotNull().joinToString(" · ")
                            if (detail.isNotBlank()) {
                                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    OutlinedButton(onClick = { BridgeActivityLog.clear(context) }) {
                        Text(stringResource(R.string.av_bridge_activity_clear))
                    }
                }
            }

            if (!com.openclaw.assistant.BuildConfig.IS_SIDELOAD) {
                Text(
                    stringResource(R.string.av_bridge_play_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun BridgeStatusCard(
    port: Int,
    bindMode: BridgeBindMode,
    running: MobileBridgeConfig.Running?,
    tokenPresent: Boolean,
) {
    // Informational only: the on/off control lives on the home screen (MobileBridgeSwitchCard).
    // Every signal here follows the *actual* bound state (`running`), not the persisted setting:
    // after an app restart the setting can still say enabled while nothing is listening.
    val actuallyRunning = running != null
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                StatusDot(active = actuallyRunning)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.mobile_bridge_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    // 两行分开写：目前在 = server 实际绑定的（改配置并重启前不变）；拟在 = 当前设置里保存的值，重启后生效
                    Text(
                        running?.let { stringResource(R.string.av_bridge_now_running, it.port, modeLabel(it.bindMode)) }
                            ?: stringResource(R.string.av_bridge_now_stopped),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.av_bridge_pending, port, modeLabel(bindMode)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(if (tokenPresent) stringResource(R.string.av_bridge_token_ready) else stringResource(R.string.av_bridge_no_token)) })
                AssistChip(onClick = {}, label = { Text(if (actuallyRunning) stringResource(R.string.av_bridge_api_active) else stringResource(R.string.av_bridge_api_stopped)) })
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowChipRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
private fun StatusLine(label: String, active: Boolean) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        StatusDot(active = active)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusDot(active: Boolean) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .then(
                Modifier
            ),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Card(
            modifier = Modifier.size(12.dp),
            shape = RoundedCornerShape(6.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            ),
        ) {}
    }
}

@Composable
internal fun modeLabel(m: BridgeBindMode) = when (m) {
    BridgeBindMode.LOCAL_ONLY -> stringResource(R.string.av_bridge_bind_local)
    BridgeBindMode.LAN -> stringResource(R.string.av_bridge_bind_lan)
}

@Composable
private fun approvalLabel(m: BridgeApprovalMode) = when (m) {
    BridgeApprovalMode.ALWAYS_CONFIRM -> stringResource(R.string.av_bridge_approval_always)
    BridgeApprovalMode.CONFIRM_MEDIUM_HIGH -> stringResource(R.string.av_bridge_approval_med_high)
    BridgeApprovalMode.TRUSTED -> stringResource(R.string.av_bridge_approval_trusted)
}
