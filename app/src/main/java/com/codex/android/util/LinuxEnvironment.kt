package com.codex.android.util

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * 自包含 Linux 环境管理器。
 *
 * 负责：
 * - 管理 APK 中打包的 proot 引擎（位于 native libs 目录）
 * - 下载并解压 Ubuntu rootfs
 * - 提供 proot 执行包装器
 * - 状态检测与报告
 *
 * Android 10+ W^X 限制：只能执行 native libs 目录中的二进制。
 * proot 二进制已打包为 libproot.so（随 APK 安装时自动提取到 native libs）。
 */
class LinuxEnvironment(private val context: Context) {

    companion object {
        private const val TAG = "LinuxEnvironment"

        private const val ROOTFS_DIR = "linux-rootfs"
        private const val ROOTFS_ARCHIVE = "ubuntu-base.tar.gz"

        private val ROOTFS_MIRRORS = listOf(
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.nju.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.sjtug.sjtu.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.huaweicloud.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.tencent.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
        )

        private val ALPINE_ROOTFS_MIRRORS = listOf(
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz",
            "https://mirrors.aliyun.com/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz",
            "https://mirrors.ustc.edu.cn/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz",
            "https://dl-cdn.alpinelinux.org/alpine/v3.19/releases/aarch64/alpine-minirootfs-3.19.1-aarch64.tar.gz"
        )

        // Debian rootfs — proot-distro 预构建 tarball（tar.xz 格式）
        private val DEBIAN_ROOTFS_MIRRORS = listOf(
            "https://github.com/termux/proot-distro/releases/download/v4.7.0/debian-bookworm-aarch64-pd-v4.7.0.tar.xz",
            "https://ghfast.top/https://github.com/termux/proot-distro/releases/download/v4.7.0/debian-bookworm-aarch64-pd-v4.7.0.tar.xz"
        )

        // 各发行版元信息
        private val DISTRO_META = mapOf(
            "ubuntu" to DistroMeta("Ubuntu 24.04 LTS", "ubuntu-base.tar.gz", ROOTFS_MIRRORS, expectedSha256 = "PLACEHOLDER_UBUNTU_SHA256"),
            "alpine" to DistroMeta("Alpine 3.19 (轻量)", "alpine-minirootfs.tar.gz", ALPINE_ROOTFS_MIRRORS, expectedSha256 = "PLACEHOLDER_ALPINE_SHA256"),
            "debian" to DistroMeta("Debian 12", "debian-base.tar.xz", DEBIAN_ROOTFS_MIRRORS, expectedSha256 = "PLACEHOLDER_DEBIAN_SHA256")
        )

        data class DistroMeta(
            val displayName: String,
            val archiveName: String,
            val mirrors: List<String>,
            val expectedSha256: String = ""  // placeholder, to be updated with real hashes
        )

        private const val CONNECT_TIMEOUT_MS = 10_000

        /** Compute SHA-256 hex digest of a file */
        private fun computeSHA256(file: File): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { fis ->
                val buffer = ByteArray(8192)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    enum class EngineState {
        UNAVAILABLE,
        NOT_INSTALLED,
        INSTALLING,
        READY,
        ERROR
    }

    data class LinuxEnvInfo(
        val state: EngineState = EngineState.UNAVAILABLE,
        val prootPath: String = "",
        val prootLoaderPath: String = "",
        val prootLoader32Path: String = "",
        val rootfsPath: String = "",
        val tallocPath: String = "",
        val errorMessage: String = ""
    )

    fun getInfo(): LinuxEnvInfo {
        val ai = context.packageManager?.getApplicationInfo(context.packageName, 0)
        val nativeLibraryDir = ai?.nativeLibraryDir
        if (nativeLibraryDir == null) {
            return LinuxEnvInfo(EngineState.UNAVAILABLE, errorMessage = "无法获取 native libs 目录")
        }
        val proot = File(nativeLibraryDir, "libproot.so")
        val loader = File(nativeLibraryDir, "libproot-loader.so")
        val loader32 = File(nativeLibraryDir, "libproot-loader32.so")
        val talloc = File(nativeLibraryDir, "libtalloc.so")
        val rootfs = getRootfsDir()

        return when {
            !proot.canExecute() ->
                LinuxEnvInfo(EngineState.UNAVAILABLE, errorMessage = "proot 引擎未就绪")
            !loader.canExecute() ->
                LinuxEnvInfo(EngineState.UNAVAILABLE, errorMessage = "proot loader 未就绪")
            !rootfs.isDirectory() || rootfs.listFiles()?.isEmpty() != false ->
                LinuxEnvInfo(EngineState.NOT_INSTALLED,
                    prootPath = proot.path, prootLoaderPath = loader.path,
                    prootLoader32Path = loader32.path, tallocPath = talloc.path)
            !isRootfsValid() ->
                LinuxEnvInfo(EngineState.ERROR,
                    prootPath = proot.path, prootLoaderPath = loader.path,
                    prootLoader32Path = loader32.path, tallocPath = talloc.path,
                    errorMessage = "rootfs 不完整")
            else ->
                LinuxEnvInfo(EngineState.READY,
                    prootPath = proot.path, prootLoaderPath = loader.path,
                    prootLoader32Path = loader32.path, tallocPath = talloc.path,
                    rootfsPath = rootfs.path)
        }
    }

    fun getRootfsDir(): File = File(context.filesDir, ROOTFS_DIR)

    fun isRootfsValid(): Boolean {
        val rootfs = getRootfsDir()
        if (!rootfs.isDirectory()) return false
        return File(rootfs, "bin/bash").canExecute() || File(rootfs, "bin/sh").canExecute()
    }

    fun isInstalled(): Boolean = getInfo().state == EngineState.READY

    fun getProotEnv(): Map<String, String> {
        val info = getInfo()
        val env = mutableMapOf<String, String>()
        if (info.prootLoaderPath.isNotEmpty()) env["PROOT_LOADER"] = info.prootLoaderPath
        if (info.prootLoader32Path.isNotEmpty()) env["PROOT_LOADER_32"] = info.prootLoader32Path

        val libLinkDir = File(context.cacheDir, "proot-libs")
        libLinkDir.mkdirs()
        val tallocLink = File(libLinkDir, "libtalloc.so.2")
        if (!tallocLink.exists() && info.tallocPath.isNotEmpty()) {
            try {
                Os.symlink(info.tallocPath, tallocLink.path)
            } catch (e: Exception) {
                Log.w(TAG, "创建 talloc 符号链接失败", e)
            }
        }

        val ai2 = context.packageManager?.getApplicationInfo(context.packageName, 0)
        val nativeLibraryDir = ai2?.nativeLibraryDir ?: ""
        env["LD_LIBRARY_PATH"] = "$nativeLibraryDir:${libLinkDir.path}:/system/lib64:/system/lib"
        return env
    }

    fun buildProotCommand(command: String): List<String> {
        val info = getInfo()
        val rootfs = getRootfsDir().path
        return listOf(
            info.prootPath,
            "--rootfs=$rootfs",
            "--root-id",
            "--kill-on-exit",
            "-0",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/data/data/${context.packageName}:$rootfs/data/data/${context.packageName}",
            "-b", "/storage",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "SHELL=/bin/bash",
            "USER=root",
            "/bin/bash", "-c", command
        )
    }

    suspend fun runCommand(
        command: String,
        timeoutMs: Long = 120_000L
    ): AndroidShellExecutor.ShellResult = withContext(Dispatchers.IO) {
        val info = getInfo()
        if (info.state != EngineState.READY) {
            return@withContext AndroidShellExecutor.ShellResult(-1, "",
                "自包含 Linux 未就绪: ${info.errorMessage}")
        }

        try {
            val cmd = buildProotCommand(command)
            val env = getProotEnv()
            val pb = ProcessBuilder(cmd)
            env.forEach { (k, v) -> pb.environment()[k] = v }

            val process = pb.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)

            if (!finished) {
                process.destroyForcibly()
                AndroidShellExecutor.ShellResult(-1, stdout, stderr, isTimedOut = true)
            } else {
                AndroidShellExecutor.ShellResult(process.exitValue(), stdout, stderr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "proot 执行命令失败", e)
            AndroidShellExecutor.ShellResult(-1, "", "执行失败: ${e.message}")
        }
    }

    suspend fun installRootfs(
        distro: String = "ubuntu",
        onProgress: ((Long, Long) -> Unit)? = null,
        onStatus: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val meta = DISTRO_META[distro] ?: DISTRO_META["ubuntu"]!!
            val rootfs = getRootfsDir()
            if (rootfs.isDirectory()) {
                onStatus?.invoke("清理旧的 rootfs...")
                rootfs.deleteRecursively()
            }
            rootfs.mkdirs()

            val archive = File(context.cacheDir, meta.archiveName)

            onStatus?.invoke("下载 ${meta.displayName} rootfs...")
            var downloaded = false
            for (mirror in meta.mirrors) {
                onStatus?.invoke("尝试镜像: $mirror")
                try {
                    val conn = URL(mirror).openConnection() as HttpURLConnection
                    conn.connectTimeout = CONNECT_TIMEOUT_MS
                    conn.readTimeout = 180_000
                    conn.instanceFollowRedirects = true
                    conn.connect()

                    if (conn.responseCode != HttpURLConnection.HTTP_OK) continue

                    val total = conn.contentLengthLong
                    onStatus?.invoke("开始下载 (${total / 1024 / 1024}MB)...")
                    val input = conn.inputStream
                    val output = FileOutputStream(archive)
                    val buffer = ByteArray(8192)
                    var read: Int
                    var totalRead = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalRead += read.toLong()
                        onProgress?.invoke(totalRead, total)
                    }
                    output.close()
                    input.close()
                    conn.disconnect()

                    if (archive.length() > 1_000_000) {
                        // SHA256 校验（MITM 防护）
                        val expectedHash = meta.expectedSha256
                        if (expectedHash.isNotEmpty() && !expectedHash.startsWith("PLACEHOLDER_")) {
                            val actualHash = computeSHA256(archive)
                            if (actualHash != expectedHash) {
                                Log.e(TAG, "SHA256 mismatch for ${meta.archiveName}: expected=$expectedHash, got=$actualHash")
                                archive.delete()
                                continue  // try next mirror
                            }
                            Log.i(TAG, "SHA256 verified for ${meta.archiveName}")
                        } else {
                            Log.w(TAG, "SHA256 not configured for ${meta.archiveName}, skipping validation")
                        }
                        downloaded = true
                        onStatus?.invoke("下载完成 (${archive.length() / 1024 / 1024}MB)")
                        break
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "镜像下载失败: $mirror", e)
                    onStatus?.invoke("镜像不可达，尝试下一个...")
                }
            }

            if (!downloaded) {
                onStatus?.invoke("所有镜像均不可达，下载失败")
                return@withContext false
            }

            onStatus?.invoke("正在解压 rootfs...")
            extractRootfs(archive, rootfs, onProgress)

            archive.delete()

            if (isRootfsValid()) {
                onStatus?.invoke("${meta.displayName} rootfs 安装成功!")
                setupRootfs(rootfs)
                // 预装基础系统工具（git/curl/wget/ca-certificates 等）
                installBaseSystemTools()
                return@withContext true
            } else {
                onStatus?.invoke("rootfs 解压后验证失败")
                return@withContext false
            }
        } catch (e: Exception) {
            onStatus?.invoke("安装异常: ${e.message}")
            Log.e(TAG, "安装 rootfs 失败", e)
            false
        }
    }

    private fun extractRootfs(
        archive: File,
        dest: File,
        onProgress: ((Long, Long) -> Unit)? = null
    ) {
        try {
            val totalBytes = archive.length()
            var processed = 0L
            // 根据文件扩展名自动检测压缩格式
            val decompressedStream = if (archive.name.endsWith(".xz")) {
                XZCompressorInputStream(archive.inputStream())
            } else {
                GZIPInputStream(archive.inputStream())
            }
            decompressedStream.use { gz ->
                TarArchiveInputStream(gz).use { tar ->
                    var entry: TarArchiveEntry? = tar.nextTarEntry
                    while (entry != null) {
                        val entryName = entry.name

                        // H-1: Path traversal protection - reject entries that escape dest
                        if (entryName.contains("..") || entryName.startsWith("/")) {
                            Log.w(TAG, "Skipping unsafe tar entry: $entryName")
                            entry = tar.nextTarEntry
                            continue
                        }

                        val target = File(dest, entryName)
                        // Canonical path check
                        if (!target.canonicalPath.startsWith(dest.canonicalPath + File.separator) 
                            && target.canonicalPath != dest.canonicalPath) {
                            Log.w(TAG, "Skipping path traversal entry: $entryName")
                            entry = tar.nextTarEntry
                            continue
                        }

                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else if (entry.isSymbolicLink) {
                            // Validate symlink target doesn't escape dest
                            val linkTarget = entry.linkName ?: ""
                            if (linkTarget.contains("..") || linkTarget.startsWith("/")) {
                                val linkFile = File(dest, linkTarget)
                                if (!linkFile.canonicalPath.startsWith(dest.canonicalPath + File.separator)
                                    && linkFile.canonicalPath != dest.canonicalPath) {
                                    Log.w(TAG, "Skipping unsafe symlink: $entryName -> $linkTarget")
                                    entry = tar.nextTarEntry
                                    continue
                                }
                            }
                            try {
                                target.parentFile?.mkdirs()
                                Os.symlink(linkTarget, target.path)
                            } catch (e: Exception) {
                                Log.w(TAG, "创建符号链接失败: $entryName", e)
                            }
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                val buffer = ByteArray(8192)
                                var read: Int
                                while (tar.read(buffer).also { read = it } != -1) {
                                    out.write(buffer, 0, read)
                                    processed += read
                                }
                            }
                            // Preserve executable permission
                            if (entry.mode and 0x40 != 0) {
                                target.setExecutable(true, false)
                            }
                            // Preserve last modified time
                            if (entry.modTime.time > 0) {
                                target.setLastModified(entry.modTime.time)
                            }
                        }
                        entry = tar.nextTarEntry
                        onProgress?.invoke(processed, totalBytes)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解压 rootfs 失败", e)
            throw e
        }
    }

    /**
     * rootfs 安装后的初始配置。
     */
    private fun setupRootfs(rootfs: File) {
        try {
            // DNS 配置
            val resolvConf = File(rootfs, "etc/resolv.conf")
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

            // 基础目录
            listOf("dev", "proc", "sys", "tmp", "root", "home").forEach {
                File(rootfs, it).mkdirs()
            }

            // Agent 二进制安装目录
            File(rootfs, "usr/local/bin").mkdirs()
        } catch (e: Exception) {
            Log.w(TAG, "设置 rootfs 失败", e)
        }
    }

    /**
     * 预装基础系统工具（在 installRootfs 末尾调用）。
     * 参考 Operit：开箱即用，无需用户在向导手动选择系统工具。
     */
    private suspend fun installBaseSystemTools() {
        try {
            Log.i(TAG, "预装基础系统工具...")
            // 检测发行版：Alpine 用 apk，其他用 apt
            val osRelease = File(getRootfsDir(), "etc/os-release")
            val isAlpine = osRelease.exists() && osRelease.readText().contains("Alpine", ignoreCase = true)

            if (isAlpine) {
                // Alpine: apk 包管理
                runCommand("apk update 2>&1", 60_000)
                val alpineTools = listOf(
                    "ca-certificates", "curl", "wget", "git", "unzip", "vim", "musl-locales"
                )
                val result = runCommand("apk add --no-cache " + alpineTools.joinToString(" ") + " 2>&1", 300_000)
                if (result.exitCode == 0) {
                    Log.i(TAG, "Alpine 基础系统工具安装完成")
                } else {
                    Log.w(TAG, "部分 Alpine 工具安装失败: ${result.stderr.take(200)}")
                }
            } else {
                // Debian/Ubuntu: apt 包管理
                runCommand("apt-get update -qq 2>&1", 60_000)

                val baseTools = listOf(
                    "ca-certificates",  // HTTPS 证书
                    "curl",             // HTTP 客户端
                    "wget",             // 下载工具
                    "git",              // 版本管理
                    "unzip",            // 解压
                    "vim-tiny",         // 最小编辑器
                    "locales"           // locale 支持
                )

                val installCmd = "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq " +
                    baseTools.joinToString(" ") + " 2>&1"

                val result = runCommand(installCmd, 300_000)
                if (result.exitCode == 0) {
                    Log.i(TAG, "基础系统工具安装完成")
                } else {
                    Log.w(TAG, "部分基础工具安装失败: ${result.stderr.take(200)}")
                }

                // 配置 locale
                runCommand("locale-gen en_US.UTF-8 2>&1", 10_000)
            }
        } catch (e: Exception) {
            Log.w(TAG, "预装基础工具失败（非致命）", e)
        }
    }

    suspend fun uninstall(): Boolean = withContext(Dispatchers.IO) {
        try {
            val rootfs = getRootfsDir()
            if (rootfs.isDirectory()) rootfs.deleteRecursively()
            // 清理所有发行版归档
            DISTRO_META.values.forEach { meta ->
                File(context.cacheDir, meta.archiveName).delete()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "卸载 rootfs 失败", e)
            false
        }
    }
}
