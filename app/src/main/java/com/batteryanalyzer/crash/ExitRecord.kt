package com.batteryanalyzer.crash

/**
 * 回退事件记录
 */
data class ExitRecord(
    /** 退回原因 */
    val reason: AppExitReason,

    /** 发生时间戳 (epoch ms) */
    val timestamp: Long = System.currentTimeMillis(),

    /** 异常堆栈信息（仅崩溃场景有值） */
    val stackTrace: String? = null,

    /** 异常消息 */
    val message: String? = null,

    /** 当前操作的上下文标记 */
    val contextTags: List<String> = emptyList(),

    /** 当前内存信息 */
    val memoryInfo: String? = null,

    /** 当前 BLE 连接状态 */
    val bleConnectionState: String? = null,

    /** 进程是否被杀死 */
    val processKilled: Boolean = false
) {
    /** 格式化输出为可读字符串 */
    fun toFormattedString(): String = buildString {
        appendLine("=" .repeat(70))
        appendLine("【回退BUG记录】")
        appendLine("=".repeat(70))
        appendLine("退回原因   : ${reason.label} (${reason.name})")
        appendLine("发生时间   : ${formatTimestamp(timestamp)}")
        appendLine("进程被杀   : $processKilled")
        appendLine("-".repeat(70))

        if (!contextTags.isNullOrEmpty()) {
            appendLine("上下文标记 : ${contextTags.joinToString(" → ")}")
            appendLine("-".repeat(70))
        }

        if (!bleConnectionState.isNullOrBlank()) {
            appendLine("BLE状态    : $bleConnectionState")
            appendLine("-".repeat(70))
        }

        if (!memoryInfo.isNullOrBlank()) {
            appendLine("内存信息   : $memoryInfo")
            appendLine("-".repeat(70))
        }

        if (!message.isNullOrBlank()) {
            appendLine("异常消息   : $message")
            appendLine("-".repeat(70))
        }

        if (!stackTrace.isNullOrBlank()) {
            appendLine("异常堆栈   :")
            appendLine(stackTrace)
            appendLine("-".repeat(70))
        }

        appendLine("记录时间   : ${formatTimestamp(System.currentTimeMillis())}")
        appendLine("=".repeat(70))
        appendLine()
    }

    companion object {
        fun formatTimestamp(ms: Long): String {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault())
            return sdf.format(java.util.Date(ms))
        }
    }
}
