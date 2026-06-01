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
 *
 * 与 BentOS 的差异：
 * - BentOS 用 CUSE/FUSE 实现真正的 /dev/ 文件系统，我们用 Kotlin 接口抽象
 * - BentOS 的设备持有密钥，我们的设备只控制可见性
 * - 我们的模型更轻量，适配 Android 进程内架构
 */
interface CapabilityDevice {
    /** 设备路径，如 /dev/shell, /dev/fs/read */
    val devicePath: String

    /** 设备类别 */
    val category: DeviceCategory

    /** 权限级别 */
    val permissionLevel: PermissionLevel

    /** 设备是否就绪（如 proot 未安装时 linux_shell 不可用） */
    suspend fun isReady(): Boolean

    /** 将设备转换为 AgentTool（只有挂载且就绪才调用） */
    fun toTool(): AgentTool

    /** 设备描述（写入 system prompt） */
    fun describe(): String
}

/**
 * 设备类别。
 */
enum class DeviceCategory(val label: String) {
    SHELL("命令执行"),
    FILESYSTEM("文件系统"),
    ENVIRONMENT("环境管理"),
    NETWORK("网络"),
    LLM("推理")
}

/**
 * 权限级别。
 *
 * SAFE     - 只读、无副作用，默认挂载
 * MODERATE - 有副作用但可控，需用户确认后挂载
 * ELEVATED - 高风险操作，需显式授权
 * PRIVILEGED - 需要特殊权限（预留）
 */
enum class PermissionLevel(val label: String) {
    SAFE("安全"),
    MODERATE("中等"),
    ELEVATED("高风险"),
    PRIVILEGED("特权")
}

// ========== 具体设备实现 ==========

/**
 * Shell 命令执行设备。
 * 自动路由到 Android 原生 Shell 或 proot Linux。
 */
class ShellDevice(private val context: Context) : CapabilityDevice {
    override val devicePath = "/dev/shell"
    override val category = DeviceCategory.SHELL
    override val permissionLevel = PermissionLevel.MODERATE

    override suspend fun isReady() = true
    override fun toTool() = ShellTool(context)
    override fun describe() = "命令执行设备。可执行 Shell 命令，自动路由到 Android 或 proot Linux 环境。"
}

/**
 * 文件读取设备。
 */
class FileReadDevice(private val context: Context) : CapabilityDevice {
    override val devicePath = "/dev/fs/read"
    override val category = DeviceCategory.FILESYSTEM
    override val permissionLevel = PermissionLevel.SAFE

    override suspend fun isReady() = true
    override fun toTool() = FileReadTool(context)
    override fun describe() = "文件读取设备。可读取应用内部存储中的文件内容。"
}

/**
 * 文件写入设备。
 */
class FileWriteDevice(private val context: Context) : CapabilityDevice {
    override val devicePath = "/dev/fs/write"
    override val category = DeviceCategory.FILESYSTEM
    override val permissionLevel = PermissionLevel.MODERATE

    override suspend fun isReady() = true
    override fun toTool() = FileWriteTool(context)
    override fun describe() = "文件写入设备。可创建或覆盖文件。"
}

/**
 * 文件搜索设备。
 */
class SearchDevice(private val context: Context) : CapabilityDevice {
    override val devicePath = "/dev/search"
    override val category = DeviceCategory.FILESYSTEM
    override val permissionLevel = PermissionLevel.SAFE

    override suspend fun isReady() = true
    override fun toTool() = SearchTool(context)
    override fun describe() = "文件搜索设备。可按文件名或内容搜索文件系统。"
}

/**
 * Proot 环境管理设备。
 */
class ProotEnvDevice(private val context: Context) : CapabilityDevice {
    override val devicePath = "/dev/env/proot"
    override val category = DeviceCategory.ENVIRONMENT
    override val permissionLevel = PermissionLevel.SAFE

    override suspend fun isReady() = true
    override fun toTool() = ProotEnvTool(context)
    override fun describe() = "环境管理设备。可查询 proot Linux 环境状态、安装包、在 proot 中执行命令。"
}

/**
 * Linux Shell 设备。
 * 仅在 proot Linux 环境已就绪时可用。
 */
class LinuxShellDevice(private val context: Context) : CapabilityDevice {
    override val devicePath = "/dev/linux/shell"
    override val category = DeviceCategory.SHELL
    override val permissionLevel = PermissionLevel.MODERATE

    private val linuxEnv by lazy { com.codex.android.util.LinuxEnvironment(context) }

    override suspend fun isReady() = linuxEnv.isInstalled()
    override fun toTool() = LinuxShellTool(context)
    override fun describe() = "Linux Shell 设备。在 Ubuntu proot 环境中执行命令，提供完整 Linux 工具链。"
}
