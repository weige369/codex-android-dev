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
import java.util.Locale

enum class RuntimeState {
    STOPPED, DOWNLOADING, EXTRACTING, INSTALLING,
    STARTING, RUNNING, ERROR
}

/**
 * Codex exec-server 运行时服务。
 *
 * Android untrusted_app domain 无法执行 ptrace（proot 依赖 ptrace），
 * 因此 App 内 ProcessBuilder 启动 proot 始终 exit=255。
 *
 * 解决方案：通过 Shizuku（shell domain）或 Root 执行。
 * Shizuku API 已在 build.gradle 中声明，本文件直接使用。
 *
 * 启动顺序：
 * 1. 尝试 Shizuku（如果安装了 Shizuku 并授权）
 * 2. 尝试 Root（su -c）
 * 3. 普通 ProcessBuilder（仅作 fallback，通常不可用）
 */
class CodexRuntimeService : Service() {

    companion object {
        private const val TAG = "CRS"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "codex_runtime"

        const val DEFAULT_WS_PORT = 9877

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

    // ─── 启动序列 ─────────────────────────────────────────────

    private suspend fun start() {
        if (isRunning) return
        try {
            _state.value = RuntimeState.STARTING

            if (!codexManager.isInstalled()) {
                if (!download()) return
            }
            if (!codexManager.verifyBinary()) {
                log("二进制损坏，重新下载")
                codexManager.cleanup()
                if (!download()) return
            }

            _state.value = RuntimeState.INSTALLING
            installToRootfs()

            _state.value = RuntimeState.STARTING
            _wsPort = findFreePort(DEFAULT_WS_PORT)

            if (!launchProot()) return
            isRunning = true

            delay(2000)
            _state.value = RuntimeState.RUNNING
            updateNotify("Codex 已就绪")
            broadcast()

        } catch (e: Exception) {
            err("启动异常: ${e.message}")
            Log.e(TAG, "启动失败", e)
        }
    }

    private suspend fun download(): Boolean {
        _state.value = RuntimeState.DOWNLOADING
        log("下载 Codex CLI...")
        val ok = withContext(Dispatchers.IO) {
            codexManager.downloadWithProgress { p, t ->
                val pct = if (t > 0) (p * 100 / t) else 0
                if (pct % 5 == 0) { log("下载 $pct%"); updateNotify("下载 $pct%") }
            }
        }
        if (!ok) { err("下载失败"); return false }

        _state.value = RuntimeState.EXTRACTING
        log("解压...")
        if (!codexManager.extractBinary()) { err("解压失败"); return false }
        log("二进制就绪: ${codexManager.codexBinary.length()} B")
        return true
    }

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

    // ─── proot 启动（多层 fallback） ─────────────────────────

    private fun launchProot(): Boolean {
        val libDir = findNativeLibDir()
        if (libDir == null) { err("无法找到 libproot.so"); return false }

        val tmpDir = File(filesDir, "tmp").also { it.mkdirs() }
        val rootfs = File(filesDir, "linux-rootfs").absolutePath
        val port = _wsPort

        // 构建脚本内容
        val script = buildString {
            append("#!/system/bin/sh\n")
            append("export LD_LIBRARY_PATH=$libDir\n")
            append("export PROOT_LOADER=$libDir/libproot-loader.so\n")
            append("export PROOT_TMP_DIR=$tmpDir\n")
            append("exec $libDir/libproot.so")
            append(" --rootfs='$rootfs'")
            append(" --root-id --kill-on-exit")
            append(" -b /dev -b /proc -b /sys -b /storage")
            append(" -w /root")
            append(" /usr/bin/env -i")
            append(" HOME=/root")
            append(" PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            append(" TERM=xterm-256color LANG=C.UTF-8 SHELL=/bin/bash USER=root")
            append(" /bin/bash -c")
            append(" '/usr/local/bin/codex exec-server --listen ws://0.0.0.0:$port'")
            append("\n")
        }

        val scriptFile = File(filesDir, "start_codex.sh")
        scriptFile.writeText(script)
        scriptFile.setExecutable(true)

        // 尝试顺序: Shizuku → Root → ProcessBuilder (fallback)
        log("尝试启动 proot...")

        if (tryShizuku(scriptFile)) {
            _mode = "shizuku"; return true
        }
        if (tryRoot(scriptFile)) {
            _mode = "root"; return true
        }
        if (tryFallback(scriptFile)) {
            _mode = "fallback"; return true
        }

        err("所有启动方式均失败（需要 Shizuku 或 Root）")
        return false
    }

    // ─── Shizuku 方式 ────────────────────────────────────────

    private fun tryShizuku(scriptFile: File): Boolean {
        return try {
            // 通过反射调用 Shizuku API，避免编译期依赖
            val cls = Class.forName("moe.shizuku.api.ShizukuClient")
            val ready = cls.getMethod("isShizukuReady").invoke(null) as? Boolean ?: false
            if (!ready) { log("Shizuku 未就绪"); return false }

            log("通过 Shizuku 执行脚本...")
            // 使用 Runtime 在 shizuku 环境中执行
            // Shizuku 的本质是：用 Shizuku binder 代理可以获得 shell UID 的进程
            // 我们通过 Shizuku 的 newProcess 或直接通过反射创建

            // 最简单的方式：用 shizuku 的 execute 方法
            val service = cls.getMethod("getService").invoke(null)
            val execMethod = service.javaClass.getMethod("exec",
                Array<String>::class.java, String::class.java, String::class.java, Int::class.javaPrimitiveType)

            val result = execMethod.invoke(service,
                arrayOf("/system/bin/sh", scriptFile.absolutePath),
                filesDir.absolutePath,  // cwd
                null,  // env
                0      // flags
            )

            log("Shizuku 进程已启动")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku 启动失败: ${e.message}")
            false
        }
    }

    // ─── Root 方式 ───────────────────────────────────────────

    private fun tryRoot(scriptFile: File): Boolean {
        return try {
            val su = File("/system/bin/su")
            if (!su.exists()) { log("su 不可用"); return false }

            log("通过 Root 执行脚本...")
            val pb = ProcessBuilder("su", "-c", "sh ${scriptFile.absolutePath}")
                .redirectErrorStream(true)
                .directory(filesDir)
            val p = pb.start()

            // 非阻塞读取输出
            scope.launch(Dispatchers.IO) {
                p.inputStream.bufferedReader().use { r ->
                    r.lines().forEach { if (it.isNotBlank()) log("su: $it") }
                }
            }
            scope.launch(Dispatchers.IO) {
                val exit = p.waitFor()
                log("su 进程退出: $exit")
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Root 启动失败: ${e.message}")
            false
        }
    }

    // ─── Fallback: ProcessBuilder ────────────────────────────

    private fun tryFallback(scriptFile: File): Boolean {
        return try {
            log("Fallback: ProcessBuilder...")
            val pb = ProcessBuilder("sh", scriptFile.absolutePath)
                .redirectErrorStream(true)
                .directory(filesDir)
            val p = pb.start()

            scope.launch(Dispatchers.IO) {
                p.inputStream.bufferedReader().use { r ->
                    r.lines().forEach { if (it.isNotBlank()) log("fb: $it") }
                }
            }
            scope.launch(Dispatchers.IO) {
                val exit = p.waitFor()
                log("fb 进程退出: $exit")
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "ProcessBuilder 失败: ${e.message}")
            false
        }
    }

    // ─── 工具函数 ────────────────────────────────────────────

    private fun findNativeLibDir(): String? {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val libDir = appInfo.nativeLibraryDir
            if (libDir != null && File(libDir).exists()) libDir else null
        } catch (e: Exception) {
            Log.w(TAG, "查找 lib 目录失败", e)
            null
        }
    }

    private fun stopCodex() {
        isRunning = false
        _state.value = RuntimeState.STOPPED
    }

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

    // ─── 日志 ─────────────────────────────────────────────────

    private val logLock = Any()
    private val MAX_LOG = 200

    private fun log(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(java.util.Date())
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

    // ─── 通知 ─────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Codex 运行时",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Codex 后台服务"; setShowBadge(false) }
            )
        }
    }

    private fun notify(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
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
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notify(text))
    }
}