package com.codex.android.feature.workspace

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
 * Codex Android v2.0 — WorkspaceScreen (Dashboard).
 *
 * Layout:
 *   RuntimeBanner
 *   ProjectSection
 *   RecentFiles
 *   GitStatus
 *   RunningTasks
 *
 * Proportions: 40% of user time.
 */

@Composable
fun WorkspaceScreen() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(CxBackground),
        contentPadding = PaddingValues(CxSpaceLg),
        verticalArrangement = Arrangement.spacedBy(CxSpaceMd)
    ) {
        // ── Runtime Banner ──
        item { RuntimeBanner() }

        // ── Project Section ──
        item { SectionLabel("Project") }
        item { ProjectCard() }

        // ── Recent Files ──
        item { SectionLabel("Recent Files") }
        item { RecentFilesList() }

        // ── Git Status ──
        item { SectionLabel("Git") }
        item { GitStatusCard() }

        // ── Running Tasks ──
        item { SectionLabel("Running Tasks") }
        item { RunningTasksCard() }

        item { Spacer(Modifier.height(CxSpaceXl)) }
    }
}

// ─────────────────────────────────────────────────
// Runtime Banner
// ─────────────────────────────────────────────────

@Composable
private fun RuntimeBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CxShapeDefault,
        colors = CardDefaults.cardColors(containerColor = CxSurfaceVariant)
    ) {
        Column(modifier = Modifier.padding(CxSpaceLg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Ubuntu Runtime",
                    style = MaterialTheme.typography.titleMedium,
                    color = CxTextPrimary
                )
                Spacer(Modifier.weight(1f))
                StatusBadge("Online", CxOnline)
            }
            Spacer(Modifier.height(CxSpaceMd))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MetricChip("CPU", "12%")
                MetricChip("RAM", "2.1 GB")
                MetricChip("Storage", "35 GB free")
            }
        }
    }
}

@Composable
private fun MetricChip(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = CxTextPrimary, fontFamily = FontFamily.Monospace)
        Text(label, fontSize = 11.sp, color = CxTextTertiary)
    }
}

// ─────────────────────────────────────────────────
// Project Card
// ─────────────────────────────────────────────────

@Composable
private fun ProjectCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CxShapeDefault,
        colors = CardDefaults.cardColors(containerColor = CxSurface)
    ) {
        Column(modifier = Modifier.padding(CxSpaceLg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Folder, null, tint = CxPrimary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(CxSpaceSm))
                Text("codex-android-dev", style = MaterialTheme.typography.titleMedium, color = CxTextPrimary)
            }
            Spacer(Modifier.height(CxSpaceSm))
            Row(horizontalArrangement = Arrangement.spacedBy(CxSpaceSm)) {
                TagChip("Compose")
                TagChip("Kotlin")
                TagChip("MCP")
                TagChip("Agent")
            }
            Spacer(Modifier.height(CxSpaceSm))
            Text("Updated 3m ago", fontSize = 12.sp, color = CxTextTertiary)
        }
    }
}

@Composable
private fun TagChip(text: String) {
    Surface(shape = CxShapeSmall, color = CxPrimary.copy(alpha = 0.12f)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = CxSpaceSm, vertical = CxSpaceXs),
            fontSize = 11.sp,
            color = CxPrimary,
            fontWeight = FontWeight.Medium
        )
    }
}

// ─────────────────────────────────────────────────
// Recent Files
// ─────────────────────────────────────────────────

private val recentFiles = listOf(
    "MainActivity.kt",
    "AgentScreen.kt",
    "WorkspaceScreen.kt",
    "RuntimeManager.kt",
    "GitManager.kt"
)

@Composable
private fun RecentFilesList() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CxShapeDefault,
        colors = CardDefaults.cardColors(containerColor = CxSurface)
    ) {
        Column {
            recentFiles.forEachIndexed { index, name ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { }
                        .padding(horizontal = CxSpaceLg, vertical = CxSpaceMd),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Code, null, tint = CxTextSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(CxSpaceSm))
                    Text(name, style = CxCodeStyle, color = CxTextPrimary)
                }
                if (index < recentFiles.lastIndex) {
                    HorizontalDivider(color = CxBorder, modifier = Modifier.padding(horizontal = CxSpaceLg))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────
// Git Status
// ─────────────────────────────────────────────────

@Composable
private fun GitStatusCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CxShapeDefault,
        colors = CardDefaults.cardColors(containerColor = CxSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CxSpaceLg),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.GitBranch, null, tint = CxPrimary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(CxSpaceSm))
                Text("main", fontFamily = FontFamily.Monospace, color = CxTextPrimary, fontSize = 14.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(CxSpaceLg)) {
                GitStat("3", "M", CxWarning)
                GitStat("1", "↑", CxBlue)
            }
        }
    }
}

@Composable
private fun GitStat(value: String, label: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, color = color, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        Text(label, fontSize = 11.sp, color = CxTextTertiary)
    }
}

// ─────────────────────────────────────────────────
// Running Tasks
// ─────────────────────────────────────────────────

@Composable
private fun RunningTasksCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CxShapeDefault,
        colors = CardDefaults.cardColors(containerColor = CxSurface)
    ) {
        Column(modifier = Modifier.padding(CxSpaceLg)) {
            Text("No running tasks", style = MaterialTheme.typography.bodyMedium, color = CxTextSecondary)
        }
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
internal fun StatusBadge(label: String, color: androidx.compose.ui.graphics.Color) {
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
            Spacer(Modifier.width(CxSpaceXs))
            Text(label, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium)
        }
    }
}