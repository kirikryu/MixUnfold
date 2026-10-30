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
2. `./build.sh` 本地构建（debug 签名），或直接使用官方 Release 产物
   （两者签名不同，不能互相覆盖安装，见下方[签名与校验](#签名与校验)）
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

## 签名与校验

### 官方版与本地构建版的差异

- **官方发布版**（[GitHub Releases](https://github.com/kirikryu/MixUnfold/releases)）
  由维护者的**私有 release keystore** 签发。密钥只存在于维护者本地与 CI
  的 GitHub Secrets，仓库及任何克隆中都不包含它；CI 在 `v*` tag 构建时把
  keystore 从 Secrets 解码到临时目录签名，构建后删除。
- **本地源码构建**：`./build.sh` 默认使用 Android 标准 debug 签名
  （`~/.android/debug.keystore`，缺失时自动生成；可用 `DEBUG_STORE_FILE`
  等变量切换为自定义 keystore）。**本地构建版与官方版签名不同，不能互相
  覆盖安装**，换装需卸载重装；debug 构建不得当作官方版分发。
- **本地 release 构建**（维护者自用）：密码优先从 macOS Keychain 读取
  （`security add-generic-password -U -a "$USER" -s "MixUnfold-release" -w`，
  仓库不保存任何明文密码文件），也可导出环境变量
  `RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS /
  RELEASE_KEY_PASSWORD`，或复制
  [signing.properties.example](signing.properties.example) 为仓库根目录
  的 `signing.properties` 填入真实值（已被 .gitignore 排除，**禁止提交**）。
  配置缺失或字段为空时 `./build.sh --release` 会失败并提示，不会用 debug
  签名冒充发布版。

### 覆盖安装（升级）条件

Android 允许覆盖安装（保留数据升级）须**同时**满足：

1. 包名相同（`cetushome.rotationunlock`）
2. 签名证书相同
3. 新 APK 的 `versionCode` 不低于已安装版本
4. 用户允许安装未知来源应用（adb 安装还需 USB 调试授权）

第 2 条不满足时系统报“签名不一致”，必须先卸载旧版（数据丢失）。
Play App Signing、系统平台签名等由商店/系统托管的场景规则另计——本模块
面向已 root 设备的 adb 侧载分发，不经过 Play 渠道。

### 校验命令

```bash
# 查看 APK 使用的签名证书
apksigner verify --print-certs app-release.apk

# APK 文件哈希
sha256sum app-release.apk            # macOS: shasum -a 256 app-release.apk

# 查看 keystore 中某张证书的信息与指纹
keytool -list -v -keystore release.jks -alias <alias>
```

官方版证书指纹（随每个 Release 附于附件 `SHA256SUMS.txt`）：

```text
Signer #1 certificate SHA-256: 234743a1e6dce8365b66e382e7c478fee80757b9d90d22f34697d89c26f34967
```

### 证书 SHA-256 ≠ APK 文件 SHA-256

- **证书 SHA-256** 只能确认“这个 APK 用了哪张证书”。与上方指纹一致仅
  说明签名密钥相同，**不能单独证明 APK 由官方构建**——同一张证书可以
  签出内容任意的 APK。
- **APK 文件 SHA-256** 才对应“这个确切的文件”，可用于发现传输损坏或
  篡改；但其参考值必须来自**可信渠道**（官方 Release 附件
  `SHA256SUMS.txt`、维护者另行公布的渠道），与 APK 同处分发的哈希没有
  防篡改意义。

请仅从官方 Releases 下载 APK，并对照 Release 附件 `SHA256SUMS.txt`
核对（附件中 `#` 注释行为官方证书 SHA-256，可与上方指纹对照）：

```bash
grep -v '^#' SHA256SUMS.txt | sha256sum -c -   # macOS: … | shasum -a 256 -c
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
