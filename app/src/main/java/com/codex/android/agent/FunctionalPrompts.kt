package com.codex.android.agent

/**
 * 功能性提示词模板。
 *
 * 移植自 Operit FunctionalPrompts，集中管理 Agent 系统使用的各类提示词：
 * 1. 对话摘要提示词 - 压缩历史时使用
 * 2. 工具系统提示词 - 描述可用工具的格式
 * 3. 记忆提取提示词 - 从对话中提取关键信息
 *
 * 设计原则：
 * - 提示词与逻辑分离，方便迭代优化
 * - 支持中英文切换
 * - 核心提示词尽量精简（减少 token 消耗）
 */
object FunctionalPrompts {

    // ===== 对话摘要 =====

    /**
     * 对话摘要提示词（中文）。
     * 当对话历史过长时，用此提示词让 LLM 生成压缩摘要。
     */
    const val SUMMARY_PROMPT_ZH = """
你是负责生成对话摘要的AI助手。根据"上一次摘要"（如有）和"最近对话"，生成全新摘要。

格式要求：
==========对话摘要==========

【核心任务状态】
当前步骤、已完成动作、正在处理事项、下一步。明确任务状态（已完成/进行中/等待中）。

【关键信息与上下文】
- 用户需求、限制、背景
- 技术关键元素（函数、配置、路径、命令）
- 问题探索路径和验证结果
- 影响后续决策的因素

============================

内容要求：专业、完整、自包含。聚焦最新对话，如新旧冲突以最新为准。摘要需使AI仅凭此摘要即可恢复上下文。"""

    /**
     * 对话摘要提示词（英文）
     */
    const val SUMMARY_PROMPT_EN = """
You are an AI assistant generating conversation summaries. Based on the "Previous Summary" (if any) and "Recent Conversation", generate a new summary.

Format:
==========Conversation Summary==========

[Core Task Status]
Current step, completed actions, ongoing work, next step. State task status explicitly.

[Key Information & Context]
- User requirements, constraints, background
- Technical key elements (functions, configs, paths, commands)
- Exploration path and verification results
- Factors affecting future decisions

========================================

Content: Professional, complete, self-contained. Focus on recent conversation. If conflicts, use latest. Summary must allow AI to fully reconstruct context."""

    fun summaryPrompt(useEnglish: Boolean = false): String {
        return if (useEnglish) SUMMARY_PROMPT_EN.trimIndent() else SUMMARY_PROMPT_ZH.trimIndent()
    }

    // ===== 工具格式提示 =====

    /**
     * XML 工具调用格式提示（嵌入 system prompt）
     * 告诉 LLM 如何使用 XML 格式调用工具（不支持 function calling 的模型）
     */
    const val TOOL_FORMAT_INSTRUCTION = """
当你需要使用工具时，可以：
1. 如果支持 function calling，直接调用工具
2. 如果不支持，使用 XML 格式：
<tool name="工具名">
<param name="参数名">参数值</param>
</tool>

可用工具：见下方工具列表。每个工具的参数见工具定义。"""

    // ===== 环境感知提示 =====

    /**
     * Proot 环境提示（嵌入 system prompt）
     */
    fun prootEnvironmentPrompt(hasProot: Boolean): String {
        return if (hasProot) {
            """
Linux 环境: Ubuntu proot 已就绪
- shell 工具会自动通过 proot 执行 Linux 命令
- 可直接执行: python3, npm, git, gcc, make 等
- 用 apt-get install 按需安装更多工具"""
        } else {
            """
Linux 环境: 未安装（仅 Android Shell 可用）
- 只能使用基础命令: ls, cat, grep, find, cp, mv 等
- 如需完整开发工具链，请引导用户安装 Ubuntu proot"""
        }
    }

    // ===== 安全提示 =====

    /**
     * 安全约束提示（嵌入 system prompt）
     */
    const val SAFETY_CONSTRAINTS = """
安全约束：
- 危险命令（rm -rf /, dd, mkfs 等）执行前必须提醒用户
- 不要删除系统关键文件
- 不要执行可能造成不可逆损害的操作
- 文件操作限制在应用内部存储范围内
- 网络操作需注意隐私和安全性"""
}
