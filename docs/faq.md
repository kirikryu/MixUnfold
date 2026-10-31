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
