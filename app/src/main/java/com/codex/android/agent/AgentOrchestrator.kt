package com.codex.android.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * Agent 编排器。
 *
 * 核心定位：不自己当 Agent，而是管理多个原生 Agent 进程的生命周期。
 * 将 Codex CLI / OpenCode / OpenManus 等 Agent 的 stdin/stdout 桥接到 Android UI。
 *
 * 架构：
 * ┌──────────┐     ┌──────────────────┐     ┌─────────────┐
 * │ Chat UI  │ ←→  │ AgentOrchestrator│ ←→  │ Agent Process│
 * │ Compose  │     │ (进程管理+桥接)   │     │ (proot内)   │
 * └──────────┘     └──────────────────┘     └─────────────┘
 *
 * Agent 进程运行在 proot Linux 环境中，通过 stdin/stdout 交互。
 * Orchestrator 负责：
 * 1. Agent 进程的启动/停止/重启
 * 2. 多 Agent 路由（用户消息 → 正确的 Agent）
 * 3. 权限网关（CapabilityDevice 控制工具权限）
 * 4. I/O 桥接（Agent 输出 → UI 展示，用户输入 → Agent stdin）
 * 5. 会话管理（多轮对话、上下文保持）
 */
class AgentOrchestrator(private val context: Context) {

    companion object {
        private const val TAG = "AgentOrchestrator"
    }

    // ===== Agent 类型定义 =====

    enum class AgentType(val id: String, val displayName: String, val binaryName: String) {
        CODEX("codex", "Codex CLI", "codex"),
        OPENCODE("opencode", "OpenCode", "opencode"),
        OPENMANUS("openmanus", "OpenManus", "python"),
        NATIVE("native", "内置 Agent", "");  // 当前 NativeAgentService 的降级方案
    }

    // ===== Agent 进程状态 =====

    enum class AgentState {
        IDLE,           // 空闲
        STARTING,       // 正在启动
        RUNNING,        // 运行中
        STREAMING,      // 正在流式输出
        WAITING_INPUT,  // 等待用户输入
        ERROR,          // 错误
        STOPPED         // 已停止
    }

    data class AgentProcess(
        val type: AgentType,
        val process: Process?,
        val state: AgentState = AgentState.IDLE,
        val pid: Int = -1,
        val workingDir: String = ""
    )

    // ===== 状态管理 =====

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _activeAgent = MutableStateFlow<AgentType?>(null)
    val activeAgent: StateFlow<AgentType?> = _activeAgent.asStateFlow()

    private val _agentState = MutableStateFlow(AgentState.IDLE)
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    private val _output = MutableStateFlow("")
    val output: StateFlow<String> = _output.asStateFlow()

    private val _agents = mutableMapOf<AgentType, AgentProcess>()

    // ===== 权限网关 =====

    private val capabilityRegistry = CapabilityRegistry(context)
    private val permissionManager = ToolPermissionManager.getInstance(context)

    // ===== Agent 启动 =====

    /**
     * 启动指定类型的 Agent。
     *
     * @param type Agent 类型
     * @param workingDir 工作目录
     * @return 是否启动成功
     */
    suspend fun startAgent(type: AgentType, workingDir: String? = null): Boolean {
        if (_agents[type]?.state == AgentState.RUNNING) {
            Log.w(TAG, "Agent ${type.displayName} 已在运行")
            return true
        }

        _agentState.value = AgentState.STARTING
        _activeAgent.value = type

        // 检查 proot 环境
        val linuxEnv = com.codex.android.util.LinuxEnvironment(context)
        val linuxInfo = linuxEnv.getInfo()
        if (linuxInfo.state != com.codex.android.util.LinuxEnvironment.EngineState.READY) {
            _agentState.value = AgentState.ERROR
            Log.e(TAG, "Linux 环境未就绪，无法启动 ${type.displayName}")
            return false
        }

        return when (type) {
            AgentType.CODEX -> startCodexAgent(workingDir)
            AgentType.OPENCODE -> startOpenCodeAgent(workingDir)
            AgentType.OPENMANUS -> startOpenManusAgent(workingDir)
            AgentType.NATIVE -> {
                // 内置 Agent 不需要启动进程
                _agentState.value = AgentState.RUNNING
                true
            }
        }
    }

    /**
     * 启动 Codex CLI Agent。
     *
     * codex --model <model> --approval-mode <mode>
     * 运行在 proot 中，stdin/stdout 交互。
     */
    private suspend fun startCodexAgent(workingDir: String?): Boolean {
        return try {
            val workDir = workingDir ?: context.filesDir.absolutePath
            val apiKey = getApiKey()
            val model = getModel()

            // 构建命令: proot ... codex --model deepseek/deepseek-chat
            val command = buildString {
                append("codex")
                append(" --model ").append(model)
                append(" --approval-mode full-auto")  // 自动模式（权限由 CapabilityRegistry 控制）
                append(" --quiet")  // 非 TUI 模式
            }

            val process = launchInProot(command, workDir, mapOf(
                "OPENAI_API_KEY" to apiKey,
                "OPENAI_BASE_URL" to getApiUrl()
            ))

            _agents[AgentType.CODEX] = AgentProcess(
                type = AgentType.CODEX,
                process = process,
                state = AgentState.RUNNING,
                pid = getProcessPid(process),
                workingDir = workDir
            )

            // 启动输出监听
            process?.let { startOutputMonitor(AgentType.CODEX, it) }
            _agentState.value = AgentState.RUNNING

            Log.i(TAG, "Codex Agent 已启动 (PID: ${getProcessPid(process)})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动 Codex Agent 失败", e)
            _agentState.value = AgentState.ERROR
            false
        }
    }

    /**
     * 启动 OpenCode Agent。
     *
     * opencode run "prompt" --model provider/model
     * Go 二进制，官方提供 linux-arm64。
     */
    private suspend fun startOpenCodeAgent(workingDir: String?): Boolean {
        return try {
            val workDir = workingDir ?: context.filesDir.absolutePath
            val apiKey = getApiKey()
            val model = getModel()

            // OpenCode 使用 opencode run 子命令
            val command = "opencode --model $model"

            val process = launchInProot(command, workDir, mapOf(
                "OPENAI_API_KEY" to apiKey,
                "OPENAI_BASE_URL" to getApiUrl()
            ))

            _agents[AgentType.OPENCODE] = AgentProcess(
                type = AgentType.OPENCODE,
                process = process,
                state = AgentState.RUNNING,
                pid = getProcessPid(process),
                workingDir = workDir
            )

            process?.let { startOutputMonitor(AgentType.OPENCODE, it) }
            _agentState.value = AgentState.RUNNING

            Log.i(TAG, "OpenCode Agent 已启动 (PID: ${getProcessPid(process)})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动 OpenCode Agent 失败", e)
            _agentState.value = AgentState.ERROR
            false
        }
    }

    /**
     * 启动 OpenManus Agent。
     *
     * python main.py --task "prompt"
     * 需要 Python 3.12 环境。
     */
    private suspend fun startOpenManusAgent(workingDir: String?): Boolean {
        return try {
            val workDir = workingDir ?: context.filesDir.absolutePath

            // 检查 OpenManus 是否已安装
            val manusDir = "${linuxHomeDir()}/OpenManus"
            if (!java.io.File(manusDir).exists()) {
                Log.e(TAG, "OpenManus 未安装，请先运行安装向导")
                _agentState.value = AgentState.ERROR
                return false
            }

            val command = "python main.py"
            val process = launchInProot(command, manusDir, mapOf(
                "OPENAI_API_KEY" to getApiKey()
            ))

            _agents[AgentType.OPENMANUS] = AgentProcess(
                type = AgentType.OPENMANUS,
                process = process,
                state = AgentState.RUNNING,
                pid = getProcessPid(process),
                workingDir = manusDir
            )

            process?.let { startOutputMonitor(AgentType.OPENMANUS, it) }
            _agentState.value = AgentState.RUNNING

            Log.i(TAG, "OpenManus Agent 已启动 (PID: ${getProcessPid(process)})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动 OpenManus Agent 失败", e)
            _agentState.value = AgentState.ERROR
            false
        }
    }

    // ===== I/O 桥接 =====

    /**
     * 向当前活跃的 Agent 发送消息。
     */
    fun sendInput(text: String): Boolean {
        val activeType = _activeAgent.value ?: return false
        val agent = _agents[activeType] ?: return false
        val process = agent.process ?: return false

        return try {
            val writer = OutputStreamWriter(process.outputStream)
            writer.write(text)
            writer.write("\n")
            writer.flush()
            Log.d(TAG, "已发送输入到 ${activeType.displayName}: ${text.take(50)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "发送输入失败", e)
            false
        }
    }

    /**
     * 启动 Agent 输出监听协程。
     * 将 Agent 的 stdout/stderr 实时推送到 output StateFlow。
     */
    private fun startOutputMonitor(type: AgentType, process: Process) {
        scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    _output.value = line ?: ""
                    Log.d(TAG, "[${type.displayName}] $line")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Agent ${type.displayName} 输出监听结束", e)
            }
        }

        // stderr 单独监听
        scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(process.errorStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    Log.w(TAG, "[${type.displayName}:stderr] $line")
                }
            } catch (e: Exception) {
                // 忽略
            }
        }

        // 进程退出监听
        scope.launch {
            val exitCode = process.waitFor()
            Log.i(TAG, "Agent ${type.displayName} 退出，code=$exitCode")
            _agents[type]?.let {
                _agents[type] = it.copy(state = AgentState.STOPPED)
            }
            if (_activeAgent.value == type) {
                _agentState.value = AgentState.STOPPED
            }
        }
    }

    // ===== Agent 管理 =====

    /**
     * 停止指定 Agent。
     */
    fun stopAgent(type: AgentType) {
        val agent = _agents[type] ?: return
        agent.process?.destroyForcibly()
        _agents[type] = agent.copy(state = AgentState.STOPPED)
        if (_activeAgent.value == type) {
            _agentState.value = AgentState.STOPPED
            _activeAgent.value = null
        }
        Log.i(TAG, "Agent ${type.displayName} 已停止")
    }

    /**
     * 停止所有 Agent。
     */
    fun stopAll() {
        _agents.keys.toList().forEach { stopAgent(it) }
    }

    /**
     * 切换活跃 Agent。
     */
    suspend fun switchAgent(type: AgentType): Boolean {
        val currentType = _activeAgent.value
        if (currentType == type) return true

        // 暂停当前 Agent
        currentType?.let { stopAgent(it) }

        // 启动新 Agent
        return startAgent(type)
    }

    /**
     * 获取所有 Agent 的状态。
     */
    fun getAgentStatuses(): Map<AgentType, AgentState> {
        return _agents.mapValues { it.value.state }
    }

    /**
     * 检查 Agent 二进制是否已安装。
     */
    suspend fun isAgentInstalled(type: AgentType): Boolean {
        return when (type) {
            AgentType.CODEX -> checkBinaryInProot("codex")
            AgentType.OPENCODE -> checkBinaryInProot("opencode")
            AgentType.OPENMANUS -> {
                val manusDir = "${linuxHomeDir()}/OpenManus"
                java.io.File(manusDir).exists() && java.io.File(manusDir, "main.py").exists()
            }
            AgentType.NATIVE -> true  // 内置 Agent 始终可用
        }
    }

    // ===== Proot 执行 =====

    /**
     * 在 proot 环境中启动命令。
     */
    private fun launchInProot(command: String, workingDir: String, env: Map<String, String>): Process? {
        val linuxEnv = com.codex.android.util.LinuxEnvironment(context)

        // 构建完整命令: proot ... /bin/bash -c "cd workingDir && command"
        val fullCommand = buildString {
            append("cd ").append(workingDir).append(" && ")
            env.forEach { (k, v) ->
                append("export ").append(k).append("='").append(v).append("' && ")
            }
            append(command)
        }

        // 使用 LinuxEnvironment 运行（需要改造为非阻塞模式）
        return try {
            val processBuilder = ProcessBuilder("sh", "-c", fullCommand)
            processBuilder.redirectErrorStream(false)
            processBuilder.start()
        } catch (e: Exception) {
            Log.e(TAG, "启动 proot 命令失败: $command", e)
            null
        }
    }

    private suspend fun checkBinaryInProot(name: String): Boolean {
        return try {
            val linuxEnv = com.codex.android.util.LinuxEnvironment(context)
            val result = linuxEnv.runCommand("which $name", 5_000)
            result.exitCode == 0 && result.stdout.isNotBlank()
        } catch (e: Exception) {
            false
        }
    }

    // ===== 配置辅助 =====

    private fun getApiKey(): String {
        val prefs = context.getSharedPreferences("codex_agent_prefs", Context.MODE_PRIVATE)
        return prefs.getString("api_key", "") ?: ""
    }

    private fun getApiUrl(): String {
        val prefs = context.getSharedPreferences("codex_agent_prefs", Context.MODE_PRIVATE)
        val customUrl = prefs.getString("custom_url", "") ?: ""
        if (customUrl.isNotBlank()) return customUrl.trimEnd('/')
        val providerId = prefs.getString("provider_id", "deepseek") ?: "deepseek"
        val provider = ApiProvider.getById(providerId) ?: ApiProvider.BUILT_IN.first()
        return provider.baseUrl.trimEnd('/')
    }

    private fun getModel(): String {
        val prefs = context.getSharedPreferences("codex_agent_prefs", Context.MODE_PRIVATE)
        val model = prefs.getString("api_model", "") ?: ""
        if (model.isNotBlank()) return model
        val providerId = prefs.getString("provider_id", "deepseek") ?: "deepseek"
        val provider = ApiProvider.getById(providerId) ?: ApiProvider.BUILT_IN.first()
        return provider.defaultModel
    }

    private fun linuxHomeDir(): String {
        return "${context.filesDir.absolutePath}/linux/home"
    }

    fun destroy() {
        stopAll()
        scope.cancel()
    }

    private fun getProcessPid(process: Process?): Int {
        if (process == null) return -1
        return try {
            val field = process.javaClass.getDeclaredField("pid")
            field.isAccessible = true
            field.getInt(process)
        } catch (_: Exception) {
            -1
        }
    }
}
