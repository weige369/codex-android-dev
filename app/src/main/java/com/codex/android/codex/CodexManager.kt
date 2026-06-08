package com.codex.android.codex

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Codex CLI 管理器。
 *
 * 处理 Codex CLI 二进制文件的下载、验证、生命周期管理。
 * 二进制文件从 GitHub Releases 下载，解压后运行。
 */
class CodexManager(private val context: Context) {

    companion object {
        private const val TAG = "CodexManager"
        private const val CODEX_DIR = "codex"
        private const val CODEX_BINARY_NAME = "codex"

        /** 应用内置/已知可用的 Codex 版本。在线检查可发现更高版本并一键升级。 */
        const val CODEX_VERSION = "0.133.0"

        // GitHub 仓库
        private const val GITHUB_REPO = "openai/codex"
        // Gitee 镜像仓库（国内直连，需镜像同步对应 Release）
        private const val GITEE_REPO = "mirrors/codex"

        // GitHub Releases API（用于在线检查最新版本）
        private const val RELEASES_API =
            "https://api.github.com/repos/$GITHUB_REPO/releases?per_page=30"

        // 下载连接超时（毫秒）。缩短以便在不可达镜像间快速切换。
        private const val CONNECT_TIMEOUT_MS = 8_000

        /**
         * 当前设备 CPU 架构与对应的 Codex release 产物信息。
         * @param supported 是否存在可用的官方 musl 产物
         * @param abi Android ABI 名称（如 arm64-v8a）
         * @param rustTarget Rust 目标三元组（用于拼接产物名）
         */
        data class ArchInfo(val supported: Boolean, val abi: String, val rustTarget: String)

        /**
         * 探测当前设备 CPU 架构，返回对应的 Codex 产物信息。
         * Codex 官方仅提供 aarch64 / x86_64 的 linux-musl 产物，其它架构（如 32 位 armv7）不支持。
         */
        fun detectArch(): ArchInfo {
            val abis = android.os.Build.SUPPORTED_ABIS ?: emptyArray()
            return when {
                abis.contains("arm64-v8a") ->
                    ArchInfo(true, "arm64-v8a", "aarch64-unknown-linux-musl")
                abis.contains("x86_64") ->
                    ArchInfo(true, "x86_64", "x86_64-unknown-linux-musl")
                else ->
                    ArchInfo(false, abis.firstOrNull() ?: "unknown", "")
            }
        }

        private fun archiveName(arch: ArchInfo): String = "codex-${arch.rustTarget}.tar.gz"

        private fun releasePath(version: String, archive: String): String =
            "$GITHUB_REPO/releases/download/rust-v$version/$archive"

        /**
         * 为指定版本 + 架构构建全部下载镜像 URL（中国大陆友好，按可用性从高到低排列）。
         * 架构不支持时返回空列表。
         */
        fun buildMirrorUrls(version: String, arch: ArchInfo): List<String> {
            if (!arch.supported) return emptyList()
            val archive = archiveName(arch)
            val ghPath = releasePath(version, archive)
            val ghUrl = "https://github.com/$ghPath"
            val giteeUrl =
                "https://gitee.com/$GITEE_REPO/releases/download/rust-v$version/$archive"
            return listOf(
                // 国内 GitHub 加速代理（中国大陆可达，优先尝试）
                "https://ghfast.top/$ghUrl",
                "https://gh-proxy.com/$ghUrl",
                "https://ghproxy.net/$ghUrl",
                "https://mirror.ghproxy.com/$ghUrl",
                // Gitee Release 镜像（国内直连）
                giteeUrl,
                // GitHub 直连（海外网络 / 已配置代理时可用）
                ghUrl,
                // 旧版加速镜像（备用）
                "https://githubfast.com/$ghPath",
            )
        }

        /** 默认版本 + 当前设备架构的下载源（供诊断页探测）。 */
        fun getAllDownloadUrls(): List<String> = buildMirrorUrls(CODEX_VERSION, detectArch())

        /**
         * 比较两个语义化版本号（x.y.z）。a>b 返回正数，a<b 返回负数，相等返回 0。
         * 非法/缺失段按 0 处理。
         */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.trim().removePrefix("v").split('.', '-')
                .map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            val pa = parts(a); val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val x = pa.getOrElse(i) { 0 }
                val y = pb.getOrElse(i) { 0 }
                if (x != y) return x - y
            }
            return 0
        }
    }

    // 文件路径
    val codexDir: File get() = File(context.filesDir, CODEX_DIR)
    val codexBinary: File get() = File(codexDir, CODEX_BINARY_NAME)
    val archiveFile: File get() = File(codexDir, "$CODEX_BINARY_NAME.tar.gz")
    val workspaceDir: File get() = File(context.filesDir, "workspace")
    /** 记录已安装二进制的版本号 */
    private val versionFile: File get() = File(codexDir, ".installed_version")

    // 下载进度回调
    var onProgress: ((downloaded: Long, total: Long) -> Unit)? = null

    /**
     * 检查 Codex 二进制是否已安装
     */
    fun isInstalled(): Boolean {
        return codexBinary.exists() && codexBinary.canExecute() && codexBinary.length() > 10_000_000
    }

    /**
     * 验证二进制文件完整性
     */

    /** 手动导入结果，附带可展示给用户的具体原因 */
    data class ImportResult(val success: Boolean, val message: String)

    /**
     * 从本地文件导入 Codex 二进制（带完整性校验）。
     * 校验文件头（ELF）、文件大小，并对常见错误（误选压缩包等）给出明确提示。
     */
    fun importBinaryChecked(sourceFile: File): ImportResult {
        return try {
            if (!sourceFile.exists() || sourceFile.length() == 0L) {
                return ImportResult(false, "文件不存在或为空")
            }

            val header = ByteArray(4)
            FileInputStream(sourceFile).use { input ->
                if (input.read(header) < 4) {
                    return ImportResult(false, "文件过短，无法识别格式")
                }
            }

            // gzip 魔数 1F 8B —— 用户多半误选了未解压的 .tar.gz
            val isGzip = header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte()
            if (isGzip) {
                return ImportResult(
                    false,
                    "这是压缩包(.tar.gz)。请先解压，导入其中名为 codex 的可执行文件"
                )
            }

            // ELF 魔数 7F 45 4C 46
            val isElf = header[0] == 0x7F.toByte() &&
                header[1] == 0x45.toByte() &&
                header[2] == 0x4C.toByte() &&
                header[3] == 0x46.toByte()
            if (!isElf) {
                return ImportResult(false, "不是有效的 Linux ELF 可执行文件（文件头不匹配）")
            }

            if (sourceFile.length() < 10_000_000) {
                val mb = sourceFile.length() / 1024 / 1024
                return ImportResult(
                    false,
                    "文件过小（${mb}MB），Codex 二进制应大于 10MB，文件可能不完整"
                )
            }

            codexDir.mkdirs()
            sourceFile.inputStream().use { input ->
                codexBinary.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            codexBinary.setExecutable(true)
            val sizeMb = codexBinary.length() / 1024 / 1024
            Log.i(TAG, "导入 Codex 二进制: ${codexBinary.length()} bytes")
            ImportResult(true, "导入成功（${sizeMb}MB）")
        } catch (e: Exception) {
            Log.e(TAG, "导入失败", e)
            ImportResult(false, "导入异常: ${e.message ?: "未知错误"}")
        }
    }

    /**
     * 从本地文件导入 Codex 二进制（向后兼容包装）。
     * 用于在网络不可用时手动导入。
     */
    fun importBinary(sourceFile: File): Boolean = importBinaryChecked(sourceFile).success

    /**
     * 验证文件完整性（给定 SHA256 哈希）。
     * @param file 要验证的文件
     * @param expectedHash 期望的 SHA256 哈希（十六进制），传 null 则跳过校验
     * @return true 表示文件存在且（如提供哈希）校验通过
     */
    fun verifyFileIntegrity(file: File, expectedHash: String? = null): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        if (expectedHash == null) return true
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual.equals(expectedHash, ignoreCase = true)
        } catch (e: Exception) {
            Log.w(TAG, "完整性校验失败", e)
            false
        }
    }

    /**
     * 下载 Codex CLI（带进度回调，原子性写入）
     */
    suspend fun downloadWithProgress(
        version: String = CODEX_VERSION,
        onProgressCallback: ((progress: Long, total: Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        // 确保目录存在
        codexDir.mkdirs()

        val arch = detectArch()
        if (!arch.supported) {
            Log.e(TAG, "当前 CPU 架构不受支持: ${arch.abi}")
            return@withContext false
        }
        val urls = buildMirrorUrls(version, arch)
        val tempFile = File(codexDir, "codex.tar.gz.tmp")

        // 遍历所有镜像源，直到有一个成功
        for ((index, mirrorUrl) in urls.withIndex()) {
            try {
                Log.i(TAG, "尝试下载源 #${index + 1}: $mirrorUrl")
                tempFile.delete() // 清理可能残留的临时文件
                val success = downloadFromUrlToFile(mirrorUrl, tempFile, onProgressCallback)
                if (success) {
                    // 原子性重命名
                    if (!tempFile.renameTo(archiveFile)) {
                        tempFile.copyTo(archiveFile, overwrite = true)
                        tempFile.delete()
                    }
                    Log.i(TAG, "从源 #${index + 1} 下载成功")
                    return@withContext true
                }
            } catch (e: Exception) {
                Log.w(TAG, "源 #${index + 1} 下载失败: ${e.message}")
                tempFile.delete() // 清理失败的临时文件
            }
        }

        Log.e(TAG, "所有下载源均不可用")
        tempFile.delete()
        false
    }

    /**
     * 检查 Codex 二进制文件完整性（向后兼容）。
     */
    fun verifyBinary(): Boolean {
        if (!codexBinary.exists()) return false
        if (codexBinary.length() < 10_000_000) return false
        try {
            val header = ByteArray(4)
            RandomAccessFile(codexBinary, "r").use { raf ->
                raf.readFully(header)
            }
            return header[0] == 0x7F.toByte() &&
                   header[1] == 0x45.toByte() &&
                   header[2] == 0x4C.toByte() &&
                   header[3] == 0x46.toByte()
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * 从指定 URL 下载到指定文件（而非固定 archiveFile）。
     */
    private fun downloadFromUrlToFile(
        urlString: String,
        target: File,
        onProgressCallback: ((progress: Long, total: Long) -> Unit)? = null
    ): Boolean {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/octet-stream")
        connection.setRequestProperty("User-Agent", "Codex-Android/1.0")
        connection.connect()

        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("HTTP $responseCode for $urlString")
        }

        val contentLength = connection.contentLength.toLong()
        Log.i(TAG, "开始下载 (大小: ${contentLength / 1024 / 1024}MB)")

        connection.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead: Long = 0
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    onProgressCallback?.invoke(totalBytesRead, contentLength)
                }
            }
        }
        Log.i(TAG, "下载完成: ${target.length()} bytes")
        return true
    }

    /**
     * 从指定 URL 下载（向后兼容）。
     */
    private fun downloadFromUrl(
        urlString: String,
        onProgressCallback: ((progress: Long, total: Long) -> Unit)? = null
    ): Boolean {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/octet-stream")
        connection.setRequestProperty("User-Agent", "Codex-Android/1.0")

        connection.connect()

        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("HTTP $responseCode for $urlString")
        }

        val contentLength = connection.contentLength.toLong()
        Log.i(TAG, "开始下载 (大小: ${contentLength / 1024 / 1024}MB)")

        connection.inputStream.use { input ->
            FileOutputStream(archiveFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead: Long = 0

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    onProgressCallback?.invoke(totalBytesRead, contentLength)
                }
            }
        }

        Log.i(TAG, "下载完成: ${archiveFile.length()} bytes")
        return true
    }

    /**
     * 从备用地址下载
     */
    private fun downloadFromFallback(
        onProgressCallback: ((progress: Long, total: Long) -> Unit)?
    ): Boolean {
        Log.i(TAG, "尝试从备用地址下载...")

        // 创建占位文件提示用户手动下载
        codexBinary.createNewFile()
        val hint = getAllDownloadUrls().firstOrNull() ?: "（当前 CPU 架构无官方产物）"
        codexBinary.writeText(
            "Codex CLI 自动下载失败。\n" +
            "请手动下载: $hint\n" +
            "然后复制到: ${codexBinary.absolutePath}\n" +
            "并执行: chmod +x ${codexBinary.name}"
        )
        return false
    }

    /**
     * 解压下载的 Codex 二进制
     */
    suspend fun extractBinary(installedVersion: String = CODEX_VERSION): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!archiveFile.exists()) {
                Log.e(TAG, "压缩包不存在: ${archiveFile.absolutePath}")
                return@withContext false
            }

            Log.i(TAG, "解压 Codex 二进制...")

            // 读取 tar.gz 文件
            // 先解压 gzip，然后提取 tar 中的 codex 二进制
            val tempDir = File(codexDir, "extract")
            tempDir.mkdirs()

            // 使用进程调用 tar 命令（如果可用）
            try {
                val process = ProcessBuilder()
                    .command("tar", "-xzf", archiveFile.absolutePath, "-C", tempDir.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                val exitCode = process.waitFor()

                if (exitCode == 0) {
                    // 查找解压后的 codex 二进制
                    val extracted = tempDir.listFiles()?.firstOrNull { file ->
                        file.name == "codex" || file.name.endsWith("/codex") || !file.extension.let { it == "gz" || it == "tar" }
                    } ?: tempDir.resolve("codex")

                    // 如果没有直接找到 codex 文件，递归查找
                    val codexFile = findCodexBinary(tempDir)
                    if (codexFile != null && codexFile.exists()) {
                        codexFile.copyTo(codexBinary, overwrite = true)
                        codexBinary.setExecutable(true)
                        recordInstalledVersion(installedVersion)
                        Log.i(TAG, "Codex 二进制解压完成: ${codexBinary.length()} bytes")
                        tempDir.deleteRecursively()
                        archiveFile.delete() // 清理压缩包
                        return@withContext true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "tar 命令失败: ${e.message}")
            }

            // 如果 tar 命令不可用，使用 Java 方式
            return@withContext extractWithJava(tempDir, installedVersion)
        } catch (e: Exception) {
            Log.e(TAG, "解压失败", e)
            false
        }
    }

    /**
     * 递归查找 codex 二进制文件
     */
    private fun findCodexBinary(dir: File): File? {
        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val found = findCodexBinary(file)
                if (found != null) return found
            } else if (file.name == CODEX_BINARY_NAME || file.name.startsWith("codex")) {
                if (file.length() > 10_000_000) { // 大于 10MB
                    return file
                }
            }
        }
        return null
    }

    /**
     * 使用 Java 的 GZIP + Tar 解压
     */
    private fun extractWithJava(tempDir: File, installedVersion: String = CODEX_VERSION): Boolean {
        try {
            val tarFile = File(codexDir, "codex.tar")
            
            // 解压 gzip
            GZIPInputStream(FileInputStream(archiveFile)).use { gzis ->
                FileOutputStream(tarFile).use { fos ->
                    gzis.copyTo(fos)
                }
            }

            // 从 tar 提取 codex 二进制
            // 读取 tar 格式的头部并提取文件
            val buffer = ByteArray(8192)
            RandomAccessFile(tarFile, "r").use { raf ->
                while (raf.filePointer < raf.length()) {
                    // Tar 头部 512 字节
                    val header = ByteArray(512)
                    raf.readFully(header)

                    val name = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000').trim()
                    if (name.isEmpty()) break

                    val sizeStr = String(header, 124, 12, Charsets.UTF_8).trimEnd('\u0000').trim()
                    val size = try {
                        Integer.parseInt(sizeStr.trim(), 8) // Tar 大小是八进制
                    } catch (e: NumberFormatException) {
                        0
                    }

                    if (size <= 0) continue

                    // 检查是否为 codex 二进制
                    val fileName = name.substringAfterLast('/')
                    if (fileName == CODEX_BINARY_NAME || fileName.startsWith("codex")) {
                        Log.i(TAG, "找到 Codex 二进制: $name (大小: $size)")
                        
                        FileOutputStream(codexBinary).use { fos ->
                            val dataBuffer = ByteArray(8192)
                            var remaining = size
                            while (remaining > 0) {
                                val toRead = minOf(dataBuffer.size, remaining)
                                val read = raf.read(dataBuffer, 0, toRead)
                                if (read == -1) break
                                fos.write(dataBuffer, 0, read)
                                remaining -= read
                            }
                        }

                        codexBinary.setExecutable(true)
                        recordInstalledVersion(installedVersion)
                        Log.i(TAG, "Codex 二进制提取完成: ${codexBinary.length()} bytes")

                        // 清理
                        tarFile.delete()
                        tempDir.deleteRecursively()
                        archiveFile.delete()
                        return true
                    } else {
                        // 跳过文件数据
                        val padding = (512 - (size % 512)) % 512
                        raf.skipBytes(size + padding)
                    }
                }
            }

            Log.w(TAG, "在 tar 归档中未找到 codex 二进制")
            tarFile.delete()
            tempDir.deleteRecursively()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Java 解压失败", e)
            tempDir.deleteRecursively()
            return false
        }
    }

    /**
     * 创建默认 Codex 配置
     */
    fun createDefaultConfig(): Boolean {
        return try {
            val configContent = """
# Codex Android Configuration
model = "gpt-4o"
provider = "openai"
approval = "never"
sandbox = "off"
skip-git-repo-check = true
experimental_features = true

[mcp]
# MCP servers will be auto-configured

[plugins]
# Codex plugin system

[features]
codex-agent = true
codex-mcp = true
""".trimIndent()

            getConfigFile().parentFile?.mkdirs()
            getConfigFile().writeText(configContent)
            Log.i(TAG, "默认配置已创建")
            true
        } catch (e: Exception) {
            Log.e(TAG, "创建配置失败", e)
            false
        }
    }

    fun getConfigDir(): File = File(context.filesDir, ".codex").also { it.mkdirs() }
    fun getConfigFile(): File = File(getConfigDir(), "config.toml")

    // ====== 版本记录与升级 ======

    /** 写入已安装版本标记。 */
    private fun recordInstalledVersion(version: String) {
        try {
            codexDir.mkdirs()
            versionFile.writeText(version.trim())
        } catch (e: Exception) {
            Log.w(TAG, "写入版本标记失败", e)
        }
    }

    /**
     * 返回已安装二进制的版本号。
     * 无标记但二进制存在（如手动导入或旧版本安装）时返回 null（版本未知）。
     */
    fun getInstalledVersion(): String? {
        if (!isInstalled()) return null
        return try {
            if (versionFile.exists()) versionFile.readText().trim().ifEmpty { null } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 在线检查 Codex 最新版本（GitHub Releases API，取首个 rust-v* 标签）。
     * 网络不可用或解析失败时返回 null。
     */
    suspend fun checkLatestVersion(): String? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Codex-Android/1.0")
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "检查更新失败: HTTP ${conn.responseCode}")
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            // 在 tag_name 字段中寻找首个 rust-v<semver> 标签（Releases API 按时间倒序返回）
            val tag = Regex("\"tag_name\"\\s*:\\s*\"(rust-v[0-9][^\"]*)\"")
                .find(body)?.groupValues?.get(1)
                ?: return@withContext null
            tag.removePrefix("rust-v").trim().ifEmpty { null }
        } catch (e: Exception) {
            Log.w(TAG, "检查更新异常: ${e.message}")
            null
        }
    }

    /** 给定最新版本号，判断是否有可用更新（与已安装版本比较；未知则与内置版本比较）。 */
    fun isUpdateAvailable(latest: String): Boolean {
        val current = getInstalledVersion() ?: CODEX_VERSION
        return compareVersions(latest, current) > 0
    }

    /**
     * 升级到指定版本：下载 → 解压 → 记录版本。成功返回 true。
     * 失败时保留原有二进制（解压会覆盖，故失败前不删除现有文件）。
     */
    suspend fun upgradeTo(
        version: String,
        onProgressCallback: ((progress: Long, total: Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!downloadWithProgress(version, onProgressCallback)) return@withContext false
        if (!extractBinary(version)) return@withContext false
        verifyBinary()
    }

    // ====== 直接运行自检（无 Termux 可行性验证） ======

    /** 直接执行自检结果。 */
    data class DirectExecResult(
        val success: Boolean,
        val message: String,
        val sdkInt: Int = android.os.Build.VERSION.SDK_INT
    )

    /**
     * 在不依赖 Termux 的情况下，尝试直接执行 codex 二进制（`codex --version`）以验证可行性。
     *
     * 注意：Android 10+（API 29+）禁止执行应用私有数据目录中的可执行文件（W^X 限制），
     * 这通常会导致 EACCES。该自检用于在真机上给出确切结论，无法在构建环境中预判。
     */
    fun testDirectExecution(): DirectExecResult {
        val sdk = android.os.Build.VERSION.SDK_INT
        if (!isInstalled()) {
            return DirectExecResult(false, "二进制未安装，无法自检", sdk)
        }
        return try {
            val process = ProcessBuilder(codexBinary.absolutePath, "--version")
                .redirectErrorStream(true)
                .start()
            // 先等待超时，避免在挂起的进程上 readText() 无限阻塞。
            // `--version` 输出很小，不会撑满管道缓冲区导致写阻塞。
            val finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return DirectExecResult(false, "执行超时（15s 内无响应）", sdk)
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val exit = process.exitValue()
            if (exit == 0) {
                DirectExecResult(true, "可直接运行：${output.take(120)}", sdk)
            } else {
                DirectExecResult(false, "退出码 $exit：${output.take(160)}", sdk)
            }
        } catch (e: IOException) {
            // 典型为 EACCES（W^X）或 ENOEXEC（架构/libc 不兼容）
            val hint = if (sdk >= 29) "（Android $sdk 限制执行私有目录文件，需 Termux/proot 或将二进制打入 native 库）" else ""
            DirectExecResult(false, "无法直接执行: ${e.message}$hint", sdk)
        } catch (e: Exception) {
            DirectExecResult(false, "自检异常: ${e.message}", sdk)
        }
    }

    /**
     * 清理下载文件
     */
    fun cleanup() {
        try {
            archiveFile.delete()
            codexBinary.delete()
            versionFile.delete()
            Log.i(TAG, "已清理 Codex 文件")
        } catch (e: Exception) {
            Log.w(TAG, "清理失败", e)
        }
    }
}
