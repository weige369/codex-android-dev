package com.codex.android.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.codex.android.codex.CodexManager
import com.codex.android.service.CodexRuntimeService
import com.codex.android.ui.theme.*
import kotlinx.coroutines.launch

/**
 * Codex settings screen.
 * Extended with navigation to Skills, MCP, GitHub, Diagnostics, and About.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodexSettingsScreen(
    onBack: () -> Unit = {},
    onOpenSkills: (() -> Unit)? = null,
    onOpenMCP: (() -> Unit)? = null,
    onOpenGitHub: (() -> Unit)? = null,
    onOpenDiagnostic: (() -> Unit)? = null,
    onOpenAbout: (() -> Unit)? = null,
    onOpenApiProvider: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val codexManager = remember { CodexManager(context) }

    // Connection settings
    var connMode by remember { mutableStateOf(
        context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
            .getString("conn_mode", "local")
    ) }
    var apiKey by remember { mutableStateOf(
        context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
            .getString("api_key", "") ?: ""
    ) }
    var apiUrl by remember { mutableStateOf(
        context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
            .getString("api_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
    ) }
    var apiModel by remember { mutableStateOf(
        context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
            .getString("api_model", "gpt-4o") ?: "gpt-4o"
    ) }
    var showApiKey by remember { mutableStateOf(false) }

    // Security level
    var securityLevel by remember { mutableStateOf(
        context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
            .getString("security_level", "standard") ?: "standard"
    ) }

    // Binary status
    val isInstalled = codexManager.isInstalled()
    val binarySize = if (isInstalled) {
        "%.1f MB".format(codexManager.codexBinary.length() / (1024.0 * 1024.0))
    } else "未安装"

    Scaffold(
        containerColor = CodexBackground,
        topBar = {
            TopAppBar(
                title = { Text("设置", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CodexOnSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = CodexOnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CodexBackground
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // ===== Connection Mode =====
            item {
                CodexSectionHeader("连接方式", Icons.Default.Link)
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = CodexSurfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        ConnModeOption(
                            title = "本地 Codex CLI (推荐)",
                            subtitle = "下载并运行原生 Codex CLI 二进制",
                            icon = Icons.Default.Terminal,
                            selected = connMode == "local",
                            onClick = { connMode = "local"; saveConnMode(context, "local") }
                        )
                        Spacer(Modifier.height(4.dp))
                        ConnModeOption(
                            title = "OpenAI 兼容 API",
                            subtitle = "通过 API Key 连接云端",
                            icon = Icons.Default.Cloud,
                            selected = connMode == "api",
                            onClick = { connMode = "api"; saveConnMode(context, "api") }
                        )
                        Spacer(Modifier.height(4.dp))
                        ConnModeOption(
                            title = "自定义 WebSocket",
                            subtitle = "手动连接 Codex exec-server",
                            icon = Icons.Default.Link,
                            selected = connMode == "ws",
                            onClick = { connMode = "ws"; saveConnMode(context, "ws") }
                        )
                    }
                }
            }

            item {
                HorizontalDivider(color = CodexOutline, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
            }

            // ===== API Settings (when API mode) =====
            if (connMode == "api") {
                item {
                    CodexSectionHeader("API 配置", Icons.Default.Api)
                }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = CodexSurfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            OutlinedTextField(
                                value = apiUrl,
                                onValueChange = { apiUrl = it; savePref(context, "api_url", it) },
                                label = { Text("API URL") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = apiModel,
                                onValueChange = { apiModel = it; savePref(context, "api_model", it) },
                                label = { Text("模型") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it; savePref(context, "api_key", it) },
                                label = { Text("API Key") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showApiKey = !showApiKey }) {
                                        Icon(
                                            if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            if (showApiKey) "隐藏" else "显示"
                                        )
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                            )
                        }
                    }
                }
            }

            item {
                HorizontalDivider(color = CodexOutline, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
            }

            // ===== Security Level =====
            item {
                CodexSectionHeader("安全等级", Icons.Default.Shield)
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = CodexSurfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "控制 AI 工具的权限范围（Shell 执行、文件读写）。",
                            fontSize = 12.sp,
                            color = CodexOnSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
                        )
                        ConnModeOption(
                            title = "安全",
                            subtitle = "禁用 Shell 工具，文件读写仅限工作目录",
                            icon = Icons.Default.Shield,
                            selected = securityLevel == "safe",
                            onClick = { securityLevel = "safe"; savePref(context, "security_level", "safe") }
                        )
                        ConnModeOption(
                            title = "标准 (推荐)",
                            subtitle = "禁用 Shell 工具，可读写全盘文件",
                            icon = Icons.Default.Security,
                            selected = securityLevel == "standard",
                            onClick = { securityLevel = "standard"; savePref(context, "security_level", "standard") }
                        )
                        ConnModeOption(
                            title = "完全",
                            subtitle = "无限制：可执行任意 Shell + 全盘文件访问",
                            icon = Icons.Default.LockOpen,
                            selected = securityLevel == "full",
                            onClick = { securityLevel = "full"; savePref(context, "security_level", "full") }
                        )
                    }
                }
            }

            item {
                HorizontalDivider(color = CodexOutline, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
            }

            // ===== Codex Binary =====
            item {
                CodexSectionHeader("Codex CLI 二进制", Icons.Default.Terminal)
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = CodexSurfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("状态", fontSize = 14.sp, color = CodexOnSurfaceVariant)
                                Text(
                                    if (isInstalled) "已安装 ($binarySize)" else "未安装",
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    color = CodexOnSurface
                                )
                            }
                            if (!isInstalled) {
                                Button(
                                    onClick = { CodexRuntimeService.start(context) },
                                    colors = ButtonDefaults.buttonColors(containerColor = CodexBrandOrange)
                                ) {
                                    Text("下载")
                                }
                            }
                        }
                        if (isInstalled) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { 1f },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = CodexBrandOrange,
                                trackColor = CodexBrandOrange.copy(alpha = 0.1f)
                            )
                        }
                    }
                }
            }

            // ===== 版本检查与升级 =====
            if (isInstalled) {
                item {
                    CodexSectionHeader("版本与升级", Icons.Default.SystemUpdate)
                }
                item {
                    CodexUpdateCard(codexManager = codexManager, context = context)
                }
            }

            // ===== Feature Navigation =====
            // ===== 手动导入二进制 =====
            item {
                CodexSectionHeader("手动导入", Icons.Default.Upload)
            }
            item {
                ManualImportCard(
                    codexManager = codexManager,
                    context = context
                )
            }

            item {
                CodexSectionHeader("功能", Icons.Default.Extension)
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = CodexSurfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        if (onOpenApiProvider != null) {
                            SettingsNavItem(
                                icon = Icons.Default.SmartToy,
                                title = "AI 提供商",
                                subtitle = "配置 DeepSeek / OpenAI / SiliconFlow 等",
                                onClick = onOpenApiProvider
                            )
                        }
                        if (onOpenSkills != null) {
                            SettingsNavItem(
                                Icons.Default.Extension,
                                "Skills 管理",
                                "安装和管理 Codex 插件",
                                onClick = onOpenSkills
                            )
                        }
                        if (onOpenMCP != null) {
                            SettingsNavItem(
                                Icons.Default.Memory,
                                "MCP 服务器",
                                "Android 系统工具集成",
                                onClick = onOpenMCP
                            )
                        }
                        if (onOpenGitHub != null) {
                            SettingsNavItem(
                                Icons.Default.Code,
                                "GitHub 导入",
                                "从 GitHub 导入仓库",
                                onClick = onOpenGitHub
                            )
                        }
                        SettingsNavItem(
                            Icons.Default.BugReport,
                            "诊断检查",
                            "运行设备诊断测试",
                            onClick = { onOpenDiagnostic?.invoke() }
                        )
                        SettingsNavItem(
                            Icons.Default.Info,
                            "关于",
                            "版本和系统信息",
                            onClick = { onOpenAbout?.invoke() }
                        )
                    }
                }
            }

            // ===== Info =====
            item {
                CodexSectionHeader("关于", Icons.Default.Info)
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = CodexSurfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        SettingsRow("Codex CLI 版本", CodexManager.CODEX_VERSION)
                        SettingsRow("Agent 引擎", "Codex CLI (OpenAI)")
                        SettingsRow("集成方式", "WebSocket JSON-RPC")
                        SettingsRow("应用版本", "1.11.0+7")
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun CodexSectionHeader(title: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = CodexBrandOrange,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = CodexBrandOrange
        )
    }
}

@Composable
private fun ConnModeOption(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) CodexBrandOrange
                   else CodexOnSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) CodexBrandOrange
                        else CodexOnSurface
            )
            Text(
                subtitle,
                fontSize = 12.sp,
                color = CodexOnSurfaceVariant
            )
        }
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = CodexBrandOrange,
                unselectedColor = CodexOnSurfaceVariant
            )
        )
    }
}

@Composable
private fun SettingsNavItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = CodexBrandOrange, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = CodexOnSurface)
                Text(subtitle, fontSize = 11.sp, color = CodexOnSurfaceVariant)
            }
            Icon(
                Icons.Default.ChevronRight,
                null,
                tint = CodexOnSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun CodexUpdateCard(
    codexManager: CodexManager,
    context: Context
) {
    val scope = rememberCoroutineScope()
    var installedVersion by remember { mutableStateOf(codexManager.getInstalledVersion()) }
    var checking by remember { mutableStateOf(false) }
    var latestVersion by remember { mutableStateOf<String?>(null) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var upgrading by remember { mutableStateOf(false) }
    var progressPct by remember { mutableStateOf(0) }

    val updateAvailable = latestVersion?.let { codexManager.isUpdateAvailable(it) } == true

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = CodexSurfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("已安装版本", fontSize = 13.sp, color = CodexOnSurfaceVariant)
                    Text(
                        installedVersion?.let { "v$it" } ?: "未知（手动导入）",
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = CodexOnSurface
                    )
                }
                if (!upgrading) {
                    OutlinedButton(
                        onClick = {
                            checking = true
                            statusMsg = "正在检查..."
                            scope.launch {
                                val latest = codexManager.checkLatestVersion()
                                checking = false
                                if (latest == null) {
                                    statusMsg = "无法检查更新（网络不可用或被限制）"
                                } else {
                                    latestVersion = latest
                                    statusMsg = if (codexManager.isUpdateAvailable(latest))
                                        "发现新版本 v$latest" else "已是最新（v$latest）"
                                }
                            }
                        },
                        enabled = !checking
                    ) {
                        Text(if (checking) "检查中..." else "检查更新")
                    }
                }
            }

            statusMsg?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 13.sp, color = CodexOnSurfaceVariant)
            }

            if (updateAvailable && !upgrading) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val target = latestVersion ?: return@Button
                        upgrading = true
                        progressPct = 0
                        statusMsg = "正在升级到 v$target..."
                        scope.launch {
                            val ok = codexManager.upgradeTo(target) { progress, total ->
                                progressPct = if (total > 0) (progress * 100 / total).toInt() else 0
                            }
                            upgrading = false
                            if (ok) {
                                statusMsg = "升级成功，已安装 v$target"
                                installedVersion = codexManager.getInstalledVersion()
                                latestVersion = null
                                Toast.makeText(context, "Codex 已升级到 v$target", Toast.LENGTH_LONG).show()
                            } else {
                                statusMsg = "升级失败，原版本保持不变"
                                Toast.makeText(context, "升级失败，请检查网络后重试", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = CodexBrandOrange)
                ) {
                    Text("升级到 v${latestVersion}")
                }
            }

            if (upgrading) {
                Spacer(Modifier.height(12.dp))
                Text("升级中... $progressPct%", fontSize = 13.sp, color = CodexOnSurface)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progressPct / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = CodexBrandOrange
                )
            }
        }
    }
}

@Composable
private fun ManualImportCard(
    codexManager: CodexManager,
    context: Context
) {
    var isImporting by remember { mutableStateOf(false) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var importOk by remember { mutableStateOf(false) }
    var helpExpanded by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            isImporting = true
            importStatus = "正在导入..."
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val tempFile = java.io.File(context.cacheDir, "codex-import")
                inputStream?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val result = codexManager.importBinaryChecked(tempFile)
                tempFile.delete()
                isImporting = false
                importOk = result.success
                importStatus = result.message
                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                isImporting = false
                importOk = false
                importStatus = "导入异常: ${e.message}"
                Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = CodexSurfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "如果自动下载失败，可以手动导入 Codex 二进制文件",
                fontSize = 13.sp,
                color = CodexOnSurfaceVariant
            )

            // 折叠的导入说明
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { helpExpanded = !helpExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.HelpOutline,
                    null,
                    tint = CodexBrandOrange,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "导入说明（文件格式 / 路径 / 校验）",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = CodexOnSurface
                )
                Icon(
                    if (helpExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null,
                    tint = CodexOnSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            if (helpExpanded) {
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        appendLine("• 文件类型：aarch64 Linux 的 codex 可执行文件（ELF 格式）")
                        appendLine("• 不要直接选 .tar.gz 压缩包，请先解压取出其中的 codex 文件")
                        appendLine("• 文件大小通常 > 10MB；过小说明下载不完整")
                        appendLine("• 导入会校验 ELF 文件头，失败时会显示具体原因")
                        append("• 导入后将存放到：filesDir/codex/codex 并自动赋予可执行权限")
                    },
                    fontSize = 12.sp,
                    color = CodexOnSurfaceVariant,
                    lineHeight = 18.sp
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { importLauncher.launch("*/*") },
                    modifier = Modifier.weight(1f),
                    enabled = !isImporting
                ) {
                    Icon(Icons.Default.Upload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("选择文件")
                }

                Button(
                    onClick = {
                        isImporting = true
                        importStatus = "下载中..."
                        CodexRuntimeService.start(context)
                        isImporting = false
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isImporting,
                    colors = ButtonDefaults.buttonColors(containerColor = CodexBrandOrange)
                ) {
                    Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("重试下载")
                }
            }

            if (importStatus != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    importStatus!!,
                    fontSize = 12.sp,
                    color = when {
                        isImporting -> CodexOnSurfaceVariant
                        importOk -> StatusOnline
                        else -> MaterialTheme.colorScheme.error
                    },
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = CodexOnSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = CodexBrandOrange)
    }
}

private fun saveConnMode(context: Context, mode: String) {
    context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
        .edit().putString("conn_mode", mode).apply()
}

private fun savePref(context: Context, key: String, value: String) {
    context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
        .edit().putString(key, value).apply()
}
