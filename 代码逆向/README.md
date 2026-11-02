# 代码逆向 · 荣耀 / 华为手写笔系统行为分析

对荣耀设备上「手写笔」相关系统应用的静态分析 + 真机实测记录。所有结论标注了类/方法/常量出处，
并区分【实证】【实测】【推断】三种可信度（约定见 [`AI-CONTEXT.md`](AI-CONTEXT.md)）。

> ⚠️ **性质说明**：这是**研究记录**，不是可直接照抄的实施方案。
> 其中涉及修改系统 Settings 键值的脚本，均已在真机验证过"可写/不可写"的边界，
> 但**不保证**在其他机型、其他系统版本上有效。使用前请先读对应文档的"未确认项"。

## 分析对象

| 设备 / 版本锚点 | 值 |
|---|---|
| 实机 | CGA-W00（荣耀平板），`ro.build.characteristics=tablet` |
| 手写笔 | Magic-Pencil 3（CD54S）；另测 M-Pencil 1 代（CD52）、M-Pencil 2（CD54） |
| 系统 | Android 16 / 17（两个阶段分别实测） |

涉及的 5 个系统包：

| 包名 | 角色 | 版本 |
|---|---|---|
| `com.android.bluetooth` | **生产方**：霍尔/磁吸、HID 识别、PID 白名单、绑服务、发广播 | 17 |
| `com.hihonor.hnbluetoothpencilmanager` | **总调度 + 状态机**：MainService / HwPencilController / AGPService | 11.0.0.7 |
| `com.hihonor.stylus.floatmenu` | **悬浮菜单本体**：BTMessageService / StylusFloatBallService | 19.0.1.310 |
| `com.hihonor.featurelayer.sharedfeature.stylus` | 笔迹引擎（penkit impl） | 13.1.0.100 |
| `com.hihonor.globalwrite` | 全局手写 / 自由批注 / 智慧套索 | 19.0.1.308 |

另有两份独立分析：`com.hihonor.android.instantshare`（荣耀分享 17.0.0.24）、
`com.hihonor.android.launcher`（PC 桌面）。

## 目录

```
代码逆向/
├── README.md                       ← 你在这里
├── AI-CONTEXT.md                   AI 阅读入口：结构化事实、状态机、键值表、陷阱清单
│
├── 01-悬浮菜单机制/                 研究主线：为什么悬浮球不出现
│   ├── README.md                   现象 → 原理 → 修复工具（含第 13 节真机实测修正）
│   ├── 全逻辑闭环.md                ★ 五包逐层链路、三个决定性卡点、帧率真相
│   ├── 思考.md                      实测原始记录，含推翻自己前几轮预判的部分
│   ├── 华为vs荣耀对比.md            华为侧机制对照，解释"为什么荣耀笔在华为上不行"
│   ├── 加强方案.md                  面向 App 侧的设备识别与兜底策略
│   └── 假装有一支手写笔.md          无实体笔时驱动悬浮菜单的可行路径
│
├── 02-充电机制/
│   └── 荣耀手写笔充电机制.md        ★ 能否"≥90% 停充"的结论：应用层做不到，附三条原因
│
├── 03-一碰传与PC桌面/
│   ├── 一碰传SDK与碰一碰路由研究.md  官方 SDK 接口、nfc_tag_share_config.json 业务路由表
│   └── PC桌面与一碰传开启逻辑.md     launcherType 切换链路、一碰传开关的真实写入方
│
├── scripts/                        真机诊断与模拟脚本（adb shell / Shizuku 即可，多数不需要 root）
│   ├── stylus_float_menu.sh                悬浮球诊断 / 显示 / 隐藏 / 反相保活
│   ├── 复刻真笔机制.sh                      稳定版：监听充电状态 → 写吸附键，模拟取下/磁吸
│   ├── 复刻真笔机制-完整版.sh               激进版：额外补键值指纹、多路径请求 120Hz
│   └── 手写笔帧率、触控率、刷新和悬浮菜单关联测试.sh   A/B/C 分组实验，产出可回传的报告
│
└── reports/
    └── stylus_fps_test_20261005_162826.txt  一次完整的 A/B/C 实验原始输出
```

## 建议阅读顺序

1. **想快速知道结论** → `01-悬浮菜单机制/全逻辑闭环.md` 的 §0 和 §10
2. **想了解怎么让球出现** → 同上 §7「修复与自动化手段」，再看 `scripts/`
3. **想知道为什么 120Hz 没拿到** → 同上 §4，尤其 §4.2「悬浮球与帧率无关的决定性证据」
4. **想做充电控制** → `02-充电机制/荣耀手写笔充电机制.md` §0（结论是不行）与 §4（三条路线及代价）
5. **想做 NFC 碰一碰** → `03-一碰传与PC桌面/一碰传SDK与碰一碰路由研究.md` §3

## 相关代码

本仓库同时包含一个 Android 应用（`app/`），它对上述机制的工程化实现在：

- `app/src/main/java/com/batteryanalyzer/stylus/` — 笔识别、系统笔状态读写、悬浮菜单模拟（A/C 模式）
- `app/src/main/java/com/batteryanalyzer/keymap/` — 系统键值映射与还原
- `app/src/main/java/com/batteryanalyzer/ble/` — BLE 扫描与 Battery Service 读值

## 免责声明

本项目仅供学习与研究。修改系统 Settings 键值属于非官方途径，可能与原厂服务冲突、
导致系统行为异常。请勿用于绕过授权、商业作弊或其他不当用途。
