package com.codex.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.agent.ApiProvider
import com.codex.android.agent.NativeAgentService
import kotlinx.coroutines.launch

/**
 * AI 提供商配置界面。
 * 支持选择多个提供商、配置 API Key 和自定义端点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiProviderScreen(
    onBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val agent = remember { NativeAgentService.getInstance(context) }

    var selectedProviderId by remember { mutableStateOf(agent.getProviderId()) }
    var apiKey by remember { mutableStateOf(agent.getApiKey()) }
    var customUrl by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(agent.getApiModel()) }
    var showApiKey by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI 提供商", fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // 说明
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                    )
                ) {
                    Text(
                        "选择 AI 提供商并配置 API Key 即可使用 Codex。\n" +
                        "无需安装任何外部二进制。\n" +
                        "推荐中国大陆用户使用 DeepSeek 或 SiliconFlow。",
                        modifier = Modifier.padding(16.dp),
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                }
            }

            // 提供商列表
            item {
                Text("选择提供商", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }

            items(ApiProvider.BUILT_IN) { provider ->
                ProviderCard(
                    provider = provider,
                    isSelected = provider.id == selectedProviderId,
                    onSelect = {
                        selectedProviderId = provider.id
                        model = provider.defaultModel
                        testResult = null
                    }
                )
            }

            // API Key
            item {
                Spacer(Modifier.height(8.dp))
                Text("API Key", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        testResult = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("sk-...") },
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null
                            )
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }

            // 自定义 URL（仅 openai-compatible）
            if (selectedProviderId == "openai-compatible") {
                item {
                    Text("自定义 API 端点", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = {
                            customUrl = it
                            testResult = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://your-api-endpoint.com/v1") },
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )
                }
            }

            // 模型
            item {
                Text("模型", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = {
                        model = it
                        testResult = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("gpt-4o") },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }

            // 保存 & 测试
            item {
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            agent.setConfig(selectedProviderId, apiKey, customUrl, model)
                            testResult = "✅ 配置已保存"
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("保存配置")
                    }
                    OutlinedButton(
                        onClick = {
                            agent.setConfig(selectedProviderId, apiKey, customUrl, model)
                            isTesting = true
                            testResult = null
                            scope.launch {
                                val ok = agent.testConnection()
                                isTesting = false
                                testResult = if (ok) "✅ API 连接成功！" else "❌ API 连接失败，请检查配置"
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isTesting
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("测试连接")
                        }
                    }
                }
            }

            // 测试结果
            if (testResult != null) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (testResult!!.startsWith("✅"))
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Text(
                            testResult!!,
                            modifier = Modifier.padding(12.dp),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // 获取 API Key 链接
            item {
                Spacer(Modifier.height(16.dp))
                Text("获取 API Key", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                val selectedProvider = ApiProvider.getById(selectedProviderId)
                val links = when (selectedProviderId) {
                    "deepseek" -> "https://platform.deepseek.com/api_keys"
                    "openai" -> "https://platform.openai.com/api-keys"
                    "siliconflow" -> "https://cloud.siliconflow.cn/account/ak"
                    "zhipu" -> "https://open.bigmodel.cn/usercenter/apikeys"
                    "moonshot" -> "https://platform.moonshot.cn/console/api-keys"
                    else -> ""
                }
                if (links.isNotBlank()) {
                    Text(
                        links,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: ApiProvider,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        onClick = onSelect,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(provider.name, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                if (provider.baseUrl.isNotBlank()) {
                    Text(
                        provider.baseUrl,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "默认模型: ${provider.defaultModel}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
