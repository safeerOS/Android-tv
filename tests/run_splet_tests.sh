#!/usr/bin/env bash
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
KOTLINC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/SpletMostPravila.kt" \
  "$TEST_DIR/SpletMostPravilaTest.kt" -include-runtime -d "$OUT/splet.jar"
java -cp "$OUT/splet.jar" si.safeer.tv.SpletMostPravilaTestKt
