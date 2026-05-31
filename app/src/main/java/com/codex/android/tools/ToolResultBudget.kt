package com.codex.android.tools

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 工具输出预算管理器。
 *
 * 当工具执行产生的输出超过 [MAX_INLINE_SIZE]（100K 字符）时，
 * 将完整输出写入临时文件，仅向 LLM 上下文返回 head/tail 预览，
 * 避免超长输出撑爆上下文窗口。
 *
 * 对标 OpenCode 的 output truncation 机制：
 * - 截断后仍保留首尾各 [PREVIEW_SIZE] 字符，让 LLM 能看到输出的大致结构。
 * - 附加 `truncated: true` 元数据和临时文件路径，LLM 可通过 FileReadTool
 *   按需读取完整内容。
 */
object ToolResultBudget {

    private const val TAG = "ToolResultBudget"

    /** 内联输出的最大字符数（100K）。超过此阈值将触发截断。 */
    const val MAX_INLINE_SIZE = 100_000

    /** 截断后保留的首/尾预览字符数（各 5000）。 */
    const val PREVIEW_SIZE = 5_000

    /** 截断输出存放的临时目录名称。 */
    private const val TRUNCATED_DIR = "tool_truncated"

    /**
     * 处理原始工具结果，按预算截断超长输出。
     *
     * @param result 工具原始执行结果。
     * @param tempDir 临时文件目录，用于存放超长输出。
     * @return 处理后的结果。若无需截断则原样返回；否则替换 output 为预览文本，
     *   并在 metadata 中添加 truncated / tempFilePath 字段。
     */
    fun process(result: RawToolResult, tempDir: File): RawToolResult {
        val output = result.output
        if (output.length <= MAX_INLINE_SIZE) {
            return result
        }

        // 超长输出 → 保存到临时文件，返回 head/tail 预览
        val truncatedDir = File(tempDir, TRUNCATED_DIR).also { it.mkdirs() }
        val tempFile = File(truncatedDir, "output_${UUID.randomUUID()}.txt")

        return try {
            tempFile.writeText(output)

            val head = output.take(PREVIEW_SIZE)
            val tail = output.takeLast(PREVIEW_SIZE)
            val omitted = output.length - 2 * PREVIEW_SIZE

            val preview = buildString {
                append(head)
                appendLine()
                appendLine()
                appendLine("... [已省略 ${omitted} 字符，完整输出已保存到 ${tempFile.absolutePath}] ...")
                appendLine()
                append(tail)
            }

            val metadata = JSONObject(result.metadata.toString()).apply {
                put("truncated", true)
                put("tempFilePath", tempFile.absolutePath)
                put("originalSize", output.length)
                put("previewSize", 2 * PREVIEW_SIZE)
            }

            result.copy(output = preview, metadata = metadata)
        } catch (e: Exception) {
            Log.e(TAG, "保存截断输出到临时文件失败", e)
            // 降级：仅做简单截断，不保存文件
            val head = output.take(PREVIEW_SIZE)
            val tail = output.takeLast(PREVIEW_SIZE)

            val preview = buildString {
                append(head)
                appendLine()
                appendLine("... [输出过长（${output.length} 字符），临时文件写入失败，仅显示首尾预览] ...")
                appendLine()
                append(tail)
            }

            val metadata = JSONObject(result.metadata.toString()).apply {
                put("truncated", true)
                put("originalSize", output.length)
                put("fileSaveError", e.message)
            }

            result.copy(output = preview, metadata = metadata)
        }
    }

    /**
     * 清理所有截断临时文件。
     *
     * 应在 Turn 结束或会话销毁时调用，避免临时文件堆积。
     */
    fun cleanup(tempDir: File) {
        val truncatedDir = File(tempDir, TRUNCATED_DIR)
        if (truncatedDir.exists()) {
            truncatedDir.listFiles()?.forEach { file ->
                if (!file.delete()) {
                    Log.w(TAG, "清理临时文件失败: ${file.absolutePath}")
                }
            }
        }
    }
}
