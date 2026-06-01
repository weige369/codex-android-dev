package com.codex.android.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 能力设备接口。
 *
 * 借鉴 BentOS 的 /dev/ 设备模型：
 * - Agent 的每项能力 = 一个"设备"
 * - 设备必须被"挂载"(mount)才能使用
 * - 未挂载的设备结构性不可访问（不是策略禁止，而是根本不存在）
 * - 每个设备有明确的权限级别和安全边界
 */
interface CapabilityDevice {
    val devicePath: String
    val category: DeviceCategory
    val permissionLevel: PermissionLevel
    suspend fun isReady(): Boolean
    fun toTool(): AgentTool
    fun describe(): String
}

enum class DeviceCategory(val label: String) {
    SHELL("命令执行"),
    FILESYSTEM("文件系统"),
    ENVIRONMENT("环境管理"),
    NETWORK("网络"),
    LLM("推理")
}

enum class PermissionLevel(val label: String) {
    SAFE("安全"),
    MODERATE("中等"),
    ELEVATED("高风险"),
    PRIVILEGED("特权")
}

// ========== 具体设备实现 ==========

class ShellDevice(private val context: Context) : CapabilityDevice {
    override val devicePath = "/dev/shell"
    override val category = DeviceCategory.SHELL
    override val permissionLevel = PermissionLevel.MODERATE
    override suspend fun isReady() = true
    override fun toTool() = ShellTool(context)
    override fun describe() = "命令执行设备。自动路由到 Android 或 proot Linux 环境。"
}

class FileReadDevice(private val context: Context) : CapabilityDevice {
    override val devicePath = "/dev/fs/read"
    override val category = DeviceCategory.FILESYSTEM
    override val permissionLevel = PermissionLevel.SAFE
    override suspend fun isReady() = true
    override fun toTool() = FileReadTool(context)
    override fun describe() = "文件读取设备。读取应用内部存储中的文件。"
}

class FileWriteDevice(private val context: Context) : CapabilityDevice {
    override val devicePath = "/dev/fs/write"
    override val category = DeviceCategory.FILESYSTEM
    override val permissionLevel = PermissionLevel.MODERATE
    override suspend fun isReady() = true
    override fun toTool() = FileWriteTool(context)
    override fun describe() = "文件写入设备。创建或覆盖文件。"
}

class FileSystemDevice(private val context: Context) : CapabilityDevice {
    override val devicePath = "/dev/fs/manage"
    override val category = DeviceCategory.FILESYSTEM
    override val permissionLevel = PermissionLevel.MODERATE
    override suspend fun isReady() = true
    override fun toTool() = FileSystemTool(context)
    override fun describe() = "文件系统管理设备。列出目录、创建/移动/复制文件、查找、grep搜索等。"
}

class SearchDevice(private val context: Context) : CapabilityDevice {
    override val devicePath = "/dev/search"
    override val category = DeviceCategory.FILESYSTEM
    override val permissionLevel = PermissionLevel.SAFE
    override suspend fun isReady() = true
    override fun toTool() = SearchTool(context)
    override fun describe() = "文件搜索设备。按文件名或内容搜索。"
}

class ProotEnvDevice(private val context: Context) : CapabilityDevice {
    override val devicePath = "/dev/env/proot"
    override val category = DeviceCategory.ENVIRONMENT
    override val permissionLevel = PermissionLevel.SAFE
    override suspend fun isReady() = true
    override fun toTool() = ProotEnvTool(context)
    override fun describe() = "环境管理设备。查询 proot 状态、安装包、在 proot 中执行命令。"
}

class LinuxShellDevice(private val context: Context) : CapabilityDevice {
    override val devicePath = "/dev/linux/shell"
    override val category = DeviceCategory.SHELL
    override val permissionLevel = PermissionLevel.MODERATE
    private val linuxEnv by lazy { com.codex.android.util.LinuxEnvironment(context) }
    override suspend fun isReady() = linuxEnv.isInstalled()
    override fun toTool() = LinuxShellTool(context)
    override fun describe() = "Linux Shell 设备。在 Ubuntu proot 环境中执行命令。"
}
