package com.codex.android.agent

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

/**
 * 工具权限请求弹窗。
 *
 * 移植自 Operit ToolPermissionDialog + PermissionRequestOverlay，
 * 改为标准 Compose Dialog（Operit 用系统悬浮窗，我们用 Dialog 更安全）。
 *
 * 三态决策：
 * - ✅ 允许 (ALLOW) — 本次允许，下次还会问
 * - 🔁 始终允许 (ALWAYS_ALLOW) — 允许并记住，以后不再问
 * - ❌ 拒绝 (FORBID) — 本次拒绝
 *
 * 用法：
 * ```kotlin
 * val permissionManager = ToolPermissionManager.getInstance(context)
 * val pendingRequest by remember { mutableStateOf(permissionManager.getPendingRequest()) }
 *
 * pendingRequest?.let { request ->
 *     ToolPermissionDialog(
 *         request = request,
 *         onAllow = { /* scope.launch { permissionManager.respondToPermissionRequest(PermissionDecision.ALLOW) } */ },
 *         onAlwaysAllow = { /* scope.launch { permissionManager.respondToPermissionRequest(PermissionDecision.ALLOW, alwaysApply = true) } */ },
 *         onDeny = { /* scope.launch { permissionManager.respondToPermissionRequest(PermissionDecision.FORBID) } */ }
 *     )
 * }
 * ```
 */
@Composable
fun ToolPermissionDialog(
    request: PermissionRequest,
    onAllow: () -> Unit,
    onAlwaysAllow: () -> Unit,
    onDeny: () -> Unit
) {
    Dialog(onDismissRequest = onDeny) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 权限级别图标
                PermissionLevelIcon(request.permissionLevel)

                Spacer(modifier = Modifier.height(16.dp))

                // 标题
                Text(
                    text = "工具权限请求",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 工具信息
                InfoRow("工具", request.toolName)
                InfoRow("设备", request.devicePath)
                InfoRow("权限级别", request.permissionLevel.label)
                if (request.description.isNotBlank()) {
                    InfoRow("说明", request.description)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 权限级别警告
                PermissionWarning(request.permissionLevel)

                Spacer(modifier = Modifier.height(24.dp))

                // 按钮区
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 拒绝按钮
                    OutlinedButton(
                        onClick = onDeny,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("拒绝")
                    }

                    // 允许按钮
                    Button(
                        onClick = onAllow,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("允许")
                    }
                }

                // 始终允许按钮（仅 SAFE 和 MODERATE 级别显示）
                if (request.permissionLevel == PermissionLevel.SAFE ||
                    request.permissionLevel == PermissionLevel.MODERATE) {
                    Spacer(modifier = Modifier.height(8.dp))

                    TextButton(
                        onClick = onAlwaysAllow,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("始终允许此工具（不再询问）", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionLevelIcon(level: PermissionLevel) {
    val iconText = when (level) {
        PermissionLevel.SAFE -> "🟢"
        PermissionLevel.MODERATE -> "🟡"
        PermissionLevel.ELEVATED -> "🟠"
        PermissionLevel.PRIVILEGED -> "🔴"
    }
    Text(
        text = iconText,
        fontSize = 40.sp
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PermissionWarning(level: PermissionLevel) {
    val (text, color) = when (level) {
        PermissionLevel.SAFE -> "此操作安全，仅读取数据" to MaterialTheme.colorScheme.primary
        PermissionLevel.MODERATE -> "此操作可能修改文件或执行命令" to MaterialTheme.colorScheme.tertiary
        PermissionLevel.ELEVATED -> "⚠️ 此操作有较高风险，可能影响系统" to MaterialTheme.colorScheme.error
        PermissionLevel.PRIVILEGED -> "🚨 此操作需要特权，请谨慎授权" to MaterialTheme.colorScheme.error
    }

    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(300),
        label = "warning_color"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(animatedColor.copy(alpha = 0.12f))
            .padding(12.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = animatedColor,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * 工具权限设置页面。
 * 展示所有工具的权限级别，允许用户修改。
 */
@Composable
fun ToolPermissionSettingsScreen(
    permissionManager: ToolPermissionManager,
    modifier: Modifier = Modifier
) {
    var masterSwitch by remember { mutableStateOf(PermissionDecision.ASK) }
    val scope = rememberCoroutineScope()

    // 收集主控开关状态
    LaunchedEffect(Unit) {
        permissionManager.masterSwitchFlow.collect { masterSwitch = it }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "工具权限设置",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 主控开关
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "默认权限策略",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                PermissionDecisionRow(
                    label = "自动允许所有工具",
                    selected = masterSwitch == PermissionDecision.ALLOW,
                    onClick = {
                        masterSwitch = PermissionDecision.ALLOW
                        scope.launch { permissionManager.setMasterSwitch(PermissionDecision.ALLOW) }
                    }
                )
                PermissionDecisionRow(
                    label = "每次询问",
                    selected = masterSwitch == PermissionDecision.ASK,
                    onClick = {
                        masterSwitch = PermissionDecision.ASK
                        scope.launch { permissionManager.setMasterSwitch(PermissionDecision.ASK) }
                    }
                )
                PermissionDecisionRow(
                    label = "禁止所有工具",
                    selected = masterSwitch == PermissionDecision.FORBID,
                    onClick = {
                        masterSwitch = PermissionDecision.FORBID
                        scope.launch { permissionManager.setMasterSwitch(PermissionDecision.FORBID) }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 各工具权限设置
        Text(
            text = "工具级别权限（覆盖默认策略）",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        // TODO: 列出所有已挂载工具的权限设置
        // 需要从 CapabilityRegistry 获取工具列表
    }
}

@Composable
private fun PermissionDecisionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
    }
}
