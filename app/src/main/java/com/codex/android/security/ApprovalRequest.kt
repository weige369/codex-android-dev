package com.codex.android.security

/**
 * 审批请求数据类。
 *
 * 用于三层权限中间件在评估过程中传递审批信息。
 * 当工具调用需要用户确认时（Layer 3），会生成 [ApprovalRequest]；
 * 用户响应后返回 [ApprovalResponse]，完成审批流程。
 */

/**
 * 审批请求。
 *
 * 描述一次需要用户确认的工具调用，包含调用标识、工具名称、
 * 调用参数、请求原因和创建时间戳。
 *
 * @property toolCallId 工具调用的唯一标识符（对应 LLM 返回的 tool_call.id）
 * @property toolName   工具名称（如 "shell"、"file_write"）
 * @property arguments  工具调用参数的 JSON 字符串
 * @property reason     请求用户确认的原因（如「危险命令：递归删除文件」）
 * @property timestamp  请求创建的时间戳（毫秒，System.currentTimeMillis()）
 */
data class ApprovalRequest(
    val toolCallId: String,
    val toolName: String,
    val arguments: String,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 审批响应。
 *
 * 用户对 [ApprovalRequest] 的确认或拒绝结果。
 *
 * @property requestId 对应的审批请求 ID（即 [ApprovalRequest.toolCallId]）
 * @property approved  用户是否批准执行。true 表示允许，false 表示拒绝
 * @property userNote  用户的附加备注（可选，如拒绝原因或附加指示）
 */
data class ApprovalResponse(
    val requestId: String,
    val approved: Boolean,
    val userNote: String = ""
)
