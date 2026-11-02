package com.batteryanalyzer.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.batteryanalyzer.R

/**
 * 设备图标提供器
 *
 * 根据设备名称关键词自动匹配对应设备类型图标，用于通知右侧大图标 (setLargeIcon)。
 * 映射规则：
 * - 手写笔/触控笔  → ic_device_stylus  (铅笔图标)
 * - 键盘/输入设备   → ic_device_keyboard (键盘图标)
 * - 耳机/音箱       → ic_device_headphone (耳机图标)
 * - 其他/未知       → ic_device_phone    (手机图标 - 默认)
 *
 * 使用方式：调用 [getDeviceIcon] 或 [getDeviceIconBitmap] 即可获取对应图标。
 * 如需自定义尺寸，从返回的 Drawable 自行缩放。
 */
object DeviceIconProvider {

    // ===== 设备名称关键词 =====

    /** 手写笔关键词 */
    val STYLUS_KEYWORDS = listOf(
        "Magic-Pencil", "Magic Pencil", "M-Pencil", "M-Pen",
        "S Pen", "S-Pen", "S Pen Pro", "S Pen Fold",
        "Apple Pencil", "Surface Pen", "Surface Slim Pen",
        "Xiaomi Pen", "Xiaomi Smart Pen", "Redmi Pen", "Redmi Smart Pen",
        "OPPO Pen", "OPPO Pencil", "vivo Pen", "vivo Pencil",
        "Lenovo Pen", "Lenovo Precision Pen", "Tab Pen", "Tab Pen Plus",
        "Active Pen", "Wacom", "Bamboo Ink", "Bamboo Sketch",
        "Smart Pen", "Stylus Pen", "Stylus", "Digital Pen", "USI Pen",
        "手写笔", "触控笔", "触屏笔", "画笔", "电容笔", "点阵笔", "电子笔"
    )

    /** 键盘/输入设备关键词 */
    val KEYBOARD_KEYWORDS = listOf(
        "Keyboard", "Keychron", "MX Keys", "Magic Keyboard",
        "Smart Keyboard", "Type Cover", "Trackpad", "Touchpad",
        "Mouse", "Trackball", "TrackPoint",
        "键盘", "鼠标", "触摸板", "触控板"
    )

    /** 耳机/音箱关键词 */
    val HEADPHONE_KEYWORDS = listOf(
        "AirPods", "AirPod", "Powerbeats", "Beats",
        "Galaxy Buds", "GalaxyBuds", "Buds", "Galaxy Bud",
        "Sony", "WF-", "WH-", "WI-",
        "Bose", "QC", "QuietComfort",
        "Jabra", "Elite", "Sennheiser", "Momentum",
        "JBL", "Soundcore", "Anker",
        "Xiaomi Buds", "Redmi Buds", "RedmiBuds",
        "FreeBuds", "FreeLace",
        "OPPO Enco", "Enco", "OnePlus Buds",
        "vivo TWS", "iQOO TWS",
        "Marshall", "Major", "Minor",
        "Nothing Ear", "Ear (",
        "Skullcandy", "Audio-Technica",
        "Headset", "Headphone", "Earphone", "Earbud",
        "Speaker", "SoundBar",
        "耳机", "音箱", "音响"
    )

    // ===== 公开 API =====

    /**
     * 根据设备名称返回对应的图标 Drawable 资源 ID
     */
    fun getIconResId(deviceName: String): Int {
        val name = deviceName.trim()
        if (name.isEmpty()) return R.drawable.ic_device_phone

        return when {
            STYLUS_KEYWORDS.any { name.contains(it, ignoreCase = true) } ->
                R.drawable.ic_device_stylus
            HEADPHONE_KEYWORDS.any { name.contains(it, ignoreCase = true) } ->
                R.drawable.ic_device_headphone
            KEYBOARD_KEYWORDS.any { name.contains(it, ignoreCase = true) } ->
                R.drawable.ic_device_keyboard
            else ->
                R.drawable.ic_device_phone  // 默认手机图标
        }
    }

    /**
     * 根据设备名称返回对应的图标 Drawable
     */
    fun getDeviceIcon(context: Context, deviceName: String): Drawable? {
        return ContextCompat.getDrawable(context, getIconResId(deviceName))
    }

    /**
     * 根据设备名称返回对应图标并转换为 Bitmap（用于 setLargeIcon）
     */
    fun getDeviceIconBitmap(context: Context, deviceName: String): Bitmap? {
        val drawable = getDeviceIcon(context, deviceName) ?: return null
        return drawableToBitmap(drawable)
    }

    /**
     * 获取默认设备图标 Bitmap（用于设备名称未知时）
     */
    fun getDefaultIconBitmap(context: Context): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_device_phone) ?: return null
        return drawableToBitmap(drawable)
    }

    // ===== 内部工具 =====

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        // 使用 drawable 的固有尺寸，若不确定则用 96x96
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
