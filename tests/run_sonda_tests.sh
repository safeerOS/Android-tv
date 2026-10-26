#!/usr/bin/env bash
# Sonda toka proti pravemu krajevnemu strezniku HTTP (naloga 114) - brez naprave, imenik je nadomestek (tests/stubs/Imenik.kt).
#   KOTLINC=/pot/do/kotlinc tests/run_sonda_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
OKHTTP_LIBS="$PROJECT_DIR/libs/okhttp-4.12.0.jar:$PROJECT_DIR/libs/okio-jvm-3.6.0.jar"   # SondaVeriga gre prek OkHttp (J2)
"$KOTLINC" -J-Xmx1g -cp "$OKHTTP_LIBS" \
    "$SRC/si/safeer/tv/os/SondaToka.kt" \
    "$SRC/si/safeer/tv/os/SondaVeriga.kt" \
    "$SRC/si/safeer/tv/os/WebtorPravila.kt" \
    "$SRC/si/safeer/tv/os/SondaHlsPravila.kt" \
    "$TEST_DIR/stubs/SpletniVir.kt" \
    "$SRC/si/safeer/tv/os/OsPravila.kt" \
    "$SRC/si/safeer/tv/os/NedelujocePravila.kt" \
    "$SRC/si/safeer/tv/os/JezikiVsebine.kt" \
    "$SRC/si/safeer/tv/os/SkrivnostiPravila.kt" \
    "$TEST_DIR/stubs/Imenik.kt" \
    "$TEST_DIR/SondaTokaTest.kt" \
    -include-runtime -d "$OUT/sonda.jar"
java -cp "$OUT/sonda.jar:$OKHTTP_LIBS" si.safeer.tv.os.SondaTokaTestKt
