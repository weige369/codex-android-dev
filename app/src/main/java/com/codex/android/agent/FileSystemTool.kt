package com.codex.android.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 增强文件系统工具。
 *
 * 移植自 Operit StandardFileSystemTools 核心能力：
 * - 列出目录内容（含大小/权限/修改时间）
 * - 创建目录
 * - 移动/复制文件
 * - 获取文件信息
 * - 查找文件（按名称模式）
 * - Grep 搜索文件内容
 * - 修改文件权限
 *
 * 与 FileReadTool/FileWriteTool 互补，提供更完整的文件管理能力。
 */
class FileSystemTool(private val context: Context) : AgentTool {
    override val name = "fs"
    override val description = buildString {
        appendLine("文件系统管理工具。支持的操作：")
        appendLine("- list: 列出目录内容")
        appendLine("- mkdir: 创建目录")
        appendLine("- info: 获取文件/目录信息")
        appendLine("- move: 移动/重命名文件")
        appendLine("- copy: 复制文件")
        appendLine("- find: 按名称模式查找文件")
        appendLine("- grep: 搜索文件内容")
        appendLine("- chmod: 修改文件权限")
    }

    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("description", "操作: list/mkdir/info/move/copy/find/grep/chmod")
            })
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "目标路径")
            })
            put("destination", JSONObject().apply {
                put("type", "string")
                put("description", "目标路径（move/copy 时必填）")
            })
            put("pattern", JSONObject().apply {
                put("type", "string")
                put("description", "搜索模式（find/grep 时必填）")
            })
            put("permissions", JSONObject().apply {
                put("type", "string")
                put("description", "权限字符串如 755（chmod 时必填）")
            })
            put("recursive", JSONObject().apply {
                put("type", "boolean")
                put("description", "是否递归（find 时可选，默认 false）")
            })
            put("max_results", JSONObject().apply {
                put("type", "integer")
                put("description", "最大结果数（默认 50）")
            })
        })
        put("required", JSONArray().put("action").put("path"))
    }

    override suspend fun execute(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        val action = args.optString("action", "").lowercase()
        val path = args.optString("path", "")

        if (action.isBlank()) return "错误: 未指定操作"
        if (path.isBlank() && action != "find" && action != "grep") return "错误: 未指定路径"

        // 路径安全校验
        val pathError = PathValidator.validatePath(path)
        if (pathError != null && action != "find" && action != "grep") return "路径校验失败: $pathError"

        return when (action) {
            "list" -> listDirectory(path)
            "mkdir" -> createDirectory(path)
            "info" -> getFileInfo(path)
            "move" -> moveFile(path, args.optString("destination", ""))
            "copy" -> copyFile(path, args.optString("destination", ""))
            "find" -> findFiles(path.ifBlank { context.filesDir.absolutePath }, args.optString("pattern", ""), args.optBoolean("recursive", false), args.optInt("max_results", 50))
            "grep" -> grepContent(path.ifBlank { context.filesDir.absolutePath }, args.optString("pattern", ""), args.optInt("max_results", 50))
            "chmod" -> chmod(path, args.optString("permissions", ""))
            else -> "未知操作: $action"
        }
    }

    private fun resolveFile(path: String): File {
        return if (path.startsWith("/")) File(path) else File(context.filesDir, path)
    }

    private fun listDirectory(path: String): String {
        val dir = resolveFile(path)
        if (!dir.exists()) return "目录不存在: $path"
        if (!dir.isDirectory) return "不是目录: $path"

        val files = dir.listFiles() ?: return "无法读取目录: $path"
        if (files.isEmpty()) return "空目录"

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder()
        sb.appendLine("📁 ${dir.absolutePath} (${files.size} 项)")
        sb.appendLine()

        // 排序：目录在前，文件在后
        val sorted = files.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })

        for (f in sorted) {
            val icon = if (f.isDirectory) "📁" else "📄"
            val size = if (f.isDirectory) "-" else formatSize(f.length())
            val date = dateFormat.format(Date(f.lastModified()))
            val perms = if (f.canRead()) "r" else "-" +
                        if (f.canWrite()) "w" else "-" +
                        if (f.canExecute()) "x" else "-"
            sb.appendLine("$icon ${f.name.padEnd(40)} $size.padEnd(12) $perms  $date")
        }

        val totalSize = files.filter { !it.isDirectory }.sumOf { it.length() }
        sb.appendLine()
        sb.append("总计: ${files.count { it.isDirectory }} 目录, ${files.count { !it.isDirectory }} 文件, ${formatSize(totalSize)}")

        return sb.toString()
    }

    private fun createDirectory(path: String): String {
        val dir = resolveFile(path)
        if (dir.exists()) return "目录已存在: $path"
        val created = dir.mkdirs()
        return if (created) "目录已创建: ${dir.absolutePath}" else "创建失败: ${dir.absolutePath}"
    }

    private fun getFileInfo(path: String): String {
        val file = resolveFile(path)
        if (!file.exists()) return "文件不存在: $path"

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return buildString {
            appendLine("名称: ${file.name}")
            appendLine("路径: ${file.absolutePath}")
            appendLine("类型: ${if (file.isDirectory) "目录" else "文件"}")
            if (!file.isDirectory) appendLine("大小: ${formatSize(file.length())} (${file.length()} bytes)")
            appendLine("修改时间: ${dateFormat.format(Date(file.lastModified()))}")
            appendLine("可读: ${file.canRead()}")
            appendLine("可写: ${file.canWrite()}")
            appendLine("可执行: ${file.canExecute()}")
            if (file.isDirectory) {
                val children = file.listFiles()
                appendLine("子项数: ${children?.size ?: "无法读取"}")
            }
        }
    }

    private fun moveFile(srcPath: String, destPath: String): String {
        if (destPath.isBlank()) return "错误: 未指定目标路径"
        val src = resolveFile(srcPath)
        val dest = resolveFile(destPath)
        if (!src.exists()) return "源文件不存在: $srcPath"

        val moved = src.renameTo(dest)
        return if (moved) "已移动: $srcPath → $destPath" else "移动失败"
    }

    private fun copyFile(srcPath: String, destPath: String): String {
        if (destPath.isBlank()) return "错误: 未指定目标路径"
        val src = resolveFile(srcPath)
        val dest = resolveFile(destPath)
        if (!src.exists()) return "源文件不存在: $srcPath"
        if (src.isDirectory) return "不支持复制目录（请用 shell 工具 cp -r）"

        return try {
            dest.parentFile?.mkdirs()
            src.inputStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output, 8192)
                }
            }
            "已复制: $srcPath → $destPath (${formatSize(src.length())})"
        } catch (e: Exception) {
            "复制失败: ${e.message}"
        }
    }

    private fun findFiles(rootPath: String, pattern: String, recursive: Boolean, maxResults: Int): String {
        if (pattern.isBlank()) return "错误: 未指定搜索模式"
        val root = resolveFile(rootPath)
        if (!root.exists()) return "目录不存在: $rootPath"

        val results = mutableListOf<String>()
        findFilesRecursive(root, pattern, recursive, results, maxResults)

        return if (results.isEmpty()) "未找到匹配 '$pattern' 的文件"
        else "找到 ${results.size} 个结果:\n" + results.joinToString("\n")
    }

    private fun findFilesRecursive(dir: File, pattern: String, recursive: Boolean, results: MutableList<String>, max: Int) {
        if (results.size >= max) return
        dir.listFiles()?.forEach { f ->
            if (results.size >= max) return
            if (f.name.contains(pattern, ignoreCase = true)) {
                results.add(f.absolutePath)
            }
            if (recursive && f.isDirectory) {
                findFilesRecursive(f, pattern, recursive, results, max)
            }
        }
    }

    private fun grepContent(rootPath: String, pattern: String, maxResults: Int): String {
        if (pattern.isBlank()) return "错误: 未指定搜索模式"
        val root = resolveFile(rootPath)
        if (!root.exists()) return "目录不存在: $rootPath"

        val results = mutableListOf<String>()
        grepRecursive(root, pattern, results, maxResults)

        return if (results.isEmpty()) "未找到匹配 '$pattern' 的内容"
        else "找到 ${results.size} 处匹配:\n" + results.joinToString("\n")
    }

    private fun grepRecursive(dir: File, pattern: String, results: MutableList<String>, max: Int) {
        if (results.size >= max) return
        dir.listFiles()?.forEach { f ->
            if (results.size >= max) return
            if (f.isDirectory) {
                grepRecursive(f, pattern, results, max)
            } else if (f.length() < 500_000 && f.canRead()) {
                try {
                    f.readText().lineSequence().forEachIndexed { i, line ->
                        if (results.size >= max) return
                        if (line.contains(pattern, ignoreCase = true)) {
                            results.add("${f.absolutePath}:${i + 1}: ${line.trim().take(120)}")
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun chmod(path: String, permissions: String): String {
        if (permissions.isBlank()) return "错误: 未指定权限"
        if (!permissions.matches(Regex("[0-7]{3}"))) return "权限格式错误，应为三位八进制数如 755"

        val file = resolveFile(path)
        if (!file.exists()) return "文件不存在: $path"

        val mode = permissions.toInt(8)
        val ownerRead = (mode and 0x100) != 0
        val ownerWrite = (mode and 0x080) != 0
        val ownerExec = (mode and 0x040) != 0

        file.setReadable(ownerRead, true)
        file.setWritable(ownerWrite, true)
        file.setExecutable(ownerExec, true)

        return "权限已修改: $path → $permissions"
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024 * 1024))} GB"
        }
    }
}
