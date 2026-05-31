# Codex Android 主 Agent 架构设计

> 全面照搬 Codex CLI + OpenCode 的 Agent 架构，适配 Android 环境

## 1. 设计目标

将 codex-android 从当前「WebView + WebSocket 桥接器」的简单架构，升级为与 [Codex CLI](https://github.com/openai/codex) 和 [OpenCode](https://github.com/opencode-ai/opencode) 对齐的完整 Agent 运行时，包括：

- **Agent Loop（智能体循环）**：用户→模型→工具→模型的迭代循环，直到模型输出最终助手消息
- **多 Agent 模式**：Build（完全权限）/ Plan（只读分析）/ Sandbox（受限执行）三级 Agent
- **工具注册与执行**：可扩展的工具系统，含权限检查、沙箱执行、输出截断
- **上下文窗口管理**：自动压缩（Compaction）、工具输出预算、overflow recovery
- **分层权限体系**：融合 Operit 的 5 级 Shell 执行器 + Codex 的 3 级安全策略
- **持久会话**：客户端/服务端架构，会话跨 Activity 生命周期存活
- **MCP 协议支持**：Model Context Protocol 客户端，连接外部工具服务

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────────┐
│                    Presentation Layer                        │
│  ┌──────────┐  ┌──────────┐  ┌───────────┐  ┌───────────┐  │
│  │  Chat UI  │  │ File Tab │  │ Agent Tab │  │  Settings  │  │
│  └─────┬────┘  └─────┬────┘  └─────┬─────┘  └─────┬─────┘  │
│        └──────────────┴─────────────┴──────────────┘        │
│                           │ ACP / UI Events                  │
├─────────────────────────────────────────────────────────────┤
│                    Agent Runtime (Service)                    │
│                                                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                   Agent Runner                         │  │
│  │  ┌─────────────┐  ┌─────────────┐  ┌──────────────┐  │  │
│  │  │ QueryEngine │←→│ ConvHistory │←→│  Compactor   │  │  │
│  │  └──────┬──────┘  └─────────────┘  └──────────────┘  │  │
│  │         │                                              │  │
│  │         ▼                                              │  │
│  │  ┌─────────────────────────────────────────────────┐  │  │
│  │  │            Tool Orchestrator                     │  │  │
│  │  │  ┌─────┐ ┌─────┐ ┌──────┐ ┌──────┐ ┌────────┐ │  │  │
│  │  │  │Shell│ │File │ │Search│ │ Patch│ │MCP Tool│ │  │  │
│  │  │  └──┬──┘ └──┬──┘ └──┬───┘ └──┬───┘ └───┬────┘ │  │  │
│  │  └─────┼───────┼───────┼────────┼─────────┼──────┘  │  │
│  └────────┼───────┼───────┼────────┼─────────┼─────────┘  │
│           │       │       │        │         │             │
│  ┌────────▼───────▼───────▼────────▼─────────▼─────────┐  │
│  │               Permission Middleware                   │  │
│  │  Config Rules → Guardian LLM → User Prompt           │  │
│  └───────────────────────┬─────────────────────────────┘  │
│                          │                                  │
│  ┌───────────────────────▼─────────────────────────────┐  │
│  │             Shell Executor Factory                    │  │
│  │  (Operit 5-level: Standard → Accessibility →         │  │
│  │   Debugger → Admin → Root + Proot)                   │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │               LLM Provider Layer                      │  │
│  │  OpenAI API │ Anthropic │ Local Ollama │ Custom       │  │
│  │  (SSE streaming, Responses API compatible)            │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐  │
│  │               State Persistence (SQLite)              │  │
│  │  Conversations │ Tool Results │ Agent Sessions        │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. 核心模块设计

### 3.1 Agent Runner — 智能体循环

**参照**：Codex CLI 的 `QueryEngine` + OpenCode 的 `agent.ts`

```kotlin
// agent/AgentRunner.kt
class AgentRunner(
    private val provider: LLMProvider,
    private val toolOrchestrator: ToolOrchestrator,
    private val convHistory: ConversationHistory,
    private val compactor: ContextCompactor,
    private val config: AgentConfig
) {
    // 事件驱动：TurnStarted / ToolCall / ToolResult / TurnCompleted / StreamDelta
    sealed class AgentEvent {
        data class TurnStarted(val turnId: String) : AgentEvent()
        data class StreamDelta(val text: String) : AgentEvent()
        data class ToolCallRequested(val call: ToolCall) : AgentEvent()
        data class ToolCallCompleted(val result: ToolResult) : AgentEvent()
        data class TurnCompleted(val message: String) : AgentEvent()
        data class CompactionOccurred(val tokensSaved: Int) : AgentEvent()
        data class Error(val message: String) : AgentEvent()
    }

    /**
     * 执行一次完整的 Agent Loop（一个 Turn）。
     * 循环：推理 → 工具调用 → 执行 → 追加结果 → 再推理，直到模型输出助手消息。
     */
    suspend fun runTurn(
        userMessage: String,
        agentMode: AgentMode = AgentMode.BUILD,
        eventSink: (AgentEvent) -> Unit
    ) {
        convHistory.addUserMessage(userMessage)
        var turnCount = 0

        while (turnCount < config.maxTurns) {
            turnCount++

            // 1. 检查上下文窗口，必要时压缩
            if (convHistory.estimatedTokens() > config.autoCompactLimit) {
                val saved = compactor.compact(convHistory, provider)
                eventSink(AgentEvent.CompactionOccurred(saved))
            }

            // 2. 构建请求（instructions + tools + input）
            val request = buildRequest(agentMode)

            // 3. 调用 LLM（流式 SSE）
            val response = provider.query(request) { delta ->
                eventSink(AgentEvent.StreamDelta(delta))
            }

            // 4. 处理响应
            when (response) {
                is LLMResponse.AssistantMessage -> {
                    convHistory.addAssistantMessage(response.text)
                    eventSink(AgentEvent.TurnCompleted(response.text))
                    return // 循环结束
                }
                is LLMResponse.ToolCalls -> {
                    for (call in response.calls) {
                        eventSink(AgentEvent.ToolCallRequested(call))
                        val result = toolOrchestrator.execute(call, agentMode)
                        convHistory.addToolResult(call.id, result)
                        eventSink(AgentEvent.ToolCallCompleted(result))
                    }
                    // 继续循环
                }
            }
        }

        // 超过最大轮次
        eventSink(AgentEvent.Error("Agent 达到最大轮次限制 (${config.maxTurns})"))
    }
}
```

**关键设计决策**（照搬 Codex）：

| 决策 | Codex 做法 | 本项目做法 |
|------|-----------|-----------|
| 请求格式 | Responses API（instructions + tools + input） | 兼容 OpenAI Chat Completions API，封装为 Responses API 语义 |
| 无状态请求 | 不用 `previous_response_id`，每次发送完整历史 | 同样无状态，支持 ZDR |
| Prompt 缓存优化 | 静态内容（instructions/tools）放前部 | 同样策略：system prompt → tools → instructions → messages |
| 自动压缩 | `auto_compact_limit` 触发 → `/responses/compact` | token 阈值触发 → 本地 LLM 摘要压缩 |
| 工具输出预算 | >100K chars 存磁盘，返回 head/tail 摘要 | `ToolResultBudget` 同样策略 |

### 3.2 Agent Mode — 多 Agent 模式

**参照**：OpenCode 的 Build/Plan/Sandbox + Codex 的 approval_policy

```kotlin
// agent/AgentMode.kt
enum class AgentMode(
    val displayName: String,
    val description: String,
    val isReadOnly: Boolean,
    val defaultTools: Set<String>,
    val permission: PermissionLevel
) {
    BUILD(
        displayName = "Build",
        description = "默认模式，完整文件编辑和命令执行权限",
        isReadOnly = false,
        defaultTools = setOf("shell", "file_read", "file_write", "file_edit", "search", "patch", "web_fetch"),
        permission = PermissionLevel.FULL
    ),
    PLAN(
        displayName = "Plan",
        description = "只读模式，代码分析和探索，不允许编辑文件",
        isReadOnly = true,
        defaultTools = setOf("shell_readonly", "file_read", "search", "web_fetch"),
        permission = PermissionLevel.STANDARD
    ),
    SANDBOX(
        displayName = "Sandbox",
        description = "受限权限，执行前需确认，文件操作限制在沙箱目录",
        isReadOnly = false,
        defaultTools = setOf("shell_sandbox", "file_read", "file_write_sandbox", "search"),
        permission = PermissionLevel.SAFE
    )
}
```

### 3.3 Tool Orchestrator — 工具注册与执行

**参照**：Codex 的 `ToolHandler` 注册表 + OpenCode 的四阶段 Pipeline

```kotlin
// tools/ToolOrchestrator.kt
class ToolOrchestrator(
    private val permissionMiddleware: PermissionMiddleware,
    private val resultBudget: ToolResultBudget
) {
    private val registry = mutableMapOf<String, ToolHandler>()

    fun register(handler: ToolHandler) {
        registry[handler.name] = handler
    }

    /**
     * 工具执行四阶段 Pipeline（照搬 OpenCode）：
     * 1. 参数验证（Zod schema / Kotlin type check）
     * 2. 权限评估（全局规则 → Agent 规则 → 用户规则，findLast 语义）
     * 3. Effect 执行（协程作用域 + 错误链管理）
     * 4. 输出截断（超长输出存磁盘，返回 head/tail 预览）
     */
    suspend fun execute(call: ToolCall, mode: AgentMode): ToolResult {
        val handler = registry[call.name]
            ?: return ToolResult.error("未知工具: ${call.name}")

        // Phase 1: 参数验证
        val params = handler.validateParams(call.arguments)
            ?: return ToolResult.error("参数验证失败: ${call.arguments}")

        // Phase 2: 权限评估
        val permResult = permissionMiddleware.evaluate(call, mode)
        when (permResult) {
            is PermissionResult.Denied ->
                return ToolResult.error("权限拒绝: ${permResult.reason}")
            is PermissionResult.Ask -> {
                // 发起用户确认请求（通过 ACP/UI）
                val approved = permissionMiddleware.requestApproval(call)
                if (!approved) return ToolResult.error("用户拒绝执行: ${call.name}")
            }
            is PermissionResult.Allowed -> { /* 继续执行 */ }
        }

        // Phase 3: 执行
        val rawResult = handler.execute(params, mode)

        // Phase 4: 输出截断与格式化
        return resultBudget.process(rawResult)
    }
}

// 工具处理器接口
interface ToolHandler {
    val name: String
    val description: String
    val parameters: ToolParameterSchema  // JSON Schema

    fun validateParams(args: JSONObject): JSONObject?
    suspend fun execute(params: JSONObject, mode: AgentMode): RawToolResult
}
```

**内置工具清单**（照搬 Codex + OpenCode）：

| 工具 | 描述 | Codex 有 | OpenCode 有 | 权限要求 |
|------|------|---------|------------|---------|
| `shell` | 执行 Shell 命令 | ✅ | ✅ | FULL/ask |
| `file_read` | 读取文件 | ✅ | ✅ | 无 |
| `file_write` | 写入文件 | ✅ | ✅ | STANDARD+ |
| `file_edit` | 编辑文件（diff/patch） | ✅ | ✅ | STANDARD+ |
| `search` | 搜索文件/代码 | ✅ | ✅ | 无 |
| `patch` | 应用 diff patch | ✅ | ❌ | STANDARD+ |
| `web_fetch` | 获取网页内容 | ❌ | ✅ | STANDARD+ |
| `update_plan` | 更新任务计划 | ✅ | ❌ | 无 |
| `list_dir` | 列出目录 | ✅ | ✅ | 无 |
| `mcp_*` | MCP 外部工具 | ✅ | ✅ | 按配置 |

### 3.4 Permission Middleware — 分层权限中间件

**参照**：Codex 的三层审批管线（Config → Guardian LLM → User）+ Operit 的 5 级 Shell 执行器

```kotlin
// security/PermissionMiddleware.kt
class PermissionMiddleware(
    private val config: CodexConfig,
    private val shellExecutorFactory: ShellExecutorFactory,
    private val userPrompt: suspend (ToolCall, String?) -> Boolean
) {
    /**
     * 三层权限评估（照搬 Codex can_use_tool()）：
     * Layer 1: Config-based pattern matching（配置规则匹配）
     * Layer 2: Guardian LLM risk assessment（可选，LLM 自动风险评估）
     * Layer 3: Interactive user prompt（用户交互确认）
     *
     * 结果按 turn 缓存，同一 turn 内相同操作不重复询问。
     */
    suspend fun evaluate(call: ToolCall, mode: AgentMode): PermissionResult {
        val cacheKey = "${call.name}:${call.arguments}"

        // Turn-scoped 缓存
        turnCache[cacheKey]?.let { return it }

        // Layer 1: 配置规则
        val configResult = evaluateConfigRules(call, mode)
        if (configResult != PermissionResult.Ask) {
            turnCache[cacheKey] = configResult
            return configResult
        }

        // Layer 2: Guardian LLM（可选）
        if (config.guardianEnabled) {
            val guardianResult = evaluateGuardian(call)
            if (guardianResult != PermissionResult.Ask) {
                turnCache[cacheKey] = guardianResult
                return guardianResult
            }
        }

        // Layer 3: 用户确认
        val reason = SecurityPolicy.dangerousCommandReason(call.arguments.optString("command"))
        val approved = userPrompt(call, reason)
        val result = if (approved) PermissionResult.Allowed
                     else PermissionResult.Denied(reason ?: "用户拒绝")
        turnCache[cacheKey] = result
        return result
    }
}
```

### 3.5 Shell Executor Factory — 多级 Shell 执行器

**直接照搬 Operit**，整合现有 `AndroidShellExecutor`：

```kotlin
// shell/ShellExecutorFactory.kt — 照搬 Operit 设计
enum class AndroidPermissionLevel {
    STANDARD,      // 标准: Runtime.exec("sh", "-c", cmd)
    ACCESSIBILITY, // 无障碍服务: 通过 AccessibilityService 执行
    DEBUGGER,      // 调试模式: 利用 JDWP 调试连接提升权限
    ADMIN,         // 设备管理员: DeviceAdmin API 有限操作
    ROOT           // Root: su -c 或 libsu
}

class ShellExecutorFactory(private val context: Context) {
    private val executors = mutableMapOf<AndroidPermissionLevel, ShellExecutor>()

    fun getExecutor(level: AndroidPermissionLevel): ShellExecutor {
        return executors.getOrPut(level) {
            when (level) {
                STANDARD -> StandardShellExecutor(context)
                ACCESSIBILITY -> AccessibilityShellExecutor(context)
                DEBUGGER -> DebuggerShellExecutor(context)
                ADMIN -> AdminShellExecutor(context)
                ROOT -> RootShellExecutor(context)
            }.also { it.initialize() }
        }
    }

    /**
     * 获取当前设备可用的最高权限执行器
     * 按权限从高到低尝试：ROOT → ADMIN → DEBUGGER → ACCESSIBILITY → STANDARD
     */
    fun getHighestAvailable(): Pair<ShellExecutor, ShellExecutor.PermissionStatus> {
        for (level in AndroidPermissionLevel.values().reversed()) {
            val executor = getExecutor(level)
            val status = executor.hasPermission()
            if (executor.isAvailable() && status.granted) {
                return executor to status
            }
        }
        return getExecutor(STANDARD) to ShellExecutor.PermissionStatus.granted()
    }

    /**
     * 为 proot Linux 环境创建执行器
     * Android 36+ W^X 限制下，这是主要的代码执行方式
     */
    fun getProotExecutor(linuxEnv: LinuxEnvironment): ProotShellExecutor {
        return ProotShellExecutor(linuxEnv)
    }
}
```

### 3.6 Context Compactor — 上下文压缩

**参照**：Codex 的三阶段压缩（micro → snip → full）

```kotlin
// agent/ContextCompactor.kt
class ContextCompactor(private val provider: LLMProvider) {

    /**
     * 三阶段上下文压缩：
     *
     * Phase 1 — Micro-compaction: 内联剥离过期工具输出
     *   - 移除 >50K token 的旧工具结果
     *   - 保留最近 N 次工具调用的完整输出
     *
     * Phase 2 — Snip compaction: 移除低价值消息
     *   - 低于 token 阈值的中间对话
     *   - 重复的确认消息
     *
     * Phase 3 — Full compaction: LLM 摘要压缩
     *   - 用 LLM 生成对话摘要
     *   - 保留关键文件内容（最多 5 个文件，50K token 预算）
     *   - Ghost snapshot 保留（加密的 latent understanding）
     *
     * Overflow recovery:
     *   - 如果压缩本身也超限，先剥离所有媒体附件
     *   - 再重放原始用户 prompt
     *   - 如果仍失败，抛出 ContextOverflowError 让用户决定是否新开会话
     */
    suspend fun compact(history: ConversationHistory, provider: LLMProvider): Int {
        var savedTokens = 0

        // Phase 1
        savedTokens += microCompact(history)

        if (history.estimatedTokens() > FULL_COMPACT_THRESHOLD) {
            // Phase 2
            savedTokens += snipCompact(history)
        }

        if (history.estimatedTokens() > FULL_COMPACT_THRESHOLD) {
            // Phase 3
            savedTokens += fullCompact(history, provider)
        }

        return savedTokens
    }
}
```

### 3.7 ConversationHistory — 对话历史与持久化

**参照**：OpenCode 的 SQLite 持久化 + Codex 的消息格式

```kotlin
// agent/ConversationHistory.kt
class ConversationHistory(
    private val dao: ConversationDao  // Room SQLite DAO
) {
    private val messages = mutableListOf<ConversationItem>()
    private var estimatedTokenCount = 0

    sealed class ConversationItem {
        abstract val id: String
        abstract val timestamp: Long

        data class UserMessage(
            override val id: String,
            override val timestamp: Long,
            val content: String
        ) : ConversationItem()

        data class AssistantMessage(
            override val id: String,
            override val timestamp: Long,
            val content: String
        ) : ConversationItem()

        data class ToolCallItem(
            override val id: String,
            override val timestamp: Long,
            val callId: String,
            val toolName: String,
            val arguments: String
        ) : ConversationItem()

        data class ToolResultItem(
            override val id: String,
            override val timestamp: Long,
            val callId: String,
            val output: String,
            val isSuccess: Boolean
        ) : ConversationItem()

        data class SystemMessage(
            override val id: String,
            override val timestamp: Long,
            val content: String,
            val role: String = "developer"  // system / developer
        ) : ConversationItem()

        data class CompactionMarker(
            override val id: String,
            override val timestamp: Long,
            val summary: String,
            val tokensSaved: Int
        ) : ConversationItem()
    }

    /**
     * 构建 Responses API 请求的 input 数组
     * 按 Codex 规范组装：system → tools → instructions → messages
     */
    fun buildApiInput(agentMode: AgentMode, config: AgentConfig): JSONObject {
        // ... 按 Codex 规范组装 JSON
    }
}
```

### 3.8 Prompt 构建 — 初始提示构建

**参照**：Codex 的多源提示聚合

```kotlin
// agent/PromptBuilder.kt
class PromptBuilder(private val context: Context, private val config: AgentConfig) {

    /**
     * 构建初始提示，照搬 Codex 的四层消息结构：
     *
     * 1. [role=developer] 沙箱权限说明
     *    - 当前安全等级
     *    - 可写目录列表
     *    - Shell 工具的权限限制
     *
     * 2. [role=developer] 开发者指令（可选）
     *    - 用户自定义指令
     *    - 从 ~/.codex/AGENTS.md 读取
     *
     * 3. [role=user] 用户指令聚合
     *    - AGENTS.md 项目上下文
     *    - 技能（Skills）描述
     *    - 项目文档摘要
     *
     * 4. [role=user] 环境上下文
     *    - 当前工作目录
     *    - Shell 类型
     *    - 设备信息
     *    - 权限级别
     */
    fun buildInitialMessages(agentMode: AgentMode): List<ConversationItem> {
        val messages = mutableListOf<ConversationItem>()

        // 1. 沙箱权限说明
        messages.add(ConversationItem.SystemMessage(
            id = "sys_sandbox",
            timestamp = System.currentTimeMillis(),
            content = buildSandboxDescription(agentMode)
        ))

        // 2. 开发者指令
        config.developerInstructions?.let { instructions ->
            messages.add(ConversationItem.SystemMessage(
                id = "sys_dev",
                timestamp = System.currentTimeMillis(),
                content = instructions,
                role = "developer"
            ))
        }

        // 3. 用户指令聚合
        val userInstructions = aggregateUserInstructions()
        if (userInstructions.isNotEmpty()) {
            messages.add(ConversationItem.UserMessage(
                id = "usr_context",
                timestamp = System.currentTimeMillis(),
                content = userInstructions
            ))
        }

        // 4. 环境上下文
        messages.add(ConversationItem.UserMessage(
            id = "usr_env",
            timestamp = System.currentTimeMillis(),
            content = buildEnvironmentContext()
        ))

        return messages
    }

    private fun buildEnvironmentContext(): String {
        return """
        |<environment_context>
        | <cwd>${config.workingDirectory}</cwd>
        | <shell>sh</shell>
        | <os>Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})</os>
        | <device>${android.os.Build.MODEL}</device>
        | <arch>${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}</arch>
        | <permission_level>${config.permissionLevel}</permission_level>
        | <proot_available>${config.prootAvailable}</proot_available>
        |</environment_context>
        """.trimMargin()
    }
}
```

### 3.9 LLM Provider Layer — 模型提供商抽象

**参照**：OpenCode 的 75+ 模型支持 + Codex 的 Responses API

```kotlin
// provider/LLMProvider.kt
interface LLMProvider {
    val id: String
    val name: String

    suspend fun query(
        request: LLMRequest,
        onStream: (String) -> Unit
    ): LLMResponse

    suspend fun compact(
        messages: List<ConversationItem>,
        instructions: String
    ): List<ConversationItem>

    data class LLMRequest(
        val instructions: String,
        val tools: List<ToolDefinition>,
        val input: List<JSONObject>,
        val model: String,
        val temperature: Float = 0.0f,
        val maxTokens: Int = 16384
    )

    sealed class LLMResponse {
        data class AssistantMessage(val text: String, val reasoning: String? = null) : LLMResponse()
        data class ToolCalls(val calls: List<ToolCall>) : LLMResponse()
    }
}

// provider/OpenAIProvider.kt — 支持 Responses API + Chat Completions API
class OpenAIProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val model: String = "gpt-4o"
) : LLMProvider {
    // SSE 流式响应
    // 支持 /v1/responses (Responses API) 和 /v1/chat/completions (兼容模式)
    // 自动检测端点能力
}

// provider/OllamaProvider.kt — 本地模型
class OllamaProvider(
    private val baseUrl: String = "http://127.0.0.1:11434"
) : LLMProvider {
    // 通过 proot 运行 ollama serve
    // OpenAI 兼容 API
}
```

### 3.10 MCP Bridge — Model Context Protocol

**保留并增强**现有 `CodexMCPBridge`，照搬 Codex 的 MCP 客户端实现：

```kotlin
// mcp/MCPClientManager.kt
class MCPClientManager {
    private val clients = mutableMapOf<String, MCPClient>()

    /**
     * MCP 工具发现与注册：
     * - 连接 MCP server（stdio / SSE）
     * - 获取 tools/list
     * - 注册到 ToolOrchestrator
     * - 监听 notifications/tools/list_changed
     *
     * 工具命名规范：mcp__{server_name}__{tool_name}
     * （照搬 Codex 的命名约定）
     */
    suspend fun connectServer(config: MCPServerConfig): List<ToolDefinition> {
        val client = MCPClient(config)
        val tools = client.listTools()

        // 按照稳定顺序注册（Codex 的缓存优化策略）
        val sortedTools = tools.sortedBy { it.name }
        clients[config.name] = client

        return sortedTools.map { tool ->
            ToolDefinition(
                name = "mcp__${config.name}__${tool.name}",
                description = tool.description,
                parameters = tool.parameters
            )
        }
    }
}
```

---

## 4. 服务端架构 — 持久会话

**参照**：OpenCode 的 Client/Server + Daemon 架构

```kotlin
// service/CodexRuntimeService.kt — 增强版
class CodexRuntimeService : Service() {
    private val agentRunner by lazy { AgentRunner(...) }
    private val sessionStore by lazy { SessionStore(database) }

    /**
     * 客户端/服务端架构（照搬 OpenCode）：
     * - Service 是长驻后台的 Agent Server
     * - UI (Activity/WebView) 是 TUI Client
     * - 通过 ACP (Agent Client Protocol) 通信
     * - 会话跨 Activity 生命周期存活
     * - 关闭 App 后重新打开，恢复同一会话
     */
    inner class AgentBinder : Binder() {
        fun getAgentRunner(): AgentRunner = agentRunner
        fun getSessionStore(): SessionStore = sessionStore
        fun subscribe(): StateFlow<AgentState> = agentState
    }

    // Agent 状态
    sealed class AgentState {
        object Idle : AgentState()
        data class Running(val turnId: String, val mode: AgentMode) : AgentState()
        data class AwaitingApproval(val call: ToolCall) : AgentState()
        data class Error(val message: String) : AgentState()
    }
}
```

---

## 5. 实施路线图

### Phase 1: Agent Runner 核心（1-2 天）
- [ ] `AgentRunner.kt` — Agent Loop 循环
- [ ] `ConversationHistory.kt` — 对话历史管理
- [ ] `PromptBuilder.kt` — 初始提示构建
- [ ] `LLMProvider` 接口 + `OpenAIProvider` 实现
- [ ] 与现有 `CodexBridge` / `CodexApiBridge` 整合

### Phase 2: 工具系统（1-2 天）
- [ ] `ToolOrchestrator.kt` — 工具注册与执行 Pipeline
- [ ] `ShellTool` — Shell 命令执行（整合现有 `AndroidShellExecutor`）
- [ ] `FileReadTool` / `FileWriteTool` / `FileEditTool`
- [ ] `SearchTool` / `PatchTool`
- [ ] `ToolResultBudget` — 输出截断

### Phase 3: 权限体系（1 天）
- [ ] `PermissionMiddleware` — 三层审批管线
- [ ] `ShellExecutorFactory` — 5 级 Shell 执行器（照搬 Operit）
- [ ] `StandardShellExecutor` / `RootShellExecutor` / `ProotShellExecutor`
- [ ] Turn-scoped 审批缓存

### Phase 4: 上下文管理（1 天）
- [ ] `ContextCompactor` — 三阶段压缩
- [ ] `ToolResultBudget` — 大输出管理
- [ ] Overflow recovery
- [ ] SQLite 持久化（Room）

### Phase 5: 多 Agent 模式 + MCP（1 天）
- [ ] `AgentMode` — Build/Plan/Sandbox 三模式
- [ ] MCP 客户端增强（稳定排序 + list_changed 监听）
- [ ] Agent 切换 UI

### Phase 6: 集成测试与优化
- [ ] 与 WebView UI 集成
- [ ] 流式输出渲染
- [ ] 性能测试与 prompt 缓存优化
- [ ] CI 构建验证

---

## 6. 与现有代码的映射

| 新模块 | 替换/增强 | 现有文件 |
|--------|----------|---------|
| `AgentRunner` | 增强 | `CodexAgentProvider.kt` |
| `ToolOrchestrator` | 新增 | — |
| `ShellTool` | 增强 | `AndroidShellExecutor.kt` |
| `ShellExecutorFactory` | 替换 | `AndroidShellExecutor.createProcess()` |
| `PermissionMiddleware` | 增强 | `SecurityPolicy.kt` + `ShellConfirmationManager.kt` |
| `ConversationHistory` | 新增 | `CodexApiBridge.messageHistory` |
| `ContextCompactor` | 新增 | — |
| `PromptBuilder` | 新增 | `CodexBridge` 中的 prompt 构建 |
| `LLMProvider` | 抽象 | `CodexApiBridge` + `CodexBridge` |
| `MCPClientManager` | 增强 | `CodexMCPBridge.kt` |
| `CodexRuntimeService` | 增强 | `CodexRuntimeService.kt` |

---

## 7. 参考资料

- [深入解析 Codex 智能体循环 | OpenAI](https://openai.com/index/unrolling-the-codex-agent-loop/) — Agent Loop 核心设计
- [Codex CLI 源码](https://github.com/openai/codex) — 28 个 Python 模块，6 层架构
- [OpenCode 架构详解](https://anxiangsir.github.io/pages/opencode/) — C/S 架构、ACP 协议、多 Agent
- [OpenCode Agent 配置](https://opencode.ai/docs/ko/agents/) — Build/Plan/Sandbox 模式
- [Operit ShellExecutor](https://github.com/nicepkg/operit) — 5 级权限 Shell 执行器
- [Codex CLI 论文](https://arxiv.org/pdf/2604.11518) — Rust→Python 迁移，Agent 系统全貌
