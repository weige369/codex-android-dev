package com.codex.android.agent

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import java.io.File

/**
 * 初始提示构建器。
 *
 * 按照四层消息结构构建 Agent 的初始对话上下文，
 * 将权限说明、开发者指令、用户指令和环境信息注入到对话历史中。
 *
 * 四层结构（照搬 Codex 架构）：
 * 1. [role=developer] 沙箱权限说明 — 告知 LLM 当前可用工具和安全约束
 * 2. [role=developer] 开发者指令（可选） — 用户自定义的系统级指令
 * 3. [role=user] 用户指令聚合 — 合并 AGENTS.md 项目指令和用户输入
 * 4. [role=user] 环境上下文 — cwd/shell/os/device/arch/permission_level/proot_available
 *
 * @property context Android 上下文，用于读取 SharedPreferences 和项目文件
 * @property config Agent 配置，提供权限等级和工作目录等信息
 */
class PromptBuilder(
    private val context: Context,
    private val config: AgentConfig
) {

    companion object {
        private const val TAG = "PromptBuilder"

        /** 项目级指令文件名（类似 .editorconfig / .claude） */
        private const val AGENTS_MD = "AGENTS.md"

        /** 安全等级偏好键 */
        private const val KEY_PERMISSION_LEVEL = "permission_level"

        /** 开发者指令偏好键 */
        private const val KEY_DEVELOPER_INSTRUCTIONS = "developer_instructions"
    }

    /** 偏好存储 */
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(AgentConfig.PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ========== 第一层：沙箱权限说明 ==========

    /**
     * 构建沙箱权限说明消息。
     *
     * 根据当前 Agent 模式和权限等级，生成描述可用工具和安全约束的
     * developer 消息。LLM 将依据此消息判断可以执行哪些操作。
     *
     * @return 沙箱权限说明的 ConversationItem
     */
    private fun buildPermissionMessage(): ConversationHistory.ConversationItem.SystemMessage {
        val mode = config.defaultMode
        val sb = StringBuilder()

        sb.appendLine("你是一个运行在 Android 设备上的 AI 编程助手。")
        sb.appendLine()

        // 权限等级说明
        sb.appendLine("## 权限等级")
        sb.appendLine("当前模式: ${mode.displayName} (${mode.name})")
        sb.appendLine("权限级别: ${mode.permission}")
        if (mode.isReadOnly) {
            sb.appendLine("⚠️ 当前为只读模式，禁止修改文件或执行写入操作。")
        }
        sb.appendLine()

        // 可用工具列表
        sb.appendLine("## 可用工具")
        mode.defaultTools.forEach { tool ->
            sb.appendLine("- **${tool.name}**: ${tool.description}")
        }
        sb.appendLine()

        // 安全约束
        sb.appendLine("## 安全约束")
        sb.appendLine("- 仅使用上述列出的工具，不要尝试使用未列出的工具")
        sb.appendLine("- 不要尝试访问工作目录以外的敏感系统路径")
        sb.appendLine("- 执行可能有害的操作前需要确认")
        if (config.prootAvailable) {
            sb.appendLine("- proot 可用，可以在隔离环境中执行命令")
        } else {
            sb.appendLine("- proot 不可用，命令将在应用沙箱中执行")
        }

        return ConversationHistory.ConversationItem.SystemMessage(
            content = sb.toString().trimEnd(),
            role = "developer"
        )
    }

    // ========== 第二层：开发者指令 ==========

    /**
     * 构建开发者指令消息（可选）。
     *
     * 从 SharedPreferences 读取用户设置的开发者自定义指令，
     * 如编码规范、项目特定约束等。未设置时跳过此层。
     *
     * @return 开发者指令的 ConversationItem，未设置时返回 null
     */
    private fun buildDeveloperInstructions(): ConversationHistory.ConversationItem.SystemMessage? {
        val instructions = config.developerInstructions
            ?: prefs.getString(KEY_DEVELOPER_INSTRUCTIONS, null)

        if (instructions.isNullOrBlank()) return null

        return ConversationHistory.ConversationItem.SystemMessage(
            content = instructions.trim(),
            role = "developer"
        )
    }

    // ========== 第三层：用户指令聚合 ==========

    /**
     * 查找并读取项目目录中的 AGENTS.md 文件。
     *
     * AGENTS.md 是项目级别的指令文件，类似于 .editorconfig，
     * 包含项目特定的编码规范、架构约束和开发约定。
     * 查找顺序：工作目录 → 工作目录的父目录（最多向上 3 层）。
     *
     * @return AGENTS.md 的内容，未找到时返回 null
     */
    private fun findAgentsMd(): String? {
        var dir = File(config.workingDirectory)
        var depth = 0

        while (dir.exists() && depth < 4) {
            val agentsFile = File(dir, AGENTS_MD)
            if (agentsFile.exists() && agentsFile.canRead()) {
                return try {
                    agentsFile.readText().trim().ifEmpty { null }
                } catch (e: Exception) {
                    Log.w(TAG, "读取 AGENTS.md 失败: ${e.message}")
                    null
                }
            }
            val parent = dir.parentFile ?: break
            dir = parent
            depth++
        }
        return null
    }

    /**
     * 构建用户指令聚合消息。
     *
     * 将 AGENTS.md 的项目指令作为用户指令层注入。
     * 如果存在 AGENTS.md，将其内容包装为结构化的指令消息。
     *
     * @param userInput 用户输入的 prompt（可为空）
     * @return 用户指令消息列表
     */
    private fun buildUserInstructions(userInput: String?): List<ConversationHistory.ConversationItem> {
        val messages = mutableListOf<ConversationHistory.ConversationItem>()
        val contentBuilder = StringBuilder()

        // AGENTS.md 项目指令
        val agentsMd = findAgentsMd()
        if (!agentsMd.isNullOrBlank()) {
            contentBuilder.appendLine("## 项目指令 (AGENTS.md)")
            contentBuilder.appendLine(agentsMd)
            contentBuilder.appendLine()
        }

        // 用户输入
        if (!userInput.isNullOrBlank()) {
            contentBuilder.appendLine("## 用户任务")
            contentBuilder.appendLine(userInput.trim())
        }

        if (contentBuilder.isNotEmpty()) {
            messages.add(ConversationHistory.ConversationItem.UserMessage(
                content = contentBuilder.toString().trimEnd()
            ))
        }

        return messages
    }

    // ========== 第四层：环境上下文 ==========

    /**
     * 构建环境上下文消息。
     *
     * 注入当前运行环境的详细信息，使 LLM 了解：
     * - 工作目录 (cwd)
     * - 默认 shell 类型
     * - 操作系统版本
     * - 设备型号
     * - CPU 架构
     * - 权限等级
     * - proot 是否可用
     *
     * @return 环境上下文的 ConversationItem
     */
    private fun buildEnvironmentContext(): ConversationHistory.ConversationItem.UserMessage {
        val sb = StringBuilder()

        sb.appendLine("## 环境信息")
        sb.appendLine("- 工作目录: ${config.workingDirectory}")
        sb.appendLine("- Shell: /system/bin/sh")
        sb.appendLine("- 操作系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("- 设备: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("- CPU 架构: ${Build.SUPPORTED_ABIS?.firstOrNull() ?: "unknown"}")
        sb.appendLine("- 权限等级: ${config.permissionLevel}")
        sb.appendLine("- proot 可用: ${config.prootAvailable}")

        return ConversationHistory.ConversationItem.UserMessage(
            content = sb.toString().trimEnd()
        )
    }

    // ========== 公开接口 ==========

    /**
     * 构建完整的初始消息列表。
     *
     * 按四层结构依次构建并返回所有初始 ConversationItem，
     * 将作为 Agent 首轮对话的上下文注入到 ConversationHistory 中。
     *
     * @param userInput 用户输入的 prompt（可选，延迟注入到第三层）
     * @return 按顺序排列的初始消息列表
     */
    fun buildInitialMessages(userInput: String? = null): List<ConversationHistory.ConversationItem> {
        val messages = mutableListOf<ConversationHistory.ConversationItem>()

        // 第一层：沙箱权限说明
        messages.add(buildPermissionMessage())

        // 第二层：开发者指令（可选）
        buildDeveloperInstructions()?.let { messages.add(it) }

        // 第三层：用户指令聚合（AGENTS.md + 用户输入）
        messages.addAll(buildUserInstructions(userInput))

        // 第四层：环境上下文
        messages.add(buildEnvironmentContext())

        Log.d(TAG, "构建初始消息: ${messages.size} 条, 模式=${config.defaultMode.name}")
        return messages
    }
}
