# MixUnfold

小米 MIX FOLD 1（代号 `cetus`）折叠内屏桌面的 LSPosed 模块：解锁内屏展开后
桌面与锁屏的横竖旋转，统一桌面网格布局。**不修改任何系统文件**——桌面保持
小米平台签名，系统权限无损。

**适配**：MIX FOLD 1（cetus）· 系统桌面 4.39.12.x · HyperOS 1.0（Android 13）· LSPosed

## 支持范围

| 项目 | 要求 |
|---|---|
| 设备 | 小米 MIX FOLD 1（`cetus`），其它机型未验证 |
| 系统 | HyperOS 1.0（Android 13），系统桌面 `com.miui.home` 4.39.12.x |
| 框架 | LSPosed（需 root：Magisk / KernelSU） |
| 作用域 | 系统桌面（`com.miui.home`）+ 系统界面（`com.android.systemui`）+ 系统框架（`android`） |

系统桌面或 MIUI 大版本更新可能使模块失效，需等适配。

## 功能

- **桌面旋转解锁**：内屏展开后桌面跟随系统横竖旋转；折叠态外屏保持原版竖屏
- **锁屏旋转解锁**：内屏展开态的锁屏跟随系统自动旋转；折叠态外屏锁屏保持竖屏
- **内屏自由旋转**：内屏展开时所有应用跟随系统自动旋转（含原生锁定方向的
  应用），可按应用排除；相机类应用永远不参与；折叠态外屏任何应用都保持
  原版竖屏
- **统一网格**：桌面列数横竖屏一致（默认 3 列），避免同一份图标数据库在两种
  列数间切换导致的小组件遮挡
- **横屏页数可选（2/3 页）**：两页模式自动反向对齐，页缝两侧列距一致，
  外侧边距加大也不产生页间豁口
- **控制面板**：滑杆调参 + 一键重启桌面

## 安装与激活

1. 设备已 root（Magisk / KernelSU）并安装 LSPosed
2. 从 [GitHub Releases](https://github.com/kirikryu/MixUnfold/releases) 下载
   APK 并按 Release 附件 `SHA256SUMS.txt` 校验（详见
   [docs/faq.md](docs/faq.md#如何校验-apk-完整性)），然后安装：

   ```bash
   adb install -r MixUnfold.apk
   ```

3. LSPosed → 模块 → 启用 **MixUnfold** → 作用域勾选 **系统桌面**、
   **系统界面** 与 **系统框架**（三个都要勾；重装模块后系统框架偶发需要
   重新确认）
4. 重启桌面：模块内「重启系统桌面」按钮，或
   `adb shell am force-stop com.miui.home`
5. 确认生效：内屏展开后桌面横放跟随旋转；或执行
   `adb logcat -d | grep CetusHomeHook`，出现 `init complete` 即注入成功

> - 通过 adb 更新模块 APK 后，LSPosed 偶发不会向新启动的桌面进程重新注入
>   （功能整体回到原版行为），整机重启一次即可恢复。
> - 自行从源码构建的版本与官方版**签名不同，不能互相覆盖安装**，见
>   [CONTRIBUTING.md](CONTRIBUTING.md#签名与构建)。

## 使用与配置

打开桌面上的 **MixUnfold**：

| 开关/滑杆 | 默认 | 说明 |
|---|---|---|
| 桌面列数 | 3 | 滑杆 2–6，横竖屏统一 |
| 横屏同屏页数 | 3 | 下拉选择 2 页 / 3 页 |
| 屏幕外侧边距优化 | 关 | 开启后边距自动取固定值（竖 120 / 横 280 或 0 px），不可手调；关闭则保持小米原版边距 |
| 锁屏允许横屏 | 开 | 内屏展开态锁屏跟随旋转；关闭并重启系统界面即恢复原版 |
| 内屏自由旋转 | 开 | 总开关：内屏展开时所有应用跟随系统自动旋转（含原生锁定方向的应用），即时生效；合盖后外屏强制竖屏（视频全屏等显式横屏除外） |
| 排除应用 | 空 | 二级界面按应用勾选（带搜索）；清单内的应用维持原厂方向行为，即时生效 |

- **旋转开关与排除清单改动即时生效**：首次使用需点面板顶部按钮授予
  「修改系统设置」权限（一次性；root 授权作为回退通道）
- 桌面布局项（列数/页数/边距）改动后需点 **重启系统桌面** 生效（需 root）；
  重启后建议横竖屏切换几次，让布局优化完全应用
- 横屏锁屏可能出现布局异常（时钟/壁纸/手势区等，小米未适配 cetus 横屏锁屏
  UI）；出问题关闭「锁屏允许横屏」并重启系统界面即可回退

命令行等价（可选，无需打开应用）：

```bash
adb shell settings put system cetus_home_columns 3
adb shell settings put system cetus_home_pad_opt 1
adb shell settings put system cetus_home_land_pages 3
adb shell settings put system cetus_home_lockscreen_rot 1
adb shell am force-stop com.miui.home            # 桌面设置改动后重启桌面
adb shell pkill -f com.android.systemui          # 锁屏设置改动后重启系统界面

# 内屏自由旋转（即时生效，无需重启）
adb shell settings put system cetus_free_rot 1
adb shell settings put system cetus_free_rot_exclude com.foo;com.bar
```

## 常见问题

**模块没生效？** 按顺序检查：

1. LSPosed 中模块已启用，作用域 **系统桌面**、**系统界面** 与 **系统框架** 都已勾选
2. 已重启桌面（改过锁屏开关的，已重启系统界面或整机）
3. `adb logcat -d | grep CetusHomeHook` 有 `init complete`；没有 → 整机重启一次
4. 系统桌面版本是否为 4.39.12.x（其它版本未验证）

**桌面偶发崩溃后自动重载？** 这是 MIUI 桌面自身的后台回收机制（内存压力下
杀进程重载），与本模块无关，详见
[docs/faq.md](docs/faq.md#桌面偶发崩溃后自动重载)。

**覆盖安装报"签名不一致"？** 官方版与本地构建版签名不同，必须卸载重装
（会丢桌面布局数据）；覆盖安装的完整条件见
[docs/faq.md](docs/faq.md#覆盖安装升级的条件)。

**升级后失效？** 先过一遍上面的生效检查清单；系统桌面/MIUI 大版本更新可能
改变目标类结构，需等模块适配更新。

**如何抓日志、如何反馈？** 见下一节。

## 日志与反馈

- 日志 TAG：`CetusHomeHook`

  ```bash
  adb logcat -d | grep CetusHomeHook
  ```

- 提 Issue：[github.com/kirikryu/MixUnfold/issues](https://github.com/kirikryu/MixUnfold/issues)，
  或酷安联系 [@桐间洛露](http://www.coolapk.com/u/2802773)
- Issue 请附：模块版本、LSPosed 版本、系统版本（HyperOS/MIUI 完整版本号）、
  系统桌面版本号、完整 logcat 输出、复现步骤

## 风险与免责

本模块通过运行时挂钩修改系统桌面与系统界面的行为，可能随系统更新失效，
也可能导致桌面或锁屏显示异常。是否使用请自行评估，作者不对使用本模块造成
的任何问题负责。

## 下载与致谢

- 下载：[GitHub Releases](https://github.com/kirikryu/MixUnfold/releases)，
  完整性校验随每个 Release 附 `SHA256SUMS.txt`
- License：MIT，见 [LICENSE](LICENSE)
- 开发者文档：[设计文档](docs/DESIGN.md)（原理、Hook 点与推导）·
  [贡献指南](CONTRIBUTING.md)（开发环境、构建、签名）·
  [变更日志](CHANGELOG.md) · [FAQ](docs/faq.md)
