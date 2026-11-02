package com.batteryanalyzer.keymap

import android.content.Context
import android.os.Build
import android.util.Log
import com.batteryanalyzer.DebugConfig
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 键值映射日志记录器 v1.0
 *
 * 将所有键值映射操作写入本地文件 "获取键值.txt"，用于诊断键值映射问题。
 *
 * 每条日志记录以下信息:
 * 1. 是否获取到实际键值 (keyCode/scanCode)
 * 2. 使用的键值是什么 (数值 + 名称)
 * 3. 该键值是否成功映射 (源键→目标键 + 注入结果)
 *
 * 辅助排查:
 * - 键值760是否被系统全局搜索/输入框拦截而非蓝牙电量软件接收
 * - 无障碍服务是否能收到该键值事件
 * - Linux→Android 键值翻译是否正确
 */
object KeyMappingLogger {

    private const val TAG = "KeyMappingLogger"
    private const val LOG_FILE_NAME = "获取键值.txt"
    private const val MAX_LOG_SIZE_BYTES = 5 * 1024 * 1024L // 5MB 上限自动轮转

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "KeyMappingLogger").apply { priority = Thread.MIN_PRIORITY }
    }
    private val lock = ReentrantLock()

    private var logDir: File? = null
    private var initialized = false

    // ============ 设备信息覆盖 (由 KeyMappingScreen 注入，供 KeyMappingService 使用) ============
    /** 当前选中设备名称 */
    @Volatile var deviceNameOverride: String? = null
    /** 当前选中设备类型 (BLE / INPUT) */
    @Volatile var deviceTypeOverride: String? = null
    /** 当前选中设备标识符 (MAC / modelName) */
    @Volatile var deviceIdentifierOverride: String? = null

    // ============ 日志操作类型 ============

    enum class OpType(val label: String) {
        /** 物理按键到达无障碍服务 onKeyEvent */
        KEY_EVENT_RECEIVED("按键事件到达"),
        /** 学习模式捕获到键值 */
        LEARN_CAPTURE("学习模式捕获"),
        /** 学习模式下未捕获到键值（超时/取消） */
        LEARN_TIMEOUT("学习超时"),
        /** 映射模式命中并尝试注入 */
        MAPPING_MATCH("映射命中"),
        /** 映射模式未命中（无匹配规则） */
        MAPPING_NO_MATCH("映射未命中"),
        /** 键值注入执行 */
        INJECTION("键值注入"),
        /** 手动输入键值 */
        MANUAL_INPUT("手动输入"),
        /** 手动修正 Linux 键值 */
        MANUAL_CORRECTION("手动修正"),
        /** 服务启动/停止 */
        SERVICE_STATE("服务状态"),
        /** 映射启用/禁用 */
        MAPPING_TOGGLE("映射开关"),
        /** v6.7: getevent 底层监听状态 */
        GETEENT_STATUS("底层监听状态"),
        /** v6.7: getevent 捕获到键值 */
        GETEENT_CAPTURE("底层监听捕获"),
    }

    // ============ 日志数据类 ============

    data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val opType: OpType,
        // 设备信息
        val deviceId: Long = -1,
        val deviceName: String = "",
        val deviceType: String = "",   // "BLE" / "INPUT" / "UNKNOWN"
        val deviceIdentifier: String = "", // MAC / modelName
        // 按键事件原始信息
        val scanCode: Int = 0,
        val keyCode: Int = 0,
        val keyName: String = "",
        val eventAction: String = "",   // "DOWN" / "UP" / "FLAG_CANCELED"
        val eventSource: String = "",   // InputDevice source hex
        // 处理结果
        val acquiredActualKey: Boolean = false,   // 1. 是否获取到实际键值
        val usedKeyInfo: String = "",              // 2. 使用的键值说明 (如 "keyCode=131(F1)")
        val mappingMatched: Boolean = false,       // 3. 映射是否命中
        val mappingId: Long = -1,
        val matchType: String = "",                // "keyCode精确匹配" / "scanCode兜底匹配" / "学习模式" / "手动输入"
        val targetKeyCode: Int = -1,
        val targetKeyName: String = "",
        // 注入结果
        val injectionMethod: String = "",
        val injectionSuccess: Boolean? = null,     // null = 未执行注入
        val injectionDetail: String = "",
        // 诊断信息
        val accessibilityRunning: Boolean = false,
        val shizukuAvailable: Boolean = false,
        val shizukuGranted: Boolean = false,
        val activeMappingCount: Int = 0,
        val extraDiagnosis: String = "",           // 额外诊断说明
    )

    // ============ 初始化 ============

    fun init(context: Context) {
        if (initialized) return
        lock.withLock {
            if (initialized) return@withLock
            try {
                // 优先使用外部存储 (用户可访问)
                logDir = context.getExternalFilesDir("logs")
                if (logDir == null || !logDir!!.exists()) {
                    logDir = File(context.filesDir, "logs")
                }
                logDir!!.mkdirs()
                initialized = true
                Log.i(TAG, "日志目录: ${logDir!!.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "初始化日志目录失败: ${e.message}")
            }
        }
    }

    // ============ 对外 API ============

    /**
     * 记录按键事件到达无障碍服务
     * 这是诊断键值 760 是否被系统拦截的关键入口 ——
     * 如果这里收不到事件，说明被全局搜索/输入框拦截了
     */
    fun logKeyEventReceived(
        keyCode: Int, scanCode: Int, action: Int,
        eventDeviceId: Int, eventSource: Int,
        accessibilityRunning: Boolean, shizukuGranted: Boolean,
    ) {
        val actionName = when (action) {
            0 -> "DOWN"
            1 -> "UP"
            2 -> "MULTIPLE"
            else -> "FLAG_CANCELED"
        }
        val keyName = KeyMappingManager.lookupCodeName(keyCode) ?: "UNKNOWN"
        // 只有 DOWN 事件才会被后续处理
        val isActuallyProcessed = action == 0 // ACTION_DOWN

        writeLog(LogEntry(
            opType = OpType.KEY_EVENT_RECEIVED,
            deviceId = eventDeviceId.toLong(),
            deviceIdentifier = "inputDevice:$eventDeviceId",
            deviceType = "INPUT",
            scanCode = scanCode,
            keyCode = keyCode,
            keyName = keyName,
            eventAction = actionName,
            eventSource = "0x${eventSource.toString(16)}",
            acquiredActualKey = isActuallyProcessed && (keyCode > 0 || scanCode > 0),
            usedKeyInfo = if (keyCode > 0) "keyCode=$keyCode($keyName)" else "scanCode=$scanCode",
            extraDiagnosis = buildString {
                if (!isActuallyProcessed) append("非DOWN事件，不处理; ")
                if (keyCode <= 0 && scanCode <= 0) append("键值无效(keyCode=0,scanCode=0); ")
                if (keyCode != scanCode && scanCode > 0) append("Linux→Android翻译: scanCode=$scanCode→keyCode=$keyCode; ")
            },
            accessibilityRunning = accessibilityRunning,
            shizukuGranted = shizukuGranted,
        ))
    }

    /**
     * 记录学习模式捕获到键值
     */
    fun logLearnCapture(
        keyCode: Int, scanCode: Int,
        learnTarget: KeyMappingManager.LearnTarget,
        mappingId: Long,
        deviceId: Long, deviceName: String, deviceType: String, deviceIdentifier: String,
        accessibilityRunning: Boolean, shizukuGranted: Boolean,
        isKnownKey: Boolean,
    ) {
        val keyName = KeyMappingManager.lookupCodeName(keyCode) ?: "UNKNOWN_$keyCode"
        val targetLabel = when (learnTarget) {
            KeyMappingManager.LearnTarget.SOURCE -> "源键"
            KeyMappingManager.LearnTarget.TARGET -> "目标键"
        }

        writeLog(LogEntry(
            opType = OpType.LEARN_CAPTURE,
            deviceId = deviceId,
            deviceName = deviceName,
            deviceType = deviceType,
            deviceIdentifier = deviceIdentifier,
            scanCode = scanCode,
            keyCode = keyCode,
            keyName = keyName,
            eventAction = "DOWN",
            acquiredActualKey = true,
            usedKeyInfo = "keyCode=$keyCode($keyName), scanCode=$scanCode",
            mappingMatched = true,
            mappingId = mappingId,
            matchType = "学习模式($targetLabel)",
            extraDiagnosis = buildString {
                if (scanCode > 0 && keyCode != scanCode) append("Linux→Android翻译: scanCode=$scanCode→keyCode=$keyCode; ")
                if (!isKnownKey) append("未知键值，已自动注册; ")
                if (keyCode > 284) append("键值>$284，可能为Linux键值; ")
            },
            accessibilityRunning = accessibilityRunning,
            shizukuGranted = shizukuGranted,
        ))
    }

    /**
     * 记录学习超时/取消
     */
    fun logLearnTimeout(
        reason: String,
        deviceId: Long, deviceName: String,
    ) {
        writeLog(LogEntry(
            opType = OpType.LEARN_TIMEOUT,
            deviceId = deviceId,
            deviceName = deviceName,
            acquiredActualKey = false,
            usedKeyInfo = "未捕获到键值",
            extraDiagnosis = reason,
        ))
    }

    /**
     * 记录映射模式匹配命中
     */
    fun logMappingMatch(
        sourceKeyCode: Int, targetKeyCode: Int,
        scanCode: Int, matchedKeyCode: Int,
        matchType: String, mappingId: Long,
        deviceId: Long, deviceName: String, deviceType: String, deviceIdentifier: String,
        accessibilityRunning: Boolean, shizukuGranted: Boolean,
        activeMappingCount: Int,
    ) {
        val sourceName = KeyMappingManager.lookupCodeName(sourceKeyCode) ?: "UNKNOWN_$sourceKeyCode"
        val targetName = KeyMappingManager.lookupCodeName(targetKeyCode) ?: "UNKNOWN_$targetKeyCode"
        val matchedName = KeyMappingManager.lookupCodeName(matchedKeyCode) ?: "UNKNOWN_$matchedKeyCode"

        writeLog(LogEntry(
            opType = OpType.MAPPING_MATCH,
            deviceId = deviceId,
            deviceName = deviceName,
            deviceType = deviceType,
            deviceIdentifier = deviceIdentifier,
            scanCode = scanCode,
            keyCode = matchedKeyCode,
            keyName = matchedName,
            eventAction = "DOWN",
            acquiredActualKey = true,
            usedKeyInfo = "配置源键=$sourceKeyCode($sourceName), 实际命中: $matchType($matchedKeyCode)",
            mappingMatched = true,
            mappingId = mappingId,
            matchType = matchType,
            targetKeyCode = targetKeyCode,
            targetKeyName = targetName,
            extraDiagnosis = buildString {
                if (matchType.contains("scanCode")) append("scanCode兜底匹配生效(可能使用了Linux键值); ")
                if (sourceKeyCode != matchedKeyCode) append("源键值${sourceKeyCode}≠物理键值${matchedKeyCode}($matchType); ")
            },
            accessibilityRunning = accessibilityRunning,
            shizukuGranted = shizukuGranted,
            activeMappingCount = activeMappingCount,
        ))
    }

    /**
     * 记录映射模式未匹配到任何规则
     */
    fun logMappingNoMatch(
        keyCode: Int, scanCode: Int,
        activeMappingCount: Int,
        accessibilityRunning: Boolean,
        deviceId: Long,
    ) {
        val keyName = KeyMappingManager.lookupCodeName(keyCode) ?: "UNKNOWN_$keyCode"

        writeLog(LogEntry(
            opType = OpType.MAPPING_NO_MATCH,
            deviceId = deviceId,
            scanCode = scanCode,
            keyCode = keyCode,
            keyName = keyName,
            eventAction = "DOWN",
            acquiredActualKey = true,
            usedKeyInfo = "keyCode=$keyCode($keyName), scanCode=$scanCode",
            mappingMatched = false,
            extraDiagnosis = buildString {
                append("活跃映射数=$activeMappingCount; ")
                if (activeMappingCount == 0) append("无活跃映射规则，按键被放行; ")
                else append("按键(${keyCode})未匹配到任何源键配置; ")
                if (keyCode > 284) append("注意: keyCode>284 可能是Linux键值，请检查.kl翻译; ")
            },
            accessibilityRunning = accessibilityRunning,
            activeMappingCount = activeMappingCount,
        ))
    }

    /**
     * 记录防抖拦截
     */
    fun logDebounceBlocked(
        keyCode: Int, mappingId: Long,
        deviceId: Long, deviceName: String,
    ) {
        val keyName = KeyMappingManager.lookupCodeName(keyCode) ?: "UNKNOWN_$keyCode"
        writeLog(LogEntry(
            opType = OpType.MAPPING_MATCH,
            deviceId = deviceId,
            deviceName = deviceName,
            keyCode = keyCode,
            keyName = keyName,
            acquiredActualKey = true,
            usedKeyInfo = "keyCode=$keyCode($keyName)",
            mappingMatched = false,
            mappingId = mappingId,
            extraDiagnosis = "防抖拦截(300ms内重复按键)",
        ))
    }

    /**
     * 记录键值注入执行
     */
    fun logInjection(
        targetKeyCode: Int,
        method: String,
        success: Boolean,
        detail: String,
        deviceId: Long, deviceName: String,
    ) {
        val targetName = KeyMappingManager.lookupCodeName(targetKeyCode) ?: "UNKNOWN_$targetKeyCode"
        writeLog(LogEntry(
            opType = OpType.INJECTION,
            deviceId = deviceId,
            deviceName = deviceName,
            targetKeyCode = targetKeyCode,
            targetKeyName = targetName,
            injectionMethod = method,
            injectionSuccess = success,
            injectionDetail = detail,
            extraDiagnosis = if (!success) "注入失败: $detail" else "",
        ))
    }

    /**
     * 记录手动输入键值
     */
    fun logManualInput(
        inputText: String, parsedCode: Int, parsedName: String,
        isSource: Boolean, mappingId: Long,
        deviceId: Long, deviceName: String, deviceType: String, deviceIdentifier: String,
    ) {
        val role = if (isSource) "源键" else "目标键"
        writeLog(LogEntry(
            opType = OpType.MANUAL_INPUT,
            deviceId = deviceId,
            deviceName = deviceName,
            deviceType = deviceType,
            deviceIdentifier = deviceIdentifier,
            keyCode = parsedCode,
            keyName = parsedName,
            acquiredActualKey = parsedCode > 0,
            usedKeyInfo = "输入=\"$inputText\" → 解析=$parsedCode($parsedName) [$role]",
            mappingId = mappingId,
            matchType = "手动输入",
            extraDiagnosis = buildString {
                if (parsedCode <= 0) append("解析失败: 输入\"$inputText\"无法识别; ")
                if (parsedCode > 284 && isSource) append("源键值>$284可能为Linux键值; ")
            },
        ))
    }

    /**
     * 记录手动修正 Linux→Android 键值
     */
    fun logManualCorrection(
        linuxCode: Int, androidCode: Int, androidName: String,
        correctionSource: String,
        deviceId: Long, deviceName: String,
    ) {
        writeLog(LogEntry(
            opType = OpType.MANUAL_CORRECTION,
            deviceId = deviceId,
            deviceName = deviceName,
            scanCode = linuxCode,
            keyCode = androidCode,
            keyName = androidName,
            acquiredActualKey = true,
            usedKeyInfo = "Linux键值=$linuxCode → Android键值=$androidCode($androidName)",
            matchType = "手动修正($correctionSource)",
            extraDiagnosis = "用户点击修正按钮，来源: $correctionSource",
        ))
    }

    /**
     * 记录无障碍服务启动/停止
     */
    fun logServiceState(connected: Boolean) {
        writeLog(LogEntry(
            opType = OpType.SERVICE_STATE,
            accessibilityRunning = connected,
            usedKeyInfo = if (connected) "无障碍服务已启动" else "无障碍服务已停止",
            extraDiagnosis = if (!connected) "按键拦截功能不可用" else "",
        ))
    }

    /**
     * 记录映射启用/禁用
     */
    fun logMappingToggle(
        enabled: Boolean, mappingCount: Int,
        deviceId: Long, deviceName: String,
    ) {
        writeLog(LogEntry(
            opType = OpType.MAPPING_TOGGLE,
            deviceId = deviceId,
            deviceName = deviceName,
            activeMappingCount = if (enabled) mappingCount else 0,
            usedKeyInfo = if (enabled) "映射已启用(${mappingCount}条规则)" else "映射已禁用",
            extraDiagnosis = if (enabled && mappingCount == 0) "警告: 无有效映射规则" else "",
        ))
    }

    /**
     * v6.7: 记录 getevent 底层监听状态变化
     */
    fun logGeteventStatus(
        active: Boolean, detail: String,
        enterCode: Int,
        deviceId: Long, deviceName: String,
    ) {
        writeLog(LogEntry(
            opType = OpType.GETEENT_STATUS,
            deviceId = deviceId,
            deviceName = deviceName,
            keyCode = enterCode,
            usedKeyInfo = if (active) "getevent 底层监听已启动" else "getevent 底层监听已停止",
            extraDiagnosis = buildString {
                append(detail)
                if (!active) append("; ")
                if (enterCode > 0) append("指定监听键值: $enterCode (0x${enterCode.toString(16)}); ")
            },
        ))
    }

    /**
     * v6.7: 记录 getevent 捕获到键值
     */
    fun logGeteventCapture(
        keyCode: Int, rawLine: String,
        enterCodeMode: Boolean,
        deviceId: Long, deviceName: String,
    ) {
        val keyName = KeyMappingManager.lookupCodeName(keyCode) ?: "UNKNOWN_$keyCode"
        writeLog(LogEntry(
            opType = OpType.GETEENT_CAPTURE,
            deviceId = deviceId,
            deviceName = deviceName,
            keyCode = keyCode,
            keyName = keyName,
            eventAction = "DOWN",
            acquiredActualKey = true,
            usedKeyInfo = "getevent 捕获: keyCode=$keyCode (0x${keyCode.toString(16)}) [$keyName]",
            matchType = "底层监听(getevent)",
            extraDiagnosis = buildString {
                append("raw: $rawLine; ")
                if (enterCodeMode) append("指定键值模式，已匹配; ")
                if (keyCode > 284) append("keyCode>$284 为厂商自定义键值(绕过系统拦截成功); ")
            },
        ))
    }

    /**
     * 获取日志文件路径 (供外部读取/分享)
     */
    fun getLogFilePath(): String? {
        val dir = logDir ?: return null
        val file = File(dir, LOG_FILE_NAME)
        return file.absolutePath
    }

    /**
     * 清空日志文件
     */
    fun clearLog() {
        writeExecutor.execute {
            try {
                val dir = logDir ?: return@execute
                val file = File(dir, LOG_FILE_NAME)
                if (file.exists()) file.delete()
                // 写入文件头
                writeHeader(file)
                Log.i(TAG, "日志已清空")
            } catch (e: Exception) {
                Log.w(TAG, "清空日志失败: ${e.message}")
            }
        }
    }

    // ============ 内部实现 ============

    private fun writeLog(entry: LogEntry) {
        // v6.8: 调试开关 — 关闭时不写入文件，避免日志文件膨胀
        if (!DebugConfig.keyMappingLog) return
        if (!initialized) {
            Log.w(TAG, "Logger未初始化，丢弃日志: ${entry.opType.label}")
            return
        }
        writeExecutor.execute {
            try {
                val dir = logDir ?: return@execute
                val file = File(dir, LOG_FILE_NAME)

                // 文件超过上限时轮转
                if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                    val backup = File(dir, "获取键值_旧.txt")
                    backup.delete()
                    file.renameTo(backup)
                    Log.i(TAG, "日志文件已轮转 (>${MAX_LOG_SIZE_BYTES / 1024 / 1024}MB)")
                }

                val isNewFile = !file.exists()
                val writer = PrintWriter(FileWriter(file, true), true)

                if (isNewFile) {
                    writeHeader(file)
                    writer.println(formatHeader())
                }

                writer.println(formatEntry(entry))
                writer.println()
                writer.close()
            } catch (e: Exception) {
                Log.w(TAG, "写入日志失败: ${e.message}")
            }
        }
    }

    private fun writeHeader(file: File) {
        // 通过 PrintWriter 写入在 formatHeader 中处理
    }

    private fun formatHeader(): String {
        return buildString {
            append("=" .repeat(80)).append("\n")
            append("📋 键值映射日志文件 — 获取键值.txt\n")
            append("创建时间: ${dateFormat.format(Date())}\n")
            append("Android SDK: ${Build.VERSION.SDK_INT}\n")
            append("设备型号: ${Build.MODEL}\n")
            append("=$".repeat(39)).append("=").append("\n")
            append("\n")
            append("字段说明:\n")
            append("  获取键值: 是否成功捕获/解析到实际键值\n")
            append("  使用键值: 实际使用的键值编号及名称\n")
            append("  映射结果: 源键值是否成功映射到目标键值\n")
            append("  注入结果: 目标键值是否成功注入到系统\n")
            append("\n")
            append("=" .repeat(80))
        }
    }

    private fun formatEntry(entry: LogEntry): String {
        val ts = dateFormat.format(Date(entry.timestamp))
        val opIcon = when (entry.opType) {
            OpType.KEY_EVENT_RECEIVED -> "🔔"
            OpType.LEARN_CAPTURE -> "📡"
            OpType.LEARN_TIMEOUT -> "⏰"
            OpType.MAPPING_MATCH -> "✅"
            OpType.MAPPING_NO_MATCH -> "❌"
            OpType.INJECTION -> "💉"
            OpType.MANUAL_INPUT -> "⌨️"
            OpType.MANUAL_CORRECTION -> "🔧"
            OpType.SERVICE_STATE -> "⚙️"
            OpType.MAPPING_TOGGLE -> "🔒"
            OpType.GETEENT_STATUS -> "🔬"
            OpType.GETEENT_CAPTURE -> "🎯"
        }

        return buildString {
            append("=" .repeat(80)).append("\n")
            append("$opIcon ${entry.opType.label}\n")
            append("时间: $ts\n")

            // 设备信息
            if (entry.deviceId > 0 || entry.deviceName.isNotBlank()) {
                append("-".repeat(80)).append("\n")
                append("设备信息:\n")
                if (entry.deviceId > 0) append("  设备ID: ${entry.deviceId}\n")
                if (entry.deviceName.isNotBlank()) append("  设备名称: ${entry.deviceName}\n")
                if (entry.deviceType.isNotBlank()) append("  设备类型: ${entry.deviceType}\n")
                if (entry.deviceIdentifier.isNotBlank()) append("  标识符: ${entry.deviceIdentifier}\n")
            }

            // 按键事件
            if (entry.keyCode > 0 || entry.scanCode > 0) {
                append("-".repeat(80)).append("\n")
                append("按键信息:\n")
                if (entry.scanCode > 0) append("  物理键值(scanCode): ${entry.scanCode}\n")
                if (entry.keyCode > 0) append("  Android键值(keyCode): ${entry.keyCode}")
                if (entry.keyName.isNotBlank() && entry.keyName != "UNKNOWN" && entry.keyName != "UNKNOWN_${entry.keyCode}") {
                    append(" (${entry.keyName})")
                }
                append("\n")
                if (entry.eventAction.isNotBlank()) append("  按键动作: ${entry.eventAction}\n")
                if (entry.eventSource.isNotBlank()) append("  事件来源: ${entry.eventSource}\n")
            }

            // 处理结果 — 核心三项
            append("-".repeat(80)).append("\n")
            append("处理结果:\n")
            append("  1. 是否获取到实际键值: ${if (entry.acquiredActualKey) "✅ 是" else "❌ 否"}\n")
            if (entry.usedKeyInfo.isNotBlank()) append("  2. 使用的键值: ${entry.usedKeyInfo}\n")
            if (entry.matchType.isNotBlank()) append("     匹配方式: ${entry.matchType}\n")
            if (entry.opType == OpType.MAPPING_MATCH || entry.opType == OpType.MAPPING_NO_MATCH || entry.opType == OpType.LEARN_CAPTURE) {
                append("  3. 是否成功映射: ${if (entry.mappingMatched) "✅ 是" else "❌ 否"}\n")
            }
            if (entry.mappingId > 0) append("     映射ID: ${entry.mappingId}\n")
            if (entry.targetKeyCode > 0) {
                val targetDisplay = if (entry.targetKeyName.isNotBlank() && !entry.targetKeyName.startsWith("UNKNOWN"))
                    " → ${entry.targetKeyName}(${entry.targetKeyCode})" else " → ${entry.targetKeyCode}"
                append("     目标键值: $targetDisplay\n")
            }

            // 注入结果
            if (entry.injectionMethod.isNotBlank()) {
                append("-".repeat(80)).append("\n")
                append("注入结果:\n")
                append("  注入方式: ${entry.injectionMethod}\n")
                if (entry.injectionSuccess != null) {
                    append("  注入结果: ${if (entry.injectionSuccess) "✅ 成功" else "❌ 失败"}\n")
                }
                if (entry.injectionDetail.isNotBlank()) append("  详情: ${entry.injectionDetail}\n")
            }

            // 诊断信息
            append("-".repeat(80)).append("\n")
            append("诊断状态:\n")
            append("  无障碍服务: ${if (entry.accessibilityRunning) "运行中" else "未运行"}\n")
            append("  Shizuku权限: ${if (entry.shizukuGranted) "已授权" else if (entry.shizukuAvailable) "已安装未授权" else "不可用"}\n")
            if (entry.activeMappingCount >= 0) append("  活跃映射数: ${entry.activeMappingCount}\n")

            // 关键诊断
            if (entry.extraDiagnosis.isNotBlank()) {
                append("-".repeat(80)).append("\n")
                append("⚠ 诊断说明: ${entry.extraDiagnosis}\n")
            }

            append("=" .repeat(80))
        }
    }
}
