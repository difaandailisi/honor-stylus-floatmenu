package com.batteryanalyzer.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.batteryanalyzer.MainActivity
import com.batteryanalyzer.data.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 通知管理器 - 电量提醒、充放电通知
 * 支持每设备独立提醒阈值 (v3.0)
 *
 * 注意：调用方应在不再需要时调用 destroy() 释放协程资源
 */
class BatteryNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_BATTERY = "battery_alert"
        const val CHANNEL_CHARGING = "charging_status"

        // NOTIFICATION_ID 已迁移至 NotificationIdHelper（支持图标版本动态偏移）
        // 旧常量 NOTIFICATION_ID_BATTERY(1001) / NOTIFICATION_ID_CHARGING(1002) 已移除

        private const val DEFAULT_CHARGE_ALERT = 85
        private const val DEFAULT_LOW_BATTERY_ALERT = 20
    }

    private val preferencesManager = PreferencesManager(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 每设备缓存阈值: deviceId -> (chargeAlert, lowBatteryAlert)
    // ConcurrentHashMap 保证并发读写安全，无需额外 synchronized
    private val deviceThresholds = ConcurrentHashMap<Long, Pair<Int, Int>>()

    // 每设备阈值提醒开关缓存: deviceId -> enabled
    private val deviceReminderEnabled = ConcurrentHashMap<Long, Boolean>()

    // 当前活跃设备 ID（最近一次 sendBatteryAlert 所使用的设备）
    @Volatile
    private var activeDeviceId: Long = -1

    // 通知节流：防止同一类型提醒频繁推送
    private var lastAlertType: String? = null
    private var lastAlertTime: Long = 0
    private val ALERT_COOLDOWN_MS = 60 * 1000L // 同一类型提醒冷却 1 分钟

    // 缓存最近一次设备状态，用于阈值变更后重新评估
    @Volatile
    private var lastKnownDeviceName: String? = null
    @Volatile
    private var lastKnownLevel: Int = -1
    @Volatile
    private var lastKnownCharging: Boolean = false

    // 阈值穿越检测：记录上一次触发提醒的电量值
    @Volatile
    private var lastAlertedLevel: Int = -1

    init {
        createNotificationChannels()
    }

    /**
     * 刷新指定设备的阈值缓存，并在阈值变更后重新评估是否应触发通知
     */
    fun refreshSettings(deviceId: Long) {
        if (deviceId <= 0) return
        scope.launch {
            val oldPair = deviceThresholds[deviceId]
            val oldCharge = oldPair?.first ?: DEFAULT_CHARGE_ALERT
            val oldLow = oldPair?.second ?: DEFAULT_LOW_BATTERY_ALERT

            val newCharge = preferencesManager.getDeviceChargeAlertLevel(deviceId).first()
            val newLow = preferencesManager.getDeviceLowBatteryAlertLevel(deviceId).first()
            deviceThresholds[deviceId] = newCharge to newLow

            // v6.2: 同步加载阈值提醒开关状态
            val newEnabled = preferencesManager.getDeviceThresholdReminderEnabled(deviceId).first()
            val oldEnabled = deviceReminderEnabled[deviceId] ?: false
            deviceReminderEnabled[deviceId] = newEnabled

            // 阈值变更且该设备正在活跃监控中 → 重新评估
            val thresholdChanged = (newCharge != oldCharge) || (newLow != oldLow)
            if (thresholdChanged && activeDeviceId == deviceId
                && lastKnownLevel in 0..100 && lastKnownDeviceName != null
            ) {
                lastAlertType = null
                lastAlertTime = 0
                lastAlertedLevel = -1
                sendBatteryAlert(lastKnownDeviceName!!, lastKnownLevel, lastKnownCharging)
            }

            // v6.2: 开关状态变更 → 重新评估（开关从关到开时立即触发检查）
            if (newEnabled && !oldEnabled && activeDeviceId == deviceId
                && lastKnownLevel in 0..100 && lastKnownDeviceName != null
            ) {
                lastAlertType = null
                lastAlertTime = 0
                lastAlertedLevel = -1
                sendBatteryAlert(lastKnownDeviceName!!, lastKnownLevel, lastKnownCharging)
            }
        }
    }

    /**
     * v6.2: 检查指定设备的阈值提醒是否已启用
     * @return true=提醒已启用，false=提醒已关闭
     */
    suspend fun isReminderEnabled(deviceId: Long): Boolean {
        deviceReminderEnabled[deviceId]?.let { return it }
        val enabled = preferencesManager.getDeviceThresholdReminderEnabled(deviceId).first()
        deviceReminderEnabled[deviceId] = enabled
        return enabled
    }

    /**
     * 获取指定设备缓存的阈值，未加载则从持久化存储异步加载
     */
    private suspend fun getDeviceThresholds(deviceId: Long): Pair<Int, Int> {
        deviceThresholds[deviceId]?.let { return it }

        // 异步加载（仅在缓存 miss 时发生一次）
        try {
            val charge = withContext(Dispatchers.IO) {
                preferencesManager.getDeviceChargeAlertLevel(deviceId).first()
            }
            val low = withContext(Dispatchers.IO) {
                preferencesManager.getDeviceLowBatteryAlertLevel(deviceId).first()
            }
            val pair = charge to low
            deviceThresholds[deviceId] = pair
            return pair
        } catch (_: Exception) {
            return DEFAULT_CHARGE_ALERT to DEFAULT_LOW_BATTERY_ALERT
        }
    }

    private fun createNotificationChannels() {
        val batteryChannel = NotificationChannel(
            CHANNEL_BATTERY,
            "电量提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "电量高/低提醒通知"
        }

        val chargingChannel = NotificationChannel(
            CHANNEL_CHARGING,
            "充电状态",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "充电状态变化通知"
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(batteryChannel)
        manager.createNotificationChannel(chargingChannel)
    }

    /**
     * 发送电量提醒 - 使用用户自定义阈值，带节流控制和阈值穿越检测
     * @return 提醒结果类型 + 状态文案，供悬浮窗使用；无提醒时返回 null
     */
    data class AlertResult(
        val alertType: String,
        val thresholdStatusText: String // 供悬浮窗使用的状态文案
    )

    // 内部数据类：when 分支返回的提醒负载
    private data class AlertPayload(
        val alertType: String,
        val title: String,
        val message: String,
        val thresholdStatusText: String
    )

    suspend fun sendBatteryAlert(deviceName: String, level: Int, isCharging: Boolean, deviceId: Long = activeDeviceId): AlertResult? {
        // 缓存最新状态，供阈值变更后重新评估
        lastKnownDeviceName = deviceName
        lastKnownLevel = level
        lastKnownCharging = isCharging
        if (deviceId > 0) activeDeviceId = deviceId

        // v6.2: 阈值提醒开关未启用 → 不发通知、不返回阈值状态
        val reminderEnabled = try { isReminderEnabled(deviceId) } catch (_: Exception) { false }
        if (!reminderEnabled) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return null
            }
        }

        val (chargeAlert, lowAlert) = getDeviceThresholds(deviceId)
        val now = System.currentTimeMillis()

        // ===== 阈值穿越检测 =====
        // 仅当电量穿越阈值时才视为"已到达"，区分"持续处于阈值区域"和"首次穿越"
        val prevLevel = lastAlertedLevel

        val (alertType, title, message, thresholdStatusText) = when {
            // 充满电 - 精确检测：上次未充满，本次充满
            level >= 100 && isCharging && prevLevel < 100 -> {
                AlertPayload("full", "充电完成",
                    "$deviceName 电量已充满至 100%，请断开充电", "已充满 100%")
            }
            // 充电穿越阈值：上次低于充电阈值，本次达到或超过
            level >= chargeAlert && isCharging && prevLevel in 0..<(chargeAlert.coerceAtMost(100)) -> {
                AlertPayload("charge_alert", "已到达充电阈值",
                    "$deviceName 电量已达 $level%（穿越充电阈值 $chargeAlert%），请断开充电",
                    "已到达充电阈值 $chargeAlert%")
            }
            // 持续处于充电阈值之上（非首次穿越），作为静默状态不推送系统通知但仍更新悬浮窗
            level >= chargeAlert && isCharging && prevLevel >= chargeAlert -> {
                // 不发送系统通知（已在阈值之上），但上报状态给悬浮窗
                return AlertResult("charge_active", "已到达充电阈值 $chargeAlert%")
            }
            // 充电穿越阈值（首次检测，prevLevel < 0 时）
            level >= chargeAlert && isCharging && prevLevel < 0 -> {
                AlertPayload("charge_alert", "已到达充电阈值",
                    "$deviceName 当前电量 $level%，已达到设定充电阈值 $chargeAlert%，请断开充电",
                    "已到达充电阈值 $chargeAlert%")
            }
            // 极低电量 (≤5%) - 精确穿越检测
            level <= 5 && !isCharging && prevLevel > 5 -> {
                AlertPayload("critical", "电量严重不足",
                    "$deviceName 电量已降至 $level%（穿越耗电阈值 5%），请充电",
                    "已到达耗电阈值 5%")
            }
            // 极低电量 - 首次检测
            level <= 5 && !isCharging && prevLevel < 0 -> {
                AlertPayload("critical", "电量严重不足",
                    "$deviceName 当前电量 $level%，已达到设定耗电阈值 5%，请充电",
                    "已到达耗电阈值 5%")
            }
            // 穿越低电量阈值：上次高于阈值，本次降到或低于
            level <= lowAlert && !isCharging && prevLevel > lowAlert -> {
                AlertPayload("low_alert", "已到达耗电阈值",
                    "$deviceName 电量已降至 $level%（穿越耗电阈值 $lowAlert%），请充电",
                    "已到达耗电阈值 $lowAlert%")
            }
            // 持续处于低电量阈值之下（非首次穿越）
            level <= lowAlert && !isCharging && prevLevel in 0..lowAlert -> {
                return AlertResult("low_active", "已到达耗电阈值 $lowAlert%")
            }
            // 低电量首次检测
            level <= lowAlert && !isCharging && prevLevel < 0 -> {
                AlertPayload("low_alert", "已到达耗电阈值",
                    "$deviceName 当前电量 $level%，已达到设定耗电阈值 $lowAlert%，请充电",
                    "已到达耗电阈值 $lowAlert%")
            }
            else -> return null
        }

        // 记录本次触发的电量值，供下次穿越检测
        lastAlertedLevel = level

        // ===== 节流控制 =====
        // 极低电量(≤5%)和充满电每次变化都推送
        val isCritical = (alertType == "critical" || alertType == "full")

        if (!isCritical) {
            // 同类型提醒：冷却期内不再发送
            if (alertType == lastAlertType && now - lastAlertTime < ALERT_COOLDOWN_MS) {
                // 冷却中，但仍返回状态供悬浮窗使用
                return AlertResult(alertType, thresholdStatusText)
            }
        }

        lastAlertType = alertType
        lastAlertTime = now

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        withContext(Dispatchers.Main) {
            val largeIcon = DeviceIconProvider.getDeviceIconBitmap(context, deviceName)
                ?: DeviceIconProvider.getDefaultIconBitmap(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_BATTERY)
                .setSmallIcon(com.batteryanalyzer.R.drawable.ic_stat_battery)
                .setLargeIcon(largeIcon)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            val manager = context.getSystemService(NotificationManager::class.java)
            manager.notify(NotificationIdHelper.batteryAlertId(), notification)
        }

        return AlertResult(alertType, thresholdStatusText)
    }

    /**
     * 发送充电状态变化通知
     */
    suspend fun sendChargingStatus(deviceName: String, level: Int, isCharging: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val title = if (isCharging) "开始充电" else "停止充电"
        val message = "$deviceName 当前电量 $level%"

        withContext(Dispatchers.Main) {
            val largeIcon = DeviceIconProvider.getDeviceIconBitmap(context, deviceName)
                ?: DeviceIconProvider.getDefaultIconBitmap(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_CHARGING)
                .setSmallIcon(com.batteryanalyzer.R.drawable.ic_stat_battery)
                .setLargeIcon(largeIcon)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            val manager = context.getSystemService(NotificationManager::class.java)
            manager.notify(NotificationIdHelper.chargingStatusId(), notification)
        }
    }

    /**
     * 获取当前电量的阈值到达状态文案，供悬浮窗使用。
     * @param deviceId 设备 ID，用于读取该设备的阈值
     * @return 阈值状态文案；若未到达任何阈值则返回 null
     */
    suspend fun getThresholdStatusText(level: Int, isCharging: Boolean, deviceId: Long = activeDeviceId): String? {
        if (level !in 0..100) return null
        // v6.2: 阈值提醒未启用 → 不显示阈值状态
        val reminderEnabled = try { isReminderEnabled(deviceId) } catch (_: Exception) { false }
        if (!reminderEnabled) return null
        val (chargeAlert, lowAlert) = getDeviceThresholds(deviceId)
        return when {
            level >= 100 && isCharging -> "已充满 100%"
            level >= chargeAlert && isCharging -> "已到达充电阈值 $chargeAlert%"
            level <= 5 && !isCharging -> "已到达耗电阈值 5%"
            level <= lowAlert && !isCharging -> "已到达耗电阈值 $lowAlert%"
            else -> null
        }
    }

    /**
     * 释放协程资源，防止内存泄漏。
     * 应在不再使用此管理器时调用。
     */
    fun destroy() {
        scope.cancel()
    }
}
