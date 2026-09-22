#!/usr/bin/env bash
# Preizkus SmartOmnibox (naslov ali iskanje) v navadnem JVM.
#   KOTLINC=/pot/do/kotlinc tests/run_omnibox_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$HOME/Namizje/Neimenovana mapa/streamN-TV2/android_tv/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"
ANDROID_JAR="$(ls "$PROJECT_DIR"/.android-sdk/platforms/android-3*/android.jar | head -1)"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

"$KOTLINC" -J-Xmx2g -cp "$ANDROID_JAR" \
    "$SRC/si/safeer/tv/SmartOmnibox.kt" \
    "$TEST_DIR/SmartOmniboxTest.kt" \
    -include-runtime -d "$OUT/omnibox.jar"

java -cp "$OUT/omnibox.jar:$ANDROID_JAR" si.safeer.tv.SmartOmniboxTestKt
