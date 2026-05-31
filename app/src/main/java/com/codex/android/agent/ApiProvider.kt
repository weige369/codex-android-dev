package com.codex.android.agent

/**
 * API 提供商配置。
 * 支持多提供商切换，解决中国大陆无法直连 api.openai.com 的问题。
 */
data class ApiProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val defaultModel: String,
    val supportsStreaming: Boolean = true,
    val supportsToolCall: Boolean = true,
    val requiresApiKey: Boolean = true,
) {
    companion object {
        val BUILT_IN = listOf(
            ApiProvider(
                id = "deepseek",
                name = "DeepSeek",
                baseUrl = "https://api.deepseek.com/v1",
                defaultModel = "deepseek-chat",
            ),
            ApiProvider(
                id = "openai",
                name = "OpenAI",
                baseUrl = "https://api.openai.com/v1",
                defaultModel = "gpt-4o",
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
            ),
            ApiProvider(
                id = "zhipu",
                name = "智谱 AI",
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                defaultModel = "glm-4-flash",
            ),
            ApiProvider(
                id = "moonshot",
                name = "Moonshot (Kimi)",
                baseUrl = "https://api.moonshot.cn/v1",
                defaultModel = "moonshot-v1-8k",
            ),
        )

        fun getById(id: String): ApiProvider? = BUILT_IN.find { it.id == id }
    }
}
