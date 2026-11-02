package com.batteryanalyzer.ui.theme

import androidx.compose.ui.graphics.Color

// 主色调 - 科技蓝
val PrimaryBlue = Color(0xFF2196F3)
val PrimaryBlueDark = Color(0xFF1565C0)
val PrimaryBlueLight = Color(0xFF64B5F6)

// 充电颜色
val ChargingGreen = Color(0xFF4CAF50)
val ChargingGreenLight = Color(0xFF81C784)

// 放电颜色
val DischargingOrange = Color(0xFFFF9800)
val DischargingOrangeLight = Color(0xFFFFB74D)

// 低电量颜色
val LowBatteryRed = Color(0xFFF44336)
val CriticalBatteryRed = Color(0xFFD32F2F)

// 背景色
val BackgroundLight = Color(0xFFF5F7FA)
val SurfaceLight = Color(0xFFFFFFFF)
val CardLight = Color(0xFFFFFFFF)

// 暗色主题
val BackgroundDark = Color(0xFF121212)
val SurfaceDark = Color(0xFF1E1E1E)
val CardDark = Color(0xFF2C2C2C)

// 文字颜色
val TextPrimary = Color(0xFF212121)
val TextSecondary = Color(0xFF757575)
val TextOnDark = Color(0xFFE0E0E0)

// 暗色文字
val TextPrimaryDark = Color(0xFFE0E0E0)
val TextSecondaryDark = Color(0xFF9E9E9E)

/**
 * 根据深色模式返回对应的主题色配色
 */
data class AppThemeColors(
    val background: Color,
    val surface: Color,
    val card: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val isDark: Boolean
)

fun lightThemeColors() = AppThemeColors(
    background = BackgroundLight,
    surface = SurfaceLight,
    card = CardLight,
    textPrimary = TextPrimary,
    textSecondary = TextSecondary,
    isDark = false
)

fun darkThemeColors() = AppThemeColors(
    background = BackgroundDark,
    surface = SurfaceDark,
    card = CardDark,
    textPrimary = TextOnDark,
    textSecondary = TextSecondaryDark,
    isDark = true
)

// 电量颜色映射
fun getBatteryColor(level: Int, isCharging: Boolean = false): Color {
    return when {
        isCharging -> ChargingGreen
        level <= 10 -> CriticalBatteryRed
        level <= 20 -> LowBatteryRed
        level <= 50 -> DischargingOrange
        else -> PrimaryBlue
    }
}

// 健康度颜色
fun getHealthColor(health: Float): Color {
    return when {
        health >= 90 -> ChargingGreen
        health >= 70 -> DischargingOrange
        else -> LowBatteryRed
    }
}

fun getHealthText(health: Float): String {
    return when {
        health >= 90 -> "优秀"
        health >= 80 -> "良好"
        health >= 70 -> "一般"
        health >= 60 -> "较差"
        else -> "需要更换"
    }
}
