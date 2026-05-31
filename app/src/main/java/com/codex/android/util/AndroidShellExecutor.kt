package com.codex.android.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Android Shell 执行器。
 *
 * 支持多种执行环境：
 * - SHIZUKU: Shizuku 高级权限
 * - ROOT: Root 权限
 */
object AndroidShellExecutor {

    private const val TAG = "AndroidShellExecutor"
    private const val DEFAULT_TIMEOUT_MS = 120_000L

    enum class PermissionLevel {
        NORMAL,

        UBUNTU_PROOT,
        SHIZUKU,
        ROOT
    }

    data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val isTimedOut: Boolean = false,
        val permissionLevel: PermissionLevel = PermissionLevel.NORMAL
    )

    data class ProcessInfo(
        val id: Int,
        val command: String,
        val startTime: Long,
        val permissionLevel: PermissionLevel
    )

    private val activeProcesses = ConcurrentHashMap<Int, Process>()
    private val processHistory = mutableListOf<ProcessInfo>()
    private val pidCounter = AtomicInteger(0)

    // 开发环境管理器（由外部初始化）
    private var devEnv: DevelopmentEnvironment? = null

    fun init(context: Context) {
        devEnv = DevelopmentEnvironment(context)
    }

    fun getDevEnv(): DevelopmentEnvironment? = devEnv

    suspend fun execute(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        permissionLevel: PermissionLevel = PermissionLevel.NORMAL,
        env: Map<String, String> = emptyMap()
    ): ShellResult = withContext(Dispatchers.IO) {
        try {
            val process = createProcess(command, permissionLevel, env)
            val id = pidCounter.incrementAndGet()
            activeProcesses[id] = process
            processHistory.add(ProcessInfo(id, command, System.currentTimeMillis(), permissionLevel))

            val stdout = StringBuilder()
            val stderr = StringBuilder()

            val stdoutThread = Thread {
                try { process.inputStream.bufferedReader().use { r -> r.lines().forEach { stdout.appendLine(it) } } }
                catch (e: Exception) { Log.e(TAG, "读取标准输出失败", e) }
            }
            val stderrThread = Thread {
                try { process.errorStream.bufferedReader().use { r -> r.lines().forEach { stderr.appendLine(it) } } }
                catch (e: Exception) { Log.e(TAG, "读取错误输出失败", e) }
            }
            stdoutThread.start(); stderrThread.start()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            stdoutThread.join(1000); stderrThread.join(1000)

            if (!finished) {
                killProcess(id)
                activeProcesses.remove(id)
                ShellResult(-1, stdout.toString(), stderr.toString(), isTimedOut = true, permissionLevel = permissionLevel)
            } else {
                activeProcesses.remove(id)
                ShellResult(process.exitValue(), stdout.toString(), stderr.toString(), permissionLevel = permissionLevel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行命令失败: $command", e)
            ShellResult(-1, "", "执行失败: ${e.message}", permissionLevel = permissionLevel)
        }
    }

    private fun createProcess(
        command: String,
        permissionLevel: PermissionLevel,
        env: Map<String, String>
    ): Process {
        return when (permissionLevel) {
            PermissionLevel.UBUNTU_PROOT -> {
                val envObj = devEnv
                if (envObj != null) {
                    envObj.createProotProcess(command, env)
                } else {
                    ProcessBuilder("sh", "-c", command)
                        .apply { environment().putAll(env) }
                        .start()
                }
            }
            PermissionLevel.SHIZUKU -> {
                ProcessBuilder("sh", "-c", command)
                    .apply { environment().putAll(env) }
                    .start()
            }
            PermissionLevel.ROOT -> {
                ProcessBuilder("su", "-c", command)
                    .apply { environment().putAll(env) }
                    .start()
            }
            PermissionLevel.NORMAL -> {
                ProcessBuilder("sh", "-c", command)
                    .apply { environment().putAll(env) }
                    .start()
            }
        }
    }

    fun killProcess(id: Int) {
        activeProcesses[id]?.let { process ->
            try { process.destroyForcibly() } catch (e: Exception) { Log.w(TAG, "杀死进程失败: $id", e) }
        }
    }

    fun killAll() {
        activeProcesses.keys.toList().forEach { id -> killProcess(id); activeProcesses.remove(id) }
    }

    fun getActiveProcesses(): List<ProcessInfo> = processHistory.toList()

    fun isShizukuAvailable(context: Context): Boolean {
        return try { Class.forName("moe.shizuku.api.ShizukuApi"); true } catch (_: Exception) { false }
    }

    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            process.destroy()
            line != null
        } catch (_: Exception) { false }
    }

}
