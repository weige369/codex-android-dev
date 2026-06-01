package com.codex.android.agent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType

/**
 * LLM API 重试策略。
 * 移植自 Operit LlmRetryPolicy，处理：
 * - 网络超时重试
 * - 429 速率限制重试
 * - 5xx 服务器错误重试
 */
object LlmRetryPolicy {
    private const val TAG = "LlmRetryPolicy"

    data class RetryConfig(
        val maxRetries: Int = 3,
        val initialDelayMs: Long = 1000,
        val maxDelayMs: Long = 30000,
        val backoffMultiplier: Float = 2.0f,
        val retryableStatusCodes: Set<Int> = setOf(429, 500, 502, 503, 504)
    )

    val DEFAULT = RetryConfig()

    /**
     * 计算重试延迟时间（指数退避 + 抖动）。
     */
    fun calculateDelay(attempt: Int, config: RetryConfig = DEFAULT): Long {
        val baseDelay = config.initialDelayMs * (config.backoffMultiplier.toDouble().pow(attempt)).toLong()
        val jitter = (Math.random() * baseDelay * 0.3).toLong()
        return minOf(baseDelay + jitter, config.maxDelayMs)
    }

    /**
     * 判断是否应该重试。
     */
    fun shouldRetry(attempt: Int, statusCode: Int?, exception: Exception?, config: RetryConfig = DEFAULT): Boolean {
        if (attempt >= config.maxRetries) return false

        // 速率限制或服务器错误
        if (statusCode != null && statusCode in config.retryableStatusCodes) return true

        // 网络异常
        if (exception != null) {
            val msg = exception.message ?: ""
            return msg.contains("timeout", ignoreCase = true) ||
                   msg.contains("connection", ignoreCase = true) ||
                   msg.contains("reset", ignoreCase = true) ||
                   msg.contains("refused", ignoreCase = true)
        }

        return false
    }

    private fun Double.pow(n: Int): Double = Math.pow(this, n.toDouble())
}

/**
 * API 连接测试器。
 * 移植自 Operit ModelConfigConnectionTester。
 * 验证 API Key + URL + Model 配置是否正确。
 */
class ApiConnectionTester(private val context: Context) {

    companion object {
        private const val TAG = "ApiConnectionTester"
        private const val TEST_TIMEOUT_MS = 15_000L
    }

    data class TestResult(
        val success: Boolean,
        val message: String,
        val latencyMs: Long = 0,
        val modelAvailable: Boolean = false
    )

    /**
     * 测试 API 连接。
     * 发送一个最小的请求验证配置是否正确。
     */
    suspend fun testConnection(apiUrl: String, apiKey: String, model: String): TestResult {
        if (apiUrl.isBlank()) return TestResult(false, "API URL 为空")
        if (apiKey.isBlank()) return TestResult(false, "API Key 为空")
        if (model.isBlank()) return TestResult(false, "模型名称为空")

        val completedUrl = EndpointCompleter.completeEndpoint(apiUrl)

        return try {
            val startTime = System.currentTimeMillis()

            val client = OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(TEST_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()

            val requestBody = JSONObject().apply {
                put("model", model)
                put("messages", org.json.JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", "Hi") })
                })
                put("max_tokens", 5)
                put("stream", false)
            }

            val request = Request.Builder()
                .url(completedUrl)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = withTimeout(TEST_TIMEOUT_MS) {
                client.newCall(request).execute()
            }

            val latencyMs = System.currentTimeMillis() - startTime
            val responseBody = response.body?.string() ?: ""

            when {
                response.isSuccessful -> {
                    val respJson = try { JSONObject(responseBody) } catch (_: Exception) { null }
                    val modelInResponse = respJson?.optString("model", "") ?: ""
                    TestResult(
                        success = true,
                        message = "连接成功 ✓ (延迟 ${latencyMs}ms)",
                        latencyMs = latencyMs,
                        modelAvailable = modelInResponse.isNotBlank()
                    )
                }
                response.code == 401 -> TestResult(false, "API Key 无效 (401)", latencyMs)
                response.code == 403 -> TestResult(false, "访问被拒绝 (403)", latencyMs)
                response.code == 404 -> TestResult(false, "API 端点不存在 (404)，请检查 URL", latencyMs)
                response.code == 429 -> TestResult(false, "速率限制 (429)，API Key 配额不足", latencyMs)
                response.code in 500..599 -> TestResult(false, "服务器错误 (${response.code})", latencyMs)
                else -> TestResult(false, "HTTP ${response.code}: ${responseBody.take(200)}", latencyMs)
            }
        } catch (e: java.net.UnknownHostException) {
            TestResult(false, "DNS 解析失败: ${e.message}")
        } catch (e: java.net.SocketTimeoutException) {
            TestResult(false, "连接超时，请检查网络或更换 API 端点")
        } catch (e: java.net.ConnectException) {
            TestResult(false, "连接被拒绝: ${e.message}")
        } catch (e: Exception) {
            TestResult(false, "连接失败: ${e.message}")
        }
    }
}
