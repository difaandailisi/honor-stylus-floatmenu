# 一碰传 SDK 与“碰一碰”路由研究（代码级）

> **分析对象**：`com.hihonor.android.instantshare`（荣耀分享）**17.0.0.24**（工作区 `63v57kha`）
> **方法**：逐方法读 smali + dump assets 配置；结论均标注类/方法/常量。
> **回答**：① 一碰传的官方 SDK 接口是什么；② “碰一碰”的业务路由规则是什么；③ 能不能用实体 NFC 标签实现“贴一下传文件”。

---

## 0. 概览：三件事被挖出来了

| 发现 | 位置 | 意义 |
|---|---|---|
| **一碰传有官方 SDK** | `j1/b` + `j1/d` + AIDL `N2/a` / `N2/b` | 三方 App 可以通过它**注册分享能力并主动分享内容** |
| **碰一碰业务路由表** | `assets/nfc_tag_share_config.json` | 定义了 5 种 biz（现慧空间/打印/无线投屏/车机/美团）及其目标 App |
| **Nearby 能力白名单** | `assets/com_hihonor_nearby_whitelist.json` | 只有 7 个“包名+签名”通过校验的应用能用 Nearby 能力 |

---

## 1. 一碰传 SDK 全景

### 1.1 SDK 对外的入口类：`j1/b`（单例门面）

```java
public class j1.b {
    private static volatile j1.b b;      // 单例
    private final j1.d a;                // 真正的实现

    public static synchronized j1.b d()                                              // 获取单例
    public int a(Context, int, int, j1.a callback, Bundle)   // registerOneTouchShare（完整）
    public int b(Context, int, j1.a callback)                // registerOneTouchShare（简版）
    public int c(Context)                                   // deactivateShareIntention（注销）
    public boolean e()                                      // 是否支持（→ j1.d.t()）
    public int f()                                          // release（释放/解绑）
    public int g(j1.b$a data)                               // ★ share（分享内容）
}
```

### 1.2 绑定系统服务（`j1/d.o(Context)`：bindRemoteService）

```java
// 日志 TAG = "onetouchsharesdk-Delegate"
Intent i = new Intent()
    .setComponent(new ComponentName(
            "com.hihonor.systemserver",
            "com.hihonor.systemserver.onetouchshare.OneTouchShareService"))
    .setAction("com.hihonor.onetouchshare.action.SDK_BIND_ONE_TOUCH_SERVER");   // ★ SDK 绑定 action
bindService(i, mServiceConnection, …);
```
→ **所有能力都由 system_server 的 `OneTouchShareService` 提供**，SDK 只是客户端。

### 1.3 三层接口（真正的协议）

```java
// ① N2/a —— 服务端接口（客户端持有）
public interface N2.a extends IInterface {
    int X(int, String, N2.b callback, Bundle, String) -> int;      // ★ 激活（注册分享意图）
    int a(com.hihonor.systemserver.onetouchshare.common.OneTouchShareData) -> int;  // ★ 分享
    int i1() -> int;                                              // ★ 注销
}

// ② N2/b —— 服务端→客户端的 AIDL 回调
public interface N2.b extends IInterface {
    void Q(int, int, String);      // 状态变化（state / errCode / 附加串）
    void x1(int, String);          // 另一类回调
}

// ③ j1/a —— SDK 对外的 Java 回调接口（供接入方实现）
public interface j1.a {
    void a(int, int, String);
    void b(int, String);
}
```
→ 对应关系：`N2/b.Q` ↔ `j1/a.a`；`N2/b.x1` ↔ `j1/a.b`。

### 1.4 数据模型：`OneTouchShareData` 与它的 Builder

**类名**：`com.hihonor.systemserver.onetouchshare.common.OneTouchShareData`
（注意：**这个类跟 system_server 同名同包，也打包在本应用里** —— 因为它是跨进程共享的数据类）

**Builder：`j1.b$a` 字段表**

| 字段 | 类型 | 推测含义 | 设置方法 |
|---|---|---|---|
| `a` | `int` | 状态/标志位 | — |
| `b` | `List` | **待分享的内容/文件列表** | `l(List)` |
| `c` | `CharSequence` | **标题** | `j(CharSequence)` |
| `d` | `Bundle` | 透传 extras | `k(Bundle)` |
| `e` | `String` | 附加字符串 | — |
| `f` | `Uri` | **缩略图 URI（thumbnailUri）** | — |
| `g` | `byte[]` | **缩略图二进制（thumbnail）** | — |
| `h` | `String` | action 类字段 | — |

有效性校验：`j1.b$a.i()` → `!List.isEmpty() && !TextUtils.isEmpty(...) && !Bundle.isEmpty()`。
`j1/d.r(j1/b$a)` 里把 Builder 转成 `OneTouchShareData`（见到 `title` / `thumbnail` / `thumbnailUri` / `action` 四个 key）。

### 1.5 注册：`j1/d.n(Context, int, int, j1/a, Bundle)`（= activateShareIntention）

**方法体分支与返回码（逐条）**

| 返回码 | 触发条件 | 日志 |
|---|---|---|
| `-6` | 参数错误（context 为 null 或第 2 个 int ≤ 0 或 callback 为 null） | `"activateShareIntention: param err"` |
| `-2` | 设备不支持 | `"registerOneTouchShare: device is not support oneTouchShare."` |
| `-3` | **一碰传开关未开启**（`j1/d.s()` 读 `Settings.Global.one_touch_share_setting_state`） | `"activateShareIntention: oneTouchShare is not enable"` |
| `-1` | 取不到包名 / 远端服务为 null | `"registerOneTouchShare: can not get packageName by context"` / `"activateShareIntention: mOneTouchShareService is null."` |
| `-5` | 重复激活（同一 Context 已激活） | `"activateShareIntention: Intention multiple activate"` |
| `0` | 成功 | 若服务已在 → 直接走 `j1/d.m(packageName)`；否则起新线程 `j1/c` 去 bindService，并先 `"activateShareIntention: mOneTouchShareService exist"` |

**关键实现细节**
```java
// 保存参数
this.a = ctx; this.h = true /*已激活*/; this.e = callback; this.k = p3; this.l = bundle;
// 包名（用于告诉系统“我是谁在分享”）
this.c = ctx.getApplicationInfo().packageName;
```
→ 所以 `j1/b.a(Context, int, int, j1.a, Bundle)` 里那两个 int 中的一个用于“激活标志/业务编码”，另一个存入 `k`。

### 1.6 分享：`j1/d.w(j1/b$a data)`（= share / oneTouchShareSend）

| 返回码 | 触发条件 | 日志 |
|---|---|---|
| `-7` | **未先激活**（`h == false`） | `"share: mIsActivateShareIntention is false"` |
| `-1` | context 为 null | `"oneTouchShareSend: context is null."` |
| `-2` | 设备不支持 | `"share: device is not support oneTouchShare."` |
| `-1`/异常 | 内容校验不过：`if ((j & 1) != 0 && data.i())` → 即“该 biz 要求必须有内容，而 data 为空” | — |
| 其它 | 成功路径日志 | `"oneTouchShareSend, packageName:" + c` |

→ **调用顺序必须是：先 `registerOneTouchShare(...)` 激活，再 `share(data)`。**

### 1.7 注销与释放

```java
j1/b.c(Context) → j1/d.q(Context)   // deactivateShareIntention
   // 日志："deactivateShareIntention: device is not support oneTouchShare."
   //       "deactivateShareIntention: Intention not activate"
   //       "activateShareIntention: callback is not register"
   //       "activateShareIntention: catch RemoteException."
j1/b.f() → j1/d.v()                 // release：内部会 try deactivate + unbindService
   // 日志："release: device is not support oneTouchShare." / "release: unbindService" / "release: context is null."
```

### 1.8 接入的前置门槛（都来自代码/配置）

| 门槛 | 判定位置 |
|---|---|
| 设备支持一碰传 | `j1/d.p()` 反射读系统属性 **`msc.super_device.one_touch`**（`p()` 里还用字符串 `touchCapability` / `10.0.0.106` 作为能力与版本） |
| 一碰传开关已开启 | `j1/d.s()` 读 `Settings.Global.one_touch_share_setting_state` |
| 主用户 | `i1/h.w()` → `UserHandleEx.myUserId()`（`"not main user"`） |
| NFC 硬件 | `i1/h.C()` → `PackageManager.hasSystemFeature("android.hardware.nfc")` |
| Nearby 能力白名单 | `assets/com_hihonor_nearby_whitelist.json`（**包名 + 签名**双校验） |
| 三模 WiFi / 亮屏 / 折叠形态 | `i1/h.E()`（`hn_is_three_mode_support`）、`i1/h.A()`、`i1/h.t()/u()` |

**Nearby 白名单全文（7 条）**
```json
{ "whitelist": [
  { "packageName": "com.hihonor.magichome",          "signature": "C3993A5EAA8E41A802DA97AEDDB399642F2F6D68E8A6FC774C3253F1680F0FF5" },
  { "packageName": "com.hihonor.deviceauth",         "signature": "483013CE65E1B0E10AD76A2EB80409A2A2B8B35EBE81A33B1F6EDD7FACBD64DC" },
  { "packageName": "honor_system_signal",            "signature": "483013CE65E1B0E10AD76A2EB80409A2A2B8B35EBE81A33B1F6EDD7FACBD64DC" },
  { "packageName": "com.hihonor.contacts.sync",      "signature": "fac8929978968d665951971bc502f04d19d680c54d0e1ef9df5272230b5f7679" },
  { "packageName": "com.hihonor.communication.demo", "signature": "483013ce65e1b0e10ad76a2eb80409a2a2b8b35ebe81a33b1f6edd7facbd64dc" },
  { "packageName": "com.hihonor.dmkitdemo",          "signature": "621e1faeff9a745b7f821eff363ba536a70fc5d7b91ffba564c4029f4aa4c4b8" },
  { "packageName": "com.hihonor.hisight",            "signature": "483013ce65e1b0e10ad76a2eb80409a2a2b8b35ebe81a33b1f6edd7facbd64dc" }
] }
```
> 注意：这几个签名里 `483013CE…` 就是**荣耀系统签名**（与手写笔那批包同一张证书）。\n> 也就是说：**Nearby/碰一碰能力面向“系统签名的自家应用”开放**，第三方 App 能否用要看是否另售 SDK 白名单。

---

## 2. “碰一碰”业务路由机制

### 2.1 `assets/nfc_tag_share_config.json` 全文（2301 B）

```json
{
  "doll": {
    "card":        { "type": 2, "triggerType": 1002 },
    "launchMode":     "activity",
    "appIdKeyInNfc": "app",
    "apps": {
      "01": {
        "pkg":  "com.hihonor.hnsmartwarehub",
        "class": "com.hihonor.hnsmartwarehub.view.MainActivity",
        "prePull": {
          "pkg":    "com.hihonor.hnsmartwarehub",
          "class":  "com.hihonor.hnsmartwarehub.view.QueryDataActivity"
        }
      }
    },
    "intentExtras": [
      { "key": "APP_DATA",  "source": "nfcTag", "field": "biz" }
    ]
  },

  "printer": {
    "card":        { "type": 1, "triggerType": 1004 },
    "appIdKeyInNfc": "app",
    "apps": { "01": { "launchMode": "callback_touch" } }
  },

  "WirelessCasting": {
    "card":        { "type": 1, "triggerType": 1003 },
    "pkg":    "com.hihonor.pcassistant",
    "class":  "com.hihonor.pcassistant.service.CloseRangeListenerService",
    "launchMode":     "service",
    "action":   "android.nfc.action.NDEF_DISCOVERED",
    "intentExtras": [
      { "key": "intent",     "source": "nfcTag", "field": "intent",   "type": "parcelable", "class": "android.content.Intent" },
      { "key": "devName",    "source": "nfcTag", "field": "devName" },
      { "key": "screenType", "source": "nfcTag", "field": "screenType" }
    ]
  },

  "HonorAuto": {
    "card":        { "type": 1, "triggerType": 1005 },
    "dpLink": "hihonor://car/connect?mac=&moduleId=&businessId=&pin=&vender=&discoveryType=NFC",
    "launchMode":     "activity",
    "action":   "com.hihonor.auto.deeplink.CONNECT_CAR",
    "pkg": "com.hihonor.auto",
    "browser": false,
    "appMarket": true
  },

  "meituan": {
    "card":        { "type": 1, "triggerType": 1006 },
    "apps": [
      { "pkg": "com.sankuai.meituan", "class": "com.meituan.android.nfc.NFCLinkActivity",
        "launchMode": "activity", "action": "android.nfc.action.NDEF_DISCOVERED",
        "dpLink": { "source": "nfcTag", "field": "uri" }, "browser": false, "appMarket": false },
      { "launchMode": "activity", "action": "android.intent.action.VIEW",
        "dpLink": "hap://app/com.sankuai.meituan/Detail?key1=1", "browser": true }
    ]
  }
}
```

### 2.2 字段解释

| 字段 | 含义 |
|---|---|
| `card.type` | 卡片类型（1 / 2） |
| `card.triggerType` | **触发方式编号**（见下表 1002~1006） |
| `launchMode` | `activity` / `service` / `callback_touch` |
| `appIdKeyInNfc` | **NFC 数据里哪个 key 表示 appId**（值为 `"app"`） |
| `apps`（对象/数组） | 目标应用列表（`pkg` + `class` + `launchMode` + `action` + `dpLink`） |
| `prePull` | 预先拉起（提前加载）的组件 |
| `intentExtras` | **从 NFC 数据里取字段填进 Intent**（`source: "nfcTag"`，`field` 可为 `biz` / `intent` / `devName` / `screenType` / `uri`） |
| `browser` / `appMarket` | 未安装时是否走浏览器 / 应用市场（如 `market://details?id=`） |

### 2.3 业务（biz）总表

| biz | triggerType | 目标 | launchMode | 说明 |
|---|---|---|---|---|
| `doll` | **1002** | `com.hihonor.hnsmartwarehub/.view.MainActivity`（预拉 `QueryDataActivity`） | activity | 荣耀智慧空间（智能玩具？） |
| `printer` | **1004** | — | **callback_touch** | 打印（由回调决定目标） |
| `WirelessCasting` | **1003** | `com.hihonor.pcassistant/.service.CloseRangeListenerService` | **service** | 无线投屏 / PC 协同，action = `NDEF_DISCOVERED` |
| `HonorAuto` | **1005** | `com.hihonor.auto`（deeplink `hihonor://car/connect?…&discoveryType=NFC`） | activity | 车机连接（未安装走应用市场） |
| `meituan` | **1006** | `com.sankuai.meituan/.nfc.NFCLinkActivity`（备选 `hap://app/…`） | activity | 美团（含快应用 fallback） |

> `triggerType` 1001 未在本配置中出现（可能内置在其他代码里）。

### 2.4 关键：本应用**不是**标签读取方

| 检查 | 结果 |
|---|---|
| manifest 里搜 `nfc.action`（NDEF/TECH/TAG_DISCOVERED） | **0 命中** |
| 代码里搜 `enableReaderMode` | **0 命中** |
| 但配置里出现 `android.nfc.action.NDEF_DISCOVERED` | ✅ 说明**存在一个上游读标签的组件** |

→ 机制推断（与代码一致）：**上游（系统 NFC 服务 / 系统 UI / 智慧空间等）读到 NFC 标签后，按 `triggerType` 把事件与标签字段（含 `app` = appId）交给本应用的 `NfcTagShareManager.onNfcTouchEvent(...)`，本应用再按 `nfc_tag_share_config.json` 路由到目标应用或服务。**
（本应用内 `g1/d` 里对应方法：`h(...)` = onNfcTouchEvent、`y(...)` = handleBizTouchEvent、`C()/H()` = 读本配置文件、`K()/M()` = 启动目标 Activity/Service。）

---

## 3. 回到你的需求：NFC 标签能不能实现“贴一下传文件”？

### 3.1 结论（分层）

| 想法 | 可行性 | 原因 |
|---|---|---|
| 用 **adb** 直接开启一碰传 | ❌ 不行 | 开关写入方是 system_server 的 `SwitchService`（受系统权限保护）；改 `Settings.Global.one_touch_share_setting_state` 只是改数字，不会真开启 |
| 贴一个**普通 NDEF URL 标签**就自动用一碰传传文件 | ❌ 不行 | ① 本应用无 NFC 标签入口（manifest 无 filter + 无 reader mode）；② **标签不能当接收方**，一碰传是“设备↔设备”会话（NFC 握手 → Nearby 建 Wi-Fi/BT → 传文件） |
| 贴标签 → **自己写 App** 处理标签 → 调一碰传 SDK 发送 | ✅ **可行**（推荐） | SDK 入口已确认：bind `com.hihonor.systemserver/.onetouchshare.OneTouchShareService`（action `SDK_BIND_ONE_TOUCH_SERVER`），`registerOneTouchShare` + `share(OneTouchShareData)` |
| 复用系统已有的**碰一碰路由**（`nfc_tag_share_config.json`） | ⚠️ 有条件可行 | 需要标签内容符合其约定（`appIdKeyInNfc = "app"` + `triggerType` 对应），且由上游组件派发；当前配置里的 biz 是智慧空间/打印/投屏/车机/美团，**不含“传图片文件给任意设备”** |

### 3.2 推荐方案：自建 App + 官方 SDK（伪代码）

```java
// 1) 你的 App 声明接标签（AndroidManifest）
//    <intent-filter>
//        <action android:name="android.nfc.action.NDEF_DISCOVERED"/>
//        <category android:name="android.intent.category.DEFAULT"/>
//        <data android:scheme="https" android:host="your.domain"/>   // 或你的自定义 scheme
//    </intent-filter>

// 2) 贴标签后，你的 Activity 被拉起 → 调 SDK
OneTouchShareSdk sdk = OneTouchShareSdk.getInstance();     // j1/b.d()

// 2.1 注册分享意图（activateShareIntention）
int r = sdk.register(                                   // j1/b.a(Context,int,int,j1.a,Bundle)
        this, bizFlag, bizCode,
        new Callback() {                                // j1/a
            public void onState(int a, int b, String s) { /* N2/b.Q → j1/a.a */ }
            public void onOther(int a, String s)        { /* N2/b.x1 → j1/a.b */ }
        },
        new Bundle() /* 可放 touchCapability 等 */ );
// 返回码：0 成功 / -1 取不到包名 / -2 设备不支持 / -3 一碰传未开启 / -5 重复激活 / -6 参数错

// 2.2 分享内容（share）
OneTouchShareData.Builder b = new OneTouchShareData.Builder();
b.l(fileList);            // ★ 待分享的文件/URI 列表
b.j("标题");              // title
b.k(extras);              // Bundle
// b 里还有 thumbnail(byte[]) / thumbnailUri(Uri) 等字段
int rr = sdk.share(b.build());                          // j1/b.g → j1/d.w
// 返回码：-1 context null / -2 设备不支持 / -7 未先激活

// 3) 用完注销
sdk.deactivate(this);     // j1/b.c → j1/d.q
sdk.release();            // j1/b.f → j1/d.v（内部 unbindService）
```

**前提**（缺一不可）：
1. 一碰传**开关已开启**（`Settings.Global.one_touch_share_setting_state`）；
2. 设备支持（系统属性 `msc.super_device.one_touch` 非 0）；
3. **主用户** + 有 NFC；
4. 若涉及 Nearby 能力，还需命中 `com_hihonor_nearby_whitelist.json`（**包名+签名**双校验，当前 7 条均为荣耀系统签名应用）→ **第三方 App 是否可用，取决于荣耀是否给 SDK 白名单**。

### 3.3 备选：用普通“分享”入口（不是一碰传）

manifest 里还有一个**对外公开的分享入口**：
```xml
<activity android:name="com.hihonor.android.hwshare.ui.ThirdShareActivity" android:exported="true">
    <intent-filter><action android:name="android.intent.action.SEND"/>          <data android:mimeType="*/*"/></intent-filter>
    <intent-filter><action android:name="android.intent.action.SEND_MULTIPLE"/> <data android:mimeType="*/*"/></intent-filter>
    <intent-filter><action android:name="android.intent.action.VIEW"/> …</intent-filter>
</activity>
```
→ 任何 App（或你的标签 App）都可以用 `ACTION_SEND`（带 `EXTRA_STREAM`）把文件交给荣耀分享；但它走的是**普通分享流程（选设备）**，不是“一碰即传”。

---

## 4. 其它 assets（顺手 dump 出的资产清单）

| 资产 | 大小 | 作用 |
|---|---|---|
| `assets/nfc_tag_share_config.json` | 2301 B | ★ 碰一碰业务路由表（见 §2） |
| `assets/com_hihonor_nearby_whitelist.json` | 1048 B | ★ Nearby 能力白名单（包名+签名） |
| `assets/onetouch_brand_tips/brand_tips.json` | 39867 B | **跨品牌一碰传引导页配置**（含苹果等），配图 `images/img_0.png` / `img_1.png` |
| `assets/onetouch_feature_introduce/feature_introduce_{dark,light}.json` | 57 KB | 一碰传**功能介绍动效**（Lottie），配图 6 张 |
| `assets/hihonor/connected_anim.json` / `connected_loop_anim.json` | 24.8 KB | 连接/循环动效 |
| `assets/adi-registration.properties` | 26 B | 华为分析 SDK 的 appId：`C3EAG3QSSZ7JYAAAAAAAAAAAAA` |
| `assets/grs_*` / `global_cfg_for_android_mobile_honor.xml` / `rootcas.bks` 等 | — | GRS（华为路由/证书）与网络基础配置 |

---

## 5. 速查表

```
# 一碰传 SDK
入口类        j1.b（单例 d()）；实现类 j1.d；回调接口 j1.a
绑定服务      com.hihonor.systemserver/.onetouchshare.OneTouchShareService
绑定 Action   com.hihonor.onetouchshare.action.SDK_BIND_ONE_TOUCH_SERVER
AIDL          N2.a = X(int,String,N2.b,Bundle,String) / a(OneTouchShareData) / i1()
              N2.b = Q(int,int,String) / x1(int,String)
数据模型      com.hihonor.systemserver.onetouchshare.common.OneTouchShareData
Builder       j1.b$a { a:int, b:List, c:title, d:Bundle, e:String, f:thumbnailUri, g:thumbnail[], h:String }
开关状态键    Settings.Global.one_touch_share_setting_state（只读；写方为 system_server）
开关服务      com.hihonor.systemserver/.onetouchshare.SwitchService + action CHANGE_SWITCH
支持属性      msc.super_device.one_touch（+ touchCapability / 10.0.0.106）

# 碰一碰路由
配置          assets/nfc_tag_share_config.json
biz           doll(1002) / printer(1004) / WirelessCasting(1003) / HonorAuto(1005) / meituan(1006)
字段          card.type, card.triggerType, launchMode(activity|service|callback_touch),
              appIdKeyInNfc="app", apps[].{pkg,class,action,dpLink}, prePull, intentExtras[source=nfcTag],
              browser, appMarket
引用类        g1/d(NfcTagShareManager), f1/*（NFC 触碰）, g1/b(NfcTagBizConfig)

# 分享入口（非一碰传）
com.hihonor.android.hwshare.ui.ThirdShareActivity（exported，支持 SEND / SEND_MULTIPLE / VIEW，*/*）
```

---

## 6. 未确认项（诚实标注）

1. **`OneTouchShareService` / `SwitchService` 的内部实现不在本包内**（在 system_server / framework 侧）—— 因此无法从静态分析确认“SDK 是否对第三方开放”“是否需要额外签名/白名单授权”。
2. `N2/a.X(int,String,N2.b,Bundle,String)` 的**5 个参数精确语义**（哪个是 bizId / 版本 / action）未逐行确认（已确认 bundle 里会用 `touchCapability`）。
3. `j1/d.j` 这个“激活标志位”与 `share()` 里 `(j & 1)` 校验的**具体语义**（可能是“是否允许空内容分享”）未确认。
4. `triggerType` 的**完整取值表**（本配置只见到 1002~1006；1001 及更大值未出现）。
5. **上游读标签组件是谁**（哪个系统进程把 NFC 数据交给 `NfcTagShareManager`）未确认 —— 这决定了“实体标签能否直接触发碰一碰”。
6. `assets/onetouch_brand_tips/brand_tips.json`（39 KB）未展开 —— 里面是跨品牌引导的完整文案/图示配置，可另行 dump。

---

*本文与 `PC桌面与一碰传开启逻辑.md`（开启路径与门槛）、`华为vs荣耀对比.md`、`全逻辑闭环2.md`、`荣耀手写笔充电机制.md` 同目录。*
