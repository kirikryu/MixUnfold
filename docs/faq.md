# FAQ

长问答与进阶排查。常见问题速查见 [README](../README.md#常见问题)。

## 如何校验 APK 完整性

仅从官方
[GitHub Releases](https://github.com/kirikryu/MixUnfold/releases) 下载，
并对照每个 Release 附件 `SHA256SUMS.txt` 核对（附件中 `#` 注释行为官方
证书 SHA-256）：

```bash
grep -v '^#' SHA256SUMS.txt | sha256sum -c -   # macOS: … | shasum -a 256 -c
```

### 证书 SHA-256 ≠ APK 文件 SHA-256

- **证书 SHA-256** 只能确认"这个 APK 用了哪张证书"。与官方指纹一致仅说明
  签名密钥相同，**不能单独证明 APK 由官方构建**——同一张证书可以签出
  内容任意的 APK。
- **APK 文件 SHA-256** 才对应"这个确切的文件"，可用于发现传输损坏或篡改；
  但其参考值必须来自**可信渠道**（官方 Release 附件 `SHA256SUMS.txt`），
  与 APK 同处分发的哈希没有防篡改意义。

## 覆盖安装（升级）的条件

Android 允许覆盖安装（保留数据升级）须**同时**满足：

1. 包名相同（`cetushome.rotationunlock`）
2. 签名证书相同
3. 新 APK 的 `versionCode` 不低于已安装版本
4. 用户允许安装未知来源应用（adb 安装还需 USB 调试授权）

第 2 条不满足时系统报"签名不一致"，必须先卸载旧版（桌面布局数据会丢）。
官方版与本地源码构建版签名不同，**不能互相覆盖安装**。

### 校验签名证书

```bash
apksigner verify --print-certs MixUnfold.apk
```

v1.6.2 的实际值（后续版本文件哈希不同，以各 Release 附件为准）：

```text
904da53054a8c3ee906bda1ff7ff90bc3fb55ce3ffc80f483b169e783d45eb5f  MixUnfold.apk
Signer #1 certificate SHA-256 digest: 234743a1e6dce8365b66e382e7c478fee80757b9d90d22f34697d89c26f34967
```

## 桌面偶发崩溃后自动重载

这是 MIUI 桌面**原生**的后台回收机制，与本模块无关：系统在内存压力下销毁
后台已久的桌面 Activity 时，MIUI 会故意杀掉整个进程并经 FallbackHome 重新
拉起，用户侧表现为"桌面崩溃重载"。伴随特征：整机 swap 高占用、load 飙升、
某前台应用失控占用 CPU。崩溃缓冲区无模块相关记录。

## adb 更新模块后功能整体失效

通过 adb 更新模块 APK 后，LSPosed 偶发不会向随后启动的作用域应用重新注入
（无任何模块日志，功能整体回到原版行为）。**整机重启一次**可恢复。

判别注入是否成功：

```bash
adb logcat -d | grep CetusHomeHook   # 出现 "init complete" 即注入成功
```

因此安装/更新后建议整机重启一次再验证功能。

## 锁屏横屏的验证与回退

- **验证**：内屏展开 + 锁屏 + 横放时

  ```bash
  adb shell "dumpsys window windows | grep -A3 'u0 NotificationShade}'" | grep or=
  ```

  应显示 `USER`（原版为 `PORTRAIT`）；折叠态外屏锁屏必须仍为 `PORTRAIT`。
- **回退**：关闭"锁屏允许横屏"并重启系统界面
  （`adb shell pkill -f com.android.systemui`），内外屏行为与原厂一致。
- **已知风险**：小米疑似因一代折叠内屏横屏锁屏 UI 未适配而特判 cetus，
  横屏锁屏可能出现布局异常（时钟/日期、壁纸裁切、上滑手势区、相机入口、
  下拉通知中心）。

## 折叠态外屏为什么不旋转

外屏保持竖屏锁定是**原版桌面的策略**而非系统限制（外屏上其它应用的旋转
不受影响）。本模块按"外屏正常、内屏自动旋转"显式维持该行为；若外屏出现
误旋转属于 bug，请提 Issue。技术根因与修正记录见
[DESIGN.md 第 8 节](DESIGN.md)。

## 桌面编辑模式缩略图显示异常

桌面编辑模式的缩略图视图未针对多列网格做适配，显示可能与实际桌面不一致，
属已知限制，不影响正常使用。

## 桌面不足 5 页时横屏三页末组不满

预期行为：三页模式不强制补页，页数少于翻页单位时末组可能不满，无法修改。

## 内屏自由旋转：播放器全屏 / 游戏锁横屏"失灵"

「内屏自由旋转」开启时，已参与旋转的应用在运行期发出的方向请求（播放器
点全屏、游戏内切横屏）会被吸收为"跟随系统旋转"——应用不横、你横。把
设备转回手持方向即可；想让某应用严格按自身请求定向，在模块设置页把它
勾进「排除应用」（即时生效）。未参与旋转（不在弱表中的）应用不受影响，
方向请求原样生效。

## 哪些应用永远不旋转

- **相机类**：`com.android.camera`、`com.android.cameraextensions`、
  `com.xiaomi.cameratools` 始终排除（成像方向依赖传感器，不可配置）。
- **分屏 / 小窗 / 尺寸兼容模式**中的应用不参与。
- 排除清单中的应用（自行勾选，随时可取消）。
- 嵌入 / 平行窗口应用同样跟随旋转：先前记录的"嵌入包解锁导致 MIUI 嵌入
  扩展崩溃"经机主更正为第三方模块（完美横屏应用计划）配置冲突所致，
  已撤销该排除；如复现崩溃请反馈。

## 系统框架作用域与注入确认

「内屏自由旋转」的钩子位于 system_server，要求 LSPosed 作用域勾选
**系统框架（android）**。重装/更新模块后若功能未生效：

1. LSPosed → 模块 → MixUnfold → 确认「系统框架」仍勾选（重装后偶发
   需要重新确认）；
2. 整机重启一次；
3. 确认注入：`adb logcat -d | grep CetusHomeHook` 应出现
   `attached to system_server` 与 `hook W1/W2/W3 ok`。

覆盖安装后 system_server 不注入但桌面正常、且日志出现
`Failure to parse package archive ... No such file or directory` 的，
重新安装一次模块（设置里允许 USB 安装，或 `su -c pm install -r`）再重启。

## 合盖后外屏强制竖屏

「内屏自由旋转」需要全局自动旋转保持开启，而 cetus 原厂没有生效的"外屏
锁竖屏"机制（相关代码全部被禁用的横屏引擎门控，实机确认）——原厂行为
下自由旋转类应用（DeepSeek、微信等）合盖后在外屏仍会转横。本模块按
"内屏自由、外屏锁定"实现：合盖时把主显示旋转钉死竖屏（logcat 出现
`W4: folded -> default display pinned ROTATION_0`），任何应用、任何配置
组合下外屏 `mRotation=0`；展开后立即恢复跟随。**例外**：显式横屏请求
（视频应用的全屏播放）放行，外屏全屏视频照常横屏。关闭总开关即完全
回到原厂行为（外屏跟随全局自动旋转设置）。

## 紧急禁用内屏自由旋转

- **先试软回退**：模块设置页关闭「内屏自由旋转」总开关（即时生效，
  全机恢复原厂方向行为）。
- **无法开机 / 界面异常**：LSPosed（安全模式或恢复后）关闭 MixUnfold
  模块对「系统框架」的作用域，或直接卸载模块
  （`su -c pm uninstall cetushome.rotationunlock`），重启即可——本功能
  不写任何系统文件，卸载即完全复原。
