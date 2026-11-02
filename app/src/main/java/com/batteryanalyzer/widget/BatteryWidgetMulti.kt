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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.batteryanalyzer.MainActivity
import com.batteryanalyzer.R
import com.batteryanalyzer.data.BatteryRepository
import com.batteryanalyzer.data.PreferencesManager
import kotlinx.coroutines.flow.firstOrNull

/**
 * 多设备桌面小组件 — 同时显示多个已保存设备的实时电量
 * 支持自由调整尺寸 (拉伸 / 缩放 / 变宽变高)
 * 根据可用高度自动显示 1~5 个设备
 */
class BatteryWidgetMulti : GlanceAppWidget() {

    /** 设备行数据 */
    data class DeviceRow(
        val name: String,
        val level: Int,
        val isCharging: Boolean,
        val chargeAlert: Int = 85,
        val lowBatteryAlert: Int = 20,
        val deviceId: Long = -1,
        val macAddress: String = ""
    )

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = BatteryRepository(context)
        val prefs = PreferencesManager(context)

        // 获取所有已保存设备
        val allDevices = repo.getAllDevices().firstOrNull() ?: emptyList()
        val devices = allDevices.take(5)

        // 预加载每条记录 + 每设备阈值
        val deviceRows = devices.map { device ->
            val record = repo.getLatestRecord(device.id)
            val chargeAlert = prefs.getDeviceChargeAlertLevel(device.id).firstOrNull() ?: 85
            val lowBatteryAlert = prefs.getDeviceLowBatteryAlertLevel(device.id).firstOrNull() ?: 20
            DeviceRow(
                name = device.displayName,  // 使用 displayName（备注优先）
                level = record?.batteryLevel ?: -1,
                isCharging = record?.isCharging ?: false,
                chargeAlert = chargeAlert,
                lowBatteryAlert = lowBatteryAlert,
                deviceId = device.id,
                macAddress = device.macAddress
            )
        }

        provideContent {
            LargeMultiLayout(context, deviceRows.take(5))
        }
    }

    // ==================== 布局模式 ====================

    /** 大模式：完整行 + 迷你条 */
    @Composable
    private fun LargeMultiLayout(
        context: Context, rows: List<DeviceRow>
    ) {
        val textS = SECOND_COLOR

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(BG_COLOR)
                .cornerRadius(14.dp)
                .padding(10.dp)
                .clickable(actionStartActivity(ComponentName(context, MainActivity::class.java)))
        ) {
            Text(
                text = "🔋 蓝牙设备电量",
                style = TextStyle(color = PRIMARY_COLOR, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = GlanceModifier.height(4.dp))

            if (rows.isEmpty()) {
                Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    contentAlignment = Alignment.Center) {
                    Text("暂无设备，请在应用中添加", style = TextStyle(
                        color = textS, fontSize = 10.sp, textAlign = TextAlign.Center))
                }
            } else {
                rows.forEachIndexed { i, row ->
                    val icon = deviceIcon(row.name)
                    val col = levelColor(row.level, row.isCharging)

                    Row(
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = icon, style = TextStyle(fontSize = 12.sp))
                        Spacer(modifier = GlanceModifier.width(5.dp))
                        Text(text = row.name, style = TextStyle(
                            color = PRIMARY_COLOR, fontSize = 10.sp, fontWeight = FontWeight.Medium
                        ), modifier = GlanceModifier.defaultWeight())
                        Spacer(modifier = GlanceModifier.width(4.dp))
                        // 阈值缩略显示
                        Text(text = "△${row.chargeAlert}/${row.lowBatteryAlert}", 
                            style = TextStyle(color = textS, fontSize = 7.sp))
                        Spacer(modifier = GlanceModifier.width(6.dp))
                        Text(text = if (row.level >= 0) "${row.level}%" else "--",
                            style = TextStyle(color = col, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                    }

                    if (row.level >= 0) {
                        MiniBar(row.level, row.isCharging)
                    }

                    if (i < rows.size - 1) {
                        Spacer(modifier = GlanceModifier.height(2.dp))
                    }
                }
            }
        }
    }

    // ==================== 通用组件 ====================

    @Composable
    private fun MiniBar(level: Int, isCharging: Boolean) {
        val barColor = when {
            isCharging -> GREEN
            level <= 10 -> RED
            level <= 40 -> ORANGE
            level >= 50 -> GREEN
            else -> BLUE
        }
        val barLen = (level.coerceIn(0, 100) / 5).coerceIn(0, 20)
        val emptyLen = 20 - barLen

        Row(modifier = GlanceModifier.fillMaxWidth().height(3.dp)) {
            Text(text = "█".repeat(barLen), style = TextStyle(color = barColor, fontSize = 2.sp))
            Text(text = "█".repeat(emptyLen), style = TextStyle(color = BAR_BG_COLOR, fontSize = 2.sp))
        }
    }

    // ==================== 辅助 ====================

    private fun deviceIcon(name: String): String = when {
        name.contains("Pen", true) || name.contains("笔", true) -> "✏️"
        name.contains("Buds", true) || name.contains("耳机", true) -> "🎧"
        else -> "📱"
    }

    private fun levelColor(level: Int, isCharging: Boolean): ColorProvider = when {
        level < 0 -> SECOND_COLOR
        isCharging -> GREEN
        level <= 10 -> RED
        level <= 40 -> ORANGE
        level >= 50 -> GREEN
        else -> BLUE
    }

    companion object {
        // ===== 颜色定义 (Glance 1.1.1: 必须用 ColorProvider(R.color.xxx)) =====

        // 状态色
        private val GREEN  = ColorProvider(R.color.status_green)
        private val RED    = ColorProvider(R.color.status_red)
        private val ORANGE = ColorProvider(R.color.status_orange)
        private val BLUE   = ColorProvider(R.color.status_blue)

        // 主题相关色（系统自动从 values-night/ 切换）
        private val BG_COLOR      = ColorProvider(R.color.widget_bg)
        private val PRIMARY_COLOR = ColorProvider(R.color.widget_text_primary)
        private val SECOND_COLOR  = ColorProvider(R.color.widget_text_secondary)
        private val BAR_BG_COLOR  = ColorProvider(R.color.bar_bg)
    }
}

class BatteryWidgetMultiReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BatteryWidgetMulti()
}
