package com.codex.android.agent

import org.json.JSONObject

/**
 * Agent 工具接口。
 * 学习 Operit 的 ToolExecutor 模式，每个工具实现此接口。
 */
interface AgentTool {
    val name: String
    val description: String
    val parameterSchema: JSONObject

    /**
     * 执行工具。
     * @param argumentsJson JSON 格式的参数字符串
     * @return 工具执行结果文本
     */
    suspend fun execute(argumentsJson: String): String
}

/**
 * Shell 命令执行工具。
 * 参考 Operit StandardShellToolExecutor，直接用 Runtime.exec() 执行命令。
 * 不依赖外部 Codex CLI 二进制。
 */
class ShellTool(private val context: android.content.Context) : AgentTool {
    override val name = "shell"
    override val description = "执行 Shell 命令并返回输出。可用于文件操作、系统信息查询、包管理等。"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("command", JSONObject().apply {
                put("type", "string")
                put("description", "要执行的 Shell 命令")
            })
        })
        put("required", JSONArray().put("command"))
    }

    override suspend fun execute(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        val command = args.optString("command", "")
        if (command.isBlank()) return "错误: 未提供命令"

        return try {
            // 检查是否需要 proot
            val linuxEnv = com.codex.android.util.LinuxEnvironment(context)
            val linuxInfo = linuxEnv.getInfo()

            if (linuxInfo.state == com.codex.android.util.LinuxEnvironment.EngineState.READY &&
                needsProot(command)) {
                // 通过 proot 执行
                val result = linuxEnv.runCommand(command, 30_000)
                buildString {
                    append("退出码: ${result.exitCode}\n")
                    if (result.stdout.isNotBlank()) append("输出:\n${result.stdout.take(8000)}\n")
                    if (result.stderr.isNotBlank()) append("错误:\n${result.stderr.take(2000)}\n")
                    if (result.isTimedOut) append("⚠️ 命令超时\n")
                }
            } else {
                // 直接执行
                executeDirect(command)
            }
        } catch (e: Exception) {
            "命令执行失败: ${e.message}"
        }
    }

    private fun needsProot(command: String): Boolean {
        // 这些命令需要 Linux 环境
        val prootCommands = listOf("apt", "apt-get", "dpkg", "node", "nodejs", "npm",
            "python", "python3", "pip", "pip3", "gcc", "g++", "make", "cmake")
        return prootCommands.any { command.startsWith(it) || command.contains(" $it ") }
    }

    private fun executeDirect(command: String): String {
        return try {
            val useShell = command.contains("|") || command.contains("&&") ||
                command.contains(">") || command.contains("<") || command.contains(";")

            val process = if (useShell) {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            } else {
                Runtime.getRuntime().exec(command)
            }

            val completed = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return "命令超时（30秒）"
            }

            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.exitValue()

            buildString {
                append("退出码: $exitCode\n")
                if (stdout.isNotBlank()) append("输出:\n${stdout.take(8000)}\n")
                if (stderr.isNotBlank()) append("错误:\n${stderr.take(2000)}\n")
            }
        } catch (e: Exception) {
            "执行失败: ${e.message}"
        }
    }
}

/**
 * 文件读取工具
 */
class FileReadTool(private val context: android.content.Context) : AgentTool {
    override val name = "file_read"
    override val description = "读取文件内容。支持读取应用内部存储中的文件。"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "文件路径（相对路径基于应用内部存储根目录）")
            })
        })
        put("required", JSONArray().put("path"))
    }

    override suspend fun execute(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        val path = args.optString("path", "")
        if (path.isBlank()) return "错误: 未提供文件路径"

        // 安全检查：禁止路径穿越
        if (path.contains("..")) return "错误: 路径不允许包含 '..'"

        val file = if (path.startsWith("/")) java.io.File(path) else java.io.File(context.filesDir, path)
        if (!file.exists()) return "文件不存在: ${file.absolutePath}"
        if (!file.canRead()) return "无法读取文件: ${file.absolutePath}"
        if (file.length() > 1_000_000) return "文件过大（${file.length() / 1024}KB），请分段读取"

        return try {
            file.readText().take(50000)
        } catch (e: Exception) {
            "读取失败: ${e.message}"
        }
    }
}

/**
 * 文件写入工具
 */
class FileWriteTool(private val context: android.content.Context) : AgentTool {
    override val name = "file_write"
    override val description = "写入文件。可创建新文件或覆盖已有文件。"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "文件路径（相对路径基于应用内部存储根目录）")
            })
            put("content", JSONObject().apply {
                put("type", "string")
                put("description", "要写入的文件内容")
            })
        })
        put("required", JSONArray().put("path").put("content"))
    }

    override suspend fun execute(argumentsJson: String): String {
        val args = JSONObject(argumentsJson)
        val path = args.optString("path", "")
        val content = args.optString("content", "")
        if (path.isBlank()) return "错误: 未提供文件路径"
        if (path.contains("..")) return "错误: 路径不允许包含 '..'"

        val file = if (path.startsWith("/")) java.io.File(path) else java.io.File(context.filesDir, path)

        return try {
            file.parentFile?.mkdirs()
            file.writeText(content)
            "文件已写入: ${file.absolutePath} (${content.length} 字符)"
        } catch (e: Exception) {
            "写入失败: ${e.message}"
        }
    }
}
