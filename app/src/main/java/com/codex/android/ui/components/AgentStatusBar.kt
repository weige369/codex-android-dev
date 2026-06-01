package com.codex.android.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
 * Modern status bar inspired by Cursor + Warp — Phase 5 Upgrade.
 *
 * Connection states:
 *   Connected:  Solid green dot (#2ED573) + faint glow halo
 *   Connecting: Rotating CodexBrandOrange dot + pulse animation
 *   Error:      Hollow red dot (#FF4757)
 *
 * Running state: current file path (truncated to last 2 segments) + elapsed time (mm:ss)
 * Agent phase pill: small text label ("thinking" / "executing" / "done"), CodexBrandOrange 10% bg
 */
@Composable
fun AgentStatusBar(
    state: RuntimeState,
    isConnected: Boolean,
    onToggle: () -> Unit = {},
    currentFilePath: String = "",
    elapsedSeconds: Int = 0,
    agentPhase: String = "",
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAnim"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlphaAnim"
    )

    val isRunning = state == RuntimeState.RUNNING || state == RuntimeState.NATIVE_MODE
    val isConnecting = state == RuntimeState.STARTING ||
                       state == RuntimeState.DOWNLOADING ||
                       state == RuntimeState.EXTRACTING
    val hasError = state == RuntimeState.ERROR

    val connectionState = when {
        isRunning && isConnected -> ConnectionState.CONNECTED
        isConnecting -> ConnectionState.CONNECTING
        hasError -> ConnectionState.ERROR
        isRunning -> ConnectionState.CONNECTING
        else -> ConnectionState.DISCONNECTED
    }

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
            // Connection state indicator
            ConnectionStateIndicator(
                connectionState = connectionState,
                pulseScale = pulseScale,
                glowAlpha = glowAlpha
            )

            Spacer(Modifier.width(8.dp))

            // Status text
            Text(
                text = statusText(state, isConnected),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )

            // Running state: file path + elapsed time
            if (isRunning && currentFilePath.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = truncatePath(currentFilePath),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = CodexOnSurfaceVariant,
                    maxLines = 1
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatElapsed(elapsedSeconds),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = CodexBrandOrange,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.weight(1f))

            // Agent phase pill
            if (agentPhase.isNotBlank()) {
                AgentPhasePill(phase = agentPhase)
                Spacer(Modifier.width(8.dp))
            }

            // Status badge
            StatusBadge(state = state, isConnected = isConnected)
        }
    }
}

private enum class ConnectionState {
    CONNECTED, CONNECTING, ERROR, DISCONNECTED
}

@Composable
private fun ConnectionStateIndicator(
    connectionState: ConnectionState,
    pulseScale: Float,
    glowAlpha: Float
) {
    Box(contentAlignment = Alignment.Center) {
        when (connectionState) {
            ConnectionState.CONNECTED -> {
                // Faint green glow halo
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(StatusOnline.copy(alpha = 0.15f * pulseScale))
                )
                // Solid green dot (#2ED573)
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(StatusOnline)
                )
            }
            ConnectionState.CONNECTING -> {
                // Pulsing orange halo
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(CodexBrandOrange.copy(alpha = 0.15f * glowAlpha))
                )
                // Pulsing orange dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .scale(0.8f + 0.2f * glowAlpha)
                        .clip(CircleShape)
                        .background(CodexBrandOrange)
                )
            }
            ConnectionState.ERROR -> {
                // Hollow red dot (#FF4757)
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, Color(0xFFFF4757), CircleShape)
                )
            }
            ConnectionState.DISCONNECTED -> {
                // Dim gray dot
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(StatusOffline)
                )
            }
        }
    }
}

@Composable
private fun AgentPhasePill(phase: String) {
    val (text, textColor) = when (phase.lowercase()) {
        "thinking" -> "thinking" to CodexBrandOrange
        "executing" -> "executing" to CodexBrandOrange
        "done" -> "done" to StatusOnline
        else -> phase to CodexOnSurfaceVariant
    }

    Surface(
        shape = PillShape,
        color = CodexBrandOrange.copy(alpha = 0.10f)
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

@Composable
private fun StatusBadge(
    state: RuntimeState,
    isConnected: Boolean
) {
    val (text, bgColor, textColor) = when {
        state == RuntimeState.RUNNING && isConnected ->
            Triple("在线", StatusOnline.copy(alpha = 0.15f), StatusOnline)
        state == RuntimeState.RUNNING ->
            Triple("已启动", CodexBrandOrange.copy(alpha = 0.15f), CodexBrandOrange)
        state == RuntimeState.ERROR ->
            Triple("异常", StatusError.copy(alpha = 0.15f), StatusError)
        state == RuntimeState.STARTING ->
            Triple("启动中", CodexBrandOrange.copy(alpha = 0.15f), CodexBrandOrange)
        state == RuntimeState.DOWNLOADING ->
            Triple("下载中", CodexBrandOrange.copy(alpha = 0.15f), CodexBrandOrange)
        state == RuntimeState.EXTRACTING ->
            Triple("解压中", CodexBrandOrange.copy(alpha = 0.15f), CodexBrandOrange)
        state == RuntimeState.NATIVE_MODE ->
            Triple("API模式", CodexBrandOrange.copy(alpha = 0.15f), CodexBrandOrange)
        state == RuntimeState.NATIVE_MODE ->
            Triple("解压中", CodexBrandOrange.copy(alpha = 0.15f), CodexBrandOrange)
        else ->
            Triple("已停止", StatusOffline.copy(alpha = 0.15f), StatusOffline)
    }

    Surface(
        shape = PillShape,
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
        RuntimeState.STARTING -> "正在启动..."
        RuntimeState.RUNNING -> if (connected) "Codex 运行中" else "Codex 已启动"
        RuntimeState.ERROR -> "Codex 运行异常"
        RuntimeState.NATIVE_MODE -> "API 直连模式"
    }
}

private fun truncatePath(path: String): String {
    val segments = path.split("/")
    return if (segments.size <= 2) path
    else segments.takeLast(2).joinToString("/")
}

private fun formatElapsed(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}

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
            shape = CardShape,
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

@Composable
fun StreamBubble(
    content: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
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
