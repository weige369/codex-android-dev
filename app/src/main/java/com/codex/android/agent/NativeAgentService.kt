package com.codex.android.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import com.codex.android.agent.ToolCallBridge
import com.codex.android.agent.ToolPermissionManager
import com.codex.android.agent.PermissionDecision
import java.util.concurrent.TimeUnit

/**
 * 原生 AI Agent 服务。
 *
 * 核心思路（学习 Operit EnhancedAIService）：
 * 不依赖任何外部二进制，直接在 Kotlin 进程内完成：
 * 1. 调用 OpenAI 兼容 API（流式 SSE）
 * 2. 维护对话历史
 * 3. 解析 AI 响应中的工具调用
 * 4. 执行工具并将结果回传 API
 * 5. 循环直到 AI 返回纯文本回复
 *
 * 这使得 Codex 在 Android 36 上无需 proot 即可工作。
 */
class NativeAgentService(private val context: Context) {

    companion object {
        private const val TAG = "NativeAgentService"
        private const val PREFS_NAME = "codex_agent_prefs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_API_URL = "api_url"
        private const val KEY_API_MODEL = "api_model"
        private const val KEY_PROVIDER_ID = "provider_id"
        private const val KEY_CUSTOM_URL = "custom_url"

        /** Agent 连接状态 */
        enum class ConnectionState {
            DISCONNECTED, CONNECTING, CONNECTED, STREAMING, ERROR
        }

        /** 单例 */
        @Volatile private var INSTANCE: NativeAgentService? = null
        fun getInstance(ctx: Context): NativeAgentService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NativeAgentService(ctx.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ===== 配置 =====

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    fun getApiKey(): String = prefs.getString(KEY_API_KEY, "") ?: ""
    fun getApiUrl(): String {
        val customUrl = prefs.getString(KEY_CUSTOM_URL, "") ?: ""
        if (customUrl.isNotBlank()) return customUrl.trimEnd('/')
        val providerId = prefs.getString(KEY_PROVIDER_ID, "deepseek") ?: "deepseek"
        val provider = ApiProvider.getById(providerId) ?: ApiProvider.BUILT_IN.first()
        return provider.baseUrl.trimEnd('/')
    }
    fun getApiModel(): String {
        val model = prefs.getString(KEY_API_MODEL, "") ?: ""
        if (model.isNotBlank()) return model
        val providerId = prefs.getString(KEY_PROVIDER_ID, "deepseek") ?: "deepseek"
        val provider = ApiProvider.getById(providerId) ?: ApiProvider.BUILT_IN.first()
        return provider.defaultModel
    }
    fun getProviderId(): String = prefs.getString(KEY_PROVIDER_ID, "deepseek") ?: "deepseek"

    fun setConfig(providerId: String, apiKey: String, customUrl: String = "", model: String = "") {
        val provider = ApiProvider.getById(providerId)
        prefs.edit().apply {
            putString(KEY_PROVIDER_ID, providerId)
            putString(KEY_API_KEY, apiKey)
            putString(KEY_CUSTOM_URL, customUrl)
            putString(KEY_API_MODEL, model.ifBlank { provider?.defaultModel ?: "" })
            apply()
        }
    }

    fun isConfigured(): Boolean = getApiKey().isNotBlank() && getApiUrl().isNotBlank()

    // ===== 状态 =====

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    // ===== HTTP 客户端 =====

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ===== 对话历史 =====

    private val conversationHistory = ConcurrentLinkedQueue<JSONObject>()

    fun clearHistory() {
        conversationHistory.clear()
    }

    // ===== 工具注册 =====

    /**
     * 能力设备注册表。
     *
     * 借鉴 BentOS 的 /dev/ 设备模型：
     * - 设备必须被"挂载"(mount)才能使用
     * - 未挂载的设备结构性不可访问（LLM 根本不知道它的存在）
     * - 每个设备有明确的权限级别（SAFE/MODERATE/ELEVATED/PRIVILEGED）
     * - 运行时动态挂载/卸载（如 proot 安装后自动挂载 Linux Shell）
     */
    private val capabilityRegistry = CapabilityRegistry(context)

    /**
     * 工具权限管理器（运行时安全：决定工具是否可以执行）
     * 与 CapabilityRegistry 配合：
     * - CapabilityRegistry 管理设备挂载（结构性安全，决定工具是否暴露给 LLM）
     * - ToolPermissionManager 管理工具执行权限（运行时安全，决定工具是否可以执行）
     */
    private val permissionManager = ToolPermissionManager.getInstance(context)

    /** 获取权限管理器（供 UI 层展示权限弹窗） */
    fun getPermissionManager(): ToolPermissionManager = permissionManager

    /**
     * 获取能力设备注册表（供 UI 层查询设备状态/权限）。
     */
    fun getCapabilityRegistry(): CapabilityRegistry = capabilityRegistry

    /**
     * 挂载所有可用设备（替代原 registerDefaultTools + registerEnvironmentAwareTools）。
     *
     * CapabilityRegistry.autoMount() 会：
     * 1. 自动挂载 SAFE 级别设备
     * 2. 如果用户已授权 MODERATE，自动挂载 MODERATE 设备
     * 3. 如果 proot 已就绪，自动挂载 Linux Shell 设备
     */
    suspend fun mountCapabilities() {
        capabilityRegistry.autoMount()
    }

    /**
     * 授权权限级别并尝试挂载对应设备。
     */
    suspend fun grantPermissionAndMount(level: PermissionLevel) {
        capabilityRegistry.grantLevel(level)
        capabilityRegistry.autoMount()
    }

    /**
     * 一键授权 MODERATE 权限（Shell、文件写入等）。
     */
    suspend fun grantModerateAccess() {
        capabilityRegistry.grantModerateAccess()
        capabilityRegistry.autoMount()
    }

    fun getToolDefinitions(): JSONArray {
        return capabilityRegistry.getToolDefinitions()
    }

    // ===== 核心：发送消息 =====

    private var currentEventSource: EventSource? = null

    /**
     * 发送消息并获取流式响应。
     * 回调在 IO 线程执行，UI 层需要切线程。
     */
    fun sendPromptStream(
        prompt: String,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            onError("请先配置 API Key（设置 → AI 提供商）")
            return
        }

        val apiUrl = getApiUrl().trimEnd('/')
        val model = getApiModel()

        // 添加用户消息到历史
        val userMsg = JSONObject().apply {
            put("role", "user")
            put("content", prompt)
        }
        conversationHistory.add(userMsg)

        // 构建请求体
        val messagesArray = JSONArray()
        // System prompt
        messagesArray.put(JSONObject().apply {
            put("role", "system")
            put("content", buildSystemPrompt())
        })
        conversationHistory.forEach { messagesArray.put(it) }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("messages", messagesArray)
            // 工具定义
            if (capabilityRegistry.getMountedToolNames().isNotEmpty()) {
                put("tools", getToolDefinitions())
                put("tool_choice", "auto")
            }
        }

        Log.i(TAG, "发送 API 请求: $model @ $apiUrl")

        val request = Request.Builder()
            .url("$apiUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        _connectionState.value = ConnectionState.STREAMING

        val factory = EventSources.createFactory(client)
        currentEventSource = factory.newEventSource(request, object : EventSourceListener() {
            private val contentBuilder = StringBuilder()
            private var toolCalls = mutableListOf<ToolCallAccumulator>()
            private var finishReason: String? = null

            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.i(TAG, "API 流连接已打开")
                _connectionState.value = ConnectionState.CONNECTED
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    // 处理完成
                    val fullContent = contentBuilder.toString()

                    if (finishReason == "tool_calls" && toolCalls.isNotEmpty()) {
                        // AI 请求调用工具
                        scope.launch {
                            handleToolCalls(toolCalls, onChunk, onComplete, onError)
                        }
                    } else {
                        // 纯文本回复，完成
                        conversationHistory.add(JSONObject().apply {
                            put("role", "assistant")
                            put("content", fullContent)
                        })
                        _connectionState.value = ConnectionState.CONNECTED
                        onComplete(fullContent)
                    }
                    return
                }

                try {
                    val json = JSONObject(data)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta")
                        finishReason = choice.optString("finish_reason", null)

                        if (delta != null) {
                            // 文本内容
                            val content = delta.optString("content", "")
                            if (content.isNotEmpty()) {
                                contentBuilder.append(content)
                                onChunk(content)
                            }

                            // 工具调用
                            val toolCallsDelta = delta.optJSONArray("tool_calls")
                            if (toolCallsDelta != null) {
                                for (i in 0 until toolCallsDelta.length()) {
                                    val tc = toolCallsDelta.getJSONObject(i)
                                    val idx = tc.optInt("index", 0)
                                    while (toolCalls.size <= idx) {
                                        toolCalls.add(ToolCallAccumulator())
                                    }
                                    val acc = toolCalls[idx]
                                    tc.optJSONObject("function")?.let { fn ->
                                        fn.optString("name", "").takeIf { it.isNotEmpty() }?.let { acc.name = it }
                                        fn.optString("arguments", "").let { acc.argumentsBuilder.append(it) }
                                    }
                                    tc.optString("id", "").takeIf { it.isNotEmpty() }?.let { acc.id = it }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SSE 解析错误: ${e.message}")
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val errorMsg = when {
                    t != null -> "连接失败: ${t.message}"
                    response != null -> "HTTP ${response.code}: ${response.message}"
                    else -> "未知错误"
                }
                Log.e(TAG, errorMsg, t)
                _connectionState.value = ConnectionState.ERROR
                onError(errorMsg)
            }

            override fun onClosed(eventSource: EventSource) {
                Log.i(TAG, "API 流已关闭")
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        })
    }

    /**
     * 处理 AI 的工具调用请求。
     * 执行工具后将结果回传 API，继续对话循环。
     */
    private suspend fun handleToolCalls(
        toolCalls: List<ToolCallAccumulator>,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        // 将 AI 的工具调用消息加入历史
        val assistantMsg = JSONObject().apply {
            put("role", "assistant")
            put("content", JSONObject.NULL)
            val tcArray = JSONArray()
            toolCalls.forEach { tc ->
                tcArray.put(JSONObject().apply {
                    put("id", tc.id)
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", tc.name)
                        put("arguments", tc.argumentsBuilder.toString())
                    })
                })
            }
            put("tool_calls", tcArray)
        }
        conversationHistory.add(assistantMsg)

        // 逐个执行工具
        for (tc in toolCalls) {
            val toolName = tc.name
            val toolArgs = tc.argumentsBuilder.toString()
            Log.i(TAG, "执行工具: $toolName")

            // 权限检查（ToolPermissionManager ALLOW/ASK/FORBID）
            val device = capabilityRegistry.getMountedDeviceByName(toolName)
            val allowed = permissionManager.checkPermission(toolName, device)
            if (!allowed) {
                Log.w(TAG, "工具 $toolName 权限被拒绝")
                conversationHistory.add(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", tc.id)
                    put("content", "权限不足: 工具 '$toolName' 被禁止执行。请在设置中授予权限。")
                })
                onChunk("🚫 权限不足: $toolName\n")
                continue
            }
            Log.i(TAG, "执行工具: $toolName")

            onChunk("\n🔧 执行工具: $toolName\n")

            val result = try {
                // 通过 CapabilityRegistry 执行，结构性保证安全：
                // 未挂载设备的工具根本不会被发送给 LLM，也不会被执行
                capabilityRegistry.executeTool(toolName, toolArgs)
            } catch (e: Exception) {
                Log.e(TAG, "工具 $toolName 执行失败", e)
                "工具执行失败: ${e.message}"
            }

            // 将工具结果加入历史
            conversationHistory.add(JSONObject().apply {
                put("role", "tool")
                put("tool_call_id", tc.id)
                put("content", result)
            })

            onChunk("📋 结果: ${result.take(500)}${if (result.length > 500) "..." else ""}\n")
        }

        // 重新发送请求，让 AI 处理工具结果
        withContext(Dispatchers.Main) {
            sendPromptContinue(onChunk, onComplete, onError)
        }
    }

    /**
     * 继续对话（工具调用后）
     */
    private fun sendPromptContinue(
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val apiKey = getApiKey()
        val apiUrl = getApiUrl().trimEnd('/')
        val model = getApiModel()

        val messagesArray = JSONArray()
        messagesArray.put(JSONObject().apply {
            put("role", "system")
            put("content", buildSystemPrompt())
        })
        conversationHistory.forEach { messagesArray.put(it) }

        val requestBody = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("messages", messagesArray)
            if (capabilityRegistry.getMountedToolNames().isNotEmpty()) {
                put("tools", getToolDefinitions())
                put("tool_choice", "auto")
            }
        }

        val request = Request.Builder()
            .url("$apiUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val factory = EventSources.createFactory(client)
        currentEventSource = factory.newEventSource(request, object : EventSourceListener() {
            private val contentBuilder = StringBuilder()
            private var continueToolCalls = mutableListOf<ToolCallAccumulator>()
            private var finishReason: String? = null

            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.i(TAG, "继续对话流连接已打开")
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data == "[DONE]") {
                    val fullContent = contentBuilder.toString()
                    if (finishReason == "tool_calls" && continueToolCalls.isNotEmpty()) {
                        scope.launch {
                            handleToolCalls(continueToolCalls, onChunk, onComplete, onError)
                        }
                    } else {
                        conversationHistory.add(JSONObject().apply {
                            put("role", "assistant")
                            put("content", fullContent)
                        })
                        _connectionState.value = ConnectionState.CONNECTED
                        onComplete(fullContent)
                    }
                    return
                }
                try {
                    val json = JSONObject(data)
                    val choices = json.optJSONArray("choices")
                    if (choices != null && choices.length() > 0) {
                        val choice = choices.getJSONObject(0)
                        val delta = choice.optJSONObject("delta")
                        finishReason = choice.optString("finish_reason", null)
                        if (delta != null) {
                            val content = delta.optString("content", "")
                            if (content.isNotEmpty()) {
                                contentBuilder.append(content)
                                onChunk(content)
                            }
                            val tcDelta = delta.optJSONArray("tool_calls")
                            if (tcDelta != null) {
                                for (i in 0 until tcDelta.length()) {
                                    val tc = tcDelta.getJSONObject(i)
                                    val idx = tc.optInt("index", 0)
                                    while (continueToolCalls.size <= idx) {
                                        continueToolCalls.add(ToolCallAccumulator())
                                    }
                                    val acc = continueToolCalls[idx]
                                    tc.optJSONObject("function")?.let { fn ->
                                        fn.optString("name", "").takeIf { it.isNotEmpty() }?.let { acc.name = it }
                                        fn.optString("arguments", "").let { acc.argumentsBuilder.append(it) }
                                    }
                                    tc.optString("id", "").takeIf { it.isNotEmpty() }?.let { acc.id = it }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "继续对话 SSE 解析错误: ${e.message}")
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val errorMsg = when {
                    t != null -> "继续对话失败: ${t.message}"
                    response != null -> "HTTP ${response.code}"
                    else -> "未知错误"
                }
                Log.e(TAG, errorMsg, t)
                _connectionState.value = ConnectionState.ERROR
                onError(errorMsg)
            }

            override fun onClosed(eventSource: EventSource) {
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        })
    }

    fun cancelStream() {
        currentEventSource?.cancel()
        currentEventSource = null
        _connectionState.value = ConnectionState.CONNECTED
    }

    /**
     * 测试 API 连通性
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val apiUrl = getApiUrl().trimEnd('/')
            val request = Request.Builder()
                .url("$apiUrl/models")
                .addHeader("Authorization", "Bearer ${getApiKey()}")
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.w(TAG, "API 连通性测试失败: ${e.message}")
            false
        }
    }

    /**
     * 将服务标记为就绪（无需外部二进制）
     */
    /**
     * 将服务标记为就绪。
     * 使用 CapabilityRegistry 挂载所有可用设备。
     */
    fun markReady() {
        scope.launch {
            mountCapabilities()
            withContext(Dispatchers.Main) {
                _isReady.value = true
                _connectionState.value = ConnectionState.CONNECTED
            }
        }
    }

    fun destroy() {
        cancelStream()
        scope.cancel()
        conversationHistory.clear()
    }

    // ===== 辅助 =====

    private fun buildSystemPrompt(): String {
        // 动态检测环境状态
        val devEnv = com.codex.android.util.DevelopmentEnvironment(context)
        val envInfo = runCatching { devEnv.getSelfContainedLinuxInfo() }.getOrNull()
        val hasProot = envInfo?.state == com.codex.android.util.LinuxEnvironment.EngineState.READY

        // 读取已安装工具列表
        val prefs = context.getSharedPreferences("codex_setup_prefs", android.content.Context.MODE_PRIVATE)
        val installedTools = prefs.getStringSet("installed_tools", emptySet()) ?: emptySet()

        // 构建环境信息
        val envInfoText = if (hasProot) {
            val toolsList = if (installedTools.isNotEmpty()) installedTools.joinToString(", ") else "通过 apt-get install 按需安装"
            "Linux 环境: Ubuntu proot 已就绪\n" +
            "- 可用命令: apt-get, python3, pip3, node, npm, git, vim, curl, wget, gcc, make 等\n" +
            "- 已安装工具: $toolsList\n" +
            "- shell 工具会自动通过 proot 执行 Linux 命令\n" +
            "- 可直接执行: python3 script.py, npm install, git clone, gcc main.c 等"
        } else {
            "Linux 环境: 未安装（仅 Android Shell 可用）\n" +
            "- 只能使用基础命令: ls, cat, grep, find, cp, mv 等\n" +
            "- 如需完整开发工具链，请引导用户安装 Ubuntu proot"
        }

        // 构建设备清单（BentOS 风格）
        val deviceManifest = capabilityRegistry.getDeviceManifest()

        // 构建执行策略
        val strategyText = if (hasProot) {
            "- 优先使用 proot Linux 环境执行开发相关命令"
        } else {
            "- 当前仅 Android Shell，避免使用 Linux 特有命令"
        }

        return buildString {
            appendLine("你是一个运行在 Android 设备上的 AI 编程助手（Codex Agent）。")
            appendLine("你可以执行 Shell 命令、读写文件、搜索代码来帮助用户完成编程任务。")
            appendLine()
            appendLine("当前环境信息：")
            appendLine("- 设备: Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            appendLine("- 架构: ${android.os.Build.SUPPORTED_ABIS?.firstOrNull() ?: "unknown"}")
            appendLine("- 工作目录: ${context.filesDir.absolutePath}")
            appendLine(envInfoText)
            appendLine()
            appendLine("可用设备：")
            appendLine(deviceManifest)
            appendLine()
            appendLine("执行策略：")
            appendLine(strategyText)
            appendLine("- /dev/fs/read + /dev/fs/write 用于精确的文件操作，/dev/shell 用于批量操作")
            appendLine("- /dev/search 用于查找文件和代码内容")
            appendLine("- /dev/env/proot 用于查询和管理 Linux 环境")
            appendLine("- /dev/linux/shell 在 proot 中执行完整的 Linux 命令")
            appendLine("- 危险命令（rm -rf、dd 等）执行前需提醒用户")
            appendLine()
            append("请用中文回复。")
        }
    }

    /** 工具调用累积器 */
    private class ToolCallAccumulator {
        var id: String = ""
        var name: String = ""
        val argumentsBuilder = StringBuilder()
    }
}
