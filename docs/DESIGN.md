# 设计文档

目标设备：Xiaomi MIX FOLD 1（代号 `cetus`），HyperOS 1.0（Android 13），
系统桌面 `com.miui.home` 4.39.12.x（`RELEASE-4.39.12.6887`）。文中行号/
字段以该版本反编译结果为准，关键钩子点已经设备实机 dexdump 复核。

## 1. 为什么运行时挂钩是唯一可行路线

`com.miui.home` 为平台签名 priv-app。`INTERNAL_SYSTEM_WINDOW` 等
`signature|privileged` 权限由 PackageManager 在**每次开机扫描时按 APK
签名证书**授予，与 APK 所在分区无关。实测两条替换路线：

| 路线 | 结果 |
|---|---|
| Core Patch 将自签 APK 作为更新装入 `/data` | 桌面循环崩溃：`BadTokenException: permission denied for window type 2027`（`GestureStubView` 初始化全屏手势侧边条） |
| Magisk 模块将自签 APK 挂载回 `/product/priv-app/MiuiHomeT/` | 同样崩溃；`dumpsys package` 显示 `INTERNAL_SYSTEM_WINDOW: granted=false`、`START_ANY_ACTIVITY: granted=false` |
| LSPosed 运行时挂钩 | APK 未改动，权限授予不受影响 ✅ |

结论：除非持有小米平台密钥，否则一切"换 APK"路线都会在权限授予环节失败。

## 2. 桌面架构关键点（逆向结论）

- **页面单元**：折叠大屏态下 `CellScreen.createCellScreen` 将页宽设为
  `屏宽 / 2`；`ScreenView.onMeasure` 按 `视口宽 ÷ 页宽` 计算 `mVisibleRange`
  → 外屏 1 页、展开竖持 2 页同屏。翻页吸附按 `mVisibleRange` 倍数校准
  （`calibrateCurrentScreenIndex`），成对出现
- **网格实例选择**：`LauncherCellCountCompat.getInstance()` 的分支顺序中
  `isFoldDevice()` 先于 `cetus` 判断 → cetus 上实际实例化的是
  `LauncherCellCountCompatDeviceFold`；`…DevicesCetus` 为不可达死代码
- **网格钉死**：两个设备类的 `getCellCountXDef/Max/Min` 均返回常量 4，
  且 `SharedPreferences` 初始化时会写回，因此仅改默认值无效，
  必须 Min/Max/Def（或其上游入口）一起接管
- **旋转排除**：`DeviceConfig.updateRotate` 硬编码
  `sRotatable = isInFoldDeviceLargeScreen(ctx) && !Build.DEVICE.equals("cetus")`
- **页内边距**：`CellLayout` 仅有 `mCellPaddingLeft`（无右内边距、无居中），
  网格自 `paddingLeft` 起笔、按 `mCellWidth + mWidthGap` 铺设
- **页间缝的几何来源**：`跨页列距 − 页内列距 ≡ 2 × workspaceCellPaddingSide`
  （展开态 stock 83px → 页间缝多出 166px）

## 3. 钩子清单

挂在 `com.miui.home` 进程内（经典 Xposed API）：

| # | 目标 | 方式 | 效果 |
|---|---|---|---|
| 1 | `DeviceConfig.updateRotate` | after | `sRotatable = isInFoldDeviceLargeScreen(ctx)`，去除 cetus 排除；上下文优先取**实时桌面 Activity**（见 1b） |
| 1b | `DeviceConfig.isRotatable` | before | 消费点实时重判折叠态：按桌面当前所在屏的 `smallestScreenWidthDp`（内屏 ≈654dp、外屏 ≈393dp，阈值 600），MIUI 自家 `isInFoldDeviceLargeScreen(实时上下文)` 作后备 |
| 2 | `LauncherCellCountCompatDevice.shouldUseDeviceValue` | replace→true | 绕过密度/主题门槛，保证设备值生效 |
| 3 | 基类 `getCellCountXDef/Max/Min` + Fold/Cetus 子类 `…FromDevice` | replace→统一列数 | 桌面列数（设置键 `cetus_home_columns`） |
| 4 | `DeviceConfig.setWorkspaceCellPadding` | after | 展开态 `sWorkspaceCellPaddingSide = B/2`（B 为外侧边距，竖/横独立键） |
| 5 | `CellLayout.onScreenOrientationChanged/onScreenSizeChanged` | after | 偶数页 `paddingLeft = B`、奇数页 `= 0`（反向对齐，模数随横屏页数）；仅展开态生效 |
| 6/7 | `CellScreen.createCellScreen/onScreenSizeChanged/onScreenOrientationChanged` | after | 页宽刷新：竖持 `屏宽/2`、横持三页模式 `屏宽/3`（原生仅在创建时赋值一次） |
| 8 | `DeviceConfig.calcGridSize` | after | 三页模式下工作宽跟随 `屏宽/3`，并触发 `calculateCellSize` 重算 |
| 9 | `DeviceConfig.getCellCountX` | before→实时列数 | 原生列数进程启动后缓存且旋转不重读；设置值进程内缓存一次，热路径零额外查询 |
| 10 | `Application.onCreate` | after | 注册进程内重启广播接收器 |
| 11/12 | `Launcher.onResume` / `Workspace.reorderScreens` | after | 偶数页策略：页数不足翻页单位时末尾补空白页（编辑模式跳过）；onResume 顺带触发 13b 的方向重发（去重，无开销） |
| 13 | `Launcher.checkDualScreen` | after | 三页模式下把每页页宽重新设为 `屏宽/3`：原生在每次配置变化后重断言页宽 `屏宽/2`，会覆盖钩子 6/7 的结果 |
| 13b | `Launcher.onConfigurationChanged` | after | 折叠/展开切换后重跑方向请求决策（复刻 `RotationHelper.notifyChange` 并按 `mLastActivityFlags` 去重） |
| 14 | `FoldScreenIndexCalculator.obtainValidCurrentScreenIndex` | before | 三页模式下吸附目标改为 3 页组边界（原生硬编码 `% 2` 成对校准并强制末页回退一位）；非三页模式不动 |
| 15/16 | `Workspace.deleteScreen(long)` / `removeScreen(int)` | before | 偶数页保护：移除末页会破坏翻页单位奇偶、且该页为空白页时否决移除（非空页始终放行）；仅展开态且「外侧边距优化」开启时生效，横屏三页模式不启用 |

另有 `Application.onCreate` after 中注册的进程内广播接收器
（`cetushome.rotationunlock.RESTART`），供控制面板触发桌面重启。

第三目标进程 `system_server`：**引擎模式（E0，见第 11 节）**——点亮休眠的
MIUI 折叠横屏引擎并桥接排除清单；第 10 节的 W1–W5 为引擎不可用时的
回退实现（当前 ROM 已验证引擎可用，未注册）。

另一目标进程 `com.android.systemui`（锁屏旋转解锁，根因与风险见第 9 节）：

| # | 目标 | 方式 | 效果 |
|---|---|---|---|
| S1 | `NotificationShadeWindowControllerImpl.adjustScreenOrientation`（`hookAllMethods`，避开 State 参数类型耦合） | after | 仅当结果为 PORTRAIT 且 `State.inLargeScreen`（内屏展开）时改写 `mLpChanged.screenOrientation` 为 USER；折叠态外屏锁屏与解锁路径（UNSPECIFIED）一律不改写。总开关 `cetus_home_lockscreen_rot`（默认开），同一轮 `apply()` 内 `applyWindowLayoutParams()` 会把改写后的参数推送上窗 |

## 4. 反向对齐的几何推导

设页宽 `W`（= 屏宽/2）、列宽 `c`、外侧边距 `B`、列数 `n`：

- 钩子 4 使 `工作宽 = W − B`，故 `c = (W − B) / n`
- 偶数页（paddingLeft = B）：列中心位于 `B + c/2 + k·c`，最右列贴到页缝
- 奇数页（paddingLeft = 0）：列中心位于 `W/2 + c/2 + k·c`，最右列留 `B` 到屏幕边
- 跨页列距 = `(W/2 + c/2) − (B + (n−1)c + c/2) = W/2 − B − (n−1)c = c`

即**跨页列距 ≡ 页内列距**，与列数、屏幕方向无关；边距 `B` 因此可以
任意加大而不会在页缝处产生豁口。对称边距方案则恒有
`跨页 − 页内 = 2 × padding`，这是两种方案的本质差异。

前提：翻页吸附按 `mVisibleRange` 倍数成对校准（见第 2 节），
否则会出现奇偶错位的页面组合。

## 5. 替换路线的失败证据

`/data` 更新路线崩溃栈（节选）：

```
FATAL EXCEPTION: FsGestureSecondaryThread
Process: com.miui.home
android.view.WindowManager$BadTokenException: Unable to add window
  -- permission denied for window type 2027
  at com.miui.home.recents.GestureStubView.<init>(GestureStubView.java:356)
```

`/product` 挂载路线的权限状态（`dumpsys package com.miui.home`）：

```
android.permission.INTERNAL_SYSTEM_WINDOW: granted=false
android.permission.START_ANY_ACTIVITY:     granted=false
```

两台状态下桌面均在全屏手势初始化时崩溃，与补丁内容无关。

## 6. LSPosed 注入注意事项

通过 adb 更新模块 APK 后，LSPosed 偶发不向随后启动的作用域应用重新
注入（无任何模块日志，功能整体回到原版行为）。整机重启可恢复。
判别方法：

```bash
adb logcat -d | grep CetusHomeHook   # 出现 "init complete" 即注入成功
```

因此发布/测试流程中，安装后建议整机重启一次再验证功能。

## 7. 原生的"销毁即自杀"机制（与本模块无关的后台重载）

`Launcher.onDestroy`（原版逻辑，非本模块代码）：

```java
if (Application.getLauncher() != this) {
    Process.killProcess(Process.myPid());   // Launcher.java，原生
    return;
}
```

系统在内存压力下销毁后台已久的桌面 Activity 时，若销毁实例与应用内
注册的当前实例不一致，MIUI 会故意杀掉整个进程并让系统经 FallbackHome
重新拉起——用户侧表现为"桌面崩溃重载"。触发条件是系统级内存压力，
与本模块无关（崩溃缓冲区无记录、模块接收器未触发、调用栈纯原生）。
伴随特征：整机 swap 高占用、load 飙升、某前台应用失控占用 CPU。

## 8. 外屏误旋转的根因与修正（v1.6.4）

预期是"外屏正常（锁定竖屏）、展开内屏跟随系统旋转"，实测展开后再折叠，
外屏桌面也能转到横屏。逆向结论（4.39.12）：

1. **方向请求的唯一出口**：`RotationHelper.notifyChange()`——
   `isRotatable()==true` 时 `setRequestedOrientation(UNSPECIFIED)`
   （跟随传感器/系统自动旋转），false 时按状态机请求锁定（空闲 →
   `PORTRAIT`）。请求**只在 notifyChange 里发出**，且其公开的重入点
   （`setCurrentStateRequest/setCurrentTransitionRequest`）都对"状态
   未变"去重。
2. **折叠切换不触发重判**：`Launcher` 声明了全部 `configChanges`
   （旋转/折叠均不重建 Activity），而 `RotationHelper.onConfigurationChanged`
   是一个**空方法**——折叠后没有任何路径重新调用 notifyChange，
   展开态发出的 `UNSPECIFIED` 请求原样残留在外屏上。
3. **静态值本身也过期**：`updateRotate` 只在
   `Application.onConfigurationChanged/onDisplayFoldChanged` 等少数时点
   调用，且实参是**应用上下文**；`isInFoldDeviceLargeScreen` 走
   `Configuration.getScreenType()` 反射路径，应用上下文的配置不随折叠
   可靠更新，折叠后仍判"大屏"→ `sRotatable=true`。

修正（钩子 1b + 13b）：

- **消费点实时重判**（1b）：`isRotatable()` before 钩子按桌面 Activity
  实时所在的屏重新推导（`smallestScreenWidthDp ≥ 600`，MIUI 自家
  判定作后备），所有消费方读到的都是当前真实状态，不再依赖任何缓存。
- **折叠后重发请求**（13b）：`Launcher.onConfigurationChanged` after
  钩子复刻 notifyChange 的决策并按 `mLastActivityFlags` 去重重发——
  展开态不变（`UNSPECIFIED`，去重为零开销），折叠后立即回落到
  `PORTRAIT` 锁定，与原版外屏行为一致。`Launcher.onResume` 顺带走
  同一去重路径作保险。

## 9. 锁屏旋转的根因与 SystemUI 钩子（S1）

适配 HyperOS V816.0.8.0.TJTCNXM（Android 13）的 MiuiSystemUI。实测
cetus 的锁屏（内屏展开/外屏折叠/下拉通知中心/bouncer/AOD）全部被锁死
竖屏，而解锁后旋转链路完全正常；WMS 的方向决策来源正是锁屏窗口
`NotificationShade`（`deepestLastOrientationSource=Window{NotificationShade}`）。

**根因**（反编译确认）：`NotificationShadeWindowControllerImpl.adjustScreenOrientation`
在 keyguard 相关分支里：

```java
if ((MiuiKeyguardUtils.IS_FOLD && !state.inLargeScreen)
        || Build.DEVICE.equalsIgnoreCase(DeviceConfig.DEVICE_CETUS)) {
    mLpChanged.screenOrientation = 1;   // PORTRAIT：cetus 无条件命中
} else if (isKeyguardScreenRotationAllowed()) { … }
```

`|| DEVICE == "cetus"` 恒真，折叠判定与旋转设置（AOSP `lockscreen.rot_override`
路径）全部短路失效——只有改写本方法的结果才行。方法在 `apply(State)`
中部被调用，其后 `applyWindowLayoutParams()` 会 `mLp.copyFrom(mLpChanged)`
并 `updateViewLayout` 上窗，故 after 钩子里只改 `mLpChanged` 即可，无需
自行 `setAttributes`。

**门控顺序**（折叠态外屏无条件不动）：

1. 总开关 `cetus_home_lockscreen_rot`（默认开，故障快速关闭）；
2. `lp.screenOrientation == 1`（PORTRAIT）才处理——解锁路径写
   `-1`（UNSPECIFIED），天然穿透，保证解锁后行为与原厂一致；
3. `State.inLargeScreen`（内屏展开）才改写为 `2`（USER，跟随系统自动
   旋转开关与用户旋转设定）；折叠态外屏的 PORTRAIT 原样保留。

**已知风险与回退**：MIUI 特判 cetus 疑因一代内屏横屏锁屏 UI 未适配。
横屏锁屏可能布局异常（时钟/壁纸/手势区/相机入口/通知中心）；横屏熄屏
AOD（`mDozing` 路径）若异常，在 after 内加
`if (XposedHelpers.getBooleanField(param.args[0], "mDozing")) return;`
即可回退 AOD 而保留亮屏锁屏旋转；整体不可接受则开关默认关。设置值
SystemUI 进程内读取一次，关闭开关后重启 SystemUI
（`pkill -f com.android.systemui`）恢复原厂行为。

**验收**：内屏展开+锁屏+横放
`dumpsys window windows | grep -A3 'u0 NotificationShade}' | grep or=`
应为 USER、`dumpsys window | grep mRotation=` 可到 1；折叠态外屏锁屏
必须仍为 PORTRAIT（无条件回归项）；解锁后旋转与改造前一致。

## 10. 内屏全局旋转的根因与 system_server 钩子（W1/W2/W3）

适配 HyperOS V816.0.8.0.TJTCNXM（Android 13）。cetus 上 MIUI 折叠屏横屏
适配引擎整体未启用：`ro.config.miui_orientation_enable` 未设置，引擎所有
调用链被 `MiuiOrientationStub.isEnabled()` 门控（`ActivityRecord.setOrientation`
的 MIUI 补丁 services.jar ~7094-7126 三重 guard 提前 return），实测为死代码。
应用各按 manifest 方向声明运行。因此不 hook 引擎写点，而在活跃写点
（W1 登记+中和）与两个**活跃读取点**施加等效改写（效果等同引擎
FULL_LANDSCAPE 分支：显示跟随自动旋转与控制中心旋转锁）：

| # | 目标 | 方式 | 效果 |
|---|---|---|---|
| W1 | `ActivityRecord.setOrientation(int, WindowContainer)` | before | 登记 + 请求中和：requested ∉ {-1,-2,3}、非相机包、且 `firstWindowDrawn==false`（构造期请求，此时 `mActivityComponent` 已赋值，取包名安全）→ 记入弱表（value = 登记时原请求，供恢复）；随后 d–g 门控全部满足时把请求参数与 `info.screenOrientation` 同时改写为 -1（等同引擎 FULL_LANDSCAPE case-1 的双写：启动预览窗按 `info` 渲染，漏写 `info` 会先闪一帧竖屏预览）；门控不满足则保留原请求、仅登记（由 W2/W3 在读点继续守护）。2 参覆写是 ActivityRecord 上唯一声明的 setOrientation（ctor 单参调用 ~1844 行经 `WindowContainer.setOrientation(int)` 虚派发必经此处） |
| W2 | `ActivityRecord.getOrientation(int)` | after | 命中成员改写为 `SCREEN_ORIENTATION_UNSPECIFIED(-1)`。**必须 hook 覆写本身**：本 ROM 的 ActivityRecord 覆写该方法且不调 super（~7206 行，内含投屏/嵌入干预分支），hook WindowContainer 基类版本实测零触发 |
| W3 | `WindowContainer.getRequestedConfigurationOrientation(boolean)` | after | `args[0]==true` 为 display 级变体，直接 return（永不涉及 activity）；`ActivityRecord.isInstance` 限定（ActivityRecord 未覆写该方法，已核实）；命中成员改写为 `Configuration.ORIENTATION_UNDEFINED(0)`。漏掉此点会出现"显示已横、窗口仍竖"的错位渲染 |
| W4 | `DisplayRotation.rotationForOrientation(int, int)` | before | 合盖强制竖屏：折叠时把默认显示的旋转决策钉死为 `ROTATION_0`（仅 `isDefaultDisplay` + `mPolicy.isDisplayFolded()`，总开关管辖）；**显式横屏请求（LANDSCAPE/SENSOR_LANDSCAPE/REVERSE_LANDSCAPE，即视频全屏类）放行**，外屏全屏视频保留原厂横屏能力。ROM 自身"外屏锁竖屏"配置对（`mUserRotationModeOuter`）从不被应用（全部更新路径被 `isEnabled()` 门控，实机 `mUserRotationMode=USER_ROTATION_FREE`），全局自动旋转常开时自由旋转类应用在原厂也会把外屏转横——W4 兑现"内屏自由、外屏锁定"（机主要求，超出原厂的显式模块行为） |
| W5 | `ActivityRecord.isSnapshotCompatible(TaskSnapshot)` | after | 功能开启时（d/c/e/f/g 门控通过）对快照启动窗一律返回 false：任务快照窗渲染磁盘缓存的截图位图，与 `info`/`mOrientation` 无关、也不要求应用已登记（原生 UNSPECIFIED 的拨号/时钟/日历从不进弱表）——竖屏持久化快照会在旋转跟随窗口前闪一帧竖屏；快照窗被跳过后回落 splash（已随 W1 的 info 中和跟随）。曾尝试"快照 rotation 与显示 rotation 不一致才跳过"的精细化判定，实机失效（启动窗决策时记录尚无可靠显示挂载），已回退为无条件跳过。TaskSnapshot 参数类在本 ROM 类加载器不可解析（ClassNotFoundError 实测），故按方法名 `hookAllMethods` 挂钩 |

**W2/W3 改写前提**（全部满足才改写；W2 实际顺序 b→d→e→a→c→f→g，与
原始 a→b→c→d→e→f→g 的偏差是**恢复可达性**要求所致：d/e 兼作恢复门，
而被中和记录的当前值恰为 -1，谓词 a 会先行 bail——d/e 若在 a 之后，
总开关关闭与排除清单的即时还原永远不可达，实机表现为"清理后台才生效"。
改写路径内 c 之后保持原顺序）：

b) 成员在弱表（弱表为空整链快速 bail——W2 是递归热路径）；
d) 总开关开启（否则还原已中和记录并返回）；
e) 包名不在用户排除清单（否则还原并返回）；
a) requested/当前值通过目标谓词 ∉ {-1,-2,3}。W3 里映射为配置空间等价形式：
   结果 ∈ {ORIENTATION_PORTRAIT(1), ORIENTATION_LANDSCAPE(2)}（UNDEFINED 即穿透）；
c) 不在内置相机名单 `HashSet.contains`；
f) **折叠门（硬保障）**：先查 ROM 自身全局折叠态
   `mPolicy.isDisplayFolded()`（与 MIUI 补丁同源的
   `mAtmService.mWindowManager.mPolicy.isDisplayFolded()` 反射调用，关闭
   折叠切换期 reparent 记录合并配置滞后的竞态窗口），再按 ActivityRecord
   实时合并配置的 `smallestScreenWidthDp` 判定（实机：内屏 676dp、外屏
   320dp，阈值 600——与桌面侧钩子同一判别式；低于任务书参考值 654/393，
   对阈值更宽容）。每次求值实时反射判定，任何异常或无法求值一律闭合为
   "折叠"→ 不改写，保证折叠态外屏永不触碰；
g) 非多窗口、非 MIUI 尺寸兼容——**例外：MIUI 平行窗口（嵌入）记录跟随
   旋转**（`inMultiWindowMode()` 为真时放行 `mMiuiEmbeddingEnabled`==true
   的记录，构造期 final 字段 ~340/~1826 按包启用；分屏/小窗/PIP 等其余
   多窗口保持原厂）。本 ROM 实际 API（均已反编译核实）：
   `ConfigurationContainer.inMultiWindowMode()`（继承，~253）、
   `ActivityRecord.inMiuiSizeCompatMode()`（~7178，MIUI 专有）。任一调用
   失败或 API 缺失一律闭合为"不允许改写"。原 `isEmbedded()` 检查已撤销：
   早前记录的"嵌入包解锁导致 MIUI 嵌入扩展崩溃（微博 → MiuiSplitController
   Invalid Task id）"经机主更正，实为第三方旋转模块（完美横屏应用计划，
   Magisk 模块）配置冲突所致，非嵌入解锁本身；如复现异常可重新收紧。

**弱表 key 选择**：ActivityRecord 实例本身（`WeakHashMap` +
`Collections.synchronizedMap`，求值在 WMS 锁下多线程进行）。W1 登记与
W2/W3 查询取的是同一引用（各自的 `param.thisObject`），零反射；IBinder
token 方案需在两侧各加一次反射取 token，无收益。

**配置**（两项，`Settings.System`，system_server 内 ContentObserver 缓存，
切换即时生效无需重启）：

- 总开关 `cetus_free_rot`：1=开（默认）。关闭后即使成员在弱表中也全部
  不改写（全机恢复原厂）。
- 排除清单 `cetus_free_rot_exclude`：分号分隔包名。语义只有一种：清单内
  的应用不旋转（维持原厂方向行为），其余全部跟随旋转。

懒加载红线：`handleLoadPackage("android")` 时 SettingsProvider 未就绪，
首次 hook 调用（`freeRotOn()`）才读配置；读取失败保持未加载态、下次调用
重试（期间取默认值：功能开、无排除）。Observer 注册失败仅损失即时性，
不损失正确性。

**内置相机名单**（代码常量，不可配置，任何状态排除；成像方向依赖传感器，
永远不动）：`com.android.camera`、`com.android.cameraextensions`、
`com.xiaomi.cameratools`（2026-10-31 实机 `pm list packages | grep -iE
'camera'` 核实）。

**已知偏差（设计代价，非 bug）**：弱表内成员运行期的
`setRequestedOrientation`（播放器点全屏、游戏切横屏）会被吸收为"跟随
旋转"，用户转回手持方向即可——"所有应用自由旋转"的固有代价；**未登记**
活动（构造后 `firstWindowDrawn==true` 才首次发出锁定请求的极少数路径）
的运行期请求保持原样。两条表述的区别：已登记 = 构造期请求进入过弱表，
此后其运行期方向请求全部被吸收；未登记 = 构造期从未发出过锁定请求，
其后续行为完全原厂。**外屏合盖强制竖屏（W4）**为模块行为而非原厂行为：
原厂在全局自动旋转开启时自由旋转类应用合盖后仍会转横（外屏锁定机制在
cetus 上是死代码，实机确认）；本模块按机主要求合盖钉死竖屏，关闭总开关
即回到原厂行为。

**LSPosed 注入注意（system_server 特有）**：lspd 对 system_server 的注入
发生在 PackageManager 就绪之前，模块 APK 路径取自其 `modules_config.db`。
覆盖安装若换了 `/data/app` 路径而 LSPosedService 未及刷新（其刷新例程
恰是开机扫描，路径失效即死循环），system_server 将静默不注入（app 进程
不受影响，lspd 会经 PM 重新解析）。现象：`logcat` 出现
`LSPosedService: Failure to parse package archive ... No such file or
directory`。处置：趁系统运行重新 `pm install -r` 一次（触发
PACKAGE_REPLACED 刷新路径）后重启；"系统框架"作用域在 LSPosed 中存储为
`system` 包名行，重装模块后需机主在管理器中重新确认。

**验收**：内屏展开后锁竖屏（设置/浏览器）与锁横屏（游戏/播放器）应用都
跟随物理旋转，`dumpsys window | grep 'mRotation='` 跟随变化；排除/开关
变更 `logcat` 中 `free-rot config reloaded` 逐一命中、无需重启；合盖后
所有应用竖屏 `mRotation=0`（无条件回归项）。

## 11. 引擎模式（E0）：点亮原生折叠横屏引擎

机主提出"移除全部 cetus 限制、与后面几代一视同仁"后，逆向确认该路线
成立并已实施，W1–W5（第 10 节）整体退役为回退实现：

- **引擎本体**：`com.android.server.wm.MiuiOrientationImpl`
  （miui-services.jar，`MiuiStubUtil` 单例，反编译确认实例即该类）。
  `isEnabled() = ENABLED(prop ro.config.miui_orientation_enable，cetus
  未设) && MIUI 优化 && 智能旋转设置` → cetus 上恒 false；但
  `IS_FOLD = persist.sys.muiltdisplay_type==2` 实机为 **true**，引擎完整
  可用。E0 = hook `isEnabled()` 前置返回总开关状态（`cetus_free_rot`，
  懒加载 + ContentObserver 与第 10 节同源）。
- **点亮后的原生能力**（全部是手搓 W1–W5 试图复刻的）：写点中和
  （`info.screenOrientation=-1` + `super(-1)`，启动窗构造性一致，闪帧/
  颓顿问题簇不存在）；内外屏旋转设置原生分离
  （`accelerometer_rotation_outer/inner`、`user_rotation_outer/inner`，
  折叠切换时经 `setUserRotation` 应用，外屏默认 LOCKED 竖屏、显式横屏
  （视频全屏）放行——第 10 节 W4 的语义原生成立）；嵌入记录引擎原生
  豁免（`isEmbedded(r)` → stock）；相机有专属策略
  （`FullScreen3AppCameraStrategy`）。
- **允许表桥接**：引擎内屏解锁是**按包允许表**（system/user 两级 map，
  cetus 系统表为空 → 不桥接则全 stock）。模块在首次 isEnabled 调用与
  排除清单变更时重建用户表：**全部已装应用 − 用户排除 − 相机 −
  桌面（com.miui.home，保留桌面侧自有钩子）**，值 `nr`（FULL_LANDSCAPE
  使能位），经 `clearUserSettings + setUserSettings` 全量重建，引擎持久
  化到自身设置文件。同步在配置线程执行（避免 WMS 锁内做 PM 查询）。
- **总开关语义**：关 → `isEnabled()` 恢复 false → 引擎休眠、全机原厂
  （允许表文件残留但引擎不读，无行为影响）。
- **已知限制**：引擎允许表在开机首次 isEnabled 调用与排除清单变更时全量
  重建，新安装应用需一次重启或任意排除变更才跟随内屏旋转（后续可加
  PACKAGE_ADDED 增量同步；选择器自身的应用列表已实时刷新：onResume 重查
  + 安装/替换/卸载广播）；外屏 LOCKED 对在折叠事件时应用，开机即折叠的
  场景需一次折叠循环生效。
- **侦察记录**：`MiuiStubUtil` 与引擎策略不在 services.jar（在
  miui-services.jar / miui-framework），`TaskSnapshot` 类在 system_server
  类加载器不可解析（W5 按名挂钩的原因）；引擎模式下 `systemContext()`
  必须用 lpp.classLoader（`actRecCls` 未解析，曾致 NPE 静默断链）。
