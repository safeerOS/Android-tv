#!/usr/bin/env bash
# Preizkus UrlSanitizer (cistilec sledilnih parametrov) v navadnem JVM.
#   KOTLINC=/pot/do/kotlinc tests/run_urlsanitizer_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$HOME/Namizje/Neimenovana mapa/streamN-TV2/android_tv/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

"$KOTLINC" -J-Xmx2g "$SRC/si/safeer/tv/UrlSanitizer.kt" "$TEST_DIR/UrlSanitizerTest.kt" \
    -include-runtime -d "$OUT/urlsanitizer.jar"

java -cp "$OUT/urlsanitizer.jar" si.safeer.tv.UrlSanitizerTestKt
