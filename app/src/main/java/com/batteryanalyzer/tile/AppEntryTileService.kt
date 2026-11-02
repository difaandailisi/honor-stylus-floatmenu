package com.batteryanalyzer.tile

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.TileService
import com.batteryanalyzer.MainActivity
import com.batteryanalyzer.R

/**
 * 控制中心 (QuickSettings) 磁贴：快速进入应用
 * 用户需在系统快捷设置面板中手动添加此磁贴
 */
class AppEntryTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = android.service.quicksettings.Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.logo_ic_tile_app_entry_new)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        // 打开主界面
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivityAndCollapse(intent)
    }
}
