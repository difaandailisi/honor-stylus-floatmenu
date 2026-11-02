package com.batteryanalyzer.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 电池通道类型 - 用于区分多电池设备（如蓝牙耳机）
 */
enum class BatteryChannel {
    /** 单通道（默认，兼容旧数据） */
    SINGLE,
    /** 左耳 */
    LEFT,
    /** 右耳 */
    RIGHT,
    /** 充电仓 */
    CASE,
    /** 其他/未知通道 */
    OTHER;

    companion object {
        /** 友好名称映射 */
        fun displayName(channel: BatteryChannel): String = when (channel) {
            SINGLE -> "电池"
            LEFT -> "左耳"
            RIGHT -> "右耳"
            CASE -> "充电仓"
            OTHER -> "其他"
        }

        /** 图标映射 */
        fun icon(channel: BatteryChannel): String = when (channel) {
            SINGLE -> "🔋"
            LEFT -> "👂L"
            RIGHT -> "👂R"
            CASE -> "📦"
            OTHER -> "❓"
        }
    }
}

/**
 * 电量记录表 - 记录每次电量变化
 * 支持多通道电量记录（左/右耳、充电仓等）
 */
@Entity(
    tableName = "battery_records",
    foreignKeys = [
        ForeignKey(
            entity = DeviceInfo::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["deviceId"]),
        Index(value = ["timestamp"]),
        Index(value = ["deviceId", "timestamp"]),
        Index(value = ["deviceId", "channel"]),
        Index(value = ["deviceId", "channel", "timestamp"])
    ]
)
data class BatteryRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    val batteryLevel: Int,       // 电量百分比 0-100
    val voltage: Float = 0f,     // 电压(V)
    val temperature: Float = 0f, // 温度(°C)
    val isCharging: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val estimatedMah: Float = 0f, // 估算的实际毫安时
    val channel: BatteryChannel = BatteryChannel.SINGLE // 电量通道
)
