#!/usr/bin/env bash
# Preizkus predprevedenega nabora EasyList.   KOTLINC=/pot/do/kotlinc tests/run_filter_predpomnilnik_tests.sh
# SAFEER_EASYLIST=/pot/do/easylist.txt: tudi na polnem EasyList in z meritvijo casa. SRC_DIR, PREDP_SRC: samo za preverbo brez popravka.
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="${SRC_DIR:-$PROJECT_DIR/src/main/kotlin}"
KOTLINC="${KOTLINC:-${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" -J-Xmx1g "$SRC/com/safeer/threatfeed/FilterListEngine.kt" "$SRC/com/safeer/threatfeed/FilterSetPredpomnilnik.kt" "$SRC/com/safeer/threatfeed/ThreatListAgent.kt" "$TEST_DIR/FilterPredpomnilnikTest.kt" -include-runtime -d "$OUT/predp.jar"
java -Xss8m -Dpredp.src="${PREDP_SRC:-$SRC/si/safeer/tv}" -cp "$OUT/predp.jar" com.safeer.threatfeed.FilterPredpomnilnikTestKt ${SAFEER_EASYLIST:+"$SAFEER_EASYLIST"}
