#!/usr/bin/env bash
# Pravila Safeer OS (mere ikon, vrstica Nadaljuj, kartica Zaslon) v navadnem JVM, brez Androida.
#   KOTLINC=/pot/do/kotlinc tests/run_os_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$HOME/Namizje/Neimenovana mapa/streamN-TV2/android_tv/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

"$KOTLINC" -J-Xmx1g \
    "$SRC/si/safeer/tv/os/OsPravila.kt" \
    "$TEST_DIR/OsPravilaTest.kt" \
    -include-runtime -d "$OUT/os.jar"

java -cp "$OUT/os.jar" si.safeer.tv.os.OsPravilaTestKt
