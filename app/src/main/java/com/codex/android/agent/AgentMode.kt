package com.codex.android.agent

/**
 * Agent 运行模式枚举。
 *
 * 定义三种预设的权限模式，控制智能体可使用的工具集和操作权限。
 * 每种模式对应不同的安全等级，从完全权限到受限沙箱环境。
 *
 * - BUILD: 完整权限模式，适合开发构建任务
 * - PLAN: 只读规划模式，适合分析和规划任务
 * - SANDBOX: 受限沙箱模式，适合运行不受信任的代码
 *
 * @property displayName 用户可见的显示名称
 * @property description 模式功能描述
 * @property isReadOnly 是否为只读模式（禁止文件写入和命令修改操作）
 * @property defaultTools 该模式下可用的默认工具列表
 * @property permission 权限等级标识字符串
 */
enum class AgentMode(
    val displayName: String,
    val description: String,
    val isReadOnly: Boolean,
    val defaultTools: List<ToolDefinition>,
    val permission: String
) {
    /**
     * 构建模式：完整权限。
     *
     * 允许执行任意 shell 命令、读写文件、应用补丁、搜索和网络请求。
     * 适用于信任环境下的开发、构建和自动化任务。
     */
    BUILD(
        displayName = "构建模式",
        description = "完整权限：可执行 shell 命令、读写文件、搜索和访问网络",
        isReadOnly = false,
        defaultTools = listOf(
            ToolDefinition.SHELL,
            ToolDefinition.FILE_WRITE,
            ToolDefinition.SEARCH,
            ToolDefinition.PATCH,
            ToolDefinition.WEB_FETCH
        ),
        permission = "full"
    ),

    /**
     * 规划模式：只读权限。
     *
     * 仅允许执行只读 shell 命令、读取文件、搜索和网络请求。
     * 适用于代码分析、架构规划等不需要修改文件的场景。
     */
    PLAN(
        displayName = "规划模式",
        description = "只读权限：仅可读取文件和执行只读命令",
        isReadOnly = true,
        defaultTools = listOf(
            ToolDefinition.SHELL_READONLY,
            ToolDefinition.FILE_READ,
            ToolDefinition.SEARCH,
            ToolDefinition.WEB_FETCH
        ),
        permission = "readonly"
    ),

    /**
     * 沙箱模式：受限权限。
     *
     * 允许在沙箱环境中执行命令和读写沙箱内文件，以及搜索。
     * 适用于运行不受信任的代码或进行安全隔离的任务。
     */
    SANDBOX(
        displayName = "沙箱模式",
        description = "受限权限：仅在沙箱环境中操作，文件访问被限制",
        isReadOnly = false,
        defaultTools = listOf(
            ToolDefinition.SHELL_SANDBOX,
            ToolDefinition.FILE_READ,
            ToolDefinition.FILE_WRITE_SANDBOX,
            ToolDefinition.SEARCH
        ),
        permission = "sandbox"
    );

    companion object {
        private const val TAG = "AgentMode"

        /**
         * 根据权限等级字符串查找匹配的 AgentMode。
         *
         * @param permission 权限等级标识（"full" / "readonly" / "sandbox"）
         * @return 匹配的 AgentMode，未匹配时默认返回 BUILD
         */
        fun fromPermission(permission: String): AgentMode {
            return entries.find { it.permission == permission } ?: BUILD
        }

        /**
         * 根据名称查找 AgentMode（兼容大小写）。
         *
         * @param name 模式名称
         * @return 匹配的 AgentMode，未匹配时默认返回 BUILD
         */
        fun fromName(name: String): AgentMode {
            return try {
                valueOf(name.uppercase())
            } catch (_: IllegalArgumentException) {
                BUILD
            }
        }
    }

    /**
     * 获取该模式下所有工具的 API 格式列表。
     *
     * @return 可直接嵌入 LLM 请求的 tools JSONArray
     */
    fun toolsToApiFormat(): org.json.JSONArray {
        return org.json.JSONArray().apply {
            defaultTools.forEach { tool ->
                put(tool.toApiFormat())
            }
        }
    }
}
