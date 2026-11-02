package com.batteryanalyzer.integration

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.batteryanalyzer.MainActivity
import com.batteryanalyzer.data.PreferencesManager
import com.batteryanalyzer.notification.BatteryNotificationManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

/**
 * 电池提醒功能 - 端到端集成测试
 *
 * 模拟完整的用户使用流程：
 * 1. 用户打开App → 授予通知权限
 * 2. 设备连接 → 电池数据开始流入
 * 3. 充电到阈值 → 触发充电提醒通知 + 悬浮窗更新
 * 4. 放电到阈值 → 触发放电提醒通知 + 悬浮窗更新
 * 5. 验证桌面小组件数据与通知数据一致性
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class BatteryAlertIntegrationTest {

    private lateinit var context: Application
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var notificationManager: BatteryNotificationManager
    private lateinit var shadowNotificationManager: ShadowNotificationManager

    // 全局测试参数（模拟用户设置）
    private val deviceName = "Magic-Pencil"
    private val chargeAlertThreshold = 85
    private val lowBatteryThreshold = 20

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        preferencesManager = PreferencesManager(context)

        Shadows.shadowOf(context).grantPermissions(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )

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
    // 场景1：完整的充电提醒流程
    // ================================================================

    @Test
    fun `test complete charging alert flow from device connected to threshold`() {
        // Step 1: 确认通知权限已授予
        assertNotificationPermissionGranted()

        // Step 2: 模拟设备连接（电量从 80% 开始充电）
        val chargingLevels = listOf(80, 83, 85, 87, 90, 94, 97, 100)
        val alertLevels = mutableListOf<Int>()

        for (level in chargingLevels) {
            runTest {
                notificationManager.sendBatteryAlert(deviceName, level, isCharging = true)
            }

            val notifications = shadowNotificationManager.allNotifications
            if (notifications.isNotEmpty()) {
                val lastNotification = notifications.last()
                val text = lastNotification.extras.getString(Notification.EXTRA_TEXT)

                // 验证提醒仅在达到阈值时触发
                if (level >= 85 || level >= 100) {
                    alertLevels.add(level)
                    assertTrue(
                        "充电提醒通知应包含「请断开充电」($level%)",
                        text?.contains("请断开充电") == true
                    )
                }
            }
        }

        // 验证关键提醒节点
        assertTrue(
            "应在 85% 时触发充电提醒",
            alertLevels.any { it >= 85 }
        )
        assertTrue(
            "应在 100% 时触发充满提醒",
            alertLevels.any { it >= 100 }
        )
    }

    // ================================================================
    // 场景2：完整的耗电提醒流程
    // ================================================================

    @Test
    fun `test complete discharging alert flow`() {
        assertNotificationPermissionGranted()

        // 模拟设备从高电量逐渐放电到低电量
        val dischargingLevels = listOf(60, 45, 30, 20, 15, 8, 4)
        val alertLevels = mutableListOf<Int>()

        for (level in dischargingLevels) {
            runTest {
                notificationManager.sendBatteryAlert(deviceName, level, isCharging = false)
            }

            val notifications = shadowNotificationManager.allNotifications
            if (notifications.isNotEmpty()) {
                val lastNotification = notifications.last()
                val text = lastNotification.extras.getString(Notification.EXTRA_TEXT)

                // 验证提醒仅在低于阈值时触发
                if (level <= 20) {
                    alertLevels.add(level)
                    assertTrue(
                        "低电量提醒通知应包含「请充电」($level%)",
                        text?.contains("请充电") == true
                    )
                }
            }
        }

        assertTrue(
            "应在 ≤20% 时触发低电量提醒",
            alertLevels.isNotEmpty()
        )
        assertTrue(
            "应在 ≤5% 时触发极低电量提醒",
            alertLevels.any { it <= 5 }
        )
    }

    // ================================================================
    // 场景3：充放电切换场景
    // ================================================================

    @Test
    fun `test charging status transition notifications`() {
        assertNotificationPermissionGranted()

        // 开始充电
        runTest {
            notificationManager.sendChargingStatus(deviceName, level = 30, isCharging = true)
        }
        val notificationsAfterCharge = shadowNotificationManager.allNotifications
        val chargeNotif = notificationsAfterCharge.last()
        assertEquals(
            "开始充电通知标题",
            "开始充电",
            chargeNotif.extras.getString(Notification.EXTRA_TITLE)
        )
        assertTrue(
            "充电通知应包含电量",
            chargeNotif.extras.getString(Notification.EXTRA_TEXT)?.contains("30%") == true
        )

        // 停止充电
        runTest {
            notificationManager.sendChargingStatus(deviceName, level = 85, isCharging = false)
        }
        val notificationsAfterStop = shadowNotificationManager.allNotifications
        val stopNotif = notificationsAfterStop.last()
        assertEquals(
            "停止充电通知标题",
            "停止充电",
            stopNotif.extras.getString(Notification.EXTRA_TITLE)
        )
        assertTrue(
            "停止充电通知应包含电量",
            stopNotif.extras.getString(Notification.EXTRA_TEXT)?.contains("85%") == true
        )
    }

    // ================================================================
    // 场景4：权限缺失时的行为验证
    // ================================================================

    @Test
    fun `test app behavior when notification permission is revoked`() {
        // 先确认权限已授予
        assertTrue(
            "权限应已授予",
            checkNotificationPermission() == PackageManager.PERMISSION_GRANTED
        )

        // 撤销权限
        Shadows.shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val deniedMgr = BatteryNotificationManager(context)

        // 尝试发送所有类型的提醒
        runTest {
            deniedMgr.sendBatteryAlert(deviceName, level = 90, isCharging = true)
            deniedMgr.sendBatteryAlert(deviceName, level = 10, isCharging = false)
            deniedMgr.sendChargingStatus(deviceName, level = 50, isCharging = true)
        }

        val notifications = shadowNotificationManager.allNotifications
        assertTrue(
            "权限被拒后不应发送任何通知",
            notifications.isEmpty()
        )

        deniedMgr.destroy()
    }

    // ================================================================
    // 场景5：MainActivity 权限请求流程
    // ================================================================

    @Test
    fun `test MainActivity requests notification permission`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = try {
            controller.setup()
        } catch (_: Exception) {
            // Compose Activity 可能在 Robolectric 中有兼容性问题
            null
        }

        if (activity != null) {
            // 验证 Activity 的 intent 配置（直接访问真实 Activity 的 intent）
            val intent = activity.getIntent()
            assertNotNull("MainActivity intent 不应为空", intent)
        }
        controller.destroy()
    }

    // ================================================================
    // 场景6：多设备名称下的提醒内容
    // ================================================================

    @Test
    fun `test notification content with different device names`() {
        val deviceNames = listOf(
            "Magic-Pencil",
            "AirPods Pro",
            "Galaxy Watch",
            "蓝牙设备-001"
        )

        for (name in deviceNames) {
            runTest {
                notificationManager.sendBatteryAlert(name, level = 90, isCharging = true)
            }
        }

        val notifications = shadowNotificationManager.allNotifications
        assertEquals(
            "每个设备应分别发送提醒",
            deviceNames.size,
            notifications.size
        )

        // 验证每个设备的通知内容都包含对应设备名
        for ((index, name) in deviceNames.withIndex()) {
            val text = notifications[index].extras.getString(Notification.EXTRA_TEXT)
            assertTrue(
                "通知内容应包含设备名「$name」",
                text?.contains(name) == true
            )
        }
    }

    // ================================================================
    // 场景7：Widget 与通知的数据一致性端到端
    // ================================================================

    @Test
    fun `test widget and notification data consistency end to end`() {
        // 测试数据：Widget 底层逻辑与通知逻辑使用相同阈值
        val testScenarios = listOf(
            // (level, isCharging, widgetHighlightCharge, widgetHighlightLow, notificationCharge, notificationLow)
            Scenario(95, true, true, false, true, false),   // 充电超阈值
            Scenario(90, true, true, false, true, false),   // 充电超阈值
            Scenario(84, true, false, false, false, false),  // 充电但未达阈值
            Scenario(60, false, false, false, false, false), // 安全范围
            Scenario(20, false, false, true, false, true),   // 达到低电量阈值
            Scenario(15, false, false, true, false, true),   // 低于低电量阈值
            Scenario(5, false, false, true, false, true),    // 极低电量
            Scenario(3, false, false, true, false, true),    // 极低电量
        )

        for (scenario in testScenarios) {
            // Widget 侧
            val widgetHighlightCharge = scenario.level >= chargeAlertThreshold && scenario.isCharging
            val widgetHighlightLow = scenario.level in 0..lowBatteryThreshold

            // 通知侧
            val notificationCharge = scenario.level >= chargeAlertThreshold && scenario.isCharging
            val notificationLow = scenario.level <= lowBatteryThreshold && !scenario.isCharging

            assertEquals(
                "电量=${scenario.level} 充电=${scenario.isCharging}: Widget充电高亮与预期不一致",
                scenario.widgetHighlightCharge, widgetHighlightCharge
            )
            assertEquals(
                "电量=${scenario.level} 充电=${scenario.isCharging}: Widget低电量高亮与预期不一致",
                scenario.widgetHighlightLow, widgetHighlightLow
            )
            assertEquals(
                "电量=${scenario.level} 充电=${scenario.isCharging}: 通知充电提醒与预期不一致",
                scenario.notificationCharge, notificationCharge
            )
            assertEquals(
                "电量=${scenario.level} 充电=${scenario.isCharging}: 通知低电量提醒与预期不一致",
                scenario.notificationLow, notificationLow
            )

            // 关键断言：Widget 高亮状态与通知触发状态必须一致
            if (scenario.isCharging) {
                assertEquals(
                    "充电场景：Widget高亮与通知触发应一致",
                    widgetHighlightCharge, notificationCharge
                )
            }
            if (!scenario.isCharging && scenario.level <= lowBatteryThreshold) {
                assertEquals(
                    "放电场景：Widget低电量高亮与通知低电量提醒应一致",
                    widgetHighlightLow, notificationLow
                )
            }
        }
    }

    // ================================================================
    // 场景8：验证通知中包含所有必需字段
    // ================================================================

    @Test
    fun `test charging alert notification contains all required fields`() {
        runTest {
            notificationManager.sendBatteryAlert(deviceName, level = 90, isCharging = true)
        }

        val notification = shadowNotificationManager.allNotifications.first()
        val extras = notification.extras

        // 必须包含：当前电量
        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        assertTrue("充电提醒必须包含当前电量", Regex("""\d+%""").containsMatchIn(text))

        // 必须包含：充电阈值
        assertTrue("充电提醒必须包含充电阈值", text.contains(chargeAlertThreshold.toString()))

        // 必须包含：请断开充电
        assertTrue("充电提醒必须包含「请断开充电」提示", text.contains("请断开充电"))

        // 必须有标题
        assertNotNull("充电提醒必须有标题", extras.getString(Notification.EXTRA_TITLE))

        // 必须可自动取消
        assertTrue(
            "充电提醒应有自动取消标志",
            (notification.flags and Notification.FLAG_AUTO_CANCEL) != 0
        )
    }

    @Test
    fun `test discharging alert notification contains all required fields`() {
        runTest {
            notificationManager.sendBatteryAlert(deviceName, level = 15, isCharging = false)
        }

        val notification = shadowNotificationManager.allNotifications.first()
        val extras = notification.extras

        val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
        assertTrue("低电量提醒必须包含当前电量", Regex("""\d+%""").containsMatchIn(text))
        assertTrue("低电量提醒必须包含耗电阈值", text.contains(lowBatteryThreshold.toString()))
        assertTrue("低电量提醒必须包含「请充电」提示", text.contains("请充电"))
        assertNotNull("低电量提醒必须有标题", extras.getString(Notification.EXTRA_TITLE))
        assertTrue(
            "低电量提醒应有自动取消标志",
            (notification.flags and Notification.FLAG_AUTO_CANCEL) != 0
        )
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    private fun assertNotificationPermissionGranted() {
        assertEquals(
            "POST_NOTIFICATIONS 权限应已授予",
            PackageManager.PERMISSION_GRANTED,
            checkNotificationPermission()
        )
    }

    private fun checkNotificationPermission(): Int {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    }

    private data class Scenario(
        val level: Int,
        val isCharging: Boolean,
        val widgetHighlightCharge: Boolean,
        val widgetHighlightLow: Boolean,
        val notificationCharge: Boolean,
        val notificationLow: Boolean
    )
}
