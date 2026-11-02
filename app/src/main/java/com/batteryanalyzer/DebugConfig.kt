package com.batteryanalyzer

/**
 * 调试开关配置 (无UI, 仅供内部/开发者/AI 使用)
 *
 * 所有标志默认为 false (关闭)，避免影响正常运行性能。
 * AI 查找时可快速定位并切换到 true 以启用诊断日志。
 *
 * 用法:
 *   DebugConfig.keyMappingLog = true   // 开启获取键值日志
 *   DebugConfig.crashExitLog  = true   // 开启回退BUG日志
 */
object DebugConfig {

    /**
     * 控制 KeyMappingLogger 是否写入 "获取键值.txt"
     * 关闭后日志文件不会增长，但内存中的数据库操作不受影响
     */
    @Volatile
    var keyMappingLog: Boolean = false

    /**
     * 控制 CrashExitMonitor 是否写入 "回退BUG.txt"
     * 关闭后非崩溃事件不会写入文件，但崩溃捕获始终生效
     */
    @Volatile
    var crashExitLog: Boolean = false

}
