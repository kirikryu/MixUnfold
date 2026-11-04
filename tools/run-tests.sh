#!/usr/bin/env bash
# Minimal JVM regression runner for the exclude-list hardening tests
# (app/tests) — no Gradle, no device. Compiles the touched classes plus the
# tests against android.jar and runs them on a plain JVM; an android.util.Log
# shim (app/tests/shim) shadows android.jar's stub at runtime so drop-logging
# is captured and asserted.
set -euo pipefail
cd "$(dirname "$0")/.."

JDK_BIN="${JDK_BIN:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin}"
AJ="${AJ:-/opt/homebrew/share/android-commandlinetools/platforms/android-35/android.jar}"

OUT_DIR=build/tests
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/shim" "$OUT_DIR/main" "$OUT_DIR/t"

"$JDK_BIN/javac" -source 8 -target 8 -Xlint:-options -d "$OUT_DIR/shim" \
    app/tests/shim/android/util/Log.java

"$JDK_BIN/javac" -source 8 -target 8 -Xlint:-options -cp "$AJ" -d "$OUT_DIR/main" \
    app/src/cetushome/hook/ExcludeCodec.java \
    app/src/cetushome/hook/RootWriter.java \
    app/src/cetushome/hook/SettingsWriter.java

"$JDK_BIN/javac" -source 8 -target 8 -Xlint:-options \
    -cp "$OUT_DIR/shim:$OUT_DIR/main:$AJ" -d "$OUT_DIR/t" \
    app/tests/cetushome/hook/ExcludeHardeningTest.java

# shim dir first: android.util.Log resolves to the recorder, not android.jar
exec "$JDK_BIN/java" -cp "$OUT_DIR/shim:$OUT_DIR/main:$OUT_DIR/t" \
    cetushome.hook.ExcludeHardeningTest
