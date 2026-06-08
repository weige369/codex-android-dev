package com.codex.android.ui.github

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.codex.github.GitHubApiClient
import com.codex.android.core.designsystem.*
import kotlinx.coroutines.launch

/**
 * Pull Request 管理界面。
 *
 * 提供：
 * - PR 列表查看（open/closed/all）
 * - PR 详情查看
 * - 创建 PR
 * - 评论 PR
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubPRScreen(
    repoFullName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apiClient = remember { GitHubApiClient(context) }

    val owner = repoFullName.split("/").getOrElse(0) { "" }
    val repo = repoFullName.split("/").getOrElse(1) { "" }

    var prs by remember { mutableStateOf<List<GitHubApiClient.PullRequest>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var filterState by remember { mutableStateOf("open") }
    var selectedPR by remember { mutableStateOf<GitHubApiClient.PullRequest?>(null) }
    var showCreatePR by remember { mutableStateOf(false) }

    // Create PR fields
    var createTitle by remember { mutableStateOf("") }
    var createBody by remember { mutableStateOf("") }
    var createHead by remember { mutableStateOf("") }
    var createBase by remember { mutableStateOf("") }
    var isCreating by remember { mutableStateOf(false) }

    // Comments
    var comments by remember { mutableStateOf<List<GitHubApiClient.PRComment>>(emptyList()) }
    var commentsLoading by remember { mutableStateOf(false) }
    var newComment by remember { mutableStateOf("") }
    var isCommenting by remember { mutableStateOf(false) }

    fun loadPRs(state: String = filterState) {
        isLoading = true
        errorMessage = null
        scope.launch {
            apiClient.listPullRequests(owner, repo, state).onSuccess {
                prs = it
            }.onFailure {
                errorMessage = "加载失败: ${it.message}"
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { loadPRs() }

    val currentPR = selectedPR
    if (currentPR != null) {
        PRDetailScreen(
            pr = currentPR,
            comments = comments,
            commentsLoading = commentsLoading,
            newComment = newComment,
            isCommenting = isCommenting,
            onNewCommentChange = { newComment = it },
            onSendComment = {
                if (newComment.isBlank()) return@PRDetailScreen
                isCommenting = true
                scope.launch {
                    apiClient.createPRComment(owner, repo, currentPR.number, newComment).onSuccess {
                        newComment = ""
                        commentsLoading = true
                        apiClient.listPRComments(owner, repo, currentPR.number).onSuccess {
                            comments = it
                        }
                        commentsLoading = false
                    }
                    isCommenting = false
                }
            },
            onBack = {
                selectedPR = null
                comments = emptyList()
            },
            repoFullName = repoFullName
        )
        return
    }

    if (showCreatePR) {
        CreatePRScreen(
            title = createTitle,
            onTitleChange = { createTitle = it },
            body = createBody,
            onBodyChange = { createBody = it },
            headBranch = createHead,
            onHeadBranchChange = { createHead = it },
            baseBranch = createBase,
            onBaseBranchChange = { createBase = it },
            isCreating = isCreating,
            onCreate = {
                if (createTitle.isBlank() || createHead.isBlank() || createBase.isBlank()) {
                    Toast.makeText(context, "请填写标题和分支名", Toast.LENGTH_SHORT).show()
                    return@CreatePRScreen
                }
                isCreating = true
                scope.launch {
                    apiClient.createPullRequest(owner, repo, createTitle, createBody, createHead, createBase).onSuccess {
                        createTitle = ""
                        createBody = ""
                        createHead = ""
                        createBase = ""
                        showCreatePR = false
                        loadPRs()
                        Toast.makeText(context, "PR 已创建", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(context, "创建失败: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                    isCreating = false
                }
            },
            onBack = { showCreatePR = false },
            repoFullName = repoFullName
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pull Requests - $repoFullName", fontSize = 16.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { loadPRs() }) {
                        Icon(Icons.Default.Refresh, "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CxSurface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreatePR = true }) {
                Icon(Icons.Default.Add, "创建 PR")
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
                    onClick = { filterState = "open"; loadPRs("open") },
                    label = { Text("开启的") }
                )
                FilterChip(
                    selected = filterState == "closed",
                    onClick = { filterState = "closed"; loadPRs("closed") },
                    label = { Text("已关闭") }
                )
                FilterChip(
                    selected = filterState == "all",
                    onClick = { filterState = "all"; loadPRs("all") },
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
            } else if (prs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无 Pull Request", color = CxTextSecondary)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(prs) { pr ->
                        PRCard(
                            pr = pr,
                            onClick = {
                                selectedPR = pr
                                commentsLoading = true
                                scope.launch {
                                    apiClient.listPRComments(owner, repo, pr.number).onSuccess {
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
private fun PRCard(
    pr: GitHubApiClient.PullRequest,
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
                    color = if (pr.state == "open") CxOnline.copy(alpha = 0.15f)
                    else CxError.copy(alpha = 0.15f)
                ) {
                    Icon(
                        if (pr.state == "open") Icons.Default.AccountTree else Icons.Default.Close,
                        null,
                        modifier = Modifier.size(20.dp).padding(2.dp),
                        tint = if (pr.state == "open") CxOnline else CxError
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("PR #${pr.number}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(pr.title, fontSize = 14.sp, maxLines = 2)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pr.userLogin, fontSize = 11.sp, color = CxTextSecondary)
                Text("${pr.headBranch} → ${pr.baseBranch}", fontSize = 11.sp, color = CxPrimary)
                Text(pr.createdAt.take(10), fontSize = 11.sp, color = CxTextSecondary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PRDetailScreen(
    pr: GitHubApiClient.PullRequest,
    comments: List<GitHubApiClient.PRComment>,
    commentsLoading: Boolean,
    newComment: String,
    isCommenting: Boolean,
    onNewCommentChange: (String) -> Unit,
    onSendComment: () -> Unit,
    onBack: () -> Unit,
    repoFullName: String
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PR #${pr.number}", fontSize = 16.sp) },
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(pr.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("by ${pr.userLogin}", fontSize = 12.sp, color = CxTextSecondary)
                            Text(pr.createdAt.take(10), fontSize = 12.sp, color = CxTextSecondary)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = {},
                                label = { Text(pr.state) },
                                leadingIcon = {
                                    Icon(
                                        if (pr.state == "open") Icons.Default.AccountTree else Icons.Default.Close,
                                        null, modifier = Modifier.size(14.dp)
                                    )
                                }
                            )
                            AssistChip(
                                onClick = {},
                                label = { Text("${pr.headBranch} → ${pr.baseBranch}") }
                            )
                        }
                        if (pr.body.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(pr.body, fontSize = 13.sp)
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
private fun CreatePRScreen(
    title: String,
    onTitleChange: (String) -> Unit,
    body: String,
    onBodyChange: (String) -> Unit,
    headBranch: String,
    onHeadBranchChange: (String) -> Unit,
    baseBranch: String,
    onBaseBranchChange: (String) -> Unit,
    isCreating: Boolean,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    repoFullName: String
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("创建 Pull Request", fontSize = 16.sp) },
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
                placeholder = { Text("PR 标题...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isCreating
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = headBranch,
                    onValueChange = onHeadBranchChange,
                    label = { Text("来源分支") },
                    placeholder = { Text("feature/xxx") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !isCreating
                )
                OutlinedTextField(
                    value = baseBranch,
                    onValueChange = onBaseBranchChange,
                    label = { Text("目标分支") },
                    placeholder = { Text("main") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    enabled = !isCreating
                )
            }

            OutlinedTextField(
                value = body,
                onValueChange = onBodyChange,
                label = { Text("描述") },
                placeholder = { Text("PR 描述...") },
                modifier = Modifier.fillMaxWidth().height(200.dp),
                maxLines = 10,
                enabled = !isCreating
            )

            Button(
                onClick = onCreate,
                enabled = !isCreating && title.isNotBlank() && headBranch.isNotBlank() && baseBranch.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isCreating) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (isCreating) "创建中..." else "创建 Pull Request")
            }
        }
    }
}
