package com.codex.android.tools

import android.content.Context
import android.util.Log
import com.codex.android.agent.AgentMode
import com.codex.android.security.SecurityPolicy
import com.codex.android.security.ShellConfirmationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 工具注册与执行编排器。
 *
 * 对标 OpenCode 的 ToolRegistry + ToolExecutor，统一管理所有 [ToolHandler]，
 * 并通过四阶段 Pipeline 控制每次工具调用的生命周期：
 *
 * 1. **参数验证** — 调用 [ToolHandler.validateParams]，快速失败。
 * 2. **权限评估** — 通过 [PermissionMiddleware] 检查安全等级与危险操作。
 * 3. **Effect 执行** — 在协程作用域内调用 [ToolHandler.execute]，捕获异常。
 * 4. **输出截断** — 通过 [ToolResultBudget] 裁剪超长输出。
 *
 * 此外，编排器维护 Turn-scoped 审批缓存：同一轮对话中对同一危险操作的
 * 用户确认不会被重复请求。
 */
class ToolOrchestrator(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    companion object {
        private const val TAG = "ToolOrchestrator"
    }

    // ==================== 工具注册表 ====================

    /** 已注册的工具处理器，key 为工具名称。 */
    private val registry = ConcurrentHashMap<String, ToolHandler>()

    /**
     * 注册一个工具处理器。
     *
     * 若同名工具已存在，将覆盖（便于测试注入 Mock）。
     */
    fun register(handler: ToolHandler) {
        registry[handler.name] = handler
        Log.d(TAG, "已注册工具: ${handler.name}")
    }

    /**
     * 批量注册工具。
     */
    fun registerAll(handlers: List<ToolHandler>) {
        handlers.forEach { register(it) }
    }

    /**
     * 注销指定工具。
     */
    fun unregister(name: String) {
        registry.remove(name)
    }

    /**
     * 获取已注册的所有工具名称。
     */
    fun registeredTools(): Set<String> = registry.keys.toSet()

    /**
     * 获取指定名称的工具处理器。
     */
    fun getHandler(name: String): ToolHandler? = registry[name]

    /**
     * 根据 [AgentMode] 获取当前可用的工具列表。
     *
     * 不同模式可用的工具不同：
     * - BUILD  — 全部工具
     * - PLAN   — 只读工具（Shell 受限为只读命令集，FileWrite/FileEdit 不可用）
     * - SANDBOX — 仅安全工具（Shell 受限在工作目录内）
     */
    fun availableTools(mode: AgentMode): List<ToolHandler> {
        val restrictedTools = when (mode) {
            AgentMode.PLAN -> setOf("file_write", "file_edit")
            AgentMode.SANDBOX -> emptySet()
            AgentMode.BUILD -> emptySet()
        }
        return registry.values.filter { it.name !in restrictedTools }
    }

    /**
     * 根据模式生成 LLM function calling 可用的工具定义列表。
     */
    fun toolDefinitions(mode: AgentMode): List<JSONObject> {
        return availableTools(mode).map { handler ->
            JSONObject().apply {
                put("name", handler.name)
                put("description", handler.description)
                put("parameters", handler.parameters)
            }
        }
    }

    // ==================== Turn-scoped 审批缓存 ====================

    /**
     * Turn 内审批缓存。
     *
     * Key 为 "工具名:操作摘要"（如 "shell:rm -rf /tmp/build"），
     * Value 为用户是否已批准。
     *
     * 在同一 Turn 中，用户对同一操作的确认不会再次弹出。
     */
    private val approvalCache = ConcurrentHashMap<String, Boolean>()

    /**
     * 检查审批缓存。
     *
     * @return true 表示本次 Turn 内已被批准过，无需再次请求用户。
     */
    fun isApprovedInTurn(key: String): Boolean = approvalCache[key] == true

    /**
     * 记录审批结果到 Turn 缓存。
     */
    fun cacheApproval(key: String, approved: Boolean) {
        approvalCache[key] = approved
    }

    /**
     * 清空 Turn 缓存。应在每个 Turn 开始时调用。
     */
    fun clearTurnCache() {
        approvalCache.clear()
    }

    // ==================== 四阶段 Pipeline ====================

    /**
     * 执行工具调用的主入口。
     *
     * 依次经过参数验证 → 权限评估 → Effect 执行 → 输出截断 四个阶段。
     *
     * @param toolName 工具名称。
     * @param args     LLM 传入的参数 JSON。
     * @param mode     当前 Agent 运行模式。
     * @return 处理后的工具结果。
     */
    suspend fun execute(
        toolName: String,
        args: JSONObject,
        mode: AgentMode
    ): RawToolResult {
        Log.d(TAG, "执行工具: $toolName, 模式: $mode")

        // ── 阶段 0：工具查找 ──
        val handler = registry[toolName]
            ?: return RawToolResult(
                output = "未知工具: $toolName",
                isError = true,
                exitCode = -1
            )

        // ── 阶段 1：参数验证 ──
        val validationError = handler.validateParams(args)
        if (validationError != null) {
            Log.w(TAG, "参数验证失败: $toolName → $validationError")
            return RawToolResult(
                output = validationError.toString(),
                isError = true,
                metadata = validationError
            )
        }

        // ── 阶段 2：权限评估 ──
        val permResult = PermissionMiddleware.evaluate(
            context = context,
            handler = handler,
            args = args,
            mode = mode,
            approvalCache = approvalCache
        )
        if (permResult.denied) {
            Log.w(TAG, "权限评估拒绝: $toolName → ${permResult.reason}")
            return RawToolResult(
                output = permResult.reason,
                isError = true,
                metadata = JSONObject().apply {
                    put("permissionDenied", true)
                    put("reason", permResult.reason)
                }
            )
        }

        // ── 阶段 3：Effect 执行 ──
        val rawResult = try {
            withContext(scope.coroutineContext) {
                handler.execute(args, mode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "工具执行异常: $toolName", e)
            RawToolResult(
                output = "工具执行异常: ${e.message}",
                isError = true,
                exitCode = -1,
                metadata = JSONObject().apply {
                    put("exception", e.javaClass.simpleName)
                    put("message", e.message)
                }
            )
        }

        // ── 阶段 4：输出截断 ──
        val budgetedResult = ToolResultBudget.process(
            result = rawResult,
            tempDir = context.cacheDir
        )

        Log.d(TAG, "工具执行完成: $toolName, isError=${budgetedResult.isError}")
        return budgetedResult
    }

    // ==================== 权限中间件 ====================

    /**
     * 权限评估中间件。
     *
     * 在工具执行前统一检查：
     * - 安全等级是否允许该操作
     * - 危险操作是否需要用户确认
     * - AgentMode 是否限制该工具的使用方式
     *
     * 所有检查均为纯函数，不产生副作用。
     */
    object PermissionMiddleware {

        /**
         * 权限评估结果。
         */
        data class PermResult(
            val denied: Boolean,
            val reason: String = ""
        )

        /**
         * 执行权限评估。
         *
         * @param context       应用上下文，用于读取安全等级。
         * @param handler       目标工具处理器。
         * @param args          工具参数。
         * @param mode          当前 Agent 模式。
         * @param approvalCache Turn 内审批缓存。
         * @return 评估结果。denied=true 时应拒绝执行。
         */
        suspend fun evaluate(
            context: Context,
            handler: ToolHandler,
            args: JSONObject,
            mode: AgentMode,
            approvalCache: ConcurrentHashMap<String, Boolean>
        ): PermResult {
            // ── Shell 工具权限 ──
            if (handler.name == "shell") {
                return evaluateShellPermission(context, args, mode, approvalCache)
            }

            // ── 文件写入类工具权限 ──
            if (handler.name in setOf("file_write", "file_edit")) {
                val path = args.optString("path", "")
                if (path.isNotEmpty()) {
                    val denial = SecurityPolicy.checkFileAccess(context, path)
                    if (denial != null) {
                        return PermResult(denied = true, reason = denial)
                    }
                }

                // PLAN 模式禁止写入
                if (mode == AgentMode.PLAN) {
                    return PermResult(
                        denied = true,
                        reason = "PLAN 模式下不允许写入文件。请切换到 BUILD 模式执行写入操作。"
                    )
                }
            }

            // ── 文件读取类工具权限 ──
            if (handler.name in setOf("file_read", "search")) {
                val path = when (handler.name) {
                    "file_read" -> args.optString("path", "")
                    "search" -> args.optString("path", "")
                    else -> ""
                }
                if (path.isNotEmpty()) {
                    val denial = SecurityPolicy.checkFileAccess(context, path)
                    if (denial != null) {
                        return PermResult(denied = true, reason = denial)
                    }
                }
            }

            return PermResult(denied = false)
        }

        /**
         * Shell 工具专用权限评估。
         */
        private suspend fun evaluateShellPermission(
            context: Context,
            args: JSONObject,
            mode: AgentMode,
            approvalCache: ConcurrentHashMap<String, Boolean>
        ): PermResult {
            // 安全等级检查
            if (!SecurityPolicy.isShellAllowed(context)) {
                return PermResult(
                    denied = true,
                    reason = SecurityPolicy.shellDeniedResponse(context)
                )
            }

            val command = args.optString("command", "")
            if (command.isBlank()) {
                return PermResult(denied = false)
            }

            // PLAN 模式只允许只读命令
            if (mode == AgentMode.PLAN) {
                val readOnlyCommands = setOf(
                    "ls", "cat", "head", "tail", "find", "grep", "git", "pwd",
                    "which", "whoami", "echo", "wc", "sort", "uniq", "diff",
                    "file", "stat", "du", "df", "env", "printenv", "type",
                    "less", "more", "tree", "dirname", "basename"
                )
                val baseCommand = command.trim().split(Regex("\\s+")).firstOrNull() ?: ""
                if (baseCommand !in readOnlyCommands) {
                    return PermResult(
                        denied = true,
                        reason = "PLAN 模式下仅允许只读命令（如 ls, cat, find, grep, git status 等）。" +
                            "当前命令 \"$baseCommand\" 不在允许列表中。请切换到 BUILD 模式。"
                    )
                }
            }

            // 危险命令检测
            val dangerReason = SecurityPolicy.dangerousCommandReason(command)
            if (dangerReason != null) {
                val cacheKey = "shell:$command"
                if (approvalCache[cacheKey] == true) {
                    return PermResult(denied = false)
                }

                // 请求用户确认
                val approved = ShellConfirmationManager.requestApproval(command, dangerReason)
                approvalCache[cacheKey] = approved

                if (!approved) {
                    return PermResult(
                        denied = true,
                        reason = SecurityPolicy.shellRejectedByUserResponse(command, dangerReason)
                    )
                }
            }

            return PermResult(denied = false)
        }
    }
}
