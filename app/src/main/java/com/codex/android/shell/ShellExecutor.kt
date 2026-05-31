package com.codex.android.shell

import kotlinx.coroutines.flow.Flow

/**
 * Shell 执行器接口。
 *
 * 定义统一的 Shell 命令执行抽象，支持多种执行环境
 *（标准 Shell、proot Linux、Root 等）。每个执行器实现对应一种权限级别，
 * 由 [ShellExecutorFactory] 根据需求创建和缓存。
 *
 * 设计原则：
 * - 所有 Shell 操作通过此接口统一调度
 * - 权限级别通过 [AndroidPermissionLevel] 标识
 * - 进程生命周期由 [ShellProcess] 管理
 * - 异步执行基于 Kotlin 协程
 */
interface ShellExecutor {

    /**
     * 执行一条 Shell 命令并等待完成。
     *
     * 阻塞当前协程直到命令执行完毕或超时，返回完整的执行结果。
     * 适用于短命令和脚本执行场景。
     *
     * @param command 要执行的 Shell 命令
     * @return 命令执行结果，包含 stdout、stderr 和退出码
     */
    suspend fun executeCommand(command: String): CommandResult

    /**
     * 获取该执行器对应的权限级别。
     *
     * @return Android 权限级别枚举
     */
    fun getPermissionLevel(): AndroidPermissionLevel

    /**
     * 检查该执行器是否可用。
     *
     * 可用性取决于运行环境：
     * - StandardShellExecutor: 始终可用
     * - ProotShellExecutor: proot 引擎和 rootfs 是否就绪
     * - RootShellExecutor: 设备是否已 root
     *
     * @return true 表示该执行器当前可用
     */
    fun isAvailable(): Boolean

    /**
     * 请求该执行器所需的运行时权限。
     *
     * 某些执行器（如 RootShellExecutor）需要用户授权才能使用。
     * 此方法触发权限请求流程，结果通过回调返回。
     *
     * @param onResult 权限请求结果回调，true 表示授权成功
     */
    fun requestPermission(onResult: (Boolean) -> Unit)

    /**
     * 检查该执行器的权限状态。
     *
     * @return 权限状态，包含是否已授权和原因描述
     */
    fun hasPermission(): PermissionStatus

    /**
     * 初始化执行器。
     *
     * 在首次使用前调用，执行必要的初始化操作
     *（如检测环境、预热进程池等）。
     */
    fun initialize()

    /**
     * 启动一个长期运行的 Shell 进程。
     *
     * 返回 [ShellProcess] 接口，调用方可通过 Flow 实时读取
     * stdout/stderr 输出，适用于长时间运行的命令
     *（如服务器进程、日志监听等）。
     *
     * @param command 要执行的 Shell 命令
     * @return Shell 进程实例
     */
    suspend fun startProcess(command: String): ShellProcess

    //region 嵌套数据类

    /**
     * 命令执行结果。
     *
     * @property success  命令是否成功执行（exitCode == 0）
     * @property stdout   标准输出内容
     * @property stderr   标准错误内容
     * @property exitCode 进程退出码（0 表示成功，-1 表示执行失败或超时）
     */
    data class CommandResult(
        val success: Boolean,
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    )

    /**
     * 权限状态。
     *
     * @property granted 是否已授权
     * @property reason  状态原因描述（如 "Root 权限未授予" 或 "权限已获取"）
     */
    data class PermissionStatus(
        val granted: Boolean,
        val reason: String
    )

    //endregion
}

/**
 * Shell 进程接口。
 *
 * 表示一个正在运行的 Shell 进程，提供实时输出流和生命周期管理。
 * 通过 [ShellExecutor.startProcess] 创建。
 *
 * 设计为响应式流模式：
 * - stdout/stderr 通过 Kotlin Flow 实时推送
 * - 进程状态通过 [isAlive] 和 [exitCode] 查询
 * - 调用 [destroy] 终止进程
 */
interface ShellProcess {

    /**
     * 标准输出流。
     *
     * 实时推送进程的 stdout 行，每行作为一个 Flow emission。
     * 进程结束后 Flow 自动完成。
     */
    val stdout: Flow<String>

    /**
     * 标准错误流。
     *
     * 实时推送进程的 stderr 行，每行作为一个 Flow emission。
     * 进程结束后 Flow 自动完成。
     */
    val stderr: Flow<String>

    /**
     * 进程是否仍在运行。
     */
    val isAlive: Boolean

    /**
     * 进程退出码。
     *
     * 进程仍在运行时返回 null；进程结束后返回退出码（0 表示成功）。
     */
    val exitCode: Int?

    /**
     * 销毁进程。
     *
     * 强制终止当前进程，释放相关资源。
     * 调用后 [isAlive] 将变为 false。
     */
    fun destroy()
}
