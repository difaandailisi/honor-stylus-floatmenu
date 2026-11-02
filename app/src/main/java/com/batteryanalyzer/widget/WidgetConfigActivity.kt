package com.batteryanalyzer.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.batteryanalyzer.data.BatteryRepository
import com.batteryanalyzer.model.DeviceInfo
import com.batteryanalyzer.ui.theme.PrimaryBlue
import kotlinx.coroutines.flow.firstOrNull

/**
 * 小组件配置 Activity
 * 用户添加桌面小组件时，可从已保存设备中选择要显示的设备
 */
class WidgetConfigActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 获取小组件 ID
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val repo = BatteryRepository(this)

        setContent {
            MaterialTheme {
                WidgetConfigScreen(
                    repository = repo,
                    onDeviceSelected = { deviceId ->
                        saveWidgetPref(deviceId)
                        setResultAndFinish(RESULT_OK)
                    },
                    onCancel = {
                        setResultAndFinish(RESULT_CANCELED)
                    }
                )
            }
        }
    }

    private fun saveWidgetPref(deviceId: Long) {
        getSharedPreferences("widget_device_prefs", Context.MODE_PRIVATE)
            .edit()
            .putLong("widget_device_$appWidgetId", deviceId)
            .apply()
    }

    private fun setResultAndFinish(resultCode: Int) {
        val resultValue = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        setResult(resultCode, resultValue)
        finish()
    }

    companion object {
        /**
         * 读取小组件实例配置的设备 ID
         */
        fun getConfiguredDeviceId(context: Context, appWidgetId: Int): Long {
            return context.getSharedPreferences("widget_device_prefs", Context.MODE_PRIVATE)
                .getLong("widget_device_$appWidgetId", -1L)
        }
    }
}

@Composable
fun WidgetConfigScreen(
    repository: BatteryRepository,
    onDeviceSelected: (Long) -> Unit,
    onCancel: () -> Unit
) {
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        devices = repository.getAllDevices().firstOrNull() ?: emptyList()
        loading = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            "选择要显示的设备",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        Text(
            "选择此小组件要显示的蓝牙设备电量",
            fontSize = 13.sp,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (loading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (devices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.BluetoothDisabled,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "暂无已保存的设备\n请先在应用中扫描并连接蓝牙设备",
                        fontSize = 14.sp,
                        color = Color.Gray,
                        lineHeight = 20.sp
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices) { device ->
                    DeviceSelectCard(
                        device = device,
                        repository = repository,
                        onClick = { onDeviceSelected(device.id) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("取消", fontSize = 14.sp)
        }
    }
}

@Composable
fun DeviceSelectCard(
    device: DeviceInfo,
    repository: BatteryRepository,
    onClick: () -> Unit
) {
    var latestLevel by remember { mutableStateOf(-1) }
    var latestCharging by remember { mutableStateOf(false) }

    LaunchedEffect(device.id) {
        val record = repository.getLatestRecord(device.id)
        latestLevel = record?.batteryLevel ?: -1
        latestCharging = record?.isCharging ?: false
    }

    val icon = when {
        device.name.contains("Pen", ignoreCase = true) ||
            device.name.contains("笔", ignoreCase = true) -> "✏️"
        device.name.contains("Buds", ignoreCase = true) ||
            device.name.contains("耳机", ignoreCase = true) -> "🎧"
        else -> "📱"
    }

    val levelColor = when {
        latestLevel < 0 -> Color.Gray
        latestCharging -> Color(0xFF4CAF50)
        latestLevel <= 10 -> Color(0xFFF44336)
        latestLevel <= 40 -> Color(0xFFFF9800)
        else -> PrimaryBlue
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, fontSize = 22.sp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    device.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                Text(
                    device.macAddress,
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
            if (latestLevel >= 0) {
                Text(
                    "$latestLevel%",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = levelColor
                )
                if (latestCharging) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("⚡", fontSize = 14.sp)
                }
            } else {
                Text("--", fontSize = 16.sp, color = Color.Gray)
            }
        }
    }
}
