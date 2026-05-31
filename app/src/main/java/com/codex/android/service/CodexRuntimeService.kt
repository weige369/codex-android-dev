package com.codex.android.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.codex.android.agent.NativeAgentService
import com.codex.android.codex.CodexManager
import com.codex.android.util.AndroidShellExecutor
import com.codex.android.util.DevelopmentEnvironment
import java.io.File
import com.codex.android.util.LinuxEnvironment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.ServerSocket

enum class RuntimeState {
    STOPPED,
    DOWNLOADING,
    EXTRACTING,
    STARTING,
    RUNNING,
    NATIVE_MODE,  // 新增：原生 API 模式（无需外部二进制）
    ERROR
}

/**
 * 前台服务，管理 Codex 运行时生命周期。
 *
 * 运行策略（按优先级）：
 * 1. 原生 API 模式 → 直接在进程内调用 OpenAI 兼容 API（推荐，无需外部二进制）
 * 2. proot Linux 模式 → 在 proot Ubuntu 中运行 Codex CLI（实验性）
 * 3. 直接运行 → 尝试直接执行 Codex 二进制（Android 36 几乎不可能）
 *
 * 学习 Operit 的核心经验：AI Agent 不需要外部二进制，
 * 直接在 Kotlin 进程内调用 API + 执行工具即可。
 */
class CodexRuntimeService : Service() {

    companion object {
        private const val TAG = "CodexRuntimeService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "codex_runtime"
        private const val ACTION_START = "com.codex.android.action.START_CODEX"
        private const val ACTION_STOP = "com.codex.android.action.STOP_CODEX"
        private const val ACTION_STATUS = "com.codex.android.action.CODEX_STATUS"

        const val DEFAULT_WS_PORT = 9877
        const val DEFAULT_HTTP_PORT = 19327

        private val _state = MutableStateFlow(RuntimeState.STOPPED)
        val state: StateFlow<RuntimeState> = _state.asStateFlow()

        private val _logs = MutableStateFlow<List<String>>(emptyList())
        val logs: StateFlow<List<String>> = _logs.asStateFlow()

        private var _wsPort = DEFAULT_WS_PORT
        val wsPort: Int get() = _wsPort

        private var _runningMode: String = "unknown"
        val runningMode: String get() = _runningMode

        fun start(context: Context) {
            val intent = Intent(context, CodexRuntimeService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CodexRuntimeService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val logsLock = Any()
    private lateinit var codexManager: CodexManager
    private lateinit var devEnv: DevelopmentEnvironment
    private lateinit var nativeAgent: NativeAgentService
    private var codexProcess: java.lang.Process? = null
    @Volatile
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        codexManager = CodexManager(this)
        devEnv = DevelopmentEnvironment(this)
        nativeAgent = NativeAgentService.getInstance(this)
        AndroidShellExecutor.init(this)
        createNotificationChannel()
        addLog("CodexRuntimeService 已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification("Codex 启动中..."))
                serviceScope.launch { startCodex() }
            }
            ACTION_STOP -> {
                stopCodex()
                stopSelf()
            }
            ACTION_STATUS -> broadcastStatus()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCodex()
        serviceScope.cancel()
        _state.value = RuntimeState.STOPPED
        addLog("CodexRuntimeService 已销毁")
        super.onDestroy()
    }

    private suspend fun startCodex() {
        if (isRunning) {
            addLog("Codex 已在运行中 (模式: $_runningMode)")
            return
        }

        try {
            _state.value = RuntimeState.STARTING
            addLog("开始启动 Codex...")

            // ===== 策略 1（推荐）：原生 API 模式 =====
            // 学习 Operit：直接在 Kotlin 进程内调用 AI API，无需外部二进制
            if (nativeAgent.isConfigured()) {
                addLog("检测到 API 配置，使用原生 API 模式")
                addLog("提供商: ${nativeAgent.getProviderId()}, 模型: ${nativeAgent.getApiModel()}")

                // 测试 API 连通性
                val connected = nativeAgent.testConnection()
                if (connected) {
                    startNativeMode()
                    return
                } else {
                    addLog("⚠️ API 连接测试失败，尝试其他模式...")
                }
            } else {
                addLog("未配置 API Key，跳过原生 API 模式")
                addLog("💡 提示：在设置页面配置 AI 提供商即可直接使用，无需安装任何二进制")
            }

            // ===== 策略 2：proot Linux 模式（实验性） =====
            val linuxEnv = LinuxEnvironment(this)
            val linuxInfo = linuxEnv.getInfo()
            val hasProotLinux = linuxInfo.state == LinuxEnvironment.EngineState.READY

            if (hasProotLinux && codexManager.isInstalled()) {
                addLog("检测到 proot Linux + Codex 二进制，尝试 proot 模式...")
                _runningMode = "proot-linux"
                startCodexInProot(linuxInfo)
                return
            }

            // ===== 策略 3：直接运行（最后手段） =====
            if (codexManager.isInstalled()) {
                addLog("尝试直接运行 Codex 二进制...")
                _runningMode = "direct"

                val probe = codexManager.testDirectExecution()
                if (probe.success) {
                    addLog("自检通过：${probe.message}")
                    // 需要先下载/解压
                    _state.value = RuntimeState.STARTING
                    startDirect()
                    return
                } else {
                    addLog("直接运行不可用: ${probe.message}")
                }
            }

            // ===== 无可用模式 =====
            addLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            addLog("❌ 所有启动模式均不可用")
            addLog("")
            addLog("推荐操作：在设置页面配置 AI 提供商")
            addLog("支持: DeepSeek / OpenAI / SiliconFlow / 智谱 / Moonshot")
            addLog("配置后无需安装任何二进制即可使用")
            addLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            _state.value = RuntimeState.ERROR
            updateNotification("请配置 AI 提供商")

        } catch (e: Exception) {
            _state.value = RuntimeState.ERROR
            addLog("Codex 启动异常: ${e.message}")
            Log.e(TAG, "启动 Codex 失败", e)
            updateNotification("Codex 错误: ${e.message}")
        }
    }

    /**
     * 原生 API 模式启动（推荐）。
     * 无需外部二进制，直接在 Kotlin 进程内完成 AI Agent 闭环。
     */
    private fun startNativeMode() {
        _runningMode = "native-api"
        nativeAgent.markReady()
        isRunning = true
        _state.value = RuntimeState.NATIVE_MODE
        addLog("✅ Codex 原生 API 模式已就绪")
        addLog("   无需外部二进制，直接调用 AI API")
        addLog("   提供商: ${nativeAgent.getProviderId()}")
        addLog("   模型: ${nativeAgent.getApiModel()}")
        updateNotification("Codex 已就绪（原生 API）")
        broadcastStatus()
    }

    /**
     * 在 Ubuntu proot 中启动 Codex
     */
    private suspend fun startCodexInProot(linuxInfo: LinuxEnvironment.LinuxEnvInfo) {
        try {
            val linuxEnv = LinuxEnvironment(this)

            addLog("安装 Codex 到 proot rootfs...")
            val rootfsBin = File(linuxInfo.rootfsPath, "/usr/local/bin")
            rootfsBin.mkdirs()
            codexManager.codexBinary.inputStream().use { input ->
                File(rootfsBin, "codex").outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            File(rootfsBin, "codex").setExecutable(true)

            addLog("通过 proot 启动 Codex...")
            val launchCmd = "codex --unix-daemon --http-port ${_wsPort + 1} --ws-port $_wsPort"
            val cmd = linuxEnv.buildProotCommand(launchCmd)
            val prootEnv = linuxEnv.getProotEnv()

            codexProcess = ProcessBuilder(cmd)
                .apply {
                    environment().putAll(prootEnv)
                    redirectErrorStream(false)
                }
                .start()
            isRunning = true

            serviceScope.launch {
                try {
                    codexProcess?.inputStream?.bufferedReader()?.use { reader ->
                        reader.lines().forEach { line ->
                            addLog("[Codex-proot] $line")
                            if (line.contains("listening", ignoreCase = true) ||
                                line.contains("started", ignoreCase = true) ||
                                line.contains("ready", ignoreCase = true)) {
                                _state.value = RuntimeState.RUNNING
                                updateNotification("Codex 已就绪 (proot Linux)")
                                broadcastStatus()
                            }
                        }
                    }
                } catch (e: Exception) {
                    addLog("Codex proot 输出流已关闭: ${e.message}")
                }
            }

            // 等待启动
            delay(5000)

            if (codexProcess?.isAlive == true) {
                _state.value = RuntimeState.RUNNING
                addLog("Codex proot 模式已启动")
                updateNotification("Codex 已就绪 (proot)")
                broadcastStatus()
            } else {
                val exit = codexProcess?.exitValue() ?: -1
                addLog("Codex proot 进程异常退出 (exit=$exit)")
                addLog("💡 建议切换到原生 API 模式：在设置中配置 AI 提供商")
                _state.value = RuntimeState.ERROR
                updateNotification("proot 模式失败，请配置 API")
                isRunning = false
            }
        } catch (e: Exception) {
            addLog("proot 模式启动失败: ${e.message}")
            addLog("💡 建议切换到原生 API 模式：在设置中配置 AI 提供商")
            _state.value = RuntimeState.ERROR
            updateNotification("Codex 启动失败（proot）")
        }
    }

    /**
     * 直接启动（Android 原生，失败率高）
     */
    private suspend fun startDirect() {
        addLog("尝试直接运行 Codex...")
        val probe = codexManager.testDirectExecution()
        if (!probe.success) {
            addLog("直接运行不可用: ${probe.message}")
            addLog("💡 建议切换到原生 API 模式：在设置中配置 AI 提供商")
            _state.value = RuntimeState.ERROR
            updateNotification("需要 API 配置")
            return
        }

        try {
            val process = ProcessBuilder(
                codexManager.codexBinary.absolutePath,
                "exec-server",
                "--port", _wsPort.toString(),
                "--http-port", (_wsPort + 1).toString(),
                "--skip-git-repo-check"
            ).apply {
                redirectErrorStream(true)
                environment()["CODEX_CONFIG_DIR"] = codexManager.getConfigDir().absolutePath
                environment()["HOME"] = codexManager.workspaceDir.absolutePath
                directory(codexManager.workspaceDir)
            }.start()
            codexProcess = process
            isRunning = true
            addLog("已直接启动 Codex exec-server")

            serviceScope.launch {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        reader.lines().forEach { line ->
                            addLog("[Codex] $line")
                            if (line.contains("listening", ignoreCase = true) ||
                                line.contains("started", ignoreCase = true) ||
                                line.contains("ready", ignoreCase = true)) {
                                _state.value = RuntimeState.RUNNING
                                updateNotification("Codex 已就绪")
                                broadcastStatus()
                            }
                        }
                    }
                } catch (e: Exception) {
                    addLog("Codex 输出流已关闭: ${e.message}")
                }
            }
        } catch (e: Exception) {
            addLog("直接启动失败: ${e.message}")
            _state.value = RuntimeState.ERROR
            updateNotification("Codex 启动失败")
        }
    }

    private fun stopCodex() {
        addLog("正在停止 Codex...")
        isRunning = false
        try {
            codexProcess?.destroyForcibly()
            codexProcess?.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) { Log.w(TAG, "停止 Codex 进程时出错", e) }
        codexProcess = null
        nativeAgent.cancelStream()
        _state.value = RuntimeState.STOPPED
        addLog("Codex 已停止")
    }

    private fun broadcastStatus() {
        val intent = Intent("com.codex.android.CODEX_STATUS").apply {
            putExtra("state", _state.value.name)
            putExtra("wsPort", _wsPort)
            putExtra("isRunning", isRunning)
            putExtra("runningMode", _runningMode)
        }
        sendBroadcast(intent)
    }

    private fun findFreePort(startPort: Int): Int {
        var port = startPort
        while (port < startPort + 100) {
            try { ServerSocket(port).use { it.close(); return port } } catch (e: Exception) { port++ }
        }
        return startPort
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Codex 运行时", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Codex AI 编码代理后台服务"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CodexRuntimeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Codex AI")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .build()
    }

    private fun updateNotification(content: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification(content))
    }

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        synchronized(logsLock) {
            val newLogs = _logs.value + "[$timestamp] $message"
            _logs.value = if (newLogs.size > 500) newLogs.takeLast(200) else newLogs
        }
        Log.d(TAG, message)
    }
}
