package com.batteryanalyzer.model

/**
 * 时间维度枚举 - 用于电量曲线聚合展示
 */
enum class TimeDimension(val label: String, val shortLabel: String, val durationMs: Long) {
    /** 按分钟聚合 — 原始精度 */
    MINUTES("按分钟", "分", 60_000L),
    /** 按天聚合 */
    DAYS("按天", "天", 86_400_000L),
    /** 按月聚合 */
    MONTHS("按月", "月", 2_592_000_000L),
    /** 按年聚合 */
    YEARS("按年", "年", 31_536_000_000L);

    companion object {
        fun all(): List<TimeDimension> = entries.toList()
    }
}

/**
 * 曲线数据点 — 用于电量趋势曲线
 * @param timestamp 该时间点的毫秒时间戳
 * @param avgLevel 该聚合时段的平均电量
 * @param minLevel 该聚合时段的最低电量
 * @param maxLevel 该聚合时段的最高电量
 * @param chargingRatio 该时段内充电记录的占比(0-1)
 * @param recordCount 该时段原始记录数
 */
data class CurvePoint(
    val timestamp: Long,
    val avgLevel: Float,
    val minLevel: Int,
    val maxLevel: Int,
    val chargingRatio: Float = 0f,
    val recordCount: Int = 0
)
