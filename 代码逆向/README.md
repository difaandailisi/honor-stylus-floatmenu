# 荣耀手写笔「悬浮快捷菜单」——现象、原理与修复工具

> 适用对象（均已静态分析，版本号如下）：
>
> | 包名 | 应用名 | 版本 |
> |---|---|---|
> | `com.hihonor.stylus.floatmenu` | 手写笔 | 19.0.1.310 |
> | `com.hihonor.hnbluetoothpencilmanager` | （蓝牙手写笔管理） | 11.0.0.7 |
> | `com.hihonor.featurelayer.sharedfeature.stylus` | 手写笔服务 | 13.1.0.100 |
> | `com.hihonor.globalwrite` | 全局手写 | 19.0.1.308 |
>
> 四个包**同一张系统签名证书**（SHA256 `483013ce65e1b0e10ad76a2eb80409a2a2b8b35ebe81a33b1f6edd7facbd64dc`），都是 `signatureOrSystem` 级系统应用。

---

## 0. 这份文档解决什么问题

你的实测现象：

1. **CD52（M-Pencil 第一代）**：完全唤不起手写笔服务/悬浮菜单；
2. **CD54（M-Pencil 2）/ CD54S（M-Pencil 3）**：设置里有「手写笔」入口、能当荣耀笔书写，但**悬浮快捷菜单不出现**；
3. 通过**反复点“配对/取消配对”**能把悬浮菜单“卡”出来；
4. 你要的其实是：**悬浮菜单出现时带来的 120Hz + 高触控采样**（否则 90Hz 写不进去）。

本文档给出：（a）链路与判定原理；（b）每道门的对应日志；（c）**不靠碰运气**的触发/保活方式；（d）为什么不推荐把悬浮菜单当作刷新率开关。

---

## 1. 四个应用的分工

```
com.hihonor.hnbluetoothpencilmanager   蓝牙侧：笔/键盘连接、电量、吸附、OTA、日志收集
        │  通过 bind BTMessageService 上报状态
        ▼
com.hihonor.stylus.floatmenu           ★悬浮快捷菜单本体 + 设置入口（开关就住在这里）
        │  需要画布/工具能力时
        ▼
com.hihonor.featurelayer.sharedfeature.stylus   手写引擎（penkit impl）+ 工具条/弹窗菜单 UI

com.hihonor.globalwrite               全局手写：手写输入法、自由批注、智慧套索、取色、GIF
        └─ 它也会拉起/刷新悬浮球（类名就叫 StylusFloatBallNotifier）
```

---

## 2. 悬浮菜单是怎么被“叫”出来的（4 段链路）

```
① 系统侧广播
     · HID 连接广播（Intent 里带 vid / pid / ver / STATE）
     · 磁吸广播（Bundle: message.source == "magnetic.request"，message.content 32 字节且 data[10]==2）
        ▼  isHwPencilDevice(vid, pid) 白名单校验（★卡点 1）
② com.hihonor.hnbluetoothpencilmanager
     onPencilAttached()  → 必须已配对(isDeviceBonded) → connectHid()
     onPencilConnected() → 写 honor_bt_pencil_connection_state；setStylusScenceFps(60)
                         → broadcastConnectionStateChanged(2)
                         → startPencilBallService(2)
        ▼  bindService → com.hihonor.stylus.floatmenu/.service.BTMessageService
        ▼  发 Message(what=1, arg1=state)          // 2=连接/唤醒, 0=断开, 1=休眠, -2=销毁
③ com.hihonor.stylus.floatmenu
     BTMessageService 收到 → F1/a.a(ctx, state)
        · 写 Settings.Global: key_bt_pencil_state = state
        · 检查 F1/c.e(ctx): stylus_support_floating_capsule == 1        （★卡点 2）
        · 检查 F1/a.e(state): state ∈ {0,1,2,3,4}
        · startService(StylusFloatBallService, action=com.hihonor.stylus.floating.capsule,
                       extra key_pencil_state = state)
④ com.hihonor.stylus.floatmenu.floatball.service.StylusFloatBallService
     onStartCommand() → t(state) → 状态机 → p()（★卡点 3，见第 6 节 9 条判定）
```

**关键代码（`onStartCommand`）**：

```java
int state = intent.getIntExtra("key_pencil_state", -1);
if (state != -1) { t(state); return START_STICKY; }   // 有该 extra → 直接进状态机
else { j(intent); q(); s(intent); }                   // globalwrite 走的另一条（带 key_extra_ball_rect）
```

---

## 3. 为什么“只有 state = 2 或 4”才会显示

`StylusFloatBallService.t(int state)` 是悬浮球的状态机：

| state | 动作 | 结果 |
|---|---|---|
| 0 | 菜单收起 + `e(true)` + “cancel notify because pencil disconnected” | **隐藏** |
| 1 | `e(false)` | **隐藏**（笔休眠） |
| 3 | `e(false)` | **隐藏** |
| **2** | `q(); p();` | **走显示判定** |
| **4** | `p();` | **走显示判定** |
| 其他 | 什么都不做 | 保持原状 |

> 这就是你“狂点配对/取消配对能卡出来”的根因：那个过程反复产生 HID 连接事件 → 蓝牙包反复发 `arg1=2` → 其中一次满足所有条件 → 球显示；**之后再没有 0/1/3 事件来关它**，于是就“卡住”了。

---

## 4. 为什么“吸附=隐藏、取下=显示”（M-Pencil 无按键的关键）

M-Pencil 全系没有实体按键，所以“唤醒/调出”只能依赖**磁吸状态变化 + 笔内传感器**。代码里对应：

```java
// LE1/f —— 单例，监听磁吸状态
f() {
    ContentResolver.registerContentObserver(
        Settings$Global.getUriFor("key_pencil_attach_state"), true, observer);
}
h() { for (l : list) if (l instanceof E1/d) l.h(); }     // 逐个回调

// StylusFloatBallService.h()  ← 被 E1/d 回调（日志: onBtPencilAttachStateChange:）
h() {
    if (LF1/a.d(ctx)) {        // d() = (key_pencil_attach_state==1 && key_pencil_attach_type==1)
        e(false);              // ← 吸附态：隐藏
    } else {
        p();                   // ← 非吸附：立刻显示判定
    }
}
```

所以对无按键的磁吸笔：

* **把笔吸在机身上 → 隐藏**（设计如此：吸附＝在充电，此时不需要悬浮球）；
* **把笔取下 → 走 p() 判定 → 显示**。

---

## 5. 为什么本工具选择“改 Settings.Global”这条路

三条理由，全部来自代码：

1. **这就是系统自己的通道。** 悬浮菜单只认两个输入源：蓝牙包 bind 过来的 `Message`，以及 `Settings.Global` 键变化触发的 `ContentObserver`。前者要签名级权限（`com.hihonor.stylus.floatcapsule.STYLUS_MENU` / `ACCESS_HONOR_PENCIL_MANAGER_SERVICE`），**普通方式调不到**；后者只需 `WRITE_SECURE_SETTINGS`（adb shell / root / Shizuku 即可）。
2. **`ContentObserver` 只在“值发生变化”时回调。** 如果键原本已是 0，你再写一次 0 不会触发任何回调。所以工具里的 `trigger_detach()` 故意做 **1 → 0** 的跳变（`key_pencil_attach_state` 与 `key_pencil_attach_type` 各一次），确保 `h()` 真的被调用。
3. **`onStartCommand` 认 Intent extra。** `am start-service ... --ei key_pencil_state 2` 能直接进状态机 `t(2)`，绕开第①②段（蓝牙包），作为兜底。

---

## 6. 九条显示判定（对应日志 → 处置）

| # | 条件 | 不满足时的日志 | 处置 |
|---|---|---|---|
| 1 | `stylus_support_floating_capsule == 1` | `Stylus floating is disabled.` | 设置里打开开关；或 `settings put` |
| 2 | 非无障碍模式 | `Accessibility Mode.` | 临时关闭无障碍服务 |
| 3 | 非 PC 投屏 | `Pc cast mode.` | 退出投屏/桌面模式 |
| 4 | 非隔空鼠标(聚光灯) | `Air mouse mode is on.` | 关闭隔空/聚光灯模式 |
| 5 | 折叠屏内屏(DisplayMode=2) 需机型白名单；外折副屏(3) 不支持 | `folding screen home screen` / `Outward foldable sub screen mode, hide float ball.` | 换屏幕形态 |
| 6 | `honor_bt_pencil_connection_state != 0` | `Pencil is disConnected, not show float ball !` | 笔没被蓝牙包接管（见第 8 节白名单） |
| 7 | **笔必须“非吸附”** | `Pencil is Attached, not show float ball!!` | **把笔从机身取下** |
| 8 | `key_bt_pencil_state ∈ {2,4}` | `Pencil state is wrong! state = X` | 触发一次连接/唤醒（或工具 `trigger`） |
| 9 | 非录屏 | `视频录制中，无法使用悬浮快捷菜单` | 停止录屏 |
| — | 服务启动时还会看 `not support GraphicsTablet!` / `PcGraphicsTablet` 相关分支 | | 退出图形平板/PC 副屏模式 |

> 抓日志：`sh stylus_float_menu.sh logcat`（关键字：`StylusFloatingCapsuleService|BtPencilStateHelper|PencilController|FloatingBallUtils`）。

---

## 7. 对你三种现象的解释

| 现象 | 代码解释 |
|---|---|
| **CD54 / CD54S：设置里有“手写笔”入口** | 那个入口是 floatmenu 自己的 `activity-alias` + `com.android.settings.action.EXTRA_APP_SETTINGS` 注册的，**与插哪支笔无关**，只证明 APK 装了 |
| **CD54 / CD54S：能写但没有悬浮菜单** | 笔被当作荣耀笔（能写 ≈ HID 通），但第③④段没走完：要么 `key_bt_pencil_state` 停在 0/1/3，要么 `key_pencil_attach_state` 判定为“吸附”，要么磁吸键根本没被系统写过（Observer 不触发） |
| **狂点配对/取消配对能卡出来** | 反复制造 HID 连接事件 → 反复 `arg1=2` → 命中一次完整条件 → 球显示且无人关闭 |
| **CD52：连服务都唤不起** | 更前置：`isHwPencilDevice(vid,pid)` 判定失败（VID 必须 0x12d1/0x9c6，且 PID 必须在白名单里），或系统不发那两类广播 → 蓝牙包从不接管这支笔 |

---

## 8. “笔类型白名单”到底是什么

**两层过滤：**

```java
// 第 1 层：硬编码 VID 白名单
isHwPencilDevice(vid, pid):
    if (vid != 0x12d1 && vid != 0x9c6) return false;
    for (p : sPencilPidList) if (pid == p) return true;    // 第 2 层
    return false;

// 第 2 层：PID 列表来自 Settings.Global（MainService.updateConfig()）
"HnBlePeripheralCfgpencilpid"      → 笔 PID（十进制，逗号分隔）→ updatePidList()
"HnBlePeripheralCfgkeyboardpid"    → 键盘
"HnBlePeripheralCfgkbsuphostdicpid"
"HnBlePeripheralCfgkbname"
// 四个键任一为空 → “updateConfig: invalid config”，白名单整体失效
```

已知换算：`CD52 = 0x1091 = 4241`、`ALITA = 0x10a5 = 4261`。**CD54/CD54S 在代码里没有常量**，必须从 logcat 抓：

```
PencilController: setHidDeviceInfo: hidVid: <vid>, hidPid: <pid>, hidVersion: <ver>
```

补白名单后需要**重启蓝牙服务进程**（`am force-stop com.hihonor.hnbluetoothpencilmanager`）或重启设备，并确保四个键都非空。

---

## 9. 帧率的真相：悬浮菜单不是刷新率开关

```java
// com.hihonor.hnbluetoothpencilmanager.HwPencilController
setStylusScenceFps(int fps):
    ServiceManager.getService("AGPService")            // 反射
    parcel.writeInterfaceToken("AGPService"); parcel.writeInt(fps);
    binder.transact(0x7a, ...)                          // TRANSACT_SET_STYLUS_SCENE_EVENT
// 触发点只有 4 处：笔连接(60) / 笔唤醒(60) / 笔休眠(0) / 笔断开(0)
```

* 你观察到的“悬浮菜单出现 → 120Hz”，本质是**笔被判定为“已连接/唤醒”**触发了 AGPService 的 stylus 场景，悬浮球只是**伴生现象**；
* floatmenu 与 globalwrite 里**搜不到 AGPService / refreshRate** → 应用层不直接改刷新率；
* 另有笔迹预测帧率在 `手写笔服务` 模块：`HnStrokeEstimateImpl.setRefreshRate(float)`（门槛：`ro.product.manufacturer` 含 HONOR 且 `ro.build.magic_api_level >= 20`）。

**所以要 120Hz，请直接用：**

```bash
sh stylus_float_menu.sh fps on          # = settings put system min_refresh_rate/peak_refresh_rate 120
sh stylus_float_menu.sh fps scene-on    # root: service call AGPService 0x7a i32 60
```

---

## 10. 工具用法

```
sh stylus_float_menu.sh <命令>

  check | status      只读检查：环境 + 四个应用 + 每道判定门 + 白名单
  show                一键出现（开关/连接/状态机 + 伪造磁吸 + 兜底投递）
  trigger             只做“伪造一次磁吸 + 投递 state=2”
  keepalive [秒]      循环保活（默认 20s），Ctrl+C 退出
  hide                隐藏（把 attach 键置为吸附态）
  hideoff             彻底关闭（总开关置 0 + 停止服务）
  fps on|off          锁定 / 恢复 120Hz
  fps scene-on|off    root：下发 / 复位 AGPService stylus 场景
  log | logcat        抓关键日志
  backup              备份将被修改的键
  rollback            按备份回滚
  help                帮助
```

**推荐流程**

```bash
sh stylus_float_menu.sh check          # 1. 先看哪道门没过
sh stylus_float_menu.sh backup         # 2. 改之前留底
sh stylus_float_menu.sh logcat &       # 3. 另开窗口抓日志
sh stylus_float_menu.sh show           # 4. 触发
sh stylus_float_menu.sh keepalive 20   # 5. 需要常驻时
sh stylus_float_menu.sh rollback       # 6. 想恢复时
```

**用电脑 adb 时**：把脚本推到手机，或直接在电脑上跑等效命令（每条加 `adb shell` 前缀）：

```bash
adb push stylus_float_menu.sh /data/local/tmp/
adb shell sh /data/local/tmp/stylus_float_menu.sh check
adb shell sh /data/local/tmp/stylus_float_menu.sh show
```

**运行权限**：脚本需要能写 `Settings.Global`，三种方式任选——
（a）电脑 adb shell；（b）MT 的 Shell（root，脚本会自动尝试 `su -c`）；（c）Shizuku 授权的终端。

---

## 11. 风险、副作用与回滚

| 项 | 说明 |
|---|---|
| 键会被系统覆盖 | `key_bt_pencil_state`、`honor_bt_pencil_connection_state`、`key_pencil_attach_state` 都由系统/蓝牙包实时写。手工值可能被秒改回 → 这正是需要 `keepalive` 的原因 |
| 伪造“非吸附”的副作用 | 笔吸在机身上（充电）时也会显示悬浮球，与实际语境不符 |
| 伪造 `honor_bt_pencil_connection_state=2` | 只影响依赖它的界面判定；若键本来被系统写成别的含义，可能让状态显示不一致 |
| 关屏/重启 | 本工具只在运行时有效；重启后需重跑（要开机自启需 root 方案，如 Magisk 模块） |
| 耗电 | `keepalive` 每轮会写设置 + 拉起系统服务，建议间隔 ≥ 20s，用完即停 |
| 回滚 | `rollback` 会按 `backup` 的文件把键恢复（值为 `null` 时执行 `settings delete`） |

---

## 12. 适用性与免责

* 所有结论来自对上述**四个 APK 的静态分析**（`Settings.Global` 键名、判定顺序、日志字符串都已在 smali 里定位）。**未在你的真机上逐条实测**；个别机型/ROM 可能键名或行为有差异。
* 这些键属于系统内部实现，**会随版本变化**（本文档基于上表版本）。升级固件后请重跑 `check`。
* 改 `Settings.Global` 需要系统级权限（adb/root/Shizuku）。**不要在没有备份的情况下批量写入**；请先 `backup`。
* 本工具**不修改任何 APK**，只写运行时设置，随时可 `rollback`。


---

## 13. 真机实测修正（v1.1.0，务必先读）

> 本节来自对 **CGA-W00 平板（Android 16 / targetSdk 36 / magic_api_level=43，adb shell UID 2000，无 root）** 的实测记录，原始数据见同目录 `思考.md`。
> 结论：**第 6、9、11 节的部分内容已被实测推翻，以本节为准。**

### 13.1 被推翻的四条结论

| # | 原文（v1.0 脚本/文档） | 实测结果 | 修正 |
|---|---|---|---|
| 1 | `check` 把 `Settings.Global.stylus_support_floating_capsule = null` 判为 `FAIL -> 总开关未开` | 该键为 **null**，悬浮球**照样能显示**；而 `Settings.System` 表里有 `stylus_enable=1`、`stylus_state_introduce=1` | 该判决是**误报**；真开关**疑似**在 System 表的 `stylus_enable`。脚本已改成"未知 / 以实测为准" |
| 2 | `start_ball()` 用 `am start-service` 拉起悬浮球服务 | `dumpsys activity services` 显示该服务 `permission=com.hihonor.stylus.floatcapsule.STYLUS_MENU`（**signature 级**），shell 永远拿不到；而且服务**本来就在运行** | `am start-service` 是**死代码**，已降级为可选诊断 `sendstate` |
| 3 | `fps scene-on` 用 `service call AGPService 0x7a` | 需 root；`service call` 不写 interface token（源码是 `writeInterfaceToken("AGPService")`）；事务码随固件变化 | 标为**未验证 / 大概率不可用**，仅 root 下保留并打印警告 |
| 4 | 第 6 节教用 logcat 关键字定位"卡在第几道门" | 本机 app 判定日志被荣耀**限流**，一条都没打出来 | **改用黑盒验证**：`dumpsys window windows` 看 `FloatBallWindowView` 与 `mViewVisibility` |

### 13.2 第五条同样重要：这些键是「事件驱动」，不是轮询

**原文（第 11 节）** 担心"系统持续回写、球会闪烁、必须 20 秒一次 keepalive 打地鼠" —— **错了**。

实测：磁吸状态下强行写 `key_pencil_attach_state=0`，t+2s 与 t+8s 读回都是 0，窗口数稳定为 6，**纹丝不动**。
⇒ 系统**只在物理事件（吸附 / 取下）发生的那一瞬间**写一次键，此后不再覆盖。

**实践意义**：
- 一次写入**长期有效**，不需要高频保活；
- 伪造值会保持到下一次真实插拔笔事件；
- 反相器轮询间隔可以放到 **30 秒甚至更长**，开销几乎为零。

### 13.3 判定与验证的正确姿势

- 判定是 **AND** 关系：即使把开关写 1，磁吸时仍会被 `Pencil is Attached, not show float ball!!` 独立拦截
  ⇒ 要"磁吸时也显示"，**必须伪造 `key_pencil_attach_state`**，没有替代路径。
- **窗口存在 ≠ 可见**：`grep -c FloatBallWindowView` 非 0 只说明窗口建了；
  必须看 **`mViewVisibility=0x0`**（可见）与 `mObscured=false`。
- 球的位置会被持久化（`key_stylus_ball_rect_left/top/right/bottom`），说明它曾正常显示过。

### 13.4 实测有效的操作

```bash
# 让球出现（已验证）
adb shell settings put global key_bt_pencil_state 2
adb shell settings put global key_pencil_attach_state 0
adb shell 'dumpsys window windows | grep -c FloatBallWindowView'    # 非 0 即出现

# 让球隐藏
adb shell 'settings put global key_pencil_attach_type 1; settings put global key_pencil_attach_state 1'
```

**「反相器」方案**（脚本 `invert` 命令）：

```
监听 honor_bt_pencil_charging_state （系统写、不参与判定；实测 磁吸=1 / 取下=0）
        │  单向、异键
        ▼
写   key_pencil_attach_state         （参与判定）
```

**绝对不要"监听 A 写 A"**：`Settings.Global` 不记录写入者，会形成
`系统写1 → 你写0 → 你看到0 → 你写1 → …` 的死循环；而且"自己写的 0"与"系统写的 0"**无法区分**。

### 13.5 CD52（第一代 M-Pencil）为什么救不回来

实测数据：

| 键 | Magic-Pencil 3 | CD52 |
|---|---|---|
| `honor_bt_pencil_connection_state` | 2 | **1** |
| `key_bt_pencil_state` | 2 / 4 | **0** |
| `honor_bt_pencil_battery_level` | 79 / 80 | **-1** |
| `honor_bt_pencil_model_number` | HONOR Magic-Pencil 3 | **仍是上一支笔的残留** |

链路证据：
`dumpsys bluetooth_manager` → `BONDED`、`Encryption LE keySize=16`、`GATT_CH_OPEN`（`hnbluetoothpencilmanager` 与 `hid` 都持有 ACL）；
`dumpsys input` → **VID=0x12d1、PID=0x1091=4241（白名单该过的都过了）**；设备类只有 `KEYBOARD | EXTERNAL`，无 STYLUS；`ExternalStylusPresence: NONE`。

> ⚠️ **修正：不要把"无 STYLUS"当成罪证。** 后来在华为平板 BAH3-W59 上用同一支 CD52 对照，
> 原生环境里 `Classes: 0x80000009`（EXTERNAL\|CURSOR\|KEYBOARD）**同样没有 STYLUS**。
> 这是 CD52 自身设计，笔迹走 `ts_pen`（屏幕触控通道），从不经过 HID STYLUS。

**结论（反证法）**：硬件链路 100% 通，断在**上层协议解析** —— 新版 `hnbluetoothpencilmanager 11.0.0.7` 只保留了 Magic-Pencil 3 的新私有协议，老协议实现被移除（对比：华为版保留了 `OLD_MODEL_ID_DATA_LENGTH=0x20` 分支，荣耀版该常量已被删除）。
CD52 于是退化成一个"会报按键的蓝牙键盘"。

**同一支笔在华为平板上的对照（决定性证据）**：
`huawei_bt_pencil_connection_state=2` ✅ / `battery_level=11` ✅ / `model_number=M-Pencil` ✅ / `hall_state="00"` ✅
→ **CD52 硬件完全正常，是荣耀软件不认它。**

要让 CD52 写字，需要逆向私有 GATT 协议 + `INJECT_EVENTS` 级注入 + 常驻服务 →
**这不是改 Settings 能做到的；adb / Shizuku / 甚至 root 都救不回书写功能。**

### 13.6 120Hz（原始诉求）的最终结论

| 层 | 能否触及 |
|---|---|
| `floatmenu` 悬浮球 UI（只读 `Settings.Global` 判定） | **可以伪造**（已实测成功） |
| `min/peak_refresh_rate`（Settings.System，AOSP 约定键） | 可写，但**荣耀未必读**，且会被"智能刷新率"覆盖 |
| **AGPService stylus 场景**（真正的高刷来源） | **够不着**：需 root/system，且由蓝牙包的真实连接/唤醒事件驱动 |

**"悬浮菜单出现 → 120Hz"是伴生关系，不是因果**：两者都是"笔被判定为已连接/唤醒"的结果。
伪造 Settings 只能骗出悬浮球，**骗不出 AGPService 的 stylus 场景**。

⇒ 要 120Hz：**设置 → 显示和亮度 → 屏幕刷新率 → 选"高"，并关闭"智能/动态刷新率"**（配合 Magic-Pencil 3 才完整）。

### 13.7 v1.1.0 脚本的命令变更

| 命令 | 变化 |
|---|---|
| 新增 `verify` | 窗口数 + `mViewVisibility` + `ExternalStylusPresence`（黑盒验证，取代日志排障） |
| 新增 `dump` | 一次性打印 Global / System / 白名单全部相关键 |
| 新增 `diag` | 蓝牙配对、HID 类、VID/PID、服务权限证据 |
| 新增 `invert auto\|always [秒]` | 反相器；`keepalive` 保留为别名（但已无必要） |
| `sendstate` | 原 `start_ball`，降级为诊断并打印"必失败"原因 |
| `check` | 增加 System 表与黑盒状态；`stylus_support_floating_capsule=null` 不再判 FAIL |
| `logcat` | 标注"本机被限流，基本无用" |
| `fps` | `scene-*` 打印三重失败原因，仅 root 下尝试 |

### 13.8 仍未确认（遗留项）

1. `stylus_support_floating_capsule`(Global=null) 与 `stylus_enable`(System=1) 到底哪个是 floatmenu 真正读取的开关 —— 需做一次"写 0 验证"；
2. Magic-Pencil 3 磁吸时 `ExternalStylusPresence` 是否变为非 `NONE` —— 做一次即可 100% 坐实"系统认笔"的判定标准；
3. 反相器方案落地需要常驻载体（Tasker / Shizuku 前台 / root）；
4. 笔未连接时，悬浮球菜单内各项（批注、套索等）点击后的真实行为。


### 13.9 让 CD54 / CD54S 也能「一连上就出现悬浮球」（看门狗方案）

**为什么 MP3 会自动出现、CD54/CD54S 不会？**

悬浮球只在有人调用 `p()` 时才会被**重新评估**，而 `p()` 的触发源只有三类（代码实读）：

1. `key_pencil_attach_state` 变化（磁吸 / 取下）→ `LE1/f` 的 ContentObserver → `h()`；
2. 蓝牙包 bind 过来的状态消息（`arg1 ∈ {2,4}` 才显示）→ `t(2)` / `t(4)`；
3. 应用自身其它入口（`onStartCommand` 的另一分支、折叠屏 DisplayMode 变化等）。

> 补充：`onConfigurationChanged` 只调用 `v()`（更新球位置），**不调用 `p()`** —— 所以“旋转屏幕”并不能让它出现。

MP3 走通了第 1、2 条；CD54/CD54S 若在这两条上**没触发**、或**触发了但被拦**，球就不会自动出现。
而“狂点配对/取消配对”本质是**人为制造大量第 2 条事件**，撞上一次完整条件后把球卡住。

**30 秒判定它属于哪一类**（对 MP3 与 CD54 各做一遍对比即可）：

| 观察项 | 命令 | 判读 |
|---|---|---|
| 连接瞬间的状态机 | `adb shell settings get global key_bt_pencil_state` | 是否变成 2/4 → 第 2 条事件到了没 |
| 取笔 / 吸笔时的磁吸键 | 取下读一次 `key_pencil_attach_state` 与 `key_pencil_attach_type`，吸上、等 2s 再读 | 值**变不变** → 第 1 条链路是否生效 |
| 球是否存在 | `adb shell 'dumpsys window windows \| grep -c FloatBallWindowView'` | 结果 |

- attach 键在取/吸时**不变** → 磁吸事件链路对这些笔没生效；
- `key_bt_pencil_state` 始终不是 2/4 → 蓝牙包没发显示事件。

**两种情况下，最实用的解法是同一条：条件式看门狗** —— 把“必须由系统事件触发”改成“我们按条件补刀”，
从而与 MP3 的原生体验等价（连接后最多等一个轮询周期，球就出现）。

```bash
# 一连上就出现（推荐）
adb shell sh /data/local/tmp/stylus_float_menu.sh watch always 15

# 更贴近原生语义：只在笔未吸附时才补
adb shell sh /data/local/tmp/stylus_float_menu.sh watch idle 15
```

看门狗内部逻辑（每个周期只做三件事）：

```
读 conn / pstate / charge / win_count
  ├─ win_count != 0            -> 球已在，什么都不做
  ├─ conn = 0 / null           -> 笔未连接，不动作
  ├─ (idle 模式) charge = 1    -> 笔在吸附，跳过
  └─ 否则：pstate 不是 2/4 就写 2；再执行 trigger_show()（伪造 1 -> 0 取下笔）
```

**载体选择**（沿用 §5.3 结论）：

| 载体 | 结论 |
|---|---|
| 电脑 adb 循环 | 拔线即失效 |
| **Shizuku + Tasker / MacroDroid 定时任务** | 可用；轮询 15~30s 足够；重启后需重新激活 Shizuku |
| root + Magisk 开机脚本 | 一劳永逸 |

**注意**：看门狗会覆盖 `hide`（它会不断把球拉回来）。要隐藏请先停掉看门狗，再 `hide`。
