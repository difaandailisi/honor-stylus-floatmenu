package com.batteryanalyzer.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batteryanalyzer.LocalAppColors
import com.batteryanalyzer.keymap.KeyMappingManager
import com.batteryanalyzer.stylus.StylusSimulator
import com.batteryanalyzer.stylus.StylusVendorSystem
import com.batteryanalyzer.ui.theme.ChargingGreen
import com.batteryanalyzer.ui.theme.DischargingOrange
import com.batteryanalyzer.ui.theme.LowBatteryRed
import com.batteryanalyzer.ui.theme.PrimaryBlue
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 华为手写笔模拟
 *
 * 两个模式：
 *  - A 纯模拟：没有笔也假装有一支，强制把悬浮菜单顶出来并保持
 *  - B 真笔跟随：真连着 M-Pencil 2/3 (CD54/CD54S) / M-Pencil Pro / 荣耀笔时，
 *    磁吸充电 → 菜单消失；取下不充电 → 菜单出现
 *
 * ⚠️ 仅荣耀体系可用。华为体系由系统原生接管，禁用全部写入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StylusSimScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val colors = LocalAppColors.current

    val mode by StylusSimulator.mode.collectAsState()
    val running by StylusSimulator.running.collectAsState()
    val status by StylusSimulator.status.collectAsState()
    val env by StylusSimulator.env.collectAsState()
    val lastWrite by StylusSimulator.lastWriteTime.collectAsState()

    var selectedMode by remember { mutableStateOf(StylusSimulator.Mode.OFF) }
    var intervalSec by remember { mutableStateOf(20) }
    var showUnpairDialog by remember { mutableStateOf(false) }

    // 进入页面刷新一次；随后每 3 秒刷新环境展示（只读 Settings，开销极低）
    LaunchedEffect(Unit) {
        StylusSimulator.refreshEnv(context)
        while (true) {
            kotlinx.coroutines.delay(3000)
            StylusSimulator.refreshEnv(context)
        }
    }

    // 引擎停止时同步把选择复位
    LaunchedEffect(running) {
        if (!running) selectedMode = StylusSimulator.Mode.OFF
    }

    // 前置条件失效时自动取消对应模式的选择
    LaunchedEffect(env.bleConnected) {
        if (selectedMode == StylusSimulator.Mode.FOLLOW && !env.bleConnected) {
            selectedMode = StylusSimulator.Mode.OFF
        }
    }

    val isHonor = env.system == StylusVendorSystem.HONOR
    val isHuawei = env.system == StylusVendorSystem.HUAWEI
    val usable = isHonor && env.shizukuGranted

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("华为手写笔模拟", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryBlue,
                    titleContentColor = androidx.compose.ui.graphics.Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ===== 顶部状态总览：悬浮菜单是否开启（实时检测） =====
            StatusBanner(
                system = env.system,
                shizukuGranted = env.shizukuGranted,
                menuVisible = env.menuVisible
            )

            // ===== 环境检测 =====
            SectionCard(title = "环境检测") {
                EnvRow("设备体系", when (env.system) {
                    StylusVendorSystem.HONOR -> "荣耀（可模拟）"
                    StylusVendorSystem.HUAWEI -> "华为（原生接管）"
                    else -> "未识别"
                }, if (isHonor) ChargingGreen else colors.textSecondary)
                EnvRow("笔连接状态", env.connectionText,
                    if (env.isTakenOver) ChargingGreen else colors.textSecondary)
                EnvRow("磁吸/充电", env.chargingText, colors.textSecondary)
                EnvRow("系统型号", env.modelNumber ?: "未知", colors.textSecondary)
                EnvRow("BLE 连接", if (env.bleConnected) "已连接" else "未连接",
                    if (env.bleConnected) ChargingGreen else LowBatteryRed)
                EnvRow("识别到的笔", env.penModelLabel,
                    if (env.isTargetPen) ChargingGreen else DischargingOrange)
            }

            // ===== 华为体系：禁用并提示 =====
            if (isHuawei) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = DischargingOrange.copy(alpha = 0.12f)
                    )
                ) {
                    Row(modifier = Modifier.padding(14.dp)) {
                        Icon(Icons.Default.Info, null, tint = DischargingOrange)
                        Column(modifier = Modifier.padding(start = 10.dp)) {
                            Text("你的设备原生支持手写笔", fontWeight = FontWeight.Bold,
                                fontSize = 14.sp, color = colors.textPrimary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "检测到华为体系（com.huawei.stylus.floatmenu）。华为的悬浮菜单由系统流程拉起，" +
                                    "需 android.permission.REBOOT 签名权限，写 Settings 只能改内部状态、" +
                                    "骗不出悬浮窗。本功能已禁用，不会写入任何键值。",
                                fontSize = 12.sp, color = colors.textSecondary
                            )
                        }
                    }
                }
            }

            if (env.system == StylusVendorSystem.UNKNOWN) {
                NoticeCard(
                    "未检测到荣耀悬浮菜单组件（com.hihonor.stylus.floatmenu），模拟功能不可用。",
                    LowBatteryRed
                )
            }

            // 写入权限（Shizuku）的状态与授权统一在「设置 → 权限与注入状态」管理，此处不重复展示
            Text(
                "写入权限（Shizuku）的状态与授权统一在「设置 → 权限与注入状态」管理，与键值映射共用同一份授权。",
                fontSize = 12.sp, color = colors.textSecondary, lineHeight = 17.sp
            )

            // ===== 模式选择 =====
            SectionCard(title = "选择模式") {
                ModeOption(
                    selected = selectedMode == StylusSimulator.Mode.SIMULATE,
                    enabled = usable && !isHuawei,
                    title = "A · 假装有笔（凭空造一支笔）",
                    desc = "完全不需要笔。会写入 connection_state=2，" +
                        "让系统「相信」有一支笔在线——包括笔身份在内的整条链路都被伪造。" +
                        "代价：会留下「假笔状态」，停止时自动还原。" +
                        "适用于手边一支笔都没有的场景。",
                    onClick = { selectedMode = StylusSimulator.Mode.SIMULATE }
                )
                ModeOption(
                    selected = selectedMode == StylusSimulator.Mode.FORCE_MENU,
                    enabled = usable && !isHuawei,
                    title = "C · 强制悬浮菜单（不碰笔身份）",
                    desc = "只驱动悬浮菜单状态机把它顶出来，绝不写型号 / 电量 / 名字等任何笔身份键。" +
                        "无真实笔时临时开 conn=2 顶出菜单、随后关回 0，全程不留假笔状态；" +
                        "因此可独立使用、也可在 A 之后直接切换（不会把菜单一起清掉）。" +
                        if (env.connectionState == 0) "当前 conn=0，启动后将临时开/关 conn 顶出菜单。" else "当前 conn=${env.connectionState}（真实笔，不碰）。",
                    onClick = { selectedMode = StylusSimulator.Mode.FORCE_MENU }
                )
                ModeOption(
                    selected = selectedMode == StylusSimulator.Mode.FOLLOW,
                    enabled = usable && !isHuawei && env.bleConnected,
                    title = "B · 真笔跟随",
                    desc = if (env.bleConnected) {
                        "已识别：${env.penModelLabel}。磁吸充电 → 菜单消失；取下不充电 → 菜单出现。" +
                            "★ 本模式不伪造「有笔」，只跟随真实磁吸状态，判定依据是 App 自己的 BLE 连接。"
                    } else {
                        "未检测到已连接的手写笔。请先在「仪表盘」连接你的 " +
                            "M-Pencil 2/3 (CD54/CD54S)、M-Pencil Pro 或荣耀笔。"
                    },
                    onClick = { selectedMode = StylusSimulator.Mode.FOLLOW }
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text("看门狗间隔（球有 45 秒空闲超时，必须 < 30 秒）",
                    fontSize = 12.sp, color = colors.textSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 20, 25).forEach { sec ->
                        FilterChip(
                            selected = intervalSec == sec,
                            onClick = { intervalSec = sec },
                            label = { Text("${sec}s") }
                        )
                    }
                }
            }

            // ===== 控制 =====
            SectionCard(title = "运行控制") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            StylusSimulator.start(
                                context, selectedMode, intervalSec * 1000L
                            )
                        },
                        enabled = usable && selectedMode != StylusSimulator.Mode.OFF && !running &&
                            (selectedMode != StylusSimulator.Mode.FOLLOW || env.bleConnected),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                    ) {
                        Icon(Icons.Default.PlayArrow, null)
                        Text(" 启动")
                    }
                    OutlinedButton(
                        onClick = { StylusSimulator.stop(context) },
                        enabled = running,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Stop, null)
                        Text(" 停止")
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("状态：", fontSize = 12.sp, color = colors.textSecondary)
                    Text(
                        if (running) status else "未运行",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (running) ChargingGreen else colors.textSecondary
                    )
                }
                if (lastWrite > 0) {
                    Text(
                        "最近写入：${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(java.util.Date(lastWrite))}",
                        fontSize = 11.sp, color = colors.textSecondary
                    )
                }
                if (mode == StylusSimulator.Mode.SIMULATE && env.hasRealPen && running) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "检测到真笔接入。系统在物理事件时会覆盖这些键，" +
                            "看门狗会在一个周期内重新拉回悬浮菜单。",
                        fontSize = 11.sp, color = DischargingOrange
                    )
                }
            }

            // ===== 恢复原始状态 =====
            SectionCard(title = "恢复原始状态") {
                Text(
                    "点「停止」会还原 A 伪造过的 connection_state，并强制隐藏悬浮菜单（带回读校验，确保写入生效）。\n" +
                        "若仍有残留、或想一次性清干净，用下面的按钮。",
                    fontSize = 12.sp, color = colors.textSecondary, lineHeight = 17.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { StylusSimulator.resetToOriginal(context, unpair = false) },
                    enabled = usable,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("清除键值残留（CONNECT / 状态机 / 型号 …）") }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { showUnpairDialog = true },
                    enabled = usable,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = LowBatteryRed)
                ) { Text("彻底恢复（含解除蓝牙配对）") }

                if (showUnpairDialog) {
                    AlertDialog(
                        onDismissRequest = { showUnpairDialog = false },
                        title = { Text("确认彻底恢复？") },
                        text = {
                            Text(
                                "将清除所有荣耀笔相关 Settings 残留，并尝试解除已配对的手写笔。" +
                                    "解除配对后需要重新吸附/配对才能使用。是否继续？",
                                fontSize = 13.sp
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showUnpairDialog = false
                                StylusSimulator.resetToOriginal(context, unpair = true)
                            }) { Text("确认", color = LowBatteryRed) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showUnpairDialog = false }) { Text("取消") }
                        }
                    )
                }
            }

            // ===== 原理说明 =====
            SectionCard(title = "原理与边界") {
                Text(
                    "· 三种模式区别：A 伪造整条笔链路（含 conn=2，留假笔残留，停止时还原）；" +
                        "C 只顶菜单、仅 conn=0 时最小补 conn=2（不写任何笔身份键，零身份残留）；B 跟随真实磁吸。\n" +
                        "· 单向写入：读 charging_state（系统写、不参与判定）→ 写 attach_state（参与判定），" +
                        "避免自激震荡。\n" +
                        "· 跳变触发：ContentObserver 只在值变化时回调，故每轮先写反值再写目标值。\n" +
                        "· 仅荣耀可用：华为把判定放在系统流程里（需 REBOOT 权限），骗不出悬浮窗。\n" +
                        "· 只能复刻悬浮菜单：120Hz 由蓝牙包 → AGPService 驱动，Settings 够不着。\n" +
                        "· 常驻性：当前看门狗运行在 App 进程内，App 被杀即停止。",
                    fontSize = 12.sp, color = colors.textSecondary, lineHeight = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    val colors = LocalAppColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.textPrimary)
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun EnvRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = colors.textSecondary)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

@Composable
private fun ModeOption(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = if (enabled) onClick else null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = if (enabled) colors.textPrimary else colors.textSecondary)
            Spacer(modifier = Modifier.height(2.dp))
            Text(desc, fontSize = 11.sp, color = colors.textSecondary, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun NoticeCard(text: String, tint: androidx.compose.ui.graphics.Color) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.12f))
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Icon(Icons.Default.Warning, null, tint = tint)
            Text(text, fontSize = 12.sp, color = colorsText(),
                modifier = Modifier.padding(start = 10.dp), lineHeight = 17.sp)
        }
    }
}

@Composable
private fun colorsText(): androidx.compose.ui.graphics.Color = LocalAppColors.current.textPrimary

/**
 * 顶部状态总览：实时显示「悬浮菜单是否开启」。
 * 依据 [StylusEnv.menuVisible]（由系统态 conn≠0 ∧ pstate=2 ∧ attach=0 推导）。
 */
@Composable
private fun StatusBanner(
    system: StylusVendorSystem,
    shizukuGranted: Boolean,
    menuVisible: Boolean
) {
    val colors = LocalAppColors.current
    val (icon, text, color) = when {
        system != StylusVendorSystem.HONOR ->
            Triple(Icons.Default.Warning, "本机不支持悬浮菜单模拟", LowBatteryRed)
        menuVisible ->
            Triple(Icons.Default.CheckCircle, "悬浮菜单：显示中", ChargingGreen)
        else ->
            Triple(Icons.Default.Info, "悬浮菜单：未显示", colors.textSecondary)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "体系：${when (system) {
                    StylusVendorSystem.HONOR -> "荣耀"
                    StylusVendorSystem.HUAWEI -> "华为"
                    else -> "未识别"
                }}　·　写入授权：${if (shizukuGranted) "已授权" else "未授权"}",
                fontSize = 12.sp, color = colors.textSecondary
            )
        }
    }
}
