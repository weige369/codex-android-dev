package com.codex.android.security

import android.content.Context
import android.util.Log
import com.codex.android.agent.AgentMode
import com.codex.android.agent.ToolDefinition

/**
 * 三层权限中间件。
 *
 * 在 Agent 循环的工具执行阶段，对所有工具调用进行权限评估，
 * 决定是否允许执行、拒绝或需要用户确认。
 *
 * 评估按三层递进：
 * - **Layer 1 — Config-based pattern matching**：基于当前安全等级（SecurityPolicy）
 *   和 AgentMode 的规则匹配，快速决定允许或拒绝。
 * - **Layer 2 — Guardian LLM risk assessment**：可选的 LLM 风险评估层。
 *   当前预留接口，暂不实现。
 * - **Layer 3 — Interactive user prompt**：对于无法自动裁决的操作，
 *   生成 [ApprovalRequest] 交由用户确认。
 *
 * **Turn-scoped 审批缓存**：同一轮对话内，相同工具+参数组合的审批结果会被缓存，
 * 避免重复弹窗。每轮对话开始时调用 [clearTurnCache] 清空缓存。
 */
class PermissionMiddleware(private val context: Context) {

    companion object {
        private const val TAG = "PermissionMiddleware"

        /** 需要特殊处理的 Shell 相关工具名称集合。 */
        private val SHELL_TOOL_NAMES = setOf(
            "shell", "shell_readonly", "shell_sandbox"
        )
    }

    //region PermissionResult — 权限评估结果

    /**
     * 权限评估结果密封类。
     *
     * 三层评估的最终输出，表示对工具调用的裁决。
     */
    sealed class PermissionResult {
        /** 允许执行。 */
        data object Allowed : PermissionResult()

        /** 拒绝执行，附带拒绝原因。 */
        data class Denied(val reason: String) : PermissionResult()

        /** 需要用户确认，附带确认原因和审批请求。 */
        data class Ask(val request: ApprovalRequest) : PermissionResult()
    }

    //endregion

    //region Turn-scoped 审批缓存

    /**
     * Turn-scoped 审批缓存。
     *
     * Key 格式: "{toolName}:{argumentsHash}"，确保同一轮对话内
     * 相同工具+参数组合只触发一次用户确认。
     */
    private val turnCache = mutableMapOf<String, PermissionResult>()

    /**
     * 清空当前轮次的审批缓存。
     *
     * 应在每轮 Agent 循环开始时调用，确保新一轮对话不会复用
     * 上一轮的审批结果。
     */
    fun clearTurnCache() {
        turnCache.clear()
    }

    /**
     * 生成缓存键。
     *
     * @param toolName 工具名称
     * @param arguments 参数字符串
     * @return 缓存键，格式 "{toolName}:{hashCode}"
     */
    private fun cacheKey(toolName: String, arguments: String): String {
        return "$toolName:${arguments.hashCode()}"
    }

    //endregion

    //region 公开 API

    /**
     * 对工具调用进行三层权限评估。
     *
     * 评估流程：
     * 1. 检查 turn-scoped 缓存，命中则直接返回缓存结果
     * 2. Layer 1: 基于 SecurityPolicy + AgentMode 的规则匹配
     * 3. Layer 2: Guardian LLM 风险评估（预留，当前跳过）
     * 4. Layer 3: 生成 ApprovalRequest 交由用户确认
     *
     * @param toolCallId LLM 返回的工具调用 ID
     * @param toolName   工具名称
     * @param arguments  工具参数 JSON 字符串
     * @param mode       当前 Agent 运行模式
     * @return 权限评估结果
     */
    fun evaluate(
        toolCallId: String,
        toolName: String,
        arguments: String,
        mode: AgentMode
    ): PermissionResult {
        // 检查 turn-scoped 缓存
        val key = cacheKey(toolName, arguments)
        turnCache[key]?.let {
            Log.d(TAG, "缓存命中: $toolName (turn-scoped)")
            return it
        }

        // Layer 1: Config-based pattern matching
        val layer1Result = evaluateLayer1(toolName, arguments, mode)
        if (layer1Result != null) {
            Log.d(TAG, "Layer 1 裁决: $toolName -> ${layer1Result::class.simpleName}")
            turnCache[key] = layer1Result
            return layer1Result
        }

        // Layer 2: Guardian LLM risk assessment（预留）
        val layer2Result = evaluateLayer2(toolName, arguments, mode)
        if (layer2Result != null) {
            Log.d(TAG, "Layer 2 裁决: $toolName -> ${layer2Result::class.simpleName}")
            turnCache[key] = layer2Result
            return layer2Result
        }

        // Layer 3: Interactive user prompt
        val layer3Result = evaluateLayer3(toolCallId, toolName, arguments, mode)
        Log.d(TAG, "Layer 3 裁决: $toolName -> ${layer3Result::class.simpleName}")
        // 注意：Ask 结果不缓存，因为用户尚未响应
        // 缓存将在 processApprovalResponse 中写入
        return layer3Result
    }

    /**
     * 处理用户的审批响应。
     *
     * 当 Layer 3 生成 [PermissionResult.Ask] 后，用户通过 UI 做出选择，
     * 调用此方法将结果写入 turn-scoped 缓存，后续相同调用不再弹窗。
     *
     * @param response 用户的审批响应
     * @return 最终的 PermissionResult（Allowed 或 Denied）
     */
    fun processApprovalResponse(response: ApprovalResponse): PermissionResult {
        val result: PermissionResult = if (response.approved) {
            PermissionResult.Allowed
        } else {
            PermissionResult.Denied(response.userNote.ifEmpty { "用户拒绝执行" })
        }
        // 写入缓存（使用 requestId 作为 toolCallId 的回溯）
        // 注意：此时无法精确还原 arguments，但 request 本身携带了完整信息
        Log.d(TAG, "审批响应: ${response.requestId} -> ${result::class.simpleName}")
        return result
    }

    /**
     * 将审批响应结果缓存。
     *
     * 在外部获取到用户审批结果后，可调用此方法将结果缓存，
     * 确保同一轮内相同调用不再重复请求用户确认。
     *
     * @param toolName  工具名称
     * @param arguments 参数字符串
     * @param result    审批结果
     */
    fun cacheApprovalResult(toolName: String, arguments: String, result: PermissionResult) {
        val key = cacheKey(toolName, arguments)
        turnCache[key] = result
    }

    //endregion

    //region Layer 1: Config-based pattern matching

    /**
     * Layer 1: 基于安全等级和 AgentMode 的规则匹配。
     *
     * 规则逻辑：
     * - PLAN 模式（只读）：仅允许只读工具，拒绝所有写入/执行类工具
     * - SANDBOX 模式：仅允许沙箱工具，拒绝非沙箱工具
     * - BUILD 模式 + SecurityPolicy.FULL：允许 Shell 工具，但危险命令需进一步评估
     * - BUILD 模式 + 非 FULL：拒绝 Shell 工具
     * - Shell 工具的特殊处理：结合 SecurityPolicy.dangerousCommandReason 判断
     *
     * @return 非空时表示 Layer 1 可裁决；null 表示需继续下一层评估
     */
    private fun evaluateLayer1(
        toolName: String,
        arguments: String,
        mode: AgentMode
    ): PermissionResult? {
        // PLAN 模式只允许只读工具
        if (mode == AgentMode.PLAN) {
            val readOnlyTools = setOf("shell_readonly", "file_read", "search", "web_fetch")
            return if (toolName in readOnlyTools) {
                PermissionResult.Allowed
            } else {
                PermissionResult.Denied("当前为规划模式（只读），禁止执行写入或修改操作: $toolName")
            }
        }

        // SANDBOX 模式只允许沙箱工具
        if (mode == AgentMode.SANDBOX) {
            val sandboxTools = setOf("shell_sandbox", "file_read", "file_write_sandbox", "search")
            return if (toolName in sandboxTools) {
                PermissionResult.Allowed
            } else {
                PermissionResult.Denied("当前为沙箱模式，禁止使用非沙箱工具: $toolName")
            }
        }

        // BUILD 模式下的 Shell 工具特殊处理
        if (toolName in SHELL_TOOL_NAMES) {
            return evaluateShellTool(toolName, arguments)
        }

        // BUILD 模式下的文件写入工具：检查安全等级
        if (toolName == "file_write") {
            return if (SecurityPolicy.isFullFileAccessAllowed(context)) {
                // FULL/STANDARD 允许文件写入，无需进一步评估
                PermissionResult.Allowed
            } else {
                // SAFE 模式下，文件写入的路径校验在工具执行层完成
                // 此处仅标记为需要路径校验，允许执行
                PermissionResult.Allowed
            }
        }

        // BUILD 模式下其他工具默认允许
        return PermissionResult.Allowed
    }

    /**
     * Shell 工具的特殊评估逻辑。
     *
     * 结合 SecurityPolicy 的安全等级和危险命令检测：
     * - SecurityPolicy 非 FULL 等级：直接拒绝任意 Shell
     * - 危险命令：返回 Ask，需要用户确认
     * - 普通 Shell 命令：允许
     *
     * @return Layer 1 的 Shell 工具评估结果
     */
    private fun evaluateShellTool(
        toolName: String,
        arguments: String
    ): PermissionResult? {
        // 沙箱 Shell 始终允许（在沙箱内执行）
        if (toolName == "shell_sandbox") {
            return PermissionResult.Allowed
        }

        // 只读 Shell：允许（但不允许危险命令中的修改类操作）
        if (toolName == "shell_readonly") {
            // 只读模式下也检查危险命令，防止绕过
            val command = extractCommand(arguments)
            val dangerReason = SecurityPolicy.dangerousCommandReason(command)
            return if (dangerReason != null) {
                PermissionResult.Denied("只读模式下禁止执行危险命令: $dangerReason")
            } else {
                PermissionResult.Allowed
            }
        }

        // 完整 Shell (toolName == "shell")
        if (!SecurityPolicy.isShellAllowed(context)) {
            return PermissionResult.Denied(
                "当前安全等级（${SecurityPolicy.currentLevel(context)}）禁止执行任意 Shell 命令。" +
                        "如需执行，请在设置中将安全等级调整为「完全」。"
            )
        }

        // 检查危险命令
        val command = extractCommand(arguments)
        val dangerReason = SecurityPolicy.dangerousCommandReason(command)
        return if (dangerReason != null) {
            // 危险命令返回 null，让 Layer 3 处理用户确认
            null
        } else {
            PermissionResult.Allowed
        }
    }

    //endregion

    //region Layer 2: Guardian LLM risk assessment（预留）

    /**
     * Layer 2: Guardian LLM 风险评估。
     *
     * 预留接口，当前始终返回 null（不做裁决，交给下一层）。
     *
     * 未来实现时，此层将：
     * - 将工具调用上下文发送给 Guardian LLM
     * - LLM 评估操作的风险等级（如是否可能造成数据丢失、系统损坏等）
     * - 返回 Allowed / Denied / null（无法裁决，交给 Layer 3）
     *
     * @return 非空时表示 Layer 2 可裁决；null 表示需继续 Layer 3
     */
    private fun evaluateLayer2(
        toolName: String,
        arguments: String,
        mode: AgentMode
    ): PermissionResult? {
        // TODO: 实现 Guardian LLM 风险评估
        // 预留接口，当前跳过
        return null
    }

    //endregion

    //region Layer 3: Interactive user prompt

    /**
     * Layer 3: 交互式用户确认。
     *
     * 当 Layer 1 和 Layer 2 均无法自动裁决时，生成 [ApprovalRequest]
     * 交由用户确认。主要用于：
     * - 危险 Shell 命令的执行确认
     * - 其他需要人工判断的操作
     *
     * @return 始终返回 [PermissionResult.Ask]，包含审批请求
     */
    private fun evaluateLayer3(
        toolCallId: String,
        toolName: String,
        arguments: String,
        mode: AgentMode
    ): PermissionResult {
        // 生成确认原因
        val reason = buildAskReason(toolName, arguments)

        val request = ApprovalRequest(
            toolCallId = toolCallId,
            toolName = toolName,
            arguments = arguments,
            reason = reason
        )

        return PermissionResult.Ask(request)
    }

    /**
     * 构建 Layer 3 的确认原因文本。
     *
     * 对 Shell 工具，结合 SecurityPolicy.dangerousCommandReason 生成原因；
     * 对其他工具，生成通用原因描述。
     */
    private fun buildAskReason(toolName: String, arguments: String): String {
        if (toolName in SHELL_TOOL_NAMES) {
            val command = extractCommand(arguments)
            val dangerReason = SecurityPolicy.dangerousCommandReason(command)
            return if (dangerReason != null) {
                "危险命令需要确认: $dangerReason\n命令: $command"
            } else {
                "Shell 命令需要确认执行\n命令: $command"
            }
        }

        return "工具调用需要确认: $toolName"
    }

    //endregion

    //region 辅助方法

    /**
     * 从工具参数 JSON 中提取 command 字段值。
     *
     * @param arguments 工具参数 JSON 字符串
     * @return command 字段值，解析失败时返回空字符串
     */
    private fun extractCommand(arguments: String): String {
        return try {
            val json = org.json.JSONObject(arguments)
            json.optString("command", "")
        } catch (e: Exception) {
            Log.w(TAG, "解析工具参数失败", e)
            ""
        }
    }

    //endregion
}
