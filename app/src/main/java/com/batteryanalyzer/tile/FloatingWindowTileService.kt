package com.batteryanalyzer.tile

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.batteryanalyzer.R
import com.batteryanalyzer.data.PreferencesManager
import com.batteryanalyzer.service.FloatingBatteryService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * 控制中心 (QuickSettings) 磁贴：开启/关闭悬浮窗
 * 用户需在系统快捷设置面板中手动添加此磁贴
 */
class FloatingWindowTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: PreferencesManager

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(this)
    }

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val enabled = prefs.floatingWindowEnabled.firstOrNull() ?: false
            updateTileState(enabled)
        }
    }

    override fun onClick() {
        super.onClick()

        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // 权限不足，提示用户授权
            unlockAndRun {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                startActivityAndCollapse(intent)
            }
            return
        }

        scope.launch {
            val current = prefs.floatingWindowEnabled.firstOrNull() ?: false
            val newState = !current
            prefs.setFloatingWindowEnabled(newState)
            updateTileState(newState)

            // 启动/停止悬浮窗服务
            if (newState) {
                val intent = Intent(this@FloatingWindowTileService, FloatingBatteryService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } else {
                val intent = Intent(this@FloatingWindowTileService, FloatingBatteryService::class.java)
                stopService(intent)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateTileState(enabled: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (enabled) "悬浮窗已开启" else "悬浮窗已关闭"
        tile.subtitle = if (enabled) "点击关闭" else "点击开启"
        tile.icon = Icon.createWithResource(this@FloatingWindowTileService, R.drawable.logo_ic_tile_floating_new)
        tile.updateTile()
    }
}
