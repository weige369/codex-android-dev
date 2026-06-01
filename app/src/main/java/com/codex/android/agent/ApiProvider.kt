package com.codex.android.agent

/**
 * API 提供商配置（v2）。
 *
 * 支持多提供商切换，解决中国大陆无法直连 api.openai.com 的问题。
 *
 * v2 改进：
 * - 添加 supportsThinking 标记（DeepSeek V4 思考模式需要特殊处理）
 * - 更新 DeepSeek 默认模型为 deepseek-chat（V4 Pro 思考模型）
 * - 添加 SiliconFlow 更多模型选项
 */
data class ApiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val supportsStreaming: Boolean = true,
    val supportsToolCall: Boolean = true,
    val requiresApiKey: Boolean = true,
    /** 是否支持思考模式（如 DeepSeek V4），需要处理 reasoning_content */
    val supportsThinking: Boolean = false,
) {
    companion object {
        val BUILT_IN = listOf(
            ApiProvider(
                id = "deepseek",
                name = "DeepSeek",
                baseUrl = "https://api.deepseek.com/v1",
                defaultModel = "deepseek-chat",
                supportsThinking = true,  // deepseek-chat 现在是 V4 Pro 思考模型
                supportsToolCall = true,
            ),
            ApiProvider(
                id = "deepseek-coder",
                name = "DeepSeek Coder",
                baseUrl = "https://api.deepseek.com/v1",
                defaultModel = "deepseek-coder",
                supportsToolCall = true,
            ),
            ApiProvider(
                id = "openai",
                name = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                defaultModel = "gpt-4o",
                supportsToolCall = true,
            ),
            ApiProvider(
                id = "openai-compatible",
                name = "OpenAI 兼容（自定义端点）",
                baseUrl = "",
                defaultModel = "gpt-4o",
            ),
            ApiProvider(
                id = "siliconflow",
                name = "SiliconFlow",
                baseUrl = "https://api.siliconflow.cn/v1",
                defaultModel = "deepseek-ai/DeepSeek-V3",
                supportsToolCall = true,
            ),
            ApiProvider(
                id = "zhipu",
                name = "智谱 AI",
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                defaultModel = "glm-4-flash",
                supportsToolCall = true,
            ),
            ApiProvider(
                id = "moonshot",
                name = "Moonshot (Kimi)",
                baseUrl = "https://api.moonshot.cn/v1",
                defaultModel = "moonshot-v1-8k",
                supportsToolCall = true,
            ),
        )

        fun getById(id: String): ApiProvider? = BUILT_IN.find { it.id == id }
    }
}
