package com.codex.android.ui.github

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.codex.github.GitHubApiClient
import com.codex.android.core.designsystem.*
import kotlinx.coroutines.launch

/**
 * Issue 管理界面。
 *
 * 提供：
 * - Issue 列表查看（open/closed/all）
 * - Issue 详情查看
 * - 创建 Issue
 * - 评论 Issue
 * - 关闭 Issue
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubIssueScreen(
    repoFullName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiClient = remember { GitHubApiClient(context) }

    val owner = repoFullName.split("/").getOrElse(0) { "" }
    val repo = repoFullName.split("/").getOrElse(1) { "" }

    var issues by remember { mutableStateOf<List<GitHubApiClient.Issue>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var filterState by remember { mutableStateOf("open") }
    var selectedIssue by remember { mutableStateOf<GitHubApiClient.Issue?>(null) }
    var showCreateIssue by remember { mutableStateOf(false) }

    // Create fields
    var createTitle by remember { mutableStateOf("") }
    var createBody by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }

    // Comments
    var comments by remember { mutableStateOf<List<GitHubApiClient.IssueComment>>(emptyList()) }
    var commentsLoading by remember { mutableStateOf(false) }
    var newComment by remember { mutableStateOf("") }
    var isCommenting by remember { mutableStateOf(false) }

    fun loadIssues(state: String = filterState) {
        isLoading = true
        errorMessage = null
        scope.launch {
            apiClient.listIssues(owner, repo, state).onSuccess {
                issues = it
            }.onFailure {
                errorMessage = "加载失败: ${it.message}"
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { loadIssues() }

    val currentIssue = selectedIssue
    if (currentIssue != null) {
        IssueDetailScreen(
            issue = currentIssue,
            comments = comments,
            commentsLoading = commentsLoading,
            newComment = newComment,
            isCommenting = isCommenting,
            onNewCommentChange = { newComment = it },
            onSendComment = {
                if (newComment.isBlank()) return@IssueDetailScreen
                isCommenting = true
                scope.launch {
                    apiClient.createIssueComment(owner, repo, currentIssue.number, newComment).onSuccess {
                        newComment = ""
                        commentsLoading = true
                        apiClient.listIssueComments(owner, repo, currentIssue.number).onSuccess {
                            comments = it
                        }
                        commentsLoading = false
                    }
                    isCommenting = false
                }
            },
            onCloseIssue = {
                scope.launch {
                    apiClient.closeIssue(owner, repo, currentIssue.number).onSuccess {
                        selectedIssue = null
                        loadIssues()
                        Toast.makeText(context, "Issue 已关闭", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, "关闭失败: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onBack = {
                selectedIssue = null
                comments = emptyList()
            },
            repoFullName = repoFullName
        )
        return
    }

    if (showCreateIssue) {
        CreateIssueScreen(
            title = createTitle,
            onTitleChange = { createTitle = it },
            body = createBody,
            onBodyChange = { createBody = it },
            isCreating = isCreating,
            onCreate = {
                if (createTitle.isBlank()) {
                    Toast.makeText(context, "请填写标题", Toast.LENGTH_SHORT).show()
                    return@CreateIssueScreen
                }
                isCreating = true
                scope.launch {
                    apiClient.createIssue(owner, repo, createTitle, createBody).onSuccess {
                        createTitle = ""
                        createBody = ""
                        showCreateIssue = false
                        loadIssues()
                        Toast.makeText(context, "Issue 已创建", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, "创建失败: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                    isCreating = false
                }
            },
            onBack = { showCreateIssue = false },
            repoFullName = repoFullName
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Issues - $repoFullName", fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { loadIssues() }) {
                        Icon(Icons.Default.Refresh, "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CxSurface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateIssue = true }) {
                Icon(Icons.Default.Add, "创建 Issue")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // Filter chips
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filterState == "open",
                    onClick = { filterState = "open"; loadIssues("open") },
                    label = { Text("开启的") }
                )
                FilterChip(
                    selected = filterState == "closed",
                    onClick = { filterState = "closed"; loadIssues("closed") },
                    label = { Text("已关闭") }
                )
                FilterChip(
                    selected = filterState == "all",
                    onClick = { filterState = "all"; loadIssues("all") },
                    label = { Text("全部") }
                )
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (errorMessage != null) {
                val msg = errorMessage ?: return
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(msg, color = CxError)
                }
            } else if (issues.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无 Issue", color = CxTextSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(issues) { issue ->
                        IssueCard(
                            issue = issue,
                            onClick = {
                                selectedIssue = issue
                                commentsLoading = true
                                scope.launch {
                                    apiClient.listIssueComments(owner, repo, issue.number).onSuccess {
                                        comments = it
                                    }
                                    commentsLoading = false
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IssueCard(
    issue: GitHubApiClient.Issue,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = CxSurface.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (issue.state == "open") CxOnline.copy(alpha = 0.15f)
                    else CxError.copy(alpha = 0.15f)
                ) {
                    Icon(
                        if (issue.state == "open") Icons.Default.BugReport else Icons.Default.CheckCircle,
                        null,
                        modifier = Modifier.size(20.dp).padding(2.dp),
                        tint = if (issue.state == "open") CxOnline else CxError
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("#${issue.number} ${issue.title}", fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 2)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(issue.userLogin, fontSize = 11.sp, color = CxTextSecondary)
                issue.labels.take(3).forEach { label ->
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = CxPrimary.copy(alpha = 0.15f)
                    ) {
                        Text(label, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 10.sp)
                    }
                }
                Text("${issue.comments} 评论", fontSize = 11.sp, color = CxTextSecondary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IssueDetailScreen(
    issue: GitHubApiClient.Issue,
    comments: List<GitHubApiClient.IssueComment>,
    commentsLoading: Boolean,
    newComment: String,
    isCommenting: Boolean,
    onNewCommentChange: (String) -> Unit,
    onSendComment: () -> Unit,
    onCloseIssue: () -> Unit,
    onBack: () -> Unit,
    repoFullName: String
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Issue #${issue.number}", fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (issue.state == "open") {
                        IconButton(onClick = onCloseIssue) {
                            Icon(Icons.Default.Close, "关闭 Issue")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CxSurface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(issue.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("by ${issue.userLogin}", fontSize = 12.sp, color = CxTextSecondary)
                            Text(issue.createdAt.take(10), fontSize = 12.sp, color = CxTextSecondary)
                        }
                        Spacer(Modifier.height(8.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text(issue.state) },
                            leadingIcon = {
                                Icon(
                                    if (issue.state == "open") Icons.Default.BugReport else Icons.Default.Check,
                                    null, modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                        if (issue.body.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(issue.body, fontSize = 13.sp)
                        }
                    }
                }
            }

            item {
                Text("评论 (${comments.size})", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            if (commentsLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                items(comments) { comment ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = CxSurface.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(comment.userLogin, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(comment.createdAt.take(10), fontSize = 11.sp, color = CxTextSecondary)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(comment.body, fontSize = 13.sp)
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newComment,
                        onValueChange = onNewCommentChange,
                        placeholder = { Text("写评论...") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        enabled = !isCommenting
                    )
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = onSendComment,
                        enabled = !isCommenting && newComment.isNotBlank()
                    ) {
                        if (isCommenting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Send, "发送")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateIssueScreen(
    title: String,
    onTitleChange: (String) -> Unit,
    body: String,
    onBodyChange: (String) -> Unit,
    isCreating: Boolean,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    repoFullName: String
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("创建 Issue", fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CxSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("仓库: $repoFullName", fontSize = 13.sp, color = CxTextSecondary)

            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                label = { Text("标题") },
                placeholder = { Text("Issue 标题...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isCreating
            )

            OutlinedTextField(
                value = body,
                onValueChange = onBodyChange,
                label = { Text("描述") },
                placeholder = { Text("Issue 描述...") },
                modifier = Modifier.fillMaxWidth().height(200.dp),
                maxLines = 10,
                enabled = !isCreating
            )

            Button(
                onClick = onCreate,
                enabled = !isCreating && title.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isCreating) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isCreating) "创建中..." else "创建 Issue")
            }
        }
    }
}
