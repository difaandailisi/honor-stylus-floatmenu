package com.batteryanalyzer.stylus

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.os.Build
import android.util.Log
import com.batteryanalyzer.ble.BleManager
import com.batteryanalyzer.keymap.KeyMappingManager
import rikka.shizuku.Shizuku

/**
 * 手写笔体系 / 环境检测
 *
 * 【为什么必须先检测体系】——逆向实证（`代码逆向/华为vs荣耀对比.md` §7.2 / §7.4 / §10.4）：
 *
 * |        | 判定放在哪         | 写 Settings 能否骗出悬浮菜单 |
 * |--------|-------------------|---------------------------|
 * | 荣耀   | 可写的 Settings 里 | ✅ 能（L2 悬浮球真的会出现）  |
 * | 华为   | 系统流程里         | ❌ 不能（只能骗到 L1 内部状态）|
 *
 * 华为侧实证：`am startservice FloatMenuService` → `Error: Requires permission android.permission.REBOOT`。
 * 文档原话：**「荣耀把判定放在了可写的设置里，所以能骗；华为把判定放在了系统流程里，天然骗不动。」**
 *
 * ⇒ 所以本功能**只在荣耀体系可用**；华为体系是系统原生接管（能连能写），必须禁用并提示，
 *   绝不能去写它的键（写了也无效，还可能污染系统状态）。
 */
enum class StylusVendorSystem { HONOR, HUAWEI, UNKNOWN }

data class StylusEnv(
    val system: StylusVendorSystem = StylusVendorSystem.UNKNOWN,
    /** 该体系是否支持「写 Settings 伪造悬浮菜单」—— 仅荣耀为 true */
    val canSimulate: Boolean = false,
    /** honor_bt_pencil_connection_state：2=已接管 / 1=连上未接管 / 0=未连接 / -1=未知 */
    val connectionState: Int = -1,
    /** honor_bt_pencil_charging_state：1=磁吸充电 / 0=取下 / -1=未知 */
    val chargingState: Int = -1,
    val modelNumber: String? = null,
    val shizukuAvailable: Boolean = false,
    val shizukuGranted: Boolean = false,
    /**
     * App 自身是否建立了 BLE 连接。
     * ★ 这是"真笔跟随"的**唯一可信信号源**：它来自蓝牙栈，
     *   与我们通过 Shizuku 写进去的任何 Settings 键值完全无关，
     *   因此不会被"假笔状态"（我们伪造的 connection_state=2）污染。
     */
    val bleConnected: Boolean = false,
    /** BLE 解析到的设备名 */
    val penName: String? = null,
    /** 分类后的笔型号标签：M-Pencil Pro / M-Pencil 2/3 (CD54/CD54S) / Magic-Pencil / … */
    val penModelLabel: String = "未检测到",
    /** 是否为受支持的目标手写笔 */
    val isTargetPen: Boolean = false,
    /** key_bt_pencil_state：2=取下唤醒(球显示) / 0=断开(球收起) / 4=磁吸充电(球隐藏) / -1=未知 */
    val penState: Int = -1,
    /** key_pencil_attach_state：0=取下(未吸附) / 1=吸附 / -1=未知 */
    val attachState: Int = -1,
    /**
     * 悬浮菜单（球）当前是否处于「显示中」。
     * 由系统态推导：荣耀体系下，conn≠0 且 pstate=2（取下唤醒）且 attach=0（未吸附）时球出现。
     * 这是 UI 顶部「悬浮菜单是否开启」状态检测的核心依据。
     */
    val menuVisible: Boolean = false
) {
    /** 是否有真笔连上（含"连上但未被接管"） */
    val hasRealPen: Boolean get() = connectionState >= 1

    /** 是否已被系统笔包接管（此时 120Hz 等由系统负责） */
    val isTakenOver: Boolean get() = connectionState == 2

    val connectionText: String
        get() = when (connectionState) {
            2 -> "已接管"
            1 -> "已连接·未接管"
            0 -> "未连接"
            else -> "未知"
        }

    val chargingText: String
        get() = when (chargingState) {
            1 -> "磁吸充电中"
            0 -> "已取下"
            else -> "未知"
        }
}

object StylusEnvDetector {

    private const val TAG = "StylusEnvDetector"

    /** 荣耀悬浮菜单包 */
    private const val PKG_HONOR_FLOATMENU = "com.hihonor.stylus.floatmenu"
    /** 华为悬浮菜单包（系统原生接管，不可模拟） */
    private const val PKG_HUAWEI_FLOATMENU = "com.huawei.stylus.floatmenu"

    fun detect(context: Context): StylusEnv {
        val honor = isInstalled(context, PKG_HONOR_FLOATMENU)
        val huawei = isInstalled(context, PKG_HUAWEI_FLOATMENU)

        val system = when {
            honor -> StylusVendorSystem.HONOR
            huawei -> StylusVendorSystem.HUAWEI
            else -> StylusVendorSystem.UNKNOWN
        }

        // 系统态读数（荣耀体系才有意义；华为体系这些键为 huawei_* 前缀，读不到会降级为 -1）
        val state = HonorSystemPenStateReader.read(context)

        val shizukuAvailable = try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            Log.d(TAG, "Shizuku 不可用: ${t.message}")
            false
        }
        val shizukuGranted = try {
            KeyMappingManager.hasShizukuPermission()
        } catch (t: Throwable) {
            Log.d(TAG, "Shizuku 权限查询失败: ${t.message}")
            false
        }

        // ★ BLE 侧信号（真笔跟随的可信来源，不受我们自己写过的 Settings 影响）
        val bleMac = try {
            BleManager.getInstance(context).connectedDeviceMac.value
        } catch (t: Throwable) {
            Log.d(TAG, "读取 BLE 连接状态失败: ${t.message}")
            null
        }
        val bleName = try {
            BleManager.getInstance(context).resolvedDeviceName.value
        } catch (t: Throwable) {
            null
        }
        val bleConnected = !bleMac.isNullOrBlank()
        val (label, isTarget) = classifyPen(bleName, state.modelNumber)

        // ★ 悬浮菜单「显示中」检测：读系统态 pstate/attach（与模拟写入的键同源）。
        //   荣耀体系下 pstate=2(取下唤醒) ∧ attach=0(未吸附) → 球在显示。
        //   注意：不要求 conn≠0——C 模式按用户实测「开闸顶出菜单后再关回 conn=0」，
        //   菜单在 conn=0 时依然保持显示，故不能靠 conn 判定菜单可见性。
        val pstate = readGlobalIntSafe(context, "key_bt_pencil_state", -1)
        val attach = readGlobalIntSafe(context, "key_pencil_attach_state", -1)
        val menuVisible = system == StylusVendorSystem.HONOR
            && pstate == 2
            && attach == 0

        return StylusEnv(
            system = system,
            canSimulate = system == StylusVendorSystem.HONOR,
            connectionState = state.connectionState,
            chargingState = state.chargingState,
            modelNumber = state.modelNumber,
            shizukuAvailable = shizukuAvailable,
            shizukuGranted = shizukuGranted,
            bleConnected = bleConnected,
            penName = bleName,
            penModelLabel = label,
            isTargetPen = isTarget,
            penState = pstate,
            attachState = attach,
            menuVisible = menuVisible
        )
    }

    /**
     * 按 BLE 设备名 + 系统型号分类笔型号。
     * 优先用 BLE 设备名（真笔跟随的可信来源），系统型号兜底。
     */
    private fun classifyPen(bleName: String?, sysModel: String?): Pair<String, Boolean> {
        val combined = "${bleName ?: ""} ${sysModel ?: ""}"
        val name = bleName?.takeIf { it.isNotBlank() }
        return when {
            combined.contains("M-Pencil Pro", true) ->
                "华为 M-Pencil Pro" to true
            combined.contains("M-Pencil", true) ->
                "华为 M-Pencil 2/3 (CD54/CD54S)" to true
            combined.contains("Magic-Pencil", true) || combined.contains("Magic Pencil", true) ->
                "荣耀 Magic-Pencil" to true
            combined.contains("Pencil", true) || combined.contains("Stylus", true) ||
                combined.contains("手写笔", false) || combined.contains("触控笔", false) ->
                "未知手写笔（${name ?: "无名"}）" to true
            // 括号不可省：否则 `if ... else (X to false)` 会被解析成
            // then 分支返回 String、else 分支返回 Pair，公共类型退化为 Serializable
            else -> (if (name == null) "未检测到" else "非手写笔设备（$name）") to false
        }
    }

    private fun isInstalled(context: Context, pkg: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(pkg, 0)
        }
        true
    } catch (_: Throwable) {
        false
    }

    /** 安全读取 Settings.Global 整型键；任意异常（权限/键不存在）降级为 def */
    private fun readGlobalIntSafe(context: Context, key: String, def: Int): Int = try {
        Settings.Global.getInt(context.contentResolver, key, def)
    } catch (_: Throwable) {
        def
    }
}
