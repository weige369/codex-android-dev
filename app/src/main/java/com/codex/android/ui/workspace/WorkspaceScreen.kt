package com.codex.android.ui.workspace

import android.webkit.WebView
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.codex.android.bridge.CodexBridge
import com.codex.android.codex.CodexManager
import com.codex.android.service.CodexRuntimeService
import com.codex.android.service.RuntimeState
import com.codex.android.ui.components.AgentStatusBar
import com.codex.android.ui.theme.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

/**
 * Codex workspace main screen.
 * Layout: TopActionBar -> WebView (fill) -> AgentStatusBar
 * This avoids WebView consuming touch events from overlay composables.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    codexManager: CodexManager,
    runtimeState: RuntimeState,
    isWsConnected: Boolean,
    workspacePath: String,
    wsPort: Int,
    codexBridge: CodexBridge?,
    onWebViewReady: ((android.webkit.WebView) -> Unit)? = null,
    onOpenSettings: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMCP: () -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenDevEnv: () -> Unit = {},
    onOpenDiagnostic: () -> Unit = {},
    onOpenFileBrowser: (() -> Unit)? = null,
    onOpenAbout: (() -> Unit)? = null,
    onOpenAIChat: (() -> Unit)? = null,
    onToggleRuntime: () -> Unit,
    onExportFile: ((String) -> Unit)? = null
) {
    val isRunning = runtimeState == RuntimeState.RUNNING
    val isStarting = runtimeState == RuntimeState.STARTING ||
                     runtimeState == RuntimeState.DOWNLOADING ||
                     runtimeState == RuntimeState.EXTRACTING ||
                     runtimeState == RuntimeState.INSTALLING
    val hasError = runtimeState == RuntimeState.ERROR

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Top action bar
        WorkspaceTopBar(
            isRunning = isRunning,
            isStarting = isStarting,
            hasError = hasError,
            runtimeState = runtimeState,
            onToggleRuntime = onToggleRuntime,
            onOpenGitHub = onOpenGitHub,
            onOpenSkills = onOpenSkills,
            onOpenMCP = onOpenMCP,
            onOpenDevEnv = onOpenDevEnv,
            onOpenDiagnostic = onOpenDiagnostic,
            onOpenSettings = onOpenSettings,
            onOpenFileBrowser = onOpenFileBrowser,
            onOpenAbout = onOpenAbout,
            onOpenAIChat = onOpenAIChat
        )

        // Main content area with WebView
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // Show startup/error placeholder when WebView not active
            if (!isRunning && !isWsConnected) {
                StartPlaceholder(
                    runtimeState = runtimeState,
                    onToggleRuntime = onToggleRuntime
                )
            }

            // WebView (always created, visibility controlled by alpha)
            WebViewContainer(
                codexBridge = codexBridge,
                wsPort = wsPort,
                isRunning = isRunning,
                onWebViewReady = onWebViewReady,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isRunning || isWsConnected) Modifier
                        else Modifier.alpha(0f)
                    )
            )
        }

        // Bottom status bar
        AgentStatusBar(
            state = runtimeState,
            isConnected = isWsConnected
        )
    }
}

/**
 * Placeholder shown when Codex is not running.
 */
@Composable
private fun StartPlaceholder(
    runtimeState: RuntimeState,
    onToggleRuntime: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (runtimeState == RuntimeState.STOPPED) {
                // Welcome / start prompt
                Surface(
                    shape = CircleShape,
                    color = CodexPrimary.copy(alpha = 0.1f),
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "Cx",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = CodexPrimary
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Codex Android",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "AI 编码代理 · 安卓原生",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = onToggleRuntime,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CodexPrimary),
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("启动 Codex", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            } else if (runtimeState == RuntimeState.DOWNLOADING) {
                CircularProgressIndicator(color = CodexPrimary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("下载 Codex CLI...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (runtimeState == RuntimeState.EXTRACTING) {
                CircularProgressIndicator(color = CodexPrimary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("解压 Codex 二进制...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (runtimeState == RuntimeState.STARTING) {
                CircularProgressIndicator(color = CodexPrimary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("正在启动 Codex...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (runtimeState == RuntimeState.INSTALLING) {
                CircularProgressIndicator(color = CodexPrimary, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text("正在安装到 rootfs...", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // 显示运行时日志（下载/启动过程中）
            if (runtimeState == RuntimeState.DOWNLOADING || 
                runtimeState == RuntimeState.EXTRACTING || 
                runtimeState == RuntimeState.STARTING ||
                runtimeState == RuntimeState.INSTALLING ||
                runtimeState == RuntimeState.ERROR) {
                val logs by CodexRuntimeService.logs.collectAsState()
                if (logs.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    // 复制按钮 — 使用 Android ClipboardManager 确保可靠性
                    val context = androidx.compose.ui.platform.LocalContext.current
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Runtime 日志", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = {
                            val fullLog = logs.joinToString("\n")
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Runtime Logs", fullLog))
                        }) {
                            Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("一键复制", fontSize = 11.sp)
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0A0A0F)
                    ) {
                        LazyColumn(modifier = Modifier.padding(8.dp)) {
                            items(logs.takeLast(30)) { logLine ->
                                Text(
                                    logLine,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF4AF626),
                                    lineHeight = 14.sp
                                )
                            }
                        }
                    }
                }
            }
            
            if (runtimeState == RuntimeState.ERROR) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = StatusError,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text("启动失败", fontSize = 16.sp, color = StatusError)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onToggleRuntime) {
                    Text("重试")
                }
            }
        }
    }
}

/**
 * WebView wrapper that handles proper initialization.
 */
@Composable
private fun WebViewContainer(
    codexBridge: CodexBridge?,
    wsPort: Int,
    isRunning: Boolean,
    onWebViewReady: ((android.webkit.WebView) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = false
                settings.mediaPlaybackRequiresUserGesture = false

                addJavascriptInterface(
                    CodexWebViewBridge(codexBridge),
                    "CodexAndroidBridge"
                )

                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        view.evaluateJavascript(
                            "window.CODEX_WS_PORT = $wsPort;" +
                            "window.WS_PORT = $wsPort;" +
                            "window.onCodexWsPortUpdate && window.onCodexWsPortUpdate($wsPort);",
                            null
                        )
                        if (isRunning) {
                            view.evaluateJavascript(
                                "window.connectWebSocket && window.connectWebSocket();",
                                null
                            )
                        }
                    }
                }

                loadUrl("file:///android_asset/web-chat/index.html")

                // Expose WebView reference for status forwarding
                onWebViewReady?.invoke(this)
            }
        },
        modifier = modifier
    )
}

/**
 * Top action bar with Replit-inspired design.
 * Uses proper touch targets (>=40dp) and background to ensure clickability.
 */
@Composable
private fun WorkspaceTopBar(
    isRunning: Boolean,
    isStarting: Boolean,
    hasError: Boolean,
    runtimeState: RuntimeState,
    onToggleRuntime: () -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenMCP: () -> Unit,
    onOpenDevEnv: () -> Unit,
    onOpenDiagnostic: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFileBrowser: (() -> Unit)?,
    onOpenAbout: (() -> Unit)?,
    onOpenAIChat: (() -> Unit)?
) {
    var showMenu by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Logo + Title
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = CodexPrimary.copy(alpha = 0.15f),
                modifier = Modifier.size(34.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "Cx",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = CodexPrimary
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Codex",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            // Status dot
            if (isRunning) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(StatusOnline)
                )
            } else if (isStarting) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(StatusWarning)
                )
            } else if (hasError) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(StatusError)
                )
            }

            Spacer(Modifier.weight(1f))

            // Action buttons (always visible, but some only active when running)
            // GitHub button
            IconButton(
                onClick = onOpenGitHub
            ) {
                Icon(
                    Icons.Outlined.Code,
                    contentDescription = "GitHub",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // AI Chat button
            if (onOpenAIChat != null) {
                IconButton(onClick = onOpenAIChat) {
                    Icon(
                        Icons.Outlined.Chat,
                        contentDescription = "AI 对话",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // MCP button
            IconButton(
                onClick = onOpenMCP
            ) {
                Icon(
                    Icons.Outlined.Memory,
                    contentDescription = "MCP",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Start/Stop button
            IconButton(
                onClick = onToggleRuntime
            ) {
                Icon(
                    if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = if (isRunning) "停止" else "启动",
                    tint = if (isRunning) StatusOnline else CodexPrimary
                )
            }

            // More menu
            Box {
                IconButton(
                    onClick = { showMenu = true }
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "更多"
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(CodexSurface)
                ) {
                    DropdownMenuItem(
                        text = { Text("文件浏览器", fontSize = 14.sp) },
                        onClick = { showMenu = false; onOpenFileBrowser?.invoke() },
                        leadingIcon = { Icon(Icons.Outlined.Folder, null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("MCP 服务器", fontSize = 14.sp) },
                        onClick = { showMenu = false; onOpenMCP() },
                        leadingIcon = { Icon(Icons.Outlined.Memory, null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Skills 插件", fontSize = 14.sp) },
                        onClick = { showMenu = false; onOpenSkills() },
                        leadingIcon = { Icon(Icons.Outlined.Extension, null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("开发环境", fontSize = 14.sp) },
                        onClick = { showMenu = false; onOpenDevEnv() },
                        leadingIcon = { Icon(Icons.Outlined.Build, null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("诊断检查", fontSize = 14.sp) },
                        onClick = { showMenu = false; onOpenDiagnostic() },
                        leadingIcon = { Icon(Icons.Outlined.BugReport, null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("设置", fontSize = 14.sp) },
                        onClick = { showMenu = false; onOpenSettings() },
                        leadingIcon = { Icon(Icons.Outlined.Settings, null, modifier = Modifier.size(18.dp)) }
                    )
                    DropdownMenuItem(
                        text = { Text("关于", fontSize = 14.sp) },
                        onClick = { showMenu = false; onOpenAbout?.invoke() },
                        leadingIcon = { Icon(Icons.Outlined.Info, null, modifier = Modifier.size(18.dp)) }
                    )
                }
            }
        }
    }
}

/**
 * WebView JavaScript bridge interface.
 */
class CodexWebViewBridge(
    private val codexBridge: CodexBridge?
) {
    companion object {
        private const val TAG = "CodexWebViewBridge"
    }

    @android.webkit.JavascriptInterface
    fun isCodexReady(): Boolean {
        return codexBridge?.connectionState?.value == CodexBridge.ConnectionState.CONNECTED
    }

    @android.webkit.JavascriptInterface
    fun postMessage(jsonMessage: String) {
        android.util.Log.d(TAG, "JS -> Bridge: $jsonMessage")
        codexBridge?.let { bridge ->
            try {
                val msg = org.json.JSONObject(jsonMessage)
                when (msg.optString("type")) {
                    "prompt" -> bridge.sendPrompt(msg.getString("data"))
                    "disconnect" -> bridge.disconnect()
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "JS message parse error", e)
            }
        }
    }
}

/**
 * Forward runtime status to WebView JS.
 */
fun forwardStatusToWebView(
    webView: WebView?,
    state: RuntimeState,
    wsPort: Int,
    isRunning: Boolean
) {
    val wv = webView ?: return
    wv.post {
        try {
            wv.evaluateJavascript(
                "window.onCodexStatusUpdate && window.onCodexStatusUpdate('${state.name}', $wsPort, $isRunning);",
                null
            )
            if (state == RuntimeState.RUNNING) {
                wv.evaluateJavascript(
                    "window.connectWebSocket && window.connectWebSocket();",
                    null
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("forwardStatusToWebView", "JS call failed", e)
        }
    }
}
