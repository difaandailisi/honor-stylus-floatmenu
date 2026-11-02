package com.batteryanalyzer.notification

import android.app.NotificationManager
import android.content.Context

/**
 * 通知 ID 辅助工具 —— 解决荣耀 EMUI/MagicOS 通知图标缓存问题
 *
 * ## 问题
 * 荣耀 SystemUI 按 (packageName, notificationId) 缓存通知图标的解码 Bitmap。
 * 即使 App 更新后更换通知图标资源，同一 notificationId 的通知仍显示旧图标。
 * 卸载/强制停止均无法清除，唯有重启手机才能释放 SystemUI 进程级缓存。
 *
 * ## 方案
 * - 每次更换通知图标时，递增 [ICON_VERSION]。
 * - 通知 ID = 基础 ID + ICON_VERSION * 10000。
 * - 版本号变 → 通知 ID 变 → SystemUI 视为"全新通知" → 重新加载图标。
 *
 * ## 使用
 * 1. 在 App 启动时调用 [init]（由 BatteryMonitorService 负责）
 * 2. 各处使用对应的 Id 获取函数替代硬编码通知 ID
 * 3. 更换图标资源后，递增 ICON_VERSION 即可
 */
object NotificationIdHelper {

    private const val PREFS_NAME = "notification_icon_cache"
    private const val KEY_ICON_VERSION = "icon_version"
    private const val OFFSET_MULTIPLIER = 10000

    /**
     * 当前图标版本号。每次更换通知图标资源时递增此值。
     *
     * v1: ic_stat_battery.xml（白色电池+闪电矢量图标）
     */
    const val ICON_VERSION = 1

    @Volatile
    private var initialized = false

    /**
     * 初始化：清除上版本通知缓存 + 更新本版本标记。
     * 应在 App 最早启动点调用（BatteryMonitorService.onCreate），
     * 重复调用无副作用。
     */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val oldVersion = prefs.getInt(KEY_ICON_VERSION, 0)

            if (oldVersion != ICON_VERSION) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as? NotificationManager
                if (nm != null) {
                    if (oldVersion == 0) {
                        // 首次迁移：清除旧版硬编码通知 ID
                        nm.cancel(1001)
                        nm.cancel(1002)
                        nm.cancel(2001)
                        nm.cancel(3001)
                    } else {
                        // 版本升级：清除上一版 offset 通知
                        val oldBase = oldVersion * OFFSET_MULTIPLIER
                        nm.cancel(1001 + oldBase)
                        nm.cancel(1002 + oldBase)
                        nm.cancel(2001 + oldBase)
                        nm.cancel(3001 + oldBase)
                    }
                }
                prefs.edit().putInt(KEY_ICON_VERSION, ICON_VERSION).apply()
            }
            initialized = true
        }
    }

    /** 电池提醒通知 ID（原 1001） */
    fun batteryAlertId(): Int = 1001 + ICON_VERSION * OFFSET_MULTIPLIER

    /** 充电状态通知 ID（原 1002） */
    fun chargingStatusId(): Int = 1002 + ICON_VERSION * OFFSET_MULTIPLIER

    /** 后台监控前台服务通知 ID（原 2001） */
    fun monitorServiceId(): Int = 2001 + ICON_VERSION * OFFSET_MULTIPLIER

    /** 悬浮窗前台服务通知 ID（原 3001） */
    fun floatingServiceId(): Int = 3001 + ICON_VERSION * OFFSET_MULTIPLIER
}
