#!/usr/bin/env bash
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
KOTLINC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/SpletMostPravila.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/SpletIkonePravila.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/YoutubeNaDotik.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/os/DlnaPravila.kt" \
  "$TEST_DIR/SpletMostPravilaTest.kt" "$TEST_DIR/SpletIkonePravilaTest.kt" "$TEST_DIR/YoutubeNaDotikTest.kt" "$TEST_DIR/DlnaPravilaTest.kt" \
  -include-runtime -d "$OUT/splet.jar"
java -cp "$OUT/splet.jar" si.safeer.tv.SpletMostPravilaTestKt
java -cp "$OUT/splet.jar" si.safeer.tv.SpletIkonePravilaTestKt
java -cp "$OUT/splet.jar" si.safeer.tv.YoutubeNaDotikTestKt
java -cp "$OUT/splet.jar" si.safeer.tv.os.DlnaPravilaTestKt
