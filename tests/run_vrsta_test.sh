#!/usr/bin/env bash
# Izhodna vrsta povezave: naprava, ki ne bere, ne sme zadrzati huba (pravi socketi, JVM).
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$HOME/Namizje/Neimenovana mapa/streamN-TV2/android_tv/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"

OUT="${VRSTA_OUT:-$(mktemp -d)}"
mkdir -p "$OUT"

"$KOTLINC" -J-Xmx2g \
    "$TEST_DIR/stubs/Log.kt" \
    "$SRC/si/safeer/tv/cast/HubStreznik.kt" \
    "$SRC/si/safeer/tv/cast/JsonLahki.kt" \
    "$TEST_DIR/VrstaTest.kt" \
    -include-runtime -d "$OUT/vrsta.jar"

java -cp "$OUT/vrsta.jar" si.safeer.tv.cast.VrstaTest
