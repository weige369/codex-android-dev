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
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.huaweicloud.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://mirrors.tencent.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
        )

        private const val CONNECT_TIMEOUT_MS = 20_000
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
        onProgress: ((Long, Long) -> Unit)? = null,
        onStatus: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val rootfs = getRootfsDir()
            if (rootfs.isDirectory()) {
                onStatus?.invoke("清理旧的 rootfs...")
                rootfs.deleteRecursively()
            }
            rootfs.mkdirs()

            val archive = File(context.cacheDir, ROOTFS_ARCHIVE)

            onStatus?.invoke("下载 Ubuntu rootfs (~37MB)...")
            var downloaded = false
            for (mirror in ROOTFS_MIRRORS) {
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
                onStatus?.invoke("Ubuntu rootfs 安装成功!")
                setupRootfs(rootfs)
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
            GZIPInputStream(archive.inputStream()).use { gz ->
                TarArchiveInputStream(gz).use { tar ->
                    var entry = tar.nextEntry
                    var processed = 0L
                    while (entry != null) {
                        val target = File(dest, entry.name)
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else if (entry.isSymbolicLink) {
                            try {
                                Os.symlink(entry.linkName ?: "", target.path)
                            } catch (_: Exception) {}
                        } else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                val buffer = ByteArray(8192)
                                var read: Int
                                while (tar.read(buffer).also { read = it } != -1) {
                                    out.write(buffer, 0, read)
                                }
                            }
                            if (entry.mode and 64 != 0) {
                                target.setExecutable(true, false)
                            }
                        }
                        entry = tar.nextEntry
                        processed = totalBytes - tar.available().toLong()
                        onProgress?.invoke(processed, totalBytes)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解压 rootfs 失败", e)
            throw e
        }
    }

    private fun setupRootfs(rootfs: File) {
        try {
            val resolvConf = File(rootfs, "etc/resolv.conf")
            resolvConf.parentFile?.mkdirs()
            resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

            listOf("dev", "proc", "sys", "tmp", "root", "home").forEach {
                File(rootfs, it).mkdirs()
            }
        } catch (e: Exception) {
            Log.w(TAG, "设置 rootfs 失败", e)
        }
    }

    suspend fun uninstall(): Boolean = withContext(Dispatchers.IO) {
        try {
            val rootfs = getRootfsDir()
            if (rootfs.isDirectory()) rootfs.deleteRecursively()
            File(context.cacheDir, ROOTFS_ARCHIVE).delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "卸载 rootfs 失败", e)
            false
        }
    }
}
