package com.batteryanalyzer.stylus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

/**
 * 设备备注变更广播 - 用于跨组件同步
 *
 * 当用户在设置中修改设备备注名后，
 * 此广播会通知悬浮窗、通知栏、小组件等组件刷新显示名称。
 */
object RemarkSyncManager {

    /** 备注更新广播 Action */
    const val ACTION_REMARK_UPDATED = "com.batteryanalyzer.action.REMARK_UPDATED"

    /** Intent Extra: 设备 ID */
    const val EXTRA_DEVICE_ID = "device_id"

    /** Intent Extra: 新备注名 */
    const val EXTRA_REMARK = "remark"

    /** Intent Extra: 新的显示名称 (displayName) */
    const val EXTRA_DISPLAY_NAME = "display_name"

    /**
     * 发送备注更新广播
     */
    fun broadcastRemarkUpdate(
        context: Context,
        deviceId: Long,
        remark: String,
        displayName: String
    ) {
        val intent = Intent(ACTION_REMARK_UPDATED).apply {
            putExtra(EXTRA_DEVICE_ID, deviceId)
            putExtra(EXTRA_REMARK, remark)
            putExtra(EXTRA_DISPLAY_NAME, displayName)
            // 设为包私有广播，仅本应用内接收
            `package` = context.packageName
        }
        context.sendBroadcast(intent)
        Log.d("RemarkSync", "Broadcast remark update: deviceId=$deviceId, name=$displayName")
    }

    /**
     * 注册备注更新监听
     */
    fun register(context: Context, onRemarkUpdated: (deviceId: Long, remark: String, displayName: String) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_REMARK_UPDATED) {
                    val deviceId = intent.getLongExtra(EXTRA_DEVICE_ID, -1L)
                    val remark = intent.getStringExtra(EXTRA_REMARK) ?: ""
                    val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME) ?: ""
                    if (deviceId > 0) {
                        Log.d("RemarkSync", "Received remark update: deviceId=$deviceId, name=$displayName")
                        onRemarkUpdated(deviceId, remark, displayName)
                    }
                }
            }
        }
        val filter = IntentFilter(ACTION_REMARK_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        return receiver
    }

    /**
     * 注销广播接收器
     */
    fun unregister(context: Context, receiver: BroadcastReceiver?) {
        try {
            receiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {
            // 接收器可能已注销
        }
    }
}
