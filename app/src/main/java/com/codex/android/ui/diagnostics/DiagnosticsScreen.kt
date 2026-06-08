package com.codex.android.ui.diagnostics

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.diagnostics.CrashHandler
import com.codex.android.diagnostics.DiagnosticPrefs
import com.codex.android.diagnostics.DiagnosticsRunner
import com.codex.android.diagnostics.ReportUploader
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val diagnostics = remember { DiagnosticsRunner(context) }
    val uploader = remember { ReportUploader(context) }
    val prefs = remember { DiagnosticPrefs(context) }
    val crashHandler = remember { CrashHandler(context) }

    var report by remember { mutableStateOf<DiagnosticsRunner.DiagnosticsReport?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var expandedItem by remember { mutableStateOf<String?>(null) }

    // GitHub 上传状态
    var showGitHubConfig by remember { mutableStateOf(false) }
    var githubToken by remember { mutableStateOf(prefs.githubToken) }
    var repoOwner by remember { mutableStateOf(prefs.repoOwner) }
    var repoName by remember { mutableStateOf(prefs.repoName) }
    var showToken by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }
    var uploadResult by remember { mutableStateOf<String?>(null) }

    // 崩溃日志
    var crashLogs by remember { mutableStateOf<List<File>>(emptyList()) }
    var showCrashLogs by remember { mutableStateOf(false) }

    // 日志导出
    var lastExportPath by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isRunning = true
        report = diagnostics.runAll()
        crashLogs = crashHandler.getCrashLogs()
        isRunning = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("APP 诊断", fontSize = 18.sp) },
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
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // ===== 概览 =====
            item {
                Card(modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isRunning) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp)); Text("正在诊断...")
                            } else if (report != null) {
                                val allPassed = report?.isAllPassed ?: false
                                val icon = if (allPassed) Icons.Default.CheckCircle else Icons.Default.Warning
                                val color = if (allPassed) Color(0xFF2ED573) else Color(0xFFFFA502)
                                Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(if (allPassed) "全部通过" else "发现问题",
                                        fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                    Text("${report?.passedCount ?: 0}/${report?.totalCount ?: 0} 通过 · ${report?.failedCount ?: 0} 失败",
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (report != null && !isRunning) {
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    scope.launch { isRunning = true; report = diagnostics.runAll(); crashLogs = crashHandler.getCrashLogs(); isRunning = false }
                                }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp)); Text("刷新", fontSize = 13.sp)
                                }
                                OutlinedButton(onClick = {
                                    val text = buildReportText(report)
                                    val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clip.setPrimaryClip(android.content.ClipData.newPlainText("诊断报告", text))
                                    Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                                }, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp)); Text("复制", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }

            // ===== 设备信息 =====
            if (report != null) {
                item {
                    Text("设备信息", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                }
                item {
                    Card(modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), Arrangement.SpaceBetween) {
                                Text("Android", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(report?.deviceInfo?.androidVersion ?: "未知", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), Arrangement.SpaceBetween) {
                                Text("设备", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(report?.deviceInfo?.device ?: "未知", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), Arrangement.SpaceBetween) {
                                Text("架构", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(report?.deviceInfo?.arch ?: "未知", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), Arrangement.SpaceBetween) {
                                Text("内存", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(report?.deviceInfo?.memory ?: "未知", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), Arrangement.SpaceBetween) {
                                Text("存储", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(report?.deviceInfo?.diskSpace ?: "未知", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }

                // ===== 测试结果 =====
                item {
                    Text("测试结果", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
                }
                val results = report?.results ?: emptyList()
                items(results) { result ->
                    val severityColor = when (result.severity) {
                        DiagnosticsRunner.Severity.ERROR -> Color(0xFFFF4757)
                        DiagnosticsRunner.Severity.WARNING -> Color(0xFFFFA502)
                        DiagnosticsRunner.Severity.INFO -> if (result.passed) Color(0xFF2ED573) else MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val bgColor = when (result.severity) {
                        DiagnosticsRunner.Severity.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
                        DiagnosticsRunner.Severity.WARNING -> Color(0xFFFFA502).copy(alpha = 0.08f)
                        DiagnosticsRunner.Severity.INFO -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    }
                    Card(onClick = { expandedItem = if (expandedItem == result.name) null else result.name },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = bgColor)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (result.passed) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                    null, tint = severityColor, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(result.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                Text(if (result.passed) "通过" else "失败", fontSize = 12.sp,
                                    color = if (result.passed) Color(0xFF2ED573) else Color(0xFFFF4757),
                                    fontFamily = FontFamily.Monospace)
                            }
                            Text(result.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = if (expandedItem == result.name) Int.MAX_VALUE else 1,
                                modifier = Modifier.padding(start = 26.dp, top = 4.dp))
                            if (expandedItem == result.name && result.suggestion.isNotBlank()) {
                                Surface(modifier = Modifier.padding(start = 26.dp, top = 8.dp),
                                    shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Row(modifier = Modifier.padding(8.dp)) {
                                        Icon(Icons.Default.Lightbulb, null, tint = Color(0xFFFFA502), modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(result.suggestion, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ===== GitHub 上报 =====
            item {
                Text("GitHub 上报", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 16.dp))
            }

            // Token 配置折叠
            item {
                Card(onClick = { showGitHubConfig = !showGitHubConfig },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Code, null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(if (prefs.isConfigured) "已配置 GitHub Token" else "配置 GitHub 连接",
                                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Icon(if (showGitHubConfig) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        }
                    }
                }
            }

            if (showGitHubConfig) {
                item {
                    Card(modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            OutlinedTextField(value = githubToken, onValueChange = { githubToken = it },
                                label = { Text("GitHub Token") },
                                placeholder = { Text("ghp_xxxxxxxxxxxxxxxxxxxx") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showToken = !showToken }) {
                                        Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                                    }
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(value = repoOwner, onValueChange = { repoOwner = it },
                                    label = { Text("仓库 Owner") }, modifier = Modifier.weight(1f), singleLine = true)
                                OutlinedTextField(value = repoName, onValueChange = { repoName = it },
                                    label = { Text("仓库名") }, modifier = Modifier.weight(1f), singleLine = true)
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                prefs.githubToken = githubToken
                                prefs.repoOwner = repoOwner
                                prefs.repoName = repoName
                                Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("保存配置")
                            }
                        }
                    }
                }
            }

            // 上传按钮
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (!prefs.isConfigured) {
                            Toast.makeText(context, "请先配置 GitHub Token", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (report == null) return@Button
                        scope.launch {
                            isUploading = true; uploadResult = null
                            val r = if (prefs.gistMode) {
                                uploader.uploadToGist(report ?: return@launch, prefs.githubToken)
                            } else {
                                uploader.uploadToGitHubIssues(report ?: return@launch, prefs.githubToken, prefs.repoOwner, prefs.repoName)
                            }
                            uploadResult = r.message
                            isUploading = false
                            if (r.success) Toast.makeText(context, "上传成功: ${r.url}", Toast.LENGTH_LONG).show()
                            else Toast.makeText(context, "上传失败: ${r.message.take(100)}", Toast.LENGTH_LONG).show()
                        }
                    }, modifier = Modifier.weight(1f), enabled = !isUploading && report != null) {
                        if (isUploading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(if (prefs.gistMode) "上传到 Gist" else "创建 Issue", fontSize = 13.sp)
                    }

                    OutlinedButton(onClick = {
                        prefs.gistMode = !prefs.gistMode
                    }, modifier = Modifier.weight(1f)) {
                        Text(if (prefs.gistMode) "切换为 Issue" else "切换为 Gist", fontSize = 13.sp)
                    }
                }
            }

            if (uploadResult != null) {
                val result = uploadResult ?: "Unknown"
                item {
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text(result, modifier = Modifier.padding(12.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // ===== 崩溃日志 =====
            item {
                Text("崩溃日志", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 16.dp))
            }

            item {
                Card(onClick = { showCrashLogs = !showCrashLogs },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.BugReport, null, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("崩溃记录", fontWeight = FontWeight.Medium)
                                Text("${crashLogs.size} 条记录", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (crashLogs.isNotEmpty()) {
                                TextButton(onClick = { crashHandler.clearCrashLogs(); crashLogs = emptyList() }) {
                                    Text("清除", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            if (showCrashLogs && crashLogs.isNotEmpty()) {
                items(crashLogs) { file ->
                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF0A0A0F)) {
                        Text(file.name, modifier = Modifier.padding(12.dp),
                            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF4AF626))
                    }
                }
            }

            // ===== 快速操作 =====
            item {
                Text("快速操作", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp, top = 16.dp))
            }

            item {
                OutlinedButton(onClick = {
                    scope.launch {
    isRunning = true
    val results = diagnostics.runCategory("network")
    report = report?.let { r -> r.copy(results = results) } ?: DiagnosticsRunner.DiagnosticsReport(results = results)
    isRunning = false
}
                }, modifier = Modifier.fillMaxWidth(), enabled = !isRunning) {
                    Icon(Icons.Default.Wifi, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("仅测试网络")
                }
            }
            item {
                OutlinedButton(onClick = {
                    scope.launch {
    isRunning = true
    val results = diagnostics.runCategory("environment")
    report = report?.let { r -> r.copy(results = results) } ?: DiagnosticsRunner.DiagnosticsReport(results = results)
    isRunning = false
}
                }, modifier = Modifier.fillMaxWidth(), enabled = !isRunning) {
                    Icon(Icons.Default.Build, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("仅测试开发环境")
                }
            }
            item {
                OutlinedButton(onClick = {
                    scope.launch {
    isRunning = true
    val results = diagnostics.runCategory("codex")
    report = report?.let { r -> r.copy(results = results) } ?: DiagnosticsRunner.DiagnosticsReport(results = results)
    isRunning = false
}
                }, modifier = Modifier.fillMaxWidth(), enabled = !isRunning) {
                    Icon(Icons.Default.Terminal, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("仅测试 Codex CLI")
                }
            }
            // 导出日志
            item {
                OutlinedButton(onClick = {
                    try {
                        val file = crashHandler.exportLogcat(800)
                        lastExportPath = file.absolutePath
                        Toast.makeText(context, "日志已导出: ${file.name}", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text("导出 Logcat 日志")
                }
            }
            if (lastExportPath != null) {
                item {
                    Text("上次导出: $lastExportPath", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace)
                }
            }

            // ===== 使用说明 =====
            item {
                Spacer(Modifier.height(16.dp))
                Surface(shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)) {
                    Text(
                        "使用说明:\n" +
                        "1. 配置 GitHub Token → 诊断结果可自动创建 Issue/Gist\n" +
                        "2. Token 需要 repo 和 gist 权限\n" +
                        "3. GitHub Token 仅存储在本地\n" +
                        "4. 崩溃日志自动捕获在 crashes/ 目录",
                        modifier = Modifier.padding(16.dp), fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 18.sp)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

private fun buildReportText(report: DiagnosticsRunner.DiagnosticsReport?): String {
    if (report == null) return "诊断未运行"
    val sb = StringBuilder()
    sb.appendLine("=== Codex Android 诊断报告 ===")
    sb.appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(report.timestamp))}")
    sb.appendLine()
    sb.appendLine("--- 设备 ---")
    sb.appendLine("Android: ${report.deviceInfo.androidVersion}")
    sb.appendLine("设备: ${report.deviceInfo.device}")
    sb.appendLine("架构: ${report.deviceInfo.arch}")
    sb.appendLine("内存: ${report.deviceInfo.memory}")
    sb.appendLine()
    sb.appendLine("--- 测试结果: ${report.passedCount}/${report.totalCount} 通过 ---")
    for (r in report.results) {
        val status = if (r.passed) "✅" else "❌"
        sb.appendLine("$status ${r.name}: ${r.detail}")
        if (r.suggestion.isNotBlank()) sb.appendLine("   建议: ${r.suggestion}")
    }
    return sb.toString()
}
