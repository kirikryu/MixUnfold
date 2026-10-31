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
