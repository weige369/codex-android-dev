package com.codex.android.agent

import android.util.Log
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.json.JSONObject

/**
 * SSE 流解析器。
 *
 * 从 Operit EnhancedAIService 提取的 SSE 解析逻辑，消除 NativeAgentService 中
 * sendPromptStream 和 sendPromptContinue 的重复代码。
 *
 * 核心改进（vs 原版重复代码）：
 * 1. 统一处理 SSE 事件解析（文本内容 + 工具调用增量）
 * 2. 完成时自动检测 XML 工具调用（通过 ToolCallBridge）
 * 3. 支持 thinking/reasoning 内容提取（部分模型如 DeepSeek R1）
 * 4. 错误处理增强：超时、HTTP 错误码、JSON 解析容错
 */
class SSEStreamParser(
    private val tag: String = "SSEStreamParser",
    private val onContent: (String) -> Unit,
    private val onToolCall: (List<ToolCallAccumulator>) -> Unit,
    private val onComplete: (fullContent: String, hadToolCalls: Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val detectXmlToolCalls: Boolean = true
) : EventSourceListener() {

    private val contentBuilder = StringBuilder()
    private val thinkingBuilder = StringBuilder()
    private val toolCalls = mutableListOf<ToolCallAccumulator>()
    private var finishReason: String? = null

    /** 累积思考内容（DeepSeek R1 等） */
    val thinkingContent: String get() = thinkingBuilder.toString()

    override fun onOpen(eventSource: EventSource, response: Response) {
        Log.i(tag, "SSE 流连接已打开")
    }

    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
        if (data == "[DONE]") {
            val fullContent = contentBuilder.toString()

            // 检查是否有 function calling 的工具调用
            if (finishReason == "tool_calls" && toolCalls.isNotEmpty()) {
                onToolCall(toolCalls)
                return
            }

            // 检查文本中是否有 XML 格式的工具调用（不支持 function calling 的模型）
            if (detectXmlToolCalls && fullContent.isNotBlank()) {
                val (textContent, xmlToolCalls) = ToolCallBridge.parseXmlToolCalls(fullContent)
                if (xmlToolCalls != null && xmlToolCalls.length() > 0) {
                    val xmlAccumulators = mutableListOf<ToolCallAccumulator>()
                    for (i in 0 until xmlToolCalls.length()) {
                        val tc = xmlToolCalls.getJSONObject(i)
                        val fn = tc.getJSONObject("function")
                        xmlAccumulators.add(ToolCallAccumulator().apply {
                            this.id = tc.optString("id", "call_xml_$i")
                            this.name = fn.getString("name")
                            this.argumentsBuilder.append(fn.getString("arguments"))
                        })
                    }
                    onToolCall(xmlAccumulators)
                    return
                }
            }

            onComplete(fullContent, false)
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
                    // 思考内容（DeepSeek R1 reasoning_content）
                    val thinkingContent = delta.optString("reasoning_content", "")
                    if (thinkingContent.isNotEmpty()) {
                        thinkingBuilder.append(thinkingContent)
                        // 不输出思考内容到 UI，但保留供调试
                    }

                    // 文本内容
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty()) {
                        contentBuilder.append(content)
                        onContent(content)
                    }

                    // 工具调用增量
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
            Log.w(tag, "SSE 解析错误: ${e.message}")
        }
    }

    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
        val errorMsg = when {
            t != null -> "连接失败: ${t.message}"
            response != null -> {
                val statusCode = response.code
                when (statusCode) {
                    401 -> "API Key 无效 (401)"
                    403 -> "访问被拒绝 (403)"
                    404 -> "API 端点不存在 (404)"
                    429 -> "请求过于频繁 (429)"
                    in 500..599 -> "服务器错误 ($statusCode)"
                    else -> "HTTP $statusCode: ${response.message}"
                }
            }
            else -> "未知错误"
        }
        Log.e(tag, errorMsg, t)
        onError(errorMsg)
    }

    override fun onClosed(eventSource: EventSource) {
        Log.i(tag, "SSE 流已关闭")
    }

    /**
     * 获取完整内容（用于调试）
     */
    fun getFullContent(): String = contentBuilder.toString()
}

/**
 * 工具调用累积器（从 NativeAgentService 提取为公共类）。
 * 用于累积 SSE 流中的工具调用增量数据。
 */
class ToolCallAccumulator {
    var id: String = ""
    var name: String = ""
    val argumentsBuilder = StringBuilder()

    /** 构建为 OpenAI 格式的 ToolCall 对象 */
    fun toToolCall(): ToolCall {
        val args = runCatching { JSONObject(argumentsBuilder.toString()) }.getOrDefault(JSONObject())
        return ToolCall(
            id = id.ifBlank { "call_${name}_${System.currentTimeMillis()}" },
            name = name,
            arguments = args
        )
    }
}
