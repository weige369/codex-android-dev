package com.codex.android.agent

import android.content.Context
import android.util.Log
import com.codex.android.util.LinuxEnvironment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Proot Agent 服务适配器 — 一次性执行模式。
 *
 * 核心设计：每次 sendPromptStream() 调用启动一个新的 Agent 进程，
 * 进程执行完毕后自动退出，stdout 实时推送给 UI。
 *
 * 这是因为 Codex CLI / OpenCode 都是 TUI 优先的 CLI 工具，
 * 不支持真正的交互式 stdin/stdout 会话。
 * 正确用法是 one-shot 执行：
 * - Codex CLI: `codex --full-auto "prompt" < /dev/null`
 * - OpenCode:  `opencode run --format json "prompt"`
 * - OpenManus: `python3 -m openmanus "prompt"`
 *
 * 架构：
 * ┌──────────────┐  sendPromptStream()  ┌──────────────────┐
 * │ NativeChatView│ ──onChunk/onComplete→│ ProotAgentService │
 * │              │                       │ (one-shot 适配器) │
 * └──────────────┘                       └───────┬──────────┘
 *                                                │ 每次启动新进程
 *                                        ┌───────┴──────────┐
 *                                        │ AgentProcessManager│
 *                                        │ (proot 进程管理)   │
 *                                        └──────────────────┘
 */
class ProotAgentService(private val context: Context) : ChatAgent {

    companion object {
        private const val TAG = "ProotAgentService"
        private const val STREAM_TIMEOUT_MS = 300_000L  // 5 分钟总超时

        @Volatile
        private var INSTANCE: ProotAgentService? = null

        fun getInstance(ctx: Context): ProotAgentService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProotAgentService(ctx.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val processManager = AgentProcessManager(context)
    private val binaryManager = AgentBinaryManager(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ===== 连接状态 =====

    private val _connectionState = MutableStateFlow(AgentConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<AgentConnectionState> = _connectionState.asStateFlow()

    // ===== 当前 Agent 类型 =====
    private var currentAgentType: AgentOrchestrator.AgentType = AgentOrchestrator.AgentType.CODEX

    // ===== 当前执行 Job =====
    private var currentExecutionJob: Job? = null

    // ===== 公开 API =====

    fun selectAgent(type: AgentOrchestrator.AgentType) {
        currentAgentType = type
    }

    fun getSelectedAgent(): AgentOrchestrator.AgentType = currentAgentType

    fun isAgentInstalled(type: AgentOrchestrator.AgentType): Boolean {
        return binaryManager.isInstalled(type)
    }

    suspend fun installAgent(
        type: AgentOrchestrator.AgentType,
        onProgress: (AgentBinaryManager.InstallProgress) -> Unit
    ): Boolean {
        return binaryManager.install(type, onProgress)
    }

    fun getAllAgentStatuses() = binaryManager.getAllStatuses()

    fun isProotReady(): Boolean {
        val linuxEnv = LinuxEnvironment(context)
        return linuxEnv.isInstalled() && linuxEnv.getInfo().state == LinuxEnvironment.EngineState.READY
    }

    /**
     * 检查 proot 环境和 Agent 安装状态，返回是否"就绪"。
     * one-shot 模式下不需要 connect()，但需要确认前置条件。
     */
    suspend fun connect(): Boolean {
        if (_connectionState.value == AgentConnectionState.CONNECTED ||
            _connectionState.value == AgentConnectionState.STREAMING
        ) {
            return true
        }

        _connectionState.value = AgentConnectionState.CONNECTING

        if (!isProotReady()) {
            _connectionState.value = AgentConnectionState.ERROR
            Log.e(TAG, "proot 环境未就绪")
            return false
        }

        if (!binaryManager.isInstalled(currentAgentType)) {
            _connectionState.value = AgentConnectionState.ERROR
            Log.e(TAG, "${currentAgentType.displayName} 未安装")
            return false
        }

        // one-shot 模式：不需要启动长驻进程，直接标记为已连接
        _connectionState.value = AgentConnectionState.CONNECTED
        Log.i(TAG, "${currentAgentType.displayName} 就绪 (one-shot 模式)")
        return true
    }

    /**
     * 发送 prompt 并处理流式响应 — one-shot 执行模式。
     *
     * 每次 prompt 启动一个新的 Agent 进程，进程执行完毕自动退出。
     * stdout 实时推送给 onChunk，进程退出时调用 onComplete。
     */
    override fun sendPromptStream(
        prompt: String,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        // 取消上一次执行
        currentExecutionJob?.cancel()

        currentExecutionJob = scope.launch {
            // 前置检查
            if (!isProotReady()) {
                onError("proot 环境未就绪，请先安装 Linux 环境")
                return@launch
            }

            if (!binaryManager.isInstalled(currentAgentType)) {
                onError("${currentAgentType.displayName} 未安装，请在设置中安装")
                return@launch
            }

            val apiKey = getApiKey()
            if (apiKey.isBlank()) {
                onError("API Key 未配置，请先在设置中填写 API Key")
                return@launch
            }

            _connectionState.value = AgentConnectionState.STREAMING

            // 构建 one-shot 命令
            val command = buildOneShotCommand(currentAgentType, prompt)
            val env = buildEnv(currentAgentType)

            Log.i(TAG, "启动 ${currentAgentType.displayName}: ${command.take(120)}")

            // 停止之前可能残留的进程
            processManager.stop()

            // 启动新进程
            val launched = processManager.launch(
                agentType = currentAgentType,
                command = command,
                workingDir = "/root",
                env = env
            )

            if (!launched) {
                _connectionState.value = AgentConnectionState.ERROR
                onError("启动 ${currentAgentType.displayName} 失败，请检查 proot 环境")
                return@launch
            }

            // 收集输出
            val responseBuffer = StringBuilder()
            var processExited = false

            // 监听 stdout
            val stdoutJob = launch {
                processManager.stdout.collect { line ->
                    if (isActive && !processExited) {
                        // 过滤 ANSI 转义码 + 解析 Agent 输出格式
                        val cleaned = stripAnsiEscape(line)
                        val parsed = parseAgentOutput(cleaned, currentAgentType)
                        if (parsed != null) {
                            responseBuffer.appendLine(parsed)
                            onChunk(parsed + "\n")
                        }
                    }
                }
            }

            // 监听进程退出
            val exitJob = launch {
                processManager.processState.collect { state ->
                    when (state) {
                        AgentProcessManager.ProcessState.STOPPED,
                        AgentProcessManager.ProcessState.CRASHED -> {
                            if (!processExited) {
                                processExited = true
                                stdoutJob.cancel()

                                val response = responseBuffer.toString().trim()
                                val exitInfo = processManager.processInfo.value
                                val exitCode = exitInfo?.exitCode

                                if (state == AgentProcessManager.ProcessState.CRASHED && response.isEmpty()) {
                                    _connectionState.value = AgentConnectionState.ERROR
                                    onError("${currentAgentType.displayName} 异常退出 (code=$exitCode)")
                                } else {
                                    _connectionState.value = AgentConnectionState.CONNECTED
                                    if (response.isNotEmpty()) {
                                        onComplete(response)
                                    } else {
                                        // 进程正常退出但无输出 — 可能是帮助信息或空响应
                                        onComplete("(Agent 退出，code=$exitCode，无输出)")
                                    }
                                }
                                Log.i(TAG, "${currentAgentType.displayName} 执行完成, code=$exitCode, ${response.length} 字符")
                            }
                        }
                        else -> {}
                    }
                }
            }

            // 超时保护
            delay(STREAM_TIMEOUT_MS)
            if (!processExited) {
                processExited = true
                stdoutJob.cancel()
                exitJob.cancel()
                processManager.stop()

                val response = responseBuffer.toString().trim()
                if (response.isNotEmpty()) {
                    _connectionState.value = AgentConnectionState.CONNECTED
                    onComplete(response)
                } else {
                    _connectionState.value = AgentConnectionState.ERROR
                    onError("${currentAgentType.displayName} 执行超时 (${STREAM_TIMEOUT_MS / 1000}s)")
                }
            }
        }
    }

    fun disconnect() {
        currentExecutionJob?.cancel()
        currentExecutionJob = null
        processManager.stop()
        _connectionState.value = AgentConnectionState.DISCONNECTED
        Log.i(TAG, "Agent 已断开")
    }

    // ===== 命令构建 =====

    /**
     * 构建 one-shot 执行命令。
     *
     * 关键：每个 CLI 工具的非交互模式不同：
     * - Codex CLI: `codex --full-auto "prompt" < /dev/null`
     *   (--full-auto 跳过审批, < /dev/null 防止 stdin 阻塞)
     * - OpenCode: `opencode run --format json "prompt"`
     *   (run 子命令是非交互模式, --format json 输出 NDJSON)
     * - OpenManus: `python3 -m openmanus "prompt"` (取决于其 CLI 设计)
     */
    private fun buildOneShotCommand(type: AgentOrchestrator.AgentType, prompt: String): String {
        // Shell 转义 prompt 中的特殊字符
        val escapedPrompt = prompt
            .replace("\\", "\\\\")
            .replace("'", "'\\''")
            .replace("\"", "\\\"")
            .replace("\n", " ")
            .take(2000)  // 限制长度，防止命令行过长

        return when (type) {
            AgentOrchestrator.AgentType.CODEX -> {
                val model = getModel()
                // codex --full-auto: 全自动模式，跳过审批
                // < /dev/null: 防止 codex 阻塞在 stdin 读取
                buildString {
                    append("codex --full-auto")
                    append(" --model '").append(model).append("'")
                    append(" '").append(escapedPrompt).append("'")
                    append(" < /dev/null")
                }
            }
            AgentOrchestrator.AgentType.OPENCODE -> {
                val model = getModel()
                // opencode run: 非交互模式
                // --format json: 输出 NDJSON 事件流
                buildString {
                    append("opencode run")
                    append(" --format json")
                    append(" -m '").append(model).append("'")
                    append(" '").append(escapedPrompt).append("'")
                }
            }
            AgentOrchestrator.AgentType.OPENMANUS -> {
                buildString {
                    append("cd /root/OpenManus && ")
                    append("python3 -m openmanus")
                    append(" '").append(escapedPrompt).append("'")
                }
            }
            AgentOrchestrator.AgentType.NATIVE -> {
                "echo 'Native agent does not use proot'"
            }
        }
    }

    /**
     * 构建环境变量。
     */
    private fun buildEnv(type: AgentOrchestrator.AgentType): Map<String, String> {
        val env = mutableMapOf<String, String>()
        val apiKey = getApiKey()
        val apiUrl = getApiUrl()

        when (type) {
            AgentOrchestrator.AgentType.CODEX -> {
                env["OPENAI_API_KEY"] = apiKey
                if (apiUrl.isNotBlank()) env["OPENAI_BASE_URL"] = apiUrl
                // 禁止 codex 尝试打开浏览器 OAuth
                env["CODEX_DISABLE_BROWSER"] = "1"
            }
            AgentOrchestrator.AgentType.OPENCODE -> {
                env["OPENAI_API_KEY"] = apiKey
                if (apiUrl.isNotBlank()) env["OPENAI_BASE_URL"] = apiUrl
            }
            AgentOrchestrator.AgentType.OPENMANUS -> {
                env["OPENAI_API_KEY"] = apiKey
                if (apiUrl.isNotBlank()) env["OPENAI_API_BASE"] = apiUrl
            }
            AgentOrchestrator.AgentType.NATIVE -> {}
        }

        return env
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

    // ===== 工具方法 =====

    /**
     * 去除 ANSI 转义序列。
     * TUI 工具（如 codex）可能在非 TTY 环境输出残留转义码。
     */
    private fun stripAnsiEscape(input: String): String {
        return input.replace(Regex("\u001B\\[[;\\d]*[ -/]*[@-~]"), "")
    }

    /**
     * 解析 Agent stdout 行。
     *
     * - OpenCode --format json 输出 NDJSON 事件流，需要提取 text 内容
     * - Codex CLI 输出纯文本，直接透传
     * - OpenManus 输出待确认
     */
    private fun parseAgentOutput(line: String, agentType: AgentOrchestrator.AgentType): String? {
        return when (agentType) {
            AgentOrchestrator.AgentType.OPENCODE -> {
                // OpenCode NDJSON: {"type":"assistant_content","delta":{"text":"..."},...}
                if (line.trimStart().startsWith("{")) {
                    try {
                        val json = org.json.JSONObject(line)
                        when (json.optString("type")) {
                            "assistant_content" -> {
                                val delta = json.optJSONObject("delta")
                                delta?.optString("text", null)
                            }
                            "tool_call" -> {
                                val name = json.optJSONObject("function")?.optString("name", "") ?: ""
                                "🔧 $name"
                            }
                            "tool_result" -> {
                                val content = json.optString("content", "")
                                if (content.isNotBlank()) content.take(200) else null
                            }
                            "error" -> {
                                val msg = json.optString("message", json.optString("error", ""))
                                if (msg.isNotBlank()) "❌ $msg" else null
                            }
                            else -> null  // 忽略其他事件类型
                        }
                    } catch (_: Exception) {
                        // 非 JSON 行，直接透传
                        line
                    }
                } else {
                    line.ifBlank { null }
                }
            }
            else -> {
                // Codex CLI / OpenManus: 纯文本输出
                line.ifBlank { null }
            }
        }
    }

    override fun cancelStream() {
        currentExecutionJob?.cancel()
        currentExecutionJob = null
        processManager.stop()
        _connectionState.value = AgentConnectionState.CONNECTED
        Log.i(TAG, "Stream cancelled, process stopped")
    }

    override fun isConfigured(): Boolean = getApiKey().isNotBlank() && getApiUrl().isNotBlank()
}
