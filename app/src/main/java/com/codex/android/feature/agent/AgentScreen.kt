package com.codex.android.feature.agent

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.core.designsystem.*

/**
 * Codex Android v2.0 — AgentScreen.
 *
 * Layout:
 *   AgentStatus bar
 *   CurrentTask progress
 *   Timeline (streaming actions)
 *   ToolCalls (expandable cards)
 *
 * Proportions: 35% of user time.
 */

@Composable
fun AgentScreen() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(CxBackground),
        contentPadding = PaddingValues(CxSpaceLg),
        verticalArrangement = Arrangement.spacedBy(CxSpaceMd)
    ) {
        // ── Agent Status ──
        item { AgentStatusBar() }

        // ── Current Task ──
        item { CurrentTaskCard() }

        // ── Timeline ──
        item { SectionLabel("Timeline") }
        items(sampleTimeline) { event -> TimelineEventCard(event) }

        item { Spacer(Modifier.height(CxSpaceXl)) }
    }
}

// ─────────────────────────────────────────────────
// Agent Status Bar
// ─────────────────────────────────────────────────

@Composable
private fun AgentStatusBar() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CxShapeDefault,
        colors = CardDefaults.cardColors(containerColor = CxSurfaceVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CxSpaceLg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Surface(
                shape = CircleShape,
                color = CxPrimary.copy(alpha = 0.15f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.SmartToy, null, tint = CxPrimary, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(CxSpaceMd))
            Column(modifier = Modifier.weight(1f)) {
                Text("Codex CLI", style = MaterialTheme.typography.titleMedium, color = CxTextPrimary)
                Text("gpt-5-codex", fontSize = 12.sp, color = CxTextSecondary)
            }
            StatusBadge("Connected", CxOnline)
        }
    }
}

// ─────────────────────────────────────────────────
// Current Task
// ─────────────────────────────────────────────────

@Composable
private fun CurrentTaskCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CxShapeDefault,
        colors = CardDefaults.cardColors(containerColor = CxSurface)
    ) {
        Column(modifier = Modifier.padding(CxSpaceLg)) {
            Text("Current Task", style = MaterialTheme.typography.labelMedium, color = CxTextTertiary)
            Spacer(Modifier.height(CxSpaceSm))
            Text("Refactor Workspace UI", style = MaterialTheme.typography.titleMedium, color = CxTextPrimary)
            Spacer(Modifier.height(CxSpaceMd))
            LinearProgressIndicator(
                progress = { 0.78f },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = CxPrimary,
                trackColor = CxBorder
            )
            Spacer(Modifier.height(CxSpaceSm))
            Text("78%", fontSize = 13.sp, color = CxPrimary, fontFamily = FontFamily.Monospace)
        }
    }
}

// ─────────────────────────────────────────────────
// Timeline
// ─────────────────────────────────────────────────

private data class TimelineEvent(
    val time: String,
    val action: String,
    val detail: String = "",
    val status: EventStatus = EventStatus.SUCCESS
)

private enum class EventStatus { SUCCESS, RUNNING, ERROR }

private val sampleTimeline = listOf(
    TimelineEvent("22:01", "Read File", "WorkspaceScreen.kt", EventStatus.SUCCESS),
    TimelineEvent("22:02", "Analyze UI", "Detected 3 components", EventStatus.SUCCESS),
    TimelineEvent("22:03", "Generate Compose", "Created 2 files", EventStatus.SUCCESS),
    TimelineEvent("22:04", "Apply Patch", "main → feature/v2", EventStatus.RUNNING),
)

@Composable
private fun TimelineEventCard(event: TimelineEvent) {
    val borderColor = when (event.status) {
        EventStatus.SUCCESS -> CxPrimary
        EventStatus.RUNNING -> CxRunning
        EventStatus.ERROR -> CxError
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CxShapeDefault,
        colors = CardDefaults.cardColors(containerColor = CxSurface)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Left border
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(IntrinsicSize.Min)
                    .background(borderColor)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(CxSpaceLg)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(event.time, fontSize = 11.sp, color = CxTextTertiary, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    EventStatusBadge(event.status)
                }
                Spacer(Modifier.height(CxSpaceXs))
                Text(event.action, fontWeight = FontWeight.SemiBold, color = CxTextPrimary, fontSize = 14.sp)
                if (event.detail.isNotBlank()) {
                    Text(event.detail, fontSize = 12.sp, color = CxTextSecondary, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun EventStatusBadge(status: EventStatus) {
    val (label, color) = when (status) {
        EventStatus.SUCCESS -> "Success" to CxSuccess
        EventStatus.RUNNING -> "Running" to CxRunning
        EventStatus.ERROR -> "Error" to CxError
    }
    Surface(shape = CxShapeSmall, color = color.copy(alpha = 0.12f)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = CxSpaceSm, vertical = CxSpaceXs),
            fontSize = 10.sp,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

// ─────────────────────────────────────────────────
// Shared
// ─────────────────────────────────────────────────

@Composable
private fun SectionLabel(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = CxTextTertiary,
        modifier = Modifier.padding(top = CxSpaceSm)
    )
}

@Composable
private fun StatusBadge(label: String, color: androidx.compose.ui.graphics.Color) {
    Surface(shape = CxShapeSmall, color = color.copy(alpha = 0.12f)) {
        Row(
            modifier = Modifier.padding(horizontal = CxSpaceSm, vertical = CxSpaceXs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium)
        }
    }
}