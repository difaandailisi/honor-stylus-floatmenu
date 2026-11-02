package com.batteryanalyzer.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.batteryanalyzer.LocalAppColors
import com.batteryanalyzer.ble.ConnectionState
import com.batteryanalyzer.model.BatteryChannel
import com.batteryanalyzer.model.DeviceInfo
import com.batteryanalyzer.service.BatteryMonitorService
import com.batteryanalyzer.ui.components.BatteryRingChart
import com.batteryanalyzer.ui.components.StatsCard
import com.batteryanalyzer.ui.theme.*
import com.batteryanalyzer.viewmodel.MainViewModel
import com.batteryanalyzer.viewmodel.QuickFetchResult
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

/**
 * 仪表盘主界面 - 支持多设备切换、显示连接/未连接状态
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: MainViewModel, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    // v6.1: 仪表盘只显示 BLE 蓝牙设备，屏蔽虚拟输入设备（Button Jack/pmic 等）
    val rawDevices by viewModel.allDevices.collectAsState()
    val devices = rawDevices.filter { it.isBleDevice }
    val batteryLevel by viewModel.currentBatteryLevel.collectAsState()
    val isCharging by viewModel.isCharging.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val currentDevice by viewModel.currentDevice.collectAsState()
    val batteryHealth by viewModel.batteryHealth.collectAsState()
    val chargingSessions by viewModel.chargingSessions.collectAsState()
    val records by viewModel.batteryRecords.collectAsState()
    val selectedDeviceId by viewModel.selectedDeviceId.collectAsState()

    val multiBatteryLevels by viewModel.multiBatteryLevels.collectAsState()
    val availableChannels by viewModel.availableChannels.collectAsState()
    val connectedMac by viewModel.connectedDeviceMac.collectAsState()

    // v7: 标称容量修改弹窗状态
    var showCapacityDialog by remember { mutableStateOf(false) }
    var capacityInput by remember { mutableStateOf("") }

    val isConnected = connectionState == ConnectionState.CONNECTED

    // 快速获取电量状态
    val quickFetching by viewModel.quickFetching.collectAsState()
    val quickFetchResult by viewModel.quickFetchResult.collectAsState()

    // 通知权限
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(context, "通知权限被拒绝，将无法收到电量提醒", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val hasNotification = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotification) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("蓝牙电量分析", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryBlue,
                    titleContentColor = androidx.compose.ui.graphics.Color.White
                ),
                actions = {
                    IconButton(onClick = { onNavigate("settings") }) {
                        Icon(Icons.Default.Settings, "设置", tint = androidx.compose.ui.graphics.Color.White)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 连接状态卡片 / 设备信息展示
            item {
                if (currentDevice != null) {
                    val device = currentDevice!!
                    // 有选中设备：始终展示设备卡片（已连接或离线均可查看历史数据）
                    ConnectedDeviceCard(
                        deviceName = device.displayName,
                        macAddress = device.macAddress,
                        batteryLevel = batteryLevel,
                        isCharging = isCharging,
                        isConnected = isConnected,
                        batteryHealth = batteryHealth,
                        multiBatteryLevels = multiBatteryLevels,
                        availableChannels = availableChannels,
                        isQuickFetching = quickFetching,
                        quickFetchResult = quickFetchResult,
                        onClick = { onNavigate("power") },
                        onConnect = { viewModel.connectToSavedDevice(device.id) },
                        onDisconnect = { viewModel.disconnectCurrentDevice() },
                        onQuickFetch = { viewModel.fetchBatteryFromBondedDevice(device.id) },
                        onManualReadBattery = { viewModel.manualReadBatteryLevel() }
                    )
                } else {
                    // 无任何设备：引导扫描
                    DisconnectedCard(onScanClick = { onNavigate("scan") })
                }
            }

            // 系统读数交叉校验卡片（逆向增强：荣耀系统 Settings.Global honor_bt_pencil_*）
            if (currentDevice != null) {
                item {
                    SystemReadingCard(
                        localLevel = batteryLevel,
                        localCharging = isCharging
                    )
                }
            }

            // 统计卡片 (基于选中设备)
            if (currentDevice != null) {
                val device = currentDevice!!
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            StatsCard(
                                title = "标称容量",
                                value = "${device.batteryCapacityMah}mAh",
                                subtitle = "${device.displayName.ifEmpty { device.modelName }}${if (device.isCapacityUserSet) " · 手动" else ""}",
                                modifier = Modifier.fillMaxWidth()
                            )
                            IconButton(
                                onClick = {
                                    capacityInput = device.batteryCapacityMah.toString()
                                    showCapacityDialog = true
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(y = (-6).dp)
                                    .size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "修改标称容量",
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        StatsCard(
                            title = "电池健康度",
                            value = "${String.format("%.1f", batteryHealth)}%",
                            subtitle = getHealthText(batteryHealth),
                            valueColor = getHealthColor(batteryHealth),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StatsCard(
                            title = "充电次数",
                            value = "${chargingSessions.size}",
                            subtitle = "历史充电",
                            valueColor = ChargingGreen,
                            modifier = Modifier.weight(1f)
                        )
                        StatsCard(
                            title = "数据记录",
                            value = "${records.size}",
                            subtitle = "条记录",
                            valueColor = PrimaryBlueLight,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 快捷操作
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("快捷操作", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary)
                    // 策略说明
                    Text("长时→健康度  |  短时→参考",
                        fontSize = 10.sp, color = colors.textSecondary.copy(alpha = 0.7f))
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionCard(Icons.Default.BluetoothSearching, "扫描设备",
                        subtitle = "搜索蓝牙",
                        onClick = { onNavigate("scan") }, modifier = Modifier.weight(1f))
                    ActionCard(Icons.Default.Analytics, "长时分析",
                        subtitle = "计入健康度",
                        onClick = { onNavigate("analysis") },
                        modifier = Modifier.weight(1f), enabled = currentDevice != null)
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionCard(Icons.Default.Bolt, "短时功耗",
                        subtitle = "仅作参考",
                        onClick = { onNavigate("power") },
                        modifier = Modifier.weight(1f), enabled = currentDevice != null)
                    ActionCard(Icons.Default.Keyboard, "键值映射",
                        subtitle = "自定义按键",
                        onClick = { onNavigate("keymapping") },
                        modifier = Modifier.weight(1f))
                }
            }

            // 已保存设备列表（含未连接状态）
            if (devices.isNotEmpty()) {
                item {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("已保存设备 (${devices.size})", fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
                            Text("点击连接", fontSize = 12.sp, color = colors.textSecondary)
                        }
                    }
                }
                items(devices) { device ->
                    SavedDeviceCard(
                        device = device,
                        isSelected = device.id == selectedDeviceId,
                        isConnected = isConnected && connectedMac == device.macAddress,
                        isQuickFetching = quickFetching,
                        onClick = {
                            if (!isConnected || device.id != selectedDeviceId) {
                                // 未连接或不是当前设备 → 尝试连接
                                viewModel.connectToSavedDevice(device.id)
                                Toast.makeText(context, "正在连接 ${device.name}...", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onQuickFetch = { viewModel.fetchBatteryFromBondedDevice(device.id) },
                        onDisconnect = { viewModel.disconnectCurrentDevice() },
                        onDelete = {
                            if (devices.size > 1) viewModel.deleteDevice(device.id)
                            else Toast.makeText(context, "至少保留一个设备", Toast.LENGTH_SHORT).show()
                        },
                        onUnpair = { viewModel.unpairDevice(device) },
                        onTogglePen = { viewModel.setHandwritingPen(device.id, it) }
                    )
                }
            }
        }

        // 修改标称容量弹窗（v7: 用户手动设置电池容量）
        if (showCapacityDialog && currentDevice != null) {
            val target = currentDevice!!
            AlertDialog(
                onDismissRequest = { showCapacityDialog = false },
                confirmButton = {
                    TextButton(onClick = {
                        val v = capacityInput.toIntOrNull()
                        if (v != null && v > 0) {
                            viewModel.updateDeviceCapacity(target.id, v.coerceIn(1, 10000))
                        }
                        showCapacityDialog = false
                    }) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { showCapacityDialog = false }) { Text("取消") }
                },
                title = { Text("修改标称容量") },
                text = {
                    Column {
                        Text(
                            "设备：${target.displayName}",
                            fontSize = 12.sp, color = colors.textSecondary
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = capacityInput,
                            onValueChange = { capacityInput = it.filter { c -> c.isDigit() }.take(6) },
                            label = { Text("容量 (mAh)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "不修改则保留自动识别值（华为/荣耀笔 82mAh，其他手写笔 90mAh）",
                            fontSize = 11.sp, color = colors.textSecondary
                        )
                    }
                }
            )
        }
    }
}

// ============ 设备信息卡片（已连接 / 离线查看） ============
@Composable
fun ConnectedDeviceCard(
    deviceName: String,
    macAddress: String,
    batteryLevel: Int,
    isCharging: Boolean,
    isConnected: Boolean,
    batteryHealth: Float,
    multiBatteryLevels: Map<BatteryChannel, Int> = emptyMap(),
    availableChannels: List<BatteryChannel> = emptyList(),
    isQuickFetching: Boolean = false,
    quickFetchResult: com.batteryanalyzer.viewmodel.QuickFetchResult? = null,
    onClick: () -> Unit,
    onConnect: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onQuickFetch: () -> Unit = {},
    onManualReadBattery: () -> Unit = {}
) {
    val colors = LocalAppColors.current

    // 【修复】判断是否已获取到有效电量数据（-1 表示尚未读取）
    val hasValidBattery = batteryLevel >= 0
    val validMultiLevels = multiBatteryLevels.filter { it.value >= 0 }
    val hasMultiChannel = validMultiLevels.size > 1 ||
            (validMultiLevels.size == 1 && validMultiLevels.keys.first() != BatteryChannel.SINGLE)

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (hasMultiChannel && hasValidBattery) {
                // === 多通道布局：左中右三个环形图 ===
                Text(
                    "多通道电量",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val displayChannels = listOf(BatteryChannel.LEFT, BatteryChannel.RIGHT, BatteryChannel.CASE)
                    displayChannels.forEach { channel ->
                        if (channel in validMultiLevels.keys) {
                            val level = validMultiLevels[channel] ?: 0
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(BatteryChannel.icon(channel), fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                BatteryRingChart(level = level, isCharging = isCharging, size = 70)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "$level%",
                                    fontSize = 11.sp,
                                    color = colors.textSecondary
                                )
                                Text(
                                    BatteryChannel.displayName(channel),
                                    fontSize = 10.sp,
                                    color = colors.textSecondary.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = colors.textSecondary.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(8.dp))
            }

            // === 设备信息行 ===
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!hasMultiChannel || !hasValidBattery) {
                    if (hasValidBattery) {
                        BatteryRingChart(
                            level = batteryLevel.coerceIn(0, 100),
                            isCharging = isCharging,
                            size = 100
                        )
                    } else {
                        // 电量未获取时显示加载中或离线状态
                        Box(
                            modifier = Modifier.size(100.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("--", fontWeight = FontWeight.Bold, fontSize = 24.sp, color = colors.textSecondary)
                                Text(
                                    if (isConnected) "获取中" else "离线",
                                    fontSize = 10.sp,
                                    color = colors.textSecondary.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(deviceName, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.textPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = if (isConnected) ChargingGreen.copy(alpha = 0.12f) else LowBatteryRed.copy(alpha = 0.1f),
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                if (isConnected) "已连接" else "未连接",
                                color = if (isConnected) ChargingGreen else LowBatteryRed,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        "MAC: $macAddress",
                        fontSize = 11.sp,
                        color = colors.textSecondary.copy(alpha = 0.7f),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    Text(
                        if (isConnected) {
                            if (hasValidBattery) {
                                if (isCharging) "充电中" else "使用中"
                            } else "正在读取电量..."
                        } else {
                            "离线 - 可查看历史数据"
                        },
                        color = if (isConnected && hasValidBattery && isCharging) ChargingGreen else colors.textSecondary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("健康度: ${getHealthText(batteryHealth)} (${String.format("%.0f", batteryHealth)}%)",
                        color = getHealthColor(batteryHealth), fontSize = 13.sp)
                    Text("点击查看详情 >", color = PrimaryBlue, fontSize = 12.sp)
                }
                if (hasMultiChannel && hasValidBattery) {
                    // 显示综合电量
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${batteryLevel.coerceIn(0, 100)}%",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = getBatteryColor(batteryLevel, isCharging)
                        )
                        Text("综合", fontSize = 10.sp, color = colors.textSecondary)
                    }
                }
            }
            if (!hasMultiChannel || !hasValidBattery) {
                Spacer(modifier = Modifier.height(8.dp))
            }
            // 快速获取电量结果提示
            if (!isConnected && quickFetchResult != null && quickFetchResult.macAddress == macAddress) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = if (quickFetchResult.error != null)
                        LowBatteryRed.copy(alpha = 0.08f) else
                        ChargingGreen.copy(alpha = 0.08f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (quickFetchResult.error != null) Icons.Default.Warning else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (quickFetchResult.error != null) LowBatteryRed else ChargingGreen,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (quickFetchResult.error != null) quickFetchResult.error!!
                            else "电量获取成功: ${quickFetchResult.batteryLevel}%",
                            fontSize = 12.sp,
                            color = if (quickFetchResult.error != null) LowBatteryRed else ChargingGreen
                        )
                    }
                }
            }

            // 连接/断开按钮
            if (isConnected) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 手动获取电量按钮（自动获取失败时补充使用）
                    OutlinedButton(
                        onClick = onManualReadBattery,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryBlue)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("获取电量", fontSize = 13.sp)
                    }
                    // 断开连接按钮
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = LowBatteryRed)
                    ) {
                        Icon(Icons.Default.BluetoothDisabled, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("断开连接", fontSize = 13.sp)
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 获取电量按钮 - 从系统已配对设备快速读取
                    OutlinedButton(
                        onClick = onQuickFetch,
                        modifier = Modifier.weight(1f),
                        enabled = !isQuickFetching
                    ) {
                        if (isQuickFetching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = PrimaryBlue
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("获取中...", fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("获取电量", fontSize = 13.sp)
                        }
                    }
                    // 连接设备按钮
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                    ) {
                        Icon(Icons.Default.BluetoothConnected, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("连接设备", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ============ 未连接提示卡片 ============
@Composable
fun DisconnectedCard(onScanClick: () -> Unit) {
    val colors = LocalAppColors.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onScanClick() },
        colors = CardDefaults.cardColors(containerColor = colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.BluetoothDisabled, contentDescription = null,
                modifier = Modifier.size(48.dp), tint = colors.textSecondary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("未连接设备", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colors.textPrimary)
            Text("点击扫描并连接蓝牙设备", color = colors.textSecondary, fontSize = 14.sp)
        }
    }
}

// ============ 已保存设备卡片（含未连接状态和切换功能） ============
@Composable
fun SavedDeviceCard(
    device: DeviceInfo,
    isSelected: Boolean,
    isConnected: Boolean,
    isQuickFetching: Boolean = false,
    onClick: () -> Unit,
    onQuickFetch: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onDelete: () -> Unit,
    onUnpair: () -> Unit = {},
    onTogglePen: (Boolean) -> Unit = {}
) {
    val colors = LocalAppColors.current
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = colors.card
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 3.dp else 1.dp)
    ) {
        Row(modifier = Modifier) {
            // 选中状态左侧指示条
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .defaultMinSize(minHeight = 60.dp)
                        .background(PrimaryBlue)
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 设备图标 - 选中时高亮
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = if (isSelected) PrimaryBlue.copy(alpha = 0.12f) else colors.textSecondary.copy(alpha = 0.06f),
                            shape = RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val icon = when {
                        device.displayName.contains("Pen", ignoreCase = true) ||
                        device.displayName.contains("笔", ignoreCase = true) ||
                        device.displayName.contains("M-Pencil", ignoreCase = true) -> Icons.Default.Edit
                        device.displayName.contains("Buds", ignoreCase = true) ||
                        device.displayName.contains("耳机", ignoreCase = true) ||
                        device.displayName.contains("AirPods", ignoreCase = true) -> Icons.Default.Headphones
                        else -> Icons.Default.DevicesOther
                    }
                    Icon(icon, contentDescription = null,
                        tint = if (isSelected) PrimaryBlue else colors.textSecondary.copy(alpha = 0.7f),
                        modifier = Modifier.size(22.dp))
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            device.displayName,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) PrimaryBlue else colors.textPrimary,
                            fontSize = 15.sp
                        )
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = PrimaryBlue,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text("当前", color = androidx.compose.ui.graphics.Color.White,
                                    fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp))
                            }
                        }
                    }
                    Text(
                        "MAC: ${device.macAddress}",
                        fontSize = 10.sp,
                        color = colors.textSecondary.copy(alpha = 0.7f),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${device.batteryCapacityMah}mAh",
                            fontSize = 12.sp, color = colors.textSecondary
                        )
                        Text(" · ", fontSize = 12.sp, color = colors.textSecondary.copy(alpha = 0.4f))
                        Text(
                            dateFormat.format(Date(device.lastSeenTime)),
                            fontSize = 12.sp, color = colors.textSecondary
                        )
                    }
                    if (!isConnected) {
                        Text("点击连接此设备",
                            fontSize = 11.sp, color = PrimaryBlue.copy(alpha = 0.8f))
                    }
                    // v8: 手写笔标记开关（仅 BLE 设备显示，决定备份/压缩/非破坏取消配对范围）
                    if (device.isBleDevice) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Switch(
                                checked = device.isHandwritingPen,
                                onCheckedChange = onTogglePen
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (device.isHandwritingPen) "手写笔 · 已标记" else "标记为手写笔",
                                fontSize = 11.sp, color = colors.textSecondary
                            )
                        }
                    }
                }

                // 连接状态标签
                Surface(
                    color = if (isConnected) ChargingGreen.copy(alpha = 0.12f) else colors.textSecondary.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        if (isConnected) "● 已连接" else "○ 未连接",
                        color = if (isConnected) ChargingGreen else colors.textSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (isConnected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            // 右侧操作按钮列
            Column(
                modifier = Modifier.padding(end = 4.dp).padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 主操作：已连接→断开连接；未连接→读取电量（原"刷新"，语义改为读电量）
                if (isConnected) {
                    IconButton(onClick = onDisconnect, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.BluetoothDisabled,
                            contentDescription = "断开连接",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = onQuickFetch,
                        enabled = !isQuickFetching,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "读取电量",
                            tint = if (isQuickFetching) colors.textSecondary.copy(alpha = 0.3f) else PrimaryBlue,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // 更多菜单：收起"取消系统配对"与"删除设备"，避免误触
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "更多操作",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        if (device.isBleDevice) {
                            DropdownMenuItem(
                                onClick = { showMenu = false; onUnpair() },
                                leadingIcon = {
                                    Icon(Icons.Default.LinkOff, null,
                                        tint = colors.textSecondary, modifier = Modifier.size(20.dp))
                                },
                                text = { Text("取消系统配对", color = colors.textPrimary, fontSize = 14.sp) }
                            )
                        }
                        DropdownMenuItem(
                            onClick = { showMenu = false; showDeleteConfirm = true },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, null,
                                    tint = CriticalBatteryRed, modifier = Modifier.size(20.dp))
                            },
                            text = { Text("删除设备", color = CriticalBatteryRed, fontSize = 14.sp) }
                        )
                    }
                }
            }

            // 删除二次确认弹窗（破坏性操作防护）
            if (showDeleteConfirm) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirm = false },
                    confirmButton = {
                        TextButton(onClick = {
                            showDeleteConfirm = false
                            onDelete()
                        }) { Text("删除", color = CriticalBatteryRed) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
                    },
                    title = { Text("删除设备？") },
                    text = {
                        Text(
                            "将删除「${device.displayName}」及其全部电量记录，此操作不可恢复" +
                                "（除非此前已通过备份导出）。系统配对将一并取消。",
                            fontSize = 14.sp, color = colors.textSecondary
                        )
                    }
                )
            }
        }
    }
}

/**
 * 【逆向增强】系统读数交叉校验卡片
 *
 * 数据源：荣耀系统蓝牙包写入 `Settings.Global` 的 `honor_bt_pencil_*` 键
 * （逆向依据见 `代码逆向/README.md` §10、`代码逆向/全逻辑闭环.md` §2.2）。
 *
 * 展示「系统读数 vs 本机 BLE 读数」，差异超过阈值时高亮提示，
 * 便于发现自研 0xFE42 / 0x180F 协议解析偏差。
 */
@Composable
private fun SystemReadingCard(localLevel: Int, localCharging: Boolean) {
    val systemState by BatteryMonitorService.systemPenState.collectAsState()
    if (!systemState.available) return

    val colors = LocalAppColors.current
    val sysLevel = systemState.batteryLevel
    val divergence = if (localLevel in 0..100 && systemState.hasValidBattery) {
        abs(localLevel - sysLevel)
    } else null
    val hasDivergence = divergence != null && divergence > 5

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "系统读数交叉校验",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                ReadingItem(
                    label = "本机 BLE",
                    value = if (localLevel in 0..100) "$localLevel%" else "--",
                    valueColor = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                ReadingItem(
                    label = "系统读数",
                    value = if (systemState.hasValidBattery) "$sysLevel%" else "--",
                    valueColor = if (hasDivergence) LowBatteryRed else colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = when {
                    hasDivergence -> "⚠️ 读数分歧 ${divergence}%，建议核对协议解析"
                    systemState.hasValidBattery ->
                        "✓ 与系统读数一致 · 系统型号：${systemState.modelNumber ?: "未知"}"
                    else -> "系统未提供有效电量（握手未完成或该笔未被系统接管）"
                },
                fontSize = 12.sp,
                color = if (hasDivergence) LowBatteryRed else colors.textSecondary
            )

            if (systemState.hasValidCharging) {
                Spacer(modifier = Modifier.height(4.dp))
                val sysCharging = systemState.isCharging
                val same = sysCharging == localCharging
                Text(
                    text = if (same) {
                        "充电状态一致：${if (sysCharging) "充电中" else "未充电"}"
                    } else {
                        "充电状态不一致：系统=${if (sysCharging) "充电中" else "未充电"}，本机=${if (localCharging) "充电中" else "未充电"}"
                    },
                    fontSize = 12.sp,
                    color = if (same) colors.textSecondary else LowBatteryRed
                )
            }
        }
    }
}

@Composable
private fun ReadingItem(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppColors.current
    Column(modifier = modifier) {
        Text(text = label, fontSize = 11.sp, color = colors.textSecondary)
        Text(text = value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

@Composable
fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    subtitle: String? = null
) {
    val colors = LocalAppColors.current
    Card(
        modifier = modifier.clickable(enabled = enabled) { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) colors.card else colors.card.copy(alpha = 0.5f)
        ),
        // 移除阴影，保持界面视觉干净统一
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) PrimaryBlue else colors.textSecondary.copy(alpha = 0.4f),
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (enabled) colors.textPrimary else colors.textSecondary.copy(alpha = 0.5f)
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = colors.textSecondary.copy(alpha = 0.7f)
                )
            }
        }
    }
}