package com.batteryanalyzer.crash

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import com.batteryanalyzer.DebugConfig
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Android 异常捕获与回退桌面监控模块
 *
 * 功能：
 * 1. 捕获未处理异常（闪退/崩溃）
 * 2. 检测用户按 Home 键退回桌面（仅内存记录，不写文件）
 * 3. 检测应用内部逻辑退出
 * 4. 内存泄漏检测（Activity 泄漏）
 * 5. 手写笔、BLE、电量查询等操作的上下文标记
 *
 * 所有异常退回事宜会自动输出到存储中 "回退BUG.txt" 文件
 *
 * IO 优化策略：
 * - 单线程 + 阻塞队列批量写入，避免频繁 new Thread
 * - 5s 内相同原因+消息去重
 * - 内存告警 5 分钟内只写一次
 * - 用户主动退回桌面不写文件（非异常）
 *
 * 使用方法：
 * - 在 Application.onCreate() 中调用 CrashExitMonitor.init(this)
 * - 在各个关键操作前调用 CrashExitMonitor.enterContext(tag) / exitContext()
 */
object CrashExitMonitor {

    private const val TAG = "CrashExitMonitor"
    private const val LOG_FILE_NAME = "回退BUG.txt"

    /** 同类记录去重窗口 (ms) */
    private const val DEDUP_WINDOW_MS = 5_000L
    /** 内存告警写入最小间隔 (ms) */
    private const val MEMORY_WARNING_INTERVAL_MS = 5 * 60_000L
    /** 内存监控检查间隔 (ms) — 从 30s 提升到 120s，减少 CPU 开销 */
    private const val MEMORY_MONITOR_INTERVAL_MS = 120_000L

    private lateinit var appContext: Context
    private val initialized = AtomicBoolean(false)
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    // 存储权限状态
    private var storageGranted = false

    // ---------- 上下文标记 ----------
    /** 当前活跃的上下文标记栈（线程安全） */
    private val contextTags = CopyOnWriteArrayList<String>()

    // ---------- Activity 追踪 ----------
    private val activityRefs = mutableListOf<WeakReference<Activity>>()
    private var visibleActivityCount = 0
    private var userPressedHome = false

    // ---------- BLE 状态 ----------
    private val bleConnectionState = AtomicReference<String?>(null)

    // ---------- 退出记录缓存 ----------
    private val exitRecords = mutableListOf<ExitRecord>()

    // ---------- 主线程 Handler ----------
    private val mainHandler = Handler(Looper.getMainLooper())

    // ==================== IO 优化：单线程写入器 ====================

    /** 写入请求队列 */
    private val writeQueue = LinkedBlockingQueue<ExitRecord>(64)

    /** 写入线程 */
    @Volatile private var writerThread: Thread? = null
    private val writerRunning = AtomicBoolean(false)

    /** 去重：上次写入的原因 + 消息摘要 + 时间戳 */
    private var lastWrittenReason: String? = null
    private var lastWrittenMessage: String? = null
    private val lastWriteTimeMs = AtomicLong(0L)

    /** 内存告警上次写入时间 */
    private val lastMemoryWarningTimeMs = AtomicLong(0L)

    // ==================== 初始化 ====================

    /**
     * 初始化异常监控模块，需在 Application.onCreate() 中最早调用
     */
    fun init(application: Application) {
        if (!initialized.compareAndSet(false, true)) {
            Log.w(TAG, "CrashExitMonitor 已初始化，跳过重复初始化")
            return
        }
        appContext = application.applicationContext

        // 检查存储权限
        storageGranted = checkStoragePermission()

        // 【省电优化】写入线程改为懒启动 — 仅在首次 enqueueWrite 时创建
        // startWriterThread() 移至 enqueueWrite() 中按需调用

        // 设置未捕获异常处理器
        setupCrashHandler()

        // 注册 Activity 生命周期回调
        registerActivityCallbacks(application)

        // 启动内存泄漏检测
        startMemoryMonitor()

        Log.i(TAG, "CrashExitMonitor 初始化完成 | 存储权限=$storageGranted")
    }

    // ==================== 写入线程 ====================

    private fun startWriterThread() {
        if (!writerRunning.compareAndSet(false, true)) return
        val thread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            Log.d(TAG, "写入线程已启动")
            while (writerRunning.get()) {
                try {
                    val record = writeQueue.poll(5, TimeUnit.SECONDS) ?: continue
                    writeRecordToFile(record)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "写入线程异常", e)
                }
            }
            // 退出前排空剩余队列
            drainRemaining()
            Log.d(TAG, "写入线程已退出")
        }, "CrashLogWriter").apply {
            isDaemon = true
            start()
        }
        writerThread = thread
    }

    /** 排空队列中剩余的记录 */
    private fun drainRemaining() {
        val remaining = mutableListOf<ExitRecord>()
        writeQueue.drainTo(remaining)
        remaining.forEach { record ->
            try {
                writeRecordToFile(record)
            } catch (_: Exception) {}
        }
    }

    /** 刷新队列并等待写入完成（崩溃场景） */
    private fun flushAndStop() {
        writerRunning.set(false)
        writerThread?.interrupt()
        drainRemaining()
    }

    // ==================== 上下文标记 ====================

    /**
     * 进入某个操作上下文（操作开始前调用）
     * @param tag 上下文标记，如 "BLE_CONNECT"、"STYLUS_INPUT"、"BATTERY_READ"
     */
    @JvmStatic
    fun enterContext(tag: String) {
        contextTags.add(tag)
        if (contextTags.size > 20) {
            // 防止无限增长，保留最近的 20 个标记
            contextTags.removeAt(0)
        }
    }

    /**
     * 退出某个操作上下文（操作完成后调用）
     * @param tag 与 enterContext 匹配的标记
     */
    @JvmStatic
    fun exitContext(tag: String) {
        contextTags.remove(tag)
    }

    /**
     * 设置 BLE 连接状态快照（供崩溃时记录）
     */
    @JvmStatic
    fun updateBleState(state: String?) {
        bleConnectionState.set(state)
    }

    // ==================== 公共查询 ====================

    /** 获取当前上下文标记快照 */
    @JvmStatic
    fun getCurrentContextTags(): List<String> = contextTags.toList()

    /** 获取所有已记录的退出事件 */
    @JvmStatic
    fun getExitRecords(): List<ExitRecord> = exitRecords.toList()

    /** 获取日志文件路径 */
    @JvmStatic
    fun getLogFilePath(): File {
        val dir = if (storageGranted && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            File(Environment.getExternalStorageDirectory(), "Documents")
        } else {
            // Android 10+ 或权限不足：写入 app 私有目录
            File(appContext.getExternalFilesDir(null), "crash_logs")
        }
        if (!dir.exists()) dir.mkdirs()
        return File(dir, LOG_FILE_NAME)
    }

    // ==================== 存储权限检查 ====================

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            appContext.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    // ==================== 崩溃捕获 (UncaughtExceptionHandler) ====================

    private fun setupCrashHandler() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "捕获到未处理异常: thread=${thread.name}", throwable)

            val stackTrace = getStackTraceString(throwable)
            val memoryInfo = collectMemoryInfo()

            // 判断详细原因
            val reason = when {
                throwable is OutOfMemoryError -> AppExitReason.MEMORY_LEAK
                contextTags.any { it.startsWith("BLE") } -> AppExitReason.BLE_OPERATION
                contextTags.any { it.startsWith("STYLUS") } -> AppExitReason.STYLUS_OPERATION
                contextTags.any { it.startsWith("BATTERY") } -> AppExitReason.BATTERY_QUERY
                else -> AppExitReason.SYSTEM_CRASH
            }

            val record = ExitRecord(
                reason = reason,
                stackTrace = stackTrace,
                message = "${throwable.javaClass.name}: ${throwable.message}",
                contextTags = contextTags.toList(),
                memoryInfo = memoryInfo,
                bleConnectionState = bleConnectionState.get(),
                processKilled = true
            )

            exitRecords.add(record)

            // 停止写入线程并同步排空队列
            writerRunning.set(false)
            writerThread?.interrupt()
            try {
                // 先写入当前崩溃记录
                writeRecordToFile(record)
                // 排空队列中早前排队的记录
                drainRemaining()
            } catch (_: Exception) {}

            // 交给系统默认处理器
            originalHandler?.uncaughtException(thread, throwable)

            // 杀死进程
            Process.killProcess(Process.myPid())
        }
    }

    // ==================== Activity 生命周期回调 ====================

    private fun registerActivityCallbacks(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                activityRefs.add(WeakReference(activity))
                userPressedHome = false
            }

            override fun onActivityStarted(activity: Activity) {
                visibleActivityCount++
            }

            override fun onActivityResumed(activity: Activity) {
                // 从后台恢复
            }

            override fun onActivityPaused(activity: Activity) {
                // 即将进入后台
            }

            override fun onActivityStopped(activity: Activity) {
                visibleActivityCount--
                checkAppWentToBackground()
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                // 清理已销毁 Activity 的引用
                activityRefs.removeAll { it.get() == null || it.get() == activity }

                // 如果这是最后一个 Activity 且用户没有按 Home 键
                // 说明是应用内部调用了 finish() 导致退出
                if (activity.isTaskRoot && activity.isFinishing && !userPressedHome) {
                    recordAppExit(
                        reason = AppExitReason.APP_INTERNAL_EXIT,
                        message = "Activity 栈已清空 (${activity.javaClass.simpleName} destroyed)",
                        processKilled = false
                    )
                }
            }

            /** 必须在 Activity 中手动调用 onUserLeaveHint 委托 */
            fun onUserLeaveHintDelegated() {
                userPressedHome = true
                recordAppExit(
                    reason = AppExitReason.USER_PRESS_HOME,
                    message = "用户按 Home 键或切换到其他应用",
                    processKilled = false
                )
            }
        })
    }

    /**
     * 当 Activity.onUserLeaveHint() 被调用时，由 Activity 委托至此
     * 必须在每个 Activity 中手动调用
     */
    fun onUserLeaveHint() {
        userPressedHome = true
        recordAppExit(
            reason = AppExitReason.USER_PRESS_HOME,
            message = "用户按 Home 键或切换到其他应用",
            processKilled = false
        )
    }

    /**
     * 检测 App 是否切到后台
     */
    private fun checkAppWentToBackground() {
        if (visibleActivityCount <= 0) {
            Log.d(TAG, "应用进入后台 (visibleActivityCount=0)")

            // 上下文标记分析
            val reason = when {
                contextTags.any { it.startsWith("BLE") } -> AppExitReason.BLE_OPERATION
                contextTags.any { it.startsWith("STYLUS") } -> AppExitReason.STYLUS_OPERATION
                contextTags.any { it.startsWith("BATTERY") } -> AppExitReason.BATTERY_QUERY
                userPressedHome -> AppExitReason.USER_PRESS_HOME
                else -> AppExitReason.APP_INTERNAL_EXIT
            }

            recordAppExit(
                reason = reason,
                message = "所有 Activity 进入 onStop，应用不可见",
                processKilled = false
            )
        }
    }

    // ==================== 内存泄漏检测 ====================

    private fun startMemoryMonitor() {
        // 每 120 秒检查一次内存状态和 Activity 泄漏（原 30s，降低 CPU 开销 4 倍）
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                try {
                    checkForLeakedActivities()
                    collectAndLogMemoryWarning()
                } catch (_: Exception) {
                } finally {
                    mainHandler.postDelayed(this, MEMORY_MONITOR_INTERVAL_MS)
                }
            }
        }, MEMORY_MONITOR_INTERVAL_MS)
    }

    /**
     * 检测可能存在泄漏的 Activity（WeakReference 未释放且 Activity 已 destroyed）
     */
    private fun checkForLeakedActivities() {
        val leaked = activityRefs.filter { ref ->
            val activity = ref.get()
            activity != null && activity.isDestroyed
        }
        if (leaked.isNotEmpty()) {
            val names = leaked.mapNotNull { it.get()?.javaClass?.simpleName }
            Log.w(TAG, "检测到可能存在泄漏的 Activity (${leaked.size} 个): $names")

            recordAppExit(
                reason = AppExitReason.MEMORY_LEAK,
                message = "检测到 ${leaked.size} 个已销毁但未释放的 Activity: $names",
                processKilled = false
            )

            // 清理泄漏引用
            activityRefs.removeAll { it in leaked }
        }
    }

    /**
     * 记录内存警告
     */
    private fun collectAndLogMemoryWarning() {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMemory = runtime.maxMemory() / (1024 * 1024)
        val ratio = usedMemory.toFloat() / maxMemory.toFloat()

        // 内存使用超过 85% 发出警告
        if (ratio > 0.85f) {
            Log.w(TAG, "内存使用率过高: ${usedMemory}MB / ${maxMemory}MB (${(ratio * 100).toInt()}%)")

            recordAppExit(
                reason = AppExitReason.MEMORY_LEAK,
                message = "内存使用率过高: ${(ratio * 100).toInt()}% (${usedMemory}MB/${maxMemory}MB)",
                processKilled = false
            )
        }
    }

    // ==================== 记录退出事件 ====================

    private fun recordAppExit(
        reason: AppExitReason,
        message: String,
        processKilled: Boolean
    ) {
        // 用户主动退回桌面不写文件（非异常行为）
        if (reason == AppExitReason.USER_PRESS_HOME) {
            Log.d(TAG, "用户主动退回桌面 (USER_PRESS_HOME)，跳过文件写入")
            exitRecords.add(ExitRecord(
                reason = reason,
                message = message,
                contextTags = contextTags.toList(),
                memoryInfo = "",
                bleConnectionState = bleConnectionState.get(),
                processKilled = false
            ))
            trimExitRecords()
            return
        }

        // 内存告警限流：5 分钟内最多写一次
        if (reason == AppExitReason.MEMORY_LEAK) {
            val now = System.currentTimeMillis()
            val last = lastMemoryWarningTimeMs.get()
            if (now - last < MEMORY_WARNING_INTERVAL_MS) {
                Log.d(TAG, "内存告警限流，跳过写入 (last=${now - last}ms ago)")
                return
            }
            // CAS 尝试更新时间戳，成功才允许写入（并发安全）
            if (!lastMemoryWarningTimeMs.compareAndSet(last, now)) {
                Log.d(TAG, "内存告警限流（并发冲突），跳过写入")
                return
            }
        }

        // 去重：5 秒内相同原因 + 相同消息摘要跳过写入
        if (shouldDedup(reason.name, message)) {
            Log.d(TAG, "去重跳过写入: reason=${reason.label}, msg=$message")
            return
        }

        val memoryInfo = collectMemoryInfo()

        val record = ExitRecord(
            reason = reason,
            message = message,
            contextTags = contextTags.toList(),
            memoryInfo = memoryInfo,
            bleConnectionState = bleConnectionState.get(),
            processKilled = processKilled
        )

        exitRecords.add(record)
        trimExitRecords()

        // v6.8: 调试开关 — 关闭时不排队写入文件
        if (DebugConfig.crashExitLog) {
            // 异步排队写入（单线程消费）
            enqueueWrite(record)
        }

        Log.d(TAG, "记录退出事件: reason=${reason.label}, msg=$message")
    }

    /** 去重判断 */
    private fun shouldDedup(reason: String, message: String): Boolean {
        val now = System.currentTimeMillis()
        val lastTime = lastWriteTimeMs.get()
        if (now - lastTime < DEDUP_WINDOW_MS &&
            reason == lastWrittenReason &&
            message.take(120) == lastWrittenMessage?.take(120)
        ) {
            return true
        }
        // 更新去重状态
        lastWrittenReason = reason
        lastWrittenMessage = message
        lastWriteTimeMs.set(now)
        return false
    }

    /** 裁剪内存缓存 */
    private fun trimExitRecords() {
        if (exitRecords.size > 200) {
            exitRecords.removeAt(0)
        }
    }

    // ==================== 文件写入 ====================

    /**
     * 入队写入（非阻塞），首次调用时懒启动写入线程
     */
    private fun enqueueWrite(record: ExitRecord) {
        // 【省电优化】懒启动：仅在首次需要写入时才创建线程
        if (!writerRunning.get()) {
            startWriterThread()
        }
        // 队列满时丢弃最旧的，保证不阻塞
        while (!writeQueue.offer(record)) {
            writeQueue.poll()
        }
    }

    private fun writeRecordToFile(record: ExitRecord) {
        val file = getLogFilePath()
        val writer = FileWriter(file, true)
        try {
            writer.write(record.toFormattedString())
            writer.flush()
        } finally {
            try { writer.close() } catch (_: Exception) {}
        }
    }

    // ==================== 工具方法 ====================

    private fun getStackTraceString(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }

    private fun collectMemoryInfo(): String {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val total = runtime.totalMemory() / (1024 * 1024)
        val max = runtime.maxMemory() / (1024 * 1024)
        val ratio = if (max > 0) (used * 100 / max).toInt() else 0

        val debugInfo = StringBuilder()
        debugInfo.appendLine("Java堆: 已用=${used}MB, 分配=${total}MB, 上限=${max}MB (${ratio}%)")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val memInfo = ActivityManager.MemoryInfo()
            val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.getMemoryInfo(memInfo)
            if (am != null) {
                debugInfo.appendLine(
                    "系统内存: 可用=${memInfo.availMem / (1024 * 1024)}MB, " +
                    "低内存=${memInfo.lowMemory}, 阈值=${memInfo.threshold / (1024 * 1024)}MB"
                )
            }
        }

        return debugInfo.toString().trimEnd()
    }
}
