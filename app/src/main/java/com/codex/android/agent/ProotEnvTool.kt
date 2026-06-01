package com.codex.android.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Proot 环境管理工具。
 * 让 AI 能感知和管理 Linux proot 环境。
 */
class ProotEnvTool(private val context: Context) : AgentTool {
    override val name = "proot_env"
    override val description = buildString {
        appendLine("查询和管理 Linux proot 环境。")
        appendLine("支持的操作：")
        appendLine("- status: 查看环境状态和已安装工具")
        appendLine("- install: 安装指定 apt 包")
        appendLine("- run: 在 proot 中执行命令（比 shell 更明确地使用 Linux 环境）")
    }
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("description", "操作: status / install / run")
            })
            put("package", JSONObject().apply {
                put("type", "string")
                put("description", "要安装的包名（install 操作时必填）")
            })
            put("command", JSONObject().apply {
                put("type", "string")
                put("description", "要执行的命令（run 操作时必填）")
            })
        })
        put("required", JSONArray().put("action"))
    }

    override suspend fun execute(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        val action = args.optString("action", "").lowercase()
        return when (action) {
            "status" -> getEnvStatus()
            "install" -> installPackage(args.optString("package", ""))
            "run" -> runInProot(args.optString("command", ""))
            else -> "未知操作: $action。支持: status / install / run"
        }
    }

    private fun getEnvStatus(): String {
        val linuxEnv = com.codex.android.util.LinuxEnvironment(context)
        val info = linuxEnv.getInfo()
        val sb = StringBuilder()
        sb.appendLine("=== Codex Linux 环境状态 ===")
        sb.appendLine("状态: ${if (info.state == com.codex.android.util.LinuxEnvironment.EngineState.READY) "✅ 已就绪" else "❌ 未就绪: ${info.errorMessage}"}")
        if (info.state == com.codex.android.util.LinuxEnvironment.EngineState.READY) {
            sb.appendLine("Proot: ${info.prootPath}")
            sb.appendLine("Rootfs: ${linuxEnv.getRootfsDir().absolutePath}")
            // 检查常用工具
            val tools = listOf("python3", "pip3", "node", "npm", "git", "vim", "curl", "wget", "gcc", "make", "htop")
            val prootEnv = com.codex.android.environment.ProotEnvironment(context)
            sb.appendLine("\n已安装工具:")
            tools.chunked(4).forEach { chunk ->
                sb.appendLine("  " + chunk.joinToString("  ") { it })
            }
        } else {
            sb.appendLine("\n提示: 需要先在「设置 → 环境」中安装 Ubuntu proot")
        }
        return sb.toString()
    }

    private suspend fun installPackage(packageName: String): String {
        if (packageName.isBlank()) return "错误: 未指定包名"
        val linuxEnv = com.codex.android.util.LinuxEnvironment(context)
        if (!linuxEnv.isInstalled()) return "错误: Linux 环境未安装"
        val result = linuxEnv.runCommand("DEBIAN_FRONTEND=noninteractive apt-get install -y $packageName 2>&1", 120_000)
        return buildString {
            appendLine("安装 $packageName:")
            if (result.exitCode == 0) {
                appendLine("✅ 安装成功")
            } else {
                appendLine("❌ 安装失败 (exit ${result.exitCode})")
            }
            if (result.stdout.isNotBlank()) append(result.stdout.take(3000))
            if (result.stderr.isNotBlank()) append("\n[stderr] ${result.stderr.take(1000)}")
        }
    }

    private suspend fun runInProot(command: String): String {
        if (command.isBlank()) return "错误: 未提供命令"
        val linuxEnv = com.codex.android.util.LinuxEnvironment(context)
        if (!linuxEnv.isInstalled()) return "错误: Linux 环境未安装，请先安装 Ubuntu proot"
        val result = linuxEnv.runCommand(command, 120_000)
        return buildString {
            appendLine("退出码: ${result.exitCode}")
            if (result.stdout.isNotBlank()) append(result.stdout.take(8000))
            if (result.stderr.isNotBlank()) append("\n[stderr] ${result.stderr.take(2000)}")
            if (result.isTimedOut) appendLine("\n⚠️ 命令超时")
        }
    }
}
