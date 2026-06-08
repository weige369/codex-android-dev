package com.codex.android.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codex.android.core.designsystem.*
import com.codex.android.navigation.CxScreen

/**
 * Codex Android v2.0 — Adaptive navigation shell.
 *
 * Phone  (<600dp) : Bottom NavigationBar
 * Tablet (600-840dp) : NavigationRail (icon-only, auto-hiding labels)
 * Large  (>840dp) : Permanent NavigationDrawer sidebar
 *
 * Slot-based: content is provided via composable lambda per screen.
 */

enum class WindowSize { Compact, Medium, Expanded }

@Composable
fun rememberWindowSize(): WindowSize {
    val width = BoxWithConstraintsScopeAmbient.current?.maxWidth ?: return WindowSize.Compact
    return when {
        width < 600.dp -> WindowSize.Compact
        width < 840.dp -> WindowSize.Medium
        else -> WindowSize.Expanded
    }
}

// ── Adaptive Shell ──────────────────────────────────────────────

@Composable
fun CxAdaptiveShell(
    workspaceContent: @Composable () -> Unit,
    agentContent: @Composable () -> Unit,
    terminalContent: @Composable () -> Unit,
    devtoolsContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit
) {
    // Read window size from BoxWithConstraints ancestor
    var selected by remember { mutableStateOf(CxScreen.Workspace) }

    // Use a simple width-breakpoint detector instead of Ambient
    val windowSize = rememberWindowSize()

    when (windowSize) {
        WindowSize.Expanded -> CxSidebarLayout(selected, onSelect = { selected = it }) {
            CxPageContent(selected, workspaceContent, agentContent, terminalContent, devtoolsContent, settingsContent)
        }
        WindowSize.Medium -> CxRailLayout(selected, onSelect = { selected = it }) {
            CxPageContent(selected, workspaceContent, agentContent, terminalContent, devtoolsContent, settingsContent)
        }
        WindowSize.Compact -> CxBottomBarLayout(selected, onSelect = { selected = it }) {
            CxPageContent(selected, workspaceContent, agentContent, terminalContent, devtoolsContent, settingsContent)
        }
    }
}

@Composable
private fun CxPageContent(
    screen: CxScreen,
    workspace: @Composable () -> Unit,
    agent: @Composable () -> Unit,
    terminal: @Composable () -> Unit,
    devtools: @Composable () -> Unit,
    settings: @Composable () -> Unit
) {
    when (screen) {
        CxScreen.Workspace -> workspace()
        CxScreen.Agent -> agent()
        CxScreen.Terminal -> terminal()
        CxScreen.DevTools -> devtools()
        CxScreen.Settings -> settings()
    }
}

// ── Compact: Bottom NavigationBar ────────────────────────────────

@Composable
private fun CxBottomBarLayout(
    selected: CxScreen,
    onSelect: (CxScreen) -> Unit,
    content: @Composable () -> Unit
) {
    Scaffold(
        containerColor = CxBackground,
        bottomBar = {
            NavigationBar(
                containerColor = CxSurface,
                tonalElevation = 0.dp
            ) {
                CxScreen.entries.forEach { screen ->
                    NavigationBarItem(
                        selected = selected == screen,
                        onClick = { onSelect(screen) },
                        icon = {
                            Icon(
                                if (selected == screen) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screen.label
                            )
                        },
                        label = { Text(screen.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = CxPrimary,
                            selectedTextColor = CxPrimary,
                            unselectedIconColor = CxTextTertiary,
                            unselectedTextColor = CxTextTertiary,
                            indicatorColor = CxPrimary.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            content()
        }
    }
}

// ── Medium: NavigationRail ───────────────────────────────────────

@Composable
private fun CxRailLayout(
    selected: CxScreen,
    onSelect: (CxScreen) -> Unit,
    content: @Composable () -> Unit
) {
    Row(modifier = Modifier.fillMaxSize().background(CxBackground)) {
        NavigationRail(
            containerColor = CxSurface,
            modifier = Modifier.fillMaxHeight().width(80.dp)
        ) {
            Spacer(Modifier.weight(1f))
            CxScreen.entries.forEach { screen ->
                NavigationRailItem(
                    selected = selected == screen,
                    onClick = { onSelect(screen) },
                    icon = {
                        Icon(
                            if (selected == screen) screen.selectedIcon else screen.unselectedIcon,
                            contentDescription = screen.label
                        )
                    },
                    label = { Text(screen.label, fontSize = androidx.compose.ui.unit.sp(11)) },
                    colors = NavigationRailItemDefaults.colors(
                        selectedIconColor = CxPrimary,
                        selectedTextColor = CxPrimary,
                        unselectedIconColor = CxTextTertiary,
                        unselectedTextColor = CxTextTertiary,
                        indicatorColor = CxPrimary.copy(alpha = 0.12f)
                    )
                )
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.weight(1f))
        }
        // Vertical divider
        Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(CxBorder))
        // Content area
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

// ── Expanded: Permanent Sidebar ──────────────────────────────────

@Composable
private fun CxSidebarLayout(
    selected: CxScreen,
    onSelect: (CxScreen) -> Unit,
    content: @Composable () -> Unit
) {
    Row(modifier = Modifier.fillMaxSize().background(CxBackground)) {
        // Sidebar
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(260.dp)
                .background(CxSurface)
                .padding(CxSpaceLg)
        ) {
            // Header
            Text(
                "Codex",
                style = MaterialTheme.typography.headlineSmall,
                color = CxPrimary,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
            )
            Spacer(Modifier.height(CxSpaceSm))
            Text("Mobile AI IDE", style = MaterialTheme.typography.labelSmall, color = CxTextTertiary)
            Spacer(Modifier.height(CxSpace2xl))

            CxScreen.entries.forEach { screen ->
                val isSelected = selected == screen
                SidebarItem(
                    icon = if (isSelected) screen.selectedIcon else screen.unselectedIcon,
                    label = screen.label,
                    subtitle = screenDescription(screen),
                    selected = isSelected,
                    onClick = { onSelect(screen) }
                )
                Spacer(Modifier.height(CxSpaceXs))
            }

            Spacer(Modifier.weight(1f))
            // Footer
            Text("v2.0.0", style = MaterialTheme.typography.labelSmall, color = CxTextTertiary)
        }

        // Divider
        Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(CxBorder))

        // Content area
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
private fun SidebarItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) CxPrimary.copy(alpha = 0.12f) else CxTransparent
    val fg = if (selected) CxPrimary else CxTextSecondary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(CxRadiusMd))
            .clickable(onClick = onClick)
            .padding(CxSpaceMd),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(CxSpaceMd))
        Column {
            Text(label, fontSize = androidx.compose.ui.unit.sp(14), color = fg,
                fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal)
            Text(subtitle, fontSize = androidx.compose.ui.unit.sp(11), color = CxTextTertiary)
        }
    }
}

private fun screenDescription(screen: CxScreen): String = when (screen) {
    CxScreen.Workspace -> "Dashboard & projects"
    CxScreen.Agent -> "AI agent monitor"
    CxScreen.Terminal -> "Linux terminal"
    CxScreen.DevTools -> "Processes & MCP"
    CxScreen.Settings -> "Configuration"
}