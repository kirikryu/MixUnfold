# 设计文档

目标设备：Xiaomi MIX FOLD 1（代号 `cetus`），MIUI 系统桌面 `com.miui.home`
4.39.12.x（Android 11）。文中行号/字段以该版本反编译结果为准。

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

全部挂在 `com.miui.home` 进程内（经典 Xposed API）：

| # | 目标 | 方式 | 效果 |
|---|---|---|---|
| 1 | `DeviceConfig.updateRotate` | after | `sRotatable = isInFoldDeviceLargeScreen(ctx)`，去除 cetus 排除 |
| 2 | `LauncherCellCountCompatDevice.shouldUseDeviceValue` | replace→true | 绕过密度/主题门槛，保证设备值生效 |
| 3 | 基类 `getCellCountXDef/Max/Min` + Fold/Cetus 子类 `…FromDevice` | replace→统一列数 | 桌面列数（设置键 `cetus_home_columns`） |
| 4 | `DeviceConfig.setWorkspaceCellPadding` | after | 展开态 `sWorkspaceCellPaddingSide = B/2`（B 为外侧边距，竖/横独立键） |
| 5 | `CellLayout.onScreenOrientationChanged/onScreenSizeChanged` | after | 偶数页 `paddingLeft = B`、奇数页 `= 0`（反向对齐，模数随横屏页数）；仅展开态生效 |
| 6/7 | `CellScreen.createCellScreen/onScreenSizeChanged/onScreenOrientationChanged` | after | 页宽刷新：竖持 `屏宽/2`、横持三页模式 `屏宽/3`（原生仅在创建时赋值一次） |
| 8 | `DeviceConfig.calcGridSize` | after | 三页模式下工作宽跟随 `屏宽/3`，并触发 `calculateCellSize` 重算 |
| 9 | `DeviceConfig.getCellCountX` | before→实时列数 | 原生列数进程启动后缓存且旋转不重读；设置值进程内缓存一次，热路径零额外查询 |
| 10 | `Application.onCreate` | after | 注册进程内重启广播接收器 |
| 11/12 | `Launcher.onResume` / `Workspace.reorderScreens` | after | 偶数页策略：页数不足翻页单位时末尾补空白页（编辑模式跳过） |

另有 `Application.onCreate` after 中注册的进程内广播接收器
（`cetushome.rotationunlock.RESTART`），供控制面板触发桌面重启。

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
