package com.codex.android.agent

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 工具权限管理器。
 *
 * 移植自 Operit ToolPermissionSystem，核心改进：
 * 1. 使用 DataStore 替代 SharedPreferences（更安全、支持异步）
 * 2. 与 CapabilityDevice/PermissionLevel 集成（我们已有的权限体系）
 * 3. 三态权限: ALLOW / ASK / FORBID（Operit 验证过的最简模型）
 * 4. 支持运行时权限请求回调（供 UI 层展示权限弹窗）
 *
 * 权限检查流程：
 * 1. 检查工具级别的权限设置 (ALLOW/ASK/FORBID)
 * 2. 如果是 ALLOW → 直接通过
 * 3. 如果是 ASK → 触发权限请求回调，等待用户决策
 * 4. 如果是 FORBID → 直接拒绝
 * 5. 没有工具级别设置时，使用主控开关
 *
 * 与 CapabilityRegistry 的关系：
 * - CapabilityRegistry 管理设备挂载（结构性安全，决定工具是否暴露给 LLM）
 * - ToolPermissionManager 管理工具执行权限（运行时安全，决定工具是否可以执行）
 * - 两者配合：未挂载的设备 LLM 看不到 → 不会被调用；已挂载的设备需要权限才能执行
 */
class ToolPermissionManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ToolPermissionManager"
        private const val REQUEST_TIMEOUT_MS = 60_000L

        @Volatile
        private var INSTANCE: ToolPermissionManager? = null

        fun getInstance(context: Context): ToolPermissionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ToolPermissionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ===== DataStore =====

    private val Context.toolPermissionsDataStore: DataStore<Preferences> by preferencesDataStore(name = "tool_permissions")

    private val masterSwitchKey = stringPreferencesKey("master_switch")
    private fun toolPermissionKey(toolName: String) = stringPreferencesKey("tool_permission_$toolName")
    private fun toolCategoryPermissionKey(category: String) = stringPreferencesKey("category_permission_$category")

    // ===== 权限请求回调 =====

    private val mutex = Mutex()

    /** 权限请求回调。UI 层设置此回调来接收权限请求。 */
    var permissionRequestCallback: ((PermissionRequest) -> Unit)? = null

    /** 当前挂起的权限请求 */
    private var pendingRequest: PermissionRequest? = null
    private var pendingResult: ((PermissionDecision) -> Unit)? = null

    // ===== 权限检查 =====

    /**
     * 检查工具是否允许执行。
     *
     * @param toolName 工具名称
     * @param capabilityDevice 对应的能力设备（用于获取权限级别和描述）
     * @return true 表示允许执行
     */
    suspend fun checkPermission(toolName: String, capabilityDevice: CapabilityDevice?): Boolean {
        val decision = getPermissionDecision(toolName)

        return when (decision) {
            PermissionDecision.ALLOW -> true
            PermissionDecision.FORBID -> {
                Log.i(TAG, "工具 $toolName 被禁止执行")
                false
            }
            PermissionDecision.ASK -> requestPermissionFromUser(toolName, capabilityDevice)
        }
    }

    /**
     * 获取工具的权限决策（不触发 UI 交互）
     */
    suspend fun getPermissionDecision(toolName: String): PermissionDecision {
        val preferences = context.toolPermissionsDataStore.data.first()

        // 1. 检查工具级别设置
        val toolLevel = preferences[toolPermissionKey(toolName)]
        if (toolLevel != null) {
            return PermissionDecision.fromString(toolLevel)
        }

        // 2. 检查设备类别级别设置
        // (capabilityDevice 可能为 null，此处不直接引用，由调用方传入类别)

        // 3. 使用主控开关
        val masterSwitch = preferences[masterSwitchKey] ?: PermissionDecision.ASK.name
        return PermissionDecision.fromString(masterSwitch)
    }

    /**
     * 获取工具级别的权限决策 Flow
     */
    fun getPermissionFlow(toolName: String): Flow<PermissionDecision> {
        return context.toolPermissionsDataStore.data.map { preferences ->
            val toolLevel = preferences[toolPermissionKey(toolName)]
            if (toolLevel != null) {
                PermissionDecision.fromString(toolLevel)
            } else {
                val masterSwitch = preferences[masterSwitchKey] ?: PermissionDecision.ASK.name
                PermissionDecision.fromString(masterSwitch)
            }
        }
    }

    /**
     * 获取主控开关 Flow
     */
    val masterSwitchFlow: Flow<PermissionDecision> = context.toolPermissionsDataStore.data.map { preferences ->
        PermissionDecision.fromString(preferences[masterSwitchKey] ?: PermissionDecision.ASK.name)
    }

    // ===== 权限设置 =====

    /**
     * 设置主控开关
     */
    suspend fun setMasterSwitch(decision: PermissionDecision) {
        context.toolPermissionsDataStore.edit { preferences ->
            preferences[masterSwitchKey] = decision.name
        }
        Log.i(TAG, "主控开关设为: $decision")
    }

    /**
     * 设置工具级别权限
     */
    suspend fun setToolPermission(toolName: String, decision: PermissionDecision) {
        context.toolPermissionsDataStore.edit { preferences ->
            preferences[toolPermissionKey(toolName)] = decision.name
        }
        Log.i(TAG, "工具 $toolName 权限设为: $decision")
    }

    /**
     * 批量设置工具权限
     */
    suspend fun setToolPermissions(permissions: Map<String, PermissionDecision>) {
        context.toolPermissionsDataStore.edit { preferences ->
            permissions.forEach { (toolName, decision) ->
                preferences[toolPermissionKey(toolName)] = decision.name
            }
        }
        Log.i(TAG, "批量设置 ${permissions.size} 个工具权限")
    }

    /**
     * 清除工具级别权限（回退到主控开关）
     */
    suspend fun clearToolPermission(toolName: String) {
        context.toolPermissionsDataStore.edit { preferences ->
            preferences.remove(toolPermissionKey(toolName))
        }
    }

    /**
     * 一键允许所有 MODERATE 及以下权限的设备（便捷方法）
     */
    suspend fun allowModerateTools() {
        setMasterSwitch(PermissionDecision.ALLOW)
        // 对 ELEVATED 和 PRIVILEGED 保持 ASK
    }

    // ===== 权限请求（UI 交互）=====

    /**
     * 请求用户授权。
     * 通过 permissionRequestCallback 通知 UI 层展示权限弹窗。
     * 等待用户决策或超时。
     */
    private suspend fun requestPermissionFromUser(toolName: String, device: CapabilityDevice?): Boolean {
        return mutex.withLock {
            val request = PermissionRequest(
                toolName = toolName,
                devicePath = device?.devicePath ?: "/dev/unknown",
                permissionLevel = device?.permissionLevel ?: PermissionLevel.MODERATE,
                description = device?.describe() ?: "工具: $toolName"
            )

            pendingRequest = request
            permissionRequestCallback?.invoke(request)

            try {
                kotlinx.coroutines.withTimeout(REQUEST_TIMEOUT_MS) {
                    suspendCancellableCoroutine { continuation ->
                        pendingResult = { decision ->
                            when (decision) {
                                PermissionDecision.ALLOW -> continuation.resume(true)
                                PermissionDecision.FORBID -> continuation.resume(false)
                                PermissionDecision.ASK -> continuation.resume(false)
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w(TAG, "权限请求超时: $toolName")
                false
            } finally {
                pendingRequest = null
                pendingResult = null
            }
        }
    }

    /**
     * 用户对权限请求做出响应。
     * 由 UI 层（ToolPermissionDialog）调用。
     */
    suspend fun respondToPermissionRequest(decision: PermissionDecision, alwaysApply: Boolean = false) {
        val request = pendingRequest ?: return

        if (alwaysApply && decision == PermissionDecision.ALLOW) {
            setToolPermission(request.toolName, PermissionDecision.ALLOW)
        }

        pendingResult?.invoke(decision)
    }

    /**
     * 获取当前挂起的权限请求
     */
    fun getPendingRequest(): PermissionRequest? = pendingRequest

    /**
     * 是否有挂起的权限请求
     */
    fun hasPendingRequest(): Boolean = pendingRequest != null
}

// ===== 数据类 =====

/**
 * 权限决策三态
 */
enum class PermissionDecision {
    ALLOW,   // 自动允许
    ASK,     // 每次询问
    FORBID;  // 禁止

    companion object {
        fun fromString(value: String?): PermissionDecision {
            return when (value?.uppercase()) {
                "ALLOW" -> ALLOW
                "ASK", "CAUTION" -> ASK
                "FORBID", "DENY" -> FORBID
                else -> ASK
            }
        }
    }
}

/**
 * 权限请求（传递给 UI 展示弹窗）
 */
data class PermissionRequest(
    val toolName: String,
    val devicePath: String,
    val permissionLevel: PermissionLevel,
    val description: String
)

/**
 * Kotlin 协程的 suspendCancellableCoroutine 支持
 */
private suspend inline fun <T> suspendCancellableCoroutine(
    crossinline block: (kotlin.coroutines.Continuation<T>) -> Unit
): T = kotlinx.coroutines.suspendCancellableCoroutine(block)
