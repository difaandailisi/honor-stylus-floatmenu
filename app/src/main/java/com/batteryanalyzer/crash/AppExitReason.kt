package com.batteryanalyzer.crash

/**
 * 应用退回桌面的原因分类
 */
enum class AppExitReason(val label: String) {
    /** 用户主动按 Home 键退回桌面 */
    USER_PRESS_HOME("用户主动退回桌面"),

    /** 应用内部逻辑调用 finish() / navigate 回退等 */
    APP_INTERNAL_EXIT("应用内部逻辑退回桌面"),

    /** 系统崩溃 / 未捕获异常 / 闪退 */
    SYSTEM_CRASH("系统崩溃或闪退"),

    /** 内存泄漏导致 OOM / 系统回收 */
    MEMORY_LEAK("内存泄漏回退桌面"),

    /** 手写笔操作期间回退桌面 */
    STYLUS_OPERATION("调用手写笔回退桌面"),

    /** 蓝牙 / BLE 操作期间回退桌面 */
    BLE_OPERATION("使用蓝牙或BLE回退桌面"),

    /** 查询电量或其他参数期间回退桌面 */
    BATTERY_QUERY("查询电量或参数回退桌面"),

    /** 未知原因 */
    UNKNOWN("未知原因回退桌面")
}
