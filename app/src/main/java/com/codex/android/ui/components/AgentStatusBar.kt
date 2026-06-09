package com.codex.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.service.RuntimeState
import com.codex.android.ui.theme.*

/**
 * Modern status bar inspired by Replit.
 * Shows runtime state with animated indicator and quick actions.
 */
@Composable
fun AgentStatusBar(
    state: RuntimeState,
    isConnected: Boolean,
    onToggle: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")

    val statusColor by animateColorAsState(
        targetValue = when (state) {
            RuntimeState.RUNNING -> StatusOnline
            RuntimeState.STARTING,
            RuntimeState.DOWNLOADING,
            RuntimeState.EXTRACTING,
            RuntimeState.INSTALLING -> StatusWarning
            RuntimeState.ERROR -> StatusError
            RuntimeState.STOPPED -> StatusOffline
        },
        label = "statusColorAnim"
    )

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAnim"
    )

    val shouldPulse = state == RuntimeState.RUNNING

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status dot with pulse
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .then(
                        if (shouldPulse) Modifier.scale(pulseScale) else Modifier
                    )
                    .background(statusColor)
            )
            Spacer(Modifier.width(8.dp))

            // Status text
            Text(
                text = statusText(state, isConnected),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.weight(1f))

            // Badge / indicator
            Badge(
                state = state,
                isConnected = isConnected
            )
        }
    }
}

@Composable
private fun Badge(
    state: RuntimeState,
    isConnected: Boolean
) {
    val (text, bgColor, textColor) = when {
        state == RuntimeState.RUNNING && isConnected ->
            Triple("在线", StatusOnline.copy(alpha = 0.15f), StatusOnline)
        state == RuntimeState.RUNNING ->
            Triple("已启动", StatusWarning.copy(alpha = 0.15f), StatusWarning)
        state == RuntimeState.ERROR ->
            Triple("异常", StatusError.copy(alpha = 0.15f), StatusError)
        state == RuntimeState.STARTING ->
            Triple("启动中", StatusWarning.copy(alpha = 0.15f), StatusWarning)
        state == RuntimeState.DOWNLOADING ->
            Triple("下载中", StatusWarning.copy(alpha = 0.15f), StatusWarning)
        state == RuntimeState.EXTRACTING ->
            Triple("解压中", StatusWarning.copy(alpha = 0.15f), StatusWarning)
        state == RuntimeState.INSTALLING ->
            Triple("安装中", StatusWarning.copy(alpha = 0.15f), StatusWarning)
        else ->
            Triple("已停止", StatusOffline.copy(alpha = 0.15f), StatusOffline)
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = bgColor
    ) {
        Text(
            text = " $text ",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

private fun statusText(state: RuntimeState, connected: Boolean): String {
    return when (state) {
        RuntimeState.STOPPED -> "Codex 已停止"
        RuntimeState.DOWNLOADING -> "正在下载 Codex CLI..."
        RuntimeState.EXTRACTING -> "正在解压..."
        RuntimeState.INSTALLING -> "正在安装到 rootfs..."
        RuntimeState.STARTING -> "正在启动..."
        RuntimeState.RUNNING -> if (connected) "Codex 运行中" else "Codex 已启动"
        RuntimeState.ERROR -> "Codex 运行异常"
    }
}

/**
 * Quick action button for agent/terminal actions.
 */
@Composable
fun AgentActionButton(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(36.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
        }
    }
}

/**
 * Stream message bubble for Codex streaming content.
 */
@Composable
fun StreamBubble(
    content: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Typing cursor
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(8.dp, 14.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(1.dp)
                    )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                content,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 20.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private val FastOutSlowInEasing: Easing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
