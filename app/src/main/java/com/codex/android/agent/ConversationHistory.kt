package com.codex.android.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 对话历史管理器。
 *
 * 维护 Agent 与 LLM 之间的完整对话上下文，支持多种消息类型的添加、
 * token 估算和 API 请求格式转换。内部使用 synchronized 保证线程安全。
 *
 * 消息类型包括：
 * - [ConversationItem.UserMessage] 用户输入
 * - [ConversationItem.AssistantMessage] 助手回复
 * - [ConversationItem.ToolCallItem] 工具调用请求
 * - [ConversationItem.ToolResultItem] 工具执行结果
 * - [ConversationItem.SystemMessage] 系统消息
 * - [ConversationItem.CompactionMarker] 压缩标记（记录历史压缩事件）
 */
class ConversationHistory {

    companion object {
        private const val TAG = "ConversationHistory"

        /** Token 估算系数：约 4 个字符对应 1 个 token */
        private const val CHARS_PER_TOKEN = 4
    }

    /** 线程安全的消息列表 */
    private val items = mutableListOf<ConversationItem>()

    /**
     * 对话条目密封类。
     *
     * 定义对话历史中所有可能的消息类型，每种类型对应不同的角色和内容结构。
     */
    sealed class ConversationItem {

        /** 用户消息 */
        data class UserMessage(
            val content: String,
            val timestamp: Long = System.currentTimeMillis()
        ) : ConversationItem()

        /** 助手消息（LLM 的文本回复） */
        data class AssistantMessage(
            val content: String,
            val toolCalls: List<ToolCall> = emptyList(),
            val timestamp: Long = System.currentTimeMillis()
        ) : ConversationItem()

        /** 工具调用请求（LLM 请求执行某个工具） */
        data class ToolCallItem(
            val toolCall: ToolCall,
            val timestamp: Long = System.currentTimeMillis()
        ) : ConversationItem()

        /** 工具执行结果 */
        data class ToolResultItem(
            val result: ToolResult,
            val timestamp: Long = System.currentTimeMillis()
        ) : ConversationItem()

        /** 系统消息（开发者指令、环境上下文等） */
        data class SystemMessage(
            val content: String,
            val role: String = "developer",
            val timestamp: Long = System.currentTimeMillis()
        ) : ConversationItem()

        /** 压缩标记（记录对话历史被压缩的事件） */
        data class CompactionMarker(
            val tokensBefore: Int,
            val tokensAfter: Int,
            val summary: String,
            val timestamp: Long = System.currentTimeMillis()
        ) : ConversationItem()
    }

    // ========== 消息添加方法 ==========

    /**
     * 添加用户消息。
     *
     * @param content 用户输入的文本内容
     */
    fun addUserMessage(content: String) {
        synchronized(items) {
            items.add(ConversationItem.UserMessage(content))
        }
    }

    /**
     * 添加助手消息。
     *
     * @param content 助手的文本回复
     * @param toolCalls 助手请求的工具调用列表（如有）
     */
    fun addAssistantMessage(content: String, toolCalls: List<ToolCall> = emptyList()) {
        synchronized(items) {
            items.add(ConversationItem.AssistantMessage(content, toolCalls))
        }
    }

    /**
     * 添加工具执行结果。
     *
     * @param result 工具执行结果
     */
    fun addToolResult(result: ToolResult) {
        synchronized(items) {
            items.add(ConversationItem.ToolResultItem(result))
        }
    }

    /**
     * 添加系统消息。
     *
     * @param content 系统消息内容
     * @param role 消息角色（默认 "developer"）
     */
    fun addSystemMessage(content: String, role: String = "developer") {
        synchronized(items) {
            items.add(ConversationItem.SystemMessage(content, role))
        }
    }

    /**
     * 添加工具调用条目。
     *
     * @param toolCall 工具调用请求
     */
    fun addToolCall(toolCall: ToolCall) {
        synchronized(items) {
            items.add(ConversationItem.ToolCallItem(toolCall))
        }
    }

    // ========== 查询方法 ==========

    /**
     * 获取当前对话历史的快照。
     *
     * @return 对话条目的不可变副本
     */
    fun getItems(): List<ConversationItem> {
        synchronized(items) {
            return items.toList()
        }
    }

    /**
     * 获取对话条目数量。
     */
    fun size(): Int {
        synchronized(items) {
            return items.size
        }
    }

    /**
     * 判断对话历史是否为空。
     */
    fun isEmpty(): Boolean {
        synchronized(items) {
            return items.isEmpty()
        }
    }

    // ========== Token 估算 ==========

    /**
     * 估算当前对话历史的总 token 数。
     *
     * 使用简单启发式估算：4 个字符 ≈ 1 个 token。
     * 估算内容包括消息文本、工具调用参数和工具结果输出。
     * 这只是一个粗略估算，实际 token 数取决于分词器。
     *
     * @return 估算的 token 总数
     */
    fun estimatedTokens(): Int {
        synchronized(items) {
            var totalChars = 0
            for (item in items) {
                totalChars += when (item) {
                    is ConversationItem.UserMessage -> item.content.length
                    is ConversationItem.AssistantMessage -> {
                        item.content.length + item.toolCalls.sumOf { it.arguments.toString().length + it.name.length }
                    }
                    is ConversationItem.ToolCallItem -> {
                        item.toolCall.arguments.toString().length + item.toolCall.name.length
                    }
                    is ConversationItem.ToolResultItem -> item.result.output.length
                    is ConversationItem.SystemMessage -> item.content.length
                    is ConversationItem.CompactionMarker -> item.summary.length
                }
            }
            return totalChars / CHARS_PER_TOKEN
        }
    }

    // ========== API 格式构建 ==========

    /**
     * 构建 OpenAI API 请求格式的 input 消息数组。
     *
     * 将内部对话历史转换为符合 OpenAI Chat Completions API 格式的
     * JSONArray，可直接用于 LLM 请求的 messages 字段。
     *
     * 转换规则：
     * - UserMessage → {role: "user", content: ...}
     * - AssistantMessage → {role: "assistant", content: ..., tool_calls: [...]}
     * - ToolResultItem → {role: "tool", tool_call_id: ..., content: ...}
     * - SystemMessage → {role: role, content: ...}
     * - CompactionMarker → {role: "system", content: summary}
     * - ToolCallItem → 合并到前一个 AssistantMessage 的 tool_calls 中
     *
     * @return OpenAI API 格式的 messages JSONArray
     */
    fun buildApiInput(): JSONArray {
        val messages = JSONArray()
        synchronized(items) {
            for (item in items) {
                when (item) {
                    is ConversationItem.UserMessage -> {
                        messages.put(JSONObject().apply {
                            put("role", "user")
                            put("content", item.content)
                        })
                    }
                    is ConversationItem.AssistantMessage -> {
                        val msg = JSONObject().apply {
                            put("role", "assistant")
                            put("content", item.content.ifEmpty { null })
                        }
                        if (item.toolCalls.isNotEmpty()) {
                            val toolCallsArray = JSONArray()
                            item.toolCalls.forEach { tc ->
                                toolCallsArray.put(tc.toApiFormat())
                            }
                            msg.put("tool_calls", toolCallsArray)
                        }
                        messages.put(msg)
                    }
                    is ConversationItem.ToolCallItem -> {
                        // 独立的 ToolCallItem 转换为包含 tool_calls 的 assistant 消息
                        val msg = JSONObject().apply {
                            put("role", "assistant")
                            put("content", JSONObject.NULL)
                            put("tool_calls", JSONArray().apply {
                                put(item.toolCall.toApiFormat())
                            })
                        }
                        messages.put(msg)
                    }
                    is ConversationItem.ToolResultItem -> {
                        messages.put(item.result.toApiFormat())
                    }
                    is ConversationItem.SystemMessage -> {
                        messages.put(JSONObject().apply {
                            put("role", item.role)
                            put("content", item.content)
                        })
                    }
                    is ConversationItem.CompactionMarker -> {
                        messages.put(JSONObject().apply {
                            put("role", "system")
                            put("content", "[对话历史已被压缩] ${item.summary}")
                        })
                    }
                }
            }
        }
        return messages
    }

    // ========== 压缩支持 ==========

    /**
     * 执行对话历史压缩。
     *
     * 当估算 token 数超过阈值时，保留 SystemMessage 和最近的对话内容，
     * 将较早的历史替换为压缩摘要，以控制上下文长度。
     *
     * @param summary 压缩后的历史摘要文本
     * @param keepRecent 保留最近的 N 条对话条目（默认 6）
     * @return 被移除的条目数量
     */
    fun compact(summary: String, keepRecent: Int = 6): Int {
        synchronized(items) {
            val tokensBefore = estimatedTokens()

            // 分离系统消息和普通消息
            val systemMessages = items.filterIsInstance<ConversationItem.SystemMessage>()
            val nonSystemItems = items.filter { it !is ConversationItem.SystemMessage }

            if (nonSystemItems.size <= keepRecent) {
                Log.d(TAG, "对话历史较短，无需压缩")
                return 0
            }

            // 保留最近的条目
            val recentItems = nonSystemItems.takeLast(keepRecent)
            val removedCount = nonSystemItems.size - recentItems.size

            // 重建消息列表：系统消息 + 压缩标记 + 最近消息
            items.clear()
            systemMessages.forEach { items.add(it) }
            items.add(ConversationItem.CompactionMarker(
                tokensBefore = tokensBefore,
                tokensAfter = estimatedTokens(),
                summary = summary
            ))
            recentItems.forEach { items.add(it) }

            val tokensAfter = estimatedTokens()
            Log.i(TAG, "对话压缩完成: $tokensBefore → $tokensAfter tokens, 移除 $removedCount 条")

            // 更新压缩标记中的 tokensAfter
            val markerIndex = items.indexOfFirst { it is ConversationItem.CompactionMarker }
            if (markerIndex >= 0) {
                val marker = items[markerIndex] as ConversationItem.CompactionMarker
                items[markerIndex] = marker.copy(tokensAfter = tokensAfter)
            }

            return removedCount
        }
    }

    /**
     * 清除所有对话历史。
     */
    fun clear() {
        synchronized(items) {
            items.clear()
        }
    }

    /**
     * 获取对话历史的可读摘要（用于调试）。
     */
    override fun toString(): String {
        synchronized(items) {
            return "ConversationHistory(size=${items.size}, estimatedTokens=${estimatedTokens()})"
        }
    }
}
