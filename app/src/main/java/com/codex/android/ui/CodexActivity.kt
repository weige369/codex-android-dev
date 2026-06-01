package com.codex.android.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.codex.android.bridge.CodexBridge
import com.codex.android.codex.CodexManager
import com.codex.android.service.CodexNotifications
import com.codex.android.service.CodexRuntimeService
import com.codex.android.service.RuntimeState
import com.codex.android.security.ShellConfirmationManager
import com.codex.android.ui.about.AboutScreen
import com.codex.android.ui.diagnostics.DiagnosticsScreen
import com.codex.android.ui.environment.DevEnvironmentScreen
import com.codex.android.ui.files.FileBrowserScreen
import com.codex.android.ui.github.GitHubImportScreen
import com.codex.android.ui.mcp.CodexMCPScreen
import com.codex.android.ui.settings.CodexSettingsScreen
import com.codex.android.ui.settings.ApiProviderScreen
import com.codex.android.ui.skills.CodexSkillsScreen
import com.codex.android.ui.theme.CodexTheme
import com.codex.android.ui.theme.CodexPrimary
import com.codex.android.ui.theme.CodexBackground
import com.codex.android.ui.theme.CodexOnSurface
import com.codex.android.ui.workspace.WorkspaceScreen
import com.codex.android.ui.workspace.forwardStatusToWebView
import com.codex.android.ui.setup.SetupWizardScreen
import com.codex.android.data.preferences.SetupPreferences
import com.codex.android.ui.github.GitHubRepoScreen
import com.codex.android.ui.github.GitHubPRScreen
import com.codex.android.ui.github.GitHubIssueScreen
import com.codex.android.data.preferences.GitHubAuthPreferences
import com.codex.android.data.preferences.GitHubUser
import com.codex.android.util.AndroidShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CodexActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CodexActivity"
    }

    val codexManager by lazy { CodexManager(this) }
    val codexBridge by lazy { CodexBridge("ws://127.0.0.1:${CodexRuntimeService.DEFAULT_WS_PORT}") }

    // Compose reactive state
    private val _currentScreen = mutableStateOf<Screen>(Screen.Workspace)
    private val _runtimeStateFlow = mutableStateOf(RuntimeState.STOPPED)
    private val _wsPortFlow = mutableIntStateOf(CodexRuntimeService.DEFAULT_WS_PORT)
    private val _isRunningFlow = mutableStateOf(false)
    private val _wsConnectedFlow = mutableStateOf(false)

    // WebView reference
    private var _webView: WebView? = null

    // GitHub repo state (for navigation to repo detail)
    private var _currentGitHubRepo = mutableStateOf<Pair<String, String>?>(null)
    private var _currentGitHubRepoForPRs = mutableStateOf<Pair<String, String>?>(null)
    private var _currentGitHubRepoForIssues = mutableStateOf<Pair<String, String>?>(null)

    sealed class Screen {
        data object Workspace : Screen()
        data object Settings : Screen()
        data object Skills : Screen()
        data object MCP : Screen()
        data object GitHubImport : Screen()
        data object FileBrowser : Screen()
        data object DevEnvironment : Screen()
        data object Diagnostic : Screen()
        data object About : Screen()
        data object ApiProvider : Screen()
        data object SetupWizard : Screen()
        data class GitHubRepo(val repoFullName: String, val localPath: String) : Screen()
        data object GitHubPRList : Screen()
        data object GitHubIssueList : Screen()
    }



    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) Log.i(TAG, "通知权限已授予")
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getStringExtra("state") ?: return
            val port = intent.getIntExtra("wsPort", CodexRuntimeService.DEFAULT_WS_PORT)
            val running = intent.getBooleanExtra("isRunning", false)

            val runtimeState = try {
                RuntimeState.valueOf(state)
            } catch (e: Exception) {
                RuntimeState.ERROR
            }
            Log.i(TAG, "Codex 状态: $state (端口: $port, 运行: $running)")

            // Forward state to WebView
            forwardStatusToWebView(_webView, runtimeState, port, running)

            // Connect bridge when running
            if (runtimeState == RuntimeState.RUNNING) {
                if (codexBridge.connectionState.value != CodexBridge.ConnectionState.CONNECTED) {
                    codexBridge.connect()
                }
            }

            // Update Compose state
            _runtimeStateFlow.value = runtimeState
            _wsPortFlow.intValue = port
            _isRunningFlow.value = running
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize shell executor
        AndroidShellExecutor.init(this)

        // Create notification channel
        CodexNotifications.createChannels(this)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        // Register runtime status broadcast receiver
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_EXPORTED else 0
        registerReceiver(statusReceiver, IntentFilter("com.codex.android.CODEX_STATUS"), receiverFlags)

        // Observe runtime state from service
        lifecycleScope.launch {
            CodexRuntimeService.state.collect { state ->
                _runtimeStateFlow.value = state
            }
        }

        handleSendIntent(intent)

        setContent {
            CodexTheme {
                val setupPrefs = remember { SetupPreferences.getInstance(this@CodexActivity) }
                var isSetupComplete by remember { mutableStateOf<Boolean?>(null) }

                LaunchedEffect(Unit) {
                    isSetupComplete = setupPrefs.isSetupCompleted()
                }

                isSetupComplete?.let { completed ->
                    if (!completed) {
                        SetupWizardScreen(
                            onComplete = {
                                lifecycleScope.launch {
                                    setupPrefs.markSetupCompleted()
                                    isSetupComplete = true
                                }
                            },
                            onSkip = {
                                lifecycleScope.launch {
                                    setupPrefs.markSetupCompleted()
                                    isSetupComplete = true
                                }
                            }
                        )
                        return@CodexTheme
                    }
                }

                if (isSetupComplete == null) return@CodexTheme

                val screen by _currentScreen
                val runtimeState by _runtimeStateFlow
                val wsPort by _wsPortFlow
                val isRunning by _isRunningFlow
                val wsConnected by _wsConnectedFlow

                CodexMainLayout(
                    currentScreen = screen,
                    onNavigate = { navigateTo(it) },
                    content = {
                        AnimatedContent(
                            targetState = screen,
                            transitionSpec = {
                                fadeIn(animationSpec = tween(220)) + slideInHorizontally(
                                    animationSpec = tween(220),
                                    initialOffsetX = { it / 6 }
                                ) togetherWith fadeOut(animationSpec = tween(150)) + slideOutHorizontally(
                                    animationSpec = tween(150),
                                    targetOffsetX = { -it / 6 }
                                )
                            },
                            label = "screenTransition"
                        ) { currentScreen ->
                            when (currentScreen) {
                                Screen.Workspace -> WorkspaceScreen(
                                    codexManager = codexManager,
                                    runtimeState = runtimeState,
                                    isWsConnected = wsConnected,
                                    workspacePath = codexManager.workspaceDir.absolutePath,
                                    wsPort = wsPort,
                                    codexBridge = codexBridge,
                                    onWebViewReady = { webView -> _webView = webView },
                                    onOpenSettings = { navigateTo(Screen.Settings) },
                                    onOpenSkills = { navigateTo(Screen.Skills) },
                                    onOpenMCP = { navigateTo(Screen.MCP) },
                                    onOpenGitHub = { navigateTo(Screen.GitHubImport) },
                                    onOpenDevEnv = { navigateTo(Screen.DevEnvironment) },
                                    onOpenDiagnostic = { navigateTo(Screen.Diagnostic) },
                                    onOpenFileBrowser = { navigateTo(Screen.FileBrowser) },
                                    onOpenAbout = { navigateTo(Screen.About) },
                                    onOpenApiProvider = { navigateTo(Screen.ApiProvider) },
                                    onToggleRuntime = {
                                        if (isRunning) {
                                            CodexRuntimeService.stop(this@CodexActivity)
                                        } else {
                                            CodexRuntimeService.start(this@CodexActivity)
                                        }
                                    },
                                    onExportFile = { path ->
                                        exportWorkspaceToDownloads(path)
                                    }
                                )
                                Screen.FileBrowser -> FileBrowserScreen(
                                    workspacePath = codexManager.workspaceDir.absolutePath,
                                    onBack = { navigateTo(Screen.Workspace) }
                                )
                                Screen.DevEnvironment -> DevEnvironmentScreen(
                                    onBack = { navigateTo(Screen.Workspace) }
                                )
                                Screen.Settings -> CodexSettingsScreen(
                                    onBack = { navigateTo(Screen.Workspace) },
                                    onOpenSkills = { navigateTo(Screen.Skills) },
                                    onOpenMCP = { navigateTo(Screen.MCP) },
                                    onOpenGitHub = { navigateTo(Screen.GitHubImport) },
                                    onOpenDiagnostic = { navigateTo(Screen.Diagnostic) },
                                    onOpenAbout = { navigateTo(Screen.About) },
                                    onOpenApiProvider = { navigateTo(Screen.ApiProvider) }
                                )
                                Screen.Skills -> CodexSkillsScreen(
                                    onBack = { navigateTo(Screen.Settings) }
                                )
                                Screen.MCP -> CodexMCPScreen(
                                    onBack = { navigateTo(Screen.Settings) }
                                )
                                Screen.GitHubImport -> GitHubImportScreen(
                                    workspaceDir = codexManager.workspaceDir.absolutePath,
                                    onBack = { navigateTo(Screen.Workspace) },
                                    onManageRepo = { fullName, localPath ->
                                        _currentGitHubRepo.value = Pair(fullName, localPath)
                                        navigateTo(Screen.GitHubRepo(fullName, localPath))
                                    }
                                )
                                Screen.Diagnostic -> DiagnosticsScreen(
                                    onBack = { navigateTo(Screen.Settings) }
                                )
                                Screen.About -> AboutScreen(
                                    onBack = { navigateTo(Screen.Settings) }
                                )
                                Screen.ApiProvider -> ApiProviderScreen(
                                    onBack = { navigateTo(Screen.Settings) }
                                )
                                Screen.SetupWizard -> SetupWizardScreen(
                                    onComplete = {
                                        lifecycleScope.launch {
                                            SetupPreferences.getInstance(this@CodexActivity).markSetupCompleted()
                                            navigateTo(Screen.Workspace)
                                        }
                                    },
                                    onSkip = {
                                        lifecycleScope.launch {
                                            SetupPreferences.getInstance(this@CodexActivity).markSetupCompleted()
                                            navigateTo(Screen.Workspace)
                                        }
                                    }
                                )
                                is Screen.GitHubRepo -> {
                                    val repoScreen = currentScreen as Screen.GitHubRepo
                                    GitHubRepoScreen(
                                        repoFullName = repoScreen.repoFullName,
                                        repoLocalPath = repoScreen.localPath,
                                        onBack = { navigateTo(Screen.GitHubImport) },
                                        onOpenPRs = {
                                            _currentGitHubRepoForPRs.value = Pair(repoScreen.repoFullName, repoScreen.localPath)
                                            navigateTo(Screen.GitHubPRList)
                                        },
                                        onOpenIssues = {
                                            _currentGitHubRepoForIssues.value = Pair(repoScreen.repoFullName, repoScreen.localPath)
                                            navigateTo(Screen.GitHubIssueList)
                                        }
                                    )
                                }
                                Screen.GitHubPRList -> {
                                    val repoInfo = _currentGitHubRepoForPRs.value
                                    if (repoInfo != null) {
                                        GitHubPRScreen(
                                            repoFullName = repoInfo.first,
                                            onBack = {
                                                _currentGitHubRepo.value?.let { (name, path) ->
                                                    navigateTo(Screen.GitHubRepo(name, path))
                                                } ?: navigateTo(Screen.GitHubImport)
                                            }
                                        )
                                    } else {
                                        GitHubPRScreen(
                                            repoFullName = "",
                                            onBack = { navigateTo(Screen.GitHubImport) }
                                        )
                                    }
                                }
                                Screen.GitHubIssueList -> {
                                    val repoInfo = _currentGitHubRepoForIssues.value
                                    if (repoInfo != null) {
                                        GitHubIssueScreen(
                                            repoFullName = repoInfo.first,
                                            onBack = {
                                                _currentGitHubRepo.value?.let { (name, path) ->
                                                    navigateTo(Screen.GitHubRepo(name, path))
                                                } ?: navigateTo(Screen.GitHubImport)
                                            }
                                        )
                                    } else {
                                        GitHubIssueScreen(
                                            repoFullName = "",
                                            onBack = { navigateTo(Screen.GitHubImport) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                )

                // 危险 Shell 命令确认弹窗
                val pendingConfirmation by ShellConfirmationManager.pending.collectAsState()
                pendingConfirmation?.let { confirmation ->
                    AlertDialog(
                        onDismissRequest = { ShellConfirmationManager.deny() },
                        icon = {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFE65100)
                            )
                        },
                        title = {
                            Text("确认执行危险命令", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "AI 请求执行可能造成破坏的命令：${confirmation.reason}。",
                                    fontSize = 14.sp
                                )
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        confirmation.command,
                                        modifier = Modifier.padding(10.dp),
                                        fontSize = 13.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                                Text(
                                    "仅在你确认安全时再执行。",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { ShellConfirmationManager.approve() }) {
                                Text("仍然执行", color = Color(0xFFC62828))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { ShellConfirmationManager.deny() }) {
                                Text("取消")
                            }
                        }
                    )
                }
            }
        }
    }

    private fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    fun setWebViewRef(webView: WebView?) {
        _webView = webView
    }

    private fun exportWorkspaceToDownloads(workspacePath: String) {
        try {
            val sourceDir = java.io.File(workspacePath)
            if (!sourceDir.exists()) {
                android.widget.Toast.makeText(this, "工作区为空，无文件可导出", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            val exportDir = java.io.File(downloadsDir, "CodexWorkspace")
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    sourceDir.copyRecursively(exportDir, overwrite = true)
                    launch(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            this@CodexActivity,
                            "已导出到: ${exportDir.absolutePath}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        Log.i(TAG, "工作区已导出到: ${exportDir.absolutePath}")
                    }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) {
                        android.widget.Toast.makeText(this@CodexActivity, "导出失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "导出工作区失败", e)
            android.widget.Toast.makeText(this, "导出失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle OAuth callback first
        handleOAuthCallback(intent)
        handleSendIntent(intent)
    }

    /**
     * Handle GitHub OAuth PKCE callback: codex://github-oauth-callback?code=xxx&state=xxx
     *
     * PKCE flow: exchange authorization code + code_verifier for access token,
     * then fetch user info and save auth state.
     */
    private fun handleOAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (!GitHubAuthPreferences.isOAuthRedirectUri(uri)) return

        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")
        val error = uri.getQueryParameter("error")

        if (error != null) {
            val errorDesc = uri.getQueryParameter("error_description") ?: error
            Log.e(TAG, "OAuth error: $error - $errorDesc")
            android.widget.Toast.makeText(this, "GitHub 登录失败: $errorDesc", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        if (code == null || state == null) {
            Log.e(TAG, "OAuth callback missing code or state")
            android.widget.Toast.makeText(this, "GitHub 登录失败: 参数缺失", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        Log.i(TAG, "OAuth callback received, exchanging code for token...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val authPrefs = GitHubAuthPreferences.getInstance(this@CodexActivity)
                val result = authPrefs.exchangeCodeForToken(code, state)

                when (result) {
                    is GitHubAuthPreferences.ExchangeResult.Success -> {
                        // Save token FIRST so GitHubApiClient can read it from DataStore
                        authPrefs.updateAccessToken(
                            accessToken = result.accessToken,
                            tokenType = result.tokenType,
                            expiresIn = result.expiresIn,
                            grantedScope = result.grantedScope
                        )

                        // Now fetch user info using the saved token
                        val apiClient = com.codex.android.codex.github.GitHubApiClient(this@CodexActivity)
                        val userResult = apiClient.getCurrentUser()

                        if (userResult.isSuccess) {
                            val ghUser = userResult.getOrThrow()
                            val gitHubUser = GitHubUser(
                                id = 0L,
                                login = ghUser.login,
                                name = ghUser.name,
                                email = null,
                                avatarUrl = ghUser.avatarUrl,
                                bio = ghUser.bio,
                                publicRepos = ghUser.publicRepos,
                                followers = ghUser.followers,
                                following = ghUser.following
                            )
                            authPrefs.updateUserInfo(gitHubUser)
                            authPrefs.saveAuthInfo(
                                accessToken = result.accessToken,
                                tokenType = result.tokenType,
                                expiresIn = result.expiresIn,
                                refreshToken = result.refreshToken,
                                userInfo = gitHubUser,
                                grantedScope = result.grantedScope
                            )
                            Log.i(TAG, "GitHub login success: ${ghUser.login}")
                            runOnUiThread {
                                android.widget.Toast.makeText(this@CodexActivity, "GitHub 登录成功: ${ghUser.login}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Log.e(TAG, "Failed to fetch GitHub user info after token exchange")
                            runOnUiThread {
                                android.widget.Toast.makeText(this@CodexActivity, "GitHub 登录成功但获取用户信息失败", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    is GitHubAuthPreferences.ExchangeResult.Error -> {
                        Log.e(TAG, "Token exchange failed: ${result.message}")
                        runOnUiThread {
                            android.widget.Toast.makeText(this@CodexActivity, "GitHub 登录失败: ${result.message}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "OAuth callback handling error", e)
                runOnUiThread {
                    android.widget.Toast.makeText(this@CodexActivity, "GitHub 登录异常: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    private fun handleSendIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
            Log.i(TAG, "收到分享文本: $sharedSubject - ${sharedText.take(100)}")

            _webView?.post {
                val jsCode = "window.onSharedText && window.onSharedText(${org.json.JSONObject.quote(sharedText)}, ${org.json.JSONObject.quote(sharedSubject)});"
                _webView?.evaluateJavascript(jsCode, null)
            }

            android.widget.Toast.makeText(this, "文本已分享到 Codex", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        codexBridge.destroy()
    }
}

// =============================================================================
// Side Drawer Navigation Layout (Operit-inspired)
// =============================================================================

/** Simplified drawer navigation item */
data class DrawerNavItem(
    val title: String,
    val icon: ImageVector,
    val screen: CodexActivity.Screen,
)

/** Main drawer navigation items — simplified per spec */
private val drawerNavItems = listOf(
    DrawerNavItem("终端", Icons.Outlined.Terminal, CodexActivity.Screen.Workspace),
    DrawerNavItem("文件", Icons.Outlined.Folder, CodexActivity.Screen.FileBrowser),
    DrawerNavItem("环境", Icons.Outlined.Build, CodexActivity.Screen.DevEnvironment),
    DrawerNavItem("设置", Icons.Outlined.Settings, CodexActivity.Screen.Settings),
)

/** Get the title for current screen to display in top bar */
private fun screenTitle(screen: CodexActivity.Screen): String = when (screen) {
    CodexActivity.Screen.Workspace -> "工作区"
    CodexActivity.Screen.FileBrowser -> "文件管理"
    CodexActivity.Screen.DevEnvironment -> "开发环境"
    CodexActivity.Screen.MCP -> "MCP 服务器"
    CodexActivity.Screen.Skills -> "Skills"
    CodexActivity.Screen.GitHubImport -> "GitHub"
    CodexActivity.Screen.Diagnostic -> "诊断"
    CodexActivity.Screen.Settings -> "设置"
    CodexActivity.Screen.About -> "关于"
    CodexActivity.Screen.ApiProvider -> "API 配置"
    CodexActivity.Screen.SetupWizard -> "设置向导"
    CodexActivity.Screen.GitHubPRList -> "Pull Requests"
    CodexActivity.Screen.GitHubIssueList -> "Issues"
    is CodexActivity.Screen.GitHubRepo -> "仓库详情"
}

// Codex brand colors for drawer
private val DrawerBackground = CodexBackground       // #1A1818
private val DrawerAccent = CodexPrimary              // #F54E00
private val DrawerText = Color(0xFFE8E6E3)           // onSurface text
private val DrawerTextVariant = Color(0xFF9E9890)    // secondary text

/**
 * Main layout with side drawer navigation — Operit-inspired elastic animation.
 *
 * - Drawer slides in from left with scale+alpha spring animation
 * - Main content shifts right + shrinks + rotates with rounded corners + shadow
 * - Top bar with hamburger menu + page title
 * - Simplified: no waterGlass/liquidGlass, direct CodexSurface color
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexMainLayout(
    currentScreen: CodexActivity.Screen,
    onNavigate: (CodexActivity.Screen) -> Unit,
    content: @Composable () -> Unit,
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Animated fraction: 0 = closed, 1 = open
    val transition = updateTransition(targetState = drawerState.isOpen, label = "drawerTransition")
    val drawerFraction by transition.animateFloat(
        transitionSpec = {
            if (targetState) {
                spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)
            } else {
                spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
            }
        },
        label = "drawerFraction"
    ) { isOpen -> if (isOpen) 1f else 0f }

    // Determine which nav item should be highlighted
    val selectedScreen = when (currentScreen) {
        CodexActivity.Screen.Workspace -> CodexActivity.Screen.Workspace
        CodexActivity.Screen.FileBrowser -> CodexActivity.Screen.FileBrowser
        CodexActivity.Screen.DevEnvironment -> CodexActivity.Screen.DevEnvironment
        CodexActivity.Screen.Settings, CodexActivity.Screen.ApiProvider -> CodexActivity.Screen.Settings
        CodexActivity.Screen.About -> CodexActivity.Screen.About
        else -> currentScreen
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                drawerFraction = drawerFraction,
                selectedScreen = selectedScreen,
                onNavigate = { screen ->
                    onNavigate(screen)
                    scope.launch { drawerState.close() }
                }
            )
        },
        gesturesEnabled = true,
    ) {
        // Main content with elastic transform when drawer opens
        val density = LocalDensity.current
        val yOffsetPx = with(density) { 12.dp.toPx() }
        val shadowElevationPx = with(density) { 24.dp.toPx() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Shift right by 82% of drawer width when open
                    val shiftRatio = 0.82f
                    translationX = size.width * shiftRatio * drawerFraction
                    // Subtle Y-axis offset
                    translationY = yOffsetPx * drawerFraction
                    // Scale down 8%
                    scaleX = 1f - 0.08f * drawerFraction
                    scaleY = 1f - 0.08f * drawerFraction
                    // Y-axis rotation -7°
                    rotationY = -7f * drawerFraction
                    // Rounded corners when shifted
                    shape = if (drawerFraction > 0.01f) {
                        RoundedCornerShape(24.dp * drawerFraction)
                    } else {
                        RectangleShape
                    }
                    clip = drawerFraction > 0.01f
                    // Shadow
                    shadowElevation = shadowElevationPx * drawerFraction
                    ambientShadowColor = Color.Black.copy(alpha = 0.3f * drawerFraction)
                    spotShadowColor = Color.Black.copy(alpha = 0.2f * drawerFraction)
                }
        ) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    CodexTopBar(
                        title = screenTitle(currentScreen),
                        onMenuClick = { scope.launch { drawerState.open() } }
                    )
                },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * Top app bar with hamburger menu + title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodexTopBar(
    title: String,
    onMenuClick: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
        },
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(
                    imageVector = Icons.Outlined.Menu,
                    contentDescription = "菜单",
                    modifier = Modifier.size(26.dp)
                )
            }
        },
        actions = {
            // Placeholder for future action buttons (e.g., runtime status indicator)
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = DrawerText,
            navigationIconContentColor = DrawerText,
        )
    )
}

/**
 * Drawer content — simplified Operit-style with brand header, main nav items, and about at bottom.
 * Uses Codex brand colors (#1A1818 background, #F54E00 accent, #E8E6E3 text).
 */
@Composable
private fun DrawerContent(
    drawerFraction: Float,
    selectedScreen: CodexActivity.Screen,
    onNavigate: (CodexActivity.Screen) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .graphicsLayer {
                // Scale from 0.92 → 1.0 and alpha from 0.72 → 1.0
                scaleX = 0.92f + 0.08f * drawerFraction
                scaleY = 0.92f + 0.08f * drawerFraction
                alpha = 0.72f + 0.28f * drawerFraction
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .background(DrawerBackground)
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── Brand header ──
        Spacer(Modifier.height(24.dp))

        Icon(
            imageVector = Icons.Filled.Terminal,
            contentDescription = null,
            tint = DrawerAccent,
            modifier = Modifier.size(48.dp)
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Codex",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = DrawerAccent,
        )

        Text(
            text = "AI Development Environment",
            style = MaterialTheme.typography.bodySmall,
            color = DrawerTextVariant,
        )

        Spacer(Modifier.height(16.dp))

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = Color(0xFF3D3835),
        )

        Spacer(Modifier.height(8.dp))

        // ── Main navigation items ──
        drawerNavItems.forEach { item ->
            val isSelected = item.screen == selectedScreen
            DrawerNavItem(
                title = item.title,
                icon = item.icon,
                isSelected = isSelected,
                onClick = { onNavigate(item.screen) },
            )
        }

        Spacer(Modifier.weight(1f))

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = Color(0xFF3D3835),
        )

        Spacer(Modifier.height(8.dp))

        // ── About at bottom ──
        DrawerNavItem(
            title = "关于",
            icon = Icons.Outlined.Info,
            isSelected = selectedScreen == CodexActivity.Screen.About,
            onClick = { onNavigate(CodexActivity.Screen.About) },
        )

        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Single drawer navigation item — rounded card with bottom accent bar when selected.
 * Uses Codex brand colors: accent #F54E00, text #E8E6E3.
 */
@Composable
private fun DrawerNavItem(
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) {
            DrawerAccent.copy(alpha = 0.15f)
        } else {
            Color.Transparent
        },
        onClick = onClick,
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (isSelected) DrawerAccent else DrawerTextVariant,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) DrawerAccent else DrawerText,
                )
            }
            // Bottom accent bar for selected state
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(0.4f)
                        .height(2.dp)
                        .padding(horizontal = 24.dp)
                        .drawBehind {
                            drawRoundRect(
                                color = DrawerAccent,
                                cornerRadius = CornerRadius(1.dp.toPx()),
                            )
                        }
                )
            }
        }
    }
}
