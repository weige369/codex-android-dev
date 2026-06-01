package com.codex.android.agent

import kotlinx.coroutines.flow.StateFlow

/**
 * Chat Agent 统一接口。
 *
 * NativeAgentService 和 ProotAgentService 都实现此接口，
 * 使得 NativeChatView 可以与两种 Agent 交互。
 */

/**
 * 连接状态枚举，所有 ChatAgent 实现共用。
 */
enum class AgentConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, STREAMING, ERROR
}

interface ChatAgent {
    /**
     * 连接状态。
     */
    val connectionState: StateFlow<AgentConnectionState>

    /**
     * 发送 prompt 并接收流式响应。
     */
    fun sendPromptStream(
        prompt: String,
        onChunk: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    )

    /**
     * 取消当前流式响应。
     */
    fun cancelStream()

    /**
     * 是否已配置（API Key 等）。
     */
    fun isConfigured(): Boolean
}