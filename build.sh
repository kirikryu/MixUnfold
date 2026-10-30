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
BT="${BT:-/opt/homebrew/share/android-commandlinetools/build-tools/34.0.0}"
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
        echo "[0/6] generating standard debug keystore ($KS)"
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
    echo "[0/6] release signing: $KS_ALIAS <- $(basename "$KS")（来源: ${src}）"
}

if [ "$MODE" = release ]; then
    setup_release_signing
else
    [ -f "$ROOT/signing.properties" ] && \
        echo "note: debug 构建；忽略 signing.properties（release 构建用 --release）"
    setup_debug_signing
    echo "[0/6] debug signing: $KS_ALIAS <- $KS"
fi

# --- compile / package -------------------------------------------------------

echo "[1/6] javac"
rm -rf build && mkdir -p build/assets build/classes
# shellcheck disable=SC2046
"$JDK_BIN/javac" -source 8 -target 8 -Xlint:-options -cp "$AJ" -d build/classes \
    $(find app/src -name '*.java') \
    $(find app/stubs -name '*.java')

echo "[2/6] d8"
# shellcheck disable=SC2046
"$BT/d8" --release --min-api "$API" --output build \
    $(find build/classes/cetushome -name '*.class')

echo "[3/6] package"
cp app/assets/xposed_init build/assets/xposed_init
(cd build \
    && "$BT/aapt2" link -o base.apk -I "$AJ" \
        --manifest ../app/AndroidManifest.xml \
        --min-sdk-version "$API" --target-sdk-version 30 \
    && zip -q base.apk classes.dex \
    && zip -q base.apk assets/xposed_init)

echo "[4/6] zipalign"
"$BT/zipalign" -f -p 4 build/base.apk build/aligned.apk

echo "[5/6] apksigner ($MODE)"
# OUT 所在目录不随仓库分发（releases/ 被 gitignore），签名前确保存在
mkdir -p "$(dirname "$OUT")"
export MIXUNFOLD_KS_PASS="$KS_PASS" MIXUNFOLD_KEY_PASS="$KEY_PASS"
"$BT/apksigner" sign \
    --ks "$KS" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "env:MIXUNFOLD_KS_PASS" --key-pass "env:MIXUNFOLD_KEY_PASS" \
    --out "$OUT" build/aligned.apk
unset MIXUNFOLD_KS_PASS MIXUNFOLD_KEY_PASS

echo "[6/6] verify"
"$BT/apksigner" verify --print-certs "$OUT"

echo "OK -> $OUT ($MODE 签名)"
echo "install: adb install -r $OUT"
