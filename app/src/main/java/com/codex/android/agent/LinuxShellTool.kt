package com.codex.android.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Linux Shell 工具 - 在 proot Ubuntu 环境中执行命令。
 * 仅在 proot 已就绪时注册。
 */
class LinuxShellTool(private val context: Context) : AgentTool {
    override val name = "linux_shell"
    override val description = buildString {
        appendLine("在 Ubuntu proot Linux 环境中执行命令。")
        appendLine("可使用完整的 Linux 工具链：python3, node, npm, git, gcc, pip3, apt-get 等。")
        appendLine("仅当 Linux 环境已安装时可用。")
    }
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("command", JSONObject().apply { put("type", "string"); put("description", "要在 Linux 中执行的命令") })
            put("workdir", JSONObject().apply { put("type", "string"); put("description", "工作目录（可选）") })
            put("timeout_ms", JSONObject().apply { put("type", "integer"); put("description", "超时（毫秒），默认 60000") })
        })
        put("required", JSONArray().put("command"))
    }

    override suspend fun execute(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        val command = args.optString("command", "")
        if (command.isBlank()) return "错误: 未提供命令"

        val linuxEnv = com.codex.android.util.LinuxEnvironment(context)
        if (!linuxEnv.isInstalled()) return "错误: Linux 环境未就绪"

        val timeoutMs = args.optLong("timeout_ms", 60_000L)
        val workdir = args.optString("workdir", null)
        val effectiveCmd = if (workdir != null) "cd $workdir 2>/dev/null && $command" else command

        val result = linuxEnv.runCommand(effectiveCmd, timeoutMs)
        return buildString {
            appendLine("退出码: ${result.exitCode}")
            if (result.stdout.isNotBlank()) append(result.stdout.take(8000))
            if (result.stderr.isNotBlank()) append("\n[stderr] ${result.stderr.take(2000)}")
            if (result.isTimedOut) appendLine("\n⚠️ 命令超时")
        }
    }
}
