package com.codex.android.agent

import android.content.Context
import android.util.Log
import com.codex.android.util.LinuxEnvironment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Proot Agent 服务适配器。
 *
 * 将 AgentProcessManager 的交互式 shell I/O 模型
 * 转换为 NativeChatView 期望的 sendPromptStream 回调模型。
 *
 * 架构：
 * ┌──────────────┐    sendPromptStream()    ┌──────────────────┐
 * │ NativeChatView│  ───onChunk/onComplete──→│ ProotAgentService │
 * │              │                           │ (适配器)          │
 * └──────────────┘                           └───────┬──────────┘
 *                                                     │ stdin/stdout
 *                                             ┌───────┴──────────┐
 *                                             │ AgentProcessManager│
 *                                             │ (proot 进程管理)   │
 *                                             └──────────────────┘
 *
 * 关键设计：
 * - 用户发送 prompt → 写入 agent 进程 stdin
 * - Agent 输出 → 通过 SharedFlow 实时推送给 onChunk
 * - 检测响应结束：空闲超时（agent 输出停止 3 秒）或特定标记
 */
class ProotAgentService(private val context: Context) : ChatAgent {

    companion object {
        private const val TAG = "ProotAgentService"
        private const val RESPONSE_IDLE_TIMEOUT_MS = 3000L  // 3秒无输出视为响应结束
        private const val RESPONSE_IDLE_CHECK_INTERVAL_MS = 500L

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

    // ===== 连接状态（兼容 NativeAgentService 接口）=====

    private val _connectionState = MutableStateFlow(AgentConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<AgentConnectionState> = _connectionState.asStateFlow()

    // ===== 当前 Agent 类型 =====
    private var currentAgentType: AgentOrchestrator.AgentType = AgentOrchestrator.AgentType.CODEX

    // ===== 响应收集状态 =====
    private var isCollectingResponse = false
    private var lastOutputTime = 0L
    private var responseBuffer = StringBuilder()
    private var currentOnChunk: ((String) -> Unit)? = null
    private var currentOnComplete: ((String) -> Unit)? = null
    private var currentOnError: ((String) -> Unit)? = null
    private var idleCheckJob: Job? = null

    // ===== 公开 API =====

    /**
     * 选择 Agent 类型。
     */
    fun selectAgent(type: AgentOrchestrator.AgentType) {
        currentAgentType = type
    }

    /**
     * 获取选中的 Agent 类型。
     */
    fun getSelectedAgent(): AgentOrchestrator.AgentType = currentAgentType

    /**
     * 检查 Agent 是否已安装。
     */
    fun isAgentInstalled(type: AgentOrchestrator.AgentType): Boolean {
        return binaryManager.isInstalled(type)
    }

    /**
     * 安装 Agent。
     */
    suspend fun installAgent(
        type: AgentOrchestrator.AgentType,
        onProgress: (AgentBinaryManager.InstallProgress) -> Unit
    ): Boolean {
        return binaryManager.install(type, onProgress)
    }

    /**
     * 获取所有 Agent 安装状态。
     */
    fun getAllAgentStatuses() = binaryManager.getAllStatuses()

    /**
     * 检查 proot 环境是否就绪。
     */
    fun isProotReady(): Boolean {
        val linuxEnv = LinuxEnvironment(context)
        return linuxEnv.isInstalled() && linuxEnv.getInfo().state == LinuxEnvironment.EngineState.READY
    }

    /**
     * 启动 Agent 进程。
     *
     * 必须在 sendPromptStream 之前调用。
     */
    suspend fun connect(): Boolean {
        if (_connectionState.value == AgentConnectionState.CONNECTED ||
            _connectionState.value == AgentConnectionState.STREAMING
        ) {
            return true
        }

        _connectionState.value = AgentConnectionState.CONNECTING

        // 检查 proot 环境
        if (!isProotReady()) {
            _connectionState.value = AgentConnectionState.ERROR
            Log.e(TAG, "proot 环境未就绪")
            return false
        }

        // 检查 Agent 是否已安装
        if (!binaryManager.isInstalled(currentAgentType)) {
            _connectionState.value = AgentConnectionState.ERROR
            Log.e(TAG, "${currentAgentType.displayName} 未安装")
            return false
        }

        // 构建启动命令
        val command = buildCommand(currentAgentType)
        val env = buildEnv(currentAgentType)

        // 启动进程
        val success = processManager.launch(
            agentType = currentAgentType,
            command = command,
            workingDir = "/root",
            env = env
        )

        if (success) {
            _connectionState.value = AgentConnectionState.CONNECTED
            startOutputCollection()
            Log.i(TAG, "${currentAgentType.displayName} 已连接")
        } else {
            _connectionState.value = AgentConnectionState.ERROR
            Log.e(TAG, "${currentAgentType.displayName} 启动失败")
        }

        return success
    }

    /**
     * 发送 prompt 并处理流式响应。
     *
     * 兼容 NativeAgentService.sendPromptStream 的接口。
     * 流程：写入 agent stdin → 收集 stdout → 空闲超时视为完成
     */
    override fun sendPromptStream(
        prompt: String,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        scope.launch {
            // 确保已连接
            if (_connectionState.value != AgentConnectionState.CONNECTED &&
                _connectionState.value != AgentConnectionState.STREAMING
            ) {
                val connected = connect()
                if (!connected) {
                    onError("无法连接到 ${currentAgentType.displayName}，请检查 proot 环境和 Agent 安装状态")
                    return@launch
                }
            }

            _connectionState.value = AgentConnectionState.STREAMING
            responseBuffer.clear()
            isCollectingResponse = true
            lastOutputTime = System.currentTimeMillis()

            // 保存回调
            currentOnChunk = onChunk
            currentOnComplete = onComplete
            currentOnError = onError

            // 发送 prompt 到 stdin
            val sent = processManager.sendInput(prompt)
            if (!sent) {
                _connectionState.value = AgentConnectionState.ERROR
                onError("发送消息失败，Agent 进程可能已退出")
                cleanupResponse()
                return@launch
            }

            // 启动空闲检测
            startIdleDetection()

            Log.d(TAG, "已发送 prompt: ${prompt.take(50)}...")
        }
    }

    /**
     * 停止当前 Agent 进程。
     */
    fun disconnect() {
        processManager.stop()
        _connectionState.value = AgentConnectionState.DISCONNECTED
        cleanupResponse()
        Log.i(TAG, "Agent 已断开")
    }

    // ===== 内部方法 =====

    /**
     * 构建启动命令。
     */
    private fun buildCommand(type: AgentOrchestrator.AgentType): String {
        return when (type) {
            AgentOrchestrator.AgentType.CODEX -> {
                val model = getModel()
                "codex --model $model --quiet"
            }
            AgentOrchestrator.AgentType.OPENCODE -> {
                "opencode"
            }
            AgentOrchestrator.AgentType.OPENMANUS -> {
                "cd /root/OpenManus && python3 -m openmanus"
            }
            AgentOrchestrator.AgentType.NATIVE -> {
                // 不应该到这里
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

    /**
     * 获取 API 配置（复用 NativeAgentService 的 SharedPreferences）。
     */
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

    /**
     * 收集 Agent stdout 输出并推送给回调。
     */
    private fun startOutputCollection() {
        scope.launch {
            processManager.stdout.collect { line ->
                if (isCollectingResponse) {
                    lastOutputTime = System.currentTimeMillis()
                    responseBuffer.appendLine(line)
                    currentOnChunk?.invoke(line + "\n")
                    Log.d(TAG, "Agent output: ${line.take(80)}")
                }
            }
        }

        // 监听 stderr 作为辅助信息
        scope.launch {
            processManager.stderr.collect { line ->
                Log.w(TAG, "Agent stderr: ${line.take(80)}")
                // stderr 不推送给 UI，但标记为有活动
                lastOutputTime = System.currentTimeMillis()
            }
        }

        // 监听进程状态
        scope.launch {
            processManager.processState.collect { state ->
                when (state) {
                    AgentProcessManager.ProcessState.CRASHED,
                    AgentProcessManager.ProcessState.STOPPED -> {
                        if (isCollectingResponse) {
                            val response = responseBuffer.toString().trim()
                            if (response.isNotEmpty()) {
                                currentOnComplete?.invoke(response)
                            } else {
                                currentOnError?.invoke("Agent 进程意外退出")
                            }
                            cleanupResponse()
                        }
                        _connectionState.value = AgentConnectionState.ERROR
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * 空闲检测：agent 输出停止后判定响应结束。
     */
    private fun startIdleDetection() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            while (isCollectingResponse) {
                delay(RESPONSE_IDLE_CHECK_INTERVAL_MS)
                val elapsed = System.currentTimeMillis() - lastOutputTime
                if (elapsed >= RESPONSE_IDLE_TIMEOUT_MS && responseBuffer.isNotEmpty()) {
                    // 空闲超时，视为响应完成
                    val response = responseBuffer.toString().trim()
                    currentOnComplete?.invoke(response)
                    cleanupResponse()
                    _connectionState.value = AgentConnectionState.CONNECTED
                    Log.d(TAG, "响应完成 (空闲超时 ${RESPONSE_IDLE_TIMEOUT_MS}ms), ${response.length} 字符")
                    break
                }
            }
        }
    }

    /**
     * 清理响应收集状态。
     */
    private fun cleanupResponse() {
        isCollectingResponse = false
        responseBuffer.clear()
        currentOnChunk = null
        currentOnComplete = null
        currentOnError = null
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    /**
     * 是否已配置 API。
     */
    override fun cancelStream() {
        processManager.stop()
        _connectionState.value = AgentConnectionState.DISCONNECTED
        cleanupResponse()
        Log.i(TAG, "Stream cancelled")
    }

    override fun isConfigured(): Boolean = getApiKey().isNotBlank() && getApiUrl().isNotBlank()
}
