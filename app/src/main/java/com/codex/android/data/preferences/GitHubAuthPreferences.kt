package com.codex.android.data.preferences

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.codex.android.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

private val Context.githubAuthDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "github_auth_preferences")

@Serializable
data class GitHubUser(
    @SerialName("id") val id: Long,
    @SerialName("login") val login: String,
    @SerialName("name") val name: String? = null,
    @SerialName("email") val email: String? = null,
    @SerialName("avatar_url") val avatarUrl: String,
    @SerialName("bio") val bio: String? = null,
    @SerialName("public_repos") val publicRepos: Int? = null,
    @SerialName("followers") val followers: Int? = null,
    @SerialName("following") val following: Int? = null
)

/**
 * GitHub认证偏好设置管理器
 * 负责管理GitHub OAuth PKCE认证状态、用户信息和访问令牌
 *
 * PKCE (Proof Key for Code Exchange) 流程：
 * 1. 生成 code_verifier + code_challenge (S256)
 * 2. 授权 URL 包含 code_challenge
 * 3. 回调时用 code + code_verifier 换取 token（无需 client_secret）
 */
class GitHubAuthPreferences(private val context: Context) {

    companion object {
        private const val TAG = "GitHubAuth"

        // GitHub OAuth相关配置
        val GITHUB_CLIENT_ID = BuildConfig.GITHUB_CLIENT_ID
        // R-2 fix: client_secret removed — PKCE flow does not require client secret
        const val GITHUB_SCOPE = "notifications,public_repo,user:email,read:user"
        private const val REQUIRED_AUTH_VERSION = 2
        private const val GITHUB_REDIRECT_SCHEME = "codex"
        private const val GITHUB_REDIRECT_HOST = "github-oauth-callback"
        const val GITHUB_REDIRECT_URI = "$GITHUB_REDIRECT_SCHEME://$GITHUB_REDIRECT_HOST"
        
        // 认证相关键
        private val IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
        private val ACCESS_TOKEN = stringPreferencesKey("access_token")
        private val TOKEN_TYPE = stringPreferencesKey("token_type")
        private val TOKEN_EXPIRES_AT = longPreferencesKey("token_expires_at")
        private val REFRESH_TOKEN = stringPreferencesKey("refresh_token")
        private val USER_INFO = stringPreferencesKey("user_info")
        private val LAST_LOGIN_TIME = longPreferencesKey("last_login_time")
        private val AUTH_VERSION = longPreferencesKey("auth_version")
        private val GRANTED_SCOPE = stringPreferencesKey("granted_scope")
        private val PENDING_OAUTH_STATE = stringPreferencesKey("pending_oauth_state")
        private val PENDING_CODE_VERIFIER = stringPreferencesKey("pending_code_verifier")
        
        @Volatile
        private var INSTANCE: GitHubAuthPreferences? = null
        
        fun getInstance(context: Context): GitHubAuthPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GitHubAuthPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun createOAuthState(): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            return (1..32)
                .map { chars.random() }
                .joinToString("")
        }

        /**
         * 生成 PKCE code_verifier (43-128 字符的随机字符串)
         * RFC 7636: 使用 unreserved chars [A-Z] / [a-z] / [0-9] / "-" / "." / "_" / "~"
         */
        fun generateCodeVerifier(): String {
            val random = SecureRandom()
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
            return (1..64)  // 64 字符，在 43-128 范围内
                .map { chars[random.nextInt(chars.length)] }
                .joinToString("")
        }

        /**
         * 计算 PKCE code_challenge (S256 方法)
         * code_challenge = BASE64URL(SHA256(code_verifier))
         */
        fun computeCodeChallenge(codeVerifier: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(codeVerifier.toByteArray(Charsets.US_ASCII))
            return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }

        fun isOAuthRedirectUri(uri: Uri?): Boolean {
            return uri?.scheme == GITHUB_REDIRECT_SCHEME && uri.host == GITHUB_REDIRECT_HOST
        }
    }

    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val requiredScopes: Set<String> =
        GITHUB_SCOPE.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun parseScopeSet(scope: String?): Set<String> {
        return scope
            ?.split(",")
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
    }

    private fun isAuthSessionCurrent(preferences: Preferences): Boolean {
        val grantedScopes = parseScopeSet(preferences[GRANTED_SCOPE])
        val authVersion = preferences[AUTH_VERSION] ?: 0L
        return authVersion >= REQUIRED_AUTH_VERSION && grantedScopes.containsAll(requiredScopes)
    }

    // 登录状态Flow
    val isLoggedInFlow: Flow<Boolean> = context.githubAuthDataStore.data.map { preferences ->
        (preferences[IS_LOGGED_IN] ?: false) && isAuthSessionCurrent(preferences)
    }

    // 访问令牌Flow
    val accessTokenFlow: Flow<String?> = context.githubAuthDataStore.data.map { preferences ->
        if (isAuthSessionCurrent(preferences)) preferences[ACCESS_TOKEN] else null
    }

    // 用户信息Flow
    val userInfoFlow: Flow<GitHubUser?> = context.githubAuthDataStore.data.map { preferences ->
        if (!isAuthSessionCurrent(preferences)) {
            return@map null
        }
        val userInfoJson = preferences[USER_INFO]
        if (userInfoJson != null) {
            try {
                json.decodeFromString<GitHubUser>(userInfoJson)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    // 最后登录时间Flow
    val lastLoginTimeFlow: Flow<Long> = context.githubAuthDataStore.data.map { preferences ->
        preferences[LAST_LOGIN_TIME] ?: 0L
    }

    /**
     * 保存认证信息
     */
    suspend fun saveAuthInfo(
        accessToken: String,
        tokenType: String = "bearer",
        expiresIn: Long? = null,
        refreshToken: String? = null,
        userInfo: GitHubUser,
        grantedScope: String? = null
    ) {
        context.githubAuthDataStore.edit { preferences ->
            preferences[IS_LOGGED_IN] = true
            preferences[ACCESS_TOKEN] = accessToken
            preferences[TOKEN_TYPE] = tokenType
            preferences[USER_INFO] = json.encodeToString(userInfo)
            preferences[LAST_LOGIN_TIME] = System.currentTimeMillis()
            preferences[AUTH_VERSION] = REQUIRED_AUTH_VERSION.toLong()
            preferences[GRANTED_SCOPE] = grantedScope.orEmpty()
            
            expiresIn?.let {
                preferences[TOKEN_EXPIRES_AT] = System.currentTimeMillis() + (it * 1000)
            }
            
            refreshToken?.let {
                preferences[REFRESH_TOKEN] = it
            }
        }
    }

    /**
     * 更新用户信息
     */
    suspend fun updateUserInfo(userInfo: GitHubUser) {
        context.githubAuthDataStore.edit { preferences ->
            preferences[USER_INFO] = json.encodeToString(userInfo)
        }
    }

    /**
     * 更新访问令牌
     */
    suspend fun updateAccessToken(
        accessToken: String,
        tokenType: String = "bearer",
        expiresIn: Long? = null,
        grantedScope: String? = null
    ) {
        context.githubAuthDataStore.edit { preferences ->
            preferences[ACCESS_TOKEN] = accessToken
            preferences[TOKEN_TYPE] = tokenType
            preferences[AUTH_VERSION] = REQUIRED_AUTH_VERSION.toLong()
            preferences[GRANTED_SCOPE] = grantedScope.orEmpty()
            
            expiresIn?.let {
                preferences[TOKEN_EXPIRES_AT] = System.currentTimeMillis() + (it * 1000)
            }
        }
    }

    /**
     * 检查令牌是否已过期
     */
    suspend fun isTokenExpired(): Boolean {
        val preferences = context.githubAuthDataStore.data.first()
        val expiresAt = preferences[TOKEN_EXPIRES_AT] ?: return false
        return System.currentTimeMillis() >= expiresAt
    }

    /**
     * 获取当前访问令牌
     */
    suspend fun getCurrentAccessToken(): String? {
        val preferences = context.githubAuthDataStore.data.first()
        if (!isAuthSessionCurrent(preferences)) {
            return null
        }
        return preferences[ACCESS_TOKEN]
    }

    /**
     * 获取当前用户信息
     */
    suspend fun getCurrentUserInfo(): GitHubUser? {
        val preferences = context.githubAuthDataStore.data.first()
        if (!isAuthSessionCurrent(preferences)) {
            return null
        }
        val userInfoJson = preferences[USER_INFO]
        return if (userInfoJson != null) {
            try {
                json.decodeFromString<GitHubUser>(userInfoJson)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    /**
     * 检查是否已登录
     */
    suspend fun isLoggedIn(): Boolean {
        val preferences = context.githubAuthDataStore.data.first()
        return (preferences[IS_LOGGED_IN] ?: false) && isAuthSessionCurrent(preferences)
    }

    /**
     * 登出
     */
    suspend fun logout() {
        context.githubAuthDataStore.edit { preferences ->
            preferences.clear()
        }
    }

    suspend fun setPendingOAuthState(state: String) {
        context.githubAuthDataStore.edit { preferences ->
            preferences[PENDING_OAUTH_STATE] = state
        }
    }

    suspend fun consumePendingOAuthState(): String? {
        val preferences = context.githubAuthDataStore.data.first()
        val state = preferences[PENDING_OAUTH_STATE]
        context.githubAuthDataStore.edit { mutablePreferences ->
            mutablePreferences.remove(PENDING_OAUTH_STATE)
        }
        return state
    }

    /**
     * 保存 PKCE code_verifier（启动授权时调用）
     */
    suspend fun saveCodeVerifier(codeVerifier: String) {
        context.githubAuthDataStore.edit { preferences ->
            preferences[PENDING_CODE_VERIFIER] = codeVerifier
        }
    }

    /**
     * 消费 PKCE code_verifier（token exchange 时调用，用后即焚）
     */
    suspend fun consumeCodeVerifier(): String? {
        val preferences = context.githubAuthDataStore.data.first()
        val verifier = preferences[PENDING_CODE_VERIFIER]
        context.githubAuthDataStore.edit { mutablePreferences ->
            mutablePreferences.remove(PENDING_CODE_VERIFIER)
        }
        return verifier
    }

    /**
     * 生成GitHub OAuth PKCE授权URL
     *
     * PKCE 流程（RFC 7636）：
     * 1. 生成随机 code_verifier
     * 2. 计算 code_challenge = BASE64URL(SHA256(code_verifier))
     * 3. 授权 URL 包含 code_challenge + code_challenge_method=S256
     * 4. 回调时用 code_verifier 换取 token（无需 client_secret）
     */
    suspend fun getAuthorizationUrlWithPKCE(state: String = createOAuthState()): String {
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = computeCodeChallenge(codeVerifier)

        // 保存 state 和 code_verifier 供回调时验证
        setPendingOAuthState(state)
        saveCodeVerifier(codeVerifier)

        Log.d(TAG, "PKCE: code_verifier length=${codeVerifier.length}, code_challenge length=${codeChallenge.length}")

        return Uri.parse("https://github.com/login/oauth/authorize")
            .buildUpon()
            .appendQueryParameter("client_id", GITHUB_CLIENT_ID)
            .appendQueryParameter("redirect_uri", GITHUB_REDIRECT_URI)
            .appendQueryParameter("scope", GITHUB_SCOPE)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
            .toString()
    }

    /**
     * 生成GitHub OAuth授权URL（兼容旧调用方，内部走 PKCE）
     */
    suspend fun getAuthorizationUrl(state: String = createOAuthState()): String {
        return getAuthorizationUrlWithPKCE(state)
    }

    /**
     * 用 PKCE 方式交换 authorization code 获取 access token。
     *
     * POST https://github.com/login/oauth/access_token
     * Body: client_id + code + code_verifier + redirect_uri
     * 不需要 client_secret（PKCE 用 code_verifier 替代）
     *
     * @param code OAuth 回调返回的 authorization code
     * @param state OAuth 回调返回的 state（用于验证 CSRF）
     * @return exchange 结果
     */
    suspend fun exchangeCodeForToken(code: String, state: String): ExchangeResult {
        // 验证 state 防止 CSRF
        val pendingState = consumePendingOAuthState()
        if (pendingState != state) {
            Log.e(TAG, "OAuth state mismatch: expected=$pendingState, got=$state")
            return ExchangeResult.Error("Security: state mismatch, possible CSRF attack")
        }

        // 获取 code_verifier
        val codeVerifier = consumeCodeVerifier()
        if (codeVerifier == null) {
            Log.e(TAG, "PKCE code_verifier not found")
            return ExchangeResult.Error("PKCE code_verifier not found, please retry login")
        }

        return try {
            val url = URL("https://github.com/login/oauth/access_token")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true

            // PKCE: 不需要 client_secret，用 code_verifier 替代
            val body = """{"client_id":"$GITHUB_CLIENT_ID","code":"$code","code_verifier":"$codeVerifier","redirect_uri":"$GITHUB_REDIRECT_URI"}"""

            conn.outputStream.use { os ->
                os.write(body.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val responseCode = conn.responseCode
            val responseBody = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            conn.disconnect()

            if (responseCode !in 200..299) {
                Log.e(TAG, "Token exchange failed: HTTP $responseCode - $responseBody")
                return ExchangeResult.Error("Token exchange failed: HTTP $responseCode")
            }

            val jsonResp = org.json.JSONObject(responseBody)
            val error = jsonResp.optString("error", "")
            if (error.isNotEmpty()) {
                val errorDesc = jsonResp.optString("error_description", error)
                Log.e(TAG, "Token exchange error: $error - $errorDesc")
                return ExchangeResult.Error(errorDesc)
            }

            val accessToken = jsonResp.getString("access_token")
            val tokenType = jsonResp.optString("token_type", "bearer")
            val expiresIn = jsonResp.optLong("expires_in", -1).let { if (it > 0) it else null }
            val refreshToken = jsonResp.optString("refresh_token", null)
            val grantedScope = jsonResp.optString("scope", "")

            Log.i(TAG, "PKCE token exchange success, scope: $grantedScope")

            ExchangeResult.Success(
                accessToken = accessToken,
                tokenType = tokenType,
                expiresIn = expiresIn,
                refreshToken = refreshToken,
                grantedScope = grantedScope
            )
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange exception", e)
            ExchangeResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Token exchange 结果
     */
    sealed class ExchangeResult {
        data class Success(
            val accessToken: String,
            val tokenType: String,
            val expiresIn: Long?,
            val refreshToken: String?,
            val grantedScope: String
        ) : ExchangeResult()

        data class Error(val message: String) : ExchangeResult()
    }

    /**
     * 获取访问令牌的授权头
     */
    suspend fun getAuthorizationHeader(): String? {
        val token = getCurrentAccessToken()
        return if (token != null) {
            "Bearer $token"
        } else {
            null
        }
    }
} 
