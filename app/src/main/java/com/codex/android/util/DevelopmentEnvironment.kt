package com.codex.android.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 开发环境管理器。
 *
 * 使用内置 proot + Ubuntu rootfs 运行 Linux 程序。
 * 不再需要外部 Termux 安装。
 */
class DevelopmentEnvironment(val context: Context) {

    companion object {
        private const val TAG = "DevelopmentEnvironment"

        // 自包含路径（在 APP 内部）
        const val APP_BIN = "bin"
        const val APP_HOME = "home"

        // Android 系统 shell
        const val SYSTEM_SH = "/system/bin/sh"
        const val SYSTEM_BIN = "/system/bin"
    }

    /**
     * 环境状态
     */
    enum class EnvState {
        /** 自包含模式（无 proot Linux） */
        SELF_CONTAINED,
        /** 自包含 Linux（proot + rootfs）已就绪 */
        SELF_CONTAINED_LINUX,
        /** 环境出错 */
        ERROR
    }

    /**
     * 环境信息
     */
    data class EnvInfo(
        val state: EnvState = EnvState.SELF_CONTAINED,
        val hasNodeJs: Boolean = false,
        val hasPython: Boolean = false,
        val hasGit: Boolean = false,
        val hasUbuntu: Boolean = false,
        val hasCodex: Boolean = false,
        val hasProot: Boolean = false,
        val nodeVersion: String = "",
        val pythonVersion: String = "",
        val gitVersion: String = "",
        val ubuntuVersion: String = "",
        val errorMessage: String = ""
    )

    fun getAppBinDir(): File = File(context.filesDir, APP_BIN).also { it.mkdirs() }
    fun getAppHomeDir(): File = File(context.filesDir, APP_HOME).also { it.mkdirs() }

    /**
     * 完整环境检测。
     * 只检测内置 Linux 环境（proot + Ubuntu rootfs）。
     */
    suspend fun getEnvironmentInfo(): EnvInfo = withContext(Dispatchers.IO) {
        val appBinDir = getAppBinDir()
        val hasCodexSelf = File(appBinDir, "codex").canExecute()

        val linuxEnv = LinuxEnvironment(context)
        val linuxInfo = linuxEnv.getInfo()
        val hasSelfContainedLinux = linuxInfo.state == LinuxEnvironment.EngineState.READY

        if (hasSelfContainedLinux) {
            return@withContext EnvInfo(
                state = EnvState.SELF_CONTAINED_LINUX,
                hasCodex = hasCodexSelf,
                hasNodeJs = false,
                hasPython = false,
                hasGit = false,
                hasUbuntu = true,
                ubuntuVersion = "24.04 LTS (proot)"
            )
        }
        return@withContext EnvInfo(
            state = EnvState.SELF_CONTAINED,
            hasCodex = hasCodexSelf
        )
    }

    /**
     * 创建 proot 进程（供 AndroidShellExecutor 使用）
     */
    fun createProotProcess(command: String, env: Map<String, String> = emptyMap()): java.lang.Process {
        val linuxEnv = LinuxEnvironment(context)
        val cmd = linuxEnv.buildProotCommand(command)
        val prootEnv = linuxEnv.getProotEnv()

        return ProcessBuilder(cmd)
            .apply {
                environment().putAll(prootEnv)
                environment().putAll(env)
                redirectErrorStream(false)
            }
            .start()
    }

    /**
     * 安装自包含 Linux 环境（proot + Ubuntu rootfs）
     */
    suspend fun installSelfContainedLinux(
        onProgress: ((Long, Long) -> Unit)? = null,
        onStatus: ((String) -> Unit)? = null
    ): Boolean {
        val linuxEnv = LinuxEnvironment(context)
        return linuxEnv.installRootfs(onProgress, onStatus)
    }

    /**
     * 检查自包含 Linux 状态
     */
    fun getSelfContainedLinuxInfo(): LinuxEnvironment.LinuxEnvInfo {
        val linuxEnv = LinuxEnvironment(context)
        return linuxEnv.getInfo()
    }

    fun getSetupGuide(): String {
        return """
╔══════════════════════════════════════╗
║      Codex Android 开发环境          ║
╚══════════════════════════════════════╝

⚡ 内置 Linux 环境（免 Termux）
• 使用 proot 引擎在 App 内运行 Ubuntu 24.04 LTS
• 无需安装任何第三方 App
• 在"环境"页面点击"一键安装 Linux 环境"即可

完成后你将获得：
• Ubuntu 24.04 LTS 环境
• 可通过 proot 运行 Codex
        """.trimIndent()
    }
}
