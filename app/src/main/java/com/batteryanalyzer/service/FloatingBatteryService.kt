package com.batteryanalyzer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.batteryanalyzer.MainActivity
import com.batteryanalyzer.R
import com.batteryanalyzer.ble.BleManager
import com.batteryanalyzer.data.BatteryRepository
import com.batteryanalyzer.data.PreferencesManager
import com.batteryanalyzer.notification.BatteryNotificationManager
import com.batteryanalyzer.notification.DeviceIconProvider
import com.batteryanalyzer.notification.NotificationIdHelper
import com.batteryanalyzer.stylus.RemarkSyncManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import kotlin.math.abs

/**
 * 悬浮窗电量显示服务
 * 支持自由拖动交互（已移除贴边吸附隐藏逻辑）
 * 适配 13.3 英寸及以上大尺寸平板
 */
class FloatingBatteryService : Service() {

    companion object {
        private const val CHANNEL_ID = "floating_battery"
        // NOTIFICATION_ID 已迁移至 NotificationIdHelper.floatingServiceId()
        private const val STATUS_BAR_OFFSET_DP = 60f    // 状态栏偏移(dp)
        private const val MIN_DRAG_MARGIN_DP = 16f      // 拖动最小边距(dp)
    }

    // 窗口层级
    private val layoutType: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private lateinit var windowManager: WindowManager
    private var rootContainer: LinearLayout? = null
    private var batteryText: TextView? = null
    private var deviceText: TextView? = null
    private var statusText: TextView? = null
    private lateinit var bleManager: BleManager
    private lateinit var prefs: PreferencesManager
    private lateinit var repo: BatteryRepository
    private lateinit var notificationManager: BatteryNotificationManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 拖动状态
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialWindowX = 0
    private var initialWindowY = 0
    private var isDragging = false
    private val dragThreshold = 8f        // 拖动最小识别距离(dp)
    private var dragThresholdPx = 0f
    private var windowLayoutParams: WindowManager.LayoutParams? = null

    // 窗口添加状态（防止重复 addView）
    private var isWindowAdded = false

    // 屏幕尺寸
    private var screenWidth = 0
    private var screenHeight = 0
    private var density = 1f
    private var statusBarHeightPx = 0
    private var usableHeight = 0           // 可用高度（排除导航栏/状态栏）
    private var minDragMarginPx = 0        // 拖动最小边界

    // 电量状态缓存（跨线程读写，需 @Volatile）
    @Volatile
    private var lastBatteryLevel = -1
    @Volatile
    private var lastIsCharging = false
    @Volatile
    private var lastSnapshotTime = 0L
    private var currentDeviceName = "未连接"
    private var currentDeviceId: Long = -1

    /** 备注更新广播接收器 */
    private var remarkReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = checkNotNull(getSystemService(WINDOW_SERVICE)) as WindowManager
        bleManager = BleManager.getInstance(this)
        prefs = PreferencesManager(this)
        repo = BatteryRepository(this)
        notificationManager = BatteryNotificationManager(this)

        // 监听设备备注变更（全局同步）
        remarkReceiver = RemarkSyncManager.register(this) { deviceId, _, displayName ->
            if (deviceId == currentDeviceId && displayName != currentDeviceName) {
                currentDeviceName = displayName
                deviceText?.post {
                    deviceText?.text = if (displayName.length > 10) displayName.take(10) + "..." else displayName
                }
            }
        }

        density = resources.displayMetrics.density
        dragThresholdPx = dragThreshold * density
        minDragMarginPx = (MIN_DRAG_MARGIN_DP * density).toInt()

        // 获取实际屏幕尺寸（含系统栏区域，适配大屏平板）
        val metrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.currentWindowMetrics.bounds.apply {
                // Android 11+：使用窗口指标获取完整屏幕区域
            }
            resources.displayMetrics
        } else {
            resources.displayMetrics
        }
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        // 获取状态栏高度
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        statusBarHeightPx = if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
        if (statusBarHeightPx == 0) {
            statusBarHeightPx = (24 * density).toInt()
        }

        // 计算可用高度：屏幕高度减去底部导航栏估算
        // 大屏平板（13.3"+）通常有系统导航栏
        val navBarResId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val navBarHeight = if (navBarResId > 0) resources.getDimensionPixelSize(navBarResId) else 0
        usableHeight = screenHeight - navBarHeight
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canShowOverlay()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NotificationIdHelper.floatingServiceId(), createNotification())
        showFloatingWindow()
        startMonitoring()

        // 【省电优化 #2】悬浮窗是 UI 服务，被系统杀死后不应自动重启。
        // 用户可手动重新开启；BatteryMonitorService 的 START_STICKY 已保证数据不中断。
        return START_NOT_STICKY
    }

    private fun canShowOverlay(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    // ========================= 创建悬浮窗 =========================

    private fun showFloatingWindow() {
        if (isWindowAdded || rootContainer != null) return

        val pad = (10 * density).toInt()
        val statusOffsetPx = (STATUS_BAR_OFFSET_DP * density).toInt()

        // === 内容卡片 (直接使用 LinearLayout 作为根容器) ===
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.argb(230, 30, 30, 30))
                cornerRadius = (12 * density)
            }
        }

        // 设备名称
        deviceText = TextView(this).apply {
            text = "---"
            textSize = 10f
            setTextColor(Color.argb(200, 255, 255, 255))
            gravity = Gravity.CENTER
            maxEms = 12
            setSingleLine(true)
        }
        rootContainer?.addView(deviceText)

        // 电量大字
        batteryText = TextView(this).apply {
            text = "--%"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setOnLongClickListener {
                val prefsMgr = PreferencesManager(this@FloatingBatteryService)
                kotlinx.coroutines.runBlocking {
                    prefsMgr.setFloatingWindowEnabled(false)
                }
                stopSelf()
                true
            }
        }
        rootContainer?.addView(batteryText)

        // 状态文字
        statusText = TextView(this).apply {
            text = "未连接"
            textSize = 9f
            setTextColor(Color.argb(180, 255, 255, 255))
            gravity = Gravity.CENTER
        }
        rootContainer?.addView(statusText)

        // === 设置触摸监听 (自由拖动，无吸附) ===
        rootContainer?.setOnTouchListener { _, event -> handleTouch(event) }

        // === 窗口参数 ===
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - pad - (80 * density).toInt()  // 默认右上角
            y = statusOffsetPx
        }
        windowLayoutParams = params
        windowManager.addView(rootContainer, params)
        isWindowAdded = true
    }

    // ========================= 触摸交互（自由拖动，无吸附）=========================

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                windowLayoutParams?.let {
                    initialWindowX = it.x
                    initialWindowY = it.y
                }
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                val absDx = abs(dx)
                val absDy = abs(dy)

                if (!isDragging && (absDx > dragThresholdPx || absDy > dragThresholdPx)) {
                    isDragging = true
                }

                if (isDragging) {
                    updateWindowPosition(
                        initialWindowX + dx.toInt(),
                        initialWindowY + dy.toInt()
                    )
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                isDragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return false
    }

    /**
     * 更新窗口位置，适配大尺寸平板
     * X 轴边界：视图至少保留 minDragMarginPx 在屏幕内可见
     * Y 轴边界：0 ~ usableHeight 减去视图高度
     */
    private fun updateWindowPosition(x: Int, y: Int) {
        val container = rootContainer ?: return
        windowLayoutParams?.let { params ->
            val viewWidth = container.width
            val viewHeight = container.height

            // 计算合理边界，确保悬浮窗不完全移出屏幕
            val actualWidth = if (viewWidth > 0) viewWidth else (120 * density).toInt()
            val actualHeight = if (viewHeight > 0) viewHeight else (50 * density).toInt()

            // X 轴：至少露出 minDragMarginPx，最多不超出屏幕右边缘
            val maxX = screenWidth - minDragMarginPx
            val minX = -(actualWidth - minDragMarginPx)
            params.x = x.coerceIn(minX, maxX)

            // Y 轴：0 ~ usableHeight - 视图高度，适配大屏平板
            val maxY = (usableHeight - actualHeight).coerceAtLeast(0)
            params.y = y.coerceIn(0, maxY)

            try {
                windowManager.updateViewLayout(container, params)
            } catch (_: Exception) {}
        }
    }

    // ========================= 电量监控 =========================

    private fun startMonitoring() {

        // 主数据源：从 BatteryMonitorService 共享快照获取
        scope.launch {
            BatteryMonitorService.batterySnapshot.collect { snapshot ->
                if (snapshot.level in 0..100) {
                    lastSnapshotTime = System.currentTimeMillis()
                    val name = snapshot.deviceName.ifBlank { currentDeviceName }
                    if (snapshot.deviceId > 0) {
                        if (currentDeviceId <= 0) {
                            // 首次获取到有效设备 ID 时加载该设备阈值
                            notificationManager.refreshSettings(snapshot.deviceId)
                        }
                        currentDeviceId = snapshot.deviceId
                    }
                    if (snapshot.level != lastBatteryLevel || snapshot.isCharging != lastIsCharging) {
                        currentDeviceName = name
                        withContext(Dispatchers.Main) {
                            updateFloatingView(currentDeviceName, snapshot.level, snapshot.isCharging)
                        }
                        lastBatteryLevel = snapshot.level
                        lastIsCharging = snapshot.isCharging
                    }
                }
            }
        }

        // 【省电优化 #7】仅在快照超过 10 分钟无更新时才走 DB 兜底
        // BLE 连接正常时快照每秒更新，快照超时 = BLE 已断开
        scope.launch {
            while (isActive) {
                try {
                    if (System.currentTimeMillis() - lastSnapshotTime > 600_000) {
                        // 快照 10 分钟未更新 → BLE 已断开，DB 兜底
                        val deviceId = prefs.selectedDeviceId.firstOrNull() ?: -1L
                        val device = if (deviceId > 0) repo.getDeviceById(deviceId) else repo.getConnectedDevice()
                        if (device != null) {
                            // 确保该设备的阈值已加载到通知管理器（修复阈值Bug）
                            notificationManager.refreshSettings(device.id)
                            currentDeviceId = device.id

                            val latestRecord = repo.getLatestRecord(device.id)
                            val level = latestRecord?.batteryLevel ?: -1
                            val isCharging = latestRecord?.isCharging ?: false
                            val displayName = device.displayName // 使用备注名
                            if (level != lastBatteryLevel || isCharging != lastIsCharging) {
                                withContext(Dispatchers.Main) {
                                    updateFloatingView(displayName, level, isCharging)
                                }
                                lastBatteryLevel = level
                                lastIsCharging = isCharging
                                currentDeviceName = displayName
                            }
                        } else {
                            withContext(Dispatchers.Main) { updateFloatingView("未连接", -1, false) }
                        }
                    }
                } catch (_: Exception) {}
                delay(600_000)  // 10 分钟轮询间隔，进一步降低 CPU 唤醒频率
            }
        }
    }

    private suspend fun updateFloatingView(deviceName: String, level: Int, isCharging: Boolean) {
        deviceText?.text = if (deviceName.length > 10) deviceName.take(10) + "..." else deviceName

        batteryText?.text = if (level >= 0) "$level%" else "--"
        batteryText?.setTextColor(
            when {
                level < 0 -> Color.argb(255, 158, 158, 158)
                isCharging -> Color.argb(255, 76, 175, 80)
                level <= 10 -> Color.argb(255, 244, 67, 54)
                level <= 40 -> Color.argb(255, 255, 152, 0)
                else -> Color.argb(255, 76, 175, 80)
            }
        )

        val thresholdStatus = notificationManager.getThresholdStatusText(level, isCharging, currentDeviceId)
        statusText?.text = thresholdStatus ?: when {
            level < 0 -> "未连接"
            isCharging -> "⚡ 充电中"
            level <= 10 -> "极低电量"
            level <= 40 -> "电量不足"
            else -> "电量充足"
        }

        statusText?.setTextColor(
            if (thresholdStatus != null)
                Color.argb(255, 255, 167, 38)
            else
                Color.argb(180, 255, 255, 255)
        )
    }

    // ========================= 通知 =========================

    private fun createNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "悬浮窗监控",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "悬浮窗电量显示后台服务"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val largeIcon = DeviceIconProvider.getDefaultIconBitmap(this)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("电量悬浮窗")
            .setContentText("正在监控设备电量")
            .setSmallIcon(com.batteryanalyzer.R.drawable.ic_stat_battery)
            .setLargeIcon(largeIcon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        RemarkSyncManager.unregister(this, remarkReceiver)
        remarkReceiver = null
        isWindowAdded = false
        try {
            rootContainer?.let { windowManager.removeView(it) }
        } catch (_: Exception) {}
        rootContainer = null
        batteryText = null
        deviceText = null
        statusText = null
        windowLayoutParams = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
