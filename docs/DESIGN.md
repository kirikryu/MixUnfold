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
| 3 | 基类 `getCellCountXDef/Max/Min` + Fold/Cetus 子类 `…FromDevice` | replace→统一列数 | 桌面列数（配置键 `cetus_home_columns`，私有 prefs，见第 15 节） |
| 4 | `DeviceConfig.setWorkspaceCellPadding` | after | 展开态 `sWorkspaceCellPaddingSide = B/2`（B 为外侧边距，竖/横独立键） |
| 5 | `CellLayout.onScreenOrientationChanged/onScreenSizeChanged` | after | 偶数页 `paddingLeft = B`、奇数页 `= 0`（反向对齐，模数随横屏页数）；仅展开态生效 |
| 6/7 | `CellScreen.createCellScreen/onScreenSizeChanged/onScreenOrientationChanged` | after | 页宽刷新：竖持 `屏宽/2`、横持三页模式 `屏宽/3`（原生仅在创建时赋值一次） |
| 8 | `DeviceConfig.calcGridSize` | after | 三页模式下工作宽跟随 `屏宽/3`，并触发 `calculateCellSize` 重算 |
| 9 | `DeviceConfig.getCellCountX` | before→实时列数 | 原生列数进程启动后缓存且旋转不重读；配置值进程内缓存一次，热路径零额外查询 |
| 10 | `Application.onCreate` | after | 注册进程内重启广播接收器 |
| 11/12 | `Launcher.onResume` / `Launcher.onConfigurationChanged`（13b 复用路径）/ `Workspace.reorderScreens`（后两者 post 延迟执行） | after | 偶数页策略：页数为奇且末页**有内容**时补 1 页空白；末尾空白页**一律不动**（可能是原厂拖拽预备页或用户刚建的页，见第 12 节）；编辑模式跳过；重入锁防双补；onResume 顺带触发 13b 的方向重发（去重，无开销） |
| 13 | `Launcher.checkDualScreen` | after | 三页模式下把每页页宽重新设为 `屏宽/3`：原生在每次配置变化后重断言页宽 `屏宽/2`，会覆盖钩子 6/7 的结果 |
| 13b | `Launcher.onConfigurationChanged` | after | 折叠/展开切换后重跑方向请求决策（复刻 `RotationHelper.notifyChange` 并按 `mLastActivityFlags` 去重）；顺带 post 偶数页检查——Launcher 自消化 configChanges（旋转不重建、不触发 onResume），不挂这里的话旋转后奇偶约束的切换要等下次 resume 才生效 |
| 14 | `FoldScreenIndexCalculator.obtainValidCurrentScreenIndex` | before | 三页模式下吸附目标改为 3 页组边界（原生硬编码 `% 2` 成对校准并强制末页回退一位）；非三页模式不动 |
| 15/16 | `Workspace.deleteScreen(long)` / `removeScreen(int)` | before | 偶数页保护：**仅编辑模式**中，移除末页会破坏翻页单位奇偶、且该页为空白页时否决移除（非空页始终放行；普通模式的移除属于原厂自身流程——拖边建页会重建末尾页，拦截会弄死手势）；仅展开态且「外侧边距优化」开启时生效，横屏三页模式不启用 |
| 17 | `ItemInfo.load(Context, Cursor)` | after | 运行时小部件收缩：桌面容器（`container==-100`）条目解析后 `spanX→min(spanX,列数)`、`cellX→min(cellX,列数−spanX)`（仅 X 侧——模块从不改行数，Y 不可能越界）。**不写回数据库**，列数调回原值重启桌面即恢复原始尺寸。根因与数学见第 14 节 |
| 18 | `MIUIWidgetCompat.createItemInfo(Launcher, Bundle)` | after | MIUI 小部件添加路径 span 归一：选择器 bundle 的 span 按**原厂网格**标注，原生仅在 `getCellCountX()==getCellCountXMin()` 时（"最窄网格，选择器尺寸可信"）用 bundle 原值覆盖——模块把 Min 钉到设置列数使该门恒真，4 列标注的 spanX=4 进入 3 列网格被 `Workspace.acceptDrop`（:1320）直接拒绝（"添加不了"）。钳制返回对象的 `spanX`，覆盖选择器拖出 / 预备 / 替换全部三个调用方（§14 根因三） |
| 19 | `MIUIWidgetReplaceHandler.canReplace(Bundle, MIUIWidgetBasicInfo)` | before | 更换小部件路径的同一标注错配、且早一重门：`canReplace`（:15-20）要求**旧部件运行时 spanX ≥ 新部件 bundle 标注 spanX**——收窄网格下旧部件显示宽为钳制后的 3、bundle 仍标 4，`3>=4` 恒假 → `replaceWidget abort`（:72-74，在移除旧部件之前返回）→ 更换无声无息不生效（4 列 4≥4 恒过；列数 <4 均触发）。仅当标注 spanX 超出当前列数时按 `WidgetSpan.fitSpanX` 折算后重判，Y 比较与不超界情形完全走原厂语义（§14 根因四） |

表中钩子 4/5/6/7/8/11/12/14/15/16 的方向/模式判定统一经 `modeCtx()`
取**实时桌面 Activity** 的 configuration（Activity 尚未创建的进程启动早期
回退 app 上下文）。根因见第 12 节：app 上下文的方向在折叠切换后同样残留
旧值，曾致横屏三页模式偶发误补空白页。

配置来源自 1.9.0 起为模块私有 `SharedPreferences`（写路径、迁移与
跨进程读取见第 15 节）；钩子目标、方式、数学与编号均未变化。

另有 `Application.onCreate` after 中注册的进程内广播接收器
（`cetushome.rotationunlock.RESTART`），保留作手动/调试重启入口；控制
面板的「重启系统桌面」走固定命令的 su 路径（见第 15 节），不发送该广播。

第三目标进程 `system_server`：**引擎模式（E0+E1，见第 11 节）**——点亮休眠的
MIUI 折叠横屏引擎并桥接排除清单（E0）与平行窗口旋转（E1）；第 10 节的
W1–W5 为引擎不可用时的回退实现（当前 ROM 已验证引擎可用，未注册）。

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

**配置**（两项，模块私有 prefs；存储、迁移与跨进程读取见第 15 节。
system_server 在 `freeRotOn()` 内做 500ms 去抖重读，切换 ≤0.5s 生效，
无需重启）：

- 总开关 `cetus_free_rot`：1=开（默认）。关闭后即使成员在弱表中也全部
  不改写（全机恢复原厂）。
- 排除清单 `cetus_free_rot_exclude`：分号分隔包名。语义只有一种：清单内
  的应用不旋转（维持原厂方向行为），其余全部跟随旋转。段按
  `^[A-Za-z0-9_.]+$` 白名单过滤（公共 `ExcludeCodec` 统一解析/重组，
  非法段丢弃并记 logcat）：该值可能来自 1.8.x `Settings.System` 的
  迁移，仍按不可信输入处理，非法段不进入匹配逻辑。

懒加载红线：`handleLoadPackage("android")` 时 SettingsProvider 未就绪；
私有 prefs 无 provider 依赖。仅当本次是覆盖升级且 prefs 仍缺 key 时，
才只读回落旧 `Settings.System`——provider 未就绪则保持未加载态、下次
调用重试（期间取默认值：功能开、无排除）。迁移完成后 8 个键齐全，不再
读旧命名空间。

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
  懒加载 + 500ms 去抖重读，配置存储见第 15 节）。
- **点亮后的原生能力**（全部是手搓 W1–W5 试图复刻的）：写点中和
  （`info.screenOrientation=-1` + `super(-1)`，启动窗构造性一致，闪帧/
  颓顿问题簇不存在）；内外屏旋转设置原生分离
  （`accelerometer_rotation_outer/inner`、`user_rotation_outer/inner`，
  折叠切换时经 `setUserRotation` 应用，外屏默认 LOCKED 竖屏、显式横屏
  （视频全屏）放行——第 10 节 W4 的语义原生成立）；嵌入记录引擎原生
  豁免（`isNeedUpdateConfig` 对嵌入记录返回 false → 引擎不干预；平行窗口
  的方向入口是嵌入控制器，由 E1 接管，见下）；相机有专属策略
  （`FullScreen3AppCameraStrategy`）。
- **允许表桥接**：引擎内屏解锁是**按包允许表**（system/user 两级 map，
  cetus 系统表为空 → 不桥接则全 stock）。模块在首次 isEnabled 调用与
  排除清单变更时重建用户表：**全部已装应用 − 用户排除 − 相机 −
  桌面（com.miui.home，保留桌面侧自有钩子）**，值 `nr`（FULL_LANDSCAPE
  使能位），经 `clearUserSettings + setUserSettings` 全量重建，引擎持久
  化到自身设置文件。同步在配置线程执行（避免 WMS 锁内做 PM 查询）。
- **平行窗口旋转桥接（E1a + E1b）**：引擎对平行窗口是双重豁免——求值豁免
  （`isNeedUpdateConfig` 对嵌入记录返回 false）且**写点豁免**（services.jar
  MIUI 补丁：`inMultiWindowMode()` → `super.setOrientation(请求值)` 直通，
  运行期方向请求从未被任何机制中和）。实机（微信公众号文章页复现）定位出
  两条独立链路，分别桥接：

  - **E1a（读点门，惠及规则内嵌入应用）**：嵌入控制器
    （`com.android.server.wm.MiuiActivityEmbeddingController`，
    miui-embedding-window.jar 随包反编译复核）的旋转支持在 cetus 编译关闭：
    构造期 `mSupportGlobalRotation = MiuiEmbeddingWindowStub.IS_TABLET ? true
    : resources.getBoolean(0x11050022)`（即
    `android.miui:bool/config_embedded_activity_rotate`，cetus false），字段
    全 jar 仅 `shouldOverrideOrientation` 一处读，且为 false 时
    `overrideOrientation` 恒返回 -3（UNSET）。调用链：`ActivityRecord.
    getOrientation(int)`（MIUI 覆写，第 10 节 W2 所指 ~7206 处）每次求值、于
    `mOrientation` 之前调用
    `MiuiEmbeddingWindowServiceStub.get().overrideOrientation(this)`。
    E1a = 按方法名 hook `shouldOverrideOrientation`（同 S1/W5 避开参数类型
    耦合）：总开关关 / 相机 / 排除清单命中 → 强制 false（与 stock 字段 false
    逐位一致）；否则逐次置 true 放行——嵌入规则、可见性、`!isDisplayFolded()`
    （合盖外屏永不通过）等 stock 门控保持原语义。
    **实测边界（重要）**：动态嵌入规则 `isEmbeddingEnabledForPackage` 对
    **自适配平行窗口应用恒为 false**（微信实测：静态配置表
    `cmd miui_embedding_window dump-rule` 显示 `EmbeddedEnable->true`，
    但动态判定叠加 `isAppSelfAdaptEmbed` / `isAppNeedSystemAdaptation` /
    `mFullRules` / `mSizeCompatRules` 检查后恒 false——微信走自家 magic-window
    适配 + freeform 管理，不受系统嵌入规则管辖；开机即 false 且整个会话无
    翻转）。E1a 对这类应用从未生效；
  - **E1b（写点中和，覆盖自适配平行窗口）**：平行窗口此前"能横屏"的真相是
    **未指定方向的记录（manifest UNSPECIFIED）跟随自由的显示旋转**，与嵌入
    路径无关；而应用在平行窗口内运行期调 `setRequestedOrientation(PORTRAIT)`
    （微信文章页实锤：`mOriginScreenOrientation=UNSPECIFIED` 但
    `mOriginRequestOrientation=PORTRAIT`，经 `ActivityRecord.setOrientation`
    落盘 `mOrientation=1`）会把整个任务与显示钉死竖屏，物理重转才偶发恢复、
    锁定方向时粘死。E1b = hook `ActivityRecord.setOrientation(int,
    WindowContainer)`（legacy W1 的引擎模式孪生）：请求 ∉ {-1,-2,3}、总开关
    开、非相机、非排除、**平行窗口记录**（live `isEmbedded()` 或 magic-window
    窗口模式 101 "miui-magic-windows"；分屏/小窗/PIP 与全屏记录不命中——
    全屏记录归引擎自身写路径管）且非折叠、非 size-compat 时双写中和
    （`args[0]=-1` + `info.screenOrientation=-1`，引擎 case-1 等效），计入
    `ROT_REG`；总开关关闭/排除变更时经 `restoreStockOrientation` 重发原请求
    即时还原（钩子门控失效后自然放行）。**折叠门教训**：平行窗口窗格的合并
    配置 `smallestScreenWidthDp≈338dp`（窄竖栏），第 10 节 f 门的"per-record
    sw<600 视为折叠外屏"判定会把展开内屏上的窗格记录**误判为折叠**导致改写
    永不命中——E1b 的折叠门只查 ROM 全局折叠态
    （`mPolicy.isDisplayFolded()`，与第 10 节 f 门第一段同源），查询失败保守
    跳过，外屏保障不变。即时性与允许表不同：写点/读点逐次求值、无持久状态，
    总开关与排除清单变更即时生效。
  - 仅引擎模式注册（legacy W1–W5 的 g 门本就放行嵌入记录，再挂会双重接管）。
- **总开关语义**：关 → `isEnabled()` 恢复 false → 引擎休眠、全机原厂
  （允许表文件残留但引擎不读，无行为影响）。
- **已知限制（排除清单变更需重启才完美应用，机主标记待修）**：引擎在
  允许表命中时于**写点持久中和**记录（`info.screenOrientation` 与
  `mOrientation` 均写为 -1），允许表变更对存量记录不追溯，两个方向的
  即时性都不完整：
  1. 新装应用不在允许表 → 不跟随（需重启或任意排除变更触发重建）；
  2. 新排除的应用此前已被中和 → 存量 ActivityRecord 仍保持跟随（需重启
     或该记录重建）。
  修复方向：排除变更时按允许表 diff 对存量记录做**反向还原**（等价于
  第 10 节 W2 时代的 `restoreStockOrientation`：把 `info.screenOrientation`
  /`mOrientation` 写回原厂值，该实现仍在引擎模式的回退代码里可复用）；
  新装应用监听 PACKAGE_ADDED 增量同步。选择器自身的应用列表已实时刷新
  （onResume 重查 + 安装/替换/卸载广播）。外屏 LOCKED 对在折叠事件时
  应用，开机即折叠的场景需一次折叠循环生效。
- **侦察记录**：`MiuiStubUtil` 与引擎策略不在 services.jar（在
  miui-services.jar / miui-framework），`TaskSnapshot` 类在 system_server
  类加载器不可解析（W5 按名挂钩的原因）；引擎模式下 `systemContext()`
  必须用 lpp.classLoader（`actRecCls` 未解析，曾致 NPE 静默断链）。

## 12. 偶数页误补的根因与修正

**症状**（机主实测）：桌面偶发**一次多出两页空白页**（如 3 页内容变
5 页）；另曾观察到横屏三页模式下补出 1 页空白页、且原厂编辑模式清理
不掉；第三轮实测又发现普通模式拖图标到末页右缘**不弹新页**（编辑模式
里同样手势才可用），且编辑模式刚添加的空页随后被模块删掉。

**根因一（双补，"+2 页"的主因）**：补页入口 `ensureEvenPages` 单次调用
最多补 1 页（modulo=2，deficit≤1），"+2"必然是两次调用都读到了补页前
的旧页数。`insertNewScreen`/`removeScreen` 会触发原厂自身的
`reorderScreens` 回调，其 after 钩子**同步内联重入** `ensureEvenPages`，
此刻新页尚未计入 `getScreenCount` → 仍读到奇数 → 再补一页 → 一次事件
+2 页。是否重入取决于原厂内部路径，故"偶发"。

**根因二（过期方向，"+1 页"）**：三页豁免门原以
`isLandscape(appContext())` 判定"两页配对补齐还是三页免补"，而 app
上下文的 configuration 在折叠切换后不随折叠可靠更新（第 8 节已实证的
同一过期源；v1.6.4 修外屏误旋转时内屏判定 `onInnerScreen` 已迁移到
实时桌面 Activity，方向判定漏迁）。展开到横屏后残留竖屏方向：三页
豁免门失效 → 3 页被判作竖屏两页模式的奇数页 → 误补 1 页；同一误判还
令 15/16 的否决门在真三页横屏下错误生效，拦掉原厂对末尾空白页的清理。

**修正**（钩子 11/12）：

1. **重入锁**：`paddingLock` 覆盖增/删执行期，重入调用直接跳过并记
   日志（`even-pages: re-entrant call skipped`）；
2. **`reorderScreens` 触发改为 post 延迟**：不在原厂增/删页操作中途
   读页数（`onResume` 路径本就 post）；
3. **触发点补全（`onConfigurationChanged`）**：Launcher 自消化全部
   configChanges（旋转不重建、不触发 onResume），旋转/折叠切换后
   无人重估奇偶——"横屏变奇数页 → 转竖屏"后不补页、要等锁屏解锁。
   13b 路径复用 `postEvenPagesCheck` 补齐；
4. **奇数且末页有内容才补页；末尾空白页一律不动**。曾实现过"奇数 +
   末尾空白 → 删空白页"的自愈，实机日志证明它会误删两类合法页：原厂
   拖边建页先建好的预备页（图标尚在拖拽中、页面为空，日志
   `dropped trailing empty page, count 9 -> 8` 连续命中每次手势重试）、
   用户在编辑模式刚添加的空页。末尾空白保留时奇数页不处理——末位
   空白独立收尾最后一对，内容页配对不受影响；
5. **否决门（15/16）收窄到编辑模式**：实机日志（`vetoed removal …
   count=8`）证明普通模式拖边建页流程内部会先移除末尾页再重建，无
   编辑门限的否决把该流程拦死（拖边建页失效）。原设计要防的"编辑
   进出时原厂清页"仍被覆盖；若退出编辑的清页发生在编辑标志清除
   之后，空白页会被原厂清掉，由下一个触发点按第 4 条补回（可接受
   的抖动）；
6. **方向判定统一走 `modeCtx()`**（实时 Launcher Activity 优先、app
   上下文兜底）：钩子 4/5/6/7/8/11/12/14/15/16 共 9 处，消除根因二。
   `onInnerScreen` 内部与钩子 13（`param.thisObject` 即实时 Launcher）
   本就正确，不动。

**残留既定行为**：竖屏两页模式下内容页为奇数、且末页有内容时仍合法
补 1 页配对（钩子 11/12 的设计目标）。触发点覆盖 resume / 配置变化
（旋转、折叠切换）/ reorder 三类；奇数页若在免补模式（横屏三页）下
产生，转入两页模式的旋转当拍即触发补页检查（13b 复用路径）。该配对
空白页在横屏三页下短暂可见，进出一次编辑模式即由原厂清理（修正后
15/16 在三页横屏不再误拦）。折叠态外屏任何情况都不增删页
（`onInnerScreen` 门保障）。

## 13. 排除清单注入加固与安全扫描 Seal 语义

**1.9.0 退役说明**：配置迁入模块私有 prefs 后，`RootWriter` /
`shellQuote` / `settings put` 写入链已整体删除，本节加固的 su 注入面
不复存在；`ExcludeCodec` 保留为迁移值 / 备份恢复值的输入校验。以下
为 1.8.x 历史记录与 Seal 语义，新配置存储见第 15 节。1.9.0 复扫回执
`scan-2026-11-05T05-27-59.604Z-c054b961ad74`（Seal
`sha256:a6136f52…47e44c7`，deep）：**0 findings**，旧 finding 随
`RootWriter` 删除消失。

**根因**：`cetus_free_rot_exclude` 存于 `Settings.System`，该命名空间对
持用户授予 `WRITE_SETTINGS` 的任意第三方应用可写（无 per-key ACL）。
毒化存量值（含单引号的段）经排除选择器回读重组后，曾原样拼入常驻
su shell 的单引号包裹——单引号可闭合逃逸，构成条件性 root shell
命令注入链（触发需：恶意 app 已获 WRITE_SETTINGS + 用户在排除选择器
toggle + 模块自身未持该权限走 su 回退）。

**修复**（分支 `fix/exclude-shell-hardening`，commit `695a822`）：三层
白名单（`ExcludeCodec` parse/join 统一 `^[A-Za-z0-9_.]+$`、非法段丢弃
记 logcat；`SettingsWriter` String 写入前过滤）+ sink 侧
`RootWriter.shellQuote` POSIX 单引号转义（su 非交互参数形式在主流实现
仍过 `sh -c`，参数数组不能消除 shell 层，故在 sink 转义兜底）。回归：
`tools/run-tests.sh`（当轮 15 项，含严格引号文法不可闭合校验；1.9.0 起
shell 层用例已随 `RootWriter` 删除，parse/join 白名单用例保留）。

**Seal 语义**（Mimosa 扫描，重要）：扫描产物目录中的 **Seal 派生自
扫描元数据（含时间戳等实例信息），不是纯内容哈希——同一工作区重扫
Seal 必变**。不得用 Seal 比对"代码内容是否变化"；内容比对用 commit
hash。Seal 的用途仅是锁定单次扫描产物（findings / coverage / report）
的完整性与可复核性。本修复链的对应关系：旧基线
`scan-2026-11-04T13-06-48.048Z-2c5dbccc5a50`（Seal `3b73c756…f4e28d1`，
confirmed 条件注入）→ 修复后
`scan-2026-11-04T13-49-45.328Z-abf210abac64`（Seal `32700b0b…1232553f`，
同 finding 静态残留 FP，见 commit message disposition）。

**Advisory 处置**（误报关闭，2026-11-04）：deep 扫描
`scan-2026-11-04T14-48-57.059Z-e3b688e03b1d`（Seal
`a530b4c5…c24a5179`）报告唯一 finding——MEDIUM 跨文件污点
`MainHook.java:1258 → RootWriter.java:29`（"命令行参数 → Runtime/
ProcessBuilder 命令执行"），与上段修复后扫描的静态残留同族。人工复核
判定误报/不可利用：(1) 锚点行位于 E1 的 Javadoc 注释内，污点源头是把
Xposed 回调参数 `param.args[0]` 误认作进程 argv——注释行不构成运行时
数据流；(2) 唯一 sink 为 `RootWriter.write` 内固定 `exec("su")`，无变量
拼接（finding 报告的 sink 行 29 实为方法签名行，exec 在 34，行号仅近似
锚定）；(3) 全仓库仅 `SettingsWriter` 调用 `RootWriter.write`——int
重载 value 为 `int` 类型安全，String 重载经 `ExcludeCodec` 白名单 +
`shellQuote` 转义双层防线。该次扫描 completeness partial（动态派发调用
图缺口），不构成清白证明，但上述证据链不足以维持漏洞定性，不阻塞
发布。**复核触发条件**：新增绕过 `SettingsWriter` 直接调用
`RootWriter.write` 的路径；`shellQuote` / `ExcludeCodec` 被修改或移除；
发现其他动态派发真实调用链；`exec("su")` 后 stdin 写入逻辑变化且未
转义。再次扫描命中同类 advisory 时按本节三元组（注释锚点 / 固定 sink /
唯一入口）快速比对，回归防线见 `tools/run-tests.sh`。处置提交：
`8961413`、`303702d`。

**行为级背书**（真机 cetus / MIUI V816.0.8.0，Magisk root + LSPosed，
1.8.3/vc24 含加固 commit）：su 写入链已行为级验证——toggle 走 su 回退
（WRITE_SETTINGS app-op 保持 default，排除直写路径），Magisk 授权库
确认真实 su 授权，写入成功且存储值无碎引号，shellQuote 输出被真实
su shell 原样消化，测试无残留。原扫描盲区③（静态边界/无运行时验证）
就此闭环；盲区①（调用图 partial）②（依赖闭包未扫描）为静态侧固有
边界，维持已记录状态。HEAD 复扫回执
`scan-2026-11-04T15-42-44.425Z-e4f339cb4a2f`（Seal
`bef4203b…04b38d3be`）：同一 findingId 持久复现、无新 finding，sink
行号 29→36 随本次加固注释移动，印证锚点追踪 sink 本体。
1.8.4 发布前全量复扫回执 `scan-2026-11-05T04-20-12.541Z-cbfbb80715d8`
（Seal `sha256:16dea688…967865a`，deep）：0 high / 1 medium / 0 low /
0 business-logic，唯一 finding 的 findingId 与昨日 HEAD 扫描**逐位
相同**（`finding:5ed4ef6ce64178f2ca8f9225`，sink 仍为 RootWriter.java:36
固定 `exec("su")`），锚点随钩子 17-19 的插入漂移 1258→1421（仍在 E1
Javadoc 注释内）——findingId 为内容哈希派生身份，逐位相同即同源；
钩子 17-19 与 run-tests.sh 加固零新增 finding，本节判定继续有效
（Seal 跨次扫描不可比，见上文 Seal 语义）。

注：Mimosa 钩子不读取本节处置状态，同一 scan ID 的 advisory
会在后续提交/CI 中重复提示，属预期行为；以本节判定为准，勿重复处置。

## 14. 三列模式下小部件不显示的根因与运行时收缩（钩子 17）

**症状**（机主实测，v1.8.3）：`桌面列数=3`（一页三列，非横屏三页）时——
1. 原厂 4 列时代放置的 2x4 小部件（满页宽）不显示，应收缩成 2x3 维持显示；
2. 新添加的 2x3 小部件偶发不显示（MIUI/HyperOS 标准小部件；Android 原生
   小部件疑似同病未测）；
3. 日历"临近日程"等一批小部件**添加不了**（2026-11-05 真机反馈，
   钩子 17 之后的增量表现，根因三）；
4. 长按小部件 →「更换小部件」选择新部件**不生效**，仍是原部件
（2026-11-05 真机反馈，钩子 18 验收后的增量表现；4 列正常、<4 列均触发，
根因四）。

**不显示的机制**（4.39.12 jadx 行号）：绑定渲染链
`Workspace.addInScreen → CellLayout.addView`（CellLayout.java:1488）→
`updateCellOccupiedMarks`（CellLayout.java:3501-3510）按
`cellX..cellX+spanX` 对 `mOccupiedCell` 二维数组**无边界守卫直写**——
任何 `cellX+spanX > mHCells`（=模块列数）的行抛 `ArrayIndexOutOfBounds`，
异常沿 `Launcher.bindAppWidget` 的 `catch (RuntimeException)` 被静默吞掉
（"server side exception,ignore it"，Launcher.java:4674-4677）→ 条目
从未 add 进 CellLayout，**不渲染但数据库行完好**（全程无 markDeleted
参与）。两症状同根：

- 源一：存量 2x4（spanX=4）任何位置都越界（症状 1）；
- 源二：**4 列坐标里合法的 cellX≥1**——spanX=3 的"2x3"放在 cellX=1 在
  4 列网格完全合法，列数收窄到 3 后 1+3>3 同样越界（症状 2"偶发"的正解：
  取决于部件落点，cellX=0 显示、cellX≥1 消失）；
- 源三：原厂 `reCalculateSpan`（LauncherModel.java:1161-1168）跨网格
  迁移时**只重写 span 不挪 cellX**（span 4→3 后 cellX=1 仍越界）。

**添加不了的机制**（根因三，jadx 行号）：MIUI 小部件选择器的添加入口
`MIUIWidgetCompat.createItemInfo(Launcher, Bundle)`（MIUIWidgetCompat.java:255-300）
从 bundle 读取 `widget_span_x/y`——**按原厂网格标注**。appwidget 分支先经
`fromProviderInfo→initSpans`（像素映射，clamp 安全），但随后：

```java
if (DeviceConfig.getCellCountX() == DeviceConfig.getCellCountXMin(launcher)) {
    info.spanX = bundleSpanX;   // "最窄网格时选择器尺寸可信"——原厂 4==4，
}                               // 选择器本就按 4 列出尺寸，自洽
```

模块把 Min 钉到设置列数，`3==3` 恒真 → 4 列标注的 spanX=4 原样覆盖进
3 列网格 → `Workspace.acceptDrop`（Workspace.java:1320）`spanX > 列数`
直接拒绝放置——小部件拖到桌面即被弹回，**根本进不了放置/装载链**。
无 provider 的市场下载子分支（:281-289）更是无条件直取 bundle 原值。
maml 分支（:302 `new MaMlWidgetInfo(i3,i4)`）构造器内 `calculateSpan()`
（MaMlWidgetInfo.java:58-66）经 `getWidgetSpanX` 收敛，不受此门影响。
三个调用方——选择器拖出 `AssistantDragOverlay`:555/:603（含
prepare_add_widget 预备）与桌面替换 `MIUIWidgetReplaceHandler`:76——
全部汇过该单点。

**为什么原厂机制没救回来**：MIUI 按格数分库存桌面
（`DeviceConfig.calcGridSize`，DeviceConfig.java:417-433 →
`launcher_3x6.db`），首启缺库时 `tryToMigrateDefaultDatabase`
（DeviceConfig.java:2191-2212）从最接近的旧库（4x6）整库拷贝并置
`sNeedRecalculateWidgetSpan`——但该重算**只挂在 appwidget 装载路径**
（LauncherModel.java:781-783），MIUI 自家 gadget/maml 的装载
（`loadGadget` LauncherModel.java:840 / `loadMaMl` :909）不做任何 span
重算，且不修正 cellX。模块在 getter 层改列数绕过了整套迁移编排，越界行
原样进入渲染链。

**修复**（钩子 17，装载路径）：`ItemInfo.load(Context, Cursor)`
（ItemInfo.java:239-261）是全部五类桌面条目（shortcut / folder /
appwidget / gadget / maml）装载解析的**唯一汇流点**——各子类 `load` 均
super 调用（LauncherAppWidgetInfo:155-156、GadgetInfo :539-540、
FolderInfo :369-370、ShortcutInfo :529-530；maml 经 LauncherModel.java:909）。
after 钩子在解析完成后就地钳制内存 ItemInfo：

- 仅桌面容器（`container==-100`）：文件夹内容用各自网格且恒 1x1，不碰；
- `spanX→min(spanX,列数)`、`cellX→min(cellX,列数−spanX)`（负值抬 0）；
  纯数学见 `WidgetSpan`（`WidgetSpanTest` 21 项断言，`tools/run-tests.sh`
  全套 39 项）；
- **不写回数据库**（机主选定的运行时收缩语义）：切回原列数重启即恢复
  原始尺寸；原厂后续自行重排若持久化钳制值亦无冲突。

**修复**（钩子 18，添加路径）：`createItemInfo` after 钳制返回对象的
`spanX→min(spanX,列数)`（复用 `WidgetSpan.fitSpanX`），在 acceptDrop/
预览/放置任何消费方读到之前生效；maml 分支内部已收敛，钳制为无害幂等。
初版曾据静态分析断言"新加部件 span 全部经 `getWidgetSpanX` clamp
（`sCellCountX` 经 `loadCellsCountConfig` :1851-1863 被已钩住的 Min/Max
收敛到列数）、无需添加侧归一、钩子 18 取消"——该结论漏算了
`createItemInfo` 的 bundle 覆盖门（根因三），2026-11-05 真机反馈证伪，
钩子 18 恢复并落在此单点。落点侧仍无需归一：
`findNearestVacantAreaByCellPos` 始终在 mHCells 边界内搜索。

**残留行为**：被钳制的行若与其他条目同格会产生视觉重叠（优于不显示）；
经钩子 18 添加的小部件按钳制后的 span 持久化（3 列下即 2x3），后续切回
4 列保持 2x3 不再放大——与存量条目的可逆语义不同，属添加必然落库的
固有行为；原生 `targetCellWidth` 元数据分支
（LauncherAppWidgetProviderInfo.java:38-44）已被 maxSpanX≤列数的区间检查
挡回像素映射，无独立风险。

**更换不生效的机制**（根因四，2026-11-05 真机反馈补录）：钩子 17/18 验收
通过后（添加/移动/移除/切列数/补页全部正常，无小部件盖图标、多余图标
自动挤到末页——原厂 vacant 搜索的行为，与模块正交），唯一残留是长按
小部件 →「更换小部件」→ 选择新部件**不生效、桌面原地不动**。链条：
`replaceWidget`（MIUIWidgetReplaceHandler.java:58-100）在移除旧部件**之前**
先过 `canReplace`（:15-20）——要求旧部件**运行时** spanX/Y ≥ 新部件
bundle 的**标注** spanX/Y。收窄网格下旧部件显示宽为钳制值（3 列下 3），
bundle 仍按原厂 4 列标注（4）→ `3>=4` 恒假 → abort 于任何状态改动之前，
无任何用户可见反馈。4 列下 4≥4 恒过故不触发；列数 <4 均触发（2 列更甚）。
修复（钩子 19）：`canReplace` before 钩子，仅当标注 spanX 超出当前列数时
把标注值经 `WidgetSpan.fitSpanX` 折算到当前网格再比（X 折算、Y 原样——
行数不受模块影响）；标注本就 ≤ 列数或键缺失时完全不碰，原厂判定逐位
保留。通过后的落位复用钩子 18（`replaceWidget` 第 :76 行同样经
`createItemInfo` 建新部件，span 已钳制），cellX/cellY 拷贝自旧部件
（装载时已入界），无需额外处理。

**Mimosa 提示处置**（2026-11-05，本节一次性记录）：编辑
`tools/run-tests.sh` 时钩子对其 `mkdir`/`rm` 行报"命令注入"（锚点行号
随编辑漂移，首见 :15 后移至 :51——与 §13 sink 29→36 同现象）——涉及
变量（`OUT_DIR` 等）均为脚本内硬编码常量或开发者自设 env 覆盖，无任何
外部输入流入命令，且新增软链调用守卫后 `rm -rf` 只可能在仓库根内执行；
与 §13 已裁决的静态残留同族误报，不处置。

1.9.0 的配置存储迁移只改变列数/边距的读取来源，钩子 17–19 的钳制数学、
`WidgetSpan` 纯函数与"不写回数据库"语义不变。

## 15. 配置存储迁移（1.9.0）

**根因**：1.8.x 的 8 个配置键存于 `Settings.System`，该命名空间对持
用户授予 `WRITE_SETTINGS` 的任意第三方应用可写（无 per-key ACL）；模块
app 未获该权限时经 `SettingsWriter → RootWriter.exec("su")` 回退写入。
Mimosa 因此反复报告跨文件污点
`finding:5ed4ef6ce64178f2ca8f9225`（source 为配置读取、sink 为 su
shell；锚点随代码漂移，1.8.4 复扫落在 E1 Javadoc 注释内）。1.8.3 的
白名单 + `shellQuote` 只能让静态分析无法证明闭合，不能消除 source/sink
本身。

**目标架构**：

| 进程 | 角色 | 机制 | 生效 |
|---|---|---|---|
| 模块 app | 唯一写方 | `SettingsWriter → MixPrefs → SharedPreferences("mixunfold_prefs", MODE_PRIVATE)`，`commit()` 同步落盘 | 立即 |
| `com.miui.home` | 只读 | `HookPrefs`（classic `XSharedPreferences`）在 `loadCfg` 一次读取 | 重启桌面 |
| `com.android.systemui` | 只读 | `loadLockRotCfg` 一次读取 | 重启 SystemUI |
| `system_server` | 只读 | `ensureSysCfg` 首读；`freeRotOn()` 内 500ms 去抖 `reload()`；XSP 监听器可用时置 dirty 跳过等待 | ≤0.5s |
| 任意 hook 进程 | 禁止写 | `XSharedPreferences.edit()` 抛 `UnsupportedOperationException`；代码无 `Settings.System` 写路径 | — |

键名与默认值原样保留（`PrefsKeys`）：`cetus_home_columns`(3)、
`cetus_home_padding_side`(120)、`cetus_home_padding_side_land`(120)、
`cetus_home_land_pages`(3)、`cetus_home_pad_opt`(0)、
`cetus_home_lockscreen_rot`(1)、`cetus_free_rot`(1)、
`cetus_free_rot_exclude`("")。`PrefsKeys.LEGACY_KEYS` 是迁移与回落的
唯一键表，`PrefsMigrationTest` 逐字锁定。

**迁移判定**：`SharedPreferences.contains()` 是唯一判据——已存的 0/false
是有效值，不与默认值比较。app 每次启动补齐缺失键并写
`mixunfold_migrated_v1`；是否把旧 `Settings.System` 值作为来源由
`PackageInfo.firstInstallTime < lastUpdateTime` 决定：

| 场景 | 判定 | app 行为 | hook 行为 |
|---|---|---|---|
| 1.8.4 覆盖升级 | firstInstall < lastUpdate | 复制旧值，缺失键写默认；exclude 过 `ExcludeCodec`；写 marker | app 运行前缺 key 只读旧值；运行后 8 键齐全 |
| 全新安装 / 卸载重装 | firstInstall == lastUpdate | 忽略旧命名空间，写默认 + marker | 默认值（卸载即失效） |
| 升级后未打开 app | firstInstall < lastUpdate，prefs 空 | 未迁移 | 只读旧值，行为与 1.8.4 一致 |
| 清除应用数据（未卸载） | firstInstall < lastUpdate | 重新导入旧值 | 已知边界：如需彻底归零，卸载重装（见 FAQ） |

旧 `Settings.System` 键不删除（模块无写权限，且升级回落需要）；重装靠
安装时间判定忽略它们。多用户按 hook 进程 `uid / 100000` 拼
`/data/user[_de]/<user>/...`；用户 0 完整支持，第二空间仅在模块同样
安装并打开过时有效（待真机验证）。

**存储上下文（真机实测，LSPosed 2.2.1 / cetus HyperOS 1.0）**：首选的
classic 直读路线（`XSharedPreferences(File)` 读
`/data/data/<pkg>/shared_prefs`）**实测被 SELinux 拦截**——桥构造成功但
`contains()` 恒 false（`source=legacy/default` 而值正确），hook 进程对
其它 app 私有文件无读权。已切换 **LSPosed 新 XSharedPreferences（V3）**：
清单声明 `xposedsharedprefs` + `xposedsharedprefswatcher` 元数据，app 侧
尝试 `MODE_WORLD_READABLE`（模块 app 进程实测被拒 `SecurityException:
MODE_WORLD_READABLE no longer supported`，探测一次后缓存、回落
MODE_PRIVATE——**V3 由 lspd root 守护读取/转发，不依赖 DAC 世界可读位**，
回落无害），hook 侧用 `XSharedPreferences(pkg, name)` String 构造器由
LSPosed 解析可读路径。实测 launcher / SystemUI / system_server 三进程
`prefs bridge ready, readable=true`、`config loaded ... source=prefs`。
`MixPrefs.USE_DEVICE_PROTECTED` 仍默认 `false`（CE 路径实测可用）；
DE 路径保留常量开关未实测（direct boot 需求出现时再切换）。

**跨进程即时性（真机实测）**：watcher 通道**可用**——modules 日志
`prefs change listener registered`（system_server），清单
`xposedsharedprefswatcher` 元数据生效；总开关关/开真机切换，
`free-rot config reloaded: on=false` / `on=true` 均在切换后即时出现
（无需重启，多次连续切换逐一跟上、顺序正确）。写侧无逐毫秒时间戳，
未测得精确延迟值；通道语义为 watcher 置 dirty → 下一次 hook 调用立即
`reload()`（跳过 500ms 等待），watcher 不可用时退化为 500ms 轮询。
500ms 轮询兜底保留。

**安装通道坑（真机复现 + HEAD 对照）**：`adb install`（流式/增量）产生
的 APK 路径，lspd 守护开机扫描时 `ziparchive` 打开报 ENOENT（守护
verbose 日志），PackageParser 解析失败 → system_server 静默不注入；
launcher/SystemUI 走 zygote 注入不受影响。**HEAD（未改动 1.8.4 基线）
对照复现同样失败**，确认非本迁移回归、为环境问题。处置即 DESIGN 第 10
节已记录路径：`adb push` 到设备本地后 `pm install -r`（触发
PACKAGE_REPLACED 刷新）再重启——实测 `parse_failures=0`、三进程正常
注入。另：卸载重装后模块需在管理器重新启用 + 「系统框架」作用域需重新
确认（FAQ 已有）。

**安全边界**：删除 `RootWriter`、`shellQuote`、`settingShellLine` 与
`WRITE_SETTINGS` 声明；配置写入不再需要权限或 root。`ExcludeCodec`
保留（迁移值/备份恢复值仍按不可信输入过滤）。`MainActivity` 的
「重启系统桌面」保留原有 `Runtime.exec("su")` + 固定命令
（`am force-stop` + HOME）——这是全仓库唯一 exec，无任何配置/用户数据
流入；它是 1.8.4 实机验证过不会留下 AMS 脏状态的重启路径。是否改为
广播或移除按钮需机主另行决定（任务书"全仓库无 exec"理想与此处的取舍
见 CHANGELOG 1.9.0）。

**回退路线**：1.9.x 保留只读旧键回落；下个大版本删除回落与 `MixPrefs`
中的 legacy 读取。`USE_DEVICE_PROTECTED` 切换不需要改键名或迁移逻辑。

**Mimosa 回执**（2026-11-05，1.9.0 终态，deep）：
`scan-2026-11-05T08-12-48.840Z-17944a7abb92`（Seal
`sha256:50ac27a5…f3f74dd0ea`），**0 findings**；依赖风险 0
（completion=not_applicable）。真机验证后的终态复扫：1.8.4 基线唯一
finding `finding:5ed4ef6ce64178f2ca8f9225` 消失——`RootWriter` /
`shellQuote` 已删除，配置读取不再流向任何 su 汇点；V3 元数据 /
MODE_WORLD_READABLE 探测回落 / HookPrefs 监听器等新增面零 finding。
