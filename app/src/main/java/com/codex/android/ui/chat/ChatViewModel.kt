package com.codex.android.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codex.android.bridge.CodexBridge
import com.codex.android.provider.CodexAgentProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Chat ViewModel — 管理 AI 聊天会话的消息流与代理状态。
 *
 * 支持两种模式：
 * - Codex 模式：通过 CodexAgentProvider 连接本地 Codex CLI exec-server
 * - API 模式：通过 HTTP API 直接调用 OpenAI 兼容接口
 */
class ChatViewModel : ViewModel() {

    companion object {
        private const val TAG = "ChatViewModel"
        private const val DEFAULT_WS_PORT = 9877
    }

    /** 聊天模式 */
    enum class ChatMode {
        CODEX,   // 本地 Codex CLI
        API      // OpenAI 兼容 API
    }

    /** 单条消息 */
    data class ChatMessage(
        val id: String = java.util.UUID.randomUUID().toString().take(8),
        val role: Role,
        val content: String,
        val isStreaming: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class Role {
        USER, ASSISTANT, SYSTEM
    }

    /** Agent 状态 */
    enum class AgentStatus {
        DISCONNECTED, CONNECTING, CONNECTED, BUSY, ERROR
    }

    // ---- State ----

    private val _chatMode = MutableStateFlow(ChatMode.CODEX)
    val chatMode: StateFlow<ChatMode> = _chatMode.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _agentStatus = MutableStateFlow(AgentStatus.DISCONNECTED)
    val agentStatus: StateFlow<AgentStatus> = _agentStatus.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ---- Internals ----

    private var agentProvider: CodexAgentProvider? = null
    private var streamingJob: Job? = null

    // ---- Mode ----

    fun setChatMode(mode: ChatMode) {
        _chatMode.value = mode
        if (mode == ChatMode.CODEX) {
            connectCodexAgent()
        }
    }

    fun toggleChatMode() {
        setChatMode(if (_chatMode.value == ChatMode.CODEX) ChatMode.API else ChatMode.CODEX)
    }

    // ---- Codex Agent ----

    fun connectCodexAgent() {
        if (_agentStatus.value == AgentStatus.CONNECTED || _agentStatus.value == AgentStatus.BUSY) return

        _agentStatus.value = AgentStatus.CONNECTING
        val provider = CodexAgentProvider(DEFAULT_WS_PORT).also { agentProvider = it }

        provider.onStreamMessage = { chunk ->
            updateLastAssistantMessage(chunk)
        }

        provider.onError = { error ->
            _agentStatus.value = AgentStatus.ERROR
            _errorMessage.value = error
            _isProcessing.value = false
            Log.e(TAG, "Agent error: $error")
        }

        provider.onSessionUpdate = { session ->
            if (session.status == CodexAgentProvider.AgentStatus.COMPLETED ||
                session.status == CodexAgentProvider.AgentStatus.FAILED ||
                session.status == CodexAgentProvider.AgentStatus.CANCELLED) {
                _agentStatus.value = AgentStatus.CONNECTED
                _isProcessing.value = false
                finalizeLastAssistantMessage()
            }
        }

        provider.connect()
        _agentStatus.value = AgentStatus.CONNECTED
    }

    fun disconnectCodexAgent() {
        agentProvider?.disconnect()
        agentProvider = null
        _agentStatus.value = AgentStatus.DISCONNECTED
    }

    // ---- Send ----

    fun sendMessage(text: String) {
        if (text.isBlank() || _isProcessing.value) return

        _errorMessage.value = null
        val userMsg = ChatMessage(role = Role.USER, content = text.trim())
        val assistantMsg = ChatMessage(role = Role.ASSISTANT, content = "", isStreaming = true)
        _messages.value = _messages.value + listOf(userMsg, assistantMsg)

        when (_chatMode.value) {
            ChatMode.CODEX -> sendViaCodex(text.trim())
            ChatMode.API -> sendViaApi(text.trim())
        }
    }

    private fun sendViaCodex(prompt: String) {
        _isProcessing.value = true
        _agentStatus.value = AgentStatus.BUSY

        val provider = agentProvider
        if (provider == null || provider.state.value != CodexAgentProvider.ProviderState.CONNECTED) {
            connectCodexAgent()
        }

        agentProvider?.let {
            it.dispatchSubAgent(prompt, name = "Android-Codex")
        } ?: run {
            // fallback: try direct bridge
            _errorMessage.value = "Codex Agent 未连接，请先启动 Codex 运行时"
            _isProcessing.value = false
            _agentStatus.value = AgentStatus.ERROR
            updateLastAssistantMessage("[错误]: Codex Agent 未连接")
            finalizeLastAssistantMessage()
        }
    }

    private fun sendViaApi(prompt: String) {
        // API 模式留作占位——由 CodexSettingsScreen 配置的 API Key 决定行为
        _isProcessing.value = true
        _agentStatus.value = AgentStatus.BUSY

        viewModelScope.launch {
            try {
                // TODO: 实际的 OpenAI 兼容 API 调用
                val fallback = "API 模式尚未完全实现。请切换到 Codex 模式以使用本地 CLI。"
                updateLastAssistantMessage(fallback)
            } catch (e: Exception) {
                _errorMessage.value = "API 调用失败: ${e.message}"
                updateLastAssistantMessage("[错误]: ${e.message}")
            } finally {
                finalizeLastAssistantMessage()
                _agentStatus.value = AgentStatus.CONNECTED
                _isProcessing.value = false
            }
        }
    }

    fun retryLast() {
        val lastUserMsg = _messages.value.lastOrNull { it.role == Role.USER } ?: return
        // 移除最后一次助手消息
        val msgs = _messages.value.toMutableList()
        if (msgs.lastOrNull()?.role == Role.ASSISTANT) msgs.removeLast()
        _messages.value = msgs
        sendMessage(lastUserMsg.content)
    }

    fun clearMessages() {
        _messages.value = emptyList()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    // ---- Internal helpers ----

    private fun updateLastAssistantMessage(chunk: String) {
        val msgs = _messages.value.toMutableList()
        val lastIdx = msgs.indexOfLast { it.role == Role.ASSISTANT }
        if (lastIdx >= 0) {
            val updated = msgs[lastIdx].copy(
                content = msgs[lastIdx].content + chunk,
                isStreaming = true
            )
            msgs[lastIdx] = updated
            _messages.value = msgs
        }
    }

    private fun finalizeLastAssistantMessage() {
        val msgs = _messages.value.toMutableList()
        val lastIdx = msgs.indexOfLast { it.role == Role.ASSISTANT }
        if (lastIdx >= 0) {
            msgs[lastIdx] = msgs[lastIdx].copy(isStreaming = false)
            _messages.value = msgs
        }
    }

    override fun onCleared() {
        super.onCleared()
        disconnectCodexAgent()
    }
}
