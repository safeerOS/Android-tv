#!/usr/bin/env bash
# Preizkus pretvorbe temnega sloga (TemniSlogPravila.kt) v JVM in skripte MEDIJ_KOREN_JS v Node.js.
#   KOTLINC=/pot/do/kotlinc tests/run_temni_slog_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$HOME/Namizje/Neimenovana mapa/streamN-TV2/android_tv/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

echo "== 1. JVM preizkusi (TemniSlogPravilaTest.kt) =="
"$KOTLINC" -J-Xmx1g \
    "$SRC/si/safeer/tv/TemniSlogPravila.kt" \
    "$TEST_DIR/TemniSlogPravilaTest.kt" \
    -include-runtime -d "$OUT/temni.jar"

java -cp "$OUT/temni.jar" si.safeer.tv.TemniSlogPravilaTestKt

echo "== 2. Node.js preizkusi (medij_koren_test.js) =="
node "$TEST_DIR/medij_koren_test.js"

echo "VSI PREIZKUSI TEMNEGA SLOGA SO USPELI (OK)!"
