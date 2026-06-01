package com.codex.android.ui.workspace

import android.webkit.WebView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.codex.android.ui.workspace.NativeChatView
import com.codex.android.agent.NativeAgentService
import com.codex.android.agent.ProotAgentService
import com.codex.android.agent.ChatAgent
import com.codex.android.agent.AgentOrchestrator
import com.codex.android.agent.AgentConnectionState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.platform.LocalContext
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
    onToggleRuntime: () -> Unit,
    onExportFile: ((String) -> Unit)? = null,
    onOpenApiProvider: (() -> Unit)? = null
) {
    val isRunning = runtimeState == RuntimeState.RUNNING || runtimeState == RuntimeState.NATIVE_MODE
    val isStarting = runtimeState == RuntimeState.STARTING ||
                     runtimeState == RuntimeState.DOWNLOADING ||
                     runtimeState == RuntimeState.EXTRACTING
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
            onOpenAbout = onOpenAbout
        )

        // Main content area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // Native API mode: use Compose chat interface instead of WebView
            val ctx = LocalContext.current
            val nativeAgent = remember(ctx) { NativeAgentService.getInstance(ctx) }
            val prootAgent = remember(ctx) { ProotAgentService.getInstance(ctx) }
            val prootReady = remember { mutableStateOf(false) }
            
            // Check proot readiness periodically
            LaunchedEffect(Unit) {
                while (true) {
                    prootReady.value = prootAgent.isProotReady()
                    delay(3000)
                }
            }
            
            // Agent mode selection
            val selectedAgentType = remember { mutableStateOf(AgentOrchestrator.AgentType.NATIVE) }
            val chatAgent: ChatAgent = if (selectedAgentType.value != AgentOrchestrator.AgentType.NATIVE && prootReady.value) {
                prootAgent.also { it.selectAgent(selectedAgentType.value) }
            } else {
                nativeAgent
            }
            
            if (runtimeState == RuntimeState.NATIVE_MODE) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Agent type selector bar
                    AgentTypeSelector(
                        selectedType = selectedAgentType.value,
                        prootReady = prootReady.value,
                        onTypeSelected = { selectedAgentType.value = it }
                    )
                    NativeChatView(
                        agent = chatAgent,
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    )
                }
            } else {

                // Binary mode: use WebView
                if (!isRunning && !isWsConnected) {
                    StartPlaceholder(
                        runtimeState = runtimeState,
                        onToggleRuntime = onToggleRuntime
                    )
                }

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
        }

        // Bottom status bar (Phase 5: file path, elapsed, agent phase)
        AgentStatusBar(
            state = runtimeState,
            isConnected = isWsConnected,
            currentFilePath = workspacePath,
            elapsedSeconds = 0,
            agentPhase = when {
                isRunning && isWsConnected -> "executing"
                isStarting -> "thinking"
                hasError -> ""
                else -> ""
            }
        )
    }
}

/**
 * Placeholder shown when Codex is not running.
 * Redesigned: Codex brand orange + Inter 400 negative tracking + stage progress.
 */
@Composable
private fun StartPlaceholder(
    runtimeState: RuntimeState,
    onToggleRuntime: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "brandPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "brandPulseAlpha"
    )
    val glowScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "brandGlowScale"
    )
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    val currentStage = when (runtimeState) {
        RuntimeState.STOPPED -> -1
        RuntimeState.DOWNLOADING -> 0
        RuntimeState.EXTRACTING -> 1
        RuntimeState.STARTING -> 2
        RuntimeState.RUNNING -> 4
        RuntimeState.NATIVE_MODE -> 4
        RuntimeState.ERROR -> -1
    }
    val isTransitioning = currentStage in 0..3

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (runtimeState == RuntimeState.STOPPED) {
                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(animationSpec = tween(600)) + scaleIn(initialScale = 0.8f)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(contentAlignment = Alignment.Center) {
                            Surface(
                                shape = CircleShape,
                                color = CodexBrandOrange.copy(alpha = 0.08f * pulseAlpha),
                                modifier = Modifier.size((96 * glowScale).dp)
                            ) {}
                            Surface(
                                shape = CircleShape,
                                color = CodexBrandOrange.copy(alpha = 0.15f * pulseAlpha),
                                modifier = Modifier.size((88 * glowAlpha(pulseAlpha)).dp)
                            ) {}
                            Surface(
                                shape = CircleShape,
                                color = CodexBrandOrange.copy(alpha = 0.12f),
                                modifier = Modifier.size(80.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "Cx",
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CodexBrandOrange.copy(alpha = pulseAlpha)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "Codex",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Normal,
                            fontFamily = FontFamily.SansSerif,
                            letterSpacing = (-0.02).sp,
                            color = CodexBrandOrange
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "AI Agent for Android",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal,
                            color = CodexOnSurfaceVariant
                        )
                        Spacer(Modifier.height(36.dp))
                        Button(
                            onClick = onToggleRuntime,
                            shape = ButtonShape,
                            colors = ButtonDefaults.buttonColors(containerColor = CodexBrandOrange),
                            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 14.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("启动 Codex", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Powered by Cursor + Warp aesthetics",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.SansSerif,
                            color = CodexOnSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            } else if (isTransitioning) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        Surface(
                            shape = CircleShape,
                            color = CodexBrandOrange.copy(alpha = 0.08f * pulseAlpha),
                            modifier = Modifier.size((72 * glowScale).dp)
                        ) {}
                        Surface(
                            shape = CircleShape,
                            color = CodexBrandOrange.copy(alpha = 0.12f),
                            modifier = Modifier.size(60.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "Cx",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CodexBrandOrange.copy(alpha = pulseAlpha)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        "Codex",
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Normal,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = (-0.02).sp,
                        color = CodexBrandOrange
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "AI Agent for Android",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        color = CodexOnSurfaceVariant
                    )
                    Spacer(Modifier.height(32.dp))
                    AgentStageProgress(currentStage = currentStage)
                    Spacer(Modifier.height(24.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = CodexBrandOrange,
                        trackColor = CodexOutline
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = when (runtimeState) {
                            RuntimeState.DOWNLOADING -> "正在下载 Codex CLI..."
                            RuntimeState.EXTRACTING -> "正在解压 Linux 环境..."
                            RuntimeState.STARTING -> "正在启动 Agent..."
                            else -> "准备中..."
                        },
                        fontSize = 13.sp,
                        color = CodexOnSurfaceVariant
                    )
                    val logs by CodexRuntimeService.logs.collectAsState()
                    if (logs.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            shape = CardShape,
                            color = CodexTerminalBg
                        ) {
                            LazyColumn(modifier = Modifier.padding(8.dp)) {
                                items(logs.takeLast(20)) { logLine ->
                                    Text(logLine, style = TerminalTextStyle, color = Color(0xFF4AF626))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Powered by Cursor + Warp aesthetics",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.SansSerif,
                        color = CodexOnSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            } else if (runtimeState == RuntimeState.ERROR) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = StatusError,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text("启动失败", fontSize = 16.sp, color = StatusError)
                val logs by CodexRuntimeService.logs.collectAsState()
                if (logs.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        shape = CardShape,
                        color = CodexTerminalBg
                    ) {
                        LazyColumn(modifier = Modifier.padding(8.dp)) {
                            items(logs.takeLast(20)) { logLine ->
                                Text(logLine, style = TerminalTextStyle, color = Color(0xFF4AF626))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = onToggleRuntime, shape = ButtonShape) { Text("重试") }
            }
        }
    }
}

/**
 * Agent ready stage progress: Connecting -> Linux -> Codex -> Agent -> Ready
 */
@Composable
private fun AgentStageProgress(currentStage: Int) {
    val stages = listOf("Connecting", "Linux", "Codex", "Agent", "Ready")
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        stages.forEachIndexed { index, label ->
            val isCompleted = index < currentStage
            val isCurrent = index == currentStage
            Surface(
                shape = PillShape,
                color = when {
                    isCompleted -> CodexBrandOrange.copy(alpha = 0.9f)
                    isCurrent -> CodexBrandOrange.copy(alpha = 0.15f)
                    else -> CodexOutline.copy(alpha = 0.3f)
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isCompleted -> Color.White
                                    isCurrent -> CodexBrandOrange
                                    else -> CodexOnSurfaceVariant.copy(alpha = 0.4f)
                                }
                            )
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        label,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        color = when {
                            isCompleted -> Color.White
                            isCurrent -> CodexBrandOrange
                            else -> CodexOnSurfaceVariant.copy(alpha = 0.5f)
                        }
                    )
                }
            }
            if (index < stages.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(8.dp)
                        .height(1.dp)
                        .background(
                            if (index < currentStage) CodexBrandOrange.copy(alpha = 0.5f)
                            else CodexOutline.copy(alpha = 0.3f)
                        )
                )
            }
        }
    }
}

private fun glowAlpha(pulseAlpha: Float): Float = 0.92f + (pulseAlpha - 0.6f) * 0.2f

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

                loadUrl("file:///android_asset/web/codex-ui.html")

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
    onOpenAbout: (() -> Unit)?
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

/**
 * Agent 类型选择器。
 * 
 * 品牌色芯片组：API直连（内置SSE） / Codex CLI / OpenCode / OpenManus
 * Proot agent 仅在 proot 环境就绪时可选。
 */
@Composable
private fun AgentTypeSelector(
    selectedType: AgentOrchestrator.AgentType,
    prootReady: Boolean,
    onTypeSelected: (AgentOrchestrator.AgentType) -> Unit
) {
    val agentTypes = listOf(
        AgentOrchestrator.AgentType.NATIVE to "API 直连",
        AgentOrchestrator.AgentType.CODEX to "Codex CLI",
        AgentOrchestrator.AgentType.OPENCODE to "OpenCode",
        AgentOrchestrator.AgentType.OPENMANUS to "OpenManus"
    )
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CodexSurface.copy(alpha = 0.95f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        agentTypes.forEach { (type, label) ->
            val isProotAgent = type != AgentOrchestrator.AgentType.NATIVE
            val enabled = !isProotAgent || prootReady
            val selected = selectedType == type
            
            FilterChip(
                selected = selected,
                onClick = { if (enabled) onTypeSelected(type) },
                label = {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            !enabled -> CodexOnSurfaceVariant.copy(alpha = 0.3f)
                            selected -> CodexBrandOrange
                            else -> CodexOnSurfaceVariant
                        }
                    )
                },
                enabled = enabled,
                shape = RoundedCornerShape(12.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CodexBrandOrange.copy(alpha = 0.15f),
                    containerColor = CodexSurfaceVariant.copy(alpha = 0.2f),
                    disabledContainerColor = CodexSurfaceVariant.copy(alpha = 0.08f)
                ),
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = CodexOutline.copy(alpha = 0.2f),
                    selectedBorderColor = CodexBrandOrange.copy(alpha = 0.5f),
                    enabled = enabled,
                    selected = selected
                ),
                modifier = Modifier.height(28.dp)
            )
        }
        
        // Proot status indicator
        if (!prootReady) {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "proot未就绪，仅API直连可用",
                fontSize = 10.sp,
                color = CodexOnSurfaceVariant.copy(alpha = 0.4f),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
