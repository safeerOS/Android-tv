#!/usr/bin/env bash
# Meritev ozkega grla huba (vihar prijav, zataknjena naprava) v navadnem JVM.
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$HOME/Namizje/Neimenovana mapa/streamN-TV2/android_tv/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"

OUT="${OBREMENITEV_OUT:-$(mktemp -d)}"
mkdir -p "$OUT"

"$KOTLINC" -J-Xmx2g \
    "$TEST_DIR/stubs/Log.kt" \
    "$SRC/si/safeer/tv/cast/HubStreznik.kt" \
    "$SRC/si/safeer/tv/cast/JsonLahki.kt" \
    "$SRC/si/safeer/tv/cast/HubTokovi.kt" \
    "$SRC/si/safeer/tv/cast/Spake2.kt" \
    "$SRC/si/safeer/tv/cast/HubUsmerjevalnik.kt" \
    "$SRC/si/safeer/tv/cast/RegisterNaprav.kt" \
    "$SRC/si/safeer/tv/cast/KrogZaupanja.kt" \
    "$TEST_DIR/ObremenitevTest.kt" \
    -include-runtime -d "$OUT/obremenitev.jar"

java -cp "$OUT/obremenitev.jar" si.safeer.tv.cast.ObremenitevTest
