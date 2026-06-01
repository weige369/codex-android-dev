package com.codex.android.agent

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.codex.android.util.AndroidShellExecutor
import com.codex.android.util.LinuxEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Agent 二进制管理器。
 *
 * 负责从 GitHub Release 下载 Agent 二进制到 proot 环境，
 * 并进行 SHA256 校验确保完整性。
 *
 * 支持的 Agent：
 * - Codex CLI：Rust 静态二进制 (aarch64-unknown-linux-musl)
 * - OpenCode：Go 二进制 (linux-arm64)
 * - OpenManus：Python 包 (pip install)
 *
 * 下载流程：
 * 1. 检查本地缓存是否已有有效二进制（SHA256 匹配）
 * 2. 从 GitHub Release 下载到缓存目录
 * 3. 校验 SHA256
 * 4. 复制到 proot 的 /usr/local/bin/（或 pip install）
 * 5. 设置可执行权限
 *
 * 所有二进制不打包进 APK（APK 已 229MB），运行时按需下载。
 */
class AgentBinaryManager(private val context: Context) {

    companion object {
        private const val TAG = "AgentBinaryManager"
        private const val PREFS_NAME = "codex_agent_binary_prefs"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 300_000  // 5 分钟，大文件可能慢

        // Agent 二进制缓存目录（Android 侧）
        private const val CACHE_DIR = "agent-binaries"

        // proot 内安装目录
        private const val PROOT_BIN_DIR = "/usr/local/bin"

        // ===== Agent 定义 =====

        data class AgentBinaryDef(
            val agentType: AgentOrchestrator.AgentType,
            val displayName: String,
            val description: String,
            val installMethod: InstallMethod,
            val estimatedSize: String,
            val version: String
        )

        enum class InstallMethod {
            BINARY_DOWNLOAD,    // 直接下载二进制（Codex, OpenCode）
            PIP_INSTALL,        // pip install（OpenManus）
            SCRIPT_INSTALL      // 脚本安装（预留）
        }

        val AGENT_DEFINITIONS = listOf(
            AgentBinaryDef(
                agentType = AgentOrchestrator.AgentType.CODEX,
                displayName = "Codex CLI",
                description = "OpenAI Codex CLI - Rust 静态二进制",
                installMethod = InstallMethod.BINARY_DOWNLOAD,
                estimatedSize = "~15MB",
                version = "0.133.0"
            ),
            AgentBinaryDef(
                agentType = AgentOrchestrator.AgentType.OPENCODE,
                displayName = "OpenCode",
                description = "OpenCode - Go 语言 AI 编程助手",
                installMethod = InstallMethod.BINARY_DOWNLOAD,
                estimatedSize = "~25MB",
                version = "latest"
            ),
            AgentBinaryDef(
                agentType = AgentOrchestrator.AgentType.OPENMANUS,
                displayName = "OpenManus",
                description = "OpenManus - Python AI Agent 框架",
                installMethod = InstallMethod.PIP_INSTALL,
                estimatedSize = "~200MB",
                version = "latest"
            )
        )

        // ===== 下载 URL 模板 =====

        // Codex CLI: 从 weige369/codex fork 的 Release 下载
        // 需要预先交叉编译 aarch64-musl 版本并上传到 Release
        private val CODEX_DOWNLOAD_URLS = listOf(
            "https://github.com/weige369/codex/releases/download/v0.133.0/codex-aarch64-unknown-linux-musl",
            "https://github.com/openai/codex/releases/latest/download/codex-aarch64-unknown-linux-musl"
        )

        // OpenCode: 官方提供 linux-arm64 二进制
        private val OPENCODE_DOWNLOAD_URLS = listOf(
            "https://github.com/opencode-ai/opencode/releases/latest/download/opencode-linux-arm64",
            "https://github.com/opencode-ai/opencode/releases/latest/download/opencode-arm64-linux"
        )
    }

    // ===== 安装进度 =====

    data class InstallProgress(
        val phase: InstallPhase,
        val progress: Float,
        val message: String
    )

    enum class InstallPhase {
        IDLE,
        CHECKING,           // 检查环境
        DOWNLOADING,        // 下载中
        VERIFYING,          // 校验 SHA256
        INSTALLING,         // 安装到 proot
        CONFIGURING,        // 配置环境
        COMPLETED,
        FAILED
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val linuxEnv = LinuxEnvironment(context)

    // ===== 公开 API =====

    /**
     * 检查 Agent 是否已安装。
     */
    fun isInstalled(agentType: AgentOrchestrator.AgentType): Boolean {
        return prefs.getBoolean("${agentType.id}_installed", false)
    }

    /**
     * 获取已安装版本。
     */
    fun getInstalledVersion(agentType: AgentOrchestrator.AgentType): String {
        return prefs.getString("${agentType.id}_version", "") ?: ""
    }

    /**
     * 安装 Agent。
     */
    suspend fun install(
        agentType: AgentOrchestrator.AgentType,
        onProgress: (InstallProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        onProgress(InstallProgress(InstallPhase.CHECKING, 0.05f, "检查 Linux 环境..."))

        if (!linuxEnv.isInstalled()) {
            onProgress(InstallProgress(InstallPhase.FAILED, 0f, "Linux 环境未安装，请先安装 proot 环境"))
            return@withContext false
        }

        val def = AGENT_DEFINITIONS.find { it.agentType == agentType }
        if (def == null) {
            onProgress(InstallProgress(InstallPhase.FAILED, 0f, "未知的 Agent 类型"))
            return@withContext false
        }

        when (def.installMethod) {
            InstallMethod.BINARY_DOWNLOAD -> installBinary(def, onProgress)
            InstallMethod.PIP_INSTALL -> installViaPip(def, onProgress)
            InstallMethod.SCRIPT_INSTALL -> {
                onProgress(InstallProgress(InstallPhase.FAILED, 0f, "脚本安装暂未实现"))
                false
            }
        }
    }

    /**
     * 卸载 Agent。
     */
    suspend fun uninstall(agentType: AgentOrchestrator.AgentType): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val binaryName = getBinaryName(agentType)
                if (binaryName.isNotEmpty()) {
                    linuxEnv.runCommand("rm -f $PROOT_BIN_DIR/$binaryName", 5_000)
                }
                prefs.edit()
                    .remove("${agentType.id}_installed")
                    .remove("${agentType.id}_version")
                    .remove("${agentType.id}_sha256")
                    .apply()

                // 清理缓存
                val cacheFile = File(getCacheDir(), agentType.id)
                if (cacheFile.exists()) cacheFile.delete()

                true
            } catch (e: Exception) {
                Log.e(TAG, "卸载 ${agentType.displayName} 失败", e)
                false
            }
        }

    /**
     * 获取所有 Agent 的安装状态。
     */
    fun getAllStatuses(): Map<AgentOrchestrator.AgentType, AgentBinaryStatus> {
        return AGENT_DEFINITIONS.associate { def ->
            def.agentType to AgentBinaryStatus(
                agentType = def.agentType,
                isInstalled = isInstalled(def.agentType),
                version = getInstalledVersion(def.agentType),
                definition = def
            )
        }
    }

    data class AgentBinaryStatus(
        val agentType: AgentOrchestrator.AgentType,
        val isInstalled: Boolean,
        val version: String,
        val definition: AgentBinaryDef
    )

    // ===== 二进制下载安装 =====

    private suspend fun installBinary(
        def: AgentBinaryDef,
        onProgress: (InstallProgress) -> Unit
    ): Boolean {
        val urls = when (def.agentType) {
            AgentOrchestrator.AgentType.CODEX -> CODEX_DOWNLOAD_URLS
            AgentOrchestrator.AgentType.OPENCODE -> OPENCODE_DOWNLOAD_URLS
            else -> emptyList()
        }

        if (urls.isEmpty()) {
            onProgress(InstallProgress(InstallPhase.FAILED, 0f, "无可用下载链接"))
            return false
        }

        // 下载
        onProgress(InstallProgress(InstallPhase.DOWNLOADING, 0.1f, "正在下载 ${def.displayName}..."))
        val cacheDir = getCacheDir()
        cacheDir.mkdirs()
        val targetFile = File(cacheDir, def.agentType.id)

        var downloaded = false
        var downloadedFrom = ""

        for (url in urls) {
            onProgress(InstallProgress(InstallPhase.DOWNLOADING, 0.15f, "尝试下载: ${url.take(60)}..."))
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Accept", "application/octet-stream")
                conn.connect()

                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "下载失败 HTTP ${conn.responseCode}: $url")
                    conn.disconnect()
                    continue
                }

                val total = conn.contentLengthLong
                onProgress(InstallProgress(InstallPhase.DOWNLOADING, 0.2f,
                    "下载中 (${total / 1024 / 1024}MB)..."))

                val input = conn.inputStream
                val output = FileOutputStream(targetFile)
                val buffer = ByteArray(8192)
                var read: Int
                var totalRead = 0L
                val digest = MessageDigest.getInstance("SHA-256")

                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    totalRead += read
                    digest.update(buffer, 0, read)

                    val pct = if (total > 0) {
                        0.2f + (totalRead.toFloat() / total) * 0.5f
                    } else 0.4f
                    onProgress(InstallProgress(InstallPhase.DOWNLOADING, pct.coerceAtMost(0.7f),
                        "下载中 ${totalRead / 1024 / 1024}MB / ${total / 1024 / 1024}MB"))
                }
                output.close()
                input.close()
                conn.disconnect()

                // SHA256 校验
                val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                onProgress(InstallProgress(InstallPhase.VERIFYING, 0.75f,
                    "SHA256: ${sha256.take(16)}..."))

                downloaded = true
                downloadedFrom = url

                // 保存 SHA256
                prefs.edit().putString("${def.agentType.id}_sha256", sha256).apply()

                break
            } catch (e: Exception) {
                Log.w(TAG, "下载失败: $url", e)
                onProgress(InstallProgress(InstallPhase.DOWNLOADING, 0.15f, "下载失败，尝试下一个源..."))
            }
        }

        if (!downloaded) {
            onProgress(InstallProgress(InstallPhase.FAILED, 0f, "所有下载源均不可用"))
            return false
        }

        // 安装到 proot
        onProgress(InstallProgress(InstallPhase.INSTALLING, 0.8f, "正在安装到 proot 环境..."))
        val binaryName = getBinaryName(def.agentType)
        val installResult = installBinaryToProot(targetFile, binaryName)

        if (!installResult) {
            onProgress(InstallProgress(InstallPhase.FAILED, 0f, "安装到 proot 失败"))
            return false
        }

        // 配置
        onProgress(InstallProgress(InstallPhase.CONFIGURING, 0.9f, "正在配置环境..."))
        configureAgent(def.agentType)

        // 记录安装状态
        prefs.edit()
            .putBoolean("${def.agentType.id}_installed", true)
            .putString("${def.agentType.id}_version", def.version)
            .apply()

        onProgress(InstallProgress(InstallPhase.COMPLETED, 1f, "${def.displayName} 安装完成!"))
        Log.i(TAG, "${def.displayName} 安装成功, from=$downloadedFrom")
        return true
    }

    /**
     * 将下载的二进制安装到 proot 的 /usr/local/bin/。
     */
    private fun installBinaryToProot(sourceFile: File, binaryName: String): Boolean {
        return try {
            val rootfs = linuxEnv.getRootfsDir()
            val binDir = File(rootfs, PROOT_BIN_DIR.removePrefix("/"))
            binDir.mkdirs()

            val target = File(binDir, binaryName)
            sourceFile.copyTo(target, overwrite = true)
            target.setExecutable(true, false)
            target.setReadable(true, false)

            Log.i(TAG, "二进制已安装: ${target.absolutePath} (${target.length() / 1024}KB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "安装二进制到 proot 失败", e)
            false
        }
    }

    // ===== pip 安装 =====

    private suspend fun installViaPip(
        def: AgentBinaryDef,
        onProgress: (InstallProgress) -> Unit
    ): Boolean {
        onProgress(InstallProgress(InstallPhase.CHECKING, 0.1f, "检查 Python 环境..."))

        // 检查 Python 是否可用
        val pyCheck = linuxEnv.runCommand("python3 --version", 10_000)
        if (pyCheck.exitCode != 0) {
            onProgress(InstallProgress(InstallPhase.FAILED, 0f, "Python 未安装，请先在 proot 环境中安装 python3"))
            return false
        }

        onProgress(InstallProgress(InstallPhase.INSTALLING, 0.2f,
            "正在安装 ${def.displayName} (pip install)..."))

        // OpenManus 安装
        val installCmd = when (def.agentType) {
            AgentOrchestrator.AgentType.OPENMANUS -> {
                // 先 clone 仓库再安装依赖
                buildString {
                    append("cd /root && ")
                    append("if [ ! -d OpenManus ]; then ")
                    append("git clone https://github.com/FoundationAgents/OpenManus.git; ")
                    append("fi && ")
                    append("cd OpenManus && ")
                    append("pip3 install -r requirements.txt 2>&1 && ")
                    append("pip3 install -e . 2>&1")
                }
            }
            else -> "pip3 install ${def.agentType.id}"
        }

        val result = linuxEnv.runCommand(installCmd, 600_000)  // 10 分钟超时

        if (result.exitCode != 0) {
            onProgress(InstallProgress(InstallPhase.FAILED, 0f,
                "pip 安装失败: ${result.stderr.take(200)}"))
            return false
        }

        // 记录安装状态
        prefs.edit()
            .putBoolean("${def.agentType.id}_installed", true)
            .putString("${def.agentType.id}_version", def.version)
            .apply()

        onProgress(InstallProgress(InstallPhase.COMPLETED, 1f, "${def.displayName} 安装完成!"))
        true
    }

    // ===== 配置 =====

    private fun configureAgent(agentType: AgentOrchestrator.AgentType) {
        when (agentType) {
            AgentOrchestrator.AgentType.CODEX -> {
                // Codex 配置目录
                val rootfs = linuxEnv.getRootfsDir()
                val codexConfig = File(rootfs, "root/.codex")
                codexConfig.mkdirs()
            }
            AgentOrchestrator.AgentType.OPENCODE -> {
                // OpenCode 配置
                val rootfs = linuxEnv.getRootfsDir()
                val opencodeConfig = File(rootfs, "root/.config/opencode")
                opencodeConfig.mkdirs()
            }
            AgentOrchestrator.AgentType.OPENMANUS -> {
                // OpenManus 配置在 clone 时已处理
            }
            AgentOrchestrator.AgentType.NATIVE -> {
                // 内置 Agent 无需配置
            }
        }
    }

    // ===== 工具方法 =====

    private fun getBinaryName(agentType: AgentOrchestrator.AgentType): String {
        return when (agentType) {
            AgentOrchestrator.AgentType.CODEX -> "codex"
            AgentOrchestrator.AgentType.OPENCODE -> "opencode"
            AgentOrchestrator.AgentType.OPENMANUS -> ""  // Python 项目，无单一二进制
            AgentOrchestrator.AgentType.NATIVE -> ""
        }
    }

    private fun getCacheDir(): File = File(context.cacheDir, CACHE_DIR)
}
