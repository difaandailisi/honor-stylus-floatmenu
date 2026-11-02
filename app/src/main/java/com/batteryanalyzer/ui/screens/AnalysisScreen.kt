package com.batteryanalyzer.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batteryanalyzer.LocalAppColors
import com.batteryanalyzer.ble.ConnectionState
import com.batteryanalyzer.data.BatteryRepository
import com.batteryanalyzer.model.*
import com.batteryanalyzer.ui.components.BatteryRingChart
import com.batteryanalyzer.ui.components.SessionRow
import com.batteryanalyzer.ui.components.TimeCurveChart
import com.batteryanalyzer.ui.theme.*
import com.batteryanalyzer.viewmodel.MainViewModel
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.*

/**
 * 长时电量分析界面 —— 【计入电池健康度评估】
 *
 * 核心策略：
 * - 仅分析完整充放电循环（0→100%充电 / 100→0%放电）
 * - 基于完整循环计算实际容量和健康度
 * - 与短时功耗分析（仅作灵敏参考）完全独立
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val colors = LocalAppColors.current
    val currentDevice by viewModel.currentDevice.collectAsState()
    val batteryHealth by viewModel.batteryHealth.collectAsState()
    val healthResult by viewModel.healthResult.collectAsState()
    val batteryLevel by viewModel.currentBatteryLevel.collectAsState()
    val isCharging by viewModel.isCharging.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val connectedMac by viewModel.connectedDeviceMac.collectAsState()
    val longTermAnalysis by viewModel.longTermAnalysis.collectAsState()
    val completeChargeCycles by viewModel.completeChargeCycles.collectAsState()
    val completeDischargeCycles by viewModel.completeDischargeCycles.collectAsState()
    val chargingSessions by viewModel.chargingSessions.collectAsState()
    val dischargeSessions by viewModel.dischargeSessions.collectAsState()
    val allDevices by viewModel.allDevices.collectAsState()
    val selectedDeviceId by viewModel.selectedDeviceId.collectAsState()

    // 时间维度曲线
    val curvePoints by viewModel.curvePoints.collectAsState()
    val curveLoading by viewModel.curveLoading.collectAsState()
    val timeDimension by viewModel.timeDimension.collectAsState()

    var showDeviceSelector by remember { mutableStateOf(false) }
    var showHealthInfoDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("电量分析") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.refreshLongTermAnalysis()
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
        if (showDeviceSelector) {
            AlertDialog(
                onDismissRequest = { showDeviceSelector = false },
                title = { Text("选择设备") },
                text = {
                    LazyColumn {
                        items(allDevices) { device ->
                            val deviceConnected = connectionState == ConnectionState.CONNECTED && connectedMac == device.macAddress
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
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
                                    Text(if (deviceConnected) "已连接" else "未连接",
                                        fontSize = 12.sp, color = colors.textSecondary)
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

        // 电池健康度知识弹窗
        if (showHealthInfoDialog) {
            HealthInfoDialog(onDismiss = { showHealthInfoDialog = false })
        }

        if (currentDevice == null) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Analytics, contentDescription = null,
                        modifier = Modifier.size(64.dp), tint = colors.textSecondary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("请先选择设备", color = colors.textSecondary, fontSize = 16.sp)
                }
            }
            return@Scaffold
        }

        val device = currentDevice!!

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // === 使用须知：请勿在使用过程中充电 ===
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = DischargingOrange.copy(alpha = 0.08f)
                    )
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Warning, contentDescription = null,
                            tint = DischargingOrange, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("⚠️ 长时分析使用须知",
                                fontWeight = FontWeight.Bold, fontSize = 13.sp, color = DischargingOrange)
                            Text("请勿在手写笔使用过程中充电！\n" +
                                 "• 充电时请将笔静置，确保完整0→100%充电循环\n" +
                                 "• 放电时请正常使用至0%，确保完整100→0%放电循环\n" +
                                 "• 只有完整的充放电循环才会被计入长时分析和健康度统计",
                                fontSize = 11.sp, color = colors.textSecondary, lineHeight = 17.sp)
                        }
                    }
                }
            }

            // === 策略说明横幅 ===
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = ChargingGreen.copy(alpha = 0.08f)
                    )
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null,
                            tint = ChargingGreen, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("长时电量分析 · 计入电池健康度",
                                fontWeight = FontWeight.Bold, fontSize = 13.sp, color = ChargingGreen)
                            Text("仅分析完整充放电循环（0→100%充电 / 100→0%放电）",
                                fontSize = 11.sp, color = colors.textSecondary)
                        }
                    }
                }
            }

            // ============ 电量趋势曲线 + 时间维度切换 ============
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
                            // 刷新按钮
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

                        // 时间维度切换 — 四个按钮
                        TimeDimensionSwitcher(
                            selected = timeDimension,
                            onSelect = { viewModel.setTimeDimension(it) }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // 曲线图表
                        TimeCurveChart(
                            points = curvePoints,
                            dimension = timeDimension,
                            isLoading = curveLoading,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // 曲线说明
                        Spacer(modifier = Modifier.height(6.dp))
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

            // === 实时电量卡片（状态与电量分离） ===
            item {
                val isConnected = connectionState == ConnectionState.CONNECTED
                val statusText = when {
                    !isConnected -> "离线"
                    isCharging -> "充电中"
                    batteryLevel in 0..100 -> "使用中"
                    else -> "获取中"
                }
                val statusColor = when {
                    !isConnected -> colors.textSecondary
                    isCharging -> ChargingGreen
                    else -> DischargingOrange
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        BatteryRingChart(level = batteryLevel.coerceIn(0, 100), isCharging = isCharging, size = 70, showStatusText = false)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(device.displayName, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = colors.textPrimary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (batteryLevel >= 0) "$batteryLevel%" else "--",
                                    fontWeight = FontWeight.Bold, fontSize = 20.sp,
                                    color = getBatteryColor(batteryLevel, isCharging)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = statusColor.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        statusText,
                                        color = statusColor,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // === 电池健康度（基于长时数据） ===
            item {
                LongTermHealthCard(
                    health = batteryHealth,
                    healthResult = healthResult,
                    longTermAnalysis = longTermAnalysis,
                    deviceName = device.displayName,
                    onHealthInfoClick = { showHealthInfoDialog = true }
                )
            }

            // === 完整充电循环列表 ===
            item {
                if (completeChargeCycles.isNotEmpty()) {
                    Text("完整充电循环 (${completeChargeCycles.size}次)", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            items(completeChargeCycles.takeLast(20)) { cycle ->
                CompleteCycleCard(cycle, isCharge = true)
            }
            if (completeChargeCycles.size > 20) {
                item {
                    Text("... 共 ${completeChargeCycles.size} 次完整充电循环，仅显示最近20次",
                        fontSize = 11.sp, color = colors.textSecondary, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
            }

            // === 完整放电循环列表 ===
            item {
                if (completeDischargeCycles.isNotEmpty()) {
                    Text("完整放电循环 (${completeDischargeCycles.size}次)", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
            items(completeDischargeCycles.takeLast(20)) { cycle ->
                CompleteCycleCard(cycle, isCharge = false)
            }
            if (completeDischargeCycles.size > 20) {
                item {
                    Text("... 共 ${completeDischargeCycles.size} 次完整放电循环，仅显示最近20次",
                        fontSize = 11.sp, color = colors.textSecondary, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
            }

            // === 健康度分析与建议 ===
            item {
                HealthAnalysisCard(
                    health = batteryHealth,
                    chargingSessions = chargingSessions,
                    dischargeSessions = dischargeSessions,
                    batteryCapacity = device.batteryCapacityMah
                )
            }

            // === 底部策略说明 ===
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = ChargingGreen.copy(alpha = 0.05f)),
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Info, contentDescription = null,
                                tint = ChargingGreen, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text("长时 vs 短时分析策略", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = ChargingGreen)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("• 长时分析（本页）：完整0-100充电/100-0放电解计入健康度\n" +
                                     "• 短时分析（功耗页）：仅作灵敏参考，不计入健康度计算\n" +
                                     "• 健康度仅基于完整充放电循环数据评估",
                                    fontSize = 10.sp, color = colors.textSecondary, lineHeight = 16.sp)
                            }
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

// ==================== 长时分析子组件 ====================

/**
 * 长时健康度卡片 —— 基于完整充放电循环数据
 */
@Composable
fun LongTermHealthCard(
    health: Float,
    healthResult: BatteryRepository.BatteryHealthResult?,
    longTermAnalysis: LongTermAnalysisResult?,
    deviceName: String,
    onHealthInfoClick: () -> Unit = {}
) {
    val colors = LocalAppColors.current
    val actualCapacity = healthResult?.actualCapacityMah ?: 0f
    val theoreticalCapacity = healthResult?.theoreticalCapacityMah ?: 80
    val cycleCount = healthResult?.estimatedCycleCount ?: 0f

    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                progress = { health / 100f },
                modifier = Modifier.size(80.dp),
                color = getHealthColor(health),
                strokeWidth = 8.dp,
                trackColor = colors.textSecondary.copy(alpha = 0.2f),
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text("电池健康度", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.textPrimary)
                Text("${String.format("%.1f", health)}% - ${getHealthText(health)}",
                    color = getHealthColor(health), fontWeight = FontWeight.Medium, fontSize = 16.sp)
                Text(deviceName, color = colors.textSecondary, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Text("了解电池健康度 >",
                    color = PrimaryBlue, fontSize = 11.sp,
                    modifier = Modifier.clickable { onHealthInfoClick() })
            }
        }
        if (healthResult != null) {
            HorizontalDivider(color = colors.textSecondary.copy(alpha = 0.1f))
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("实际容量", fontSize = 12.sp, color = colors.textSecondary)
                        Text("${String.format("%.1f", actualCapacity)} mAh", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            color = if (actualCapacity >= theoreticalCapacity * 0.8f) ChargingGreen else DischargingOrange)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("理论容量", fontSize = 12.sp, color = colors.textSecondary)
                        Text("$theoreticalCapacity mAh", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = PrimaryBlue)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                val capacityRatio = (actualCapacity / theoreticalCapacity.coerceAtLeast(1)).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { capacityRatio },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = getHealthColor(health), trackColor = colors.textSecondary.copy(alpha = 0.15f))
                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("衰减: ${String.format("%.1f", (theoreticalCapacity - actualCapacity).coerceAtLeast(0f))} mAh",
                        fontSize = 11.sp, color = colors.textSecondary)
                    Text("估算循环: ${String.format("%.1f", cycleCount)} 次",
                        fontSize = 11.sp, color = colors.textSecondary)
                }
                // 基于的数据来源说明
                Spacer(modifier = Modifier.height(4.dp))
                Text("基于 ${healthResult?.completeChargeCycles ?: 0} 次完整充电 + ${healthResult?.completeDischargeCycles ?: 0} 次完整放电解计算",
                    fontSize = 10.sp, color = ChargingGreen.copy(alpha = 0.7f))
            }
        }
    }
}

/**
 * 完整充放电循环卡片
 */
@Composable
private fun CompleteCycleCard(cycle: CompleteCycle, isCharge: Boolean) {
    val colors = LocalAppColors.current
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // 类型图标
            Surface(
                color = (if (isCharge) ChargingGreen else DischargingOrange).copy(alpha = 0.12f),
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    if (isCharge) "⚡充" else "🔋放",
                    color = if (isCharge) ChargingGreen else DischargingOrange,
                    fontWeight = FontWeight.Bold, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("${cycle.startLevel}% → ${cycle.endLevel}% (${cycle.coveragePercent}%范围)",
                    fontWeight = FontWeight.Medium, fontSize = 13.sp, color = colors.textPrimary)
                Text(dateFormat.format(Date(cycle.startTime)),
                    fontSize = 11.sp, color = colors.textSecondary)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(String.format("%.1f mAh", cycle.totalMah),
                    fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = if (isCharge) ChargingGreen else DischargingOrange)
                Text(String.format("%.0f min", cycle.durationMinutes.toFloat()),
                    fontSize = 10.sp, color = colors.textSecondary)
            }
        }
    }
}

/**
 * 健康度分析建议卡片
 */
@Composable
fun HealthAnalysisCard(
    health: Float,
    chargingSessions: List<ChargingSession>,
    dischargeSessions: List<DischargeSession>,
    batteryCapacity: Int
) {
    val colors = LocalAppColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("健康度评估与建议 (基于长时完整循环)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.height(12.dp))

            val analysis = buildString {
                when {
                    health >= 90 -> {
                        append("✅ 电池状态优秀，标称容量 $batteryCapacity mAh\n")
                        append("电池几乎无衰减，完整充放电循环正常")
                    }
                    health >= 80 -> {
                        append("🟢 电池状态良好\n")
                        append("轻微容量衰减，不影响日常使用")
                    }
                    health >= 70 -> {
                        append("🟡 电池状态一般\n")
                        append("容量有所下降，建议避免过度充放电")
                    }
                    health >= 60 -> {
                        append("🟠 电池状态较差\n")
                        append("容量明显下降，使用时间缩短，建议关注更换")
                    }
                    else -> {
                        append("🔴 电池状态不佳\n")
                        append("容量严重下降，建议更换电池或手写笔")
                    }
                }
                if (chargingSessions.isNotEmpty()) {
                    val avgChargeLevel = chargingSessions.map { it.startLevel }.average()
                    append("\n\n")
                    if (avgChargeLevel < 20) append("⚠️ 经常在电量很低时才充电，可能加速老化。")
                    else if (avgChargeLevel > 50) append("👍 充电习惯良好！随用随充有助于延长寿命。")
                    else append("💡 建议保持随用随充的习惯。")
                }
                // 提醒不要在充电时使用
                append("\n\n📌 注意：使用过程中充电会导致数据不准，" +
                       "请充电时静置设备，确保完整循环记录。")
            }
            Text(text = analysis, fontSize = 13.sp, color = colors.textPrimary, lineHeight = 20.sp)
        }
    }
}

/**
 * 电池健康度知识介绍弹窗
 */
@Composable
fun HealthInfoDialog(onDismiss: () -> Unit) {
    val colors = LocalAppColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null,
                    tint = PrimaryBlue, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("电池健康度说明", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("什么是电池健康度？", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = colors.textPrimary)
                Text("电池健康度反映电池当前实际容量相对于出厂标称容量的比例。" +
                     "随着充放电循环次数增加，电池会逐渐老化，容量下降。",
                    fontSize = 12.sp, color = colors.textSecondary, lineHeight = 18.sp)

                Text("健康度如何计算？", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = colors.textPrimary)
                Text("本应用通过长期监控完整充放电循环（0→100%充电 和 100→0%放电）来估算实际容量：" +
                     "\n• 记录每次完整的0-100%充电过程消耗的电量(mAh)" +
                     "\n• 记录每次完整的100-0%放电过程放出的电量(mAh)" +
                     "\n• 取最近10次循环的加权平均值作为实际容量" +
                     "\n• 健康度 = 实际容量 ÷ 标称容量 × 100%",
                    fontSize = 12.sp, color = colors.textSecondary, lineHeight = 18.sp)

                Text("健康度等级说明", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = colors.textPrimary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = ChargingGreen, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.size(10.dp)) {}
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("优秀 (≥90%)：电池状态极佳，几乎无衰减", fontSize = 12.sp, color = ChargingGreen)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = getHealthColor(80f), shape = MaterialTheme.shapes.small,
                        modifier = Modifier.size(10.dp)) {}
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("良好 (80-89%)：轻微衰减，不影响日常使用", fontSize = 12.sp, color = getHealthColor(80f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = getHealthColor(70f), shape = MaterialTheme.shapes.small,
                        modifier = Modifier.size(10.dp)) {}
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("一般 (70-79%)：容量有所下降，建议关注", fontSize = 12.sp, color = getHealthColor(70f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = getHealthColor(60f), shape = MaterialTheme.shapes.small,
                        modifier = Modifier.size(10.dp)) {}
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("较差 (60-69%)：容量明显下降，建议准备更换", fontSize = 12.sp, color = getHealthColor(60f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = LowBatteryRed, shape = MaterialTheme.shapes.small,
                        modifier = Modifier.size(10.dp)) {}
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("需更换 (<60%)：容量严重不足，建议更换电池", fontSize = 12.sp, color = LowBatteryRed)
                }

                Text("如何获得准确的健康度数据？", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = colors.textPrimary)
                Text("1. 请勿在使用过程中充电！充电时将笔静置放置\n" +
                     "2. 让设备完成至少一次完整的 0→100% 充电循环\n" +
                     "3. 让设备完成至少一次完整的 100→0% 放电循环\n" +
                     "4. 持续监控以获得更准确的长期健康度评估\n" +
                     "5. 短时功耗分析仅作参考，不计入健康度计算",
                    fontSize = 12.sp, color = colors.textSecondary, lineHeight = 18.sp)

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = DischargingOrange.copy(alpha = 0.06f)
                    )
                ) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Warning, contentDescription = null,
                            tint = DischargingOrange, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("重要提醒：长时间边充电边使用（如在充电时书写）会导致电量数据不准确，" +
                             "破坏完整的充放电循环记录，从而影响健康度评估的准确性。",
                            fontSize = 11.sp, color = DischargingOrange, lineHeight = 16.sp)
                    }
                }

                Text("锂电池保养建议", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    color = colors.textPrimary)
                Text("• 避免电量长期低于20%再充电\n" +
                     "• 避免长时间100%满电存放\n" +
                     "• 建议保持电量在20%-80%之间\n" +
                     "• 每月至少进行一次完整充放电循环\n" +
                     "• 避免在极端温度环境下使用或充电",
                    fontSize = 12.sp, color = colors.textSecondary, lineHeight = 18.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("我知道了")
            }
        }
    )
}

/**
 * 时间维度切换器 — 四个圆形切换按钮
 */
@Composable
fun TimeDimensionSwitcher(
    selected: TimeDimension,
    onSelect: (TimeDimension) -> Unit
) {
    val colors = LocalAppColors.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        TimeDimension.all().forEach { dim ->
            val isSelected = selected == dim
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(dim) },
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = when (dim) {
                                TimeDimension.MINUTES -> Icons.Default.Schedule
                                TimeDimension.DAYS -> Icons.Default.CalendarToday
                                TimeDimension.MONTHS -> Icons.Default.DateRange
                                TimeDimension.YEARS -> Icons.Default.Timeline
                            },
                            contentDescription = dim.label,
                            tint = if (isSelected) Color.White else colors.textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            dim.label,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) Color.White else colors.textSecondary
                        )
                    }
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = PrimaryBlue,
                    selectedLabelColor = Color.White
                ),
                modifier = Modifier.weight(1f)
            )
        }
    }
}
