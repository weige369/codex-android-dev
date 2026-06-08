package com.codex.android.provider

import android.util.Log
import com.codex.android.bridge.CodexBridge
import com.codex.android.service.CodexRuntimeService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Codex Agent Provider — 增强版。
 *
 * 参考 Claude Code agents 系统设计：
 * - 主 Agent + 子 Agent 分发
 * - 会话隔离管理
 * - 任务队列与并发控制
 * - 健康监控与自动重连
 */
class CodexAgentProvider(
    private val wsPort: Int = CodexRuntimeService.DEFAULT_WS_PORT
) {
    companion object {
        private const val TAG = "CodexAgentProvider"
    }

    enum class ProviderState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    /** Agent 会话信息 */
    data class AgentSession(
        val id: String = UUID.randomUUID().toString().take(8),
        val name: String = "Agent",
        val status: AgentStatus = AgentStatus.IDLE,
        val prompt: String = "",
        val result: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        val completedAt: Long? = null
    )

    enum class AgentStatus {
        IDLE, RUNNING, COMPLETED, FAILED, CANCELLED
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var bridge: CodexBridge? = null
    private val isConnected = AtomicBoolean(false)
    private var healthCheckJob: Job? = null

    private val _state = MutableStateFlow(ProviderState.DISCONNECTED)
    val state: StateFlow<ProviderState> = _state.asStateFlow()

    private val _currentRequestId = MutableStateFlow<String?>(null)
    val currentRequestId: StateFlow<String?> = _currentRequestId.asStateFlow()

    // Agent 会话管理
    private val sessions = ConcurrentHashMap<String, AgentSession>()
    private val _activeSessions = MutableStateFlow<List<AgentSession>>(emptyList())
    val activeSessions: StateFlow<List<AgentSession>> = _activeSessions.asStateFlow()

    var onStreamMessage: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onSessionUpdate: ((AgentSession) -> Unit)? = null

    fun connect() {
        if (isConnected.get()) return
        _state.value = ProviderState.CONNECTING
        bridge = CodexBridge("ws://127.0.0.1:$wsPort").also { b ->
            b.onConnectionChange = { state ->
                when (state) {
                    CodexBridge.ConnectionState.CONNECTED -> {
                        isConnected.set(true)
                        _state.value = ProviderState.CONNECTED
                        startHealthCheck()
                    }
                    CodexBridge.ConnectionState.ERROR -> { _state.value = ProviderState.ERROR }
                    CodexBridge.ConnectionState.DISCONNECTED -> {
                        isConnected.set(false)
                        _state.value = ProviderState.DISCONNECTED
                        stopHealthCheck()
                    }
                    else -> {}
                }
            }
            b.connect()
        }
    }

    fun disconnect() {
        stopHealthCheck()
        bridge?.destroy()
        bridge = null
        isConnected.set(false)
        _state.value = ProviderState.DISCONNECTED
    }

    suspend fun execute(prompt: String, stream: Boolean = true): String {
        val b = bridge ?: throw IllegalStateException("未连接到 Codex exec-server")
        return b.executePrompt(prompt, stream)
    }

    fun sendPrompt(prompt: String) {
        val b = bridge ?: run { onError?.invoke("未连接到 Codex exec-server"); return }
        b.onMessage = { msg -> 
            // 解析流式消息：尝试提取 content 字段
            try {
                val json = org.json.JSONObject(msg)
                val content = if (json.has("method") && json.getString("method") == "response") {
                    json.optJSONObject("params")?.optString("content", msg) ?: msg
                } else {
                    msg
                }
                onStreamMessage?.invoke(content)
            } catch (e: Exception) {
                // 无法解析为 JSON，原样传递
                onStreamMessage?.invoke(msg)
            }
        }
        b.sendPrompt(prompt)
    }

    /**
     * 发送流式提示，逐块回调
     */
    fun sendPromptStream(
        prompt: String,
        onChunk: (String) -> Unit,
        onComplete: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val b = bridge ?: run { onError?.invoke("未连接到 Codex exec-server"); return }

        val originalOnMessage = b.onMessage
        val buffer = StringBuilder()

        b.onMessage = { msg ->
            try {
                val json = org.json.JSONObject(msg)
                if (json.has("method") && json.getString("method") == "response") {
                    val params = json.optJSONObject("params") ?: json
                    val content = params.optString("content", "")
                    val isFinal = params.optBoolean("isFinal", false) || 
                                  params.optBoolean("done", false)

                    if (content.isNotEmpty()) {
                        buffer.append(content)
                        onChunk(content)
                    }

                    if (isFinal) {
                        b.onMessage = originalOnMessage
                        onComplete?.invoke()
                    }
                } else if (json.has("id") && json.has("result")) {
                    // 最终结果
                    b.onMessage = originalOnMessage
                    onComplete?.invoke()
                } else {
                    // 其他消息原样传递
                    originalOnMessage?.invoke(msg)
                }
            } catch (e: Exception) {
                // 非 JSON 消息，直接当 chunk 处理
                onChunk(msg)
            }
        }

        b.sendPrompt(prompt)
    }

    /**
     * 分发子 Agent 任务（类似 Claude Code agents 系统）
     */
    fun dispatchSubAgent(
        prompt: String,
        name: String = "Sub-Agent",
        onResult: ((String) -> Unit)? = null
    ): String {
        val session = AgentSession(
            name = name,
            status = AgentStatus.RUNNING,
            prompt = prompt
        )
        sessions[session.id] = session
        refreshSessions()
        onSessionUpdate?.invoke(session)

        scope.launch {
            try {
                val result = execute(prompt, stream = true)
                val updated = session.copy(
                    status = AgentStatus.COMPLETED,
                    result = result,
                    completedAt = System.currentTimeMillis()
                )
                sessions[session.id] = updated
                refreshSessions()
                onSessionUpdate?.invoke(updated)
                onResult?.invoke(result)
            } catch (e: Exception) {
                val failed = session.copy(
                    status = AgentStatus.FAILED,
                    result = "错误: ${e.message}",
                    completedAt = System.currentTimeMillis()
                )
                sessions[session.id] = failed
                refreshSessions()
                onSessionUpdate?.invoke(failed)
                onError?.invoke(e.message ?: "Agent 执行失败")
            }
        }
        return session.id
    }

    /**
     * 取消 Agent 任务
     */
    fun cancelSession(sessionId: String) {
        val session = sessions[sessionId] ?: return
        if (session.status == AgentStatus.RUNNING) {
            val updated = session.copy(
                status = AgentStatus.CANCELLED,
                completedAt = System.currentTimeMillis()
            )
            sessions[sessionId] = updated
            refreshSessions()
            onSessionUpdate?.invoke(updated)
        }
    }

    /**
     * 清理已完成的任务
     */
    fun clearCompletedSessions() {
        val completedIds = sessions.filter { (_, s) ->
            s.status == AgentStatus.COMPLETED || s.status == AgentStatus.FAILED || s.status == AgentStatus.CANCELLED
        }.keys
        completedIds.forEach { sessions.remove(it) }
        refreshSessions()
    }

    private fun refreshSessions() {
        _activeSessions.value = sessions.values.sortedByDescending { it.createdAt }
    }

    suspend fun ping(): Boolean {
        return try {
            val b = bridge ?: return false
            val result = b.getStatus()
            !result.contains("error")
        } catch (e: Exception) { false }
    }

    /**
     * 健康检查 — 每分钟检测连接状态
     */
    private fun startHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(60_000)
                if (isConnected.get()) {
                    val alive = ping()
                    if (!alive) {
                        Log.w(TAG, "健康检查失败，尝试重连...")
                        disconnect()
                        connect()
                    }
                }
            }
        }
    }

    private fun stopHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = null
    }

    fun destroy() {
        disconnect()
        sessions.clear()
        refreshSessions()
        scope.cancel()
    }
}
