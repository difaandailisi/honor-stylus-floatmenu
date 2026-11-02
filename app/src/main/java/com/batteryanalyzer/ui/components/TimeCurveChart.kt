package com.batteryanalyzer.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batteryanalyzer.LocalAppColors
import com.batteryanalyzer.model.CurvePoint
import com.batteryanalyzer.model.TimeDimension
import com.batteryanalyzer.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 时间维度电量曲线图 — Canvas 绘制，美观且支持交互
 *
 * 特性：
 * - 渐变面积图 + 贝塞尔平滑曲线
 * - 自适应时间轴标签（按时间维度）
 * - Y轴刻度 0-100%
 * - 高亮充电段
 * - 触摸查看详情
 * - 数据摘要统计
 */
@Composable
fun TimeCurveChart(
    points: List<CurvePoint>,
    dimension: TimeDimension,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    title: String = ""
) {
    val colors = LocalAppColors.current

    // 动画进度
    val animProgress = remember { Animatable(0f) }
    LaunchedEffect(points.size, dimension) {
        animProgress.snapTo(0f)
        animProgress.animateTo(1f, animationSpec = tween(800, easing = FastOutSlowInEasing))
    }

    // 触摸提示
    var tooltipPoint by remember { mutableStateOf<CurvePoint?>(null) }
    var tooltipPos by remember { mutableStateOf(Offset.Zero) }

    Column(modifier = modifier) {
        // 图表标题和数据摘要
        if (points.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (title.isNotEmpty()) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = colors.textPrimary)
                }
                Text(
                    "${points.size} 个数据点",
                    fontSize = 11.sp, color = colors.textSecondary
                )
            }
        }

        // 图表区域
        Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
            if (points.isEmpty() && !isLoading) {
                // 空数据占位
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无曲线数据", color = colors.textSecondary, fontSize = 14.sp)
                }
            } else if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("加载中...", color = colors.textSecondary, fontSize = 14.sp)
                }
            } else {
                val leftPadding = 40f
                val bottomPadding = 40f
                val topPadding = 8f
                val rightPadding = 16f

                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 4.dp, end = 4.dp, top = 4.dp)
                        .pointerInput(points) {
                            detectTapGestures { offset ->
                                val chartWidth = size.width - leftPadding - rightPadding
                                val chartHeight = size.height - topPadding - bottomPadding
                                if (chartWidth <= 0 || chartHeight <= 0 || points.size < 2) return@detectTapGestures

                                val tapX = offset.x - leftPadding
                                val nearestIdx = ((tapX / chartWidth) * (points.size - 1))
                                    .toInt()
                                    .coerceIn(0, points.size - 1)
                                tooltipPoint = points[nearestIdx]
                                tooltipPos = Offset(
                                    leftPadding + (nearestIdx.toFloat() / (points.size - 1)) * chartWidth,
                                    points[nearestIdx].avgLevel
                                )
                            }
                        }
                ) {
                    val width = size.width
                    val height = size.height
                    val chartWidth = width - leftPadding - rightPadding
                    val chartHeight = height - topPadding - bottomPadding

                    if (chartWidth <= 0 || chartHeight <= 0 || points.size < 1) return@Canvas

                    // ====== Y轴网格线和标签 ======
                    for (i in 0..4) {
                        val y = topPadding + chartHeight * i / 4f
                        val label = "${100 - i * 25}%"
                        // 网格线
                        drawLine(
                            color = colors.textSecondary.copy(alpha = 0.12f),
                            start = Offset(leftPadding, y),
                            end = Offset(width - rightPadding, y),
                            strokeWidth = 1f
                        )
                        // Y轴标签
                        drawContext.canvas.nativeCanvas.drawText(
                            label,
                            leftPadding - 6f,
                            y + 5f,
                            android.graphics.Paint().apply {
                                textSize = 22f
                                color = android.graphics.Color.argb(
                                    140,
                                    if (colors.isDark) 200 else 100,
                                    if (colors.isDark) 200 else 100,
                                    if (colors.isDark) 200 else 100
                                )
                                textAlign = android.graphics.Paint.Align.RIGHT
                                isAntiAlias = true
                            }
                        )
                    }

                    if (points.size < 2) {
                        // 单个数据点
                        val x = leftPadding + chartWidth / 2f
                        val y = topPadding + chartHeight * (100f - points[0].avgLevel.coerceIn(0f, 100f)) / 100f
                        drawCircle(color = PrimaryBlue, radius = 8f, center = Offset(x, y))
                        drawCircle(color = PrimaryBlue.copy(alpha = 0.2f), radius = 16f, center = Offset(x, y))
                        return@Canvas
                    }

                    // ====== 充电区域高亮 ======
                    var chargeStartIdx: Int? = null
                    for (i in points.indices) {
                        val p = points[i]
                        if (p.chargingRatio >= 0.5f && chargeStartIdx == null) {
                            chargeStartIdx = i
                        } else if ((p.chargingRatio < 0.5f || i == points.lastIndex) && chargeStartIdx != null) {
                            val startX = leftPadding + chartWidth * chargeStartIdx!!.toFloat() / (points.size - 1)
                            val endX = leftPadding + chartWidth * i.toFloat() / (points.size - 1)
                            drawRect(
                                color = ChargingGreen.copy(alpha = 0.08f),
                                topLeft = Offset(startX, topPadding),
                                size = Size(endX - startX, chartHeight)
                            )
                            chargeStartIdx = null
                        }
                    }

                    // ====== 按动画进度截取数据点 ======
                    val visibleCount = (points.size * animProgress.value).toInt().coerceAtLeast(2)
                    val subset = points.take(visibleCount)
                    val subsetLastIdx = (subset.size - 1).coerceAtLeast(1)

                    // ====== 计算像素坐标 ======
                    val cooked = subset.mapIndexed { index, p ->
                        val x = leftPadding + chartWidth * index.toFloat() / (points.size - 1).coerceAtLeast(1)
                        val y = topPadding + chartHeight * (100f - p.avgLevel.coerceIn(0f, 100f)) / 100f
                        Triple(p, Offset(x, y), index)
                    }

                    // ====== 渐变面积填充 ======
                    if (cooked.size >= 2) {
                        val fillPath = Path().apply {
                            moveTo(cooked.first().second.x, topPadding + chartHeight)
                            cooked.forEach { (_, offset, _) ->
                                lineTo(offset.x, offset.y)
                            }
                            lineTo(cooked.last().second.x, topPadding + chartHeight)
                            close()
                        }

                        // 电量颜色渐变
                        val avgLevel = cooked.map { it.first.avgLevel }.average().toFloat()
                        val fillTop = getBatteryColor(avgLevel.toInt().coerceIn(0, 100))
                        val fillBot = getBatteryColor(avgLevel.toInt().coerceIn(0, 100)).copy(alpha = 0.02f)

                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(fillTop.copy(alpha = 0.25f), fillBot),
                                startY = topPadding,
                                endY = topPadding + chartHeight
                            )
                        )
                    }

                    // ====== 贝塞尔平滑曲线 ======
                    if (cooked.size >= 2) {
                        val linePath = Path()
                        linePath.moveTo(cooked.first().second.x, cooked.first().second.y)

                        for (i in 1 until cooked.size) {
                            val prev = cooked[i - 1].second
                            val curr = cooked[i].second
                            val cpX = (prev.x + curr.x) / 2f
                            // 控制点带垂直偏移使曲线更平滑
                            val prevCp = Offset(cpX, prev.y)
                            val currCp = Offset(cpX, curr.y)
                            linePath.cubicTo(
                                prevCp.x, prevCp.y,
                                currCp.x, currCp.y,
                                curr.x, curr.y
                            )
                        }

                        // 发光底边（使曲线更柔和）
                        drawPath(
                            path = linePath,
                            color = PrimaryBlue.copy(alpha = 0.25f),
                            style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )

                        // 主曲线
                        drawPath(
                            path = linePath,
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    DischargingOrange.copy(alpha = 0.7f),
                                    PrimaryBlue,
                                    ChargingGreen.copy(alpha = 0.7f)
                                )
                            ),
                            style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }

                    // ====== 数据点（仅显示适量） ======
                    val maxDots = 50
                    val dotStep = if (cooked.size > maxDots) cooked.size / maxDots else 1
                    cooked.forEachIndexed { idx, (p, offset, _) ->
                        if (idx % dotStep == 0 || idx == cooked.lastIndex) {
                            val dotColor = when {
                                p.chargingRatio >= 0.6f -> ChargingGreen
                                p.avgLevel <= 20 -> LowBatteryRed
                                p.avgLevel <= 50 -> DischargingOrange
                                else -> PrimaryBlue
                            }
                            drawCircle(color = colors.surface, radius = 4f, center = offset)
                            drawCircle(color = dotColor, radius = 3f, center = offset)
                        }
                    }

                    // ====== X轴标签 ======
                    val timeFormat = when (dimension) {
                        TimeDimension.MINUTES -> SimpleDateFormat("MM-dd\nHH:mm", Locale.getDefault())
                        TimeDimension.DAYS -> SimpleDateFormat("MM/dd", Locale.getDefault())
                        TimeDimension.MONTHS -> SimpleDateFormat("yyyy/MM", Locale.getDefault())
                        TimeDimension.YEARS -> SimpleDateFormat("yyyy", Locale.getDefault())
                    }

                    val labelStep = if (cooked.size <= 6) 1
                    else if (cooked.size <= 12) 2
                    else if (cooked.size <= 30) cooked.size / 5
                    else cooked.size / 6

                    cooked.forEachIndexed { idx, (p, offset, _) ->
                        if (idx % labelStep == 0 || idx == cooked.lastIndex) {
                            val label = timeFormat.format(Date(p.timestamp))
                            val lines = label.split("\n")
                            lines.forEachIndexed { lineIdx, line ->
                                drawContext.canvas.nativeCanvas.drawText(
                                    line,
                                    offset.x.coerceIn(leftPadding, width - rightPadding - 30f),
                                    topPadding + chartHeight + 16f + lineIdx * 13f,
                                    android.graphics.Paint().apply {
                                        textSize = if (dimension == TimeDimension.MINUTES) 18f else 20f
                                        color = android.graphics.Color.argb(
                                            130,
                                            if (colors.isDark) 200 else 100,
                                            if (colors.isDark) 200 else 100,
                                            if (colors.isDark) 200 else 100
                                        )
                                        textAlign = android.graphics.Paint.Align.CENTER
                                        isAntiAlias = true
                                    }
                                )
                            }
                        }
                    }

                    // ====== Tooltip 提示 ======
                    if (tooltipPoint != null && tooltipPos != Offset.Zero) {
                        val tp = tooltipPoint!!
                        val tipX = tooltipPos.x.coerceIn(leftPadding + 40f, width - rightPadding - 40f)
                        val tipY = topPadding + chartHeight * (100f - tp.avgLevel.coerceIn(0f, 100f)) / 100f

                        // 竖线指示器
                        drawLine(
                            color = colors.textSecondary.copy(alpha = 0.3f),
                            start = Offset(tipX, topPadding),
                            end = Offset(tipX, topPadding + chartHeight),
                            strokeWidth = 1f
                        )

                        // 高亮圆点
                        drawCircle(color = PrimaryBlue, radius = 7f, center = Offset(tipX, tipY))
                        drawCircle(color = colors.surface, radius = 4f, center = Offset(tipX, tipY))
                    }
                }
            }
        }

        // ====== 数据统计行 (带日期范围) ======
        if (points.isNotEmpty() && !isLoading) {
            Spacer(modifier = Modifier.height(6.dp))
            val dateFmt = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
            val startDate = points.first().timestamp
            val endDate = points.last().timestamp
            val dateLabel = when (dimension) {
                TimeDimension.MINUTES -> dateFmt.format(Date(startDate))
                TimeDimension.DAYS -> "${dateFmt.format(Date(startDate))} ~ ${dateFmt.format(Date(endDate))}"
                TimeDimension.MONTHS -> {
                    val yf = SimpleDateFormat("yyyy", Locale.getDefault())
                    val mf = SimpleDateFormat("MM", Locale.getDefault())
                    "${yf.format(Date(startDate))}年${mf.format(Date(startDate))}月 ~ ${yf.format(Date(endDate))}年${mf.format(Date(endDate))}月"
                }
                TimeDimension.YEARS -> {
                    val yf = SimpleDateFormat("yyyy", Locale.getDefault())
                    "${yf.format(Date(startDate))}年 ~ ${yf.format(Date(endDate))}年"
                }
            }
            // 日期标注
            Text(
                dateLabel,
                fontSize = 11.sp,
                color = colors.textSecondary.copy(alpha = 0.7f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                val avgAll = points.map { it.avgLevel }.average().toFloat()
                val minAll = points.minOf { it.minLevel }
                val maxAll = points.maxOf { it.maxLevel }

                CurveStatItem("平均", "${String.format("%.1f", avgAll)}%", getBatteryColor(avgAll.toInt()))
                CurveStatItem("最高", "${maxAll}%", ChargingGreen)
                CurveStatItem("最低", "${minAll}%", LowBatteryRed)
            }
        }

        // ====== Tooltip 详情 ======
        if (tooltipPoint != null) {
            val tp = tooltipPoint!!
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.card.copy(alpha = 0.95f))
                    .padding(10.dp)
            ) {
                val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(timeFmt.format(Date(tp.timestamp)), fontSize = 11.sp, color = colors.textSecondary)
                        Text("平均: ${String.format("%.1f", tp.avgLevel)}%", fontSize = 13.sp,
                            fontWeight = FontWeight.Medium, color = colors.textPrimary)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("范围: ${tp.minLevel}% - ${tp.maxLevel}%", fontSize = 11.sp, color = colors.textSecondary)
                        Text(if (tp.chargingRatio >= 0.5f) "⚡ 充电中" else "🔋 放电中",
                            fontSize = 11.sp, color = if (tp.chargingRatio >= 0.5f) ChargingGreen else DischargingOrange)
                    }
                }
            }
        }
    }
}

@Composable
private fun CurveStatItem(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    val colors = LocalAppColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = colors.textSecondary)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}
