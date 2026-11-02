package com.batteryanalyzer.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.batteryanalyzer.crash.CrashExitMonitor
import com.batteryanalyzer.model.BatteryChannel
import com.batteryanalyzer.stylus.HonorSystemPenStateReader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 蓝牙设备扫描结果
 */
data class ScanResult(
    val device: BluetoothDevice,
    val rssi: Int,
    val scanRecord: ScanRecord?,
    val deviceName: String,
    val deviceType: DeviceType
)

enum class DeviceType {
    STYLUS,    // 手写笔
    AUDIO,     // 蓝牙耳机/音箱
    WEARABLE,  // 智能手表/手环
    COMPUTER,  // 电脑/平板
    OTHER_BLE, // 其他 BLE 设备
    CLASSIC,   // 经典蓝牙设备
    UNKNOWN    // 未知设备
}

/**
 * 设备连接状态
 */
enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

/**
 * BLE 管理器 - 多通道电量扫描、连接、读取
 *
 * 核心逻辑:
 * 1. 同时扫描 BLE 和经典蓝牙设备
 * 2. 智能识别设备类型（手写笔/耳机/手表等）
 * 3. 连接到目标设备
 * 4. 发现所有 Battery Service 实例 → 左/右耳+充电仓
 * 5. 支持 HID Service 电量报告解析
 * 6. 支持厂商自定义电量服务
 * 7. 订阅电量变化通知
 */
@SuppressLint("MissingPermission")
class BleManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BleManager"

        // ===== 单例 =====
        @Volatile
        private var INSTANCE: BleManager? = null

        fun getInstance(context: Context): BleManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BleManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        // ===== 标准 BATTERY SERVICE UUIDs =====
        private val BATTERY_SERVICE_UUID =
            UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        private val BATTERY_LEVEL_CHAR_UUID =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

        // ===== HID SERVICE (用于耳机多通道电量) =====
        private val HID_SERVICE_UUID =
            UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
        private val HID_REPORT_CHAR_UUID =
            UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb")
        private val HID_REPORT_MAP_UUID =
            UUID.fromString("00002a4b-0000-1000-8000-00805f9b34fb")
        private val PROTOCOL_MODE_UUID =
            UUID.fromString("00002a4e-0000-1000-8000-00805f9b34fb")
        private val REPORT_REF_DESCRIPTOR_UUID =
            UUID.fromString("00002908-0000-1000-8000-00805f9b34fb")

        // ===== Generic Access Service (设备名称) =====
        private val GENERIC_ACCESS_SERVICE_UUID =
            UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        private val DEVICE_NAME_CHAR_UUID =
            UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")

        // ===== DEVICE INFO SERVICE =====
        private val DEVICE_INFO_SERVICE_UUID =
            UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val MANUFACTURER_NAME_CHAR_UUID =
            UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
        private val MODEL_NUMBER_CHAR_UUID =
            UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        private val SERIAL_NUMBER_CHAR_UUID =
            UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
        private val PNP_ID_CHAR_UUID =
            UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb")

        // ===== 厂商自定义服务 (常见耳机品牌) =====
        // Apple / Beats (ANCS + 自定义)
        private val APPLE_SERVICE_UUID =
            UUID.fromString("D0611E78-BBB4-4591-A5F8-487910AE4366")
        // Samsung Galaxy Buds
        private val SAMSUNG_BUDS_SERVICE_UUID =
            UUID.fromString("0000fe01-0000-1000-8000-00805f9b34fb")
        // 通用厂商服务
        private val XIAOMI_SERVICE_UUID =
            UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")

        // ===== 荣耀/华为 手写笔自定义电量服务 =====
        // Magic-Pencil / M-Pencil 通过此特征上报电量 + 充电状态
        // 数据格式: 2D 04 [level] [status] [charging]
        //   例: 2D-04-01-01-01 = 充电中,  2D-04-01-01-00 = 放电/使用中
        private val HONOR_PENCIL_SERVICE_UUID =
            UUID.fromString("0000fe42-cc7a-482a-984a-7f2ed5b3e512")

        // CCCD
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // ===== 已知设备名称关键词 =====
        val KNOWN_STYLUS_NAMES = listOf(
            // 荣耀 / 华为
            "Magic-Pencil", "Magic Pencil", "Huawei M-Pencil", "M-Pencil",
            // 三星
            "S Pen", "S-Pen", "S Pen Pro", "S Pen Fold", "Tab S Pen",
            // Apple
            "Apple Pencil",
            // 微软 Surface
            "Surface Pen", "Surface Slim Pen",
            // 小米 / Redmi
            "Xiaomi Pen", "Xiaomi Smart Pen", "Redmi Pen", "Redmi Smart Pen",
            // OPPO
            "OPPO Pen", "OPPO Pencil",
            // vivo
            "vivo Pen", "vivo Pencil",
            // 联想
            "Lenovo Pen", "Lenovo Precision Pen", "Lenovo Tab Pen",
            "Tab Pen", "Tab Pen Plus", "Active Pen",
            // Wacom
            "Wacom", "Bamboo Ink", "Bamboo Sketch",
            // 通用
            "Smart Pen", "Stylus Pen", "Stylus",
            "Digital Pen", "USI Pen", "M-Pen",
            // 中文关键词
            "手写笔", "触控笔", "触屏笔", "画笔", "电容笔", "点阵笔", "电子笔"
        )

        val KNOWN_AUDIO_NAMES = listOf(
            "AirPods", "AirPod", "Powerbeats", "Beats",
            "Galaxy Buds", "GalaxyBuds", "Buds", "Galaxy Bud",
            "Sony", "WF-", "WH-", "WI-",
            "Bose", "QC", "QuietComfort",
            "Jabra", "Elite", "Sennheiser", "Momentum",
            "JBL", "Soundcore", "Anker",
            "Xiaomi Buds", "Redmi Buds", "RedmiBuds",
            "Huawei FreeBuds", "FreeBuds", "FreeLace",
            "OPPO Enco", "Enco", "OnePlus Buds",
            "vivo TWS", "iQOO TWS",
            "Marshall", "Major", "Minor",
            "Nothing Ear", "Ear (",
            "Skullcandy", "Audio-Technica",
            "Headset", "Headphone", "Earphone", "Earbud",
            "Speaker", "SoundBar",
            "耳机", "音箱", "音响"
        )

        val KNOWN_WEARABLE_NAMES = listOf(
            "Galaxy Watch", "GalaxyWatch",
            "Apple Watch", "AppleWatch",
            "Mi Band", "MiBand", "Redmi Watch", "RedmiWatch",
            "Huawei Watch", "HuaweiWatch", "Huawei Band",
            "OPPO Watch", "OPPO Band",
            "Fitbit", "Garmin", "Amazfit",
            "Watch", "Band",
            "手表", "手环"
        )

        val KNOWN_COMPUTER_NAMES = listOf(
            "iPad", "iPhone", "MacBook", "Mac",
            "Surface Pro", "Surface Laptop", "Surface Go",
            "ThinkPad", "ThinkBook",
            "GALAXY Tab", "Galaxy Tab",
            "MatePad", "MateBook",
            "Mi Pad", "Redmi Pad",
            "OPPO Pad", "vivo Pad",
            "Notebook", "Laptop", "Desktop", "PC-"
        )

        private const val SCAN_TIMEOUT_MS = 20000L
    }

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter
    private var bluetoothGatt: BluetoothGatt? = null

    // ===== 状态流 =====

    private val _scanResults = MutableStateFlow<List<ScanResult>>(emptyList())
    val scanResults: StateFlow<List<ScanResult>> = _scanResults.asStateFlow()

    // 扫描结果更新节流 — 避免每帧广播都触发 O(n log n) 排序 + UI 重组
    @Volatile private var lastScanUpdateTime = 0L

    // 扫描运行状态 — 供 UI 响应式订阅，避免 local mutableState 与实际状态不同步
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // 【新增】多通道电量: Map<BatteryChannel, 电量值>
    private val _multiBatteryLevels = MutableStateFlow<Map<BatteryChannel, Int>>(emptyMap())
    val multiBatteryLevels: StateFlow<Map<BatteryChannel, Int>> = _multiBatteryLevels.asStateFlow()

    // 【兼容】单一电量值 = SINGLE 通道的值（或所有通道平均值）
    private val _batteryLevel = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    private val _availableChannels = MutableStateFlow<List<BatteryChannel>>(emptyList())
    val availableChannels: StateFlow<List<BatteryChannel>> = _availableChannels.asStateFlow()

    private val _deviceManufacturer = MutableStateFlow<String?>(null)
    val deviceManufacturer: StateFlow<String?> = _deviceManufacturer.asStateFlow()

    private val _deviceModel = MutableStateFlow<String?>(null)
    val deviceModel: StateFlow<String?> = _deviceModel.asStateFlow()

    private val _serialNumber = MutableStateFlow<String?>(null)
    val serialNumber: StateFlow<String?> = _serialNumber.asStateFlow()

    private val _errorMessage = MutableSharedFlow<String>()
    val errorMessage: SharedFlow<String> = _errorMessage

    private val bleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 连接生命周期内专用 scope，每次 connect/disconnect 时重建，防止协程泄漏
    private var connectionScope: CoroutineScope? = null

    // 【修复】防止 connectInternal 重入：系统弹窗（蓝牙配对）期间 Activity 重建可能触发重复连接
    private val connectingLock = AtomicBoolean(false)

    private var scanJob: Job? = null
    // 连接后电量读取重试（兜底机制）
    private var batteryRetryJob: Job? = null
    private val _connectedDeviceMac = MutableStateFlow<String?>(null)
    val connectedDeviceMac: StateFlow<String?> = _connectedDeviceMac.asStateFlow()

    /** v4.1: 连接后解析到的真实设备名称（用于修正 "未知设备" → 真实名称） */
    private val _resolvedDeviceName = MutableStateFlow<String?>(null)
    val resolvedDeviceName: StateFlow<String?> = _resolvedDeviceName.asStateFlow()

    private var connectedDeviceType: DeviceType = DeviceType.UNKNOWN

    // 经典蓝牙扫描
    private var classicDiscoveryReceiver: BroadcastReceiver? = null
    private val seenDevices = ConcurrentHashMap<String, ScanResult>()

    // 配对状态监听
    private var bondStateReceiver: BroadcastReceiver? = null

    // 充电检测
    private var previousBatteryLevel = -1
    /** 是否已从硬件协议（如荣耀 0xFE42）获取到充电状态，true 时禁用趋势推断 */
    @Volatile
    private var hasHardwareCharging = false
    private var chargingCheckJob: Job? = null

    // 【新增】HID 服务电量解析缓冲
    private var hidReportBuffer = ByteArray(0)

    // 【新增】多实例 Battery Service 计数器
    private var batteryServiceCount = 0

    /**
     * 检查蓝牙是否可用
     */
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter.isEnabled

    /** 是否正在执行连接操作（供外部判断，避免扫描与连接并发） */
    fun isConnecting(): Boolean = connectingLock.get()

    /** 是否正在扫描中 */
    fun isScanActive(): Boolean = scanJob?.isActive == true

    fun isLocationEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    // ============ 扫描 ============

    /**
     * @param includeClassic 是否同时扫描经典蓝牙设备（默认 false 以省电）
     *   经典蓝牙发现功耗约 100mA+，仅在用户明确需要时开启
     */
    fun startScan(scope: CoroutineScope, includeClassic: Boolean = false) {
        CrashExitMonitor.enterContext("BLE_SCAN")
        if (!isBluetoothEnabled()) {
            scope.launch { _errorMessage.emit("蓝牙未开启，请先打开蓝牙") }
            return
        }
        if (!hasRequiredPermissions()) {
            scope.launch {
                _errorMessage.emit(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "缺少\"附近设备\"权限，请在设置中授予"
                    else "缺少\"位置信息\"权限，请在设置中授予"
                )
            }
            return
        }
        if (!isLocationEnabled()) {
            scope.launch { _errorMessage.emit("位置服务未开启，BLE 扫描需要开启位置服务（GPS）") }
            return  // Android 12 以下没有位置权限扫描静默返回空，必须阻止
        }
        // 【修复】连接进行中不允许扫描，大部分 BLE 芯片不支持同时扫描和 GATT 连接
        if (connectingLock.get()) {
            Log.w(TAG, "startScan 被阻止：连接操作正在进行中，BLE 芯片不支持扫描与连接并发")
            scope.launch { _errorMessage.emit("连接操作进行中，请稍后再试") }
            return
        }

        _scanResults.value = emptyList()
        seenDevices.clear()
        // 【关键修复】重置节流时间戳，确保新扫描的第一个设备能立即显示
        // 否则上次扫描最后 500ms 内的发射会阻塞新扫描的首个设备，UI 一直空着
        lastScanUpdateTime = 0L
        _isScanning.value = true

        startBleScan(scope)
        // 【省电优化】经典蓝牙发现默认关闭，仅在用户手动触发时开启
        if (includeClassic) {
            startClassicDiscovery(scope)
        }

        // 【省电修复】使用 bleScope 而非外部 scope，避免调用方 scope 取消导致扫描永不停止
        scanJob?.cancel()   // 取消旧定时器，防止残留协程杀死新扫描
        scanJob = bleScope.launch {
            delay(SCAN_TIMEOUT_MS)
            stopScan()
        }
    }

    private fun startBleScan(scope: CoroutineScope) {
        try {
            // 每次扫描前重新获取 scanner，避免类加载时蓝牙未就绪导致永久失能
            val scanner = bluetoothAdapter.bluetoothLeScanner
            if (scanner == null) {
                Log.e(TAG, "BLE scanner 不可用，蓝牙适配器可能未就绪")
                scope.launch { _errorMessage.emit("蓝牙扫描器未就绪，请重新开关蓝牙后重试") }
                return
            }
            // 扫描降为 LOW_POWER 模式：电量监控不需要高频扫描
            // 注意：setReportDelay(非0) 会开启硬件批量模式 → 回调 onBatchScanResults 而非 onScanResult
            // 软件层已用 emitScanResultsThrottled() 做 500ms 节流，硬件层无需再叠一层，避免引入批量回调复杂度
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setReportDelay(0)  // 逐条回调 onScanResult，由软件层 emitScanResultsThrottled 节流
                .build()
            scanner.startScan(null, scanSettings, bleScanCallback)
            Log.d(TAG, "BLE 扫描已启动 (LOW_POWER 模式)")
        } catch (e: SecurityException) {
            scope.launch { _errorMessage.emit("权限不足: 缺少蓝牙扫描权限") }
        } catch (e: Exception) {
            scope.launch { _errorMessage.emit("BLE 扫描启动失败: ${e.message}") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startClassicDiscovery(scope: CoroutineScope) {
        try {
            if (bluetoothAdapter.isDiscovering) bluetoothAdapter.cancelDiscovery()
            classicDiscoveryReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.action) {
                        BluetoothDevice.ACTION_FOUND -> {
                            val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                            device?.let { addClassicDevice(it, rssi) }
                        }
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                            Log.d(TAG, "经典蓝牙发现完成")
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                context.registerReceiver(classicDiscoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(classicDiscoveryReceiver, filter)
            }
            if (bluetoothAdapter.startDiscovery()) {
                Log.d(TAG, "经典蓝牙发现已启动")
            } else {
                scope.launch { _errorMessage.emit("经典蓝牙扫描启动失败") }
            }
        } catch (e: SecurityException) {
            scope.launch { _errorMessage.emit("权限不足: 缺少蓝牙扫描权限") }
        } catch (e: Exception) {
            scope.launch { _errorMessage.emit("经典蓝牙扫描失败: ${e.message}") }
        }
    }

    private fun addClassicDevice(device: BluetoothDevice, rssi: Int) {
        // 屏蔽手机 / 电脑 / 干电池鼠标
        if (shouldSkipDevice(device, false)) return
        val name = device.name ?: "未知设备"
        val deviceType = classifyDevice(name, false, false)
        val result = ScanResult(
            device = device, rssi = if (rssi == Short.MIN_VALUE.toInt()) -100 else rssi,
            scanRecord = null, deviceName = name, deviceType = deviceType
        )
        seenDevices[device.address] = result
        emitScanResultsThrottled()
    }

    /** 节流更新扫描结果 — 最多每500ms触发一次排序+发射。先写时间戳再排序，避免多线程竞态重复触发 */
    private fun emitScanResultsThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastScanUpdateTime >= 500) {
            lastScanUpdateTime = now  // 先更新时间戳，防止并发线程重复进入
            _scanResults.value = seenDevices.values.sortedByDescending { it.rssi }
        }
    }

    /**
     * 扫描过滤：屏蔽手机、电脑，以及无可读电量的"干电池"蓝牙鼠标。
     * - 手机 (MAJOR_PHONE) / 电脑 (MAJOR_COMPUTER)：一律屏蔽。
     * - 蓝牙鼠标 (MAJOR_PERIPHERAL + 鼠标子类型)：仅当扫描记录中不含标准电量服务
     *   (0x180F) 时屏蔽（干电池鼠标无电量读数，对电量分析无意义）；带电量服务的
     *   充电鼠标予以保留。
     * 注意：BLE 广播期 BluetoothClass 经常为 null，故此处仅作辅助过滤；连接阶段
     * (connect / onServicesDiscovered) 另有兜底拦截，确保这类设备既不会出现在列表也不会被连接。
     */
    /**
     * 扫描过滤：屏蔽手机、电脑，以及无可读电量的"干电池"蓝牙鼠标。
     * 使用 BluetoothClass.Device 的官方常量数值（Kotlin 下符号常无法稳定解析，
     * 且 Android 不存在 PERIPHERAL_MOUSE 常量，鼠标归类于 PERIPHERAL_POINTING）：
     *   MAJOR_COMPUTER       = 0x100
     *   MAJOR_PHONE          = 0x200
     *   MAJOR_PERIPHERAL     = 0x500
     *   PERIPHERAL_POINTING  = 0x580（鼠标/指点设备）
     *   PERIPHERAL_KEYBOARD_POINTING = 0x5C0（键盘+指点组合）
     */
    private fun shouldSkipDevice(device: BluetoothDevice, hasBatteryService: Boolean): Boolean {
        return try {
            val btClass = device.bluetoothClass ?: return false
            val major = btClass.majorDeviceClass
            val dc = btClass.deviceClass
            when {
                // 手机：一律屏蔽
                major == 0x200 -> {
                    Log.d(TAG, "扫描过滤: 跳过手机 ${device.name}")
                    true
                }
                // 电脑：一律屏蔽
                major == 0x100 -> {
                    Log.d(TAG, "扫描过滤: 跳过电脑 ${device.name}")
                    true
                }
                // 外设：仅屏蔽鼠标类（且无可读电量）的干电池鼠标
                major == 0x500 -> {
                    val isMouse = dc == 0x580 || dc == 0x5C0
                    if (isMouse && !hasBatteryService) {
                        Log.d(TAG, "扫描过滤: 跳过干电池鼠标 ${device.name}")
                        true
                    } else false
                }
                else -> false
            }
        } catch (e: SecurityException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun classifyDevice(name: String, hasBatteryService: Boolean, isBle: Boolean): DeviceType {
        if (name.isEmpty() || name == "未知设备") {
            return if (isBle) DeviceType.OTHER_BLE else DeviceType.UNKNOWN
        }
        if (KNOWN_STYLUS_NAMES.any { name.contains(it, ignoreCase = true) }) return DeviceType.STYLUS
        if (KNOWN_AUDIO_NAMES.any { name.contains(it, ignoreCase = true) }) return DeviceType.AUDIO
        if (KNOWN_WEARABLE_NAMES.any { name.contains(it, ignoreCase = true) }) return DeviceType.WEARABLE
        if (KNOWN_COMPUTER_NAMES.any { name.contains(it, ignoreCase = true) }) return DeviceType.COMPUTER
        if (hasBatteryService && isBle) return DeviceType.OTHER_BLE
        if (!isBle) return DeviceType.CLASSIC
        return if (isBle) DeviceType.OTHER_BLE else DeviceType.UNKNOWN
    }

    fun stopScan() {
        try { bluetoothAdapter.bluetoothLeScanner?.stopScan(bleScanCallback) } catch (e: Exception) { Log.e(TAG, "Stop BLE scan error: ${e.message}") }
        try { bluetoothAdapter.cancelDiscovery() } catch (e: Exception) { Log.e(TAG, "Cancel classic discovery error: ${e.message}") }
        try { classicDiscoveryReceiver?.let { context.unregisterReceiver(it) } } catch (e: Exception) { Log.e(TAG, "Unregister receiver error: ${e.message}") }
        classicDiscoveryReceiver = null
        scanJob?.cancel()
        _isScanning.value = false
        CrashExitMonitor.exitContext("BLE_SCAN")
    }

    // ============ 连接 ============

    fun connect(device: BluetoothDevice, scope: CoroutineScope) {
        // 【修复】防止重入：若已有连接正在进行中，忽略重复调用
        if (connectingLock.get()) {
            Log.w(TAG, "connect 已有连接进行中，忽略重复调用")
            return
        }
        // 屏蔽手机 / 电脑 / 干电池鼠标：直接拒绝连接
        if (shouldSkipDevice(device, false)) {
            Log.w(TAG, "connect 拒绝被屏蔽设备: ${device.name}")
            bleScope.launch { _errorMessage.emit("已屏蔽该设备类型（手机/电脑/干电池鼠标）") }
            return
        }
        // 取消上一次连接残留的充电检测协程
        connectionScope?.cancel()
        connectionScope = null
        // 综合判断设备类型，支持经典蓝牙 HID 设备
        val name = device.name ?: "未知设备"
        val isBle = device.type == BluetoothDevice.DEVICE_TYPE_LE ||
                device.type == BluetoothDevice.DEVICE_TYPE_DUAL
        connectedDeviceType = when {
            KNOWN_STYLUS_NAMES.any { name.contains(it, ignoreCase = true) } -> DeviceType.STYLUS
            KNOWN_AUDIO_NAMES.any { name.contains(it, ignoreCase = true) } -> DeviceType.AUDIO
            KNOWN_WEARABLE_NAMES.any { name.contains(it, ignoreCase = true) } -> DeviceType.WEARABLE
            !isBle -> DeviceType.CLASSIC
            else -> DeviceType.OTHER_BLE
        }
        connectInternal(device, scope)
    }

    fun connectByAddress(address: String, scope: CoroutineScope) {
        CrashExitMonitor.enterContext("BLE_CONNECT")
        CrashExitMonitor.updateBleState("正在连接 $address")
        // 【修复】防止重入：若已有连接正在进行中，忽略重复调用
        if (connectingLock.get()) {
            Log.w(TAG, "connectByAddress 已有连接进行中，忽略重复调用")
            return
        }
        try {
            // 取消上一次连接残留的充电检测协程
            connectionScope?.cancel()
            connectionScope = null
            val device = bluetoothAdapter.getRemoteDevice(address)
            // 屏蔽手机 / 电脑 / 干电池鼠标：直接拒绝连接
            if (shouldSkipDevice(device, false)) {
                Log.w(TAG, "connectByAddress 拒绝被屏蔽设备: $address")
                bleScope.launch { _errorMessage.emit("已屏蔽该设备类型（手机/电脑/干电池鼠标）") }
                return
            }
            val name = device.name ?: "未知设备"
            val isBle = device.type == BluetoothDevice.DEVICE_TYPE_LE ||
                    device.type == BluetoothDevice.DEVICE_TYPE_DUAL
            connectedDeviceType = when {
                KNOWN_STYLUS_NAMES.any { name.contains(it, ignoreCase = true) } -> DeviceType.STYLUS
                KNOWN_AUDIO_NAMES.any { name.contains(it, ignoreCase = true) } -> DeviceType.AUDIO
                KNOWN_WEARABLE_NAMES.any { name.contains(it, ignoreCase = true) } -> DeviceType.WEARABLE
                !isBle -> DeviceType.CLASSIC
                else -> DeviceType.OTHER_BLE
            }
            connectInternal(device, scope)
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.DISCONNECTED
            bleScope.launch { _errorMessage.emit("连接失败: 无效的设备地址") }
        }
    }

    fun isDeviceBonded(address: String): Boolean {
        return try {
            bluetoothAdapter.getRemoteDevice(address).bondState == BluetoothDevice.BOND_BONDED
        } catch (e: Exception) { false }
    }

    /**
     * v8: 取消系统层配对（解绑）。通过反射调用隐藏的 removeBond()。
     * 仅解绑系统配对，不删除 App 内设备行与电量记录（非破坏性取消配对）。
     * @return 是否成功发起解绑（true 不代表系统已确认，需等 ACTION_BOND_STATE_CHANGED）
     */
    @SuppressLint("MissingPermission")
    fun cancelBond(address: String): Boolean {
        return try {
            val device = bluetoothAdapter.getRemoteDevice(address)
            val method = device.javaClass.getMethod("removeBond")
            method.invoke(device) as? Boolean ?: true
        } catch (e: Exception) {
            Log.e(TAG, "取消配对失败: $address → ${e.message}")
            false
        }
    }

    fun getConnectedDeviceAddress(): String? = _connectedDeviceMac.value

    fun getConnectedDeviceType(): DeviceType = connectedDeviceType

    // ============ 手动读取已连接设备电量（使用当前 GATT 连接） ============

    /**
     * 在已建立的 GATT 连接上强制读取电池电量。
     * 作为自动获取失败时的补充手段，直接读取所有 Battery Service 的电量特征值。
     *
     * @param onResult 回调：电量值、是否充电中、错误信息
     * @return 是否成功发起了读取请求
     */
    fun manualReadBatteryLevel(
        onResult: (batteryLevel: Int, isCharging: Boolean, error: String?) -> Unit
    ): Boolean {
        val gatt = bluetoothGatt
        if (gatt == null) {
            onResult(-1, false, "未连接设备")
            return false
        }
        if (_connectionState.value != ConnectionState.CONNECTED) {
            onResult(-1, false, "设备未连接")
            return false
        }

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val hasResult = AtomicBoolean(false)

        try {
            // 尝试读取标准 Battery Service
            val batteryServices = gatt.services.filter { it.uuid == BATTERY_SERVICE_UUID }
            if (batteryServices.isNotEmpty()) {
                for (service in batteryServices) {
                    val char = service.getCharacteristic(BATTERY_LEVEL_CHAR_UUID) ?: continue
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                        Log.d(TAG, "手动读取: 强制读取标准 Battery Level 特征值")
                        gatt.readCharacteristic(char)
                        // 延迟后检查结果
                        bleScope.launch {
                            delay(2000)
                            if (!hasResult.get()) {
                                val level = _batteryLevel.value
                                if (level in 0..100) {
                                    mainHandler.post { onResult(level, _isCharging.value, null) }
                                } else {
                                    mainHandler.post { onResult(-1, false, "读取超时，设备未响应") }
                                }
                            }
                        }
                        return true // 成功发起请求
                    }
                }
            }

            // 尝试 HID Service
            val hidService = gatt.getService(HID_SERVICE_UUID)
            if (hidService != null) {
                val reportChar = hidService.getCharacteristic(HID_REPORT_CHAR_UUID)
                if (reportChar != null && reportChar.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    Log.d(TAG, "手动读取: 强制读取 HID Report")
                    gatt.readCharacteristic(reportChar)
                    bleScope.launch {
                        delay(2000)
                        if (!hasResult.get()) {
                            val level = _batteryLevel.value
                            if (level in 0..100) {
                                mainHandler.post { onResult(level, _isCharging.value, null) }
                            } else {
                                mainHandler.post { onResult(-1, false, "HID 读取超时") }
                            }
                        }
                    }
                    return true
                }
            }

            onResult(-1, false, "设备无可读取的电量特征")
            return false
        } catch (e: SecurityException) {
            onResult(-1, false, "权限不足")
            return false
        } catch (e: Exception) {
            onResult(-1, false, "读取失败: ${e.message}")
            return false
        }
    }

    // ============ 快速获取已配对设备电量（无需在App内重连） ============

    /**
     * 快速获取已配对设备的电量 — 临时GATT连接后读取电量值，用完即断开
     * 适用于已在系统层面配对但未在App内连接的设备
     * @param address 设备MAC地址
     * @param onResult 结果回调：(电量值, 是否充电, 错误信息?)
     */
    @SuppressLint("MissingPermission")
    fun quickFetchBatteryLevel(
        address: String,
        scope: CoroutineScope,
        onResult: (batteryLevel: Int, isCharging: Boolean, error: String?) -> Unit
    ) {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        if (!isBluetoothEnabled()) {
            onResult(-1, false, "蓝牙未开启")
            return
        }
        if (!hasConnectPermission()) {
            onResult(-1, false, "缺少蓝牙连接权限")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val device = bluetoothAdapter.getRemoteDevice(address)
                Log.d(TAG, "快速获取电量: 连接已配对设备 ${device.name ?: address}")

                // 临时连接 GATT 以快速读取已配对设备电量（默认传输层）
                val quickFetchCallback = object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                            Log.d(TAG, "快速获取: GATT已连接, 开始discoverServices")
                            gatt.discoverServices()
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            Log.d(TAG, "快速获取: GATT已断开")
                        }
                    }

                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            Log.e(TAG, "快速获取: 服务发现失败")
                            mainHandler.post { onResult(-1, false, "服务发现失败") }
                            gatt.disconnect()
                            gatt.close()
                            return
                        }

                        val batteryService = gatt.getService(BATTERY_SERVICE_UUID)
                        if (batteryService == null) {
                            // 尝试 HID Service
                            val hidService = gatt.getService(HID_SERVICE_UUID)
                            if (hidService != null) {
                                Log.d(TAG, "快速获取: 使用 HID Service 读取电量")
                                val reportChar = hidService.getCharacteristic(HID_REPORT_CHAR_UUID)
                                if (reportChar != null && reportChar.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                                    gatt.readCharacteristic(reportChar)
                                    return
                                }
                            }
                            Log.w(TAG, "快速获取: 设备无标准 Battery Service 且无 HID Report")
                            // 尝试枚举所有特征
                            tryReadAnyBatteryChar(gatt)
                            if (gatt.services.flatMap { it.characteristics }.none {
                                it.uuid == BATTERY_LEVEL_CHAR_UUID || it.uuid == HID_REPORT_CHAR_UUID
                            }) {
                                mainHandler.post { onResult(-1, false, "该设备不支持标准电量服务") }
                                gatt.disconnect()
                                gatt.close()
                            }
                            return
                        }

                        val batteryChar = batteryService.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)
                        if (batteryChar == null) {
                            Log.w(TAG, "快速获取: 设备无 Battery Level 特征")
                            mainHandler.post { onResult(-1, false, "该设备无法读取电量") }
                            gatt.disconnect()
                            gatt.close()
                            return
                        }

                        if (batteryChar.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                            Log.d(TAG, "快速获取: 读取电量特征值...")
                            gatt.readCharacteristic(batteryChar)
                        } else {
                            Log.w(TAG, "快速获取: 电量特征不支持 READ")
                            mainHandler.post { onResult(-1, false, "该设备不支持直接读取电量") }
                            gatt.disconnect()
                            gatt.close()
                        }
                    }

                    override fun onCharacteristicRead(
                        gatt: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int
                    ) {
                        val data = characteristic.value
                        when (characteristic.uuid) {
                            BATTERY_LEVEL_CHAR_UUID -> {
                                if (status == BluetoothGatt.GATT_SUCCESS) {
                                    val level = characteristic.getIntValue(
                                        BluetoothGattCharacteristic.FORMAT_UINT8, 0
                                    ).coerceIn(0, 100)
                                    Log.d(TAG, "快速获取: 电量 = $level%")
                                    mainHandler.post { onResult(level, false, null) }
                                } else {
                                    mainHandler.post { onResult(-1, false, "读取电量失败") }
                                }
                            }
                            HID_REPORT_CHAR_UUID -> {
                                if (status == BluetoothGatt.GATT_SUCCESS && data != null && data.isNotEmpty()) {
                                    val level = (data[0].toInt() and 0xFF).coerceIn(0, 100)
                                    Log.d(TAG, "快速获取(HID): 电量 = $level%")
                                    mainHandler.post { onResult(level, false, null) }
                                } else {
                                    mainHandler.post { onResult(-1, false, "HID读取失败") }
                                }
                            }
                            else -> {
                                if (data != null && data.size in 1..3) {
                                    val level = (data[0].toInt() and 0xFF).coerceIn(0, 100)
                                    mainHandler.post { onResult(level, false, null) }
                                } else {
                                    mainHandler.post { onResult(-1, false, "未知电量格式") }
                                }
                            }
                        }
                        gatt.disconnect()
                        gatt.close()
                    }
                }

                val transport = BluetoothDevice.TRANSPORT_AUTO
                val tempGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    device.connectGatt(context, false, quickFetchCallback, transport)
                } else {
                    device.connectGatt(context, false, quickFetchCallback)
                }

                // 超时保护
                delay(8000)
                try {
                    tempGatt.disconnect()
                    tempGatt.close()
                } catch (_: Exception) {}
            } catch (e: SecurityException) {
                mainHandler.post {
                    onResult(-1, false, "权限不足: ${e.message}")
                }
            } catch (e: Exception) {
                mainHandler.post {
                    onResult(-1, false, "获取失败: ${e.message}")
                }
            }
        }
    }

    private fun connectInternal(device: BluetoothDevice, scope: CoroutineScope) {
        // 【修复】防止重入：当系统蓝牙配对弹窗触发 Activity 重建时，
        // ViewModel init 的自动重连可能再次调用 connect，此锁阻止并发连接
        if (!connectingLock.compareAndSet(false, true)) {
            Log.w(TAG, "connectInternal 已有连接进行中，忽略重复调用")
            return
        }
        // 【修复】专用连接作用域：disconnect() 时整体取消，彻底终止挂起的配对等待 /
        // 旧 GATT 连接。否则用户快速切换不同设备时，上一次 connectInternal 协程仍存活，
        // 其残留的 createBond() 等待 + 后续 connectGatt 会再次弹出系统配对框，造成"多个弹窗"。
        connectionScope?.cancel()
        connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val connScope = connectionScope!!

        stopScan()
        _connectionState.value = ConnectionState.CONNECTING

        if (!hasConnectPermission()) {
            _connectionState.value = ConnectionState.DISCONNECTED
            connectingLock.set(false)
            connScope.launch { _errorMessage.emit("缺少\"连接蓝牙设备\"权限，请在系统设置中授予") }
            return
        }

        connScope.launch(Dispatchers.IO) {
            try {
                bluetoothGatt?.close()

                val currentBondState = device.bondState
                Log.d(TAG, "连接前配对状态: ${
                    when (currentBondState) {
                        BluetoothDevice.BOND_BONDED -> "已配对"
                        BluetoothDevice.BOND_BONDING -> "配对中"
                        else -> "未配对"
                    }
                } (${device.address})")

                // 【关键修复】不再于连接前"强制发起配对并阻塞等待 15s 让用户点系统弹窗"。
                // 手写笔电量(Battery Service 0x180F)多数无需配对即可读取。强制配对会导致：
                //   1) 切换设备时弹出突兀的配对框，用户误触/未在 15s 内点拨导致切换卡住；
                //   2) 因 connectingLock 被 15s 阻塞 + disconnect() 无法取消在途协程，
                //      用户反复点不同设备会堆积多个配对弹窗。
                // 改为：直接 GATT 连接；仅当真实读取因「未授权/未加密」失败时，
                // 才在 onCharacteristicRead/onDescriptorWrite 中惰性发起配对(ensureBondThenRetry)，
                // 配对成功后再读取电量。BOND_BONDING 时仍短暂等待已在进行中的配对完成，避免重复 createBond 触发的栈冲突。
                if (currentBondState == BluetoothDevice.BOND_BONDING) {
                    Log.w(TAG, "设备正在配对中(BOND_BONDING)，等待配对完成: ${device.address}")
                    try {
                        kotlinx.coroutines.withTimeout(15_000L) {
                            while (device.bondState == BluetoothDevice.BOND_BONDING) {
                                delay(500)
                            }
                        }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        Log.w(TAG, "配对进行中等待超时，继续尝试连接")
                    }
                }



                // 根据设备类型选择传输层（默认 AUTO；手写笔/HID 为双模设备）
                val transport = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    BluetoothDevice.TRANSPORT_AUTO
                } else 0

                Log.d(TAG, "连接设备: transport=${if (transport == BluetoothDevice.TRANSPORT_AUTO) "AUTO" else if (transport == BluetoothDevice.TRANSPORT_LE) "LE" else "DEFAULT"}, type=$connectedDeviceType")

                bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    device.connectGatt(context, false, gattCallback, transport)
                } else {
                    device.connectGatt(context, false, gattCallback)
                }

                // 超时保护：如果10秒后仍未连接成功，尝试备用传输模式
                delay(10_000)
                if (_connectionState.value != ConnectionState.CONNECTED &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (transport == BluetoothDevice.TRANSPORT_AUTO) {
                        // TRANSPORT_AUTO 失败，尝试强制 BLE
                        Log.d(TAG, "TRANSPORT_AUTO 连接超时，尝试 TRANSPORT_LE 备用...")
                        try { bluetoothGatt?.close() } catch (_: Exception) {}
                        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 【关键修复】CancellationException 必须重新抛出，不能被 catch(Exception) 吞掉
                // 否则协程取消后 BLE 操作可能仍在执行，导致未捕获异常 → 进程退出
                _connectionState.value = ConnectionState.DISCONNECTED
                throw e
            } catch (e: SecurityException) {
                _connectionState.value = ConnectionState.DISCONNECTED
                _errorMessage.tryEmit("连接失败: 权限不足")
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.DISCONNECTED
                _errorMessage.tryEmit("连接失败: ${e.message}")
            } finally {
                connectingLock.set(false)
            }
        }
    }

    fun disconnect() {
        CrashExitMonitor.enterContext("BLE_DISCONNECT")
        CrashExitMonitor.updateBleState("正在断开连接")
        connectingLock.set(false)
        connectionScope?.cancel()
        connectionScope = null
        chargingCheckJob?.cancel()
        chargingCheckJob = null
        batteryRetryJob?.cancel()
        batteryRetryJob = null
        previousBatteryLevel = -1
        hasHardwareCharging = false
        unregisterBondStateReceiver()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDeviceMac.value = null
        _resolvedDeviceName.value = null
        _deviceModel.value = null
        _deviceManufacturer.value = null
        _isCharging.value = false
        _batteryLevel.value = -1
        _multiBatteryLevels.value = emptyMap()
        _availableChannels.value = emptyList()
        batteryServiceCount = 0
        hidReportBuffer = ByteArray(0)
        CrashExitMonitor.updateBleState("已断开连接")
        CrashExitMonitor.exitContext("BLE_DISCONNECT")
        CrashExitMonitor.exitContext("BLE_SERVICE_DISCOVERY")
    }

    @SuppressLint("MissingPermission")
    private fun registerBondStateReceiver(device: BluetoothDevice) {
        unregisterBondStateReceiver()
        bondStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val bondDevice: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        if (bondDevice?.address != device.address) return
                        val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                        when (bondState) {
                            BluetoothDevice.BOND_BONDED -> {
                                Log.d(TAG, "配对成功: ${device.address}")
                                bleScope.launch { _errorMessage.emit("设备配对成功") }
                                // 【修复】配对成功后重新读取电量（之前因未授权失败），避免停留在无数据状态
                                bleScope.launch { reReadBatteryAfterBond() }
                            }

                        }
                    }
                }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.registerReceiver(bondStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(bondStateReceiver, filter)
        }
    }

    private fun unregisterBondStateReceiver() {
        try { bondStateReceiver?.let { context.unregisterReceiver(it) } } catch (e: Exception) { Log.e(TAG, "Unregister bond receiver error: ${e.message}") }
        bondStateReceiver = null
    }

    /**
     * 【修复】惰性配对：仅在真实读取/写入因缺少授权失败时调用。
     * - 已配对但报未授权（加密密钥过期等）：直接重试读取。
     * - 配对进行中：等待 BOND_BONDED 回调后由 reReadBatteryAfterBond 重试。
     * - 未配对：注册监听并发起系统配对（此时才弹框，且只会弹一次）。
     *
     * 这样避免了"连接前强制 createBond() + 阻塞 15s"导致的：切换卡顿、
     * 以及反复点击不同设备堆积多个配对弹窗的问题。
     */
    @SuppressLint("MissingPermission")
    private fun ensureBondThenRetry(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val device = gatt.device
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                Log.d(TAG, "惰性配对: 已配对，重试读取")
                try { gatt.readCharacteristic(characteristic) } catch (_: Exception) {}
            }
            BluetoothDevice.BOND_BONDING -> {
                Log.d(TAG, "惰性配对: 配对进行中，等待完成后重试")
            }
            else -> {
                Log.d(TAG, "惰性配对: 发起系统配对 (${device.address})")
                registerBondStateReceiver(device)
                try { device.createBond() } catch (e: Exception) { Log.e(TAG, "惰性配对失败: ${e.message}") }
            }
        }
    }

    /** 配对成功后，重新读取所有电量相关特征（标准 Battery Service + 荣耀手写笔自定义特征） */
    @SuppressLint("MissingPermission")
    private fun reReadBatteryAfterBond() {
        val gatt = bluetoothGatt ?: return
        if (_connectionState.value != ConnectionState.CONNECTED) return
        try {
            for (svc in gatt.services) {
                if (svc.uuid == BATTERY_SERVICE_UUID) {
                    svc.getCharacteristic(BATTERY_LEVEL_CHAR_UUID)?.let { gatt.readCharacteristic(it) }
                } else if (svc.uuid == HONOR_PENCIL_SERVICE_UUID) {
                    svc.getCharacteristic(HONOR_PENCIL_SERVICE_UUID)?.let { gatt.readCharacteristic(it) }
                }
            }
        } catch (e: Exception) { Log.e(TAG, "配对后重读电量异常: ${e.message}") }
    }

    // ============ BLE 扫描回调 ============

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) {
            val device = result.device
            val name = device.name ?: "未知设备"
            val hasBatteryService = result.scanRecord?.serviceUuids
                ?.any { it.uuid == BATTERY_SERVICE_UUID } == true
            // 屏蔽手机 / 电脑 / 干电池鼠标（BLE 扫描期 BluetoothClass 常为 null，此处尽力而为）
            if (shouldSkipDevice(device, hasBatteryService)) return
            val deviceType = classifyDevice(name, hasBatteryService, true)
            val scanResult = ScanResult(device = device, rssi = result.rssi,
                scanRecord = result.scanRecord, deviceName = name, deviceType = deviceType)
            seenDevices[device.address] = scanResult
            emitScanResultsThrottled()
        }

        override fun onScanFailed(errorCode: Int) {
            val errorMsg = when (errorCode) {
                ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "扫描已在运行中"
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "应用注册失败"
                ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "内部错误"
                ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "不支持 BLE 扫描"
                else -> "未知扫描错误: $errorCode"
            }
            Log.e(TAG, "BLE 扫描失败: $errorMsg")
            bleScope.launch { _errorMessage.emit("BLE 扫描失败: $errorMsg") }
        }
    }

    // ============ GATT 回调 (多通道支持) ============

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "Connection state: $status -> $newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        _connectedDeviceMac.value = gatt.device.address
                        _connectionState.value = ConnectionState.CONNECTED
                        CrashExitMonitor.exitContext("BLE_CONNECT")
                        CrashExitMonitor.enterContext("BLE_SERVICE_DISCOVERY")
                        CrashExitMonitor.updateBleState("已连接: ${gatt.device.name} (${gatt.device.address})")
                        batteryServiceCount = 0
                        // v4.1: 连接成功后尝试解析真实设备名称（修正"未知设备"）
                        tryResolveDeviceName(gatt.device)
                        // 【省电优化】请求低功耗连接参数：电量监控不需要高频数据，100-300ms 连接间隔足够
                        try {
                            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                        } catch (e: SecurityException) { /* 权限不足，跳过 */ }
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _connectedDeviceMac.value = null
                    _resolvedDeviceName.value = null
                    _batteryLevel.value = -1
                    _multiBatteryLevels.value = emptyMap()
                    _availableChannels.value = emptyList()
                    batteryServiceCount = 0
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            // 兜底拦截：扫描期 BluetoothClass 可能为 null 漏过手机/电脑/干电池鼠标，
            // 服务发现阶段基于真实 BluetoothClass 再次屏蔽，避免无意义的服务枚举与电量轮询。
            val hasBattery = gatt.services.any { it.uuid == BATTERY_SERVICE_UUID }
            if (shouldSkipDevice(gatt.device, hasBattery)) {
                Log.i(TAG, "服务发现兜底: 跳过被屏蔽设备 ${gatt.device.name}")
                return
            }

            // v4.1: 服务发现完成后再次尝试解析真实设备名称
            tryResolveDeviceName(gatt.device)

            readDeviceInfo(gatt)

            // 【核心】枚举所有 Battery Service 实例
            val batteryServices = gatt.services.filter {
                it.uuid == BATTERY_SERVICE_UUID
            }

            Log.d(TAG, "发现 ${batteryServices.size} 个 Battery Service 实例")

            when {
                batteryServices.size >= 3 -> {
                    // 3个 Battery Service = 左耳 + 右耳 + 充电仓 (如某些耳机)
                    enableMultiBatteryNotifications(gatt, batteryServices)
                }
                batteryServices.size == 2 -> {
                    // 2个 Battery Service = 左耳 + 右耳 (无独立充电仓，或充电仓+单耳)
                    enableMultiBatteryNotifications(gatt, batteryServices)
                }
                batteryServices.size == 1 -> {
                    // 1个 Battery Service → 尝试 HID 方式获取多通道
                    val hidService = gatt.getService(HID_SERVICE_UUID)
                    if (hidService != null && connectedDeviceType == DeviceType.AUDIO) {
                        Log.d(TAG, "发现 HID Service，尝试获取多通道电量")
                        enableHidBatteryNotifications(gatt, hidService)
                    }
                    // 同时也启用标准 Battery Service
                    enableBatteryNotifications(gatt, batteryServices.first(), BatteryChannel.SINGLE)
                }
                else -> {
                    // 没有 Battery Service → 尝试 HID 或厂商自定义
                    tryAlternativeServices(gatt)
                }
            }

            // 【兜底】服务发现完成 3 秒后，若仍未获取到电量，强制重试一次
            scheduleBatteryReadRetry(gatt)

            // 【关键修复】无论是否有标准 Battery Service，都尝试查找并订阅
            // 荣耀/华为手写笔的自定义充电状态特征。
            // 该特征位于独立 "Unknown Service" 中，包含明确的充放电标志位（01=充/00=放），
            // 是阈值提醒功能正常工作的前提条件。
            enableHonorPencilNotification(gatt)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            // 【修复】读取因缺少配对授权/加密失败时，惰性发起配对（仅此时才弹系统配对框，且只弹一次），
            // 配对成功后在 BondReceiver 中重试读取。5=GATT_INSUFFICIENT_AUTHENTICATION, 15=GATT_INSUFFICIENT_ENCRYPTION
            if (status == 5 || status == 15) {
                Log.w(TAG, "读取特征需配对授权(status=$status)，惰性发起配对: ${gatt.device.address}")
                ensureBondThenRetry(gatt, characteristic)
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) return


            when (characteristic.uuid) {
                BATTERY_LEVEL_CHAR_UUID -> {
                    CrashExitMonitor.enterContext("BATTERY_QUERY:BLE_READ")
                    try {
                        // 根据特征所属的 service instance ID 判断通道
                        val service = characteristic.service
                        val channel = inferChannelFromService(gatt, service)
                        val level = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                        updateBatteryLevel(channel, level)
                    } finally {
                        CrashExitMonitor.exitContext("BATTERY_QUERY:BLE_READ")
                    }
                }
                HID_REPORT_CHAR_UUID -> {
                    parseHidBatteryReport(gatt, characteristic.value ?: return)
                }
                MODEL_NUMBER_CHAR_UUID -> {
                    val model = characteristic.getStringValue(0) ?: ""
                    _deviceModel.value = model
                    Log.d(TAG, "设备型号: $model")
                    resolveNameFromDeviceInfo(gatt.device)
                }
                MANUFACTURER_NAME_CHAR_UUID -> {
                    val mfr = characteristic.getStringValue(0)
                    _deviceManufacturer.value = mfr
                    Log.d(TAG, "制造商: $mfr")
                    resolveNameFromDeviceInfo(gatt.device)
                }
                DEVICE_NAME_CHAR_UUID -> {
                    val deviceName = characteristic.getStringValue(0)?.trim() ?: ""
                    if (deviceName.isNotBlank() && deviceName != "未知设备" && !isMacAddress(deviceName)) {
                        Log.i(TAG, "从 GATT Device Name (0x2A00) 读取到真实名称: '$deviceName'")
                        _resolvedDeviceName.value = deviceName
                    }
                }
                SERIAL_NUMBER_CHAR_UUID -> {
                    val sn = characteristic.getStringValue(0) ?: ""
                    if (sn.isNotBlank()) {
                        _serialNumber.value = sn
                        Log.d(TAG, "序列号 (Serial Number): $sn")
                    }
                }
                PNP_ID_CHAR_UUID -> {
                    val pnpData = characteristic.value
                    if (pnpData != null && pnpData.size >= 4) {
                        val vendorId = ((pnpData[1].toInt() and 0xFF) shl 8) or (pnpData[0].toInt() and 0xFF)
                        Log.d(TAG, "厂商ID (PNP): 0x${vendorId.toString(16)}")
                    }
                }
                else -> {
                    // 厂商自定义特征内的电量
                    if (characteristic.uuid == HONOR_PENCIL_SERVICE_UUID) {
                        parseHonorPencilData(characteristic.value ?: return)
                    } else {
                        parseVendorBatteryData(gatt, characteristic)
                    }
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            when (characteristic.uuid) {
                BATTERY_LEVEL_CHAR_UUID -> {
                    CrashExitMonitor.enterContext("BATTERY_QUERY:BLE_NOTIFY")
                    try {
                        val service = characteristic.service
                        val channel = inferChannelFromService(gatt, service)
                        val level = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0)
                        updateBatteryLevel(channel, level)
                    } finally {
                        CrashExitMonitor.exitContext("BATTERY_QUERY:BLE_NOTIFY")
                    }
                }
                HID_REPORT_CHAR_UUID -> {
                    parseHidBatteryReport(gatt, characteristic.value ?: return)
                }
                // 【关键修复】荣耀/华为手写笔自定义特征 — 直接提取充电状态
                HONOR_PENCIL_SERVICE_UUID -> {
                    parseHonorPencilData(characteristic.value ?: return)
                }
                else -> {
                    parseVendorBatteryData(gatt, characteristic)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // 【修复】描述符(CCCD)写入因未配对授权失败时，惰性发起配对
            if (status == 5 || status == 15) {
                Log.w(TAG, "描述符写入需配对授权(status=$status)，惰性发起配对")
                ensureBondThenRetry(gatt, descriptor.characteristic)
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val char = descriptor.characteristic

            when (char.uuid) {
                BATTERY_LEVEL_CHAR_UUID -> {
                    gatt.readCharacteristic(char)
                    readChargingStatus(gatt)
                }
                HID_REPORT_CHAR_UUID -> {
                    gatt.readCharacteristic(char)
                }
                // 荣耀手写笔：启用通知后立即读取首帧数据获取充电状态
                HONOR_PENCIL_SERVICE_UUID -> {
                    gatt.readCharacteristic(char)
                }
                else -> {
                    // 厂商自定义特征
                    gatt.readCharacteristic(char)
                }
            }
        }
    }

    // ============ 通道推断逻辑 ============

    /**
     * 根据 service 在 GATT 服务列表中的位置推断通道类型
     * 适用于有多个 Battery Service 实例的设备
     */
    private fun inferChannelFromService(gatt: BluetoothGatt, targetService: BluetoothGattService): BatteryChannel {
        val batteryServices = gatt.services.filter { it.uuid == BATTERY_SERVICE_UUID }
        val index = batteryServices.indexOfFirst { it.instanceId == targetService.instanceId }

        if (batteryServices.size >= 3) {
            return when (index) {
                0 -> BatteryChannel.LEFT
                1 -> BatteryChannel.RIGHT
                2 -> BatteryChannel.CASE
                else -> BatteryChannel.OTHER
            }
        }
        if (batteryServices.size == 2) {
            return when (index) {
                0 -> BatteryChannel.LEFT
                1 -> BatteryChannel.RIGHT
                else -> BatteryChannel.OTHER
            }
        }
        return BatteryChannel.SINGLE
    }

    // ============ 多 Battery Service 通知启用 ============

    private fun enableMultiBatteryNotifications(gatt: BluetoothGatt, services: List<BluetoothGattService>) {
        val channels = mutableListOf<BatteryChannel>()

        services.forEachIndexed { index, service ->
            val channel = when {
                services.size >= 3 -> when (index) {
                    0 -> BatteryChannel.LEFT
                    1 -> BatteryChannel.RIGHT
                    2 -> BatteryChannel.CASE
                    else -> BatteryChannel.OTHER
                }
                services.size == 2 -> when (index) {
                    0 -> BatteryChannel.LEFT
                    1 -> BatteryChannel.RIGHT
                    else -> BatteryChannel.OTHER
                }
                else -> BatteryChannel.SINGLE
            }
            channels.add(channel)
            enableBatteryNotifications(gatt, service, channel)
        }

        _availableChannels.value = channels
        Log.d(TAG, "多通道电量监控已启用: $channels")
    }

    private fun enableBatteryNotifications(gatt: BluetoothGatt, service: BluetoothGattService, channel: BatteryChannel) {
        val batteryLevelChar = service.getCharacteristic(BATTERY_LEVEL_CHAR_UUID) ?: return

        val hasNotify = batteryLevelChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val hasRead = batteryLevelChar.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0

        if (hasNotify) {
            // 启用通知 — 写入 CCCD 描述符
            gatt.setCharacteristicNotification(batteryLevelChar, true)
            batteryServiceCount++

            val descriptor = batteryLevelChar.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
                // 注意：不要在此处立即调用 readCharacteristic！
                // Android GATT 栈同一时间只能处理一个操作。
                // writeDescriptor 是异步的，之后 onDescriptorWrite 回调
                // 会触发 readCharacteristic 来获取首帧电量。
                Log.d(TAG, "启用 ${BatteryChannel.displayName(channel)} 通知 + 描述符写入, 首帧电量由 onDescriptorWrite 触发")
            } else {
                // 无 CCCD 描述符（极少见），尝试直接读取
                if (hasRead) {
                    Log.d(TAG, "无 CCCD 描述符, 直接读取 ${BatteryChannel.displayName(channel)} 电量")
                    gatt.readCharacteristic(batteryLevelChar)
                } else {
                    Log.w(TAG, "${BatteryChannel.displayName(channel)}: 支持 NOTIFY 但无 CCCD, 且不支持 READ, 无法获取首帧电量")
                }
            }
        } else if (hasRead) {
            // 仅支持读取，不支持通知 — 直接读取
            Log.d(TAG, "直接读取 ${BatteryChannel.displayName(channel)} 电量特征值...")
            gatt.readCharacteristic(batteryLevelChar)
        } else {
            Log.w(TAG, "Battery Level 特征既不支持 READ 也不支持 NOTIFY，无法获取电量")
        }
    }

    // ============ HID Service 电量解析 ============

    private fun enableHidBatteryNotifications(gatt: BluetoothGatt, hidService: BluetoothGattService) {
        // 尝试启用 HID Report 通知
        val reportChar = hidService.getCharacteristic(HID_REPORT_CHAR_UUID)
        if (reportChar != null) {
            gatt.setCharacteristicNotification(reportChar, true)
            val descriptor = reportChar.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }
            Log.d(TAG, "启用 HID Report 电量通知")
        }

        // 也检查 Input Report (有些设备把电量放在 Input Report 里)
        for (char in hidService.characteristics) {
            Log.d(TAG, "HID 特征: ${char.uuid}, 属性: 0x${char.properties.toString(16)}")
        }
    }

    /**
     * 解析 HID Battery Report 数据
     *
     * 常见格式:
     * - Apple AirPods: [左耳%, 右耳%, 充电仓%, ...]
     * - Samsung Galaxy Buds: [左耳%, 右耳%, 充电仓%, ...]
     * - 通用格式: 每个字节代表一个电池电量
     */
    private fun parseHidBatteryReport(gatt: BluetoothGatt, data: ByteArray) {
        if (data.isEmpty()) return

        Log.d(TAG, "HID Report: ${data.joinToString(" ") { "%02X".format(it) }}")

        hidReportBuffer = data

        val channels = mutableListOf<BatteryChannel>()
        val levels = mutableMapOf<BatteryChannel, Int>()

        // 尝试多种常见格式解析

        // 格式1: 简单多字节 [左, 右, 仓] (最常见, 如 AirPods)
        // 【修复】原判断 data.size >= 3 && data.any { in 0..100 } 过宽：
        // 任意 3 字节(甚至更长的状态包被截断)只要有一个值落在 0..100 就被当成
        // [左,右,仓] 多通道，导致单电池设备被误报多通道电量。
        // 收紧为：必须恰好 3 字节且三字节全部落在 0..100，才视为 TWS 三通道布局。
        if (data.size == 3 &&
            (data[0].toInt() and 0xFF) in 0..100 &&
            (data[1].toInt() and 0xFF) in 0..100 &&
            (data[2].toInt() and 0xFF) in 0..100
        ) {
            val left = data[0].toInt() and 0xFF
            val right = data[1].toInt() and 0xFF
            val case = data[2].toInt() and 0xFF

            if (left in 0..100 && right in 0..100) {
                levels[BatteryChannel.LEFT] = left
                levels[BatteryChannel.RIGHT] = right
                channels.add(BatteryChannel.LEFT)
                channels.add(BatteryChannel.RIGHT)
                if (case in 0..100) {
                    levels[BatteryChannel.CASE] = case
                    channels.add(BatteryChannel.CASE)
                }
            }
        }

        // 格式2: 只有2字节 [左, 右]
        if (levels.isEmpty() && data.size >= 2) {
            val left = data[0].toInt() and 0xFF
            val right = data[1].toInt() and 0xFF
            if (left in 0..100 && right in 0..100) {
                levels[BatteryChannel.LEFT] = left
                levels[BatteryChannel.RIGHT] = right
                channels.add(BatteryChannel.LEFT)
                channels.add(BatteryChannel.RIGHT)
            }
        }

        // 格式3: 单字节 (只有主电池)
        if (levels.isEmpty() && data.size >= 1) {
            val level = data[0].toInt() and 0xFF
            if (level in 0..100) {
                levels[BatteryChannel.SINGLE] = level
                channels.add(BatteryChannel.SINGLE)
            }
        }

        if (levels.isNotEmpty()) {
            // 更新多通道状态
            _multiBatteryLevels.value = levels.mapValues { it.value.coerceIn(0, 100) }
            _availableChannels.value = channels

            // 【优化】主电量显示最低值（保守策略），而非无意义的平均值
            // 对 TWS 耳机等场景，平均值（如 63%）会误导用户忽略已耗尽的那一侧
            val minLevel = levels.values.minOrNull()?.coerceIn(0, 100) ?: -1
            _batteryLevel.value = minLevel
        }
    }

    // ============ 荣耀/华为 手写笔自定义协议解析 ============

    /**
     * 查找并订阅荣耀/华为手写笔的自定义充电状态特征
     * 该特征位于 Unknown Service (0000fe42-cc7a-482a-984a-7f2ed5b3e512) 内
     */
    private fun enableHonorPencilNotification(gatt: BluetoothGatt) {
        // 遍历所有服务查找荣耀手写笔特征
        for (service in gatt.services) {
            for (char in service.characteristics) {
                if (char.uuid == HONOR_PENCIL_SERVICE_UUID) {
                    Log.d(TAG, "发现 HONOR Pencil 充电状态特征 in 服务 ${service.uuid}")

                    val hasNotify = char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                    val hasRead = char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0

                    if (hasNotify) {
                        gatt.setCharacteristicNotification(char, true)
                        val descriptor = char.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                            Log.d(TAG, "已启用 HONOR Pencil 充电状态通知")
                        }
                        return
                    } else if (hasRead) {
                        Log.d(TAG, "HONOR Pencil 特征仅支持 READ，立即读取一次")
                        gatt.readCharacteristic(char)
                        return
                    } else {
                        Log.w(TAG, "HONOR Pencil 特征不支持 READ 也不支持 NOTIFY")
                        return
                    }
                }
            }
        }
        Log.d(TAG, "未找到 HONOR Pencil 自定义充电特征（可能非荣耀手写笔设备）")
    }

    /**
     * 解析荣耀 Magic-Pencil / 华为 M-Pencil 自定义特征数据
     *
     * 协议格式（来自 nRF Connect 实测抓包）:
     *   字节0: 0x2D — 报告ID / 固定前缀
     *   字节1: 0x04 — 数据类型标识
     *   字节2: 子状态码（如 0x01），**不是电量百分比**
     *   字节3: 状态位（如是否在仓内等）
     *   字节4: **充电状态** — 0x01=充电中, 0x00=放电(使用中)
     *
     * 示例:
     *   2D-04-01-01-01 → 充电中（最后字节=01）
     *   2D-04-01-01-00 → 放电/使用中（最后字节=00）
     *
     * 【关键】此方法仅负责设置 _isCharging 充电状态标志位。
     * 电量值始终由标准 Battery Service (0x180F) 提供，
     * 绝不从此自定义协议中提取或覆盖电量，避免子状态码被误读为电量百分比。
     * 之前该 UUID 的通知走 parseVendorBatteryData() 兜底，因 data.size=5 > 3 被直接丢弃，
     * 导致 _isCharging 永远依赖不可靠的趋势推断，阈值提醒无法触发。
     */
    /**
     * 【逆向增强】读取荣耀系统态充电位 (Settings.Global `honor_bt_pencil_charging_state`)。
     *
     * 逆向依据：系统蓝牙包完成 GATT 握手后会把充电位写入 Settings.Global
     * （见 `代码逆向/全逻辑闭环.md` §2.2）。
     * 仅在自研 0xFE42 协议解析缺失/歧义时作兜底，**绝不覆盖已由硬件确认的值**。
     * 系统只维护"当前这一支笔"，MAC 不一致说明是残留值 → 返回 null 拒绝使用。
     */
    private fun systemChargingOrNull(): Boolean? {
        val state = HonorSystemPenStateReader.read(context)
        if (!state.available) return null
        if (!HonorSystemPenStateReader.matchesMac(state, _connectedDeviceMac.value)) return null
        return if (state.hasValidCharging) state.isCharging else null
    }

    private fun parseHonorPencilData(data: ByteArray) {
        if (data.isEmpty()) return

        Log.d(TAG, "HONOR Pencil 数据: ${data.joinToString(" ") { "%02X".format(it) }}")

        // 格式校验：至少5字节，且前缀匹配 0x2D 0x04
        if (data.size >= 5 && data[0] == 0x2D.toByte() && data[1] == 0x04.toByte()) {
            // 字节4：充电状态标志位
            val chargingFlag = data[4].toInt() and 0xFF
            val isCurrentlyCharging = (chargingFlag == 0x01)

            // 【关键-修复】仅设置充电状态，绝不更新电量值。
            // 字节2是子状态码（如 0x01），不是电量百分比。
            // 电量值由标准 Battery Service (0x180F) 独立提供。
            val oldCharging = _isCharging.value
            _isCharging.value = isCurrentlyCharging

            // 【省电优化】硬件充电状态已可用，禁用趋势推断避免覆盖冲突
            hasHardwareCharging = true

            Log.d(TAG, "HONOR Pencil 充电状态: $isCurrentlyCharging " +
                    "(原始字节=0x${chargingFlag.toString(16)}, 变化=${oldCharging != isCurrentlyCharging})")

            // 【逆向增强】与荣耀系统态充电位交叉校验（仅记录差异，硬件直读值仍优先）
            val sysCharging = systemChargingOrNull()
            if (sysCharging != null && sysCharging != isCurrentlyCharging) {
                Log.w(TAG, "充电状态与系统读数不一致: 硬件=$isCurrentlyCharging, 系统=$sysCharging（以硬件为准）")
            }
        } else {
            // 非标准格式：尝试宽松解析 — 取最后一个有效字节作为状态
            Log.w(TAG, "HONOR Pencil 数据格式异常，尝试宽松解析 (${data.size} 字节)")
            // 【修复】原逻辑把任意 2 字节数据的最后一字节 == 0x01 直接当"充电中"，
            // 其它厂商 2 字节数据(如状态/错误码)会被误判为充电。
            // 收紧：仅当末字节明确为 0x00(未充)或 0x01(充电)时才更新状态，
            // 其它取值视为未知，保持原 _isCharging 不变，避免误标充电。
            if (data.size >= 2) {
                val lastByte = data[data.size - 1].toInt() and 0xFF
                if (lastByte == 0x00 || lastByte == 0x01) {
                    _isCharging.value = (lastByte == 0x01)
                    Log.d(TAG, "HONOR Pencil 充电状态(宽松): ${_isCharging.value}")
                } else {
                    Log.d(TAG, "HONOR Pencil 末字节=$lastByte 非充电状态标志，宽松解析无结论")
                    // 【逆向增强】协议歧义 → 用荣耀系统态充电位兜底
                    val sysCharging = systemChargingOrNull()
                    if (sysCharging != null) {
                        _isCharging.value = sysCharging
                        hasHardwareCharging = true
                        Log.d(TAG, "HONOR Pencil 充电状态(系统兜底): $sysCharging")
                    } else {
                        Log.d(TAG, "系统态亦无有效充电位，保持原状态不变")
                    }
                }
            }
            // 【修复】宽松解析路径也绝不从此协议中提取电量值
        }
    }

    // ============ 厂商自定义服务 ============

    private fun tryAlternativeServices(gatt: BluetoothGatt) {
        Log.d(TAG, "未发现标准 Battery Service，尝试厂商自定义服务...")

        // 枚举所有服务和特征
        for (service in gatt.services) {
            Log.d(TAG, "服务: ${service.uuid}")
            for (char in service.characteristics) {
                Log.d(TAG, "  特征: ${char.uuid} (属性: 0x${char.properties.toString(16)})")

                // 如果特征支持通知/读取且可能是电量特征
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0 ||
                    char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                ) {
                    // 尝试读取非标准特征的值
                    tryReadCharacteristic(gatt, char)
                }
            }
        }
    }

    private fun tryReadCharacteristic(gatt: BluetoothGatt, char: BluetoothGattCharacteristic) {
        // 尝试启用通知（如果是 notify 类型）
        if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            gatt.setCharacteristicNotification(char, true)
            val descriptor = char.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (descriptor != null) {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        }
        // 尝试直接读取
        if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
            gatt.readCharacteristic(char)
        }
    }

    private fun parseVendorBatteryData(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val data = characteristic.value ?: return
        val uuid = characteristic.uuid.toString().uppercase()

        Log.d(TAG, "厂商数据: $uuid = ${data.joinToString(" ") { "%02X".format(it) }}")

        // 尝试常见厂商格式
        // 如果数据短且值在 0-100 范围，可能是电量
        if (data.size in 1..3) {
            val possibleLevels = data.map { it.toInt() and 0xFF }.filter { it in 0..100 }
            if (possibleLevels.size == 1) {
                updateBatteryLevel(BatteryChannel.SINGLE, possibleLevels[0])
            } else if (possibleLevels.size >= 2) {
                val channels = mutableListOf<BatteryChannel>()
                val levels = mutableMapOf<BatteryChannel, Int>()

                possibleLevels.forEachIndexed { index, level ->
                    val channel = when (index) {
                        0 -> BatteryChannel.LEFT
                        1 -> BatteryChannel.RIGHT
                        2 -> BatteryChannel.CASE
                        else -> BatteryChannel.OTHER
                    }
                    levels[channel] = level
                    channels.add(channel)
                }
                _multiBatteryLevels.value = levels
                _availableChannels.value = channels
                _batteryLevel.value = possibleLevels.minOrNull()?.coerceIn(0, 100) ?: -1
            }
        }
    }

    /**
     * 快速获取场景：无标准 Battery Service 时，尝试读取任何可能是电量的特征
     */
    private fun tryReadAnyBatteryChar(gatt: BluetoothGatt) {
        for (svc in gatt.services) {
            for (char in svc.characteristics) {
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                    gatt.readCharacteristic(char)
                    return
                }
            }
        }
    }

    // ============ 统一电量更新 ============

    private fun updateBatteryLevel(channel: BatteryChannel, level: Int) {
        val clampedLevel = level.coerceIn(0, 100)
        val current = _multiBatteryLevels.value.toMutableMap()
        current[channel] = clampedLevel
        _multiBatteryLevels.value = current

        // 更新可用通道列表
        val channels = _availableChannels.value.toMutableList()
        if (channel !in channels) {
            channels.add(channel)
            _availableChannels.value = channels
        }

        // 主电量 = SINGLE 值，或多通道最低值（保守策略）
        val mainLevel = when {
            channel == BatteryChannel.SINGLE -> clampedLevel
            current.size > 1 -> current.values.minOrNull()?.coerceIn(0, 100) ?: clampedLevel
            else -> clampedLevel
        }
        _batteryLevel.value = mainLevel

        Log.d(TAG, "${BatteryChannel.displayName(channel)} 电量: $clampedLevel%")
    }

    // ============ 电量读取兜底重试 ============

    /**
     * 服务发现完成后延迟重试读取电量。
     *
     * 场景：当设备支持 NOTIFY 但不支持 READ 时，首帧电量依赖
     * writeDescriptor → onDescriptorWrite → readCharacteristic 链路。
     * 若此链路因设备兼容性问题未触发，3 秒后强制执行一次性补救读取。
     */
    private fun scheduleBatteryReadRetry(gatt: BluetoothGatt) {
        batteryRetryJob?.cancel()
        batteryRetryJob = bleScope.launch {
            delay(3000) // 等待 descriptor write + read 链路完成

            // 检查是否已有有效电量
            if (_batteryLevel.value >= 0 && _multiBatteryLevels.value.isNotEmpty()) {
                Log.d(TAG, "电量已获取，跳过重试")
                return@launch
            }

            Log.d(TAG, "电量未获取，执行兜底重试读取...")

            try {
                // 重试1: 标准 Battery Service
                val batteryServices = gatt.services.filter { it.uuid == BATTERY_SERVICE_UUID }
                for (service in batteryServices) {
                    val char = service.getCharacteristic(BATTERY_LEVEL_CHAR_UUID) ?: continue
                    if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                        Log.d(TAG, "重试: 读取标准 Battery Level 特征值")
                        gatt.readCharacteristic(char)
                        delay(1000) // 等待读取完成
                        if (_batteryLevel.value >= 0) return@launch
                    }
                }

                // 重试2: HID Report
                val hidService = gatt.getService(HID_SERVICE_UUID)
                if (hidService != null) {
                    val reportChar = hidService.getCharacteristic(HID_REPORT_CHAR_UUID)
                    if (reportChar != null && reportChar.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                        Log.d(TAG, "重试: 读取 HID Report 特征值")
                        gatt.readCharacteristic(reportChar)
                        delay(1000)
                        if (_batteryLevel.value >= 0) return@launch
                    }
                }

                if (_batteryLevel.value < 0) {
                    Log.w(TAG, "重试失败: 仍无法获取电量, 请尝试手动点击「获取电量」")
                }
            } catch (e: Exception) {
                Log.e(TAG, "重试读取电量异常: ${e.message}")
            }
        }
    }

    /**
     * v4.1: 连接成功后尝试解析真实设备名称
     *
     * 问题背景：BLE 扫描时 device.name 可能为 null（显示"未知设备"），
     * 但连接后 Android 系统可能从 GATT 层或配对信息中获取到真实名称。
     * 此方法在连接成功和服务发现两个时机检测名称变化，
     * 通过 [resolvedDeviceName] StateFlow 通知 ViewModel 更新数据库。
     */
    private fun tryResolveDeviceName(device: BluetoothDevice) {
        try {
            val rawName = device.name
            // 有效的可读名称（非 MAC 地址、非"未知设备"、非空）直接采用
            if (!rawName.isNullOrBlank() && rawName != "未知设备" && !isMacAddress(rawName)) {
                Log.i(TAG, "v4.1 解析到真实设备名称: '$rawName' (MAC=${device.address})")
                _resolvedDeviceName.value = rawName
                return
            }
            // 华为/荣耀手写笔常见情况：device.name 为空或退化为 MAC 地址，
            // 需根据 GATT Device Info 参数 (Manufacturer / Model) 推断真实名称
            resolveNameFromDeviceInfo(device)
        } catch (e: SecurityException) {
            Log.w(TAG, "v4.1 读取设备名称权限不足: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "v4.1 解析设备名称异常: ${e.message}")
        }
    }

    /** 判断字符串是否为 MAC 地址 (XX:XX:XX:XX:XX:XX) */
    private fun isMacAddress(s: String): Boolean {
        return s.matches(Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$"))
    }

    /**
     * 当 device.name 无法作为名称时，依据已读取的 GATT Device Info
     * (Manufacturer Name / Model Number) 推断真实设备名称。
     * 典型场景：华为 M-Pencil / 荣耀 Magic-Pencil 广播不含可读设备名，
     * 但 Manufacturer=HUAWEI/HONOR、Model 含 M-Pencil。
     * 通用场景：用 "Manufacturer Model" 组合名称。
     */
    private fun resolveNameFromDeviceInfo(device: BluetoothDevice) {
        val mfr = _deviceManufacturer.value
        val model = _deviceModel.value
        val resolved = when {
            // 华为 / 荣耀 手写笔：组合成可读名称
            mfr?.contains("HUAWEI", true) == true ->
                "HUAWEI " + (model?.takeIf { it.isNotBlank() } ?: "M-Pencil")
            mfr?.contains("HONOR", true) == true ->
                "HONOR " + (model?.takeIf { it.isNotBlank() } ?: "Magic-Pencil")
            !model.isNullOrBlank() -> model
            !mfr.isNullOrBlank() -> mfr
            else -> null
        }
        if (!resolved.isNullOrBlank() && resolved != "未知设备" && !isMacAddress(resolved)) {
            Log.i(TAG, "v4.1 由 BLE 参数推断设备名称: '$resolved' (MAC=${device.address}, mfr=$mfr, model=$model)")
            _resolvedDeviceName.value = resolved
        } else {
            Log.d(TAG, "v4.1 仍无法推断设备名称 (MAC=${device.address})")
        }
    }

    // ============ 设备信息读取 ============

    private fun readDeviceInfo(gatt: BluetoothGatt) {
        // 优先读取 Generic Access Service 的 Device Name (0x2A00) — 华为/荣耀手写笔真实名称在此
        val genericAccessService = gatt.getService(GENERIC_ACCESS_SERVICE_UUID)
        genericAccessService?.getCharacteristic(DEVICE_NAME_CHAR_UUID)?.let { gatt.readCharacteristic(it) }

        val deviceInfoService = gatt.getService(DEVICE_INFO_SERVICE_UUID) ?: return
        deviceInfoService.getCharacteristic(MODEL_NUMBER_CHAR_UUID)?.let { gatt.readCharacteristic(it) }
        deviceInfoService.getCharacteristic(MANUFACTURER_NAME_CHAR_UUID)?.let { gatt.readCharacteristic(it) }
        deviceInfoService.getCharacteristic(SERIAL_NUMBER_CHAR_UUID)?.let { gatt.readCharacteristic(it) }
        deviceInfoService.getCharacteristic(PNP_ID_CHAR_UUID)?.let { gatt.readCharacteristic(it) }
    }

    // ============ 充电状态检测 ============

    private fun readChargingStatus(gatt: BluetoothGatt) {
        val batteryService = gatt.getService(BATTERY_SERVICE_UUID) ?: return
        for (characteristic in batteryService.characteristics) {
            Log.d(TAG, "BatteryService 特征: ${characteristic.uuid}")
        }
        startChargingDetection()
    }

    private fun startChargingDetection() {
        // 【省电优化】若已有硬件充电状态协议（如荣耀/华为 0xFE42），
        // 跳过趋势推断，避免硬件状态与推断互相覆盖导致充电状态错误
        if (hasHardwareCharging) {
            Log.d(TAG, "硬件充电状态已可用，跳过趋势推断")
            return
        }

        // 使用连接生命周期的专用 scope，确保 disconnect 或重连时旧协程被彻底取消
        connectionScope?.cancel()
        connectionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        chargingCheckJob = connectionScope!!.launch {
            var consecutiveUp = 0
            var consecutiveDown = 0

            _batteryLevel.collect { level ->
                if (level < 0 || level > 100) return@collect

                if (previousBatteryLevel in 0..100) {
                    val change = level - previousBatteryLevel
                    when {
                        change >= 2 -> {
                            consecutiveUp++
                            consecutiveDown = 0
                            if (consecutiveUp >= 1) _isCharging.value = true
                        }
                        change == 1 -> {
                            consecutiveUp++
                            consecutiveDown = 0
                            if (consecutiveUp >= 2) _isCharging.value = true
                        }
                        change <= -2 -> {
                            consecutiveDown++
                            consecutiveUp = 0
                            if (consecutiveDown >= 1) _isCharging.value = false
                        }
                        change == -1 -> {
                            consecutiveDown++
                            consecutiveUp = 0
                            if (consecutiveDown >= 2) _isCharging.value = false
                        }
                    }
                }
                previousBatteryLevel = level
            }
        }
    }
}
