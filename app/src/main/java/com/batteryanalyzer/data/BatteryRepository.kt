package com.batteryanalyzer.data

import android.content.Context
import android.util.Log
import androidx.room.Room
import com.batteryanalyzer.model.*
import kotlinx.coroutines.flow.Flow
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 电量数据仓库 - 统一数据访问层
 *
 * 核心策略：
 * - 设备标识：严格以 MAC 地址为唯一标识，不同 MAC = 不同设备
 * - 短时分析：灵敏参考指标，不计入电池健康度计算
 * - 长时分析：完整0-100充放电循环，计入电池健康度评估
 */
class BatteryRepository(context: Context) {

    companion object {
        private const val TAG = "BatteryRepository"

        /**
         * 根据设备名称/型号自动识别电池容量 (mAh)
         *
         * 数据来源：
         * - Honor Magic-Pencil 3:    82mAh (官方拆解)
         * - Huawei M-Pencil 全系列:  82mAh (拆解报告, 同荣耀方案)
         * - Apple Pencil 1代:        85mAh (iFixit 拆解, 3.85V/0.329Wh)
         * - Apple Pencil 2代:        90mAh (拆解报告)
         * - Apple Pencil USB-C:      85mAh (推测接近1代)
         * - Samsung S Pen Pro:       30mAh (三星官方规格)
         * - Xiaomi Smart Pen 2代:    80mAh (官方规格)
         * - Redmi 手写笔:            80mAh (官方规格)
         * - OPPO Pencil:             83mAh (拆解报告)
         * - OPPO Pencil 2:           83mAh (推测同系列)
         * - vivo Pencil 系列:        80mAh (推测, 15h续航)
         * - Lenovo Tab Pen Plus:     80mAh (官方规格 AP500U)
         * - Lenovo Precision Pen 2:  30mAh (AAAA/可充电版本)
         * - Surface Slim Pen 2:      46mAh (微软官方 0.171Wh/3.7V)
         * - Wacom Bamboo Ink Plus:   85mAh (可充电 USB-C)
         * - USI 标准手写笔:          80mAh (通用推测)
         * - 未知设备(默认):           100mAh (保守估计)
         */
        private fun detectBatteryCapacity(name: String, modelName: String): Int {
            val n = name.lowercase()
            val m = modelName.lowercase()

            // ===== 荣耀 Magic-Pencil: 82mAh =====
            if (n.contains("magic-pencil") || n.contains("magic pencil") ||
                m.contains("magic-pencil") || m.contains("magic pencil")
            ) return 82

            // ===== 华为 M-Pencil: 82mAh (同荣耀方案) =====
            if (n.contains("m-pencil") || m.contains("m-pencil")) return 82

            // ===== HUAWEI / HONOR 品牌手写笔: 82mAh =====
            // 通过 GATT Device Info (Manufacturer/Model) 或 0x2A00 解析到的名称
            // 如 "HUAWEI M-Pencil", "HONOR Magic-Pencil"
            if ((n.contains("huawei") || n.contains("honor")) &&
                (n.contains("pen") || n.contains("pencil") || n.contains("笔") || n.contains("stylus"))
            ) return 82

            // ===== Apple Pencil 2代: 90mAh =====
            if (n.contains("apple pencil") && (n.contains("2") || n.contains("2nd") || n.contains("二代")))
                return 90

            // ===== Apple Pencil 全系列 (1代 / USB-C / Pro): 85mAh =====
            if (n.contains("apple pencil")) return 85

            // ===== Samsung S Pen Pro: 30mAh =====
            if ((n.contains("s pen") || n.contains("s-pen")) && n.contains("pro")) return 30
            // Samsung S Pen Fold Edition: 30mAh
            if ((n.contains("s pen") || n.contains("s-pen")) && n.contains("fold")) return 30

            // ===== 小米 / Redmi 手写笔: 80mAh =====
            if (n.contains("xiaomi") && (n.contains("pen") || n.contains("pencil") || n.contains("手写笔")))
                return 80
            if (n.contains("redmi") && (n.contains("pen") || n.contains("pencil") || n.contains("手写笔")))
                return 80
            if (n.contains("xiaomi smart pen") || n.contains("smart pen") && n.contains("xiaomi"))
                return 80

            // ===== OPPO Pencil: 83mAh =====
            if (n.contains("oppo") && (n.contains("pencil") || n.contains("pen"))) return 83

            // ===== vivo Pencil: 80mAh =====
            if (n.contains("vivo") && (n.contains("pencil") || n.contains("pen"))) return 80

            // ===== Lenovo Precision Pen 2: 30mAh =====
            if (n.contains("precision pen")) return 30
            // ===== Lenovo Tab Pen Plus / Tab Pen: 80mAh =====
            if ((n.contains("lenovo") || n.contains("tab pen")) && n.contains("pen")) return 80

            // ===== Surface Slim Pen (可充电): 46mAh =====
            if (n.contains("surface slim pen")) return 46
            // ===== Surface Pen (AAAA电池, 不可充电, 但 BLE 可报电量): 标准 AAAA ~500mAh =====
            // 实际 BLE 报告的电量是估算值, 不做容量换算

            // ===== Wacom Bamboo Ink Plus (可充电): 85mAh =====
            if (n.contains("bamboo ink") || n.contains("bamboo sketch")) return 85

            // ===== Wacom 其他可充电笔: 85mAh =====
            if (n.contains("wacom") && (n.contains("pen") || n.contains("stylus"))) return 85

            // ===== USI (Universal Stylus Initiative) 标准: 80mAh =====
            if (n.contains("usi pen") || n.contains("usi stylus")) return 80

            // ===== 中文关键词 + 品牌 =====
            if (n.contains("华为") && (n.contains("笔") || n.contains("pencil") || n.contains("pen")))
                return 82

            // ===== 通用智能笔/触控笔: 默认 90mAh =====
            if (n.contains("smart pen") || n.contains("stylus pen") ||
                n.contains("digital pen") || n.contains("active pen") ||
                n.contains("手写笔") || n.contains("触控笔") || n.contains("触屏笔") ||
                n.contains("电容笔") || n.contains("点阵笔") || n.contains("电子笔") ||
                n.contains("m-pen")
            ) return 90

            // ===== 未知设备: 保守默认 100mAh =====
            return 100
        }

        /**
         * v8: 根据设备名/型号判断是否手写笔（用于收窄备份/非破坏取消配对范围）
         */
        fun isHandwritingPen(name: String, modelName: String): Boolean {
            val n = name.lowercase()
            val m = modelName.lowercase()
            return n.contains("pen") || n.contains("pencil") || n.contains("stylus") ||
                n.contains("笔") || m.contains("pen") || m.contains("pencil") || m.contains("stylus") ||
                n.contains("wacom") || m.contains("wacom") || n.contains("usi") || m.contains("usi")
        }
    }

    /** 数据库构造 —— 使用正式 Migration 链，杜绝 fallbackToDestructiveMigration() 致用户数据丢失
     *
     *  后续升级步骤：
     *  1. 在 AppDatabase 中新增 Migration(oldVer, newVer) companion 对象并加入 ALL_MIGRATIONS
     *  2. 递增 @Database version 到 newVer
     *  3. 重新编译即可
     *  参考：https://developer.android.com/training/data-storage/room/migrating-db-versions
     */
    private val db = Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "battery_analyzer.db"
    ).addMigrations(*AppDatabase.ALL_MIGRATIONS)
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()

    private val deviceDao = db.deviceDao()
    private val recordDao = db.batteryRecordDao()
    val keyMappingDao = db.deviceKeyMappingDao()
    val customKeyCodeDao = db.customKeyCodeDao()

    // ============ 设备操作 - 严格以 MAC 地址为唯一标识 ============

    fun getAllDevices(): Flow<List<DeviceInfo>> = deviceDao.getAllDevices()

    suspend fun getAllDevicesList(): List<DeviceInfo> = deviceDao.getAllDevicesList()

    suspend fun getDeviceByMac(macAddress: String): DeviceInfo? =
        deviceDao.getDeviceByMac(macAddress)

    suspend fun getDeviceById(id: Long): DeviceInfo? =
        deviceDao.getDeviceById(id)

    suspend fun getConnectedDevice(): DeviceInfo? =
        deviceDao.getConnectedDevice()

    suspend fun deleteDevice(id: Long) = deviceDao.deleteDevice(id)

    /**
     * 插入或更新设备 —— 严格以 MAC 地址为唯一标识
     * 仅当 MAC 地址不同时才识别为不同设备，相同 MAC 仅更新信息
     * 避免基于设备名称的误合并
     */
    suspend fun insertOrUpdateDevice(name: String, macAddress: String, modelName: String = ""): Long {
        // 1. 严格按 MAC 查找已有设备
        val existing = deviceDao.getDeviceByMac(macAddress)
        return if (existing != null) {
            // MAC 已存在，仅更新设备名称和最近在线时间，不创建新设备
            Log.d(TAG, "设备已存在(MAC=$macAddress)，更新信息: $name")
            deviceDao.updateDevice(
                existing.copy(name = name, lastSeenTime = System.currentTimeMillis())
            )
            existing.id
        } else {
            // MAC 不存在，创建新设备记录
            Log.d(TAG, "新设备(MAC=$macAddress): $name")
            val capacity = detectBatteryCapacity(name, modelName)
            deviceDao.insertDevice(
                DeviceInfo(
                    name = name,
                    macAddress = macAddress,
                    modelName = modelName,
                    batteryCapacityMah = capacity,
                    isHandwritingPen = isHandwritingPen(name, modelName)
                )
            )
        }
    }

    /**
     * 验证两个MAC地址是否为同一设备
     */
    fun isSameDevice(mac1: String, mac2: String): Boolean {
        return mac1.equals(mac2, ignoreCase = true)
    }

    suspend fun updateConnectionStatus(id: Long, connected: Boolean) {
        deviceDao.updateConnectionStatus(id, connected)
    }

    /** 重置所有设备的连接状态为离线（App 启动时调用） */
    suspend fun resetAllConnectionStatus() {
        deviceDao.resetAllConnectionStatus()
    }

    /** v4.1: 连接后自动修正设备名称（仅当旧名称为"未知设备"时） */
    suspend fun updateDeviceNameIfUnknown(macAddress: String, name: String) {
        deviceDao.updateDeviceNameIfUnknown(macAddress, name)
    }

    /** v4.2: 连接后同步修正设备名称 + 电池容量（仅当旧名称为"未知设备"时） */
    suspend fun updateDeviceNameAndCapacityIfUnknown(macAddress: String, name: String) {
        // 若用户已手动设置过容量，则只更新名称，保留手动容量值
        val existing = deviceDao.getDeviceByMac(macAddress)
        if (existing?.isCapacityUserSet == true) {
            Log.d(TAG, "v4.2 跳过容量自动识别(用户已手动设置): $macAddress → '$name'")
            deviceDao.updateDeviceNameIfUnknown(macAddress, name)
            return
        }
        val capacity = detectBatteryCapacity(name, "")
        deviceDao.updateDeviceNameAndCapacityIfUnknown(macAddress, name, capacity)
        Log.d(TAG, "v4.2 设备名称+容量已修正: $macAddress → '$name' (${capacity}mAh)")
    }

    /** v7: 用户手动修改电池容量（标记 isCapacityUserSet，后续自动识别不再覆盖） */
    suspend fun updateDeviceCapacity(deviceId: Long, capacity: Int) {
        val valid = capacity.coerceIn(1, 10000)
        deviceDao.updateDeviceCapacity(deviceId, valid)
        Log.d(TAG, "v7 用户手动设置容量: deviceId=$deviceId → ${valid}mAh")
    }

    /** v7: 可观察设备单行（容量/名称变更后实时刷新 UI 与监控服务） */
    fun observeDevice(id: Long): Flow<DeviceInfo?> = deviceDao.observeDeviceById(id)

    // ============ v8: 手写笔标记 / 历史健康快照 ============

    /** 用户手动标记/取消标记手写笔 */
    suspend fun setHandwritingPen(deviceId: Long, isPen: Boolean) {
        deviceDao.updateHandwritingPen(deviceId, isPen)
    }

    /**
     * 【逆向增强】补全设备画像：型号 + 手写笔标记。
     *
     * 数据来源优先级：
     *  1. 硬件 VID/PID 识别（HonorPenIdentifier，含 CD52 / Magic-Pencil 3 等具体型号）
     *  2. 荣耀系统态 `honor_bt_pencil_model_number`
     *
     * 调用方需自行保证"仅在字段缺失时写入"，避免覆盖用户已确认的数据。
     */
    suspend fun updateDeviceProfile(deviceId: Long, modelName: String, isPen: Boolean) {
        val existing = deviceDao.getDeviceById(deviceId) ?: return
        deviceDao.updateDevice(existing.copy(modelName = modelName, isHandwritingPen = isPen))
        Log.d(TAG, "设备画像更新: id=$deviceId, model='$modelName', isPen=$isPen")
    }

    /** 写入 3 个月滚动压缩后的健康度快照 */
    suspend fun saveHistoricalHealth(deviceId: Long, health: Float) {
        deviceDao.updateHistoricalHealth(deviceId, health, System.currentTimeMillis())
    }

    /**
     * v8: 手写笔电量记录滚动压缩。
     * 仅对手写笔生效：删除早于 maxAgeMs 的原始采样，并把压缩前的健康度快照写入设备行，
     * 避免 battery_records 无限膨胀（不影响实时健康度重算，仅保留近 N 天原始数据）。
     * @param maxAgeMs 保留窗口，默认 90 天
     */
    suspend fun compactOldRecords(maxAgeMs: Long = 90L * 24 * 60 * 60 * 1000) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        getAllDevicesList()
            .filter { it.isHandwritingPen }
            .forEach { dev ->
                val range = recordDao.getDataTimeRange(dev.id)
                // 仅当存在早于 cutoff 的记录时才压缩（避免无谓写库与覆盖快照）
                if (range.minTime in 1..cutoff) {
                    val health = getBatteryHealthResult(dev.id).healthPercent
                    recordDao.deleteRecordsBefore(dev.id, cutoff)
                    deviceDao.updateHistoricalHealth(dev.id, health, System.currentTimeMillis())
                    Log.d(TAG, "v8 滚动压缩完成: ${dev.macAddress} → 删除 ${cutoff} 前记录, 健康度快照=$health")
                }
            }
    }

    // ============ v8: 基于 MAC 的备份 / 恢复（SAF 文件，跨重装可用） ============

    /**
     * 导出全部手写笔设备（设备元信息 + 原始电量采样）为 JSON 字符串。
     */
    suspend fun exportBackup(): String {
        val pens = getAllDevicesList().filter { it.isHandwritingPen }
        val root = JSONObject().apply {
            put("version", 1)
            put("app", "BatteryAnalyzer")
            put("exportedAt", System.currentTimeMillis())
        }
        val arr = JSONArray()
        pens.forEach { dev ->
            val o = JSONObject().apply {
                put("mac", dev.macAddress)
                put("name", dev.name)
                put("modelName", dev.modelName)
                put("remark", dev.remark)
                put("batteryCapacityMah", dev.batteryCapacityMah)
                put("isCapacityUserSet", dev.isCapacityUserSet)
                put("historicalHealthPercent", dev.historicalHealthPercent)
            }
            val recArr = JSONArray()
            getAllRecords(dev.id).forEach { r ->
                recArr.put(JSONObject().apply {
                    put("ts", r.timestamp)
                    put("level", r.batteryLevel)
                    put("v", r.voltage)
                    put("t", r.temperature)
                    put("c", r.isCharging)
                    put("mah", r.estimatedMah)
                    put("ch", r.channel.name)
                })
            }
            o.put("records", recArr)
            arr.put(o)
        }
        root.put("devices", arr)
        return root.toString(2)
    }

    /**
     * 从备份 JSON 恢复手写笔设备。
     * 规则：仅当 App 内查不到该 MAC 时新建设备并导入原始记录；
     *       若已存在，则不覆盖容量/备注等用户设置，仅追加本地缺失的原始记录，并补齐手写笔标志。
     * @return 处理的设备数量
     */
    suspend fun importBackup(json: String): Int {
        val root = JSONObject(json)
        val arr = root.optJSONArray("devices") ?: return 0
        var imported = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val mac = o.optString("mac", "")
            if (mac.isBlank()) continue
            val name = o.optString("name", "未知设备")
            val modelName = o.optString("modelName", "")
            val remark = o.optString("remark", "")
            val capacity = o.optInt("batteryCapacityMah", 80)
            val isCapSet = o.optBoolean("isCapacityUserSet", false)
            val histHealth = o.optDouble("historicalHealthPercent", 100.0).toFloat()

            val existing = getDeviceByMac(mac)
            val deviceId = if (existing == null) {
                deviceDao.insertDevice(
                    DeviceInfo(
                        name = name,
                        macAddress = mac,
                        modelName = modelName,
                        remark = remark,
                        batteryCapacityMah = capacity,
                        isCapacityUserSet = isCapSet,
                        isHandwritingPen = true,
                        historicalHealthPercent = histHealth
                    )
                )
            } else {
                existing.id
            }

            // 追加本地缺失的原始记录（按 ts|level|channel 去重，避免重复导入）
            val recArr = o.optJSONArray("records")
            if (recArr != null) {
                val existKeys = if (existing != null) {
                    getAllRecords(existing.id)
                        .map { "${it.timestamp}|${it.batteryLevel}|${it.channel.name}" }
                        .toSet()
                } else emptySet()
                val toInsert = mutableListOf<BatteryRecord>()
                for (j in 0 until recArr.length()) {
                    val ro = recArr.getJSONObject(j)
                    val ts = ro.optLong("ts", 0L)
                    val level = ro.optInt("level", -1)
                    if (ts <= 0 || level < 0) continue
                    val ch = try {
                        BatteryChannel.valueOf(ro.optString("ch", "SINGLE"))
                    } catch (_: Exception) { BatteryChannel.SINGLE }
                    val key = "$ts|$level|${ch.name}"
                    if (existing != null && key in existKeys) continue
                    toInsert.add(
                        BatteryRecord(
                            deviceId = deviceId,
                            batteryLevel = level,
                            voltage = ro.optDouble("v", 0.0).toFloat(),
                            temperature = ro.optDouble("t", 0.0).toFloat(),
                            isCharging = ro.optBoolean("c", false),
                            timestamp = ts,
                            estimatedMah = ro.optDouble("mah", 0.0).toFloat(),
                            channel = ch
                        )
                    )
                }
                if (toInsert.isNotEmpty()) insertRecords(toInsert)
            }

            // 已存在设备也确保标记为手写笔（备份即视为手写笔）
            if (existing != null && !existing.isHandwritingPen) {
                deviceDao.updateHandwritingPen(existing.id, true)
            }
            imported++
        }
        return imported
    }

    /** v5.0: 获取所有设备（含输入设备），用于键值映射 */
    fun getAllDevicesForKeyMapping(): Flow<List<DeviceInfo>> = deviceDao.getAllDevicesForKeyMapping()

    /** v5.0: 获取所有输入设备 */
    suspend fun getInputDevices(): List<DeviceInfo> = deviceDao.getInputDevices()

    /**
     * v5.0: 插入或更新输入设备（键盘/鼠标/2.4G等非蓝牙设备）
     * 以 virtualMac（"input_设备名"）为唯一标识
     */
    /**
     * v5.2: 插入或更新输入设备（键盘/鼠标/2.4G/磁吸等非BLE设备）
     *
     * 通过 InputManager API 扫描到的设备即处于**已连接**状态，
     * 系统移除设备后下次扫描时不再出现，届时由 refreshInputDevices 标记断连。
     */
    suspend fun insertOrUpdateInputDevice(
        name: String,
        virtualMac: String,
        sources: String
    ): Long {
        val existing = deviceDao.getInputDeviceByVirtualMac(virtualMac)
        val now = System.currentTimeMillis()
        return if (existing != null) {
            deviceDao.updateDevice(
                existing.copy(
                    name = name,
                    isConnected = true,
                    lastSeenTime = now
                )
            )
            existing.id
        } else {
            val device = DeviceInfo(
                name = name,
                macAddress = virtualMac,
                deviceType = "INPUT_DEVICE",
                modelName = sources,
                batteryCapacityMah = 0,
                isConnected = true,
                lastSeenTime = now
            )
            deviceDao.insertDevice(device)
        }
    }

    /** 更新设备备注名 */
    suspend fun updateDeviceRemark(id: Long, remark: String) {
        deviceDao.updateRemark(id, remark)
    }

    /** 更新设备序列号（按 MAC 地址匹配） */
    suspend fun updateDeviceSerialNumber(macAddress: String, serialNumber: String) {
        deviceDao.updateSerialNumber(macAddress, serialNumber)
    }

    /**
     * v5.2: 清理已断连超过指定时间的无效输入设备记录
     * 用于清除之前扫描残留的 gpio-keys/pmic 等内部设备
     */
    suspend fun cleanStaleInputDevices(maxDisconnectedMs: Long = 24 * 60 * 60 * 1000L): Int {
        val devices = getInputDevices()
        val now = System.currentTimeMillis()
        var cleaned = 0
        devices.forEach { dev ->
            if (!dev.isConnected && (now - dev.lastSeenTime) > maxDisconnectedMs) {
                deviceDao.deleteDevice(dev.id)
                cleaned++
            }
        }
        return cleaned
    }

    // ============ 电量记录操作 ============

    suspend fun insertRecord(record: BatteryRecord) {
        recordDao.insertRecord(record)
    }

    /** 批量插入多通道记录 —— 使用 Room 原生批量插入，一次事务完成 */
    suspend fun insertRecords(records: List<BatteryRecord>) {
        if (records.isEmpty()) return
        recordDao.insertRecords(records)
    }

    fun getRecordsByDevice(deviceId: Long): Flow<List<BatteryRecord>> =
        recordDao.getRecordsByDevice(deviceId)

    fun getRecordsByDeviceAndChannel(deviceId: Long, channel: BatteryChannel): Flow<List<BatteryRecord>> =
        recordDao.getRecordsByDeviceAndChannel(deviceId, channel.name)

    fun getRecordsInRange(deviceId: Long, start: Long, end: Long): Flow<List<BatteryRecord>> =
        recordDao.getRecordsInRangeFlow(deviceId, start, end)

    suspend fun getRecordsInRangeByChannel(deviceId: Long, start: Long, end: Long, channel: BatteryChannel): List<BatteryRecord> =
        recordDao.getRecordsInRangeByChannel(deviceId, start, end, channel.name)

    suspend fun getLatestRecord(deviceId: Long): BatteryRecord? =
        recordDao.getLatestRecord(deviceId)

    suspend fun getLatestRecordByChannel(deviceId: Long, channel: BatteryChannel): BatteryRecord? =
        recordDao.getLatestRecordByChannel(deviceId, channel.name)

    suspend fun getAllRecords(deviceId: Long): List<BatteryRecord> =
        recordDao.getAllRecords(deviceId)

    suspend fun getRecordCount(deviceId: Long): Int =
        recordDao.getRecordCount(deviceId)

    /** 获取设备的所有可用通道 */
    suspend fun getAvailableChannels(deviceId: Long): List<BatteryChannel> {
        return recordDao.getAvailableChannels(deviceId).map {
            try { BatteryChannel.valueOf(it) } catch (e: Exception) { BatteryChannel.SINGLE }
        }.ifEmpty { listOf(BatteryChannel.SINGLE) }
    }

    // ============ 会话分析 (通用) ============

    /**
     * 分析充电会话 - 智能识别充电片段
     */
    suspend fun analyzeChargingSessions(deviceId: Long): List<ChargingSession> {
        return analyzeChargingSessions(deviceId, recordDao.getAllRecords(deviceId))
    }

    /** 内部版本：接受已加载的记录列表，避免在 getBatteryHealthResult() 中重复查询 */
    private suspend fun analyzeChargingSessions(deviceId: Long, records: List<BatteryRecord>): List<ChargingSession> {
        if (records.size < 2) return emptyList()

        val sessions = mutableListOf<ChargingSession>()
        var currentSession: ChargingSession? = null

        for (i in 1 until records.size) {
            val prev = records[i - 1]
            val curr = records[i]
            // 【修复 #12】原阈值 +1（需 >=2% 上升）导致缓慢充电（每次 +1%）被遗漏
            // 改为 > 即可捕获所有充电行为，同时用 isCharging 标志位辅助确认
            val isActuallyCharging = curr.batteryLevel > prev.batteryLevel

            if (isActuallyCharging) {
                if (currentSession == null) {
                    currentSession = ChargingSession(
                        deviceId = deviceId,
                        startTime = prev.timestamp,
                        startLevel = prev.batteryLevel
                    )
                }
                currentSession = currentSession?.copy(
                    endTime = curr.timestamp,
                    endLevel = curr.batteryLevel
                )
            } else if (curr.batteryLevel < prev.batteryLevel && currentSession != null) {
                finishChargingSession(currentSession!!, deviceId, sessions)
                currentSession = null
            }
        }
        currentSession?.let { finishChargingSession(it, deviceId, sessions) }
        return sessions
    }

    private suspend fun finishChargingSession(
        session: ChargingSession, deviceId: Long, sessions: MutableList<ChargingSession>
    ) {
        val capacity = getDeviceById(deviceId)?.batteryCapacityMah ?: 80
        val chargedMah = (session.endLevel - session.startLevel) / 100f * capacity
        val range = session.endLevel - session.startLevel
        val isCompleteCycle = session.startLevel <= 20 && session.endLevel >= 80
        sessions.add(
            session.copy(
                chargedMah = chargedMah.coerceAtLeast(0f),
                durationMinutes = (session.endTime - session.startTime) / 60000,
                isCompleted = session.endLevel >= 95,
                sessionType = if (isCompleteCycle) AnalysisStrategy.LONG_TERM else AnalysisStrategy.SHORT_TERM
            )
        )
    }

    /**
     * 分析耗电会话
     */
    suspend fun analyzeDischargeSessions(deviceId: Long): List<DischargeSession> {
        return analyzeDischargeSessions(deviceId, recordDao.getAllRecords(deviceId))
    }

    /** 内部版本：接受已加载的记录列表，避免在 getBatteryHealthResult() 中重复查询 */
    private suspend fun analyzeDischargeSessions(deviceId: Long, records: List<BatteryRecord>): List<DischargeSession> {
        if (records.size < 2) return emptyList()

        val sessions = mutableListOf<DischargeSession>()
        var currentSession: DischargeSession? = null

        for (i in 1 until records.size) {
            val prev = records[i - 1]
            val curr = records[i]
            val isActuallyDischarging = curr.batteryLevel < prev.batteryLevel && !curr.isCharging

            if (isActuallyDischarging) {
                if (currentSession == null) {
                    currentSession = DischargeSession(
                        deviceId = deviceId,
                        startTime = prev.timestamp,
                        startLevel = prev.batteryLevel
                    )
                }
                currentSession = currentSession?.copy(
                    endTime = curr.timestamp,
                    endLevel = curr.batteryLevel
                )
            } else if (curr.batteryLevel >= prev.batteryLevel && currentSession != null) {
                finishDischargeSession(currentSession!!, deviceId, sessions)
                currentSession = null
            }
        }
        currentSession?.let { finishDischargeSession(it, deviceId, sessions) }
        return sessions
    }

    private suspend fun finishDischargeSession(
        session: DischargeSession, deviceId: Long, sessions: MutableList<DischargeSession>
    ) {
        val capacity = getDeviceById(deviceId)?.batteryCapacityMah ?: 80
        val consumedMah = (session.startLevel - session.endLevel) / 100f * capacity
        val durationHours = (session.endTime - session.startTime) / 3600000f
        val avgRate = if (durationHours > 0) consumedMah / durationHours else 0f
        val range = session.startLevel - session.endLevel
        val isCompleteCycle = session.startLevel >= 80 && session.endLevel <= 20
        sessions.add(
            session.copy(
                consumedMah = consumedMah.coerceAtLeast(0f),
                durationMinutes = (session.endTime - session.startTime) / 60000,
                avgDischargeRate = avgRate,
                sessionType = if (isCompleteCycle) AnalysisStrategy.LONG_TERM else AnalysisStrategy.SHORT_TERM
            )
        )
    }

    // ============ 长时完整充放电循环分析 (计入健康度) ============

    /**
     * 识别完整充电循环：从 ≤20% 充到 ≥80%
     * 这些是长时分析核心数据，必须计入电池健康度评估
     */
    suspend fun analyzeCompleteChargeCycles(deviceId: Long): List<CompleteCycle> {
        return analyzeCompleteChargeCycles(deviceId, recordDao.getAllRecords(deviceId))
    }

    /** 内部版本：接受已加载的记录列表，避免在 getLongTermAnalysisResult() 中重复查询 */
    private suspend fun analyzeCompleteChargeCycles(deviceId: Long, records: List<BatteryRecord>): List<CompleteCycle> {
        if (records.size < 2) return emptyList()

        val cycles = mutableListOf<CompleteCycle>()
        val capacity = getDeviceById(deviceId)?.batteryCapacityMah ?: 80
        var inCharge = false
        var chargeStartTime = 0L
        var chargeStartLevel = 0

        for (i in 1 until records.size) {
            val prev = records[i - 1]
            val curr = records[i]
            // 【修复 #12】原阈值 +1 导致缓慢充电的完整循环被遗漏
            val isCharging = curr.batteryLevel > prev.batteryLevel

            if (isCharging && !inCharge) {
                inCharge = true
                chargeStartTime = prev.timestamp
                chargeStartLevel = prev.batteryLevel
            }

            if (inCharge && (!isCharging || i == records.lastIndex)) {
                val endTime = curr.timestamp
                val endLevel = curr.batteryLevel
                val range = endLevel - chargeStartLevel

                // 仅识别完整循环：起始 ≤20% 且 终止 ≥80%
                if (chargeStartLevel <= 20 && endLevel >= 80 && range >= 60) {
                    val totalMah = (range / 100f) * capacity
                    val durationMin = (endTime - chargeStartTime) / 60000L
                    val avgRate = if (durationMin > 0) totalMah / (durationMin / 60f) else 0f

                    cycles.add(
                        CompleteCycle(
                            deviceId = deviceId,
                            cycleType = CycleType.FULL_CHARGE,
                            startTime = chargeStartTime,
                            endTime = endTime,
                            startLevel = chargeStartLevel,
                            endLevel = endLevel,
                            totalMah = totalMah,
                            durationMinutes = durationMin,
                            avgRateMahPerHour = avgRate,
                            coveragePercent = range
                        )
                    )
                }
                inCharge = false
            }
        }
        return cycles
    }

    /**
     * 识别完整放电循环：从 ≥80% 放到 ≤20%
     * 这些是长时分析核心数据，必须计入电池健康度评估
     */
    suspend fun analyzeCompleteDischargeCycles(deviceId: Long): List<CompleteCycle> {
        return analyzeCompleteDischargeCycles(deviceId, recordDao.getAllRecords(deviceId))
    }

    /** 内部版本：接受已加载的记录列表，避免在 getLongTermAnalysisResult() 中重复查询 */
    private suspend fun analyzeCompleteDischargeCycles(deviceId: Long, records: List<BatteryRecord>): List<CompleteCycle> {
        if (records.size < 2) return emptyList()

        val cycles = mutableListOf<CompleteCycle>()
        val capacity = getDeviceById(deviceId)?.batteryCapacityMah ?: 80
        var inDischarge = false
        var dischargeStartTime = 0L
        var dischargeStartLevel = 0

        for (i in 1 until records.size) {
            val prev = records[i - 1]
            val curr = records[i]
            val isDischarging = curr.batteryLevel < prev.batteryLevel && !curr.isCharging

            if (isDischarging && !inDischarge) {
                inDischarge = true
                dischargeStartTime = prev.timestamp
                dischargeStartLevel = prev.batteryLevel
            }

            if (inDischarge && (!isDischarging || i == records.lastIndex)) {
                val endTime = curr.timestamp
                val endLevel = curr.batteryLevel
                val range = dischargeStartLevel - endLevel

                // 仅识别完整循环：起始 ≥80% 且 终止 ≤20%
                if (dischargeStartLevel >= 80 && endLevel <= 20 && range >= 60) {
                    val totalMah = (range / 100f) * capacity
                    val durationMin = (endTime - dischargeStartTime) / 60000L
                    val avgRate = if (durationMin > 0) totalMah / (durationMin / 60f) else 0f

                    cycles.add(
                        CompleteCycle(
                            deviceId = deviceId,
                            cycleType = CycleType.FULL_DISCHARGE,
                            startTime = dischargeStartTime,
                            endTime = endTime,
                            startLevel = dischargeStartLevel,
                            endLevel = endLevel,
                            totalMah = totalMah,
                            durationMinutes = durationMin,
                            avgRateMahPerHour = avgRate,
                            coveragePercent = range
                        )
                    )
                }
                inDischarge = false
            }
        }
        return cycles
    }

    /**
     * 获取长时电量分析结果 —— 【计入电池健康度评估】
     * 仅分析完整充放电循环数据，与短时分析完全独立
     */
    suspend fun getLongTermAnalysisResult(deviceId: Long): LongTermAnalysisResult {
        return getLongTermAnalysisResult(deviceId, recordDao.getAllRecords(deviceId))
    }

    /** 内部版本：接受已加载的记录列表，避免在 getBatteryHealthResult() 中重复查询 */
    private suspend fun getLongTermAnalysisResult(deviceId: Long, allRecords: List<BatteryRecord>): LongTermAnalysisResult {
        val device = getDeviceById(deviceId)
        val capacity = device?.batteryCapacityMah ?: 80

        val chargeCycles = analyzeCompleteChargeCycles(deviceId, allRecords)
        val dischargeCycles = analyzeCompleteDischargeCycles(deviceId, allRecords)

        // 基于完整充电循环计算实际容量
        val actualCapacity: Float
        val health: Float

        if (chargeCycles.isNotEmpty()) {
            val recentChargeCycles = chargeCycles.takeLast(10)
            var totalWeight = 0f
            var weightedSum = 0f
            recentChargeCycles.forEachIndexed { index, cycle ->
                val weight = (index + 1).toFloat()
                // 【修复】coveragePercent 可能为 0，除以 0 会得到 Infinity/NaN，
                // 经 coerceIn 兜底成 100f 反而高估健康度。分母下限取 1（Int 类型）。
                val adjustedCapacity = cycle.totalMah / (cycle.coveragePercent.coerceAtLeast(1) / 100f)
                weightedSum += adjustedCapacity * weight
                totalWeight += weight
            }
            actualCapacity = if (totalWeight > 0) (weightedSum / totalWeight).coerceAtLeast(1f) else capacity.toFloat()
        } else if (dischargeCycles.isNotEmpty()) {
            val recentDischargeCycles = dischargeCycles.takeLast(10)
            var totalWeight = 0f
            var weightedSum = 0f
            recentDischargeCycles.forEachIndexed { index, cycle ->
                val weight = (index + 1).toFloat()
                val adjustedCapacity = cycle.totalMah / (cycle.coveragePercent.coerceAtLeast(1) / 100f)
                weightedSum += adjustedCapacity * weight
                totalWeight += weight
            }
            actualCapacity = if (totalWeight > 0) (weightedSum / totalWeight).coerceAtLeast(1f) else capacity.toFloat()
        } else {
            actualCapacity = capacity.toFloat()
        }

        health = (actualCapacity / capacity * 100).coerceIn(30f, 100f)

        // 估算等效循环次数
        val totalChargedMah = chargeCycles.sumOf { it.totalMah.toDouble() }
        val estimatedCycles = if (capacity > 0) (totalChargedMah / capacity).toFloat() else 0f

        val analysisStartTime = allRecords.firstOrNull()?.timestamp ?: 0L
        val analysisEndTime = allRecords.lastOrNull()?.timestamp ?: 0L

        return LongTermAnalysisResult(
            deviceId = deviceId,
            deviceCapacityMah = capacity,
            completeChargeCycles = chargeCycles,
            totalChargeCycles = chargeCycles.size,
            totalChargedMah = chargeCycles.sumOf { it.totalMah.toDouble() }.toFloat(),
            avgChargeCapacityMah = if (chargeCycles.isNotEmpty()) {
                chargeCycles.map { it.totalMah / (it.coveragePercent / 100f) }.average().toFloat()
            } else 0f,
            completeDischargeCycles = dischargeCycles,
            totalDischargeCycles = dischargeCycles.size,
            totalDischargedMah = dischargeCycles.sumOf { it.totalMah.toDouble() }.toFloat(),
            avgDischargeCapacityMah = if (dischargeCycles.isNotEmpty()) {
                dischargeCycles.map { it.totalMah / (it.coveragePercent / 100f) }.average().toFloat()
            } else 0f,
            estimatedActualCapacityMah = actualCapacity,
            healthPercent = health,
            estimatedCycleCount = estimatedCycles,
            analyzedRecordCount = allRecords.size,
            analysisStartTime = analysisStartTime,
            analysisEndTime = analysisEndTime
        )
    }

    // ============ 电池健康度 (仅基于长时完整循环) ============

    /**
     * 电池健康度分析结果
     */
    data class BatteryHealthResult(
        val healthPercent: Float,
        val actualCapacityMah: Float,
        val theoreticalCapacityMah: Int,
        val totalChargingSessions: Int,
        val totalDischargeSessions: Int,
        val estimatedCycleCount: Float,
        val completeChargeCycles: Int = 0,
        val completeDischargeCycles: Int = 0
    )

    /**
     * 计算电池健康度 —— 【仅基于长时完整充放电循环】
     * 短时分析数据不参与此计算
     */
    suspend fun calculateBatteryHealth(deviceId: Long): Float {
        return getBatteryHealthResult(deviceId).healthPercent
    }

    /**
     * 获取完整的电池健康度分析结果
     * 核心原则：仅使用完整0-100充放电循环（长时分析），
     * 短时功耗分析数据不计入健康度计算
     */
    suspend fun getBatteryHealthResult(deviceId: Long): BatteryHealthResult {
        val device = getDeviceById(deviceId)
        val theoreticalCapacity = device?.batteryCapacityMah ?: 80

        // 【修复】一次性加载所有记录，避免 getLongTermAnalysisResult + analyzeChargingSessions
        // + analyzeDischargeSessions 各自重复执行 getAllRecords() 全表查询（原为 3 次，现为 1 次）
        val allRecords = recordDao.getAllRecords(deviceId)

        // 使用长时分析结果（仅完整循环）
        val longTermResult = getLongTermAnalysisResult(deviceId, allRecords)
        val chargingSessions = analyzeChargingSessions(deviceId, allRecords)
        val dischargeSessions = analyzeDischargeSessions(deviceId, allRecords)

        if (longTermResult.completeChargeCycles.isEmpty() && longTermResult.completeDischargeCycles.isEmpty()) {
            // 无完整循环，返回默认健康度（不基于短时数据估算以保持准确性）
            return BatteryHealthResult(
                healthPercent = 100f,
                actualCapacityMah = theoreticalCapacity.toFloat(),
                theoreticalCapacityMah = theoreticalCapacity,
                totalChargingSessions = chargingSessions.size,
                totalDischargeSessions = dischargeSessions.size,
                estimatedCycleCount = 0f,
                completeChargeCycles = 0,
                completeDischargeCycles = 0
            )
        }

        return BatteryHealthResult(
            healthPercent = longTermResult.healthPercent,
            actualCapacityMah = longTermResult.estimatedActualCapacityMah,
            theoreticalCapacityMah = theoreticalCapacity,
            totalChargingSessions = chargingSessions.size,
            totalDischargeSessions = dischargeSessions.size,
            estimatedCycleCount = longTermResult.estimatedCycleCount,
            completeChargeCycles = longTermResult.totalChargeCycles,
            completeDischargeCycles = longTermResult.totalDischargeCycles
        )
    }

    // ============ 短时功耗分析 (仅做灵敏参考，不计入健康度) ============

    /**
     * 短时功耗分析 - 【不计入电池健康度统计】
     * 用于高灵敏度实时功耗对比参考
     * 支持从分钟到年的任意时间窗口
     *
     * @param deviceId 设备ID
     * @param windowStartMs 时间窗起始(毫秒时间戳)
     * @param windowEndMs 时间窗结束(毫秒时间戳)
     */
    suspend fun analyzeShortTermPower(
        deviceId: Long,
        windowStartMs: Long,
        windowEndMs: Long
    ): ShortTermPowerAnalysis {
        val device = getDeviceById(deviceId)
        val capacity = device?.batteryCapacityMah ?: 80
        val records = recordDao.getRecordsInRange(deviceId, windowStartMs, windowEndMs)

        if (records.size < 2) {
            return ShortTermPowerAnalysis(
                deviceId = deviceId,
                deviceCapacityMah = capacity,
                windowStartMs = windowStartMs,
                windowEndMs = windowEndMs,
                totalRecords = records.size,
                changes = emptyList(),
                chargeCount = 0, totalChargedMah = 0f, totalChargeTimeMinutes = 0f,
                avgChargeRate = 0f, maxChargeRate = 0f, maxChargeRateLevel = 0,
                dischargeCount = 0, totalDischargedMah = 0f, totalDischargeTimeMinutes = 0f,
                avgDischargeRate = 0f, maxDischargeRate = 0f, maxDischargeRateLevel = 0,
                netChangePercent = 0, netChangeMah = 0f, chargeDischargeRatio = 0f
            )
        }

        // 逐对分析：每1%变化都统计瞬时功率
        // 注意：短时数据不参与健康度计算
        val changes = mutableListOf<PowerChange>()

        for (i in 1 until records.size) {
            val prev = records[i - 1]
            val curr = records[i]

            if (curr.batteryLevel == prev.batteryLevel) continue

            val changePercent = curr.batteryLevel - prev.batteryLevel
            val durationMs = curr.timestamp - prev.timestamp
            if (durationMs <= 0 || durationMs > 1800000) continue // 超过30分钟视为断连

            val durationSeconds = durationMs / 1000f
            val durationHours = durationSeconds / 3600f
            val changeMah = (changePercent / 100f) * capacity
            val rateMahPerHour = if (durationHours > 0) {
                abs(changeMah / durationHours)
            } else 0f

            changes.add(
                PowerChange(
                    timestamp = curr.timestamp,
                    fromLevel = prev.batteryLevel,
                    toLevel = curr.batteryLevel,
                    isCharging = changePercent > 0,
                    durationSeconds = durationSeconds,
                    changePercent = changePercent,
                    changeMah = changeMah,
                    rateMahPerHour = rateMahPerHour
                )
            )
        }

        val chargeChanges = changes.filter { it.isCharging }
        val dischargeChanges = changes.filter { !it.isCharging }

        return ShortTermPowerAnalysis(
            deviceId = deviceId,
            deviceCapacityMah = capacity,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            totalRecords = records.size,
            changes = changes,
            chargeCount = chargeChanges.size,
            totalChargedMah = chargeChanges.sumOf { it.changeMah.toDouble() }.toFloat(),
            totalChargeTimeMinutes = chargeChanges.sumOf { (it.durationSeconds / 60).toDouble() }.toFloat(),
            avgChargeRate = if (chargeChanges.isNotEmpty()) chargeChanges.map { it.rateMahPerHour }.average().toFloat() else 0f,
            maxChargeRate = chargeChanges.maxByOrNull { it.rateMahPerHour }?.rateMahPerHour ?: 0f,
            maxChargeRateLevel = chargeChanges.maxByOrNull { it.rateMahPerHour }?.fromLevel ?: 0,
            dischargeCount = dischargeChanges.size,
            totalDischargedMah = dischargeChanges.sumOf { abs(it.changeMah).toDouble() }.toFloat(),
            totalDischargeTimeMinutes = dischargeChanges.sumOf { (it.durationSeconds / 60).toDouble() }.toFloat(),
            avgDischargeRate = if (dischargeChanges.isNotEmpty()) dischargeChanges.map { it.rateMahPerHour }.average().toFloat() else 0f,
            maxDischargeRate = dischargeChanges.maxByOrNull { it.rateMahPerHour }?.rateMahPerHour ?: 0f,
            maxDischargeRateLevel = dischargeChanges.maxByOrNull { it.rateMahPerHour }?.fromLevel ?: 0,
            netChangePercent = if (records.size >= 2) records.last().batteryLevel - records.first().batteryLevel else 0,
            netChangeMah = ((if (records.size >= 2) records.last().batteryLevel - records.first().batteryLevel else 0) / 100f) * capacity,
            chargeDischargeRatio = if (dischargeChanges.isNotEmpty()) {
                (if (chargeChanges.isNotEmpty()) chargeChanges.map { it.rateMahPerHour }.average() else 0.0).toFloat() /
                (dischargeChanges.map { it.rateMahPerHour }.average().toFloat().coerceAtLeast(0.01f))
            } else 0f
        )
    }

    /**
     * 获取设备的最后N条记录（用于实时短时分析）
     */
    suspend fun getRecentRecords(deviceId: Long, count: Int = 200): List<BatteryRecord> {
        return recordDao.getLatestRecords(deviceId, count).reversed()
    }

    // ============ 时间维度聚合曲线数据 ============

    /**
     * 按时间维度获取聚合后的曲线数据点
     * 优先从聚合缓存读取，若缓存无效则从原始数据实时计算并写入缓存
     *
     * @param deviceId 设备ID
     * @param dimension 时间维度（分钟/天/月/年）
     * @param start 查询起始时间戳
     * @param end 查询结束时间戳
     * @param forceRefresh 强制重新计算（忽略缓存）
     */
    suspend fun getCurvePoints(
        deviceId: Long,
        dimension: TimeDimension,
        start: Long,
        end: Long,
        forceRefresh: Boolean = false
    ): List<CurvePoint> {
        // 检查缓存是否有效
        val cacheFresh = if (!forceRefresh) {
            val marker = recordDao.getDataFreshnessMarker(deviceId, dimension.name)
            marker != null
        } else false

        if (cacheFresh) {
            val cached = recordDao.getAggregatedCache(deviceId, dimension.name, start, end)
            if (cached.isNotEmpty()) {
                Log.d(TAG, "使用聚合缓存: $dimension, ${cached.size} 个数据点")
                return cached.map { it.toCurvePoint() }
            }
        }

        // 实时计算聚合数据
        Log.d(TAG, "实时聚合计算: $dimension, $start ~ $end")
        val aggregated = when (dimension) {
            TimeDimension.MINUTES -> recordDao.getMinuteAggregation(deviceId, start, end)
            TimeDimension.DAYS -> recordDao.getDayAggregation(deviceId, start, end)
            TimeDimension.MONTHS -> recordDao.getMonthAggregation(deviceId, start, end)
            TimeDimension.YEARS -> recordDao.getYearAggregation(deviceId, start, end)
        }

        val points = aggregated.map { it.toCurvePoint() }

        // 将计算结果写入缓存（原子替换，事务保护）
        if (points.isNotEmpty()) {
            try {
                val cacheEntities = aggregated.map { it.toCacheEntity(deviceId, dimension) }
                recordDao.replaceAggregatedCache(deviceId, dimension.name, cacheEntities)
                Log.d(TAG, "聚合缓存已更新: $dimension, ${cacheEntities.size} 条")
            } catch (e: Exception) {
                Log.w(TAG, "写入聚合缓存失败: ${e.message}")
            }
        }

        return points
    }

    /**
     * 获取指定设备数据的总时间范围（数据库聚合查询，O(1) 内存）
     */
    suspend fun getDataTimeRange(deviceId: Long): Pair<Long, Long> {
        val range = recordDao.getDataTimeRange(deviceId)
        if (range.minTime == 0L && range.maxTime == 0L) return Pair(0L, 0L)
        return Pair(range.minTime, range.maxTime)
    }

    // ============ 扩展函数：聚合结果 → CurvePoint ============

    private fun AggregationResult.toCurvePoint(): CurvePoint {
        return CurvePoint(
            timestamp = periodStart,
            avgLevel = avgLevel.toFloat(),
            minLevel = minLevel,
            maxLevel = maxLevel,
            chargingRatio = chargingRatio.toFloat(),
            recordCount = recordCount
        )
    }

    private fun AggregationResult.toCacheEntity(deviceId: Long, dimension: TimeDimension): AggregatedRecordEntity {
        return AggregatedRecordEntity(
            deviceId = deviceId,
            dimension = dimension.name,
            periodStart = periodStart,
            avgLevel = avgLevel.toFloat(),
            minLevel = minLevel,
            maxLevel = maxLevel,
            chargingRatio = chargingRatio.toFloat(),
            recordCount = recordCount
        )
    }

    private fun AggregatedRecordEntity.toCurvePoint(): CurvePoint {
        return CurvePoint(
            timestamp = periodStart,
            avgLevel = avgLevel,
            minLevel = minLevel,
            maxLevel = maxLevel,
            chargingRatio = chargingRatio,
            recordCount = recordCount
        )
    }
}
