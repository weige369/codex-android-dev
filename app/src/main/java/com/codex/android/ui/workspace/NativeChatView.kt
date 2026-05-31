package com.codex.android.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.agent.NativeAgentService
import com.codex.android.ui.theme.CodexBrandOrange
import kotlinx.coroutines.launch

/**
 * 原生 AI 聊天界面。
 * 在 NATIVE_MODE 下替代 WebView，直接在 Compose 中显示对话。
 */
@Composable
fun NativeChatView(
    agent: NativeAgentService,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var isStreaming by remember { mutableStateOf(false) }
    var currentStreamContent by remember { mutableStateOf("") }

    LaunchedEffect(agent.connectionState) {
        isStreaming = agent.connectionState.value.name == "STREAMING"
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 消息列表
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages) { msg ->
                MessageBubble(msg)
            }
            // 流式输出中的内容
            if (currentStreamContent.isNotBlank()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            currentStreamContent,
                            modifier = Modifier.padding(12.dp),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // 输入区域
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息...", fontSize = 14.sp) },
                    shape = RoundedCornerShape(20.dp),
                    maxLines = 4,
                    enabled = !isStreaming
                )
                Spacer(Modifier.width(8.dp))
                if (isStreaming) {
                    IconButton(onClick = { agent.cancelStream() }) {
                        Icon(Icons.Default.Stop, "停止", tint = MaterialTheme.colorScheme.error)
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                val prompt = inputText
                                inputText = ""
                                messages = messages + ChatMessage("user", prompt)
                                currentStreamContent = ""

                                scope.launch {
                                    with(kotlinx.coroutines.Dispatchers.IO) {
                                        agent.sendPromptStream(
                                            prompt = prompt,
                                            onChunk = { chunk ->
                                                currentStreamContent += chunk
                                            },
                                            onComplete = { fullResponse ->
                                                messages = messages + ChatMessage("assistant", fullResponse)
                                                currentStreamContent = ""
                                            },
                                            onError = { error ->
                                                messages = messages + ChatMessage("error", error)
                                                currentStreamContent = ""
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        enabled = inputText.isNotBlank()
                    ) {
                        Icon(
                            Icons.Default.Send, "发送",
                            tint = if (inputText.isNotBlank()) CodexBrandOrange
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }
    }
}

data class ChatMessage(val role: String, val content: String)

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    val isError = msg.role == "error"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = when {
                isUser -> CodexBrandOrange.copy(alpha = 0.15f)
                isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
            modifier = Modifier.fillMaxWidth(if (isUser) 0.85f else 1f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    when (msg.role) {
                        "user" -> "你"
                        "error" -> "⚠️ 错误"
                        else -> "🤖 Codex"
                    },
                    fontSize = 11.sp,
                    color = when {
                        isUser -> CodexBrandOrange
                        isError -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    msg.content,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
