package com.batteryanalyzer.keymap

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * 键值映射无障碍服务 v6.4
 *
 * 两大模式：
 * 1. 学习模式 — 拦截所有按键，根据 [KeyMappingManager.learnTarget] 学习原始键值或目标键值
 * 2. 映射模式 — 遍历当前设备所有已启用映射，匹配源键值后注入对应目标键值
 *
 * v6.0: 支持一设备多条映射规则，通过 [KeyMappingManager.getEnabledMappings] 遍历匹配。
 * v6.4: scanCode 兜底匹配 — 用户手动输入 Linux 键值(如760)时，系统会按 keyCode 精确匹配
 *       失败后自动用 scanCode 兜底匹配，解决 Linux/Android 键值翻译不一致问题。
 *
 * 用户必须在「设置 → 无障碍」中手动开启此服务。
 */
class KeyMappingService : AccessibilityService() {

    companion object {
        private const val TAG = "KeyMappingService"

        @Volatile
        var isRunning = false
            private set
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不使用窗口事件
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val scanCode = event.scanCode
        val action = event.action

        // v6.5: 诊断日志 — 同时记录 keyCode 和 scanCode，用于排查 Linux/Android 键值翻译
        Log.d(TAG, "onKeyEvent: keyCode=$keyCode, scanCode=$scanCode, action=$action, deviceId=${event.deviceId}, source=${event.source}")

        // v6.5: 日志文件 — 记录每一个到达无障碍服务的按键事件 (诊断760被拦截的关键入口)
        KeyMappingLogger.logKeyEventReceived(
            keyCode = keyCode, scanCode = scanCode, action = action,
            eventDeviceId = event.deviceId, eventSource = event.source,
            accessibilityRunning = isRunning,
            shizukuGranted = KeyMappingManager.shizukuPermissionGranted.value,
        )

        // 仅处理 DOWN 事件，忽略 UP 和 FLAG_CANCELED
        if (action != KeyEvent.ACTION_DOWN) return false

        // 获取当前设备信息用于日志
        val deviceId = KeyMappingManager.currentDeviceId.value
        val devName = resolveDeviceName(deviceId)
        val devType = resolveDeviceType(deviceId)
        val devIdentifier = resolveDeviceIdentifier(deviceId)

        // ====== 模式 1: 学习模式 ======
        if (KeyMappingManager.learnModeActive.value) {
            Log.d(TAG, "学习模式捕获键值: keyCode=$keyCode, scanCode=$scanCode, target=${KeyMappingManager.learnTarget.value.name}")

            // v6.5: 日志文件 — 学习模式捕获
            KeyMappingLogger.logLearnCapture(
                keyCode = keyCode, scanCode = scanCode,
                learnTarget = KeyMappingManager.learnTarget.value,
                mappingId = KeyMappingManager.currentEditingMappingId.value,
                deviceId = deviceId, deviceName = devName,
                deviceType = devType, deviceIdentifier = devIdentifier,
                accessibilityRunning = isRunning,
                shizukuGranted = KeyMappingManager.shizukuPermissionGranted.value,
                isKnownKey = KeyMappingManager.isKnownKeyCode(keyCode),
            )

            // v6.4: 学习模式同时传递 keyCode 和 scanCode，让 Manager 建立双向映射
            KeyMappingManager.onKeyDetected(keyCode, scanCode)

            // 学习模式下吞掉事件，避免触发系统默认行为
            return true
        }

        // ====== 模式 2: 映射模式 v6.5 — 多映射遍历匹配 ======
        // v6.4: 源键匹配策略 — 先精确匹配 Android keyCode，再兜底匹配 Linux scanCode
        //       用户手动输入 Linux 键值(如760)时，物理按键会被 .kl 翻译为 Android 键值(如131=F1)
        //       scanCode 兜底可以匹配到用户手动输入的 Linux 键值
        val enabledMappings = KeyMappingManager.getEnabledMappings()
        if (enabledMappings.isEmpty()) {
            // v6.5: 日志文件 — 无活跃映射规则
            KeyMappingLogger.logMappingNoMatch(
                keyCode = keyCode, scanCode = scanCode,
                activeMappingCount = 0,
                accessibilityRunning = isRunning,
                deviceId = deviceId,
            )
            return false
        }

        var anyMatched = false
        for (mapping in enabledMappings) {
            val code = mapping.sourceKeyCode
            if (code <= 0) continue

            // v6.4: 两层匹配 — keyCode 精确匹配 或 scanCode 兜底匹配
            val matched = keyCode == code || (scanCode > 0 && scanCode == code)

            if (matched) {
                anyMatched = true
                val matchType = if (keyCode == code) "keyCode精确匹配" else "scanCode兜底匹配"
                Log.d(TAG, "映射命中($matchType): keyCode=$keyCode, scanCode=$scanCode → target=${mapping.targetKeyCode} (mappingId=${mapping.id})")

                // v6.5: 日志文件 — 映射命中
                KeyMappingLogger.logMappingMatch(
                    sourceKeyCode = mapping.sourceKeyCode, targetKeyCode = mapping.targetKeyCode,
                    scanCode = scanCode, matchedKeyCode = if (keyCode == code) keyCode else scanCode,
                    matchType = matchType, mappingId = mapping.id,
                    deviceId = deviceId, deviceName = devName,
                    deviceType = devType, deviceIdentifier = devIdentifier,
                    accessibilityRunning = isRunning,
                    shizukuGranted = KeyMappingManager.shizukuPermissionGranted.value,
                    activeMappingCount = enabledMappings.size,
                )

                // 防抖
                if (KeyMappingManager.shouldDebounce()) {
                    Log.d(TAG, "防抖拦截")
                    KeyMappingLogger.logDebounceBlocked(
                        keyCode = keyCode, mappingId = mapping.id,
                        deviceId = deviceId, deviceName = devName,
                    )
                    return true
                }

                // 注入对应的目标键值
                KeyMappingManager.injectTargetKey(mapping.targetKeyCode)

                // return true = 拦截原键值行为
                return true
            }
        }

        // v6.5: 日志文件 — 未匹配到任何规则
        if (!anyMatched) {
            KeyMappingLogger.logMappingNoMatch(
                keyCode = keyCode, scanCode = scanCode,
                activeMappingCount = enabledMappings.size,
                accessibilityRunning = isRunning,
                deviceId = deviceId,
            )
        }

        return false // 放行其他键值
    }

    // v6.5: 辅助方法 — 从 KeyMappingManager 获取设备信息用于日志
    private fun resolveDeviceName(deviceId: Long): String {
        // 通过全局字段获取 (简化实现，完整设备名由 Screen 端注入)
        return KeyMappingLogger.deviceNameOverride ?: "设备#$deviceId"
    }

    private fun resolveDeviceType(deviceId: Long): String {
        return KeyMappingLogger.deviceTypeOverride ?: ""
    }

    private fun resolveDeviceIdentifier(deviceId: Long): String {
        return KeyMappingLogger.deviceIdentifierOverride ?: ""
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        KeyMappingManager.accessibilityService = this
        KeyMappingManager.onAccessibilityServiceStateChanged(true)
        KeyMappingManager.refreshInjectionMethodPublic()
        KeyMappingLogger.logServiceState(connected = true)
        Log.i(TAG, "键值映射服务已启动")

        // Android 14+ 需要调用来接收硬件按键
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                // v6.3: 先尝试带 FLAG_FILTER_KEY_EVENTS 参数的版本，
                //       某些 ROM 需要显式标志才转发 vendor key (如键值 760)
                val methodWithFlag = javaClass.superclass.getMethod("requestFilterKeyEvents", Int::class.javaPrimitiveType)
                methodWithFlag.invoke(this, 1) // FLAG_FILTER_KEY_EVENTS = 1
                Log.i(TAG, "requestFilterKeyEvents(FLAG_FILTER_KEY_EVENTS) 已调用 (API 34+)")
            } catch (e: Exception) {
                Log.w(TAG, "requestFilterKeyEvents(int) 失败: ${e.message}, 尝试无参版本")
                try {
                    val method = javaClass.superclass.getMethod("requestFilterKeyEvents")
                    method.invoke(this)
                    Log.i(TAG, "requestFilterKeyEvents() 无参版本已调用 (API 34+)")
                } catch (e2: Exception) {
                    Log.w(TAG, "requestFilterKeyEvents 全部失败: ${e2.message}")
                }
            }
        }
    }

    override fun onDestroy() {
        KeyMappingManager.accessibilityService = null
        KeyMappingManager.onAccessibilityServiceStateChanged(false)
        KeyMappingManager.refreshInjectionMethodPublic()
        KeyMappingManager.stopLearnMode()
        KeyMappingLogger.logServiceState(connected = false)
        isRunning = false
        super.onDestroy()
        Log.i(TAG, "键值映射服务已停止")
    }
}
