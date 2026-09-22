#!/usr/bin/env bash
# Preizkus Internet gateway politike (poti, dovoljeni cilji, vrata) v navadnem JVM.
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$HOME/Namizje/Neimenovana mapa/streamN-TV2/android_tv/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

"$KOTLINC" -J-Xmx2g "$SRC/si/safeer/tv/link/InternetPoti.kt" "$TEST_DIR/InternetPotiTest.kt" \
    -include-runtime -d "$OUT/gateway.jar"
java -cp "$OUT/gateway.jar" InternetPotiTestKt
