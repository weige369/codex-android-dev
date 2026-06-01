package com.codex.android.agent

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 能力设备注册表。
 *
 * 管理所有可用设备的挂载/卸载，替代原来的 registerTool 直接注册。
 *
 * 核心安全原则（借鉴 BentOS）：
 * 1. 默认最小权限 — 启动时只挂载 SAFE 级别设备
 * 2. 显式授权 — MODERATE/ESCALATED 需要授权后才挂载
 * 3. 运行时动态 — 设备可随环境变化挂载/卸载（如 proot 安装后挂载 Linux Shell）
 * 4. 结构性保证 — 未挂载设备的工具定义不会发送给 LLM，LLM 根本不知道它的存在
 *
 * 用法：
 *   val registry = CapabilityRegistry(context)
 *   registry.autoMount()  // 根据环境自动挂载
 *   registry.getToolDefinitions()  // 获取已挂载设备的工具定义
 *   registry.getDeviceManifest()   // 获取设备清单（写入 system prompt）
 */
class CapabilityRegistry(private val context: Context) {

    companion object {
        private const val TAG = "CapabilityRegistry"
        private const val PREFS_NAME = "codex_capability_prefs"
        private const val KEY_GRANTED_LEVELS = "granted_permission_levels"
        private const val KEY_MOUNTED_DEVICES = "mounted_device_paths"

        /** 所有已知设备工厂 */
        private val DEVICE_FACTORIES: List<(Context) -> CapabilityDevice> = listOf(
            ::ShellDevice,
            ::FileReadDevice,
            ::FileWriteDevice,
            ::FileSystemDevice,
            ::SearchDevice,
            ::ProotEnvDevice,
            ::LinuxShellDevice
        )
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 已挂载的设备，key = devicePath */
    private val mountedDevices = mutableMapOf<String, CapabilityDevice>()

    /** 已授权的权限级别 */
    private val grantedLevels = mutableSetOf<PermissionLevel>()

    /** 所有已知设备实例（包括未挂载的） */
    private val allDevices = mutableMapOf<String, CapabilityDevice>()

    init {
        // 初始化所有设备实例
        DEVICE_FACTORIES.forEach { factory ->
            val device = factory(context)
            allDevices[device.devicePath] = device
        }
        // 恢复已授权的权限级别
        loadGrantedLevels()
    }

    // ========== 挂载管理 ==========

    /**
     * 挂载设备。
     * 只有权限级别已授权且设备就绪时才能挂载。
     *
     * @return 是否挂载成功
     */
    suspend fun mount(devicePath: String): Boolean {
        val device = allDevices[devicePath] ?: run {
            Log.w(TAG, "未知设备: $devicePath")
            return false
        }

        // 检查权限
        if (!grantedLevels.contains(device.permissionLevel)) {
            Log.w(TAG, "权限不足，无法挂载 $devicePath (需要 ${device.permissionLevel})")
            return false
        }

        // 检查就绪状态
        if (!device.isReady()) {
            Log.w(TAG, "设备未就绪: $devicePath")
            return false
        }

        mountedDevices[devicePath] = device
        saveMountedDevices()
        Log.i(TAG, "设备已挂载: $devicePath [${device.permissionLevel}]")
        return true
    }

    /**
     * 卸载设备。
     */
    fun unmount(devicePath: String) {
        mountedDevices.remove(devicePath)
        saveMountedDevices()
        Log.i(TAG, "设备已卸载: $devicePath")
    }

    /**
     * 根据环境自动挂载设备。
     *
     * 规则：
     * - SAFE 级别设备：总是自动挂载（如果就绪）
     * - MODERATE 级别设备：如果用户之前已授权，自动挂载
     * - ELEVATED/PRIVILEGED：需要显式调用 grantLevel + mount
     */
    suspend fun autoMount() {
        // SAFE 级别默认授权
        grantedLevels.add(PermissionLevel.SAFE)

        for ((path, device) in allDevices) {
            if (device.permissionLevel in grantedLevels && device.isReady()) {
                mountedDevices[path] = device
            }
        }

        saveMountedDevices()
        Log.i(TAG, "自动挂载完成: ${mountedDevices.size} 个设备")
    }

    // ========== 权限管理 ==========

    /**
     * 授权权限级别。
     * 通常在用户确认后调用（如 UI 中勾选"允许 Agent 执行 Shell 命令"）。
     */
    fun grantLevel(level: PermissionLevel) {
        grantedLevels.add(level)
        saveGrantedLevels()
        Log.i(TAG, "已授权权限级别: $level")
    }

    /**
     * 撤销权限级别。
     */
    fun revokeLevel(level: PermissionLevel) {
        grantedLevels.remove(level)
        // 卸载需要该级别的设备
        mountedDevices.entries.removeIf { it.value.permissionLevel == level }
        saveGrantedLevels()
        saveMountedDevices()
        Log.i(TAG, "已撤销权限级别: $level")
    }

    /**
     * 检查权限级别是否已授权。
     */
    fun isLevelGranted(level: PermissionLevel): Boolean = grantedLevels.contains(level)

    /**
     * 获取当前所有已授权的权限级别。
     */
    fun getGrantedLevels(): Set<PermissionLevel> = grantedLevels.toSet()

    /**
     * 一键授权所有 MODERATE 权限（便捷方法）。
     * 对应"允许 Agent 执行命令和写入文件"的用户选择。
     */
    fun grantModerateAccess() {
        grantLevel(PermissionLevel.SAFE)
        grantLevel(PermissionLevel.MODERATE)
    }

    // ========== 工具定义 ==========

    /**
     * 获取所有已挂载设备的工具定义。
     * 只有挂载的设备才会出现在 LLM 的工具列表中。
     */
    fun getToolDefinitions(): JSONArray {
        val tools = JSONArray()
        mountedDevices.forEach { (_, device) ->
            val tool = device.toTool()
            val toolObj = JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parameterSchema)
                })
            }
            tools.put(toolObj)
        }
        return tools
    }

    /**
     * 执行工具调用。
     * 只有已挂载设备的工具才能被执行，结构性保证安全。
     */
    suspend fun executeTool(name: String, argumentsJson: String): String {
        val device = mountedDevices.values.find { it.toTool().name == name }
            ?: return "错误: 工具 '$name' 不可用（设备未挂载或权限不足）"

        return device.toTool().execute(argumentsJson)
    }

    /**
     * 检查工具是否可用。
     */
    fun isToolAvailable(name: String): Boolean {
        return mountedDevices.values.any { it.toTool().name == name }
    }

    // ========== 设备清单（写入 System Prompt）==========

    /**
     * 生成设备清单文本，写入 system prompt。
     * 让 LLM 知道哪些设备可用、哪些待就绪、哪些不可用。
     */
    fun getDeviceManifest(): String {
        val sb = StringBuilder()
        sb.appendLine("可用设备（/dev/）：")

        for ((path, device) in allDevices.entries.sortedBy { it.key }) {
            val mounted = mountedDevices.containsKey(path)
            val status = when {
                mounted -> "✅ 已挂载"
                !device.permissionLevel.let { grantedLevels.contains(it) } -> "🔒 权限不足"
                else -> "⏳ 待就绪"
            }
            sb.appendLine("- $path [${device.permissionLevel}] ${device.describe()} $status")
        }

        sb.appendLine()
        sb.appendLine("未挂载的设备不可使用。需要更多权限时请告知用户。")
        return sb.toString()
    }

    /**
     * 获取已挂载设备名称列表。
     */
    fun getMountedToolNames(): List<String> {
        return mountedDevices.values.map { it.toTool().name }
    }

    // ========== 持久化 ==========

    private fun loadGrantedLevels() {
        val saved = prefs.getStringSet(KEY_GRANTED_LEVELS, null)
        if (saved != null) {
            saved.forEach { name ->
                try {
                    grantedLevels.add(PermissionLevel.valueOf(name))
                } catch (_: Exception) {}
            }
        }
    }

    private fun saveGrantedLevels() {
        prefs.edit().putStringSet(KEY_GRANTED_LEVELS, grantedLevels.map { it.name }.toSet()).apply()
    }

    private fun saveMountedDevices() {
        prefs.edit().putStringSet(KEY_MOUNTED_DEVICES, mountedDevices.keys.toSet()).apply()
    }
}
