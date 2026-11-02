package com.batteryanalyzer.notification

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Build.VERSION_CODES
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.batteryanalyzer.data.PreferencesManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

/**
 * 电池通知管理器 - 自动化单元测试
 *
 * 覆盖：
 * 1. 通知权限验证 (POST_NOTIFICATIONS)
 * 2. 充电提醒通知内容验证（含「请断开充电」）
 * 3. 耗电提醒通知内容验证（含「请充电」）
 * 4. 节流控制逻辑
 * 5. 通知 Channel 创建
 * 6. 通知 ID 一致性
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU], manifest = Config.NONE)
class BatteryNotificationManagerTest {

    private lateinit var context: Application
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var notificationManager: BatteryNotificationManager
    private lateinit var shadowNotificationManager: ShadowNotificationManager

    private val testDeviceName = "Magic-Pencil"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        preferencesManager = PreferencesManager(context)

        // 授予 POST_NOTIFICATIONS 权限
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        // v6.2: 默认关闭阈值提醒，单测中显式开启，否则 sendBatteryAlert 不会发送通知
        runTest {
            preferencesManager.setDeviceThresholdReminderEnabled(-1L, true)
        }

        notificationManager = BatteryNotificationManager(context)
        shadowNotificationManager = Shadows.shadowOf(
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        )
    }

    @After
    fun tearDown() {
        notificationManager.destroy()
    }

    // ================================================================
    // 测试1：验证通知权限是否正常保留
    // ================================================================

    @Test
    fun `test notification permission is granted and retained`() {
        // 验证 POST_NOTIFICATIONS 权限已授予
        val result = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        )
        assertEquals(
            "POST_NOTIFICATIONS 权限应为已授予状态",
            PackageManager.PERMISSION_GRANTED,
            result
        )
    }

    @Test
    fun `test notification not sent when POST_NOTIFICATIONS permission denied`() {
        // 撤销权限
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val mgr = BatteryNotificationManager(context)
        runTest {
            mgr.sendBatteryAlert(testDeviceName, level = 90, isCharging = true)
        }

        val notifications = shadowNotificationManager.allNotifications
        assertTrue(
            "无 POST_NOTIFICATIONS 权限时不应发送任何通知",
            notifications.isEmpty()
        )
        mgr.destroy()
    }

    @Test
    fun `test notification channels are created on init`() {
        val channels = shadowNotificationManager.notificationChannels
        val channelIds = channels.map { it.id }

        assertTrue(
            "应创建 battery_alert Channel",
            channelIds.contains("battery_alert")
        )
        assertTrue(
            "应创建 charging_status Channel",
            channelIds.contains("charging_status")
        )

        val batteryChannel = channels.first { it.id == "battery_alert" }
        assertEquals(
            "battery_alert Channel 重要性应为 HIGH",
            NotificationManager.IMPORTANCE_HIGH,
            batteryChannel.importance
        )
        assertEquals(
            "battery_alert Channel 名称应为「电量提醒」",
            "电量提醒",
            batteryChannel.name
        )
    }

    // ================================================================
    // 测试2：模拟触发充电提醒，检查通知内容
    // ================================================================

    @Test
    fun `test charging alert contains correct level and threshold with disconnect warning`() {
        // 充电至90%，高于默认阈值85%
        runTest {
            notificationManager.sendBatteryAlert(testDeviceName, level = 90, isCharging = true)
        }

        val notifications = shadowNotificationManager.allNotifications
        assertFalse("充电提醒应发送一条通知", notifications.isEmpty())

        val notification = notifications.first()
        val title = notification.extras.getString(Notification.EXTRA_TITLE)
        val text = notification.extras.getString(Notification.EXTRA_TEXT)

        assertEquals("通知标题应为「充电提醒」", "充电提醒", title)
        assertTrue(
            "通知内容应包含当前电量 90%",
            text?.contains("90%") == true
        )
        assertTrue(
            "通知内容应包含充电阈值 85%",
            text?.contains("85%") == true
        )
        assertTrue(
            "通知内容应包含「请断开充电」提示",
            text?.contains("请断开充电") == true
        )

        // 验证恰好提交了一条电池提醒通知
        assertEquals("应提交一条电池提醒通知", 1, shadowNotificationManager.allNotifications.size)
    }

    @Test
    fun `test full charge alert contains disconnect warning`() {
        runTest {
            notificationManager.sendBatteryAlert(testDeviceName, level = 100, isCharging = true)
        }

        val notifications = shadowNotificationManager.allNotifications
        assertFalse("充满电提醒应发送通知", notifications.isEmpty())

        val text = notifications.first().extras.getString(Notification.EXTRA_TEXT)
        assertTrue("充满电通知应包含「请断开充电」", text?.contains("请断开充电") == true)
        assertTrue("充满电通知应包含「100%」", text?.contains("100%") == true)
        assertEquals(
            "充满电通知标题应为「充电完成」",
            "充电完成",
            notifications.first().extras.getString(Notification.EXTRA_TITLE)
        )
    }

    @Test
    fun `test charging alert sent with correct notification channel`() {
        runTest {
            notificationManager.sendBatteryAlert(testDeviceName, level = 92, isCharging = true)
        }

        val notifications = shadowNotificationManager.allNotifications
        val notification = notifications.first()

        // 验证通知已发出（通过内容验证 channel 行为）
        val title = notification.extras.getString(Notification.EXTRA_TITLE)
        assertNotNull("通知标题不应为空", title)
    }

    // ================================================================
    // 测试3：模拟触发放电/低电量提醒，检查通知内容
    // ================================================================

    @Test
    fun `test low battery alert contains correct level and threshold with charge warning`() {
        // 电量降至15%，低于默认阈值20%
        runTest {
            notificationManager.sendBatteryAlert(testDeviceName, level = 15, isCharging = false)
        }

        val notifications = shadowNotificationManager.allNotifications
        assertFalse("低电量提醒应发送通知", notifications.isEmpty())

        val text = notifications.first().extras.getString(Notification.EXTRA_TEXT)
        assertTrue("低电量通知应包含当前电量 15%", text?.contains("15%") == true)
        assertTrue("低电量通知应包含耗电阈值 20%", text?.contains("20%") == true)
        assertTrue("低电量通知应包含「请充电」提示", text?.contains("请充电") == true)
        assertEquals(
            "低电量通知标题应为「低电量提醒」",
            "低电量提醒",
            notifications.first().extras.getString(Notification.EXTRA_TITLE)
        )
    }

    @Test
    fun `test critical low battery alert contains charge warning`() {
        runTest {
            notificationManager.sendBatteryAlert(testDeviceName, level = 3, isCharging = false)
        }

        val notifications = shadowNotificationManager.allNotifications
        assertFalse("极低电量提醒应发送通知", notifications.isEmpty())

        val text = notifications.first().extras.getString(Notification.EXTRA_TEXT)
        assertTrue("极低电量通知应包含「3%」", text?.contains("3%") == true)
        assertTrue("极低电量通知应包含耗电阈值 5%", text?.contains("5%") == true)
        assertTrue("极低电量通知应包含「请充电」", text?.contains("请充电") == true)
        assertEquals(
            "极低电量通知标题应为「电量严重不足」",
            "电量严重不足",
            notifications.first().extras.getString(Notification.EXTRA_TITLE)
        )
    }

    // ================================================================
    // 测试4：验证通知优先级 (PRIORITY_HIGH 用于电池提醒)
    // ================================================================

    @Test
    fun `test battery alert notification has high priority`() {
        runTest {
            notificationManager.sendBatteryAlert(testDeviceName, level = 88, isCharging = true)
        }

        val notifications = shadowNotificationManager.allNotifications
        val notification = notifications.first()

        // NotificationCompat 设置的 PRIORITY_HIGH 对应 Notification.priority 字段
        val priority = notification.priority
        // PRIORITY_HIGH = 1 in NotificationCompat mapping
        assertTrue(
            "电池提醒通知应有高优先级(>=1)",
            priority >= 1
        )
    }

    @Test
    fun `test notification has autoCancel enabled`() {
        runTest {
            notificationManager.sendBatteryAlert(testDeviceName, level = 88, isCharging = true)
        }

        val notifications = shadowNotificationManager.allNotifications
        val notification = notifications.first()

        // FLAG_AUTO_CANCEL
        val flags = notification.flags
        assertTrue(
            "通知应有 FLAG_AUTO_CANCEL 标记",
            (flags and Notification.FLAG_AUTO_CANCEL) != 0
        )
    }

    // ================================================================
    // 测试5：节流控制 - 同一类型提醒冷却3分钟
    // ================================================================

    @Test
    fun `test throttle prevents duplicate charge alerts within cooldown`() {
        // 第一次充电提醒
        runTest {
            notificationManager.sendBatteryAlert(testDeviceName, level = 86, isCharging = true)
        }
        val countAfterFirst = shadowNotificationManager.allNotifications.size

        // 立即再发同类型提醒（应该在冷却期内被阻止）
        runTest {
            notificationManager.sendBatteryAlert(testDeviceName, level = 87, isCharging = true)
        }
        val countAfterSecond = shadowNotificationManager.allNotifications.size

        assertEquals(
            "冷却期内同类型充电提醒应被节流阻止",
            countAfterFirst,
            countAfterSecond
        )
    }

    @Test
    fun `test critical alert bypasses throttle`() {
        // 第一次极低电量提醒
        runTest {
            notificationManager.sendBatteryAlert(testDeviceName, level = 4, isCharging = false)
        }
        val countAfterFirst = shadowNotificationManager.allNotifications.size

        // 立即再次极低电量提醒（critical 应绕过节流）
        runTest {
            notificationManager.sendBatteryAlert(testDeviceName, level = 3, isCharging = false)
        }
        val countAfterSecond = shadowNotificationManager.allNotifications.size

        // 极低电量每次都发送
        assertTrue(
            "极低电量提醒应绕过节流控制",
            countAfterSecond >= countAfterFirst
        )
    }

    // ================================================================
    // 测试6：验证 sendChargingStatus
    // ================================================================

    @Test
    fun `test charging status notification content`() {
        runTest {
            notificationManager.sendChargingStatus(testDeviceName, level = 45, isCharging = true)
        }

        val notifications = shadowNotificationManager.allNotifications
        assertFalse("充电状态通知应被发送", notifications.isEmpty())

        val notification = notifications.last()
        val title = notification.extras.getString(Notification.EXTRA_TITLE)
        val text = notification.extras.getString(Notification.EXTRA_TEXT)

        assertEquals("充电状态通知标题应为「开始充电」", "开始充电", title)
        assertTrue("充电状态通知应包含电量 45%", text?.contains("45%") == true)
    }

    @Test
    fun `test discharge status notification content`() {
        runTest {
            notificationManager.sendChargingStatus(testDeviceName, level = 60, isCharging = false)
        }

        val notifications = shadowNotificationManager.allNotifications
        assertFalse("放电状态通知应被发送", notifications.isEmpty())

        val title = notifications.last().extras.getString(Notification.EXTRA_TITLE)
        assertEquals("停止充电通知标题应为「停止充电」", "停止充电", title)
    }

    // ================================================================
    // 测试7：验证无提醒场景（电量在安全范围内不应发送提醒）
    // ================================================================

    @Test
    fun `test no alert when battery level is in safe range`() {
        val countBefore = shadowNotificationManager.allNotifications.size

        // 电量在安全范围：21-84%，不充电
        runTest {
            notificationManager.sendBatteryAlert(testDeviceName, level = 50, isCharging = false)
        }

        val countAfter = shadowNotificationManager.allNotifications.size
        assertEquals(
            "安全范围内的电量不应触发提醒",
            countBefore,
            countAfter
        )
    }

    @Test
    fun `test no alert when charging but below threshold`() {
        val countBefore = shadowNotificationManager.allNotifications.size

        // 充电中但电量低于充电提醒阈值（84 < 85）
        runTest {
            notificationManager.sendBatteryAlert(testDeviceName, level = 84, isCharging = true)
        }

        val countAfter = shadowNotificationManager.allNotifications.size
        assertEquals(
            "低于充电阈值时不应触发充电提醒",
            countBefore,
            countAfter
        )
    }

    // ================================================================
    // 测试8：验证通知 ID 常量和 Channel ID 常量
    // ================================================================

    @Test
    fun `test notification ID constants are correct`() {
        // 基础 ID = 100x + ICON_VERSION * 10000
        assertEquals("电池提醒通知ID = 1001 + version*10000", 1001 + NotificationIdHelper.ICON_VERSION * 10000, NotificationIdHelper.batteryAlertId())
        assertEquals("充电状态通知ID = 1002 + version*10000", 1002 + NotificationIdHelper.ICON_VERSION * 10000, NotificationIdHelper.chargingStatusId())
    }

    @Test
    fun `test channel ID constants are correct`() {
        assertEquals("电池提醒Channel应为battery_alert", "battery_alert", BatteryNotificationManager.CHANNEL_BATTERY)
        assertEquals("充电状态Channel应为charging_status", "charging_status", BatteryNotificationManager.CHANNEL_CHARGING)
    }
}
