package com.batteryanalyzer.stylus

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * 荣耀手写笔「系统态读数」
 *
 * 逆向来源：`代码逆向/README.md` §10、`代码逆向/全逻辑闭环.md` §2.2 / §10。
 *
 * 荣耀系统蓝牙包 `com.hihonor.hnbluetoothpencilmanager` 在完成 GATT 握手后，
 * 会把笔的权威状态写入 `Settings.Global`（五个包均只"读"这些键，写入方在蓝牙/笔包）。
 * 第三方 App **可以读**这些键，因此可把它作为对自研 BLE 解析结果的
 * 「交叉校验 / 兜底」数据源，而无需自行还原私有 GATT 协议。
 *
 * ⚠️ 重要：系统值并非永远可靠。
 * 实测 CD52（第一代 M-Pencil）握手失败时：`battery_level = -1`、`model_number` 是
 * 上一支笔的残留值。因此本项目把系统值定位为**补充校验**，
 * 绝不无条件覆盖 BLE 解析值；仅在 BLE 值无效/缺失时才作为兜底。
 */
data class SystemPenState(
    val batteryLevel: Int = -1,
    val chargingState: Int = -1,
    val connectionState: Int = -1,
    val modelNumber: String? = null,
    val macAddress: String? = null,
    val hardwareVersion: String? = null,
    val softwareVersion: String? = null,
    val systemVersion: String? = null,
    val serialId: String? = null,
    val subModelId: String? = null,
    /** 系统 PID 白名单原文（十进制逗号分隔），供设备识别增强使用 */
    val pencilPidWhitelist: String? = null,
    /** 是否至少读到一个有效键 —— 用于判断本机是否处于荣耀体系 */
    val available: Boolean = false
) {
    /** 系统判定「已接管」(connection_state == 2)；1=连上但未接管，0=未连接 */
    val isConnected: Boolean get() = connectionState == 2

    /** 系统判定「充电中」(charging_state == 1) */
    val isCharging: Boolean get() = chargingState == 1

    /** 系统电量是否有效（-1 表示握手失败/未知） */
    val hasValidBattery: Boolean get() = batteryLevel in 0..100

    /** 系统充电位是否明确（0=未充 / 1=充电） */
    val hasValidCharging: Boolean get() = chargingState == 0 || chargingState == 1

    /** 系统连接位是否明确 */
    val hasValidConnection: Boolean get() = connectionState >= 0

    override fun toString(): String =
        "SystemPenState(battery=$batteryLevel, charging=$chargingState, conn=$connectionState, " +
            "model=$modelNumber, mac=$macAddress, available=$available)"
}

/**
 * 系统态读数读取器。
 *
 * 所有读取都被 try/catch 包裹：非荣耀设备、ROM 限制或键不存在时静默降级为
 * `SystemPenState(available = false)`，调用方无需处理异常。
 */
object HonorSystemPenStateReader {

    private const val TAG = "HonorSysPenState"

    // ---- Settings.Global 键名（逆向实证） ----
    const val KEY_BATTERY_LEVEL = "honor_bt_pencil_battery_level"
    const val KEY_CHARGING_STATE = "honor_bt_pencil_charging_state"
    const val KEY_CONNECTION_STATE = "honor_bt_pencil_connection_state"
    const val KEY_MODEL_NUMBER = "honor_bt_pencil_model_number"
    const val KEY_MAC_ADDRESS = "honor_bt_pencil_mac_address"
    const val KEY_HARDWARE_VERSION = "honor_bt_pencil_hardware_version"
    const val KEY_SOFTWARE_VERSION = "honor_bt_pencil_software_version"
    const val KEY_SYSTEM_VERSION = "honor_bt_pencil_system_version"
    const val KEY_SERIAL_ID = "honor_bt_pencil_serial_id"
    const val KEY_SUB_MODEL_ID = "honor_bt_pencil_submodel_id"
    /** 系统 PID 白名单（十进制逗号分隔） */
    const val KEY_PENCIL_PID_WHITELIST = "HnBlePeripheralCfgpencilpid"

    /**
     * 读取系统态快照。任何异常都降级为「不可用」，绝不抛给调用方。
     */
    fun read(context: Context): SystemPenState {
        return try {
            val cr = context.contentResolver
            val mac = globalString(cr, KEY_MAC_ADDRESS)
            val model = globalString(cr, KEY_MODEL_NUMBER)
            val hw = globalString(cr, KEY_HARDWARE_VERSION)
            val sw = globalString(cr, KEY_SOFTWARE_VERSION)
            val sys = globalString(cr, KEY_SYSTEM_VERSION)
            val serial = globalString(cr, KEY_SERIAL_ID)
            val subModel = globalString(cr, KEY_SUB_MODEL_ID)
            val whitelist = globalString(cr, KEY_PENCIL_PID_WHITELIST)

            val battery = globalInt(cr, KEY_BATTERY_LEVEL, -1)
            val charging = globalInt(cr, KEY_CHARGING_STATE, -1)
            val conn = globalInt(cr, KEY_CONNECTION_STATE, -1)

            val available = battery >= 0 || charging >= 0 || conn >= 0 ||
                !model.isNullOrBlank() || !mac.isNullOrBlank()

            SystemPenState(
                batteryLevel = battery,
                chargingState = charging,
                connectionState = conn,
                modelNumber = model,
                macAddress = mac,
                hardwareVersion = hw,
                softwareVersion = sw,
                systemVersion = sys,
                serialId = serial,
                subModelId = subModel,
                pencilPidWhitelist = whitelist,
                available = available
            ).also {
                if (available) Log.d(TAG, "系统态读数: $it")
            }
        } catch (t: Throwable) {
            // 部分 ROM 对 Settings.Global 私有键有权限限制 —— 静默降级
            Log.d(TAG, "读取系统态失败(非荣耀设备或无权限): ${t.message}")
            SystemPenState()
        }
    }

    /**
     * 判断系统态读数是否属于指定 MAC 的笔。
     *
     * 系统只维护「当前这一支笔」的状态，MAC 不一致说明是另一支笔或残留值，
     * 不能用于交叉校验。系统未提供 MAC 时无法排除，宽松放行。
     */
    fun matchesMac(state: SystemPenState, macAddress: String?): Boolean {
        if (macAddress.isNullOrBlank()) return false
        val sysMac = state.macAddress
        if (sysMac.isNullOrBlank()) return true
        return sysMac.equals(macAddress, ignoreCase = true)
    }

    private fun globalInt(cr: ContentResolver, key: String, def: Int): Int =
        try {
            Settings.Global.getInt(cr, key, def)
        } catch (t: Throwable) {
            def
        }

    private fun globalString(cr: ContentResolver, key: String): String? =
        try {
            Settings.Global.getString(cr, key)
        } catch (t: Throwable) {
            null
        }
}
