#!/usr/bin/env bash
# MixUnfold — reproducible build (no Gradle / Android Studio needed)
# Requires: JDK 17, Android build-tools (aapt2/d8/zipalign/apksigner), android.jar
set -euo pipefail
cd "$(dirname "$0")"

JDK_BIN="${JDK_BIN:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin}"
BT="${BT:-/opt/homebrew/share/android-commandlinetools/build-tools/34.0.0}"
AJ="${AJ:-/opt/homebrew/share/android-commandlinetools/platforms/android-35/android.jar}"
KS="${KS:-keystore/debug.jks}"
KS_ALIAS="${KS_ALIAS:-cetushome}"
KS_PASS="${KS_PASS:-***REMOVED***}"
OUT="${OUT:-releases/MixUnfold.apk}"
API=27

if [ ! -f "$KS" ]; then
    echo "[0/6] generating local debug keystore ($KS)"
    mkdir -p "$(dirname "$KS")"
    "$JDK_BIN/keytool" -genkeypair -keystore "$KS" -alias "$KS_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=MixUnfold (local debug)"
fi

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

echo "[5/6] apksigner"
mkdir -p "$(dirname "$OUT")"
"$BT/apksigner" sign \
    --ks "$KS" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --out "$OUT" build/aligned.apk

echo "[6/6] verify"
"$BT/apksigner" verify "$OUT"

echo "OK -> $OUT"
echo "install: adb install -r $OUT"
