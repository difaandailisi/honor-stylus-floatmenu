# 后续深化优化建议

## 1. BLE 连接参数深度优化

**位置**: `BleManager.kt` - `onConnectionStateChange` (约第 859 行)

**当前状态**: 使用 `CONNECTION_PRIORITY_BALANCED`（连接间隔 30-50ms）

**建议优化**: 针对手写笔这种低频数据上报设备，可进一步降低功耗，请求 `CONNECTION_PRIORITY_LOW_POWER`（Android 8.0+）

**收益**: 连接间隔增大到 100-125ms，对手写笔电量监控足够，可降低 BLE 射频功耗约 30-50%

---

## 2. 多通道 Snapshot 扩展

**位置**: `BatteryMonitorService.kt` - `batterySnapshot`

**当前状态**: `BatterySnapshot` 仅包含单设备 `level` 和 `isCharging`

**建议优化**: 将 `multiBatteryLevels` 纳入 `BatterySnapshot`，增加 `multiChannelLevels: Map<BatteryChannel, Int>` 和 `channels: List<BatteryChannel>` 字段

**收益**: FloatingBatteryService、Widget 等下游组件可直接从 Snapshot 读取所有通道数据，减少重复 BleManager 查询

---

## 3. GATT 快速获取添加优先级请求

**位置**: `BleManager.kt` - `quickFetchBatteryLevel()` (约第 571 行)

**当前状态**: 临时 GATT 连接未请求连接优先级

**建议优化**: 在 `onConnectionStateChange(STATE_CONNECTED)` 回调中，立即请求 `CONNECTION_PRIORITY_LOW_POWER`，避免临时连接对系统蓝牙栈造成高功耗压力

---
