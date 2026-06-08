package com.codex.android.ui.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.util.WorkspaceFileManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileBrowserScreen(
    workspacePath: String,
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentDir by remember { mutableStateOf(File(workspacePath)) }
    var files by remember { mutableStateOf(WorkspaceFileManager.scanDirectory(currentDir)) }
    var selectedFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showNewItemDialog by remember { mutableStateOf(false) }
    var newItemName by remember { mutableStateOf("") }
    var newItemIsDir by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var fileToDelete by remember { mutableStateOf<WorkspaceFileManager.FileItem?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<WorkspaceFileManager.FileItem?>(null) }
    var newName by remember { mutableStateOf("") }
    var showDetailFile by remember { mutableStateOf<WorkspaceFileManager.FileItem?>(null) }
    var currentPathHistory by remember { mutableStateOf(listOf(workspacePath)) }
    var snackbarMessage by remember { mutableStateOf<String?>(null) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    val safExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val sourceDir = currentDir
            exportViaSaf(context, sourceDir, uri)
            snackbarMessage = "导出完成"
        }
    }

    val filteredFiles = remember(files, searchQuery) {
        if (searchQuery.isBlank()) files
        else files.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    fun enterDirectory(dir: File) {
        currentDir = dir
        files = WorkspaceFileManager.scanDirectory(dir)
        currentPathHistory = currentPathHistory + dir.absolutePath
        selectedFiles = emptySet()
    }

    fun goUp() {
        if (currentPathHistory.size > 1) {
            currentPathHistory = currentPathHistory.dropLast(1)
            val parent = File(currentPathHistory.last())
            currentDir = parent
            files = WorkspaceFileManager.scanDirectory(parent)
            selectedFiles = emptySet()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column {
                    Text("文件", fontSize = 18.sp)
                    Text(currentDir.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }},
                navigationIcon = {
                    IconButton(onClick = if (currentPathHistory.size > 1) {{ goUp() }} else onBack) {
                        Icon(if (currentPathHistory.size > 1) Icons.Default.ArrowUpward else Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        Text("${selectedFiles.size} 已选", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        IconButton(onClick = {
                            val items = files.filter { it.path in selectedFiles }
                            val shareFiles = items.filter { !it.isDirectory }.map { File(it.path) }
                            if (shareFiles.isNotEmpty()) WorkspaceFileManager.shareMultipleFiles(context, shareFiles)
                        }) { Icon(Icons.Default.Share, "分享选中") }
                        IconButton(onClick = {
                            files.filter { it.path in selectedFiles }.forEach { WorkspaceFileManager.deleteFile(File(it.path)) }
                            files = WorkspaceFileManager.scanDirectory(currentDir)
                            selectedFiles = emptySet(); isSelectionMode = false
                            snackbarMessage = "已删除选中文件"
                        }) { Icon(Icons.Default.Delete, "删除选中", tint = MaterialTheme.colorScheme.error) }
                        IconButton(onClick = { isSelectionMode = false; selectedFiles = emptySet() }) { Icon(Icons.Default.Close, "取消") }
                    } else {
                        IconButton(onClick = { showSearch = !showSearch }) { Icon(Icons.Default.Search, "搜索") }
                        IconButton(onClick = { newItemName = ""; newItemIsDir = false; showNewItemDialog = true }) { Icon(Icons.Default.CreateNewFolder, "新建") }
                        IconButton(onClick = { safExportLauncher.launch(null) }) { Icon(Icons.Default.SaveAlt, "导出到...") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), tonalElevation = 1.dp) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val totalSize = WorkspaceFileManager.getDirectorySize(currentDir)
                    Text("${files.size} 项 · ${formatSize(totalSize)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { snackbarMessage = WorkspaceFileManager.exportToDownloads(context, currentDir, currentDir.name).message }) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                        Text("导出到 Downloads", fontSize = 12.sp)
                    }
                    TextButton(onClick = { WorkspaceFileManager.shareMultipleFiles(context, files.filter { !it.isDirectory }.map { File(it.path) }) }) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                        Text("全部分享", fontSize = 12.sp)
                    }
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AnimatedVisibility(visible = showSearch) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, placeholder = { Text("搜索文件...") },
                        modifier = Modifier.fillMaxWidth().padding(8.dp), singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = { if (searchQuery.isNotBlank()) IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Clear, "清除") } })
                }
            }

            if (filteredFiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                        Spacer(Modifier.height(8.dp))
                        Text(if (searchQuery.isNotBlank()) "未找到匹配的文件" else "工作区为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(filteredFiles, key = { it.path }) { item ->
                        FileRow(item = item, isSelected = item.path in selectedFiles, isSelectionMode = isSelectionMode,
                            dateFormat = dateFormat,
                            onTap = {
                                if (isSelectionMode) { toggleSelection(selectedFiles, item.path) { selectedFiles = it } }
                                else if (item.isDirectory) { enterDirectory(File(item.path)) }
                                else { openFile(context, File(item.path)) }
                            },
                            onLongPress = { if (!isSelectionMode) { isSelectionMode = true; selectedFiles = setOf(item.path) } },
                            onShare = { WorkspaceFileManager.shareFile(context, File(item.path)) },
                            onOpenWith = { openFileWith(context, File(item.path)) },
                            onRename = { renameTarget = item; newName = item.name; showRenameDialog = true },
                            onDelete = { fileToDelete = item; showDeleteConfirm = true },
                            onDetails = { showDetailFile = item }
                        )
                    }
                }
            }
        }
    }

    // Dialogs
    if (showNewItemDialog) {
        AlertDialog(onDismissRequest = { showNewItemDialog = false }, title = { Text(if (newItemIsDir) "新建文件夹" else "新建文件") },
            text = { Column {
                OutlinedTextField(value = newItemName, onValueChange = { newItemName = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = newItemIsDir, onCheckedChange = { newItemIsDir = it }); Text("创建为文件夹") }
            }},
            confirmButton = { TextButton(onClick = {
                if (newItemName.isNotBlank()) {
                    val newFile = File(currentDir, newItemName)
                    val success = if (newItemIsDir) newFile.mkdir() else newFile.createNewFile()
                    snackbarMessage = if (success) "已创建: $newItemName" else "创建失败（可能已存在）"
                    files = WorkspaceFileManager.scanDirectory(currentDir); showNewItemDialog = false
                }
            }) { Text("创建") }},
            dismissButton = { TextButton(onClick = { showNewItemDialog = false }) { Text("取消") } }
        )
    }

    if (showDeleteConfirm && fileToDelete != null) {
        val deleteTarget = fileToDelete ?: return
        AlertDialog(onDismissRequest = { showDeleteConfirm = false }, title = { Text("确认删除") },
            text = { Text("确定要删除「${deleteTarget.name}」吗？\n此操作不可撤销。") },
            confirmButton = { TextButton(onClick = {
                WorkspaceFileManager.deleteFile(File(deleteTarget.path))
                files = WorkspaceFileManager.scanDirectory(currentDir)
                showDeleteConfirm = false; fileToDelete = null; snackbarMessage = "已删除"
            }) { Text("删除", color = MaterialTheme.colorScheme.error) }},
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }

    if (showRenameDialog && renameTarget != null) {
        val renameSource = renameTarget ?: return
        AlertDialog(onDismissRequest = { showRenameDialog = false }, title = { Text("重命名") },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("新名称") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = {
                if (newName.isNotBlank()) {
                    WorkspaceFileManager.renameFile(File(renameSource.path), newName)
                    files = WorkspaceFileManager.scanDirectory(currentDir); showRenameDialog = false; snackbarMessage = "已重命名"
                }
            }) { Text("确定") }},
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("取消") } }
        )
    }

    val detailFile = showDetailFile
    if (detailFile != null) {
        val detail = detailFile
        AlertDialog(onDismissRequest = { showDetailFile = null }, title = { Text("文件详情") },
            text = { Column {
                DetailRow("名称", detail.name); DetailRow("路径", detail.path)
                DetailRow("类型", if (detail.isDirectory) "文件夹" else detail.extension.uppercase())
                DetailRow("大小", if (detail.isDirectory) "-" else formatSize(detail.size))
                DetailRow("修改时间", dateFormat.format(Date(detail.lastModified)))
                if (!detail.isDirectory) { DetailRow("MIME", WorkspaceFileManager.getMimeType(detail.name) ?: "未知"); DetailRow("文本文件", if (WorkspaceFileManager.isTextFile(detail.name)) "是" else "否") }
            }},
            confirmButton = { TextButton(onClick = { showDetailFile = null }) { Text("关闭") } },
            dismissButton = { TextButton(onClick = { WorkspaceFileManager.shareFile(context, File(detail.path)); showDetailFile = null }) { Text("分享") } }
        )
    }

    snackbarMessage?.let { msg ->
        Snackbar(modifier = Modifier.padding(16.dp), action = { TextButton(onClick = { snackbarMessage = null }) { Text("关闭") } }) { Text(msg, fontSize = 13.sp) }
        LaunchedEffect(msg) { kotlinx.coroutines.delay(3000); snackbarMessage = null }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: WorkspaceFileManager.FileItem, isSelected: Boolean, isSelectionMode: Boolean,
    dateFormat: SimpleDateFormat,
    onTap: () -> Unit, onLongPress: () -> Unit,
    onShare: () -> Unit, onOpenWith: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onDetails: () -> Unit
) {
    var showActions by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).combinedClickable(onClick = onTap, onLongClick = onLongPress),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(8.dp)) {
        Column {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(iconForFile(item), null, modifier = Modifier.size(24.dp), tint = if (item.isDirectory) Color(0xFFFFA726) else MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(buildString {
                        if (item.isDirectory) append("文件夹") else append(formatSize(item.size))
                        append(" · "); append(dateFormat.format(Date(item.lastModified)))
                    }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isSelectionMode) { Checkbox(checked = isSelected, onCheckedChange = { onTap() }) }
                else { IconButton(onClick = { showActions = !showActions }) { Icon(Icons.Default.MoreVert, "更多", modifier = Modifier.size(18.dp)) } }
            }
            AnimatedVisibility(visible = showActions) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ActionChip(Icons.Default.Share, "分享", onShare); ActionChip(Icons.Default.OpenInNew, "打开方式", onOpenWith)
                        ActionChip(Icons.Default.DriveFileRenameOutline, "重命名", onRename); ActionChip(Icons.Default.Info, "详情", onDetails)
                        ActionChip(Icons.Default.Delete, "删除", onDelete)
                    }
                }
            }
        }
    }
}

@Composable private fun ActionChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
        Icon(icon, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(2.dp)); Text(label, fontSize = 10.sp)
    }
}

@Composable private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

private fun iconForFile(item: WorkspaceFileManager.FileItem): ImageVector = when {
    item.isDirectory -> Icons.Default.CreateNewFolder
    item.extension in listOf("kt","kts","py","js","ts","tsx","jsx","java","rs","go","swift","dart","sql","sh") -> Icons.Default.Code
    item.extension in listOf("html","htm","css","scss") -> Icons.Default.Code
    item.extension == "json" -> Icons.Default.DataObject
    item.extension == "xml" -> Icons.Default.DataArray
    item.extension == "md" -> Icons.Default.Description
    item.extension in listOf("png","jpg","jpeg","gif","webp","svg","bmp") -> Icons.Default.Image
    item.extension in listOf("mp4","mov","avi","mkv","webm","flv","wmv","3gp") -> Icons.Default.VideoFile
    item.extension in listOf("mp3","wav","aac","flac","ogg","wma","m4a") -> Icons.Default.AudioFile
    item.extension in listOf("zip","tar","gz","bz2","xz","rar","7z") -> Icons.Default.Archive
    item.extension == "apk" -> Icons.Default.Android
    item.extension == "pdf" -> Icons.Default.PictureAsPdf
    else -> Icons.Default.InsertDriveFile
}

private fun toggleSelection(current: Set<String>, path: String, onUpdate: (Set<String>) -> Unit) { onUpdate(if (path in current) current - path else current + path) }

private fun openFile(context: Context, file: File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, WorkspaceFileManager.getMimeType(file.name) ?: "*/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent) else Toast.makeText(context, "没有应用可以打开此文件", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) { Toast.makeText(context, "打开失败: ${e.message}", Toast.LENGTH_SHORT).show() }
}

private fun openFileWith(context: Context, file: File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, WorkspaceFileManager.getMimeType(file.name) ?: "*/*"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(Intent.createChooser(intent, "打开: ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { Toast.makeText(context, "打开失败: ${e.message}", Toast.LENGTH_SHORT).show() }
}

private fun exportViaSaf(context: Context, sourceDir: File, targetTreeUri: Uri) {
    try {
        val docUri = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, targetTreeUri) ?: return
        sourceDir.listFiles()?.forEach { file ->
            val targetFile = if (file.isDirectory) docUri.createDirectory(file.name)
            else docUri.createFile(WorkspaceFileManager.getMimeType(file.name) ?: "application/octet-stream", file.name)
            targetFile?.let { context.contentResolver.openOutputStream(it.uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } }
        }
    } catch (e: Exception) { Toast.makeText(context, "SAF 导出失败: ${e.message}", Toast.LENGTH_SHORT).show() }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1048576 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1073741824 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}


