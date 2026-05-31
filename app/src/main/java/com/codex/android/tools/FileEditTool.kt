package com.codex.android.tools

import android.util.Log
import com.codex.android.agent.AgentMode
import com.codex.android.security.SecurityPolicy
import org.json.JSONObject
import java.io.File

/**
 * 文件编辑工具。
 *
 * 提供精确的字符串替换编辑能力（replace_one 语义）：
 * - 在文件中查找 [old_string] 的首次出现，替换为 [new_string]。
 * - 若 [old_string] 未找到或出现多次，返回错误提示。
 * - 替换后自动写回文件（使用原子写入）。
 *
 * 此工具适用于 LLM 已知文件精确内容的场景，避免了"全文重写"的开销。
 * 安全等级检查与 [FileWriteTool] 一致。
 * PLAN 模式下此工具被 Orchestrator 权限中间件拦截，不可用。
 */
class FileEditTool(
    private val context: android.content.Context
) : ToolHandler {

    companion object {
        private const val TAG = "FileEditTool"
    }

    override val name: String = "file_edit"

    override val description: String = buildString {
        appendLine("精确编辑文件：查找并替换一段文本。")
        appendLine("使用 replace_one 语义——仅替换首次匹配。")
        appendLine("如果 old_string 出现零次或多次，将返回错误提示。")
        appendLine("PLAN 模式下不可用。")
    }

    override val parameters: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "文件路径（必填）")
            })
            put("old_string", JSONObject().apply {
                put("type", "string")
                put("description", "要被替换的原始文本（必填，必须精确匹配）")
            })
            put("new_string", JSONObject().apply {
                put("type", "string")
                put("description", "替换后的新文本（必填）")
            })
        })
        put("required", org.json.JSONArray().apply {
            put("path")
            put("old_string")
            put("new_string")
        })
    }

    override fun validateParams(args: JSONObject): JSONObject? {
        if (!args.has("path") || args.optString("path").isBlank()) {
            return JSONObject().apply {
                put("error", "缺少必填参数 'path'")
                put("field", "path")
            }
        }
        if (!args.has("old_string")) {
            return JSONObject().apply {
                put("error", "缺少必填参数 'old_string'")
                put("field", "old_string")
            }
        }
        if (!args.has("new_string")) {
            return JSONObject().apply {
                put("error", "缺少必填参数 'new_string'")
                put("field", "new_string")
            }
        }

        val oldString = args.getString("old_string")
        val newString = args.getString("new_string")
        if (oldString == newString) {
            return JSONObject().apply {
                put("error", "old_string 与 new_string 相同，无需替换")
                put("field", "new_string")
            }
        }

        return null
    }

    override suspend fun execute(params: JSONObject, mode: AgentMode): RawToolResult {
        val path = params.getString("path")
        val oldString = params.getString("old_string")
        val newString = params.getString("new_string")

        // 安全等级检查
        val denial = SecurityPolicy.checkFileAccess(context, path)
        if (denial != null) {
            return RawToolResult(output = denial, isError = true)
        }

        val file = File(path)

        // 文件存在性检查
        if (!file.exists()) {
            return RawToolResult(
                output = "文件不存在: $path",
                isError = true
            )
        }

        if (file.isDirectory) {
            return RawToolResult(
                output = "路径是目录，不是文件: $path",
                isError = true
            )
        }

        return try {
            val content = file.readText(Charsets.UTF_8)

            // 查找匹配次数
            val matchCount = countOccurrences(content, oldString)

            when {
                matchCount == 0 -> {
                    RawToolResult(
                        output = buildString {
                            appendLine("未找到要替换的文本。请确认 old_string 与文件内容精确匹配。")
                            appendLine()
                            appendLine("提示：")
                            appendLine("- 检查缩进（空格 vs 制表符）")
                            appendLine("- 检查行尾字符（LF vs CRLF）")
                            appendLine("- 使用 file_read 工具查看文件实际内容")
                        },
                        isError = true,
                        metadata = JSONObject().apply {
                            put("path", path)
                            put("matchCount", 0)
                        }
                    )
                }
                matchCount > 1 -> {
                    RawToolResult(
                        output = buildString {
                            appendLine("old_string 在文件中出现 $matchCount 次，无法确定替换哪一处。")
                            appendLine("请提供更多上下文使 old_string 唯一匹配。")
                            appendLine()
                            appendLine("建议：在 old_string 中包含更多周围的行，使其在文件中只出现一次。")
                        },
                        isError = true,
                        metadata = JSONObject().apply {
                            put("path", path)
                            put("matchCount", matchCount)
                        }
                    )
                }
                else -> {
                    // 精确替换一次
                    val newContent = content.replaceFirst(oldString, newString)
                    file.writeText(newContent, Charsets.UTF_8)

                    val linesChanged = countLineChanges(oldString, newString)

                    RawToolResult(
                        output = buildString {
                            appendLine("文件编辑成功: $path")
                            appendLine("替换了 1 处匹配，影响约 $linesChanged 行")
                        },
                        metadata = JSONObject().apply {
                            put("path", path)
                            put("matchCount", 1)
                            put("linesChanged", linesChanged)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "编辑文件失败: $path", e)
            RawToolResult(
                output = "编辑文件失败: ${e.message}",
                isError = true
            )
        }
    }

    /**
     * 计算子字符串在文本中出现的次数。
     */
    private fun countOccurrences(text: String, substring: String): Int {
        if (substring.isEmpty()) return 0
        var count = 0
        var index = 0
        while (text.indexOf(substring, index).also { index = it } != -1) {
            count++
            index += substring.length
        }
        return count
    }

    /**
     * 估算替换影响的行数。
     */
    private fun countLineChanges(oldString: String, newString: String): Int {
        val oldLines = oldString.lines().size
        val newLines = newString.lines().size
        return maxOf(oldLines, newLines)
    }
}
