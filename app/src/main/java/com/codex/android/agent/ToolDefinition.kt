package com.codex.android.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具定义数据类。
 *
 * 描述一个可供 LLM 调用的工具，包含名称、描述和 JSON Schema 格式的参数定义。
 * 遵循 OpenAI function calling 的工具规范格式。
 *
 * @property name 工具名称，需全局唯一（如 "shell"、"file_write"）
 * @property description 工具功能描述，LLM 依据此描述选择合适的工具
 * @property parameters 参数的 JSON Schema 对象，定义工具接受的输入结构
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JSONObject
) {

    companion object {
        private const val TAG = "ToolDefinition"

        /**
         * 从简化的参数描述快速构建 ToolDefinition。
         *
         * @param name 工具名称
         * @param description 工具描述
         * @param paramPairs 参数名到类型描述的键值对，如 "command" to "string"
         * @return 构建好的 ToolDefinition
         */
        fun simple(
            name: String,
            description: String,
            paramPairs: List<Pair<String, String>> = emptyList()
        ): ToolDefinition {
            val properties = JSONObject()
            val required = JSONArray()
            paramPairs.forEach { (paramName, typeDesc) ->
                properties.put(paramName, JSONObject().apply {
                    put("type", typeDesc)
                })
                required.put(paramName)
            }
            val schema = JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", required)
            }
            return ToolDefinition(name, description, schema)
        }

        // ========== 内置工具定义 ==========

        /** Shell 执行工具（完整权限） */
        val SHELL = ToolDefinition(
            name = "shell",
            description = "在终端中执行 shell 命令并返回输出。支持管道、重定向等标准 shell 特性。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("command", JSONObject().apply {
                        put("type", "string")
                        put("description", "要执行的 shell 命令")
                    })
                    put("timeout", JSONObject().apply {
                        put("type", "integer")
                        put("description", "超时时间（毫秒），默认 30000")
                    })
                })
                put("required", JSONArray().put("command"))
            }
        )

        /** Shell 只读工具（仅允许读取类命令） */
        val SHELL_READONLY = ToolDefinition(
            name = "shell_readonly",
            description = "以只读模式执行 shell 命令。仅允许读取类操作（如 ls、cat、head、grep -r 等），禁止写入和修改。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("command", JSONObject().apply {
                        put("type", "string")
                        put("description", "要执行的只读 shell 命令")
                    })
                })
                put("required", JSONArray().put("command"))
            }
        )

        /** Shell 沙箱工具 */
        val SHELL_SANDBOX = ToolDefinition(
            name = "shell_sandbox",
            description = "在受限沙箱环境中执行 shell 命令。文件系统访问被限制在沙箱目录内。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("command", JSONObject().apply {
                        put("type", "string")
                        put("description", "要在沙箱中执行的 shell 命令")
                    })
                })
                put("required", JSONArray().put("command"))
            }
        )

        /** 文件读取工具 */
        val FILE_READ = ToolDefinition(
            name = "file_read",
            description = "读取指定路径的文件内容并返回文本。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "要读取的文件路径")
                    })
                    put("offset", JSONObject().apply {
                        put("type", "integer")
                        put("description", "起始行号（从 0 开始），默认 0")
                    })
                    put("limit", JSONObject().apply {
                        put("type", "integer")
                        put("description", "最大读取行数，默认读取全部")
                    })
                })
                put("required", JSONArray().put("path"))
            }
        )

        /** 文件写入工具 */
        val FILE_WRITE = ToolDefinition(
            name = "file_write",
            description = "将内容写入指定路径的文件。会创建不存在的父目录。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "要写入的文件路径")
                    })
                    put("content", JSONObject().apply {
                        put("type", "string")
                        put("description", "要写入的文件内容")
                    })
                })
                put("required", JSONArray().apply {
                    put("path")
                    put("content")
                })
            }
        )

        /** 沙箱内文件写入工具 */
        val FILE_WRITE_SANDBOX = ToolDefinition(
            name = "file_write_sandbox",
            description = "在沙箱目录内写入文件。文件路径被限制在沙箱根目录下。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "沙箱内的相对文件路径")
                    })
                    put("content", JSONObject().apply {
                        put("type", "string")
                        put("description", "要写入的文件内容")
                    })
                })
                put("required", JSONArray().apply {
                    put("path")
                    put("content")
                })
            }
        )

        /** Patch 应用工具 */
        val PATCH = ToolDefinition(
            name = "patch",
            description = "对文件应用 unified diff patch。支持精确的行级编辑。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "要 patch 的文件路径")
                    })
                    put("diff", JSONObject().apply {
                        put("type", "string")
                        put("description", "unified diff 格式的补丁内容")
                    })
                })
                put("required", JSONArray().apply {
                    put("path")
                    put("diff")
                })
            }
        )

        /** 搜索工具 */
        val SEARCH = ToolDefinition(
            name = "search",
            description = "在文件系统中搜索匹配指定模式的文件或内容。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("pattern", JSONObject().apply {
                        put("type", "string")
                        put("description", "搜索模式（支持 glob 或正则）")
                    })
                    put("path", JSONObject().apply {
                        put("type", "string")
                        put("description", "搜索根目录，默认为工作目录")
                    })
                    put("type", JSONObject().apply {
                        put("type", "string")
                        put("description", "搜索类型：filename / content，默认 filename")
                    })
                })
                put("required", JSONArray().put("pattern"))
            }
        )

        /** Web 请求工具 */
        val WEB_FETCH = ToolDefinition(
            name = "web_fetch",
            description = "获取指定 URL 的内容并返回文本。仅支持 HTTP/HTTPS 协议。",
            parameters = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "string")
                        put("description", "要获取的 URL 地址")
                    })
                    put("method", JSONObject().apply {
                        put("type", "string")
                        put("description", "HTTP 方法，默认 GET")
                    })
                })
                put("required", JSONArray().put("url"))
            }
        )
    }

    /**
     * 转换为 OpenAI API 工具格式。
     *
     * 生成符合 OpenAI function calling 规范的 JSONObject，
     * 可直接嵌入到 LLM 请求的 tools 数组中。
     *
     * @return OpenAI 格式的工具描述对象
     */
    fun toApiFormat(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", parameters)
            })
        }
    }
}

/**
 * 工具调用数据类。
 *
 * 表示 LLM 返回的一次工具调用请求，包含调用 ID、工具名称和参数。
 *
 * @property id 工具调用的唯一标识符，由 LLM 生成
 * @property name 要调用的工具名称
 * @property arguments 调用参数的 JSONObject
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject
) {

    companion object {
        private const val TAG = "ToolCall"

        /**
         * 从 OpenAI API 响应的 tool_calls 数组元素解析 ToolCall。
         *
         * @param json API 返回的 tool_call JSON 对象
         * @return 解析后的 ToolCall 实例
         * @throws org.json.JSONException 解析失败时抛出
         */
        fun fromApiJson(json: JSONObject): ToolCall {
            val function = json.getJSONObject("function")
            return ToolCall(
                id = json.getString("id"),
                name = function.getString("name"),
                arguments = JSONObject(function.optString("arguments", "{}"))
            )
        }
    }

    /**
     * 转换为 OpenAI API 消息格式中的 tool_call 对象。
     *
     * @return 符合 API 格式的 JSONObject
     */
    fun toApiFormat(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("arguments", arguments.toString())
            })
        }
    }
}

/**
 * 工具执行结果数据类。
 *
 * 表示一次工具调用的执行结果，包含输出内容、是否成功等状态信息。
 *
 * @property callId 对应的 ToolCall 的 ID
 * @property output 工具执行的输出内容
 * @property isSuccess 是否执行成功
 * @property isTruncated 输出是否被截断（输出过长时）
 * @property metadata 额外的元数据（如执行耗时、退出码等）
 */
data class ToolResult(
    val callId: String,
    val output: String,
    val isSuccess: Boolean = true,
    val isTruncated: Boolean = false,
    val metadata: JSONObject = JSONObject()
) {

    companion object {
        private const val TAG = "ToolResult"

        /** 输出内容的最大长度，超出时截断 */
        private const val MAX_OUTPUT_LENGTH = 50_000

        /**
         * 创建工具结果，自动处理输出截断。
         *
         * @param callId 调用 ID
         * @param output 原始输出
         * @param isSuccess 是否成功
         * @param extraMetadata 额外元数据键值对
         * @return 处理后的 ToolResult
         */
        fun create(
            callId: String,
            output: String,
            isSuccess: Boolean = true,
            vararg extraMetadata: Pair<String, Any>
        ): ToolResult {
            val isTruncated = output.length > MAX_OUTPUT_LENGTH
            val truncatedOutput = if (isTruncated) {
                output.take(MAX_OUTPUT_LENGTH) + "\n... [输出被截断，共 ${output.length} 字符]"
            } else {
                output
            }
            val metadata = JSONObject().apply {
                extraMetadata.forEach { (key, value) -> put(key, value) }
            }
            return ToolResult(
                callId = callId,
                output = truncatedOutput,
                isSuccess = isSuccess,
                isTruncated = isTruncated,
                metadata = metadata
            )
        }

        /**
         * 创建错误结果。
         *
         * @param callId 调用 ID
         * @param errorMessage 错误信息
         * @return 标记为失败的 ToolResult
         */
        fun error(callId: String, errorMessage: String): ToolResult {
            return ToolResult(
                callId = callId,
                output = errorMessage,
                isSuccess = false,
                metadata = JSONObject().apply { put("error", true) }
            )
        }
    }

    /**
     * 转换为 OpenAI API 消息格式中的 tool 结果对象。
     *
     * @return 符合 API 格式的 JSONObject
     */
    fun toApiFormat(): JSONObject {
        return JSONObject().apply {
            put("role", "tool")
            put("tool_call_id", callId)
            put("content", output)
        }
    }
}
