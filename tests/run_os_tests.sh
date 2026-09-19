#!/usr/bin/env bash
# Pravila Safeer OS (mere ikon, vrstica Nadaljuj, kartica Zaslon) in varno dekodiranje ikon
# v navadnem JVM, brez Androida (BitmapFactory je nadomestek v tests/stubs).
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
    "$TEST_DIR/stubs/BitmapFactory.kt" \
    "$SRC/si/safeer/tv/os/OsPravila.kt" \
    "$SRC/si/safeer/tv/os/VarnaSlika.kt" \
    "$SRC/si/safeer/tv/tv/PredajaStrani.kt" \
    "$TEST_DIR/OsPravilaTest.kt" \
    "$TEST_DIR/VarnaSlikaTest.kt" \
    "$TEST_DIR/PredajaStraniTest.kt" \
    -include-runtime -d "$OUT/os.jar"

java -cp "$OUT/os.jar" si.safeer.tv.os.OsPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.VarnaSlikaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.PredajaStraniTestKt
