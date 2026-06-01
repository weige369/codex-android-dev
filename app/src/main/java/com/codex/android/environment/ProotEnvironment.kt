package com.codex.android.environment

import android.content.Context
import android.util.Log
import com.codex.android.util.AndroidShellExecutor
import com.codex.android.util.LinuxEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * proot Linux 环境管理器。
 *
 * 超越 Operit 的简单 pnpm/pip 检测，提供完整的发行版安装、
 * 工具包管理和命令执行能力。
 *
 * 核心能力：
 * - 一键安装 Debian/Ubuntu/Alpine（通过 proot + rootfs 下载）
 * - 分类工具包管理（编程语言/开发工具/AI-ML/系统工具）
 * - apt-get 包安装与状态检测
 * - 存储空间预估与管理
 * - 自动配置中文 locale、时区、镜像源
 */
class ProotEnvironment(private val context: Context) {

    companion object {
        private const val TAG = "ProotEnvironment"

        // 支持的 Linux 发行版
        val SUPPORTED_DISTROS = listOf(
            DistroInfo("ubuntu", "Ubuntu 24.04 LTS", "推荐 · 生态最丰富", "~150MB"),
            DistroInfo("debian", "Debian 12", "稳定 · 服务器首选", "~120MB"),
            DistroInfo("alpine", "Alpine 3.19", "轻量 · 仅~5MB", "~30MB")
        )

        // 工具包定义（分类）
        val TOOL_CATEGORIES = listOf(
            ToolCategory(
                id = "languages",
                name = "编程语言",
                icon = "💻",
                tools = listOf(
                    ToolInfo("python3", "Python 3", "~60MB", "pip, venv"),
                    ToolInfo("nodejs", "Node.js 20 LTS", "~80MB", "npm, npx"),
                    ToolInfo("gcc", "GCC 编译器", "~40MB", "C/C++ 编译"),
                    ToolInfo("golang-go", "Go 语言", "~120MB", "go build"),
                    ToolInfo("rustc", "Rust 语言", "~200MB", "cargo"),
                    ToolInfo("default-jdk", "Java JDK", "~150MB", "javac, jar")
                )
            ),
            ToolCategory(
                id = "devtools",
                name = "开发工具",
                icon = "🔧",
                tools = listOf(
                    ToolInfo("git", "Git", "~30MB", "版本管理"),
                    ToolInfo("vim", "Vim", "~10MB", "编辑器"),
                    ToolInfo("curl", "cURL", "~2MB", "HTTP 客户端"),
                    ToolInfo("wget", "Wget", "~2MB", "下载工具"),
                    ToolInfo("openssh-client", "OpenSSH", "~15MB", "ssh, scp"),
                    ToolInfo("make", "Make", "~2MB", "构建工具"),
                    ToolInfo("cmake", "CMake", "~20MB", "C/C++ 构建")
                )
            ),
            ToolCategory(
                id = "aiml",
                name = "AI / ML",
                icon = "🤖",
                tools = listOf(
                    ToolInfo("python3-pip", "pip", "~10MB", "Python 包管理"),
                    ToolInfo("python3-venv", "Python venv", "~5MB", "虚拟环境")
                )
            ),
            ToolCategory(
                id = "system",
                name = "系统工具",
                icon = "📦",
                tools = listOf(
                    ToolInfo("htop", "htop", "~1MB", "进程监控"),
                    ToolInfo("neofetch", "neofetch", "~1MB", "系统信息"),
                    ToolInfo("zip", "zip", "~1MB", "压缩"),
                    ToolInfo("unzip", "unzip", "~1MB", "解压"),
                    ToolInfo("tmux", "tmux", "~3MB", "终端复用"),
                    ToolInfo("tree", "tree", "~1MB", "目录树")
                )
            )
        )

        // 镜像源配置
        val MIRROR_SOURCES = listOf(
            MirrorSource("default", "默认源", ""),
            MirrorSource("tuna", "清华大学", "https://mirrors.tuna.tsinghua.edu.cn"),
            MirrorSource("aliyun", "阿里云", "https://mirrors.aliyun.com"),
            MirrorSource("ustc", "中科大", "https://mirrors.ustc.edu.cn"),
            MirrorSource("huawei", "华为云", "https://mirrors.huaweicloud.com")
        )

        // Alpine rootfs 镜像（独立于 Ubuntu）
        private val ALPINE_ROOTFS_MIRRORS = listOf(
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz",
            "https://mirrors.aliyun.com/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz",
            "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz"
        )

        // Debian rootfs 镜像
        private val DEBIAN_ROOTFS_MIRRORS = listOf(
            "https://mirrors.tuna.tsinghua.edu.cn/debian-cd/current/arm64/iso-cd/debian-12.5.0-arm64-netinst.iso",
            "https://mirrors.aliyun.com/debian-cd/current/arm64/iso-cd/debian-12.5.0-arm64-netinst.iso"
        )
    }

    // 数据类
    data class DistroInfo(
        val id: String,
        val displayName: String,
        val description: String,
        val estimatedSize: String
    )

    data class ToolCategory(
        val id: String,
        val name: String,
        val icon: String,
        val tools: List<ToolInfo>
    )

    data class ToolInfo(
        val packageName: String,
        val displayName: String,
        val estimatedSize: String,
        val description: String
    )

    data class MirrorSource(
        val id: String,
        val displayName: String,
        val url: String
    )

    data class StorageInfo(
        val totalMB: Long,
        val usedMB: Long,
        val availableMB: Long,
        val rootfsMB: Long
    )

    data class InstallProgress(
        val phase: InstallPhase,
        val progress: Float,
        val message: String
    )

    enum class InstallPhase {
        IDLE, DOWNLOADING, EXTRACTING, CONFIGURING, INSTALLING_TOOLS, COMPLETED, FAILED
    }

    private val linuxEnv = LinuxEnvironment(context)

    /**
     * 检查发行版是否已安装
     */
    fun isDistroInstalled(): Boolean = linuxEnv.isInstalled()

    /**
     * 获取当前安装的发行版信息
     */
    fun getInstalledDistroInfo(): String {
        if (!isDistroInstalled()) return "未安装"
        val rootfs = linuxEnv.getRootfsDir()
        // 检测 os-release 判断发行版
        val osRelease = File(rootfs, "etc/os-release")
        if (osRelease.exists()) {
            val content = osRelease.readText()
            val nameMatch = Regex("""PRETTY_NAME="(.+?)"""").find(content)
            if (nameMatch != null) return nameMatch.groupValues[1]
        }
        return "Ubuntu (proot)"
    }

    /**
     * 安装 Linux 发行版
     * @param distro 发行版ID：ubuntu / debian / alpine
     * @param mirror 镜像源ID
     * @param onProgress 进度回调
     */
    suspend fun installDistro(
        distro: String,
        mirror: String,
        onProgress: (InstallProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            when (distro) {
                "ubuntu" -> installUbuntu(mirror, onProgress)
                "alpine" -> installAlpine(mirror, onProgress)
                "debian" -> installDebian(mirror, onProgress)
                else -> {
                    onProgress(InstallProgress(InstallPhase.FAILED, 0f, "不支持的发行版: $distro"))
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "安装发行版失败", e)
            onProgress(InstallProgress(InstallPhase.FAILED, 0f, "安装失败: ${e.message}"))
            false
        }
    }

    private suspend fun installUbuntu(
        mirror: String,
        onProgress: (InstallProgress) -> Unit
    ): Boolean {
        onProgress(InstallProgress(InstallPhase.DOWNLOADING, 0.1f, "正在下载 Ubuntu rootfs..."))
        val ok = linuxEnv.installRootfs(
            onProgress = { read, total ->
                val pct = if (total > 0) (read.toFloat() / total) * 0.6f + 0.1f else 0.3f
                onProgress(InstallProgress(InstallPhase.DOWNLOADING, pct.coerceAtMost(0.7f),
                    "下载中 ${read / 1024 / 1024}MB / ${total / 1024 / 1024}MB"))
            },
            onStatus = { msg ->
                when {
                    msg.contains("解压") -> onProgress(InstallProgress(InstallPhase.EXTRACTING, 0.7f, msg))
                    msg.contains("成功") -> onProgress(InstallProgress(InstallPhase.COMPLETED, 1f, msg))
                    else -> onProgress(InstallProgress(InstallPhase.DOWNLOADING, 0.3f, msg))
                }
            }
        )

        if (ok) {
            onProgress(InstallProgress(InstallPhase.CONFIGURING, 0.9f, "正在配置镜像源和 locale..."))
            configureDistro("ubuntu", mirror)
            onProgress(InstallProgress(InstallPhase.COMPLETED, 1f, "Ubuntu 安装完成!"))
        }
        return ok
    }

    private suspend fun installAlpine(
        mirror: String,
        onProgress: (InstallProgress) -> Unit
    ): Boolean {
        onProgress(InstallProgress(InstallPhase.DOWNLOADING, 0.1f, "正在下载 Alpine minirootfs (~5MB)..."))
        val ok = linuxEnv.installRootfs(
            distro = "alpine",
            onProgress = { read, total ->
                val pct = if (total > 0) (read.toFloat() / total) * 0.6f + 0.1f else 0.3f
                onProgress(InstallProgress(InstallPhase.DOWNLOADING, pct.coerceAtMost(0.7f),
                    "下载中 ${read / 1024 / 1024}MB / ${total / 1024 / 1024}MB"))
            },
            onStatus = { msg ->
                when {
                    msg.contains("解压") -> onProgress(InstallProgress(InstallPhase.EXTRACTING, 0.7f, msg))
                    msg.contains("成功") -> onProgress(InstallProgress(InstallPhase.COMPLETED, 1f, msg))
                    else -> onProgress(InstallProgress(InstallPhase.DOWNLOADING, 0.3f, msg))
                }
            }
        )

        if (ok) {
            onProgress(InstallProgress(InstallPhase.CONFIGURING, 0.9f, "正在配置..."))
            configureDistro("alpine", mirror)
            onProgress(InstallProgress(InstallPhase.COMPLETED, 1f, "安装完成!"))
        }
        return ok
    }

    private suspend fun installDebian(
        mirror: String,
        onProgress: (InstallProgress) -> Unit
    ): Boolean {
        onProgress(InstallProgress(InstallPhase.DOWNLOADING, 0.1f, "正在下载 Debian rootfs..."))
        val ok = linuxEnv.installRootfs(
            distro = "debian",
            onProgress = { read, total ->
                val pct = if (total > 0) (read.toFloat() / total) * 0.6f + 0.1f else 0.3f
                onProgress(InstallProgress(InstallPhase.DOWNLOADING, pct.coerceAtMost(0.7f),
                    "下载中 ${read / 1024 / 1024}MB / ${total / 1024 / 1024}MB"))
            },
            onStatus = { msg ->
                when {
                    msg.contains("解压") -> onProgress(InstallProgress(InstallPhase.EXTRACTING, 0.7f, msg))
                    msg.contains("成功") -> onProgress(InstallProgress(InstallPhase.COMPLETED, 1f, msg))
                    else -> onProgress(InstallProgress(InstallPhase.DOWNLOADING, 0.3f, msg))
                }
            }
        )

        if (ok) {
            onProgress(InstallProgress(InstallPhase.CONFIGURING, 0.9f, "正在配置..."))
            configureDistro("debian", mirror)
            onProgress(InstallProgress(InstallPhase.COMPLETED, 1f, "安装完成!"))
        }
        return ok
    }

    /**
     * 配置发行版：镜像源、locale、时区
     */
    private fun configureDistro(distro: String, mirror: String) {
        try {
            val rootfs = linuxEnv.getRootfsDir()

            // 配置镜像源（apt）
            if (distro != "alpine") {
                val mirrorUrl = MIRROR_SOURCES.find { it.id == mirror }?.url
                if (!mirrorUrl.isNullOrEmpty()) {
                    val sourcesList = File(rootfs, "etc/apt/sources.list")
                    if (sourcesList.exists()) {
                        val content = when (distro) {
                            "ubuntu" -> """
                                deb ${mirrorUrl}/ubuntu/ noble main restricted universe multiverse
                                deb ${mirrorUrl}/ubuntu/ noble-updates main restricted universe multiverse
                                deb ${mirrorUrl}/ubuntu/ noble-security main restricted universe multiverse
                            """.trimIndent()
                            "debian" -> """
                                deb ${mirrorUrl}/debian/ bookworm main contrib non-free
                                deb ${mirrorUrl}/debian/ bookworm-updates main contrib non-free
                                deb ${mirrorUrl}/debian-security/ bookworm-security main contrib non-free
                            """.trimIndent()
                            else -> sourcesList.readText()
                        }
                        sourcesList.writeText(content)
                    }
                }
            }

            // 配置中文 locale
            val localeGen = File(rootfs, "etc/locale.gen")
            if (localeGen.exists()) {
                val content = localeGen.readText()
                if (!content.contains("zh_CN.UTF-8")) {
                    localeGen.appendText("\nzh_CN.UTF-8 UTF-8\n")
                }
            }

            // 配置时区（中国标准时间）
            val localtime = File(rootfs, "etc/localtime")
            val shanghaiTz = File(rootfs, "usr/share/zoneinfo/Asia/Shanghai")
            if (shanghaiTz.exists()) {
                try {
                    localtime.delete()
                    android.system.Os.symlink(
                        "/usr/share/zoneinfo/Asia/Shanghai",
                        localtime.absolutePath
                    )
                } catch (_: Exception) {}
            }

            Log.i(TAG, "发行版配置完成: $distro, mirror: $mirror")
        } catch (e: Exception) {
            Log.w(TAG, "配置发行版失败（非致命）", e)
        }
    }

    /**
     * 安装开发工具包（通过 proot 内的 apt-get）
     */
    suspend fun installTools(
        tools: Set<String>,
        onProgress: (InstallProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isDistroInstalled()) {
            onProgress(InstallProgress(InstallPhase.FAILED, 0f, "Linux 环境未安装"))
            return@withContext false
        }

        if (tools.isEmpty()) {
            onProgress(InstallProgress(InstallPhase.COMPLETED, 1f, "无需安装"))
            return@withContext true
        }

        try {
            // 先更新 apt
            onProgress(InstallProgress(InstallPhase.INSTALLING_TOOLS, 0.05f, "正在更新软件源..."))
            val updateResult = executeInProot("apt-get update -qq 2>&1")
            Log.i(TAG, "apt update: ${updateResult.stdout.take(200)}")

            val total = tools.size
            var installed = 0

            for (tool in tools) {
                installed++
                val pct = 0.1f + (installed.toFloat() / total) * 0.85f
                onProgress(InstallProgress(
                    InstallPhase.INSTALLING_TOOLS,
                    pct,
                    "正在安装 $tool ($installed/$total)..."
                ))

                val installResult = executeInProot(
                    "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq $tool 2>&1"
                )

                if (installResult.exitCode != 0) {
                    Log.w(TAG, "安装 $tool 失败: ${installResult.stderr.take(200)}")
                }
            }

            onProgress(InstallProgress(InstallPhase.COMPLETED, 1f, "工具安装完成! 已安装 $installed 个包"))
            true
        } catch (e: Exception) {
            Log.e(TAG, "安装工具失败", e)
            onProgress(InstallProgress(InstallPhase.FAILED, 0f, "安装失败: ${e.message}"))
            false
        }
    }

    /**
     * 在 proot 环境中执行命令
     */
    suspend fun executeInProot(command: String): AndroidShellExecutor.ShellResult {
        return linuxEnv.runCommand(command)
    }

    /**
     * 获取已安装的工具列表
     */
    suspend fun getInstalledTools(): List<ToolInfo> = withContext(Dispatchers.IO) {
        if (!isDistroInstalled()) return@withContext emptyList()

        val installed = mutableListOf<ToolInfo>()
        for (category in TOOL_CATEGORIES) {
            for (tool in category.tools) {
                // 检查 dpkg 是否标记为已安装
                val result = executeInProot("dpkg -s ${tool.packageName} 2>/dev/null | grep Status")
                if (result.stdout.contains("install ok installed")) {
                    installed.add(tool)
                }
            }
        }
        installed
    }

    /**
     * 获取存储空间信息
     */
    fun getStorageUsage(): StorageInfo {
        val rootfs = linuxEnv.getRootfsDir()
        val rootfsSize = if (rootfs.exists()) getDirSize(rootfs) else 0L

        val filesDir = context.filesDir
        val totalSpace = filesDir.totalSpace / 1024 / 1024
        val freeSpace = filesDir.freeSpace / 1024 / 1024
        val usedSpace = totalSpace - freeSpace

        return StorageInfo(
            totalMB = totalSpace,
            usedMB = usedSpace,
            availableMB = freeSpace,
            rootfsMB = rootfsSize / 1024 / 1024
        )
    }

    /**
     * 估算选中工具的总大小
     */
    fun estimateToolsSize(tools: Set<String>): String {
        var totalMB = 0
        for (category in TOOL_CATEGORIES) {
            for (tool in category.tools) {
                if (tool.packageName in tools) {
                    val sizeStr = tool.estimatedSize.replace("~", "").replace("MB", "").trim()
                    totalMB += sizeStr.toIntOrNull() ?: 0
                }
            }
        }
        return "~${totalMB}MB"
    }

    private fun getDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        var size = 0L
        dir.listFiles()?.forEach { size += getDirSize(it) }
        return size
    }
}
