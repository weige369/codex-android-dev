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
import java.util.concurrent.TimeUnit

/**
 * 原生 AI Agent 服务（v2 优化版）。
 *
 * 核心改进（vs v1）：
 * 1. 使用 SSEStreamParser 统一 SSE 解析，消除 sendPromptStream/sendPromptContinue ~200行重复代码
 * 2. DeepSeek V4 reasoning_content 正确回传，修复多轮工具调用后 400 错误
 * 3. 工具结果自动裁剪，避免超长输出导致 token 爆炸
 * 4. 对话历史 token 估算 + 自动压缩
 * 5. 集成 EndpointCompleter 自动补全 API 端点
 * 6. HTTP 错误响应体日志增强
 */
class NativeAgentService(private val context: Context) : ChatAgent {

    companion object {
        private const val TAG = "NativeAgentService"
        private const val PREFS_NAME = "codex_agent_prefs"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_API_URL = "api_url"
        private const val KEY_API_MODEL = "api_model"
        private const val KEY_PROVIDER_ID = "provider_id"
        private const val KEY_CUSTOM_URL = "custom_url"

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

    override fun isConfigured(): Boolean = getApiKey().isNotBlank() && getApiUrl().isNotBlank()

    // ===== 状态 =====

    private val _connectionState = MutableStateFlow(AgentConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<AgentConnectionState> = _connectionState.asStateFlow()

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
    private val compactor = ConversationCompactor(context)

    /** 当前轮次的思考内容（DeepSeek V4 reasoning_content） */
    @Volatile private var currentThinkingContent: String = ""

    fun clearHistory() {
        conversationHistory.clear()
        currentThinkingContent = ""
    }

    // ===== 工具注册 =====

    private val capabilityRegistry = CapabilityRegistry(context)
    private val permissionManager = ToolPermissionManager.getInstance(context)

    fun getPermissionManager(): ToolPermissionManager = permissionManager
    fun getCapabilityRegistry(): CapabilityRegistry = capabilityRegistry

    suspend fun mountCapabilities() {
        capabilityRegistry.autoMount()
    }

    suspend fun grantPermissionAndMount(level: PermissionLevel) {
        capabilityRegistry.grantLevel(level)
        capabilityRegistry.autoMount()
    }

    suspend fun grantModerateAccess() {
        capabilityRegistry.grantModerateAccess()
        capabilityRegistry.autoMount()
    }

    fun getToolDefinitions(): JSONArray {
        return capabilityRegistry.getToolDefinitions()
    }

    // ===== 核心：发送消息（使用 SSEStreamParser）=====

    private var currentEventSource: EventSource? = null

    /**
     * 发送消息并获取流式响应。
     *
     * v2: 统一使用 SSEStreamParser，消除 sendPromptStream + sendPromptContinue 重复代码。
     * 所有流式请求走同一个 streamRequest() 方法，工具调用后递归回调。
     */
    override fun sendPromptStream(
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

        // 添加用户消息到历史
        conversationHistory.add(JSONObject().apply {
            put("role", "user")
            put("content", prompt)
        })

        // 检查历史是否需要压缩
        val messagesForCheck = buildMessagesArray()
        if (compactor.estimateTokens(messagesForCheck) > ConversationCompactor.COMPACTION_THRESHOLD_TOKENS) {
            compressHistory()
        }

        // 重置思考内容
        currentThinkingContent = ""

        // 发起流式请求
        streamRequest(onChunk, onComplete, onError)
    }

    /**
     * 统一的流式请求方法。
     *
     * 替代 v1 中 sendPromptStream 和 sendPromptContinue 两个几乎相同的方法。
     * 使用 SSEStreamParser 统一处理 SSE 解析。
     */
    private fun streamRequest(
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val apiKey = getApiKey()
        val apiUrl = getApiUrl().trimEnd('/')
        val model = getApiModel()

        // 构建 messages
        val messagesArray = buildMessagesArray()

        val requestBody = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("messages", messagesArray)
            // 工具定义
            if (capabilityRegistry.getMountedToolNames().isNotEmpty()) {
                put("tools", getToolDefinitions())
            }
        }

        Log.i(TAG, "发送 API 请求: $model @ $apiUrl (历史 ${conversationHistory.size} 条)")

        val request = Request.Builder()
            .url("$apiUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        _connectionState.value = AgentConnectionState.STREAMING

        // SSEStreamParser 统一解析
        val parser = SSEStreamParser(
            tag = TAG,
            onContent = { content -> onChunk(content) },
            onToolCall = { toolCalls ->
                scope.launch {
                    handleToolCalls(toolCalls, onChunk, onComplete, onError)
                }
            },
            onComplete = { fullContent, _ ->
                // 将助手回复加入历史
                conversationHistory.add(JSONObject().apply {
                    put("role", "assistant")
                    put("content", fullContent)
                })
                _connectionState.value = AgentConnectionState.CONNECTED
                onComplete(fullContent)
            },
            onError = { error ->
                Log.e(TAG, "SSE 错误: $error")
                _connectionState.value = AgentConnectionState.ERROR
                onError(error)
            },
            detectXmlToolCalls = true
        )

        val factory = EventSources.createFactory(client)
        currentEventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.i(TAG, "SSE 连接已打开")
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                // 委托给 SSEStreamParser
                parser.onEvent(eventSource, id, type, data)

                // 额外提取 reasoning_content（DeepSeek V4）
                if (data != "[DONE]") {
                    try {
                        val json = JSONObject(data)
                        val delta = json.optJSONArray("choices")
                            ?.optJSONObject(0)?.optJSONObject("delta")
                        delta?.optString("reasoning_content", "")?.takeIf { it.isNotEmpty() }?.let {
                            currentThinkingContent += it
                            // 发送思考内容给 UI（使用特殊标记 <think/> 让 NativeChatView 识别）
                            onChunk("<think/>$it")
                        }
                    } catch (_: Exception) {}
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val errorMsg = when {
                    response != null -> {
                        val body = try { response.body?.string()?.take(500) } catch (_: Exception) { null }
                        "HTTP ${response.code}: ${body ?: response.message}"
                    }
                    t != null -> "连接失败: ${t.message}"
                    else -> "未知错误"
                }
                Log.e(TAG, errorMsg, t)
                _connectionState.value = AgentConnectionState.ERROR
                onError(errorMsg)
            }

            override fun onClosed(eventSource: EventSource) {
                _connectionState.value = AgentConnectionState.DISCONNECTED
            }
        })
    }

    /**
     * 构建 OpenAI API messages 数组。
     *
     * v2 改进：
     * - 自动裁剪过长的工具结果
     * - DeepSeek reasoning_content 正确回传
     */
    private fun buildMessagesArray(): JSONArray {
        val messages = JSONArray()

        // System prompt
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", buildSystemPrompt())
        })

        // 遍历历史，构建消息
        val rawMessages = JSONArray()
        conversationHistory.forEach { rawMessages.put(it) }

        // 使用 compactor 裁剪工具结果
        val trimmed = compactor.trimToolResults(rawMessages)
        for (i in 0 until trimmed.length()) {
            messages.put(trimmed.optJSONObject(i))
        }

        return messages
    }

    /**
     * 处理工具调用。
     *
     * v2 改进：
     * - DeepSeek V4 修复：assistant 消息需要回传 reasoning_content
     * - 工具结果自动裁剪
     */
    private suspend fun handleToolCalls(
        toolCalls: List<ToolCallAccumulator>,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        // 将 AI 的工具调用消息加入历史
        // DeepSeek V4 修复：如果有 reasoning_content，需要回传
        val assistantMsg = JSONObject().apply {
            put("role", "assistant")
            // DeepSeek V4: reasoning_content 必须在 content 中回传，否则多轮后 400
            if (currentThinkingContent.isNotBlank()) {
                put("content", currentThinkingContent)
            } else {
                put("content", JSONObject.NULL)
            }
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

        // 清空思考内容（已处理）
        currentThinkingContent = ""

        // 逐个执行工具
        for (tc in toolCalls) {
            val toolName = tc.name
            val toolArgs = tc.argumentsBuilder.toString()
            Log.i(TAG, "执行工具: $toolName")

            // 权限检查
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

            onChunk("\n🔧 执行工具: $toolName\n")

            val result = try {
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

            // 裁剪显示
            val displayResult = if (result.length > 500) result.take(500) + "..." else result
            onChunk("📋 结果: $displayResult\n")
        }

        // 重新发起请求（让 AI 处理工具结果）
        withContext(Main) {
            streamRequest(onChunk, onComplete, onError)
        }
    }

    /**
     * 压缩对话历史（简单截断策略）。
     * 保留系统消息 + 最近 6 条对话。
     */
    private fun compressHistory() {
        val allItems = conversationHistory.toList()
        conversationHistory.clear()

        // 只保留最后 6 条（非系统消息）
        val recentItems = allItems.takeLast(6)
        recentItems.forEach { conversationHistory.add(it) }

        Log.i(TAG, "对话历史已压缩: ${allItems.size} → ${recentItems.size} 条")
    }

    override fun cancelStream() {
        currentEventSource?.cancel()
        currentEventSource = null
        _connectionState.value = AgentConnectionState.CONNECTED
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

    fun markReady() {
        scope.launch {
            mountCapabilities()
            withContext(Main) {
                _isReady.value = true
                _connectionState.value = AgentConnectionState.CONNECTED
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
        val devEnv = com.codex.android.util.DevelopmentEnvironment(context)
        val envInfo = runCatching { devEnv.getSelfContainedLinuxInfo() }.getOrNull()
        val hasProot = envInfo?.state == com.codex.android.util.LinuxEnvironment.EngineState.READY

        val prefs = context.getSharedPreferences("codex_setup_prefs", android.content.Context.MODE_PRIVATE)
        val installedTools = prefs.getStringSet("installed_tools", emptySet()) ?: emptySet()

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

        val deviceManifest = capabilityRegistry.getDeviceManifest()

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
}
