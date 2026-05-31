package com.codex.android.tools

import android.util.Log
import com.codex.android.agent.AgentMode
import com.codex.android.security.SecurityPolicy
import org.json.JSONObject
import java.io.File

/**
 * 文件写入工具。
 *
 * 提供安全的文件写入能力，包含以下保护措施：
 * - 安全等级检查：SAFE 模式下仅允许写入沙箱内文件。
 * - 自动创建父目录：当 createDirs=true 时自动创建缺失的目录结构。
 * - 原子写入：先写入临时文件，再 rename 到目标路径，避免写入中断导致文件损坏。
 *
 * 原子写入流程：
 * 1. 将内容写入 `目标路径.tmp._codex_new` 临时文件
 * 2. 若目标文件已存在，先备份为 `目标路径.tmp._codex_backup`
 * 3. 将临时文件 rename 到目标路径
 * 4. 成功后删除备份文件
 *
 * 在 PLAN 模式下此工具被 Orchestrator 权限中间件拦截，不可用。
 */
class FileWriteTool(
    private val context: android.content.Context
) : ToolHandler {

    companion object {
        private const val TAG = "FileWriteTool"

        /** 临时文件后缀。 */
        private const val TEMP_SUFFIX = ".tmp._codex_new"

        /** 备份文件后缀。 */
        private const val BACKUP_SUFFIX = ".tmp._codex_backup"
    }

    override val name: String = "file_write"

    override val description: String = buildString {
        appendLine("写入文件内容。")
        appendLine("支持自动创建父目录和原子写入（先写临时文件再 rename）。")
        appendLine("PLAN 模式下不可用。")
    }

    override val parameters: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "文件路径（必填）")
            })
            put("content", JSONObject().apply {
                put("type", "string")
                put("description", "要写入的文件内容（必填）")
            })
            put("createDirs", JSONObject().apply {
                put("type", "boolean")
                put("description", "是否自动创建父目录（可选，默认 true）")
            })
        })
        put("required", org.json.JSONArray().apply {
            put("path")
            put("content")
        })
    }

    override fun validateParams(args: JSONObject): JSONObject? {
        if (!args.has("path") || args.optString("path").isBlank()) {
            return JSONObject().apply {
                put("error", "缺少必填参数 'path'")
                put("field", "path")
            }
        }
        if (!args.has("content")) {
            return JSONObject().apply {
                put("error", "缺少必填参数 'content'")
                put("field", "content")
            }
        }
        return null
    }

    override suspend fun execute(params: JSONObject, mode: AgentMode): RawToolResult {
        val path = params.getString("path")
        val content = params.getString("content")
        val createDirs = params.optBoolean("createDirs", true)

        // 安全等级检查
        val denial = SecurityPolicy.checkFileAccess(context, path)
        if (denial != null) {
            return RawToolResult(output = denial, isError = true)
        }

        val targetFile = File(path)
        val parentDir = targetFile.parentFile

        // 自动创建父目录
        if (createDirs && parentDir != null && !parentDir.exists()) {
            val created = parentDir.mkdirs()
            if (!created && !parentDir.exists()) {
                return RawToolResult(
                    output = "创建父目录失败: ${parentDir.absolutePath}",
                    isError = true
                )
            }
        }

        // 父目录存在性检查
        if (parentDir != null && !parentDir.exists()) {
            return RawToolResult(
                output = "父目录不存在: ${parentDir.absolutePath}。可设置 createDirs=true 自动创建。",
                isError = true
            )
        }

        // 原子写入
        return try {
            atomicWrite(targetFile, content)

            val metadata = JSONObject().apply {
                put("path", path)
                put("bytesWritten", content.toByteArray(Charsets.UTF_8).size)
                put("created", !targetFile.exists() || targetFile.length() == 0L)
                put("atomicWrite", true)
            }

            RawToolResult(
                output = "文件写入成功: $path",
                metadata = metadata
            )
        } catch (e: Exception) {
            Log.e(TAG, "写入文件失败: $path", e)
            RawToolResult(
                output = "写入文件失败: ${e.message}",
                isError = true
            )
        }
    }

    /**
     * 原子写入：先写临时文件，再 rename 到目标路径。
     *
     * 此方法保证：
     * - 写入过程中如果进程崩溃，目标文件保持原始内容不变。
     * - 如果目标文件不存在，直接创建。
     */
    private fun atomicWrite(target: File, content: String) {
        val tempFile = File(target.absolutePath + TEMP_SUFFIX)
        val backupFile = File(target.absolutePath + BACKUP_SUFFIX)

        try {
            // 步骤 1：写入临时文件
            tempFile.writeText(content, Charsets.UTF_8)

            // 步骤 2：如果目标已存在，先备份
            if (target.exists()) {
                if (!target.renameTo(backupFile)) {
                    // 备份失败，但目标文件存在，尝试直接覆盖
                    Log.w(TAG, "备份目标文件失败，尝试直接覆盖: ${target.absolutePath}")
                }
            }

            // 步骤 3：将临时文件 rename 到目标路径
            if (!tempFile.renameTo(target)) {
                // rename 失败，尝试复制方式
                Log.w(TAG, "rename 失败，尝试直接写入: ${target.absolutePath}")
                target.writeText(content, Charsets.UTF_8)
                tempFile.delete()
            }

            // 步骤 4：删除备份
            if (backupFile.exists()) {
                backupFile.delete()
            }
        } catch (e: Exception) {
            // 清理临时文件
            tempFile.delete()
            // 尝试恢复备份
            if (backupFile.exists() && !target.exists()) {
                backupFile.renameTo(target)
            }
            throw e
        }
    }
}
