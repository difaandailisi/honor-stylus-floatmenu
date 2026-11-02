package com.batteryanalyzer.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.batteryanalyzer.MainActivity
import com.batteryanalyzer.R
import com.batteryanalyzer.ble.BleManager
import com.batteryanalyzer.data.BatteryRepository
import com.batteryanalyzer.data.PreferencesManager
import com.batteryanalyzer.model.BatteryChannel
import com.batteryanalyzer.model.BatteryRecord
import com.batteryanalyzer.notification.BatteryNotificationManager
import com.batteryanalyzer.notification.DeviceIconProvider
import com.batteryanalyzer.notification.NotificationIdHelper
import com.batteryanalyzer.crash.CrashExitMonitor
import com.batteryanalyzer.stylus.HonorPenIdentifier
import com.batteryanalyzer.stylus.HonorSystemPenStateReader
import com.batteryanalyzer.stylus.RemarkSyncManager
import com.batteryanalyzer.stylus.SystemPenState
import com.batteryanalyzer.widget.BatteryWidget
import com.batteryanalyzer.widget.BatteryWidgetMulti
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeout
import kotlin.math.abs

/** 本机 BLE 读数与荣耀系统读数的分歧告警阈值（百分比）—— 文件级常量，供 Snapshot 与 Service 共用 */
private const val LEVEL_DIVERGENCE_THRESHOLD = 5

/**
 * 后台电量监控服务 —— 【唯一 BLE 数据处理中心】
 *
 * 架构原则：
 * - 本 Service 是唯一 collect bleManager.batteryLevel 的地方
 * - 其他组件（悬浮窗、Widget、ViewModel）通过 [batterySnapshot] 获取数据
 * - DB 写入经内存缓冲区批量落盘，减少磁盘 I/O
 * - Widget 更新限流，避免高频重建
 */
class BatteryMonitorService : Service() {

    /** 供其他组件订阅的电量快照 */
    data class BatterySnapshot(
        val level: Int,
        val isCharging: Boolean,
        val deviceName: String,
        val deviceId: Long = -1,
        val timestamp: Long = System.currentTimeMillis(),
        // ===== 逆向增强：荣耀系统态读数（Settings.Global honor_bt_pencil_*） =====
        /** 系统上报电量，-1 = 系统未提供/握手失败 */
        val systemLevel: Int = -1,
        /** 系统上报充电位，null = 系统未提供 */
        val systemCharging: Boolean? = null,
        /** 系统上报型号（honor_bt_pencil_model_number） */
        val systemModel: String? = null,
        /** 系统态是否可读（非荣耀设备/无权限时为 false） */
        val systemAvailable: Boolean = false,
        /** 本机 BLE 解析值与系统读数的绝对差值；任一方无效时为 null */
        val levelDivergence: Int? = null
    ) {
        /** 是否存在需要提示的读数分歧 */
        val hasDivergence: Boolean get() = levelDivergence != null && levelDivergence!! > LEVEL_DIVERGENCE_THRESHOLD
    }

    companion object {
        const val CHANNEL_ID = "battery_monitor_service"

        private const val TAG = "BatteryMonitorService"

        // NOTIFICATION_ID 已迁移至 NotificationIdHelper.monitorServiceId()
        // 旧常量 NOTIFICATION_ID(2001) 已移除

        // ===== 逆向增强：系统态交叉校验 =====
        /** 系统态轮询间隔（系统键为事件驱动、写一次长期有效，30s 足够且开销极低） */
        private const val SYSTEM_STATE_POLL_INTERVAL_MS = 30_000L
        /** 共享状态 —— FloatingBatteryService 等组件通过此 Flow 获取最新电量 */
        private val _batterySnapshot = MutableStateFlow(BatterySnapshot(-1, false, ""))
        val batterySnapshot: StateFlow<BatterySnapshot> = _batterySnapshot.asStateFlow()

        /** 共享状态 —— 荣耀系统态读数（供 UI 展示「系统读数 vs 本机读数」） */
        private val _systemPenState = MutableStateFlow(SystemPenState())
        val systemPenState: StateFlow<SystemPenState> = _systemPenState.asStateFlow()
    }

    private lateinit var repository: BatteryRepository
    private lateinit var bleManager: BleManager
    private lateinit var notificationManager: BatteryNotificationManager
    private lateinit var preferencesManager: PreferencesManager

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var deviceId: Long = -1
    private var deviceName: String = ""
    private var deviceCapacity: Int = 80
    private var lastBatteryLevel: Int = -1
    private var lastMultiBatteryLevels: Map<BatteryChannel, Int> = emptyMap()
    private var wasCharging: Boolean? = null
    private var savedDeviceMac: String? = null

    /** 逆向增强：本次连接是否已完成设备画像补全（避免重复写库） */
    @Volatile
    private var profileEnriched = false

    /** 监控协程作用域 —— 每次切换设备前 cancel，避免旧设备的 combine 监听协程残留并发、重复写库拖慢切换 */
    private var monitorScope: CoroutineScope? = null

    // ===== 省电优化：内存缓冲批量写入 =====
    private val writeBuffer = mutableListOf<BatteryRecord>()
    private var flushJob: Job? = null
    private val FLUSH_INTERVAL_MS = 60_000L       // 最长缓存 60 秒
    private val FLUSH_BATCH_SIZE = 20              // 或攒够 20 条

    // ===== 省电优化：Widget 更新节流 =====
    private var lastWidgetUpdateTime = 0L
    private val WIDGET_UPDATE_INTERVAL_MS = 60_000L  // 最多每分钟更新一次 Widget

    // ===== 省电优化：通知更新节流 =====
    private var lastNotificationUpdateTime = 0L
    private val NOTIFICATION_UPDATE_INTERVAL_MS = 15_000L  // 最多每 15 秒更新一次前台通知

    // ===== 省电优化：充电事件去重 =====
    private var lastChargingEventTime = 0L
    private val CHARGING_EVENT_DEDUP_MS = 10_000L  // 10 秒内充电状态事件去重

    /** 备注更新广播接收器 */
    private var remarkReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        // 初始化通知图标版本管理：检测版本变化 → 清除旧通知 → 后续通知使用新 ID
        NotificationIdHelper.init(this)
        repository = BatteryRepository(this)
        bleManager = BleManager.getInstance(this)
        notificationManager = BatteryNotificationManager(this)
        preferencesManager = PreferencesManager(this)

        // 监听设备备注变更（全局同步：更新通知 + 快照）
        remarkReceiver = RemarkSyncManager.register(this) { deviceId, _, displayName ->
            if (deviceId == this.deviceId && displayName != this.deviceName) {
                this.deviceName = displayName
                updateBatterySnapshot(lastBatteryLevel, wasCharging ?: false)
                // 刷新通知显示
                notificationManager.refreshSettings(deviceId)
                serviceScope.launch { updateNotification(lastBatteryLevel, wasCharging ?: false) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NotificationIdHelper.monitorServiceId(), createNotification())

        serviceScope.launch {
            // 切换设备时取消上一台设备的监控协程，避免多组监听并发、重复写库拖慢切换
            monitorScope?.cancel()
            monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            // 优先从 Intent 获取参数
            savedDeviceMac = intent?.getStringExtra("device_mac")
            val intentDeviceId = intent?.getLongExtra("device_id", -1) ?: -1

            // 如果 Intent 没有，从数据库恢复
            if (savedDeviceMac.isNullOrBlank()) {
                val device = repository.getConnectedDevice()
                if (device != null) {
                    savedDeviceMac = device.macAddress
                }
            }

            val device = if (intentDeviceId > 0) repository.getDeviceById(intentDeviceId)
                else repository.getConnectedDevice()

            if (device == null && savedDeviceMac.isNullOrBlank()) {
                stopSelf()
                return@launch
            }

            deviceId = device?.id ?: intentDeviceId
            // 使用 displayName（备注名优先，无备注则使用原始名称）
            deviceName = device?.displayName ?: (intent?.getStringExtra("device_name") ?: "未知设备")
            deviceCapacity = device?.batteryCapacityMah ?: 80
            // 设备可能已切换 —— 重置画像补全标记，允许对新设备重新补全
            profileEnriched = false
            _systemPenState.value = SystemPenState()

            // v7: 实时监听设备容量变化（用户手动修改容量后，服务内 mAh 计算同步生效）
            if (deviceId > 0) {
                monitorScope!!.launch {
                    repository.observeDevice(deviceId).collect { updated ->
                        if (updated != null) deviceCapacity = updated.batteryCapacityMah
                    }
                }
            }

            // 用 MAC 地址尝试重连 GATT（如果 BleManager 当前未连接）
            if (bleManager.connectionState.value != com.batteryanalyzer.ble.ConnectionState.CONNECTED
                && !savedDeviceMac.isNullOrBlank()
            ) {
                // 后台服务使用 serviceScope 重连，确保超时保护等协程正常工作
                serviceScope.launch {
                    try {
                        bleManager.connectByAddress(savedDeviceMac!!, this)
                    } catch (e: Exception) {
                        // 重连失败不崩溃
                    }
                }
            }

            // 刷新通知设置（加载该设备阈值）
            notificationManager.refreshSettings(deviceId)

            startMonitoring()
        }

        return START_NOT_STICKY  // 避免 stopSelf() 后系统自动重启死循环
    }

    private suspend fun startMonitoring() {
        // 恢复上次的电量状态
        lastBatteryLevel = bleManager.batteryLevel.value
        lastMultiBatteryLevels = bleManager.multiBatteryLevels.value
        wasCharging = bleManager.isCharging.value
        notificationManager.refreshSettings(deviceId)

        // 更新共享快照初始值
        updateBatterySnapshot(lastBatteryLevel, wasCharging ?: false)

        // 【省电优化 #15】合并两个 DataStore Flow 监听为一个 combine
        if (deviceId > 0) {
            monitorScope!!.launch {
                combine(
                    preferencesManager.getDeviceChargeAlertLevel(deviceId),
                    preferencesManager.getDeviceLowBatteryAlertLevel(deviceId)
                ) { _, _ -> Unit }.distinctUntilChanged().collect {
                    notificationManager.refreshSettings(deviceId)
                }
            }
            // v6.2: 单独监听阈值提醒开关状态变化
            monitorScope!!.launch {
                preferencesManager.getDeviceThresholdReminderEnabled(deviceId).distinctUntilChanged().collect {
                    notificationManager.refreshSettings(deviceId)
                }
            }
        }

        // 启动定时 flush 协程（归监控作用域，切换设备时一并取消）
        flushJob = monitorScope!!.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushWriteBuffer()
            }
        }

        // ===== 逆向增强：荣耀系统态交叉校验轮询 =====
        // 系统键为"事件驱动、写一次长期有效"，30s 轮询足够且开销极低
        monitorScope!!.launch {
            while (isActive) {
                try {
                    crossCheckSystemState()
                } catch (t: Throwable) {
                    Log.d(TAG, "系统态交叉校验异常(已忽略): ${t.message}")
                }
                delay(SYSTEM_STATE_POLL_INTERVAL_MS)
            }
        }

        // 立即触发一次检查
        val initLevel = lastBatteryLevel
        val initCharging = bleManager.isCharging.value
        if (initLevel in 0..100) {
            notificationManager.sendBatteryAlert(deviceName, initLevel, initCharging, deviceId)
            updateNotification(initLevel, initCharging)
        }

        // ===== 【省电优化 #8, #13】合并 batteryLevel + isCharging 为单一 combine =====
        // 原来两个独立的 collect 协程（batteryLevel + isCharging）合并为单一 combine，
        // 避免同一事件被两次处理导致重复 DB 写入和重复通知。
        monitorScope!!.launch {
            combine(
                bleManager.batteryLevel,
                bleManager.isCharging
            ) { level, nowCharging -> Pair(level, nowCharging) }
                .collect { (level, nowCharging) ->
                    CrashExitMonitor.enterContext("BATTERY_QUERY:Monitor")
                    try {
                        if (level !in 0..100) return@collect

                        // 初始化 wasCharging
                        if (wasCharging == null) {
                            wasCharging = nowCharging
                            if (level != lastBatteryLevel) {
                                val elapsedMah = if (lastBatteryLevel >= 0)
                                    (level - lastBatteryLevel) / 100f * deviceCapacity else 0f
                                bufferRecord(BatteryRecord(
                                    deviceId = deviceId, batteryLevel = level,
                                    isCharging = nowCharging, estimatedMah = elapsedMah,
                                    channel = BatteryChannel.SINGLE
                                ))
                                lastBatteryLevel = level
                            }
                            return@collect
                        }

                        val levelChanged = level != lastBatteryLevel
                        val chargingChanged = wasCharging != nowCharging

                        if (!levelChanged && !chargingChanged) return@collect

                        // 1. 更新共享快照（供悬浮窗读取）
                        updateBatterySnapshot(level, nowCharging)

                        // 2. 数据库写入 — 统一入口，避免重复记录 (#13)
                        if (levelChanged) {
                            val elapsedMah = if (lastBatteryLevel >= 0)
                                (level - lastBatteryLevel) / 100f * deviceCapacity else 0f
                            bufferRecord(BatteryRecord(
                                deviceId = deviceId, batteryLevel = level,
                                isCharging = nowCharging, estimatedMah = elapsedMah,
                                channel = BatteryChannel.SINGLE
                            ))
                        } else if (chargingChanged) {
                            // 仅充电状态变化（如荣耀手写笔 0xFE42 协议独立推送）
                            bufferRecord(BatteryRecord(
                                deviceId = deviceId, batteryLevel = level,
                                isCharging = nowCharging, estimatedMah = 0f,
                                channel = BatteryChannel.SINGLE
                            ))
                        }

                        // 3. 通知提醒
                        notificationManager.sendBatteryAlert(deviceName, level, nowCharging, deviceId)
                        if (chargingChanged) {
                            val now = System.currentTimeMillis()
                            if (now - lastChargingEventTime > CHARGING_EVENT_DEDUP_MS) {
                                lastChargingEventTime = now
                                notificationManager.sendChargingStatus(deviceName, level, nowCharging)
                            }
                        }

                        // 4. 更新前台通知（节流: 最多 15 秒一次）
                        val now = System.currentTimeMillis()
                        if (now - lastNotificationUpdateTime > NOTIFICATION_UPDATE_INTERVAL_MS) {
                            lastNotificationUpdateTime = now
                            updateNotification(level, nowCharging)
                        }

                        // 5. Widget 节流更新
                        updateWidgetThrottled()

                        if (levelChanged) lastBatteryLevel = level
                        wasCharging = nowCharging
                    } finally {
                        CrashExitMonitor.exitContext("BATTERY_QUERY:Monitor")
                    }
                }
        }

        // 多通道监听（独立于主电量+充电状态的 combine）
        monitorScope!!.launch {
            bleManager.multiBatteryLevels.collect { multiLevels ->
                if (multiLevels.isEmpty()) return@collect
                multiLevels.forEach { (channel, level) ->
                    if (level in 0..100) {
                        val previousLevel = lastMultiBatteryLevels[channel]
                        if (previousLevel == null || previousLevel != level) {
                            val isCharging = bleManager.isCharging.value
                            val elapsedMah = if (previousLevel != null)
                                (level - previousLevel) / 100f * deviceCapacity else 0f
                            bufferRecord(BatteryRecord(
                                deviceId = deviceId, batteryLevel = level,
                                isCharging = isCharging, estimatedMah = elapsedMah,
                                channel = channel
                            ))
                        }
                    }
                }
                lastMultiBatteryLevels = multiLevels
            }
        }
    }

    // ===== 省电优化：内存缓冲批量写入 =====

    private fun bufferRecord(record: BatteryRecord) {
        synchronized(writeBuffer) { writeBuffer.add(record) }
        // 【修复】缓冲区达到阈值时立即 flush，但不取消定时 flush 循环，
        // 避免数据在 Service 被系统杀死时丢失。
        if (writeBuffer.size >= FLUSH_BATCH_SIZE) {
            serviceScope.launch { flushWriteBuffer() }
        }
    }

    private suspend fun flushWriteBuffer() {
        val batch: List<BatteryRecord>
        synchronized(writeBuffer) {
            if (writeBuffer.isEmpty()) return
            batch = writeBuffer.toList()
            writeBuffer.clear()
        }
        repository.insertRecords(batch)
    }

    // ===== 省电优化：共享快照 =====

    private fun updateBatterySnapshot(level: Int, isCharging: Boolean) {
        val sys = _systemPenState.value
        val divergence = if (level in 0..100 && sys.hasValidBattery) {
            abs(level - sys.batteryLevel)
        } else null
        _batterySnapshot.value = BatterySnapshot(
            level = level,
            isCharging = isCharging,
            deviceName = deviceName,
            deviceId = deviceId,
            systemLevel = sys.batteryLevel,
            systemCharging = if (sys.hasValidCharging) sys.isCharging else null,
            systemModel = sys.modelNumber,
            systemAvailable = sys.available,
            levelDivergence = divergence
        )
    }

    // ===== 逆向增强：荣耀系统态交叉校验 =====

    /**
     * 读取荣耀系统态（Settings.Global `honor_bt_pencil_*`）并与自研 BLE 解析值交叉校验。
     *
     * 策略（绝不无条件覆盖本机值）：
     * - 本机 BLE 电量无效（未取到/越界）而系统值有效 → 用系统值**兜底**，避免"无电量可用"；
     * - 两者都有效但差异 > [LEVEL_DIVERGENCE_THRESHOLD] → 记录 divergence 供 UI 提示存疑；
     * - 系统维护的是另一支笔（MAC 不匹配）→ 直接放弃本次校验。
     */
    private suspend fun crossCheckSystemState() {
        val state = HonorSystemPenStateReader.read(this)
        if (!state.available || !HonorSystemPenStateReader.matchesMac(state, savedDeviceMac)) {
            // 非荣耀设备 / 无权限 / 系统维护的是另一支笔 → 清空，不参与校验
            if (_systemPenState.value.available) {
                _systemPenState.value = SystemPenState()
            }
            return
        }
        _systemPenState.value = state

        // 1) 设备画像补全（型号 / 手写笔标记）
        enrichDeviceProfile(state)

        val localLevel = lastBatteryLevel
        val sysLevel = state.batteryLevel

        // 2) 本机无有效电量 → 系统值兜底
        if (localLevel !in 0..100 && state.hasValidBattery) {
            Log.d(TAG, "本机 BLE 电量无效($localLevel)，采用荣耀系统读数兜底: $sysLevel%")
            lastBatteryLevel = sysLevel
            val charging = wasCharging ?: state.isCharging
            updateBatterySnapshot(sysLevel, charging)
            notificationManager.sendBatteryAlert(deviceName, sysLevel, charging, deviceId)
            return
        }

        // 3) 两者都有效 → 计算分歧
        if (localLevel in 0..100 && state.hasValidBattery) {
            val divergence = abs(localLevel - sysLevel)
            if (divergence > LEVEL_DIVERGENCE_THRESHOLD) {
                Log.w(TAG, "电量与系统读数分歧 ${divergence}%：本机 BLE=$localLevel%，系统=$sysLevel%")
            }
        }
        updateBatterySnapshot(lastBatteryLevel, wasCharging ?: false)
    }

    /**
     * 【逆向增强】补全设备画像：型号 + 手写笔标记。
     *
     * 数据源优先级：
     *  1. 硬件 VID/PID 识别（[HonorPenIdentifier]，最可靠，含 CD52/MP3 等具体型号）
     *  2. 系统 `honor_bt_pencil_model_number`
     * 仅在字段缺失时写入，不覆盖用户已确认的数据。
     */
    private suspend fun enrichDeviceProfile(state: SystemPenState) {
        if (deviceId <= 0 || profileEnriched) return
        val device = repository.getDeviceById(deviceId) ?: return

        val identity = savedDeviceMac?.let { HonorPenIdentifier.resolve(this, it) }
        val resolvedModel = identity?.modelName
            ?: state.modelNumber?.takeIf { it.isNotBlank() }
        val shouldMarkPen = identity?.isHonorPen == true || device.isHandwritingPen

        val modelChanged = !resolvedModel.isNullOrBlank() && device.modelName.isBlank()
        val penFlagChanged = shouldMarkPen && !device.isHandwritingPen

        if (modelChanged || penFlagChanged) {
            repository.updateDeviceProfile(
                deviceId = deviceId,
                modelName = if (modelChanged) resolvedModel!! else device.modelName,
                isPen = shouldMarkPen
            )
            Log.d(TAG, "设备画像已补全: model=${if (modelChanged) resolvedModel else device.modelName}, isPen=$shouldMarkPen")
        }
        profileEnriched = true
    }

    // ===== 省电优化：Widget 节流刷新 =====

    private fun updateWidgetThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastWidgetUpdateTime < WIDGET_UPDATE_INTERVAL_MS) return
        lastWidgetUpdateTime = now
        updateWidget()
    }

    /**
     * 触发所有桌面小组件刷新（单设备 + 多设备）
     * 【省电优化】仅在小组件实例存在时才触发更新，避免无效 IO
     */
    private fun updateWidget() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val glanceManager = GlanceAppWidgetManager(this@BatteryMonitorService)
                // 单设备 Widget
                val singleIds = glanceManager.getGlanceIds(BatteryWidget::class.java)
                if (singleIds.isNotEmpty()) {
                    val singleWidget = BatteryWidget()
                    singleIds.forEach { glanceId ->
                        singleWidget.update(this@BatteryMonitorService, glanceId)
                    }
                }
                // 多设备 Widget — 仅在实例存在时更新
                val multiIds = glanceManager.getGlanceIds(BatteryWidgetMulti::class.java)
                if (multiIds.isNotEmpty()) {
                    val multiWidget = BatteryWidgetMulti()
                    multiIds.forEach { glanceId ->
                        multiWidget.update(this@BatteryMonitorService, glanceId)
                    }
                }
            } catch (_: Exception) { }
        }
    }

    /**
     * 更新前台通知为当前电量状态
     */
    private suspend fun updateNotification(level: Int, isCharging: Boolean) {
        // 优先使用该设备的阈值到达状态文案
        val thresholdStatus = notificationManager.getThresholdStatusText(level, isCharging, deviceId)
        val statusText = thresholdStatus ?: when {
            isCharging -> "充电中 - $level%"
            level <= 10 -> "电量严重不足 - $level%"
            level <= 40 -> "电量偏低 - $level%"
            else -> "电量充足 - $level%"
        }
        val title = if (thresholdStatus != null) "操作提醒" else "蓝牙电量监控"
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        withContext(Dispatchers.Main) {
            val largeIcon = DeviceIconProvider.getDeviceIconBitmap(this@BatteryMonitorService, deviceName)
                ?: DeviceIconProvider.getDefaultIconBitmap(this@BatteryMonitorService)
            val notification = NotificationCompat.Builder(this@BatteryMonitorService, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(statusText)
                .setSmallIcon(com.batteryanalyzer.R.drawable.ic_stat_battery)
                .setLargeIcon(largeIcon)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(if (thresholdStatus != null) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
                .build()
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NotificationIdHelper.monitorServiceId(), notification)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channel = NotificationChannel(
            CHANNEL_ID,
            "后台监控",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "手写笔电量后台监控"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val largeIcon = DeviceIconProvider.getDefaultIconBitmap(this)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("蓝牙电量监控")
            .setContentText("正在监控设备电量...")
            .setSmallIcon(com.batteryanalyzer.R.drawable.ic_stat_battery)
            .setLargeIcon(largeIcon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitorScope?.cancel()
        RemarkSyncManager.unregister(this, remarkReceiver)
        remarkReceiver = null
        // 异步 flush：不阻塞 onDestroy()，使用独立线程保证落盘
        Thread {
            try {
                kotlinx.coroutines.runBlocking {
                    withTimeout(2000) { flushWriteBuffer() }
                }
            } catch (_: Exception) {
                // 静默忽略 flush 失败
            } finally {
                serviceScope.cancel()
            }
        }.start()
        super.onDestroy()
    }
}
