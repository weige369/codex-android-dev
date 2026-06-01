package com.codex.android.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codex.android.agent.AgentBinaryManager
import com.codex.android.agent.AgentEnvironmentSetup
import com.codex.android.agent.AgentOrchestrator
import com.codex.android.data.preferences.SetupPreferences
import com.codex.android.environment.ProotEnvironment
import com.codex.android.util.AndroidShellExecutor
import com.codex.android.util.DevelopmentEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * 设置向导 ViewModel。
 *
 * 管理完整的6步设置向导状态：
 * 1. 欢迎 + 设备检测
 * 2. 权限设置
 * 3. Linux 环境安装
 * 4. 开发工具安装
 * 5. AI 配置
 * 6. 完成
 */
class SetupWizardViewModel : ViewModel() {

    companion object {
        private const val TAG = "SetupWizardVM"
    }

    // ===== 当前步骤 =====
    private val _currentStep = MutableStateFlow(0)
    val currentStep: StateFlow<Int> = _currentStep.asStateFlow()

    // ===== 设备信息 =====
    data class DeviceInfo(
        val androidVersion: String = "",
        val apiLevel: Int = 0,
        val cpuArch: String = "",
        val availableStorageGB: String = "",
        val isRooted: Boolean = false,
        val deviceModel: String = "",
        val manufacturer: String = ""
    )

    private val _deviceInfo = MutableStateFlow(DeviceInfo())
    val deviceInfo: StateFlow<DeviceInfo> = _deviceInfo.asStateFlow()

    // ===== 权限状态 =====
    data class PermissionStates(
        val storage: Boolean = false,
        val notification: Boolean = false,
        val batteryOptimization: Boolean = false,
        val overlay: Boolean = false,
        val location: Boolean = false
    )

    private val _permissionStates = MutableStateFlow(PermissionStates())
    val permissionStates: StateFlow<PermissionStates> = _permissionStates.asStateFlow()

    // ===== Linux 环境 =====
    private val _selectedDistro = MutableStateFlow("ubuntu")
    val selectedDistro: StateFlow<String> = _selectedDistro.asStateFlow()

    private val _selectedMirror = MutableStateFlow("tuna")
    val selectedMirror: StateFlow<String> = _selectedMirror.asStateFlow()

    data class LinuxInstallState(
        val isInstalling: Boolean = false,
        val progress: Float = 0f,
        val phase: ProotEnvironment.InstallPhase = ProotEnvironment.InstallPhase.IDLE,
        val message: String = "",
        val isInstalled: Boolean = false,
        val hasSkipped: Boolean = false,
        val error: String? = null
    )

    private val _linuxInstallState = MutableStateFlow(LinuxInstallState())
    val linuxInstallState: StateFlow<LinuxInstallState> = _linuxInstallState.asStateFlow()

    // ===== Agent 选择 =====
    data class AgentOption(
        val type: AgentOrchestrator.AgentType,
        val displayName: String,
        val description: String,
        val estimatedSize: String,
        val requiresNodejs: Boolean = false,
        val requiresPython: Boolean = false
    )

    val agentOptions = listOf(
        AgentOption(
            type = AgentOrchestrator.AgentType.CODEX,
            displayName = "Codex CLI",
            description = "OpenAI Codex 命令行 Agent（需 Node.js）",
            estimatedSize = "~95MB",
            requiresNodejs = true
        ),
        AgentOption(
            type = AgentOrchestrator.AgentType.OPENCODE,
            displayName = "OpenCode",
            description = "Go 语言 AI 编程助手",
            estimatedSize = "~25MB"
        ),
        AgentOption(
            type = AgentOrchestrator.AgentType.OPENMANUS,
            displayName = "OpenManus",
            description = "Python AI Agent 框架（需 Python）",
            estimatedSize = "~280MB",
            requiresPython = true
        )
    )

    private val _selectedAgents = MutableStateFlow<Set<AgentOrchestrator.AgentType>>(emptySet())
    val selectedAgents: StateFlow<Set<AgentOrchestrator.AgentType>> = _selectedAgents.asStateFlow()

    data class AgentInstallState(
        val isInstalling: Boolean = false,
        val progress: Float = 0f,
        val message: String = "",
        val isCompleted: Boolean = false,
        val currentAgent: String = "",
        val error: String? = null
    )

    private val _agentInstallState = MutableStateFlow(AgentInstallState())
    val agentInstallState: StateFlow<AgentInstallState> = _agentInstallState.asStateFlow()

    private var binaryManager: AgentBinaryManager? = null
    private var envSetup: AgentEnvironmentSetup? = null

    // ===== AI 配置 =====
    data class AIProvider(
        val id: String,
        val displayName: String,
        val baseUrl: String,
        val defaultModel: String
    )

    val aiProviders = listOf(
        AIProvider("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
        AIProvider("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o"),
        AIProvider("siliconflow", "SiliconFlow", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct"),
        AIProvider("zhipu", "智谱 AI", "https://open.bigmodel.cn/api/paas/v4", "glm-4"),
        AIProvider("moonshot", "Moonshot", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
        AIProvider("custom", "自定义", "", "")
    )

    private val _selectedProvider = MutableStateFlow("deepseek")
    val selectedProvider: StateFlow<String> = _selectedProvider.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _customApiUrl = MutableStateFlow("")
    val customApiUrl: StateFlow<String> = _customApiUrl.asStateFlow()

    private val _customModel = MutableStateFlow("")
    val customModel: StateFlow<String> = _customModel.asStateFlow()

    enum class ConnectionState {
        IDLE, TESTING, SUCCESS, FAILED
    }

    data class ConnectionTestState(
        val state: ConnectionState = ConnectionState.IDLE,
        val message: String = "",
        val modelCount: Int = 0
    )

    private val _connectionTestState = MutableStateFlow(ConnectionTestState())
    val connectionTestState: StateFlow<ConnectionTestState> = _connectionTestState.asStateFlow()

    // ===== 完成状态 =====
    private val _isSetupComplete = MutableStateFlow(false)
    val isSetupComplete: StateFlow<Boolean> = _isSetupComplete.asStateFlow()

    // ===== 初始化 =====
    private var prootEnv: ProotEnvironment? = null
    private var devEnv: DevelopmentEnvironment? = null

    fun init(context: Context) {
        prootEnv = ProotEnvironment(context)
        devEnv = DevelopmentEnvironment(context)
        detectDeviceInfo(context)
        refreshPermissionStates(context)

        // 检查 Linux 是否已安装
        if (prootEnv?.isDistroInstalled() == true) {
            _linuxInstallState.value = LinuxInstallState(isInstalled = true)
        }

        // 恢复已保存的 AI 配置
        loadSavedAIConfig(context)
    }

    // ===== 步骤导航 =====
    fun nextStep() {
        if (_currentStep.value < 5) {
            _currentStep.value++
        }
    }

    fun prevStep() {
        if (_currentStep.value > 0) {
            _currentStep.value--
        }
    }

    fun goToStep(step: Int) {
        if (step in 0..5) {
            _currentStep.value = step
        }
    }

    // ===== 设备检测 =====
    private fun detectDeviceInfo(context: Context) {
        val abis = Build.SUPPORTED_ABIS ?: emptyArray()
        val cpuArch = when {
            abis.contains("arm64-v8a") -> "ARM64 (arm64-v8a)"
            abis.contains("x86_64") -> "x86_64"
            abis.contains("armeabi-v7a") -> "ARM32 (armeabi-v7a)"
            else -> abis.firstOrNull() ?: "Unknown"
        }

        val filesDir = context.filesDir
        val freeGB = "%.1f".format(filesDir.freeSpace / 1024.0 / 1024.0 / 1024.0)

        val isRooted = AndroidShellExecutor.isRootAvailable()

        _deviceInfo.value = DeviceInfo(
            androidVersion = "Android ${Build.VERSION.RELEASE}",
            apiLevel = Build.VERSION.SDK_INT,
            cpuArch = cpuArch,
            availableStorageGB = "$freeGB GB",
            isRooted = isRooted,
            deviceModel = Build.MODEL,
            manufacturer = Build.MANUFACTURER
        )
    }

    // ===== 权限管理 =====
    fun refreshPermissionStates(context: Context) {
        _permissionStates.value = PermissionStates(
            storage = checkStoragePermission(context),
            notification = checkNotificationPermission(context),
            batteryOptimization = checkBatteryOptimization(context),
            overlay = checkOverlayPermission(context),
            location = checkLocationPermission(context)
        )
    }

    private fun checkStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun checkBatteryOptimization(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun checkOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    private fun checkLocationPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun requestStoragePermission(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:com.codex.android")
            }
        } else null
    }

    fun requestBatteryOptimization(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun requestOverlayPermission(): Intent {
        return Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:com.codex.android")
        }
    }

    // ===== Linux 环境 =====
    fun selectDistro(distro: String) {
        _selectedDistro.value = distro
    }

    fun selectMirror(mirror: String) {
        _selectedMirror.value = mirror
    }

    fun installLinux(context: Context) {
        val env = prootEnv ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _linuxInstallState.value = LinuxInstallState(isInstalling = true)
            val ok = env.installDistro(
                distro = _selectedDistro.value,
                mirror = _selectedMirror.value
            ) { progress ->
                _linuxInstallState.value = LinuxInstallState(
                    isInstalling = true,
                    progress = progress.progress,
                    phase = progress.phase,
                    message = progress.message
                )
            }

            if (ok) {
                _linuxInstallState.value = LinuxInstallState(isInstalled = true, message = "安装完成!")
                // 持久化 Linux 安装状态
                context.getSharedPreferences("codex_setup_prefs", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("linux_installed", true).apply()
            } else {
                _linuxInstallState.value = LinuxInstallState(error = "安装失败，请检查网络后重试")
            }
        }
    }

    fun skipLinuxInstall() {
        _linuxInstallState.value = LinuxInstallState(hasSkipped = true)
    }

    // ===== 开发工具 =====
    fun toggleAgent(type: AgentOrchestrator.AgentType) {
        val current = _selectedAgents.value.toMutableSet()
        if (type in current) current.remove(type) else current.add(type)
        _selectedAgents.value = current
    }

    fun selectAllAgents() {
        _selectedAgents.value = agentOptions.map { it.type }.toSet()
    }

    fun clearAllAgents() {
        _selectedAgents.value = emptySet()
    }

    /**
     * 安装选中的 Agent 及其依赖环境。
     * 流程：先安装环境依赖 → 再安装 Agent 二进制
     */
    fun installSelectedAgents(ctx: android.content.Context) {
        if (binaryManager == null) binaryManager = AgentBinaryManager(ctx)
        if (envSetup == null) envSetup = AgentEnvironmentSetup(ctx)
        val bm = binaryManager ?: return
        val es = envSetup ?: return
        val agents = _selectedAgents.value
        if (agents.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            _agentInstallState.value = AgentInstallState(isInstalling = true, currentAgent = "环境准备")

            // Step 1: 安装环境依赖（Node.js/Python 等）
            val envOk = es.setupEnvironment(agents) { progress ->
                _agentInstallState.value = AgentInstallState(
                    isInstalling = true,
                    progress = progress.progress * 0.3f,
                    message = progress.message,
                    currentAgent = "环境准备"
                )
            }

            if (!envOk) {
                _agentInstallState.value = AgentInstallState(error = "环境安装失败，请检查网络")
                return@launch
            }

            // Step 2: 逐个安装 Agent
            val total = agents.size
            var installed = 0
            var allOk = true

            for (option in agentOptions) {
                if (option.type !in agents) continue
                installed++
                _agentInstallState.value = AgentInstallState(
                    isInstalling = true,
                    progress = 0.3f + (installed.toFloat() / total) * 0.65f,
                    message = "正在安装 ${option.displayName}...",
                    currentAgent = option.displayName
                )

                val ok = bm.install(option.type) { progress ->
                    _agentInstallState.value = AgentInstallState(
                        isInstalling = true,
                        progress = 0.3f + (installed.toFloat() / total) * 0.65f + progress.progress * 0.65f / total,
                        message = progress.message,
                        currentAgent = option.displayName
                    )
                }

                if (!ok) {
                    Log.w(TAG, "安装 ${option.displayName} 失败")
                    allOk = false
                }
            }

            if (allOk || installed > 0) {
                _agentInstallState.value = AgentInstallState(
                    isCompleted = true,
                    message = "${installed} 个 Agent 安装完成!",
                    progress = 1f
                )
                // 持久化
                val prefs = ctx.getSharedPreferences("codex_setup_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit()
                    .putBoolean("linux_installed", true)
                    .putStringSet("installed_agents", agents.map { it.id }.toSet())
                    .apply()
            } else {
                _agentInstallState.value = AgentInstallState(error = "Agent 安装失败")
            }
        }
    }

    fun estimateAgentSize(): String {
        var totalMB = 0
        for (opt in agentOptions) {
            if (opt.type in _selectedAgents.value) {
                val sizeStr = opt.estimatedSize.replace("~", "").replace("MB", "").trim()
                totalMB += sizeStr.toIntOrNull() ?: 0
            }
        }
        return "~${totalMB}MB"
    }

    // ===== AI 配置 =====
    fun selectProvider(provider: String) {
        _selectedProvider.value = provider
        _connectionTestState.value = ConnectionTestState()
    }

    fun setApiKey(key: String) {
        _apiKey.value = key
        _connectionTestState.value = ConnectionTestState()
    }

    fun setCustomApiUrl(url: String) {
        _customApiUrl.value = url
    }

    fun setCustomModel(model: String) {
        _customModel.value = model
    }

    fun testApiConnection() {
        val provider = aiProviders.find { it.id == _selectedProvider.value } ?: return
        val key = _apiKey.value
        if (key.isBlank()) {
            _connectionTestState.value = ConnectionTestState(
                state = ConnectionState.FAILED,
                message = "请输入 API Key"
            )
            return
        }

        val baseUrl = if (_selectedProvider.value == "custom") {
            _customApiUrl.value.trimEnd('/')
        } else {
            provider.baseUrl
        }

        if (baseUrl.isBlank()) {
            _connectionTestState.value = ConnectionTestState(
                state = ConnectionState.FAILED,
                message = "请输入 API 地址"
            )
            return
        }

        _connectionTestState.value = ConnectionTestState(
            state = ConnectionState.TESTING,
            message = "正在测试连接..."
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/models")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("Authorization", "Bearer $key")
                conn.setRequestProperty("Content-Type", "application/json")

                val code = conn.responseCode
                if (code == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val modelCount = Regex(""""id"\s*:"[^"]*"""").findAll(body).count()

                    _connectionTestState.value = ConnectionTestState(
                        state = ConnectionState.SUCCESS,
                        message = "连接成功! 可用模型: $modelCount 个",
                        modelCount = modelCount
                    )
                } else {
                    val errorBody = try {
                        conn.errorStream?.bufferedReader()?.readText()?.take(200)
                    } catch (_: Exception) { "" }
                    _connectionTestState.value = ConnectionTestState(
                        state = ConnectionState.FAILED,
                        message = "连接失败 (HTTP $code): ${errorBody ?: "未知错误"}"
                    )
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "API 连接测试失败", e)
                _connectionTestState.value = ConnectionTestState(
                    state = ConnectionState.FAILED,
                    message = "连接失败: ${e.message}"
                )
            }
        }
    }

    // ===== 保存配置 =====
    fun saveAIConfig(context: Context) {
        val provider = aiProviders.find { it.id == _selectedProvider.value } ?: return
        val prefs = context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
        val baseUrl = if (_selectedProvider.value == "custom") {
            _customApiUrl.value.trimEnd('/')
        } else {
            provider.baseUrl
        }
        val model = if (_selectedProvider.value == "custom") {
            _customModel.value.ifBlank { "default" }
        } else {
            provider.defaultModel
        }

        prefs.edit()
            .putString("conn_mode", "api")
            .putString("api_key", _apiKey.value)
            .putString("api_url", baseUrl)
            .putString("api_model", model)
            .putString("api_provider", _selectedProvider.value)
            .apply()
    }

    private fun loadSavedAIConfig(context: Context) {
        val prefs = context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)
        val savedProvider = prefs.getString("api_provider", null)
        val savedKey = prefs.getString("api_key", "") ?: ""
        val savedUrl = prefs.getString("api_url", "") ?: ""
        val savedModel = prefs.getString("api_model", "") ?: ""

        if (savedProvider != null) {
            _selectedProvider.value = savedProvider
        }
        if (savedKey.isNotEmpty()) {
            _apiKey.value = savedKey
        }
        if (savedProvider == "custom") {
            _customApiUrl.value = savedUrl
            _customModel.value = savedModel
        }
    }

    // ===== 完成设置 =====
    fun markSetupComplete(context: Context) {
        saveAIConfig(context)
        viewModelScope.launch {
            val prefs = SetupPreferences.getInstance(context)
            prefs.markSetupCompleted()
            _isSetupComplete.value = true
        }
    }
}
