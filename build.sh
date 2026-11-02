#!/usr/bin/env bash
# MixUnfold — reproducible build (no Gradle / Android Studio needed)
# Requires: JDK 17, Android build-tools (aapt2/d8/zipalign/apksigner), android.jar
#
# Usage:
#   ./build.sh            # 本地 debug 构建：标准 debug 签名（缺省，可安装测试）
#   ./build.sh --release  # release 构建：私有 keystore 签名（官方发布用）
#
# release 签名配置来源（优先级从高到低）：
#   1. macOS Keychain：service=MixUnfold-release，account=$USER
#      （security add-generic-password -U -a "$USER" -s "MixUnfold-release" -w）
#   2. 环境变量 RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD /
#      RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD（CI 用，密钥不落盘）
#   3. 仓库根目录 signing.properties（参考 signing.properties.example，
#      已被 .gitignore 排除，禁止提交）
# 配置缺失时 release 构建失败并给出提示；debug 构建不受影响。
# 密码经 apksigner 的 env: 通道传入，不出现在命令行参数（ps 不可见）。
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$PWD"

MODE=debug
for arg in "$@"; do
    case "$arg" in
        --release) MODE=release ;;
        --debug)   MODE=debug ;;
        -h|--help)
            sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "unknown option: $arg (see $0 --help)" >&2; exit 2 ;;
    esac
done
[ "${RELEASE_BUILD:-0}" = "1" ] && MODE=release

JDK_BIN="${JDK_BIN:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin}"
# build-tools 36 起的 d8 才支持 miuix/Compose 1.11 的 Java 21 字节码（major 65）
BT="${BT:-/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0}"
AJ="${AJ:-/opt/homebrew/share/android-commandlinetools/platforms/android-35/android.jar}"
OUT="${OUT:-releases/MixUnfold.apk}"
API=27

KEYCHAIN_SERVICE="${KEYCHAIN_SERVICE:-MixUnfold-release}"
KEYCHAIN_ACCOUNT="${KEYCHAIN_ACCOUNT:-$USER}"

trim() {
    local s="$1"
    s="${s#"${s%%[![:space:]]*}"}"
    s="${s%"${s##*[![:space:]]}"}"
    printf '%s' "$s"
}

# --- signing -----------------------------------------------------------------

KS="" KS_ALIAS="" KS_PASS="" KEY_PASS=""

read_keychain_password() {
    command -v security >/dev/null 2>&1 || return 0
    security find-generic-password -a "$KEYCHAIN_ACCOUNT" \
        -s "$KEYCHAIN_SERVICE" -w 2>/dev/null || true
}

setup_debug_signing() {
    # 标准 debug 签名：~/.android/debug.keystore（与 Android Studio/Gradle
    # 的 debug 构建同一套约定，密码 android 是公开的行业惯例，非机密）。
    # 可用 DEBUG_STORE_FILE / DEBUG_STORE_PASS / DEBUG_KEY_ALIAS /
    # DEBUG_KEY_PASS 显式切换为本地自定义 keystore。
    KS="${DEBUG_STORE_FILE:-$HOME/.android/debug.keystore}"
    KS_ALIAS="${DEBUG_KEY_ALIAS:-androiddebugkey}"
    KS_PASS="${DEBUG_STORE_PASS:-android}"
    KEY_PASS="${DEBUG_KEY_PASS:-$KS_PASS}"
    if [ ! -f "$KS" ]; then
        if [ -n "${DEBUG_STORE_FILE:-}" ]; then
            echo "error: DEBUG_STORE_FILE 不存在: $KS" >&2
            exit 1
        fi
        echo "[0/7] generating standard debug keystore ($KS)"
        mkdir -p "$(dirname "$KS")"
        "$JDK_BIN/keytool" -genkeypair -keystore "$KS" -alias "$KS_ALIAS" \
            -keyalg RSA -keysize 2048 -validity 10950 \
            -storepass "$KS_PASS" -keypass "$KEY_PASS" \
            -dname "CN=Android Debug,O=Android,C=US"
    fi
}

setup_release_signing() {
    local prop="$ROOT/signing.properties"
    local sf="" sp="" ka="" kp="" src=""
    if [ -f "$prop" ]; then
        local line key val
        while IFS= read -r line || [ -n "$line" ]; do
            line="${line%$'\r'}"
            case "$line" in ''|\#*) continue ;; esac
            key="$(trim "${line%%=*}")"
            val="$(trim "${line#*=}")"
            case "$key" in
                storeFile)     sf="$val" ;;
                storePassword) sp="$val" ;;
                keyAlias)      ka="$val" ;;
                keyPassword)   kp="$val" ;;
            esac
        done < "$prop"
    fi

    # storeFile/keyAlias 不是机密（keystore 文件本身不入库，别名公开写在
    # CI workflow 中），提供仓库约定默认值；密码仍必须来自 Keychain/env/文件。
    KS="${RELEASE_STORE_FILE:-$sf}"
    [ -z "$KS" ] && KS="keystore/release.jks"
    KS_ALIAS="${RELEASE_KEY_ALIAS:-$ka}"
    [ -z "$KS_ALIAS" ] && KS_ALIAS="mixunfold"

    # store 密码优先级：Keychain > 环境变量 > signing.properties
    KS_PASS="$(read_keychain_password)"
    if [ -n "$KS_PASS" ]; then
        src="keychain($KEYCHAIN_SERVICE)"
    else
        KS_PASS="${RELEASE_STORE_PASSWORD:-}"
        if [ -n "$KS_PASS" ]; then
            src="environment"
        elif [ -n "$sp" ]; then
            KS_PASS="$sp"
            src="signing.properties"
        fi
    fi

    KEY_PASS="${RELEASE_KEY_PASSWORD:-}"
    [ -z "$KEY_PASS" ] && KEY_PASS="$kp"
    [ -z "$KEY_PASS" ] && KEY_PASS="$KS_PASS"

    if [ -z "$KS" ] || [ -z "$KS_PASS" ] || [ -z "$KS_ALIAS" ]; then
        cat >&2 <<'EOF'
error: release 构建需要签名配置，但未找到或字段为空。
按优先级检查以下任一来源：
  1. macOS Keychain：
       security add-generic-password -U -a "$USER" -s "MixUnfold-release" -w
  2. 环境变量 RELEASE_STORE_FILE / RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS
     （可选 RELEASE_KEY_PASSWORD，缺省与 store 密码相同）
  3. 仓库根目录 signing.properties（复制 signing.properties.example 填写，
     该文件已被 .gitignore 排除，禁止提交）
请配置签名或使用 debug 构建：./build.sh
EOF
        exit 1
    fi
    case "$KS" in
        /*) ;;
        *)  KS="$ROOT/$KS" ;;
    esac
    if [ ! -f "$KS" ]; then
        echo "error: keystore 文件不存在: $KS（检查 storeFile / RELEASE_STORE_FILE）" >&2
        exit 1
    fi
    echo "[0/7] release signing: $KS_ALIAS <- $(basename "$KS")（来源: ${src}）"
}

if [ "$MODE" = release ]; then
    setup_release_signing
else
    [ -f "$ROOT/signing.properties" ] && \
        echo "note: debug 构建；忽略 signing.properties（release 构建用 --release）"
    setup_debug_signing
    echo "[0/7] debug signing: $KS_ALIAS <- $KS"
fi

# --- compile / package -------------------------------------------------------

# miuix (Compose) 设置页需要 Kotlin/Compose 工具链与 AAR 闭包，全部钉版本
# 由 tools/fetch-deps.sh 下载到 build/deps/（首次构建约 100MB，之后离线）。
DEPS=build/deps
KOTLINC="$DEPS/kotlinc/bin/kotlinc"
AJ36="$DEPS/android-36/android.jar"
PLUGIN="$DEPS/tools/compose-compiler-plugin.jar"
if [ ! -x "$KOTLINC" ] || [ ! -f "$AJ36" ] || [ ! -f "$PLUGIN" ]; then
    ./tools/fetch-deps.sh
fi

echo "[1/7] kotlinc + javac"
rm -rf build/classes build/kotlin_classes build/assets build/res-*.zip
rm -f build/classes*.dex build/base.apk build/aligned.apk build/d8-inputs.txt
mkdir -p build/assets build/classes build/kotlin_classes

# Kotlin（设置页）：Java 源一并传入仅供解析（kotlinc 不编译它们）；
# classpath = 依赖闭包的 class jar + API 36 android.jar（Compose 1.11 要求）
DEP_JARS="$(find "$DEPS/classes" -name '*.jar' | sort | tr '\n' ':')"
# shellcheck disable=SC2046
"$KOTLINC" -jvm-target 1.8 \
    -Xplugin="$PLUGIN" \
    -cp "$DEP_JARS$AJ36" \
    -d build/kotlin_classes \
    $(find app/src -name '*.kt') \
    $(find app/src -name '*.java') \
    $(find app/stubs -name '*.java')

# Java（hook + AppPicker）：约定不变（-source/-target 8），classpath 加
# Kotlin 输出以支持少量 Java→Kotlin 引用
# shellcheck disable=SC2046
"$JDK_BIN/javac" -source 8 -target 8 -Xlint:-options \
    -cp "$AJ:build/kotlin_classes" -d build/classes \
    $(find app/src -name '*.java') \
    $(find app/stubs -name '*.java')

echo "[2/7] resources + library R"
cp app/assets/xposed_init build/assets/xposed_init
# 自身 + 依赖 AAR 的资源：aapt2 compile 成 flat zip 后并入 link
"$BT/aapt2" compile --dir app/res -o build/res-app.zip
RES_ARGS=("-R" "$PWD/build/res-app.zip")
# androidx AAR 不携带 R 类（AGP 在应用构建期按最终资源 ID 重建）：这里对每个
# 带资源的 AAR 读其 manifest package，用 --extra-packages 生成对应 R.java，
# 编译后一并 dex，否则库代码运行期 NoClassDefFoundError。
EXTRA_ARGS=()
seen_pkgs=" "
for rd in "$DEPS"/res/*/; do
    [ -d "$rd" ] || continue
    name="$(basename "$rd")"
    "$BT/aapt2" compile --dir "$rd" -o "build/res-$name.zip"
    RES_ARGS+=("-R" "$PWD/build/res-$name.zip")
    aar="$(ls "$DEPS/aar/$name"-*.aar 2>/dev/null | head -1)"
    [ -n "$aar" ] || { echo "warn: $name 找不到对应 AAR，跳过库 R 生成" >&2; continue; }
    # androidx AAR 的 manifest 是纯文本 XML（部分新版是 proto，本闭包内均为文本）
    pkg="$(unzip -p "$aar" AndroidManifest.xml \
        | grep -m1 -oE 'package="[^"]+"' | cut -d'"' -f2)"
    [ -n "$pkg" ] || { echo "warn: $name manifest 无 package，跳过库 R 生成" >&2; continue; }
    case "$seen_pkgs" in
        *" $pkg "*) ;;
        *)  seen_pkgs="$seen_pkgs$pkg "
            EXTRA_ARGS+=("--extra-packages" "$pkg") ;;
    esac
done
rm -rf build/r-gen build/r-classes
# link 只产出资源与 R.java；dex 在下一步生成后回填进 base.apk
(cd build \
    && "$BT/aapt2" link -o base.apk -I "$ROOT/$DEPS/android-36/android.jar" \
        --manifest ../app/AndroidManifest.xml \
        --min-sdk-version "$API" --target-sdk-version 30 \
        --auto-add-overlay --java r-gen "${EXTRA_ARGS[@]}" \
        "${RES_ARGS[@]}")
# 编译各库 R（引用 android.jar 常量即可），并入 dex
# shellcheck disable=SC2046
"$JDK_BIN/javac" -source 8 -target 8 -Xlint:-options -cp "$AJ" \
    -d build/r-classes $(find build/r-gen -name '*.java')
"$JDK_BIN/jar" --create --file build/app-r-classes.jar -C build/r-classes .

echo "[3/7] d8"
# build-tools 34 的 d8 不收目录/输入清单：先把应用类目录打成 jar，
# 再与全部依赖 class jar 一起 dex；--lib 用 API 36 jar（desugar 上下文）。
"$JDK_BIN/jar" --create --file build/app-java-classes.jar -C build/classes .
"$JDK_BIN/jar" --create --file build/app-kt-classes.jar -C build/kotlin_classes .
# shellcheck disable=SC2046
"$BT/d8" --release --min-api "$API" --lib "$AJ36" --output build \
    build/app-java-classes.jar build/app-kt-classes.jar build/app-r-classes.jar \
    $(find "$DEPS/classes" -name '*.jar')

echo "[4/7] package"
(cd build \
    && zip -q base.apk classes*.dex \
    && zip -q base.apk assets/xposed_init)

echo "[5/7] zipalign"
"$BT/zipalign" -f -p 4 build/base.apk build/aligned.apk

echo "[6/7] apksigner ($MODE)"
# OUT 所在目录不随仓库分发（releases/ 被 gitignore），签名前确保存在
mkdir -p "$(dirname "$OUT")"
export MIXUNFOLD_KS_PASS="$KS_PASS" MIXUNFOLD_KEY_PASS="$KEY_PASS"
"$BT/apksigner" sign \
    --ks "$KS" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "env:MIXUNFOLD_KS_PASS" --key-pass "env:MIXUNFOLD_KEY_PASS" \
    --out "$OUT" build/aligned.apk
unset MIXUNFOLD_KS_PASS MIXUNFOLD_KEY_PASS

echo "[7/7] verify"
"$BT/apksigner" verify --print-certs "$OUT"

echo "OK -> $OUT ($MODE 签名)"
echo "install: adb install -r $OUT"
