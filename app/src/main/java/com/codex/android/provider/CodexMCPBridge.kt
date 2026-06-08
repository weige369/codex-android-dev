package com.codex.android.provider

import android.content.Context
import android.util.Log
import com.codex.android.codex.AnyclawManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/**
 * Codex MCP 桥接器 — 增强版。
 *
 * 集成 Anyclaw 工具注册表：
 * - 工具注册与发现
 * - Anyclaw 工具包管理
 * - MCP 协议兼容
 * - 工具执行桥接
 */
class CodexMCPBridge(private val context: Context) {

    companion object {
        private const val TAG = "CodexMCPBridge"
        private const val MCP_CONFIG_DIR = "codex_mcp"
        private const val MCP_CONFIG_FILE = "mcp_servers.json"
    }

    enum class ServerState {
        STOPPED, STARTING, RUNNING, ERROR
    }

    data class MCPTool(
        val name: String,
        val description: String,
        val serverId: String = "default"
    )

    data class MCPServer(
        val id: String,
        val name: String,
        val url: String = "",
        val state: ServerState = ServerState.STOPPED,
        val tools: List<MCPTool> = emptyList(),
        val authType: String = "none",
        val isBuiltin: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val configDir = File(context.filesDir, MCP_CONFIG_DIR)
    private val configFile = File(configDir, MCP_CONFIG_FILE)

    val anyclawManager = AnyclawManager(context)

    private val _state = MutableStateFlow(ServerState.STOPPED)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val _tools = MutableStateFlow<List<MCPTool>>(emptyList())
    val tools: StateFlow<List<MCPTool>> = _tools.asStateFlow()

    private val _servers = MutableStateFlow<List<MCPServer>>(emptyList())
    val servers: StateFlow<List<MCPServer>> = _servers.asStateFlow()

    private val _logs = MutableStateFlow("")
    val logs: StateFlow<String> = _logs.asStateFlow()

    init {
        configDir.mkdirs()
        loadServers()
    }

    fun start() {
        if (_state.value == ServerState.RUNNING) return
        _state.value = ServerState.STARTING
        addLog("MCP 服务器启动中...")

        scope.launch {
            try {
                // 注册内置 Android 系统工具
                addLog("注册 Android 系统工具...")
                registerBuiltinTools()

                // 注册 Anyclaw 工具包
                addLog("加载 Anyclaw 工具注册表...")
                registerAnyclawTools()

                _state.value = ServerState.RUNNING
                addLog("MCP 服务器已就绪 (${_tools.value.size} 个工具)")
            } catch (e: Exception) {
                _state.value = ServerState.ERROR
                addLog("启动失败: ${e.message}")
                Log.e(TAG, "MCP 启动失败", e)
            }
        }
    }

    fun stop() {
        _state.value = ServerState.STOPPED
        _tools.value = emptyList()
        addLog("MCP 服务器已停止")
    }

    /**
     * 刷新 Anyclaw 注册表
     */
    fun refreshAnyclawRegistry(callback: (Boolean) -> Unit = {}) {
        scope.launch {
            try {
                anyclawManager.refreshRegistry()
                registerAnyclawTools()
                addLog("Anyclaw 注册表已刷新 (${_tools.value.size} 个工具)")
                callback(true)
            } catch (e: Exception) {
                addLog("刷新 Anyclaw 注册表失败: ${e.message}")
                callback(false)
            }
        }
    }

    /**
     * 通过 MCP 执行工具
     */
    suspend fun executeTool(toolName: String, args: Map<String, String> = emptyMap()): String {
        val tool = _tools.value.find { it.name == toolName }
            ?: return "{\"error\":\"tool not found: $toolName\"}"

        return when {
            tool.name.startsWith("anyclaw_") || tool.serverId == "anyclaw" -> {
                anyclawManager.executeTool(toolName.removePrefix("anyclaw_"), args)
            }
            tool.serverId == "builtin" -> {
                executeBuiltinTool(tool.name, args)
            }
            else -> "{\"error\":\"unknown server: ${tool.serverId}\"}"
        }
    }

    private suspend fun executeBuiltinTool(toolName: String, args: Map<String, String>): String {
        return try {
            when (toolName) {
                "android_read_file" -> {
                    val path = args["path"] ?: return "{\"error\":\"path required\"}"
                    com.codex.android.security.SecurityPolicy.checkFileAccess(context, path)?.let { return it }
                    val content = File(path).readText()
                    "{\"success\":true,\"content\":\"${content.replace("\"", "\\\"").take(10000)}\"}"
                }
                "android_write_file" -> {
                    val path = args["path"] ?: return "{\"error\":\"path required\"}"
                    val content = args["content"] ?: return "{\"error\":\"content required\"}"
                    com.codex.android.security.SecurityPolicy.checkFileAccess(context, path)?.let { return it }
                    File(path).apply { parentFile?.mkdirs() }.writeText(content)
                    "{\"success\":true,\"message\":\"文件已写入: $path\"}"
                }
                "android_list_directory" -> {
                    val path = args["path"] ?: "/sdcard"
                    com.codex.android.security.SecurityPolicy.checkFileAccess(context, path)?.let { return it }
                    val dir = File(path)
                    if (!dir.exists() || !dir.isDirectory) return "{\"error\":\"目录不存在: $path\"}"
                    val entries = dir.listFiles()?.map { f ->
                        val type = if (f.isDirectory) "dir" else "file"
                        val size = if (f.isFile) f.length() else 0L
                        mapOf("name" to f.name, "type" to type, "size" to size)
                    } ?: emptyList()
                    val json = org.json.JSONArray()
                    entries.forEach { json.put(org.json.JSONObject(it)) }
                    "{\"success\":true,\"path\":\"$path\",\"entries\":${json}}"
                }
                "android_shell" -> {
                    if (!com.codex.android.security.SecurityPolicy.isShellAllowed(context)) {
                        return com.codex.android.security.SecurityPolicy.shellDeniedResponse(context)
                    }
                    val command = args["command"] ?: return "{\"error\":\"command required\"}"
                    // 危险命令需用户显式确认后才执行（在「完全」等级下作为中间安全网）。
                    com.codex.android.security.SecurityPolicy.dangerousCommandReason(command)?.let { reason ->
                        val approved = com.codex.android.security.ShellConfirmationManager.requestApproval(command, reason)
                        if (!approved) {
                            return com.codex.android.security.SecurityPolicy.shellRejectedByUserResponse(command, reason)
                        }
                    }
                    val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", command))
                    val output = process.inputStream.bufferedReader().readText()
                    "{\"success\":true,\"output\":\"${output.replace("\"", "\\\"").take(10000)}\"}"
                }
                "android_get_device_info" -> {
                    val info = org.json.JSONObject().apply {
                        put("manufacturer", android.os.Build.MANUFACTURER)
                        put("model", android.os.Build.MODEL)
                        put("brand", android.os.Build.BRAND)
                        put("device", android.os.Build.DEVICE)
                        put("sdk", android.os.Build.VERSION.SDK_INT)
                        put("release", android.os.Build.VERSION.RELEASE)
                        put("arch", System.getProperty("os.arch") ?: "unknown")
                    }
                    "{\"success\":true,\"device\":${info}}"
                }
                "android_screenshot" -> {
                    val cmd = "screencap -p"
                    val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))
                    val bytes = process.inputStream.readBytes()
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    "{\"success\":true,\"screenshot_base64\":\"$base64\"}"
                }
                "android_get_battery" -> {
                    val intent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
                    val pct = if (scale > 0) level * 100 / scale else level
                    val status = when (intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)) {
                        android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                        android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                        android.os.BatteryManager.BATTERY_STATUS_FULL -> "full"
                        android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                        else -> "unknown"
                    }
                    "{\"success\":true,\"level\":$pct,\"status\":\"$status\"}"
                }
                "android_get_network" -> {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                    val active = cm.activeNetworkInfo
                    val type = when (active?.type) {
                        android.net.ConnectivityManager.TYPE_WIFI -> "wifi"
                        android.net.ConnectivityManager.TYPE_MOBILE -> "mobile"
                        android.net.ConnectivityManager.TYPE_ETHERNET -> "ethernet"
                        else -> "none"
                    }
                    val connected = active?.isConnected ?: false
                    "{\"success\":true,\"network_type\":\"$type\",\"connected\":$connected}"
                }
                else -> "{\"error\":\"not implemented: $toolName\"}"
            }
        } catch (e: Exception) {
            "{\"error\":\"${e.message}\"}"
        }
    }

    /**
     * 添加 OAuth 认证的 MCP 服务器
     */
    suspend fun addOAuthServer(name: String, authUrl: String, tokenUrl: String): Boolean {
        return try {
            val server = MCPServer(
                id = "mcp_${System.currentTimeMillis()}",
                name = name,
                authType = "oauth"
            )
            val servers = _servers.value.toMutableList()
            servers.add(server)
            _servers.value = servers
            saveServers()
            addLog("OAuth 服务器已添加: $name")
            true
        } catch (e: Exception) {
            Log.e(TAG, "添加 OAuth 服务器失败", e)
            false
        }
    }

    /**
     * 添加自定义 MCP 服务器
     */
    suspend fun addServer(name: String, url: String): Boolean {
        return try {
            val server = MCPServer(
                id = "mcp_${System.currentTimeMillis()}",
                name = name,
                url = url
            )
            val servers = _servers.value.toMutableList()
            servers.add(server)
            _servers.value = servers
            saveServers()
            addLog("服务器已添加: $name ($url)")
            true
        } catch (e: Exception) { false }
    }

    /**
     * 移除 MCP 服务器
     */
    fun removeServer(serverId: String) {
        val servers = _servers.value.toMutableList()
        servers.removeAll { it.id == serverId }
        _servers.value = servers
        saveServers()
    }

    private fun registerBuiltinTools() {
        val builtinTools = listOf(
            MCPTool("android_read_file", "读取 Android 文件系统文件", "builtin"),
            MCPTool("android_write_file", "写入 Android 文件系统文件", "builtin"),
            MCPTool("android_list_directory", "列出目录内容", "builtin"),
            MCPTool("android_shell", "执行 Android Shell 命令", "builtin"),
            MCPTool("android_get_device_info", "获取设备信息", "builtin"),
            MCPTool("android_screenshot", "截取屏幕", "builtin"),
            MCPTool("android_get_battery", "获取电池状态", "builtin"),
            MCPTool("android_get_network", "获取网络状态", "builtin"),
        )
        _tools.value = builtinTools
    }

    private fun registerAnyclawTools() {
        val anyclawTools = anyclawManager.installedPackages.value.flatMap { pkg ->
            pkg.tools.map { tool ->
                MCPTool(
                    name = "anyclaw_${pkg.id}_${tool.name}",
                    description = "[${pkg.name}] ${tool.description}",
                    serverId = "anyclaw"
                )
            }
        }
        val allTools = _tools.value.toMutableList()
        allTools.removeAll { it.serverId == "anyclaw" }
        allTools.addAll(anyclawTools)
        _tools.value = allTools
    }

    private fun loadServers() {
        try {
            if (configFile.exists()) {
                val json = JSONObject(configFile.readText())
                val arr = json.optJSONArray("servers")
                if (arr != null) {
                    val list = mutableListOf<MCPServer>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        list.add(MCPServer(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            url = obj.optString("url", ""),
                            state = try { ServerState.valueOf(obj.optString("state", "STOPPED")) } catch (_: Exception) { ServerState.STOPPED },
                            authType = obj.optString("authType", "none"),
                            isBuiltin = obj.optBoolean("isBuiltin", false)
                        ))
                    }
                    _servers.value = list
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载服务器配置失败", e)
        }
    }

    private fun saveServers() {
        try {
            val arr = org.json.JSONArray()
            for (s in _servers.value) {
                arr.put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("url", s.url)
                    put("state", s.state.name)
                    put("authType", s.authType)
                    put("isBuiltin", s.isBuiltin)
                })
            }
            configFile.writeText(JSONObject().apply {
                put("servers", arr)
            }.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "保存服务器配置失败", e)
        }
    }

    private fun addLog(message: String) {
        _logs.value += "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}] $message\n"
    }

    fun destroy() {
        anyclawManager.destroy()
        scope.cancel()
    }
}
