#!/usr/bin/env bash
# Preizkus DRM pravil in orodij (ChromeOS UA, enoten seznam izvorov, EME shim skripta) v navadnem JVM.
#   KOTLINC=/pot/do/kotlinc tests/run_drm_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$HOME/Namizje/Neimenovana mapa/streamN-TV2/android_tv/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

"$KOTLINC" \
    "$TEST_DIR/stubs/Context.kt" \
    "$TEST_DIR/stubs/Uri.kt" \
    "$TEST_DIR/stubs/R.kt" \
    "$TEST_DIR/stubs/Log.kt" \
    "$TEST_DIR/stubs/JSONObject.kt" \
    "$TEST_DIR/stubs/DrmStubs.kt" \
    "$TEST_DIR/stubs/WebkitStubs.kt" \
    "$TEST_DIR/stubs/WebViewCompatStubs.kt" \
    "$TEST_DIR/stubs/PackageInfoStubs.kt" \
    "$TEST_DIR/stubs/ActivityStubs.kt" \
    "$TEST_DIR/stubs/OsBuildStubs.kt" \
    "$TEST_DIR/stubs/AndroidRStubs.kt" \
    "$SRC/si/safeer/tv/DrmPravila.kt" \
    "$TEST_DIR/DrmPravilaTest.kt" \
    -include-runtime -d "$OUT/drm.jar"

java -cp "$OUT/drm.jar" si.safeer.tv.DrmPravilaTestKt
