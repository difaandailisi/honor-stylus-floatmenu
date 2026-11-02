package com.batteryanalyzer.keymap

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.batteryanalyzer.data.BatteryRepository
import com.batteryanalyzer.model.CustomKeyCode
import com.batteryanalyzer.model.DeviceKeyMapping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 键值映射管理器 v6.4
 *
 * 核心改进：
 * 1. 每设备独立映射（通过 deviceId 区分）
 * 2. 双学习模式（原始键值 / 目标键值分别学习）
 * 3. 动态键值字典扩展（自定义键值入库 global）
 * 4. 启用锁定（启用后防误触修改）
 * 5. 三层注入管道：Shizuku → AccessibilityService GlobalAction → Shell
 * 6. v6.4: Linux→Android 键值反向解析 (.kl 文件) + scanCode 兜底匹配
 */
object KeyMappingManager {

    private const val TAG = "KeyMappingManager"
    private const val PREFS_NAME = "key_mapping_prefs"
    private const val KEY_LAST_DETECTED = "last_detected_code"
    private const val DEBOUNCE_MS = 300L

    // ============ 注入方法枚举 ============

    enum class InjectionMethod(val label: String, val description: String) {
        SHIZUKU("Shizuku", "通过 Shizuku 服务执行 input keyevent，兼容所有键值"),
        ACCESSIBILITY("无障碍服务", "通过无障碍服务全局操作，仅支持返回/主页/最近任务等"),
        SHELL("Shell", "通过 shell 命令注入，需要 ADB/root 权限"),
        NONE("不可用", "当前无可用注入方式，请安装 Shizuku 或连接 ADB")
    }

    /** 学习目标类型 */
    enum class LearnTarget { SOURCE, TARGET }

    // ============ 三层键值字典 (内置) ============

    private val androidKeyCodes = mapOf(
        "KEYCODE_BACK" to 4, "KEYCODE_HOME" to 3, "KEYCODE_MENU" to 82,
        "KEYCODE_CALL" to 5, "KEYCODE_ENDCALL" to 6,
        "KEYCODE_VOLUME_UP" to 24, "KEYCODE_VOLUME_DOWN" to 25, "KEYCODE_VOLUME_MUTE" to 164,
        "KEYCODE_POWER" to 26, "KEYCODE_CAMERA" to 27, "KEYCODE_FOCUS" to 80,
        "KEYCODE_SEARCH" to 84, "KEYCODE_APP_SWITCH" to 187, "KEYCODE_NOTIFICATION" to 83,
        "KEYCODE_MEDIA_PLAY_PAUSE" to 85, "KEYCODE_MEDIA_STOP" to 86,
        "KEYCODE_MEDIA_NEXT" to 87, "KEYCODE_MEDIA_PREVIOUS" to 88,
        "KEYCODE_MEDIA_REWIND" to 89, "KEYCODE_MEDIA_FAST_FORWARD" to 90,
        "KEYCODE_MUTE" to 91, "KEYCODE_WINDOW" to 171,
        "KEYCODE_SLEEP" to 223, "KEYCODE_WAKEUP" to 224, "KEYCODE_FLASHLIGHT" to 265,
        "KEYCODE_SYSRQ" to 120,
        "KEYCODE_STYLUS_BUTTON_PRIMARY" to 334, "KEYCODE_STYLUS_BUTTON_SECONDARY" to 335,
        "KEYCODE_STYLUS_BUTTON_TERTIARY" to 336, "KEYCODE_STYLUS_BUTTON_TAIL" to 337,
        "KEYCODE_DPAD_UP" to 19, "KEYCODE_DPAD_DOWN" to 20,
        "KEYCODE_DPAD_LEFT" to 21, "KEYCODE_DPAD_RIGHT" to 22, "KEYCODE_DPAD_CENTER" to 23,
        "KEYCODE_ESCAPE" to 111, "KEYCODE_ENTER" to 66, "KEYCODE_DEL" to 67,
        "KEYCODE_TAB" to 61, "KEYCODE_SPACE" to 62,
        "KEYCODE_PAGE_UP" to 92, "KEYCODE_PAGE_DOWN" to 93,
        "KEYCODE_SCREENSHOT" to 120, "KEYCODE_BRIGHTNESS_UP" to 221, "KEYCODE_BRIGHTNESS_DOWN" to 220,
        // 扩展：英文特殊标点符号和数字
        "KEYCODE_0" to 7, "KEYCODE_1" to 8, "KEYCODE_2" to 9, "KEYCODE_3" to 10, "KEYCODE_4" to 11,
        "KEYCODE_5" to 12, "KEYCODE_6" to 13, "KEYCODE_7" to 14, "KEYCODE_8" to 15, "KEYCODE_9" to 16,
        "KEYCODE_A" to 29, "KEYCODE_B" to 30, "KEYCODE_C" to 31, "KEYCODE_D" to 32,
        "KEYCODE_E" to 33, "KEYCODE_F" to 34, "KEYCODE_G" to 35, "KEYCODE_H" to 36,
        "KEYCODE_I" to 37, "KEYCODE_J" to 38, "KEYCODE_K" to 39, "KEYCODE_L" to 40,
        "KEYCODE_M" to 41, "KEYCODE_N" to 42, "KEYCODE_O" to 43, "KEYCODE_P" to 44,
        "KEYCODE_Q" to 45, "KEYCODE_R" to 46, "KEYCODE_S" to 47, "KEYCODE_T" to 48,
        "KEYCODE_U" to 49, "KEYCODE_V" to 50, "KEYCODE_W" to 51, "KEYCODE_X" to 52,
        "KEYCODE_Y" to 53, "KEYCODE_Z" to 54,
        "KEYCODE_COMMA" to 55, "KEYCODE_PERIOD" to 56, "KEYCODE_SLASH" to 76,
        "KEYCODE_SEMICOLON" to 74, "KEYCODE_APOSTROPHE" to 75,
        "KEYCODE_LEFT_BRACKET" to 71, "KEYCODE_RIGHT_BRACKET" to 72,
        "KEYCODE_MINUS" to 69, "KEYCODE_EQUALS" to 70, "KEYCODE_GRAVE" to 68,
        "KEYCODE_AT" to 77, "KEYCODE_POUND" to 18, "KEYCODE_STAR" to 17,
        "KEYCODE_PLUS" to 81, "KEYCODE_NUM" to 78, "KEYCODE_NUMPAD_0" to 144,
        "KEYCODE_NUMPAD_1" to 145, "KEYCODE_NUMPAD_2" to 146, "KEYCODE_NUMPAD_3" to 147,
        "KEYCODE_NUMPAD_4" to 148, "KEYCODE_NUMPAD_5" to 149, "KEYCODE_NUMPAD_6" to 150,
        "KEYCODE_NUMPAD_7" to 151, "KEYCODE_NUMPAD_8" to 152, "KEYCODE_NUMPAD_9" to 153,
        "KEYCODE_NUMPAD_DOT" to 158, "KEYCODE_NUMPAD_ADD" to 157, "KEYCODE_NUMPAD_SUBTRACT" to 156,
        "KEYCODE_NUMPAD_MULTIPLY" to 155, "KEYCODE_NUMPAD_DIVIDE" to 154,
        "KEYCODE_NUMPAD_ENTER" to 160, "KEYCODE_NUMPAD_EQUALS" to 161,
        "KEYCODE_INSERT" to 124, "KEYCODE_FORWARD_DEL" to 112,
        "KEYCODE_NUM_LOCK" to 143, "KEYCODE_SCROLL_LOCK" to 116,
        "KEYCODE_CAPS_LOCK" to 115, "KEYCODE_BREAK" to 121,
        "KEYCODE_MOVE_HOME" to 122, "KEYCODE_MOVE_END" to 123,
        "KEYCODE_CTRL_LEFT" to 113, "KEYCODE_CTRL_RIGHT" to 114,
        "KEYCODE_SHIFT_LEFT" to 59, "KEYCODE_SHIFT_RIGHT" to 60,
        "KEYCODE_ALT_LEFT" to 57, "KEYCODE_ALT_RIGHT" to 58,
        "KEYCODE_META_LEFT" to 117, "KEYCODE_META_RIGHT" to 118,
        "KEYCODE_F1" to 131, "KEYCODE_F2" to 132, "KEYCODE_F3" to 133, "KEYCODE_F4" to 134,
        "KEYCODE_F5" to 135, "KEYCODE_F6" to 136, "KEYCODE_F7" to 137, "KEYCODE_F8" to 138,
        "KEYCODE_F9" to 139, "KEYCODE_F10" to 140, "KEYCODE_F11" to 141, "KEYCODE_F12" to 142,
    )

    private val linuxKeyCodes = mapOf(
        "KEY_BACK" to 158, "KEY_HOMEPAGE" to 172, "KEY_MENU" to 139, "KEY_SEARCH" to 217,
        "KEY_VOLUMEUP" to 115, "KEY_VOLUMEDOWN" to 114, "KEY_POWER" to 116,
        "KEY_SLEEP" to 142, "KEY_WAKEUP" to 143, "KEY_CAMERA" to 212, "KEY_CONFIG" to 213,
        "KEY_SELECT" to 353, "KEY_STYLUS" to 331, "BTN_STYLUS" to 331,
        "BTN_STYLUS2" to 332, "BTN_STYLUS3" to 333,
        "KEY_PLAYPAUSE" to 164, "KEY_NEXTSONG" to 163,
        "KEY_PREVIOUSSONG" to 165, "KEY_STOPCD" to 166, "KEY_MUTE" to 113,
    )

    private val aliases = mapOf(
        "BACK" to 4, "HOME" to 3, "MENU" to 82, "SEARCH" to 84,
        "VOLUME_UP" to 24, "VOLUME_DOWN" to 25, "POWER" to 26, "CAMERA" to 27,
        "SLEEP" to 223, "WAKEUP" to 224, "FLASHLIGHT" to 265,
        "PLAY_PAUSE" to 85, "MEDIA_NEXT" to 87, "MEDIA_PREVIOUS" to 88,
        "MUTE" to 91, "APP_SWITCH" to 187, "NOTIFICATION" to 83,
        "STYLUS" to 334, "STYLUS2" to 335,
    )

    private val allNamesToCode: Map<String, Int> by lazy {
        val map = mutableMapOf<String, Int>()
        androidKeyCodes.forEach { (k, v) -> map[k.uppercase()] = v; map[k] = v }
        linuxKeyCodes.forEach { (k, v) -> map[k.uppercase()] = v; map[k] = v }
        aliases.forEach { (k, v) -> map[k.uppercase()] = v; map[k] = v }
        map["VOLUME+"] = 24; map["VOLUME-"] = 25
        map["STYLUS_BUTTON"] = 334; map["STYLUS_BUTTON_PRIMARY"] = 334
        map
    }

    private val allCodesToName: Map<Int, String> by lazy {
        val map = mutableMapOf<Int, String>()
        androidKeyCodes.forEach { (k, v) -> map[v] = k }
        map
    }

    // ============ v6.4: Linux→Android 键值反向映射缓存 ============
    /** Linux 键值 → Android 键值 映射 (通过学习模式或 .kl 文件建立) */
    private val linuxToAndroidMap = mutableMapOf<Int, Int>()
    /** Android 键值 → Linux 键值 反向映射 */
    private val androidToLinuxMap = mutableMapOf<Int, Int>()
    /** .kl 文件是否已加载 */
    private var keyLayoutLoaded = false

    // ============ 运行时自定义键值缓存 ============
    /** 从数据库加载的自定义键值，合并到名称→代码字典 */
    private val customNamesToCode = mutableMapOf<String, Int>()
    /** 从数据库加载的自定义键值，合并到代码→名称字典 */
    private val customCodesToName = mutableMapOf<Int, String>()

    // ============ 每设备状态 v6.0: 支持多映射列表 ============

    /** 当前正在配置的设备 ID */
    private val _currentDeviceId = MutableStateFlow(-1L)
    val currentDeviceId: StateFlow<Long> = _currentDeviceId.asStateFlow()

    /** v6.0: 当前设备的所有键值映射列表 (源键→目标键) */
    private val _mappingsList = MutableStateFlow<List<DeviceKeyMapping>>(emptyList())
    val mappingsList: StateFlow<List<DeviceKeyMapping>> = _mappingsList.asStateFlow()

    /** 当前 UI 中正在编辑/学习的映射 ID (0 表示无) */
    private val _currentEditingMappingId = MutableStateFlow(0L)
    val currentEditingMappingId: StateFlow<Long> = _currentEditingMappingId.asStateFlow()

    // === 兼容旧 API (供无障碍服务读取, 指向第一个已启用的映射) ===
    /** 第一个已配好源键值的映射的 sourceKeyCode，供服务快速判断 */
    private val _sourceKeyCode = MutableStateFlow(-1)
    val sourceKeyCode: StateFlow<Int> = _sourceKeyCode.asStateFlow()

    /** 第一个已配好目标键值的映射的 targetKeyCode */
    private val _targetKeyCode = MutableStateFlow(-1)
    val targetKeyCode: StateFlow<Int> = _targetKeyCode.asStateFlow()

    /** 设备级启用状态 — v6.2: 改为反映"任意一条映射已启用" */
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** 学习模式是否激活 */
    private val _learnModeActive = MutableStateFlow(false)
    val learnModeActive: StateFlow<Boolean> = _learnModeActive.asStateFlow()

    /** 当前学习目标 */
    private val _learnTarget = MutableStateFlow(LearnTarget.SOURCE)
    val learnTarget: StateFlow<LearnTarget> = _learnTarget.asStateFlow()

    /** 学习模式下最近检测到的键值 */
    private val _lastDetectedKey = MutableStateFlow(-1)
    val lastDetectedKey: StateFlow<Int> = _lastDetectedKey.asStateFlow()

    /** 可用的注入方法 */
    private val _availableInjectionMethod = MutableStateFlow(InjectionMethod.NONE)
    val availableInjectionMethod: StateFlow<InjectionMethod> = _availableInjectionMethod.asStateFlow()

    /** Shizuku 是否可用 */
    private val _shizukuAvailable = MutableStateFlow(false)
    val shizukuAvailable: StateFlow<Boolean> = _shizukuAvailable.asStateFlow()

    /** v6.3: Shizuku 权限是否已授予 (可被 UI 观察) */
    private val _shizukuPermissionGranted = MutableStateFlow(false)
    val shizukuPermissionGranted: StateFlow<Boolean> = _shizukuPermissionGranted.asStateFlow()

    /** ADB 是否已授权 */
    private val _adbAuthorized = MutableStateFlow(false)
    val adbAuthorized: StateFlow<Boolean> = _adbAuthorized.asStateFlow()

    /** 当前设备映射是否已从 DB 加载 */
    private val _mappingLoaded = MutableStateFlow(false)
    val mappingLoaded: StateFlow<Boolean> = _mappingLoaded.asStateFlow()

    /** v6.3: 无障碍服务运行状态 (可被 UI 观察) */
    private val _accessibilityServiceRunning = MutableStateFlow(false)
    val accessibilityServiceRunning: StateFlow<Boolean> = _accessibilityServiceRunning.asStateFlow()

    /** v6.7: getevent 底层监听是否激活 (用于捕获被系统拦截的键值, 如 760) */
    private val _geteventActive = MutableStateFlow(false)
    val geteventActive: StateFlow<Boolean> = _geteventActive.asStateFlow()

    @Volatile
    var accessibilityService: AccessibilityService? = null

    private var lastTriggerTime = 0L
    private var appContext: Context? = null
    private var repository: BatteryRepository? = null
    private var shizukuInitialized = false
    @Volatile
    private var geteventMonitorThread: Thread? = null
    /** v6.11: 持有 Shizuku getevent 进程引用，使 stop 可以强制 destroy */
    @Volatile
    private var geteventProcess: Process? = null

    // v6.10: getevent 操作防抖时间戳，防止快速 start/stop 导致闪烁
    @Volatile
    private var lastGeteventToggleTime = 0L

    // ============ Shizuku 监听器 ============

    private val shizukuBinderReceivedListener = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() {
            Log.i(TAG, "Shizuku Binder 已接收")
            checkShizukuPermission()
        }
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku Binder 已断开")
        _shizukuAvailable.value = false
        refreshInjectionMethod()
        stopGeteventMonitor() // v6.11: Shizuku 死亡时自动停止底层监听
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        Log.i(TAG, "Shizuku 权限结果: requestCode=$requestCode, granted=$grantResult")
        checkShizukuPermission()
    }

    // ============ 初始化 ============

    fun init(context: Context) {
        appContext = context.applicationContext
        if (repository == null) {
            repository = BatteryRepository(context.applicationContext)
        }
        // 恢复上一次检测到的键值（兼容旧版）
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _lastDetectedKey.value = prefs.getInt(KEY_LAST_DETECTED, -1)

        // v6.3: 初始化无障碍服务运行状态
        _accessibilityServiceRunning.value =
            isAccessibilityServiceEnabled(context) || KeyMappingService.isRunning

        // v6.5: 初始化键值映射日志文件
        KeyMappingLogger.init(context.applicationContext)

        // v6.4: 预加载 .kl 文件以建立 Linux→Android 键值映射
        loadKeyLayoutMappings()

        initShizuku()
        refreshCustomKeyCodes()
    }

    // ============ v5.1: 控制中心快捷操作 ============

    /**
     * 设置当前键值映射的目标设备 ID（轻量级，不触发完整映射加载）
     */
    fun setCurrentDeviceId(deviceId: Long) {
        _currentDeviceId.value = deviceId
    }

    /**
     * 启用/禁用当前设备的所有键值映射并持久化到数据库
     * v6.0: 批量更新当前设备所有映射的启用状态
     */
    fun applyEnabled(enabled: Boolean) {
        val deviceId = _currentDeviceId.value
        _enabled.value = enabled
        // v6.3: 立即更新内存列表，确保 getEnabledMappings() 返回正确结果
        _mappingsList.value = _mappingsList.value.map { it.copy(isEnabled = enabled) }
        val activeCount = if (enabled) _mappingsList.value.size else 0
        val devName = KeyMappingLogger.deviceNameOverride ?: "设备#$deviceId"

        // v6.5: 日志文件 — 记录映射开关
        KeyMappingLogger.logMappingToggle(
            enabled = enabled, mappingCount = _mappingsList.value.size,
            deviceId = deviceId, deviceName = devName,
        )

        if (deviceId > 0) {
            val repo = repository ?: return
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    repo.keyMappingDao.updateEnabledByDeviceId(deviceId, enabled)
                    Log.d(TAG, "设备 $deviceId 所有映射已${if (enabled) "启用" else "停用"}")
                } catch (e: Exception) {
                    Log.w(TAG, "批量更新启用状态失败: ${e.message}")
                }
            }
        }
    }

    /**
     * v6.0: 切换单个映射的启用状态
     */
    fun toggleMappingEnabled(mappingId: Long, enabled: Boolean) {
        val repo = repository ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repo.keyMappingDao.updateEnabledById(mappingId, enabled)
                // 刷新本地列表
                val updated = _mappingsList.value.map {
                    if (it.id == mappingId) it.copy(isEnabled = enabled, updatedAt = System.currentTimeMillis()) else it
                }
                _mappingsList.value = updated
            } catch (e: Exception) {
                Log.w(TAG, "切换映射启用失败: ${e.message}")
            }
        }
    }

    /** 从数据库刷新自定义键值字典 */
    fun refreshCustomKeyCodes() {
        val repo = repository ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val customCodes = repo.customKeyCodeDao.getAll()
                customNamesToCode.clear()
                customCodesToName.clear()
                customCodes.forEach { cc ->
                    customCodesToName[cc.code] = cc.name.ifBlank { "custom_${cc.code}" }
                    if (cc.name.isNotBlank()) {
                        customNamesToCode[cc.name.uppercase()] = cc.code
                        customNamesToCode["KEYCODE_${cc.name.uppercase()}"] = cc.code
                    }
                }
                Log.d(TAG, "已加载 ${customCodes.size} 个自定义键值")
            } catch (e: Exception) {
                Log.w(TAG, "加载自定义键值失败: ${e.message}")
            }
        }
    }

    /**
     * 注册一个新自定义键值到数据库（全局共享）
     * @return true 表示成功录入（新键值），false 表示已存在
     */
    fun registerCustomKeyCode(code: Int, name: String, description: String = "", callback: (Boolean) -> Unit = {}) {
        val repo = repository ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = repo.customKeyCodeDao.insertIfNotExists(
                    CustomKeyCode(
                        code = code,
                        name = name.uppercase(),
                        category = "CUSTOM",
                        description = description
                    )
                )
                val isNew = result > 0
                if (isNew) {
                    // 更新内存缓存
                    customCodesToName[code] = name.uppercase()
                    customNamesToCode[name.uppercase()] = code
                    customNamesToCode["KEYCODE_${name.uppercase()}"] = code
                    Log.i(TAG, "新自定义键值已录入: $name ($code)")
                }
                kotlinx.coroutines.withContext(Dispatchers.Main) { callback(isNew) }
            } catch (e: Exception) {
                Log.w(TAG, "录入自定义键值失败: ${e.message}")
                kotlinx.coroutines.withContext(Dispatchers.Main) { callback(false) }
            }
        }
    }

    /** 获取所有自定义键值（用于 UI 展示） */
    fun getAllCustomKeyCodes(callback: (List<CustomKeyCode>) -> Unit) {
        val repo = repository ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val codes = repo.customKeyCodeDao.getAll()
                kotlinx.coroutines.withContext(Dispatchers.Main) { callback(codes) }
            } catch (_: Exception) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { callback(emptyList()) }
            }
        }
    }

    // ============ 设备切换与映射加载 ============

    /**
     * 切换到指定设备并加载其键值映射列表
     */
    fun switchToDevice(deviceId: Long) {
        if (_currentDeviceId.value == deviceId && _mappingLoaded.value) return
        _currentDeviceId.value = deviceId
        _mappingLoaded.value = false
        _currentEditingMappingId.value = 0L
        loadDeviceMapping(deviceId)
    }

    /**
     * v6.0: 从数据库加载指定设备的所有键值映射
     */
    fun loadDeviceMapping(deviceId: Long) {
        val repo = repository ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val mappings = repo.keyMappingDao.getByDeviceId(deviceId)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    _mappingsList.value = mappings
                    // 兼容旧 API: 取第一个有效映射
                    val first = mappings.firstOrNull()
                    _sourceKeyCode.value = first?.sourceKeyCode ?: -1
                    _targetKeyCode.value = first?.targetKeyCode ?: -1
                    // v6.2: _enabled 反映任意映射已启用 (不只看第一条)
                    _enabled.value = mappings.any { it.isEnabled }
                    _mappingLoaded.value = true
                    Log.d(TAG, "设备 $deviceId 已加载 ${mappings.size} 条映射, 已启用=${_enabled.value}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "加载设备映射失败: ${e.message}")
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    _mappingLoaded.value = true
                }
            }
        }
    }

    /**
     * v6.0: 保存单个映射到数据库
     */
    fun saveMapping(mapping: DeviceKeyMapping) {
        val repo = repository ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val existing = if (mapping.id > 0) repo.keyMappingDao.getById(mapping.id) else null
                val saved = mapping.copy(
                    sourceLabel = lookupCodeName(mapping.sourceKeyCode) ?: "",
                    targetLabel = lookupCodeName(mapping.targetKeyCode) ?: "",
                    injectionMethod = _availableInjectionMethod.value.name,
                    updatedAt = System.currentTimeMillis()
                )
                if (existing != null) {
                    repo.keyMappingDao.update(saved)
                } else {
                    val newId = repo.keyMappingDao.insert(saved.copy(createdAt = System.currentTimeMillis()))
                    // 刷新列表
                    loadDeviceMapping(_currentDeviceId.value)
                }
                // 刷新兼容 API
                refreshCompatFields()
                Log.d(TAG, "映射 ${saved.id} 已保存")
            } catch (e: Exception) {
                Log.w(TAG, "保存映射失败: ${e.message}")
            }
        }
    }

    /**
     * v6.0: 添加一条新的空映射规则
     */
    fun addMapping() {
        val deviceId = _currentDeviceId.value
        if (deviceId <= 0) return
        val repo = repository ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val mapping = DeviceKeyMapping(
                    deviceId = deviceId,
                    sourceKeyCode = -1,
                    targetKeyCode = -1,
                    isEnabled = false,
                    injectionMethod = _availableInjectionMethod.value.name
                )
                val newId = repo.keyMappingDao.insert(mapping)
                Log.d(TAG, "新增映射: id=$newId, deviceId=$deviceId")
                // 刷新列表
                loadDeviceMapping(deviceId)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    _currentEditingMappingId.value = newId
                }
            } catch (e: Exception) {
                Log.w(TAG, "新增映射失败: ${e.message}")
            }
        }
    }

    /**
     * v6.0: 删除指定映射
     */
    fun deleteMapping(mappingId: Long) {
        val repo = repository ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repo.keyMappingDao.deleteById(mappingId)
                Log.d(TAG, "已删除映射: $mappingId")
                loadDeviceMapping(_currentDeviceId.value)
            } catch (e: Exception) {
                Log.w(TAG, "删除映射失败: ${e.message}")
            }
        }
    }

    /**
     * v6.0: 更新指定映射的源键值
     */
    fun updateMappingSource(mappingId: Long, code: Int) {
        val repo = repository ?: return
        val label = lookupCodeName(code) ?: ""
        // v6.2: 自动注册不在字典中的键值
        if (code > 0 && !isKnownKeyCode(code)) {
            registerCustomKeyCode(code, "KEYCODE_VALUE_$code", "键值映射录入")
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repo.keyMappingDao.updateSourceById(mappingId, code, label)
                val updated = _mappingsList.value.map {
                    if (it.id == mappingId) it.copy(sourceKeyCode = code, sourceLabel = label, updatedAt = System.currentTimeMillis()) else it
                }
                _mappingsList.value = updated
                refreshCompatFields()
            } catch (e: Exception) {
                Log.w(TAG, "更新映射源键值失败: ${e.message}")
            }
        }
    }

    /**
     * v6.0: 更新指定映射的目标键值
     */
    fun updateMappingTarget(mappingId: Long, code: Int) {
        val repo = repository ?: return
        val label = lookupCodeName(code) ?: ""
        // v6.2: 自动注册不在字典中的键值
        if (code > 0 && !isKnownKeyCode(code)) {
            registerCustomKeyCode(code, "KEYCODE_VALUE_$code", "键值映射录入")
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repo.keyMappingDao.updateTargetById(mappingId, code, label)
                val updated = _mappingsList.value.map {
                    if (it.id == mappingId) it.copy(targetKeyCode = code, targetLabel = label, updatedAt = System.currentTimeMillis()) else it
                }
                _mappingsList.value = updated
                refreshCompatFields()
            } catch (e: Exception) {
                Log.w(TAG, "更新映射目标键值失败: ${e.message}")
            }
        }
    }

    /**
     * v6.0: 获取当前设备所有已启用的映射 (供无障碍服务使用)
     */
    fun getEnabledMappings(): List<DeviceKeyMapping> {
        return _mappingsList.value.filter {
            it.isEnabled && it.sourceKeyCode > 0 && it.targetKeyCode > 0
        }
    }

    /**
     * v6.0: 刷新兼容旧 API 的 sourceKeyCode / targetKeyCode
     */
    private fun refreshCompatFields() {
        val first = _mappingsList.value.firstOrNull()
        _sourceKeyCode.value = first?.sourceKeyCode ?: -1
        _targetKeyCode.value = first?.targetKeyCode ?: -1
        // v6.2: _enabled 反映任意映射已启用状态
        _enabled.value = _mappingsList.value.any { it.isEnabled }
    }

    /**
     * 保存当前设备的映射到数据库 (兼容旧 API，保存所有映射)
     */
    fun saveCurrentMapping() {
        val deviceId = _currentDeviceId.value
        if (deviceId <= 0) return
        val repo = repository ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // v6.0: 保存列表中所有的映射
                _mappingsList.value.forEach { mapping ->
                    val saved = mapping.copy(
                        sourceLabel = lookupCodeName(mapping.sourceKeyCode) ?: "",
                        targetLabel = lookupCodeName(mapping.targetKeyCode) ?: "",
                        injectionMethod = _availableInjectionMethod.value.name,
                        updatedAt = System.currentTimeMillis()
                    )
                    if (mapping.id > 0) {
                        repo.keyMappingDao.update(saved)
                    } else {
                        repo.keyMappingDao.insert(saved)
                    }
                }
                Log.d(TAG, "设备 $deviceId 所有映射已保存")
            } catch (e: Exception) {
                Log.w(TAG, "保存设备映射失败: ${e.message}")
            }
        }
    }

    // ============ Shizuku 初始化 ============

    private fun initShizuku() {
        if (shizukuInitialized) return
        shizukuInitialized = true
        try {
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            if (Shizuku.pingBinder()) {
                _shizukuAvailable.value = true
                checkShizukuPermission()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku 初始化失败: ${e.message}")
            _shizukuAvailable.value = false
        }
        refreshInjectionMethod()
    }

    fun onDestroy() {
        try {
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Exception) {}
    }

    // ============ Shizuku 权限 ============

    private fun checkShizukuPermission() {
        try {
            val granted = if (Shizuku.pingBinder()) {
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else false
            _shizukuAvailable.value = Shizuku.pingBinder()
            _shizukuPermissionGranted.value = granted
            Log.i(TAG, "Shizuku 状态: available=${_shizukuAvailable.value}, granted=$granted")
        } catch (e: Exception) {
            _shizukuAvailable.value = false
            _shizukuPermissionGranted.value = false
        }
        refreshInjectionMethod()
    }

    fun requestShizukuPermission(activity: android.app.Activity) {
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku 服务未运行")
                return
            }
            Shizuku.requestPermission(1001)
        } catch (e: Exception) {
            Log.w(TAG, "请求 Shizuku 权限失败: ${e.message}")
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
    }

    /** 将代码块投递到主线程执行 */
    private val mainHandler = Handler(Looper.getMainLooper())
    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: Exception) {
            try {
                context.packageManager.getPackageInfo("rikka.shizuku", 0)
                true
            } catch (_: Exception) { false }
        }
    }

    fun openShizukuDownload(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                "https://shizuku.rikka.app/download/"
            )).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
        } catch (_: Exception) {}
    }

    // ============ ADB 检测 ============

    fun checkAdbAuthorized() {
        Thread {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "echo ADB_OK"))
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val result = reader.readLine()
                process.waitFor()
                _adbAuthorized.value = (process.exitValue() == 0 && result?.contains("ADB_OK") == true)
            } catch (_: Exception) {
                _adbAuthorized.value = false
            }
            refreshInjectionMethod()
        }.start()
    }

    // ============ 注入方法判断 ============

    private fun refreshInjectionMethod() {
        _availableInjectionMethod.value = when {
            hasShizukuPermission() -> InjectionMethod.SHIZUKU
            _adbAuthorized.value -> InjectionMethod.SHELL
            accessibilityService != null -> InjectionMethod.ACCESSIBILITY
            else -> InjectionMethod.NONE
        }
    }

    fun refreshInjectionMethodPublic() = refreshInjectionMethod()

    // ============ v6.3: 权限状态刷新 ============

    /** 无障碍服务连接/断开时由 KeyMappingService 回调 */
    fun onAccessibilityServiceStateChanged(connected: Boolean) {
        _accessibilityServiceRunning.value = connected
        refreshInjectionMethod()
    }

    /**
     * v6.3: 从后台返回/权限授予后刷新所有权限与服务状态
     * @param context 用于查询系统无障碍服务启用状态
     */
    fun refreshPermissionsStatus(context: Context) {
        try {
            // 1. 检查无障碍服务是否已在系统中启用
            _accessibilityServiceRunning.value =
                isAccessibilityServiceEnabled(context) || KeyMappingService.isRunning
            Log.d(TAG, "刷新无障碍服务状态: ${_accessibilityServiceRunning.value}")
        } catch (e: Exception) {
            Log.w(TAG, "刷新无障碍状态失败: ${e.message}")
        }

        try {
            // 2. 重新检查 Shizuku 绑定与权限
            if (Shizuku.pingBinder()) {
                _shizukuAvailable.value = true
                checkShizukuPermission()
            } else {
                _shizukuAvailable.value = false
            }
        } catch (_: Exception) { }

        // 3. 刷新注入方式
        refreshInjectionMethod()
    }

    // ============ 双学习模式 ============

    /**
     * 启动学习模式
     * @param target 学习目标（原始键值 SOURCE / 目标键值 TARGET）
     * @param mappingId v6.0: 当前正在编辑/学习的映射 ID
     */
    fun startLearnMode(target: LearnTarget = LearnTarget.SOURCE, mappingId: Long = 0L) {
        _learnTarget.value = target
        _learnModeActive.value = true
        _lastDetectedKey.value = -1
        if (mappingId > 0) _currentEditingMappingId.value = mappingId
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putInt(KEY_LAST_DETECTED, -1)?.apply()
        Log.d(TAG, "学习模式启动: target=${target.name}, mappingId=$mappingId")
    }

    fun stopLearnMode() {
        val wasActive = _learnModeActive.value
        _learnModeActive.value = false
        stopGeteventMonitor() // v6.7: 停止底层监听
        if (wasActive) {
            val devId = _currentDeviceId.value
            val devName = KeyMappingLogger.deviceNameOverride ?: "设备#$devId"
            KeyMappingLogger.logLearnTimeout(
                reason = "用户取消学习",
                deviceId = devId, deviceName = devName,
            )
        }
        Log.d(TAG, "学习模式停止")
    }

    fun onKeyDetected(keyCode: Int, scanCode: Int = 0) {
        _lastDetectedKey.value = keyCode
        saveLastDetected(keyCode)

        // v6.4: 建立 Linux(scanCode) ↔ Android(keyCode) 双向映射
        if (scanCode > 0 && keyCode > 0 && scanCode != keyCode) {
            linuxToAndroidMap[scanCode] = keyCode
            androidToLinuxMap[keyCode] = scanCode
            Log.d(TAG, "建立键值映射: Linux($scanCode) → Android($keyCode)")
        }

        // v6.2: 学习模式下自动注册未知键值到字典（无需弹窗确认）
        if (keyCode > 0 && !isKnownKeyCode(keyCode)) {
            val autoName = "KEYCODE_UNKNOWN_$keyCode"
            registerCustomKeyCode(keyCode, autoName, "自动学习录入") { isNew ->
                Log.i(TAG, if (isNew) "自动注册新键值: $keyCode → $autoName"
                else "键值 $keyCode 已存在于字典，跳过注册")
            }
        }
        Log.d(TAG, "检测到键值: keyCode=$keyCode, scanCode=$scanCode")
    }

    // ============ v6.7: getevent 底层监听 (绕过系统拦截, 捕获 760 等厂商键值) ============

    /**
     * 通过 Shizuku 执行 getevent 直接读取 /dev/input/eventX 的原始 HID 数据。
     *
     * 为什么需要这个:
     *   Honor/MagicOS 等系统会在 InputDispatcher 层拦截厂商自定义键值 (如 760),
     *   路由到 Global Search / IME / System UI 后消费掉, 永远到达不了 AccessibilityService.onKeyEvent()。
     *   getevent 直接读取内核 evdev 层的原始事件, 完全绕过 Android 输入系统, 不受任何拦截。
     *
     * 工作原理:
     *   1. 使用 Shizuku 执行 `getevent -l` (带标签的人类可读模式)
     *   2. 监控所有 /dev/input/event* 设备
     *   3. 解析 EV_KEY 事件, 提取键值 (扫描码)
     *   4. 检测到按键按下 (DOWN) 时, 将键值报告给 onKeyDetected
     *
     * @param enterCode 用户指定的特定键值 (如 760), 仅监控该键值; ≤0 则监控所有键值
     * @param onCaptured 捕获到键值后的回调
     * @param onError 启动失败时的错误回调 (在主线程调用)
     * @param onStarted 成功启动后的回调 (在主线程调用)
     */
    fun startGeteventMonitor(
        enterCode: Int = 0,
        onCaptured: ((Int) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        onStarted: (() -> Unit)? = null
    ) {
        // v6.10: 防抖 — 距上次操作不足 400ms 时跳过，防止快速 start/stop 导致闪烁
        val now = System.currentTimeMillis()
        if (now - lastGeteventToggleTime < 400) {
            Log.d(TAG, "getevent 防抖: 距上次操作不足400ms, 跳过")
            onError?.invoke("请稍后再试")
            return
        }
        lastGeteventToggleTime = now

        if (!hasShizukuPermission()) {
            Log.w(TAG, "getevent 监听启动失败: Shizuku 权限未授予")
            onError?.invoke("Shizuku 权限未授予，无法启动底层监听")
            return
        }

        stopGeteventMonitor() // 确保之前的监听已停止

        // v6.10: 不在此处设置 _geteventActive=true!
        // 改为线程内确认进程启动成功后才标记，避免 "闪一下就灭"
        // 且旧线程清理不再能覆盖新线程的状态
        val myThread = Thread {
            var started = false // 标记是否已成功启动（设置了 _geteventActive=true）
            try {
                // 获取所有输入设备的 event 节点列表
                val eventDevices = getInputEventDevices()
                if (eventDevices.isEmpty()) {
                    Log.w(TAG, "getevent: 未找到 /dev/input/event* 设备")
                    postToMain { onError?.invoke("未找到输入设备节点") }
                    return@Thread // _geteventActive 仍为 false，UI 无闪烁
                }

                Log.i(TAG, "getevent 开始监听 ${eventDevices.size} 个输入设备 (enterCode=$enterCode)")
                KeyMappingLogger.logGeteventStatus(
                    active = true, detail = "监听 ${eventDevices.size} 个设备",
                    enterCode = enterCode,
                    deviceId = _currentDeviceId.value,
                    deviceName = KeyMappingLogger.deviceNameOverride ?: "设备#${_currentDeviceId.value}",
                )

                // 构建 getevent 命令: 监控所有 event 设备
                val cmd = arrayOf("getevent", "-l") + eventDevices.toTypedArray()

                val method = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                method.isAccessible = true
                val process = method.invoke(null, cmd, null, null) as? Process
                geteventProcess = process // v6.11: 保存引用以便 stop 可强制 destroy

                if (process == null) {
                    geteventProcess = null // v6.11: 进程创建失败，清除引用
                    Log.w(TAG, "getevent: Shizuku newProcess 返回 null")
                    KeyMappingLogger.logGeteventStatus(
                        active = false, detail = "Shizuku newProcess 返回 null",
                        enterCode = enterCode,
                        deviceId = _currentDeviceId.value,
                        deviceName = KeyMappingLogger.deviceNameOverride ?: "设备#${_currentDeviceId.value}",
                    )
                    postToMain { onError?.invoke("无法启动 getevent 进程，请检查 Shizuku 是否运行") }
                    return@Thread // _geteventActive 仍为 false，UI 无闪烁
                }

                // v6.10: 二次确认 — 此线程仍是当前活跃的监听线程
                // 如果 stopGeteventMonitor() 已在初始化期间被调用，此线程已被废弃
                if (geteventMonitorThread !== Thread.currentThread()) {
                    Log.d(TAG, "getevent: 线程已被废弃 (stop 在 init 期间调用)，放弃激活")
                    try { process.destroy() } catch (_: Exception) {}
                    postToMain { onError?.invoke("监听已被取消") }
                    return@Thread // _geteventActive 仍为 false，UI 无闪烁
                }

                // v6.10: 进程已成功创建，现在才标记为激活!
                // 此后 UI 才会显示 "底层监听已激活"
                _geteventActive.value = true
                started = true
                postToMain { onStarted?.invoke() } // v6.11: 通知 UI 监听已成功启动

                val reader = BufferedReader(InputStreamReader(process.inputStream))

                try {
                    while (_geteventActive.value) {
                        val line = reader.readLine() ?: break
                        val parsed = parseGeteventLine(line)
                        if (parsed != null) {
                            val (code, isDown) = parsed
                            if (isDown && code > 0) {
                                Log.i(TAG, "getevent 捕获键值: $code (raw=$line)")

                                // 如果是特定键值模式且不匹配, 跳过
                                if (enterCode > 0 && code != enterCode) {
                                    continue
                                }

                                KeyMappingLogger.logGeteventCapture(
                                    keyCode = code, rawLine = line,
                                    enterCodeMode = enterCode > 0,
                                    deviceId = _currentDeviceId.value,
                                    deviceName = KeyMappingLogger.deviceNameOverride ?: "设备#${_currentDeviceId.value}",
                                )

                                // 报告给学习系统 (使用 scanCode=code, 因为 getevent 给出的是原始扫描码)
                                onKeyDetected(code, code)
                                onCaptured?.invoke(code)

                                // 如果是指定键值模式, 捕获到了就停止
                                if (enterCode > 0) {
                                    stopGeteventMonitor()
                                }
                            }
                        }
                    }
                } finally {
                    // v6.10: 在 finally 中立即清理状态，防止旧线程的清理代码
                    // 把新线程刚设的 _geteventActive=true 拉回 false（竞态修复）
                    _geteventActive.value = false
                    geteventProcess = null // v6.11: 清除进程引用
                    try { process.destroy() } catch (_: Exception) {}
                    reader.close()
                }

                Log.i(TAG, "getevent 监听已结束")
                // _geteventActive 已在 finally 中置为 false，不在此处再次设置
            } catch (e: Exception) {
                Log.w(TAG, "getevent 监听异常: ${e.message}")
                if (started) {
                    _geteventActive.value = false
                }
                geteventProcess = null // v6.11: 异常时清除进程引用
                postToMain { onError?.invoke("监听异常: ${e.message?.take(40) ?: "未知错误"}") } // v6.11: 通知 UI
                KeyMappingLogger.logGeteventStatus(
                    active = false, detail = "异常: ${e.message}",
                    enterCode = enterCode,
                    deviceId = _currentDeviceId.value,
                    deviceName = KeyMappingLogger.deviceNameOverride ?: "设备#${_currentDeviceId.value}",
                )
            }
        }
        // v6.10: 在线程 start() 之前赋值，确保线程内所有权检查能正确通过
        myThread.isDaemon = true
        myThread.name = "getevent-monitor"
        geteventMonitorThread = myThread
        myThread.start()
    }

    fun stopGeteventMonitor() {
        _geteventActive.value = false
        // v6.11: 强制 destroy Shizuku 进程，解决 readLine() 忽略 interrupt 导致线程永不退出问题
        val proc = geteventProcess
        try { proc?.destroy() } catch (_: Exception) {}
        geteventProcess = null
        geteventMonitorThread?.interrupt()
        geteventMonitorThread = null
    }

    /**
     * 通过 Shizuku 获取 /dev/input/event* 设备列表
     */
    private fun getInputEventDevices(): List<String> {
        val devices = mutableListOf<String>()
        try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(
                null,
                arrayOf("sh", "-c", "ls /dev/input/event* 2>/dev/null"),
                null, null
            ) as? Process
            if (process == null) {
                Log.w(TAG, "getevent: Shizuku 无法创建设备枚举进程 (Binder 可能已断开)")
                return devices
            }

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            process.destroy()

            output.lines()
                .map { it.trim() }
                .filter { it.matches(Regex("/dev/input/event\\d+")) }
                .forEach { devices.add(it) }

            Log.d(TAG, "getevent 发现设备: $devices")
        } catch (e: Exception) {
            Log.w(TAG, "获取输入设备列表失败: ${e.message}", e)
        }
        return devices
    }

    /**
     * 解析 getevent -l 的一行输出, 提取 EV_KEY 事件的键值和动作
     *
     * getevent -l 输出格式示例:
     *   /dev/input/event5: EV_KEY       KEY_BACK            DOWN
     *   /dev/input/event5: EV_KEY       02f8                DOWN
     *   /dev/input/event5: EV_SYN       SYN_REPORT          00000000
     *
     * @return Pair(keyCode, isDown) 或 null (非 EV_KEY 或无法解析)
     */
    private fun parseGeteventLine(line: String): Pair<Int, Boolean>? {
        // 格式: "/dev/input/eventX: EV_KEY       <name/hex>            <action>"
        if (!line.contains("EV_KEY")) return null

        try {
            // 提取第三列: 键值名称或十六进制值
            // 使用正则: 冒号后, EV_KEY 后, 空白分隔, 取下一列
            val parts = line.substringAfter("EV_KEY").trim().split(Regex("\\s+"))
            if (parts.size < 2) return null

            val codeStr = parts[0]
            val action = parts[1]

            val isDown = action.equals("DOWN", ignoreCase = true)

            // 尝试解析键值
            val keyCode = try {
                // 十六进制: 如 02f8, 00c8
                if (codeStr.matches(Regex("[0-9a-fA-F]{4}"))) {
                    codeStr.toInt(16)
                } else {
                    // 命名键值 (如 KEY_BACK): 查字典
                    val name = if (codeStr.startsWith("KEY_")) codeStr.uppercase() else "KEYCODE_${codeStr.uppercase()}"
                    resolveAndroidKeyCodeName(name) ?: resolveAndroidKeyCodeName(codeStr.uppercase()) ?: 0
                }
            } catch (_: Exception) { 0 }

            if (keyCode > 0) {
                return Pair(keyCode, isDown)
            }

            // 如果字典查不到 (如 02f8=760), 尝试十六进制二次解析
            try {
                val fallback = codeStr.toInt(16)
                if (fallback > 0) return Pair(fallback, isDown)
            } catch (_: Exception) {}

        } catch (_: Exception) {}

        return null
    }

    // ============ 每设备持久化操作 v6.0 ============

    /**
     * 设置当前编辑中映射的源键值
     */
    fun setSourceKeyCode(code: Int) {
        val mappingId = _currentEditingMappingId.value
        if (mappingId > 0) {
            updateMappingSource(mappingId, code)
        }
    }

    /**
     * 设置当前编辑中映射的目标键值
     */
    fun setTargetKeyCode(code: Int) {
        val mappingId = _currentEditingMappingId.value
        if (mappingId > 0) {
            updateMappingTarget(mappingId, code)
        }
    }

    /**
     * 设置设备级启用锁定状态
     * 启用后映射激活且 UI 不可编辑
     */
    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        saveCurrentMapping()
    }

    fun saveLastDetected(code: Int) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putInt(KEY_LAST_DETECTED, code)?.apply()
    }

    fun getLastDetected(): Int =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getInt(KEY_LAST_DETECTED, -1) ?: -1

    // ============ 键值解析（合并自定义字典） ============

    /**
     * 解析用户输入的键值（支持十进制、十六进制、英文名、反射 KeyEvent）
     * 合并内置字典和自定义字典
     */
    fun parseKeyCode(input: String): Pair<Int, String> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Pair(-1, "")

        // 十六进制: 0x...
        val hexPattern = Regex("^0[xX]([0-9a-fA-F]+)$")
        hexPattern.find(trimmed)?.let { match ->
            val code = match.groupValues[1].toInt(16)
            val name = lookupCodeName(code)
            return Pair(code, if (name != null) "$name ($code)" else "十六进制键值 ($code)")
        }

        // 纯数字
        val numPattern = Regex("^(-?\\d+)$")
        numPattern.find(trimmed)?.let { match ->
            val code = match.groupValues[1].toInt()
            val name = lookupCodeName(code)
            return Pair(code, if (name != null) "$name ($code)" else "自定义键值 ($code)")
        }

        // 混合格式：包含数字优先提取
        val mixedNum = Regex("(\\d+)").find(trimmed)?.groupValues?.get(1)
        if (mixedNum != null) {
            val code = mixedNum.toInt()
            val name = lookupCodeName(code)
            return Pair(code, if (name != null) "$name ($code)" else "自定义键值 ($code)")
        }

        // 英文名查找（先查内置字典，再查自定义字典）
        val upper = trimmed.uppercase().replace(" ", "_")
        val candidates = listOf(upper, "KEYCODE_$upper", "KEY_$upper", "BTN_$upper")
        for (c in candidates) {
            // 先查内置
            allNamesToCode[c]?.let { code ->
                return Pair(code, "$c ($code)")
            }
            // 再查自定义
            customNamesToCode[c]?.let { code ->
                return Pair(code, "[自定义] $c ($code)")
            }
        }

        // 反射 KeyEvent 类
        try {
            val field = KeyEvent::class.java.getDeclaredField(upper)
            val code = field.getInt(null)
            return Pair(code, "$upper ($code)")
        } catch (_: Exception) {}

        return Pair(-1, "无法识别: $trimmed")
    }

    /**
     * 查找键值名称（合并内置+自定义字典）
     */
    fun lookupCodeName(code: Int): String? {
        // 先查内置
        allCodesToName[code]?.let { return it }
        // 再查自定义
        customCodesToName[code]?.let { return it }
        return null
    }

    /**
     * 判断键值是否在已知字典中（内置或自定义）
     */
    fun isKnownKeyCode(code: Int): Boolean {
        return allCodesToName.containsKey(code) || customCodesToName.containsKey(code)
    }

    // ============ 键值注入管道 ============

    /** v6.5: 注入指定目标键值 (含日志记录) */
    fun injectTargetKey(targetCode: Int = _targetKeyCode.value) {
        if (targetCode <= 0) return

        val method = _availableInjectionMethod.value
        val devId = _currentDeviceId.value
        val devName = KeyMappingLogger.deviceNameOverride ?: "设备#$devId"
        Log.d(TAG, "注入目标键值: $targetCode, 方法: ${method.label}")

        when (method) {
            InjectionMethod.SHIZUKU -> injectViaShizuku(targetCode, devId, devName)
            InjectionMethod.ACCESSIBILITY -> injectViaAccessibility(targetCode, devId, devName)
            InjectionMethod.SHELL -> injectViaShell(targetCode, devId, devName)
            InjectionMethod.NONE -> {
                KeyMappingLogger.logInjection(
                    targetKeyCode = targetCode, method = "不可用",
                    success = false, detail = "无可用注入方式",
                    deviceId = devId, deviceName = devName,
                )
                injectViaShell(targetCode, devId, devName)
            }
        }
    }

    private fun injectViaShizuku(target: Int, devId: Long, devName: String) {
        Thread {
            try {
                val method = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                method.isAccessible = true
                val process = method.invoke(
                    null,
                    arrayOf("input", "keyevent", target.toString()),
                    null,
                    null
                ) as? java.lang.Process

                if (process != null) {
                    process.waitFor()
                    val exitCode = process.exitValue()
                    if (exitCode == 0) {
                        Log.d(TAG, "Shizuku 反射注入成功: $target")
                        KeyMappingLogger.logInjection(
                            targetKeyCode = target, method = "Shizuku",
                            success = true, detail = "反射注入成功(exit=$exitCode)",
                            deviceId = devId, deviceName = devName,
                        )
                    } else {
                        Log.w(TAG, "Shizuku 注入失败(exit=$exitCode): $target, 回退到无障碍")
                        KeyMappingLogger.logInjection(
                            targetKeyCode = target, method = "Shizuku",
                            success = false, detail = "退出码=$exitCode, 回退到无障碍",
                            deviceId = devId, deviceName = devName,
                        )
                        injectViaAccessibility(target, devId, devName)
                    }
                    process.destroy()
                } else {
                    Log.w(TAG, "Shizuku newProcess 返回 null, 回退到无障碍")
                    KeyMappingLogger.logInjection(
                        targetKeyCode = target, method = "Shizuku",
                        success = false, detail = "newProcess返回null, 回退到无障碍",
                        deviceId = devId, deviceName = devName,
                    )
                    injectViaAccessibility(target, devId, devName)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Shizuku 反射注入异常: ${e.message}, 回退到无障碍")
                KeyMappingLogger.logInjection(
                    targetKeyCode = target, method = "Shizuku",
                    success = false, detail = "反射异常: ${e.message}, 回退到无障碍",
                    deviceId = devId, deviceName = devName,
                )
                injectViaAccessibility(target, devId, devName)
            }
        }.start()
    }

    private fun injectViaAccessibility(target: Int, devId: Long, devName: String) {
        val service = accessibilityService
        if (service == null) {
            KeyMappingLogger.logInjection(
                targetKeyCode = target, method = "无障碍服务",
                success = false, detail = "无障碍服务未连接, 回退到Shell",
                deviceId = devId, deviceName = devName,
            )
            injectViaShell(target, devId, devName)
            return
        }
        val globalAction = matchGlobalAction(target)
        if (globalAction >= 0) {
            val success = service.performGlobalAction(globalAction)
            Log.d(TAG, "无障碍 GlobalAction 注入($target → action=$globalAction): $success")
            KeyMappingLogger.logInjection(
                targetKeyCode = target, method = "无障碍服务(GlobalAction)",
                success = success, detail = "GlobalAction=$globalAction, 结果=$success",
                deviceId = devId, deviceName = devName,
            )
            if (success) return
        } else {
            KeyMappingLogger.logInjection(
                targetKeyCode = target, method = "无障碍服务",
                success = false, detail = "键值$target 不支持GlobalAction, 回退到Shell",
                deviceId = devId, deviceName = devName,
            )
        }
        injectViaShell(target, devId, devName)
    }

    private fun injectViaShell(target: Int, devId: Long, devName: String) {
        Thread {
            try {
                val pb = ProcessBuilder("input", "keyevent", target.toString())
                    .redirectErrorStream(true)
                val process = pb.start()
                process.waitFor()
                val exitCode = process.exitValue()
                if (exitCode == 0) {
                    Log.d(TAG, "Shell 注入成功: $target")
                    KeyMappingLogger.logInjection(
                        targetKeyCode = target, method = "Shell(input keyevent)",
                        success = true, detail = "Shell注入成功(exit=$exitCode)",
                        deviceId = devId, deviceName = devName,
                    )
                } else {
                    try {
                        val reader = BufferedReader(InputStreamReader(process.inputStream))
                        val error = reader.readText()
                        Log.w(TAG, "Shell 注入失败(exit=$exitCode): $error")
                        KeyMappingLogger.logInjection(
                            targetKeyCode = target, method = "Shell(input keyevent)",
                            success = false, detail = "退出码=$exitCode, 错误=$error",
                            deviceId = devId, deviceName = devName,
                        )
                    } catch (_: Exception) {
                        Log.w(TAG, "Shell 注入失败(exit=$exitCode): $target")
                        KeyMappingLogger.logInjection(
                            targetKeyCode = target, method = "Shell(input keyevent)",
                            success = false, detail = "退出码=$exitCode",
                            deviceId = devId, deviceName = devName,
                        )
                    }
                }
                process.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Shell 注入异常: ${e.message}")
                KeyMappingLogger.logInjection(
                    targetKeyCode = target, method = "Shell(input keyevent)",
                    success = false, detail = "异常: ${e.message}",
                    deviceId = devId, deviceName = devName,
                )
            }
        }.start()
    }

    fun matchGlobalAction(code: Int): Int {
        return when (code) {
            4 -> AccessibilityService.GLOBAL_ACTION_BACK
            3 -> AccessibilityService.GLOBAL_ACTION_HOME
            187 -> AccessibilityService.GLOBAL_ACTION_RECENTS
            83 -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            80 -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            else -> -1
        }
    }

    fun supportsGlobalAction(code: Int): Boolean = matchGlobalAction(code) >= 0

    // ============ 防抖 ============

    fun shouldDebounce(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < DEBOUNCE_MS) return true
        lastTriggerTime = now
        return false
    }

    // ============ 辅助工具 ============

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val service = "${context.packageName}/${KeyMappingService::class.java.name}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(service) || enabledServices.contains(KeyMappingService::class.java.name)
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    fun openDeveloperSettings(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    // ============ v6.4: Linux→Android 键值反向解析 (.kl 文件) ============

    /** .kl 文件常用搜索路径 */
    private val klSearchPaths = listOf(
        "/system/usr/keylayout/",
        "/vendor/usr/keylayout/",
        "/odm/usr/keylayout/",
        "/product/usr/keylayout/",
    )

    /**
     * 加载系统 .kl 文件，构建 Linux→Android 键值映射
     * 通过 Shizuku Shell 或直接文件读取
     */
    fun loadKeyLayoutMappings() {
        if (keyLayoutLoaded && linuxToAndroidMap.isNotEmpty()) return
        Thread {
            try {
                // 优先通过 Shizuku Shell 读取（可访问受保护的 /system 分区）
                if (hasShizukuPermission()) {
                    loadKlViaShizuku()
                }
                // 同时也尝试直接读取（可能部分路径可访问）
                loadKlDirect()
                keyLayoutLoaded = true
                Log.i(TAG, ".kl 文件解析完成: Linux→Android 映射 ${linuxToAndroidMap.size} 条")
            } catch (e: Exception) {
                Log.w(TAG, "加载 .kl 文件失败: ${e.message}")
                keyLayoutLoaded = true
            }
        }.start()
    }

    /**
     * 通过 Shizuku Shell 读取 .kl 文件
     */
    private fun loadKlViaShizuku() {
        for (path in klSearchPaths) {
            try {
                val method = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                method.isAccessible = true
                val process = method.invoke(
                    null,
                    arrayOf("sh", "-c", "cat ${path}*.kl 2>/dev/null"),
                    null, null
                ) as? Process ?: continue

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val content = reader.readText()
                process.waitFor()
                process.destroy()

                if (content.isNotBlank()) {
                    parseKlContent(content)
                }
            } catch (_: Exception) { /* 该路径不可读，继续下一个 */ }
        }
    }

    /**
     * 直接尝试读取 .kl 文件（适用于可公开访问的路径）
     */
    private fun loadKlDirect() {
        for (path in klSearchPaths) {
            try {
                val dir = java.io.File(path)
                if (!dir.exists() || !dir.isDirectory) continue
                dir.listFiles()?.filter { it.extension == "kl" }?.forEach { file ->
                    try {
                        parseKlContent(file.readText())
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) { /* 权限不足，继续 */ }
        }
    }

    /**
     * 解析 .kl 文件内容，提取 Linux→Android 键值映射
     * .kl 格式: key <linuxCode> <androidName> [flags...]
     * 例: key 760   F1
     */
    private fun parseKlContent(content: String) {
        val linePattern = Regex("""^\s*key\s+(\d+)\s+(\w+)\s*""", RegexOption.MULTILINE)
        linePattern.findAll(content).forEach { match ->
            val linuxCode = match.groupValues[1].toIntOrNull() ?: return@forEach
            val androidName = "KEYCODE_" + match.groupValues[2].uppercase()

            // 查找 Android 键值名称对应的数值
            val androidCode = resolveAndroidKeyCodeName(androidName)
                ?: resolveAndroidKeyCodeName(match.groupValues[2].uppercase())
                ?: return@forEach

            if (androidCode > 0 && linuxCode > 0 && androidCode != linuxCode) {
                linuxToAndroidMap[linuxCode] = androidCode
                androidToLinuxMap[androidCode] = linuxCode
            }
        }
    }

    /**
     * 将 Android 键值名称解析为数值
     * 例: "KEYCODE_F1" → 131
     */
    private fun resolveAndroidKeyCodeName(name: String): Int? {
        // 先查内置字典
        allNamesToCode[name]?.let { return it }
        // 反射 KeyEvent 类
        try {
            val field = KeyEvent::class.java.getDeclaredField(name)
            return field.getInt(null)
        } catch (_: Exception) {}
        return null
    }

    /**
     * v6.4: 将疑似 Linux 键值解析为对应的 Android 键值
     *
     * 工作流程:
     * 1. 先查运行时学习模式建立的 Linux→Android 映射
     * 2. 再查 .kl 文件解析的映射
     * 3. 都没找到则返回 null (该值可能是合法的 Android 键值或无法解析)
     *
     * @param suspectedLinuxCode 用户输入的键值（可能为 Linux 键值）
     * @return 对应的 Android KeyCode，解析失败返回 null
     */
    fun resolveLinuxKeyCode(suspectedLinuxCode: Int): ResolvedKeyCode? {
        // 已知的 Android 键值（≤284）直接返回，无需解析
        if (suspectedLinuxCode in 1..284 && isKnownKeyCode(suspectedLinuxCode)) return null

        // 1. 运行时学习模式建立的映射
        linuxToAndroidMap[suspectedLinuxCode]?.let { androidCode ->
            val name = lookupCodeName(androidCode) ?: "KEYCODE_UNKNOWN_$androidCode"
            return ResolvedKeyCode(androidCode, name, "学习模式", true)
        }

        // 2. 确保 .kl 文件已加载
        if (!keyLayoutLoaded) {
            loadKeyLayoutMappings()
            // 同步等待最多 3 秒
            var waited = 0
            while (!keyLayoutLoaded && waited < 3000) {
                Thread.sleep(100)
                waited += 100
            }
        }

        // 3. .kl 文件映射
        linuxToAndroidMap[suspectedLinuxCode]?.let { androidCode ->
            val name = lookupCodeName(androidCode) ?: "KEYCODE_UNKNOWN_$androidCode"
            return ResolvedKeyCode(androidCode, name, ".kl 文件", true)
        }

        return null
    }

    /**
     * v6.4: 解析结果数据类
     */
    data class ResolvedKeyCode(
        val androidCode: Int,
        val androidName: String,
        val source: String,
        val isLinuxCode: Boolean
    )

    // ============ 常用键值列表 ============

    data class KeyCodeOption(val code: Int, val label: String, val description: String)

    fun getCommonKeyCodes(): List<KeyCodeOption> {
        val builtin = listOf(
            KeyCodeOption(4, "返回", "KEYCODE_BACK"),
            KeyCodeOption(3, "主页", "KEYCODE_HOME"),
            KeyCodeOption(187, "最近任务", "KEYCODE_APP_SWITCH"),
            KeyCodeOption(24, "音量+", "KEYCODE_VOLUME_UP"),
            KeyCodeOption(25, "音量-", "KEYCODE_VOLUME_DOWN"),
            KeyCodeOption(27, "相机", "KEYCODE_CAMERA"),
            KeyCodeOption(84, "搜索", "KEYCODE_SEARCH"),
            KeyCodeOption(83, "通知栏", "KEYCODE_NOTIFICATION"),
            KeyCodeOption(85, "播放/暂停", "KEYCODE_MEDIA_PLAY_PAUSE"),
            KeyCodeOption(87, "下一曲", "KEYCODE_MEDIA_NEXT"),
            KeyCodeOption(88, "上一曲", "KEYCODE_MEDIA_PREVIOUS"),
            KeyCodeOption(91, "静音", "KEYCODE_MUTE"),
            KeyCodeOption(120, "截屏", "KEYCODE_SYSRQ"),
            KeyCodeOption(223, "锁屏", "KEYCODE_SLEEP"),
            KeyCodeOption(265, "手电筒", "KEYCODE_FLASHLIGHT"),
            KeyCodeOption(171, "分屏", "KEYCODE_WINDOW"),
            KeyCodeOption(82, "菜单", "KEYCODE_MENU"),
            KeyCodeOption(221, "亮度+", "KEYCODE_BRIGHTNESS_UP"),
            KeyCodeOption(220, "亮度-", "KEYCODE_BRIGHTNESS_DOWN"),
        )

        // v6.2: 合并自定义键值字典，用户录入的键值自动出现在常用列表中
        val builtinCodes = builtin.map { it.code }.toSet()
        val customEntries = customCodesToName.map { (code, name) ->
            KeyCodeOption(code, name, "自定义键值")
        }.filter { it.code !in builtinCodes }

        return builtin + customEntries
    }
}
