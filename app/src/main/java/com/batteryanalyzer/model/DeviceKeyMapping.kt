package com.batteryanalyzer.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 每设备键值映射表
 *
 * 每个设备拥有独立的源键值→目标键值映射规则。
 * [isEnabled] 同时充当"启用锁定"：开启后映射生效且UI禁止编辑，关闭后方可修改。
 */
@Entity(
    tableName = "device_key_mappings",
    foreignKeys = [
        ForeignKey(
            entity = DeviceInfo::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["deviceId"])]  // v6: 移除 unique，允许每设备多条映射
)
data class DeviceKeyMapping(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,                       // 关联设备 (FK → DeviceInfo.id)
    val sourceKeyCode: Int = -1,              // 原始键值
    val targetKeyCode: Int = -1,              // 映射目标键值
    val sourceLabel: String = "",             // 原始键值友好名称
    val targetLabel: String = "",             // 目标键值友好名称
    val isEnabled: Boolean = false,           // 启用锁定开关（启用后映射激活+UI锁定）
    val injectionMethod: String = "",         // 注入方式 (SHIZUKU/ACCESSIBILITY/SHELL)
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
