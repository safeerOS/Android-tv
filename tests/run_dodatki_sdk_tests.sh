#!/usr/bin/env bash
# SDK dodatkov (naloga 125): primer dodatka kot lazni dodatek na krajevnih vratih in orodje za preverjanje proti njemu.
#   KOTLINC=/pot/do/kotlinc tests/run_dodatki_sdk_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}"   # orodja: SAFEER_TOOLS_DIR ali .tools (lahko povezava) v projektu
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" -J-Xmx1g \
    "$SRC/si/safeer/tv/cast/JsonLahki.kt" \
    "$SRC/si/safeer/tv/os/DodatkiProtokol.kt" \
    "$SRC/si/safeer/tv/os/OsPravila.kt" \
    "$SRC/si/safeer/tv/os/NedelujocePravila.kt" \
    "$SRC/si/safeer/tv/os/JezikiVsebine.kt" \
    "$PROJECT_DIR/sdk/primer/PrimerDodatka.kt" \
    "$PROJECT_DIR/sdk/orodja/PreveriDodatek.kt" \
    "$TEST_DIR/DodatkiSdkTest.kt" \
    -include-runtime -d "$OUT/sdk.jar"
java -cp "$OUT/sdk.jar" si.safeer.tv.sdk.DodatkiSdkTestKt
