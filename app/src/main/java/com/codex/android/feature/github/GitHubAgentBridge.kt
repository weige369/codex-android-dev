package com.codex.android.feature.github

import com.codex.android.codex.github.GitHubApiClient
import com.codex.android.provider.CodexAgentProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * GitHub Agent Bridge — Agent 与 GitHub API 之间的核心桥接层。
 *
 * 五大能力：
 * 1. 智能 Code Review — Agent 分析 PR diff，识别问题并生成审查意见
 * 2. 自动化 Issue 建议 — Agent 扫描代码生成 Issue 草稿
 * 3. PR 描述生成 — Agent 根据 git diff 自动撰写 PR 标题/正文
 * 4. GitHub Actions 感知 — 查询工作流状态
 * 5. 自然语言 Git 操作 — 将用户意图转换为 Git 命令
 */

class GitHubAgentBridge(
    private val apiClient: GitHubApiClient,
    private val agentProvider: CodexAgentProvider,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val authTokenProvider: suspend () -> String? = { null }
) {
    companion object {
        private const val TAG = "GitHubAgentBridge"
    }

    // ── State ────────────────────────────────────────────────────

    enum class BridgeState { IDLE, REVIEWING, GENERATING, ERROR }
    private val _state = MutableStateFlow(BridgeState.IDLE)
    val state: StateFlow<BridgeState> = _state.asStateFlow()

    private val _lastReviewResult = MutableStateFlow<CodeReviewResult?>(null)
    val lastReviewResult: StateFlow<CodeReviewResult?> = _lastReviewResult.asStateFlow()

    private val _lastIssueDraft = MutableStateFlow<IssueDraft?>(null)
    val lastIssueDraft: StateFlow<IssueDraft?> = _lastIssueDraft.asStateFlow()

    // ── Data Classes ─────────────────────────────────────────────

    /** Code Review 结果 */
    data class CodeReviewResult(
        val prNumber: Int,
        val summary: String,
        val findings: List<ReviewFinding>,
        val suggestion: String,       // 可直接作为 PR comment 的完整文本
        val overallScore: Int         // 0-100
    )

    data class ReviewFinding(
        val severity: FindingSeverity,
        val file: String,
        val line: Int?,
        val message: String,
        val suggestion: String
    )

    enum class FindingSeverity {
        CRITICAL, // Bug / 安全漏洞
        WARNING,  // 性能 / 代码风格
        INFO      // 建议
    }

    /** Issue 草稿 */
    data class IssueDraft(
        val title: String,
        val body: String,
        val labels: List<String>,
        val source: String // 触发来源: "todo", "code_analysis", "bug_report"
    )

    /** Actions 工作流摘要 */
    data class WorkflowSummary(
        val name: String,
        val status: String,      // queued / in_progress / completed
        val conclusion: String?, // success / failure / cancelled
        val htmlUrl: String,
        val branch: String,
        val updatedAt: String
    )

    // ═══════════════════════════════════════════════════════════════
    // 1. 智能 Code Review
    // ═══════════════════════════════════════════════════════════════

    /**
     * 对指定 PR 执行 AI Code Review。
     *
     * 流程：
     * 1. 通过 API 获取 PR 的文件变更 diff
     * 2. 构造 Agent prompt（含 diff + review 指令）
     * 3. Agent 分析并返回结构化审查结果
     * 4. 解析为 CodeReviewResult
     */
    suspend fun reviewPullRequest(
        owner: String,
        repo: String,
        prNumber: Int,
        onProgress: ((String) -> Unit)? = null
    ): Result<CodeReviewResult> = withContext(Dispatchers.IO) {
        _state.value = BridgeState.REVIEWING
        try {
            // 1. 获取 PR 详情 + diff
            onProgress?.invoke("获取 PR #$prNumber 详情...")
            val prResult = apiClient.getPullRequest(owner, repo, prNumber)
            if (prResult.isFailure) return@withContext Result.failure(prResult.exceptionOrNull() ?: Exception("Failed to get PR"))
            val pr = prResult.getOrThrow()

            // 2. 获取 PR diff（通过 API）
            onProgress?.invoke("获取代码变更 diff...")
            val diff = fetchPRDiff(owner, repo, prNumber)
            if (diff == null) return@withContext Result.failure(Exception("无法获取 PR diff"))

            // 3. 构造 review prompt
            val prompt = buildCodeReviewPrompt(pr.title, pr.body, diff, pr.headBranch, pr.baseBranch)

            // 4. 发送给 Agent 分析
            onProgress?.invoke("Agent 正在分析代码变更...")
            val agentResponse = agentProvider.execute(prompt, stream = true)

            // 5. 解析 Agent 返回结果
            val result = parseCodeReviewResponse(prNumber, agentResponse)

            _lastReviewResult.value = result
            _state.value = BridgeState.IDLE
            onProgress?.invoke("Code Review 完成 — 评分: ${result.overallScore}/100")
            Result.success(result)

        } catch (e: Exception) {
            _state.value = BridgeState.ERROR
            Result.failure(e)
        }
    }

    /**
     * 将 Code Review 结果自动发布为 PR 评论
     */
    suspend fun postReviewComment(
        owner: String,
        repo: String,
        prNumber: Int,
        review: CodeReviewResult
    ): Result<GitHubApiClient.PRComment> {
        val commentBody = buildReviewCommentBody(review)
        return apiClient.createPRComment(owner, repo, prNumber, commentBody)
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. 自动化 Issue 建议
    // ═══════════════════════════════════════════════════════════════

    /**
     * 扫描本地代码中的 TODO/FIXME/HACK 标记，生成 Issue 建议。
     */
    suspend fun scanAndSuggestIssues(
        owner: String,
        repo: String,
        workspacePath: String
    ): Result<List<IssueDraft>> = withContext(Dispatchers.IO) {
        _state.value = BridgeState.GENERATING
        try {
            // 用 Agent 扫描代码中的问题标记
            val prompt = """
你是一个代码分析专家。请扫描以下工作目录中的代码，找出所有 TODO、FIXME、HACK 标记，
以及潜在的 Bug 或代码异味。

工作目录: $workspacePath
目标仓库: $owner/$repo

请以 JSON 数组格式返回，每个元素包含:
- title: Issue 标题
- body: Issue 详细描述
- labels: 标签数组 (如 ["bug", "enhancement"])
- source: 来源类型 ("todo" | "fixme" | "code_analysis")

请只返回 JSON 数组，不要包含其他内容。
""".trimIndent()

            val response = agentProvider.execute(prompt, stream = true)
            val drafts = parseIssueDrafts(response)

            _lastIssueDraft.value = drafts.firstOrNull()
            _state.value = BridgeState.IDLE
            Result.success(drafts)

        } catch (e: Exception) {
            _state.value = BridgeState.ERROR
            Result.failure(e)
        }
    }

    /**
     * 将 Issue 草稿发布到 GitHub
     */
    suspend fun publishIssue(owner: String, repo: String, draft: IssueDraft): Result<GitHubApiClient.Issue> {
        return apiClient.createIssue(owner, repo, draft.title, draft.body, draft.labels)
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. PR 描述自动生成
    // ═══════════════════════════════════════════════════════════════

    /**
     * 根据 git diff / 提交历史自动生成 PR 标题和正文。
     */
    suspend fun generatePRDescription(
        commitMessages: List<String>,
        diffSummary: String? = null
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        _state.value = BridgeState.GENERATING
        try {
            val commits = commitMessages.joinToString("\n") { "- $it" }
            val diffSection = if (diffSummary != null) "\n变更摘要:\n$diffSummary" else ""

            val prompt = """
你是一个 PR 撰写助手。根据以下 git 提交记录生成一个 Pull Request 描述。

提交记录:
$commits
$diffSection

请返回两行：
第一行: PR 标题（简洁，不超过 72 字符）
第二行: PR 正文（包含变更说明、测试方法、影响范围）

只返回标题和正文，不要其他内容。
""".trimIndent()

            val response = agentProvider.execute(prompt, stream = true)
            val lines = response.trim().lines()
            val title = lines.firstOrNull()?.trim() ?: "Update"
            val body = lines.drop(1).joinToString("\n").trim()

            _state.value = BridgeState.IDLE
            Result.success(Pair(title, body))

        } catch (e: Exception) {
            _state.value = BridgeState.ERROR
            Result.failure(e)
        }
    }

    /**
     * 自动创建 PR：生成描述 + 推送到 GitHub
     */
    suspend fun autoCreatePR(
        owner: String,
        repo: String,
        head: String,
        base: String = "main",
        commitMessages: List<String> = emptyList(),
        diffSummary: String? = null
    ): Result<GitHubApiClient.PullRequest> = withContext(Dispatchers.IO) {
        val descResult = generatePRDescription(commitMessages, diffSummary)
        if (descResult.isFailure) return@withContext Result.failure(descResult.exceptionOrNull() ?: Exception("Failed to generate PR description"))

        val (title, body) = descResult.getOrThrow()
        apiClient.createPullRequest(owner, repo, title, body, head, base)
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. GitHub Actions 状态查询
    // ═══════════════════════════════════════════════════════════════

    /**
     * 获取仓库最近的 Actions 工作流运行状态
     */
    suspend fun getWorkflowRuns(owner: String, repo: String): Result<List<WorkflowSummary>> {
        // 通过 GitHub API 获取 workflow runs
        // GET /repos/{owner}/{repo}/actions/runs
        // 复用已有的 apiGetArray 方法（需要扩展 GitHubApiClient）
        return Result.success(emptyList()) // 待 API 扩展
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. 自然语言 Git → 命令转换
    // ═══════════════════════════════════════════════════════════════

    /**
     * 将自然语言 Git 意图转换为实际 Git 命令。
     */
    suspend fun translateGitIntent(intent: String): Result<GitCommand> = withContext(Dispatchers.IO) {
        val prompt = """
你是一个 Git 命令翻译器。将用户的自然语言意图转换为具体的 Git 命令。

可用命令: status, log, diff, add, commit, push, pull, branch, checkout, merge, rebase, stash, reset, revert

用户意图: $intent

请以 JSON 格式返回:
{
  "command": "具体的 git 命令（不含 git 前缀）",
  "args": ["参数数组"],
  "explanation": "对用户的解释（中文）",
  "isDestructive": true/false,
  "confirmNeeded": true/false
}

只返回 JSON，不要其他内容。
""".trimIndent()

        val response = agentProvider.execute(prompt, stream = true)
        parseGitCommand(response)
    }

    data class GitCommand(
        val command: String,
        val args: List<String>,
        val explanation: String,
        val isDestructive: Boolean,
        val confirmNeeded: Boolean
    ) {
        fun toShellCommand(): String = "git $command ${args.joinToString(" ")}"
    }

    // ═══════════════════════════════════════════════════════════════
    // Internal Helpers
    // ═══════════════════════════════════════════════════════════════

    /**
     * 通过 GitHub API 获取 PR 的 diff 内容。
     * 使用 Accept: application/vnd.github.v3.diff 头
     */
    private suspend fun fetchPRDiff(owner: String, repo: String, prNumber: Int): String? {
        return apiClient.getPullRequestDiff(owner, repo, prNumber).getOrNull()
    }

    private fun buildCodeReviewPrompt(
        title: String,
        body: String,
        diff: String,
        head: String,
        base: String
    ): String {
        val truncatedDiff = if (diff.length > 8000) diff.take(8000) + "\n... (truncated)" else diff
        return """
你是一个资深代码审查专家。请审查以下 Pull Request 的代码变更。

PR 标题: $title
PR 描述: ${body.ifEmpty { "(无)" }}
源分支: $head → 目标分支: $base

## 代码变更 (diff):
```
$truncatedDiff
```

请以 JSON 格式返回审查结果:
{
  "summary": "一句话总结",
  "overallScore": 0-100,
  "findings": [
    {
      "severity": "CRITICAL|WARNING|INFO",
      "file": "文件名",
      "line": 行号或null,
      "message": "问题描述",
      "suggestion": "修改建议"
    }
  ],
  "suggestion": "完整的审查评论文本（Markdown格式）"
}

重点关注：Bug风险、安全漏洞、性能问题、代码风格。只返回JSON。
""".trimIndent()
    }

    private fun parseCodeReviewResponse(prNumber: Int, response: String): CodeReviewResult {
        return try {
            val json = org.json.JSONObject(response.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim())

            val findingsArr = json.getJSONArray("findings")
            val findings = (0 until findingsArr.length()).map { i ->
                val f = findingsArr.getJSONObject(i)
                ReviewFinding(
                    severity = try {
                        FindingSeverity.valueOf(f.getString("severity").uppercase())
                    } catch (e: Exception) { FindingSeverity.INFO },
                    file = f.optString("file", "unknown"),
                    line = if (f.has("line") && !f.isNull("line")) f.getInt("line") else null,
                    message = f.getString("message"),
                    suggestion = f.getString("suggestion")
                )
            }

            CodeReviewResult(
                prNumber = prNumber,
                summary = json.optString("summary", "Code review completed"),
                findings = findings,
                suggestion = json.optString("suggestion", ""),
                overallScore = json.optInt("overallScore", 70)
            )
        } catch (e: Exception) {
            // 解析失败时返回原始响应
            CodeReviewResult(
                prNumber = prNumber,
                summary = "解析失败，原始响应如下",
                findings = listOf(ReviewFinding(FindingSeverity.INFO, "", null, response, "")),
                suggestion = response,
                overallScore = 0
            )
        }
    }

    private fun parseIssueDrafts(response: String): List<IssueDraft> {
        return try {
            val clean = response.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val arr = org.json.JSONArray(clean)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val labelsArr = obj.optJSONArray("labels")
                val labels = if (labelsArr != null) {
                    (0 until labelsArr.length()).map { labelsArr.getString(it) }
                } else emptyList()
                IssueDraft(
                    title = obj.getString("title"),
                    body = obj.getString("body"),
                    labels = labels,
                    source = obj.optString("source", "code_analysis")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseGitCommand(response: String): GitCommand {
        return try {
            val clean = response.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val json = org.json.JSONObject(clean)
            val argsArr = json.getJSONArray("args")
            val args = (0 until argsArr.length()).map { argsArr.getString(it) }
            GitCommand(
                command = json.getString("command"),
                args = args,
                explanation = json.optString("explanation", ""),
                isDestructive = json.optBoolean("isDestructive", false),
                confirmNeeded = json.optBoolean("confirmNeeded", true)
            )
        } catch (e: Exception) {
            GitCommand("status", emptyList(), "无法解析意图，默认显示状态", false, false)
        }
    }

    private fun buildReviewCommentBody(review: CodeReviewResult): String {
        val sb = StringBuilder()
        sb.appendLine("## 🤖 AI Code Review — 评分: ${review.overallScore}/100")
        sb.appendLine()
        sb.appendLine(review.summary)
        sb.appendLine()
        if (review.findings.isNotEmpty()) {
            sb.appendLine("### 发现的问题")
            sb.appendLine()
            review.findings.forEach { f ->
                val icon = when (f.severity) {
                    FindingSeverity.CRITICAL -> "🔴"
                    FindingSeverity.WARNING -> "🟡"
                    FindingSeverity.INFO -> "🔵"
                }
                val loc = if (f.line != null) "`${f.file}:${f.line}`" else "`${f.file}`"
                sb.appendLine("$icon **${f.severity.name}** | $loc")
                sb.appendLine("> ${f.message}")
                sb.appendLine()
                if (f.suggestion.isNotBlank()) {
                    sb.appendLine("💡 ${f.suggestion}")
                    sb.appendLine()
                }
            }
        }
        return sb.toString()
    }

    fun destroy() {
        scope.cancel()
    }
}