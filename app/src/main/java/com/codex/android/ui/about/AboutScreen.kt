package com.codex.android.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.codex.android.ui.theme.CodexBrandOrange
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.codex.CodexManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    var showLicenses by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于", fontSize = 18.sp) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            // Logo & Info
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = CodexBrandOrange,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("C", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Codex Android", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("v${CodexManager.CODEX_VERSION}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "基于 OpenAI Codex CLI 的安卓原生编码代理",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // System Info
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("系统信息", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    InfoRow("设备", "${Build.MANUFACTURER} ${Build.MODEL}")
                    InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    InfoRow("架构", Build.SUPPORTED_ABIS.joinToString(", "))
                    InfoRow("Codex CLI", CodexManager.CODEX_VERSION)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Links
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    AboutItem(Icons.Default.Code, "GitHub 仓库") {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/weige369/codex-android")))
                    }
                    AboutItem(Icons.Default.Description, "开源许可", onClick = { showLicenses = true })
                    AboutItem(Icons.Default.Lock, "隐私政策", onClick = { showPrivacy = true })
                    AboutItem(Icons.Default.BugReport, "报告问题") {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/weige369/codex-android/issues")))
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        AboutItem(Icons.Default.BatteryFull, "忽略电池优化") {
                            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Codex Android • 基于 Operit 改造 • 取长补短\n让 Codex 在 Android 上原生运行",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
            )
        }
    }

    // License dialog
    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("开源许可") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "本应用使用了以下开源组件：\n\n" +
                        "- OpenAI Codex CLI (Apache 2.0)\n" +
                        "- Operit AI (自定义许可)\n" +
                        "- Jetpack Compose (Apache 2.0)\n" +
                        "- OkHttp (Apache 2.0)\n" +
                        "- Kotlinx Serialization (Apache 2.0)\n" +
                        "- Coroutines (Apache 2.0)\n" +
                        "- Ktor (Apache 2.0)\n" +
                        "- AndroidX 全家桶 (Apache 2.0)\n" +
                        "- Material3 (Apache 2.0)\n\n" +
                        "完整许可信息请查看 GitHub 仓库。",
                        fontSize = 12.sp,
                        lineHeight = 20.sp
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("关闭") } }
        )
    }

    // Privacy dialog
    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("隐私政策") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Codex Android 重视您的隐私：\n\n" +
                        "1. 本应用不会收集任何个人信息\n" +
                        "2. 所有 AI 请求直接发送到 OpenAI API（由您配置）\n" +
                        "3. 代码和文件仅存储在设备本地\n" +
                        "4. GitHub OAuth 仅在您主动使用时发起\n" +
                        "5. 我们不追踪用户行为\n\n" +
                        "您的数据完全由您掌控。",
                        fontSize = 12.sp,
                        lineHeight = 20.sp
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text("关闭") } }
        )
    }
}

@Composable private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable private fun AboutItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Default.KeyboardArrowRight, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

