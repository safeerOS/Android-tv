#!/usr/bin/env bash
# Dokaz enakosti AbpVzorec (brez regexa) s starim ujemalnikom Pattern (4. 11. 2026).
#   KOTLINC=/pot/do/kotlinc [SAFEER_EASYLIST=/pot/do/easylist.txt] tests/run_abp_vzorec_tests.sh
# Brez SAFEER_EASYLIST tece na robnih pravilih in sinteticnem korpusu; s polnim EasyList na vseh pravilih.
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
KOTLINC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" "$SRC/com/safeer/threatfeed/FilterListEngine.kt" "$TEST_DIR/AbpVzorecTest.kt" -include-runtime -d "$OUT/abp.jar"
java -Xss8m -cp "$OUT/abp.jar" com.safeer.threatfeed.AbpVzorecTestKt ${SAFEER_EASYLIST:+"$SAFEER_EASYLIST"}
