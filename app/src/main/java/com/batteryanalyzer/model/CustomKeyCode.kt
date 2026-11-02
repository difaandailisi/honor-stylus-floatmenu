package com.batteryanalyzer.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 自定义键值字典 — 动态扩展
 *
 * 当用户输入的键值不在系统内置字典（androidKeyCodes/linuxKeyCodes/aliases）中时，
 * 系统提示用户确认后自动录入此表，实现键值字典的动态扩展。
 * 自定义键值全局共享（不绑定设备），避免重复录入。
 */
@Entity(
    tableName = "custom_key_codes",
    indices = [Index(value = ["code"], unique = true)]
)
data class CustomKeyCode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: Int,                           // 键值编号 (唯一)
    val name: String = "",                   // 键值名称 (如 KEYCODE_SEMICOLON)
    val category: String = "CUSTOM",         // 分类: ANDROID / LINUX / CUSTOM
    val description: String = "",            // 可选的详细描述
    val createdAt: Long = System.currentTimeMillis()
)
