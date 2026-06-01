package com.codex.android.agent

import java.security.SecureRandom

/**
 * 工具调用标记语言的正则模式。
 *
 * 移植自 Operit ChatMarkupRegex，用于：
 * 1. 解析 LLM 输出中的 XML 格式工具调用（<tool name="x"><param name="y">...</param></tool>）
 * 2. 解析工具结果标记（<tool_result name="x">...</tool_result>）
 * 3. 支持 ToolCallBridge 在 XML ↔ OpenAI function calling 之间互转
 *
 * 为什么需要 XML 工具调用？
 * - 有些模型不支持 function calling API（XML 标记是通用 fallback）
 * - 有些模型支持但经常格式错误
 * - XML 标记更容易让 LLM 理解和生成，兼容性更广
 * - Operit 实测证明 XML 标记 + Bridge 转换是最稳定的方案
 */
object ChatMarkupPatterns {

    // ===== 标签名正则 =====

    /** 工具调用标签名: tool, tool_xxx (但不含 tool_result) */
    const val TOOL_TAG_NAME_REGEX_SOURCE = "tool(?:_(?!result(?:_|$))[A-Za-z0-9_]+)?"

    /** 工具结果标签名: tool_result, tool_result_xxx */
    const val TOOL_RESULT_TAG_NAME_REGEX_SOURCE = "tool_result(?:_[A-Za-z0-9_]+)?"

    private val toolTagNameRegex = Regex("^$TOOL_TAG_NAME_REGEX_SOURCE$", RegexOption.IGNORE_CASE)
    private val toolResultTagNameRegex = Regex("^$TOOL_RESULT_TAG_NAME_REGEX_SOURCE$", RegexOption.IGNORE_CASE)

    // ===== 工具调用匹配 =====

    /**
     * 匹配完整工具调用: <tool name="shell"><param name="command">ls</param></tool>
     * 捕获组: (1) 标签名 (2) 工具名 (3) 标签体
     */
    val toolCallPattern = Regex(
        """<($TOOL_TAG_NAME_REGEX_SOURCE)\b[^>]*name="([^"]+)"[^>]*>([\s\S]*?)</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /**
     * 匹配工具调用标签（含自闭合）
     */
    val toolTag = Regex(
        """<($TOOL_TAG_NAME_REGEX_SOURCE)\b[\s\S]*?</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    val toolSelfClosingTag = Regex(
        """<${TOOL_TAG_NAME_REGEX_SOURCE}\b[^>]*/>""",
        RegexOption.IGNORE_CASE
    )

    // ===== 工具结果匹配 =====

    val toolResultTag = Regex(
        """<($TOOL_RESULT_TAG_NAME_REGEX_SOURCE)\b[\s\S]*?</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    val toolResultAnyPattern = Regex(
        """<($TOOL_RESULT_TAG_NAME_REGEX_SOURCE)\b[^>]*>([\s\S]*?)</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    val toolResultWithNameAnyPattern = Regex(
        """<($TOOL_RESULT_TAG_NAME_REGEX_SOURCE)\b[^>]*name="([^"]+)"[^>]*>([\s\S]*?)</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    // ===== 参数匹配 =====

    /** 匹配参数: <param name="command">ls</param> */
    val toolParamPattern = Regex("""<param\s+name="([^"]+)">([\s\S]*?)</param>""")

    // ===== 属性匹配 =====

    val nameAttr = Regex("""name\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    val statusAttr = Regex("""status\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    val contentTag = Regex("""<content>([\s\S]*?)</content>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    val errorTag = Regex("""<error>([\s\S]*?)</error>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    // ===== 检测工具 =====

    fun containsAnyToolLikeTag(text: String): Boolean {
        return toolStartTagRegex.containsMatchIn(text) || toolResultStartTagRegex.containsMatchIn(text)
    }

    private val toolStartTagRegex = Regex("""<(?:$TOOL_TAG_NAME_REGEX_SOURCE)\b""", RegexOption.IGNORE_CASE)
    private val toolResultStartTagRegex = Regex("""<(?:$TOOL_RESULT_TAG_NAME_REGEX_SOURCE)\b""", RegexOption.IGNORE_CASE)

    // ===== 工具标签名生成 =====

    private val randomTagCodeChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private val randomTagCodeSource = SecureRandom()

    /** 生成随机工具标签后缀，避免 LLM 预测固定标签名 */
    fun generateRandomToolTagName(): String {
        val suffix = buildString {
            repeat(4) {
                append(randomTagCodeChars[randomTagCodeSource.nextInt(randomTagCodeChars.length)])
            }
        }
        return "tool_$suffix"
    }

    fun isToolTagName(tagName: String): Boolean = toolTagNameRegex.matches(tagName)
    fun isToolResultTagName(tagName: String): Boolean = toolResultTagNameRegex.matches(tagName)
}
