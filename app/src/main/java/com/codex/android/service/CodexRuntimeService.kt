package com.codex.android.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.codex.android.codex.CodexManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.ServerSocket

enum class RuntimeState {
    STOPPED,
    DOWNLOADING,
    EXTRACTING,
    INSTALLING,
    STARTING,
    RUNNING,
    ERROR
}

/**
 * 前台服务，管理 Codex exec-server 生命周期。
 *
 * 架构：通过 Termux 在后台启动 proot → codex exec-server，
 * App WebView 通过 WebSocket 连接 ws://127.0.0.1:PORT。
 *
 * 放弃在 App 内直接启动 proot——Android untrusted_app domain
 * 的进程无法可靠执行 ptrace（持续 exit=255）。
 */
class CodexRuntimeService : Service() {

    companion object {
        private const val TAG = "CRS"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "codex_runtime"

        const val DEFAULT_WS_PORT = 9877

        private const val TERMUX_PKG = "com.termux"
        private const val TERMUX_SVC = "$TERMUX_PKG.app.RunCommandService"
        private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"

        private val _state = MutableStateFlow(RuntimeState.STOPPED)
        val state: StateFlow<RuntimeState> = _state.asStateFlow()

        private val _logs = MutableStateFlow<List<String>>(emptyList())
        val logs: StateFlow<List<String>> = _logs.asStateFlow()

        private var _wsPort = DEFAULT_WS_PORT
        val wsPort: Int get() = _wsPort

        private var _mode: String = ""
        val runningMode: String get() = _mode

        fun start(ctx: Context) {
            val i = Intent(ctx, CodexRuntimeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CodexRuntimeService::class.java))
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = false
    private lateinit var codexManager: CodexManager

    override fun onCreate() {
        super.onCreate()
        codexManager = CodexManager(this)
        createNotificationChannel()
        log("服务已创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notify("Codex 准备中"))
        scope.launch { start() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCodex()
        scope.cancel()
        _state.value = RuntimeState.STOPPED
        log("服务已销毁")
        super.onDestroy()
    }

    // ─── 启动序列 ─────────────────────────────────────────────────

    private suspend fun start() {
        if (isRunning) return
        try {
            // 1. 检查 termux
            if (!hasTermux()) {
                err("需要安装 Termux"); return
            }
            _mode = "termux-proot"

            // 2. 确保二进制
            _state.value = RuntimeState.STARTING
            if (!codexManager.isInstalled()) {
                if (!download()) return
            }
            if (!codexManager.verifyBinary()) {
                log("二进制损坏，重新下载")
                codexManager.cleanup()
                if (!download()) return
            }

            // 3. 安装到 rootfs
            _state.value = RuntimeState.INSTALLING
            installToRootfs()

            // 4. 通过 Termux 启动
            _state.value = RuntimeState.STARTING
            _wsPort = findFreePort(DEFAULT_WS_PORT)
            if (!launchInTermux()) return
            isRunning = true

            // 延迟标记就绪（实际由 WS 连接确认）
            delay(3000)
            _state.value = RuntimeState.RUNNING
            updateNotify("Codex 已就绪")
            broadcast()

        } catch (e: Exception) {
            err("启动异常: ${e.message}")
            Log.e(TAG, "启动失败", e)
        }
    }

    // ─── 下载 ─────────────────────────────────────────────────────

    private suspend fun download(): Boolean {
        _state.value = RuntimeState.DOWNLOADING
        log("下载 Codex CLI...")
        val ok = withContext(Dispatchers.IO) {
            codexManager.downloadWithProgress { p, t ->
                val pct = if (t > 0) (p * 100 / t) else 0
                log("下载 $pct%")
                updateNotify("下载 $pct%")
            }
        }
        if (!ok) { err("下载失败"); return false }

        _state.value = RuntimeState.EXTRACTING
        log("解压...")
        if (!codexManager.extractBinary()) { err("解压失败"); return false }
        log("二进制就绪: ${codexManager.codexBinary.length()} B")
        return true
    }

    // ─── 安装 ─────────────────────────────────────────────────────

    private fun installToRootfs() {
        val rootfs = File(filesDir, "linux-rootfs")
        val bin = File(rootfs, "usr/local/bin").also { it.mkdirs() }
        val dest = File(bin, "codex")
        codexManager.codexBinary.inputStream().use { src ->
            dest.outputStream().use { src.copyTo(it) }
        }
        dest.setExecutable(true)
        log("安装完毕: ${dest.length()} B")
    }

    // ─── Termux 启动 ──────────────────────────────────────────────

    private fun hasTermux(): Boolean = try {
        packageManager.getPackageInfo(TERMUX_PKG, 0); true
    } catch (_: Exception) { false }

    private fun launchInTermux(): Boolean {
        val rootfs = File(filesDir, "linux-rootfs").absolutePath
        val port = _wsPort
        val dataDir = filesDir.absolutePath

        // 先确保 Termux 有 proot
        sendTermuxCmd("pkg install -y proot 2>/dev/null; which proot || echo NO_PROOT")

        // 构造 proot 命令
        val prootCmd = buildString {
            append("proot")
            append(" --rootfs='$rootfs'")
            append(" --root-id --kill-on-exit")
            append(" -b /dev -b /proc -b /sys")
            append(" -b '$dataDir:$dataDir'")
            append(" -b /storage")
            append(" -w /root")
            append(" /usr/bin/env -i")
            append(" HOME=/root")
            append(" PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            append(" TERM=xterm-256color LANG=C.UTF-8 SHELL=/bin/bash USER=root")
            append(" /bin/bash -c")
            append(" '/usr/local/bin/codex exec-server --listen ws://0.0.0.0:$port'")
        }

        log("发送 Termux 命令")
        log("proot: $prootCmd.take(200)...")
        return sendTermuxCmd(prootCmd)
    }

    private fun sendTermuxCmd(cmd: String): Boolean {
        return try {
            val intent = Intent().apply {
                setClassName(TERMUX_PKG, TERMUX_SVC)
                action = "$TERMUX_PKG.RUN_COMMAND"
                putExtra("$TERMUX_PKG.RUN_COMMAND_PATH", TERMUX_BASH)
                putExtra("$TERMUX_PKG.RUN_COMMAND_ARGUMENTS", arrayOf("-c", cmd))
                putExtra("$TERMUX_PKG.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("$TERMUX_PKG.RUN_COMMAND_BACKGROUND_TASK", true)
                putExtra("$TERMUX_PKG.RUN_COMMAND_SESSION_ACTION", "0")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            true
        } catch (e: Exception) {
            log("Termux 命令失败: ${e.message}")
            false
        }
    }

    // ─── 停止 ─────────────────────────────────────────────────────

    private fun stopCodex() {
        isRunning = false
        sendTermuxCmd("pkill -f 'codex exec-server' 2>/dev/null; echo ok")
        _state.value = RuntimeState.STOPPED
    }

    // ─── 工具 ─────────────────────────────────────────────────────

    private fun findFreePort(start: Int): Int {
        var p = start
        while (p < start + 100) {
            try { ServerSocket(p).use { return p } } catch (_: Exception) { p++ }
        }
        return start
    }

    private fun broadcast() {
        sendBroadcast(Intent("com.codex.android.CODEX_STATUS").apply {
            putExtra("state", _state.value.name)
            putExtra("wsPort", _wsPort)
            putExtra("isRunning", isRunning)
            putExtra("runningMode", _mode)
        })
    }

    // ─── 日志 ─────────────────────────────────────────────────────

    private val logLock = Any()
    private val MAX_LOG = 200

    private fun log(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        synchronized(logLock) {
            _logs.value = (_logs.value + "[$ts] $msg").takeLast(MAX_LOG)
        }
        Log.d(TAG, msg)
    }

    private fun err(msg: String) {
        log("ERROR: $msg")
        _state.value = RuntimeState.ERROR
        updateNotify("错误: $msg")
    }

    // ─── 通知 ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Codex 运行时", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Codex 后台服务"; setShowBadge(false)
                }
            )
        }
    }

    private fun notify(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Codex")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notify(text))
    }
}