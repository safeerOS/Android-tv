#!/usr/bin/env bash
# Sonda toka proti pravemu krajevnemu strezniku HTTP (naloga 114) - brez naprave, imenik je nadomestek (tests/stubs/Imenik.kt).
#   KOTLINC=/pot/do/kotlinc tests/run_sonda_tests.sh
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
    "$SRC/si/safeer/tv/os/SondaToka.kt" \
    "$SRC/si/safeer/tv/os/OsPravila.kt" \
    "$SRC/si/safeer/tv/os/NedelujocePravila.kt" \
    "$SRC/si/safeer/tv/os/JezikiVsebine.kt" \
    "$TEST_DIR/stubs/Imenik.kt" \
    "$TEST_DIR/SondaTokaTest.kt" \
    -include-runtime -d "$OUT/sonda.jar"
java -cp "$OUT/sonda.jar" si.safeer.tv.os.SondaTokaTestKt
