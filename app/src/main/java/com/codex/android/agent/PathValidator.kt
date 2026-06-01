package com.codex.android.agent

/**
 * 路径安全校验器。
 *
 * 移植自 Operit PathValidator，防止：
 * - 路径穿越（../）
 * - 访问系统敏感目录
 * - 注入攻击
 */
object PathValidator {

    /** 禁止访问的系统目录 */
    private val BLOCKED_PATHS = listOf(
        "/system", "/proc", "/sys", "/dev",
        "/data/data", "/data/app",
        "/sbin", "/vendor", "/etc"
    )

    /** 允许访问的应用数据目录前缀 */
    private val ALLOWED_APP_PATHS = listOf(
        "/data/data/com.codex.android",
        "/sdcard", "/storage/emulated",
        "/tmp", "/data/local/tmp"
    )

    /**
     * 校验路径安全性。
     * @return 错误消息，null 表示安全
     */
    fun validatePath(path: String): String? {
        val normalized = try {
            java.io.File(path).canonicalPath
        } catch (_: Exception) {
            return "路径无效: $path"
        }

        // 路径穿越检查
        if (path.contains("..")) {
            return "路径不允许包含 '..'"
        }

        // 系统目录检查（非 root 设备本身也有限制，但多一层防御）
        val blocked = BLOCKED_PATHS.firstOrNull { normalized.startsWith(it) }
        if (blocked != null) {
            // 允许应用自身数据目录
            val allowed = ALLOWED_APP_PATHS.firstOrNull { normalized.startsWith(it) }
            if (allowed == null) {
                return "禁止访问系统目录: $blocked"
            }
        }

        return null
    }

    /**
     * 校验 Linux (proot) 环境路径安全性。
     * proot 内路径更宽松，但仍需防止路径穿越。
     */
    fun validateLinuxPath(path: String): String? {
        if (path.contains("..")) {
            return "路径不允许包含 '..'"
        }
        if (path.isBlank()) {
            return "路径不能为空"
        }
        return null
    }

    /**
     * 校验命令安全性。
     * 检测可能危险的命令模式。
     */
    fun validateCommand(command: String): String? {
        val trimmed = command.trim()

        // 危险命令检测
        val dangerousPatterns = listOf(
            "rm -rf /", "rm -rf /*", "mkfs", "dd if=",
            ":(){ :|:& };:", "> /dev/sda",
            "chmod -R 777 /", "chown -R"
        )

        val matched = dangerousPatterns.firstOrNull { trimmed.contains(it) }
        if (matched != null) {
            return "检测到危险命令模式: $matched，请确认是否要执行"
        }

        return null
    }
}
