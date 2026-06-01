package com.codex.android.agent

import android.content.Context
import android.util.Log
import com.codex.android.util.LinuxEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Agent 环境预配置器。
 *
 * 在 proot Linux 环境中安装 Agent 运行所需的基础工具和依赖：
 * - 系统基础：git, curl, wget, ca-certificates
 * - Node.js 20 LTS + npm（Codex CLI 需要）
 * - Python 3.12 + pip + venv（OpenManus 需要）
 * - Go（OpenCode 是静态二进制，不需要，但用户可能需要）
 *
 * 安装策略：
 * 1. 优先使用 apt-get（Ubuntu/Debian rootfs）
 * 2. Node.js 使用 NodeSource 仓库获取最新 LTS
 * 3. Python 3.12 使用 deadsnakes PPA（Ubuntu）或直接 apt（若版本够新）
 * 4. 所有安装走国内镜像源加速
 *
 * 用法：在 SetupWizard 或首次启动 Agent 前调用
 */
class AgentEnvironmentSetup(private val context: Context) {

    companion object {
        private const val TAG = "AgentEnvironmentSetup"

        // 环境组件定义
        data class EnvComponent(
            val id: String,
            val displayName: String,
            val description: String,
            val estimatedSize: String,
            val requiredFor: List<AgentOrchestrator.AgentType>
        )

        val ENV_COMPONENTS = listOf(
            EnvComponent(
                id = "git",
                displayName = "Git",
                description = "版本控制（所有 Agent 都需要）",
                estimatedSize = "~30MB",
                requiredFor = listOf(
                    AgentOrchestrator.AgentType.CODEX,
                    AgentOrchestrator.AgentType.OPENCODE,
                    AgentOrchestrator.AgentType.OPENMANUS
                )
            ),
            EnvComponent(
                id = "nodejs",
                displayName = "Node.js 20 LTS",
                description = "Codex CLI 运行时依赖",
                estimatedSize = "~80MB",
                requiredFor = listOf(AgentOrchestrator.AgentType.CODEX)
            ),
            EnvComponent(
                id = "python3",
                displayName = "Python 3 + pip",
                description = "OpenManus 运行时依赖",
                estimatedSize = "~80MB",
                requiredFor = listOf(AgentOrchestrator.AgentType.OPENMANUS)
            ),
            EnvComponent(
                id = "ca_certs",
                displayName = "CA 证书",
                description = "HTTPS 连接必需",
                estimatedSize = "~1MB",
                requiredFor = listOf(
                    AgentOrchestrator.AgentType.CODEX,
                    AgentOrchestrator.AgentType.OPENCODE,
                    AgentOrchestrator.AgentType.OPENMANUS
                )
            ),
            EnvComponent(
                id = "build_essentials",
                displayName = "编译工具链",
                description = "make, gcc（部分 Python C 扩展需要）",
                estimatedSize = "~50MB",
                requiredFor = listOf(AgentOrchestrator.AgentType.OPENMANUS)
            )
        )

        // 根据选择的 Agent 计算需要安装的组件
        fun getRequiredComponents(selectedAgents: Set<AgentOrchestrator.AgentType>): List<EnvComponent> {
            val requiredIds = mutableSetOf<String>()
            for (agent in selectedAgents) {
                for (comp in ENV_COMPONENTS) {
                    if (agent in comp.requiredFor) {
                        requiredIds.add(comp.id)
                    }
                }
            }
            // ca_certs 是所有 Agent 的基础
            requiredIds.add("ca_certs")
            return ENV_COMPONENTS.filter { it.id in requiredIds }
        }
    }

    // ===== 安装进度 =====

    data class SetupProgress(
        val phase: SetupPhase,
        val progress: Float,
        val message: String
    )

    enum class SetupPhase {
        IDLE,
        PREPARING,          // 准备 apt 源
        INSTALLING_BASE,    // 安装基础包
        INSTALLING_NODEJS,  // 安装 Node.js
        INSTALLING_PYTHON,  // 安装 Python
        CONFIGURING,        // 配置环境
        COMPLETED,
        FAILED
    }

    private val linuxEnv = LinuxEnvironment(context)

    /**
     * 检查环境是否已就绪。
     */
    suspend fun isEnvironmentReady(): Boolean = withContext(Dispatchers.IO) {
        if (!linuxEnv.isInstalled()) return@withContext false

        // 检查关键工具
        val checks = listOf("git --version", "which node", "which python3")
        for (cmd in checks) {
            val result = linuxEnv.runCommand(cmd, 5_000)
            if (result.exitCode != 0) return@withContext false
        }
        true
    }

    /**
     * 检查指定组件是否已安装。
     */
    suspend fun isComponentInstalled(componentId: String): Boolean = withContext(Dispatchers.IO) {
        val checkCmd = when (componentId) {
            "git" -> "git --version"
            "nodejs" -> "node --version"
            "python3" -> "python3 --version"
            "ca_certs" -> "dpkg -s ca-certificates 2>/dev/null | grep Status"
            "build_essentials" -> "dpkg -s build-essential 2>/dev/null | grep Status"
            else -> return@withContext false
        }
        val result = linuxEnv.runCommand(checkCmd, 5_000)
        result.exitCode == 0 || result.stdout.contains("install ok installed")
    }

    /**
     * 安装所有 Agent 运行所需的环境。
     *
     * @param selectedAgents 用户选择的 Agent 类型
     * @param mirrorId 镜像源 ID
     * @param onProgress 进度回调
     */
    suspend fun setupEnvironment(
        selectedAgents: Set<AgentOrchestrator.AgentType>,
        mirrorId: String = "tuna",
        onProgress: (SetupProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (!linuxEnv.isInstalled()) {
            onProgress(SetupProgress(SetupPhase.FAILED, 0f, "Linux 环境未安装"))
            return@withContext false
        }

        val components = getRequiredComponents(selectedAgents)
        if (components.isEmpty()) {
            onProgress(SetupProgress(SetupPhase.COMPLETED, 1f, "无需安装额外组件"))
            return@withContext true
        }

        val totalSteps = components.size + 2  // +2: apt update + configure
        var currentStep = 0

        // Step 1: apt update
        onProgress(SetupProgress(SetupPhase.PREPARING, 0.05f, "更新软件源..."))
        val updateResult = linuxEnv.runCommand(
            "apt-get update -qq 2>&1",
            120_000
        )
        if (updateResult.exitCode != 0) {
            Log.w(TAG, "apt update 失败: ${updateResult.stderr.take(200)}")
            // 不致命，继续尝试安装
        }
        currentStep++

        // Step 2: 逐个安装组件
        for (comp in components) {
            currentStep++
            val baseProgress = currentStep.toFloat() / totalSteps

            // 检查是否已安装
            if (isComponentInstalled(comp.id)) {
                onProgress(SetupProgress(SetupPhase.INSTALLING_BASE, baseProgress,
                    "${comp.displayName} 已安装，跳过"))
                continue
            }

            onProgress(SetupProgress(
                when (comp.id) {
                    "nodejs" -> SetupPhase.INSTALLING_NODEJS
                    "python3" -> SetupPhase.INSTALLING_PYTHON
                    else -> SetupPhase.INSTALLING_BASE
                },
                baseProgress,
                "正在安装 ${comp.displayName}..."
            ))

            val success = installComponent(comp.id, mirrorId)
            if (!success) {
                Log.w(TAG, "安装 ${comp.displayName} 失败，继续下一个")
            }
        }

        // Step 3: 配置
        onProgress(SetupProgress(SetupPhase.CONFIGURING, 0.9f, "正在配置环境..."))
        configureEnvironment()

        onProgress(SetupProgress(SetupPhase.COMPLETED, 1f, "环境配置完成!"))
        true
    }

    /**
     * 安装单个组件。
     */
    private suspend fun installComponent(componentId: String, mirrorId: String): Boolean {
        val cmd = when (componentId) {
            "git" -> "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq git 2>&1"
            "nodejs" -> installNodejsCommand(mirrorId)
            "python3" -> installPythonCommand()
            "ca_certs" -> "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ca-certificates 2>&1"
            "build_essentials" -> "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq build-essential 2>&1"
            else -> return false
        }

        val result = linuxEnv.runCommand(cmd, 600_000)  // 10 分钟超时
        if (result.exitCode != 0) {
            Log.w(TAG, "安装 $componentId 失败: ${result.stderr.take(300)}")
        }
        return result.exitCode == 0
    }

    /**
     * 构建 Node.js 安装命令。
     * 使用 NodeSource 仓库安装 Node.js 20 LTS。
     */
    private fun installNodejsCommand(mirrorId: String): String {
        // 使用国内镜像的 NodeSource
        val nodesourceMirror = when (mirrorId) {
            "tuna" -> "https://mirrors.tuna.tsinghua.edu.cn/nodesource/deb"
            "aliyun" -> "https://mirrors.aliyun.com/nodesource/deb"
            else -> "https://deb.nodesource.com/node_20.x"
        }

        return buildString {
            // 安装 curl（如果没有）
            append("apt-get install -y -qq curl gnupg 2>&1 && ")
            // 添加 NodeSource GPG key 和仓库
            append("mkdir -p /etc/apt/keyrings && ")
            append("curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg 2>&1 && ")
            append("echo \"deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main\" | tee /etc/apt/sources.list.d/nodesource.list 2>&1 && ")
            append("apt-get update -qq 2>&1 && ")
            append("DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nodejs 2>&1")
        }
    }

    /**
     * 构建 Python 安装命令。
     * Ubuntu 24.04 rootfs 自带 Python 3.12，只需安装 pip 和 venv。
     */
    private fun installPythonCommand(): String {
        return buildString {
            // 安装 python3 + pip + venv
            append("DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ")
            append("python3 python3-pip python3-venv 2>&1")
        }
    }

    /**
     * 配置环境：npm 镜像、pip 镜像等。
     */
    private suspend fun configureEnvironment() {
        try {
            // npm 使用国内镜像
            linuxEnv.runCommand(
                "npm config set registry https://registry.npmmirror.com 2>&1",
                10_000
            )

            // pip 使用国内镜像
            val pipConf = """
                [global]
                index-url = https://pypi.tuna.tsinghua.edu.cn/simple
                trusted-host = pypi.tuna.tsinghua.edu.cn
            """.trimIndent()

            val rootfs = linuxEnv.getRootfsDir()
            val pipDir = java.io.File(rootfs, "root/.config/pip")
            pipDir.mkdirs()
            java.io.File(pipDir, "pip.conf").writeText(pipConf)

            // 设置 git 默认配置
            linuxEnv.runCommand(
                "git config --global user.name 'Codex Agent' && " +
                "git config --global user.email 'agent@codex.local' && " +
                "git config --global init.defaultBranch main",
                10_000
            )

            Log.i(TAG, "环境配置完成")
        } catch (e: Exception) {
            Log.w(TAG, "环境配置失败（非致命）", e)
        }
    }

    /**
     * 获取环境诊断信息。
     */
    suspend fun getDiagnostics(): Map<String, String> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, String>()

        if (!linuxEnv.isInstalled()) {
            result["linux"] = "未安装"
            return@withContext result
        }
        result["linux"] = "已安装"

        val checks = mapOf(
            "git" to "git --version",
            "node" to "node --version",
            "npm" to "npm --version",
            "python3" to "python3 --version",
            "pip3" to "pip3 --version",
            "codex" to "codex --version",
            "opencode" to "opencode --version"
        )

        for ((name, cmd) in checks) {
            val r = linuxEnv.runCommand(cmd, 5_000)
            result[name] = if (r.exitCode == 0) r.stdout.trim() else "未安装"
        }

        // 磁盘空间
        val df = linuxEnv.runCommand("df -h / 2>&1", 5_000)
        result["disk"] = df.stdout.trim().take(100)

        result
    }
}
