package com.batteryanalyzer.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 设备信息表
 * v5.0: 新增 deviceType 字段，支持键盘/鼠标等非蓝牙输入设备
 * v4.0: 新增 serialNumber(序列号) 和 remark(备注) 字段
 */
@Entity(tableName = "devices", indices = [Index(value = ["macAddress"], unique = true)])
data class DeviceInfo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                    // 设备名称（来自蓝牙扫描或GATT Device Name特征值）
    val macAddress: String,              // 唯一标识（BLE设备为MAC地址，输入设备为 "input_设备名"）
    val deviceType: String = "BLE",      // v5.0: 设备类型 "BLE" / "INPUT_DEVICE"
    val modelName: String = "",          // 设备型号（来自 Device Info Service Model Number）
    val serialNumber: String = "",       // 序列号（来自 Device Info Service Serial Number 0x2A25）
    val remark: String = "",             // 用户自定义备注名（仅BLE设备可编辑）
    val batteryCapacityMah: Int = 80,    // 电池容量 mAh (手写笔标准值)
    val isCapacityUserSet: Boolean = false, // 用户是否手动设置过电池容量（手动后不再被自动识别覆盖）
    val isConnected: Boolean = false,     // 是否当前已连接
    val lastSeenTime: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    // v8: 是否手写笔（按品牌名自动识别 + 用户可手动标记），决定备份/非破坏取消配对是否对其生效
    val isHandwritingPen: Boolean = false,
    // v8: 最近一次 3 个月滚动压缩时记录的健康度快照（用于压缩删记录后展示历史值）
    val historicalHealthPercent: Float = 100f,
    // v8: 最近一次滚动压缩时间
    val lastCompactionTime: Long = 0L
) {
    /** 获取用于显示的名称：优先使用备注名，否则使用原始设备名 */
    val displayName: String get() = remark.ifBlank { name }

    /** v5.0: 是否为蓝牙BLE设备 */
    val isBleDevice: Boolean get() = deviceType == "BLE"

    /** v5.0: 是否为输入设备（键盘/鼠标/2.4G等） */
    val isInputDevice: Boolean get() = deviceType == "INPUT_DEVICE"

    /** v5.0: 是否允许编辑备注（仅BLE设备） */
    val isRemarkEditable: Boolean get() = isBleDevice
}
