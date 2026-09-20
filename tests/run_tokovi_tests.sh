#!/usr/bin/env bash
# Preizkus tokov Safeer Huba (deljenje zaslona, prenos datotek) v navadnem JVM.
#   KOTLINC=/pot/do/kotlinc tests/run_tokovi_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$HOME/Namizje/Neimenovana mapa/streamN-TV2/android_tv/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

"$KOTLINC" -J-Xmx2g \
    "$TEST_DIR/stubs/Log.kt" \
    "$SRC/si/safeer/tv/cast/HubStreznik.kt" \
    "$SRC/si/safeer/tv/cast/JsonLahki.kt" \
    "$SRC/si/safeer/tv/cast/HubTokovi.kt" \
    "$SRC/si/safeer/tv/cast/Spake2.kt" \
    "$SRC/si/safeer/tv/cast/HubUsmerjevalnik.kt" \
    "$SRC/si/safeer/tv/cast/RegisterNaprav.kt" \
    "$SRC/si/safeer/tv/cast/KrogZaupanja.kt" \
    "$TEST_DIR/TokoviTest.kt" \
    -include-runtime -d "$OUT/tokovi.jar"

java -cp "$OUT/tokovi.jar" si.safeer.tv.cast.TokoviTestKt
