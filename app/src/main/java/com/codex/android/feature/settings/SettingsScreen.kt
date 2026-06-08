package com.codex.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.core.designsystem.*

/**
 * Codex Android v2.0 — SettingsScreen.
 *
 * Minimal — 2% time proportion. No decoration, no cards.
 * Dense list of settings items.
 *
 * Sections: Connection, Security, About.
 */

@Composable
fun SettingsScreen(
    onOpenSkills: (() -> Unit)? = null,
    onOpenMCP: (() -> Unit)? = null,
    onOpenDiagnostics: (() -> Unit)? = null,
    onOpenAbout: (() -> Unit)? = null
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(CxBackground),
        contentPadding = PaddingValues(CxSpaceLg),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ── Connection ──
        item { SectionDivider("Connection") }
        item { SettingsRow("Mode", "Codex CLI") }
        item { SettingsRow("API Endpoint", "ws://127.0.0.1:9877") }
        item { SettingsRow("Model", "gpt-5-codex") }

        // ── Security ──
        item { SectionDivider("Security") }
        item { SettingsRow("Security Level", "Standard") }
        item { SettingsRow("Shell Access", "Disabled") }
        item { SettingsRow("File Scope", "Workspace only") }

        // ── Runtime ──
        item { SectionDivider("Runtime") }
        item { SettingsRow("Codex CLI", "v0.28.0") }
        item { SettingsRow("Ubuntu", "24.04 LTS (arm64)") }
        item { SettingsRow("Runtime State", "Online", valueColor = CxOnline) }

        // ── Navigation ──
        item { SectionDivider("Tools") }
        item { SettingsNavRow("Skills", "Manage Codex plugins", onClick = { onOpenSkills?.invoke() }) }
        item { SettingsNavRow("MCP Center", "Android system tools", onClick = { onOpenMCP?.invoke() }) }
        item { SettingsNavRow("Diagnostics", "Run system checks", onClick = { onOpenDiagnostics?.invoke() }) }

        // ── About ──
        item { SectionDivider("About") }
        item { SettingsRow("Version", "2.0.0") }
        item { SettingsRow("Build", "50") }
        item { SettingsRow("Arch", "arm64-v8a") }
        item { SettingsNavRow("View full info", "Licenses & credits", onClick = { onOpenAbout?.invoke() }) }

        item { Spacer(Modifier.height(CxSpace2xl)) }
    }
}

// ─────────────────────────────────────────────────
// Components
// ─────────────────────────────────────────────────

@Composable
private fun SectionDivider(title: String) {
    Column {
        Spacer(Modifier.height(CxSpaceLg))
        Text(
            title,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = CxTextTertiary,
            letterSpacing = 0.5.sp
        )
        Spacer(Modifier.height(CxSpaceSm))
    }
}

@Composable
private fun SettingsRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = CxTextSecondary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = CxSpaceSm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 14.sp, color = CxTextPrimary)
        Text(value, fontSize = 13.sp, color = valueColor, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SettingsNavRow(
    label: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = CxSpaceSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = CxPrimary, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = CxTextTertiary)
        }
        Icon(Icons.Default.ChevronRight, null, tint = CxTextTertiary, modifier = Modifier.size(16.dp))
    }
}