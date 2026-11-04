#!/usr/bin/env bash
# Izbira datotek (<input type=file>, 4. 11. 2026): accept -> MIME, strani samo content:// iz izbirnika, zavihek ne zaspi.
#   KOTLINC=/pot/do/kotlinc tests/run_izbira_datotek_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/IzbiraDatotek.kt" "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/SpanjePravila.kt" \
  "$TEST_DIR/IzbiraDatotekTest.kt" -include-runtime -d "$OUT/izbira.jar"
java -cp "$OUT/izbira.jar" si.safeer.tv.IzbiraDatotekTestKt
