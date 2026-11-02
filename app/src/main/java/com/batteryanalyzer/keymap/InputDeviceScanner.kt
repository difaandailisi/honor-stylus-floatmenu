package com.batteryanalyzer.keymap

import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.util.Log
import android.view.InputDevice

/**
 * 非蓝牙输入设备扫描器 v5.2
 *
 * 通过 Android [InputManager] API 枚举系统**已连接**的键盘、鼠标、手写笔、
 * 2.4G 无线接收器、磁吸键盘等实体输入设备。
 *
 * 【核心原理】
 *   Android 系统通过 InputReader/EventHub 持续监听所有已连接输入设备。
 *   [InputManager.getInputDeviceIds()] 返回的就是**当前已连接**设备的 ID 列表，
 *   不论连接方式是 USB、蓝牙 HID、2.4G Dongle 还是磁吸 Pogo Pin。
 *   — 不需要扫描配对 — 系统已经做好了。
 *
 * 【v5.2 过滤策略（四层）】
 *   Layer 1 — isVirtual：跳过纯软件虚拟设备
 *   Layer 2 — 触摸屏误杀修正：带键盘/鼠标的触控设备（如磁吸键盘触控板）保留，纯触摸屏跳过
 *   Layer 3 — isExternal 主过滤：true=外接实体 → 保留；false → 进入 Layer 4
 *   Layer 4 — 内部设备黑名单：匹配已知主板内置设备名（gpio-keys/pmic/Button Jack/…）→ 跳过
 *            未命中黑名单的非 external 设备 → 宽容保留（兼容部分磁吸键盘 isExternal=false 的 bug）
 *
 * 这些设备不通过 BLE 管理，无法获取 MAC 地址，
 * 但可以进行键值映射（拦截按键 + 注入替换）。
 */
object InputDeviceScanner {

    private const val TAG = "InputDeviceScanner"

    /**
     * 已知的 Android 主板内部输入设备名称模式。
     * 这些设备通过内核驱动注册，isExternal=false，不应出现在键值映射列表中。
     */
    private val INTERNAL_DEVICE_PATTERNS = setOf(
        "gpio-keys",          // GPIO 按键驱动（电源键/音量键）
        "pmic_",              // 电源管理 IC 按键（pmic_pwrkey, pmic_resin 等）
        "Button Jack",        // 音频插孔检测按钮
        "qpnp_pon",           // QPNP 电源键
        "qcom-hv-hv",         // Qualcomm HV 检测
        "msm-cdc-snd",        // 音频通过 GPIO
        "sfp",                // SFP 接口检测
        "hall",               // 霍尔传感器
    )

    /**
     * 已知的外部实体键盘/输入设备名称关键词（正向匹配）。
     * 包含这些关键词的设备，即使 isExternal=false 也保留（兼容部分厂商磁吸键盘的 bug）。
     */
    private val EXTERNAL_KEYWORDS = setOf(
        "keyboard",           // 通用键盘
        "smart touch",        // HONOR Smart Touch Keyboard
        "smarttouch",         // 变体写法
        "magnetic",           // 磁吸键盘
        "dock",               // 底座键盘
        "logitech",           // 罗技外设
        "k380",              // 罗技 K380
        "mx keys",            // 罗技 MX Keys
        "magic keyboard",     // Apple Magic Keyboard
        "wireless",           // 无线键盘
        "bluetooth keyboard", // 蓝牙键盘
    )

    /** 需要在 isExternal 之前检查的真实输入源 */
    private val REAL_INPUT_SOURCES = InputDevice.SOURCE_KEYBOARD or
        InputDevice.SOURCE_MOUSE or
        InputDevice.SOURCE_STYLUS or
        InputDevice.SOURCE_GAMEPAD or
        InputDevice.SOURCE_JOYSTICK or
        InputDevice.SOURCE_TRACKBALL or
        InputDevice.SOURCE_DPAD or
        InputDevice.SOURCE_ROTARY_ENCODER

    /** 触摸类源（单独存在时跳过，与真实输入源共存时保留） */
    private val TOUCH_SOURCES = InputDevice.SOURCE_TOUCHSCREEN or
        InputDevice.SOURCE_TOUCHPAD or
        InputDevice.SOURCE_TOUCH_NAVIGATION or
        InputDevice.SOURCE_MOUSE

    /**
     * 输入设备快照（不含 MAC 地址，仅名称和产品信息）
     */
    data class InputDeviceSnapshot(
        val name: String,
        val virtualMac: String,
        val sources: String,
        val productName: String = "",
        val vendorId: Int = 0,
        val productId: Int = 0,
        val isExternal: Boolean = false,
        val deviceId: Int = -1
    )

    /**
     * 扫描系统已连接的所有**外部实体**输入设备
     *
     * 过滤策略：
     *   L1: 跳过虚拟设备 (isVirtual)
     *   L2: 跳过纯触摸设备（无键盘/鼠标源）
     *   L3: isExternal=true → 直接保留（USB/蓝牙HID/2.4G等）
     *   L4a: isExternal=false + 命中内部黑名单 → 跳过
     *   L4b: isExternal=false + 命中外部关键词 → 保留（磁吸键盘等）
     *   L4c: isExternal=false + 未命中任何规则 → 跳过（默认拒绝非外接）
     *
     * 返回设备快照列表，每个设备都是当前已连接的实体外设。
     */
    fun scanInputDevices(context: Context): List<InputDeviceSnapshot> {
        val snapshots = mutableListOf<InputDeviceSnapshot>()
        val seenVirtualMacs = mutableSetOf<String>()

        val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
            ?: return emptyList()

        val deviceIds = inputManager.inputDeviceIds ?: return emptyList()

        Log.i(TAG, "v5.2 开始扫描，系统报告 ${deviceIds.size} 个输入设备")

        for (id in deviceIds) {
            val device = InputDevice.getDevice(id) ?: continue
            val deviceName = device.name ?: "Unknown Input Device"

            // === Layer 1: 跳过纯虚拟设备 ===
            if (device.isVirtual) continue

            // 全量日志：记录所有非虚拟设备及其属性（调试用）
            Log.d(TAG, "  [原始] id=$id name=\"$deviceName\" external=${device.isExternal} sources=0x${device.sources.toString(16)} vendor=0x${device.vendorId.toString(16)} product=0x${device.productId.toString(16)}")

            // === Layer 2: 触摸/触控板过滤 ===
            val hasRealInput = (device.sources and REAL_INPUT_SOURCES) != 0
            val hasTouchOnly = (device.sources and TOUCH_SOURCES) != 0 && !hasRealInput

            if (hasTouchOnly) continue

            // 没有真实输入源的设备 → 跳过
            if (!hasRealInput) continue

            val sourceDesc = classifyDeviceSources(device.sources)
            if (sourceDesc.isEmpty()) continue

            // === Layer 3: isExternal=true → 外部实体，直接保留 ===
            if (device.isExternal) {
                addDevice(snapshots, seenVirtualMacs, deviceName, sourceDesc, device, id)
                continue
            }

            // === Layer 4a: 内部设备黑名单（isExternal=false）===
            val isInternal = INTERNAL_DEVICE_PATTERNS.any {
                deviceName.contains(it, ignoreCase = true)
            }
            if (isInternal) {
                Log.d(TAG, "  ❌ 跳过内部: $deviceName")
                continue
            }

            // === Layer 4b: 正向关键词匹配（磁吸键盘/HONOR等已知外设）===
            val hasExternalKeyword = EXTERNAL_KEYWORDS.any {
                deviceName.contains(it, ignoreCase = true)
            }
            if (hasExternalKeyword) {
                Log.d(TAG, "  ✅ 关键词匹配保留: $deviceName")
                addDevice(snapshots, seenVirtualMacs, deviceName, sourceDesc, device, id)
                continue
            }

            // === Layer 4c: 默认拒绝（isExternal=false 且无任何匹配）===
            Log.d(TAG, "  ⏭️ 跳过未知内部: $deviceName (isExternal=false, 无内外部匹配)")
        }

        Log.i(TAG, "v5.2 扫描完成: 系统${deviceIds.size}个 → 保留${snapshots.size}个外部实体")
        return snapshots
    }

    private fun addDevice(
        list: MutableList<InputDeviceSnapshot>,
        seen: MutableSet<String>,
        name: String,
        sources: String,
        device: InputDevice,
        id: Int
    ) {
        val virtualMac = "input_$name"
        if (!seen.add(virtualMac)) return

        list.add(
            InputDeviceSnapshot(
                name = name,
                virtualMac = virtualMac,
                sources = sources,
                productName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) device.name ?: "" else "",
                vendorId = device.vendorId,
                productId = device.productId,
                isExternal = device.isExternal,
                deviceId = id
            )
        )
    }

    /**
     * 根据设备 sources 位掩码分类为人类可读的类型
     */
    private fun classifyDeviceSources(sources: Int): String {
        val types = mutableListOf<String>()

        if (sources and InputDevice.SOURCE_KEYBOARD != 0) types.add("键盘")
        if (sources and InputDevice.SOURCE_MOUSE != 0) types.add("鼠标")
        if (sources and InputDevice.SOURCE_STYLUS != 0) types.add("手写笔")
        if (sources and InputDevice.SOURCE_GAMEPAD != 0) types.add("手柄")
        if (sources and InputDevice.SOURCE_JOYSTICK != 0) types.add("摇杆")
        if (sources and InputDevice.SOURCE_TRACKBALL != 0) types.add("轨迹球")
        if (sources and InputDevice.SOURCE_DPAD != 0) types.add("方向键")
        if (sources and InputDevice.SOURCE_ROTARY_ENCODER != 0) types.add("旋钮")

        return types.joinToString("+")
    }
}
