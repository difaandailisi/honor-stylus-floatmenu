# 手写笔电量分析 (Handwriting Pen Battery Analyzer)

一款专为 Android 14-16 设计的手写笔蓝牙电量监测与分析应用。

## 功能特性

### 🔍 蓝牙设备识别
- BLE (低功耗蓝牙) 扫描与配对
- 经典蓝牙设备兼容识别
- 自动识别手写笔设备（荣耀Magic-Pencil 3、华为M-Pencil、S Pen 等）
- 通过 Battery Service (0x180F) 读取实时电量

### 📊 电量监控与分析
- **实时电量显示** - 当前电量百分比，充电/放电状态
- **电量曲线图** - 时间维度上的电量变化趋势
- **充电记录** - 智能识别充电会话（支持随用随充）
- **耗电记录** - 连续放电周期追踪
- **周期统计** - 日报/月报/年报对比分析

### 🏥 电池健康度评估
- 基于实际充入容量 vs 标称容量对比
- 充电习惯分析建议
- 健康度等级：优秀/良好/一般/较差/需要更换
- 长期趋势对比

### 🔔 智能提醒
- 充电到指定阈值提醒（默认85%）
- 低电量提醒（默认20%）
- 充满提醒
- 充电状态变化通知

### 🧩 桌面小组件
- 支持添加到主屏幕的电量卡片
- 实时显示电量和充电状态
- 点击进入应用

### 🖊️ 华为手写笔悬浮菜单模拟
在荣耀/华为设备上，**无需连接任何真实手写笔**，也能把系统原生的悬浮菜单（快捷球）单独顶出来使用。

- **A 模式**：完整伪造"已连接笔"状态，体验与真笔一致
- **C 模式**：只驱动悬浮菜单弹出，不触碰笔身份，停止后不留假笔痕迹
- **环境检测面板**：实时显示 `connection_state`、`pstate`、`attach`、`atype` 等键值，以及悬浮菜单是否开启
- **一键停止 / 清除键值残留 / 彻底恢复原始状态**：清理前先等待模拟任务结束再写回中性值，并二次回读补清，防止系统笔服务重写导致假笔反复

> 依赖 Shizuku 或 `WRITE_SECURE_SETTINGS` 权限。

### ⌨️ 键值映射
- 独立于悬浮菜单的键值层能力：读写系统全局键值，进行模拟、改写与还原
- 可用于按键映射调试与设备状态定制
- 支持一键清除与恢复原始值

## 技术架构

| 层级 | 技术选型 |
|------|----------|
| UI | Jetpack Compose + Material 3 |
| 图表 | 自定义 Canvas 绘制 |
| 数据库 | Room (SQLite) |
| 小部件 | Jetpack Glance |
| 后台任务 | Foreground Service + WorkManager |
| 架构 | MVVM (ViewModel + Repository + Flow) |
| 蓝牙 | Android BLE API |

## 项目结构

```
HandwritingPenBattery/
├── app/src/main/java/com/batteryanalyzer/
│   ├── MainActivity.kt              # 主入口 + 导航
│   ├── BatteryApp.kt                # Application 初始化
│   ├── ble/BleManager.kt            # BLE 扫描 / 连接 / 电量读取
│   ├── data/                        # Room 数据库、仓库层、偏好设置
│   ├── model/                       # 设备、电量记录、充放电会话、键值映射模型
│   ├── viewmodel/MainViewModel.kt   # 统一 ViewModel
│   ├── ui/
│   │   ├── theme/AppColors.kt
│   │   ├── components/              # 电量卡片、交互式曲线、时间曲线
│   │   └── screens/                 # 仪表盘 / 扫描 / 分析 / 功耗 / 键值映射 / 手写笔模拟 / 设置
│   ├── stylus/                      # 荣耀·华为手写笔识别、系统笔状态读写、悬浮菜单模拟
│   ├── keymap/                      # 输入设备扫描与系统键值映射
│   ├── service/                     # 电量监控前台服务、悬浮窗服务
│   ├── notification/                # 通知与设备图标
│   ├── widget/                      # Glance 桌面小组件（单笔 / 多笔）
│   ├── tile/                        # 快捷设置磁贴
│   └── crash/                       # 退出原因记录与崩溃监控
├── app/schemas/                     # Room 数据库 schema 导出
├── 代码逆向/                         # 荣耀手写笔悬浮菜单的静态分析与实测记录
├── gradle/wrapper/                  # Gradle wrapper（已内置）
└── build.gradle.kts, settings.gradle.kts
```

## 构建要求

- JDK 21（直接使用 Android Studio 自带的 JBR 即可）
- Gradle 9.4.1（仓库已内置 wrapper，无需额外安装，执行 `./gradlew` 会自动下载）
- Android Gradle Plugin 9.2.1 / Kotlin 2.2.10
- Android SDK 35（`compileSdk = 35`）
- minSdk: 26 (Android 8.0) / targetSdk: 35

> 虽然在 Android 8.0 及以上都能安装，但本项目主要针对 **Android 14–16** 的 BLE 与系统手写笔服务行为做过适配与验证。

## 使用说明

1. 用 Android Studio 打开项目目录
2. 等待 Gradle Sync 完成
3. 在 Android 14-16 设备上运行
4. 首次启动授予蓝牙和通知权限
5. 点击「扫描设备」搜索附近的手写笔
6. 连接到设备后自动开始电量监控
7. 长按桌面添加电量小组件

## 支持设备

- 荣耀 Magic-Pencil 3 (82mAh) - 已优化
- 华为 M-Pencil 系列
- 三星 S Pen (BLE 版本)
- 其他支持 BLE Battery Service 的手写笔

## 权限说明

| 权限 | 用途 |
|------|------|
| BLUETOOTH_SCAN | 扫描蓝牙设备 |
| BLUETOOTH_CONNECT | 连接蓝牙设备 |
| ACCESS_FINE_LOCATION | BLE 设备发现 |
| POST_NOTIFICATIONS | 电量提醒通知 |
| FOREGROUND_SERVICE | 后台电量监控 |

## ⚠️ 免责声明

本项目仅供学习与研究使用。手写笔模拟功能通过修改系统全局设置实现，属于非官方途径，可能导致系统行为异常或与原厂服务冲突。请在充分理解风险的前提下自行承担使用后果，作者不对任何设备损坏、数据丢失或保修问题负责。请勿用于商业作弊、绕过授权或其他不当用途。

## 📄 开源协议

本项目基于 [MIT License](LICENSE) 开源。
