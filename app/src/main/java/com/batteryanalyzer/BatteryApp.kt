package com.batteryanalyzer

import android.app.Application
import android.util.Log
import com.batteryanalyzer.crash.CrashExitMonitor

/**
 * 应用入口 —— 最早初始化的地方
 * 在此初始化崩溃监控模块、数据库等全局单例
 */
class BatteryApp : Application() {

    companion object {
        private const val TAG = "BatteryApp"
    }

    override fun onCreate() {
        super.onCreate()

        // 【第一优先级】初始化异常捕获模块——必须在任何其他代码之前
        CrashExitMonitor.init(this)
        Log.i(TAG, "崩溃监控模块已初始化")
    }
}
