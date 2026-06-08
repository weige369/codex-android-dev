package com.codex.android.ui.chat

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codex.android.ui.theme.*

/**
 * AI 聊天界面。
 *
 * 支持：
 * - Codex / API 双模式切换
 * - Agent 状态指示器（连接中、就绪、忙碌、错误）
 * - 流式消息渲染
 * - 错误处理与重试
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIChatScreen(
    onBack: () -> Unit = {},
    chatViewModel: ChatViewModel = viewModel()
) {
    val messages by chatViewModel.messages.collectAsState()
    val chatMode by chatViewModel.chatMode.collectAsState()
    val agentStatus by chatViewModel.agentStatus.collectAsState()
    val isProcessing by chatViewModel.isProcessing.collectAsState()
    val errorMessage by chatViewModel.errorMessage.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("AI 对话", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        AgentStatusIndicator(status = agentStatus)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // Mode toggle
                    ModeToggleButton(
                        mode = chatMode,
                        onToggle = { chatViewModel.toggleChatMode() }
                    )
                    // Clear
                    IconButton(onClick = { chatViewModel.clearMessages() }) {
                        Icon(Icons.Default.DeleteSweep, "清空对话", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            // Error banner
            errorMessage?.let { error ->
                ErrorBanner(
                    message = error,
                    onDismiss = { chatViewModel.clearError() },
                    onRetry = { chatViewModel.retryLast() }
                )
            }

            // Input bar
            ChatInputBar(
                text = inputText,
                onTextChange = { inputText = it },
                onSend = {
                    if (inputText.isNotBlank()) {
                        chatViewModel.sendMessage(inputText)
                        inputText = ""
                    }
                },
                isEnabled = !isProcessing
            )
        }
    ) { padding ->
        if (messages.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = CodexPrimary.copy(alpha = 0.1f),
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("AI", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = CodexPrimary)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (chatMode == ChatViewModel.ChatMode.CODEX) "Codex 模式" else "API 模式",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (chatMode == ChatViewModel.ChatMode.CODEX)
                            "通过本地 Codex CLI 进行 AI 对话"
                        else
                            "通过 OpenAI 兼容 API 进行 AI 对话",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 12.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    ChatBubble(
                        message = message,
                        isLastStreaming = message == messages.lastOrNull() && message.isStreaming
                    )
                }
            }
        }
    }
}

// =============================================================================
// Sub-components
// =============================================================================

/**
 * Agent 状态指示器 — 小彩色圆点 + 文字标签
 */
@Composable
private fun AgentStatusIndicator(status: ChatViewModel.AgentStatus) {
    val (color, label) = when (status) {
        ChatViewModel.AgentStatus.DISCONNECTED -> StatusOffline to "离线"
        ChatViewModel.AgentStatus.CONNECTING -> StatusWarning to "连接中"
        ChatViewModel.AgentStatus.CONNECTED -> StatusOnline to "就绪"
        ChatViewModel.AgentStatus.BUSY -> StatusWarning to "忙碌"
        ChatViewModel.AgentStatus.ERROR -> StatusError to "错误"
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color)
        }
    }
}

/**
 * Codex / API 模式切换按钮
 */
@Composable
private fun ModeToggleButton(
    mode: ChatViewModel.ChatMode,
    onToggle: () -> Unit
) {
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (mode == ChatViewModel.ChatMode.CODEX) Icons.Default.Terminal else Icons.Default.Cloud,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (mode == ChatViewModel.ChatMode.CODEX) CodexPrimary else Color(0xFF2196F3)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (mode == ChatViewModel.ChatMode.CODEX) "Codex" else "API",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Icon(
                Icons.Default.SwapHoriz,
                contentDescription = "切换",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 聊天气泡 — 用户/助手消息
 */
@Composable
private fun ChatBubble(
    message: ChatViewModel.ChatMessage,
    isLastStreaming: Boolean
) {
    val isUser = message.role == ChatViewModel.Role.USER
    val bgColor = if (isUser) CodexPrimary.copy(alpha = 0.12f)
                  else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val shape = if (isUser)
        RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
    else
        RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        // Assistant avatar
        if (!isUser) {
            Surface(
                shape = CircleShape,
                color = CodexPrimary.copy(alpha = 0.15f),
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("Cx", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = CodexPrimary)
                }
            }
            Spacer(Modifier.width(8.dp))
        }

        Surface(
            shape = shape,
            color = bgColor,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    message.content.ifEmpty { "..." },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 20.sp,
                    fontFamily = if (isUser) FontFamily.Default else FontFamily.Monospace
                )
                // Streaming cursor
                if (isLastStreaming && message.content.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(CodexPrimary)
                    )
                }
            }
        }

        // User avatar
        if (isUser) {
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * 输入栏
 */
@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isEnabled: Boolean
) {
    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息...", fontSize = 14.sp) },
                enabled = isEnabled,
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CodexPrimary.copy(alpha = 0.4f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                ),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp)
            )

            // Send button
            FilledIconButton(
                onClick = onSend,
                enabled = isEnabled && text.isNotBlank(),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = CodexPrimary,
                    disabledContainerColor = CodexPrimary.copy(alpha = 0.3f)
                ),
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    "发送",
                    modifier = Modifier.size(20.dp),
                    tint = Color.White
                )
            }
        }
    }
}

/**
 * 错误横幅
 */
@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    Surface(
        color = StatusError.copy(alpha = 0.08f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ErrorOutline, null, tint = StatusError, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                message,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                color = StatusError,
                maxLines = 2
            )
            TextButton(onClick = onRetry) {
                Text("重试", fontSize = 12.sp)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, "关闭", modifier = Modifier.size(14.dp))
            }
        }
    }
}