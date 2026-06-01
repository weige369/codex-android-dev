package com.codex.android.agent

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * 工具调用桥接器。
 *
 * 移植自 Operit StructuredToolCallBridge，核心功能：
 * 1. OpenAI function calling (tool_calls) → XML 工具调用标记
 * 2. XML 工具调用标记 → OpenAI function calling 格式
 * 3. 工具定义 JSON 构建（AgentTool → OpenAI tools 格式）
 * 4. 对话历史中 XML/JSON 工具调用的统一处理
 *
 * 为什么需要这个？
 * - 不支持 function calling 的模型（如部分 DeepSeek 版本），LLM 会在文本中输出 XML 工具调用
 * - 支持 function calling 的模型，我们用标准 tool_calls API
 * - Bridge 让两种模式无缝互转，Agent 循环不关心底层是 XML 还是 JSON
 *
 * 适配说明（vs Operit 原版）：
 * - 去掉 PromptTurn 依赖，直接用 JSONObject 消息
 * - 去掉 package_proxy 包代理逻辑（我们不需要）
 * - 去掉 MNN Chat 历史编译（我们不用 MNN）
 * - 简化为专注于工具调用的转换
 */
object ToolCallBridge {

    private const val TAG = "ToolCallBridge"

    // ===== 工具定义构建 =====

    /**
     * 将 AgentTool 列表构建为 OpenAI tools JSON 数组。
     */
    fun buildToolsJson(tools: List<AgentTool>): JSONArray {
        val toolsArray = JSONArray()
        for (tool in tools) {
            toolsArray.put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameterSchema)
                })
            })
        }
        return toolsArray
    }

    /**
     * 从 CapabilityRegistry 获取工具定义并构建 tools JSON。
     */
    fun buildToolsFromRegistry(registry: CapabilityRegistry): JSONArray {
        return registry.getToolDefinitions()
    }

    // ===== XML → OpenAI tool_calls =====

    /**
     * 解析 LLM 文本输出中的 XML 工具调用，转为 OpenAI tool_calls JSON 数组。
     *
     * 输入示例:
     *   <tool name="shell"><param name="command">ls -la</param></tool>
     *
     * 输出示例:
     *   [{"id":"call_shell_abc1_0","type":"function","function":{"name":"shell","arguments":"{\"command\":\"ls -la\"}"}}]
     *
     * @return Pair(剩余文本, tool_calls JSONArray 或 null)
     */
    fun parseXmlToolCalls(content: String): Pair<String, JSONArray?> {
        val matches = ChatMarkupPatterns.toolCallPattern.findAll(content)
        if (!matches.any()) {
            return content to null
        }

        val toolCalls = JSONArray()
        var textContent = content
        var callIndex = 0

        matches.forEach { match ->
            val toolName = match.groupValues[2]
            val toolBody = match.groupValues[3]

            val params = JSONObject()
            ChatMarkupPatterns.toolParamPattern.findAll(toolBody).forEach { paramMatch ->
                val paramName = paramMatch.groupValues[1]
                val paramValue = XmlEscaper.unescape(paramMatch.groupValues[2].trim())
                params.put(paramName, paramValue)
            }

            val toolNamePart = sanitizeToolCallId(toolName)
            val hashPart = stableIdHashPart("${toolName}:${params}")
            val callId = sanitizeToolCallId("call_${toolNamePart}_${hashPart}_$callIndex")

            toolCalls.put(JSONObject().apply {
                put("id", callId)
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", toolName)
                    put("arguments", params.toString())
                })
            })

            callIndex++
            textContent = textContent.replace(match.value, "")
        }

        return textContent.trim() to if (toolCalls.length() > 0) toolCalls else null
    }

    // ===== OpenAI tool_calls → XML =====

    /**
     * 将 OpenAI tool_calls JSON 数组转为 XML 工具调用标记。
     *
     * 输入: [{"id":"call_xxx","type":"function","function":{"name":"shell","arguments":"{\"command\":\"ls\"}"}}]
     * 输出: <tool_a3Kx name="shell">\n<param name="command">ls</param>\n</tool_a3Kx>
     */
    fun convertToolCallsToXml(toolCalls: JSONArray): String {
        val xml = StringBuilder()

        for (i in 0 until toolCalls.length()) {
            val toolCall = toolCalls.optJSONObject(i) ?: continue
            val function = toolCall.optJSONObject("function") ?: continue
            val name = function.optString("name", "")
            if (name.isBlank()) continue

            val argumentsRaw = function.optString("arguments", "")
            val paramsObj = runCatching { JSONObject(argumentsRaw) }.getOrNull()

            val toolTagName = ChatMarkupPatterns.generateRandomToolTagName()
            xml.append("<")
                .append(toolTagName)
                .append(" name=\"")
                .append(name)
                .append("\">")

            if (paramsObj != null) {
                val keys = paramsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = paramsObj.opt(key)
                    xml.append("\n<param name=\"")
                        .append(key)
                        .append("\">")
                        .append(XmlEscaper.escape(value?.toString() ?: ""))
                        .append("</param>")
                }
            } else if (argumentsRaw.isNotBlank()) {
                xml.append("\n<param name=\"_raw_arguments\">")
                    .append(XmlEscaper.escape(argumentsRaw))
                    .append("</param>")
            }

            xml.append("\n</")
                .append(toolTagName)
                .append(">\n")
        }

        return xml.toString().trim()
    }

    /**
     * 将 LLM 文本中可能包含的 JSON 格式 tool_calls 转为 XML。
     * 用于不支持 function calling 但输出 JSON 的模型。
     */
    fun convertToolCallPayloadToXml(content: String): String {
        if (content.isBlank()) return content
        if (ChatMarkupPatterns.containsAnyToolLikeTag(content)) return content

        val toolCalls = parsePossibleToolCallsFromText(content) ?: return content
        val xml = convertToolCallsToXml(toolCalls)
        return if (xml.isBlank()) content else xml
    }

    // ===== 工具结果处理 =====

    /**
     * 解析 XML 格式的工具结果。
     * @return Pair(剩余文本, 工具结果列表)
     */
    fun parseXmlToolResults(content: String): Pair<String, List<ToolResultRecord>> {
        val matches = ChatMarkupPatterns.toolResultAnyPattern.findAll(content)
        if (!matches.any()) {
            return content to emptyList()
        }

        val results = mutableListOf<ToolResultRecord>()
        var textContent = content

        matches.forEach { match ->
            val fullContent = match.groupValues[2].trim()
            val contentMatch = ChatMarkupPatterns.contentTag.find(fullContent)
            val resultContent = if (contentMatch != null) {
                contentMatch.groupValues[1].trim()
            } else {
                fullContent
            }
            val resultName = ChatMarkupPatterns.nameAttr.find(match.value)?.groupValues?.getOrNull(1)
            results.add(ToolResultRecord(resultName, resultContent))
            textContent = textContent.replace(match.value, "").trim()
        }

        return textContent.trim() to results
    }

    /**
     * 将工具执行结果构建为 XML 工具结果标记。
     */
    fun buildToolResultXml(toolName: String, content: String, status: String = "success"): String {
        return """<tool_result name="$toolName" status="$status"><content>${XmlEscaper.escape(content)}</content></tool_result>"""
    }

    // ===== JSON 工具调用检测 =====

    /**
     * 从文本中尝试解析 JSON 格式的工具调用。
     * 覆盖多种可能格式：标准 tool_calls、function_call、output 数组等。
     */
    private fun parsePossibleToolCallsFromText(content: String): JSONArray? {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return null

        val candidates = linkedSetOf<String>()
        candidates.add(trimmed)

        // 尝试提取 JSON
        extractJson(trimmed)?.let { candidates.add(it) }
        extractJsonArray(trimmed)?.let { candidates.add(it) }

        // 提取 ```json ... ``` 代码块
        val fencedRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        fencedRegex.findAll(trimmed).forEach { match ->
            val fenced = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (fenced.isNotBlank()) candidates.add(fenced)
        }

        for (candidate in candidates) {
            val fromObject = runCatching {
                extractToolCallsFromAny(JSONObject(candidate))
            }.getOrNull()
            if (fromObject != null && fromObject.length() > 0) return fromObject

            val fromArray = runCatching {
                extractToolCallsFromArray(JSONArray(candidate))
            }.getOrNull()
            if (fromArray != null && fromArray.length() > 0) return fromArray
        }

        return null
    }

    private fun extractToolCallsFromAny(root: JSONObject): JSONArray? {
        root.optJSONArray("tool_calls")?.let { array ->
            val normalized = normalizeToolCalls(array)
            if (normalized.length() > 0) return normalized
        }

        root.optJSONObject("function_call")?.let { functionCall ->
            val normalized = normalizeSingleToolCall(functionCall, 0)
            if (normalized != null) return JSONArray().put(normalized)
        }

        if (root.optString("type", "") == "function_call") {
            val normalized = normalizeSingleToolCall(root, 0)
            if (normalized != null) return JSONArray().put(normalized)
        }

        root.optJSONArray("output")?.let { outputArray ->
            val normalized = normalizeToolCalls(outputArray)
            if (normalized.length() > 0) return normalized
        }

        return null
    }

    private fun extractToolCallsFromArray(root: JSONArray): JSONArray? {
        val normalized = normalizeToolCalls(root)
        return if (normalized.length() > 0) normalized else null
    }

    private fun normalizeToolCalls(source: JSONArray): JSONArray {
        val normalized = JSONArray()
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            val normalizedCall = normalizeSingleToolCall(item, i) ?: continue
            normalized.put(normalizedCall)
        }
        return normalized
    }

    private fun normalizeSingleToolCall(raw: JSONObject, index: Int): JSONObject? {
        val functionObject = raw.optJSONObject("function")
        val functionCallObject = raw.optJSONObject("function_call")

        val name = when {
            functionObject != null -> functionObject.optString("name", "")
            raw.optString("name", "").isNotBlank() -> raw.optString("name", "")
            functionCallObject != null -> functionCallObject.optString("name", "")
            else -> ""
        }
        if (name.isBlank()) return null

        val argumentsValue: Any? = when {
            functionObject != null && functionObject.has("arguments") -> functionObject.opt("arguments")
            raw.has("arguments") -> raw.opt("arguments")
            functionCallObject != null && functionCallObject.has("arguments") -> functionCallObject.opt("arguments")
            else -> null
        }

        val arguments = when (argumentsValue) {
            is JSONObject, is JSONArray -> argumentsValue.toString()
            is String -> if (argumentsValue.isBlank()) "{}" else argumentsValue
            null -> "{}"
            else -> argumentsValue.toString()
        }

        val rawId = raw.optString("id", "")
            .ifBlank { raw.optString("call_id", "") }
            .ifBlank { "call_${sanitizeToolCallId(name)}_$index" }
        val callId = sanitizeToolCallId(rawId)

        return JSONObject().apply {
            put("id", callId)
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("arguments", arguments)
            })
        }
    }

    // ===== 工具结果记录 =====

    data class ToolResultRecord(
        val name: String?,
        val content: String
    )

    // ===== 辅助方法 =====

    private fun sanitizeToolCallId(raw: String): String {
        val output = buildString(raw.length) {
            raw.forEach { ch ->
                if (ch.isLetterOrDigit() || ch == '_' || ch == '-') {
                    append(ch)
                } else {
                    append('_')
                }
            }
        }.replace(Regex("_+"), "_").trim('_')
        return if (output.isEmpty()) "call" else output
    }

    private fun stableIdHashPart(raw: String): String {
        val hash = raw.hashCode()
        val positive = if (hash == Int.MIN_VALUE) 0 else abs(hash)
        val base = positive.toString(36).filter { it.isLetterOrDigit() }.lowercase()
        return if (base.isEmpty()) "0" else base
    }

    private fun extractJson(text: String): String? {
        // 简单提取：找到第一个 { ... } JSON 对象
        var depth = 0
        var start = -1
        for ((i, ch) in text.withIndex()) {
            when (ch) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0 && start >= 0) return text.substring(start, i + 1) }
            }
        }
        return null
    }

    private fun extractJsonArray(text: String): String? {
        var depth = 0
        var start = -1
        for ((i, ch) in text.withIndex()) {
            when (ch) {
                '[' -> { if (depth == 0) start = i; depth++ }
                ']' -> { depth--; if (depth == 0 && start >= 0) return text.substring(start, i + 1) }
            }
        }
        return null
    }

    // ===== XML 转义 =====

    private object XmlEscaper {
        fun escape(text: String): String {
            return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
        }

        fun unescape(text: String): String {
            return text.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
        }
    }
}
