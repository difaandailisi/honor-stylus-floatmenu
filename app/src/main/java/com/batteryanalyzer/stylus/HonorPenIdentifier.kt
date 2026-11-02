package com.batteryanalyzer.stylus

import android.content.Context
import android.hardware.input.InputManager
import android.util.Log
import android.view.InputDevice

/**
 * 荣耀/华为手写笔「硬件身份识别」
 *
 * 逆向来源：`代码逆向/README.md` §8、`代码逆向/全逻辑闭环.md` §2.1 / §3 卡点1 / §5.1。
 *
 * 系统认定一支笔的两层过滤：
 *  1. VID 必须 ∈ {0x12d1, 0x09c6}（`isHnPencilDevice`）
 *  2. PID 必须在白名单 `Settings.Global.HnBlePeripheralCfgpencilpid`（十进制逗号分隔）
 *
 * 已知换算（逆向实证）：
 *  - CD52（第一代 M-Pencil） = 0x1091 = 4241
 *  - ALITA                   = 0x10a5 = 4261
 *  - Magic-Pencil 3          = 0x2502 = 9474（9473~9478 为 MP3 系列 PID 段）
 *
 * 本模块把这套判定复刻到第三方 App：
 * 通过 `InputDevice` 枚举拿到蓝牙 HID 设备的 vendorId / productId
 * （BT HID 设备的 `descriptor` 就是它的蓝牙 MAC，可据此与已连接设备对齐），
 * 从而替代纯"设备名关键词"的启发式识别，显著降低漏判/误判。
 */
data class HonorPenIdentity(
    val vendorId: Int,
    val productId: Int,
    /** 解析出的型号名（未知型号也会给出带 PID 的描述） */
    val modelName: String,
    /** 是否为逆向已确认的具体型号 */
    val isKnownModel: Boolean,
    /** PID 是否命中系统白名单 HnBlePeripheralCfgpencilpid */
    val inSystemWhitelist: Boolean
) {
    /** 是否符合荣耀笔的两层判定（VID 合法 + PID 在白名单或已知型号） */
    val isHonorPen: Boolean get() = inSystemWhitelist || isKnownModel

    override fun toString(): String =
        "HonorPenIdentity(vid=0x${vendorId.toString(16)}, pid=0x${productId.toString(16)}" +
            " (${productId}), model=$modelName, known=$isKnownModel, inWhitelist=$inSystemWhitelist)"
}

object HonorPenIdentifier {

    private const val TAG = "HonorPenIdentifier"

    /** 荣耀/华为笔 VID 白名单 */
    private val HONOR_VENDOR_IDS = setOf(0x12d1, 0x09c6)

    /** 逆向已确认的型号映射 */
    private val KNOWN_MODELS = mapOf(
        0x1091 to "HUAWEI M-Pencil (CD52 第一代)",
        0x10a5 to "HUAWEI ALITA",
        0x2502 to "HONOR Magic-Pencil 3",
        0x2501 to "HONOR Magic-Pencil 3",
        0x2503 to "HONOR Magic-Pencil 3",
        0x2504 to "HONOR Magic-Pencil 3",
        0x2505 to "HONOR Magic-Pencil 3",
        0x2506 to "HONOR Magic-Pencil 3"
    )

    /** Magic-Pencil 3 系列 PID 段（十进制 9473~9478） */
    private val MP3_PID_RANGE = 9473..9478

    /**
     * 按 VID/PID 判定身份。
     * @param whitelist 系统白名单原文（十进制逗号分隔）；为 null 时以已知型号为准
     */
    fun identify(vendorId: Int, productId: Int, whitelist: String? = null): HonorPenIdentity? {
        if (vendorId !in HONOR_VENDOR_IDS || productId == 0) return null

        val knownName = KNOWN_MODELS[productId]
        val isKnown = knownName != null
        val inWhitelist = productId in parseWhitelist(whitelist)

        if (!isKnown && !inWhitelist) return null

        val modelName = knownName
            ?: if (productId in MP3_PID_RANGE) "HONOR Magic-Pencil 3 系列"
            else "荣耀手写笔 (未知型号 PID=0x${productId.toString(16)})"

        return HonorPenIdentity(
            vendorId = vendorId,
            productId = productId,
            modelName = modelName,
            isKnownModel = isKnown,
            inSystemWhitelist = inWhitelist
        )
    }

    /**
     * 按蓝牙 MAC 解析硬件身份。
     *
     * 实现：枚举 `InputDevice`，BT HID 设备的 `descriptor` 即其蓝牙 MAC；
     * 命中后取其 `identifier.vendorId / productId` 再做 VID/PID 判定。
     */
    fun resolve(context: Context, macAddress: String): HonorPenIdentity? {
        if (macAddress.isBlank()) return null
        return try {
            val inputManager =
                context.getSystemService(Context.INPUT_SERVICE) as? InputManager ?: return null
            val target = macAddress.trim()
            val whitelist = HonorSystemPenStateReader.read(context).pencilPidWhitelist

            for (id in inputManager.inputDeviceIds) {
                val device = InputDevice.getDevice(id) ?: continue
                val descriptor = device.descriptor ?: continue
                if (!descriptor.equals(target, ignoreCase = true)) continue

                // ★ InputDevice.getIdentifier() 是隐藏 API（已实测 android.jar 中不存在），
                //   公开可用的是 InputDevice.getVendorId() / getProductId()
                val vid = device.vendorId
                val pid = device.productId
                if (vid == 0 || pid == 0) continue

                val identity = identify(vid, pid, whitelist)
                if (identity != null) {
                    Log.d(TAG, "识别到荣耀手写笔: MAC=$target → $identity")
                    return identity
                }
            }
            null
        } catch (t: Throwable) {
            Log.d(TAG, "按 MAC 识别硬件身份失败: ${t.message}")
            null
        }
    }

    private fun parseWhitelist(raw: String?): Set<Int> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
    }
}
