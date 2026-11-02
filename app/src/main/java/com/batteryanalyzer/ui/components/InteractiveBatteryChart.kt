package com.batteryanalyzer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batteryanalyzer.LocalAppColors
import com.batteryanalyzer.model.BatteryRecord
import com.batteryanalyzer.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 时间范围级别
 */
enum class TimeRangeLevel(
    val label: String,
    val defaultDurationMs: Long,
    val formatPattern: String
) {
    MINUTES("分钟", 600_000L, "HH:mm:ss"),
    HOURS("小时", 3_600_000L, "HH:mm"),
    DAY("天", 86_400_000L, "MM-dd HH:mm"),
    WEEK("周", 604_800_000L, "MM-dd HH:mm"),
    MONTH("月", 2_592_000_000L, "MM-dd"),
    YEAR("年", 31_536_000_000L, "yyyy-MM")
}

/**
 * 可缩放交互式电量曲线图
 *
 * 特性：
 * - 双指捏合缩放时间轴（以捏合中心为锚点）
 * - 水平拖动平移时间窗口
 * - 时间范围快速切换（分钟/小时/天/周/月/年）
 * - 智能X轴标签格式化
 * - 充电/耗电区域色彩区分
 * - 默认显示最近数据
 */
@Composable
fun InteractiveBatteryChart(
    records: List<BatteryRecord>,
    modifier: Modifier = Modifier,
    chartHeight: Int = 280,
    showChargingZones: Boolean = true,
    showLegend: Boolean = true,
    title: String = "电量历史曲线"
) {
    val colors = LocalAppColors.current

    if (records.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("暂无数据", color = colors.textSecondary)
        }
        return
    }

    // 按时间排序
    val sortedRecords = remember(records) { records.sortedBy { it.timestamp } }

    // 全局时间范围
    val fullTimeRange = remember(sortedRecords) {
        val min = sortedRecords.first().timestamp
        val max = sortedRecords.last().timestamp
        min to max
    }
    val fullMin = fullTimeRange.first
    val fullMax = fullTimeRange.second
    val fullRange = fullMax - fullMin

    // 当前可视时间窗口
    var viewStartMs by remember(sortedRecords) {
        val defaultWindow = 7_200_000L
        val start = (fullMax - defaultWindow).coerceAtLeast(fullMin)
        mutableStateOf(start)
    }
    var viewEndMs by remember(sortedRecords) { mutableStateOf(fullMax) }
    var currentLevel by remember { mutableStateOf(TimeRangeLevel.DAY) }

    // 过滤可视窗口内的记录
    val visibleRecords = remember(sortedRecords, viewStartMs, viewEndMs) {
        sortedRecords.filter { it.timestamp in viewStartMs..viewEndMs }
    }

    val currentDurationMs = (viewEndMs - viewStartMs).coerceAtLeast(1L)

    Column(modifier = modifier) {
        // === 标题和记录数 ===
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = colors.textPrimary)
            Spacer(modifier = Modifier.weight(1f))
            Text("${visibleRecords.size} 条", fontSize = 11.sp, color = colors.textSecondary)
        }

        // === 时间范围快速切换按钮 ===
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TimeRangeLevel.entries.forEach { level ->
                val isActive = when {
                    currentDurationMs < 900_000L -> level == TimeRangeLevel.MINUTES
                    currentDurationMs < 7_200_000L -> level == TimeRangeLevel.HOURS
                    currentDurationMs < 172_800_000L -> level == TimeRangeLevel.DAY
                    currentDurationMs < 1_209_600_000L -> level == TimeRangeLevel.WEEK
                    currentDurationMs < 5_184_000_000L -> level == TimeRangeLevel.MONTH
                    else -> level == TimeRangeLevel.YEAR
                }
                val label = when (level) {
                    TimeRangeLevel.MINUTES -> "分"
                    TimeRangeLevel.HOURS -> "时"
                    TimeRangeLevel.DAY -> "天"
                    TimeRangeLevel.WEEK -> "周"
                    TimeRangeLevel.MONTH -> "月"
                    TimeRangeLevel.YEAR -> "年"
                }

                Surface(
                    onClick = {
                        val center = (viewStartMs + viewEndMs) / 2
                        val halfWindow = level.defaultDurationMs / 2
                        viewStartMs = (center - halfWindow).coerceAtLeast(fullMin)
                        viewEndMs = (center + halfWindow).coerceAtMost(fullMax)
                        currentLevel = level
                    },
                    shape = RoundedCornerShape(6.dp),
                    color = if (isActive) PrimaryBlue.copy(alpha = 0.15f)
                    else colors.card.copy(alpha = 0.6f)
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 11.sp,
                        color = if (isActive) PrimaryBlue else colors.textSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Surface(
                onClick = {
                    viewStartMs = (fullMax - TimeRangeLevel.DAY.defaultDurationMs).coerceAtLeast(fullMin)
                    viewEndMs = fullMax
                    currentLevel = TimeRangeLevel.DAY
                },
                shape = RoundedCornerShape(6.dp),
                color = colors.card.copy(alpha = 0.4f)
            ) {
                Text(
                    "重置",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontSize = 11.sp,
                    color = colors.textSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // === 图表区域 ===
        Card(
            modifier = Modifier.fillMaxWidth().height(chartHeight.dp),
            colors = CardDefaults.cardColors(containerColor = colors.card),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (visibleRecords.size < 2) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (records.size >= 2) "请缩放或拖动查看数据" else "数据不足",
                            color = colors.textSecondary,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    ZoomableChartCanvas(
                        records = visibleRecords,
                        fullMin = fullMin,
                        fullMax = fullMax,
                        fullRange = fullRange,
                        viewStartMs = viewStartMs,
                        viewEndMs = viewEndMs,
                        currentDurationMs = currentDurationMs,
                        showChargingZones = showChargingZones,
                        showLegend = showLegend,
                        onViewChanged = { start, end ->
                            viewStartMs = start.coerceIn(fullMin, fullMax)
                            viewEndMs = end.coerceIn(fullMin, fullMax)
                        }
                    )
                }
            }
        }

        // === 底部时间轴信息 ===
        if (visibleRecords.size >= 2) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val fmt = when {
                    currentDurationMs < 600_000L -> SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    currentDurationMs < 86_400_000L -> SimpleDateFormat("HH:mm", Locale.getDefault())
                    else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                }
                Text(fmt.format(Date(viewStartMs)), fontSize = 10.sp, color = colors.textSecondary)
                Text("双指缩放 / 拖动平移", fontSize = 10.sp, color = colors.textSecondary.copy(alpha = 0.5f))
                Text(fmt.format(Date(viewEndMs)), fontSize = 10.sp, color = colors.textSecondary)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                "双指捏合缩放 · 拖动平移 · 点击标签切换",
                fontSize = 10.sp,
                color = colors.textSecondary.copy(alpha = 0.45f)
            )
        }
    }
}

/**
 * 可缩放 Canvas 曲线核心实现
 *
 * 【修复】合并为单一 detectTransformGestures，避免两个手势检测器冲突导致闪退
 */
@Composable
private fun ZoomableChartCanvas(
    records: List<BatteryRecord>,
    fullMin: Long,
    fullMax: Long,
    fullRange: Long,
    viewStartMs: Long,
    viewEndMs: Long,
    currentDurationMs: Long,
    showChargingZones: Boolean,
    showLegend: Boolean,
    onViewChanged: (Long, Long) -> Unit
) {
    val colors = LocalAppColors.current

    var chartWidthPx by remember { mutableIntStateOf(0) }

    // 时间格式化器，根据当前时间跨度自动选择
    val timeFormat = remember(currentDurationMs) {
        SimpleDateFormat(
            when {
                currentDurationMs < 300_000L -> "HH:mm:ss"
                currentDurationMs < 7_200_000L -> "HH:mm"
                currentDurationMs < 172_800_000L -> "MM-dd HH:mm"
                currentDurationMs < 5_184_000_000L -> "MM-dd"
                else -> "yyyy-MM"
            },
            Locale.getDefault()
        )
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 48.dp, end = 12.dp, top = 8.dp, bottom = 28.dp)
            .pointerInput(Unit) {
                // 【修复关键】：使用单一的 detectTransformGestures，
                // 同时处理缩放和平移，避免两个检测器竞争导致崩溃
                detectTransformGestures { centroid, pan, zoom, _ ->
                    // 1. 先处理缩放（以捏合中心为锚点）
                    if (abs(zoom - 1f) > 0.01f) {
                        val w = chartWidthPx.toFloat().coerceAtLeast(1f)
                        val centerFrac = (centroid.x / w).coerceIn(0f, 1f)
                        val centerMs = viewStartMs + (currentDurationMs * centerFrac).roundToLong()

                        val newDuration = (currentDurationMs / zoom).roundToLong()
                            .coerceIn(30_000L, fullRange.coerceAtLeast(30_000L))

                        val half = newDuration / 2
                        val newStart = (centerMs - half).coerceAtLeast(fullMin)
                        onViewChanged(newStart, (newStart + newDuration).coerceAtMost(fullMax))
                    } else {
                        // 2. 只处理平移（没有明显缩放时）
                        val w = chartWidthPx.toFloat().coerceAtLeast(1f)
                        val pixelsPerMs = w / currentDurationMs
                        // pan.x > 0 = 手指向右移动 = 显示更早的数据（viewStart 减小）
                        val timeDeltaMs = (-pan.x / pixelsPerMs).roundToLong()

                        val duration = viewEndMs - viewStartMs
                        val newStart = (viewStartMs + timeDeltaMs).coerceAtLeast(fullMin)
                        val constrainedStart =
                            if (newStart + duration > fullMax) fullMax - duration else newStart
                        onViewChanged(constrainedStart, constrainedStart + duration)
                    }
                }
            }
    ) {
        val width = size.width
        val height = size.height
        chartWidthPx = width.toInt()

        val chartLeft = 0f
        val chartRight = width
        val chartTop = 0f
        val chartBottom = height

        if (records.size < 2) return@Canvas

        // ===== 网格线和Y轴标签 =====
        val paintGrid = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#E0E0E0")
            strokeWidth = 1f
        }
        val paintLabel = android.graphics.Paint().apply {
            textSize = 22f
            color = android.graphics.Color.GRAY
            textAlign = android.graphics.Paint.Align.LEFT
            isAntiAlias = true
        }

        for (i in 0..4) {
            val y = chartTop + (chartBottom - chartTop) * i / 4
            val label = "${100 - i * 25}%"
            drawLine(color = Color.LightGray.copy(alpha = 0.2f), start = Offset(chartLeft, y), end = Offset(chartRight, y), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(label, -42f, y + 5f, paintLabel)
        }

        // ===== 充电区域着色 =====
        if (showChargingZones && records.size >= 2) {
            val totalDur = (viewEndMs - viewStartMs).toFloat().coerceAtLeast(1f)
            records.zipWithNext().forEach { (prev, curr) ->
                if (curr.isCharging) {
                    val xFracStart = ((prev.timestamp - viewStartMs) / totalDur).coerceIn(0f, 1f)
                    val xFracEnd = ((curr.timestamp - viewStartMs) / totalDur).coerceIn(0f, 1f)
                    drawRect(
                        color = ChargingGreen.copy(alpha = 0.08f),
                        topLeft = Offset(chartLeft + (chartRight - chartLeft) * xFracStart, chartTop),
                        size = Size((chartRight - chartLeft) * (xFracEnd - xFracStart).coerceAtLeast(0.001f), chartBottom - chartTop)
                    )
                }
            }
        }

        // ===== 折线绘制 =====
        val totalDurMs = (viewEndMs - viewStartMs).coerceAtLeast(1L).toFloat()
        val points = records.map { record ->
            val x = chartLeft + (chartRight - chartLeft) * ((record.timestamp - viewStartMs) / totalDurMs).coerceIn(0f, 1f)
            val y = chartTop + (chartBottom - chartTop) * (100 - record.batteryLevel.coerceIn(0, 100)) / 100f
            Offset(x, y)
        }

        if (points.size >= 2) {
            // 渐变填充
            val fillPath = Path().apply {
                moveTo(points.first().x, chartBottom)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, chartBottom)
                close()
            }
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(listOf(PrimaryBlue.copy(alpha = 0.25f), PrimaryBlue.copy(alpha = 0.03f)))
            )

            // 平滑曲线
            val linePath = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (i in 1 until points.size) {
                    val prev = points[i - 1]
                    val curr = points[i]
                    val cpx = (prev.x + curr.x) / 2
                    quadraticTo(prev.x, prev.y, cpx, (prev.y + curr.y) / 2)
                    lineTo(curr.x, curr.y)
                }
            }
            drawPath(path = linePath, color = PrimaryBlue, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

            // 数据点
            points.forEachIndexed { index, point ->
                drawCircle(
                    color = if (records[index].isCharging) ChargingGreen else PrimaryBlue,
                    radius = 3.5f,
                    center = point
                )
            }
        }

        // ===== X轴时间标签 =====
        // 【修复】使用 CENTER 对齐 + 正确的 x 坐标，确保时间文字可见
        val paintXLabel = android.graphics.Paint().apply {
            textSize = when {
                currentDurationMs < 600_000L -> 16f
                else -> 18f
            }
            color = android.graphics.Color.DKGRAY
            textAlign = android.graphics.Paint.Align.CENTER  // 居中对齐
            isAntiAlias = true
        }

        val labelCount = when {
            records.size <= 5 -> records.size
            currentDurationMs < 86_400_000L -> 6
            currentDurationMs < 604_800_000L -> 7
            else -> 8
        }.coerceAtMost(records.size)

        val step = (records.size / labelCount).coerceAtLeast(1)
        records.forEachIndexed { index, record ->
            if (index % step == 0 || index == records.lastIndex) {
                val label = timeFormat.format(Date(record.timestamp))
                val x = chartLeft + (chartRight - chartLeft) * ((record.timestamp - viewStartMs) / totalDurMs).coerceIn(0f, 1f)
                // 确保在画布范围内才绘制
                if (x >= chartLeft && x <= chartRight) {
                    drawContext.canvas.nativeCanvas.drawText(label, x, chartBottom + 20f, paintXLabel)
                }
            }
        }

        // ===== 图例 =====
        if (showLegend) {
            val legendY = chartTop + 12f
            val legendPaint = android.graphics.Paint().apply {
                textSize = 15f
                color = android.graphics.Color.DKGRAY
                textAlign = android.graphics.Paint.Align.LEFT
                isAntiAlias = true
            }

            drawCircle(ChargingGreen, 5f, Offset(chartRight - 90f, legendY))
            drawContext.canvas.nativeCanvas.drawText("充电", chartRight - 82f, legendY + 5f, legendPaint)

            drawCircle(PrimaryBlue, 5f, Offset(chartRight - 40f, legendY))
            drawContext.canvas.nativeCanvas.drawText("耗电", chartRight - 32f, legendY + 5f, legendPaint)
        }
    }
}
