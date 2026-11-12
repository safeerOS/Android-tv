#!/usr/bin/env bash
# Preizkus enakosti dvonitnega in enonitnega prevoda seznama pravil.   KOTLINC=/pot/do/kotlinc tests/run_filter_prevod_tests.sh
# SAFEER_EASYLIST=/pot/do/easylist.txt: tudi na polnem EasyList in z meritvijo casa. SRC_DIR: samo za preverbo brez popravka.
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="${SRC_DIR:-$PROJECT_DIR/src/main/kotlin}"
KOTLINC="${KOTLINC:-${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" -J-Xmx1g "$SRC/com/safeer/threatfeed/FilterListEngine.kt" "$TEST_DIR/FilterPrevodTest.kt" -include-runtime -d "$OUT/prevod.jar"
java -Xss8m -cp "$OUT/prevod.jar" com.safeer.threatfeed.FilterPrevodTestKt ${SAFEER_EASYLIST:+"$SAFEER_EASYLIST"}
