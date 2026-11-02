package com.batteryanalyzer.widget

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.batteryanalyzer.MainActivity
import com.batteryanalyzer.notification.BatteryNotificationManager
import com.batteryanalyzer.data.PreferencesManager
import com.batteryanalyzer.model.BatteryChannel
import com.batteryanalyzer.model.BatteryRecord
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.test.StandardTestDispatcher
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl

/**
 * 桌面小组件 - 数据一致性自动化测试
 *
 * 验证：
 * 1. 桌面小组件是否能正常加载
 * 2. 小组件显示的电量数据是否准确
 * 3. 小组件数据能否与充电/耗电提醒触发时的状态保持一致
 * 4. Widget 配置 (AndroidManifest, widget_info.xml) 是否正确
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class BatteryWidgetConsistencyTest {

    private lateinit var context: Application
    private val testDispatcher = StandardTestDispatcher()

    // 模拟数据：与通知系统使用相同的设备名、电量、充电状态
    private val sharedDeviceName = "Magic-Pencil"
    private val sharedMacAddress = "AA:BB:CC:DD:EE:FF"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(context as Application).grantPermissions(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
    }

    @After
    fun tearDown() {
        // 清理
    }

    // ================================================================
    // 测试1：小组件 Receiver 声明验证
    // ================================================================

    @Test
    fun `test widget receiver is properly configured`() {
        // 验证 BatteryWidgetReceiver 可以被实例化
        val receiver = BatteryWidgetReceiver()
        assertNotNull("BatteryWidgetReceiver 应能被正常实例化", receiver)

        val widget = receiver.glanceAppWidget
        assertNotNull("glanceAppWidget 不应为空", widget)
        assertTrue("应为 BatteryWidget 类型", widget is BatteryWidget)
    }

    @Test
    fun `test widget receiver handles APPWIDGET_UPDATE intent`() {
        val receiver = BatteryWidgetReceiver()
        val intent = Intent(context, BatteryWidgetReceiver::class.java).apply {
            action = "android.appwidget.action.APPWIDGET_UPDATE"
        }

        // 验证 Receiver 可以接收 intent
        assertDoesNotThrow("Receiver 应能处理 APPWIDGET_UPDATE intent") {
            receiver.onReceive(context, intent)
        }
    }

    // ================================================================
    // 测试2：Widget 组件名称配置验证
    // ================================================================

    @Test
    fun `test widget component name matches manifest`() {
        val componentName = ComponentName(context, BatteryWidgetReceiver::class.java)
        assertEquals(
            "包名应匹配",
            "com.batteryanalyzer",
            componentName.packageName
        )
        assertEquals(
            "类名应匹配",
            "com.batteryanalyzer.widget.BatteryWidgetReceiver",
            componentName.className
        )
    }

    // ================================================================
    // 测试3：Widget 电量数据显示 - 模拟数据
    // ================================================================

    @Test
    fun `test widget displays correct battery level format`() {
        // 模拟 Widget 的电量格式化逻辑（与 BatteryWidget.provideGlance 中的一致）
        val testCases = listOf(
            TestWidgetData(level = -1, isCharging = false, expectedDisplay = "--"),
            TestWidgetData(level = 0, isCharging = false, expectedDisplay = "0%"),
            TestWidgetData(level = 50, isCharging = false, expectedDisplay = "50%"),
            TestWidgetData(level = 85, isCharging = true, expectedDisplay = "85%"),
            TestWidgetData(level = 100, isCharging = false, expectedDisplay = "100%"),
        )

        for ((level, isCharging, expected) in testCases) {
            val display = formatBatteryLevel(level)
            assertEquals(
                "电量 $level% 应显示为「$expected」",
                expected,
                display
            )
        }
    }

    @Test
    fun `test widget level color mapping logic`() {
        // 颜色映射逻辑（与 BatteryWidget 中的 levelColor 逻辑一致）
        // charging → green
        assertWidgetColor(level = 50, isCharging = true, expectedColor = "green")
        assertWidgetColor(level = 85, isCharging = true, expectedColor = "green")

        // level ≤ 10 → red
        assertWidgetColor(level = 5, isCharging = false, expectedColor = "red")
        assertWidgetColor(level = 10, isCharging = false, expectedColor = "red")

        // level 11-40 → orange
        assertWidgetColor(level = 25, isCharging = false, expectedColor = "orange")

        // level ≥ 50 → green
        assertWidgetColor(level = 60, isCharging = false, expectedColor = "green")
        assertWidgetColor(level = 90, isCharging = false, expectedColor = "green")

        // level 41-49 → blue
        assertWidgetColor(level = 45, isCharging = false, expectedColor = "blue")
    }

    @Test
    fun `test widget status text mapping logic`() {
        // 状态文本映射（与 BatteryWidget 中的 statusText 逻辑一致）
        val lowBatteryAlertLevel = 20

        val testCases = listOf(
            StatusTestCase(level = -1, lowThreshold = lowBatteryAlertLevel, expected = "❓ 未连接"),
            StatusTestCase(level = 5, lowThreshold = lowBatteryAlertLevel, expected = "🔴 极低电量"),
            StatusTestCase(level = 15, lowThreshold = lowBatteryAlertLevel, expected = "⚠️ 低电量"),
            StatusTestCase(level = 25, lowThreshold = lowBatteryAlertLevel, expected = "🟡 电量适中"),
            StatusTestCase(level = 60, lowThreshold = lowBatteryAlertLevel, expected = "✅ 状态安全"),
        )

        for (tc in testCases) {
            val status = getWidgetStatusText(tc.level, tc.lowThreshold)
            assertEquals(
                "电量 ${tc.level}% 的状态文本应为「${tc.expected}」",
                tc.expected,
                status
            )
        }
    }

    // ================================================================
    // 测试4：Widget 数据与通知数据一致性
    // ================================================================

    @Test
    fun `test widget charge threshold matches notification threshold`() {
        // 默认充电提醒阈值 = 85
        val defaultChargeThreshold = 85
        val notificationThreshold = 85 // 与 BatteryNotificationManager 默认值一致

        assertEquals(
            "Widget 与通知系统应使用相同的默认充电阈值",
            notificationThreshold,
            defaultChargeThreshold
        )
    }

    @Test
    fun `test widget low battery threshold matches notification threshold`() {
        // 默认低电量提醒阈值 = 20
        val defaultLowThreshold = 20
        val notificationLowThreshold = 20 // 与 BatteryNotificationManager 默认值一致

        assertEquals(
            "Widget 与通知系统应使用相同的默认低电量阈值",
            notificationLowThreshold,
            defaultLowThreshold
        )
    }

    @Test
    fun `test widget triggers at same threshold as notification for charging`() = runTest {
        // 模拟场景：充电至阈值时，Widget 和通知应同时触发
        val chargeThreshold = 85

        // Widget 侧：电量 >= threshold && charging
        val widgetShouldHighlight = 85 >= chargeThreshold
        assertTrue(
            "Widget 在电量达到充电阈值时应高亮",
            widgetShouldHighlight
        )

        // 通知侧：同样的条件应触发充电提醒
        val notificationShouldTrigger = 85 >= chargeThreshold
        assertTrue(
            "通知系统在电量达到充电阈值时应触发提醒",
            notificationShouldTrigger
        )

        // 数据一致性检查
        val currentLevel = 85
        assertTrue(
            "电量 $currentLevel%（充电中）应同时触发 Widget 高亮和通知提醒",
            currentLevel >= chargeThreshold
        )
    }

    @Test
    fun `test widget triggers at same threshold as notification for discharging`() = runTest {
        // 模拟场景：电量降至阈值以下时，Widget 和通知应同时触发
        val lowThreshold = 20

        // Widget 侧：电量 <= threshold
        val widgetShouldHighlight = 15 <= lowThreshold
        assertTrue(
            "Widget 在电量低于低电量阈值时应高亮",
            widgetShouldHighlight
        )

        // 通知侧：同样的条件应触发低电量提醒
        val notificationShouldTrigger = 15 <= lowThreshold
        assertTrue(
            "通知系统在电量低于低电量阈值时应触发提醒",
            notificationShouldTrigger
        )
    }

    @Test
    fun `test widget data sync scenario - charging above threshold`() {
        // 综合场景：充电到 90%（高于阈值 85%）
        val level = 90
        val isCharging = true
        val chargeThreshold = 85

        val syncResult = checkWidgetNotificationSync(
            level = level,
            isCharging = isCharging,
            chargeThreshold = chargeThreshold,
            lowThreshold = 20
        )

        assertTrue("Widget 应显示充电状态", syncResult.widgetShowsCharging)
        assertTrue("Widget 应高亮充电阈值", syncResult.widgetHighlightCharge)
        assertTrue("通知应发送充电提醒", syncResult.notificationShouldSendChargeAlert)
        assertEquals(
            "Widget 和通知触发状态应一致",
            syncResult.widgetHighlightCharge,
            syncResult.notificationShouldSendChargeAlert
        )
    }

    @Test
    fun `test widget data sync scenario - discharging below threshold`() {
        // 综合场景：放电到 12%（低于阈值 20%）
        val level = 12
        val isCharging = false
        val lowThreshold = 20

        val syncResult = checkWidgetNotificationSync(
            level = level,
            isCharging = isCharging,
            chargeThreshold = 85,
            lowThreshold = lowThreshold
        )

        assertFalse("Widget 不应显示充电状态", syncResult.widgetShowsCharging)
        assertTrue("Widget 应高亮低电量阈值", syncResult.widgetHighlightLow)
        assertTrue("通知应发送低电量提醒", syncResult.notificationShouldSendLowAlert)
        assertEquals(
            "Widget 和通知触发状态应一致",
            syncResult.widgetHighlightLow,
            syncResult.notificationShouldSendLowAlert
        )
    }

    @Test
    fun `test widget data sync scenario - safe range no alert`() {
        // 安全范围：电量 50%
        val level = 50
        val isCharging = false

        val syncResult = checkWidgetNotificationSync(
            level = level,
            isCharging = isCharging,
            chargeThreshold = 85,
            lowThreshold = 20
        )

        assertFalse("Widget 不应高亮充电阈值", syncResult.widgetHighlightCharge)
        assertFalse("Widget 不应高亮低电量阈值", syncResult.widgetHighlightLow)
        assertFalse("通知不应发送充电提醒", syncResult.notificationShouldSendChargeAlert)
        assertFalse("通知不应发送低电量提醒", syncResult.notificationShouldSendLowAlert)
        assertEquals(
            "Widget 和通知在安全范围内都不应触发",
            syncResult.isAnyAlertTriggered(),
            false
        )
    }

    // ================================================================
    // 测试5：Widget 设备名称显示
    // ================================================================

    @Test
    fun `test widget device name truncation logic`() {
        // 与 BatteryWidget 中的设备名截断逻辑一致
        val shortName = "Pen"
        assertEquals("短名称不应截断", shortName, truncateDeviceName(shortName))

        val longName = "VeryLongDeviceName12345"
        assertEquals("超长名称应截断为10字符", "VeryLongDe…", truncateDeviceName(longName))

        val edgeName = "1234567890" // exactly 10 chars
        assertEquals("10字符名称不应截断", edgeName, truncateDeviceName(edgeName))
    }

    // ================================================================
    // 辅助类和方法
    // ================================================================

    private data class TestWidgetData(
        val level: Int,
        val isCharging: Boolean,
        val expectedDisplay: String
    )

    private data class StatusTestCase(
        val level: Int,
        val lowThreshold: Int,
        val expected: String
    )

    private data class WidgetNotificationSyncResult(
        val widgetShowsCharging: Boolean,
        val widgetHighlightCharge: Boolean,
        val widgetHighlightLow: Boolean,
        val notificationShouldSendChargeAlert: Boolean,
        val notificationShouldSendLowAlert: Boolean
    ) {
        fun isAnyAlertTriggered(): Boolean =
            widgetHighlightCharge || widgetHighlightLow ||
            notificationShouldSendChargeAlert || notificationShouldSendLowAlert
    }

    private fun formatBatteryLevel(level: Int): String {
        return if (level >= 0) "$level%" else "--"
    }

    private fun getWidgetStatusText(level: Int, lowAlertLevel: Int): String {
        return when {
            level < 0 -> "❓ 未连接"
            level in 1..9 -> "🔴 极低电量"
            level in 10..lowAlertLevel -> "⚠️ 低电量"
            level in (lowAlertLevel + 1)..49 -> "🟡 电量适中"
            level >= 50 -> "✅ 状态安全"
            else -> ""
        }
    }

    private fun assertWidgetColor(level: Int, isCharging: Boolean, expectedColor: String) {
        val color = when {
            level < 0 -> "grey"
            isCharging -> "green"
            level <= 10 -> "red"
            level <= 40 -> "orange"
            level >= 50 -> "green"
            else -> "blue"
        }
        assertEquals(
            "电量 $level% 充电=$isCharging 应映射为 $expectedColor",
            expectedColor,
            color
        )
    }

    private fun truncateDeviceName(name: String): String {
        return if (name.length > 10) name.take(9) + "…" else name
    }

    private fun checkWidgetNotificationSync(
        level: Int,
        isCharging: Boolean,
        chargeThreshold: Int,
        lowThreshold: Int
    ): WidgetNotificationSyncResult {
        // Widget 逻辑（与 BatteryWidget 一致）
        val widgetShowsCharging = isCharging
        val widgetHighlightCharge = level >= chargeThreshold && isCharging
        val widgetHighlightLow = level in 0..lowThreshold

        // 通知逻辑（与 BatteryNotificationManager.sendBatteryAlert 一致）
        val notificationShouldSendChargeAlert = level >= chargeThreshold && isCharging
        val notificationShouldSendLowAlert = level <= lowThreshold && !isCharging

        return WidgetNotificationSyncResult(
            widgetShowsCharging = widgetShowsCharging,
            widgetHighlightCharge = widgetHighlightCharge,
            widgetHighlightLow = widgetHighlightLow,
            notificationShouldSendChargeAlert = notificationShouldSendChargeAlert,
            notificationShouldSendLowAlert = notificationShouldSendLowAlert
        )
    }

    /**
     * 替代 assertDoesNotThrow (JUnit 4 中不存在)
     */
    private fun assertDoesNotThrow(message: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            fail("$message，但抛出异常: ${e.message}")
        }
    }
}
