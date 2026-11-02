package com.batteryanalyzer.viewmodel

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.batteryanalyzer.ble.BleManager
import com.batteryanalyzer.ble.ConnectionState
import com.batteryanalyzer.data.BatteryRepository
import com.batteryanalyzer.data.PreferencesManager
import com.batteryanalyzer.model.*
import com.batteryanalyzer.notification.BatteryNotificationManager
import com.batteryanalyzer.service.BatteryMonitorService
import com.batteryanalyzer.service.FloatingBatteryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主 ViewModel - 管理所有业务逻辑
 * 支持多设备切换，选中设备持久化
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val repository = BatteryRepository(application)
    val bleManager = BleManager.getInstance(application)
    val notificationManager = BatteryNotificationManager(application)
    val preferencesManager = PreferencesManager(application)

    // ============ UI 状态 ============

    // 设备列表
    val allDevices: StateFlow<List<DeviceInfo>> = repository.getAllDevices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 扫描结果
    val scanResults = bleManager.scanResults

    // 连接状态
    val connectionState = bleManager.connectionState

    // 当前已连接设备的 MAC 地址（用于精确判断哪个设备在线）
    val connectedDeviceMac = bleManager.connectedDeviceMac

    // 当前电量（主电量，多通道时取平均值）
    val currentBatteryLevel = bleManager.batteryLevel

    // 【新增】多通道电量：Map<BatteryChannel, 电量值>
    val multiBatteryLevels = bleManager.multiBatteryLevels

    // 【新增】可用通道列表
    val availableChannels = bleManager.availableChannels

    // 是否充电中
    val isCharging = bleManager.isCharging

    // 错误消息
    val errorMessage = bleManager.errorMessage

    // 当前选中的设备 ID (持久化到 DataStore)
    private val _selectedDeviceId = MutableStateFlow(-1L)
    val selectedDeviceId: StateFlow<Long> = _selectedDeviceId.asStateFlow()

    // v5.0: 键值映射页面使用的设备列表（含输入设备）
    val allDevicesForKeyMapping = repository.getAllDevicesForKeyMapping()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 电量记录 (基于选中设备)
    val batteryRecords: StateFlow<List<BatteryRecord>> = _selectedDeviceId
        .flatMapLatest { id ->
            if (id > 0) repository.getRecordsByDevice(id)
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 当前设备 (基于选中设备) — v7: 改为可观察 Flow，容量/名称修改后实时刷新各屏 UI
    val currentDevice: StateFlow<DeviceInfo?> = _selectedDeviceId
        .flatMapLatest { id ->
            if (id > 0) repository.observeDevice(id) else flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // 连接到当前设备的 BLE 设备对象
    private var connectedBleDevice: BluetoothDevice? = null

    // 分析数据 (基于选中设备)
    private val _chargingSessions = MutableStateFlow<List<ChargingSession>>(emptyList())
    val chargingSessions: StateFlow<List<ChargingSession>> = _chargingSessions.asStateFlow()

    private val _dischargeSessions = MutableStateFlow<List<DischargeSession>>(emptyList())
    val dischargeSessions: StateFlow<List<DischargeSession>> = _dischargeSessions.asStateFlow()

    private val _batteryHealth = MutableStateFlow(100f)
    val batteryHealth: StateFlow<Float> = _batteryHealth.asStateFlow()

    private val _healthResult = MutableStateFlow<BatteryRepository.BatteryHealthResult?>(null)
    val healthResult: StateFlow<BatteryRepository.BatteryHealthResult?> = _healthResult.asStateFlow()

    // ============ 短时功耗分析 (不计入健康度) ============

    // 当前时间窗口（毫秒）
    private val _powerWindowMs = MutableStateFlow(3600000L) // 默认1小时
    val powerWindowMs: StateFlow<Long> = _powerWindowMs.asStateFlow()

    // 短时功耗分析结果
    private val _powerAnalysis = MutableStateFlow<ShortTermPowerAnalysis?>(null)
    val powerAnalysis: StateFlow<ShortTermPowerAnalysis?> = _powerAnalysis.asStateFlow()

    // 是否正在加载功耗分析
    private val _powerAnalysisLoading = MutableStateFlow(false)
    val powerAnalysisLoading: StateFlow<Boolean> = _powerAnalysisLoading.asStateFlow()

    // ============ 长时电量分析 (计入健康度) ============

    // 长时完整循环分析结果
    private val _longTermAnalysis = MutableStateFlow<LongTermAnalysisResult?>(null)
    val longTermAnalysis: StateFlow<LongTermAnalysisResult?> = _longTermAnalysis.asStateFlow()

    // 完整充电循环列表
    private val _completeChargeCycles = MutableStateFlow<List<CompleteCycle>>(emptyList())
    val completeChargeCycles: StateFlow<List<CompleteCycle>> = _completeChargeCycles.asStateFlow()

    // 完整放电循环列表
    private val _completeDischargeCycles = MutableStateFlow<List<CompleteCycle>>(emptyList())
    val completeDischargeCycles: StateFlow<List<CompleteCycle>> = _completeDischargeCycles.asStateFlow()

    // ===== 每设备独立提醒阈值 (v3.0) =====
    // chargeAlertLevel / lowBatteryAlertLevel 现在基于选中设备动态计算
    private val _chargeAlertLevel = MutableStateFlow(85)
    val chargeAlertLevel: StateFlow<Int> = _chargeAlertLevel.asStateFlow()

    private val _lowBatteryAlertLevel = MutableStateFlow(20)
    val lowBatteryAlertLevel: StateFlow<Int> = _lowBatteryAlertLevel.asStateFlow()

    // 悬浮窗开关
    private val _floatingWindowEnabled = MutableStateFlow(false)
    val floatingWindowEnabled: StateFlow<Boolean> = _floatingWindowEnabled.asStateFlow()

    // v5.1: 键值映射开关 + 设备选择
    private val _keyMappingEnabled = MutableStateFlow(false)
    val keyMappingEnabled: StateFlow<Boolean> = _keyMappingEnabled.asStateFlow()

    private val _keyMappingDeviceId = MutableStateFlow(-1L)
    val keyMappingDeviceId: StateFlow<Long> = _keyMappingDeviceId.asStateFlow()

    // ============ 快速获取已配对设备电量 ============
    private val _quickFetching = MutableStateFlow(false)
    val quickFetching: StateFlow<Boolean> = _quickFetching.asStateFlow()

    private val _quickFetchResult = MutableStateFlow<QuickFetchResult?>(null)
    val quickFetchResult: StateFlow<QuickFetchResult?> = _quickFetchResult.asStateFlow()

    // ============ 时间维度曲线数据 ============

    /** 当前选中的时间维度 */
    private val _timeDimension = MutableStateFlow(TimeDimension.MINUTES)
    val timeDimension: StateFlow<TimeDimension> = _timeDimension.asStateFlow()

    /** 曲线数据点 */
    private val _curvePoints = MutableStateFlow<List<CurvePoint>>(emptyList())
    val curvePoints: StateFlow<List<CurvePoint>> = _curvePoints.asStateFlow()

    /** 曲线是否正在加载 */
    private val _curveLoading = MutableStateFlow(false)
    val curveLoading: StateFlow<Boolean> = _curveLoading.asStateFlow()

    /** 数据的时间范围 (起始, 结束) */
    private val _dataTimeRange = MutableStateFlow<Pair<Long, Long>>(0L to 0L)
    val dataTimeRange: StateFlow<Pair<Long, Long>> = _dataTimeRange.asStateFlow()

    // 是否有可连接的设备
    val hasConnectedDevice: Boolean get() = bleManager.connectionState.value == ConnectionState.CONNECTED

    // 序列号（来自 BLE Device Info Service）
    val bleSerialNumber = bleManager.serialNumber

    init {
        // 【修复】启动时将所有设备的连接状态重置为离线
        // 因为 App 重启后没有设备保持连接，数据库可能残留上次的 isConnected=true
        viewModelScope.launch {
            try {
                repository.resetAllConnectionStatus()
            } catch (_: Exception) {}
        }

        // 从 DataStore 恢复选中设备 — distinctUntilChanged 避免重复发射
        viewModelScope.launch {
            preferencesManager.selectedDeviceId.distinctUntilChanged().collect { id ->
                if (_selectedDeviceId.value != id) {
                    _selectedDeviceId.value = id
                }
            }
        }
        viewModelScope.launch {
            preferencesManager.floatingWindowEnabled.distinctUntilChanged().collect { _floatingWindowEnabled.value = it }
        }
        // v5.1: 键值映射状态
        viewModelScope.launch {
            preferencesManager.keyMappingEnabled.distinctUntilChanged().collect { enabled ->
                _keyMappingEnabled.value = enabled
                com.batteryanalyzer.keymap.KeyMappingManager.applyEnabled(enabled)
            }
        }
        viewModelScope.launch {
            preferencesManager.keyMappingDeviceId.distinctUntilChanged().collect { id ->
                _keyMappingDeviceId.value = id
                if (id > 0) com.batteryanalyzer.keymap.KeyMappingManager.setCurrentDeviceId(id)
            }
        }
        // 当选中的设备变化时，重新加载分析数据 + 同步该设备的阈值
        viewModelScope.launch {
            _selectedDeviceId.collect { id ->
                if (id > 0) {
                    loadAnalysis(id)
                    loadPowerAnalysis(id)
                    loadLongTermAnalysis(id)
                    loadCurveData()
                    // 同步选中设备的阈值到 UI StateFlow
                    launch {
                        preferencesManager.getDeviceChargeAlertLevel(id).distinctUntilChanged().collect {
                            _chargeAlertLevel.value = it
                        }
                    }
                    launch {
                        preferencesManager.getDeviceLowBatteryAlertLevel(id).distinctUntilChanged().collect {
                            _lowBatteryAlertLevel.value = it
                        }
                    }
                }
            }
        }

        // 当时间窗口变化时，重新加载功耗分析
        viewModelScope.launch {
            _powerWindowMs.collect { _ ->
                val id = _selectedDeviceId.value
                if (id > 0) loadPowerAnalysis(id)
            }
        }

        // 当 BLE 连接状态变化时，同步到数据库
        viewModelScope.launch {
            connectionState.collect { state ->
                val connected = state == ConnectionState.CONNECTED
                val currentId = _selectedDeviceId.value
                if (currentId > 0) {
                    repository.updateConnectionStatus(currentId, connected)
                }
            }
        }

        // 收集 BLE 序列号并持久化到数据库
        viewModelScope.launch {
            bleSerialNumber.collect { sn ->
                if (!sn.isNullOrBlank()) {
                    val mac = bleManager.connectedDeviceMac.value
                    if (!mac.isNullOrBlank()) {
                        repository.updateDeviceSerialNumber(mac, sn)
                    }
                }
            }
        }

        // v4.1: 连接后自动修正设备名称（"未知设备" → 真实名称）
        viewModelScope.launch {
            bleManager.resolvedDeviceName.collect { name ->
                if (!name.isNullOrBlank() && name != "未知设备") {
                    val mac = bleManager.connectedDeviceMac.value
                    if (!mac.isNullOrBlank()) {
                        repository.updateDeviceNameAndCapacityIfUnknown(mac, name)
                        Log.d("MainViewModel", "v4.2 设备名称+容量已修正: $mac → '$name'")
                    }
                }
            }
        }

        // 通知管理器 — 选中设备阈值变更时同步刷新
        viewModelScope.launch {
            _chargeAlertLevel.collect {
                val deviceId = _selectedDeviceId.value
                if (deviceId > 0) notificationManager.refreshSettings(deviceId)
            }
        }
        viewModelScope.launch {
            _lowBatteryAlertLevel.collect {
                val deviceId = _selectedDeviceId.value
                if (deviceId > 0) notificationManager.refreshSettings(deviceId)
            }
        }

        // 【修复】自动重连：从 DataStore 恢复上次选中的设备并尝试自动连接
        viewModelScope.launch {
            // 等待选中设备恢复
            delay(500) // 给予 DataStore 加载时间
            val savedId = preferencesManager.selectedDeviceId.first()
            val savedMac = preferencesManager.lastConnectedMac.first()
            if (savedId > 0 && savedMac.isNotBlank()) {
                val device = repository.getDeviceById(savedId)
                if (device != null) {
                    _selectedDeviceId.value = savedId
                    val mac = device.macAddress
                    if (mac.isNotBlank()) {
                        // 【修复】仅在未连接且未连接中时自动重连，防止重复连接导致 BLE 协议栈冲突
                        if (bleManager.connectionState.value != ConnectionState.CONNECTED &&
                            !bleManager.isConnecting()) {
                            try {
                                bleManager.connectByAddress(mac, viewModelScope)
                                repository.updateConnectionStatus(savedId, true)
                                // 同步启动后台监控服务（使用 displayName）
                                val ctx = getApplication<Application>()
                                val serviceIntent = Intent(ctx, BatteryMonitorService::class.java).apply {
                                    putExtra("device_mac", mac)
                                    putExtra("device_name", device.displayName)
                                    putExtra("device_id", savedId)
                                }
                                ctx.startForegroundService(serviceIntent)
                            } catch (_: Exception) {
                                // 自动连接失败，用户可手动连接
                            }
                        } else {
                            Log.w("MainViewModel", "跳过自动重连：已有连接操作进行中")
                        }
                    }
                    loadAnalysis(savedId)
                    loadPowerAnalysis(savedId)
                    loadLongTermAnalysis(savedId)
                    loadCurveData()
                }
            }
        }

        // v5.2: 启动时自动扫描已连接的**外部实体**输入设备（键盘/鼠标/2.4G/磁吸）
        // 仅保留 isExternal=true 或未命中内部设备黑名单的系统输入设备
        viewModelScope.launch {
            delay(1500)
            try {
                refreshInputDevices()
            } catch (e: Exception) {
                Log.w("MainViewModel", "v5.2 启动时输入设备扫描失败: ${e.message}")
            }
        }
    }

    // ============ 蓝牙操作 ============

    fun startScan() {
        bleManager.startScan(viewModelScope, includeClassic = true)
    }

    fun stopScan() {
        bleManager.stopScan()
    }

    /**
     * 连接设备并自动选中
     */
    fun connectToDevice(address: String) {
        val device = scanResults.value.find { it.device.address == address }?.device ?: return
        connectedBleDevice = device
        bleManager.connect(device, viewModelScope)

        viewModelScope.launch {
            val rawName = device.name ?: "未知设备"
            val deviceId = repository.insertOrUpdateDevice(
                name = rawName,
                macAddress = device.address
            )
            _selectedDeviceId.value = deviceId
            preferencesManager.setSelectedDeviceId(deviceId)
            preferencesManager.setLastConnectedMac(device.address)
            // 注意：不在此处标记 isConnected=true，连接状态由 init{} 中 connectionState 回调统一管理，避免假连接
            loadAnalysis(deviceId)

            // 启动后台监控服务（使用 displayName）
            val savedDevice = repository.getDeviceById(deviceId)
            val displayName = savedDevice?.displayName ?: rawName

            val context = getApplication<Application>()
            val serviceIntent = Intent(context, BatteryMonitorService::class.java).apply {
                putExtra("device_mac", device.address)
                putExtra("device_name", displayName)
                putExtra("device_id", deviceId)
            }
            context.startForegroundService(serviceIntent)
        }
    }

    /**
     * 断开当前 BLE 连接但不取消选中设备
     */
    fun disconnectCurrentDevice() {
        bleManager.disconnect()
        connectedBleDevice = null

        viewModelScope.launch {
            val currentId = _selectedDeviceId.value
            if (currentId > 0) repository.updateConnectionStatus(currentId, false)

            val context = getApplication<Application>()
            context.stopService(Intent(context, BatteryMonitorService::class.java))
        }
    }

    /**
     * 选中设备并查看其数据（不触发连接）
     */
    fun selectDevice(deviceId: Long) {
        _selectedDeviceId.value = deviceId
        viewModelScope.launch {
            preferencesManager.setSelectedDeviceId(deviceId)
            loadAnalysis(deviceId)
        }
    }

    /**
     * 连接已保存的设备（通过 MAC 地址直连，跳过扫描）
     * 适用于已在系统层面配对的设备
     */
    fun connectToSavedDevice(deviceId: Long) {
        viewModelScope.launch {
            val device = repository.getDeviceById(deviceId) ?: return@launch
            val mac = device.macAddress
            if (mac.isBlank()) return@launch

            val context = getApplication<Application>()

            // ===== 修复：切换设备前，先停旧监控服务并同步断开旧 GATT =====
            // 若不先断开，当旧设备仍处于 CONNECTING（如刚自动重连 / 上次切换还在超时中），
            // 下方 connectByAddress 会被 BleManager 的 connectingLock 直接丢弃本次请求，
            // 必须等旧连接超时（约 10~20s）且之后无任何重连，表现为"切换卡住 30 秒以上"。
            // disconnect() 会同步关闭旧 gatt、释放 connectingLock、将状态置为 DISCONNECTED，
            // 之后 connectByAddress 才能立即拿到锁并发起对新设备的连接。
            // stopService 确保在重启服务前结束旧设备的监控协程（见 BatteryMonitorService 的 monitorScope 兜底）。
            context.stopService(Intent(context, BatteryMonitorService::class.java))
            bleManager.disconnect()

            // 标记旧设备为离线（避免数据库残留旧设备的"已连接"状态）
            val oldId = _selectedDeviceId.value
            if (oldId > 0 && oldId != deviceId) {
                repository.updateConnectionStatus(oldId, false)
            }

            _selectedDeviceId.value = deviceId
            preferencesManager.setSelectedDeviceId(deviceId)
            loadAnalysis(deviceId)

            // 注意：不在此处标记 isConnected=true，连接状态由 init{} 中 connectionState 回调统一管理，避免假连接

            // 通过 MAC 地址直接连接
            bleManager.connectByAddress(mac, viewModelScope)

            // 保存最后连接的 MAC 地址（供后台服务恢复）
            preferencesManager.setLastConnectedMac(mac)

            // 启动后台监控服务（使用 displayName）
            val displayName = device.displayName
            val serviceIntent = Intent(context, BatteryMonitorService::class.java).apply {
                putExtra("device_mac", mac)
                putExtra("device_name", displayName)
                putExtra("device_id", deviceId)
            }
            context.startForegroundService(serviceIntent)
        }
    }

    /**
     * 删除设备
     */
    fun deleteDevice(deviceId: Long) {
        viewModelScope.launch {
            // v8: 删除前若设备仍系统配对，先停监控服务+断开 GATT+解绑，
            // 避免用户忘了先取消配对就删除、结果系统蓝牙里还残留配对记录
            val device = repository.getDeviceById(deviceId)
            val mac = device?.macAddress
            if (device != null && !mac.isNullOrBlank() && bleManager.isDeviceBonded(mac)) {
                val context = getApplication<Application>()
                context.stopService(Intent(context, BatteryMonitorService::class.java))
                bleManager.disconnect()
                delay(700) // 等 GATT 真正断开后再解绑
                bleManager.cancelBond(mac)
            }
            // 如果要删除的设备是当前选中的，切换到最近的设备
            if (_selectedDeviceId.value == deviceId) {
                val devices = repository.getAllDevices().firstOrNull()
                    ?.filter { it.id != deviceId } ?: emptyList()
                val nextId = devices.firstOrNull()?.id ?: -1L
                _selectedDeviceId.value = nextId
                preferencesManager.setSelectedDeviceId(nextId)
            }
            repository.deleteDevice(deviceId)
        }
    }

    /**
     * 更新设备备注名（含全局同步：小组件/悬浮窗/通知/控制中心）
     */
    /**
     * v7: 用户手动修改设备电池容量（mAh）。
     * 改完后全局（仪表盘/功率分析/健康度分析/监控服务）均从数据库读取最新值。
     */
    fun updateDeviceCapacity(deviceId: Long, capacity: Int) {
        viewModelScope.launch { repository.updateDeviceCapacity(deviceId, capacity) }
    }

    // ============ v8: 手写笔 —— 非破坏取消配对 / 标记 / 压缩 / 备份恢复 ============

    /**
     * 方案 A：取消配对（非破坏性）。
     * 仅解绑系统层配对并将设备标记为断连，不删除 App 内设备行与电量记录，
     * 重连（同一 MAC）后数据自动恢复。
     *
     * 关键修复：设备往往正被 BatteryMonitorService 保持活跃 GATT 连接，而 Android 在
     * 设备处于已连接/连接中状态时通常会静默拒绝 removeBond（调用被忽略），导致"解绑无效"。
     * 因此必须先停止监控服务并断开 GATT，等待连接真正断开后再执行系统解绑。
     * 华为/荣耀等 ROM 还会直接屏蔽反射 removeBond，此时返回 false，需引导用户去系统设置。
     */
    fun unpairDevice(device: DeviceInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val mac = device.macAddress
            val context = getApplication<Application>()
            // 1) 停止后台监控服务并断开活跃 GATT（已连接时系统不允许 removeBond）
            context.stopService(Intent(context, BatteryMonitorService::class.java))
            bleManager.disconnect()
            // gatt.disconnect() 是异步的，等待状态真正变为 DISCONNECTED 后再解绑
            delay(700)
            // 2) 真正解绑系统配对（反射 removeBond）
            val ok = bleManager.cancelBond(mac)
            // 3) 标记 App 内为断连
            repository.updateConnectionStatus(device.id, false)
            // 4) 反馈结果
            withContext(Dispatchers.Main) {
                if (ok) {
                    Toast.makeText(context, "已取消与「${device.displayName}」的系统配对", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "系统限制：请到「设置 → 蓝牙」中手动取消配对", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 手动标记/取消标记手写笔（决定备份/压缩/非破坏取消配对是否对其生效） */
    fun setHandwritingPen(deviceId: Long, isPen: Boolean) {
        viewModelScope.launch { repository.setHandwritingPen(deviceId, isPen) }
    }

    /** 启动滚动压缩（App 启动时调用一次，低频） */
    fun runCompaction() {
        viewModelScope.launch { repository.compactOldRecords() }
    }

    /** 导出全部手写笔备份 JSON（供 SAF 写入文件） */
    suspend fun exportBackup(): String = repository.exportBackup()

    /** 从备份 JSON 恢复（仅当 MAC 不存在时新建，已存在则追加缺失记录、不覆盖设置） */
    suspend fun importBackup(json: String): Int = repository.importBackup(json)

    fun updateDeviceRemark(deviceId: Long, remark: String) {
        viewModelScope.launch {
            repository.updateDeviceRemark(deviceId, remark)

            // 获取更新后的 displayName
            val device = repository.getDeviceById(deviceId)
            val displayName = device?.displayName ?: remark

            // 1. 全局广播同步：悬浮窗 + 通知服务
            val context = getApplication<Application>()
            com.batteryanalyzer.stylus.RemarkSyncManager.broadcastRemarkUpdate(
                context, deviceId, remark, displayName
            )

            // 2. 刷新小组件（通过 GlanceAppWidgetManager 更新所有实例）
            try {
                val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
                val widget = com.batteryanalyzer.widget.BatteryWidget()
                val glanceIds = manager.getGlanceIds(widget.javaClass)
                glanceIds.forEach { glanceId -> widget.update(context, glanceId) }
            } catch (_: Exception) {
                // 小组件更新失败不阻塞
            }
            try {
                val manager = androidx.glance.appwidget.GlanceAppWidgetManager(context)
                val widgetMulti = com.batteryanalyzer.widget.BatteryWidgetMulti()
                val glanceIds = manager.getGlanceIds(widgetMulti.javaClass)
                glanceIds.forEach { glanceId -> widgetMulti.update(context, glanceId) }
            } catch (_: Exception) {
                // 小组件更新失败不阻塞
            }
        }
    }

    /**
     * 更新当前选中设备的充电提醒阈值
     */
    fun updateChargeAlertLevel(level: Int) {
        val clamped = level.coerceIn(50, 100)
        val even = if (clamped % 2 != 0) clamped + 1 else clamped
        val valid = even.coerceAtMost(100)
        _chargeAlertLevel.value = valid
        val deviceId = _selectedDeviceId.value
        if (deviceId > 0) {
            viewModelScope.launch { preferencesManager.setDeviceChargeAlertLevel(deviceId, valid) }
        }
    }

    /**
     * 更新当前选中设备的耗电提醒阈值
     */
    fun updateLowBatteryAlertLevel(level: Int) {
        val clamped = level.coerceIn(0, 48)
        val even = if (clamped % 2 != 0) clamped - 1 else clamped
        val valid = even.coerceAtLeast(0)
        _lowBatteryAlertLevel.value = valid
        val deviceId = _selectedDeviceId.value
        if (deviceId > 0) {
            viewModelScope.launch { preferencesManager.setDeviceLowBatteryAlertLevel(deviceId, valid) }
        }
    }

    /**
     * 获取指定设备的充电提醒阈值 Flow（供外部组件使用）
     */
    fun getDeviceChargeAlertLevel(deviceId: Long): Flow<Int> =
        preferencesManager.getDeviceChargeAlertLevel(deviceId)

    /**
     * 获取指定设备的耗电提醒阈值 Flow（供外部组件使用）
     */
    fun getDeviceLowBatteryAlertLevel(deviceId: Long): Flow<Int> =
        preferencesManager.getDeviceLowBatteryAlertLevel(deviceId)

    /**
     * v6.2: 获取指定设备的阈值提醒开关状态 Flow
     */
    fun getDeviceThresholdReminderEnabled(deviceId: Long): Flow<Boolean> =
        preferencesManager.getDeviceThresholdReminderEnabled(deviceId)

    /**
     * v6.2: 设置指定设备的阈值提醒开关状态
     */
    fun setDeviceThresholdReminderEnabled(deviceId: Long, enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setDeviceThresholdReminderEnabled(deviceId, enabled)
            // 同步刷新通知管理器
            notificationManager.refreshSettings(deviceId)
        }
    }

    /**
     * 更新指定设备的充电提醒阈值
     */
    fun updateDeviceChargeAlertLevel(deviceId: Long, level: Int) {
        val clamped = level.coerceIn(50, 100)
        val even = if (clamped % 2 != 0) clamped + 1 else clamped
        val valid = even.coerceAtMost(100)
        // 如果是当前选中设备，同步更新 UI StateFlow
        if (deviceId == _selectedDeviceId.value) {
            _chargeAlertLevel.value = valid
        }
        viewModelScope.launch { preferencesManager.setDeviceChargeAlertLevel(deviceId, valid) }
    }

    /**
     * 更新指定设备的耗电提醒阈值
     */
    fun updateDeviceLowBatteryAlertLevel(deviceId: Long, level: Int) {
        val clamped = level.coerceIn(0, 48)
        val even = if (clamped % 2 != 0) clamped - 1 else clamped
        val valid = even.coerceAtLeast(0)
        if (deviceId == _selectedDeviceId.value) {
            _lowBatteryAlertLevel.value = valid
        }
        viewModelScope.launch { preferencesManager.setDeviceLowBatteryAlertLevel(deviceId, valid) }
    }

    // ============ v5.1: 键值映射开关操作 ============

    fun toggleKeyMapping(enabled: Boolean) {
        _keyMappingEnabled.value = enabled
        viewModelScope.launch {
            preferencesManager.setKeyMappingEnabled(enabled)
        }
    }

    fun setKeyMappingDeviceId(deviceId: Long) {
        _keyMappingDeviceId.value = deviceId
        viewModelScope.launch {
            preferencesManager.setKeyMappingDeviceId(deviceId)
        }
    }

    // ============ 悬浮窗操作 ============

    fun toggleFloatingWindow(enabled: Boolean) {
        _floatingWindowEnabled.value = enabled
        viewModelScope.launch { preferencesManager.setFloatingWindowEnabled(enabled) }
        val context = getApplication<Application>()
        val intent = Intent(context, FloatingBatteryService::class.java)
        if (enabled) {
            context.startForegroundService(intent)
        } else {
            context.stopService(intent)
        }
    }

    fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(getApplication())
        } else true
    }

    fun openOverlayPermissionSettings() {
        val context = getApplication<Application>()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    // ============ 后台保活权限 ============

    /**
     * 检查是否已豁免电池优化（是否在白名单中）
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getApplication<Application>()
            .getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)
    }

    /**
     * 请求关闭电池优化 — 优先匹配国产厂商专属页面，回退到标准 API。
     * 
     * 国内主流安卓品牌（小米/华为/OPPO/vivo等）通常会拦截或修改标准
     * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 的行为，因此需要优先
     * 尝试各厂商的安全中心/手机管家中的电池优化管理页面。
     * 
     * 策略：厂商专属页面 → 标准 API → 通用应用详情页
     */
    fun requestBatteryOptimizationExemption() {
        val context = getApplication<Application>()
        val pkg = context.packageName

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Toast.makeText(context, "Android 6.0 以下无需此权限", Toast.LENGTH_SHORT).show()
            return
        }

        // 检查是否已在白名单
        if (isIgnoringBatteryOptimizations()) {
            Toast.makeText(context, "已关闭电池优化，后台保活已生效", Toast.LENGTH_SHORT).show()
            return
        }

        // Step 1: 优先尝试已知厂商的电池优化管理页面
        if (tryOpenManufacturerBatteryPage(pkg)) return

        // Step 2: 回退到标准 Android 电池优化请求
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$pkg")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                Toast.makeText(context, "请在弹出窗口中点击「允许」", Toast.LENGTH_SHORT).show()
                context.startActivity(intent)
                return
            }
        } catch (_: Exception) { }

        // Step 3: 最终回退 — 打开通用应用详情页
        Toast.makeText(context, "请找到「电池」或「省电」相关选项并关闭优化", Toast.LENGTH_LONG).show()
        openAppSystemSettings()
    }

    /**
     * 尝试打开已知厂商的电池优化管理页面。
     * @return true 表示已成功跳转到厂商页面
     */
    private fun tryOpenManufacturerBatteryPage(pkg: String): Boolean {
        val context = getApplication<Application>()

        // 厂商电池优化 / 省电策略 / 后台运行管理的已知 Activity 列表
        // 格式：Triple(包名, Activity全路径, 是否附带 extra_pkgname)
        val manufacturerPages = listOf(
            // ===== 小米 MIUI / HyperOS =====
            // 安全中心 → 省电优化 → 应用智能省电
            Triple("com.miui.securitycenter",
                "com.miui.powercenter.PowerSettings", false),
            // 安全中心 → 应用管理 → 权限 → 自启动管理（部分 MIUI 版本电池优化在此）
            Triple("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity", false),
            // 安全中心 → 电池与性能
            Triple("com.miui.securitycenter",
                "com.miui.powercenter.ui.PowerControlActivity", false),
            // 设置 → 应用设置 → 应用管理 → 省电策略（MIUI 13+）
            Triple("com.android.settings",
                "com.android.settings.Settings\$HighPowerApplicationsActivity", false),

            // ===== 华为 EMUI / HarmonyOS =====
            // 手机管家 → 应用启动管理
            Triple("com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity", false),
            // 手机管家 → 电池 → 耗电防火墙 / 启动管理
            Triple("com.huawei.systemmanager",
                "com.huawei.systemmanager.power.ui.HwPowerManagerActivity", false),
            // 手机管家 → 电池优化
            Triple("com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity", false),
            // 设置 → 应用 → 应用启动管理
            Triple("com.android.settings",
                "com.android.settings.applications.ManageApplicationsActivity", false),

            // ===== OPPO ColorOS =====
            // 安全中心 → 权限隐私 → 自启动管理
            Triple("com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity", false),
            Triple("com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.FakeStartupListActivity", false),
            // 安全中心 → 电池 → 耗电异常优化 / 应用速冻
            Triple("com.coloros.safecenter",
                "com.coloros.safecenter.powersaver.PowerSaverActivity", false),
            // ColorOS 12+ 电池优化
            Triple("com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity", false),

            // ===== vivo Funtouch OS / OriginOS =====
            // i管家 → 权限管理 → 自启动 / 后台运行
            Triple("com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity", false),
            // i管家 → 省电管理 → 后台高耗电
            Triple("com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.PowerManagerActivity", false),
            // iQOO 安全中心
            Triple("com.iqoo.secure",
                "com.iqoo.secure.MainActivity", false),
            // vivo 设置 → 电池 → 后台高耗电
            Triple("com.vivo.abe",
                "com.vivo.abe.powersaver.PowerSaverActivity", false),

            // ===== 三星 One UI =====
            Triple("com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity", false),
            Triple("com.samsung.android.sm_cn",
                "com.samsung.android.sm.ui.battery.BatteryActivity", false),

            // ===== 魅族 Flyme =====
            Triple("com.meizu.safe",
                "com.meizu.safe.power.PowerSettingsActivity", false),
        )

        for ((targetPkg, activity, _) in manufacturerPages) {
            try {
                val intent = Intent().apply {
                    setClassName(targetPkg, activity)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (context.packageManager.resolveActivity(intent, 0) != null) {
                    Toast.makeText(context, "已打开品牌专属电池管理页，请找到本应用并关闭电池优化", Toast.LENGTH_LONG).show()
                    context.startActivity(intent)
                    return true
                }
            } catch (_: Exception) { }
        }

        return false
    }

    /**
     * 打开应用详细信息页（通用入口：自启动 / 关联启动 / 权限管理）
     * 适用于所有 Android 设备，用户可在该页面自行配置自启动和关联启动权限
     */
    fun openAppSystemSettings() {
        val context = getApplication<Application>()
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /**
     * 尝试打开厂商自启动管理页面
     * 不同厂商的 Intent 不同，这里依次尝试常见厂商
     * 如果都不匹配，回退到通用应用详情页
     */
    /** v5.2: 扫描并注册非蓝牙输入设备（键盘/鼠标/2.4G/磁吸），仅保留系统已连接的外部实体设备 */
    fun refreshInputDevices(snackbarMsg: ((String) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val snapshots = com.batteryanalyzer.keymap.InputDeviceScanner.scanInputDevices(context)

                // v5.2: 标记当前已连接的输入设备
                val currentMacs = snapshots.map { it.virtualMac }.toSet()
                var added = 0

                snapshots.forEach { snap ->
                    val id = repository.insertOrUpdateInputDevice(snap.name, snap.virtualMac, snap.sources)
                    if (id > 0) added++
                }

                // v5.2: 将不在本次扫描中的输入设备标记为断连
                val previousInputDevices = repository.getInputDevices()
                var disconnected = 0
                previousInputDevices.forEach { dev ->
                    if (dev.macAddress !in currentMacs && dev.isConnected) {
                        repository.updateConnectionStatus(dev.id, connected = false)
                        disconnected++
                    }
                }

                // v5.2: 清理已断连超过 24 小时的脏数据（gpio-keys/pmic 等旧扫描残留）
                val cleaned = repository.cleanStaleInputDevices()

                withContext(Dispatchers.Main) {
                    Log.d("MainViewModel", "v5.2 刷新输入设备: 发现${snapshots.size}, 新增$added, 断连$disconnected, 清理$cleaned")
                    val msg = buildString {
                        append("发现 ${snapshots.size} 个外部输入设备")
                        if (disconnected > 0) append("，${disconnected} 个已断连")
                        if (cleaned > 0) append("，${cleaned} 个旧记录已清理")
                    }
                    snackbarMsg?.invoke(msg)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.w("MainViewModel", "v5.2 刷新输入设备失败: ${e.message}")
                    snackbarMsg?.invoke("刷新失败：${e.message}")
                }
            }
        }
    }

    fun openAutoStartSettings() {
        val context = getApplication<Application>()
        val pkg = context.packageName

        // 厂商自启动管理页面的已知 Intent（按常见程度排序）
        val attempts = listOf(
            // 小米 (MIUI)
            Triple("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity", null),
            // 华为 (EMUI/HarmonyOS)
            Triple("com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity", null),
            Triple("com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity", null),
            // OPPO (ColorOS)
            Triple("com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity", null),
            Triple("com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.FakeStartupListActivity", null),
            // vivo (Funtouch OS / OriginOS)
            Triple("com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity", null),
            Triple("com.iqoo.secure",
                "com.iqoo.secure.MainActivity", null),
        )

        for ((targetPkg, activity, _) in attempts) {
            try {
                val intent = Intent().apply {
                    setClassName(targetPkg, activity)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                // 检查是否有 Activity 能处理该 Intent
                if (context.packageManager.resolveActivity(intent, 0) != null) {
                    context.startActivity(intent)
                    return
                }
            } catch (_: Exception) {
                // 该厂商不匹配，继续尝试下一个
            }
        }

        // 都尝试失败 → 打开通用应用详情页
        openAppSystemSettings()
    }

    // ============ 数据分析 ============

    private suspend fun loadAnalysis(deviceId: Long) {
        if (deviceId <= 0) return
        _chargingSessions.value = repository.analyzeChargingSessions(deviceId)
        _dischargeSessions.value = repository.analyzeDischargeSessions(deviceId)
        val result = repository.getBatteryHealthResult(deviceId)
        _batteryHealth.value = result.healthPercent
        _healthResult.value = result
    }

    fun refreshAnalysis() {
        viewModelScope.launch {
            loadAnalysis(_selectedDeviceId.value)
        }
    }

    // ============ 短时功耗分析 ============

    /**
     * 设置功耗分析时间窗口
     * @param windowMs 窗口时长(毫秒)，如 3600000 = 1小时
     */
    fun setPowerWindow(windowMs: Long) {
        _powerWindowMs.value = windowMs
    }

    /**
     * 加载短时功耗分析 - 不计入健康度
     */
    private suspend fun loadPowerAnalysis(deviceId: Long) {
        if (deviceId <= 0) return
        _powerAnalysisLoading.value = true
        try {
            val now = System.currentTimeMillis()
            val windowStart = now - _powerWindowMs.value
            val analysis = repository.analyzeShortTermPower(deviceId, windowStart, now)
            _powerAnalysis.value = analysis
        } catch (e: Exception) {
            // 数据不足时不崩溃
            _powerAnalysis.value = null
        } finally {
            _powerAnalysisLoading.value = false
        }
    }

    /**
     * 手动刷新短时功耗分析
     */
    fun refreshPowerAnalysis() {
        viewModelScope.launch {
            loadPowerAnalysis(_selectedDeviceId.value)
        }
    }

    // ============ 长时电量分析 (计入健康度) ============

    /**
     * 加载长时完整循环分析 —— 计入电池健康度评估
     * 仅分析完整充放电循环（0-100充电/100-0放电）
     */
    private suspend fun loadLongTermAnalysis(deviceId: Long) {
        if (deviceId <= 0) return
        try {
            val result = repository.getLongTermAnalysisResult(deviceId)
            _longTermAnalysis.value = result
            _completeChargeCycles.value = result.completeChargeCycles
            _completeDischargeCycles.value = result.completeDischargeCycles
        } catch (e: Exception) {
            _longTermAnalysis.value = null
            _completeChargeCycles.value = emptyList()
            _completeDischargeCycles.value = emptyList()
        }
    }

    /**
     * 手动刷新长时分析
     */
    fun refreshLongTermAnalysis() {
        viewModelScope.launch {
            loadLongTermAnalysis(_selectedDeviceId.value)
            // 同时刷新健康度（因为健康度依赖长时数据）
            loadAnalysis(_selectedDeviceId.value)
        }
    }

    fun getRecordsInRange(start: Long, end: Long): Flow<List<BatteryRecord>> {
        val deviceId = _selectedDeviceId.value
        return if (deviceId > 0) {
            repository.getRecordsInRange(deviceId, start, end)
        } else {
            flowOf(emptyList())
        }
    }

    // ============ 快速获取已配对设备电量 ============

    /**
     * 从系统已配对设备快速获取电量
     * 适用于设备已在系统层面配对但未在App内连接的情况
     */
    fun fetchBatteryFromBondedDevice(deviceId: Long) {
        viewModelScope.launch {
            val device = repository.getDeviceById(deviceId) ?: return@launch
            val mac = device.macAddress
            if (mac.isBlank()) return@launch

            _quickFetching.value = true
            _quickFetchResult.value = null

            bleManager.quickFetchBatteryLevel(mac, viewModelScope) { level, isCharging, error ->
                _quickFetching.value = false
                if (error != null) {
                    _quickFetchResult.value = QuickFetchResult(
                        batteryLevel = -1, error = error, macAddress = mac
                    )
                } else {
                    _quickFetchResult.value = QuickFetchResult(
                        batteryLevel = level, isCharging = isCharging,
                        error = null, macAddress = mac
                    )
                    // 将获取到的电量写入数据库（不建立持久连接）
                    if (level in 0..100) {
                        viewModelScope.launch {
                            repository.insertRecord(
                                BatteryRecord(
                                    deviceId = deviceId,
                                    batteryLevel = level,
                                    isCharging = isCharging,
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
                // 3秒后自动清除结果
                launch {
                    delay(3000)
                    if (_quickFetchResult.value?.macAddress == mac) {
                        _quickFetchResult.value = null
                    }
                }
            }
        }
    }

    fun clearQuickFetchResult() {
        _quickFetchResult.value = null
    }

    /**
     * 在已连接状态下手动触发一次电量读取。
     * 使用当前 GATT 连接，不创建新连接。
     * 作为自动获取失败时的补充手段。
     */
    fun manualReadBatteryLevel(onResult: (Boolean) -> Unit = {}) {
        bleManager.manualReadBatteryLevel { level, isCharging, error ->
            if (error == null && level in 0..100) {
                viewModelScope.launch {
                    val deviceId = _selectedDeviceId.value
                    if (deviceId > 0) {
                        repository.insertRecord(
                            BatteryRecord(
                                deviceId = deviceId,
                                batteryLevel = level,
                                isCharging = isCharging,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                }
                onResult(true)
            } else {
                onResult(false)
            }
        }
    }

    // ============ 时间维度曲线操作 ============

    /**
     * 切换时间维度并重新加载曲线数据（切换时强制刷新，避免缓存干扰）
     */
    fun setTimeDimension(dimension: TimeDimension) {
        _timeDimension.value = dimension
        // 切换维度时强制刷新曲线数据
        refreshCurveData()
    }

    /**
     * 加载当前设备的时间维度聚合曲线数据
     */
    fun loadCurveData() {
        viewModelScope.launch {
            val deviceId = _selectedDeviceId.value
            if (deviceId <= 0) return@launch

            _curveLoading.value = true
            try {
                // 获取数据总范围
                val range = repository.getDataTimeRange(deviceId)
                _dataTimeRange.value = range

                if (range.first == 0L || range.second == 0L) {
                    _curvePoints.value = emptyList()
                    _curveLoading.value = false
                    return@launch
                }

                val dimension = _timeDimension.value
                val points = repository.getCurvePoints(
                    deviceId = deviceId,
                    dimension = dimension,
                    start = range.first,
                    end = range.second,
                    forceRefresh = false
                )
                _curvePoints.value = points
            } catch (e: Exception) {
                _curvePoints.value = emptyList()
            } finally {
                _curveLoading.value = false
            }
        }
    }

    /**
     * 强制刷新曲线数据（清除缓存重新计算）
     */
    fun refreshCurveData() {
        viewModelScope.launch {
            val deviceId = _selectedDeviceId.value
            if (deviceId <= 0) return@launch

            _curveLoading.value = true
            try {
                val range = repository.getDataTimeRange(deviceId)
                _dataTimeRange.value = range
                if (range.first == 0L || range.second == 0L) {
                    _curvePoints.value = emptyList()
                    _curveLoading.value = false
                    return@launch
                }
                val points = repository.getCurvePoints(
                    deviceId = deviceId,
                    dimension = _timeDimension.value,
                    start = range.first,
                    end = range.second,
                    forceRefresh = true
                )
                _curvePoints.value = points
            } catch (e: Exception) {
                _curvePoints.value = emptyList()
            } finally {
                _curveLoading.value = false
            }
        }
    }

}

/**
 * 快速获取电量结果
 */
data class QuickFetchResult(
    val batteryLevel: Int,
    val isCharging: Boolean = false,
    val error: String? = null,
    val macAddress: String = ""
)
