package com.codex.android.shell

import android.util.Log
import com.codex.android.util.LinuxEnvironment
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
 * Proot Shell 执行器。
 *
 * 整合 [LinuxEnvironment] 的 proot 引擎，在自包含的 Linux 环境中
 * 执行 Shell 命令。通过 proot 的文件系统虚拟化，提供比标准 Shell
 * 更完整的 Linux 工具链（bash、apt、python 等）。
 *
 * 特性：
 * - 基于 LinuxEnvironment.buildProotCommand() 构建 proot 命令
 * - 自动检测 proot/rootfs 就绪状态
 * - 继承标准执行器的超时管理和进程追踪机制
 * - 无需 Root 权限即可运行 Linux 程序
 *
 * 注意：proot 通过 ptrace 实现系统调用拦截，性能略低于原生执行。
 * 适用于需要完整 Linux 环境的场景（如运行 Python 脚本、使用 apt 包管理器）。
 *
 * @property linuxEnv Linux 环境管理器实例
 */
class ProotShellExecutor(
    private val linuxEnv: LinuxEnvironment
) : ShellExecutor {

    companion object {
        private const val TAG = "ProotShellExecutor"

        /** 默认命令执行超时时间（毫秒）。 */
        const val DEFAULT_TIMEOUT_MS = 120_000L
    }

    /**
     * 活动进程追踪表。
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
            // 检查 proot 环境是否就绪
            val info = linuxEnv.getInfo()
            if (info.state != LinuxEnvironment.EngineState.READY) {
                return@withContext ShellExecutor.CommandResult(
                    success = false,
                    stdout = "",
                    stderr = "proot Linux 环境未就绪: ${info.errorMessage}",
                    exitCode = -1
                )
            }

            try {
                val process = createProotProcess(command)
                val processId = processIdCounter.incrementAndGet()
                activeProcesses[processId] = process

                // 读取 stdout 和 stderr
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()

                val finished = process.waitFor(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

                if (!finished) {
                    destroyProcess(processId)
                    ShellExecutor.CommandResult(
                        success = false,
                        stdout = stdout,
                        stderr = "$stderr\n命令执行超时（${DEFAULT_TIMEOUT_MS}ms）",
                        exitCode = -1
                    )
                } else {
                    activeProcesses.remove(processId)
                    val exitCode = process.exitValue()
                    ShellExecutor.CommandResult(
                        success = exitCode == 0,
                        stdout = stdout,
                        stderr = stderr,
                        exitCode = exitCode
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "proot 执行命令失败: $command", e)
                ShellExecutor.CommandResult(
                    success = false,
                    stdout = "",
                    stderr = "执行失败: ${e.message}",
                    exitCode = -1
                )
            }
        }

    override fun getPermissionLevel(): AndroidPermissionLevel {
        // proot 虚拟了 root 环境，但实际仍是应用权限
        return AndroidPermissionLevel.STANDARD
    }

    override fun isAvailable(): Boolean {
        return linuxEnv.getInfo().state == LinuxEnvironment.EngineState.READY
    }

    override fun requestPermission(onResult: (Boolean) -> Unit) {
        // proot 无需额外权限，但需要 rootfs 已安装
        onResult(isAvailable())
    }

    override fun hasPermission(): ShellExecutor.PermissionStatus {
        val info = linuxEnv.getInfo()
        return if (info.state == LinuxEnvironment.EngineState.READY) {
            ShellExecutor.PermissionStatus(
                granted = true,
                reason = "proot Linux 环境已就绪"
            )
        } else {
            ShellExecutor.PermissionStatus(
                granted = false,
                reason = "proot Linux 环境未就绪: ${info.errorMessage}"
            )
        }
    }

    override fun initialize() {
        if (initialized) return
        initialized = true
        Log.i(TAG, "ProotShellExecutor 初始化完成, 可用: ${isAvailable()}")
    }

    override suspend fun startProcess(command: String): ShellProcess =
        withContext(Dispatchers.IO) {
            val info = linuxEnv.getInfo()
            if (info.state != LinuxEnvironment.EngineState.READY) {
                throw IllegalStateException("proot Linux 环境未就绪: ${info.errorMessage}")
            }

            val process = createProotProcess(command)
            val processId = processIdCounter.incrementAndGet()
            activeProcesses[processId] = process

            ProotShellProcess(process, processId) {
                activeProcesses.remove(processId)
            }
        }

    //endregion

    //region 内部方法

    /**
     * 创建 proot 进程。
     *
     * 通过 [LinuxEnvironment.buildProotCommand] 构建完整的 proot 命令行，
     * 并注入 proot 运行所需的环境变量。
     *
     * @param command 要在 proot 内执行的命令
     * @return 启动的 Process 实例
     */
    private fun createProotProcess(command: String): Process {
        val cmd = linuxEnv.buildProotCommand(command)
        val env = linuxEnv.getProotEnv()

        val pb = ProcessBuilder(cmd)
        env.forEach { (k, v) -> pb.environment()[k] = v }
        // proot 不使用 redirectErrorStream，保留独立的 stdout/stderr
        pb.redirectErrorStream(false)

        return pb.start()
    }

    /**
     * 销毁指定 ID 的进程。
     */
    private fun destroyProcess(processId: Long) {
        activeProcesses.remove(processId)?.let { process ->
            try {
                process.destroyForcibly()
            } catch (e: Exception) {
                Log.w(TAG, "销毁 proot 进程失败: $processId", e)
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

    //endregion

    //region ShellProcess 实现

    /**
     * Proot Shell 进程实现。
     *
     * 与 [StandardShellExecutor.StandardShellProcess] 类似，
     * 但不使用 redirectErrorStream，保留独立的 stdout 和 stderr 通道。
     *
     * @property process    底层 Java Process
     * @property processId  进程唯一 ID
     * @property onCleanup  进程结束后的清理回调
     */
    private class ProotShellProcess(
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

        private val stdoutThread = Thread({
            try {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    _stdout.tryEmit(line)
                }
                reader.close()
            } catch (e: Exception) {
                Log.w(TAG, "proot stdout 读取异常: $processId", e)
            }
        }, "proot-stdout-$processId")

        private val stderrThread = Thread({
            try {
                val reader = BufferedReader(InputStreamReader(process.errorStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    _stderr.tryEmit(line)
                }
                reader.close()
            } catch (e: Exception) {
                Log.w(TAG, "proot stderr 读取异常: $processId", e)
            }
        }, "proot-stderr-$processId")

        private val waitThread = Thread({
            try {
                val code = process.waitFor()
                _exitCode = code
                _isAlive = false
                onCleanup()
            } catch (e: Exception) {
                Log.w(TAG, "proot 进程等待异常: $processId", e)
                _isAlive = false
                onCleanup()
            }
        }, "proot-wait-$processId")

        init {
            stdoutThread.start()
            stderrThread.start()
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
                Log.w(TAG, "销毁 proot 进程失败: $processId", e)
            }
            _isAlive = false
            onCleanup()
        }
    }

    //endregion
}
