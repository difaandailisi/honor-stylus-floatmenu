# 华为 vs 荣耀 手写笔体系对比

> **本文性质**：对**两套完整实现**（华为、荣耀）的逐包对比。
> 数据来源：静态分析（类/方法/常量/字符串均标注出处）+ 你的真机实测（见 `思考.md`、`README.md` 第 13 节）。
> 荣耀侧的完整链路见另一份文档 `全逻辑闭环.md`。

---

## 0. 对比对象

### 华为一套（minSdk 28 / targetSdk 28，Android 9 时代）

| 包名 | 名称 | 版本 | 大小 | 类数 | 签名 |
|---|---|---|---|---|---|
| `com.huawei.hwbluetoothpencilmanager` | （蓝牙手写笔管理） | 11.0.0.1 | **115 KB** | **140** | `603ac6a5…` |
| `com.huawei.stylus.floatmenu` | 手写笔悬浮窗 | 11.1.0.165 | 4.6 MB | 1825 | `603ac6a5…` |
| `com.huawei.featurelayer.sharedfeature.stylus` | 华为手写笔服务 | 11.1.0.329 | 4.8 MB | 1196 | `01e1fc81…` |
| `com.huawei.hiwrite` | 华为全局手写 | 11.1.0.348 | 8.6 MB | 2662 | `603ac6a5…` |

### 荣耀一套（minSdk 29~32 / targetSdk 35~36）

| 包名 | 名称 | 版本 | 类数 | 签名 |
|---|---|---|---|---|
| `com.hihonor.hnbluetoothpencilmanager` | （蓝牙手写笔管理） | 11.0.0.7 | 175 | `483013ce…` |
| `com.hihonor.stylus.floatmenu` | 手写笔 | 19.0.1.310 | 5850 | `483013ce…` |
| `com.hihonor.featurelayer.sharedfeature.stylus` | 手写笔服务 | 13.1.0.100 | 4830 | `483013ce…` |
| `com.hihonor.globalwrite` | 全局手写 | 19.0.1.308 | 11341 | `483013ce…` |

> 另有一个共同依赖：**蓝牙应用**（华为机器上是 `com.android.bluetooth` 的华为定制版；你提供的 `蓝牙_17.APK` 是 **荣耀版**，里面有 `com.hihonor.bluetooth.btdevicecircle.BlePeripheralController` 负责笔/键盘的霍尔与 HID 识别）。

---

## 1. 结论速览

| 维度 | 华为 | 荣耀 | 证据 |
|---|---|---|---|
| **刷新率控制** | **完全没有** | **有**（唯一通道） | 华为四包 `AGPService` / `refreshRate` **全 0 命中**；荣耀笔包 `setStylusScenceFps()` → `AGPService` transact `0x7a` |
| **笔迹引擎读刷新率** | 没有 | 有（只读） | 华为笔迹包 `getRefreshRate` **0 命中**；荣耀 `HnStylusViewImpl` → `Display.getRefreshRate()` → `HnStrokeEstimateImpl.setRefreshRate(F)` |
| **霍尔监听位置** | **笔包内部** | **蓝牙应用里** | 华为 `HwPencilController$HallListener`；荣耀 `BlePeripheralController$HallListener`（蓝牙 APK） |
| **霍尔位掩码** | `0x30`（笔位） | `0x34`（笔+键盘位），`PENCIL_HALL_OFFSET=0x10` | 两边常量 |
| **吸附状态写入者** | **笔包自己写** | **框架/蓝牙写**（笔包只读） | 华为：`huawei_bt_pencil_hall_state = "10"/"01"/"00"`；荣耀：`key_pencil_attach_state` 五个包均只读 |
| **通知悬浮菜单的机制** | **广播** | **bindService + Messenger** | 华为：`HWPENCIL_CONNECTION_STATE_CHANGED` 等；荣耀：`startPencilBallService(state)` → `Message(what=1,arg1=state)` |
| **悬浮菜单显示入口** | 系统/外部拉起 `FloatMenuService`（带 `positionX/positionY/prepareStatus`） | 笔包通知后由 `StylusFloatBallService.t(state)` 状态机决定 | 两边代码 |
| **悬浮菜单开关键** | `Settings.System.stylus_enable` | 同名 `stylus_enable` + 自加 `stylus_support_floating_capsule`(Global) | 两边字符串 |
| **新旧笔协议** | **显式保留两套**：`OLD_MODEL_ID_DATA_LENGTH=0x20` / `NEW_…=0x21` | 只留一套（无 OLD/NEW 常量） | 类字段对比 |
| 鼠标外设 | 有 `mouse/HwMouseController` | 无 | 类列表 |
| 键鼠跨设备互控 | 有 `hwperipheralscrossing/` + AIDL | 无 | 类列表 |
| 异常自恢复 | `PeripheralExceptionManagement` / `abnormalRecovery()` / `recoveryGattConnect()` | 无（只有重连计数） | 类列表 |
| 笔包运行身份 | **`sharedUserId=android.uid.bluetooth`**（与蓝牙栈同 UID） | 独立 UID，靠 `signatureOrSystem` 权限 | 两边 Manifest |
| OTA 实现 | ST `BlueSTSDK` + `StylusUpgradeService` | 自有 `OtaService` + `OTAConfirmDialogService` | 两边 Manifest/类 |

---

## 2. 逐层细节对比

### 2.1 吸附/磁吸：两代实现完全不同

**【华为】笔包自己监听霍尔 + 自己写设置**（`HwPencilController$HallListener.onSensorChanged`，280 行全读）：

```java
if (e.sensor.getType() != 0x10002 /*TYPE_HALL*/) { log.w("not hall sensor"); return; }
int hall = (int) e.values[0] & 0x30;             // PENCIL_HALL_OFFSET = 0x30
log.i("onSensorChanged: " + hall + ", pre state: " + mPencilPreHallState);

if (hall == 0x10) {  // PENCIL_HALL_ATTACH_RIGHT
    log.i("onSensorChanged: Right …");
    write("huawei_bt_pencil_hall_state", "01");
} else if (hall == 0x20) {  // PENCIL_HALL_ATTACH_LEFT
    log.i("onSensorChanged: Left …");
    write("huawei_bt_pencil_hall_state", "10");
} else if (hall == 0) {     // PENCIL_HALL_REMOVE
    log.i("onSensorChanged: Remove …");
    write("huawei_bt_pencil_hall_state", "00");
} else {
    log.i("onSensorChanged: Invalid state …");
}
// 最后：只在值变化时更新 mPencilPreHallState，否则打 “pencil hall state not changed”
```
键语义：`"01"`=右侧吸附、`"10"`=左侧吸附、`"00"`=已取下。
另外笔包还自己维护一个"辅助连接状态"键：
```java
SETTINGS_PENCIL_AUX_STATE = "huawei_bt_pencil_aux_state"   // "connected"/"disconnected"/"ping"/"null"
```
它出现在 `onPencilAttached` / `onPencilConnected` / `onPencilPingSucc` / `onPencilRemoved` / `onDestroy` 中。

**【荣耀】磁吸从笔包搬到了蓝牙应用**（`com.android.bluetooth` 的 `BlePeripheralController$HallListener`）：
```java
int pencilHall = (int) e.values[0] & 0x34;   // 0x30 笔位 + 0x4 键盘位
if (pencilHall != 0 && !mIsHwPencilControllerServiceStarted)
    bindService(pencilHall, "hallChange");   // 只是把笔包服务拉起来
int kbHall = (int) e.values[0] & 0x4;
updateHallStateProp(kbHall == 0 ? "false" : "true");   // 写系统属性 bluetooth.keyboard.state
```
而笔包收到 `bindReason="hallChange"` 后（`MainService.onGetBindInfo` → `onHallChanged`）**只处理键盘**：
```java
int hallState = (byte) intent.getIntExtra("hallState", 0);
hallState &= 0x4;                    // 只留键盘位
if (hallState == 0x4) mHwKeyboardController.onHallAttached();
// 没有笔的分支
```
→ 所以荣耀侧的"笔吸附状态"变成了 `key_pencil_attach_state` / `key_pencil_attach_type`，由**框架/system_server**写入（荣耀五个包都只读它）；而华为侧是**笔包自己写 `huawei_bt_pencil_hall_state`**。

### 2.2 笔包总调度：两种分发方式

**【华为】笔包 ← 广播**
```java
registerPencilBroadcast() 监听：
    com.huawei.nearby.peripheral.action.STATE_CHANGED
    com.huawei.bluetooth.action.HID_CONNECTION_STATE_CHANGE
    android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED   ← 标准 HID 广播（荣耀版没有）
主动发出：
    broadcastConnectionStateChanged(I)  → com.huawei.bluetooth.action.HWPENCIL_CONNECTION_STATE_CHANGED
    broadcastWakeUpStateChanged(I)      → com.huawei.bluetooth.action.HWPENCIL_WAKEUP_STATE_CHANGED
    broadcastBatteryChanged(I)          → com.huawei.bluetooth.action.HWPENCIL_BATTRY_LEVEL_CHANGED
```
对悬浮窗的主动动作只有两个：
```java
startPencilAppService() → startService(com.huawei.stylus.floatmenu/
        .service.BTPencilBatteryService, action=com.huawei.stylus.bt.pencil.battery)
checkLowBattery()       → startActivity(action=com.huawei.stylus.action.LOW_BATTERY_DIALOG,
                                         package=com.huawei.stylus.floatmenu)
```
另：`PENCIL_AUX_*`、`onGetDubleClickCapacity()`（写 `huawei_bt_pencil_double_click_state`）、`abnormalRecovery()`、`recoveryGattConnect()`。

**【荣耀】笔包 ← bind 权限方传入的 Intent extras，且自己是"总调度"**
```java
MainService.onGetBindInfo(Intent) 按 bindReason 四路分派：
    "connect"(3)    → onDeviceConnect()   // vid/pid → isHwPencilDevice → setHidDeviceInfo
    "hallChange"(2) → onHallChanged()     // 只处理键盘
    "pingSucc"(1)   → HwPencilController.onPencilPingSucc()
    "retryBind"(0)  → 进程被拉起后重连
对悬浮菜单：
    startPencilBallService(state) → bind BTMessageService → Message(what=1, arg1=state)
```

### 2.3 悬浮菜单：显示入口与判定的差异

**【华为】`com.huawei.stylus.floatmenu`**
- 核心服务 `FloatMenuService`（`exported=true`，`permission=android.permission.REBOOT` → **只有系统级进程能拉起**）：
  ```java
  onCreate(): new k()（k = PhoneStateListener，来电时收起）；TelephonyManager.listen(...)
  onStartCommand(intent): 读 extras positionX / positionY / prepareStatus
  onConfigurationChanged(): 交给 MenuViewManager 重算屏幕尺寸
  onDestroy(): MenuViewManager.go()（收起）
  ```
- 显示/收起由 **`view/n`（MenuViewManager，单例 `getInstance()`）** 负责：
  ```java
  ur():  log("showMenu")  →  WindowManager.removeViewImmediate(...) 然后 addView(...)     ← 真正显示
  go():  log("float menu has removed from Window")  →  removeView                         ← 真正收起
  i(int,int,int): log("startMenu prepareStatus = " …)  → 显示入口（461 行的核心逻辑）
  c(boolean):    "onTalkBackStateChange state = " → 无障碍状态变化时收起
  ```
- 它读的笔相关设置键：`huawei_bt_pencil_connection_state`、`huawei_bt_pencil_charging_state`、`huawei_bt_pencil_battery_level`、`_mac_address`、`_hardware_version`、`_system_version`、`_double_click_state`、`_ota_type`
- ⚠️ **不读** `huawei_bt_pencil_hall_state`（0 命中）；也不监听 `HWPENCIL_*` 广播（`HWPENCIL` 仅 1 处且属 OTA 类）
- 开关：**`Settings.System.stylus_enable`**（在 `androidx/core/app/e`、`SettingsSearchProvider`、`HandWritingIntroducePage` 中出现）
- 清单里还有：`BTPencilBatteryService`、`PencilTimeOutService`、`IntroduceMainActivity`（设置入口）、`DoubleClickActivity`、`OTAUpgradeActivity` / `OTAConfirmDialogService`、`LowBatteryDialogActivity`、`TimeOutDialogActivity`、`HiWriteDialogActivity`、`SettingsSearchProvider`、ST `BlueSTSDK` 升级服务

> **要点**：华为这套是"**系统在合适时机拉起 FloatMenuService 并告知位置/状态**，悬浮窗自己读设置键决定内容"。笔包**不直接指挥**悬浮窗显示。

**【荣耀】`com.hihonor.stylus.floatmenu`**
```java
BTMessageService(what=1,arg1=state) → F1/a.a() → 写 key_bt_pencil_state → startService(StylusFloatBallService)
StylusFloatBallService:
    t(state): 0/1/3 → 隐藏；2/4 → 走 p()
    h():      key_pencil_attach_state 变化 → 吸附则隐藏，非吸附则 p()
    p():      九条判定（开关/无障碍/投屏/隔空鼠标/折叠屏/连接状态/吸附状态/state∈{2,4}/录屏）
```
→ 荣耀是**笔包主动 bind 悬浮菜单服务并推状态**，悬浮菜单自己有完整状态机。

---

### 2.4 笔迹引擎（featurelayer / sharedfeature.stylus）

| | 华为 11.1.0.329 | 荣耀 13.1.0.100 |
|---|---|---|
| 类数 | 1196 | 4830 |
| `getRefreshRate` | **0 命中** | 有：`HnStylusViewImpl` / `HnPaintView` / `HnNoteWritingViewPad` / `AmplifierPageView` → `HnStrokeEstimateImpl.setRefreshRate(F)` |
| `AGPService` | **0 命中** | **0 命中** |
| 含义 | 笔迹引擎**不关心刷新率** | 引擎会**读**当前刷新率来调整笔迹预测（低延迟） |

### 2.5 全局手写（hiwrite / globalwrite）

| | 华为 `com.huawei.hiwrite` 11.1.0.348 | 荣耀 `com.hihonor.globalwrite` 19.0.1.308 |
|---|---|---|
| 类数 | 2662 | 11341 |
| `AGPService` | **0 命中** | **0 命中** |
| `refreshRate` | **0 命中** | `setFrameRate` / `refreshRate` 亦无（只有 UI 灯效字段） |

---

## 3. 三条结论（对应你的三个问题）

### ★ Q1：手写笔到底会不会触发 120fps？

**这是荣耀的"私有能力"，华为整套没有。**

- 华为四包：`AGPService` = 0、`refreshRate` = 0 → **连读都不读**；
- 荣耀：唯一通道是笔包的
  ```java
  HwPencilController.setStylusScenceFps(int)
     → ServiceManager.getService("AGPService") → writeInterfaceToken("AGPService")
     → writeInt(fps) → binder.transact(0x7a)
  // 触发点：笔连接=60 / 笔唤醒=60 / 笔休眠=0(复位) / 笔断开=0
  ```
- 而悬浮菜单（荣耀 floatmenu）**没有任何 fps 代码** → 你实测的"球出现=120Hz"是**伴生**：两者同源于"笔被笔包判定为已连接/唤醒"。
- 结论：**想让荣耀机器上笔一连接就 120Hz，真正要做的是"让笔被笔包接管并产生连接/唤醒事件"，而不是让球出现**。华为方案里根本没有这个动作，所以"笔→刷新率"在华为体系里**不存在**。

### ★ Q2：为什么华为 M-Pencil（CD54/CD54S）在荣耀机器上没有自动悬浮菜单？

两套体系的通知机制不同，导致**卡点不同**：

| | 华为 | 荣耀 |
|---|---|---|
| 谁拉起悬浮窗 | 系统/外部（`FloatMenuService` 带 `prepareStatus/positionX/positionY`） | 笔包 `startPencilBallService(state)` → bind `BTMessageService` |
| 传什么 | 广播 + 位置/状态 | `Message(what=1, arg1=state)` |
| 悬浮窗怎么判 | 自己读 `huawei_bt_pencil_*` 与 `stylus_enable` | 自己的状态机 `t()` + `p()` 九条判定 |

荣耀侧的卡点（已在 `全逻辑闭环.md` 逐行定位）：
```text
HID 连接 → vid=getHidDeviceInfo(dev,0)、pid=getHidDeviceInfo(dev,1)
   ├─ vid==0 或 pid==0                     → 不当作荣耀笔，只 250ms 重试一次
   ├─ HnBlePeripheralCfgpencilpid 为空      → 白名单失效
   └─ isHnPencilDevice(vid,pid)==false      → 跳过
只有全过 → 才 setHidDeviceInfo() → 才可能 GATT 连接 → 才有 state=2 → 球的 p() 才可能通过
```
→ **CD54/CD54S 就是卡在这条链上**（vid/pid 取不到，或 PID 不在 `HnBlePeripheralCfgpencilpid`）。
华为体系里没有"PID 白名单 + bind Message"这一套，所以**不存在同样的卡点**（它靠系统拉起 + 广播 + 自己读设置键）。

### ★ Q3：CD52 为什么在荣耀新版连不上？

**代码级原因：荣耀新版删掉了旧协议分支。**

| 常量 | 华为 11.0.0.1 | 荣耀 11.0.0.7 |
|---|---|---|
| `OLD_MODEL_ID_DATA_LENGTH` | **0x20（32）** | **不存在** |
| `NEW_MODEL_ID_DATA_LENGTH` | **0x21（33）** | **不存在** |
| `onMagneticStateChanged` 解析 | `bundle.getInt(...)`（宽松） | `getByteArray` + 长度必须 32 + `data[10]==2`（硬） |

华为**显式保留"老/新两套"**，因此能处理 CD52 的老格式；荣耀只剩单一路径 + 硬字节校验 → CD52 的磁吸/Ping 事件被直接丢弃。
荣耀上的实测表现：`battery = -1`、型号残留、`ExternalStylusPresence: NONE`。

> ⚠️ **【真机修正】不要把"HID 无 STYLUS"当成罪证**
> 后来在**华为平板 BAH3-W59** 上用**同一支 CD52**（MAC 同为 `70:61:ee:03:83:52`）做了对照：
> ```
> 7: M-Pencil   Classes: 0x80000009  (= EXTERNAL|CURSOR|KEYBOARD)
> ```
> **原生环境里同样没有 STYLUS** → 这是 CD52 自身设计，不是故障，也不是荣耀造成的。
> 笔迹走 `huawei,ts_pen` / `honor,ts_pen`（屏幕触控通道），从来不经过 HID STYLUS。
> 故 CD52 的死因**只剩一条**：GATT 私有协议解析（荣耀删掉了 `OLD_MODEL_ID_DATA_LENGTH` 分支）。

---

## 4. 能不能"互通/移植"？

| 事项 | 结论 |
|---|---|
| 直接把华为那套装到荣耀机器 | ❌ 不可行。包名、动作、权限、设置键全不同（`huawei_*` vs `honor_*`），签名也不同（`603ac6a5…` vs `483013ce…`） |
| 荣耀用华为的键 | ❌ 荣耀笔包只认自己的键（`honor_bt_pencil_*`、`HnBlePeripheralCfg*`） |
| 借鉴"华为思路"修复荣耀 | ⚠️ 有限：可以借鉴"**把 attach 状态自己写**"（华为笔包自写 `huawei_bt_pencil_hall_state`）→ 对应荣耀侧就是 `settings put` 伪造 `key_pencil_attach_state`（我们已经这么做） |
| 把荣耀的 120Hz 能力搬到华为 | ❌ 需要 `AGPService`（系统服务）与笔包改代码，无 root/签名无法实现 |
| 华为笔包与蓝牙栈同 UID | ⚠️ 这是华为的设计（`sharedUserId=android.uid.bluetooth`）；荣耀是独立 UID + 签名权限，所以荣耀侧任何"伪装成蓝牙栈"的做法都不成立 |

---

## 5. 仍未读 / 待验证（诚实标注）

1. **华为 `MenuViewManager.i(int,int,int)` 461 行**未读 —— 这是华为悬浮窗"startMenu"的完整显示条件（含 `prepareStatus` 语义）。
2. **谁拉起 `FloatMenuService`** 未确认（华为悬浮窗内部无引用，需 framework/SystemUI 侧才能定论）。
3. **华为 `FloatViewReceiver`** 监听哪些广播未读。
4. **`stylus_enable` 的读写方**未确认（华为悬浮窗读它，谁写？大概率是华为的设置应用/框架）。
5. 华为 `onMagneticStateChanged` 全文（93 行，用 `getInt` 那条）未逐行读 —— 可确认它对老笔的容忍度。
6. 华为 `featurelayer`/`hiwrite` 是否通过**其他手段**（如 `WindowManager.LayoutParams.preferredRefreshRate`）间接影响刷新率 —— 目前只查了字符串层面的 `refreshRate`/`AGPService`。

---

## 6. 四份文档的关系

| 文件 | 内容 |
|---|---|
| `思考.md` | **你的真机实测原始记录**（荣耀平板） |
| `README.md` | 荣耀侧的**原理 + 实测修正**（第 13 节） |
| `全逻辑闭环.md` | 荣耀五包**完整链路**（生产→消费，逐行出处） |
| `华为vs荣耀对比.md` | **本文**：两套实现的逐项对比与结论 |
| `stylus_float_menu.sh` / `手写笔帧率…测试.sh` | 配套工具（若仍在目录中） |

---

*本文所有结论均以「类/方法/常量/字符串命中」为唯一依据；凡属推断处均标注了不确定性。*

---

## 7. 键名对照 与「可伪造性」三层模型

> 本节回答一个实操问题：**能不能“假装有一支笔”？**
> 答案分层：**荣耀能骗到一层，华为几乎一层都骗不到；两边都骗不到系统层。**

### 7.1 键名总对照（同一语义、两套命名）

| 语义 | 荣耀键 | 华为键 | 所在表 | 写入者 | 读取者 |
|---|---|---|---|---|---|
| 笔连接状态 | `honor_bt_pencil_connection_state` | `huawei_bt_pencil_connection_state` | Global | 笔包 | 悬浮窗（ContentObserver） |
| 笔电量 | `honor_bt_pencil_battery_level` | `huawei_bt_pencil_battery_level` | Global | 笔包 | 悬浮窗 |
| 充电状态 | `honor_bt_pencil_charging_state` | `huawei_bt_pencil_charging_state` | Global | 笔包 | 悬浮窗 |
| **吸附状态** | `key_pencil_attach_state`(1/0) + `key_pencil_attach_type`(1/0) | **`huawei_bt_pencil_hall_state`**（三态字符串 `"10"`=左吸附 / `"01"`=右吸附 / `"00"`=取下） | Global | 荣耀：**框架/system_server**<br>华为：**笔包自己** | 荣耀：悬浮菜单 `LE1/f` + `LF1/a`<br>华为：**无人读**（0 命中） |
| **悬浮球状态机** | `key_bt_pencil_state`（0/1/2/3/4；**2/4 才显示**） | ❌ **不存在** | Global | 荣耀悬浮菜单 | 荣耀 `p()` |
| 辅助连接态 | ❌ | `huawei_bt_pencil_aux_state`（`connected`/`disconnected`/`ping`/`null`） | Global | 笔包 | 笔包自身 |
| 悬浮菜单开关（自造） | `stylus_support_floating_capsule` | ❌ | Global | 荣耀悬浮菜单设置页 | 荣耀 `F1/c.e()` |
| 悬浮菜单开关（原有） | `stylus_enable` | `stylus_enable` | **System** | 设置页（`SettingsEx$System.putIntForUser`） | 悬浮窗 |
| 其它元数据 | `honor_bt_pencil_mac_address` / `_serial_id` / `_hardware_version` / `_software_version` / `_system_version` / `_model_number` / `_submodel_id` / `_ota_type`、`honor_stylus_wake_state`、`key_pencil_sub_modelid` | `huawei_bt_pencil_mac_address` / `_serial_id` / `_hardware_version` / `_software_version` / `_system_version` / `_model_number` / `_double_click_state` / `_ota_type` | Global | 笔包 | 悬浮窗 / 设置页 |

> 注意：`stylus_enable` **在 System 表**（华为侧默认值 = **2**，三态），荣耀沿用了这个键名；
> 荣耀自造的 `stylus_support_floating_capsule` 在 Global 表，且真机实测为 `null`。

### 7.2 三层「有笔」模型

| 层 | “有笔”的含义 | 谁能改变它 | 荣耀 | 华为 |
|---|---|---|---|---|
| **L1 读取层** | 悬浮窗内部状态、设置页显示的“已连接 / 电量 / 型号” | 写 Settings 键 | ✅ 能 | ⚠️ **能**（但只在设置页/内部状态里看得见） |
| **L2 显示层** | 屏幕上真的出现悬浮菜单 / 悬浮球 | 触发显示流程 | ✅ **能**（判定就在 L1） | ❌ **不能** |
| **L3 系统/输入层** | 蓝牙配对记录、HID 设备、`ExternalStylusPresence`、笔压/倾斜数据 | 只有真实硬件事件（或 root 注入） | ❌ 不能 | ❌ 不能 |

### 7.3 华为侧“假装有笔”（可执行命令）

```bash
settings put system stylus_enable 1                        # 开关（注意是 System 表）
settings put global huawei_bt_pencil_connection_state 2    # 让读它的地方认为“已连接”
settings put global huawei_bt_pencil_battery_level 88
settings put global huawei_bt_pencil_charging_state 0
settings put global huawei_bt_pencil_hall_state 00         # "00"=取下；"10"/"01"=吸附
settings put global huawei_bt_pencil_aux_state connected
settings put global huawei_bt_pencil_model_number "M-Pencil"
```

**预期效果**
- **L1**：华为悬浮窗 `com/huawei/stylus/floatmenu/e/j` 已注册 ContentObserver 监听
  `huawei_bt_pencil_connection_state` / `_battery_level` / `_charging_state` → 值一变化，其内部状态刷新；
  打开“手写笔悬浮窗”的**设置页**（`HandWritingIntroducePage` 读 `_mac_address` / `_double_click_state`）**可能看到伪造信息**
- **L2**：**悬浮菜单不会出现**
- **L3**：**完全无变化**

**验证命令**
```bash
adb shell 'dumpsys input | grep -i ExternalStylusPresence'           # L3：预期 NONE
adb shell 'dumpsys input | grep -c "M-Pencil"'                        # L3：预期 0
adb shell 'dumpsys bluetooth_manager | grep -iE "Pencil|bonded"'      # L3：预期无笔
adb shell 'dumpsys window windows | grep -iE "MenuView|FloatMenu"'    # L2：预期无窗口
```

### 7.4 为什么“写设置骗出悬浮菜单”只在荣耀成立

| # | 理由 | 代码证据 |
|---|---|---|
| 1 | 华为**没有“悬浮球状态机键”**，无从下手 | `key_bt_pencil` / `key_pencil` 在华为笔包与悬浮窗**均 0 命中** |
| 2 | 华为悬浮窗**不把设置变化当显示指令** | `e/j` 只监听 3 个键且回调仅 `w()`（刷新内部状态）；**不读** `hall_state`（0 命中） |
| 3 | 华为显示入口**由系统拉起且需签名权限** | `FloatMenuService`：`permission=android.permission.REBOOT`，`onStartCommand` 需 `prepareStatus`/`positionX`/`positionY` |
| 4 | 华为广播接收器**只负责收起** | `FloatViewReceiver.register()` 只注册 `SCREEN_OFF` 与 `CLOSE_SYSTEM_DIALOGS`；`reason=homekey`/`recentapps` → `MenuViewManager.go()` |

> 一句话：**荣耀把判定放在了可写的设置里，所以能骗；华为把判定放在了系统流程里，天然骗不动。**

---

## 8. 为什么两边的笔互不兼容（CD52 → 荣耀 / Magic-Pencil 3 → 华为）

### 8.1 硬件层：其实是通的

以 CD52 在**荣耀平板**上的实测为例（见 `思考.md` §6）：

```
dumpsys bluetooth_manager:
  "M-Pencil"  [ACL BR/EDR:N LE:Y]
  Encryption status LE: keySize=16
  bond_state_changed → BONDED
  GATT_CH_OPEN，ACL holders: hnbluetoothpencilmanager(73), hid(49)
dumpsys input:
  249: M-Pencil Keyboard  Classes: KEYBOARD|EXTERNAL  vendor=0x12d1 product=0x1091
  248: M-Pencil Mouse                                  vendor=0x12d1 product=0x1091
```

BLE 发现、配对、加密、ACL、HID 通道、VID/PID 读取**全部成功**。
**若硬件不支持，链路应断在最底层，而不是“连上了但系统说这不是笔”。**
反方向（MP3 → 华为平板）虽然未实测，但 BLE + HID 是标准能力，同样大概率能配上。

### 8.2 三道软件闸门

| 层 | 闸门 | 荣耀实现 | 华为实现 |
|---|---|---|---|
| ① **设备识别** | VID + **PID 白名单** | VID ∈ {0x12d1, 0x9c6} 且 PID ∈ `HnBlePeripheralCfgpencilpid` | 同构逻辑，PID 列表换成华为配置 |
| ② **协议解析** | 私有 BLE/HID 报文 | 新版**只剩一套**：磁吸须 32 字节且 `data[10]==2` | **显式保留两套**：`OLD_MODEL_ID_DATA_LENGTH=0x20` / `NEW_…=0x21`，磁吸用宽松的 `bundle.getInt()` |
| ③ **通知链** | 谁拉起悬浮菜单 | 笔包 `bind` → `com.hihonor.stylus.floatmenu/.service.BTMessageService`（签名权限 `STYLUS_MENU`） | 笔包发 `com.huawei.bluetooth.action.HWPENCIL_*` 广播 + 系统拉起 `FloatMenuService` |

> ①② 两代**格式已经不同**（32 字节硬校验 vs `getInt`），说明协议**已从同源分叉** —— 这正是“旧笔新机 / 新笔旧机”成对失败的根本。

### 8.3 失败点定位

**CD52 → 荣耀平板**
| 环节 | 结果 |
|---|---|
| BLE 发现 / 配对 / 加密 / HID | ✅ 全通 |
| ① 识别（VID/PID + 白名单） | ✅ **已过**：`0x12d1`/`0x1091`，4241 就在白名单首位 |
| HID 描述符无 STYLUS | ⚪ **不是故障**：华为原生机上同样如此（见 §3 Q3 修正框） |
| ② GATT 私有数据（电量/型号） | ❌ `battery = -1`；型号残留上一支笔 |
| ② 磁吸 / Ping 事件 | ❌ 被丢弃（荣耀硬校验 `data[10]==2`） |
| ② 老协议支持 | ❌ 荣耀新版**没有** `OLD_MODEL_ID_DATA_LENGTH` |
→ **① 已过，卡在 ②（协议解析）**，连接本身是通的。

**Magic-Pencil 3 → 华为平板**（未实测，按同构推断）
| 层 | 预判 |
|---|---|
| 硬件/配对 | 大概率 ✅ |
| ① 识别 | ❌ MP3 的 PID 不在华为白名单（VID 都是 0x12d1/0x9c6，**区分全靠 PID**） |
| ② 协议 | ❌ 华为解析器不认识荣耀新报文（两边磁吸格式已不同） |
| ③ 通知链 | ❌ 荣耀笔包只 bind/发到 `com.hihonor.*`；华为只发 `com.huawei.*`；键名/权限也全不同 |
| 额外 | 华为蓝牙栈可能也有“拒配”策略（对称于荣耀的 `isPencilCreateBondRejectedByFoldScreen`，折叠屏 + 设备类 `0x51c` + 非白名单 App） |

### 8.4 一个“机制与硬件交界”的可疑点：霍尔掩码

| | 常量 |
|---|---|
| 华为 | `PENCIL_HALL_OFFSET = 0x30`（0x20 左 / 0x10 右 / 0x0 取下） |
| 荣耀 | `PENCIL_HALL_OFFSET = 0x10`；蓝牙应用用 `& 0x34`（笔位 + `0x4` 键盘位） |

**掩码被改过** → 暗示新机身霍尔传感器的布置/编码变了。
后果：老笔（或其吸附）在新机上**可能压根不产生正确的霍尔位** → 连“磁吸事件”都不存在。
这解释了真机现象：**把笔取下也不出现悬浮球**（不只是软件不认，事件源头可能就没产生）。
这是唯一真正“硬件参与”的部分，但它影响的是**事件链**，不是“能不能连”。

### 8.5 判定表：用日志判断卡在哪一层

```bash
adb logcat -s HnExt-BlePeripheralController MainService PencilController
```
| 现象 / 日志 | 卡在哪层 | 性质 |
|---|---|---|
| `pencil createBond rejected by fold screen` | 配对策略 | 机制 |
| `in handleHidConnectionStateChange vid=0, pid=0` / `onDeviceConnect: unknown device vid-x, pid-y` / `handleHidConnectionStateChange pid vid is 0 retry...` | ① 识别白名单 | 机制 |
| `onMagneticStateChanged: not pencil event` / `invalid data length` / `unknown cmd id` | ② 协议解析 | 机制 |
| `onSensorChanged` 完全没有输出 | 霍尔传感器 / 掩码 | **硬件参与的机制** |
| 连 `bond_state_changed → BONDED` 都到不了 | 射频 / 配对栈 | 真·硬件或固件 |

### 8.6 结论

> **CD52→荣耀、MP3→华为，失败原因都在“①识别白名单 + ②私有协议解析 + ③通知链（包名/键/权限）”这三层机制上；硬件层（BLE/配对/加密/HID）两边都是通的。**
> 唯一的“硬件味道”是**霍尔掩码 0x30 → 0x10**，它影响“磁吸事件能不能产生”，仍属“硬件差异导致机制失效”，不是“笔坏了/平板不认设备”。
> 而且这三层**都不是 adb / Shizuku / root 能改的** —— 要真修，得逆向并重建私有协议解析（不现实）。

---

## 9. 修订说明（对早期 §5「仍未读/待验证」的更新）

| §5 原条目 | 本轮结论 |
|---|---|
| 3. “`FloatViewReceiver` 监听哪些广播未读” | ✅ **已读**：只注册 `SCREEN_OFF` 与 `CLOSE_SYSTEM_DIALOGS`；`reason=homekey`/`recentapps` → 收起。**它只管隐藏，不管显示** |
| 4. “`stylus_enable` 的读写方未确认” | ✅ **已确认**：**`Settings.System`** 表，默认值 2；设置页 `androidx/core/app/e` 与 `HandWritingIntroducePage` 用 `SettingsEx$System.putIntForUser()` 写，`SettingsSearchProvider` / `HandWritingIntroducePage` 读 |
| 2. “谁拉起 `FloatMenuService` 未确认” | 🟡 **部分**：已知入口是 `onStartCommand`（`prepareStatus`/`positionX`/`positionY`）且服务需 `android.permission.REBOOT`；**调用方仍未确认**（不在本包内，指向 framework/SystemUI） |
| （新增）华为悬浮窗是否有 ContentObserver | ✅ **有**：`e/j` 监听 `huawei_bt_pencil_connection_state` / `_battery_level` / `_charging_state`；`u` = TalkBack 控制器（监听 `accessibility_screenreader_enabled`）；`HandWritingIntroducePage` 监听 `huawei_bt_pencil_double_click_state` 等 |
| 1. `MenuViewManager.i(int,int,int)` 461 行 | ❌ 仍未读（`startMenu` 的完整显示条件） |
| 5. 华为 `onMagneticStateChanged` 93 行全文 | ❌ 仍未读 |
| 6. 华为 featurelayer/hiwrite 是否用其他手段影响刷新率 | 🟡 仅字符串层已查（`AGPService`/`refreshRate` 均 0）；未逐行扫 API |

---

---

## 10. 真机对照：华为平板 BAH3-W59 + CD52（本轮实测）

> 这是**唯一一次**在华为原生体系下观察 CD52 的机会。之前所有 CD52 数据都来自荣耀平板（失败样本），
> 本轮补齐了"正常工作时长什么样"的对照基线。

### 10.1 环境

| 项 | 值 |
|---|---|
| 型号 | **BAH3-W59** |
| 形态 / Android / EMUI | tablet / **10** / EmotionUI_13.0.0 |
| 屏幕 | **60Hz**（`mDefaultPeakRefreshRate: 60.0`） |
| 笔 | CD52 / M-Pencil，MAC `70:61:ee:03:83:52`（与荣耀上同一支） |

### 10.2 CD52 在原生体系下完全正常

```
huawei_bt_pencil_connection_state = 2      ✅ 已接管（荣耀上是 1）
huawei_bt_pencil_battery_level    = 11     ✅ 有电量（荣耀上是 -1）
huawei_bt_pencil_model_number     = M-Pencil ✅ 型号正确（荣耀上是残留）
huawei_bt_pencil_hall_state       = "00"   ✅ 三态字符串，= 取下（与 §2.1 代码完全一致）
huawei_bt_pencil_charging_state   = 0
huawei_bt_pencil_hardware_version = 01
huawei_bt_pencil_aux_state        = null
stylus_enable (Settings.System)   = 1
```

**决定性结论：CD52 硬件 100% 正常。它在荣耀上失败，纯粹是软件（私有协议解析层）问题。**

### 10.3 华为为什么不做刷新率控制 —— 因为屏幕只有 60Hz

```
mDefaultPeakRefreshRate: 60.0
PRIORITY_USER_SETTING -> Vote{width=-1, height=-1, minRefreshRate=0.0, maxRefreshRate=60.0}
```

对比荣耀 CGA-W00：`supportedRefreshRates [165, 144, 120, 90, 60, 48, 30]`。

→ **"笔→120Hz"不是手写笔的通用能力，而是「高刷屏 + 荣耀私有调度」的组合产物。**
华为这套没有 `AGPService`，不是不想做，是**没有可切换的档位**（这解释了 §1 表格里"华为完全没有刷新率控制"）。

### 10.4 华为悬浮菜单 adb 拉不起（实证 §7.4 的预测）

```bash
$ am startservice -n com.huawei.stylus.floatmenu/.FloatMenuService
Error: Requires permission android.permission.REBOOT
```

→ 证实 §7.4：**"写 Settings 骗出悬浮菜单"是荣耀独有的玩法**，华为侧只能骗到 L1（内部状态），骗不出 L2（悬浮窗）。
（实测窗口列表里也只有 `IntroduceMainActivity` 设置页，无悬浮窗。）

### 10.5 HID 与笔迹通道对照

| | 荣耀 CGA-W00 | 华为 BAH3-W59 |
|---|---|---|
| CD52 的 HID 节点 | 拆成 **2 个**：`M-Pencil Keyboard`(KEYBOARD) + `M-Pencil Mouse` | 合并 **1 个**：`M-Pencil`，Classes `0x80000009`(EXTERNAL\|CURSOR\|KEYBOARD) |
| 有无 `STYLUS` class | 无 | **同样无** → 非故障，是 CD52 自身设计 |
| 笔迹通道 | `honor,ts_pen` | `huawei,ts_pen`（`DeviceType: touchScreen`） |

两边的差异只是**节点拆分方式**，能力对称。笔迹从来不走 HID STYLUS。

### 10.6 包名补充（§0 表格未列）

真机上还发现两个：
- `com.huawei.stylus.mpenzone`
- `com.huawei.featurelayer.featureframework`

---

*本文与 `全逻辑闭环.md`（荣耀链路）、`README.md`（荣耀原理+实测）、`思考.md`（你的实测原始记录）配套。*
