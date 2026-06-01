package com.codex.android.agent

import android.content.Context
import android.util.Log
import com.codex.android.util.LinuxEnvironment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.BufferOverflow
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Agent 进程管理器。
 *
 * 核心能力：通过 proot 启动长时间运行的 Agent 进程，
 * 提供流式 I/O 桥接（stdin/stdout/stderr ↔ SharedFlow）。
 *
 * 与 LinuxEnvironment.runCommand 的区别：
 * - runCommand：阻塞式，等进程全部输出才返回（适合 apt-get 等短命令）
 * - AgentProcessManager：非阻塞式，启动进程后立即返回，
 *   通过 SharedFlow 实时推送 stdout/stderr（适合 Codex/OpenCode/OpenManus 长运行进程）
 *
 * 架构：
 * ┌───────────────┐     SharedFlow<String>     ┌──────────────┐
 * │ Chat UI       │  ←──────────────────────── │ AgentProcess │
 * │ (collect)     │                             │ Manager      │
 * │               │  ─────────────────────────→ │              │
 * │ (sendInput)   │     fun sendInput()         │              │
 * └───────────────┘                             └──────┬───────┘
 *                                                      │ ProcessBuilder
 *                                                      │ (proot ...)
 *                                               ┌──────┴───────┐
 *                                               │ Agent Process │
 *                                               │ (proot 内)    │
 *                                               └──────────────┘
 */
class AgentProcessManager(private val context: Context) {

    companion object {
        private const val TAG = "AgentProcessManager"
        private const val STDOUT_BUFFER_SIZE = 8192
        private const val PATH_STANDARD =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    }

    // ===== 进程状态 =====

    enum class ProcessState {
        IDLE,           // 未启动
        STARTING,       // 正在启动
        RUNNING,        // 运行中
        STOPPING,       // 正在停止
        STOPPED,        // 已停止
        CRASHED         // 异常退出
    }

    data class AgentProcessInfo(
        val agentType: AgentOrchestrator.AgentType,
        val pid: Int = -1,
        val state: ProcessState = ProcessState.IDLE,
        val exitCode: Int? = null,
        val workingDir: String = "",
        val startedAt: Long = 0L
    )

    // ===== 流式 I/O =====

    private val _stdout = MutableSharedFlow<String>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val stdout: SharedFlow<String> = _stdout.asSharedFlow()

    private val _stderr = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val stderr: SharedFlow<String> = _stderr.asSharedFlow()

    private val _processState = MutableStateFlow(ProcessState.IDLE)
    val processState: StateFlow<ProcessState> = _processState.asStateFlow()

    private val _processInfo = MutableStateFlow<AgentProcessInfo?>(null)
    val processInfo: StateFlow<AgentProcessInfo?> = _processInfo.asStateFlow()

    // ===== 内部状态 =====

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentProcess: Process? = null
    private var monitorJobs: List<Job> = emptyList()

    // ===== 进程启动 =====

    /**
     * 通过 proot 启动 Agent 进程。
     *
     * @param agentType Agent 类型
     * @param command 要在 proot 内执行的命令（如 "codex --model deepseek/deepseek-chat"）
     * @param workingDir 工作目录（proot 内路径，如 /root/workspace）
     * @param env 额外环境变量（如 OPENAI_API_KEY）
     * @return 是否启动成功
     */
    suspend fun launch(
        agentType: AgentOrchestrator.AgentType,
        command: String,
        workingDir: String = "/root",
        env: Map<String, String> = emptyMap()
    ): Boolean = withContext(Dispatchers.IO) {
        // 先停止已有进程
        if (currentProcess != null) {
            stop()
        }

        _processState.value = ProcessState.STARTING
        _processInfo.value = AgentProcessInfo(
            agentType = agentType,
            state = ProcessState.STARTING,
            workingDir = workingDir
        )

        try {
            val linuxEnv = LinuxEnvironment(context)
            val linuxInfo = linuxEnv.getInfo()

            if (linuxInfo.state != LinuxEnvironment.EngineState.READY) {
                Log.e(TAG, "Linux 环境未就绪: ${linuxInfo.errorMessage}")
                _processState.value = ProcessState.CRASHED
                _processInfo.value = _processInfo.value?.copy(state = ProcessState.CRASHED)
                return@withContext false
            }

            // 构建 proot 命令
            val prootCmd = buildProotAgentCommand(linuxEnv, command, workingDir, env)
            val prootEnv = linuxEnv.getProotEnv()

            Log.i(TAG, "启动 Agent ${agentType.displayName}: ${prootCmd.take(200)}")

            val processBuilder = ProcessBuilder(prootCmd)
            prootEnv.forEach { (k, v) -> processBuilder.environment()[k] = v }
            processBuilder.redirectErrorStream(false)

            val process = processBuilder.start()
            currentProcess = process

            _processState.value = ProcessState.RUNNING
            _processInfo.value = AgentProcessInfo(
                agentType = agentType,
                pid = getPid(process),
                state = ProcessState.RUNNING,
                workingDir = workingDir,
                startedAt = System.currentTimeMillis()
            )

            // 启动 I/O 监听
            startStdoutMonitor(process)
            startStderrMonitor(process)
            startExitMonitor(process, agentType)

            Log.i(TAG, "Agent ${agentType.displayName} 已启动 (PID: ${getPid(process)})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动 Agent ${agentType.displayName} 失败", e)
            _processState.value = ProcessState.CRASHED
            _processInfo.value = _processInfo.value?.copy(state = ProcessState.CRASHED)
            false
        }
    }

    /**
     * 构建 proot 启动 Agent 的完整命令。
     *
     * 关键：环境变量通过 /usr/bin/env -i 注入到 proot 内部，
     * 而不是通过 ProcessBuilder.environment()（那个只影响宿主 Android 环境）。
     */
    private fun buildProotAgentCommand(
        linuxEnv: LinuxEnvironment,
        command: String,
        workingDir: String,
        env: Map<String, String>
    ): List<String> {
        val info = linuxEnv.getInfo()
        val rootfs = linuxEnv.getRootfsDir().path

        // Agent 二进制目录：/usr/local/bin（proot 内路径）
        val agentBinDir = "/usr/local/bin"
        val fullPath = "$agentBinDir:$PATH_STANDARD"

        // 构建内部命令：先 cd 到工作目录，设置环境变量，再执行
        val innerScript = buildString {
            append("cd '$workingDir' 2>/dev/null || true; ")
            // 注入环境变量
            env.forEach { (k, v) ->
                append("export $k='$v'; ")
            }
            // 确保 Agent 二进制目录在 PATH 中
            append("export PATH='$fullPath'; ")
            append(command)
        }

        // 宿主绑定：Agent 需要访问工作区目录
        val appDataDir = "/data/data/${context.packageName}"
        val workspaceBind = "$appDataDir:$rootfs$appDataDir"

        return listOf(
            info.prootPath,
            "--link2symlink",
            "--sysvipc",
            "--kernel-release=6.2.1-PRoot-Distro",
            "--rootfs=$rootfs",
            "--root-id",
            "--kill-on-exit",
            "-0",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/system",
            "-b", "/apex",
            "-b", workspaceBind,
            "-b", "/storage",
            "-b", "${context.cacheDir.absolutePath}:${rootfs}${context.cacheDir.absolutePath}",
            "-w", workingDir,
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=$fullPath",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "SHELL=/bin/bash",
            "USER=root",
            "/bin/bash", "-c", innerScript
        )
    }

    // ===== I/O 桥接 =====

    /**
     * 向 Agent 进程发送输入。
     */
    fun sendInput(text: String): Boolean {
        val process = currentProcess ?: return false
        if (_processState.value != ProcessState.RUNNING) return false

        return try {
            val writer = OutputStreamWriter(process.outputStream, Charsets.UTF_8)
            writer.write(text)
            writer.write("\n")
            writer.flush()
            Log.d(TAG, "已发送输入: ${text.take(100)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "发送输入失败", e)
            false
        }
    }

    /**
     * 发送特殊信号（如 Ctrl+C）。
     */
    fun sendSignal(signal: Int): Boolean {
        val process = currentProcess ?: return false
        return try {
            val pid = getPid(process)
            if (pid > 0) {
                android.os.Process.sendSignal(pid, signal)
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "发送信号失败", e)
            false
        }
    }

    // ===== 进程监控 =====

    private fun startStdoutMonitor(process: Process) {
        val job = scope.launch {
            try {
                val reader = BufferedReader(
                    InputStreamReader(process.inputStream, Charsets.UTF_8),
                    STDOUT_BUFFER_SIZE
                )
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    _stdout.emit(line ?: "")
                }
            } catch (e: Exception) {
                if (_processState.value == ProcessState.RUNNING) {
                    Log.e(TAG, "stdout 监听异常", e)
                }
            }
        }
        monitorJobs = monitorJobs + job
    }

    private fun startStderrMonitor(process: Process) {
        val job = scope.launch {
            try {
                val reader = BufferedReader(
                    InputStreamReader(process.errorStream, Charsets.UTF_8),
                    STDOUT_BUFFER_SIZE
                )
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    _stderr.emit(line ?: "")
                }
            } catch (e: Exception) {
                // stderr 异常通常不影响主流程
            }
        }
        monitorJobs = monitorJobs + job
    }

    private fun startExitMonitor(process: Process, agentType: AgentOrchestrator.AgentType) {
        val job = scope.launch {
            try {
                val exitCode = process.waitFor()
                val wasRunning = _processState.value == ProcessState.RUNNING

                _processState.value = if (exitCode == 0) ProcessState.STOPPED else ProcessState.CRASHED
                _processInfo.value = _processInfo.value?.copy(
                    state = if (exitCode == 0) ProcessState.STOPPED else ProcessState.CRASHED,
                    exitCode = exitCode
                )

                if (wasRunning) {
                    Log.i(TAG, "Agent ${agentType.displayName} 退出, code=$exitCode")
                    _stdout.emit("[Agent 退出, code=$exitCode]")
                }
            } catch (e: Exception) {
                Log.e(TAG, "退出监听异常", e)
            }
        }
        monitorJobs = monitorJobs + job
    }

    // ===== 进程控制 =====

    /**
     * 停止当前 Agent 进程。
     */
    fun stop() {
        _processState.value = ProcessState.STOPPING
        monitorJobs.forEach { it.cancel() }
        monitorJobs = emptyList()

        currentProcess?.let { process ->
            try {
                // 先尝试优雅关闭
                process.outputStream.close()
                process.inputStream.close()
                process.errorStream.close()
                process.destroy()

                // 给 2 秒时间优雅退出
                runBlocking {
                    withTimeoutOrNull(2000) {
                        process.waitFor()
                    }
                }

                // 强制杀死
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            } catch (e: Exception) {
                Log.w(TAG, "停止进程异常", e)
            }
        }
        currentProcess = null
        _processState.value = ProcessState.STOPPED
        _processInfo.value = _processInfo.value?.copy(state = ProcessState.STOPPED)
    }

    /**
     * 释放资源。
     */
    fun destroy() {
        stop()
        scope.cancel()
    }

    // ===== 工具方法 =====

    private fun getPid(process: Process): Int {
        return try {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process)
        } catch (_: Exception) {
            -1
        }
    }
}
