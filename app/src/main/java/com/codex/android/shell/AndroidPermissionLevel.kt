package com.codex.android.shell

/**
 * Android Shell 权限级别枚举。
 *
 * 定义 Shell 执行器的权限等级，从标准用户权限到 Root 权限递增。
 * 每个级别对应不同的执行环境和能力范围：
 *
 * - **STANDARD**: 应用进程标准权限，通过 `sh -c` 执行
 * - **ACCESSIBILITY**: 无障碍服务权限，可执行辅助操作
 * - **DEBUGGER**: 调试器权限，可附加到其他进程
 * - **ADMIN**: 设备管理员权限，可执行设备策略操作
 * - **ROOT**: Root 权限，通过 `su -c` 执行，拥有完整系统控制
 *
 * 在 [ShellExecutorFactory.getHighestAvailable] 中，按
 * ROOT → ADMIN → DEBUGGER → ACCESSIBILITY → STANDARD 的顺序
 * 选择最高可用权限级别。
 *
 * @property displayName 用户可见的显示名称
 * @property description  权限级别的功能描述
 */
enum class AndroidPermissionLevel(
    val displayName: String,
    val description: String
) {
    /**
     * 标准权限级别。
     *
     * 应用进程自身的权限，通过 `sh -c` 执行命令。
     * 可访问应用沙箱内的文件和目录，无法执行需要更高权限的操作。
     */
    STANDARD(
        displayName = "标准",
        description = "应用进程标准权限，通过 sh -c 执行，可访问应用沙箱内文件"
    ),

    /**
     * 无障碍服务权限级别。
     *
     * 通过 Android Accessibility Service 获取的增强权限，
     * 可执行 UI 自动化操作（如点击、滑动、读取屏幕内容）。
     */
    ACCESSIBILITY(
        displayName = "无障碍",
        description = "无障碍服务权限，可执行 UI 自动化和辅助操作"
    ),

    /**
     * 调试器权限级别。
     *
     * 通过 JDWP/ptrace 附加到其他进程，用于调试和进程分析。
     * 需要 android.permission.DEBUG_UID 或 ro.debuggable 属性。
     */
    DEBUGGER(
        displayName = "调试器",
        description = "调试器权限，可附加到其他进程进行分析和调试"
    ),

    /**
     * 设备管理员权限级别。
     *
     * 通过 Android Device Admin API 获取的策略管理权限，
     * 可执行设备擦除、密码策略、应用限制等操作。
     */
    ADMIN(
        displayName = "管理员",
        description = "设备管理员权限，可执行设备策略和安全操作"
    ),

    /**
     * Root 权限级别。
     *
     * 通过 `su -c` 执行命令，拥有完整的系统控制权限。
     * 需要设备已 Root（Magisk/KernelSU 等）。
     * 此级别可执行所有系统级操作，包括修改系统分区、
     * 访问所有用户数据、管理所有进程等。
     */
    ROOT(
        displayName = "Root",
        description = "Root 权限，通过 su -c 执行，拥有完整系统控制能力"
    );

    companion object {
        /**
         * 按优先级从高到低排列的权限级别列表。
         *
         * 用于 [ShellExecutorFactory.getHighestAvailable] 中
         * 选择最高可用权限级别。
         */
        val PRIORITY_ORDER = listOf(
            ROOT,
            ADMIN,
            DEBUGGER,
            ACCESSIBILITY,
            STANDARD
        )
    }
}
