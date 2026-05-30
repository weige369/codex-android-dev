package com.codex.android.codex.github

import android.content.Context
import android.util.Log
import com.codex.android.data.preferences.GitHubAuthPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub API 客户端。
 *
 * 封装 GitHub REST API v3 调用：
 * - 用户信息获取
 * - 仓库列表浏览
 * - 仓库搜索
 * - 仓库 clone/import
 * - 文件内容读写
 */
class GitHubApiClient(private val context: Context) {

    companion object {
        private const val TAG = "GitHubApiClient"
        private const val API_BASE = "https://api.github.com"
        private const val GITHUB_BASE = "https://github.com"
    }

    private val authPrefs = GitHubAuthPreferences.getInstance(context)

    data class GitHubRepo(
        val id: Long,
        val name: String,
        val fullName: String,
        val description: String,
        val language: String?,
        val stars: Int,
        val forks: Int,
        val isPrivate: Boolean,
        val isFork: Boolean,
        val cloneUrl: String,
        val sshUrl: String,
        val defaultBranch: String,
        val updatedAt: String,
        val ownerAvatar: String,
        val ownerLogin: String
    )

    data class GitHubUser(
        val login: String,
        val name: String?,
        val avatarUrl: String,
        val bio: String?,
        val publicRepos: Int,
        val followers: Int,
        val following: Int
    )

    data class ImportResult(
        val success: Boolean,
        val message: String,
        var repoPath: String? = null
    )

    /**
     * 获取当前认证用户信息
     */
    suspend fun getCurrentUser(): Result<GitHubUser> = withContext(Dispatchers.IO) {
        apiGetObject("/user") { json ->
            GitHubUser(
                login = json.getString("login"),
                name = json.optString("name"),
                avatarUrl = json.getString("avatar_url"),
                bio = json.optString("bio"),
                publicRepos = json.getInt("public_repos"),
                followers = json.getInt("followers"),
                following = json.getInt("following")
            )
        }
    }

    /**
     * 获取用户仓库列表（支持分页）
     */
    suspend fun getUserRepos(page: Int = 1, perPage: Int = 30): Result<List<GitHubRepo>> =
        withContext(Dispatchers.IO) {
            apiGetArray("/user/repos?page=$page&per_page=$perPage&sort=updated&direction=desc") { jsonArray ->
                parseRepoList(jsonArray)
            }
        }

    /**
     * 搜索仓库
     */
    suspend fun searchRepos(query: String, page: Int = 1): Result<List<GitHubRepo>> =
        withContext(Dispatchers.IO) {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            apiGetObject("/search/repositories?q=$encoded&page=$page&per_page=20") { json ->
                parseRepoList(json.getJSONArray("items"))
            }
        }

    /**
     * 获取仓库内容（文件列表）
     */
    suspend fun getRepoContents(owner: String, repo: String, path: String = ""): Result<List<RepoFile>> =
        withContext(Dispatchers.IO) {
            try {
                val result = apiGetObject("/repos/$owner/$repo/contents/$path") { json -> json }
                if (result.isSuccess) {
                    val obj = result.getOrThrow()
                    Result.success(listOf(parseFileItem(obj)))
                } else {
                    // Try as array
                    apiGetArray("/repos/$owner/$repo/contents/$path") { arr ->
                        parseFileList(arr)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 获取文件内容
     */
    suspend fun getFileContent(owner: String, repo: String, path: String): Result<String> =
        withContext(Dispatchers.IO) {
            apiGetObject("/repos/$owner/$repo/contents/$path") { json ->
                val content = json.optString("content", "")
                if (content.isNotBlank()) {
                    try {
                        val decoded = android.util.Base64.decode(content, android.util.Base64.DEFAULT)
                        String(decoded, Charsets.UTF_8)
                    } catch (e: Exception) {
                        content
                    }
                } else {
                    ""
                }
            }
        }

    /**
     * 导入仓库到本地工作区
     */
    suspend fun importRepo(
        repoUrl: String,
        targetDir: java.io.File,
        onProgress: ((String) -> Unit)? = null
    ): ImportResult {
        return withContext(Dispatchers.IO) {
            try {
                // 解析仓库 URL
                val repoInfo = parseRepoUrl(repoUrl)
                if (repoInfo == null) {
                    return@withContext ImportResult(false, "无效的仓库 URL: $repoUrl")
                }

                val (owner, repoName) = repoInfo
                onProgress?.invoke("正在获取仓库信息: $owner/$repoName")

                // 获取默认分支信息
                val apiResult = apiGetObject("/repos/$owner/$repoName") { json ->
                    json.optString("default_branch", "main")
                }

                val defaultBranch = apiResult.getOrElse { "main" }
                val cloneUrl = "https://github.com/$owner/$repoName.git"

                // 创建目标目录
                val repoDir = java.io.File(targetDir, repoName)
                if (repoDir.exists()) {
                    return@withContext ImportResult(false, "仓库 '$repoName' 已存在于工作区")
                }
                repoDir.mkdirs()

                onProgress?.invoke("正在下载仓库: $owner/$repoName ($defaultBranch)")

                // 下载仓库内容（通过 API 递归拉取）
                val downloadSuccess = downloadRepoContents(owner, repoName, "", repoDir, onProgress)

                if (downloadSuccess) {
                    onProgress?.invoke("✅ 仓库导入完成: $owner/$repoName")
                    ImportResult(true, "导入成功", repoDir.absolutePath)
                } else {
                    repoDir.deleteRecursively()
                    ImportResult(false, "下载仓库内容失败")
                }

            } catch (e: Exception) {
                Log.e(TAG, "导入仓库失败", e)
                ImportResult(false, "导入失败: ${e.message}")
            }
        }
    }

    /**
     * 递归下载仓库内容
     */
    private suspend fun downloadRepoContents(
        owner: String,
        repo: String,
        path: String,
        localDir: java.io.File,
        onProgress: ((String) -> Unit)?
    ): Boolean {
        return try {
            val contentsResult = getRepoContents(owner, repo, path)
            if (contentsResult.isFailure) return false

            contentsResult.getOrThrow().forEach { file ->
                if (file.type == "dir") {
                    val subDir = java.io.File(localDir, file.name)
                    subDir.mkdirs()
                    downloadRepoContents(owner, repo, file.path, subDir, onProgress)
                } else {
                    onProgress?.invoke("下载: ${file.path}")
                    val contentResult = getFileContent(owner, repo, file.path)
                    if (contentResult.isSuccess) {
                        val targetFile = java.io.File(localDir, file.name)
                        targetFile.writeText(contentResult.getOrThrow())
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "下载仓库内容出错", e)
            false
        }
    }

    data class RepoFile(
        val name: String,
        val path: String,
        val type: String, // "file" or "dir"
        val size: Long,
        val sha: String
    )


    // ========== Pull Request API ==========

    data class PullRequest(
        val number: Int,
        val title: String,
        val body: String,
        val state: String,
        val htmlUrl: String,
        val createdAt: String,
        val updatedAt: String,
        val userLogin: String,
        val userAvatar: String,
        val headBranch: String,
        val baseBranch: String,
        val isDraft: Boolean,
        val mergeable: Boolean?,
        val labels: List<String>
    )

    data class PRComment(
        val id: Long,
        val body: String,
        val userLogin: String,
        val userAvatar: String,
        val createdAt: String,
        val path: String?,
        val line: Int?
    )

    /**
     * 获取仓库的 Pull Request 列表
     */
    suspend fun listPullRequests(owner: String, repo: String, state: String = "open"): Result<List<PullRequest>> =
        withContext(Dispatchers.IO) {
            apiGetArray("/repos/$owner/$repo/pulls?state=$state&per_page=30") { arr ->
                (0 until arr.length()).map { i ->
                    val item = arr.getJSONObject(i)
                    val _n = item.optJSONArray("labels")?.length() ?: 0
                        val labels = (0 until _n).map { li ->
                        item.getJSONArray("labels").getJSONObject(li).getString("name")
                    }
                    PullRequest(
                        number = item.getInt("number"),
                        title = item.getString("title"),
                        body = item.optString("body", ""),
                        state = item.getString("state"),
                        htmlUrl = item.getString("html_url"),
                        createdAt = item.getString("created_at"),
                        updatedAt = item.getString("updated_at"),
                        userLogin = item.getJSONObject("user").getString("login"),
                        userAvatar = item.getJSONObject("user").getString("avatar_url"),
                        headBranch = item.getJSONObject("head").getString("ref"),
                        baseBranch = item.getJSONObject("base").getString("ref"),
                        isDraft = item.optBoolean("draft", false),
                        mergeable = if (item.has("mergeable") && !item.isNull("mergeable")) item.getBoolean("mergeable") else null,
                        labels = labels
                    )
                }
            }
        }

    /**
     * 获取单个 Pull Request 详情
     */
    suspend fun getPullRequest(owner: String, repo: String, number: Int): Result<PullRequest> =
        withContext(Dispatchers.IO) {
            apiGetObject("/repos/$owner/$repo/pulls/$number") { item ->
                val _n = item.optJSONArray("labels")?.length() ?: 0
                        val labels = (0 until _n).map { li ->
                    item.getJSONArray("labels").getJSONObject(li).getString("name")
                }
                PullRequest(
                    number = item.getInt("number"),
                    title = item.getString("title"),
                    body = item.optString("body", ""),
                    state = item.getString("state"),
                    htmlUrl = item.getString("html_url"),
                    createdAt = item.getString("created_at"),
                    updatedAt = item.getString("updated_at"),
                    userLogin = item.getJSONObject("user").getString("login"),
                    userAvatar = item.getJSONObject("user").getString("avatar_url"),
                    headBranch = item.getJSONObject("head").getString("ref"),
                    baseBranch = item.getJSONObject("base").getString("ref"),
                    isDraft = item.optBoolean("draft", false),
                    mergeable = if (item.has("mergeable") && !item.isNull("mergeable")) item.getBoolean("mergeable") else null,
                    labels = labels
                )
            }
        }

    /**
     * 创建 Pull Request
     */
    suspend fun createPullRequest(owner: String, repo: String, title: String, body: String, head: String, base: String): Result<PullRequest> =
        withContext(Dispatchers.IO) {
            apiPost("/repos/$owner/$repo/pulls",
                JSONObject().apply {
                    put("title", title)
                    put("body", body)
                    put("head", head)
                    put("base", base)
                }
            ) { item ->
                val _n = item.optJSONArray("labels")?.length() ?: 0
                        val labels = (0 until _n).map { li ->
                    item.getJSONArray("labels").getJSONObject(li).getString("name")
                }
                PullRequest(
                    number = item.getInt("number"),
                    title = item.getString("title"),
                    body = item.optString("body", ""),
                    state = item.getString("state"),
                    htmlUrl = item.getString("html_url"),
                    createdAt = item.getString("created_at"),
                    updatedAt = item.getString("updated_at"),
                    userLogin = item.getJSONObject("user").getString("login"),
                    userAvatar = item.getJSONObject("user").getString("avatar_url"),
                    headBranch = item.getJSONObject("head").getString("ref"),
                    baseBranch = item.getJSONObject("base").getString("ref"),
                    isDraft = item.optBoolean("draft", false),
                    mergeable = null,
                    labels = labels
                )
            }
        }

    /**
     * 获取 PR 评论列表
     */
    suspend fun listPRComments(owner: String, repo: String, number: Int): Result<List<PRComment>> =
        withContext(Dispatchers.IO) {
            apiGetArray("/repos/$owner/$repo/pulls/$number/comments") { arr ->
                (0 until arr.length()).map { i ->
                    val item = arr.getJSONObject(i)
                    PRComment(
                        id = item.getLong("id"),
                        body = item.getString("body"),
                        userLogin = item.getJSONObject("user").getString("login"),
                        userAvatar = item.getJSONObject("user").getString("avatar_url"),
                        createdAt = item.getString("created_at"),
                        path = item.optString("path"),
                        line = if (item.has("line") && !item.isNull("line")) item.getInt("line") else null
                    )
                }
            }
        }

    /**
     * 创建 PR 评论
     */
    suspend fun createPRComment(owner: String, repo: String, number: Int, body: String): Result<PRComment> =
        withContext(Dispatchers.IO) {
            apiPost("/repos/$owner/$repo/pulls/$number/comments",
                JSONObject().apply { put("body", body) }
            ) { item ->
                PRComment(
                    id = item.getLong("id"),
                    body = item.getString("body"),
                    userLogin = item.getJSONObject("user").getString("login"),
                    userAvatar = item.getJSONObject("user").getString("avatar_url"),
                    createdAt = item.getString("created_at"),
                    path = item.optString("path"),
                    line = if (item.has("line") && !item.isNull("line")) item.getInt("line") else null
                )
            }
        }

    // ========== Issue API ==========

    data class Issue(
        val number: Int,
        val title: String,
        val body: String,
        val state: String,
        val htmlUrl: String,
        val createdAt: String,
        val updatedAt: String,
        val userLogin: String,
        val userAvatar: String,
        val comments: Int,
        val labels: List<String>,
        val assignees: List<String>
    )

    data class IssueComment(
        val id: Long,
        val body: String,
        val userLogin: String,
        val userAvatar: String,
        val createdAt: String
    )

    /**
     * 获取仓库 Issue 列表
     */
    suspend fun listIssues(owner: String, repo: String, state: String = "open"): Result<List<Issue>> =
        withContext(Dispatchers.IO) {
            apiGetArray("/repos/$owner/$repo/issues?state=$state&per_page=30") { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val item = arr.getJSONObject(i)
                    // Filter out PRs (GitHub returns PRs in issue list too)
                    if (item.has("pull_request")) return@mapNotNull null
                    val _n = item.optJSONArray("labels")?.length() ?: 0
                        val labels = (0 until _n).map { li ->
                        item.getJSONArray("labels").getJSONObject(li).getString("name")
                    }
                    val _n2 = item.optJSONArray("assignees")?.length() ?: 0
                        val assignees = (0 until _n2).map { ai ->
                        item.getJSONArray("assignees").getJSONObject(ai).getString("login")
                    }
                    Issue(
                        number = item.getInt("number"),
                        title = item.getString("title"),
                        body = item.optString("body", ""),
                        state = item.getString("state"),
                        htmlUrl = item.getString("html_url"),
                        createdAt = item.getString("created_at"),
                        updatedAt = item.getString("updated_at"),
                        userLogin = item.getJSONObject("user").getString("login"),
                        userAvatar = item.getJSONObject("user").getString("avatar_url"),
                        comments = item.getInt("comments"),
                        labels = labels,
                        assignees = assignees
                    )
                }
            }
        }

    /**
     * 获取单个 Issue 详情
     */
    suspend fun getIssue(owner: String, repo: String, number: Int): Result<Issue> =
        withContext(Dispatchers.IO) {
            apiGetObject("/repos/$owner/$repo/issues/$number") { item ->
                val _n = item.optJSONArray("labels")?.length() ?: 0
                        val labels = (0 until _n).map { li ->
                    item.getJSONArray("labels").getJSONObject(li).getString("name")
                }
                val _n2 = item.optJSONArray("assignees")?.length() ?: 0
                        val assignees = (0 until _n2).map { ai ->
                    item.getJSONArray("assignees").getJSONObject(ai).getString("login")
                }
                Issue(
                    number = item.getInt("number"),
                    title = item.getString("title"),
                    body = item.optString("body", ""),
                    state = item.getString("state"),
                    htmlUrl = item.getString("html_url"),
                    createdAt = item.getString("created_at"),
                    updatedAt = item.getString("updated_at"),
                    userLogin = item.getJSONObject("user").getString("login"),
                    userAvatar = item.getJSONObject("user").getString("avatar_url"),
                    comments = item.getInt("comments"),
                    labels = labels,
                    assignees = assignees
                )
            }
        }

    /**
     * 创建 Issue
     */
    suspend fun createIssue(owner: String, repo: String, title: String, body: String, labels: List<String> = emptyList()): Result<Issue> =
        withContext(Dispatchers.IO) {
            apiPost("/repos/$owner/$repo/issues",
                JSONObject().apply {
                    put("title", title)
                    put("body", body)
                    if (labels.isNotEmpty()) put("labels", JSONArray(labels))
                }
            ) { item ->
                val _n = item.optJSONArray("labels")?.length() ?: 0
                    val labelList = (0 until _n).map { li ->
                    item.getJSONArray("labels").getJSONObject(li).getString("name")
                }
                Issue(
                    number = item.getInt("number"),
                    title = item.getString("title"),
                    body = item.optString("body", ""),
                    state = item.getString("state"),
                    htmlUrl = item.getString("html_url"),
                    createdAt = item.getString("created_at"),
                    updatedAt = item.getString("updated_at"),
                    userLogin = item.getJSONObject("user").getString("login"),
                    userAvatar = item.getJSONObject("user").getString("avatar_url"),
                    comments = item.getInt("comments"),
                    labels = labelList,
                    assignees = emptyList()
                )
            }
        }

    /**
     * 获取 Issue 评论列表
     */
    suspend fun listIssueComments(owner: String, repo: String, number: Int): Result<List<IssueComment>> =
        withContext(Dispatchers.IO) {
            apiGetArray("/repos/$owner/$repo/issues/$number/comments") { arr ->
                (0 until arr.length()).map { i ->
                    val item = arr.getJSONObject(i)
                    IssueComment(
                        id = item.getLong("id"),
                        body = item.getString("body"),
                        userLogin = item.getJSONObject("user").getString("login"),
                        userAvatar = item.getJSONObject("user").getString("avatar_url"),
                        createdAt = item.getString("created_at")
                    )
                }
            }
        }

    /**
     * 创建 Issue 评论
     */
    suspend fun createIssueComment(owner: String, repo: String, number: Int, body: String): Result<IssueComment> =
        withContext(Dispatchers.IO) {
            apiPost("/repos/$owner/$repo/issues/$number/comments",
                JSONObject().apply { put("body", body) }
            ) { item ->
                IssueComment(
                    id = item.getLong("id"),
                    body = item.getString("body"),
                    userLogin = item.getJSONObject("user").getString("login"),
                    userAvatar = item.getJSONObject("user").getString("avatar_url"),
                    createdAt = item.getString("created_at")
                )
            }
        }

    /**
     * 关闭 Issue
     */
    suspend fun closeIssue(owner: String, repo: String, number: Int): Result<Issue> =
        withContext(Dispatchers.IO) {
            apiPatch("/repos/$owner/$repo/issues/$number",
                JSONObject().apply { put("state", "closed") }
            ) { item ->
                val _n = item.optJSONArray("labels")?.length() ?: 0
                        val labels = (0 until _n).map { li ->
                    item.getJSONArray("labels").getJSONObject(li).getString("name")
                }
                Issue(
                    number = item.getInt("number"),
                    title = item.getString("title"),
                    body = item.optString("body", ""),
                    state = item.getString("state"),
                    htmlUrl = item.getString("html_url"),
                    createdAt = item.getString("created_at"),
                    updatedAt = item.getString("updated_at"),
                    userLogin = item.getJSONObject("user").getString("login"),
                    userAvatar = item.getJSONObject("user").getString("avatar_url"),
                    comments = item.getInt("comments"),
                    labels = labels,
                    assignees = emptyList()
                )
            }
        }

    // ========== Repo / Git 信息 API ==========

    data class BranchInfo(
        val name: String,
        val sha: String,
        val isDefault: Boolean
    )

    data class CommitInfo(
        val sha: String,
        val message: String,
        val authorName: String,
        val authorEmail: String,
        val authorDate: String,
        val htmlUrl: String
    )

    /**
     * 获取仓库分支列表
     */
    suspend fun listBranches(owner: String, repo: String): Result<List<BranchInfo>> =
        withContext(Dispatchers.IO) {
            apiGetArray("/repos/$owner/$repo/branches?per_page=100") { arr ->
                (0 until arr.length()).map { i ->
                    val item = arr.getJSONObject(i)
                    BranchInfo(
                        name = item.getString("name"),
                        sha = item.getJSONObject("commit").getString("sha"),
                        isDefault = false
                    )
                }
            }
        }

    /**
     * 获取仓库最近提交
     */
    suspend fun listCommits(owner: String, repo: String, branch: String = "main"): Result<List<CommitInfo>> =
        withContext(Dispatchers.IO) {
            apiGetArray("/repos/$owner/$repo/commits?sha=$branch&per_page=20") { arr ->
                (0 until arr.length()).map { i ->
                    val item = arr.getJSONObject(i)
                    val commit = item.getJSONObject("commit")
                    val author = commit.getJSONObject("author")
                    CommitInfo(
                        sha = item.getString("sha"),
                        message = commit.getString("message"),
                        authorName = author.getString("name"),
                        authorEmail = author.getString("email"),
                        authorDate = author.getString("date"),
                        htmlUrl = commit.optString("html_url", "")
                    )
                }
            }
        }

    /** 新增 API 方法：POST */
    private suspend fun <T> apiPost(endpoint: String, body: JSONObject, parser: (JSONObject) -> T): Result<T> {
        return apiWithBody("POST", endpoint, body, parser)
    }

    /** 新增 API 方法：PATCH */
    private suspend fun <T> apiPatch(endpoint: String, body: JSONObject, parser: (JSONObject) -> T): Result<T> {
        return apiWithBody("PATCH", endpoint, body, parser)
    }

    private suspend fun <T> apiWithBody(method: String, endpoint: String, body: JSONObject, parser: (JSONObject) -> T): Result<T> {
        return try {
            val tokenResult = getToken()
            if (tokenResult.isFailure) return Result.failure(tokenResult.exceptionOrNull()!!)

            val url = URL("$API_BASE$endpoint")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer ${tokenResult.getOrThrow()}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000

            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(body.toString())
                writer.flush()
            }

            val code = conn.responseCode
            if (code in 200..299) {
                val responseBody = readStream(conn.inputStream)
                val json = JSONObject(responseBody)
                Result.success(parser(json))
            } else {
                val error = readStream(conn.errorStream)
                Result.failure(Exception("GitHub API 错误 ($code): $error"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    // ========== 内部方法 ==========

    private suspend fun <T> apiGetObject(endpoint: String, parser: (JSONObject) -> T): Result<T> {
        return try {
            val tokenResult = getToken()
            if (tokenResult.isFailure) return Result.failure(tokenResult.exceptionOrNull()!!)

            val url = URL("$API_BASE$endpoint")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer ${tokenResult.getOrThrow()}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.connectTimeout = 15000
            conn.readTimeout = 30000

            val code = conn.responseCode
            if (code == 200) {
                val body = readStream(conn.inputStream)
                val json = JSONObject(body)
                Result.success(parser(json))
            } else if (code == 204) {
                Result.success(parser(JSONObject()))
            } else {
                val error = readStream(conn.errorStream)
                Result.failure(Exception("GitHub API 错误 ($code): $error"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun <T> apiGetArray(endpoint: String, parser: (JSONArray) -> T): Result<T> {
        return try {
            val tokenResult = getToken()
            if (tokenResult.isFailure) return Result.failure(tokenResult.exceptionOrNull()!!)

            val url = URL("$API_BASE$endpoint")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer ${tokenResult.getOrThrow()}")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.connectTimeout = 15000
            conn.readTimeout = 30000

            val code = conn.responseCode
            if (code == 200) {
                val body = readStream(conn.inputStream)
                val json = JSONArray(body)
                Result.success(parser(json))
            } else {
                val error = readStream(conn.errorStream)
                Result.failure(Exception("GitHub API 错误 ($code): $error"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun getToken(): Result<String> {
        val token = authPrefs.getCurrentAccessToken()
        return if (token != null) {
            Result.success(token)
        } else {
            Result.failure(Exception("未登录 GitHub，请先进行 OAuth 认证"))
        }
    }

    private fun parseRepoList(jsonArray: JSONArray): List<GitHubRepo> {
        return (0 until jsonArray.length()).map { i ->
            val item = jsonArray.getJSONObject(i)
            GitHubRepo(
                id = item.getLong("id"),
                name = item.getString("name"),
                fullName = item.getString("full_name"),
                description = item.optString("description", ""),
                language = item.optString("language"),
                stars = item.getInt("stargazers_count"),
                forks = item.getInt("forks_count"),
                isPrivate = item.getBoolean("private"),
                isFork = item.getBoolean("fork"),
                cloneUrl = item.getString("clone_url"),
                sshUrl = item.getString("ssh_url"),
                defaultBranch = item.optString("default_branch", "main"),
                updatedAt = item.getString("updated_at"),
                ownerAvatar = item.getJSONObject("owner").getString("avatar_url"),
                ownerLogin = item.getJSONObject("owner").getString("login")
            )
        }
    }

    private fun parseFileList(jsonArray: JSONArray): List<RepoFile> {
        return (0 until jsonArray.length()).map { i ->
            parseFileItem(jsonArray.getJSONObject(i))
        }
    }

    private fun parseFileItem(json: JSONObject): RepoFile {
        return RepoFile(
            name = json.getString("name"),
            path = json.getString("path"),
            type = json.getString("type"),
            size = json.optLong("size", 0),
            sha = json.getString("sha")
        )
    }

    private fun parseRepoUrl(url: String): Pair<String, String>? {
        // Support formats:
        // https://github.com/owner/repo
        // https://github.com/owner/repo.git
        // git@github.com:owner/repo.git
        // owner/repo
        val cleanUrl = url.trim().removeSuffix(".git")

        return when {
            cleanUrl.contains("github.com") -> {
                val parts = cleanUrl.substringAfter("github.com/").split("/")
                if (parts.size >= 2) Pair(parts[0], parts[1]) else null
            }
            cleanUrl.contains("github.com:") -> {
                val parts = cleanUrl.substringAfter("github.com:").split("/")
                if (parts.size >= 2) Pair(parts[0], parts[1]) else null
            }
            cleanUrl.contains("/") -> {
                val parts = cleanUrl.split("/")
                if (parts.size == 2) Pair(parts[0], parts[1]) else null
            }
            else -> null
        }
    }

    private fun readStream(stream: java.io.InputStream): String {
        return BufferedReader(InputStreamReader(stream)).readText()
    }
}
