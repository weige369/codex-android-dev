package com.codex.android.agent

import kotlinx.coroutines.flow.StateFlow

/**
 * Chat Agent 统一接口。
 *
 * NativeAgentService 和 ProotAgentService 都实现此接口，
 * 使得 NativeChatView 可以与两种 Agent 交互。
 */
interface ChatAgent {
    /**
     * 连接状态。
     */
    val connectionState: StateFlow<out Any>

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
     * 是否已配置（API Key 等）。
     */
    fun isConfigured(): Boolean
}
