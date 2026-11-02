package com.batteryanalyzer.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batteryanalyzer.LocalAppColors
import com.batteryanalyzer.ble.ConnectionState
import com.batteryanalyzer.model.*
import com.batteryanalyzer.ui.components.BatteryLineChart
import com.batteryanalyzer.ui.components.InteractiveBatteryChart
import com.batteryanalyzer.ui.components.SessionRow
import com.batteryanalyzer.ui.components.TimeCurveChart
import com.batteryanalyzer.ui.theme.*
import com.batteryanalyzer.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

/**
 * 短时功耗分析界面 - 充电/耗电功耗对比，【不计入健康度】
 *
 * 核心功能：
 * 1. 1%粒度电量变化追踪（高灵敏度）
 * 2. 充电 vs 耗电瞬时功率对比
 * 3. 时间-电量交互式曲线（支持分钟~年跨度的缩放查看）
 * 4. 与健康度统计完全独立，仅作灵敏参考
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PowerAnalysisScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val colors = LocalAppColors.current
    val currentDevice by viewModel.currentDevice.collectAsState()
    val batteryLevel by viewModel.currentBatteryLevel.collectAsState()
    val isCharging by viewModel.isCharging.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedMac by viewModel.connectedDeviceMac.collectAsState()
    val powerAnalysis by viewModel.powerAnalysis.collectAsState()
    val powerAnalysisLoading by viewModel.powerAnalysisLoading.collectAsState()
    val powerWindowMs by viewModel.powerWindowMs.collectAsState()
    val records by viewModel.batteryRecords.collectAsState()
    val allDevices by viewModel.allDevices.collectAsState()
    val selectedDeviceId by viewModel.selectedDeviceId.collectAsState()

    // 时间维度曲线状态
    val curvePoints by viewModel.curvePoints.collectAsState()
    val curveLoading by viewModel.curveLoading.collectAsState()
    val timeDimension by viewModel.timeDimension.collectAsState()

    var showDeviceSelector by remember { mutableStateOf(false) }

    // 扩展时间窗口选项：分钟~年
    val timeWindows = PowerWindow.entries

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("短时功耗分析") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // 刷新按钮
                    IconButton(onClick = {
                        viewModel.refreshPowerAnalysis()
                        viewModel.refreshCurveData()
                    }) {
                        Icon(Icons.Default.Refresh, "刷新")
                    }
                    if (allDevices.isNotEmpty()) {
                        IconButton(onClick = { showDeviceSelector = true }) {
                            Icon(Icons.Default.SwapHoriz, "切换设备")
                        }
                    }
                }
            )
        }
    ) { padding ->
        // 设备选择弹窗
        if (showDeviceSelector) {
            AlertDialog(
                onDismissRequest = { showDeviceSelector = false },
                title = { Text("选择设备") },
                text = {
                    LazyColumn {
                        items(allDevices) { device ->
                            val deviceConnected = connectionState == ConnectionState.CONNECTED && connectedMac == device.macAddress
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = device.id == selectedDeviceId,
                                    onClick = {
                                        viewModel.selectDevice(device.id)
                                        showDeviceSelector = false
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(device.displayName, fontWeight = FontWeight.Medium)
                                    Text(
                                        if (deviceConnected) "已连接" else "未连接",
                                        fontSize = 12.sp,
                                        color = colors.textSecondary
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDeviceSelector = false }) { Text("关闭") }
                }
            )
        }

        if (currentDevice == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("请先选择设备", color = colors.textSecondary, fontSize = 16.sp)
                    if (allDevices.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("点击右上角切换按钮", color = colors.textSecondary, fontSize = 13.sp)
                    }
                }
            }
            return@Scaffold
        }

        val device = currentDevice!!

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // === 设备状态卡片 ===
            item {
                DeviceStatusCard(
                    deviceName = device.displayName,
                    batteryLevel = batteryLevel,
                    isCharging = isCharging,
                    isConnected = connectionState == ConnectionState.CONNECTED,
                    batteryCapacity = device.batteryCapacityMah
                )
            }

            // === 时间窗口选择 (分钟~年) ===
            item {
                Text(
                    "分析时间范围",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                // 第一行：分钟、小时
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    timeWindows.take(6).forEach { tw ->
                        FilterChip(
                            selected = powerWindowMs == tw.ms,
                            onClick = { viewModel.setPowerWindow(tw.ms) },
                            label = { Text(tw.label, fontSize = 10.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                // 第二行：天、周、月、年
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    timeWindows.drop(6).forEach { tw ->
                        FilterChip(
                            selected = powerWindowMs == tw.ms,
                            onClick = { viewModel.setPowerWindow(tw.ms) },
                            label = { Text(tw.label, fontSize = 10.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // === 功耗对比核心卡片 ===
            item {
                if (powerAnalysisLoading) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.card),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (powerAnalysis != null) {
                    PowerComparisonCard(powerAnalysis!!)
                } else {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colors.card),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("数据不足，请连接设备后等待记录数据", color = colors.textSecondary)
                        }
                    }
                }
            }

            // === 电量变化曲线（可缩放交互式） ===
            item {
                val windowRecords = records.filter {
                    it.timestamp >= System.currentTimeMillis() - powerWindowMs
                }
                InteractiveBatteryChart(
                    records = windowRecords,
                    modifier = Modifier.fillMaxWidth(),
                    chartHeight = 320,
                    showChargingZones = true,
                    title = "电量变化曲线 (可缩放)"
                )
            }

            // === 电量趋势曲线（多维度聚合） ===
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // 标题栏
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Timeline, contentDescription = null,
                                    tint = PrimaryBlue, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("电量趋势曲线", fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp, color = colors.textPrimary)
                            }
                            IconButton(
                                onClick = { viewModel.refreshCurveData() },
                                enabled = !curveLoading,
                                modifier = Modifier.size(28.dp)
                            ) {
                                if (curveLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 1.5.dp,
                                        color = PrimaryBlue
                                    )
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = "刷新",
                                        tint = PrimaryBlue, modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 时间维度切换
                        TimeDimensionSwitcher(
                            selected = timeDimension,
                            onSelect = { viewModel.setTimeDimension(it) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 曲线图
                        TimeCurveChart(
                            points = curvePoints,
                            dimension = timeDimension,
                            isLoading = curveLoading,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            when (timeDimension) {
                                TimeDimension.MINUTES -> "• 按分钟显示精确电量变化，适合查看短期细节"
                                TimeDimension.DAYS -> "• 按天聚合日均电量，展示中期使用趋势"
                                TimeDimension.MONTHS -> "• 按月聚合月均电量，观察季节性变化规律"
                                TimeDimension.YEARS -> "• 按年聚合年均电量，反映长期电池衰减趋势"
                            },
                            fontSize = 10.sp,
                            color = colors.textSecondary,
                            lineHeight = 14.sp
                        )
                    }
                }
            }

            // === 充放电能耗比 ===
            item {
                powerAnalysis?.let { analysis ->
                    if (analysis.chargeDischargeRatio > 0) {
                        EnergyRatioCard(analysis)
                    }
                }
            }

            // === 详细功率变化列表 ===
            item {
                powerAnalysis?.let { analysis ->
                    if (analysis.changes.isNotEmpty()) {
                        Text(
                            "详细变化记录 (每1%电量变化)",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            powerAnalysis?.let { analysis ->
                val recentChanges = analysis.changes.takeLast(50)
                items(recentChanges, key = { it.timestamp }) { change ->
                    PowerChangeRow(change, analysis.deviceCapacityMah)
                }

                if (analysis.changes.size > 50) {
                    item {
                        Text(
                            "... 共 ${analysis.changes.size} 次变化，仅显示最近50条",
                            color = colors.textSecondary,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                        )
                    }
                }
            }

            // === 底部说明 ===
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = DischargingOrange.copy(alpha = 0.06f)
                    ),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = DischargingOrange,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                "短时功耗分析 - 仅作灵敏参考",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = DischargingOrange
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "• 本页数据仅用于实时功耗对比，【不计入电池健康度统计】\n" +
                                        "• 每1%电量变化均被精确追踪和计算\n" +
                                        "• 充电/耗电速率基于相邻两次电量变化的时间差计算\n" +
                                        "• 支持分钟~年跨度的长时间查看\n" +
                                        "• 间隔超过30分钟的变化会被自动过滤（视为设备断连）\n" +
                                        "• 完整充放电循环的健康度评估请查看「长时分析」",
                                fontSize = 11.sp,
                                color = colors.textSecondary,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }

            // === 设备 MAC 地址 ===
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Devices, contentDescription = null,
                            tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("设备信息", fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                            Text(
                                "名称: ${device.displayName}\nMAC: ${device.macAddress}",
                                fontSize = 11.sp, color = colors.textSecondary, lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== 子组件 ====================

/**
 * 设备状态卡片 - 电量数字与状态标识清晰分离
 * 状态(充电/使用/离线)独立显示在电量数字旁边，避免视觉混淆
 */
@Composable
private fun DeviceStatusCard(
    deviceName: String,
    batteryLevel: Int,
    isCharging: Boolean,
    isConnected: Boolean = true,
    batteryCapacity: Int
) {
    val colors = LocalAppColors.current
    val hasValidBattery = batteryLevel in 0..100

    // 综合状态判定
    val statusText: String
    val statusColor: Color
    val ringColor: Color
    when {
        !isConnected -> {
            statusText = "离线"
            statusColor = colors.textSecondary
            ringColor = colors.textSecondary.copy(alpha = 0.5f)
        }
        isCharging -> {
            statusText = "充电中"
            statusColor = ChargingGreen
            ringColor = ChargingGreen
        }
        else -> {
            statusText = "使用中"
            statusColor = DischargingOrange
            ringColor = getBatteryColor(batteryLevel, isCharging)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧：纯电量环形图（不夹杂状态文字）
            Box(contentAlignment = Alignment.Center) {
                if (hasValidBattery) {
                    CircularProgressIndicator(
                        progress = { (batteryLevel.coerceIn(0, 100)) / 100f },
                        modifier = Modifier.size(48.dp),
                        color = ringColor,
                        strokeWidth = 4.dp,
                        trackColor = colors.textSecondary.copy(alpha = 0.15f),
                    )
                    Text(
                        "${batteryLevel.coerceIn(0, 100)}%",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = ringColor
                    )
                } else {
                    // 无电量数据时显示占位
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "--",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = colors.textSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // 设备名称
                Text(deviceName, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = colors.textPrimary)
                Spacer(modifier = Modifier.height(4.dp))

                // 电池容量
                Text(
                    "电池容量: ${batteryCapacity}mAh",
                    fontSize = 11.sp,
                    color = colors.textSecondary
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 状态标签：独立显示，与电量数字分离
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 状态指示点
                    Surface(
                        color = statusColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(statusColor, RoundedCornerShape(50))
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                statusText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = statusColor
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 功耗对比核心卡片 - 充电 vs 耗电
 */
@Composable
private fun PowerComparisonCard(analysis: ShortTermPowerAnalysis) {
    val colors = LocalAppColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 标题
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Bolt,
                    contentDescription = null,
                    tint = DischargingOrange,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("功耗对比", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = colors.textPrimary)
                Spacer(modifier = Modifier.weight(1f))
                // 数据量标记
                Surface(
                    color = PrimaryBlueLight.copy(alpha = 0.15f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        "${analysis.changes.size}次变化",
                        fontSize = 11.sp,
                        color = PrimaryBlue,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 左右对比：充电 | 耗电
            Row(modifier = Modifier.fillMaxWidth()) {
                // === 充电侧 ===
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = ChargingGreen.copy(alpha = 0.06f)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "⚡ 充电",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = ChargingGreen
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (analysis.chargeCount > 0) {
                            // 平均充电速率
                            Text(
                                String.format("%.1f", analysis.avgChargeRate),
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = ChargingGreen
                            )
                            Text("mAh/h", fontSize = 12.sp, color = colors.textSecondary)
                            Spacer(modifier = Modifier.height(8.dp))

                            HorizontalDivider(
                                color = ChargingGreen.copy(alpha = 0.15f),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            InfoRow("变化次数", "${analysis.chargeCount} 次")
                            InfoRow("累计充入", String.format("%.1f mAh", analysis.totalChargedMah))
                            InfoRow("充电时长", String.format("%.0f 分钟", analysis.totalChargeTimeMinutes))
                            InfoRow(
                                "最大速率",
                                String.format("%.1f mAh/h", analysis.maxChargeRate)
                            )
                            if (analysis.maxChargeRateLevel > 0) {
                                Text(
                                    "@${analysis.maxChargeRateLevel}%",
                                    fontSize = 10.sp,
                                    color = colors.textSecondary
                                )
                            }
                        } else {
                            Text("无充电记录", color = colors.textSecondary, fontSize = 13.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // === 耗电侧 ===
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = DischargingOrange.copy(alpha = 0.06f)
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "🔋 耗电",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = DischargingOrange
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (analysis.dischargeCount > 0) {
                            // 平均耗电速率
                            Text(
                                String.format("%.1f", analysis.avgDischargeRate),
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                                color = DischargingOrange
                            )
                            Text("mAh/h", fontSize = 12.sp, color = colors.textSecondary)
                            Spacer(modifier = Modifier.height(8.dp))

                            HorizontalDivider(
                                color = DischargingOrange.copy(alpha = 0.15f),
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            InfoRow("变化次数", "${analysis.dischargeCount} 次")
                            InfoRow("累计消耗", String.format("%.1f mAh", analysis.totalDischargedMah))
                            InfoRow("耗电时长", String.format("%.0f 分钟", analysis.totalDischargeTimeMinutes))
                            InfoRow(
                                "最大速率",
                                String.format("%.1f mAh/h", analysis.maxDischargeRate)
                            )
                            if (analysis.maxDischargeRateLevel > 0) {
                                Text(
                                    "@${analysis.maxDischargeRateLevel}%",
                                    fontSize = 10.sp,
                                    color = colors.textSecondary
                                )
                            }
                        } else {
                            Text("无耗电记录", color = colors.textSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }

            // 净变化摘要
            if (analysis.totalRecords >= 2) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = colors.textSecondary.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val netColor = if (analysis.netChangePercent > 0) ChargingGreen
                    else if (analysis.netChangePercent < 0) DischargingOrange
                    else colors.textSecondary
                    val netPrefix = if (analysis.netChangePercent > 0) "+" else ""

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("净变化", fontSize = 10.sp, color = colors.textSecondary)
                        Text(
                            "$netPrefix${analysis.netChangePercent}%",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = netColor
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("净电量", fontSize = 10.sp, color = colors.textSecondary)
                        Text(
                            "${String.format("%.1f", analysis.netChangeMah)} mAh",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = netColor
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("数据记录", fontSize = 10.sp, color = colors.textSecondary)
                        Text(
                            "${analysis.totalRecords} 条",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = PrimaryBlue
                        )
                    }
                }
            }
        }
    }
}

/**
 * 充放电能耗比卡片
 */
@Composable
private fun EnergyRatioCard(analysis: ShortTermPowerAnalysis) {
    val colors = LocalAppColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.ShowChart,
                    contentDescription = null,
                    tint = PrimaryBlue,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("充放电能耗比", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = colors.textPrimary)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 能耗比可视化条
            val ratio = analysis.chargeDischargeRatio
            // 归一化：ratio=1 表示充电速率=耗电速率
            val barRatio = (ratio / (ratio + 1)).coerceIn(0.05f, 0.95f)

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "充电 ${String.format("%.1f", analysis.avgChargeRate)} mAh/h",
                        fontSize = 11.sp,
                        color = ChargingGreen
                    )
                    Text(
                        "耗电 ${String.format("%.1f", analysis.avgDischargeRate)} mAh/h",
                        fontSize = 11.sp,
                        color = DischargingOrange
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // 双色进度条
                LinearProgressIndicator(
                    progress = { barRatio },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = ChargingGreen,
                    trackColor = DischargingOrange,
                )

                Spacer(modifier = Modifier.height(6.dp))

                val description = when {
                    ratio > 2 -> "充电速度远超耗电速度，电池效率优秀"
                    ratio > 1.2 -> "充电稍快于耗电，属于正常状态"
                    ratio in 0.8..1.2 -> "充放电速率基本持平"
                    ratio > 0.5 -> "耗电速度略快于充电速度"
                    else -> "耗电速度明显快于充电，可能需要关注"
                }
                Text(
                    "比值: ${String.format("%.2f", ratio)} | $description",
                    fontSize = 12.sp,
                    color = colors.textSecondary
                )
            }
        }
    }
}

/**
 * 单次功率变化行
 */
@Composable
private fun PowerChangeRow(change: PowerChange, capacityMah: Int) {
    val colors = LocalAppColors.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 方向指示器
            Surface(
                color = if (change.isCharging) ChargingGreen.copy(alpha = 0.15f)
                else DischargingOrange.copy(alpha = 0.15f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    if (change.isCharging) "▲" else "▼",
                    color = if (change.isCharging) ChargingGreen else DischargingOrange,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                // 电量变化区间
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${change.fromLevel}% → ${change.toLevel}%",
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "(${if (change.changePercent > 0) "+" else ""}${change.changePercent}%)",
                        fontSize = 12.sp,
                        color = colors.textSecondary
                    )
                }
                // 时间
                Text(
                    timeFormat.format(Date(change.timestamp)),
                    fontSize = 11.sp,
                    color = colors.textSecondary
                )
            }

            // 右侧速率
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    String.format("%.1f", change.rateMahPerHour),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = if (change.isCharging) ChargingGreen else DischargingOrange
                )
                Text(
                    "mAh/h",
                    fontSize = 10.sp,
                    color = colors.textSecondary
                )
                Text(
                    String.format("%.0f秒", change.durationSeconds),
                    fontSize = 10.sp,
                    color = colors.textSecondary
                )
            }
        }
    }
}

/**
 * 信息行 - 用于功耗卡片内部
 */
@Composable
private fun InfoRow(label: String, value: String) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 11.sp, color = colors.textSecondary)
        Text(value, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary)
    }
}

// ==================== 高灵敏度电量曲线图 ====================

/**
 * 自定义高灵敏度电量曲线 - 1%粒度，带充电/耗电色彩区分
 * 每1%变化都精确绘制
 */
@Composable
fun PowerLineChart(
    records: List<BatteryRecord>,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    if (records.size < 2) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("数据不足，需要至少2条记录", color = colors.textSecondary, fontSize = 12.sp)
        }
        return
    }

    Canvas(modifier = modifier.padding(start = 40.dp, end = 12.dp, top = 8.dp, bottom = 30.dp)) {
        val width = size.width
        val height = size.height
        val chartLeft = 0f
        val chartRight = width
        val chartTop = 0f
        val chartBottom = height

        // 网格线
        for (i in 0..4) {
            val y = chartTop + (chartBottom - chartTop) * i / 4
            val label = "${100 - i * 25}%"
            drawLine(
                color = Color.LightGray.copy(alpha = 0.25f),
                start = Offset(chartLeft, y),
                end = Offset(chartRight, y),
                strokeWidth = 1f
            )
            drawContext.canvas.nativeCanvas.drawText(
                label, -35f, y + 5f,
                android.graphics.Paint().apply {
                    textSize = 20f
                    this.color = android.graphics.Color.GRAY
                    textAlign = android.graphics.Paint.Align.LEFT
                }
            )
        }

        // 绘制充电/耗电区域
        val totalPoints = records.size - 1
        records.zipWithNext().forEachIndexed { index, (prev, curr) ->
            val startX = chartLeft + (chartRight - chartLeft) * index / totalPoints
            val endX = chartLeft + (chartRight - chartLeft) * (index + 1) / totalPoints
            val isChargingSegment = curr.batteryLevel > prev.batteryLevel

            drawRect(
                color = if (isChargingSegment) ChargingGreen.copy(alpha = 0.12f)
                else DischargingOrange.copy(alpha = 0.08f),
                topLeft = Offset(startX, chartTop),
                size = Size(endX - startX, chartBottom - chartTop)
            )
        }

        // 折线路径
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
                    PrimaryBlue.copy(alpha = 0.25f),
                    PrimaryBlue.copy(alpha = 0.02f)
                )
            )
        )

        // 平滑曲线
        val linePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.forEachIndexed { i, point ->
                if (i > 0) lineTo(point.x, point.y)
            }
        }
        drawPath(
            path = linePath,
            color = PrimaryBlue,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round)
        )

        // 数据点 - 只标注充电和耗电的不同颜色
        points.forEachIndexed { index, point ->
            if (index < records.size) {
                val isChargingPoint = records[index].isCharging
                val dotColor = if (isChargingPoint) ChargingGreen else PrimaryBlue
                drawCircle(
                    color = dotColor,
                    radius = 4f,
                    center = point
                )
            }
        }

        // X轴时间标签
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        val labelStep = (records.size / 5).coerceAtLeast(1)
        records.forEachIndexed { index, record ->
            if (index % labelStep == 0 || index == records.lastIndex) {
                val label = timeFormat.format(Date(record.timestamp))
                val x = chartLeft + (chartRight - chartLeft) * index / (records.size - 1).coerceAtLeast(1)
                drawContext.canvas.nativeCanvas.drawText(
                    label, x - 25f, chartBottom + 22f,
                    android.graphics.Paint().apply {
                        textSize = 18f
                        this.color = android.graphics.Color.GRAY
                        textAlign = android.graphics.Paint.Align.LEFT
                    }
                )
            }
        }

        // 图例
        val legendY = chartTop + 8f
        // 充电
        drawCircle(ChargingGreen, 5f, Offset(chartRight - 90f, legendY))
        drawContext.canvas.nativeCanvas.drawText(
            "充电", chartRight - 82f, legendY + 5f,
            android.graphics.Paint().apply {
                textSize = 16f
                this.color = android.graphics.Color.GRAY
                textAlign = android.graphics.Paint.Align.LEFT
            }
        )
        // 耗电
        drawCircle(PrimaryBlue, 5f, Offset(chartRight - 40f, legendY))
        drawContext.canvas.nativeCanvas.drawText(
            "耗电", chartRight - 32f, legendY + 5f,
            android.graphics.Paint().apply {
                textSize = 16f
                this.color = android.graphics.Color.GRAY
                textAlign = android.graphics.Paint.Align.LEFT
            }
        )
        // 充电区域色带
        drawRect(
            ChargingGreen.copy(alpha = 0.12f),
            Offset(chartRight - 90f, legendY + 12f),
            Size(14f, 4f)
        )
        drawRect(
            DischargingOrange.copy(alpha = 0.08f),
            Offset(chartRight - 40f, legendY + 12f),
            Size(14f, 4f)
        )
    }
}
