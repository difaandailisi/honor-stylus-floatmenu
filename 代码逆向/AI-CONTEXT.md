# AI-CONTEXT.md — 给 AI 助手读的结构化事实表

> **用途**：本目录是人工研究的产物，篇幅长、叙述性强、含大量过程性推翻记录。
> 这份文件是它的**机器可读摘要**：只保留可直接使用的事实、状态机与陷阱，便于 AI 在不读全文时也能正确回答。
>
> **使用协议**
> 1. 涉及具体键值、类名、判定条件时，以本文件为准；需要出处时再回原文对应章节核对。
> 2. 本文件中的结论**只对下文「事实锚点」所列的机型与版本成立**。跨机型/跨版本时，必须降级为"待验证"。
> 3. 不要直接执行本文件或 `scripts/` 里的写操作。任何 `settings put` 前先 `settings get` 记原值，并准备回滚。
> 4. 若原文与本文件冲突，**以原文为准**——本文件可能滞后于最新一次实测。

---

## 1. 事实锚点（结论的适用范围）

| 项 | 值 |
|---|---|
| 机型 | CGA-W00（荣耀平板），`ro.build.characteristics=tablet` |
| 笔 | Magic-Pencil 3 = CD54S（主力）；对照 CD54（MP2）、CD52（MP1） |
| 系统 | Android 16 与 Android 17 各一轮实测 |
| 关键包版本 | 蓝牙 17 / 笔管理 11.0.0.7 / floatmenu 19.0.1.310 / 手写笔服务 13.1.0.100 / globalwrite 19.0.1.308 |
| 额外分析对象 | `com.hihonor.android.instantshare` 17.0.0.24、`com.hihonor.android.launcher` |

**可信度标注约定（原文通用）**
- 【实证】= 已从 smali / 资源 / 常量中读到确切类名、方法、字符串
- 【实测】= 真机跑出来的数据
- 【推断】= 由上述两者推导，未逐行确认

---

## 2. 五个包的职责与数据流

```
com.android.bluetooth（蓝牙包，版本 17）           ★ 生产方
  BlePeripheralController：霍尔/磁吸事件、HID 识别、PID 白名单校验
  → bindService 拉起笔管理服务（带 bindReason）
        ↓
com.hihonor.hnbluetoothpencilmanager（11.0.0.7）   ★ 总调度 + 状态机
  MainService / HwPencilController / HwKeyboardController / AGPService
  → 把笔状态写入 Settings.Global（供下层的 floatmenu 读取）
        ↓
com.hihonor.stylus.floatmenu（19.0.1.310）         ★ 悬浮菜单本体
  BTMessageService / StylusFloatBallService / FloatBallWindowView
  → 读 Settings.Global 判定是否显示悬浮球；ContentObserver 监听键值变化
        ↓
com.hihonor.featurelayer.sharedfeature.stylus     笔迹引擎（penkit impl）
com.hihonor.globalwrite                           全局手写 / 批注 / 套索
```

**核心机制一句话**：悬浮球是否显示，**完全由 `Settings.Global` 的键值决定**，而不是由蓝牙连接事件直接驱动。
因此这些键**可被伪造**——这是全部脚本与"假装有一支手写笔"方案的理论基础。

---

## 3. 悬浮球状态机（最关键的一张表）

```
key_bt_pencil_state ∈ {2, 4}   → 显示
key_bt_pencil_state ∈ {0,1,3}  → 隐藏
```

判定链（任一失败都不显示，日志里会打出对应英文原因）：

| 判定 | 条件 | 失败时的日志特征 |
|---|---|---|
| 总开关 | `stylus_support_floating_capsule` 未被显式关闭 | `Stylus floating is disabled.` |
| 笔连接 | `honor_bt_pencil_connection_state != 0` | `Pencil is disConnected, not show float ball !` |
| 笔状态 | `key_bt_pencil_state ∈ {2,4}` | `Pencil state is wrong! state = X` |
| 吸附态 | `key_pencil_attach_state == 1 && key_pencil_attach_type == 1` | `Pencil is Attached, not show float ball!!` |
| 设备场景 | 非无障碍/隔空鼠标/PC投屏/录屏/外折副屏 | `Accessibility Mode.` / `Air mouse mode is on.` / `视频录制中，无法使用悬浮快捷菜单` 等 |

> ⚠️ 反直觉点：**吸附（attach_state==1 且 attach_type==1）时反而"不显示"**。真笔的语义是"取下才出球"。
> 所以"让球出现"需要的是**从吸附切到非吸附**的那次状态跳变，而不是保持吸附。

**两个易错细节**
1. `stylus_support_floating_capsule` 实测本机为 `null` 仍能正常显示——**不要**把 `null` 判为"总开关关闭"。脚本 v1.1.0 已据此修正。
2. 悬浮球有 **45 秒空闲超时**（floatmenu 内部定时器）会自动收起。任何保活方案的轮询间隔必须 `< 30s`（脚本默认 20s）。

---

## 4. 三个决定性"卡点"

| # | 卡点 | 判定材料 | 备注 |
|---|---|---|---|
| 1 | 设备算不算"荣耀笔" | `vid ∈ {0x12d1, 0x09c6}` 且 `pid` 在 `HnBlePeripheralCfgpencilpid` 白名单内 | 白名单是**十进制逗号分隔**字符串 |
| 2 | 磁吸/霍尔能否触发一次判定 | 霍尔传感器 `TYPE_HALL(0x10002)` + `BlePeripheralController$HallListener` | 实机传感器为 `bu52053nvx`，`type: qti.sensor.hall_effect(65538)` |
| 3 | 悬浮球显不显示 | `key_bt_pencil_state ∈ {2,4}` | 见第 3 节 |

**PID 白名单的致命细节**：`HnBlePeripheralCfgpencilpid` / `HnBlePeripheralCfgkeyboardpid` /
`HnBlePeripheralCfgkbsuphostdicpid` / `HnBlePeripheralCfgkbname` **四个键任一为空**，
整个白名单即失效，日志为 `log.e("updateConfig: invalid config")`。补齐时必须四个一起给。

---

## 5. Settings / 属性 / 组件速查

**Settings.Global — 悬浮菜单判定相关**
```
stylus_support_floating_capsule      总开关（null 不等于关闭）
key_bt_pencil_state                  状态机：2/4 显示，0/1/3 隐藏
key_pencil_attach_state              吸附状态（1 = 吸附）
key_pencil_attach_type               吸附类型（与上者同为 1 才算吸附）
key_pencil_sub_modelid               子型号
```

**Settings.Global — 笔信息（只读使用为主）**
```
honor_bt_pencil_connection_state     连接状态（0 = 未连接）
honor_bt_pencil_battery_level        电量
honor_bt_pencil_charging_state       充电状态（"0"/"1"）
honor_bt_pencil_mac_address / _serial_id / _hardware_version / _software_version
honor_bt_pencil_system_version / _model_number / _submodel_id / _ota_type
honor_stylus_wake_state
```

**Settings.Global — 蓝牙包白名单**
```
HnBlePeripheralCfgpencilpid          笔 PID 白名单（十进制逗号分隔）★
HnBlePeripheralCfgkeyboardpid        键盘 PID
HnBlePeripheralCfgkbsuphostdicpid    键盘宿主 DID
HnBlePeripheralCfgkbname             键盘名
```

**Settings.System**
```
stylus_enable / stylus_state_introduce
min_refresh_rate / peak_refresh_rate   锁刷新率用
```

**系统属性**：`msc.sys.oemName`、`msc.tp.pencil.sleep_enable`、`bluetooth.keyboard.state`、`ro.runmode`、`ro.build.characteristics`

**组件与权限**
```
笔管理 MainService       权限 ACCESS_HONOR_PENCIL_MANAGER_SERVICE
BTMessageService         权限 com.hihonor.stylus.floatcapsule.STYLUS_MENU
StylusFloatBallService   com.hihonor.stylus.floatmenu/.floatball.service.StylusFloatBallService
悬浮球窗口               FloatBallWindowView
```

**广播 Action**
```
com.hihonor.bluetooth.action.HID_CONNECTION_STATE_CHANGE
com.hihonor.nearby.peripheral.action.STATE_CHANGED
com.hihonor.stylus.BT_MESSAGE_SERVICE
com.hihonor.stylus.floating.capsule
com.honor.bluetooth.HOST_TRIGGER_DISCONNECT
```

**蓝牙包 → 笔管理服务的 bindReason（`onGetBindInfo` 的 `reason` 字段）**

| reason | 分支 | 触发场景 |
|---|---|---|
| `0` | `retryBind` | 应用异常退出后被重新拉起 |
| `1` | `pingSucc` | 磁吸充电圈 Ping 成功 |
| `2` | `hallChange` | 霍尔/磁吸状态变化（`intent.getIntExtra("hallState", 0)`） |
| `3` | `connect` | HID 连接，读 vid/pid 认笔 |

---

## 6. 帧率真相（这条最容易答错）

- **拿到悬浮球 ≠ 拿到 120Hz。** 这是本文档花最多篇幅证伪的直觉。
- 笔上报的刷新率字段是 **60**，但实测可达 **120**——说明该字段不是刷新率的决定因素。
- 想稳定 120Hz，正确做法是直接锁 `min_refresh_rate` / `peak_refresh_rate`，
  或（仅 root）通过 `AGPService` 下发 stylus 场景。后者需要 root 且事务码存疑，**未验证**。
- 悬浮球与帧率无关的实测证据：`01-悬浮菜单机制/全逻辑闭环.md` §4.2。

---

## 7. 充电机制（结论：应用层做不到"按电量阈值停充"）

- 全包**不存在**"读笔电量 → 比阈值 → 开关充电"的代码。唯一的电量阈值逻辑是低电量**弹窗**（10/5/2）。
- `HwKeyboardController` 里有常量 `CHARGE_PATH = "sys/class/hw_power/charger/wireless_tx/tx_open"`
  与 `START/STOP_REVERSE_CHARGING`，但**只声明无引用——死代码**。
- 真正决定充不充电的是**键盘固件里的充电 IC + 内核策略**。应用只是"翻译官 + 上报员"。
- 应用层唯一碰得到的决策点：键盘查询**宿主是否在充电**，应用回发 `mHostChargingState`
  （`onGetChargingStateInqEvent()`，反射 `BatteryManager.isChargingEx()`）。

**键盘 → 应用 cmd id（`data[1]`）**

| id | 处理方法 | 含义 |
|---|---|---|
| `1` | 另一分支 | 充电开始状态查询/上报 |
| `2` | 另一分支 | 充电相关 |
| `4` | `onGetChargingStateInqEvent()` | **键盘查询宿主是否在充电** |
| `5` | `onGetAttachPencilInfo()` | 笔吸附信息（MAC/model/submodel） |
| `8` | `getInsertUsbPromptEvent()` | USB 插入提示 |
| 其它 | `log.w("unknown command id")` | 忽略 |

> 若用户要求"≥90% 停充"，正确回答是**现有代码做不到**，然后给三条路线的代价对比
> （原文 §4）：改内核/root、伪造宿主充电状态骗键盘、或在笔侧硬件层面处理。

---

## 8. 一碰传与 PC 桌面（独立于手写笔）

**一碰传 SDK（`com.hihonor.android.instantshare` 17.0.0.24）**
```
入口        j1.b（单例 d()） 实现 j1.d  回调 j1.a
绑定服务    com.hihonor.systemserver/.onetouchshare.OneTouchShareService
绑定 Action com.hihonor.onetouchshare.action.SDK_BIND_ONE_TOUCH_SERVER
AIDL        N2.a = X(int,String,N2.b,Bundle,String) / a(OneTouchShareData) / i1()
            N2.b = Q(int,int,String) / x1(int,String)
数据模型    com.hihonor.systemserver.onetouchshare.common.OneTouchShareData
Builder     j1.b$a { a:int, b:List, c:title, d:Bundle, e:String, f:thumbnailUri, g:thumbnail[], h:String }
开关状态键  Settings.Global.one_touch_share_setting_state   ← 本应用只读
开关服务    com.hihonor.systemserver/.onetouchshare.SwitchService + action CHANGE_SWITCH
支持属性    msc.super_device.one_touch (+ touchCapability)
```

**碰一碰业务路由（`assets/nfc_tag_share_config.json`）**

| biz | 值 | 业务 |
|---|---|---|
| `doll` | 1002 | 现慧空间 |
| `WirelessCasting` | 1003 | 无线投屏 |
| `printer` | 1004 | 打印 |
| `HonorAuto` | 1005 | 车机 |
| `meituan` | 1006 | 美团 |

字段：`card.type`、`card.triggerType`、`launchMode(activity|service|callback_touch)`、
`appIdKeyInNfc="app"`、`apps[].{pkg,class,action,dpLink}`、`prePull`、`intentExtras[source=nfcTag]`。

> ⚠️ 关键结论：**本应用不是 NFC 标签的读取方**。开关的真正写入方是 system_server，
> 应用只读状态。因此"用实体 NFC 标签直接触发碰一碰"在这个版本上**做不到**，
> 自建方案应走"自己的 App + 官方 SDK"，见原文 §3.2 的伪代码。

**PC 桌面（`com.hihonor.android.launcher`）**
```
组件        com.hihonor.android.launcher/.pc.PCLauncher
launcherType  合法值 1 / 4 / 8（8 = PC 桌面）
Secure 键   launcher_type_before_pc_mode（默认 1，只接受 1/4）
前置条件    平板 + cust 配置 xml/14x8/hn_launcher_default_workspace_pclauncher.xml
核心类      PCLauncher / PCLauncherPolicy / HnDesktopUtils / HomeSwitcher
```
PC 桌面的开启由**本应用自己执行**（改组件状态 + 启动 HOME Intent），官方流程会设重启标记。

---

## 9. scripts/ 命令接口

所有脚本都是 `#!/system/bin/sh`，**adb shell / Shizuku(uid 2000) 即可运行，root 非必需**（个别命令例外）。
统一用法：`sh <脚本> help` 查看完整说明。

| 脚本 | 命令 | 作用 | 风险 |
|---|---|---|---|
| `stylus_float_menu.sh` | `check` / `status` | 只读诊断各判定项 | 无 |
| | `verify` / `dump` / `diag` | 窗口、组件、状态详情 | 无 |
| | `show` / `hide` | 强行让球显示/隐藏 | 写 `key_bt_pencil_state`，会被真实事件刷回 |
| | `invert [间隔]` | 低频"反相器"保活 | 会覆盖 hide；建议 20s |
| | `backup` / `rollback` | 备份 / 还原相关键原值 | 无 / 还原 |
| | `fps` / `watch` / `sendstate` / `logcat` | 刷新率快照、持续观察、模拟上报、抓日志 | 只读为主 |
| `复刻真笔机制.sh` | `run [间隔]` / `once` / `invert` / `check` | 监听 `honor_bt_pencil_charging_state` → 写 `key_pencil_attach_state/_type`，模拟取下/磁吸 | **单向结构**：监听 A 写 B；绝不可监听 A 写 A，否则无限震荡 |
| `复刻真笔机制-完整版.sh` | `run` / `once` / `check` / `reset` | 在稳定版基础上补键值指纹、多路径请求 120Hz、尝试 AGPService | 含【未验证】项，可用 `reset` 撤销 |
| `手写笔帧率、触控率、刷新和悬浮菜单关联测试.sh` | `report` / `exp` | A/B/C 分组实验全流程 | 只改悬浮球判定，不碰蓝牙 |
| | `ball-show` / `ball-hide` / `pen` / `fps` / `modes` / `keys` / `devices` | 单项取证 | 只读 |
| | `sample` / `guide` / `idle` / `watch` | 采样、引导、空闲基线、持续观察 | 只读 |

产出：测试脚本会写 `/sdcard/Download/stylus_fps_test_<时间戳>.txt`，`reports/` 下有一份样例。

---

## 10. 已被实测证伪 / 踩过的坑（避免重复劳动）

| 曾经的判断 | 实测结果 |
|---|---|
| 抓 logcat 按"九条判定 → 日志关键字"定位卡点 | ❌ **本机 0 命中**，荣耀对系统 App 日志做了限流。这条排障路在这台设备上不可用 |
| 悬浮球出现就能拿到 120Hz | ❌ 无关。见第 6 节 |
| `stylus_support_floating_capsule == null` 说明总开关关闭 | ❌ 仍可正常显示，不能判 FAIL |
| "狂点配对/取消配对"能把球卡出来 | ⚠️ 是**碰运气**，会随机命中一次状态跳变，不能作为方案 |
| CD52（MP1）只差一个白名单就能用 | ❌ 除白名单外还有 `isPencilCreateBondRejectedByFoldScreen()` 折叠屏判定、`getHidDeviceInfo()` 取不到信息、新版移除老私有协议三重问题；**adb/Shizuku/root 都救不回**书写能力 |

---

## 11. 未确认项（不要当成已知事实）

1. `OneTouchShareService` / `SwitchService` 的内部实现不在分析范围内（在 system_server / framework 侧），
   所以"SDK 是否对第三方开放""是否需要额外签名授权"**无法从静态分析确认**。
2. `N2.a.X(int,String,N2.b,Bundle,String)` 五个参数的精确语义未逐行确认。
3. `AGPService` 的事务码与令牌存疑，`service call` 路径**未验证**。
4. `triggerType` 的完整取值表未穷举（只见 1002~1006）。
5. 上游"谁把 NFC 数据交给 `NfcTagShareManager`"未确认。
6. 跨机型（非平板、非 CGA-W00、非该批版本）时，**第 3 节状态机与第 4 节卡点全部需要重新验证**。

---

## 12. 若你要基于此写代码

- 本仓库的 Android 应用是本研究的工程化实现，优先读这些包而不是重写：
  `app/src/main/java/com/batteryanalyzer/stylus/`（笔识别、系统笔状态、悬浮菜单模拟 A/C 模式）、
  `.../keymap/`（键值映射与还原）、`.../ble/`（扫描与电量读取）。
- 写系统键值前必备份原值（脚本的 `backup` / `rollback` 已实现）。
- 需要的权限：`WRITE_SECURE_SETTINGS`，实际通过 Shizuku 或 adb 授予。
- 判定逻辑要与**真实事件**共存：系统会持续回写这些键，伪造值会被刷回，因此需要"看门狗"式低频校正，
  而不是一次性写入。
