package com.codex.android.ui.setup

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.util.DevelopmentEnvironment
import com.codex.android.util.LinuxEnvironment
import kotlinx.coroutines.launch

/**
 * 首次启动引导向导。
 *
 * 三步流程：
 * 1. 欢迎 + 权限设置
 * 2. 一键安装 Linux 环境（proot + Ubuntu rootfs）
 * 3. 完成
 *
 * 可由环境设置页面重新打开。
 */
@Composable
fun SetupWizardScreen(
    onComplete: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentStep by remember { mutableIntStateOf(0) }

    // 步骤状态
    var notificationGranted by remember { mutableStateOf(checkNotificationPermission(context)) }
    var isInstalling by remember { mutableStateOf(false) }
    var installLog by remember { mutableStateOf("") }
    var installProgress by remember { mutableStateOf("") }
    var installSuccess by remember { mutableStateOf<Boolean?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }
    var hasSkippedInstall by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationGranted = granted
    }

    val steps = listOf(
        WizardStep(
            title = "欢迎使用 Codex Android",
            subtitle = "第一步：权限设置",
            icon = Icons.Default.Star
        ),
        WizardStep(
            title = "安装 Linux 环境",
            subtitle = "第二步：一键安装",
            icon = Icons.Default.Terminal
        ),
        WizardStep(
            title = "准备就绪",
            subtitle = "第三步：完成设置",
            icon = Icons.Default.CheckCircle
        )
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(32.dp))

            // 步骤指示器
            StepIndicator(
                currentStep = currentStep,
                totalSteps = steps.size,
                titles = steps.map { it.subtitle }
            )

            Spacer(Modifier.height(32.dp))

            // 步骤内容
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    fadeIn() + slideInHorizontally { it } togetherWith
                            fadeOut() + slideOutHorizontally { -it }
                },
                label = "step_content"
            ) { step ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (step) {
                        0 -> WelcomeStep(
                            notificationGranted = notificationGranted,
                            onRequestNotification = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    notificationGranted = true
                                }
                            }
                        )
                        1 -> InstallStep(
                            isInstalling = isInstalling,
                            installLog = installLog,
                            installProgress = installProgress,
                            installSuccess = installSuccess,
                            installError = installError,
                            hasSkippedInstall = hasSkippedInstall,
                            onInstall = {
                                isInstalling = true
                                installLog = ""
                                installError = null
                                installSuccess = null
                                scope.launch {
                                    val devEnv = DevelopmentEnvironment(context)
                                    val ok = devEnv.installSelfContainedLinux(
                                        onProgress = { progress, total ->
                                            installProgress = if (total > 0) "${progress * 100 / total}%" else "${progress / 1024 / 1024}MB"
                                        },
                                        onStatus = { msg ->
                                            installLog = installLog + "\n" + msg
                                        }
                                    )
                                    installSuccess = ok
                                    installError = if (!ok) "安装失败，请检查网络后重试" else null
                                    isInstalling = false
                                }
                            },
                            onSkipInstall = {
                                hasSkippedInstall = true
                            }
                        )
                        2 -> FinishStep(
                            installSuccess = installSuccess,
                            hasSkippedInstall = hasSkippedInstall
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // 底部按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 跳过/返回按钮
                if (currentStep > 0) {
                    OutlinedButton(
                        onClick = { currentStep-- }
                    ) {
                        Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("上一步")
                    }
                } else {
                    TextButton(
                        onClick = onSkip
                    ) {
                        Text("跳过设置", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // 下一步/完成按钮
                if (currentStep < steps.size - 1) {
                    Button(
                        onClick = { currentStep++ },
                        enabled = currentStep != 0 || notificationGranted
                    ) {
                        Text("下一步")
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(18.dp))
                    }
                } else {
                    Button(
                        onClick = onComplete
                    ) {
                        Text("开始使用")
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ===== 步骤数据 =====
private data class WizardStep(
    val title: String,
    val subtitle: String,
    val icon: ImageVector
)

// ===== 步骤指示器 =====
@Composable
private fun StepIndicator(
    currentStep: Int,
    totalSteps: Int,
    titles: List<String>
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until totalSteps) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.width(80.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (i <= currentStep) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (i < currentStep) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Text(
                                "${i + 1}",
                                color = if (i == currentStep) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    titles[i],
                    fontSize = 10.sp,
                    color = if (i == currentStep) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            if (i < totalSteps - 1) {
                Divider(
                    modifier = Modifier
                        .width(40.dp)
                        .padding(bottom = 16.dp),
                    color = if (i < currentStep) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}

// ===== 第一步：欢迎 + 权限 =====
@Composable
private fun WelcomeStep(
    notificationGranted: Boolean,
    onRequestNotification: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Android,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "欢迎使用 Codex Android",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Codex Android 是运行在手机上的 AI 编程助手，无需电脑即可完成代码编写、版本管理、问题排查等工作。",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(Modifier.height(24.dp))

        // 权限卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "权限设置",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(12.dp))

                PermissionItem(
                    icon = Icons.Default.Notifications,
                    title = "通知权限",
                    description = "用于显示 Codex 运行状态和任务进度通知",
                    granted = notificationGranted,
                    onRequest = onRequestNotification
                )

                Spacer(Modifier.height(8.dp))

                PermissionItem(
                    icon = Icons.Default.Storage,
                    title = "存储权限",
                    description = "用于访问和管理工作区文件",
                    granted = true,
                    onRequest = {}
                )

                Spacer(Modifier.height(8.dp))

                PermissionItem(
                    icon = Icons.Default.Terminal,
                    title = "Linux 环境",
                    description = "下一步将一键安装内置 Linux 环境",
                    granted = false,
                    onRequest = {}
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "需要授予通知权限以便接收 Codex 运行状态更新",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ===== 权限项 =====
@Composable
private fun PermissionItem(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (granted) Color(0xFF2ED573) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "已授权",
                tint = Color(0xFF2ED573),
                modifier = Modifier.size(20.dp)
            )
        } else if (title != "Linux 环境") {
            FilledTonalButton(
                onClick = onRequest,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("授权", fontSize = 12.sp)
            }
        }
    }
}

// ===== 第二步：安装 Linux =====
@Composable
private fun InstallStep(
    isInstalling: Boolean,
    installLog: String,
    installProgress: String,
    installSuccess: Boolean?,
    installError: String?,
    hasSkippedInstall: Boolean,
    onInstall: () -> Unit,
    onSkipInstall: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Terminal,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "安装 Linux 开发环境",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "Codex 需要 Linux 环境才能运行。点击下方按钮一键安装内置 Ubuntu 24.04 LTS（约 37MB 下载）。",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(Modifier.height(20.dp))

        if (hasSkippedInstall) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFF3E0)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFFE65100),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "你跳过了安装。可以在开发环境页面随时安装。",
                        fontSize = 13.sp,
                        color = Color(0xFF5D4037)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 安装按钮
        Button(
            onClick = onInstall,
            enabled = !isInstalling && installSuccess != true,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (installSuccess == true) Color(0xFF2ED573)
                else MaterialTheme.colorScheme.primary
            )
        ) {
            if (isInstalling) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text("安装中 $installProgress")
            } else if (installSuccess == true) {
                Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("安装完成 ✓")
            } else {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("一键安装 Linux 环境")
            }
        }

        // 跳过
        if (!isInstalling && installSuccess != true) {
            TextButton(onClick = onSkipInstall) {
                Text("跳过安装，稍后再说", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 安装日志
        if (installLog.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF0A0A0F)
            ) {
                Text(
                    installLog.lines().dropWhile { it.isEmpty() }.joinToString("\n"),
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = Color(0xFF4AF626),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                )
            }
        }

        if (installError != null) {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = Color(0xFFC62828),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        installError,
                        fontSize = 13.sp,
                        color = Color(0xFFC62828)
                    )
                }
            }
        }
    }
}

// ===== 第三步：完成 =====
@Composable
private fun FinishStep(
    installSuccess: Boolean?,
    hasSkippedInstall: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = if (installSuccess == true) Color(0xFF2ED573) else MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(16.dp))

        Text(
            "准备就绪!",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            when {
                installSuccess == true -> "Linux 环境已就绪，可以开始使用 Codex 了。"
                hasSkippedInstall -> "设置已跳过，可在开发环境页面中随时完成安装。"
                else -> "基础设置已完成，可以开始使用 Codex 了。"
            },
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp
        )

        Spacer(Modifier.height(24.dp))

        // 摘要卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "设置摘要",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(12.dp))

                SummaryItem(
                    icon = Icons.Default.Notifications,
                    text = "通知权限",
                    done = true
                )
                SummaryItem(
                    icon = Icons.Default.Terminal,
                    text = "Linux 环境",
                    done = installSuccess == true
                )
                SummaryItem(
                    icon = Icons.Default.Info,
                    text = "Codex 已准备就绪",
                    done = true
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "点击「开始使用」进入主界面。之后也可以在「开发环境」页面管理 Linux 环境和诊断。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SummaryItem(
    icon: ImageVector,
    text: String,
    done: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (done) Color(0xFF2ED573) else Color(0xFFFFA502)
        )
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            if (done) "✓ 已完成" else "⚠ 未完成",
            fontSize = 12.sp,
            color = if (done) Color(0xFF2ED573) else Color(0xFFFFA502)
        )
    }
}

private fun checkNotificationPermission(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}
