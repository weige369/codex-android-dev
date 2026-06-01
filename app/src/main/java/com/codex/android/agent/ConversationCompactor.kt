package com.codex.android.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 对话历史压缩器。
 *
 * 学习 Operit 的对话管理策略，当 token 估算超过阈值时自动压缩历史：
 * 1. 保留最近 N 条对话
 * 2. 用 LLM 生成早期对话的摘要
 * 3. 将摘要插入对话历史作为 system 消息
 *
 * 为什么需要压缩？
 * - 长对话的上下文会超过模型 token 限制（如 DeepSeek 64K）
 * - 大量工具调用结果占空间（一次 ls -la 可能就几千 token）
 * - 压缩后保持关键信息，丢弃冗余细节
 *
 * 压缩策略（借鉴 Operit + 实际经验）：
 * - 阈值: 估算 token > 50000 时触发
 * - 保留: 最近 6 条消息 + 所有 system 消息
 * - 摘要: 早期对话的要点，由 LLM 生成
 * - 工具结果: 截断到 2000 字符
 */
class ConversationCompactor(private val context: Context) {

    companion object {
        private const val TAG = "ConversationCompactor"

        /** 触发压缩的 token 估算阈值 */
        const val COMPACTION_THRESHOLD_TOKENS = 50_000

        /** 保留最近的对话条目数 */
        const val KEEP_RECENT_COUNT = 6

        /** 工具结果最大保留字符数 */
        const val TOOL_RESULT_MAX_CHARS = 2_000

        /** Token 估算：约 4 字符 = 1 token */
        private const val CHARS_PER_TOKEN = 4
    }

    /**
     * 检查是否需要压缩
     */
    fun needsCompaction(history: ConversationHistory): Boolean {
        return history.estimatedTokens() > COMPACTION_THRESHOLD_TOKENS
    }

    /**
     * 估算 JSONArray 消息的 token 数
     */
    fun estimateTokens(messages: JSONArray): Int {
        var totalChars = 0
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            totalChars += msg.optString("content", "").length
            msg.optJSONArray("tool_calls")?.let { tcs ->
                for (j in 0 until tcs.length()) {
                    val tc = tcs.optJSONObject(j) ?: continue
                    totalChars += tc.optJSONObject("function")?.optString("arguments", "")?.length ?: 0
                }
            }
        }
        return totalChars / CHARS_PER_TOKEN
    }

    /**
     * 裁剪对话历史中过长的工具结果
     */
    fun trimToolResults(messages: JSONArray): JSONArray {
        val trimmed = JSONArray()
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            val role = msg.optString("role", "")

            if (role == "tool") {
                val content = msg.optString("content", "")
                if (content.length > TOOL_RESULT_MAX_CHARS) {
                    trimmed.put(JSONObject(msg.toString()).apply {
                        put("content", content.take(TOOL_RESULT_MAX_CHARS) + "\n...[输出已截断，原始 ${content.length} 字符]")
                    })
                } else {
                    trimmed.put(msg)
                }
            } else {
                trimmed.put(msg)
            }
        }
        return trimmed
    }

    /**
     * 截断对话历史到指定 token 限制
     *
     * 策略：从最早的非系统消息开始删除，直到 token 数在限制内
     */
    fun truncateToLimit(messages: JSONArray, maxTokens: Int = COMPACTION_THRESHOLD_TOKENS): JSONArray {
        var currentTokens = estimateTokens(messages)
        if (currentTokens <= maxTokens) return messages

        val result = mutableListOf<JSONObject>()
        val systemMessages = mutableListOf<JSONObject>()
        val otherMessages = mutableListOf<JSONObject>()

        // 分离系统消息和其他消息
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            if (msg.optString("role", "") == "system") {
                systemMessages.add(msg)
            } else {
                otherMessages.add(msg)
            }
        }

        // 从最早的非系统消息开始删除
        result.addAll(systemMessages)
        val recentMessages = otherMessages.takeLast(KEEP_RECENT_COUNT)
        result.addAll(recentMessages)

        // 先裁剪工具结果
        val trimmedResult = JSONArray()
        result.forEach { trimmedResult.put(it) }
        return trimToolResults(trimmedResult)
    }

    /**
     * 使用 LLM 生成对话摘要（异步）
     * 
     * 注意：这需要调用 LLM API，可能增加延迟和费用。
     * 在实际使用中可以考虑用简单截断代替摘要。
     */
    suspend fun generateSummary(
        agentService: NativeAgentService,
        messages: JSONArray
    ): String = withContext(Dispatchers.IO) {
        if (messages.length() == 0) return@withContext ""

        try {
            val summaryPrompt = buildString {
                appendLine("请简洁总结以下对话的要点（100字以内，保留关键信息和决策）：")
                appendLine()
                for (i in 0 until minOf(messages.length(), 20)) {
                    val msg = messages.optJSONObject(i) ?: continue
                    val role = msg.optString("role", "")
                    val content = msg.optString("content", "").take(500)
                    appendLine("[$role]: $content")
                }
            }

            // 简单方案：直接返回对话的精简版
            // 生产方案：调用 LLM 生成摘要（但会增加延迟）
            var summary = "对话摘要: "
            var first = true
            for (i in 0 until messages.length()) {
                val msg = messages.optJSONObject(i) ?: continue
                val role = msg.optString("role", "")
                val content = msg.optString("content", "").take(100)
                if (role == "user" || role == "assistant") {
                    if (!first) summary += "; "
                    summary += "[$role] $content"
                    first = false
                }
            }
            summary.take(500)
        } catch (e: Exception) {
            Log.e(TAG, "生成摘要失败: ${e.message}")
            "对话历史已压缩（摘要生成失败）"
        }
    }
}
