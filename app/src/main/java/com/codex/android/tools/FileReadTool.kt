package com.codex.android.tools

import android.util.Log
import com.codex.android.agent.AgentMode
import com.codex.android.security.SecurityPolicy
import org.json.JSONObject
import java.io.File

/**
 * 文件读取工具。
 *
 * 提供安全的文件读取能力，包含以下保护措施：
 * - 二进制文件检测：检查前 8192 字节是否含 NUL 字符，防止将二进制内容送入 LLM。
 * - 大文件截断：超过 100K 的文件返回前 50K + 后 50K，中间标记省略。
 * - 安全等级检查：SAFE 模式下仅允许读取沙箱内文件。
 * - 行范围读取：通过 offset/limit 参数实现分页读取。
 */
class FileReadTool(
    private val context: android.content.Context
) : ToolHandler {

    companion object {
        private const val TAG = "FileReadTool"

        /** 二进制检测的采样大小。 */
        private const val BINARY_CHECK_SIZE = 8192

        /** 大文件阈值（100K 字符）。 */
        private const val LARGE_FILE_THRESHOLD = 100_000

        /** 大文件首/尾保留大小（各 50K 字符）。 */
        private const val LARGE_FILE_KEEP_SIZE = 50_000

        /** 默认行数限制。 */
        private const val DEFAULT_LINE_LIMIT = 2000
    }

    override val name: String = "file_read"

    override val description: String = buildString {
        appendLine("读取文件内容。")
        appendLine("支持行范围读取（offset + limit）。")
        appendLine("自动检测二进制文件并拒绝读取。")
        appendLine("超大文件（>100K 字符）自动截断，返回首尾各 50K。")
    }

    override val parameters: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "文件路径（必填）")
            })
            put("offset", JSONObject().apply {
                put("type", "integer")
                put("description", "起始行号（从 0 开始，可选，默认 0）")
            })
            put("limit", JSONObject().apply {
                put("type", "integer")
                put("description", "最多读取行数（可选，默认 2000）")
            })
        })
        put("required", org.json.JSONArray().apply {
            put("path")
        })
    }

    override fun validateParams(args: JSONObject): JSONObject? {
        val path = args.optString("path", "")
        if (path.isBlank()) {
            return JSONObject().apply {
                put("error", "缺少必填参数 'path'")
                put("field", "path")
            }
        }
        return null
    }

    override suspend fun execute(params: JSONObject, mode: AgentMode): RawToolResult {
        val path = params.getString("path")
        val offset = params.optInt("offset", 0)
        val limit = params.optInt("limit", DEFAULT_LINE_LIMIT)

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

        // 目录检查
        if (file.isDirectory) {
            return RawToolResult(
                output = "路径是目录，不是文件: $path。请使用 search 工具列出目录内容。",
                isError = true
            )
        }

        // 二进制文件检测
        try {
            if (isBinaryFile(file)) {
                return RawToolResult(
                    output = "文件是二进制格式，无法以文本方式读取: $path",
                    isError = true,
                    metadata = JSONObject().apply {
                        put("binaryDetected", true)
                        put("fileSize", file.length())
                    }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "二进制检测失败: $path", e)
            // 检测失败时继续尝试读取
        }

        // 读取文件内容
        return try {
            val allLines = file.readLines()
            val totalLines = allLines.size

            // 行范围截取
            val fromIndex = offset.coerceAtLeast(0).coerceAtMost(totalLines)
            val toIndex = (offset + limit).coerceAtMost(totalLines)
            val selectedLines = allLines.subList(fromIndex, toIndex)
            val content = selectedLines.joinToString("\n")

            // 大文件截断
            val finalContent = if (content.length > LARGE_FILE_THRESHOLD) {
                val head = content.take(LARGE_FILE_KEEP_SIZE)
                val tail = content.takeLast(LARGE_FILE_KEEP_SIZE)
                val omitted = content.length - 2 * LARGE_FILE_KEEP_SIZE
                buildString {
                    append(head)
                    appendLine()
                    appendLine("... [已省略 ${omitted} 字符] ...")
                    appendLine()
                    append(tail)
                }
            } else {
                content
            }

            val metadata = JSONObject().apply {
                put("path", path)
                put("totalLines", totalLines)
                put("linesRead", selectedLines.size)
                put("offset", fromIndex)
                put("limit", limit)
                put("fileSize", file.length())
                put("truncated", content.length > LARGE_FILE_THRESHOLD)
            }

            RawToolResult(
                output = finalContent,
                metadata = metadata
            )
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "文件过大导致 OOM: $path", e)
            RawToolResult(
                output = "文件过大，无法一次性读取。请使用 offset/limit 参数分批读取。",
                isError = true,
                metadata = JSONObject().apply {
                    put("path", path)
                    put("fileSize", file.length())
                    put("outOfMemory", true)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "读取文件失败: $path", e)
            RawToolResult(
                output = "读取文件失败: ${e.message}",
                isError = true
            )
        }
    }

    /**
     * 检测文件是否为二进制格式。
     *
     * 通过检查前 [BINARY_CHECK_SIZE] 字节中是否包含 NUL 字符来判断。
     * 这是一种简单但有效的方法，与 git 的二进制检测策略一致。
     */
    private fun isBinaryFile(file: File): Boolean {
        val bytes = ByteArray(minOf(BINARY_CHECK_SIZE.toLong(), file.length()).toInt())
        file.inputStream().buffered().use { input ->
            val read = input.read(bytes)
            for (i in 0 until read) {
                if (bytes[i] == 0.toByte()) {
                    return true
                }
            }
        }
        return false
    }
}
