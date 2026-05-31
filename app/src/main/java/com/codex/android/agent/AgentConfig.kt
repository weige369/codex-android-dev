package com.codex.android.agent

/**
 * Agent 运行配置。
 *
 * 控制智能体循环的核心参数，包括最大轮次、自动压缩阈值、
 * 默认模式、工作目录、权限等级等。所有字段均有合理默认值，
 * 可通过 Builder 模式或直接构造进行定制。
 *
 * @property maxTurns 单次任务最大循环轮次，超过后强制终止（默认 50）
 * @property autoCompactLimit 自动压缩触发阈值（估算 token 数），超过时触发历史压缩（默认 100000）
 * @property guardianEnabled 是否启用 Guardian 安全守卫，对工具调用进行二次审核（默认 false）
 * @property defaultMode 默认 Agent 模式（默认 BUILD）
 * @property workingDirectory Agent 工作目录，文件操作和 shell 执行的根路径
 * @property permissionLevel 权限等级标识，如 "full" / "readonly" / "sandbox"
 * @property prootAvailable proot 是否可用（影响沙箱策略）
 * @property developerInstructions 开发者自定义指令，会注入到 system prompt 中
 * @property model 使用的 LLM 模型名称（默认 gpt-4o）
 */
data class AgentConfig(
    val maxTurns: Int = 50,
    val autoCompactLimit: Int = 100_000,
    val guardianEnabled: Boolean = false,
    val defaultMode: AgentMode = AgentMode.BUILD,
    val workingDirectory: String = "/data/data/com.codex.android/files/workspace",
    val permissionLevel: String = "full",
    val prootAvailable: Boolean = false,
    val developerInstructions: String? = null,
    val model: String = "gpt-4o"
) {

    companion object {
        private const val TAG = "AgentConfig"

        /** SharedPreferences 文件名 */
        internal const val PREFS_NAME = "codex_agent_prefs"
        internal const val KEY_PERMISSION_LEVEL = "permission_level"
        internal const val KEY_DEVELOPER_INSTRUCTIONS = "developer_instructions"
        internal const val KEY_GUARDIAN_ENABLED = "guardian_enabled"
        internal const val KEY_MODEL = "model"
        internal const val KEY_MAX_TURNS = "max_turns"
        internal const val KEY_AUTO_COMPACT_LIMIT = "auto_compact_limit"
        internal const val KEY_DEFAULT_MODE = "default_mode"
    }

    /**
     * 从 SharedPreferences 构建 AgentConfig。
     *
     * 读取用户持久化的安全等级、开发者指令等配置，
     * 未设置的项使用默认值。workingDirectory 和 prootAvailable
     * 需要由调用方传入（运行时环境相关）。
     *
     * @param prefs Android SharedPreferences 实例
     * @param workingDirectory 工作目录路径
     * @param prootAvailable proot 是否可用
     * @return 合成后的 AgentConfig
     */
    fun fromPreferences(
        prefs: android.content.SharedPreferences,
        workingDirectory: String,
        prootAvailable: Boolean
    ): AgentConfig {
        val permissionLevel = prefs.getString(KEY_PERMISSION_LEVEL, "full") ?: "full"
        val developerInstructions = prefs.getString(KEY_DEVELOPER_INSTRUCTIONS, null)
        val guardianEnabled = prefs.getBoolean(KEY_GUARDIAN_ENABLED, false)
        val model = prefs.getString(KEY_MODEL, "gpt-4o") ?: "gpt-4o"
        val maxTurns = prefs.getInt(KEY_MAX_TURNS, 50)
        val autoCompactLimit = prefs.getInt(KEY_AUTO_COMPACT_LIMIT, 100_000)
        val modeName = prefs.getString(KEY_DEFAULT_MODE, AgentMode.BUILD.name) ?: AgentMode.BUILD.name
        val defaultMode = try {
            AgentMode.valueOf(modeName)
        } catch (_: IllegalArgumentException) {
            AgentMode.BUILD
        }

        return AgentConfig(
            maxTurns = maxTurns,
            autoCompactLimit = autoCompactLimit,
            guardianEnabled = guardianEnabled,
            defaultMode = defaultMode,
            workingDirectory = workingDirectory,
            permissionLevel = permissionLevel,
            prootAvailable = prootAvailable,
            developerInstructions = developerInstructions,
            model = model
        )
    }
}
