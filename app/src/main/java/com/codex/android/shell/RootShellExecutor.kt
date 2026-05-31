package com.codex.android.shell

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Root Shell 执行器。
 *
 * 通过 `su -c` 以 Root 权限执行 Shell 命令，拥有完整的系统控制能力。
 * 需要设备已 Root（Magisk、KernelSU 等）。
 *
 * 特性：
 * - 使用 `ProcessBuilder("su", "-c", command)` 创建 Root 进程
 * - 自动检测 Root 可用性（通过 `which su` 检测）
 * - 继承标准执行器的超时管理和进程追踪机制
 * - 权限状态缓存，避免重复检测
 *
 * 安全警告：Root 执行器拥有最高权限，可执行任何系统级操作。
 * 务必通过 [PermissionMiddleware] 进行三层权限评估后再使用。
 */
class RootShellExecutor : ShellExecutor {

    companion object {
        private const val TAG = "RootShellExecutor"

        /** 默认命令执行超时时间（毫秒）。 */
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }

    /**
     * 活动进程追踪表。
     */
    private val activeProcesses = ConcurrentHashMap<Long, Process>()

    /** 进程 ID 生成器。 */
    private val processIdCounter = AtomicLong(0)

    /**
     * Root 可用性缓存。
     *
     * null 表示尚未检测，true/false 表示检测结果。
     * 使用 volatile 保证多线程可见性。
     */
    @Volatile
    private var rootAvailable: Boolean? = null

    /** 是否已初始化。 */
    @Volatile
    private var initialized = false

    //region ShellExecutor 接口实现

    override suspend fun executeCommand(command: String): ShellExecutor.CommandResult =
        withContext(Dispatchers.IO) {
            // 检查 Root 可用性
            if (!checkRootAvailable()) {
                return@withContext ShellExecutor.CommandResult(
                    success = false,
                    stdout = "",
                    stderr = "设备未 Root 或 su 不可用",
                    exitCode = -1
                )
            }

            try {
                val process = createRootProcess(command)
                val processId = processIdCounter.incrementAndGet()
                activeProcesses[processId] = process

                // redirectErrorStream(true) 已合并输出
                val output = StringBuilder()
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.appendLine(line)
                }
                reader.close()

                val finished = process.waitFor(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

                if (!finished) {
                    destroyProcess(processId)
                    ShellExecutor.CommandResult(
                        success = false,
                        stdout = output.toString(),
                        stderr = "Root 命令执行超时（${DEFAULT_TIMEOUT_MS}ms）",
                        exitCode = -1
                    )
                } else {
                    activeProcesses.remove(processId)
                    val exitCode = process.exitValue()
                    ShellExecutor.CommandResult(
                        success = exitCode == 0,
                        stdout = output.toString(),
                        stderr = "",
                        exitCode = exitCode
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Root 执行命令失败: $command", e)
                ShellExecutor.CommandResult(
                    success = false,
                    stdout = "",
                    stderr = "Root 执行失败: ${e.message}",
                    exitCode = -1
                )
            }
        }

    override fun getPermissionLevel(): AndroidPermissionLevel {
        return AndroidPermissionLevel.ROOT
    }

    override fun isAvailable(): Boolean {
        return checkRootAvailable()
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        // Root 权限需要通过 su 授权弹窗获取
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo ok"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            reader.close()
            val exitCode = process.waitFor()
            val granted = exitCode == 0 && line?.trim() == "ok"
            rootAvailable = granted
            onResult(granted)
        } catch (e: Exception) {
            Log.w(TAG, "Root 权限请求失败", e)
            rootAvailable = false
            onResult(false)
        }
    }

    override fun hasPermission(): ShellExecutor.PermissionStatus {
        val available = checkRootAvailable()
        return if (available) {
            ShellExecutor.PermissionStatus(
                granted = true,
                reason = "Root 权限已获取"
            )
        } else {
            ShellExecutor.PermissionStatus(
                granted = false,
                reason = "设备未 Root 或 su 授权被拒绝"
            )
        }
    }

    override fun initialize() {
        if (initialized) return
        initialized = true
        val available = checkRootAvailable()
        Log.i(TAG, "RootShellExecutor 初始化完成, Root 可用: $available")
    }

    override suspend fun startProcess(command: String): ShellProcess =
        withContext(Dispatchers.IO) {
            if (!checkRootAvailable()) {
                throw IllegalStateException("设备未 Root 或 su 不可用")
            }

            val process = createRootProcess(command)
            val processId = processIdCounter.incrementAndGet()
            activeProcesses[processId] = process

            RootShellProcess(process, processId) {
                activeProcesses.remove(processId)
            }
        }

    //endregion

    //region 内部方法

    /**
     * 创建 Root Shell 进程。
     *
     * 使用 `ProcessBuilder("su", "-c", command)` 并设置
     * `redirectErrorStream(true)` 合并 stdout/stderr。
     *
     * @param command 要以 Root 权限执行的命令
     * @return 启动的 Process 实例
     */
    private fun createRootProcess(command: String): Process {
        return ProcessBuilder("su", "-c", command)
            .apply {
                redirectErrorStream(true)
            }
            .start()
    }

    /**
     * 检测 Root 可用性。
     *
     * 通过执行 `which su` 检测 su 二进制是否存在。
     * 结果会被缓存，避免重复检测。
     *
     * @return true 表示 Root 可用
     */
    private fun checkRootAvailable(): Boolean {
        // 使用缓存结果
        rootAvailable?.let { return it }

        try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            reader.close()
            process.destroy()
            val available = line != null && line.trim().isNotEmpty()
            rootAvailable = available
            return available
        } catch (e: Exception) {
            Log.w(TAG, "Root 可用性检测失败", e)
            rootAvailable = false
            return false
        }
    }

    /**
     * 销毁指定 ID 的进程。
     */
    private fun destroyProcess(processId: Long) {
        activeProcesses.remove(processId)?.let { process ->
            try {
                process.destroyForcibly()
            } catch (e: Exception) {
                Log.w(TAG, "销毁 Root 进程失败: $processId", e)
            }
        }
    }

    /**
     * 销毁所有活动进程。
     */
    fun destroyAll() {
        val ids = activeProcesses.keys.toList()
        ids.forEach { id -> destroyProcess(id) }
    }

    /**
     * 重置 Root 可用性缓存。
     *
     * 当用户可能在系统设置中授予/撤销了 Root 权限时，
     * 调用此方法强制重新检测。
     */
    fun resetAvailabilityCache() {
        rootAvailable = null
    }

    //endregion

    //region ShellProcess 实现

    /**
     * Root Shell 进程实现。
     *
     * 与 StandardShellProcess 结构一致，但运行在 Root 权限下。
     *
     * @property process    底层 Java Process
     * @property processId  进程唯一 ID
     * @property onCleanup  进程结束后的清理回调
     */
    private class RootShellProcess(
        private val process: Process,
        private val processId: Long,
        private val onCleanup: () -> Unit
    ) : ShellProcess {

        private val _stdout = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
        private val _stderr = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)

        override val stdout: Flow<String> = _stdout.asSharedFlow()
        override val stderr: Flow<String> = _stderr.asSharedFlow()

        @Volatile
        private var _isAlive = true

        @Volatile
        private var _exitCode: Int? = null

        private val readThread = Thread({
            try {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    _stdout.tryEmit(line)
                }
                reader.close()
            } catch (e: Exception) {
                Log.w(TAG, "Root stdout 读取异常: $processId", e)
            }
        }, "root-stdout-$processId")

        private val errorReadThread = Thread({
            try {
                val reader = BufferedReader(InputStreamReader(process.errorStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    _stderr.tryEmit(line)
                }
                reader.close()
            } catch (e: Exception) {
                Log.w(TAG, "Root stderr 读取异常: $processId", e)
            }
        }, "root-stderr-$processId")

        private val waitThread = Thread({
            try {
                val code = process.waitFor()
                _exitCode = code
                _isAlive = false
                onCleanup()
            } catch (e: Exception) {
                Log.w(TAG, "Root 进程等待异常: $processId", e)
                _isAlive = false
                onCleanup()
            }
        }, "root-wait-$processId")

        init {
            readThread.start()
            errorReadThread.start()
            waitThread.start()
        }

        override val isAlive: Boolean
            get() = _isAlive

        override val exitCode: Int?
            get() = _exitCode

        override fun destroy() {
            try {
                process.destroyForcibly()
            } catch (e: Exception) {
                Log.w(TAG, "销毁 Root 进程失败: $processId", e)
            }
            _isAlive = false
            onCleanup()
        }
    }

    //endregion
}
