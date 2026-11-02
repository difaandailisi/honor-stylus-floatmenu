package com.batteryanalyzer.stylus

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import rikka.shizuku.Shizuku
import java.util.Locale

/**
 * 手写笔模拟引擎（荣耀体系专用）
 *
 * 复刻 `代码逆向/复刻真笔机制.sh` 与 `复刻真笔机制-完整版.sh` 的核心机制：
 *
 * 1. **单向写入**：读 `honor_bt_pencil_charging_state`（系统写、**不参与判定**）
 *    → 写 `key_pencil_attach_state`（**参与判定**）。绝不可「读 A 写 A」，否则自激震荡。
 * 2. **跳变触发**：ContentObserver 只在值变化时回调；球被 45 秒空闲超时收掉后，
 *    写同一个值没反应 —— 必须先写反值再写目标值。
 * 3. **看门狗常驻**：球有 45 秒空闲超时（floatmenu 内部定时器，改不掉），
 *    轮询间隔必须 < 30 秒。看门狗同时用来对抗「真笔接入后系统覆盖我们的值导致球掉」。
 *
 * ⚠️ 仅荣耀体系可用。华为体系由系统原生接管（`FloatMenuService` 需
 *    `android.permission.REBOOT`），写 Settings 只能骗到 L1 内部状态，骗不出悬浮窗。
 *
 * ⚠️ 写入需要 adb 级权限：本引擎优先用已授权的 `WRITE_SECURE_SETTINGS` 直写，
 *    否则经 Shizuku 反射 `newProcess` 执行 `settings put`。
 */
object StylusSimulator {

    /**
     * OFF        关闭
     * SIMULATE   A · 假装有笔：凭空造一支笔（会写 connection_state=2）
     * FOLLOW     B · 真笔跟随：跟随真实磁吸状态（不伪造有笔）
     * FORCE_MENU C · 强制悬浮菜单：只驱动菜单状态机，不碰任何笔状态
     */
    enum class Mode { OFF, SIMULATE, FOLLOW, FORCE_MENU }

    // ---- Settings.Global 键（逆向实证，`代码逆向/README.md` §10）----
    private const val K_CONN = "honor_bt_pencil_connection_state"
    private const val K_PSTATE = "key_bt_pencil_state"
    private const val K_ATTACH = "key_pencil_attach_state"
    private const val K_ATYPE = "key_pencil_attach_type"

    private const val TAG = "StylusSimulator"

    /** 默认轮询间隔。球有 45 秒空闲超时，间隔必须 < 30 秒。 */
    const val DEFAULT_INTERVAL_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _mode = MutableStateFlow(Mode.OFF)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _status = MutableStateFlow("未启动")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _env = MutableStateFlow(StylusEnv())
    val env: StateFlow<StylusEnv> = _env.asStateFlow()

    /** 最近一次成功执行写入的时间戳（供 UI 显示"看门狗还活着"） */
    private val _lastWriteTime = MutableStateFlow(0L)
    val lastWriteTime: StateFlow<Long> = _lastWriteTime.asStateFlow()

    private var watchdog: Job? = null

    /**
     * 串行化所有看门狗切换（start / stop / 彻底恢复）。
     * 确保「取消旧 tick」与「启动新 tick / 清理」之间不存在并发写 Settings 的竞态——
     * 这是 A↔C 来回切导致假笔残留、停止无法还原的核心根因。
     */
    private val transitionLock = Mutex()

    /**
     * A 模式把 conn 伪造成 2 **之前**记录的原始值。
     * 用于停止 / 切换 B 模式时还原，避免"假笔状态"残留并污染真笔判定。
     */
    private var forcedConnOriginal: Int? = null

    /** 「彻底恢复原始状态」要清除的笔相关 Settings 键 */
    private val PEN_SETTINGS_KEYS = listOf(
        K_CONN, K_PSTATE, K_ATTACH, K_ATYPE,
        "honor_bt_pencil_charging_state",
        "honor_bt_pencil_battery_level",
        "honor_bt_pencil_model_number",
        "honor_bt_pencil_mac_address",
        "honor_stylus_wake_state",
        "key_pencil_sub_modelid"
    )

    /** 刷新一次环境快照（供 UI 打开页面时立即展示） */
    fun refreshEnv(context: Context) {
        _env.value = StylusEnvDetector.detect(context.applicationContext)
    }

    fun start(context: Context, mode: Mode, intervalMs: Long = DEFAULT_INTERVAL_MS) {
        if (mode == Mode.OFF) {
            stop(context)
            return
        }
        val appCtx = context.applicationContext
        val interval = intervalMs.coerceIn(5_000L, 30_000L)
        if (_running.value && _mode.value == mode) return

        scope.launch {
            transitionLock.withLock {
                // 持锁后二次确认，避免多重点击重复进入同一模式
                if (_running.value && _mode.value == mode) return@withLock
                val switching = _running.value && _mode.value != mode
                // ★ 先取消并等旧 tick 真正结束（含其最后一个 conn=2 写），再启动新模式——
                //   否则 A↔C 来回切时新旧 simulateRealPen 并发写 conn=2，清理会漏、假笔残留。
                val old = watchdog
                watchdog = null
                old?.cancel()
                old?.join()
                _mode.value = mode
                _running.value = true
                watchdog = scope.launch {
                    // ★ 切换模式前先清理上一模式痕迹。
                    //   切到 C（FORCE_MENU）时【绝不还原 conn】：C 需要 conn≠0 才能顶出菜单，
                    //   一旦把 A 伪造的 conn=2 还原成 0，C 会因 conn==0 直接失效 →
                    //   表现即「点 C 把状态和菜单一起清掉」（用户反馈问题 2）。
                    //   故切到 C 只先收起瞬时菜单（conn 原样保留），由 forceMenu 重新顶出。
                    //   切到 A / B 时再做完整清理（还原伪造的 conn + 隐藏）。
                    if (switching) {
                        if (mode == Mode.FORCE_MENU) hideMenu(appCtx)
                        else performCleanup(appCtx)
                    }
                    while (isActive) {
                        try {
                            tick(appCtx, mode)
                        } catch (t: Throwable) {
                            Log.w(TAG, "看门狗异常(已忽略): ${t.message}")
                        }
                        delay(interval)
                    }
                }
            }
        }
    }

    /**
     * 停止：**主动隐藏悬浮菜单**，并还原 A 模式伪造过的 conn。
     *
     * 写入会阻塞等待 Shizuku 进程，故必须在 IO 线程执行（本函数在 scope 中 launch）。
     */
    fun stop(context: Context) {
        val appCtx = context.applicationContext
        _status.value = "停止中…"
        scope.launch {
            transitionLock.withLock {
                // ★ 先取消并等旧 tick 真正结束，再清理——
                //   保证「清理写入(conn=0)」一定落在旧写入(conn=2)之后。
                val old = watchdog
                watchdog = null
                _running.value = false
                _mode.value = Mode.OFF
                old?.cancel()
                old?.join()
            }
            try {
                // ★ 三个模式统一：停止 = 清除伪造状态 + 强制隐藏悬浮菜单
                val ok = performCleanup(appCtx)
                _status.value = if (ok) {
                    "已停止：已清除模拟状态并隐藏悬浮菜单"
                } else {
                    "已停止，但部分键值写入未生效（请检查 Shizuku / WRITE_SECURE_SETTINGS 授权）"
                }
            } catch (t: Throwable) {
                Log.w(TAG, "停止清理失败: ${t.message}")
                _status.value = "已停止（清理异常：${t.message}）"
            } finally {
                // ★ 只在真正停止时清空「原始 conn」记录，
                //   这样多次 A↔C 切换期间它始终保存「最初的真实值」，
                //   不会被某次切回 A 误记成伪值 2（否则停止只会把 conn 还原回 2）。
                forcedConnOriginal = null
            }
        }
    }

    private fun cancelWatchdog() {
        watchdog?.cancel()
        watchdog = null
        _running.value = false
        _mode.value = Mode.OFF
    }

    /**
     * 隐藏悬浮菜单：断开态 `pstate=0` → 状态机走"菜单收起"分支；
     * 同时 `attach=1`(吸附) 作为第二道保险，两条路径都指向隐藏。
     * 这里**不做跳变**（直接写目标值），避免停止瞬间反而把球闪出来。
     */
    private suspend fun hideMenu(context: Context) {
        putGlobalVerified(context, K_PSTATE, "0")
        putGlobalVerified(context, K_ATYPE, "1")
        putGlobalVerified(context, K_ATTACH, "1")
    }

    /**
     * 还原被 A 模式伪造过的 conn。
     * 仅当当前值仍是 2（说明期间无人改动）才还原，避免误伤系统写入的真实值。
     */
    private fun readGlobalInt(context: Context, key: String, def: Int): Int = try {
        Settings.Global.getInt(context.contentResolver, key, def)
    } catch (t: Throwable) {
        def
    }

    private fun readGlobalString(context: Context, key: String): String? = try {
        Settings.Global.getString(context.contentResolver, key)
    } catch (t: Throwable) {
        null
    }

    /**
     * 带回读校验的写入：写完立刻回读确认生效，失败自动重试。
     *
     * ★ 为什么需要：此前「停止 / 清除残留」偶尔无效果，根因是单次 `settings put`
     * 偶发丢写（Shizuku 进程创建竞态、或被系统笔服务在读回前覆盖）。
     * 回读校验能立即发现未生效的写入并重试，让停止/恢复变得可预见。
     *
     * @return 末次写入是否最终读回一致（true=生效）
     */
    private suspend fun putGlobalVerified(context: Context, key: String, value: String, retries: Int = 2): Boolean {
        repeat(retries) {
            if (putGlobal(context, key, value) && readGlobalString(context, key) == value) {
                return true
            }
            delay(100)
        }
        // 末次补写（不校验），至少把命令发出去
        return putGlobal(context, key, value)
    }


    // ============ 原始状态快照（启动前记录 / 停止时还原） ============

    /**
     * 停止 / 切换模式时的**统一清理**：撤销伪造的"笔身份" + 强制隐藏悬浮菜单。
     *
     * ★ 为什么不是"还原原始显示状态"：
     *   原始状态本身可能就是"显示菜单"（例如笔处于取下状态时，系统本来就会正常显示球）。
     *   若停止时还原原值，菜单会原样留着，与"停止即收起"的预期相悖 —— 这正是之前的 bug。
     *   因此停止的语义确定为：**清掉我伪造的一切（conn），并强制把菜单收起来**。
     */
    /**
     * 停止 / 切换 / 恢复时的**统一清理**：撤销伪造的"笔身份" + 强制隐藏悬浮菜单。
     *
     * ★ 为什么不是"还原原始显示状态"：
     *   原始状态本身可能就是"显示菜单"（例如笔处于取下状态时，系统本来就会正常显示球）。
     *   若停止时还原原值，菜单会原样留着，与"停止即收起"的预期相悖。
     *   因此停止语义确定为：**清掉我伪造的 conn，并强制把菜单收起来**。
     *
     * @return 所有写入是否最终生效（用于 UI 反馈，避免"点了没反应"却无提示）
     */
    private suspend fun performCleanup(context: Context): Boolean {
        var ok = true
        // 1) 还原被 A 模式伪造过的 conn（仅当我们曾伪造过，且当前仍是 2）
        //   注意：forcedConnOriginal 在此【不置空】——它要跨多次 A↔C 切换一直保留
        //   「最初的真实值」，直到 stop()/resetToOriginal() 才清空，否则切回 A 时会
        //   把伪值 2 记成原始值，导致停止只能还原回 2（用户反馈问题 2）。
        val original = forcedConnOriginal
        if (original != null) {
            val current = readGlobalInt(context, K_CONN, -1)
            if (current == 2) {
                val target = if (original >= 0) original.toString() else "0"
                ok = putGlobalVerified(context, K_CONN, target) && ok
                Log.d(TAG, "已还原伪造的 connection_state: 2 → $target")
            }
        }
        // 2) 强制隐藏悬浮菜单：pstate=0(断开) + attach=1(吸附) 两条路径都指向隐藏
        ok = putGlobalVerified(context, K_PSTATE, "0") && ok
        ok = putGlobalVerified(context, K_ATYPE, "1") && ok
        ok = putGlobalVerified(context, K_ATTACH, "1") && ok
        return ok
    }

    // ============ 彻底恢复原始状态 ============

    /**
     * 彻底恢复：还原快照 + 清除所有笔相关 Settings 残留（可选：解除蓝牙配对）。
     * @param unpair 是否同时解除已配对的手写笔（破坏性操作，需用户显式确认）
     */
    fun resetToOriginal(context: Context, unpair: Boolean = false) {
        val appCtx = context.applicationContext
        _status.value = "正在恢复原始状态…"
        scope.launch {
            transitionLock.withLock {
                // ★ 同 stop：串行化 + join 旧 tick，避免与旧 tick 竞态写回 conn=2。
                val old = watchdog
                watchdog = null
                _running.value = false
                _mode.value = Mode.OFF
                old?.cancel()
                old?.join()
            }
            val msg = try {
                // 先收菜单 + 清假笔，再清全部残留键（顺序不能反，否则清理后菜单又冒出来）
                val cleanOk = performCleanup(appCtx)
                val cleared = clearPenSettings(appCtx)
                // ★ 二次防御：部分 ROM 的笔管理服务会在我们清完后立刻把
                //   conn / pstate 等重写回「有笔」，表现为「点了清除但假笔还在」。
                //   故清完等一小会儿再回读一轮，没清干净的键补写中性值。
                val recleared = reclearIfNeeded(appCtx)
                val pairPart = if (unpair) {
                    val (tried, ok) = unpairPens(appCtx)
                    "，解除配对 $ok/$tried"
                } else ""
                val warn = if (!cleanOk) "（警告：部分键值写入未生效，请检查授权）" else ""
                val totalNote = if (cleared < PEN_SETTINGS_KEYS.size)
                    "（仅清理 $cleared/${PEN_SETTINGS_KEYS.size} 个，其余键写入未生效）" else ""
                val reclearNote = if (recleared > 0) "，二次补清 $recleared 个" else ""
                "已恢复原始状态（清除 $cleared 个残留键$reclearNote$pairPart）$warn$totalNote"
            } catch (t: Throwable) {
                Log.w(TAG, "恢复原始状态失败: ${t.message}")
                "恢复失败：${t.message}"
            } finally {
                forcedConnOriginal = null
            }
            _status.value = msg
        }
    }

    /**
     * 清除荣耀笔相关 Settings 残留（CONNECT / 电量 / 型号 / 状态机 …）。
     *
     * ★ 状态类键必须写入**明确的中性值**而不是只删除：
     *   只删除的话，正在运行的笔管理服务会立刻按真实笔状态重写回来，
     *   表现就是"点了清除但什么都没变"。
     * @return 实际成功的键数量（失败不计入，避免虚假的"清除 10 个"）
     */
    private suspend fun clearPenSettings(context: Context): Int {
        var n = 0
        for (key in PEN_SETTINGS_KEYS) {
            val neutral = when (key) {
                K_ATTACH, K_ATYPE -> "1"   // 吸附态 → 隐藏菜单
                else -> "0"                // conn / pstate / 其余元数据 → 断开 / 清零
            }
            // ★ 用回读校验计数：失败不计入，避免虚假的"清除 10 个"
            if (putGlobalVerified(context, key, neutral)) n++
        }
        return n
    }

    /**
     * 二次防御：清完所有残留键后，等一小段时间让系统笔服务可能的「重写」生效，
     * 再回读一轮；仍有非中性值的键补写中性值。
     *
     * 解决「点了清除键值残留 / 彻底恢复，过一会儿假笔状态又回来」的问题——
     * 部分 ROM 的笔管理服务会在我们写完立刻把 conn / pstate 等重写回「有笔」。
     */
    private suspend fun reclearIfNeeded(context: Context): Int {
        delay(400)
        var n = 0
        for (key in PEN_SETTINGS_KEYS) {
            val neutral = when (key) {
                K_ATTACH, K_ATYPE -> "1"
                else -> "0"
            }
            if (readGlobalString(context, key) != neutral) {
                if (putGlobalVerified(context, key, neutral)) n++
            }
        }
        return n
    }

    /**
     * 解除已配对的手写笔。
     *
     * ⚠️ `BluetoothDevice.removeBond()` **不在公开 SDK**（实测 android.jar 中不存在，
     *    `createBond`/`getBondState` 才是公开的），因此只能用反射调用，
     *   且部分 ROM / Android 版本会拒绝。故如实返回成功数，由 UI 展示结果。
     */
    private fun unpairPens(context: Context): Pair<Int, Int> {
        var tried = 0
        var ok = 0
        try {
            if (ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "缺少 BLUETOOTH_CONNECT 权限，无法解除配对")
                return 0 to 0
            }
            @Suppress("MissingPermission")
            val bonded = BluetoothAdapter.getDefaultAdapter()?.bondedDevices ?: emptySet()
            for (device in bonded) {
                @Suppress("MissingPermission")
                val name = device.name ?: continue
                if (!isPenName(name)) continue
                tried++
                try {
                    val method = device.javaClass.getDeclaredMethod("removeBond")
                    method.isAccessible = true
                    if (method.invoke(device) as? Boolean == true) {
                        ok++
                        Log.d(TAG, "已解除配对: $name")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "removeBond 失败($name): ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "枚举已配对设备失败: ${t.message}")
        }
        return tried to ok
    }

    private fun isPenName(name: String): Boolean {
        val n = name.lowercase(Locale.getDefault())
        return n.contains("pencil") || n.contains("stylus") ||
            n.contains("m-pen") || name.contains("手写笔") || name.contains("触控笔")
    }

    private suspend fun tick(context: Context, mode: Mode) {
        val env = StylusEnvDetector.detect(context)
        _env.value = env

        // ★ 华为体系：系统原生接管 → 禁用并停止，绝不写任何键
        if (env.system == StylusVendorSystem.HUAWEI) {
            cancelWatchdog()
            _status.value = "华为体系：系统原生接管，无需模拟（已停止）"
            return
        }
        if (!env.canSimulate) {
            _status.value = "未检测到荣耀悬浮菜单组件，模拟不可用"
            return
        }
        if (!env.shizukuGranted) {
            _status.value = "Shizuku 未授权，无法写入 Settings"
            return
        }

        when (mode) {
            Mode.SIMULATE -> simulateRealPen(context, env)
            Mode.FOLLOW -> followRealPen(context, env)
            Mode.FORCE_MENU -> forceMenu(context, env)
            Mode.OFF -> Unit
        }
    }

    /**
     * 模式 A「假装有笔」：不管有没有真笔，强制把悬浮菜单顶出来并保持。
     *
     * ★ 必须补 `connection_state=2`：`p()` 第 6 条要求 `honor_bt_pencil_connection_state != 0`，
     *   没笔时该键是 0，不补就永远出不来球。
     * ★ 每轮强制重写 = 看门狗，对抗「真笔接入后系统覆盖我们的值导致球掉」。
     */
    private suspend fun simulateRealPen(context: Context, env: StylusEnv) {
        // 记录伪造前的原始 conn，供停止 / 切 B 模式时还原
        if (forcedConnOriginal == null) {
            forcedConnOriginal = readGlobalInt(context, K_CONN, -1)
        }
        putGlobalVerified(context, K_CONN, "2")
        putGlobalVerified(context, K_PSTATE, "2")
        putGlobalVerified(context, K_ATYPE, "1")
        flipAttach(context, show = true)
        _lastWriteTime.value = System.currentTimeMillis()
        _status.value = if (env.hasRealPen) {
            "模拟中（检测到真笔接入，仍保持强制显示）"
        } else {
            "模拟中：已强制显示悬浮菜单"
        }
    }

    /**
     * 模式 B「真笔跟随」：磁吸充电 → 隐藏；取下不充电 → 显示。
     *
     * 适用：M-Pencil 2 (CD54) / M-Pencil 3 (CD54S) / M-Pencil Pro 等华为笔，
     * 以及荣耀笔 —— 它们在荣耀机上能连能写，但笔包不一定接管，故由本模式补齐悬浮菜单行为。
     */
    private suspend fun followRealPen(context: Context, env: StylusEnv) {
        // ★ 判"有没有真笔"只认 BLE 连接（我们没写过的信号），
        //   不再依赖 honor_bt_pencil_connection_state —— 那正是 A 模式伪造的键，
        //   用它判定会自污染（假笔状态让"有真笔"永远为真）。
        val connected = env.bleConnected || env.hasRealPen
        if (!connected) {
            putGlobalVerified(context, K_PSTATE, "0")
            _lastWriteTime.value = System.currentTimeMillis()
            _status.value = "未检测到已连接的手写笔：无悬浮菜单"
            return
        }
        val label = if (env.bleConnected) env.penModelLabel else (env.modelNumber ?: "手写笔")

        if (env.chargingState == 1) {
            // 磁吸充电 → 真笔写 pstate=4 / attach=1 → 球强制隐藏（设计如此）
            putGlobalVerified(context, K_PSTATE, "4")
            putGlobalVerified(context, K_ATYPE, "1")
            flipAttach(context, show = false)
            _status.value = "$label · 磁吸充电：悬浮菜单已隐藏"
        } else {
            // 取下 → 真笔写 pstate=2 / attach=0 → 球显示
            putGlobalVerified(context, K_PSTATE, "2")
            putGlobalVerified(context, K_ATYPE, "1")
            flipAttach(context, show = true)
            _status.value = "$label · 已取下：悬浮菜单已显示"
        }
        _lastWriteTime.value = System.currentTimeMillis()
    }

    /**
     * 模式 C「强制悬浮菜单」：只驱动悬浮菜单状态机，**不写任何笔状态键**。
     *
     * 与 A「假装有笔」的本质区别：
     *  - A 会写 `honor_bt_pencil_connection_state=2`，让系统"相信"有一支笔在线 → **凭空造笔**；
     *  - C 完全不碰 conn / 型号 / 电量等任何笔身份键，只把菜单顶出来 → **不留任何假笔残留**。
     *
     * ★【真机实测边界 HONOR CGA-W00】
     *   · conn=2 时仅写 pstate=2 + attach=0（不碰 conn）→ 悬浮球出现（窗口 0→6）✅
     *   · conn=0 时同样操作 → 球不出来（0）❌
     *   ⇒ `p()` 判定第 6 条 `honor_bt_pencil_connection_state != 0` 是硬门槛，
     *     C 模式必须在系统已接管某支笔（conn≥1）的前提下才有效。
     */
    private suspend fun forceMenu(context: Context, env: StylusEnv) {
        // ★ 与 A 的分界线：A 伪造整条链路(含 conn=2+身份键)；C 绝不写型号/电量/名字等任何笔身份键。
        //
        // ★ 用户真机观察修正：想要「菜单显示中但没有假笔状态」，正确时序是
        //   「先开笔状态(conn=2)打通菜单通道 → 把菜单顶出来 → 再关掉笔状态(conn=0)」。
        //   菜单被顶出后即使 conn 回到 0 仍保持显示，于是既有菜单、又零笔身份残留。
        //   故每轮心跳都重复「开闸 → 顶出 → 关闸回真实原值」，对抗 45 秒空闲超时的同时，
        //   保证两次心跳之间 conn≈0（无残留）。有真实笔(conn≥1)时不碰 conn（那是真笔，非伪造）。
        val genuine = forcedConnOriginal ?: env.connectionState
        val needGate = genuine <= 0   // 无真实笔 → 必须先临时开 conn=2 才能顶出菜单
        if (needGate) {
            putGlobalVerified(context, K_CONN, "2")   // 1) 开笔状态：打通菜单通道
        }
        // 2) 把菜单顶出来（仅驱动状态机，不写任何笔身份键）
        putGlobalVerified(context, K_PSTATE, "2")
        putGlobalVerified(context, K_ATYPE, "1")
        flipAttach(context, show = true)
        // 3) 关笔状态：移除假笔残留（菜单已显示，conn 回 0 仍保持）
        if (needGate) {
            putGlobalVerified(context, K_CONN, "0")
        }
        _lastWriteTime.value = System.currentTimeMillis()
        val note = if (needGate) "（已临时开/关 conn 顶出菜单，无笔身份残留）" else "（系统已有真实笔，仅驱动菜单）"
        _status.value = "强制显示中：仅驱动菜单状态机，未写任何笔身份键$note"
    }

    /**
     * attach 键跳变（show=true 制造 1→0；show=false 制造 0→1）。
     *
     * ★ ContentObserver 只在**值发生变化**时回调。球被空闲超时收掉后，
     *   再写同一个值不会有任何反应 —— 必须先写反值，再写目标值。
     *
     * ★【真机实测修正 HONOR CGA-W00 / Magic-Pencil 已接管(conn=2)】
     *   实测：attach 1→0 后球出现（FloatBallWindowView 0→4），**静置 48 秒仍在**，
     *   即 45 秒空闲超时在"真笔已接管"场景下**并未触发**。
     *   文档记录的 45 秒超时来自"完全无笔纯伪造"场景。
     *   ⇒ 仍保留每轮跳变：一是真笔接入时系统会在物理事件瞬间覆盖这些键（最大风险），
     *     二是纯模拟（无笔、conn 被我们强制造为 2）场景超时是否生效未经实测。
     *     代价是每个周期有约 200ms 的隐藏间隙（表现为极短闪烁）。
     */
    private suspend fun flipAttach(context: Context, show: Boolean) {
        val target = if (show) "0" else "1"
        val opposite = if (show) "1" else "0"
        putGlobal(context, K_ATTACH, opposite)
        delay(200)
        putGlobal(context, K_ATTACH, target)
    }

    /**
     * 写 Settings.Global。
     * 优先走已授权的 WRITE_SECURE_SETTINGS 直写；否则经 Shizuku 反射 newProcess。
     */
    private fun putGlobal(context: Context, key: String, value: String): Boolean {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_SECURE_SETTINGS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return try {
                Settings.Global.putString(context.contentResolver, key, value)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "直接写入失败: ${t.message}")
                false
            }
        }
        return runViaShizuku("settings", "put", "global", key, value)
    }

    /** 经 Shizuku 执行 shell 命令（反射 newProcess，兼容 Shizuku 13.x —— 沿用项目既有写法） */
    private fun runViaShizuku(vararg cmd: String): Boolean = try {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        method.isAccessible = true
        val process = method.invoke(null, cmd, null, null) as? java.lang.Process
        if (process == null) {
            Log.w(TAG, "Shizuku newProcess 返回 null")
            false
        } else {
            val code = process.waitFor()
            process.destroy()
            code == 0
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Shizuku 写入异常: ${t.message}")
        false
    }
}
