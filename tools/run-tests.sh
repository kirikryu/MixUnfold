#!/usr/bin/env bash
# Minimal JVM regression runner for the pure-logic tests (app/tests) — no
# Gradle, no device. Compiles the touched classes plus every *Test.java
# against android.jar and runs them on a plain JVM; an android.util.Log
# shim (app/tests/shim) shadows android.jar's stub at runtime so
# drop-logging is captured and asserted (ExcludeHardeningTest).
#
# Toolchain notes:
#  - javac runs with --release 8: plain -source/-target 8 would happily
#    link Java 9+ JDK APIs (List.of, String.strip ...) that pass here and
#    die with NoSuchMethodError on Android's Java 8 runtime — a false
#    green. --release compiles against the documented JDK 8 API set while
#    android.jar still supplies the android.* classes (verified:
#    List.of is rejected, the real sources compile).
#  - every *Test.java under app/tests is discovered and run even when an
#    earlier one fails; the script exits nonzero after the last one. A
#    hand-maintained test list would let a forgotten test pass forever —
#    the quietest false green there is.
set -euo pipefail

# Resolve to the physical repo root and refuse to run from elsewhere. A
# symlinked invocation resolves dirname "$0" to the link's directory, and
# the rm -rf below would then wipe the wrong build tree; the marker check
# makes that fail loudly instead of destructively.
ROOT="$(cd -P "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
[ -f "$ROOT/tools/run-tests.sh" ] || {
    echo "error: repo root not found at $ROOT (invoked through a symlink?)" >&2
    exit 1
}

JDK_BIN="${JDK_BIN:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin}"
AJ="${AJ:-/opt/homebrew/share/android-commandlinetools/platforms/android-35/android.jar}"

[ -x "$JDK_BIN/javac" ] || {
    echo "error: javac not found at $JDK_BIN/javac" >&2
    echo "       set JDK_BIN to the bin/ dir of a JDK 17+ (build.sh default:" >&2
    echo "       /opt/homebrew/opt/openjdk@17/...)" >&2
    exit 1
}
[ -f "$AJ" ] || {
    echo "error: android.jar not found at $AJ" >&2
    echo "       set AJ to an android platform jar (build.sh default:" >&2
    echo "       /opt/homebrew/share/android-commandlinetools/platforms/android-35/android.jar)" >&2
    exit 1
}

# jvm-tests, not tests: if some other tool ever squats on build/tests,
# this rm -rf must not wipe it
OUT_DIR=build/jvm-tests
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/shim" "$OUT_DIR/main" "$OUT_DIR/t"

"$JDK_BIN/javac" --release 8 -encoding UTF-8 -d "$OUT_DIR/shim" \
    app/tests/shim/android/util/Log.java

"$JDK_BIN/javac" --release 8 -encoding UTF-8 -cp "$AJ" -d "$OUT_DIR/main" \
    app/src/cetushome/hook/ExcludeCodec.java \
    app/src/cetushome/hook/RootWriter.java \
    app/src/cetushome/hook/SettingsWriter.java \
    app/src/cetushome/hook/WidgetSpan.java

# bash 3.2 (stock macOS /bin/bash) has no mapfile — append in a loop
TEST_SOURCES=()
while IFS= read -r f; do
    TEST_SOURCES+=("$f")
done < <(find app/tests -name '*Test.java' | sort)
[ "${#TEST_SOURCES[@]}" -gt 0 ] || {
    echo "error: no *Test.java found under app/tests" >&2
    exit 1
}

"$JDK_BIN/javac" --release 8 -encoding UTF-8 \
    -cp "$OUT_DIR/shim:$OUT_DIR/main:$AJ" -d "$OUT_DIR/t" \
    "${TEST_SOURCES[@]}"

# shim dir first: android.util.Log resolves to the recorder, not android.jar
TESTS_CP="$OUT_DIR/shim:$OUT_DIR/main:$OUT_DIR/t"
FAILED=0
for src in "${TEST_SOURCES[@]}"; do
    cls="cetushome.hook.$(basename "$src" .java)"
    echo "--- $cls"
    "$JDK_BIN/java" -cp "$TESTS_CP" "$cls" || FAILED=1
done
exit "$FAILED"
