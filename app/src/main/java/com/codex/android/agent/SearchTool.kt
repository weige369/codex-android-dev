package com.codex.android.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SearchTool(private val context: Context) : AgentTool {
    override val name = "search"
    override val description = "在文件系统中搜索文件名或内容。支持 filename 和 content 两种搜索类型。"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("pattern", JSONObject().apply { put("type", "string"); put("description", "搜索模式") })
            put("path", JSONObject().apply { put("type", "string"); put("description", "搜索根目录") })
            put("type", JSONObject().apply { put("type", "string"); put("description", "filename 或 content") })
        })
        put("required", JSONArray().put("pattern"))
    }

    override suspend fun execute(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        val pattern = args.optString("pattern", "")
        if (pattern.isBlank()) return "错误: 未提供搜索模式"
        val searchPath = args.optString("path", context.filesDir.absolutePath)
        val searchType = args.optString("type", "filename")
        val rootDir = File(searchPath)
        if (!rootDir.exists()) return "目录不存在: $searchPath"
        val results = mutableListOf<String>()
        val maxResults = 50
        try {
            when (searchType) {
                "content" -> searchContent(rootDir, pattern, results, maxResults)
                else -> searchFilename(rootDir, pattern, results, maxResults)
            }
        } catch (e: Exception) { return "搜索失败: ${e.message}" }
        return if (results.isEmpty()) "未找到匹配" else "找到 ${results.size} 个结果:\n" + results.joinToString("\n")
    }

    private fun searchFilename(dir: File, pattern: String, results: MutableList<String>, max: Int) {
        if (results.size >= max) return
        dir.listFiles()?.forEach { f ->
            if (results.size >= max) return
            if (f.isDirectory) searchFilename(f, pattern, results, max)
            else if (f.name.contains(pattern, ignoreCase = true)) results.add(f.absolutePath)
        }
    }

    private fun searchContent(dir: File, pattern: String, results: MutableList<String>, max: Int) {
        if (results.size >= max) return
        dir.listFiles()?.forEach { f ->
            if (results.size >= max) return
            if (f.isDirectory) searchContent(f, pattern, results, max)
            else if (f.length() < 500_000) try {
                f.readText().lineSequence().forEachIndexed { i, line ->
                    if (results.size >= max) return
                    if (line.contains(pattern, ignoreCase = true))
                        results.add("${f.absolutePath}:${i + 1}: ${line.trim().take(120)}")
                }
            } catch (_: Exception) {}
        }
    }
}
