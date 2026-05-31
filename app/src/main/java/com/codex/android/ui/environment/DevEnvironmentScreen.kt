package com.codex.android.ui.environment

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.util.DevelopmentEnvironment
import com.codex.android.util.LinuxEnvironment
import kotlinx.coroutines.launch

/**
 * 开发环境管理界面。
 *
 * 管理：
 * - 内置 Linux 环境安装（proot + Ubuntu rootfs）
 * - 环境状态检测
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevEnvironmentScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val devEnv = remember { DevelopmentEnvironment(context) }

    var envInfo by remember { mutableStateOf<DevelopmentEnvironment.EnvInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var isInstalling by remember { mutableStateOf(false) }
    var installLog by remember { mutableStateOf("") }
    var installProgress by remember { mutableStateOf("") }
    var currentAction by remember { mutableStateOf<String?>(null) }

    // 初始环境检测
    LaunchedEffect(Unit) {
        isLoading = true
        envInfo = devEnv.getEnvironmentInfo()
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("开发环境", fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // ===== 状态卡片 =====
            item {
                EnvironmentStatusCard(
                    envInfo = envInfo,
                    isLoading = isLoading
                )
            }

            // ===== 自包含 Linux 安装引导 =====
            if (envInfo?.state == DevelopmentEnvironment.EnvState.SELF_CONTAINED || envInfo?.state == DevelopmentEnvironment.EnvState.ERROR) {
                item {
                    InstallLinuxCard(
                        isInstalling = isInstalling,
                        installLog = installLog,
                        installProgress = installProgress,
                        currentAction = currentAction,
                        onInstall = {
                            isInstalling = true
                            installLog = ""
                            currentAction = "安装自包含 Linux"
                            scope.launch {
                                val linuxEnv = LinuxEnvironment(context)
                                val ok = linuxEnv.installRootfs(
                                    onProgress = { progress, total ->
                                        installProgress = if (total > 0) "${progress * 100 / total}%" else "${progress / 1024 / 1024}MB"
                                    },
                                    onStatus = { msg ->
                                        installLog = installLog + "\n" + msg
                                    }
                                )
                                if (ok) {
                                    installLog = "✅ 自包含 Linux 安装成功!"
                                    envInfo = devEnv.getEnvironmentInfo()
                                } else {
                                    installLog = "❌ 安装失败，请检查网络后重试"
                                }
                                isInstalling = false
                                currentAction = null
                            }
                        }
                    )
                }
            }



            // ===== 工具列表 =====
            if (envInfo?.state == DevelopmentEnvironment.EnvState.SELF_CONTAINED_LINUX) {
                item {
                    SectionTitle("已安装环境")
                }
                item {
                    ToolStatusList(envInfo!!)
                }
            }
            item {
                SectionTitle("开发工具")
            }
            item {
                ActionCard(
                    icon = Icons.Default.Build,
                    title = "安装开发工具",
                    subtitle = "Node.js / Python / Git / Cmake / Rust",
                    buttonText = "安装",
                    enabled = !isInstalling,
                    onAction = {
                            isInstalling = true
                            installLog = ""
                            currentAction = "安装开发工具"
                            scope.launch {
                                val linuxEnv = LinuxEnvironment(context)
                                val linuxInfo = linuxEnv.getInfo()
                                if (linuxInfo.state != LinuxEnvironment.EngineState.READY) {
                                    installLog = "请先安装 Linux 环境"
                                    isInstalling = false
                                    currentAction = null
                                    return@launch
                                }

                                installLog = "正在安装开发工具...\n"
                                val tools = listOf(
                                    "apt-get update" to "更新软件源",
                                    "apt-get install -y nodejs" to "安装 Node.js",
                                    "apt-get install -y python3 python3-pip" to "安装 Python3",
                                    "apt-get install -y git" to "安装 Git"
                                )

                                for ((cmd, name) in tools) {
                                    installLog = installLog + "\n> $name..."
                                    val result = linuxEnv.runCommand(cmd, 120_000)
                                    if (result.exitCode == 0) {
                                        installLog = installLog + " OK"
                                    } else {
                                        installLog = installLog + " FAIL (exit=${result.exitCode})"
                                        if (result.stderr.isNotBlank()) {
                                            installLog = installLog + "\n  ${result.stderr.take(200)}"
                                        }
                                    }
                                }

                                installLog = installLog + "\n\nDone!"
                                envInfo = devEnv.getEnvironmentInfo()
                                isInstalling = false
                                currentAction = null
                            }
                        }
                    )
                }

                // 刷新环境
                item {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                envInfo = devEnv.getEnvironmentInfo()
                                isLoading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("刷新环境检测")
                    }
                }

            // ===== 安装日志 =====
            if (installLog.isNotBlank()) {
                item {
                    SectionTitle("安装日志")
                }
                item {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0A0A0F)
                    ) {
                        LazyColumn(
                            modifier = Modifier.padding(8.dp)
                        ) {
                            item {
                                Text(
                                    installLog,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF4AF626),
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }

            // ===== 已安装工具列表 =====
            if (envInfo != null && envInfo!!.state != DevelopmentEnvironment.EnvState.ERROR) {
                item {
                    SectionTitle("已安装环境")
                }

                item {
                    ToolStatusList(envInfo!!)
                }
            }

            // ===== 已安装工具详情 =====
            if (envInfo != null && envInfo!!.state != DevelopmentEnvironment.EnvState.ERROR) {
                item {
                    SectionTitle("工具版本")
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            
                            ToolVersionRow("Node.js", envInfo?.nodeVersion ?: "-", envInfo?.hasNodeJs == true)
                            ToolVersionRow("Python", envInfo?.pythonVersion ?: "-", envInfo?.hasPython == true)
                            ToolVersionRow("Git", envInfo?.gitVersion ?: "-", envInfo?.hasGit == true)
                            ToolVersionRow("Codex CLI", "已安装", envInfo?.hasCodex == true)
                        }
                    }
                }
            }

            // ===== 关于 =====
            item {
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Text(
                        "开发环境基于内置 proot + Ubuntu 24.04 LTS\n无需外部 Termux 依赖",
                        modifier = Modifier.padding(16.dp),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ===== 状态卡片 =====
@Composable
private fun EnvironmentStatusCard(
    envInfo: DevelopmentEnvironment.EnvInfo?,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            if (isLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在检测环境...", fontSize = 14.sp)
                }
            } else if (envInfo == null) {
                Text("环境检测失败", color = MaterialTheme.colorScheme.error)
            } else {
                // 状态图标和文字
                Row(verticalAlignment = Alignment.Top) {
                    val (icon, color, text) = when (envInfo.state) {
                        DevelopmentEnvironment.EnvState.SELF_CONTAINED_LINUX ->
                            Triple(Icons.Default.CheckCircle, Color(0xFF2ED573), "自包含 Linux 已就绪（可运行 Codex）")
                        DevelopmentEnvironment.EnvState.SELF_CONTAINED ->
                            Triple(Icons.Default.Warning, Color(0xFFFFA502), "受限模式（无法运行 Codex）")
                        DevelopmentEnvironment.EnvState.ERROR ->
                            Triple(Icons.Default.Error, Color(0xFFFF4757), "环境异常")
                    }
                    val description = when (envInfo.state) {
                        DevelopmentEnvironment.EnvState.SELF_CONTAINED ->
                            "未检测到 Linux 环境。请安装内置 Linux 环境（proot + Ubuntu）。"
                        DevelopmentEnvironment.EnvState.SELF_CONTAINED_LINUX ->
                            "自包含 Linux 环境已就绪，可通过 proot 运行 Codex。"
                        else -> ""
                    }
                    Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                        if (description.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                description,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 18.sp
                            )
                        }
                        if (envInfo.errorMessage.isNotBlank()) {
                            Text(envInfo.errorMessage, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}


// ===== 可复制命令行 =====

@Composable
private fun CopyableCommand(command: String, onCopy: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF0A0A0F),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCopy(command) }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                command,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF4AF626),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "复制",
                tint = Color(0xFF8888AA),
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onCopy(command) }
                    .padding(5.dp)
            )
        }
    }
}

// ===== 操作卡片 =====
@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    buttonText: String,
    buttonColor: Color = MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onAction,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                if (enabled) Text(buttonText, fontSize = 13.sp)
                else CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
    }
}

// ===== 工具状态列表 =====
@Composable
private fun ToolStatusList(info: DevelopmentEnvironment.EnvInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            ToolStatusRow("Codex", info.state != DevelopmentEnvironment.EnvState.ERROR)
            ToolStatusRow("Ubuntu", info.hasUbuntu)
            ToolStatusRow("Node.js", info.hasNodeJs)
            ToolStatusRow("Python", info.hasPython)
            ToolStatusRow("Git", info.hasGit)
            ToolStatusRow("Codex CLI", info.hasCodex)
        }
    }
}

@Composable
private fun ToolStatusRow(name: String, installed: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (installed) Icons.Default.CheckCircle else Icons.Default.Cancel,
            null,
            tint = if (installed) Color(0xFF2ED573) else Color(0xFF8888AA),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(name, fontSize = 14.sp)
    }
}

@Composable
private fun ToolVersionRow(name: String, version: String, installed: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (installed) version else "未安装",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (installed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        title,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

// ===== 自包含 Linux 安装卡片 =====
@Composable
fun InstallLinuxCard(
    isInstalling: Boolean,
    installLog: String,
    installProgress: String,
    currentAction: String?,
    onInstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Terminal,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("自包含 Linux 环境", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(
                        "安装 proot + Ubuntu 24.04 LTS (arm64)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (currentAction != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    currentAction,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (installProgress.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    installProgress,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (installLog.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0A0A0F)
                ) {
                    Text(
                        installLog.trimStart(),
                        modifier = Modifier.padding(8.dp),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF4AF626),
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onInstall,
                enabled = !isInstalling,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                if (isInstalling) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("安装中...")
                } else {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("安装 Linux 环境")
                }
            }
        }
    }
}
