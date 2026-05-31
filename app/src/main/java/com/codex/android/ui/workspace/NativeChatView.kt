package com.codex.android.ui.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.android.agent.NativeAgentService
import com.codex.android.ui.theme.CodexBrandOrange
import com.codex.android.ui.theme.CodexCodeBg
import com.codex.android.ui.theme.CodexOnSurface
import com.codex.android.ui.theme.CodexOnSurfaceVariant
import com.codex.android.ui.theme.CodexOutline
import com.codex.android.ui.theme.CodexSurface
import com.codex.android.ui.theme.CodexSurfaceVariant
import com.codex.android.ui.theme.CodexError
import com.codex.android.ui.theme.CodexAccent
import com.codex.android.ui.theme.CodexPrimaryLight
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ============================================================================
// Data Models
// ============================================================================

/**
 * 增强版聊天消息模型，支持思考过程、工具调用和元数据。
 */
data class ChatMessage(
    val role: String,
    val content: String,
    val thinkingContent: String = "",
    val toolCalls: List<ToolCallInfo> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val tokenCount: Int = 0,
    val durationMs: Long = 0,
    val id: String = java.util.UUID.randomUUID().toString()
)

/**
 * 工具调用信息。
 */
data class ToolCallInfo(
    val name: String,
    val arguments: String = "",
    val result: String = "",
    val durationMs: Long = 0,
    val isSuccess: Boolean = true
)

// ============================================================================
// Main Chat View
// ============================================================================

/**
 * 原生 AI 聊天界面 - 专业级升级版。
 *
 * 设计灵感来自 Operit ChatArea：
 * - Cursor 风格消息布局（左对齐、无气泡、标签区分）
 * - 流式 Markdown 渲染（代码块高亮+复制）
 * - 思考过程折叠（动画展开/收起）
 * - 工具调用展示（折叠式+状态图标）
 * - 三点跳动加载动画
 * - 智能自动滚动
 * - 长按上下文菜单
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NativeChatView(
    agent: NativeAgentService,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var inputText by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var isStreaming by remember { mutableStateOf(false) }
    var currentStreamContent by remember { mutableStateOf("") }
    var currentThinkingContent by remember { mutableStateOf("") }
    val currentToolCalls = remember { mutableStateListOf<ToolCallInfo>() }

    // 长按菜单状态
    var menuMessageId by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    // 自动滚动控制
    val canAutoScroll by remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems == 0 || lastVisibleIndex >= totalItems - 2
        }
    }

    // 监听流式状态
    LaunchedEffect(agent.connectionState) {
        isStreaming = agent.connectionState.value.name == "STREAMING"
    }

    // 自动滚动到底部
    LaunchedEffect(messages.size, currentStreamContent) {
        if (canAutoScroll && messages.isNotEmpty()) {
            val targetIndex = if (currentStreamContent.isNotBlank()) messages.size else messages.size - 1
            if (targetIndex >= 0) {
                listState.animateScrollToItem(targetIndex)
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ===== 消息列表 =====
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
        ) {
            items(
                items = messages,
                key = { it.id }
            ) { msg ->
                CursorStyleMessage(
                    message = msg,
                    onCopy = {
                        copyToClipboard(context, msg.content)
                    },
                    onResend = {
                        if (msg.role == "user") {
                            val prompt = msg.content
                            scope.launch {
                                sendUserMessage(
                                    agent = agent,
                                    prompt = prompt,
                                    messages = messages,
                                    onStreamingChange = { isStreaming = it },
                                    onStreamContentChange = { currentStreamContent = it },
                                    onThinkingChange = { currentThinkingContent = it },
                                    onToolCallsChange = { currentToolCalls.clear(); currentToolCalls.addAll(it) }
                                )
                            }
                        }
                    },
                    onDelete = {
                        val idx = messages.indexOf(msg)
                        if (idx >= 0) messages.removeAt(idx)
                    },
                    onRetry = {
                        // 错误重试：重新发送上一条用户消息
                        val errorIdx = messages.indexOf(msg)
                        if (errorIdx > 0) {
                            val prevUserMsg = messages.subList(0, errorIdx).lastOrNull { it.role == "user" }
                            if (prevUserMsg != null) {
                                messages.removeAt(errorIdx)
                                scope.launch {
                                    sendUserMessage(
                                        agent = agent,
                                        prompt = prevUserMsg.content,
                                        messages = messages,
                                        onStreamingChange = { isStreaming = it },
                                        onStreamContentChange = { currentStreamContent = it },
                                        onThinkingChange = { currentThinkingContent = it },
                                        onToolCallsChange = { currentToolCalls.clear(); currentToolCalls.addAll(it) }
                                    )
                                }
                            }
                        }
                    },
                    menuMessageId = menuMessageId,
                    onMenuShow = { messageId ->
                        menuMessageId = messageId
                        menuExpanded = true
                    },
                    menuExpanded = menuExpanded && menuMessageId == msg.id,
                    onMenuDismiss = {
                        menuExpanded = false
                        menuMessageId = null
                    }
                )
            }

            // 流式输出中的内容
            if (currentStreamContent.isNotBlank() || isStreaming) {
                item {
                    StreamingMessage(
                        content = currentStreamContent,
                        thinkingContent = currentThinkingContent,
                        toolCalls = currentToolCalls.toList(),
                        isStreaming = isStreaming
                    )
                }
            }

            // 仅加载指示器（流开始但还没内容）
            if (isStreaming && currentStreamContent.isBlank() && currentThinkingContent.isBlank()) {
                item {
                    LoadingDotsIndicator()
                }
            }
        }

        // ===== 输入区域 =====
        ChatInputArea(
            inputText = inputText,
            onInputTextChange = { inputText = it },
            isStreaming = isStreaming,
            onSend = {
                if (inputText.isNotBlank()) {
                    val prompt = inputText
                    inputText = ""
                    currentStreamContent = ""
                    currentThinkingContent = ""
                    currentToolCalls.clear()

                    scope.launch {
                        sendUserMessage(
                            agent = agent,
                            prompt = prompt,
                            messages = messages,
                            onStreamingChange = { isStreaming = it },
                            onStreamContentChange = { currentStreamContent = it },
                            onThinkingChange = { currentThinkingContent = it },
                            onToolCallsChange = { currentToolCalls.clear(); currentToolCalls.addAll(it) }
                        )
                    }
                }
            },
            onStop = {
                agent.cancelStream()
                isStreaming = false
            }
        )
    }
}

// ============================================================================
// Cursor-Style Message
// ============================================================================

/**
 * Cursor 风格消息组件。
 * 全宽、左对齐，顶部用标签区分角色，不用气泡。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CursorStyleMessage(
    message: ChatMessage,
    onCopy: () -> Unit,
    onResend: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    menuMessageId: String?,
    onMenuShow: (String) -> Unit,
    menuExpanded: Boolean,
    onMenuDismiss: () -> Unit
) {
    val isUser = message.role == "user"
    val isError = message.role == "error"
    val isAssistant = message.role == "assistant"

    // 用户消息带暖橙半透明背景
    val bgColor = when {
        isUser -> CodexBrandOrange.copy(alpha = 0.06f)
        isError -> CodexError.copy(alpha = 0.08f)
        else -> androidx.compose.ui.graphics.Color.Transparent
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = { onMenuShow(message.id) }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor, RoundedCornerShape(6.dp))
                .padding(horizontal = 4.dp, vertical = 8.dp)
                .animateContentSize()
        ) {
            // ===== 角色标签行 =====
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                Text(
                    text = when {
                        isUser -> "You"
                        isError -> "⚠ Error"
                        else -> "Response"
                    },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        isUser -> CodexBrandOrange
                        isError -> CodexError
                        else -> CodexOnSurfaceVariant.copy(alpha = 0.6f)
                    },
                    letterSpacing = 0.5.sp
                )

                // 错误消息重试按钮
                if (isError) {
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = onRetry,
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "重试",
                            tint = CodexBrandOrange,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                // 模型名（助手消息）
                if (isAssistant && message.tokenCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${message.tokenCount} tokens",
                        fontSize = 10.sp,
                        color = CodexOnSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }

            // ===== 思考过程折叠区 =====
            if (message.thinkingContent.isNotBlank()) {
                ThinkingSection(content = message.thinkingContent)
                Spacer(Modifier.height(6.dp))
            }

            // ===== 工具调用折叠区 =====
            if (message.toolCalls.isNotEmpty()) {
                message.toolCalls.forEach { toolCall ->
                    ToolCallSection(toolCall = toolCall)
                    Spacer(Modifier.height(6.dp))
                }
            }

            // ===== 消息正文（Markdown 渲染） =====
            if (message.content.isNotBlank()) {
                MarkdownText(
                    text = message.content,
                    isCode = isError
                )
            }

            // ===== 消息底部元数据 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 耗时
                if (message.durationMs > 0) {
                    Text(
                        text = formatDuration(message.durationMs),
                        fontSize = 10.sp,
                        color = CodexOnSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(Modifier.width(8.dp))
                }
                // 时间戳
                Text(
                    text = formatTimestamp(message.timestamp),
                    fontSize = 10.sp,
                    color = CodexOnSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }

        // 长按菜单
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = onMenuDismiss,
            modifier = Modifier.background(CodexSurface)
        ) {
            DropdownMenuItem(
                text = { Text("复制", fontSize = 13.sp) },
                onClick = { onCopy(); onMenuDismiss() },
                leadingIcon = {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp),
                        tint = CodexOnSurfaceVariant)
                }
            )
            DropdownMenuItem(
                text = { Text("重新发送", fontSize = 13.sp) },
                onClick = { onResend(); onMenuDismiss() },
                leadingIcon = {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp),
                        tint = CodexOnSurfaceVariant)
                }
            )
            HorizontalDivider(color = CodexOutline.copy(alpha = 0.3f))
            DropdownMenuItem(
                text = { Text("删除", fontSize = 13.sp, color = CodexError) },
                onClick = { onDelete(); onMenuDismiss() },
                leadingIcon = {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp),
                        tint = CodexError)
                }
            )
        }
    }
}

// ============================================================================
// Streaming Message
// ============================================================================

/**
 * 流式输出中的消息组件。
 */
@Composable
private fun StreamingMessage(
    content: String,
    thinkingContent: String,
    toolCalls: List<ToolCallInfo>,
    isStreaming: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp)
    ) {
        // 角色标签
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 6.dp)
        ) {
            Text(
                "Response",
                fontSize = 11.sp,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                color = CodexOnSurfaceVariant.copy(alpha = 0.6f),
                letterSpacing = 0.5.sp
            )
            Spacer(Modifier.width(6.dp))
            if (isStreaming) {
                LoadingDotsIndicator(modifier = Modifier.size(16.dp))
            }
        }

        // 思考过程
        if (thinkingContent.isNotBlank()) {
            ThinkingSection(content = thinkingContent, isStreaming = true)
            Spacer(Modifier.height(6.dp))
        }

        // 工具调用
        if (toolCalls.isNotEmpty()) {
            toolCalls.forEach { toolCall ->
                ToolCallSection(toolCall = toolCall)
                Spacer(Modifier.height(6.dp))
            }
        }

        // 正文
        if (content.isNotBlank()) {
            MarkdownText(text = content)
        } else if (isStreaming && thinkingContent.isBlank() && toolCalls.isEmpty()) {
            // 只有加载动画，内容还没来
            LoadingDotsIndicator()
        }
    }
}

// ============================================================================
// Thinking Section
// ============================================================================

/**
 * 思考过程折叠区域。
 * 带动画展开/收起，暗色背景+小字体。
 */
@Composable
private fun ThinkingSection(
    content: String,
    isStreaming: Boolean = false
) {
    var expanded by remember { mutableStateOf(isStreaming) }

    // 流式结束后自动折叠
    LaunchedEffect(isStreaming) {
        if (!isStreaming) expanded = false
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = CodexSurfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(300))
        ) {
            // 标题行（可点击折叠）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isStreaming) "Thinking..." else "Thought process",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = CodexOnSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(Modifier.weight(1f))
                if (isStreaming) {
                    LoadingDotsIndicator(modifier = Modifier.size(14.dp))
                } else {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "折叠" else "展开",
                        tint = CodexOnSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // 展开内容
            if (expanded) {
                Text(
                    text = content,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = CodexOnSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

// ============================================================================
// Tool Call Section
// ============================================================================

/**
 * 工具调用折叠区域。
 * 显示工具名+耗时+状态，展开后显示输出。
 */
@Composable
private fun ToolCallSection(toolCall: ToolCallInfo) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = CodexSurfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(300))
        ) {
            // 标题行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Build,
                    contentDescription = "工具",
                    tint = if (toolCall.isSuccess) CodexAccent else CodexError,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    toolCall.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = CodexOnSurface
                )
                Spacer(Modifier.width(8.dp))
                if (toolCall.durationMs > 0) {
                    Text(
                        formatDuration(toolCall.durationMs),
                        fontSize = 10.sp,
                        color = CodexOnSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                Spacer(Modifier.weight(1f))
                // 状态图标
                Text(
                    if (toolCall.isSuccess) "✓" else "✗",
                    fontSize = 12.sp,
                    color = if (toolCall.isSuccess) CodexAccent else CodexError
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "折叠" else "展开",
                    tint = CodexOnSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }

            // 展开后显示参数和结果
            if (expanded) {
                // 参数
                if (toolCall.arguments.isNotBlank()) {
                    Text(
                        text = "Args: ${toolCall.arguments.take(500)}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CodexOnSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
                // 结果
                if (toolCall.result.isNotBlank()) {
                    Text(
                        text = "Output: ${toolCall.result.take(800)}${if (toolCall.result.length > 800) "…" else ""}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = CodexOnSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

// ============================================================================
// Markdown Rendering
// ============================================================================

/**
 * 基础 Markdown 渲染器（不引入外部库）。
 * 支持：代码块（深色背景+复制按钮）、行内代码、粗体、斜体、列表、链接。
 */
@Composable
private fun MarkdownText(
    text: String,
    isCode: Boolean = false
) {
    val context = LocalContext.current

    if (isCode) {
        // 错误消息直接显示
        Text(
            text = text,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = CodexError,
            lineHeight = 19.sp
        )
        return
    }

    // 按代码块分割
    val segments = splitCodeBlocks(text)

    Column(modifier = Modifier.fillMaxWidth()) {
        segments.forEach { segment ->
            when (segment) {
                is MarkdownSegment.CodeBlock -> {
                    CodeBlockView(
                        code = segment.content,
                        language = segment.language,
                        context = context
                    )
                    Spacer(Modifier.height(6.dp))
                }
                is MarkdownSegment.TextBlock -> {
                    val annotatedString = parseInlineMarkdown(segment.content)
                    Text(
                        text = annotatedString,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = CodexOnSurface,
                        fontFamily = FontFamily.SansSerif
                    )
                }
            }
        }
    }
}

/**
 * 代码块视图：深色背景 + 圆角 + 复制按钮。
 */
@Composable
private fun CodeBlockView(
    code: String,
    language: String,
    context: Context
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = CodexCodeBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // 顶部栏：语言标签 + 复制按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CodexOutline.copy(alpha = 0.2f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.ifBlank { "code" },
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = CodexOnSurfaceVariant.copy(alpha = 0.6f)
                )
                IconButton(
                    onClick = { copyToClipboard(context, code) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制代码",
                        tint = CodexOnSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            // 代码内容
            Text(
                text = code.trimEnd(),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = CodexOnSurface.copy(alpha = 0.9f),
                lineHeight = 17.sp,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}

// ============================================================================
// Markdown Parser Helpers
// ============================================================================

/**
 * Markdown 分段：代码块 vs 文本块。
 */
private sealed class MarkdownSegment {
    data class CodeBlock(val content: String, val language: String) : MarkdownSegment()
    data class TextBlock(val content: String) : MarkdownSegment()
}

/**
 * 按代码块 (```) 分割文本。
 */
private fun splitCodeBlocks(text: String): List<MarkdownSegment> {
    val result = mutableListOf<MarkdownSegment>()
    val codeBlockRegex = Regex("""```(\w*)\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
    var lastIndex = 0

    codeBlockRegex.findAll(text).forEach { match ->
        // 代码块前的文本
        if (match.range.first > lastIndex) {
            val beforeText = text.substring(lastIndex, match.range.first).trim()
            if (beforeText.isNotEmpty()) {
                result.add(MarkdownSegment.TextBlock(beforeText))
            }
        }
        // 代码块
        val language = match.groupValues[1]
        val code = match.groupValues[2]
        result.add(MarkdownSegment.CodeBlock(code, language))
        lastIndex = match.range.last + 1
    }

    // 最后的文本
    if (lastIndex < text.length) {
        val remaining = text.substring(lastIndex).trim()
        if (remaining.isNotEmpty()) {
            result.add(MarkdownSegment.TextBlock(remaining))
        }
    }

    return result
}

/**
 * 解析行内 Markdown：粗体、斜体、行内代码、列表、链接。
 */
private fun parseInlineMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        val lines = text.split("\n")
        lines.forEachIndexed { index, line ->
            if (index > 0) append("\n")
            parseLine(line, this)
        }
    }
}

/**
 * 解析单行 Markdown。
 */
private fun parseLine(line: String, builder: AnnotatedString.Builder) {
    var remaining = line
    val patterns = listOf(
        // 粗斜体
        Regex("""\*\*\*(.+?)\*\*\*"""),
        // 粗体
        Regex("""\*\*(.+?)\*\*"""),
        // 斜体
        Regex("""\*(.+?)\*"""),
        // 行内代码
        Regex("""`(.+?)`"""),
        // 链接
        Regex("""\[(.+?)\]\((.+?)\)""")
    )

    // 列表处理
    val listMatch = Regex("""^(\s*)([-*•]|\d+\.)\s(.*)""").matchEntire(remaining)
    if (listMatch != null) {
        val indent = listMatch.groupValues[1].length
        val bullet = listMatch.groupValues[2]
        val content = listMatch.groupValues[3]
        // 缩进
        repeat(indent / 2) { builder.append("  ") }
        builder.append("  $bullet ")
        parseInlineContent(content, builder)
        return
    }

    // 标题处理
    val headingMatch = Regex("""^(#{1,6})\s(.*)""").matchEntire(remaining)
    if (headingMatch != null) {
        val level = headingMatch.groupValues[1].length
        val content = headingMatch.groupValues[2]
        val weight = when (level) {
            1 -> FontWeight.Bold
            2 -> FontWeight.Bold
            3 -> FontWeight.SemiBold
            else -> FontWeight.Medium
        }
        val size = when (level) {
            1 -> 20.sp
            2 -> 17.sp
            3 -> 15.sp
            else -> 14.sp
        }
        withStyle(SpanStyle(fontWeight = weight, fontSize = size, color = CodexOnSurface)) {
            parseInlineContent(content, builder)
        }
        return
    }

    parseInlineContent(remaining, builder)
}

/**
 * 解析行内 Markdown 格式（粗体、斜体、代码、链接）。
 */
private fun parseInlineContent(text: String, builder: AnnotatedString.Builder) {
    var i = 0
    while (i < text.length) {
        when {
            // 粗斜体 ***text***
            text.startsWith("***", i) -> {
                val end = text.indexOf("***", i + 3)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 3, end))
                    }
                    i = end + 3
                } else {
                    append(text[i]); i++
                }
            }
            // 粗体 **text**
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(text[i]); i++
                }
            }
            // 斜体 *text*
            text.startsWith("*", i) && !text.startsWith("**", i) -> {
                val end = text.indexOf("*", i + 1)
                if (end > i) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }
            // 行内代码 `code`
            text.startsWith("`", i) -> {
                val end = text.indexOf("`", i + 1)
                if (end > i) {
                    withStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = CodexSurfaceVariant.copy(alpha = 0.4f),
                        color = CodexPrimaryLight
                    )) {
                        append(" ${text.substring(i + 1, end)} ")
                    }
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }
            // 链接 [text](url)
            text.startsWith("[", i) -> {
                val textEnd = text.indexOf("](", i)
                if (textEnd > i) {
                    val urlEnd = text.indexOf(")", textEnd)
                    if (urlEnd > textEnd) {
                        val linkText = text.substring(i + 1, textEnd)
                        val url = text.substring(textEnd + 2, urlEnd)
                        withStyle(SpanStyle(
                            color = CodexBrandOrange,
                            textDecoration = TextDecoration.Underline
                        )) {
                            append(linkText)
                        }
                        i = urlEnd + 1
                    } else {
                        append(text[i]); i++
                    }
                } else {
                    append(text[i]); i++
                }
            }
            else -> {
                append(text[i]); i++
            }
        }
    }
}

// ============================================================================
// Loading Dots Indicator
// ============================================================================

/**
 * 三点跳动加载动画。
 * 参考 Operit LoadingDotsIndicator：3个圆点依次弹跳。
 */
@Composable
private fun LoadingDotsIndicator(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "loadingDots")

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val offsetY by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 600
                        0f at (index * 100)
                        -8f at (150 + index * 100)
                        0f at (300 + index * 100)
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "dot_$index"
            )

            Box(
                modifier = Modifier
                    .size(5.dp)
                    .offset { IntOffset(0, offsetY.roundToInt()) }
                    .clip(CircleShape)
                    .background(CodexBrandOrange)
            )
            if (index < 2) {
                Spacer(Modifier.width(3.dp))
            }
        }
    }
}

// ============================================================================
// Chat Input Area
// ============================================================================

/**
 * 升级版输入区域。
 * 圆角输入框 + 品牌色发送按钮 + 红色停止按钮。
 */
@Composable
private fun ChatInputArea(
    inputText: String,
    onInputTextChange: (String) -> Unit,
    isStreaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CodexSurface.copy(alpha = 0.95f),
        tonalElevation = 3.dp
    ) {
        // 顶部分隔线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(CodexOutline.copy(alpha = 0.3f))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 输入框
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputTextChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        "发送消息…",
                        fontSize = 14.sp,
                        color = CodexOnSurfaceVariant.copy(alpha = 0.4f)
                    )
                },
                shape = RoundedCornerShape(24.dp),
                maxLines = 4,
                enabled = !isStreaming,
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    color = CodexOnSurface,
                    lineHeight = 19.sp
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CodexBrandOrange.copy(alpha = 0.5f),
                    unfocusedBorderColor = CodexOutline.copy(alpha = 0.3f),
                    disabledBorderColor = CodexOutline.copy(alpha = 0.15f),
                    focusedContainerColor = CodexSurfaceVariant.copy(alpha = 0.3f),
                    unfocusedContainerColor = CodexSurfaceVariant.copy(alpha = 0.15f),
                    disabledContainerColor = CodexSurfaceVariant.copy(alpha = 0.08f),
                    cursorColor = CodexBrandOrange
                )
            )

            Spacer(Modifier.width(8.dp))

            // 发送/停止按钮
            if (isStreaming) {
                // 停止按钮 - 红色调圆形
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(CodexError.copy(alpha = 0.15f))
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "停止",
                        tint = CodexError,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                // 发送按钮 - 品牌色圆形
                val hasContent = inputText.isNotBlank()
                IconButton(
                    onClick = onSend,
                    enabled = hasContent,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (hasContent) CodexBrandOrange
                            else CodexOutline.copy(alpha = 0.2f)
                        )
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "发送",
                        tint = if (hasContent) androidx.compose.ui.graphics.Color.White
                               else CodexOnSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ============================================================================
// Helper Functions
// ============================================================================

/**
 * 发送用户消息并处理流式响应。
 * 统一处理 onChunk/onComplete/onError 回调，解析思考过程和工具调用。
 */
private suspend fun sendUserMessage(
    agent: NativeAgentService,
    prompt: String,
    messages: SnapshotStateList<ChatMessage>,
    onStreamingChange: (Boolean) -> Unit,
    onStreamContentChange: (String) -> Unit,
    onThinkingChange: (String) -> Unit,
    onToolCallsChange: (List<ToolCallInfo>) -> Unit
) {
    // 添加用户消息
    messages.add(ChatMessage(role = "user", content = prompt))
    onStreamingChange(true)
    onStreamContentChange("")
    onThinkingChange("")

    val startTime = System.currentTimeMillis()
    val contentBuilder = StringBuilder()
    val thinkingBuilder = StringBuilder()

    with(kotlinx.coroutines.Dispatchers.IO) {
        agent.sendPromptStream(
            prompt = prompt,
            onChunk = { chunk ->
                // 检测思考过程标记
                if (chunk.contains("<think") || chunk.contains("<thinking")) {
                    val thinkContent = chunk.removePrefix("<think/>")
                        .removePrefix("<thinking>")
                        .removePrefix("<think/>")
                        .removePrefix("<think")
                    if (thinkContent.isNotBlank()) {
                        thinkingBuilder.append(thinkContent)
                        onThinkingChange(thinkingBuilder.toString())
                    }
                } else if (chunk.contains("🔧 执行工具:")) {
                    // 工具调用标记 - 提取工具名
                    val toolName = chunk.substringAfter("🔧 执行工具:").trim()
                    onToolCallsChange(listOf(ToolCallInfo(name = toolName, isSuccess = true)))
                } else if (chunk.contains("📋 结果:")) {
                    // 工具结果 - 追加到流式内容（不单独处理）
                    contentBuilder.append(chunk)
                    onStreamContentChange(contentBuilder.toString())
                } else {
                    contentBuilder.append(chunk)
                    onStreamContentChange(contentBuilder.toString())
                }
            },
            onComplete = { fullResponse ->
                val duration = System.currentTimeMillis() - startTime
                // 估算 token 数（粗略：4字符约1token）
                val estimatedTokens = (fullResponse.length / 4).coerceAtLeast(1)

                messages.add(
                    ChatMessage(
                        role = "assistant",
                        content = fullResponse,
                        thinkingContent = thinkingBuilder.toString(),
                        tokenCount = estimatedTokens,
                        durationMs = duration
                    )
                )
                onStreamContentChange("")
                onThinkingChange("")
                onStreamingChange(false)
            },
            onError = { error ->
                messages.add(
                    ChatMessage(
                        role = "error",
                        content = error,
                        durationMs = System.currentTimeMillis() - startTime
                    )
                )
                onStreamContentChange("")
                onStreamingChange(false)
            }
        )
    }
}

/**
 * 复制文本到剪贴板。
 */
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("codex_message", text))
}

/**
 * 格式化时间戳。
 */
private fun formatTimestamp(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

/**
 * 格式化耗时。
 */
private fun formatDuration(ms: Long): String {
    return when {
        ms < 1000 -> "${ms}ms"
        ms < 60000 -> "${(ms / 100f).roundToInt() / 10f}s"
        else -> "${ms / 60000}m ${(ms % 60000) / 1000}s"
    }
}
