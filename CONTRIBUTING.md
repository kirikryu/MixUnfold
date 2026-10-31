# 贡献指南

面向想自行构建、修改或提 PR 的开发者。普通用户请看
[README](README.md)；实现原理见 [docs/DESIGN.md](docs/DESIGN.md)。

## 反馈问题

- 提 Issue：[github.com/kirikryu/MixUnfold/issues](https://github.com/kirikryu/MixUnfold/issues)，
  或酷安联系 [@桐间洛露](http://www.coolapk.com/u/2802773)
- 请附：模块版本、LSPosed 版本、系统版本（HyperOS/MIUI 完整版本号）、
  系统桌面版本号、`adb logcat -d | grep CetusHomeHook` 完整输出、复现步骤

## 开发环境

- macOS（构建脚本默认按 Homebrew 路径查找依赖）
- JDK 17
- Android build-tools 34.0.0（`aapt2` / `d8` / `zipalign` / `apksigner`）
- android-35 的 `android.jar`

路径不标准时用环境变量覆盖：

```bash
JDK_BIN=/path/to/jdk17/bin \
BT=/path/to/build-tools/34.0.0 \
AJ=/path/to/platforms/android-35/android.jar \
./build.sh
```

## 构建步骤

```bash
./build.sh              # debug 签名，产物默认输出 releases/MixUnfold.apk
adb install -r releases/MixUnfold.apk
```

流程：`javac`（源码 + `app/stubs/` 的 Xposed stub）→ `d8` → `aapt2 link`
→ `zipalign` → `apksigner`。无 Gradle，全部在 `build.sh` 中。

### 签名与构建

- **本地构建默认使用 Android 标准 debug 签名**（`~/.android/debug.keystore`，
  缺失时自动生成；可用 `DEBUG_STORE_FILE` 等变量切换为自定义 keystore）。
  **debug 构建不得当作官方版分发**；与官方版签名不同，不能互相覆盖安装。
- **本地 release 构建**（维护者自用）：`./build.sh --release`。密码优先从
  macOS Keychain 读取：

  ```bash
  security add-generic-password -U -a "$USER" -s "MixUnfold-release" -w
  ```

  也可导出环境变量 `RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD /
  RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD`，或复制
  [signing.properties.example](signing.properties.example) 为仓库根目录的
  `signing.properties` 填入真实值（已被 .gitignore 排除，**禁止提交**）。
  配置缺失或字段为空时 `./build.sh --release` 会失败并提示，不会用 debug
  签名冒充发布版。
- **官方发布版**由维护者的私有 release keystore 签发，密钥只存在于维护者
  本地与 CI 的 GitHub Secrets，仓库及克隆中不包含。

### 校验命令

```bash
# 查看 APK 使用的签名证书
apksigner verify --print-certs app-release.apk

# APK 文件哈希
sha256sum app-release.apk            # macOS: shasum -a 256 app-release.apk

# 查看 keystore 中某张证书的信息与指纹
keytool -list -v -keystore release.jks -alias <alias>

# 用 Release 附件 SHA256SUMS.txt 校验（# 注释行为官方证书 SHA-256）
grep -v '^#' SHA256SUMS.txt | sha256sum -c -   # macOS: … | shasum -a 256 -c
```

证书 SHA-256 与 APK 文件 SHA-256 的区别、覆盖安装条件详见
[docs/faq.md](docs/faq.md)。

## CI

[.github/workflows/build.yml](.github/workflows/build.yml)：

- push / PR：gitleaks 全历史密钥扫描 + 标准 debug 签名构建
- `v*` tag 推送：release 构建（从 Secrets 解码 keystore 签名，构建后删除）
  并自动发布 Release，接入 production 环境

## 提 PR

1. Fork 后建分支；改动前建议先开 Issue 讨论
2. 提交前安装并启用 pre-commit 钩子（gitleaks 扫描暂存区，命中即阻止提交）：

   ```bash
   brew install gitleaks pre-commit
   pre-commit install
   ```

3. 保持文档同步：改钩子点需更新 [docs/DESIGN.md](docs/DESIGN.md) 的钩子清单；
   改用户可见行为需更新 [README.md](README.md) 与 [CHANGELOG.md](CHANGELOG.md)
4. 禁止提交任何密钥材料：`signing.properties`、`*.jks`、明文密码
