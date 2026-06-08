package com.codex.android.ui

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.codex.android.core.designsystem.*
import com.codex.android.feature.agent.AgentScreen
import com.codex.android.feature.devtools.DevToolsScreen
import com.codex.android.feature.terminal.TerminalScreen
import com.codex.android.feature.settings.SettingsScreen
import com.codex.android.feature.workspace.WorkspaceScreen
import com.codex.android.navigation.CxScreen

/**
 * Codex Android v2.0 — Main Scaffold with 5-tab bottom navigation.
 *
 * Tabs: Workspace / Agent / Terminal / DevTools / Settings
 *
 * Uses Crossfade for page transitions (no slide, no decoration).
 */
@Composable
fun CxMainScreen() {
    var selected by remember { mutableStateOf(CxScreen.Workspace) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = CxBackground,
        bottomBar = {
            NavigationBar(
                containerColor = CxSurface,
                contentColor = CxTextPrimary,
                tonalElevation = 0.dp
            ) {
                CxScreen.entries.forEach { screen ->
                    NavigationBarItem(
                        selected = selected == screen,
                        onClick = { selected = screen },
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
            Crossfade(
                targetState = selected,
                animationSpec = tween(200),
                label = "screen_crossfade"
            ) { screen ->
                when (screen) {
                    CxScreen.Workspace -> WorkspaceScreen()
                    CxScreen.Agent -> AgentScreen()
                    CxScreen.Terminal -> TerminalScreen()
                    CxScreen.DevTools -> DevToolsScreen()
                    CxScreen.Settings -> SettingsScreen()
                }
            }
        }
    }
}