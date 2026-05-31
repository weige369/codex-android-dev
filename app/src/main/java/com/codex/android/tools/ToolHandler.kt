package com.codex.android.tools

import com.codex.android.agent.AgentMode
import org.json.JSONObject

/**
 * 工具处理器接口。
 *
 * 每个工具（Shell、文件读写、搜索等）均实现此接口，由 [ToolOrchestrator] 统一调度。
 * 接口定义了工具的元数据、参数校验与执行逻辑，与编排层解耦。
 *
 * 设计参考 OpenCode 的 Tool trait：工具自身只负责「做什么」，
 * 「能不能做 / 怎么做」由 Orchestrator 的四阶段 Pipeline 决定。
 */
interface ToolHandler {

    /** 工具唯一名称，对应 LLM function calling 的 function name。 */
    val name: String

    /** 工具描述，供 LLM 判断何时调用。 */
    val description: String

    /**
     * 参数 JSON Schema（遵循 https://json-schema.org/ 规范）。
     *
     * 示例：
     * ```json
     * {
     *   "type": "object",
     *   "properties": {
     *     "command": { "type": "string", "description": "Shell 命令" }
     *   },
     *   "required": ["command"]
     * }
     * ```
     */
    val parameters: JSONObject

    /**
     * 校验参数是否合法。
     *
     * 工具在执行前可对参数做语义级校验（如路径合法性、必填字段存在性等），
     * 此阶段不会产生副作用。
     *
     * @param args LLM 传入的参数对象。
     * @return 校验通过返回 `null`；校验失败返回错误信息 JSON，格式：
     *   ```json
     *   { "error": "描述", "field": "出错的字段名（可选）" }
     *   ```
     */
    fun validateParams(args: JSONObject): JSONObject?

    /**
     * 执行工具逻辑。
     *
     * 由 [ToolOrchestrator] 在合适的协程作用域中调用，实现类无需自行管理协程。
     * 执行中抛出的异常会被 Orchestrator 捕获并转为 [RawToolResult.isError] = true。
     *
     * @param params 已通过 [validateParams] 校验的参数。
     * @param mode   当前 Agent 运行模式，工具可据此调整行为（如权限等级）。
     * @return 执行结果。
     */
    suspend fun execute(params: JSONObject, mode: AgentMode): RawToolResult
}

/**
 * 工具原始执行结果。
 *
 * 对标 OpenCode 的 ToolOutput，在经过 [ToolResultBudget] 截断后
 * 再送入 LLM 上下文。
 *
 * @property output   标准输出文本（可能很长，后续由 Budget 裁剪）。
 * @property isError  是否为错误结果。true 时 LLM 会看到错误提示并自行决定重试/换策略。
 * @property exitCode 命令退出码（仅 Shell 工具有意义，其余默认 0）。
 * @property metadata 附加元数据，如 truncated / tempFilePath 等。
 */
data class RawToolResult(
    val output: String,
    val isError: Boolean = false,
    val exitCode: Int = 0,
    val metadata: JSONObject = JSONObject()
)
