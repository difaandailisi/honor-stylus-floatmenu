# PC 桌面 与 一碰传：开启逻辑（代码级）

> **分析对象**
>
> | 包名 | 名称 | 版本 | 类数 | 签名 |
> |---|---|---|---|---|
> | `com.hihonor.android.launcher` | 荣耀桌面 | 18.0.0.231 | 26728 | `483013ce…` |
> | `com.hihonor.android.instantshare` | 荣耀分享 | 17.0.0.24 | 5900 | `483013ce…` |
>
> **方法**：逐方法读 smali；所有结论标注类/方法/常量/字符串。
> **回答两个问题**：① PC 桌面怎么开；② 一碰传怎么开。

---

## 0. 一句话概览

| 功能 | 归属包 | 开启的本质 | 能否用 adb/命令手动开 |
|---|---|---|---|
| **PC 桌面** | 荣耀桌面 `launcher` | 把默认 Home 切成 `com.hihonor.android.launcher/.pc.PCLauncher`（启用/禁用各 Launcher 组件 + 写持久化配置 + 可能重启） | ⚠️ **部分可以**（组件可 pm enable/disable；最终启动靠 HOME Intent） |
| **一碰传** | 荣耀分享 `instantshare` | 向 **`com.hihonor.systemserver/.onetouchshare.SwitchService`** 发 `CHANGE_SWITCH`（开关真正写方在 system_server） | ❌ **基本不行**（目标服务受系统权限保护） |

---

## 1. PC 桌面（`com.hihonor.android.launcher`）

### 1.1 先决条件：机型必须“支持 PC 模式”

`HnDesktopUtils.isPCLauncherEnable()`：

```java
public static boolean isPCLauncherEnable() {
    if (!RoPropConstant.IS_TABLET) return false;            // ★ 只有平板才继续判断
    if (sIsPCLauncherEnable == null) {
        ArrayList list = HwCfgFilePolicy.getCfgFileList(
                "xml/14x8/hn_launcher_default_workspace_pclauncher.xml", 0);  // ★ 找 cust 配置
        sIsPCLauncherEnable = !list.isEmpty();               // 文件存在 → 支持
    }
    return sIsPCLauncherEnable;
}
```

→ **PC 桌面是否可用，由设备 cust 里是否存在 `xml/14x8/hn_launcher_default_workspace_pclauncher.xml` 决定**（`HwCfgFilePolicy` 会在 /cust、/system/etc 等目录查找）。
→ 叠加 `Settings.isPCLauncher()`：`IS_TABLET && isPCLauncherEnable() && getLauncherType()==8`。

### 1.2 `launcherType`：PC 模式的“开关值”

`HnDesktopUtils.isLauncherTypeValid(int)`：**合法值只有 1 / 4 / 8**（其它值打 `"launcherType is invalid: N"`）。

它存在两个地方：

| 存储 | 键 | 读/写 |
|---|---|---|
| **系统持久化 Configuration**（`ConfigurationEx.simpleuiMode`） | —（不是普通 settings 键） | 写：`HomeSwitcher.updateConfiguration()` → `ConfigurationEx.setSimpleuiMode(type)` + `ActivityManagerEx.updatePersistentConfiguration(cfg)`<br>读：`Settings.getLauncherType()` → `ActivityManagerEx.getConfiguration()` → `ConfigurationEx` |
| **`Settings.Secure`** | `launcher_type_before_pc_mode` | 记录“进 PC 模式之前是什么类型”，默认 **1**；只接受 1/4，否则回退 1（`HnDesktopUtils.getLauncherTypeBeforeEnterPcMode()`） |
| launcher 的 SharedPreferences | `launcher_type_before_enter_pc_mode` | `HnDesktopUtils` 里 `contains()` 过它以判断“是否已记录” |

### 1.3 可切换的 Launcher 组件清单（`LauncherConstants.<clinit>`）

| 常量 | 组件 |
|---|---|
| `UNIHOME_COMPONENT` | `com.hihonor.android.launcher/.unihome.UniHomeLauncher` |
| `DRAWERHOME_COMPONENT` | `…/.drawer.DrawerLauncher` |
| `NEWSIMPLEHOME_COMPONENT` | `…/.newsimpleui.NewSimpleLauncher` |
| **`PCLAUNCHER_COMPONENT`** | **`com.hihonor.android.launcher/.pc.PCLauncher`** ← PC 桌面 |
| `PRIVACY_MOMENT_COMPONENT` | `…/.privacymoment.PrivacyMomentModeLauncher` |
| `SIMPLEHOME_COMPONENT` | `…/.simpleui.SimpleUILauncher` |
| `STREET_MODE_COMPONENT` | `…/.streetmode.StreetModeLauncher` |
| `POWER_SAVE_COMPONENT` | `…/.powersavemode.PowerSaveModeLauncher` |

### 1.4 launcherType → 目标组件（`HomeSwitcher.getChangeToComponent`）

```java
switch (launcherType) {
    case 4: disable(NEWSIMPLE/UNIHOME/PRIVACY/PCLAUNCHER); return DRAWERHOME;
    case 1: disable(NEWSIMPLE/DRAWER/PRIVACY/PCLAUNCHER);   return UNIHOME;   // 默认
    case 5: disable(UNIHOME/DRAWER/PRIVACY/PCLAUNCHER);     return NEWSIMPLEHOME;
    case 8: disable(UNIHOME/DRAWER/PRIVACY/NEWSIMPLE);      return PCLAUNCHER; // ★ PC 桌面
    default: return UNIHOME;
}
```

启/禁组件用的 API（`disableLauncherType` / `enableLauncherType`）：
```java
LauncherPackageManager.setComponentEnabledSetting(componentName, packageManager,
        2 /* COMPONENT_ENABLED_STATE_DISABLED */, 1 /* DONT_KILL_APP */);   // 启用则用 1
```
→ **本质：同一 App 里放了多个 `CATEGORY_HOME` 组件，靠“启用/禁用”互斥切换。**

### 1.5 开启 PC 桌面的完整动作（`HomeSwitcher.startNewLauncherForPCLauncherSwitch`）

```java
public static void startNewLauncherForPCLauncherSwitch(Activity activity, int launcherType, ComponentName target) {
    enableLauncherType(ctx, target);                       // ① 启用目标组件（PCLauncher）

    Intent i = new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(0x10200000)                          // ② NEW_TASK | RESET_TASK_IF_NEEDED
            .setComponent(target);                         // ★ 显式指定 PCLauncher
    i.putExtra(target.getClassName(), true);               // ③ 标记“由切换而来”

    HnDesktopUtils.setShouldSendRebootDoneToShell(ctx, true);
    setRebootAfterSwitchPCLauncher(true);                  // ④ ★ 切换后需要重启（mRebootAfterSwitchPCLauncher）
    activity.finish();
    updateConfiguration(launcherType);                     // ⑤ 写持久化 Configuration（simpleuiMode=8）

    if (PowerSaveReceiver.isLimitPowerModeOn()) {          // ⑥ ★ 极限省电模式下禁止切换
        log_err(\"Now in limit power mode, should not change Home for PCLauncher Switch\");
        return;
    }
    activity.startActivity(i);                             // ⑦ 启动 PCLauncher
    // 异常 → \"changeHome for pc launcher switch failed:\" + 组件名
}
```

**所以“开启 PC 桌面”= 四件事同时做：**
1. `pm enable` PCLauncher 组件 + `pm disable` 其它 Launcher 组件；
2. 把 `launcherType=8` 写进**系统持久化配置**（`Configuration.simpleuiMode`）；
3. 用显式 `ACTION_MAIN + CATEGORY_HOME` Intent 启动 `…/.pc.PCLauncher`；
4. 标记“切换后需要重启”（`setRebootAfterSwitchPCLauncher(true)`、`setShouldSendRebootDoneToShell(true)`）。

### 1.6 退出 / 回滚（`HnDesktopUtils.rollBackPCLauncherIfNeeded`）

```java
public static void rollBackPCLauncherIfNeeded(Context ctx) {
    if (!RoPropConstant.IS_TABLET) return;
    boolean isPCLauncherUsed = (Settings.getLauncherType() == 8);      // 当前是否在 PC 模式
    log_warn(\"rollBackPCLauncherIfNeeded isPCLauncherUsed: \" + isPCLauncherUsed);
    if (!isPCLauncherUsed) return;

    int before = getLauncherTypeBeforeEnterPcMode(ctx);               // 从 Secure 读回退目标（1 或 4）
    Settings.Secure.putInt(cr, \"launcher_type_before_pc_mode\", -1);   // ★ 清掉记录
    boolean inDesktopMode = isInDesktopMode();                        // HnDesktopVisibilityController
    …（据此回滚到 before 类型）
}
```
其他相关：
- `isPCLauncherExitScene(ctx)` / `shouldRequestPortraitAfterPCLauncherExit(launcher)` / `requestPortraitAfterPCLauncherExit(ctx)` → 退出 PC 模式后**强制回竖屏**
- `HnDesktopModeObserver` 也会写 `Settings.Secure.putInt(\"launcher_type_before_pc_mode\", type)`
- `isInDesktopMode()` → 走 `HnDesktopVisibilityController.isDesktopModeStateUpdated()` + `inDesktopModeState()` + `isDesktopDeviceSupport()`

### 1.7 相关 UI / 引导（供你定位入口）

| 类 / 资源 | 作用 |
|---|---|
| `HnDesktopIntroManager.showPCLauncherIntroDialog()` / `initPCLauncherIntroPages()` | PC 模式首次进入引导 |
| `HnDesktopPCLauncherIntroAdapter` | 引导页（`pclauncher_intro_dialog`、`item_pclauncher_intro_page`、Lottie 动画） |
| `PCLauncherOtaActivity` / `PCLauncherOtaProvider` | PC 桌面版本升级入口 |
| `HnPcLauncherBurnInProtectionController` | 防烧屏 |
| `Settings.mPCLauncherDefaultCellOptionIndex` / `dealPcLauncherCellIfNeed()` | PC 桌面网格布局 |
| `CreateFolderHelper4PCLauncher`、`HiddenAppManager.addPCLauncherHiddenIcons()` | PC 桌面专用文件夹/隐藏应用 |
| 引导标记键 | `divider_bubble_first_enter_pc_mode_showed`、`divider_bubble_pc_mode_pinning_changed_showed` |

### 1.8 手动尝试（adb，可能受机型/权限限制）

```bash
# ① 看当前 launcherType（持久化配置，普通 settings 读不到）
#    可用 dumpsys 观察，或间接判断：PCLauncher 组件是否 enabled
dumpsys package com.hihonor.android.launcher | grep -A2 "pc.PCLauncher"

# ② 手动切换组件（shell 通常有 CHANGE_COMPONENT_ENABLED_STATE 权限）
pm enable  com.hihonor.android.launcher/com.hihonor.android.launcher.pc.PCLauncher
pm disable com.hihonor.android.launcher/com.hihonor.android.launcher.unihome.UniHomeLauncher

# ③ 前进 Secure 的“回退记录”（可选）
settings put secure launcher_type_before_pc_mode 1

# ④ 启动 PC 桌面
am start -a android.intent.action.MAIN -c android.intent.category.HOME \
   -n com.hihonor.android.launcher/com.hihonor.android.launcher.pc.PCLauncher
```
**风险/限制**：
- 前提是平板 + cust 配置存在（否则 `isPCLauncherEnable()=false`，开了也可能被回滚）；
- 官方流程会**设重启标记**并 `updatePersistentConfiguration()`，纯 adb 切换不会写这个，可能出现“Home 已换但系统仍在旧模式”的不一致；
- 极限省电模式下官方直接拒绝切换。

---

## 2. 一碰传（`com.hihonor.android.instantshare`）

### 2.1 ★ 结论：开关的真正写入方是 **system_server**，不在本应用里

点开关时（`OneTouchSettingsActivity.S(I)` 全文）：

```java
private void S(int value) {                                  // 也可由 V(I) 触发
    Intent i = new Intent();
    i.setComponent(new ComponentName(
            \"com.hihonor.systemserver\",
            \"com.hihonor.systemserver.onetouchshare.SwitchService\"));   // ★ 系统服务
    i.setAction(\"com.hihonor.onetouchshare.action.CHANGE_SWITCH\");
    i.putExtra(\"key_switch_change\", value);
    startService(i);
}
```
同一逻辑也封装在工具类 `i1/h.G(...)`（日志 `\"startChangeState\"`）与 `i1/h.d(ctx,int)`（`\"changeOneTouchSwitch: context is null.\"` → 单线程池执行）。
`OneTouchSettingsActivity.W(I)` 里还有 `\"startChangeSwitch\"` / `\"OneTouchShareSwitch\"` / `catch SecurityException` → **这条路径受权限保护**。

### 2.2 开关状态存在哪（本应用读）

`i1/h.m(Context)`：
```java
public static int m(Context ctx) {
    ContentResolver cr = ctx.getContentResolver();
    return Settings.Global.getInt(cr, \"one_touch_share_setting_state\", …);   // ★ Global 表
    // 异常日志：\"getHwShareSettingsState, context is null\" / \"can't get mHwShareState\"
}
```
→ **开关状态键 = `Settings.Global.one_touch_share_setting_state`**（本应用只读；写由 system_server 的 SwitchService 完成）。
（另有 `j1/d` 也引用同一个键名。）

### 2.3 能力门槛清单（`i1/h`）

| 方法 | 判定 | 依据 |
|---|---|---|
| `C(ctx)` | 是否支持 NFC | `PackageManager.hasSystemFeature(\"android.hardware.nfc\")`（日志 `\"support Nfc\"` / `\"get isSupportNfc failed…\"`） |
| `w()` | 必须是**主用户** | `UserHandleEx.myUserId()`（`\"not main user\"`） |
| `F()` / `y()` | 是否平板 | `SystemProperties.get(\"ro.build.characteristics\") == \"tablet\"` |
| `E(ctx)` | 三模 WiFi 支持 | `Settings.Global.getInt(\"hn_is_three_mode_support\")` |
| `A(ctx)` | 是否亮屏 | `PowerManager.isInteractive()` |
| `t()` / `u()` | 折叠形态（翻盖/内折） | `HwFoldScreenManagerEx.isVerticalInwardFoldDevice()` / `getDisplayMode()` |
| `f()` | `checkOneTouchActive` | 综合 `g1/d.z()` + `h1/j.h()/o()` + 开关状态 |
| `q()/g()/k()` | 工具：SHA-256 / AES-CBC-PKCS5 加解密 | 对应文案“热点信息加密传输给对端，即用即删” |

### 2.4 Provider 给设置/搜索用的接口

`com.hihonor.android.onetouchshare.provider.OneTouchSettingsProvider`：
```java
call(method, arg, extras)     // 支持 method = \"checkResIsSupportToSearch\"，日志 \"call method = … ; arg = … ; extras = …\"
b() / a()                     // checkMenuIsSupportToShow（用 i1/h.D() 与 i1/h.B() 判断菜单是否展示）
query/insert/update/delete    // 空实现
```
→ **设置里的“一碰传”入口是否显示、搜索是否能搜到，由这个 Provider 决定。**

### 2.5 NFC Tag 业务：碰一碰之后发生了什么（`g1/d` = NfcTagShareManager）

单例 `NfcTagShareManager.x(Context)`，核心方法：

| 方法 | 作用 | 关键日志/常量 |
|---|---|---|
| `h(int,int,boolean,JSONObject,Bundle)` | **onNfcTouchEvent**（一次触碰的业务分发） | `"onNfcTouchEvent, mHasTask now, return"`、`"onNfcTouchEvent, unknown biz: "`、`privateBizId` |
| `y(int,String,JSONObject,Bundle,List)` | **handleBizTouchEvent**（按 biz 规则匹配目标） | `"handleBizTouchEvent, match size: "`、`", res: "`、`", pkg: "`、`printer` |
| `w(int,int,String)` | callbackTouchEvent（回传结果） | `touchId`、`remoteDevType`、`extraStr` |
| `v(int,int,int)` | notifyShareTaskFinish | `touchId`、`state`、`errCode` |
| `C()` / `H()` | **loadBizConfigs / readConfigFile** | 读 **assets 里的 `nfc_tag_share_config.json`**，带 `version` 字段；`"loadBizConfigs: config file is empty"` |
| `K(Intent)` | **startTargetActivity** | `"startTargetActivity ActivityNotFoundException / SecurityException"` |
| `M(Intent)` | **startTargetService** | `"startTargetService Exception / SecurityException"` |
| `I(String)` / `A(String)` | 跳应用市场 | `"market://details?id="`、`"com.android.vending"`、`"jumpGoogleAppStore"` |
| `g(int,String)` | onAppStatus（对端 App 状态回调） | `"onAppStatus: status= …, extParam: "` |
| `G(String,String)` / `L()` | 组装并回发业务数据 | `APP_DATA`、`biz`、`callFrom`、`oneTouchShare` |

→ **业务规则来自 assets 的配置文件 `nfc_tag_share_config.json`**（由 `g1/b` = `NfcTagBizConfig` 建模）；命中规则后由本应用**启动目标 Activity/Service**，或跳应用市场。

### 2.6 传输与 UI 链路

```
NFC 触碰
  → g1/d.onNfcTouchEvent (NfcTagShareManager)
      → 读 assets/nfc_tag_share_config.json 匹配 biz
      → startTargetActivity / startTargetService（或 market://details?id=…）
      → 通知任务服务：OneTouchShareTaskService（handleNfcTouchEvent / MSG_FLAG_NFC_TOUCH_EVENT）
           ▼
UI：OneTouchSendActivity / OneTouchReceiveActivity / OneTouchUncertainActivity
    动效：com.honor.mage.effects.hnonetoucheffect.OneTouch
    其它入口：
      · WidgetService → action com.hihonor.instantshare.action.ONE_TOUCH_START_HONORSHARE_SERVICE
      · j1/d           → action com.hihonor.onetouchshare.action.SDK_BIND_ONE_TOUCH_SERVER
      · NearbyConfig.TriggerTypeEnum：TRIGGER_TYPE_ONE_TOUCH_NFC / TRIGGER_TYPE_ONE_TOUCH_GSENSOR
```
另有 `f1/*`（`f1/e.onTouchEvent: triggerType not nfc, return`、`f1/h`）负责 NFC 触碰事件接入。

### 2.7 官方给的开启路径（应用内文案，zh-rCN）

| 字符串 | 内容 |
|---|---|
| `help_one_touch_with_hn_content_1` | **“%1$d. 双方设备在‘设置’>‘更多连接’中开启一碰传。”** |
| `help_one_touch_with_apple_content` | **“荣耀手机在‘设置’>‘更多连接’中开启一碰传，苹果手机开启 NFC（苹果的 NFC 位置在顶部…）。”** |
| `enable_one_touch` | “开启一碰传” |
| `one_touch_already_enabled` | “一碰传已开启” |
| `one_touch_open_wifi_dialog_tips` | “‘一碰传’功能需要开启本设备的 Wi-Fi，请开启后重新碰一碰。” |
| `settings_one_touch_with_ios_title` / `_with_hn_title` / `_with_mac_title` | “与苹果手机一碰传” / “与荣耀设备一碰传” / “与苹果电脑一碰传” |
| `enhance_onetouch_share_service_title` / `_content_has_nfc` | “启用一碰传服务” / 隐私声明（**“使用 NFC 进行设备触碰，使用蓝牙、Wi-Fi 进行数据传输”**，热点信息加密传输、即用即删） |
| `one_tap_no_support_both_sending` | “一碰传暂不支持同时分享，本机自动作为发送方” |

### 2.8 手动尝试（多半会被权限拦）

```bash
# 方式 A：直接调用 system_server 的开关服务
am startservice -n com.hihonor.systemserver/.onetouchshare.SwitchService \
   -a com.hihonor.onetouchshare.action.CHANGE_SWITCH --ei key_switch_change 1

# 方式 B：直接改状态键（本应用只读它，但可能被 system_server 覆盖，也不触发真正的开启流程）
settings put global one_touch_share_setting_state 1
adb shell settings get global one_touch_share_setting_state

# 方式 C：看当前状态与能力
am start -n com.hihonor.android.instantshare/…OneTouchSettingsActivity   # 打开设置页人工确认
pm list features | grep nfc
getprop ro.build.characteristics
```
**预期**：A 大概率返回权限错误（`SecurityException`，服务需系统签名/特权）；B 能改值但**不会真正开启服务**（因为流程在 system_server 里）。
**正规路径**：**设置 → 更多连接 → 一碰传（开启）**，且需要：有 NFC 硬件、主用户、双方设备都开启、Wi-Fi 可用。

---

## 3. 对比与可操作性总结

| | PC 桌面（launcher） | 一碰传（instantshare） |
|---|---|---|
| 开关/状态存放 | 系统**持久化 Configuration**（simpleuiMode）+ `Settings.Secure.launcher_type_before_pc_mode` | `Settings.Global.one_touch_share_setting_state`（本应用只读） |
| 谁真正执行“开启” | **本应用自己**（改组件状态 + 启动 HOME Intent） | **system_server**：`com.hihonor.systemserver/.onetouchshare.SwitchService`（action `CHANGE_SWITCH`） |
| 先决条件 | 平板 + cust 配置 `xml/14x8/hn_launcher_default_workspace_pclauncher.xml` | NFC 硬件 + 主用户 + 双方开启 + Wi-Fi（三模支持 `hn_is_three_mode_support`） |
| 能否 adb 手动开 | ⚠️ 部分可以（`pm enable/disable` + HOME Intent），但可能状态不一致/被回滚 | ❌ 基本不行（服务受权限保护） |
| 是否需要重启 | 官方流程**设了重启标记**（`setRebootAfterSwitchPCLauncher(true)`） | 不需要 |
| 额外限制 | 极限省电模式下禁止切换；退出后强制竖屏 | 触碰依赖 NFC；业务规则由 `nfc_tag_share_config.json` 决定 |

---

## 4. 速查表

**PC 桌面**
```
组件        com.hihonor.android.launcher/.pc.PCLauncher
同族组件    .unihome.UniHomeLauncher / .drawer.DrawerLauncher / .newsimpleui.NewSimpleLauncher
            /.privacymoment.PrivacyMomentModeLauncher / .simpleui.SimpleUILauncher
            /.streetmode.StreetModeLauncher / .powersavemode.PowerSaveModeLauncher
launcherType  合法值 1 / 4 / 8（8 = PC 桌面）；5 = 新简易桌面（内部）
配置判定    HwCfgFilePolicy.getCfgFileList("xml/14x8/hn_launcher_default_workspace_pclauncher.xml")
Secure 键   launcher_type_before_pc_mode（默认 1，只接受 1/4）
SharedPref  launcher_type_before_enter_pc_mode
引导标记    divider_bubble_first_enter_pc_mode_showed / divider_bubble_pc_mode_pinning_changed_showed
核心类      PCLauncher / PCLauncherPolicy / HnDesktopUtils / HomeSwitcher / HnDesktopModeObserver
           HnDesktopVisibilityController / PCLauncherOtaActivity / HnPcLauncherBurnInProtectionController
```

**一碰传**
```
开关服务    com.hihonor.systemserver/.onetouchshare.SwitchService
Action      com.hihonor.onetouchshare.action.CHANGE_SWITCH（extra: key_switch_change）
状态键      Settings.Global.one_touch_share_setting_state
能力判定    android.hardware.nfc / 主用户 / ro.build.characteristics=tablet
            hn_is_three_mode_support（三模 WiFi）/ PowerManager.isInteractive
触发        NearbyConfig.TriggerTypeEnum.TRIGGER_TYPE_ONE_TOUCH_NFC / _GSENSOR
业务规则    assets/nfc_tag_share_config.json（g1/b = NfcTagBizConfig）
核心类      g1/d(NfcTagShareManager) / f1/*(NFC 触碰) / OneTouchShareTaskService
            OneTouchSettingsActivity / OneTouchHelpActivity / FirstUseGuideActivity
            OneTouchSend|Receive|UncertainActivity / OneTouchSettingsProvider
Action      com.hihonor.instantshare.action.ONE_TOUCH_START_HONORSHARE_SERVICE
            com.hihonor.onetouchshare.action.SDK_BIND_ONE_TOUCH_SERVER
其它        com.hihonor.systemserver.onetouchshare.common.OneTouchShareData（与 system_server 共享的数据类）
            msc.super_device.one_touch（系统属性，可配 debug）
```

---

## 5. 未确认项（诚实标注）

1. `com.hihonor.systemserver/.onetouchshare.SwitchService` 内部实现**不在本次两个包里**（需 framework/services 侧才能确认它怎么写状态、开了哪些服务）。
2. `Settings.getLauncherType()` 读的是 `ConfigurationEx` 的哪个字段名（推测为 `simpleuiMode`，已见 `setSimpleuiMode(type)` 与 `updatePersistentConfiguration`，但**读侧字段名未逐行确认**）。
3. `nfc_tag_share_config.json` 的具体规则字段（`g1/b` 的字段清单未逐行读）。
4. `f1/e` / `f1/h` 的 NFC 触碰接入方式（NDEF / 私有 Tag）未确认。
5. PC 桌面切换**是否必须重启**（只确认了官方会设重启标记，未确认不重启会怎样）。

---

*本文与 `全逻辑闭环2.md`（手写笔链路）、`华为vs荣耀对比.md`、`荣耀手写笔充电机制.md` 同目录。*
