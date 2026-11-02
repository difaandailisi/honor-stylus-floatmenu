package com.batteryanalyzer.ui.screens

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.batteryanalyzer.LocalAppColors
import com.batteryanalyzer.ble.DeviceType
import com.batteryanalyzer.ble.ScanResult
import com.batteryanalyzer.ui.theme.*
import com.batteryanalyzer.viewmodel.MainViewModel

/**
 * 设备扫描界面 - 含完整的权限和状态检查
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScanScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanResults by viewModel.scanResults.collectAsState()
    // 【修复】直接从 BleManager 订阅扫描状态，消除 UI 本地状态与实际扫描状态不同步的 BUG
    // 之前用 local mutableStateOf(false)，超时后 stopScan() 不会更新它，导致 ON_RESUME 永远无法重扫
    val isScanning by viewModel.bleManager.isScanning.collectAsState()
    var composedOnce by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 错误消息 Snackbar 展示 — SharedFlow 用 LaunchedEffect 收集更可靠
    LaunchedEffect(Unit) {
        viewModel.bleManager.errorMessage.collect { msg ->
            snackbarHostState.showSnackbar(msg, duration = SnackbarDuration.Short)
        }
    }

    // ============ 系统服务引用 ============
    val bluetoothAdapter = remember {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
    }
    val locationManager = remember {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S)
            context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        else null
    }

    // ============ 权限定义：只请求实际弹出对话框的权限 ============
    // BLUETOOTH_CONNECT 在现代 Android 上多为静默授权，放在一起求可能导致系统不弹窗
    val permissionToRequest = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_SCAN  // 只请求这一个，它会弹出"附近设备"对话框
        else
            Manifest.permission.ACCESS_FINE_LOCATION  // Android 11- 弹出位置对话框
    }

    val needLocationService = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

    // ============ 状态变量（直接 mutableStateOf，每次 ON_RESUME / 回调中显式更新）============
    var bluetoothEnabled by remember { mutableStateOf(bluetoothAdapter?.isEnabled == true) }
    var permissionsGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permissionToRequest) == PackageManager.PERMISSION_GRANTED
        )
    }
    var locationEnabled by remember {
        mutableStateOf(
            if (!needLocationService) true
            else {
                val lm = locationManager
                lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                        lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
            }
        )
    }

    // ============ 同步刷新所有状态 ============
    fun refreshAllStates() {
        bluetoothEnabled = bluetoothAdapter?.isEnabled == true
        permissionsGranted = ContextCompat.checkSelfPermission(
            context, permissionToRequest
        ) == PackageManager.PERMISSION_GRANTED
        locationEnabled = if (!needLocationService) true
        else {
            val lm = locationManager
            lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                    lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        }
    }

    fun isReadyToScan(): Boolean = bluetoothEnabled && permissionsGranted && locationEnabled

    // ============ 启动扫描 ============
    fun doStartScan() {
        refreshAllStates()
        if (!isReadyToScan()) return
        viewModel.startScan()
    }

    // ============ Launchers ============
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()  // 只请求单个权限
    ) { granted ->
        refreshAllStates()
        if (isReadyToScan()) {
            viewModel.startScan()
        }
    }

    val bluetoothEnableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        refreshAllStates()
        if (isReadyToScan()) {
            viewModel.startScan()
        }
    }

    // ============ 引导式扫描启动 ============
    fun tryStartScan() {
        refreshAllStates()
        if (!bluetoothEnabled) {
            bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        if (!permissionsGranted) {
            permissionLauncher.launch(permissionToRequest)
            return
        }
        if (!locationEnabled) {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        viewModel.startScan()
    }

    // ============ 从后台返回时自动刷新并尝试扫描 ============
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                try {
                    refreshAllStates()
                    // 【修复】BLE 连接与扫描不能同时进行，若正在连接则跳过自动扫描
                    // 大部分 BLE 芯片不支持同时扫描和 GATT 连接，并发操作会导致 Native Crash
                    if (isReadyToScan() && !isScanning && !viewModel.bleManager.isConnecting()) {
                        viewModel.startScan()
                    }
                } catch (_: Exception) {
                    // 从系统弹窗（蓝牙配对/权限对话框）返回时，权限状态可能短暂不一致，
                    // BLE 操作可能抛出 SecurityException，静默吞掉避免崩溃
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ============ 首次进入：延迟 300ms 后启动扫描（确保 launcher 就绪）============
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(300)
        composedOnce = true
        tryStartScan()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopScan()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("扫描设备") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = PrimaryBlue,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    IconButton(onClick = {
                        if (isScanning) {
                            viewModel.stopScan()
                        } else {
                            tryStartScan()
                        }
                    }) {
                        Icon(
                            if (isScanning) Icons.Default.Stop else Icons.Default.Refresh,
                            if (isScanning) "停止" else "重新扫描"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // ============ 警告区域 ============

            // 1. 蓝牙未开启
            if (!bluetoothEnabled) {
                WarningCard(
                    icon = Icons.Default.BluetoothDisabled,
                    iconTint = Color(0xFFFF9800),
                    title = "蓝牙未开启",
                    message = "扫描蓝牙设备需要开启蓝牙，点击前往设置",
                    titleColor = Color(0xFFE65100),
                    msgColor = Color(0xFFFF8F00),
                    onClick = {
                        val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        bluetoothEnableLauncher.launch(enableIntent)
                    }
                )
            }

            // 2. 权限未授予
            if (!permissionsGranted) {
                WarningCard(
                    icon = Icons.Default.Security,
                    iconTint = Color(0xFFE53935),
                    title = "缺少必要权限",
                    message = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        "需要授予\"附近设备\"权限才能扫描蓝牙设备，点击授权"
                    else
                        "需要授予\"位置信息\"权限才能扫描蓝牙设备，点击授权",
                    titleColor = Color(0xFFC62828),
                    msgColor = Color(0xFFE53935),
                    onClick = {
                        permissionLauncher.launch(permissionToRequest)
                    }
                )
            }

            // 3. 位置服务未开启 (仅 Android 11-)
            if (needLocationService && !locationEnabled) {
                WarningCard(
                    icon = Icons.Default.LocationOn,
                    iconTint = Color(0xFFFF9800),
                    title = "位置服务未开启",
                    message = "在 Android 11 及以下版本中，BLE 扫描需要开启位置服务(GPS)，点击前往设置",
                    titleColor = Color(0xFFE65100),
                    msgColor = Color(0xFFFF8F00),
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                )
            }

            // ============ 不能扫描的提示 ============
            if (!isReadyToScan()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFF5F5F5)
                    ),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "请先解决上方的问题",
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "点击对应卡片即可前往设置",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }
                }
            }

            // ============ 正在扫描提示 ============
            if (isScanning && isReadyToScan()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = PrimaryBlueLight.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = PrimaryBlue
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "正在扫描附近的蓝牙设备 (BLE + 经典蓝牙)...",
                            color = PrimaryBlue,
                            fontSize = 13.sp
                        )
                    }
                }
            } else if (!isScanning && isReadyToScan()) {
                Text(
                    text = "点击刷新重新扫描",
                    fontSize = 13.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }

            // ============ 设备列表 ============
            if (scanResults.isEmpty() && !isScanning && isReadyToScan()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.BluetoothDisabled,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("未发现蓝牙设备", color = TextSecondary)
                        Text("请确保设备处于配对模式", fontSize = 12.sp, color = TextSecondary)
                    }
                }
            } else if (scanResults.isNotEmpty()) {
                // 按设备类别分组
                val stylusDevices = scanResults.filter { it.deviceType == DeviceType.STYLUS }
                val audioDevices = scanResults.filter { it.deviceType == DeviceType.AUDIO }
                val wearableDevices = scanResults.filter { it.deviceType == DeviceType.WEARABLE }
                val computerDevices = scanResults.filter { it.deviceType == DeviceType.COMPUTER }
                val otherBleDevices = scanResults.filter { it.deviceType == DeviceType.OTHER_BLE }
                val otherDevices = scanResults.filter {
                    it.deviceType == DeviceType.CLASSIC || it.deviceType == DeviceType.UNKNOWN
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 手写笔
                    if (stylusDevices.isNotEmpty()) {
                        categoryHeader("✏️ 手写笔设备", PrimaryBlue)
                        categoryDevices(stylusDevices, viewModel)
                    }

                    // 蓝牙耳机/音箱
                    if (audioDevices.isNotEmpty()) {
                        categoryHeader("🎧 蓝牙耳机 / 音箱", Color(0xFFE91E63))
                        categoryDevices(audioDevices, viewModel)
                    }

                    // 智能手表/手环
                    if (wearableDevices.isNotEmpty()) {
                        categoryHeader("⌚ 智能手表 / 手环", Color(0xFF9C27B0))
                        categoryDevices(wearableDevices, viewModel)
                    }

                    // 电脑/平板
                    if (computerDevices.isNotEmpty()) {
                        categoryHeader("💻 电脑 / 平板", Color(0xFF607D8B))
                        categoryDevices(computerDevices, viewModel)
                    }

                    // 其他 BLE 设备
                    if (otherBleDevices.isNotEmpty()) {
                        categoryHeader("📡 其他 BLE 设备", Color(0xFF00BCD4))
                        categoryDevices(otherBleDevices, viewModel)
                    }

                    // 其他蓝牙设备
                    if (otherDevices.isNotEmpty()) {
                        categoryHeader("📶 其他蓝牙设备", TextSecondary)
                        categoryDevices(otherDevices, viewModel)
                    }
                }
            }
        }
    }
}

/**
 * 警告卡片组件 - 可点击前往设置
 */
@Composable
private fun WarningCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    message: String,
    titleColor: Color,
    msgColor: Color,
    onClick: () -> Unit
) {
    val containerColor = when (iconTint) {
        Color(0xFFE53935) -> Color(0xFFFFEBEE)
        else -> Color(0xFFFFF3E0)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = titleColor)
                Text(text = message, fontSize = 12.sp, color = msgColor)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = iconTint)
        }
    }
}

@Composable
fun DeviceCard(result: ScanResult, onClick: () -> Unit, isSaved: Boolean = false) {
    val colors = LocalAppColors.current
    val (icon, iconTint) = when (result.deviceType) {
        DeviceType.STYLUS -> Icons.Default.Edit to PrimaryBlue
        DeviceType.AUDIO -> Icons.Default.Headphones to Color(0xFFE91E63)
        DeviceType.WEARABLE -> Icons.Default.Watch to Color(0xFF9C27B0)
        DeviceType.COMPUTER -> Icons.Default.Laptop to Color(0xFF607D8B)
        DeviceType.OTHER_BLE -> Icons.Default.BluetoothConnected to Color(0xFF00BCD4)
        else -> Icons.Default.Bluetooth to colors.textSecondary
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null,
                tint = iconTint, modifier = Modifier.size(36.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = result.deviceName, fontWeight = FontWeight.Medium,
                        fontSize = 16.sp, color = colors.textPrimary)
                    if (isSaved) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(color = ChargingGreen.copy(alpha = 0.12f),
                            shape = MaterialTheme.shapes.extraSmall) {
                            Text("已保存", color = ChargingGreen, fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                Text(text = result.device.address, fontSize = 12.sp, color = colors.textSecondary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SignalCellularAlt, contentDescription = null,
                        modifier = Modifier.size(12.dp), tint = colors.textSecondary)
                    Text(text = " RSSI: ${result.rssi} dBm", fontSize = 11.sp, color = colors.textSecondary)
                }
            }
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(if (isSaved) "重连" else "连接", color = Color.White)
            }
        }
    }
}

private fun LazyListScope.categoryHeader(title: String, color: Color) {
    item {
        Text(text = title, fontWeight = FontWeight.SemiBold, color = color,
            fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
    }
}

private fun LazyListScope.categoryDevices(
    devices: List<ScanResult>,
    viewModel: MainViewModel
) {
    val savedMacs by lazy { viewModel.allDevices.value.map { it.macAddress }.toSet() }
    items(devices) { result ->
        DeviceCard(
            result = result,
            isSaved = result.device.address in savedMacs,
            onClick = { viewModel.connectToDevice(result.device.address) }
        )
    }
}
