package com.batteryanalyzer.model

// ============ 会话类型标记 ============
/**
 * 分析策略枚举 - 明确区分短时与长时分析
 */
enum class AnalysisStrategy {
    /** 短时分析：仅作灵敏参考指标，不计入电池健康度计算 */
    SHORT_TERM,
    /** 长时分析：完整0-100充放电循环数据，计入电池健康度评估 */
    LONG_TERM
}

// ============ 充电/耗电会话 (通用) ============

/**
 * 充电会话 - 一次充电过程
 * @param sessionType 标识此会话属于长时完整循环还是短时片段
 */
data class ChargingSession(
    val id: Long = 0,
    val deviceId: Long,
    val startTime: Long,
    val endTime: Long = 0L,
    val startLevel: Int,
    val endLevel: Int = 0,
    val chargedMah: Float = 0f,
    val durationMinutes: Long = 0,
    val isCompleted: Boolean = false,
    val sessionType: AnalysisStrategy = AnalysisStrategy.LONG_TERM
)

/**
 * 耗电会话 - 一次连续的放电过程
 */
data class DischargeSession(
    val id: Long = 0,
    val deviceId: Long,
    val startTime: Long,
    val endTime: Long = 0L,
    val startLevel: Int,
    val endLevel: Int = 0,
    val consumedMah: Float = 0f,
    val durationMinutes: Long = 0,
    val avgDischargeRate: Float = 0f, // mAh/小时
    val sessionType: AnalysisStrategy = AnalysisStrategy.LONG_TERM
)

// ============ 长时完整充放电循环模型 (计入健康度) ============

/**
 * 完整充放电循环 - 长时分析核心数据单元
 * 定义：一次完整 0→100% 充电 或 100→0% 放电
 */
data class CompleteCycle(
    val id: Long = 0,
    val deviceId: Long,
    val cycleType: CycleType,
    val startTime: Long,
    val endTime: Long,
    val startLevel: Int,
    val endLevel: Int,
    val totalMah: Float,              // 充入/消耗的总 mAh
    val durationMinutes: Long,        // 持续时间(分钟)
    val avgRateMahPerHour: Float,     // 平均速率 mAh/h
    val coveragePercent: Int          // 覆盖的百分比范围 (如 0→95 即为 95%)
)

enum class CycleType {
    /** 完整充电循环：从低电量(≤20%)充到高电量(≥80%) */
    FULL_CHARGE,
    /** 完整放电循环：从高电量(≥80%)放到低电量(≤20%) */
    FULL_DISCHARGE
}

/**
 * 长时电量分析结果 - 计入电池健康度评估
 */
data class LongTermAnalysisResult(
    val deviceId: Long,
    val deviceCapacityMah: Int,
    // 完整充电循环统计
    val completeChargeCycles: List<CompleteCycle>,
    val totalChargeCycles: Int,
    val totalChargedMah: Float,
    val avgChargeCapacityMah: Float,      // 平均每次完整充电实际容量
    // 完整放电循环统计
    val completeDischargeCycles: List<CompleteCycle>,
    val totalDischargeCycles: Int,
    val totalDischargedMah: Float,
    val avgDischargeCapacityMah: Float,   // 平均每次完整放电实际容量
    // 综合指标
    val estimatedActualCapacityMah: Float, // 估算实际容量（基于完整循环）
    val healthPercent: Float,             // 基于完整循环计算的健康度
    val estimatedCycleCount: Float,       // 估算等效充放电循环次数
    val analyzedRecordCount: Int,         // 参与分析的总记录数
    val analysisStartTime: Long,          // 分析起始时间
    val analysisEndTime: Long             // 分析结束时间
)

// ============ 短时功耗分析模型 (不计入健康度统计) ============

/**
 * 单次电量变化记录 - 相邻两次读数间的功率变化
 * 粒度：每1%电量变化都记录
 */
data class PowerChange(
    val timestamp: Long,          // 变化发生时间
    val fromLevel: Int,           // 变化前电量 %
    val toLevel: Int,             // 变化后电量 %
    val isCharging: Boolean,      // 是否充电变化
    val durationSeconds: Float,   // 持续时间(秒)
    val changePercent: Int,       // 变化百分比 (正数=充电 负数=耗电)
    val changeMah: Float,         // 变化毫安时 (正数=充入 负数=消耗)
    val rateMahPerHour: Float     // 瞬时功率速率 mAh/h (绝对值)
)

/**
 * 短时功耗分析结果 - 【不计入电池健康度】
 * 仅作高灵敏度实时参考指标
 * 支持长跨度的充电/耗电功耗对比（分钟~年）
 */
data class ShortTermPowerAnalysis(
    val deviceId: Long,
    val deviceCapacityMah: Int,
    val windowStartMs: Long,            // 分析窗口开始时间
    val windowEndMs: Long,              // 分析窗口结束时间
    val totalRecords: Int,              // 窗口内总记录数
    val changes: List<PowerChange>,     // 每次1%变化的详细列表

    // === 充电统计 ===
    val chargeCount: Int,               // 充电变化次数
    val totalChargedMah: Float,         // 累计充入 mAh
    val totalChargeTimeMinutes: Float,  // 累计充电时长(分钟)
    val avgChargeRate: Float,           // 平均充电速率 mAh/h
    val maxChargeRate: Float,           // 最大充电速率 mAh/h
    val maxChargeRateLevel: Int,        // 最大充电速率对应的起始电量

    // === 耗电统计 ===
    val dischargeCount: Int,            // 耗电变化次数
    val totalDischargedMah: Float,      // 累计消耗 mAh
    val totalDischargeTimeMinutes: Float, // 累计耗电时长(分钟)
    val avgDischargeRate: Float,        // 平均耗电速率 mAh/h
    val maxDischargeRate: Float,        // 最大耗电速率 mAh/h
    val maxDischargeRateLevel: Int,     // 最大耗电速率对应的起始电量

    // === 时间段内汇总 ===
    val netChangePercent: Int,          // 净变化百分比 (正=净充电 负=净耗电)
    val netChangeMah: Float,            // 净变化 mAh
    val chargeDischargeRatio: Float     // 充放电能耗比 (充电/耗电 速率比)
)

/** 短时分析时间窗口预设 */
enum class PowerWindow(val label: String, val ms: Long) {
    MIN_15("15分钟", 900_000L),
    MIN_30("30分钟", 1_800_000L),
    HOUR_1("1小时", 3_600_000L),
    HOUR_3("3小时", 10_800_000L),
    HOUR_6("6小时", 21_600_000L),
    HOUR_12("12小时", 43_200_000L),
    DAY_1("1天", 86_400_000L),
    DAY_3("3天", 259_200_000L),
    WEEK_1("1周", 604_800_000L),
    MONTH_1("1月", 2_592_000_000L),
    MONTH_3("3月", 7_776_000_000L),
    YEAR_1("1年", 31_536_000_000L)
}
