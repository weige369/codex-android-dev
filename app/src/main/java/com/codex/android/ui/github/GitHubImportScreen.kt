package com.codex.android.ui.github

import android.content.Context
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.codex.github.GitHubApiClient
import kotlinx.coroutines.launch

/**
 * GitHub 导入界面。
 *
 * 提供从 GitHub 导入仓库到 Codex 工作区的完整流程：
 * 1. 仓库 URL 直接导入
 * 2. 用户仓库列表浏览
 * 3. 搜索仓库
 * 4. 导入进度显示
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubImportScreen(
    workspaceDir: String,
    onBack: () -> Unit = {},
    onManageRepo: ((fullName: String, localPath: String) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiClient = remember { GitHubApiClient(context) }

    var activeTab by remember { mutableIntStateOf(0) }
    var repoUrl by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var importLog by remember { mutableStateOf("") }
    var importResult by remember { mutableStateOf<String?>(null) }

    // 仓库列表
    var userRepos by remember { mutableStateOf<List<GitHubApiClient.GitHubRepo>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<GitHubApiClient.GitHubRepo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var currentUser by remember { mutableStateOf<GitHubApiClient.GitHubUser?>(null) }

    // 初始加载
    LaunchedEffect(Unit) {
        isLoading = true
        apiClient.getCurrentUser().onSuccess { user ->
            currentUser = user
        }.onFailure { e ->
            errorMessage = "认证失败: ${e.message}"
        }
        apiClient.getUserRepos().onSuccess { repos ->
            userRepos = repos
        }.onFailure { e ->
            if (errorMessage == null) errorMessage = "获取仓库列表失败: ${e.message}"
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("从 GitHub 导入", fontSize = 18.sp) },
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
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // Tab Row
            TabRow(selectedTabIndex = activeTab) {
                Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) {
                    TabText("URL 导入")
                }
                Tab(selected = activeTab == 1, onClick = {
                    activeTab = 1
                    if (userRepos.isEmpty()) {
                        scope.launch {
                            isLoading = true
                            apiClient.getUserRepos().onSuccess { userRepos = it }
                            isLoading = false
                        }
                    }
                }) {
                    TabText("我的仓库")
                }
                Tab(selected = activeTab == 2, onClick = { activeTab = 2 }) {
                    TabText("搜索")
                }
            }

            when (activeTab) {
                0 -> UrlImportTab(
                    repoUrl = repoUrl,
                    onUrlChange = { repoUrl = it },
                    isImporting = isImporting,
                    importLog = importLog,
                    importResult = importResult,
                    workspaceDir = workspaceDir,
                    onManageRepo = onManageRepo,
                    onStartImport = {
                        if (repoUrl.isBlank()) return@UrlImportTab
                        isImporting = true
                        importLog = ""
                        importResult = null
                        scope.launch {
                            val result = apiClient.importRepo(
                                repoUrl = repoUrl,
                                targetDir = java.io.File(workspaceDir),
                                onProgress = { msg ->
                                    importLog += "\n$msg"
                                }
                            )
                            importResult = if (result.success) "✅ 导入成功: ${result.repoPath}" else "❌ ${result.message}"
                            isImporting = false
                        }
                    }
                )
                1 -> UserReposTab(
                    repos = userRepos,
                    isLoading = isLoading,
                    currentUser = currentUser,
                    onImport = { repo ->
                        isImporting = true
                        importLog = "正在导入: ${repo.fullName}\n"
                        scope.launch {
                            val result = apiClient.importRepo(
                                repoUrl = repo.cloneUrl,
                                targetDir = java.io.File(workspaceDir),
                                onProgress = { msg ->
                                    importLog += "$msg\n"
                                }
                            )
                            importResult = if (result.success) "✅ 导入成功: ${result.repoPath}" else "❌ ${result.message}"
                            isImporting = false
                        }
                    }
                )
                2 -> SearchTab(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    results = searchResults,
                    isLoading = isLoading,
                    onSearch = {
                        if (searchQuery.isBlank()) return@SearchTab
                        scope.launch {
                            isLoading = true
                            apiClient.searchRepos(searchQuery).onSuccess { repos ->
                                searchResults = repos
                            }.onFailure { e ->
                                errorMessage = e.message
                            }
                            isLoading = false
                        }
                    },
                    onImport = { repo ->
                        isImporting = true
                        importLog = "正在导入: ${repo.fullName}\n"
                        scope.launch {
                            val result = apiClient.importRepo(
                                repoUrl = repo.cloneUrl,
                                targetDir = java.io.File(workspaceDir),
                                onProgress = { msg ->
                                    importLog += "$msg\n"
                                }
                            )
                            importResult = if (result.success) "✅ 导入成功: ${result.repoPath}" else "❌ ${result.message}"
                            isImporting = false
                        }
                    }
                )
            }

            // 错误提示
            errorMessage?.let { err ->
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { errorMessage = null }) {
                            Text("关闭")
                        }
                    }
                ) {
                    Text(err, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun TabText(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}

// ===== Tab 1: URL 导入 =====
@Composable
private fun UrlImportTab(
    repoUrl: String,
    onUrlChange: (String) -> Unit,
    isImporting: Boolean,
    importLog: String,
    importResult: String?,
    onStartImport: () -> Unit,
    workspaceDir: String = "",
    onManageRepo: ((fullName: String, localPath: String) -> Unit)? = null
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("支持以下格式:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• https://github.com/owner/repo", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• git@github.com:owner/repo.git", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("• owner/repo", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = repoUrl,
                        onValueChange = onUrlChange,
                        label = { Text("GitHub 仓库 URL") },
                        placeholder = { Text("https://github.com/owner/repo") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Link, null) },
                        enabled = !isImporting
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onStartImport,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = repoUrl.isNotBlank() && !isImporting
                    ) {
                        if (isImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (isImporting) "正在导入..." else "导入仓库")
                    }
                }
            }
        }

        if (importLog.isNotBlank()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1A1A2E)
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("导入日志", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4AF626))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            importLog.trimStart(),
                            fontSize = 11.sp,
                            color = Color(0xFF4AF626),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        importResult?.let { result ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (result.contains("✅"))
                            Color(0xFF1B5E20).copy(alpha = 0.2f)
                        else Color(0xFFB71C1C).copy(alpha = 0.2f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(result, fontSize = 14.sp)
                        if (result.contains("✅") && onManageRepo != null) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    // Parse repo name from import result path
                                    val path = workspaceDir
                                    val repoPath = result.substringAfter("✅ 导入成功: ")
                                    val repoName = repoPath.substringAfterLast("/")
                                    onManageRepo(repoName, path)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Settings, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("管理仓库", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ===== Tab 2: 用户仓库列表 =====
@Composable
private fun UserReposTab(
    repos: List<GitHubApiClient.GitHubRepo>,
    isLoading: Boolean,
    currentUser: GitHubApiClient.GitHubUser?,
    onImport: (GitHubApiClient.GitHubRepo) -> Unit
) {
    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 用户信息
        currentUser?.let { user ->
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(user.login, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(
                                "${user.publicRepos} 个公开仓库 · ${user.followers} 个关注者",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "${repos.size} 仓库",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // 仓库列表
        items(repos) { repo ->
            RepoCard(repo = repo, onImport = { onImport(repo) })
        }
    }
}

// ===== Tab 3: 搜索 =====
@Composable
private fun SearchTab(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<GitHubApiClient.GitHubRepo>,
    isLoading: Boolean,
    onSearch: () -> Unit,
    onImport: (GitHubApiClient.GitHubRepo) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // 搜索栏
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("搜索 GitHub 仓库") },
            placeholder = { Text("输入关键词搜索...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Default.Send, "搜索")
                    }
                }
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                onSearch = { onSearch() }
            )
        )

        Spacer(Modifier.height(12.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (results.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("输入关键词搜索仓库", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results) { repo ->
                    RepoCard(repo = repo, onImport = { onImport(repo) })
                }
            }
        }
    }
}

// ===== 仓库卡片 =====
@Composable
private fun RepoCard(
    repo: GitHubApiClient.GitHubRepo,
    onImport: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (repo.isPrivate) Icons.Default.Lock else Icons.Default.Public,
                        null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(repo.fullName, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                }
                if (repo.description.isNotBlank()) {
                    Text(
                        repo.description,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repo.language?.let { lang ->
                        LabelChip(lang)
                    }
                    LabelChip("⭐ ${repo.stars}")
                    LabelChip("⑂ ${repo.forks}")
                }
            }

            Spacer(Modifier.width(8.dp))

            FilledTonalButton(
                onClick = onImport,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.align(Alignment.CenterVertically)
            ) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("导入", fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun LabelChip(text: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
