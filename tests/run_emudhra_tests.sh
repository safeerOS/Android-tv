#!/usr/bin/env bash
# JVM preizkus zascite eMudhra (seznam 16 potrdil, preverba verige, zastavica opozorila, »Nazaj na varno«, opozorilna stran).
#   KOTLINC=/pot/do/kotlinc tests/run_emudhra_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/EmudhraRevocations.kt" "$TEST_DIR/EmudhraRevocationsTest.kt" \
  -include-runtime -d "$OUT/emudhra.jar"
java -cp "$OUT/emudhra.jar" si.safeer.tv.EmudhraRevocationsTestKt
