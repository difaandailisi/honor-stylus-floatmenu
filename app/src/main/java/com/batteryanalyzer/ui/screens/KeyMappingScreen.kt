package com.batteryanalyzer.ui.screens

import android.app.Activity
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.batteryanalyzer.LocalAppColors
import com.batteryanalyzer.keymap.KeyMappingLogger
import com.batteryanalyzer.keymap.KeyMappingManager
import com.batteryanalyzer.keymap.KeyMappingManager.InjectionMethod
import com.batteryanalyzer.keymap.KeyMappingManager.LearnTarget
import com.batteryanalyzer.model.DeviceInfo
import com.batteryanalyzer.model.DeviceKeyMapping
import com.batteryanalyzer.ui.theme.*
import com.batteryanalyzer.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v6.4: 每映射卡片的本地 UI 状态
 */
class MappingUIState(
    var sourceText: String = "",
    var sourceParsed: String = "",
    var sourceError: Boolean = false,
    var targetText: String = "",
    var targetParsed: String = "",
    var targetError: Boolean = false,
    // v6.4: Linux→Android 键值解析结果
    var sourceResolvedAndroid: KeyMappingManager.ResolvedKeyCode? = null,
    var sourceShowCorrection: Boolean = false
)

/**
 * 键值映射屏幕 v6.4 — 多映射卡片列表 + 右上角增加按钮
 *
 * v6.0 变更：
 * - 右上角增加 [＋] 按钮，每次点击新增一组源→目标键值映射
 * - 每个映射为独立卡片，可分别配置源键值和目标键值
 * - 每张卡片支持独立学习模式和删除操作
 * - 启用锁定为设备级开关，统一锁定所有映射卡片
 * - 无障碍服务遍历所有已启用映射规则匹配源键值
 *
 * v6.4 变更：
 * - 源键值输入区自动检测 Linux 键值(>284)并解析为对应 Android 键值
 * - 点击修正按钮可一键将 Linux 键值替换为 Android 键值
 * - scanCode 兜底匹配使手动输入的 Linux 键值也能正常工作
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyMappingScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val colors = LocalAppColors.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    // 初始化 KeyMappingManager
    LaunchedEffect(Unit) {
        KeyMappingManager.init(context)
    }

    // v5.1: 进入页面时自动扫描已连接的物理输入设备
    LaunchedEffect(Unit) {
        viewModel.refreshInputDevices()
    }

    // v6.3: 首次进入页面延迟刷新权限/服务状态（给无障碍/Shizuku 初始化留时间）
    LaunchedEffect(Unit) {
        delay(1000)
        KeyMappingManager.refreshPermissionsStatus(context)
    }

    // v6.3: 从系统设置返回后延迟 5 秒刷新权限/服务状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    delay(5_000) // 用户要求的 5 秒延迟
                    KeyMappingManager.refreshPermissionsStatus(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // v5.0: 使用含输入设备的完整设备列表
    val rawDevices by viewModel.allDevicesForKeyMapping.collectAsState()

    // v5.2: 过滤：BLE 设备全部显示，输入设备只显示已连接的
    val allMappingDevices = rawDevices.filter { device ->
        device.isBleDevice || device.isConnected
    }

    // KeyMapping 状态
    val kmDeviceId by KeyMappingManager.currentDeviceId.collectAsState()
    val enabled by KeyMappingManager.enabled.collectAsState()
    val mappingsList by KeyMappingManager.mappingsList.collectAsState()
    val learnModeActive by KeyMappingManager.learnModeActive.collectAsState()
    val learnTarget by KeyMappingManager.learnTarget.collectAsState()
    val lastDetectedKey by KeyMappingManager.lastDetectedKey.collectAsState()
    val injectionMethod by KeyMappingManager.availableInjectionMethod.collectAsState()
    val shizukuAvailable by KeyMappingManager.shizukuAvailable.collectAsState()
    val mappingLoaded by KeyMappingManager.mappingLoaded.collectAsState()
    val currentEditingId by KeyMappingManager.currentEditingMappingId.collectAsState()
    // v6.3: 改用 StateFlow 以支持无障碍服务启用后 UI 自动刷新
    val serviceRunning by KeyMappingManager.accessibilityServiceRunning.collectAsState()

    // v6.7: getevent 底层监听状态
    val geteventActive by KeyMappingManager.geteventActive.collectAsState()

    // 当前选中的设备信息
    val selectedDevice = allMappingDevices.find { it.id == kmDeviceId }

    // v6.0: 每映射卡片的本地 UI 状态 (mappingId → UI state)
    val mappingUIStates = remember { mutableStateMapOf<Long, MappingUIState>() }

    // v5.0: 设备选择下拉按钮状态
    var showDevicePicker by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    // 学习模式倒计时
    var learnCountdown by remember { mutableStateOf(0) }

    // 常用键值选择弹窗（支持填入原始键值或映射键值）
    var showCommonKeyPicker by remember { mutableStateOf(false) }
    var commonKeyPickerTarget by remember { mutableStateOf(LearnTarget.TARGET) }

    // 新键值录入对话框
    var showNewKeyDialog by remember { mutableStateOf(false) }
    var newKeyCode by remember { mutableStateOf(-1) }
    var newKeyForTarget by remember { mutableStateOf(LearnTarget.SOURCE) }

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }

    // 自动选中第一个设备
    LaunchedEffect(allMappingDevices) {
        if (kmDeviceId <= 0 && allMappingDevices.isNotEmpty()) {
            KeyMappingManager.switchToDevice(allMappingDevices.first().id)
        }
    }

    // v6.5: 设备选中后同步设备信息到日志系统 (供 KeyMappingService 使用)
    LaunchedEffect(selectedDevice) {
        selectedDevice?.let { dev ->
            KeyMappingLogger.deviceNameOverride = dev.displayName
            KeyMappingLogger.deviceTypeOverride = if (dev.isBleDevice) "BLE" else "INPUT"
            KeyMappingLogger.deviceIdentifierOverride = if (dev.isBleDevice) dev.macAddress
                else dev.modelName.ifBlank { "inputDevice:${dev.id}" }
        } ?: run {
            KeyMappingLogger.deviceNameOverride = null
            KeyMappingLogger.deviceTypeOverride = null
            KeyMappingLogger.deviceIdentifierOverride = null
        }
    }

    // v6.0: 映射列表加载后同步 UI 状态
    LaunchedEffect(mappingLoaded, mappingsList.size) {
        if (mappingLoaded) {
            // 为新出现的映射创建 UI 状态
            mappingsList.forEach { mapping ->
                if (!mappingUIStates.containsKey(mapping.id)) {
                    mappingUIStates[mapping.id] = MappingUIState(
                        sourceText = if (mapping.sourceKeyCode > 0) mapping.sourceKeyCode.toString() else "",
                        sourceParsed = if (mapping.sourceKeyCode > 0) {
                            val (_, display) = KeyMappingManager.parseKeyCode(mapping.sourceKeyCode.toString())
                            val linuxHint = if (mapping.sourceKeyCode > 284) "\n⚠ 可能为Linux键值，建议用学习模式" else ""
                            "✅ $display$linuxHint"
                        } else "",
                        targetText = if (mapping.targetKeyCode > 0) mapping.targetKeyCode.toString() else "",
                        targetParsed = if (mapping.targetKeyCode > 0) {
                            val (_, display) = KeyMappingManager.parseKeyCode(mapping.targetKeyCode.toString())
                            "✅ $display"
                        } else ""
                    )
                }
            }
            // 清理已删除的映射状态
            val existingIds = mappingsList.map { it.id }.toSet()
            mappingUIStates.keys.retainAll(existingIds)
        }
    }

    // 学习模式倒计时
    LaunchedEffect(learnModeActive) {
        if (learnModeActive) {
            learnCountdown = 15
            while (learnCountdown > 0 && learnModeActive) {
                delay(1000)
                learnCountdown--
            }
            if (learnCountdown <= 0 && learnModeActive) {
                // v6.5: 日志文件 — 记录学习超时
                selectedDevice?.let { dev ->
                    KeyMappingLogger.logLearnTimeout(
                        reason = "15秒超时，未检测到按键",
                        deviceId = dev.id, deviceName = dev.displayName,
                    )
                }
                KeyMappingManager.stopLearnMode()
                scope.launch {
                    snackbarHostState.showSnackbar("学习超时，未检测到按键")
                }
            }
        }
    }

    // v6.2: 检测到按键后自动填入 — 自动注册已在 onKeyDetected 中完成
    LaunchedEffect(lastDetectedKey) {
        if (lastDetectedKey > 0 && learnModeActive && currentEditingId > 0) {
            val keyStr = lastDetectedKey.toString()
            val (_, display) = KeyMappingManager.parseKeyCode(keyStr)

            val uiState = mappingUIStates[currentEditingId] ?: MappingUIState()
            when (learnTarget) {
                LearnTarget.SOURCE -> {
                    uiState.sourceText = keyStr
                    uiState.sourceParsed = "✅ 检测到: $display"
                    uiState.sourceError = false
                    KeyMappingManager.updateMappingSource(currentEditingId, lastDetectedKey)
                }
                LearnTarget.TARGET -> {
                    uiState.targetText = keyStr
                    uiState.targetParsed = "✅ 检测到: $display"
                    uiState.targetError = false
                    KeyMappingManager.updateMappingTarget(currentEditingId, lastDetectedKey)
                }
            }
            mappingUIStates[currentEditingId] = uiState
            KeyMappingManager.stopLearnMode()
        }
    }

    // 服务状态变化时刷新注入方法
    LaunchedEffect(serviceRunning) {
        KeyMappingManager.refreshInjectionMethodPublic()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("键值映射", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                },
                actions = {
                    // v6.0: 右上角增加按钮
                    IconButton(
                        onClick = {
                            if (selectedDevice != null) {
                                KeyMappingManager.addMapping()
                                scope.launch {
                                    snackbarHostState.showSnackbar("已新增映射规则")
                                }
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar("请先选择一个设备")
                                }
                            }
                        },
                        enabled = selectedDevice != null && !enabled,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "新增映射",
                            tint = if (selectedDevice != null && !enabled) scheme.primary
                                   else colors.textSecondary.copy(alpha = 0.4f)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(colors.background)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(8.dp))

            // ====== v5.0: 设备选择栏 ======
            DeviceSelectorBar(
                allDevices = allMappingDevices,
                selectedDeviceId = kmDeviceId,
                showPicker = showDevicePicker,
                isRefreshing = isRefreshing,
                onTogglePicker = { showDevicePicker = !showDevicePicker },
                onDismissPicker = { showDevicePicker = false },
                onDeviceSelected = { device ->
                    KeyMappingManager.switchToDevice(device.id)
                    showDevicePicker = false
                },
                onRefresh = {
                    isRefreshing = true
                    scope.launch {
                        viewModel.refreshInputDevices { msg ->
                            scope.launch { snackbarHostState.showSnackbar(msg) }
                        }
                        delay(500)
                        isRefreshing = false
                    }
                },
                colors = colors,
                scheme = scheme
            )

            Spacer(Modifier.height(12.dp))

            // 无设备时显示引导
            if (selectedDevice == null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.card)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Warning, null, tint = DischargingOrange, modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("无可用设备", fontWeight = FontWeight.Medium, color = colors.textPrimary)
                        Text(
                            "请先扫描蓝牙设备或点击刷新按钮查找键盘/鼠标等输入设备",
                            fontSize = 13.sp,
                            color = colors.textSecondary
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
                return@Column
            }

            // ====== 无障碍服务 / Shizuku 授权 / 注入方式状态 ======
            // 已全部统一迁移至「设置 → 权限与注入状态」，此处不再重复展示
            Text(
                "无障碍服务、Shizuku 授权、注入方式状态已统一移至「设置 → 权限与注入状态」。",
                fontSize = 12.sp, color = TextSecondary
            )

            Spacer(Modifier.height(16.dp))

            // ====== 启用锁定开关 (设备级) ======
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (enabled) ChargingGreen.copy(alpha = 0.12f) else colors.card
                ),
                border = if (enabled) CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(ChargingGreen.copy(alpha = 0.5f))
                ) else null
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (enabled) Icons.Default.Lock else Icons.Default.LockOpen,
                        null,
                        tint = if (enabled) ChargingGreen else colors.textSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (enabled) "映射已启用 · ${mappingsList.size} 条映射已锁定"
                            else "映射未启用 · 可自由编辑",
                            fontWeight = FontWeight.SemiBold,
                            color = if (enabled) ChargingGreen else colors.textPrimary
                        )
                        Text(
                            if (enabled) "关闭后可修改映射配置" else "配置完成后请启用映射并锁定",
                            fontSize = 12.sp,
                            color = colors.textSecondary
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = { newVal ->
                            val hasValidMapping = mappingsList.any { it.sourceKeyCode > 0 && it.targetKeyCode > 0 }
                            if (newVal && !hasValidMapping) {
                                scope.launch {
                                    snackbarHostState.showSnackbar("请至少配置一组有效的源键值和目标键值")
                                }
                            } else {
                                // v6.3: 使用 applyEnabled 同步更新 DB 与内存，确保无障碍服务正确拦截
                                KeyMappingManager.applyEnabled(newVal)
                                val msg = if (newVal) "映射已启用，所有配置已锁定" else "映射已关闭，可自由编辑"
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ChargingGreen
                        )
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ====== v6.0: 映射规则卡片列表 ======
            Text(
                "映射规则 (${mappingsList.size})",
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                color = scheme.primary
            )
            Spacer(Modifier.height(8.dp))

            if (mappingsList.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.AddCircleOutline,
                            null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "暂无映射规则",
                            fontWeight = FontWeight.Medium,
                            color = colors.textSecondary
                        )
                        Text(
                            "点击右上角 ＋ 按钮添加第一条映射",
                            fontSize = 13.sp,
                            color = colors.textSecondary.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                val isLocked = enabled

                mappingsList.forEachIndexed { index, mapping ->
                    val uiState = mappingUIStates.getOrPut(mapping.id) { MappingUIState() }
                    val hasValidMapping = mapping.sourceKeyCode > 0 && mapping.targetKeyCode > 0

                    MappingCard(
                        index = index + 1,
                        mapping = mapping,
                        uiState = uiState,
                        isLocked = isLocked,
                        learnModeActive = learnModeActive,
                        learnTarget = learnTarget,
                        currentEditingId = currentEditingId,
                        learnCountdown = learnCountdown,
                        onDelete = {
                            KeyMappingManager.deleteMapping(mapping.id)
                            mappingUIStates.remove(mapping.id)
                            scope.launch {
                                snackbarHostState.showSnackbar("映射规则 #${index + 1} 已删除")
                            }
                        },
                        onSourceTextChange = { text ->
                            uiState.sourceText = text
                            uiState.sourceResolvedAndroid = null
                            uiState.sourceShowCorrection = false
                            if (text.isNotBlank()) {
                                val (code, display) = KeyMappingManager.parseKeyCode(text)
                                if (code > 0) {
                                    // v6.5: 日志文件 — 手动输入源键值
                                    selectedDevice?.let { dev ->
                                        KeyMappingLogger.logManualInput(
                                            inputText = text, parsedCode = code,
                                            parsedName = KeyMappingManager.lookupCodeName(code) ?: "UNKNOWN_$code",
                                            isSource = true, mappingId = mapping.id,
                                            deviceId = dev.id, deviceName = dev.displayName,
                                            deviceType = if (dev.isBleDevice) "BLE" else "INPUT",
                                            deviceIdentifier = if (dev.isBleDevice) dev.macAddress
                                                else dev.modelName.ifBlank { "inputDevice:${dev.id}" },
                                        )
                                    }
                                    // v6.4: 键值 > 284 尝试解析为 Linux 键值
                                    if (code > 284) {
                                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                            val resolved = KeyMappingManager.resolveLinuxKeyCode(code)
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                uiState.sourceResolvedAndroid = resolved
                                                if (resolved != null) {
                                                    uiState.sourceParsed = "✅ $display\n⚠ Linux键值 → Android: ${resolved.androidName}(${resolved.androidCode}) [${resolved.source}]"
                                                    uiState.sourceShowCorrection = true
                                                } else {
                                                    uiState.sourceParsed = "✅ $display\n⚠ 可能为Linux键值（非Android），建议点📡用学习模式捕获"
                                                }
                                                uiState.sourceError = false
                                                mappingUIStates[mapping.id] = uiState
                                            }
                                        }
                                    } else {
                                        uiState.sourceParsed = "✅ $display"
                                        uiState.sourceError = false
                                    }
                                    if (!isLocked) {
                                        KeyMappingManager.updateMappingSource(mapping.id, code)
                                    }
                                } else {
                                    uiState.sourceParsed = "❌ $display"
                                    uiState.sourceError = true
                                }
                            } else {
                                uiState.sourceParsed = ""
                                uiState.sourceError = false
                            }
                            mappingUIStates[mapping.id] = uiState
                        },
                        onTargetTextChange = { text ->
                            uiState.targetText = text
                            if (text.isNotBlank()) {
                                val (code, display) = KeyMappingManager.parseKeyCode(text)
                                // v6.5: 日志文件 — 手动输入目标键值
                                selectedDevice?.let { dev ->
                                    KeyMappingLogger.logManualInput(
                                        inputText = text, parsedCode = code,
                                        parsedName = KeyMappingManager.lookupCodeName(code) ?: "UNKNOWN_$code",
                                        isSource = false, mappingId = mapping.id,
                                        deviceId = dev.id, deviceName = dev.displayName,
                                        deviceType = if (dev.isBleDevice) "BLE" else "INPUT",
                                        deviceIdentifier = if (dev.isBleDevice) dev.macAddress
                                            else dev.modelName.ifBlank { "inputDevice:${dev.id}" },
                                    )
                                }
                                uiState.targetParsed = if (code > 0) "✅ $display" else "❌ $display"
                                uiState.targetError = code <= 0
                                if (code > 0 && !isLocked) {
                                    // v6.2: updateMappingTarget 内部自动注册新键值，无需弹窗
                                    KeyMappingManager.updateMappingTarget(mapping.id, code)
                                }
                            } else {
                                uiState.targetParsed = ""
                                uiState.targetError = false
                            }
                            mappingUIStates[mapping.id] = uiState
                        },
                        onStartLearnSource = {
                            if (!isLocked) {
                                KeyMappingManager.startLearnMode(LearnTarget.SOURCE, mapping.id)
                            }
                        },
                        onStartLearnTarget = {
                            if (!isLocked) {
                                KeyMappingManager.startLearnMode(LearnTarget.TARGET, mapping.id)
                            }
                        },
                        onStopLearn = { KeyMappingManager.stopLearnMode() },
                        // v6.7: getevent 底层监听 (v6.11: 添加成功/失败双向反馈)
                        onToggleGetevent = {
                            if (KeyMappingManager.geteventActive.value) {
                                KeyMappingManager.stopGeteventMonitor()
                                scope.launch {
                                    snackbarHostState.showSnackbar("底层监听已关闭")
                                }
                            } else {
                                scope.launch {
                                    snackbarHostState.showSnackbar("正在启动底层监听...")
                                }
                                KeyMappingManager.startGeteventMonitor(
                                    onError = { msg ->
                                        scope.launch {
                                            snackbarHostState.showSnackbar("底层监听: $msg")
                                        }
                                    },
                                    onStarted = {
                                        scope.launch {
                                            snackbarHostState.showSnackbar("底层监听已激活，按手写笔键即可捕获")
                                        }
                                    }
                                )
                            }
                        },
                        geteventActive = geteventActive,
                        onClearSource = {
                            uiState.sourceText = ""
                            uiState.sourceParsed = ""
                            uiState.sourceError = false
                            mappingUIStates[mapping.id] = uiState
                            KeyMappingManager.updateMappingSource(mapping.id, -1)
                        },
                        onClearTarget = {
                            uiState.targetText = ""
                            uiState.targetParsed = ""
                            uiState.targetError = false
                            mappingUIStates[mapping.id] = uiState
                            KeyMappingManager.updateMappingTarget(mapping.id, -1)
                        },
                        snackbarHostState = snackbarHostState,
                        scope = scope,
                        colors = colors,
                        scheme = scheme
                    )

                    Spacer(Modifier.height(12.dp))
                }
            }

            // 常用键值选择器
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { showCommonKeyPicker = !showCommonKeyPicker },
                modifier = Modifier.fillMaxWidth(),
                enabled = !enabled && mappingsList.isNotEmpty()
            ) {
                Icon(
                    if (showCommonKeyPicker) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    null
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (showCommonKeyPicker) "收起常用键值"
                    else "从常用键值中选择（填入${if (commonKeyPickerTarget == LearnTarget.SOURCE) "原始键值" else "映射键值"}）"
                )
            }

            if (showCommonKeyPicker) {
                Card(
                    modifier = Modifier.fillMaxWidth().animateContentSize(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.surface)
                ) {
                    Column(Modifier.padding(8.dp)) {
                        // v6.7: 填入目标选择切换 — 原始键值还是映射键值
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(scheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            listOf(LearnTarget.SOURCE to "填入原始键值", LearnTarget.TARGET to "填入映射键值").forEach { (target, label) ->
                                val isSelected = commonKeyPickerTarget == target
                                TextButton(
                                    onClick = { commonKeyPickerTarget = target },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(6.dp),
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = if (isSelected) scheme.onPrimary else scheme.onSurfaceVariant,
                                        containerColor = if (isSelected) scheme.primary else Color.Transparent
                                    ),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(label, fontSize = 12.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        val options = KeyMappingManager.getCommonKeyCodes()
                        // v6.1: 若当前无编辑中的映射，使用列表中第一条
                        val effectiveEditingId = if (currentEditingId > 0) currentEditingId
                            else mappingsList.firstOrNull()?.id ?: 0L
                        options.forEachIndexed { idx, option ->
                            val supportsGA = KeyMappingManager.supportsGlobalAction(option.code)
                            Row(
                                Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = !enabled && effectiveEditingId > 0) {
                                        // v6.7: 根据 commonKeyPickerTarget 填入原始键值或映射键值
                                        val uiState = mappingUIStates[effectiveEditingId]
                                        if (uiState != null) {
                                            when (commonKeyPickerTarget) {
                                                LearnTarget.SOURCE -> {
                                                    uiState.sourceText = option.code.toString()
                                                    val (_, d) = KeyMappingManager.parseKeyCode(option.code.toString())
                                                    uiState.sourceParsed = "✅ $d"
                                                    uiState.sourceError = false
                                                    KeyMappingManager.updateMappingSource(effectiveEditingId, option.code)
                                                }
                                                LearnTarget.TARGET -> {
                                                    uiState.targetText = option.code.toString()
                                                    val (_, d) = KeyMappingManager.parseKeyCode(option.code.toString())
                                                    uiState.targetParsed = "✅ $d"
                                                    uiState.targetError = false
                                                    KeyMappingManager.updateMappingTarget(effectiveEditingId, option.code)
                                                }
                                            }
                                            mappingUIStates[effectiveEditingId] = uiState
                                        }
                                        showCommonKeyPicker = false
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(option.label, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${option.code} · ${if (supportsGA) "无障碍可用" else "需Shizuku/ADB"}",
                                        fontSize = 11.sp,
                                        color = if (supportsGA) ChargingGreen else colors.textSecondary
                                    )
                                }
                                Text(
                                    "${option.code}",
                                    color = scheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 16.sp
                                )
                            }
                            if (idx < options.size - 1) {
                                HorizontalDivider(
                                    color = colors.textSecondary.copy(alpha = 0.15f),
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ====== 使用提示 ======
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant.copy(alpha = 0.3f))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("使用提示", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = scheme.primary)
                    Spacer(Modifier.height(6.dp))
                    Text("· 点击右上角 ＋ 可为一个设备添加多条映射规则", fontSize = 12.sp, color = TextSecondary)
                    Text("· 每条规则独立配置：按下源键 → 替换为目标键", fontSize = 12.sp, color = TextSecondary)
                    Text("· 点击「学习」按钮后按下对应按键即可自动识别键值（推荐）", fontSize = 12.sp, color = TextSecondary)
                    Text("· 也可手动输入：数字(334)、十六进制(0x14e)、英文名(BACK)", fontSize = 12.sp, color = TextSecondary)
                    Text("· 源键值手动输入 >284 时会自动检测是否为 Linux 键值并提供一键修正", fontSize = 12.sp, color = TextSecondary)
                    Text("· 确认后开启「锁定开关」防止误触修改", fontSize = 12.sp, color = TextSecondary)
                    Text("· 无障碍服务仅支持返回/主页/最近任务等有限键值", fontSize = 12.sp, color = TextSecondary)
                    Text("· 完整键值注入需要 Shizuku 或 ADB 权限", fontSize = 12.sp, color = TextSecondary)
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // ====== 新键值录入对话框 ======
    if (showNewKeyDialog && newKeyCode > 0 && currentEditingId > 0) {
        NewKeyCodeDialogV6(
            keyCode = newKeyCode,
            forTarget = newKeyForTarget,
            onConfirm = { code, name, desc ->
                KeyMappingManager.registerCustomKeyCode(code, name, desc) { _ ->
                    when (newKeyForTarget) {
                        LearnTarget.SOURCE -> {
                            KeyMappingManager.updateMappingSource(currentEditingId, code)
                            val uiState = mappingUIStates[currentEditingId]
                            if (uiState != null) {
                                uiState.sourceParsed = "✅ [$name] ($code)"
                                uiState.sourceError = false
                                mappingUIStates[currentEditingId] = uiState
                            }
                        }
                        LearnTarget.TARGET -> {
                            KeyMappingManager.updateMappingTarget(currentEditingId, code)
                            val uiState = mappingUIStates[currentEditingId]
                            if (uiState != null) {
                                uiState.targetParsed = "✅ [$name] ($code)"
                                uiState.targetError = false
                                mappingUIStates[currentEditingId] = uiState
                            }
                        }
                    }
                    scope.launch {
                        snackbarHostState.showSnackbar("新键值「$name」($code) 已录入字典")
                    }
                }
                showNewKeyDialog = false
            },
            onDismiss = {
                when (newKeyForTarget) {
                    LearnTarget.SOURCE -> {
                        KeyMappingManager.updateMappingSource(currentEditingId, newKeyCode)
                        val uiState = mappingUIStates[currentEditingId]
                        if (uiState != null) {
                            uiState.sourceParsed = "✅ 自定义键值 ($newKeyCode)"
                            uiState.sourceError = false
                            mappingUIStates[currentEditingId] = uiState
                        }
                    }
                    LearnTarget.TARGET -> {
                        KeyMappingManager.updateMappingTarget(currentEditingId, newKeyCode)
                        val uiState = mappingUIStates[currentEditingId]
                        if (uiState != null) {
                            uiState.targetParsed = "✅ 自定义键值 ($newKeyCode)"
                            uiState.targetError = false
                            mappingUIStates[currentEditingId] = uiState
                        }
                    }
                }
                showNewKeyDialog = false
            }
        )
    }
}

// ====== v6.0: 单条映射卡片 ======

@Composable
private fun MappingCard(
    index: Int,
    mapping: DeviceKeyMapping,
    uiState: MappingUIState,
    isLocked: Boolean,
    learnModeActive: Boolean,
    learnTarget: LearnTarget,
    currentEditingId: Long,
    learnCountdown: Int,
    onDelete: () -> Unit,
    onSourceTextChange: (String) -> Unit,
    onTargetTextChange: (String) -> Unit,
    onStartLearnSource: () -> Unit,
    onStartLearnTarget: () -> Unit,
    onStopLearn: () -> Unit,
    // v6.7: getevent 底层监听
    onToggleGetevent: () -> Unit,
    geteventActive: Boolean,
    onClearSource: () -> Unit,
    onClearTarget: () -> Unit,
    snackbarHostState: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope,
    colors: AppThemeColors,
    scheme: androidx.compose.material3.ColorScheme
) {
    val isEditing = currentEditingId == mapping.id
    val isSourceLearning = learnModeActive && learnTarget == LearnTarget.SOURCE && isEditing
    val isTargetLearning = learnModeActive && learnTarget == LearnTarget.TARGET && isEditing
    val isAnyLearning = learnModeActive

    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEditing) scheme.primaryContainer.copy(alpha = 0.3f) else colors.card
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isEditing) 3.dp else 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // 顶部：编号 + 箭头分隔线 + 删除按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 编号标签
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = scheme.primary.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "映射 #$index",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                // 箭头指示
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = ChargingGreen.copy(alpha = 0.15f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (mapping.sourceKeyCode > 0) KeyMappingManager.lookupCodeName(mapping.sourceKeyCode) ?: "${mapping.sourceKeyCode}"
                            else "?",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (mapping.sourceKeyCode > 0) ChargingGreen else colors.textSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "→",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = ChargingGreen,
                            modifier = Modifier.padding(horizontal = 2.dp)
                        )
                        Text(
                            if (mapping.targetKeyCode > 0) KeyMappingManager.lookupCodeName(mapping.targetKeyCode) ?: "${mapping.targetKeyCode}"
                            else "?",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (mapping.targetKeyCode > 0) PrimaryBlue else colors.textSecondary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // 删除按钮
                if (!isLocked) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "删除此映射",
                            tint = LowBatteryRed.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ———— 源键值输入 ————
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "原始键值",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isLocked) colors.textSecondary else colors.textPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = uiState.sourceText,
                        onValueChange = onSourceTextChange,
                        modifier = Modifier.fillMaxWidth().alpha(if (isLocked) 0.5f else 1f),
                        enabled = !isLocked && !(isAnyLearning && !isSourceLearning),
                        label = { Text("输入或学习键值", fontSize = 12.sp) },
                        placeholder = { Text("例: 334 / 0x14e / BACK", fontSize = 12.sp) },
                        isError = uiState.sourceError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        supportingText = if (uiState.sourceParsed.isNotEmpty()) {
                            { Text(uiState.sourceParsed, color = if (uiState.sourceError) scheme.error else {
                                if (isLocked) ChargingGreen else scheme.onSurfaceVariant
                            }, fontSize = 11.sp) }
                        } else null,
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                // 操作按钮列：学习 + 删除
                Column(
                    modifier = Modifier.padding(top = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // v6.1: 学习按钮 - 增大尺寸，带文字标签
                    FilledTonalButton(
                        onClick = { onStartLearnSource() },
                        enabled = !isLocked && !isAnyLearning,
                        modifier = Modifier.height(42.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isSourceLearning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = scheme.primary
                            )
                        } else {
                            Icon(Icons.Default.TouchApp, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("学习", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    // v6.1: 删除/清除按钮
                    OutlinedButton(
                        onClick = { onClearSource() },
                        enabled = !isLocked && uiState.sourceText.isNotBlank(),
                        modifier = Modifier.height(42.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = LowBatteryRed.copy(alpha = if (!isLocked && uiState.sourceText.isNotBlank()) 0.8f else 0.3f)
                        )
                    ) {
                        Icon(Icons.Default.Backspace, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("清除", fontSize = 11.sp)
                    }
                }
            }

            if (isSourceLearning) {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "正在学习源键... (${learnCountdown}s)",
                            fontSize = 12.sp,
                            color = scheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = onStopLearn,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("取消", fontSize = 12.sp, color = scheme.error)
                        }
                    }
                    // v6.7: getevent 底层监听按钮 — 捕获被系统拦截的键值 (如 760)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (geteventActive) "🔬 底层监听已激活 (可捕获760等被拦截键值)"
                            else "⚠ 如需捕获被系统拦截的键值，请开启底层监听",
                            fontSize = 10.sp,
                            color = if (geteventActive) ChargingGreen else DischargingOrange,
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(
                            onClick = onToggleGetevent,
                            modifier = Modifier.height(28.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                if (geteventActive) "关闭" else "底层监听",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // v6.4: Linux→Android 键值自动修正按钮
            if (uiState.sourceShowCorrection && uiState.sourceResolvedAndroid != null && !isLocked) {
                val resolved = uiState.sourceResolvedAndroid!!
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DischargingOrange.copy(alpha = 0.1f))
                        .clickable {
                            // v6.5: 日志文件 — 手动修正 Linux→Android 键值 (MappingCard内无selectedDevice，使用全局覆盖值)
                            val devId = KeyMappingManager.currentDeviceId.value
                            KeyMappingLogger.logManualCorrection(
                                linuxCode = mapping.sourceKeyCode,
                                androidCode = resolved.androidCode,
                                androidName = resolved.androidName,
                                correctionSource = resolved.source,
                                deviceId = devId,
                                deviceName = KeyMappingLogger.deviceNameOverride ?: "设备#$devId",
                            )
                            // 点击自动将 Linux 键值替换为 Android 键值
                            onClearSource()
                            val androidCode = resolved.androidCode
                            val codeStr = androidCode.toString()
                            val (_, display) = KeyMappingManager.parseKeyCode(codeStr)
                            uiState.sourceText = codeStr
                            uiState.sourceParsed = "✅ $display"
                            uiState.sourceError = false
                            uiState.sourceResolvedAndroid = null
                            uiState.sourceShowCorrection = false
                            KeyMappingManager.updateMappingSource(mapping.id, androidCode)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    "已自动修正: Linux键值 → ${resolved.androidName}(${resolved.androidCode})"
                                )
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        null,
                        tint = DischargingOrange,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "点击修正: → ${resolved.androidName}(${resolved.androidCode})",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DischargingOrange,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Default.ArrowForward,
                        null,
                        tint = ChargingGreen,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ———— 目标键值输入 ————
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "目标键值",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isLocked) colors.textSecondary else colors.textPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = uiState.targetText,
                        onValueChange = onTargetTextChange,
                        modifier = Modifier.fillMaxWidth().alpha(if (isLocked) 0.5f else 1f),
                        enabled = !isLocked && !(isAnyLearning && !isTargetLearning),
                        label = { Text("替换成什么按键", fontSize = 12.sp) },
                        placeholder = { Text("例: 4 / HOME / 音量", fontSize = 12.sp) },
                        isError = uiState.targetError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        supportingText = if (uiState.targetParsed.isNotEmpty()) {
                            { Text(uiState.targetParsed, color = if (uiState.targetError) scheme.error else {
                                if (isLocked) ChargingGreen else scheme.onSurfaceVariant
                            }, fontSize = 11.sp) }
                        } else null,
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                // 操作按钮列：学习 + 删除
                Column(
                    modifier = Modifier.padding(top = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // v6.1: 学习按钮 - 增大尺寸，带文字标签
                    FilledTonalButton(
                        onClick = { onStartLearnTarget() },
                        enabled = !isLocked && !isAnyLearning,
                        modifier = Modifier.height(42.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isTargetLearning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = scheme.primary
                            )
                        } else {
                            Icon(Icons.Default.TouchApp, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("学习", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    // v6.1: 删除/清除按钮
                    OutlinedButton(
                        onClick = { onClearTarget() },
                        enabled = !isLocked && uiState.targetText.isNotBlank(),
                        modifier = Modifier.height(42.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = LowBatteryRed.copy(alpha = if (!isLocked && uiState.targetText.isNotBlank()) 0.8f else 0.3f)
                        )
                    ) {
                        Icon(Icons.Default.Backspace, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("清除", fontSize = 11.sp)
                    }
                }
            }

            if (isTargetLearning) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "正在学习目标键... (${learnCountdown}s)",
                        fontSize = 12.sp,
                        color = scheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = onStopLearn,
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("取消", fontSize = 12.sp, color = scheme.error)
                    }
                }
            }
        }
    }
}

// ====== v5.0: 设备选择栏 ======

@Composable
private fun DeviceSelectorBar(
    allDevices: List<DeviceInfo>,
    selectedDeviceId: Long,
    showPicker: Boolean,
    isRefreshing: Boolean,
    onTogglePicker: () -> Unit,
    onDismissPicker: () -> Unit,
    onDeviceSelected: (DeviceInfo) -> Unit,
    onRefresh: () -> Unit,
    colors: AppThemeColors,
    scheme: androidx.compose.material3.ColorScheme
) {
    val selectedDevice = allDevices.find { it.id == selectedDeviceId }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTogglePicker() }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (selectedDevice?.isInputDevice == true) Icons.Default.Keyboard
                    else Icons.Default.Bluetooth,
                    null,
                    tint = if (selectedDevice != null) PrimaryBlue else colors.textSecondary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))

                Column(Modifier.weight(1f)) {
                    if (selectedDevice != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                selectedDevice.displayName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (selectedDevice.isBleDevice) PrimaryBlue.copy(alpha = 0.15f)
                                       else DischargingOrange.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    if (selectedDevice.isBleDevice) "BLE" else "输入",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedDevice.isBleDevice) PrimaryBlue else DischargingOrange,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            if (selectedDevice.isBleDevice) "MAC: ${selectedDevice.macAddress}"
                            else selectedDevice.modelName.ifBlank { "输入设备" },
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.textSecondary.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text("选择设备", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = colors.textSecondary)
                        Text("点击选择或刷新设备", fontSize = 12.sp, color = colors.textSecondary)
                    }
                }

                Spacer(Modifier.width(8.dp))

                Icon(
                    if (showPicker) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    "切换设备",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(Modifier.width(8.dp))

                IconButton(
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    modifier = Modifier.size(36.dp)
                ) {
                    if (isRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = PrimaryBlue
                        )
                    } else {
                        Icon(Icons.Default.Refresh, "刷新设备列表", tint = PrimaryBlue, modifier = Modifier.size(22.dp))
                    }
                }
            }

            if (showPicker) {
                HorizontalDivider(color = colors.textSecondary.copy(alpha = 0.1f))

                if (allDevices.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("暂无设备，请连接键盘/鼠标/蓝牙设备后点击刷新", fontSize = 13.sp, color = colors.textSecondary)
                    }
                } else {
                    allDevices.forEachIndexed { idx, device ->
                        val isSelected = device.id == selectedDeviceId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .then(
                                    if (isSelected) Modifier.background(scheme.primary.copy(alpha = 0.08f))
                                    else Modifier
                                )
                                .clickable { onDeviceSelected(device) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (device.isInputDevice) Icons.Default.Keyboard else Icons.Default.Bluetooth,
                                null,
                                tint = if (isSelected) PrimaryBlue else colors.textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        device.displayName,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 14.sp,
                                        color = if (isSelected) scheme.primary else colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (device.isBleDevice) PrimaryBlue.copy(alpha = 0.1f)
                                               else DischargingOrange.copy(alpha = 0.1f)
                                    ) {
                                        Text(
                                            if (device.isBleDevice) "BLE" else "输入",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (device.isBleDevice) PrimaryBlue else DischargingOrange,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Text(
                                    if (device.isBleDevice) device.macAddress
                                    else device.modelName.ifBlank { "输入设备" },
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.textSecondary.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isSelected) {
                                Icon(Icons.Default.Check, null, tint = PrimaryBlue, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (idx < allDevices.size - 1) {
                            HorizontalDivider(
                                color = colors.textSecondary.copy(alpha = 0.06f),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }

                Text(
                    "💡 已连接的键盘/鼠标/2.4G/磁吸设备会自动检测  蓝牙设备需通过主页面扫描添加",
                    fontSize = 11.sp,
                    color = colors.textSecondary.copy(alpha = 0.5f),
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

// ====== Shizuku 权限卡片 ======

/**
 * Shizuku 权限卡片。
 * 已提升为 public：授权入口统一收敛到「设置 → Shizuku 授权」，
 * 键值映射与华为手写笔模拟共用同一张卡片、同一份授权。
 */
@Composable
fun ShizukuPermissionCard(
    shizukuAvailable: Boolean,
    onRequestPermission: () -> Unit,
    onDownload: () -> Unit,
    colors: AppThemeColors
) {
    val scheme = MaterialTheme.colorScheme
    // v6.3: 改用 StateFlow 观察，权限授予后 UI 自动刷新
    val hasPermission by KeyMappingManager.shizukuPermissionGranted.collectAsState()

    val statusColor = when {
        hasPermission -> ChargingGreen
        shizukuAvailable -> DischargingOrange
        else -> colors.textSecondary
    }
    val statusText = when {
        hasPermission -> "Shizuku 已授权"
        shizukuAvailable -> "Shizuku 已安装但未授权"
        else -> "Shizuku 未安装"
    }
    val subtitleText = when {
        hasPermission -> "支持所有键值的完整注入"
        shizukuAvailable -> "点击下方按钮请求权限"
        else -> "下载安装 Shizuku 以支持完整键值注入"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.1f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Usb, null, tint = statusColor, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(statusText, fontWeight = FontWeight.SemiBold, color = statusColor)
                    Text(subtitleText, fontSize = 12.sp, color = colors.textSecondary)
                }
                if (hasPermission) {
                    Icon(Icons.Default.CheckCircle, null, tint = ChargingGreen, modifier = Modifier.size(20.dp))
                }
            }
            if (!hasPermission) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shizukuAvailable) {
                        Button(
                            onClick = onRequestPermission,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = scheme.primary)
                        ) {
                            Icon(Icons.Default.Security, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("请求 Shizuku 权限", fontSize = 13.sp)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onDownload,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("下载 Shizuku", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// ====== v6.0: 注入方式卡片（显示活跃映射数）======

@Composable
/** 注入方式状态卡片。已提升为 public：统一在「设置 → 权限与注入状态」展示。 */
fun InjectionMethodCardV6(
    method: InjectionMethod,
    activeMappingCount: Int
) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalAppColors.current

    val statusColor = when (method) {
        InjectionMethod.SHIZUKU -> ChargingGreen
        InjectionMethod.ACCESSIBILITY -> DischargingOrange
        InjectionMethod.SHELL -> PrimaryBlue
        InjectionMethod.NONE -> LowBatteryRed
    }
    val statusIcon = when (method) {
        InjectionMethod.SHIZUKU -> Icons.Default.Usb
        InjectionMethod.ACCESSIBILITY -> Icons.Default.AccessibilityNew
        InjectionMethod.SHELL -> Icons.Default.Terminal
        InjectionMethod.NONE -> Icons.Default.Warning
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = statusColor.copy(alpha = 0.1f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(statusIcon, null, tint = statusColor, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("当前注入方式: ${method.label}", fontWeight = FontWeight.SemiBold, color = statusColor)
                    Text(
                        if (activeMappingCount > 0) "${method.description} · 活跃映射: $activeMappingCount 条"
                        else method.description,
                        fontSize = 12.sp,
                        color = colors.textSecondary
                    )
                }
            }
            when (method) {
                InjectionMethod.NONE -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "请先授权 Shizuku 或连接 ADB 以启用键值注入",
                        fontSize = 12.sp,
                        color = LowBatteryRed
                    )
                }
                else -> { }
            }
        }
    }
}

// ====== v6.0: 新键值录入对话框 ======

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewKeyCodeDialogV6(
    keyCode: Int,
    forTarget: LearnTarget,
    onConfirm: (code: Int, name: String, description: String) -> Unit,
    onDismiss: () -> Unit
) {
    val targetLabel = when (forTarget) {
        LearnTarget.SOURCE -> "原始键值"
        LearnTarget.TARGET -> "目标键值"
    }
    var nameInput by remember { mutableStateOf("") }
    var descInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.NewLabel, null, tint = PrimaryBlue) },
        title = { Text("发现新键值") },
        text = {
            Column {
                Text(
                    "检测到未知的$targetLabel: $keyCode\n\n该键值不在系统字典中，请为其命名以便下次识别。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text("键值名称 (如 SEMICOLON)") },
                    placeholder = { Text("KEYCODE_CUSTOM_$keyCode") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = descInput,
                    onValueChange = { descInput = it },
                    label = { Text("备注 (可选)") },
                    placeholder = { Text("简要描述此键值的用途") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val name = nameInput.trim().ifBlank { "CUSTOM_$keyCode" }
                onConfirm(keyCode, name, descInput.trim())
            }) { Text("录入字典") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("跳过") }
        }
    )
}

// ====== 复用组件 StepCard ======

@Composable
/** 通用步骤卡片。已提升为 public：统一在「设置 → 权限与注入状态」展示。 */
fun StepCard(
    completed: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    actionText: String?,
    onAction: (() -> Unit)?
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (completed) scheme.primaryContainer.copy(alpha = 0.5f)
                             else scheme.errorContainer.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
            if (onAction != null && actionText != null) {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.onErrorContainer)
                ) {
                    Text(actionText, fontSize = 14.sp)
                }
            }
        }
    }
}
