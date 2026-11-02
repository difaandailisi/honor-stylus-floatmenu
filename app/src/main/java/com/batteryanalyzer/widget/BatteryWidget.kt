package com.batteryanalyzer.widget

import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.batteryanalyzer.MainActivity
import com.batteryanalyzer.R
import com.batteryanalyzer.data.BatteryRepository
import com.batteryanalyzer.data.PreferencesManager
import kotlinx.coroutines.flow.firstOrNull

/**
 * 桌面小组件 - 显示选中设备的实时电量
 * 支持自由调整尺寸 (拉伸 / 缩放 / 变宽变高)
 * 自动在精简/标准/展开三种模式间切换
 */
class BatteryWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = BatteryRepository(context)
        val prefs = PreferencesManager(context)

        // 优先读取小组件配置设备
        val appWidgetId = try {
            val glanceManager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
            glanceManager.getAppWidgetId(id)
        } catch (_: Exception) { -1 }
        val configuredId = if (appWidgetId >= 0) {
            WidgetConfigActivity.getConfiguredDeviceId(context, appWidgetId)
        } else -1L

        val selectedId = when {
            configuredId > 0 -> configuredId
            else -> prefs.selectedDeviceId.firstOrNull() ?: -1L
        }

        val device = if (selectedId > 0) repo.getDeviceById(selectedId)
        else repo.getConnectedDevice()
        val latestRecord = device?.id?.let { repo.getLatestRecord(it) }

        val level = latestRecord?.batteryLevel ?: -1
        val isCharging = latestRecord?.isCharging ?: false
        val deviceName = device?.displayName ?: "未连接"
        val deviceMac = device?.macAddress ?: ""
        val actualDeviceId = device?.id ?: -1L

        val chargeAlertLevel = if (actualDeviceId > 0) prefs.getDeviceChargeAlertLevel(actualDeviceId).firstOrNull() ?: 85 else 85
        val lowBatteryAlertLevel = if (actualDeviceId > 0) prefs.getDeviceLowBatteryAlertLevel(actualDeviceId).firstOrNull() ?: 20 else 20

        provideContent {
            NormalLayout(
                context, level, isCharging, deviceName, deviceMac,
                lowBatteryAlertLevel, chargeAlertLevel
            )
        }
    }

    // ==================== 布局 ====================

    @Composable
    private fun NormalLayout(
        context: Context, level: Int, isCharging: Boolean, deviceName: String,
        deviceMac: String, lowBattery: Int, chargeAlert: Int
    ) {
        val textS = SECOND_COLOR
        val lvColor = levelColor(level, isCharging, textS)
        val barBg = BAR_BG_COLOR

        val devIcon = deviceIcon(deviceName)
        val status = statusText(level, isCharging, lowBattery, chargeAlert)
        val stColor = statusColor(level, isCharging, lowBattery, chargeAlert, textS)

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(BG_COLOR)
                .cornerRadius(14.dp)
                .padding(8.dp)
                .clickable(actionStartActivity(ComponentName(context, MainActivity::class.java)))
        ) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = devIcon, style = TextStyle(fontSize = 10.sp))
                Spacer(modifier = GlanceModifier.width(3.dp))
                Text(
                    text = deviceName,
                    style = TextStyle(color = PRIMARY_COLOR, fontSize = 10.sp, fontWeight = FontWeight.Bold),
                    modifier = GlanceModifier.defaultWeight()
                )
                if (isCharging && level >= 0) {
                    Spacer(modifier = GlanceModifier.width(2.dp))
                    Text(text = "⚡", style = TextStyle(fontSize = 9.sp))
                }
            }

            if (deviceMac.isNotBlank()) {
                Spacer(modifier = GlanceModifier.height(1.dp))
                Text(
                    text = deviceMac,
                    style = TextStyle(color = textS, fontSize = 7.sp)
                )
            }

            Spacer(modifier = GlanceModifier.height(4.dp))

            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (level >= 0) "$level%" else "--",
                    style = TextStyle(color = lvColor, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(text = status, style = TextStyle(color = stColor, fontSize = 9.sp))
                    if (level >= 0) {
                        Spacer(modifier = GlanceModifier.height(3.dp))
                        BatteryBar(level, isCharging, barBg)
                    }
                }
            }

            Spacer(modifier = GlanceModifier.height(4.dp))

            ThresholdRow(lowBattery, chargeAlert, textS)
        }
    }

    @Composable
    private fun ThresholdRow(lowBattery: Int, chargeAlert: Int, textS: ColorProvider) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "充≥$chargeAlert%",
                style = TextStyle(color = GREEN, fontSize = 8.sp)
            )
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = "耗≤$lowBattery%",
                style = TextStyle(color = ORANGE, fontSize = 8.sp)
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
            ReminderIndicator(chargeAlert, lowBattery)
        }
    }

    @Composable
    private fun ReminderIndicator(chargeAlert: Int, lowBattery: Int) {
        val indicator = when {
            chargeAlert < 50 || lowBattery > 48 -> "⚠"
            chargeAlert > 95 || lowBattery < 5 -> "!"
            else -> "●"
        }
        val indColor = when {
            chargeAlert < 50 || lowBattery > 48 -> RED
            chargeAlert > 95 || lowBattery < 5 -> ORANGE
            else -> GREEN
        }
        Text(text = indicator, style = TextStyle(color = indColor, fontSize = 8.sp))
    }

    // ==================== 通用组件 ====================

    @Composable
    private fun BatteryBar(level: Int, isCharging: Boolean, barBg: ColorProvider) {
        val barColor = when {
            isCharging -> GREEN
            level <= 10 -> RED
            level <= 40 -> ORANGE
            level >= 50 -> GREEN
            else -> BLUE
        }
        val filled = (level.coerceIn(0, 100) / 5).coerceIn(0, 20)
        val empty = 20 - filled

        Box(
            modifier = GlanceModifier.fillMaxWidth().height(8.dp)
                .background(barBg).cornerRadius(4.dp)
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth().height(8.dp)) {
                Text(text = "█".repeat(filled), style = TextStyle(color = barColor, fontSize = 6.sp))
                Text(text = "█".repeat(empty), style = TextStyle(color = barBg, fontSize = 6.sp))
            }
        }
    }

    // ==================== 辅助函数 ====================

    private fun deviceIcon(name: String): String = when {
        name.contains("Pen", true) || name.contains("笔", true) -> "✏️"
        name.contains("Buds", true) || name.contains("耳机", true) -> "🎧"
        else -> "📱"
    }

    companion object {
        // ===== 颜色定义 (Glance 1.1.1: 必须用 ColorProvider(R.color.xxx)) =====

        // 状态色（值见 res/values/colors.xml）
        private val GREEN  = ColorProvider(R.color.status_green)
        private val RED    = ColorProvider(R.color.status_red)
        private val ORANGE = ColorProvider(R.color.status_orange)
        private val BLUE   = ColorProvider(R.color.status_blue)
        private val YELLOW = ColorProvider(R.color.status_yellow)

        // 主题相关色（系统自动从 values-night/ 切换）
        private val BG_COLOR      = ColorProvider(R.color.widget_bg)
        private val PRIMARY_COLOR = ColorProvider(R.color.widget_text_primary)
        private val SECOND_COLOR  = ColorProvider(R.color.widget_text_secondary)
        private val BAR_BG_COLOR  = ColorProvider(R.color.bar_bg)

        // 颜色逻辑
        private fun levelColor(level: Int, isCharging: Boolean, fallback: ColorProvider): ColorProvider =
            when {
                level < 0 -> fallback
                isCharging -> GREEN
                level <= 10 -> RED
                level <= 40 -> ORANGE
                level >= 50 -> GREEN
                else -> BLUE
            }

        private fun statusText(level: Int, isCharging: Boolean, lowBattery: Int, chargeAlert: Int): String =
            when {
                level < 0 -> "❓ 未连接"
                isCharging && level >= chargeAlert -> "🔌 请断开充电"
                isCharging -> "🔌 充电中"
                level in 1..9 -> "🔴 极低电量"
                level in 10..lowBattery -> "⚠️ 低电量"
                level in (lowBattery + 1)..49 -> "🟡 适中"
                level >= 50 -> "✅ 安全"
                else -> ""
            }

        private fun statusColor(level: Int, isCharging: Boolean, lowBattery: Int,
                                chargeAlert: Int, fallback: ColorProvider): ColorProvider = when {
            level < 0 -> fallback
            level in 1..9 -> RED
            level in 10..lowBattery -> ORANGE
            isCharging && level >= chargeAlert -> GREEN
            level in (lowBattery + 1)..49 -> YELLOW
            level >= 50 -> GREEN
            else -> fallback
        }
    }
}

class BatteryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BatteryWidget()
}
