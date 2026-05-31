package com.codex.android.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import android.webkit.WebView
import com.codex.android.bridge.CodexBridge
import com.codex.android.codex.CodexManager
import com.codex.android.util.AndroidShellExecutor
import com.codex.android.util.DevelopmentEnvironment
import com.codex.android.util.LinuxEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * APP 全方位自测系统。
 *
 * 自动检测所有模块的健康状态，定位问题根因。
 * 测试项覆盖：UI/WebView、桥接通信、运行时环境、
 * 开发环境、文件系统、网络、权限等。
 */
class DiagnosticsRunner(private val context: Context) {

    companion object {
        private const val TAG = "DiagnosticsRunner"

        /**
         * 把已捕获的异常压缩成一行可读摘要（类型 + message），
         * 用于在界面上直接展示失败根因，完整堆栈仍写入 logcat。
         */
        fun exceptionSummary(e: Throwable): String {
            val type = e.javaClass.simpleName.ifBlank { e.javaClass.name }
            val msg = e.message?.trim()?.takeIf { it.isNotEmpty() }
            return if (msg != null) "$type: ${msg.take(200)}" else type
        }
    }

    /** 单条测试结果 */
    data class TestResult(
        val name: String,            // 测试名称
        val passed: Boolean,         // 是否通过
        val detail: String,          // 结果详情
        val severity: Severity = if (passed) Severity.INFO else Severity.ERROR,
        val suggestion: String = ""  // 修复建议
    )

    enum class Severity { INFO, WARNING, ERROR }

    /** 完整的诊断报告 */
    data class DiagnosticsReport(
        val timestamp: Long = System.currentTimeMillis(),
        val deviceInfo: DeviceInfo = DeviceInfo(),
        val results: List<TestResult> = emptyList()
    ) {
        val passedCount: Int get() = results.count { it.passed }
        val failedCount: Int get() = results.count { !it.passed }
        val totalCount: Int get() = results.size
        val isAllPassed: Boolean get() = failedCount == 0
    }

    data class DeviceInfo(
        val androidVersion: String = "${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})",
        val device: String = "${Build.MANUFACTURER} ${Build.MODEL}",
        val arch: String = Build.SUPPORTED_ABIS.joinToString(", "),
        val memory: String = "${Runtime.getRuntime().totalMemory() / 1024 / 1024}MB / ${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB",
        val diskSpace: String = "${android.os.Environment.getExternalStorageDirectory().totalSpace / 1024 / 1024}MB 可用"
    )

    /**
     * 运行全部诊断测试
     */
    suspend fun runAll(): DiagnosticsReport = withContext(Dispatchers.IO) {
        val results = mutableListOf<TestResult>()

        // 1. 设备基础信息
        results.addAll(runDeviceTests())

        // 2. 权限测试
        results.addAll(runPermissionTests())

        // 3. 网络测试
        results.addAll(runNetworkTests())

        // 4. WebView 与桥接测试
        results.addAll(runBridgeTests())

        // 5. 文件系统测试
        results.addAll(runFileSystemTests())

        // 6. Codex 二进制测试
        results.addAll(runCodexBinaryTests())

        // 7. 开发环境测试
        results.addAll(runDevEnvironmentTests())

        // 8. Shell 执行测试
        results.addAll(runShellTests())

        DiagnosticsReport(
            deviceInfo = DeviceInfo(),
            results = results
        )
    }

    /**
     * 运行指定分类的测试
     */
    suspend fun runCategory(category: String): List<TestResult> = withContext(Dispatchers.IO) {
        when (category) {
            "device" -> runDeviceTests()
            "permissions" -> runPermissionTests()
            "network" -> runNetworkTests()
            "bridge" -> runBridgeTests()
            "filesystem" -> runFileSystemTests()
            "codex" -> runCodexBinaryTests()
            "environment" -> runDevEnvironmentTests()
            "shell" -> runShellTests()
            else -> emptyList()
        }
    }

    // ====== 1. 设备基础测试 ======
    private fun runDeviceTests(): List<TestResult> {
        return listOf(
            TestResult(
                "Android 版本", true,
                "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})"
            ),
            TestResult(
                "设备型号", true,
                "${Build.MANUFACTURER} ${Build.MODEL}"
            ),
            TestResult(
                "CPU 架构", true,
                Build.SUPPORTED_ABIS.joinToString(", ")
            ),
            TestResult(
                "内存配置", true,
                "堆内存: ${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB"
            ),
            TestResult(
                "是否模拟器", Build.FINGERPRINT.contains("generic") ||
                        Build.FINGERPRINT.contains("emulator"),
                if (Build.FINGERPRINT.contains("generic")) "可能运行在模拟器上" else "物理设备",
                severity = if (Build.FINGERPRINT.contains("generic")) Severity.WARNING else Severity.INFO
            )
        )
    }

    // ====== 2. 权限测试 ======
    private fun runPermissionTests(): List<TestResult> {
        val results = mutableListOf<TestResult>()

        // 检查必要权限
        val permissions = mapOf(
            "INTERNET" to android.Manifest.permission.INTERNET,
            "FOREGROUND_SERVICE" to android.Manifest.permission.FOREGROUND_SERVICE,
            "POST_NOTIFICATIONS" to android.Manifest.permission.POST_NOTIFICATIONS,
        )

        for ((name, perm) in permissions) {
            var checkError: Exception? = null
            val granted = try {
                context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } catch (e: Exception) {
                Log.w(TAG, "权限检查失败: $name", e)
                checkError = e
                false
            }
            results.add(TestResult(
                "权限: $name", granted,
                when {
                    granted -> "已授予"
                    checkError != null -> "检查异常: ${exceptionSummary(checkError)}"
                    else -> "未授予"
                },
                severity = if (granted) Severity.INFO else Severity.WARNING,
                suggestion = when {
                    granted -> ""
                    checkError != null -> "权限检查时发生异常：${exceptionSummary(checkError)}"
                    else -> "请授予 $name 权限"
                }
            ))
        }

        // Shizuku 检测
        var shizukuError: Exception? = null
        val hasShizuku = try {
            Class.forName("moe.shizuku.api.ShizukuApi")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku API 类存在性检查失败", e)
            shizukuError = e
            false
        }
        results.add(TestResult(
            "Shizuku API", hasShizuku,
            if (hasShizuku) "可用" else "未安装",
            severity = Severity.INFO,
            suggestion = if (!hasShizuku && shizukuError != null) "类检查失败：${exceptionSummary(shizukuError)}" else ""
        ))

        return results
    }

    // ====== 3. 网络测试 ======
    private fun runNetworkTests(): List<TestResult> {
        val results = mutableListOf<TestResult>()

        // 网络连接检测
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

        results.add(TestResult(
            "网络连接", hasInternet,
            if (hasInternet) "已连接" else "未连接",
            severity = if (hasInternet) Severity.INFO else Severity.ERROR,
            suggestion = if (!hasInternet) "请连接网络" else ""
        ))

        // GitHub API 可达性
        var githubError: Exception? = null
        val githubReachable = try {
            val url = java.net.URL("https://api.github.com")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.responseCode == 200
        } catch (e: Exception) {
            Log.w(TAG, "GitHub API 可达性探测失败", e)
            githubError = e
            false
        }
        results.add(TestResult(
            "GitHub API 可达", githubReachable,
            when {
                githubReachable -> "正常"
                githubError != null -> "连接失败: ${exceptionSummary(githubError)}"
                else -> "连接失败 (HTTP 非 200)"
            },
            severity = if (githubReachable) Severity.INFO else Severity.ERROR,
            suggestion = if (!githubReachable) "检查网络或防火墙" else ""
        ))

        // OpenAI API 可达性
        var openaiError: Exception? = null
        val openaiReachable = try {
            val url = java.net.URL("https://api.openai.com")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.responseCode in 200..499
        } catch (e: Exception) {
            Log.w(TAG, "OpenAI API 可达性探测失败", e)
            openaiError = e
            false
        }
        results.add(TestResult(
            "OpenAI API 可达", openaiReachable,
            when {
                openaiReachable -> "正常"
                openaiError != null -> "连接失败: ${exceptionSummary(openaiError)}"
                else -> "连接失败"
            },
            severity = Severity.INFO
        ))

        // 本地端口检查 (Codex WebSocket)
        val wsPortOpen = try {
            val s = java.net.ServerSocket(9877)
            s.close()
            false // 端口可用说明 Codex 没在运行
        } catch (e: Exception) {
            true // 端口被占用说明已在运行
        }
        results.add(TestResult(
            "Codex WebSocket 端口 (9877)", true,
            if (wsPortOpen) "Codex 正在运行" else "端口空闲",
            severity = Severity.INFO
        ))

        return results
    }

    // ====== 4. WebView & 桥接测试 ======
    private fun runBridgeTests(): List<TestResult> {
        val results = mutableListOf<TestResult>()

        // WebView 版本
        val wvVersion = try {
            WebView.getCurrentWebViewPackage()?.versionName ?: "未知"
        } catch (e: Exception) {
            Log.w(TAG, "WebView 版本检测失败", e)
            "未知"
        }
        results.add(TestResult(
            "WebView 版本", true, wvVersion
        ))

        // WebView 可用性
        try {
            // 使用 CountDownLatch 在主线程创建 WebView
            val latch = java.util.concurrent.CountDownLatch(1)
            val holder = arrayOf<Exception?>(null)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val wv = android.webkit.WebView(context)
                    wv.destroy()
                } catch (e: Exception) {
                    holder[0] = e
                }
                latch.countDown()
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            val error = holder[0]
            results.add(TestResult("WebView 创建", error == null,
                if (error == null) "正常" else "失败: ${error.message}",
                severity = if (error == null) Severity.INFO else Severity.ERROR
            ))
        } catch (e: Exception) {
            results.add(TestResult("WebView 创建", false, "失败: ${e.message}", Severity.ERROR))
        }

        // HTML 资源检查
        var htmlError: Exception? = null
        val htmlExists = File(context.filesDir.parentFile?.parentFile, "app/src/main/assets/web/codex-ui.html")
            .exists() || try {
            context.assets.open("web/codex-ui.html").use { true }
        } catch (e: Exception) {
            Log.w(TAG, "Codex UI HTML 资源检查失败", e)
            htmlError = e
            false
        }
        results.add(TestResult(
            "Codex UI HTML 资源", htmlExists,
            if (htmlExists) "存在" else "缺失",
            severity = if (htmlExists) Severity.INFO else Severity.ERROR,
            suggestion = when {
                htmlExists -> ""
                htmlError != null -> "assets/web/codex-ui.html 读取失败：${exceptionSummary(htmlError)}"
                else -> "assets/web/codex-ui.html 文件缺失"
            }
        ))

        // Bridge 对象可用性
        var bridgeError: Exception? = null
        val bridgeAvailable = try {
            Class.forName("com.codex.android.bridge.CodexBridge")
            true
        } catch (e: Exception) {
            Log.w(TAG, "CodexBridge 类存在性检查失败", e)
            bridgeError = e
            false
        }
        results.add(TestResult(
            "CodexBridge 类", bridgeAvailable,
            if (bridgeAvailable) "正常" else "缺失",
            severity = if (bridgeAvailable) Severity.INFO else Severity.ERROR,
            suggestion = if (!bridgeAvailable && bridgeError != null) "类加载失败：${exceptionSummary(bridgeError)}" else ""
        ))

        return results
    }

    // ====== 5. 文件系统测试 ======
    private fun runFileSystemTests(): List<TestResult> {
        val results = mutableListOf<TestResult>()

        val codexDir = File(context.filesDir, "codex")
        val workspaceDir = File(context.filesDir, "workspace")
        val configDir = File(context.filesDir, ".codex")

        // 目录创建
        codexDir.mkdirs(); workspaceDir.mkdirs(); configDir.mkdirs()
        val dirsOk = codexDir.exists() && workspaceDir.exists() && configDir.exists()
        results.add(TestResult(
            "文件目录访问", dirsOk,
            "codex: ${codexDir.exists()} | workspace: ${workspaceDir.exists()} | .codex: ${configDir.exists()}",
            severity = if (dirsOk) Severity.INFO else Severity.ERROR
        ))

        // 写入测试
        var writeError: Exception? = null
        val writeOk = try {
            val testFile = File(codexDir, ".write_test")
            testFile.writeText("ok")
            val readBack = testFile.readText()
            testFile.delete()
            readBack == "ok"
        } catch (e: Exception) {
            Log.e(TAG, "文件写入测试失败", e)
            writeError = e
            false
        }
        results.add(TestResult(
            "文件写入权限", writeOk,
            when {
                writeOk -> "正常"
                writeError != null -> "写入失败: ${exceptionSummary(writeError)}"
                else -> "写入失败（写入内容与读回内容不一致）"
            },
            severity = if (writeOk) Severity.INFO else Severity.ERROR,
            suggestion = if (!writeOk && writeError != null) "文件写入异常：${exceptionSummary(writeError)}" else ""
        ))

        // 可用空间
        try {
            val dirPath = context.filesDir.parentFile?.absolutePath ?: context.filesDir.absolutePath
            val dir = java.io.File(dirPath)
            val free = dir.freeSpace / 1024 / 1024
            val enough = free > 200
            results.add(TestResult(
                "存储空间", enough,
                "${free}MB 可用 (建议 >200MB)",
                severity = if (enough) Severity.INFO else Severity.WARNING,
                suggestion = if (!enough) "存储空间不足，请清理" else ""
            ))
        } catch (e: Exception) {
            results.add(TestResult("存储空间检测", false, "检测失败: ${e.message}"))
        }

        return results
    }

    // ====== 6. Codex 二进制测试 ======
    private fun runCodexBinaryTests(): List<TestResult> {
        val results = mutableListOf<TestResult>()
        val codexManager = CodexManager(context)
        val binary = codexManager.codexBinary

        // 二进制是否存在
        val exists = binary.exists()
        results.add(TestResult(
            "Codex 二进制文件", exists,
            if (exists) "${binary.length() / 1024 / 1024}MB" else "未下载",
            severity = if (exists) Severity.INFO else Severity.WARNING,
            suggestion = if (!exists) "点击启动 Codex 自动下载" else ""
        ))

        // 二进制完整性校验
        if (exists) {
            val valid = codexManager.verifyBinary()
            results.add(TestResult(
                "Codex 二进制完整性", valid,
                if (valid) "ELF 头校验通过" else "校验失败，文件损坏",
                severity = if (valid) Severity.INFO else Severity.ERROR,
                suggestion = if (!valid) "删除重试: codexManager.cleanup()" else ""
            ))
        }

        // CPU 架构支持
        val arch = CodexManager.detectArch()
        results.add(TestResult(
            "CPU 架构支持", arch.supported,
            if (arch.supported) "${arch.abi}（支持）" else "${arch.abi}（无官方产物）",
            severity = if (arch.supported) Severity.INFO else Severity.ERROR,
            suggestion = if (!arch.supported) "Codex 仅提供 arm64-v8a / x86_64 产物，当前设备不受支持" else ""
        ))

        // 已安装版本
        if (exists) {
            val installed = codexManager.getInstalledVersion()
            results.add(TestResult(
                "Codex 版本", true,
                installed ?: "未知（手动导入或旧安装）",
                severity = Severity.INFO,
                suggestion = if (installed == null) "在设置页重新下载可记录版本号" else ""
            ))
        }

        // 直接运行自检（无 Termux 可行性验证）
        if (exists) {
            val probe = codexManager.testDirectExecution()
            results.add(TestResult(
                "Codex 直接运行自检", probe.success,
                probe.message,
                severity = if (probe.success) Severity.INFO else Severity.WARNING,
                suggestion = if (!probe.success)
                    "直接运行不可用属正常（Android ${probe.sdkInt} 限制），请使用内置 Linux 环境" else ""
            ))
        }

        // 下载源可达性（逐个检测每个镜像并分别列出状态）
        var anyMirrorReachable = false
        for (mirrorUrl in CodexManager.getAllDownloadUrls()) {
            val host = try { java.net.URL(mirrorUrl).host } catch (_: Exception) { mirrorUrl }
            var reachable = false
            var statusDetail: String
            try {
                val url = java.net.URL(mirrorUrl)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.instanceFollowRedirects = true
                conn.requestMethod = "HEAD"
                conn.setRequestProperty("User-Agent", "Codex-Android/1.0")
                val code = conn.responseCode
                reachable = code in 200..399
                statusDetail = if (reachable) "可达 (HTTP $code)" else "不可用 (HTTP $code)"
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "下载镜像可达性探测失败: $host", e)
                statusDetail = "连接失败 (${e.javaClass.simpleName})"
            }
            if (reachable) anyMirrorReachable = true
            results.add(TestResult(
                "下载镜像: $host", reachable,
                statusDetail,
                severity = if (reachable) Severity.INFO else Severity.WARNING
            ))
        }
        results.add(TestResult(
            "Codex 下载源汇总", anyMirrorReachable,
            if (anyMirrorReachable) "至少一个镜像可达" else "所有镜像均不可达",
            severity = if (anyMirrorReachable) Severity.INFO else Severity.WARNING,
            suggestion = if (!anyMirrorReachable) "请检查网络连接，或在设置中手动导入 Codex 二进制" else ""
        ))

        return results
    }

    // ====== 7. 开发环境测试 ======
    private suspend fun runDevEnvironmentTests(): List<TestResult> {
        val results = mutableListOf<TestResult>()
        val devEnv = DevelopmentEnvironment(context)
        val linuxEnv = LinuxEnvironment(context)
        val linuxInfo = linuxEnv.getInfo()

        // 自包含 Linux（proot）检测
        val hasSelfContainedLinux = linuxInfo.state == LinuxEnvironment.EngineState.READY
        results.add(TestResult(
            "自包含 Linux (proot)", hasSelfContainedLinux,
            when (linuxInfo.state) {
                LinuxEnvironment.EngineState.READY -> "已就绪 (proot + Ubuntu rootfs)"
                LinuxEnvironment.EngineState.NOT_INSTALLED -> "proot 就绪，未安装 rootfs"
                LinuxEnvironment.EngineState.UNAVAILABLE -> "proot 引擎缺失: ${linuxInfo.errorMessage}"
                LinuxEnvironment.EngineState.ERROR -> "错误: ${linuxInfo.errorMessage}"
                else -> "未知状态"
            },
            severity = if (hasSelfContainedLinux) Severity.INFO else Severity.WARNING,
            suggestion = if (!hasSelfContainedLinux && linuxInfo.state == LinuxEnvironment.EngineState.NOT_INSTALLED)
                "请在开发环境页面点击【安装 Linux 环境】一键安装" else ""
        ))

        return results
    }

    // ====== 8. Shell 执行测试 ======
    private suspend fun runShellTests(): List<TestResult> {
        val results = mutableListOf<TestResult>()

        // 基本 shell 执行
        try {
            val result = AndroidShellExecutor.execute("echo 'hello'", permissionLevel = AndroidShellExecutor.PermissionLevel.NORMAL)
            results.add(TestResult(
                "Shell 执行 (NORMAL)", result.exitCode == 0,
                "exit=${result.exitCode}: ${result.stdout.take(100)}",
                severity = if (result.exitCode == 0) Severity.INFO else Severity.ERROR
            ))
        } catch (e: Exception) {
            results.add(TestResult("Shell 执行 (NORMAL)", false, "异常: ${e.message}", Severity.ERROR))
        }


        // Root 可用性
        val hasRoot = AndroidShellExecutor.isRootAvailable()
        results.add(TestResult(
            "Root 权限", hasRoot,
            if (hasRoot) "可用" else "不可用",
            severity = Severity.INFO
        ))

        return results
    }
}
