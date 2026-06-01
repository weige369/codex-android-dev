package com.codex.android.agent

/**
 * API 端点 URL 自动补全工具。
 *
 * 移植自 Operit EndpointCompleter，解决中国用户 API 超时问题：
 * - 基础URL → 自动补全 /v1/chat/completions
 * - /v1 结尾 → 自动补全 /chat/completions
 * - 末尾加 # → 禁用自动补全
 *
 * 例如：
 * - https://api.example.com → https://api.example.com/v1/chat/completions
 * - https://my-proxy/custom/v1 → https://my-proxy/custom/v1/chat/completions
 * - https://api.example.com/v1/chat/completions → 不变
 * - https://custom.endpoint# → 禁用补全
 */
object EndpointCompleter {

    /**
     * 为类似 OpenAI 的服务自动补全 API 端点 URL。
     */
    fun completeEndpoint(endpoint: String): String {
        val trimmedEndpoint = endpoint.trim()
        if (trimmedEndpoint.endsWith("#")) {
            return trimmedEndpoint.removeSuffix("#")
        }

        val endpointWithoutSlash = trimmedEndpoint.removeSuffix("/")

        try {
            val url = java.net.URL(trimmedEndpoint)
            val path = url.path.removeSuffix("/")

            // 路径为空：补全标准路径
            if (path.isNullOrEmpty()) {
                return "$endpointWithoutSlash/v1/chat/completions"
            }

            // 以 /v1 结尾：补全后续部分
            if (path.endsWith("/v1", ignoreCase = true)) {
                return "$endpointWithoutSlash/chat/completions"
            }
        } catch (_: Exception) {
            // 无效URL，不补全
        }

        return endpoint
    }

    /**
     * 验证端点 URL 是否看起来合法。
     */
    fun isValidEndpoint(endpoint: String): Boolean {
        val trimmed = endpoint.trim()
        if (trimmed.isBlank()) return false
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false
        return try {
            java.net.URL(trimmed)
            true
        } catch (_: Exception) {
            false
        }
    }
}
