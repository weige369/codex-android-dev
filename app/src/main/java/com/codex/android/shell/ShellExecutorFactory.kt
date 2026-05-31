package com.codex.android.shell

import android.content.Context
import android.util.Log
import com.codex.android.util.LinuxEnvironment
import java.util.concurrent.ConcurrentHashMap

/**
 * Shell 执行器工厂。
 *
 * 负责创建、缓存和管理各种权限级别的 [ShellExecutor] 实例。
 * 根据请求的权限级别返回对应的执行器，并自动选择当前可用的
 * 最高权限级别。
 *
 * 执行器选择优先级（从高到低）：
 * ROOT → ADMIN → DEBUGGER → ACCESSIBILITY → STANDARD
 *
 * 工厂会缓存已创建的执行器实例，避免重复初始化。
 * 缓存使用 [ConcurrentHashMap] 保证线程安全。
 *
 * 使用示例：
 * ```kotlin
 * val factory = ShellExecutorFactory(context)
 * val executor = factory.getHighestAvailable(context)
 * val result = executor.executeCommand("ls -la")
 * ```
 */
class ShellExecutorFactory(private val context: Context) {

    companion object {
        private const val TAG = "ShellExecutorFactory"
    }

    /**
     * 执行器实例缓存。
     *
     * Key 为权限级别枚举，Value 为对应的执行器实例。
     * 使用 ConcurrentHashMap 保证多线程安全。
     */
    private val executorCache = ConcurrentHashMap<AndroidPermissionLevel, ShellExecutor>()

    /**
     * 获取指定权限级别的执行器。
     *
     * 如果缓存中已有对应实例则直接返回，否则创建新实例并缓存。
     * 对于尚未实现的权限级别（ACCESSIBILITY/DEBUGGER/ADMIN），
     * 回退到 STANDARD 执行器。
     *
     * @param context Android 上下文
     * @param level   目标权限级别
     * @return 对应权限级别的 Shell 执行器
     */
    fun getExecutor(context: Context, level: AndroidPermissionLevel): ShellExecutor {
        return executorCache.getOrPut(level) {
            createExecutor(context, level)
        }
    }

    /**
     * 获取当前可用的最高权限级别执行器。
     *
     * 按优先级 ROOT → ADMIN → DEBUGGER → ACCESSIBILITY → STANDARD
     * 逐一检测可用性，返回第一个可用的执行器。
     *
     * 此方法会在首次调用时初始化所有执行器并缓存，
     * 后续调用直接从缓存中查找。
     *
     * @param context Android 上下文
     * @return 当前可用的最高权限级别执行器
     */
    fun getHighestAvailable(context: Context): ShellExecutor {
        for (level in AndroidPermissionLevel.PRIORITY_ORDER) {
            val executor = getExecutor(context, level)
            if (executor.isAvailable()) {
                Log.d(TAG, "选择最高可用执行器: ${level.displayName}")
                return executor
            }
        }

        // 兜底：STANDARD 始终可用
        Log.d(TAG, "回退到标准执行器")
        return getExecutor(context, AndroidPermissionLevel.STANDARD)
    }

    /**
     * 获取 proot 执行器。
     *
     * 便捷方法，创建或获取与指定 [LinuxEnvironment] 关联的
     * [ProotShellExecutor] 实例。
     *
     * @param linuxEnv Linux 环境管理器实例
     * @return proot 执行器
     */
    fun getProotExecutor(linuxEnv: LinuxEnvironment): ProotShellExecutor {
        // ProotShellExecutor 使用 STANDARD 级别缓存键
        // 因为它的权限级别实际是 STANDARD
        val cached = executorCache[AndroidPermissionLevel.STANDARD]
        if (cached is ProotShellExecutor) {
            return cached
        }

        val executor = ProotShellExecutor(linuxEnv)
        executorCache[AndroidPermissionLevel.STANDARD] = executor
        return executor
    }

    /**
     * 创建指定权限级别的执行器实例。
     *
     * 根据权限级别创建对应的执行器：
     * - ROOT: [RootShellExecutor]
     * - STANDARD: [StandardShellExecutor]
     * - ACCESSIBILITY/DEBUGGER/ADMIN: 暂未实现，回退到 [StandardShellExecutor]
     *
     * @param context Android 上下文
     * @param level   目标权限级别
     * @return 新创建的 Shell 执行器实例
     */
    private fun createExecutor(context: Context, level: AndroidPermissionLevel): ShellExecutor {
        Log.i(TAG, "创建执行器: ${level.displayName}")
        val executor = when (level) {
            AndroidPermissionLevel.ROOT -> RootShellExecutor()
            AndroidPermissionLevel.STANDARD -> StandardShellExecutor()
            AndroidPermissionLevel.ACCESSIBILITY -> {
                Log.w(TAG, "ACCESSIBILITY 执行器尚未实现，回退到 STANDARD")
                StandardShellExecutor()
            }
            AndroidPermissionLevel.DEBUGGER -> {
                Log.w(TAG, "DEBUGGER 执行器尚未实现，回退到 STANDARD")
                StandardShellExecutor()
            }
            AndroidPermissionLevel.ADMIN -> {
                Log.w(TAG, "ADMIN 执行器尚未实现，回退到 STANDARD")
                StandardShellExecutor()
            }
        }
        executor.initialize()
        return executor
    }

    /**
     * 清除所有缓存的执行器实例。
     *
     * 调用后会销毁所有活动进程并释放资源。
     * 下次获取执行器时会重新创建。
     */
    fun clearCache() {
        executorCache.forEach { (_, executor) ->
            when (executor) {
                is StandardShellExecutor -> executor.destroyAll()
                is ProotShellExecutor -> executor.destroyAll()
                is RootShellExecutor -> executor.destroyAll()
            }
        }
        executorCache.clear()
        Log.i(TAG, "执行器缓存已清除")
    }

    /**
     * 获取所有已缓存的执行器及其可用性状态。
     *
     * @return 权限级别到（执行器实例, 是否可用）的映射
     */
    fun getCachedExecutors(): Map<AndroidPermissionLevel, Pair<ShellExecutor, Boolean>> {
        return executorCache.mapValues { (_, executor) ->
            executor to executor.isAvailable()
        }
    }
}
