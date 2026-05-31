package com.codex.android.tools

import android.util.Log
import com.codex.android.agent.AgentMode
import com.codex.android.security.SecurityPolicy
import org.json.JSONObject
import java.io.File

/**
 * 搜索工具。
 *
 * 提供三种搜索模式：
 * - **file** — 按文件名模式搜索（使用简单的通配符匹配）。
 * - **content** — 按内容关键词搜索文件（全文搜索）。
 * - **grep** — 类似 grep 的正则搜索，返回匹配行及其上下文。
 *
 * 结果限制最多 [MAX_RESULTS] 条，防止大量匹配结果撑爆 LLM 上下文。
 * 安全等级检查与 [FileReadTool] 一致。
 */
class SearchTool(
    private val context: android.content.Context
) : ToolHandler {

    companion object {
        private const val TAG = "SearchTool"

        /** 最大返回结果数。 */
        private const val MAX_RESULTS = 50

        /** grep 模式下每条匹配的上下文行数。 */
        private const val GREP_CONTEXT_LINES = 2
    }

    override val name: String = "search"

    override val description: String = buildString {
        appendLine("搜索文件或文件内容。")
        appendLine("支持三种模式：")
        appendLine("  - file: 按文件名模式搜索（支持 * 通配符）")
        appendLine("  - content: 按内容关键词搜索文件")
        appendLine("  - grep: 正则搜索，返回匹配行及上下文")
        appendLine("最多返回 50 条结果。")
    }

    override val parameters: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("pattern", JSONObject().apply {
                put("type", "string")
                put("description", "搜索模式：文件名通配符 / 内容关键词 / 正则表达式（必填）")
            })
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "搜索根目录（可选，默认为工作目录）")
            })
            put("type", JSONObject().apply {
                put("type", "string")
                put("description", "搜索类型：file（按文件名）/ content（按内容）/ grep（正则搜索），默认 file")
                put("enum", org.json.JSONArray().apply {
                    put("file")
                    put("content")
                    put("grep")
                })
            })
        })
        put("required", org.json.JSONArray().apply {
            put("pattern")
        })
    }

    override fun validateParams(args: JSONObject): JSONObject? {
        if (!args.has("pattern") || args.optString("pattern").isBlank()) {
            return JSONObject().apply {
                put("error", "缺少必填参数 'pattern'")
                put("field", "pattern")
            }
        }

        val type = args.optString("type", "file")
        if (type !in setOf("file", "content", "grep")) {
            return JSONObject().apply {
                put("error", "type 必须为 file / content / grep 之一")
                put("field", "type")
            }
        }

        return null
    }

    override suspend fun execute(params: JSONObject, mode: AgentMode): RawToolResult {
        val pattern = params.getString("pattern")
        val searchPath = params.optString("path", null)
        val searchType = params.optString("type", "file")

        // 确定搜索根目录
        val rootDir = resolveSearchRoot(searchPath)
            ?: return RawToolResult(
                output = "搜索路径不存在或不是目录: ${searchPath ?: "(工作目录)"}",
                isError = true
            )

        // 安全等级检查
        val denial = SecurityPolicy.checkFileAccess(context, rootDir.absolutePath)
        if (denial != null) {
            return RawToolResult(output = denial, isError = true)
        }

        return when (searchType) {
            "file" -> searchByFileName(rootDir, pattern)
            "content" -> searchByContent(rootDir, pattern)
            "grep" -> searchByGrep(rootDir, pattern)
            else -> RawToolResult(
                output = "未知搜索类型: $searchType",
                isError = true
            )
        }
    }

    /**
     * 解析搜索根目录。
     */
    private fun resolveSearchRoot(path: String?): File? {
        if (path != null) {
            val dir = File(path)
            return if (dir.exists() && dir.isDirectory) dir else null
        }
        // 默认使用应用沙箱根目录
        val sandbox = SecurityPolicy.sandboxRoot(context)
        return if (sandbox.exists()) sandbox else null
    }

    // ==================== 文件名搜索 ====================

    /**
     * 按文件名模式搜索。
     *
     * 支持简单的通配符：
     * - `*` 匹配任意字符序列
     * - `?` 匹配单个字符
     */
    private fun searchByFileName(rootDir: File, pattern: String): RawToolResult {
        val regex = globToRegex(pattern)
        val results = mutableListOf<String>()
        val truncated = mutableListOf<Boolean>()

        try {
            rootDir.walkTopDown()
                .onEnter { dir ->
                    // 跳过隐藏目录和常见的无关目录
                    !dir.name.startsWith(".") && dir.name !in IGNORED_DIRECTORIES
                }
                .filter { it.isFile }
                .filter { regex.matches(it.name) }
                .take(MAX_RESULTS + 1) // 多取一个以检测是否截断
                .forEach { file ->
                    if (results.size < MAX_RESULTS) {
                        results.add(file.relativeTo(rootDir).path)
                    } else {
                        truncated.add(true)
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "文件名搜索失败", e)
            return RawToolResult(
                output = "文件名搜索失败: ${e.message}",
                isError = true
            )
        }

        val output = buildSearchOutput(results, truncated.isNotEmpty(), "文件名")
        val metadata = JSONObject().apply {
            put("type", "file")
            put("pattern", pattern)
            put("resultCount", results.size)
            put("truncated", truncated.isNotEmpty())
        }

        return RawToolResult(output = output, metadata = metadata)
    }

    // ==================== 内容搜索 ====================

    /**
     * 按内容关键词搜索文件。
     *
     * 返回包含关键词的文件列表及匹配次数。
     */
    private fun searchByContent(rootDir: File, keyword: String): RawToolResult {
        val results = mutableListOf<String>()
        var isTruncated = false

        try {
            rootDir.walkTopDown()
                .onEnter { dir ->
                    !dir.name.startsWith(".") && dir.name !in IGNORED_DIRECTORIES
                }
                .filter { it.isFile && it.canRead() }
                .filter { file ->
                    try {
                        // 跳过二进制文件
                        if (isBinaryFile(file)) return@filter false
                        file.readText(Charsets.UTF_8).contains(keyword, ignoreCase = true)
                    } catch (e: Exception) {
                        false
                    }
                }
                .take(MAX_RESULTS + 1)
                .forEach { file ->
                    if (results.size < MAX_RESULTS) {
                        val relPath = file.relativeTo(rootDir).path
                        val matchCount = try {
                            countKeywordMatches(file, keyword)
                        } catch (e: Exception) {
                            1
                        }
                        results.add("$relPath ($matchCount 匹配)")
                    } else {
                        isTruncated = true
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "内容搜索失败", e)
            return RawToolResult(
                output = "内容搜索失败: ${e.message}",
                isError = true
            )
        }

        val output = buildSearchOutput(results, isTruncated, "内容")
        val metadata = JSONObject().apply {
            put("type", "content")
            put("pattern", keyword)
            put("resultCount", results.size)
            put("truncated", isTruncated)
        }

        return RawToolResult(output = output, metadata = metadata)
    }

    // ==================== Grep 搜索 ====================

    /**
     * 正则搜索，返回匹配行及上下文。
     */
    private fun searchByGrep(rootDir: File, pattern: String): RawToolResult {
        val regex = try {
            Regex(pattern)
        } catch (e: Exception) {
            return RawToolResult(
                output = "正则表达式无效: ${e.message}",
                isError = true
            )
        }

        val results = mutableListOf<String>()
        var totalMatches = 0
        var isTruncated = false

        try {
            rootDir.walkTopDown()
                .onEnter { dir ->
                    !dir.name.startsWith(".") && dir.name !in IGNORED_DIRECTORIES
                }
                .filter { it.isFile && it.canRead() }
                .forEach { file ->
                    if (totalMatches >= MAX_RESULTS) {
                        isTruncated = true
                        return@forEach
                    }

                    try {
                        if (isBinaryFile(file)) return@forEach
                        val lines = file.readLines(Charsets.UTF_8)
                        val relPath = file.relativeTo(rootDir).path

                        for ((index, line) in lines.withIndex()) {
                            if (totalMatches >= MAX_RESULTS) {
                                isTruncated = true
                                break
                            }

                            if (regex.containsMatchIn(line)) {
                                val sb = StringBuilder()
                                sb.appendLine("$relPath:${index + 1}:")
                                // 上下文行
                                val start = maxOf(0, index - GREP_CONTEXT_LINES)
                                val end = minOf(lines.size - 1, index + GREP_CONTEXT_LINES)
                                for (i in start..end) {
                                    val prefix = if (i == index) ">>>" else "   "
                                    sb.appendLine("  $prefix ${i + 1}: ${lines[i]}")
                                }
                                results.add(sb.toString().trimEnd())
                                totalMatches++
                            }
                        }
                    } catch (e: Exception) {
                        // 跳过无法读取的文件
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Grep 搜索失败", e)
            return RawToolResult(
                output = "Grep 搜索失败: ${e.message}",
                isError = true
            )
        }

        val output = buildSearchOutput(results, isTruncated, "Grep")
        val metadata = JSONObject().apply {
            put("type", "grep")
            put("pattern", pattern)
            put("resultCount", results.size)
            put("totalMatches", totalMatches)
            put("truncated", isTruncated)
        }

        return RawToolResult(output = output, metadata = metadata)
    }

    // ==================== 辅助方法 ====================

    /**
     * 将 glob 通配符转换为正则表达式。
     */
    private fun globToRegex(glob: String): Regex {
        val regexStr = buildString {
            append('^')
            for (ch in glob) {
                when (ch) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' ->
                        append("\\").append(ch)
                    else -> append(ch)
                }
            }
            append('$')
        }
        return Regex(regexStr, RegexOption.IGNORE_CASE)
    }

    /**
     * 构建搜索结果输出。
     */
    private fun buildSearchOutput(
        results: List<String>,
        isTruncated: Boolean,
        searchType: String
    ): String = buildString {
        appendLine("$searchType 搜索结果 (${results.size} 条):")
        appendLine()
        if (results.isEmpty()) {
            appendLine("未找到匹配结果。")
        } else {
            results.forEachIndexed { index, result ->
                appendLine("${index + 1}. $result")
            }
            if (isTruncated) {
                appendLine()
                appendLine("... 结果已截断，仅显示前 $MAX_RESULTS 条。请缩小搜索范围获取更精确的结果。")
            }
        }
    }

    /**
     * 检测文件是否为二进制格式（前 8192 字节含 NUL）。
     */
    private fun isBinaryFile(file: File): Boolean {
        val checkSize = minOf(8192L, file.length()).toInt()
        val bytes = ByteArray(checkSize)
        file.inputStream().buffered().use { input ->
            val read = input.read(bytes)
            for (i in 0 until read) {
                if (bytes[i] == 0.toByte()) return true
            }
        }
        return false
    }

    /**
     * 计算文件中关键词匹配次数。
     */
    private fun countKeywordMatches(file: File, keyword: String): Int {
        return file.readText(Charsets.UTF_8)
            .split(keyword, ignoreCase = true)
            .size - 1
            .coerceAtLeast(0)
    }

    companion object {
        /** 搜索时跳过的目录名。 */
        private val IGNORED_DIRECTORIES = setOf(
            "node_modules", ".git", ".svn", ".hg", "build", ".gradle",
            ".idea", "__pycache__", ".cache", ".tox", "venv", ".venv",
            "dist", "out", "target", ".next", ".nuxt"
        )
    }
}
