package com.batteryanalyzer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 偏好设置管理器 - 持久化用户设置
 * v3.0: 支持每设备独立充电/耗电提醒阈值
 */
class PreferencesManager(private val context: Context) {

    companion object {
        // 全局键（悬浮窗开关、选中设备、上次连接 MAC）
        private val KEY_SELECTED_DEVICE_ID = longPreferencesKey("selected_device_id")
        private val KEY_FLOATING_WINDOW_ENABLED = booleanPreferencesKey("floating_window_enabled")
        private val KEY_LAST_CONNECTED_MAC = stringPreferencesKey("last_connected_mac")

        // v5.1: 键值映射开关及最近使用的设备
        private val KEY_MAPPING_ENABLED = booleanPreferencesKey("key_mapping_enabled")
        private val KEY_MAPPING_DEVICE_ID = longPreferencesKey("key_mapping_device_id")

        // 兼容旧版全局阈值键（v2.x 升级到 v3.0 时用作默认值来源）
        @Deprecated("请使用每设备独立阈值", ReplaceWith("getDeviceChargeAlertLevel(deviceId)"))
        val KEY_CHARGE_ALERT_LEVEL = intPreferencesKey("charge_alert_level")
        @Deprecated("请使用每设备独立阈值", ReplaceWith("getDeviceLowBatteryAlertLevel(deviceId)"))
        val KEY_LOW_BATTERY_ALERT_LEVEL = intPreferencesKey("low_battery_alert_level")

        private const val DEFAULT_CHARGE_ALERT = 85
        private const val DEFAULT_LOW_BATTERY_ALERT = 20
        private const val DEFAULT_SELECTED_DEVICE = -1L

        /** 每设备阈值键前缀 */
        private const val PREFIX_CHARGE_ALERT = "charge_alert_"
        private const val PREFIX_LOW_BATTERY_ALERT = "low_battery_alert_"

        /** 每设备阈值提醒开关键前缀（v6.2：默认关闭，用户需手动开启） */
        private const val PREFIX_THRESHOLD_REMINDER = "threshold_reminder_"
    }

    // -------- 每设备独立阈值 --------

    /**
     * 获取指定设备的充电提醒阈值 Flow
     * 优先读取 per-device 设置，不存在时回退到旧版全局值
     */
    fun getDeviceChargeAlertLevel(deviceId: Long): Flow<Int> {
        val key = intPreferencesKey("$PREFIX_CHARGE_ALERT$deviceId")
        return context.dataStore.data.map { prefs ->
            prefs[key] ?: prefs[KEY_CHARGE_ALERT_LEVEL] ?: DEFAULT_CHARGE_ALERT
        }
    }

    /**
     * 获取指定设备的耗电提醒阈值 Flow
     */
    fun getDeviceLowBatteryAlertLevel(deviceId: Long): Flow<Int> {
        val key = intPreferencesKey("$PREFIX_LOW_BATTERY_ALERT$deviceId")
        return context.dataStore.data.map { prefs ->
            prefs[key] ?: prefs[KEY_LOW_BATTERY_ALERT_LEVEL] ?: DEFAULT_LOW_BATTERY_ALERT
        }
    }

    /**
     * 设置指定设备的充电提醒阈值
     */
    suspend fun setDeviceChargeAlertLevel(deviceId: Long, level: Int) {
        val key = intPreferencesKey("$PREFIX_CHARGE_ALERT$deviceId")
        context.dataStore.edit { prefs -> prefs[key] = level.coerceIn(50, 100) }
    }

    /**
     * 设置指定设备的耗电提醒阈值
     */
    suspend fun setDeviceLowBatteryAlertLevel(deviceId: Long, level: Int) {
        val key = intPreferencesKey("$PREFIX_LOW_BATTERY_ALERT$deviceId")
        context.dataStore.edit { prefs -> prefs[key] = level.coerceIn(0, 48) }
    }

    /**
     * 获取指定设备的阈值提醒开关状态 Flow（v6.2：默认关闭）
     */
    fun getDeviceThresholdReminderEnabled(deviceId: Long): Flow<Boolean> {
        val key = booleanPreferencesKey("$PREFIX_THRESHOLD_REMINDER$deviceId")
        return context.dataStore.data.map { prefs ->
            prefs[key] ?: false
        }
    }

    /**
     * 设置指定设备的阈值提醒开关（v6.2）
     * @param enabled true=开启提醒并锁定阈值, false=关闭提醒并允许修改阈值
     */
    suspend fun setDeviceThresholdReminderEnabled(deviceId: Long, enabled: Boolean) {
        val key = booleanPreferencesKey("$PREFIX_THRESHOLD_REMINDER$deviceId")
        context.dataStore.edit { prefs -> prefs[key] = enabled }
    }

    // -------- 全局选择/开关 --------

    val selectedDeviceId: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[KEY_SELECTED_DEVICE_ID] ?: DEFAULT_SELECTED_DEVICE
    }

    val floatingWindowEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_FLOATING_WINDOW_ENABLED] ?: false
    }

    val lastConnectedMac: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_LAST_CONNECTED_MAC] ?: ""
    }

    suspend fun setSelectedDeviceId(id: Long) {
        context.dataStore.edit { preferences ->
            preferences[KEY_SELECTED_DEVICE_ID] = id
        }
    }

    suspend fun setFloatingWindowEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_FLOATING_WINDOW_ENABLED] = enabled
        }
    }

    suspend fun setLastConnectedMac(mac: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LAST_CONNECTED_MAC] = mac
        }
    }

    // -------- v5.1: 键值映射开关 --------

    val keyMappingEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_MAPPING_ENABLED] ?: false
    }

    val keyMappingDeviceId: Flow<Long> = context.dataStore.data.map { preferences ->
        preferences[KEY_MAPPING_DEVICE_ID] ?: -1L
    }

    suspend fun setKeyMappingEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_MAPPING_ENABLED] = enabled
        }
    }

    suspend fun setKeyMappingDeviceId(deviceId: Long) {
        context.dataStore.edit { preferences ->
            preferences[KEY_MAPPING_DEVICE_ID] = deviceId
        }
    }

}
