package com.batteryanalyzer.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batteryanalyzer.LocalAppColors
import com.batteryanalyzer.model.BatteryRecord
import com.batteryanalyzer.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 自定义电量折线图 - 使用 Canvas 绘制
 */
@Composable
fun BatteryLineChart(
    records: List<BatteryRecord>,
    modifier: Modifier = Modifier,
    showChargingZones: Boolean = true,
    lineColor: Color = PrimaryBlue,
    chargingColor: Color = ChargingGreen
) {
    val colors = LocalAppColors.current
    if (records.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("暂无数据", color = colors.textSecondary)
        }
        return
    }

    val animatedProgress = remember { Animatable(0f) }
    LaunchedEffect(records.size) {
        animatedProgress.snapTo(0f)
        animatedProgress.animateTo(1f, animationSpec = tween(1000))
    }

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize().padding(start = 40.dp, end = 8.dp, top = 8.dp, bottom = 24.dp)) {
            val width = size.width
            val height = size.height
            val chartLeft = 0f
            val chartRight = width
            val chartTop = 0f
            val chartBottom = height

            if (records.size < 2) return@Canvas

            // 绘制网格线
            for (i in 0..4) {
                val y = chartTop + (chartBottom - chartTop) * i / 4
                val label = "${100 - i * 25}%"
                drawLine(
                    color = Color.LightGray.copy(alpha = 0.3f),
                    start = Offset(chartLeft, y),
                    end = Offset(chartRight, y),
                    strokeWidth = 1f
                )
                // Y 轴标签
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    -35f,
                    y + 4f,
                    android.graphics.Paint().apply {
                        textSize = 22f
                        this.color = android.graphics.Color.GRAY
                        textAlign = android.graphics.Paint.Align.LEFT
                    }
                )
            }

            // 绘制充电区域
            if (showChargingZones) {
                var chargingStart: Float? = null
                records.forEachIndexed { index, record ->
                    if (record.isCharging && chargingStart == null) {
                        chargingStart = index.toFloat()
                    } else if (!record.isCharging && chargingStart != null) {
                        val startX = chartLeft + (chartRight - chartLeft) * chargingStart!! / (records.size - 1)
                        val endX = chartLeft + (chartRight - chartLeft) * index / (records.size - 1)
                        drawRect(
                            color = chargingColor.copy(alpha = 0.1f),
                            topLeft = Offset(startX, chartTop),
                            size = Size(endX - startX, chartBottom - chartTop)
                        )
                        chargingStart = null
                    }
                }
            }

            // 绘制折线
            val path = Path()
            val points = records.mapIndexed { index, record ->
                val x = chartLeft + (chartRight - chartLeft) * index / (records.size - 1).coerceAtLeast(1)
                val y = chartTop + (chartBottom - chartTop) * (100 - record.batteryLevel) / 100f
                Offset(x, y)
            }

            // 渐变填充
            val fillPath = Path().apply {
                moveTo(points.first().x, chartBottom)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, chartBottom)
                close()
            }

            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        lineColor.copy(alpha = 0.3f),
                        lineColor.copy(alpha = 0.05f)
                    )
                )
            )

            // 主线
            val linePath = Path().apply {
                moveTo(points.first().x, points.first().y)
                points.forEachIndexed { i, point ->
                    if (i > 0) {
                        val prev = points[i - 1]
                        val cpx = (prev.x + point.x) / 2
                        quadraticTo(prev.x, prev.y, cpx, (prev.y + point.y) / 2)
                        lineTo(point.x, point.y)
                    }
                }
            }

            drawPath(
                path = linePath,
                color = lineColor,
                style = Stroke(width = 3f, cap = StrokeCap.Round)
            )

            // 数据点
            points.forEachIndexed { index, point ->
                val record = records[index]
                val dotColor = if (record.isCharging) chargingColor else lineColor
                drawCircle(
                    color = dotColor,
                    radius = 5f,
                    center = point
                )
            }

            // X 轴标签
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val step = (records.size / 5).coerceAtLeast(1)
            records.forEachIndexed { index, record ->
                if (index % step == 0 || index == records.lastIndex) {
                    val label = timeFormat.format(Date(record.timestamp))
                    val x = chartLeft + (chartRight - chartLeft) * index / (records.size - 1).coerceAtLeast(1)
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        x - 25f,
                        chartBottom + 20f,
                        android.graphics.Paint().apply {
                            textSize = 20f
                            this.color = android.graphics.Color.GRAY
                            textAlign = android.graphics.Paint.Align.LEFT
                        }
                    )
                }
            }
        }
    }
}

/**
 * 电量环形图
 */
@Composable
fun BatteryRingChart(
    level: Int,
    isCharging: Boolean,
    modifier: Modifier = Modifier,
    size: Int = 180,
    showStatusText: Boolean = true
) {
    val colors = LocalAppColors.current
    val color = getBatteryColor(level, isCharging)
    val animatedLevel = remember { Animatable(0f) }

    LaunchedEffect(level) {
        animatedLevel.snapTo(0f)
        animatedLevel.animateTo(
            level / 100f,
            animationSpec = tween(1500, easing = FastOutSlowInEasing)
        )
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size.dp)) {
            val stroke = 12f
            val arcSize = Size(size.dp.toPx() - stroke * 2, size.dp.toPx() - stroke * 2)
            val topLeft = Offset(stroke, stroke)

            // 背景圆弧
            drawArc(
                color = Color.LightGray.copy(alpha = 0.2f),
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )

            // 电量圆弧
            drawArc(
                color = color,
                startAngle = 135f,
                sweepAngle = 270f * animatedLevel.value,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }

        // 文字大小根据环形图尺寸动态缩放
        val levelFontSize = (size * 0.24f).coerceIn(14f, 28f)
        val statusFontSize = (size * 0.11f).coerceIn(9f, 14f)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "$level%",
                fontSize = levelFontSize.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = color,
                maxLines = 1
            )
            if (showStatusText) {
                Text(
                    text = if (isCharging) "充电中" else "使用中",
                    fontSize = statusFontSize.sp,
                    color = if (isCharging) ChargingGreen else colors.textSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 统计卡片
 */
@Composable
fun StatsCard(
    title: String,
    value: String,
    subtitle: String = "",
    valueColor: Color = PrimaryBlue,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = colors.card
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                color = colors.textSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 22.sp,
                color = valueColor,
                style = MaterialTheme.typography.titleLarge
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = colors.textSecondary
                )
            }
        }
    }
}

/**
 * Session行条目
 */
@Composable
fun SessionRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = colors.textSecondary, fontSize = 13.sp)
        Text(text = value, color = colors.textPrimary, fontSize = 13.sp)
    }
}
