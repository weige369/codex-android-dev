package com.codex.android.shell

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 标准 Shell 执行器。
 *
 * 通过 Android 系统 `sh -c` 执行命令，运行在应用进程的标准权限下。
 * 这是最基础的执行器，始终可用，无需特殊权限。
 *
 * 特性：
 * - 使用 `ProcessBuilder("sh", "-c", command)` 创建进程
 * - `redirectErrorStream(true)` 合并 stdout 和 stderr，避免管道缓冲区死锁
 * - 默认 120 秒超时管理
 * - 通过 [ConcurrentHashMap] 追踪所有活动进程
 * - 支持 [ShellExecutor.startProcess] 的流式输出模式
 */
class StandardShellExecutor : ShellExecutor {

    companion object {
        private const val TAG = "StandardShellExecutor"

        /** 默认命令执行超时时间（毫秒）。 */
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }

    /**
     * 活动进程追踪表。
     *
     * Key 为进程唯一 ID，Value 为 Process 实例。
     * 使用 ConcurrentHashMap 保证线程安全。
     */
    private val activeProcesses = ConcurrentHashMap<Long, Process>()

    /** 进程 ID 生成器。 */
    private val processIdCounter = AtomicLong(0)

    /** 是否已初始化。 */
    @Volatile
    private var initialized = false

    //region ShellExecutor 接口实现

    override suspend fun executeCommand(command: String): ShellExecutor.CommandResult =
        withContext(Dispatchers.IO) {
            try {
                val process = createProcess(command)
                val processId = processIdCounter.incrementAndGet()
                activeProcesses[processId] = process

                // redirectErrorStream(true) 已合并输出，只需读 inputStream
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
                        stderr = "命令执行超时（${DEFAULT_TIMEOUT_MS}ms）",
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
                Log.e(TAG, "执行命令失败: $command", e)
                ShellExecutor.CommandResult(
                    success = false,
                    stdout = "",
                    stderr = "执行失败: ${e.message}",
                    exitCode = -1
                )
            }
        }

    override fun getPermissionLevel(): AndroidPermissionLevel {
        return AndroidPermissionLevel.STANDARD
    }

    override fun isAvailable(): Boolean {
        // 标准 Shell 始终可用
        return true
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        // 标准 Shell 无需额外权限，直接返回成功
        onResult(true)
    }

    override fun hasPermission(): ShellExecutor.PermissionStatus {
        return ShellExecutor.PermissionStatus(
            granted = true,
            reason = "标准 Shell 无需额外权限"
        )
    }

    override fun initialize() {
        if (initialized) return
        initialized = true
        Log.i(TAG, "StandardShellExecutor 初始化完成")
    }

    override suspend fun startProcess(command: String): ShellProcess =
        withContext(Dispatchers.IO) {
            val process = createProcess(command)
            val processId = processIdCounter.incrementAndGet()
            activeProcesses[processId] = process

            StandardShellProcess(process, processId) {
                activeProcesses.remove(processId)
            }
        }

    //endregion

    //region 内部方法

    /**
     * 创建 Shell 进程。
     *
     * 使用 `ProcessBuilder("sh", "-c", command)` 并设置
     * `redirectErrorStream(true)` 合并 stdout/stderr。
     *
     * @param command 要执行的命令
     * @return 启动的 Process 实例
     */
    protected open fun createProcess(command: String): Process {
        return ProcessBuilder("sh", "-c", command)
            .apply {
                redirectErrorStream(true)
            }
            .start()
    }

    /**
     * 销毁指定 ID 的进程。
     *
     * @param processId 进程 ID
     */
    private fun destroyProcess(processId: Long) {
        activeProcesses.remove(processId)?.let { process ->
            try {
                process.destroyForcibly()
            } catch (e: Exception) {
                Log.w(TAG, "销毁进程失败: $processId", e)
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
     * 获取当前活动进程数量。
     */
    fun activeProcessCount(): Int = activeProcesses.size

    //endregion

    //region ShellProcess 实现

    /**
     * 标准 Shell 进程实现。
     *
     * 封装 Java Process，通过 SharedFlow 提供 stdout/stderr 的
     * 实时流式输出。
     *
     * @property process    底层 Java Process
     * @property processId  进程唯一 ID
     * @property onCleanup  进程结束后的清理回调
     */
    private class StandardShellProcess(
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
                Log.w(TAG, "stdout 读取异常: $processId", e)
            }
        }, "shell-stdout-$processId")

        private val errorReadThread = Thread({
            try {
                // 由于 redirectErrorStream(true)，stderr 通常为空
                val reader = BufferedReader(InputStreamReader(process.errorStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    _stderr.tryEmit(line)
                }
                reader.close()
            } catch (e: Exception) {
                Log.w(TAG, "stderr 读取异常: $processId", e)
            }
        }, "shell-stderr-$processId")

        private val waitThread = Thread({
            try {
                val code = process.waitFor()
                _exitCode = code
                _isAlive = false
                onCleanup()
            } catch (e: Exception) {
                Log.w(TAG, "进程等待异常: $processId", e)
                _isAlive = false
                onCleanup()
            }
        }, "shell-wait-$processId")

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
                Log.w(TAG, "销毁进程失败: $processId", e)
            }
            _isAlive = false
            onCleanup()
        }
    }

    //endregion
}
