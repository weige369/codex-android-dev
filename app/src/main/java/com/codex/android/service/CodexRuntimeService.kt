package com.codex.android.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
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
    ERROR
}

/**
 * 前台服务，管理 Codex CLI 运行时生命周期。
 *
 * 运行策略：
 * 1. 自包含 Linux (proot) → 在 proot Ubuntu 中运行
 * 2. 自包含模式 → 尝试直接运行（功能受限）
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
    private var totalLogCount = 0
    private val MAX_LOG_ENTRIES = 200
    private lateinit var codexManager: CodexManager
    private lateinit var devEnv: DevelopmentEnvironment
    private var codexProcess: java.lang.Process? = null
    @Volatile
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        codexManager = CodexManager(this)
        devEnv = DevelopmentEnvironment(this)
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
            addLog("Codex 已在运行中")
            return
        }

        try {
            // 检测运行环境
            val linuxEnv = LinuxEnvironment(this)
            val linuxInfo = linuxEnv.getInfo()
            val hasSelfContainedLinux = linuxInfo.state == LinuxEnvironment.EngineState.READY

            _runningMode = if (hasSelfContainedLinux) {
                addLog("自包含 Linux 环境已就绪，将通过 proot 运行 Codex")
                "proot-linux"
            } else {
                addLog("自包含模式（未安装 Linux 环境，无法运行 Codex）")
                "direct"
            }
            addLog("运行环境: $_runningMode")

            // 下载/验证二进制
            _state.value = RuntimeState.STARTING
            addLog("检查 Codex 二进制文件...")

            if (hasSelfContainedLinux && codexManager.isInstalled()) {
                // 自包含 Linux 模式：将 Codex 二进制复制到 rootfs 中运行
                addLog("自包含 Linux 模式启动...")
                _state.value = RuntimeState.STARTING
                startCodexInProot(linuxInfo)
                return
            }

            if (!codexManager.isInstalled()) {
                _state.value = RuntimeState.DOWNLOADING
                addLog("需要下载 Codex CLI...")

                val success = withContext(Dispatchers.IO) {
                    codexManager.downloadWithProgress { progress, total ->
                        val pct = if (total > 0) (progress * 100 / total) else 0
                        addLog("下载进度: $pct%")
                        updateNotification("下载 Codex... $pct%")
                    }
                }

                if (!success) {
                    _state.value = RuntimeState.ERROR
                    addLog("Codex 下载失败")
                    updateNotification("Codex 下载失败")
                    return
                }

                _state.value = RuntimeState.EXTRACTING
                addLog("正在解压 Codex CLI...")
                updateNotification("正在解压 Codex CLI...")

                if (!codexManager.extractBinary()) {
                    _state.value = RuntimeState.ERROR
                    addLog("Codex 解压失败")
                    updateNotification("Codex 解压失败")
                    return
                }

                addLog("Codex 二进制就绪: ${codexManager.codexBinary.length()} bytes")
            }

            if (!codexManager.verifyBinary()) {
                addLog("二进制验证失败，重新下载...")
                codexManager.cleanup()
                _state.value = RuntimeState.ERROR
                updateNotification("Codex 二进制损坏")
                return
            }

            if (!codexManager.getConfigFile().exists()) {
                codexManager.createDefaultConfig()
                addLog("已创建默认配置")
            }

            codexManager.workspaceDir.mkdirs()
            _wsPort = findFreePort(DEFAULT_WS_PORT)
            addLog("WebSocket 端口: $_wsPort")
            addLog("即将分配的 HTTP 端口: ${_wsPort + 1}")

            // 启动 Codex — 根据运行模式选择正确路径
            _state.value = RuntimeState.STARTING
            addLog("正在启动 Codex exec-server...")
            updateNotification("正在启动 Codex...")

            when (_runningMode) {
                "proot-linux" -> {
                    // 下载完成后也需要通过 proot 启动（不能走 direct 路径）
                    addLog("proot-linux 模式：通过 proot 启动...")
                    startCodexInProot(linuxInfo)
                    // startCodexInProot 内部已处理状态，不在此处再检查 isAlive
                    return
                }
                else -> startDirect()
            }

            // 等待确认
            delay(3000)

            if (codexProcess?.isAlive == true) {
                _state.value = RuntimeState.RUNNING
                addLog("Codex exec-server 已启动 ($_runningMode)")
                updateNotification("Codex 已就绪")
                broadcastStatus()
            } else {
                val exitCode = codexProcess?.exitValue() ?: -1
                _state.value = RuntimeState.ERROR
                addLog("Codex 进程异常退出 (exit=$exitCode)")
                addLog("请先在环境页面安装 Linux 环境")
                updateNotification("Codex 启动失败")
                isRunning = false
            }

        } catch (e: Exception) {
            _state.value = RuntimeState.ERROR
            addLog("Codex 启动异常: ${e.message}")
            Log.e(TAG, "启动 Codex 失败", e)
            updateNotification("Codex 错误: ${e.message}")
        }
    }

    /**
     * 在 Ubuntu proot 中启动 Codex
     */

    /**
     * 在 Termux 中启动 Codex
     */

    /**
     * 直接启动（Android 原生，失败率高）
     */
    private suspend fun startDirect() {
        addLog("尝试直接运行 Codex...")
        // 先做可行性自检：能否直接执行该二进制
        val probe = codexManager.testDirectExecution()
        if (!probe.success) {
            addLog("直接运行不可用: ${probe.message}")
            addLog("Codex CLI 二进制编译目标为 Linux musl")
            if (probe.sdkInt >= 29) {
                addLog("Android ${probe.sdkInt} 禁止执行应用私有目录中的可执行文件（W^X）")
            }
            addLog("请在'环境'页面安装 内置 Linux 后重试")
            _state.value = RuntimeState.ERROR
            updateNotification("需要 Termux 环境")
            return
        }

        addLog("直接运行自检通过：${probe.message}")
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
            addLog("已直接启动 Codex exec-server（自包含模式）")

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
            addLog("直接启动 exec-server 失败: ${e.message}")
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
        _state.value = RuntimeState.STOPPED
        addLog("Codex 已停止")
    }


    /**
     * 通用的 Codex 进程启动方法
     */
    private suspend fun startCodexProcess(launchCmd: String, modeName: String) {
        addLog("安装 Codex 到 $modeName 环境...")

        isRunning = true

        serviceScope.launch {
            try {
                codexProcess?.inputStream?.bufferedReader()?.use { reader ->
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
    }
    /**
     * 在自包含 Linux（proot）环境中启动 Codex
     */
    private suspend fun startCodexInProot(linuxInfo: LinuxEnvironment.LinuxEnvInfo) {
        try {
            val linuxEnv = LinuxEnvironment(this)
            
            // 将 Codex 二进制复制到 rootfs 中
            addLog("安装 Codex 到 proot rootfs...")
            addLog("rootfs 路径: ${linuxInfo.rootfsPath}")
            addLog("proot 路径: ${linuxInfo.prootPath}")
            addLog("proot loader: ${linuxInfo.prootLoaderPath}")
            val rootfsBin = File(linuxInfo.rootfsPath, "usr/local/bin")
            if (!rootfsBin.exists()) {
                rootfsBin.mkdirs()
                addLog("创建 /usr/local/bin 目录: ${rootfsBin.absolutePath}")
            }
            codexManager.codexBinary.inputStream().use { input ->
                File(rootfsBin, "codex").outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val codexInRootfs = File(rootfsBin, "codex")
            val setExecOk = codexInRootfs.setExecutable(true)
            addLog("Codex 二进制已安装到 rootfs: ${codexInRootfs.absolutePath} (${codexInRootfs.length()} bytes, executable=$setExecOk)")

            // 先做最小 proot 验证 — 检查 proot + bash 能否工作
            addLog("--- proot 验证 (echo+id) ---")
            try {
                val testCmd = linuxEnv.buildProotCommand("echo 'proot_ok' && id")
                val testEnv = linuxEnv.getProotEnv()
                // 不要移除 LD_LIBRARY_PATH — proot 自身需要它来加载 libtalloc.so 等
                // proot 内部由 env -i 清空环境变量，不会干扰 Ubuntu glibc
                addLog("proot 验证命令: ${testCmd.joinToString(" ")}")
                val testProcess = ProcessBuilder(testCmd)
                    .apply {
                        environment().putAll(testEnv)
                        redirectErrorStream(true)
                    }
                    .start()
                // 用 readLine 逐行读取避免阻塞
                val testLines = mutableListOf<String>()
                testProcess.inputStream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        testLines.add(line)
                        line = reader.readLine()
                    }
                }
                val testFinished = testProcess.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
                val testExit = if (testFinished) testProcess.exitValue() else -999
                addLog("proot 验证: exit=$testExit, lines=${testLines.size}")
                testLines.forEach { addLog("  verify: $it") }
                if (testExit != 0 || testLines.isEmpty()) {
                    addLog("WARNING: proot 基本验证异常，Codex 可能无法启动")
                }
            } catch (e: Exception) {
                addLog("proot 验证异常: ${e.javaClass.simpleName}: ${e.message}")
            }
            addLog("--- 验证结束 ---")

            // 第二轮验证：codex 二进制在 proot 内能否执行
            addLog("--- codex 二进制验证 ---")
            try {
                val probeCmd = "/usr/local/bin/codex --help 2>&1 || /usr/local/bin/codex --version 2>&1 || echo 'codex_binary_failed'"
                val probeFullCmd = linuxEnv.buildProotCommand(probeCmd)
                val probeEnv = linuxEnv.getProotEnv()
                val probeProcess = ProcessBuilder(probeFullCmd)
                    .apply {
                        environment().putAll(probeEnv)
                        redirectErrorStream(true)
                    }
                    .start()
                val probeLines = mutableListOf<String>()
                probeProcess.inputStream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        probeLines.add(line)
                        line = reader.readLine()
                    }
                }
                val probeFinished = probeProcess.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
                val probeExit = if (probeFinished) probeProcess.exitValue() else -999
                addLog("codex 验证: exit=$probeExit, lines=${probeLines.size}")
                probeLines.take(10).forEach { addLog("  codex: $it") }
            } catch (e: Exception) {
                addLog("codex 验证异常: ${e.javaClass.simpleName}: ${e.message}")
            }
            addLog("--- codex 验证结束 ---")

            // 构建启动命令
            addLog("通过 proot 启动 Codex exec-server...")
            val launchCmd = "echo '[codex] starting exec-server...' && exec /usr/local/bin/codex exec-server --port $_wsPort --http-port ${_wsPort + 1}"
            addLog("启动命令: $launchCmd")
            val cmd = linuxEnv.buildProotCommand(launchCmd)
            addLog("完整 proot 命令: ${cmd.joinToString(" ")}")
            val prootEnv = linuxEnv.getProotEnv()
            // 保留完整 env — LD_LIBRARY_PATH 是 proot 自身需要的
            // proot 内部由 buildProotCommand 中的 env -i 清空环境，不会干扰 Ubuntu glibc
            addLog("环境变量 (${prootEnv.size}): ${prootEnv.keys.take(5).joinToString()}")

            codexProcess = ProcessBuilder(cmd)
                .apply {
                    environment().putAll(prootEnv)
                    redirectErrorStream(false)
                }
                .start()
            isRunning = true
            addLog("proot 进程已启动，PID 暂不可知")

            // 同时监控 stdout 和 stderr + 进程存活
            serviceScope.launch {
                try {
                    val process = codexProcess ?: return@launch
                    
                    val stdoutJob = launch {
                        try {
                            process.inputStream.bufferedReader().use { reader ->
                                reader.lines().forEach { line ->
                                    addLog("[Codex-proot] $line")
                                    if (line.contains("listening", ignoreCase = true) ||
                                        line.contains("started", ignoreCase = true) ||
                                        line.contains("ready", ignoreCase = true)) {
                                        _state.value = RuntimeState.RUNNING
                                        updateNotification("Codex 已就绪 (自包含 Linux)")
                                        broadcastStatus()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            addLog("Codex proot stdout 已关闭: ${e.message}")
                        }
                    }
                    val stderrJob = launch {
                        try {
                            process.errorStream.bufferedReader().use { reader ->
                                reader.lines().forEach { line ->
                                    if (line.isNotBlank()) {
                                        addLog("[Codex-proot-err] $line")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            addLog("Codex proot stderr 已关闭: ${e.message}")
                        }
                    }
                    // 进程存活监控：每 5 秒检查一次
                    val monitorJob = launch {
                        var lastCheck = 0
                        while (isRunning && isActive) {
                            delay(5000)
                            lastCheck++
                            if (!process.isAlive) {
                                val exitCode = process.exitValue()
                                addLog("[Codex-proot] 进程已退出, exit=$exitCode")
                                _state.value = RuntimeState.ERROR
                                updateNotification("Codex 异常退出 (exit=$exitCode)")
                                break
                            }
                            if (lastCheck >= 12) { // 60秒无"ready"输出
                                addLog("[Codex-proot] 进程存活但60秒无就绪信号，可能卡住")
                            }
                        }
                    }
                    // 等待流关闭
                    joinAll(stdoutJob, stderrJob)
                    monitorJob.cancel()
                    
                    // 流关闭后检查最终状态
                    if (process.isAlive) {
                        addLog("[Codex-proot] stdout/stderr 已关闭但进程仍存活")
                    }
                } catch (e: Exception) {
                    addLog("Codex proot 输出监控异常: ${e.message}")
                }
            }
        } catch (e: Exception) {
            addLog("自包含 Linux 启动失败: ${e.message}")
            Log.e(TAG, "启动 Codex (proot) 失败", e)
            _state.value = RuntimeState.ERROR
            updateNotification("Codex 启动失败（自包含 Linux）")
        }
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

        // 停止按钮
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
            totalLogCount++
            val newLogs = _logs.value + "[$timestamp] $message"
            _logs.value = if (newLogs.size > MAX_LOG_ENTRIES * 2) newLogs.takeLast(MAX_LOG_ENTRIES) else newLogs
        }
        Log.d(TAG, message)
    }
}
