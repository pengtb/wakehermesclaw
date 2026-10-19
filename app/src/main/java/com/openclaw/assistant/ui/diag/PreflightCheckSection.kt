package com.openclaw.assistant.ui.diag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openclaw.assistant.R
import com.openclaw.assistant.ui.HomePreflight
import com.openclaw.assistant.ui.PreflightFix
import com.openclaw.assistant.ui.PreflightProblem

/**
 * 诊断页里的"语音与聊天前置检查"。
 *
 * 和主页点「语音对话」「打开聊天」时是同一套检查（[HomePreflight]），只是这里把所有条目
 * 一次列全、带「去处理」，用来提前发现问题，而不是点了没反应之后再猜。
 * 每次回到本页（ON_RESUME）会重新跑一遍——从系统设置里授权回来能立刻看到状态变化。
 */
@Composable
fun PreflightCheckSection(
    onFix: (PreflightFix) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }

    val problems = remember(tick) {
        HomePreflight.voiceProblems(context) + HomePreflight.voiceOptionalTips(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (problems.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.preflight_all_pass),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        } else {
            problems.forEach { problem ->
                ProblemRow(problem = problem, onFix = onFix)
            }
        }

        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { tick++ }) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.preflight_refresh), fontSize = 13.sp)
        }
    }
}

@Composable
private fun ProblemRow(problem: PreflightProblem, onFix: (PreflightFix) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Icon(
            imageVector = if (problem.optional) Icons.Default.Info else Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = if (problem.optional) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp).size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (problem.optional) {
                    stringResource(R.string.preflight_optional_tag) + " " + problem.message
                } else {
                    problem.message
                },
                fontSize = 14.sp,
                color = if (problem.optional) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            )
        }
        TextButton(onClick = { onFix(problem.fix) }) {
            Text(stringResource(R.string.preflight_action_fix), fontSize = 13.sp)
        }
    }
}
