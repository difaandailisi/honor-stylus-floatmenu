package com.batteryanalyzer.ui.screens

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batteryanalyzer.LocalAppColors
import com.batteryanalyzer.keymap.KeyMappingManager
import com.batteryanalyzer.ui.theme.*
import com.batteryanalyzer.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 设置页面 - 提醒阈值、悬浮窗开关、桌面小组件指南、关于
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val floatingEnabled by viewModel.floatingWindowEnabled.collectAsState()

    // ===== 权限与注入状态（键值映射 + 华为手写笔模拟 共用的唯一入口） =====
    val shizukuAvailable by KeyMappingManager.shizukuAvailable.collectAsState()
    val serviceRunning by KeyMappingManager.accessibilityServiceRunning.collectAsState()
    val injectionMethod by KeyMappingManager.availableInjectionMethod.collectAsState()
    val mappingsList by KeyMappingManager.mappingsList.collectAsState()
    val activity = context.asActivity()

    // 进入设置页时刷新一次无障碍/注入状态，避免使用过期缓存
    LaunchedEffect(Unit) { KeyMappingManager.refreshPermissionsStatus(context) }

    // v8: 数据备份与恢复（仅手写笔，SAF 文件；无需存储权限，跨重装可用）
    val allDevices by viewModel.allDevices.collectAsState()
    val penCount = allDevices.count { it.isHandwritingPen }
    var backupMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val json = viewModel.exportBackup()
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(json.toByteArray(Charsets.UTF_8))
                }
                backupMsg = "备份成功（${penCount} 个手写笔）"
            } catch (e: Exception) {
                backupMsg = "备份失败：${e.message}"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                val n = viewModel.importBackup(json)
                backupMsg = if (n > 0) "已恢复 $n 个手写笔设备" else "未找到可恢复的手写笔"
            } catch (e: Exception) {
                backupMsg = "恢复失败：${e.message}"
            }
        }
    }

    LaunchedEffect(backupMsg) {
        backupMsg?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            backupMsg = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryBlue,
                    titleContentColor = androidx.compose.ui.graphics.Color.White,
                    navigationIconContentColor = androidx.compose.ui.graphics.Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ===== 设备阈值设置 (v3.0: 每设备独立) =====
            Text("设备阈值设置", fontWeight = FontWeight.Bold, fontSize = 15.sp,
                color = PrimaryBlue, modifier = Modifier.padding(bottom = 8.dp))
            Text("为每个设备独立配置充电/耗电提醒阈值，各设备提醒互不干扰",
                fontSize = 12.sp, color = colors.textSecondary, modifier = Modifier.padding(bottom = 8.dp))

            // v6.1: 阈值设置只对 BLE 蓝牙设备有效，屏蔽虚拟输入设备
            val rawDevices by viewModel.allDevices.collectAsState()
            val allDevices = rawDevices.filter { it.isBleDevice }
            if (allDevices.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = colors.card),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Devices, null, tint = colors.textSecondary, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("暂无可配置设备", fontSize = 14.sp, color = colors.textSecondary)
                        Text("请先在主页面连接蓝牙设备", fontSize = 12.sp, color = colors.textSecondary)
                    }
                }
            } else {
                allDevices.forEach { device ->
                    DeviceThresholdCard(viewModel, device, colors)
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ===== v5.1: 控制中心 =====
            ControlCenterSection(viewModel, colors, context)

            Spacer(modifier = Modifier.height(16.dp))

            // ===== 悬浮窗设置 =====
            Text("显示设置", fontWeight = FontWeight.Bold, fontSize = 15.sp,
                color = PrimaryBlue, modifier = Modifier.padding(bottom = 8.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = colors.card),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.OpenInNew, null, tint = PrimaryBlue)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("电量悬浮窗", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("在屏幕右上角显示实时电量", fontSize = 12.sp, color = colors.textSecondary)
                        }
                        Switch(
                            checked = floatingEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && !viewModel.hasOverlayPermission()) {
                                    Toast.makeText(context, "需要「悬浮窗权限」，请在弹出页面中授权", Toast.LENGTH_LONG).show()
                                    viewModel.openOverlayPermissionSettings()
                                } else {
                                    viewModel.toggleFloatingWindow(enabled)
                                }
                            }
                        )
                    }
                    if (!viewModel.hasOverlayPermission()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { viewModel.openOverlayPermissionSettings() }) {
                            Text("前往开启悬浮窗权限", color = DischargingOrange, fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 桌面小组件
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.card),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Widgets, null, tint = PrimaryBlue)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("桌面小组件", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "长按桌面空白处 → 小组件 → 找到以下两种小组件：\n\n" +
                        "📱 「蓝牙电量卡片 (单设备)」\n" +
                        "   添加时可选择要显示的蓝牙设备\n" +
                        "   显示一台设备的完整电量信息（电量%、状态、阈值）\n\n" +
                        "📊 「蓝牙电量卡片 (多设备)」\n" +
                        "   同时显示最多5台已保存设备的实时电量\n" +
                        "   适合拥有多台蓝牙设备（笔+耳机+手表等）的用户\n\n" +
                        "深色/浅色主题自动切换  |  点击小组件跳转到应用",
                        fontSize = 13.sp, color = colors.textSecondary, lineHeight = 20.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ===== 分享与反馈 =====
            Card(
                colors = CardDefaults.cardColors(containerColor = colors.card),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Share, null, tint = PrimaryBlue)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("分享应用", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "将本应用分享给朋友，一起使用蓝牙电量分析",
                        fontSize = 13.sp, color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "蓝牙电量分析 - BT Battery Analyzer")
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "推荐一款蓝牙电量分析工具！\n\n" +
                                    "✨ 功能亮点：\n" +
                                    "• 支持手写笔/蓝牙耳机/手表等多种设备\n" +
                                    "• 实时电量监控 + 桌面小组件 + 悬浮窗\n" +
                                    "• 充放电分析 + 电池健康度评估\n" +
                                    "• 多维度时间曲线 (分钟/天/月/年)\n" +
                                    "• 多通道电量支持 (左耳/右耳/充电仓)\n\n" +
                                    "📱 适用于安卓 8.0+\n" +
                                    "🔗 Google Play / 应用商店搜索「蓝牙电量分析」"
                                )
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "分享应用"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("分享给朋友")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ===== 权限与注入状态（键值映射 + 华为手写笔模拟 共用的唯一入口） =====
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = PrimaryBlue.copy(alpha = if (colors.isDark) 0.12f else 0.05f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null,
                            tint = PrimaryBlue, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("权限与注入状态", fontWeight = FontWeight.Bold,
                            fontSize = 16.sp, color = PrimaryBlue)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "「键值映射」与「华为手写笔模拟」所需的权限与运行状态统一在这里管理。",
                        fontSize = 12.sp, color = colors.textSecondary
                    )

                    // ---- 无障碍服务 ----
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("无障碍服务", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        color = colors.textPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    StepCard(
                        completed = serviceRunning,
                        icon = Icons.Default.AccessibilityNew,
                        iconTint = if (serviceRunning) ChargingGreen else DischargingOrange,
                        title = if (serviceRunning) "无障碍服务已开启" else "无障碍服务未开启",
                        subtitle = if (serviceRunning) "按键拦截功能就绪"
                        else "需开启后才能拦截手写笔按键",
                        actionText = if (serviceRunning) null else "去开启",
                        onAction = if (serviceRunning) null else {
                            { KeyMappingManager.openAccessibilitySettings(context) }
                        }
                    )

                    // ---- Shizuku 授权 ----
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("Shizuku 授权", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        color = colors.textPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "两处功能都需要 adb 级权限（注入按键 / 写 Settings.Global）。" +
                            "普通 App 无法自行获取，需经 Shizuku 授权：手机点一下即可，无需连电脑。" +
                            "两处共用同一份授权，只需授权一次。",
                        fontSize = 12.sp, color = colors.textSecondary, lineHeight = 17.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ShizukuPermissionCard(
                        shizukuAvailable = shizukuAvailable,
                        onRequestPermission = {
                            activity?.let { KeyMappingManager.requestShizukuPermission(it) }
                        },
                        onDownload = { KeyMappingManager.openShizukuDownload(context) },
                        colors = colors
                    )

                    // ---- 注入方式状态 ----
                    Spacer(modifier = Modifier.height(14.dp))
                    Text("注入方式状态", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        color = colors.textPrimary)
                    Spacer(modifier = Modifier.height(6.dp))
                    InjectionMethodCardV6(
                        method = injectionMethod,
                        activeMappingCount = mappingsList.count {
                            it.isEnabled && it.sourceKeyCode > 0 && it.targetKeyCode > 0
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 关于
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = PrimaryBlue.copy(alpha = if (colors.isDark) 0.12f else 0.05f)
                ),
                // 移除阴影保持界面视觉干净统一
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BatteryFull, contentDescription = null,
                            modifier = Modifier.size(48.dp), tint = PrimaryBlue)
                        Spacer(modifier = Modifier.height(8.dp))
                        Column {
                            Text("蓝牙电量分析", fontWeight = FontWeight.Bold,
                                fontSize = 20.sp, color = PrimaryBlue)
                            Text("v2.5.1", fontSize = 14.sp, color = colors.textSecondary)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("作者：爱吃丝绒蛋糕", fontSize = 12.sp, color = colors.textSecondary)
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = PrimaryBlue.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("功能介绍", fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp, modifier = Modifier.fillMaxWidth(),
                        color = colors.textPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    FeatureItem("🔍", "BLE 蓝牙扫描与连接", "支持手写笔、蓝牙耳机、手表等多种蓝牙设备识别")
                    FeatureItem("📊", "实时电量监控", "通过蓝牙 GATT 读取电量百分比，订阅通知实时更新")
                    FeatureItem("🔋", "智能充放电分析", "自动识别充电/耗电会话，记录曲线和统计")
                    FeatureItem("❤️", "电池健康度评估", "基于实际充入容量与理论容量对比评估衰减")
                    FeatureItem("📈", "数据可视化", "电量曲线图、环形电量环、周期统计报表")
                    FeatureItem("🔔", "充电/低电量提醒", "全局提醒阈值设置，通知栏 + 悬浮窗双通道提醒")
                    FeatureItem("🔄", "多设备切换", "保存多个设备自由切换，各自独立统计数据")
                    FeatureItem("🧩", "桌面小组件", "深色/浅色主题，实时显示选中设备电量")
                    FeatureItem("💬", "电量悬浮窗", "屏幕右上角悬浮显示，长按关闭")
                    FeatureItem("📱", "后台实时监控", "前台服务持续监听，记录完整数据")
                    FeatureItem("⌨️", "键值映射", "手写笔/蓝牙键盘按键拦截与重映射（所需权限见上方「权限与注入状态」）")
                    FeatureItem("✍️", "华为手写笔模拟", "荣耀体系悬浮菜单模拟，三种模式：A 假装有笔 / B 真笔跟随磁吸 / C 强制悬浮菜单")
                    FeatureItem("🔬", "系统读数交叉校验", "读取荣耀系统 honor_bt_pencil_* 键值，与自研 BLE 解析结果比对，发现协议解析偏差")
                    FeatureItem("♻️", "恢复原始状态", "一键清除 CONNECT / 状态机 / 型号等键值残留，并支持解除蓝牙配对")

                    Spacer(modifier = Modifier.height(16.dp))

                    // ===== 更新日志 =====
                    HorizontalDivider(color = PrimaryBlue.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("📋 更新日志", fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp, modifier = Modifier.fillMaxWidth(),
                        color = colors.textPrimary)
                    Spacer(modifier = Modifier.height(8.dp))

                    ChangelogItem("v2.5.1 (2026-11)", listOf(
                        "✨ 手写笔模拟页顶部新增「悬浮菜单是否开启」实时状态检测（显示中 / 未显示 / 本机不支持）",
                        "✨ 新增数据备份与恢复：手写笔容量、备注与原始电量记录可导出为文件，重装或误删后一键恢复",
                        "✨ 新增「手写笔」标记开关，备份、滚动压缩、非破坏取消配对均以此为范围",
                        "✨ 电量记录 3 个月滚动压缩，压缩前保存健康度快照，历史数据不再无限膨胀",
                        "🐛 修复停止 / 彻底恢复后假笔状态仍残留：清理前先等待模拟任务真正结束，避免旧的 conn=2 写入晚于清理落盘",
                        "🐛 修复 A 模式与 C 模式来回切换后 conn 被错误还原、假笔状态互相污染",
                        "🔧 清完残留键值后二次回读，被系统笔服务重写回「有笔」的键自动补写中性值",
                        "🔧 所有键值写入改为「写后回读校验」，清理结果如实提示实际清理数量与未生效的键",
                        "🔧 移除设置页「赞赏支持」模块及微信收款码图片"
                    ))

                    Spacer(modifier = Modifier.height(8.dp))

                    ChangelogItem("v2.5.0 (2026-07)", listOf(
                        "✨ 新增「华为手写笔模拟」底栏页面：A 假装有笔 / B 真笔跟随 / C 强制悬浮菜单 三种模式",
                        "✨ A 假装有笔：写入 connection_state=2 让系统认定有笔，完全不需要笔也能出悬浮菜单",
                        "✨ B 真笔跟随：磁吸充电隐藏菜单、取下显示；判定改用 App 自身 BLE 连接，杜绝假笔状态自污染",
                        "✨ C 强制悬浮菜单：只驱动菜单状态机，不写任何笔身份键，零残留（实测 conn=0 时无效并明确提示）",
                        "✨ 恢复原始状态：一键清除 CONNECT/状态机/型号等键值残留，并支持解除蓝牙配对",
                        "✨ 系统读数交叉校验：读取荣耀 honor_bt_pencil_* 与 BLE 解析值比对，仪表盘展示分歧",
                        "✨ 硬件身份识别：VID/PID 白名单自动判定荣耀手写笔并补全型号（CD52 / Magic-Pencil 3）",
                        "✨ 设备画像补全：用系统型号与硬件 VID/PID 自动填充型号与手写笔标记",
                        "🔧 无障碍服务、Shizuku 授权、注入方式状态统一收纳到「设置 → 权限与注入状态」",
                        "🔧 键值映射与手写笔模拟页不再重复展示权限/注入状态，全部改到设置页统一管理",
                        "🔧 停止逻辑改为还原启动前快照，三个模式行为一致"
                    ))

                    Spacer(modifier = Modifier.height(8.dp))

                    ChangelogItem("v2.3.0 (2026-05)", listOf(
                        "✨ 键值映射支持非蓝牙输入设备（键盘/鼠标/2.4G/手柄等）",
                        "✨ 键值映射页面顶部新增设备选择栏 + 刷新按钮，一键扫描输入设备",
                        "✨ 每设备独立键值映射：BLE设备按MAC区分，输入设备按名称区分",
                        "✨ 输入设备自动通过 InputManager API 发现，无需蓝牙配对",
                        "✨ 输入设备不支持修改备注，仅以原始设备名为标识",
                        "🔧 DeviceInfo 新增 deviceType 字段，数据库迁移 v4→v5"
                    ))

                    Spacer(modifier = Modifier.height(8.dp))

                    ChangelogItem("v2.2.1 (2026-05)", listOf(
                        "🐛 修复设备名称回归：连接后\"未知设备\"无法自动更新为真实名称",
                        "🐛 修复键值映射文字消失：切换设备或退后台后配置文字丢失",
                        "✨ 键值映射改用全局设备切换按钮，移除页面内设备选择卡片",
                        "✨ Shizuku 权限请求独立卡片，移至无障碍服务下方，一键请求授权",
                        "🎨 移除圆球编号标题 UI，替换为简洁文本标题，整体视觉优化",
                        "🎨 当前启用映射状态面板置于顶部醒目位置展示",
                        "🔧 v4.1 新增 resolvedDeviceName 流，连接后自动修正设备名称"
                    ))

                    Spacer(modifier = Modifier.height(8.dp))

                    ChangelogItem("v2.2.0 (2026-05)", listOf(
                        "✨ 键值映射每设备独立配置：支持以MAC地址/备注/名称区分设备",
                        "✨ 双「自动学习」按钮：原始键值和目标键值可分别独立学习",
                        "✨ 动态键值字典：未知键值自动弹窗录入数据库，支持英文标点/数字/符号",
                        "✨ 启用锁定开关：确认启用后防止误触修改映射配置",
                        "✨ 设备备注同步：设置中修改设备备注后，键值映射页实时更新",
                        "🔧 内置键值字典扩展至200+：覆盖英文标点符号、数字键、功能键等",
                        "🔧 优化Shizuku 13.x API兼容性（反射调用newProcess）"
                    ))

                    Spacer(modifier = Modifier.height(8.dp))

                    ChangelogItem("v2.1.0 (2026-01)", listOf(
                        "✨ 新增键值映射功能：手写笔硬件按键拦截与重映射",
                        "✨ 三层注入管道：Shizuku / 无障碍服务 GlobalAction / Shell 自动回退",
                        "✨ 自动学习模式：按下手写笔按钮自动识别键值",
                        "✨ 常用键值选择器：内置19个常用键值一键选取",
                        "🔧 优化数据库查询：聚合缓存减少重复全表扫描"
                    ))

                    Spacer(modifier = Modifier.height(8.dp))

                    ChangelogItem("v2.0.0 (2025-12)", listOf(
                        "✨ 多通道电量支持：左耳/右耳/充电仓独立显示",
                        "✨ 短时功耗分析：分钟级功耗曲线与统计",
                        "✨ 长时电量分析：完整充放电循环识别与健康度评估",
                        "✨ 时间维度聚合：分钟/天/月/年级别曲线切换",
                        "🔧 每设备独立提醒阈值：充电/低电量阈值按设备分别设置"
                    ))

                    ChangelogItem("v1.x (2025)", listOf(
                        "✨ 初始版本：BLE蓝牙扫描、设备连接、电量监控",
                        "✨ 设备备注名、序列号自动获取、容量智能检测",
                        "✨ 桌面小组件、电量悬浮窗、通知提醒",
                        "🐛 修复连接状态同步、自动重连等问题"
                    ))

                    Spacer(modifier = Modifier.height(16.dp))

                    // v8: 数据备份与恢复（仅手写笔，SAF 文件，跨重装可用）
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = colors.card),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("💾 数据备份与恢复",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp, color = colors.textPrimary)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "仅对手写笔生效。可将电池容量、备注与原始电量记录导出为文件（存到下载目录等），" +
                                "重装 App 或误删设备后通过该文件恢复。系统/软件取消配对不会丢失数据，无需备份。",
                                fontSize = 11.sp, color = colors.textSecondary, lineHeight = 16.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { exportLauncher.launch("battery_pen_backup.json") }) {
                                    Text("导出备份 ($penCount)")
                                }
                                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                                    Text("导入恢复")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = DischargingOrange.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // 后台保活权限指引 — 带可操作按钮
                    Text(
                        "🔒 确保后台可靠运行",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = DischargingOrange,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "以下权限可保障实时监控和通知不中断，请逐项授权：",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // 权限操作卡片列表
                    PermissionActionCard(
                        icon = Icons.Default.BatteryFull,
                        title = "关闭电池优化",
                        description = "防止系统在后台自动清理应用，是后台保活最关键的权限",
                        buttonText = "前往设置",
                        buttonColor = DischargingOrange,
                        onClick = { viewModel.requestBatteryOptimizationExemption() }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    PermissionActionCard(
                        icon = Icons.Default.PlayArrow,
                        title = "自启动管理",
                        description = "允许应用在开机或系统事件后自动启动后台服务",
                        buttonText = "打开自启动",
                        buttonColor = PrimaryBlue,
                        onClick = { viewModel.openAutoStartSettings() }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    PermissionActionCard(
                        icon = Icons.Default.Link,
                        title = "关联启动权限",
                        description = "允许蓝牙服务被其他系统组件唤醒，确保连接稳定",
                        buttonText = "打开权限管理",
                        buttonColor = PrimaryBlue,
                        onClick = { viewModel.openAppSystemSettings() }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    PermissionActionCard(
                        icon = Icons.Default.Lock,
                        title = "锁定到后台",
                        description = "打开最近任务 → 找到本应用 → 下拉或点击锁图标锁定",
                        buttonText = "查看指引",
                        buttonColor = colors.textSecondary,
                        onClick = {
                            Toast.makeText(context, "请打开最近任务 → 找到本应用 → 下拉/点击锁定", Toast.LENGTH_LONG).show()
                        }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "⚠️ 不同手机品牌设置路径略有差异（小米/华为/OPPO/vivo等），" +
                        "部分品牌需在「安全中心」或「手机管家」中额外设置。\n" +
                        "自启动按钮会尝试匹配当前设备厂商的专属入口，如无法匹配将打开通用应用详情页。",
                        fontSize = 10.sp,
                        color = colors.textSecondary.copy(alpha = 0.7f),
                        lineHeight = 15.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = PrimaryBlue.copy(alpha = 0.15f))
                    Spacer(modifier = Modifier.height(12.dp))

                    // 免责声明
                    Text(
                        "⚠️ 免责声明",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = colors.textPrimary,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "【数据准确性】\n" +
                        "• 本应用通过蓝牙 GATT / HID 协议读取设备电量信息，数据精确度完全取决于设备制造商的实现方式\n" +
                        "• 部分设备可能不支持标准电池服务（BAS），本应用会尝试通过 HID Report 解析，但无法保证所有设备均能获取到准确电量\n" +
                        "• 电池健康度评估为基于充放电数据的估算参考值，非专业测量结果，仅供参考\n\n" +
                        "【权限说明】\n" +
                        "• 需要蓝牙连接权限（BLUETOOTH_CONNECT）用于与设备通信\n" +
                        "• Android 11 及以下需要位置/定位权限用于扫描附近蓝牙设备\n" +
                        "• 后台监控需要前台服务权限（FOREGROUND_SERVICE）\n" +
                        "• 悬浮窗需要「显示在其他应用上层」系统权限\n\n" +
                        "【使用风险】\n" +
                        "• 本软件仅供学习研究和个人使用，请勿用于任何商业用途\n" +
                        "• 使用本软件造成的任何直接或间接损失，作者不承担法律责任\n" +
                        "• 本软件为免费开源项目，不提供商业级技术支持保障\n" +
                        "• 用户应自行承担因使用本应用而产生的设备兼容性问题",
                        fontSize = 10.sp,
                        color = colors.textSecondary,
                        lineHeight = 15.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 版权信息
                    Text(
                        "© 2026 蓝牙电量分析 - 作者：爱吃丝绒蛋糕",
                        fontSize = 10.sp,
                        color = colors.textSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun FeatureItem(emoji: String, title: String, desc: String) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(emoji, fontSize = 14.sp, modifier = Modifier.width(24.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = colors.textPrimary)
            Text(desc, fontSize = 11.sp, color = colors.textSecondary, lineHeight = 16.sp)
        }
    }
}

/** 从 Context 向上找到 Activity（用于发起 Shizuku 权限请求） */
private fun android.content.Context.asActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.asActivity()
    else -> null
}

@Composable
fun ChangelogItem(version: String, items: List<String>) {
    val colors = LocalAppColors.current
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(version, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            color = PrimaryBlue)
        items.forEach { item ->
            Text(item, fontSize = 11.sp, color = colors.textSecondary,
                lineHeight = 15.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
        }
    }
}

// ============ v5.1: 控制中心区域 ============

/**
 * 控制中心 - 键值映射快捷开关及设备选择
 */
@Composable
fun ControlCenterSection(
    viewModel: MainViewModel,
    colors: com.batteryanalyzer.ui.theme.AppThemeColors,
    context: android.content.Context
) {
    val keyMappingEnabled by viewModel.keyMappingEnabled.collectAsState()
    val keyMappingDeviceId by viewModel.keyMappingDeviceId.collectAsState()
    val allDevicesForKeyMapping by viewModel.allDevicesForKeyMapping.collectAsState()

    var showDeviceDropdown by remember { mutableStateOf(false) }
    val selectedDevice = allDevicesForKeyMapping.find { it.id == keyMappingDeviceId }

    Text("控制中心", fontWeight = FontWeight.Bold, fontSize = 15.sp,
        color = PrimaryBlue, modifier = Modifier.padding(bottom = 8.dp))

    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 键值映射开关
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Keyboard, null, tint = PrimaryBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("键值映射", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        if (keyMappingEnabled) "已启用 - 按键映射功能工作中"
                        else "已停用 - 点击开关启用",
                        fontSize = 12.sp, color = colors.textSecondary
                    )
                }
                Switch(
                    checked = keyMappingEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.toggleKeyMapping(enabled)
                        Toast.makeText(
                            context,
                            "键值映射已${if (enabled) "启用" else "停用"}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = colors.textSecondary.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.height(12.dp))

            // 设备选择
            Text("映射目标设备", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                color = colors.textPrimary, modifier = Modifier.padding(bottom = 8.dp))

            if (allDevicesForKeyMapping.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DevicesOther, null, tint = colors.textSecondary.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("暂无可选设备 - 请先在主页面连接蓝牙设备",
                        fontSize = 13.sp, color = colors.textSecondary)
                }
            } else {
                // 当前选中的设备显示
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showDeviceDropdown = !showDeviceDropdown }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = if (selectedDevice?.isInputDevice == true) "⌨️" else "🔵"
                    Text(icon, fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            selectedDevice?.displayName ?: "未选择设备",
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp,
                            color = colors.textPrimary
                        )
                        Text(
                            if (selectedDevice != null) {
                                if (selectedDevice!!.isBleDevice) "BLE设备"
                                else "输入设备"
                            } else "点击选择",
                            fontSize = 11.sp, color = colors.textSecondary
                        )
                    }
                    Icon(
                        if (showDeviceDropdown) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // 设备下拉列表
                if (showDeviceDropdown) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = colors.background,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .padding(4.dp)
                    ) {
                        allDevicesForKeyMapping.forEach { device ->
                            val isSelected = device.id == keyMappingDeviceId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setKeyMappingDeviceId(device.id)
                                        showDeviceDropdown = false
                                    }
                                    .background(
                                        if (isSelected) PrimaryBlue.copy(alpha = 0.08f)
                                        else androidx.compose.ui.graphics.Color.Transparent,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val devIcon = if (device.isInputDevice) "⌨️" else "🔵"
                                Text(devIcon, fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    device.displayName,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (isSelected) PrimaryBlue else colors.textPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    if (device.isInputDevice) "输入" else "BLE",
                                    fontSize = 10.sp,
                                    color = colors.textSecondary.copy(alpha = 0.6f)
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(Icons.Default.Check, null, tint = PrimaryBlue,
                                        modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                // 提示说明
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "选择设备后，启用键值映射开关。此时手写笔按键将被映射为自定义功能。\n" +
                    "可在「键值映射」页面配置具体映射规则。",
                    fontSize = 11.sp,
                    color = colors.textSecondary.copy(alpha = 0.7f),
                    lineHeight = 15.sp
                )
            }
        }
    }
}

/**
 * 每设备阈值设置卡片 — 充电和耗电提醒阈值独立配置
 * v4.0: 显示 MAC 地址、序列号，支持备注名编辑
 */
@Composable
fun DeviceThresholdCard(
    viewModel: MainViewModel,
    device: com.batteryanalyzer.model.DeviceInfo,
    colors: AppThemeColors
) {
    val deviceId = device.id
    val chargeFlow = remember(deviceId) { viewModel.getDeviceChargeAlertLevel(deviceId) }
    val lowFlow = remember(deviceId) { viewModel.getDeviceLowBatteryAlertLevel(deviceId) }
    val chargeLevel by chargeFlow.collectAsState(initial = 85)
    val lowLevel by lowFlow.collectAsState(initial = 20)

    var expanded by remember { mutableStateOf(false) }
    var remarkText by remember(device.remark) { mutableStateOf(device.remark) }
    var showRemarkSaved by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = colors.card),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 设备标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val icon = when {
                    device.isInputDevice -> "⌨️"
                    device.name.contains("Pen", true) || device.name.contains("笔", true) -> "✏️"
                    device.name.contains("Buds", true) || device.name.contains("耳机", true) -> "🎧"
                    else -> "📱"
                }
                Text(icon, fontSize = 16.sp)
                Spacer(modifier = Modifier.width(6.dp))
                // 显示名称：优先备注名
                Text(device.displayName, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = colors.textPrimary, modifier = Modifier.weight(1f))
                // 当前阈值摘要
                Text("充≥$chargeLevel%  耗≤$lowLevel%",
                    fontSize = 11.sp, color = colors.textSecondary)
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(onClick = { expanded = !expanded }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = PrimaryBlue
                    )
                }
            }

            // MAC 地址 / 设备类型信息
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (device.isBleDevice) {
                    Text("MAC: ${device.macAddress}",
                        fontSize = 9.sp, color = colors.textSecondary.copy(alpha = 0.7f),
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.weight(1f))
                    if (device.serialNumber.isNotBlank()) {
                        Text("SN: ${device.serialNumber}",
                            fontSize = 9.sp, color = colors.textSecondary.copy(alpha = 0.7f),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    } else {
                        Text("SN: --",
                            fontSize = 9.sp, color = colors.textSecondary.copy(alpha = 0.4f))
                    }
                } else {
                    // 输入设备显示类型信息
                    Text("类型: ${device.modelName.ifBlank { "输入设备" }}",
                        fontSize = 9.sp, color = colors.textSecondary.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = DischargingOrange.copy(alpha = 0.15f)
                    ) {
                        Text("输入设备",
                            fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            color = DischargingOrange,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                }
            }

            // 展开的配置区
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = PrimaryBlue.copy(alpha = 0.1f))
                Spacer(modifier = Modifier.height(8.dp))

                // ===== 备注编辑 (v5.0: 仅BLE设备可编辑) =====
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (device.isRemarkEditable) Icons.Default.Edit else Icons.Default.Info,
                        null,
                        tint = if (device.isRemarkEditable) PrimaryBlue else colors.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("设备备注名", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = colors.textPrimary)
                    if (!device.isRemarkEditable) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("(输入设备不支持备注)", fontSize = 10.sp, color = colors.textSecondary.copy(alpha = 0.6f))
                    }
                    if (showRemarkSaved) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("(已保存)", fontSize = 11.sp, color = ChargingGreen)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = remarkText,
                    onValueChange = { newValue ->
                        if (device.isRemarkEditable) {
                            remarkText = newValue
                            showRemarkSaved = false
                        }
                    },
                    label = { Text("留空则使用原始名称「${device.name}」", fontSize = 10.sp) },
                    singleLine = true,
                    enabled = device.isRemarkEditable,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryBlue,
                        unfocusedBorderColor = colors.textSecondary.copy(alpha = 0.3f),
                        disabledBorderColor = colors.textSecondary.copy(alpha = 0.15f),
                        disabledTextColor = colors.textSecondary.copy(alpha = 0.5f)
                    )
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (device.isRemarkEditable) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = {
                                remarkText = ""
                                viewModel.updateDeviceRemark(deviceId, "")
                                showRemarkSaved = true
                            }
                        ) {
                            Text("恢复默认", color = colors.textSecondary, fontSize = 12.sp)
                        }
                        TextButton(
                            onClick = {
                                viewModel.updateDeviceRemark(deviceId, remarkText.trim())
                                showRemarkSaved = true
                            }
                        ) {
                            Text("保存备注", color = PrimaryBlue, fontSize = 12.sp)
                        }
                    }

                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = PrimaryBlue.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(8.dp))

                // ===== v6.2: 阈值提醒开关 =====
                val reminderEnabledFlow = remember(deviceId) { viewModel.getDeviceThresholdReminderEnabled(deviceId) }
                val reminderEnabled by reminderEnabledFlow.collectAsState(initial = false)
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (reminderEnabled)
                            ChargingGreen.copy(alpha = 0.08f)
                        else
                            colors.textSecondary.copy(alpha = 0.05f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (reminderEnabled) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = if (reminderEnabled) ChargingGreen else colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "充电/耗电阈值提醒",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = colors.textPrimary
                            )
                            Text(
                                if (reminderEnabled) "提醒已启用 · 阈值已锁定" else "提醒已关闭 · 可自由修改阈值",
                                fontSize = 11.sp,
                                color = if (reminderEnabled) ChargingGreen else colors.textSecondary
                            )
                        }
                        Switch(
                            checked = reminderEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.setDeviceThresholdReminderEnabled(deviceId, enabled)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                                checkedTrackColor = ChargingGreen
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 充电阈值
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BatteryChargingFull, null, tint = ChargingGreen, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("充电提醒阈值", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = colors.textPrimary)
                    if (reminderEnabled) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.Lock, null, tint = colors.textSecondary,
                            modifier = Modifier.size(12.dp))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Slider(
                    value = chargeLevel.toFloat().coerceIn(50f, 100f),
                    onValueChange = if (reminderEnabled) ({}) else { raw ->
                        val rounded = ((raw / 2f).roundToInt() * 2).coerceIn(50, 100)
                        viewModel.updateDeviceChargeAlertLevel(deviceId, rounded)
                    },
                    valueRange = 50f..100f,
                    steps = 24,
                    enabled = !reminderEnabled,
                    colors = SliderDefaults.colors(
                        thumbColor = if (reminderEnabled) colors.textSecondary else ChargingGreen,
                        activeTrackColor = if (reminderEnabled) colors.textSecondary.copy(alpha = 0.3f) else ChargingGreen
                    )
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("50%", fontSize = 10.sp, color = colors.textSecondary)
                    Text("当前: $chargeLevel%", fontWeight = FontWeight.Medium, color = ChargingGreen, fontSize = 12.sp)
                    Text("100%", fontSize = 10.sp, color = colors.textSecondary)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 耗电阈值
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BatteryAlert, null, tint = LowBatteryRed, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("耗电提醒阈值", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = colors.textPrimary)
                    if (reminderEnabled) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.Lock, null, tint = colors.textSecondary,
                            modifier = Modifier.size(12.dp))
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Slider(
                    value = lowLevel.toFloat().coerceIn(0f, 48f),
                    onValueChange = if (reminderEnabled) ({}) else { raw ->
                        val rounded = ((raw / 2f).roundToInt() * 2).coerceIn(0, 48)
                        viewModel.updateDeviceLowBatteryAlertLevel(deviceId, rounded)
                    },
                    valueRange = 0f..48f,
                    steps = 23,
                    enabled = !reminderEnabled,
                    colors = SliderDefaults.colors(
                        thumbColor = if (reminderEnabled) colors.textSecondary else LowBatteryRed,
                        activeTrackColor = if (reminderEnabled) colors.textSecondary.copy(alpha = 0.3f) else LowBatteryRed
                    )
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0%", fontSize = 10.sp, color = colors.textSecondary)
                    Text("当前: $lowLevel%", fontWeight = FontWeight.Medium, color = LowBatteryRed, fontSize = 12.sp)
                    Text("48%", fontSize = 10.sp, color = colors.textSecondary)
                }
            }
        }
    }
}

/**
 * 权限操作卡片 — 带图标、描述文字和操作按钮，用于后台保活权限引导
 */
@Composable
fun PermissionActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    buttonText: String,
    buttonColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    val colors = LocalAppColors.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = buttonColor.copy(alpha = 0.06f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = buttonColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = colors.textPrimary)
                Text(description, fontSize = 11.sp, color = colors.textSecondary, lineHeight = 15.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(buttonText, fontSize = 12.sp, color = androidx.compose.ui.graphics.Color.White)
            }
        }
    }
}
