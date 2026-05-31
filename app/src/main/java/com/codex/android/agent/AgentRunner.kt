package com.codex.android.agent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent Loop 智能体循环引擎。
 *
 * 实现核心的推理-执行循环：推理 → 工具调用 → 执行 → 追加结果 → 再推理，
 * 直到模型输出纯助手消息（无工具调用）或达到最大轮次。
 *
 * 事件通过 [SharedFlow] 向外发布，支持流式输出、工具调用监控等场景。
 * 自动压缩检查：当估算 token 数超过 [AgentConfig.autoCompactLimit] 时触发历史压缩。
 *
 * 使用方式：
 * ```kotlin
 * val runner = AgentRunner(config, apiClient)
 * runner.events.collect { event -> handleEvent(event) }
 * runner.run("帮我重构这个函数")
 * ```
 *
 * @property config Agent 运行配置
 * @property apiClient LLM API 客户端，负责发送请求和接收流式响应
 */
class AgentRunner(
    private val config: AgentConfig,
    private val apiClient: LlmApiClient
) {

    companion object {
        private const val TAG = "AgentRunner"
    }

    /** 协程作用域，生命周期与 Runner 绑定 */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 对话历史管理器 */
    private val history = ConversationHistory()

    // ========== 事件系统 ==========

    /**
     * Agent 循环事件密封类。
     *
     * 定义智能体循环过程中可能产生的所有事件类型，
     * 外部观察者通过订阅 [events] 流来接收实时状态更新。
     */
    sealed class AgentEvent {

        /** 循环开始 */
        data class TurnStarted(
            val turnNumber: Int,
            val estimatedTokens: Int
        ) : AgentEvent()

        /** 流式文本增量（LLM 输出的部分文本） */
        data class StreamDelta(
            val delta: String,
            val turnNumber: Int
        ) : AgentEvent()

        /** LLM 请求执行工具调用 */
        data class ToolCallRequested(
            val toolCall: ToolCall,
            val turnNumber: Int
        ) : AgentEvent()

        /** 工具调用执行完成 */
        data class ToolCallCompleted(
            val result: ToolResult,
            val turnNumber: Int
        ) : AgentEvent()

        /** 一轮循环完成 */
        data class TurnCompleted(
            val turnNumber: Int,
            val assistantMessage: String,
            val toolCallsCount: Int,
            val estimatedTokens: Int
        ) : AgentEvent()

        /** 对话历史压缩事件 */
        data class CompactionOccurred(
            val tokensBefore: Int,
            val tokensAfter: Int,
            val removedItems: Int
        ) : AgentEvent()

        /** 错误事件 */
        data class Error(
            val message: String,
            val cause: Throwable? = null,
            val turnNumber: Int = 0
        ) : AgentEvent()
    }

    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    // ========== LLM 请求构建 ==========

    /**
     * 构建发送给 LLM 的完整请求体。
     *
     * 包含模型名称、指令（system message）、工具定义和对话历史。
     * 对话历史通过 [ConversationHistory.buildApiInput] 转换为 API 格式。
     *
     * @return OpenAI Chat Completions API 格式的请求 JSONObject
     */
    private fun buildLlmRequest(): JSONObject {
        val mode = config.defaultMode

        return JSONObject().apply {
            put("model", config.model)
            put("stream", true)
            put("messages", history.buildApiInput())
            put("tools", mode.toolsToApiFormat())
            // 并行工具调用
            put("parallel_tool_calls", true)
        }
    }

    // ========== LLM 响应处理 ==========

    /**
     * LLM 响应结果密封类。
     *
     * 区分两种响应类型：纯助手消息（循环结束）和工具调用请求（继续循环）。
     */
    sealed class LlmResponse {
        /** 助手纯文本回复，表示循环应结束 */
        data class AssistantMessage(
            val content: String
        ) : LlmResponse()

        /** 工具调用请求，表示需要执行工具后继续推理 */
        data class ToolCalls(
            val calls: List<ToolCall>,
            val textContent: String = ""
        ) : LlmResponse()
    }

    /**
     * 解析 LLM 流式响应，提取助手消息或工具调用。
     *
     * 处理 SSE 流中的 delta 事件，累积内容并检测 tool_calls。
     * 同时通过事件流发布 [AgentEvent.StreamDelta] 事件。
     *
     * @param turnNumber 当前轮次编号
     * @return 解析后的 LLM 响应
     */
    private suspend fun parseStreamResponse(turnNumber: Int): LlmResponse {
        val contentBuilder = StringBuilder()
        val toolCallsMap = linkedMapOf<Int, ToolCallBuilder>()

        try {
            apiClient.streamChat(buildLlmRequest()).collect { chunk ->
                when (chunk) {
                    is LlmApiClient.StreamChunk.Delta -> {
                        if (chunk.content.isNotEmpty()) {
                            contentBuilder.append(chunk.content)
                            _events.emit(AgentEvent.StreamDelta(chunk.content, turnNumber))
                        }
                    }
                    is LlmApiClient.StreamChunk.ToolCallDelta -> {
                        val builder = toolCallsMap.getOrPut(chunk.index) {
                            ToolCallBuilder(index = chunk.index)
                        }
                        if (chunk.id != null) builder.id = chunk.id
                        if (chunk.name != null) builder.name = chunk.name
                        if (chunk.argumentsDelta != null) builder.argumentsBuilder.append(chunk.argumentsDelta)
                    }
                    is LlmApiClient.StreamChunk.Done -> {
                        // 流结束
                    }
                    is LlmApiClient.StreamChunk.Error -> {
                        _events.emit(AgentEvent.Error(chunk.message, turnNumber = turnNumber))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "流式响应解析异常", e)
            _events.emit(AgentEvent.Error("流式响应解析失败: ${e.message}", e, turnNumber))
            return LlmResponse.AssistantMessage(contentBuilder.toString())
        }

        val content = contentBuilder.toString()

        // 构建工具调用列表
        if (toolCallsMap.isNotEmpty()) {
            val toolCalls = toolCallsMap.values.mapNotNull { builder ->
                try {
                    ToolCall(
                        id = builder.id,
                        name = builder.name,
                        arguments = JSONObject(builder.argumentsBuilder.toString())
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "工具调用解析失败: index=${builder.index}", e)
                    null
                }
            }
            if (toolCalls.isNotEmpty()) {
                return LlmResponse.ToolCalls(calls = toolCalls, textContent = content)
            }
        }

        return LlmResponse.AssistantMessage(content)
    }

    /**
     * 工具调用增量构建器。
     *
     * 用于在流式响应中逐步累积工具调用的 id、name 和 arguments。
     */
    private data class ToolCallBuilder(
        val index: Int,
        var id: String = "",
        var name: String = "",
        val argumentsBuilder: StringBuilder = StringBuilder()
    )

    // ========== 自动压缩 ==========

    /**
     * 检查并执行自动压缩。
     *
     * 当估算 token 数超过 [AgentConfig.autoCompactLimit] 时，
     * 生成压缩摘要并调用 [ConversationHistory.compact] 压缩对话历史。
     *
     * @return 是否执行了压缩
     */
    private suspend fun checkAndCompact(): Boolean {
        val estimatedTokens = history.estimatedTokens()
        if (estimatedTokens <= config.autoCompactLimit) {
            return false
        }

        Log.i(TAG, "触发自动压缩: estimatedTokens=$estimatedTokens > limit=${config.autoCompactLimit}")
        val tokensBefore = estimatedTokens

        // 生成压缩摘要：提取关键信息的简化描述
        val summary = buildCompactionSummary()
        val removedCount = history.compact(summary)

        if (removedCount > 0) {
            val tokensAfter = history.estimatedTokens()
            _events.emit(AgentEvent.CompactionOccurred(
                tokensBefore = tokensBefore,
                tokensAfter = tokensAfter,
                removedItems = removedCount
            ))
            Log.i(TAG, "压缩完成: $tokensBefore → $tokensAfter tokens")
        }

        return removedCount > 0
    }

    /**
     * 构建压缩摘要文本。
     *
     * 从对话历史中提取关键信息，生成简要摘要用于替代被移除的历史内容。
     *
     * @return 压缩摘要文本
     */
    private fun buildCompactionSummary(): String {
        val sb = StringBuilder()
        val items = history.getItems()

        sb.appendLine("此前对话的简要摘要：")

        var userMsgCount = 0
        var toolCallCount = 0
        var errorCount = 0
        var lastUserMsg = ""

        for (item in items) {
            when (item) {
                is ConversationHistory.ConversationItem.UserMessage -> {
                    userMsgCount++
                    lastUserMsg = item.content.take(200)
                }
                is ConversationHistory.ConversationItem.ToolCallItem -> {
                    toolCallCount++
                }
                is ConversationHistory.ConversationItem.ToolResultItem -> {
                    if (!item.result.isSuccess) errorCount++
                }
                else -> { /* 其他类型不计入摘要统计 */ }
            }
        }

        sb.appendLine("- 用户消息数: $userMsgCount")
        sb.appendLine("- 工具调用数: $toolCallCount")
        if (errorCount > 0) sb.appendLine("- 工具调用失败数: $errorCount")
        if (lastUserMsg.isNotBlank()) sb.appendLine("- 最近用户请求: $lastUserMsg")

        return sb.toString().trimEnd()
    }

    // ========== 核心循环 ==========

    /**
     * 执行 Agent 循环。
     *
     * 从初始 prompt 开始，循环执行"推理 → 工具调用 → 执行 → 追加结果 → 再推理"，
     * 直到以下条件之一满足时终止：
     * 1. LLM 输出纯助手消息（无工具调用）
     * 2. 达到 [AgentConfig.maxTurns] 限制
     * 3. 发生不可恢复的错误
     * 4. 协程被取消
     *
     * @param initialPrompt 用户的初始输入
     * @return 最终的助手消息内容，异常时返回错误信息
     */
    suspend fun run(initialPrompt: String): String {
        // 注入初始消息（通过 PromptBuilder 构建四层上下文）
        val promptBuilder = PromptBuilder(
            context = apiClient.getContext(),
            config = config
        )
        val initialMessages = promptBuilder.buildInitialMessages(initialPrompt)
        initialMessages.forEach { message ->
            when (message) {
                is ConversationHistory.ConversationItem.SystemMessage ->
                    history.addSystemMessage(message.content, message.role)
                is ConversationHistory.ConversationItem.UserMessage ->
                    history.addUserMessage(message.content)
                else -> {
                    // 其他类型的初始消息暂不处理
                }
            }
        }

        var turnNumber = 0
        var lastAssistantMessage = ""

        try {
            while (scope.isActive && turnNumber < config.maxTurns) {
                turnNumber++

                // 检查是否需要自动压缩
                checkAndCompact()

                // 发布轮次开始事件
                _events.emit(AgentEvent.TurnStarted(
                    turnNumber = turnNumber,
                    estimatedTokens = history.estimatedTokens()
                ))

                Log.d(TAG, "开始轮次 $turnNumber/${config.maxTurns}, tokens=${history.estimatedTokens()}")

                // 发送 LLM 请求并解析响应
                val response = parseStreamResponse(turnNumber)

                when (response) {
                    is LlmResponse.AssistantMessage -> {
                        // LLM 输出纯助手消息，循环结束
                        lastAssistantMessage = response.content
                        history.addAssistantMessage(response.content)

                        _events.emit(AgentEvent.TurnCompleted(
                            turnNumber = turnNumber,
                            assistantMessage = response.content,
                            toolCallsCount = 0,
                            estimatedTokens = history.estimatedTokens()
                        ))

                        Log.i(TAG, "Agent 循环结束: 轮次 $turnNumber, 助手消息长度=${response.content.length}")
                        break
                    }

                    is LlmResponse.ToolCalls -> {
                        // LLM 请求工具调用，执行后继续循环
                        history.addAssistantMessage(response.textContent, response.calls)

                        // 逐个执行工具调用
                        for (toolCall in response.calls) {
                            _events.emit(AgentEvent.ToolCallRequested(toolCall, turnNumber))

                            Log.d(TAG, "工具调用: ${toolCall.name}(${toolCall.arguments})")

                            // 执行工具
                            val result = executeToolCall(toolCall)

                            // 记录结果
                            history.addToolResult(result)
                            _events.emit(AgentEvent.ToolCallCompleted(result, turnNumber))

                            Log.d(TAG, "工具结果: ${toolCall.name} -> success=${result.isSuccess}, length=${result.output.length}")
                        }

                        _events.emit(AgentEvent.TurnCompleted(
                            turnNumber = turnNumber,
                            assistantMessage = response.textContent,
                            toolCallsCount = response.calls.size,
                            estimatedTokens = history.estimatedTokens()
                        ))
                    }
                }
            }

            // 达到最大轮次
            if (turnNumber >= config.maxTurns) {
                val warning = "已达到最大轮次限制 (${config.maxTurns})，任务可能未完全完成。"
                Log.w(TAG, warning)
                _events.emit(AgentEvent.Error(warning, turnNumber = turnNumber))
                if (lastAssistantMessage.isBlank()) {
                    lastAssistantMessage = warning
                }
            }
        } catch (e: CancellationException) {
            Log.i(TAG, "Agent 循环被取消")
            _events.emit(AgentEvent.Error("循环被取消", e, turnNumber))
        } catch (e: Exception) {
            Log.e(TAG, "Agent 循环异常", e)
            _events.emit(AgentEvent.Error("循环异常: ${e.message}", e, turnNumber))
            if (lastAssistantMessage.isBlank()) {
                lastAssistantMessage = "执行出错: ${e.message}"
            }
        }

        return lastAssistantMessage
    }

    // ========== 工具执行 ==========

    /**
     * 执行单个工具调用。
     *
     * 根据 [ToolCall.name] 分发到对应的执行逻辑。
     * 当前为框架层实现，返回占位结果；实际执行逻辑由 ToolExecutor 扩展。
     *
     * @param toolCall 工具调用请求
     * @return 工具执行结果
     */
    private suspend fun executeToolCall(toolCall: ToolCall): ToolResult {
        return try {
            // 委托给 API 客户端执行工具调用
            // 实际实现中，这里会通过 ToolExecutor 分发到具体的执行器
            apiClient.executeToolCall(toolCall, config)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "工具执行失败: ${toolCall.name}", e)
            ToolResult.error(toolCall.id, "工具执行失败: ${e.message}")
        }
    }

    // ========== 生命周期 ==========

    /**
     * 取消当前运行的 Agent 循环。
     */
    fun cancel() {
        scope.cancel()
        Log.i(TAG, "Agent 循环已取消")
    }

    /**
     * 获取当前对话历史的快照。
     *
     * @return 对话条目列表
     */
    fun getHistory(): List<ConversationHistory.ConversationItem> {
        return history.getItems()
    }

    /**
     * 获取当前估算的 token 数。
     */
    fun getEstimatedTokens(): Int {
        return history.estimatedTokens()
    }

    /**
     * 清除对话历史并重置状态。
     */
    fun reset() {
        history.clear()
        Log.i(TAG, "Agent 已重置")
    }
}

/**
 * LLM API 客户端接口。
 *
 * 定义与 LLM 服务交互所需的抽象方法，由具体实现类（如 OpenAI API 桥接器）提供。
 * AgentRunner 通过此接口解耦具体的 API 调用细节。
 */
interface LlmApiClient {

    /**
     * 流式聊天响应的数据块密封类。
     */
    sealed class StreamChunk {
        /** 文本内容增量 */
        data class Delta(val content: String) : StreamChunk()

        /** 工具调用增量 */
        data class ToolCallDelta(
            val index: Int,
            val id: String? = null,
            val name: String? = null,
            val argumentsDelta: String? = null
        ) : StreamChunk()

        /** 流结束标记 */
        data object Done : StreamChunk()

        /** 错误 */
        data class Error(val message: String, val cause: Throwable? = null) : StreamChunk()
    }

    /**
     * 发送流式聊天请求。
     *
     * @param request OpenAI 格式的请求 JSONObject
     * @return 流式响应的 Flow
     */
    suspend fun streamChat(request: JSONObject): kotlinx.coroutines.flow.Flow<StreamChunk>

    /**
     * 执行工具调用。
     *
     * @param toolCall 工具调用请求
     * @param config Agent 配置
     * @return 工具执行结果
     */
    suspend fun executeToolCall(toolCall: ToolCall, config: AgentConfig): ToolResult

    /**
     * 获取 Android Context（供 PromptBuilder 使用）。
     *
     * @return Application 或 Activity Context
     */
    fun getContext(): android.content.Context
}
