package com.codex.android.ui.setup

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// ViewModel manually instantiated - no lifecycle-viewmodel-compose dependency
import com.codex.android.environment.ProotEnvironment
import com.codex.android.ui.theme.CodexPrimary
import com.codex.android.ui.theme.UbuntuOrange
import kotlinx.coroutines.launch

/**
 * 全面启动设置向导 - 超越 Operit 的分步引导式 SetupWizard。
 *
 * 6 步流程：
 * 1. 欢迎 + 设备检测
 * 2. 权限设置（存储/通知/电池/悬浮窗/位置）
 * 3. Linux 环境（发行版选择 + 一键安装 proot）
 * 4. 开发工具（分类选择 + apt-get 安装）
 * 5. AI 配置（提供商 + API Key + 连接测试）
 * 6. 完成（环境总览）
 */
@Composable
fun SetupWizardScreen(
    onComplete: () -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val viewModel = remember { SetupWizardViewModel() }
    val scope = rememberCoroutineScope()

    // 初始化 ViewModel
    LaunchedEffect(Unit) {
        viewModel.init(context)
    }

    // 收集状态
    val currentStep by viewModel.currentStep.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val permissionStates by viewModel.permissionStates.collectAsState()
    val selectedDistro by viewModel.selectedDistro.collectAsState()
    val selectedMirror by viewModel.selectedMirror.collectAsState()
    val linuxInstallState by viewModel.linuxInstallState.collectAsState()
    val selectedTools by viewModel.selectedTools.collectAsState()
    val toolsInstallState by viewModel.toolsInstallState.collectAsState()
    val selectedProvider by viewModel.selectedProvider.collectAsState()
    val apiKey by viewModel.apiKey.collectAsState()
    val customApiUrl by viewModel.customApiUrl.collectAsState()
    val customModel by viewModel.customModel.collectAsState()
    val connectionTestState by viewModel.connectionTestState.collectAsState()
    val isSetupComplete by viewModel.isSetupComplete.collectAsState()

    // 完成后回调
    LaunchedEffect(isSetupComplete) {
        if (isSetupComplete) onComplete()
    }

    // 权限请求 Launchers
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refreshPermissionStates(context) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refreshPermissionStates(context) }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refreshPermissionStates(context) }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refreshPermissionStates(context) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.refreshPermissionStates(context) }

    val steps = listOf(
        "欢迎", "权限", "Linux", "工具", "AI", "完成"
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 顶部步骤指示器
            StepIndicator(
                currentStep = currentStep,
                totalSteps = steps.size,
                labels = steps
            )

            // 步骤内容
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { it } + fadeIn() togetherWith
                                slideOutHorizontally { -it } + fadeOut()
                    } else {
                        slideInHorizontally { -it } + fadeIn() togetherWith
                                slideOutHorizontally { it } + fadeOut()
                    }
                },
                label = "wizard_step"
            ) { step ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(12.dp))

                    when (step) {
                        0 -> WelcomeStep(
                            deviceInfo = deviceInfo,
                            onStart = { viewModel.nextStep() }
                        )
                        1 -> PermissionStep(
                            permissionStates = permissionStates,
                            onRequestStorage = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    viewModel.requestStoragePermission()?.let { storageLauncher.launch(it) }
                                }
                            },
                            onRequestNotification = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            onRequestBattery = {
                                batteryLauncher.launch(viewModel.requestBatteryOptimization(context))
                            },
                            onRequestOverlay = {
                                overlayLauncher.launch(viewModel.requestOverlayPermission())
                            },
                            onRequestLocation = {
                                locationLauncher.launch(arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                ))
                            }
                        )
                        2 -> LinuxStep(
                            selectedDistro = selectedDistro,
                            selectedMirror = selectedMirror,
                            installState = linuxInstallState,
                            onSelectDistro = { viewModel.selectDistro(it) },
                            onSelectMirror = { viewModel.selectMirror(it) },
                            onInstall = { viewModel.installLinux(context) },
                            onSkip = { viewModel.skipLinuxInstall() }
                        )
                        3 -> DevToolsStep(
                            selectedTools = selectedTools,
                            installState = toolsInstallState,
                            onToggleTool = { viewModel.toggleTool(it) },
                            onSelectAll = { viewModel.selectAllTools() },
                            onClearAll = { viewModel.clearAllTools() },
                            onInstall = { viewModel.installSelectedTools() },
                            estimatedSize = viewModel.estimateToolsSize()
                        )
                        4 -> AIConfigStep(
                            selectedProvider = selectedProvider,
                            apiKey = apiKey,
                            customApiUrl = customApiUrl,
                            customModel = customModel,
                            connectionState = connectionTestState,
                            providers = viewModel.aiProviders,
                            onSelectProvider = { viewModel.selectProvider(it) },
                            onApiKeyChange = { viewModel.setApiKey(it) },
                            onCustomUrlChange = { viewModel.setCustomApiUrl(it) },
                            onCustomModelChange = { viewModel.setCustomModel(it) },
                            onTestConnection = { viewModel.testApiConnection() }
                        )
                        5 -> FinishStep(
                            permissionStates = permissionStates,
                            linuxInstallState = linuxInstallState,
                            selectedTools = selectedTools,
                            selectedProvider = selectedProvider,
                            providers = viewModel.aiProviders
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                }
            }

            // 底部导航按钮
            BottomNavButtons(
                currentStep = currentStep,
                onPrev = { viewModel.prevStep() },
                onNext = { viewModel.nextStep() },
                onSkip = onSkip,
                onComplete = { viewModel.markSetupComplete(context) },
                canProceed = when (currentStep) {
                    0 -> true // 欢迎页总是可以继续
                    1 -> true // 权限可以跳过
                    2 -> true // Linux 可以跳过
                    3 -> true // 工具可以跳过
                    4 -> true // AI 可以跳过
                    else -> true
                }
            )
        }
    }
}

// ===== 步骤指示器 =====
@Composable
private fun StepIndicator(
    currentStep: Int,
    totalSteps: Int,
    labels: List<String>
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        labels.forEachIndexed { index, label ->
            // 圆点
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                index < currentStep -> Color(0xFF2ED573) // 已完成
                                index == currentStep -> UbuntuOrange // 当前步
                                else -> MaterialTheme.colorScheme.outline // 未到达
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (index < currentStep) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = Color.White
                        )
                    } else {
                        Text(
                            "${index + 1}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (index == currentStep) Color.White
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    fontSize = 10.sp,
                    color = if (index <= currentStep) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (index == currentStep) FontWeight.Bold else FontWeight.Normal
                )
            }

            // 连接线
            if (index < totalSteps - 1) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(2.dp)
                        .padding(horizontal = 4.dp)
                        .background(
                            if (index < currentStep) Color(0xFF2ED573)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            RoundedCornerShape(1.dp)
                        )
                )
            }
        }
    }
}

// ===== 第1步：欢迎 + 设备检测 =====
@Composable
private fun WelcomeStep(
    deviceInfo: SetupWizardViewModel.DeviceInfo,
    onStart: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(20.dp))

        // 品牌 Logo
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(UbuntuOrange, Color(0xFFE95420).copy(alpha = 0.8f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Terminal,
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = Color.White
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "Codex Android",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(4.dp))

        Text(
            "AI 驱动的移动端编程环境",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        // 设备信息卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = UbuntuOrange
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "设备信息",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Spacer(Modifier.height(12.dp))

                DeviceInfoRow("系统版本", deviceInfo.androidVersion)
                DeviceInfoRow("CPU 架构", deviceInfo.cpuArch)
                DeviceInfoRow("可用存储", deviceInfo.availableStorageGB)
                DeviceInfoRow(
                    "Root 状态",
                    if (deviceInfo.isRooted) "已 Root ✓" else "未 Root",
                    valueColor = if (deviceInfo.isRooted) Color(0xFF2ED573) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                DeviceInfoRow("设备型号", "${deviceInfo.manufacturer} ${deviceInfo.deviceModel}")
            }
        }

        Spacer(Modifier.height(16.dp))

        // 功能介绍
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = UbuntuOrange.copy(alpha = 0.08f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("🚀 接下来将引导你完成", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                FeaturePreviewRow("📱", "权限设置", "授予必要权限")
                FeaturePreviewRow("🐧", "Linux 环境", "一键安装内置 Linux")
                FeaturePreviewRow("🔧", "开发工具", "按需安装编程工具")
                FeaturePreviewRow("🤖", "AI 配置", "连接 AI 编程助手")
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = UbuntuOrange),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.RocketLaunch, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("开始设置", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DeviceInfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = valueColor)
    }
}

@Composable
private fun FeaturePreviewRow(icon: String, title: String, desc: String) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ===== 第2步：权限设置 =====
@Composable
private fun PermissionStep(
    permissionStates: SetupWizardViewModel.PermissionStates,
    onRequestStorage: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestBattery: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestLocation: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))

        Icon(
            Icons.Default.Security,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = UbuntuOrange
        )

        Spacer(Modifier.height(12.dp))

        Text(
            "权限设置",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            "授予必要权限以获得完整功能体验",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        // 权限列表
        PermissionCard(
            icon = Icons.Default.Folder,
            title = "存储权限",
            description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                "管理所有文件（Android 11+）" else "读写外部存储",
            granted = permissionStates.storage,
            isRequired = true,
            onRequest = onRequestStorage
        )

        Spacer(Modifier.height(8.dp))

        PermissionCard(
            icon = Icons.Default.Notifications,
            title = "通知权限",
            description = "显示运行状态和任务进度",
            granted = permissionStates.notification,
            isRequired = true,
            onRequest = onRequestNotification
        )

        Spacer(Modifier.height(8.dp))

        PermissionCard(
            icon = Icons.Default.BatteryChargingFull,
            title = "电池优化白名单",
            description = "防止后台运行时被系统杀死",
            granted = permissionStates.batteryOptimization,
            isRequired = true,
            onRequest = onRequestBattery
        )

        Spacer(Modifier.height(8.dp))

        PermissionCard(
            icon = Icons.Default.Layers,
            title = "悬浮窗权限",
            description = "在其他应用上层显示（可选）",
            granted = permissionStates.overlay,
            isRequired = false,
            onRequest = onRequestOverlay
        )

        Spacer(Modifier.height(8.dp))

        PermissionCard(
            icon = Icons.Default.LocationOn,
            title = "位置权限",
            description = "部分功能可能需要（可选）",
            granted = permissionStates.location,
            isRequired = false,
            onRequest = onRequestLocation
        )

        Spacer(Modifier.height(8.dp))

        // 授权进度
        val granted = listOf(
            permissionStates.storage,
            permissionStates.notification,
            permissionStates.batteryOptimization,
            permissionStates.overlay,
            permissionStates.location
        ).count { it }

        LinearProgressIndicator(
            progress = { granted / 5f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = Color(0xFF2ED573),
            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
        )

        Spacer(Modifier.height(4.dp))

        Text(
            "已授权 $granted/5 项权限",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "⚠ 未授予的权限不影响核心功能，但部分体验可能受限",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    isRequired: Boolean,
    onRequest: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted)
                Color(0xFF2ED573).copy(alpha = 0.08f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    if (!isRequired) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "可选",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (granted) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "已授权",
                    tint = Color(0xFF2ED573),
                    modifier = Modifier.size(22.dp)
                )
            } else {
                FilledTonalButton(
                    onClick = onRequest,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = UbuntuOrange.copy(alpha = 0.15f),
                        contentColor = UbuntuOrange
                    )
                ) {
                    Text("授权", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

// ===== 第3步：Linux 环境 =====
@Composable
private fun LinuxStep(
    selectedDistro: String,
    selectedMirror: String,
    installState: SetupWizardViewModel.LinuxInstallState,
    onSelectDistro: (String) -> Unit,
    onSelectMirror: (String) -> Unit,
    onInstall: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))

        Icon(
            Icons.Default.Terminal,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = UbuntuOrange
        )

        Spacer(Modifier.height(12.dp))

        Text(
            "安装 Linux 环境",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            "Codex 需要内置 Linux 环境运行程序",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        // 发行版选择
        Text(
            "选择发行版",
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(Modifier.height(8.dp))

        ProotEnvironment.SUPPORTED_DISTROS.forEach { distro ->
            DistroCard(
                distro = distro,
                isSelected = selectedDistro == distro.id,
                isInstalled = installState.isInstalled && selectedDistro == distro.id,
                onSelect = { onSelectDistro(distro.id) }
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(12.dp))

        // 镜像源选择
        Text(
            "下载镜像源",
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(Modifier.height(8.dp))

        // 镜像源横向选择
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ProotEnvironment.MIRROR_SOURCES.forEach { mirror ->
                FilterChip(
                    selected = selectedMirror == mirror.id,
                    onClick = { onSelectMirror(mirror.id) },
                    label = {
                        Text(mirror.displayName, fontSize = 11.sp)
                    },
                    modifier = Modifier.weight(1f),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = UbuntuOrange.copy(alpha = 0.15f),
                        selectedLabelColor = UbuntuOrange
                    )
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 安装进度 / 按钮
        if (installState.isInstalling) {
            // 安装进度
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        when (installState.phase) {
                            ProotEnvironment.InstallPhase.DOWNLOADING -> "📥 正在下载..."
                            ProotEnvironment.InstallPhase.EXTRACTING -> "📦 正在解压..."
                            ProotEnvironment.InstallPhase.CONFIGURING -> "⚙ 正在配置..."
                            ProotEnvironment.InstallPhase.INSTALLING_TOOLS -> "🔧 安装工具..."
                            else -> installState.message
                        },
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { installState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = UbuntuOrange,
                        trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        installState.message,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (installState.isInstalled) {
            // 已安装
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF2ED573).copy(alpha = 0.08f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF2ED573),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Linux 环境已就绪!",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color(0xFF2ED573)
                    )
                }
            }
        } else {
            // 安装按钮
            Button(
                onClick = onInstall,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = UbuntuOrange),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("一键安装 Linux 环境", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(8.dp))

            TextButton(onClick = onSkip) {
                Text(
                    "跳过安装，稍后再说",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
        }

        // 错误提示
        if (installState.error != null) {
            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Error, null, tint = Color(0xFFC62828), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(installState.error!!, fontSize = 13.sp, color = Color(0xFFC62828))
                }
            }
        }
    }
}

@Composable
private fun DistroCard(
    distro: ProotEnvironment.DistroInfo,
    isSelected: Boolean,
    isInstalled: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                UbuntuOrange.copy(alpha = 0.1f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp),
        border = if (isSelected) CardDefaults.outlinedCardBorder() else null
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 单选按钮
            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(selectedColor = UbuntuOrange)
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(distro.displayName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (distro.id == "ubuntu") {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "推荐",
                            fontSize = 10.sp,
                            color = UbuntuOrange,
                            modifier = Modifier
                                .background(UbuntuOrange.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(distro.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(distro.estimatedSize, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isInstalled) {
                    Text("已安装 ✓", fontSize = 10.sp, color = Color(0xFF2ED573))
                }
            }
        }
    }
}

// ===== 第4步：开发工具 =====
@Composable
private fun DevToolsStep(
    selectedTools: Set<String>,
    installState: SetupWizardViewModel.ToolsInstallState,
    onToggleTool: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onInstall: () -> Unit,
    estimatedSize: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))

        Icon(
            Icons.Default.Build,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = UbuntuOrange
        )

        Spacer(Modifier.height(12.dp))

        Text(
            "开发工具",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            "选择需要安装的编程工具和运行时",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(12.dp))

        // 全选/取消 + 已选计数
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "已选 ${selectedTools.size} 项 · 预计 ${estimatedSize}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row {
                TextButton(onClick = onSelectAll, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("全选", fontSize = 12.sp, color = UbuntuOrange)
                }
                TextButton(onClick = onClearAll, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("取消全选", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 分类工具列表
        ProotEnvironment.TOOL_CATEGORIES.forEach { category ->
            // 分类标题
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(category.icon, fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    category.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = UbuntuOrange
                )
            }

            // 工具项
            category.tools.forEach { tool ->
                ToolItem(
                    tool = tool,
                    isSelected = tool.packageName in selectedTools,
                    onToggle = { onToggleTool(tool.packageName) },
                    isEnabled = !installState.isInstalling
                )
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(4.dp))
        }

        Spacer(Modifier.height(8.dp))

        // 安装按钮 / 进度
        if (installState.isInstalling) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(installState.message, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { installState.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = UbuntuOrange,
                        trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                }
            }
        } else if (installState.isCompleted) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF2ED573).copy(alpha = 0.08f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF2ED573), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("工具安装完成!", fontWeight = FontWeight.Bold, color = Color(0xFF2ED573))
                }
            }
        } else {
            Button(
                onClick = onInstall,
                enabled = selectedTools.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = UbuntuOrange,
                    disabledContainerColor = UbuntuOrange.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (selectedTools.isEmpty()) "请选择工具" else "安装选中工具 (${selectedTools.size})",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (installState.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    installState.error!!,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ToolItem(
    tool: ProotEnvironment.ToolInfo,
    isSelected: Boolean,
    onToggle: () -> Unit,
    isEnabled: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = isEnabled,
                onClick = onToggle,
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                UbuntuOrange.copy(alpha = 0.08f)
            else
                MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                enabled = isEnabled,
                colors = CheckboxDefaults.colors(checkedColor = UbuntuOrange)
            )
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(tool.displayName, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                Text(tool.description, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                tool.estimatedSize,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ===== 第5步：AI 配置 =====
@Composable
private fun AIConfigStep(
    selectedProvider: String,
    apiKey: String,
    customApiUrl: String,
    customModel: String,
    connectionState: SetupWizardViewModel.ConnectionTestState,
    providers: List<SetupWizardViewModel.AIProvider>,
    onSelectProvider: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onCustomUrlChange: (String) -> Unit,
    onCustomModelChange: (String) -> Unit,
    onTestConnection: () -> Unit
) {
    var showApiKey by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(8.dp))

        Icon(
            Icons.Default.SmartToy,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = UbuntuOrange
        )

        Spacer(Modifier.height(12.dp))

        Text(
            "AI 配置",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            "选择 AI 提供商并配置 API Key",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        // 提供商选择
        Text(
            "选择提供商",
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.align(Alignment.Start)
        )

        Spacer(Modifier.height(8.dp))

        providers.forEach { provider ->
            ProviderCard(
                provider = provider,
                isSelected = selectedProvider == provider.id,
                onSelect = { onSelectProvider(provider.id) }
            )
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(12.dp))

        // 自定义配置（仅自定义模式）
        if (selectedProvider == "custom") {
            OutlinedTextField(
                value = customApiUrl,
                onValueChange = onCustomUrlChange,
                label = { Text("API 地址") },
                placeholder = { Text("https://api.example.com/v1") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp)
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = customModel,
                onValueChange = onCustomModelChange,
                label = { Text("模型名称") },
                placeholder = { Text("gpt-4o") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(10.dp)
            )

            Spacer(Modifier.height(8.dp))
        }

        // API Key 输入
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text("API Key") },
            placeholder = { Text("sk-...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (showApiKey) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showApiKey = !showApiKey }) {
                    Icon(
                        if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showApiKey) "隐藏" else "显示",
                        modifier = Modifier.size(20.dp)
                    )
                }
            },
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(Modifier.height(12.dp))

        // 测试连接按钮
        OutlinedButton(
            onClick = onTestConnection,
            enabled = apiKey.isNotBlank() && connectionState.state != SetupWizardViewModel.ConnectionState.TESTING,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        ) {
            if (connectionState.state == SetupWizardViewModel.ConnectionState.TESTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = UbuntuOrange
                )
                Spacer(Modifier.width(8.dp))
                Text("正在测试连接...")
            } else {
                Icon(Icons.Default.WifiTethering, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("测试连接")
            }
        }

        // 连接状态
        if (connectionState.state != SetupWizardViewModel.ConnectionState.IDLE) {
            Spacer(Modifier.height(8.dp))
            val bgColor = when (connectionState.state) {
                SetupWizardViewModel.ConnectionState.SUCCESS -> Color(0xFF2ED573).copy(alpha = 0.08f)
                SetupWizardViewModel.ConnectionState.FAILED -> Color(0xFFFF4757).copy(alpha = 0.08f)
                else -> UbuntuOrange.copy(alpha = 0.08f)
            }
            val iconColor = when (connectionState.state) {
                SetupWizardViewModel.ConnectionState.SUCCESS -> Color(0xFF2ED573)
                SetupWizardViewModel.ConnectionState.FAILED -> Color(0xFFFF4757)
                else -> UbuntuOrange
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = bgColor),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        when (connectionState.state) {
                            SetupWizardViewModel.ConnectionState.SUCCESS -> Icons.Default.CheckCircle
                            SetupWizardViewModel.ConnectionState.FAILED -> Icons.Default.Error
                            else -> Icons.Default.Info
                        },
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(connectionState.message, fontSize = 12.sp, color = iconColor)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "💡 可稍后在「设置」页面修改 AI 配置",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ProviderCard(
    provider: SetupWizardViewModel.AIProvider,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                UbuntuOrange.copy(alpha = 0.1f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onSelect,
                colors = RadioButtonDefaults.colors(selectedColor = UbuntuOrange)
            )
            Spacer(Modifier.width(4.dp))
            Text(provider.displayName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            if (provider.id != "custom") {
                Text(
                    provider.defaultModel,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ===== 第6步：完成 =====
@Composable
private fun FinishStep(
    permissionStates: SetupWizardViewModel.PermissionStates,
    linuxInstallState: SetupWizardViewModel.LinuxInstallState,
    selectedTools: Set<String>,
    selectedProvider: String,
    providers: List<SetupWizardViewModel.AIProvider>
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))

        // 完成图标
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(Color(0xFF2ED573).copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = Color(0xFF2ED573)
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "设置完成!",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "一切准备就绪，开始你的 AI 编程之旅",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(20.dp))

        // 环境总览
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "📋 环境总览",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(12.dp))

                // 权限
                val grantedCount = listOf(
                    permissionStates.storage,
                    permissionStates.notification,
                    permissionStates.batteryOptimization,
                    permissionStates.overlay,
                    permissionStates.location
                ).count { it }

                SummaryRow(
                    icon = Icons.Default.Security,
                    label = "权限",
                    value = "$grantedCount/5 已授权",
                    isDone = grantedCount >= 3
                )

                Spacer(Modifier.height(8.dp))

                // Linux
                SummaryRow(
                    icon = Icons.Default.Terminal,
                    label = "Linux 环境",
                    value = if (linuxInstallState.isInstalled) "已安装 ✓" else if (linuxInstallState.hasSkipped) "已跳过" else "未安装",
                    isDone = linuxInstallState.isInstalled
                )

                Spacer(Modifier.height(8.dp))

                // 工具
                SummaryRow(
                    icon = Icons.Default.Build,
                    label = "开发工具",
                    value = "${selectedTools.size} 项已选",
                    isDone = selectedTools.isNotEmpty()
                )

                Spacer(Modifier.height(8.dp))

                // AI
                val providerName = providers.find { it.id == selectedProvider }?.displayName ?: "未配置"
                SummaryRow(
                    icon = Icons.Default.SmartToy,
                    label = "AI 提供商",
                    value = providerName,
                    isDone = selectedProvider.isNotEmpty()
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 提示
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = UbuntuOrange.copy(alpha = 0.06f)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Info, null, tint = UbuntuOrange, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "可随时在「设置」或「环境」页面修改以上配置",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(
    icon: ImageVector,
    label: String,
    value: String,
    isDone: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (isDone) Color(0xFF2ED573) else Color(0xFFFFA502)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (isDone) Color(0xFF2ED573) else Color(0xFFFFA502)
        )
    }
}

// ===== 底部导航按钮 =====
@Composable
private fun BottomNavButtons(
    currentStep: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onComplete: () -> Unit,
    canProceed: Boolean
) {
    Surface(
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧按钮
            if (currentStep > 0) {
                OutlinedButton(
                    onClick = onPrev,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.ArrowBack, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("上一步")
                }
            } else {
                TextButton(onClick = onSkip) {
                    Text("跳过设置", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
            }

            // 右侧按钮
            if (currentStep < 5) {
                Button(
                    onClick = onNext,
                    enabled = canProceed,
                    colors = ButtonDefaults.buttonColors(containerColor = UbuntuOrange),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("下一步")
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(16.dp))
                }
            } else {
                Button(
                    onClick = onComplete,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2ED573)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("开始使用", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}
