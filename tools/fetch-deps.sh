#!/usr/bin/env bash
# MixUnfold — pinned dependency fetcher for the miuix (Compose) settings UI.
#
# 仓库保持无 Gradle：本脚本把 Kotlin 编译器、Compose 编译器插件、API 36
# android.jar 以及 miuix/Compose/androidx 的 AAR/jar 闭包按钉死的版本下载并
# 解包到 build/deps/（gitignored），供 build.sh 直接使用。全部产物已存在时
# 跳过；加 --force 可强制重新下载。
#
# 依赖闭包由 POM/Gradle 元数据离线解析后人工钉死（解析脚本思路见分支提交
# 历史）；升级 miuix 版本时需整表重新解析。每个坐标按顺序尝试
# 「-android 孪生 AAR → 根 AAR → 根 jar」：androidx KMP 工件的根件是空壳，
# 真实字节码在 -android 变体里。
set -euo pipefail
cd "$(dirname "$0")/.."

DEPS=build/deps
AAR_DIR="$DEPS/aar"
JAR_DIR="$DEPS/classes"
RES_DIR="$DEPS/res"
TOOL_DIR="$DEPS/tools"
mkdir -p "$AAR_DIR" "$JAR_DIR" "$RES_DIR" "$TOOL_DIR"

GOOGLE=https://dl.google.com/android/maven2
CENTRAL=https://repo1.maven.org/maven2
KOTLIN_VER=2.4.0
PLATFORM_ZIP=platform-36_r02.zip

force=0
[ "${1:-}" = "--force" ] && force=1

fetch() { # fetch <url> <dest>
    [ "$force" = 0 ] && [ -s "$2" ] && return 0
    echo "  ↓ ${2##*/}"
    local attempt code=0
    for attempt in 1 2 3 4; do
        # curl 退出码 22 = 真 HTTP 404（立即换候选）；其余（SSL/连接抖动）重试
        if curl -fsSL -o "$2" "$1"; then return 0; fi
        code=$?
        rm -f "$2"
        [ "$code" = 22 ] && return 1
        sleep 2
    done
    return 1
}

repo_of() {
    case "$1" in
        androidx.*) echo "$GOOGLE" ;;
            *)      echo "$CENTRAL" ;;
    esac
}

# 每行 group:artifact:version。升级 miuix/Compose 时整表重新解析。
DEPS_LIST=(
    top.yukonga.miuix.kmp:miuix-ui-android:0.9.2
    top.yukonga.miuix.kmp:miuix-core-android:0.9.2
    top.yukonga.miuix.kmp:miuix-squircle-android:0.9.2
    top.yukonga.miuix.kmp:miuix-shader-android:0.9.2
    top.yukonga.miuix.kmp:miuix-preference-android:0.9.2
    androidx.compose.runtime:runtime-android:1.11.2
    androidx.compose.runtime:runtime-annotation-android:1.11.2
    androidx.compose.runtime:runtime-saveable-android:1.11.2
    androidx.compose.runtime:runtime-retain-android:1.11.2
    androidx.compose.ui:ui-android:1.11.2
    androidx.compose.ui:ui-geometry-android:1.11.2
    androidx.compose.ui:ui-graphics-android:1.11.2
    androidx.compose.ui:ui-text-android:1.11.2
    androidx.compose.ui:ui-unit-android:1.11.2
    androidx.compose.ui:ui-util-android:1.11.2
    androidx.compose.animation:animation-android:1.11.2
    androidx.compose.animation:animation-core-android:1.11.2
    androidx.compose.foundation:foundation-android:1.11.2
    androidx.compose.foundation:foundation-layout-android:1.11.2
    androidx.navigationevent:navigationevent:1.1.1
    androidx.navigationevent:navigationevent-compose:1.1.1
    androidx.activity:activity:1.12.4
    androidx.activity:activity-compose:1.12.4
    androidx.annotation:annotation-jvm:1.9.1
    androidx.annotation:annotation-experimental:1.4.1
    androidx.arch.core:core-common:2.2.0
    androidx.arch.core:core-runtime:2.2.0
    androidx.collection:collection-jvm:1.5.0
    androidx.concurrent:concurrent-futures:1.1.0
    androidx.core:core:1.16.0
    androidx.core:core-ktx:1.16.0
    androidx.core:core-viewtree:1.0.0
    androidx.customview:customview-poolingcontainer:1.0.0
    androidx.emoji2:emoji2:1.4.0
    androidx.graphics:graphics-path:1.0.1
    androidx.lifecycle:lifecycle-common-jvm:2.9.4
    androidx.lifecycle:lifecycle-runtime-android:2.9.4
    androidx.lifecycle:lifecycle-runtime-compose-android:2.9.4
    androidx.lifecycle:lifecycle-viewmodel-android:2.9.4
    androidx.lifecycle:lifecycle-viewmodel-savedstate-android:2.9.4
    androidx.navigationevent:navigationevent:1.1.1
    androidx.navigationevent:navigationevent-compose:1.1.1
    androidx.profileinstaller:profileinstaller:1.4.0
    androidx.savedstate:savedstate:1.4.0
    androidx.savedstate:savedstate-compose:1.4.0
    androidx.startup:startup-runtime:1.1.1
    androidx.tracing:tracing:1.2.0
    androidx.versionedparcelable:versionedparcelable:1.1.1
    androidx.window:window:1.5.0
    com.materialkolor:material-color-utilities:4.1.1
    org.jetbrains:annotations:23.0.0
    org.jetbrains.kotlin:kotlin-stdlib:$KOTLIN_VER
    org.jetbrains.kotlinx:atomicfu-jvm:0.28.0
    org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0
    org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.9.0
)

echo "[deps] maven artifacts (${#DEPS_LIST[@]})"
for coord in "${DEPS_LIST[@]}"; do
    g="${coord%%:*}"
    v="${coord##*:}"
    a="${coord#*:}"; a="${a%:*}"
    base="$(repo_of "$g")/${g//.//}/$a/$v"
    marker="$JAR_DIR/$a-$v-classes.jar"
    alt="$JAR_DIR/$a-$v.jar"
    [ "$force" = 0 ] && { [ -s "$marker" ] || [ -s "$alt" ]; } && continue
    got=""
    # 候选顺序：-android 孪生 AAR（独立 Maven 目录）→ 根 AAR → 根 jar。
    # KMP 根件（aar/jar）常是无字节码空壳，逐个校验内容。
    dir_root="$(repo_of "$g")/${g//.//}/$a/$v"
    dir_twin="$(repo_of "$g")/${g//.//}/$a-android/$v"
    for cand in "$dir_twin/$a-android-$v.aar" "$dir_root/$a-$v.aar" "$dir_root/$a-$v.jar"; do
        dl="$AAR_DIR/${cand##*/}"
        fetch "$cand" "$dl" || continue
        if [ "${dl##*.}" = "aar" ]; then
            has_cls=$(unzip -l "$dl" | awk '{print $4}' | grep -cx 'classes.jar' || true)
            if [ "$has_cls" = "0" ]; then
                rm -f "$dl"
                continue
            fi
            unzip -oq "$dl" classes.jar -d "${dl%.aar}-x"
            mv "${dl%.aar}-x/classes.jar" "$marker"
            # AAR 资源（多数 compose 库没有，有则并入 aapt2）
            if unzip -l "$dl" | awk '{print $4}' | grep -q '^res/values'; then
                rm -rf "$RES_DIR/$a"
                unzip -oq "$dl" 'res/*' -d "${dl%.aar}-x"
                mkdir -p "$RES_DIR/$a"
                cp -R "${dl%.aar}-x/res/." "$RES_DIR/$a/"
            fi
        else
            # 根 jar 也可能是空壳（只有 META-INF）：无 .class 即拒收
            if [ "$(unzip -l "$dl" | grep -c '\.class$')" = "0" ]; then
                rm -f "$dl"
                continue
            fi
            cp "$dl" "$alt"
        fi
        got=1
        break
    done
    [ -n "$got" ] || { echo "error: cannot download $coord (all candidates failed)" >&2; exit 1; }
done

echo "[deps] kotlin compiler $KOTLIN_VER"
if [ "$force" = 1 ] || [ ! -x "$DEPS/kotlinc/bin/kotlinc" ]; then
    rm -rf "$DEPS/kotlinc"
    fetch "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VER/kotlin-compiler-$KOTLIN_VER.zip" \
        "$TOOL_DIR/kotlin-compiler-$KOTLIN_VER.zip"
    unzip -oq "$TOOL_DIR/kotlin-compiler-$KOTLIN_VER.zip" -d "$DEPS"
fi

echo "[deps] compose compiler plugin"
# CLI kotlinc 用非 embeddable 版（embeddable shadow 包是给
# kotlin-compiler-embeddable/Gradle 用的，CLI 加载会类冲突）
fetch "$CENTRAL/org/jetbrains/kotlin/kotlin-compose-compiler-plugin/$KOTLIN_VER/kotlin-compose-compiler-plugin-$KOTLIN_VER.jar" \
    "$TOOL_DIR/compose-compiler-plugin.jar"

echo "[deps] android-36 platform jar（Compose 1.11 需要 compileSdk 36）"
if [ "$force" = 1 ] || [ ! -f "$DEPS/android-36/android.jar" ]; then
    fetch "https://dl.google.com/android/repository/$PLATFORM_ZIP" "$TOOL_DIR/$PLATFORM_ZIP"
    rm -rf "$DEPS/android-36" "$DEPS/plat36-x"
    unzip -oq "$TOOL_DIR/$PLATFORM_ZIP" -d "$DEPS/plat36-x"
    inner="$(find "$DEPS/plat36-x" -maxdepth 2 -name android.jar | head -1)"
    [ -n "$inner" ] || { echo "error: 平台包中未找到 android.jar" >&2; exit 1; }
    mkdir -p "$DEPS/android-36"
    mv "$inner" "$DEPS/android-36/android.jar"
    rm -rf "$DEPS/plat36-x"
fi

n_cls=$(ls "$JAR_DIR" | wc -l | tr -d ' ')
n_res=$(ls "$RES_DIR" 2>/dev/null | wc -l | tr -d ' ')
echo "[deps] OK：$n_cls 个 class jar，$n_res 个含资源（build/deps/）"
