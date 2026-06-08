package com.codex.android.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.core.designsystem.*

/**
 * Codex Android v2.0 — TerminalScreen.
 *
 * Full-screen terminal. No cards, no shadows, no surfaces.
 * Just: root@android $ prompt + output buffer.
 *
 * Proportions: 15% of user time.
 */

@Composable
fun TerminalScreen() {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val outputLog = remember { mutableStateListOf<TerminalLine>() }

    // Auto-scroll on new output
    LaunchedEffect(outputLog.size) {
        if (outputLog.isNotEmpty()) {
            listState.animateScrollToItem(outputLog.size - 1)
        }
    }

    // Focus on mount
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CxTerminalBg)
    ) {
        // ── Output buffer (fills all space) ──
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = CxSpaceMd),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            // Banner on empty
            if (outputLog.isEmpty()) {
                item {
                    Spacer(Modifier.height(CxSpaceLg))
                    TerminalText("  Codex Terminal", color = CxPrimary)
                    TerminalText("  ─────────────────────────────", color = CxBorder)
                    TerminalText("  Type 'help' for available commands.", color = CxTextTertiary)
                    Spacer(Modifier.height(CxSpaceMd))
                }
            }

            items(outputLog) { line ->
                TerminalText(
                    text = when (line.type) {
                        LineType.INPUT -> "$ ${line.content}"
                        LineType.OUTPUT -> line.content
                        LineType.ERROR -> "[err] ${line.content}"
                    },
                    color = when (line.type) {
                        LineType.INPUT -> CxTextPrimary
                        LineType.OUTPUT -> CxTextSecondary
                        LineType.ERROR -> CxError
                    }
                )
            }
        }

        // ── Input line ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(CxSurface)
                .padding(horizontal = CxSpaceMd, vertical = CxSpaceSm)
        ) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    "root@android $ ",
                    color = CxPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    textStyle = TextStyle(
                        color = CxTextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    ),
                    cursorBrush = SolidColor(CxPrimary),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            if (input.text.isNotBlank()) {
                                processCommand(input.text, outputLog)
                                input = TextFieldValue("")
                            }
                        }
                    )
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────
// Terminal logic (mock — real impl hooks into PTY)
// ─────────────────────────────────────────────────

private enum class LineType { INPUT, OUTPUT, ERROR }

private data class TerminalLine(val content: String, val type: LineType)

private fun processCommand(cmd: String, log: MutableList<TerminalLine>) {
    log.add(TerminalLine(cmd, LineType.INPUT))

    val response = when (cmd.trim().lowercase()) {
        "help" -> buildString {
            appendLine("Available commands:")
            appendLine("  help     Show this message")
            appendLine("  clear    Clear terminal")
            appendLine("  status   Show runtime status")
            appendLine("  ls       List current directory")
            appendLine("  pwd      Print working directory")
            appendLine("  version  Show Codex version")
        }
        "clear" -> { log.clear(); return }
        "status" -> "Runtime: Online | CPU: 12% | RAM: 2.1 GB free"
        "ls" -> "WorkspaceScreen.kt  AgentScreen.kt  DevToolsScreen.kt  TerminalScreen.kt"
        "pwd" -> "/data/data/com.codex.android/files/workspace"
        "version" -> "Codex Android v2.0.0 | Codex CLI v0.28.0"
        "" -> return
        else -> "command not found: $cmd"
    }

    response.lines().forEach { line ->
        log.add(TerminalLine(line, if (line.startsWith("command not found")) LineType.ERROR else LineType.OUTPUT))
    }
}

@Composable
private fun TerminalText(text: String, color: androidx.compose.ui.graphics.Color = CxTextSecondary) {
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        modifier = Modifier.padding(vertical = 1.dp)
    )
}