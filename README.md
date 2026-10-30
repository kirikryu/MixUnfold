# MixUnfold

一个面向小米 MIX FOLD 1（代号 `cetus`）系统桌面的 LSPosed 模块。

解锁折叠内屏桌面的横竖旋转；桌面网格列数全形态统一，横竖屏外侧边距
独立可调；两页反向对齐，跨页列距与页内列距完全一致。**不修改任何
系统文件**——桌面保持小米平台签名，全部系统权限无损。

适配：MIX FOLD 1 · MIUI 桌面 4.39.12.x · Android 11 · LSPosed

## 功能

- **旋转解锁**：移除 `DeviceConfig.updateRotate` 对 cetus 的硬编码排除，
  展开内屏后桌面跟随系统旋转
- **统一网格 + 独立边距**：桌面列数横竖屏统一（默认 3 列），避免同一份
  图标数据库在两种列数间切换导致的小组件遮挡；横竖屏外侧边距独立可调
- **横屏三页模式**（可选）：横屏同屏页数可设 2 或 3；三页模式下横屏
  列数自动跟随竖屏设置，数据库全形态一致
- **两页反向对齐**：偶数页网格贴左、奇数页贴右，页缝两侧列距连续，
  外侧边距可任意加大而不产生页间豁口
- **偶数页策略**：页面数自动补足到翻页单位的整数倍，翻页永远整组落地
- **控制面板**：滑杆调参（root 写入系统设置）+ 一键干净重启桌面
- **性能**：设置值每进程读取一次缓存，热路径钩子零额外查询
- **零系统侵入**：仅运行时挂钩；折叠态外屏保持原版行为

## 为什么用 LSPosed

好用。

## 安装

1. 设备已 root（Magisk / KernelSU）并安装 LSPosed
2. `./build.sh` 生成 `releases/MixUnfold.apk`，或直接使用 Release 产物
3. `adb install -r releases/MixUnfold.apk`
4. LSPosed → 模块 → 启用 MixUnfold → 作用域勾选 **系统桌面**
5. 重启桌面（模块内按钮或 `adb shell am force-stop com.miui.home`）

> 注意：通过 adb 更新模块 APK 后，LSPosed 偶发不会向新启动的桌面进程
> 重新注入（表现为功能整体回到原版行为）。此时整机重启一次即可恢复；
> 可通过 `adb logcat | grep CetusHomeHook` 出现 `init complete` 确认注入成功。

## 使用

打开桌面上的 **MixUnfold**：

| 滑杆/选项 | 默认 | 说明 |
|---|---|---|
| 桌面列数 | 3 | 滑杆，横竖屏统一（2–6） |
| 横屏同屏页数 | 3 | 下拉选择：2 页 / 3 页；=3 时列数跟随桌面列数 |
| 屏幕外侧边距优化 | 关 | 开启后进入约束方案（见下），边距不可手输 |

**外侧边距约束方案**（勾选“屏幕外侧边距优化”后生效）：

- 竖屏外侧边距：**120px 固定**
- 横屏外侧边距：同屏 2 页 → **280px**；同屏 3 页 → **0px**（三页铺满、页间无缝）
- 关闭优化时：竖屏 120px · 横屏 120px（默认）

- **应用设置**：root 写入系统设置（首次弹出 Magisk 授权）
- **重启系统桌面**：等价于在应用程序信息里强制退出后按一下 Home 键，走系统原生路径重启
- **使用建议**：重启桌面后，横竖屏切换几次，让页宽与对齐优化完全应用

命令行等价（无需打开应用）：

```bash
adb shell settings put system cetus_home_columns 3
adb shell settings put system cetus_home_pad_opt 1
adb shell settings put system cetus_home_padding_side_land 120
adb shell settings put system cetus_home_land_pages 3
adb shell am force-stop com.miui.home
```

## 签名与分发

请仅从 [GitHub Releases](https://github.com/kirikryu/MixUnfold/releases)
获取官方 APK。官方版由**私有**签名密钥签发——密钥只存在于维护者本地与 CI
的 GitHub Secrets，仓库与任何克隆中都不包含它。

- **证书指纹 = 官方身份凭证**：`apksigner verify --print-certs` 对比下方
  指纹，可确认 APK 由官方密钥签发
- **APK 文件 SHA-256 = 完整性校验**：`shasum -a 256 MixUnfold.apk` 与
  Release 说明中的哈希比对。两者是两回事——证书指纹证"谁签的"，
  文件哈希证"是不是这个文件"
- 源码自行构建的产物为本地 debug 签名，与官方版**签名不同、不能互相覆盖
  安装**，换装需卸载重装。仓库不提供面向用户的构建指引，build.sh 仅供
  CI 与开发者使用

```text
Signer #1 certificate SHA-256: 234743a1e6dce8365b66e382e7c478fee80757b9d90d22f34697d89c26f34967
```

发版由 GitHub Actions 在 `v*` tag 推送时自动构建并发布
（[.github/workflows/build.yml](.github/workflows/build.yml)）。

## 工作原理

桌面以"页面"为固定单元：外屏 1 页，展开竖持 2 页同屏（每页宽 = 屏宽/2，
由 `CellScreen.createCellScreen` 决定；横屏三页模式下为屏宽/3）。模块在
运行时改变三个输入：

1. **方向开关**：`DeviceConfig.updateRotate` 原本硬编码排除 cetus，
   钩子重算 `sRotatable = isInFoldDeviceLargeScreen(context)`
2. **网格几何**：列数（`DeviceConfig.getCellCountX`，实时）与工作宽
   （`setWorkspaceCellPadding` → `sWorkspaceCellPaddingSide = B/2`）按
   当前激活的 DeviceProfile 读取；`列宽 = (页宽 − B) / 列数`
3. **页面对齐**：`CellLayout` 仅有左内边距，网格自左起笔——偶数页
   `paddingLeft = B`、奇数页 `paddingLeft = 0`，两页在页缝处无缝相接

`跨页列距 − 页内列距 ≡ 2 × padding`（可由上述几何直接推出），反向对齐
正是利用这一点：对称边距必然产生页间豁口，反向对齐则把豁口转移到
屏幕两端的可控边距上。完整推导与钩子清单见 [docs/DESIGN.md](docs/DESIGN.md)。

## 已知边界

- 折叠态外屏由系统锁定竖屏，属物理/策略限制
- 桌面编辑模式的缩略图视图未针对多列网格做适配
- 页数少于 5 页时选三页模式，末组可能不满——预期行为，无法修改（三页模式不强制补页）
- MIUI 大版本更新可能改变目标类的结构，钩子点需重新核对

## License

MIT，见 [LICENSE](LICENSE)。
