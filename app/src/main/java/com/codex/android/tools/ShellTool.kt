package com.codex.android.tools

import com.codex.android.agent.AgentMode
import com.codex.android.util.AndroidShellExecutor
import org.json.JSONObject

/**
 * Shell 命令执行工具。
 *
 * 照搬 Codex 的 shell tool 定义，提供通用命令执行能力。
 * 根据 [AgentMode] 选择不同的 [AndroidShellExecutor.PermissionLevel]：
 * - BUILD  → UBUNTU_PROOT（优先使用 proot 环境，获得完整 Linux 工具链）
 * - PLAN   → NORMAL（仅允许只读命令，由 Orchestrator 权限中间件拦截）
 * - SANDBOX → NORMAL（限制在工作目录内，由安全策略控制）
 *
 * 危险命令检测由 [com.codex.android.security.SecurityPolicy.dangerousCommandReason]
 * 在 Orchestrator 的权限评估阶段完成，此工具仅负责执行。
 */
class ShellTool : ToolHandler {

    companion object {
        /** 默认超时时间（毫秒）。 */
        private const val DEFAULT_TIMEOUT_MS = 120_000

        /** 最大超时时间（毫秒）。 */
        private const val MAX_TIMEOUT_MS = 300_000
    }

    override val name: String = "shell"

    override val description: String = buildString {
        appendLine("执行 Shell 命令并返回输出。")
        appendLine("支持多种执行环境（取决于安全等级和 Agent 模式）。")
        appendLine("危险命令（如 rm -rf、dd、mkfs 等）需要用户确认后才执行。")
        appendLine("PLAN 模式下仅允许只读命令。")
    }

    override val parameters: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("command", JSONObject().apply {
                put("type", "string")
                put("description", "要执行的 Shell 命令")
            })
            put("workdir", JSONObject().apply {
                put("type", "string")
                put("description", "工作目录（可选，默认为当前目录）")
            })
            put("timeout_ms", JSONObject().apply {
                put("type", "integer")
                put("description", "超时时间（毫秒，可选，默认 120000，最大 300000）")
            })
        })
        put("required", org.json.JSONArray().apply {
            put("command")
        })
    }

    override fun validateParams(args: JSONObject): JSONObject? {
        // command 必填
        if (!args.has("command") || args.optString("command").isBlank()) {
            return JSONObject().apply {
                put("error", "缺少必填参数 'command'")
                put("field", "command")
            }
        }

        // timeout_ms 范围检查
        val timeoutMs = args.optInt("timeout_ms", DEFAULT_TIMEOUT_MS)
        if (timeoutMs <= 0 || timeoutMs > MAX_TIMEOUT_MS) {
            return JSONObject().apply {
                put("error", "timeout_ms 必须在 1~$MAX_TIMEOUT_MS 之间")
                put("field", "timeout_ms")
            }
        }

        return null
    }

    override suspend fun execute(params: JSONObject, mode: AgentMode): RawToolResult {
        val command = params.getString("command")
        val workdir = params.optString("workdir", null)
        val timeoutMs = params.optInt("timeout_ms", DEFAULT_TIMEOUT_MS)

        // 根据 AgentMode 选择 PermissionLevel
        val permissionLevel = resolvePermissionLevel(mode)

        // 构建最终命令（如有 workdir，则 cd 到该目录后执行）
        val effectiveCommand = if (workdir != null) {
            "cd $workdir 2>/dev/null && $command"
        } else {
            command
        }

        // 构建环境变量（SANDBOX 模式限制 HOME）
        val env = buildEnvironmentVars(mode, workdir)

        val result = AndroidShellExecutor.execute(
            command = effectiveCommand,
            timeoutMs = timeoutMs.toLong(),
            permissionLevel = permissionLevel,
            env = env
        )

        val output = buildString {
            if (result.stdout.isNotBlank()) {
                append(result.stdout)
            }
            if (result.stderr.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append("[stderr] ")
                append(result.stderr)
            }
        }

        val metadata = JSONObject().apply {
            put("exitCode", result.exitCode)
            put("isTimedOut", result.isTimedOut)
            put("permissionLevel", result.permissionLevel.name)
            put("agentMode", mode.name)
        }

        return RawToolResult(
            output = output.ifBlank { "(命令无输出)" },
            isError = result.exitCode != 0,
            exitCode = result.exitCode,
            metadata = metadata
        )
    }

    /**
     * 根据 AgentMode 解析 Shell 执行权限等级。
     */
    private fun resolvePermissionLevel(mode: AgentMode): AndroidShellExecutor.PermissionLevel {
        return when (mode) {
            AgentMode.BUILD -> {
                // 优先使用 UBUNTU_PROOT（完整 Linux 环境）
                val devEnv = AndroidShellExecutor.getDevEnv()
                if (devEnv != null) {
                    AndroidShellExecutor.PermissionLevel.UBUNTU_PROOT
                } else {
                    AndroidShellExecutor.PermissionLevel.NORMAL
                }
            }
            AgentMode.PLAN -> AndroidShellExecutor.PermissionLevel.NORMAL
            AgentMode.SANDBOX -> AndroidShellExecutor.PermissionLevel.NORMAL
        }
    }

    /**
     * 根据模式构建环境变量。
     */
    private fun buildEnvironmentVars(
        mode: AgentMode,
        workdir: String?
    ): Map<String, String> {
        val env = mutableMapOf<String, String>()

        if (mode == AgentMode.SANDBOX && workdir != null) {
            env["HOME"] = workdir
        }

        return env
    }
}
