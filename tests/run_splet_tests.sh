#!/usr/bin/env bash
# Pravila brez Androida: most strani v brskalniku, ikone strani, YouTube na dotik in zvocnik v omrezju (DLNA: razclenjevanje,
# cas, glasnost; predvajalnik upravlja zvocnik - preskok, premor, konec skladbe, prevzem zvocnika).
#   KOTLINC=/pot/do/kotlinc tests/run_splet_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$HOME/Namizje/Neimenovana mapa/streamN-TV2/android_tv/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/SpletMostPravila.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/SpletIkonePravila.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/YoutubeNaDotik.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/os/DlnaPravila.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/os/ZvocnikPravila.kt" \
  "$TEST_DIR/SpletMostPravilaTest.kt" "$TEST_DIR/SpletIkonePravilaTest.kt" "$TEST_DIR/YoutubeNaDotikTest.kt" "$TEST_DIR/DlnaPravilaTest.kt" "$TEST_DIR/ZvocnikPravilaTest.kt" \
  -include-runtime -d "$OUT/splet.jar"
java -cp "$OUT/splet.jar" si.safeer.tv.SpletMostPravilaTestKt
java -cp "$OUT/splet.jar" si.safeer.tv.SpletIkonePravilaTestKt
java -cp "$OUT/splet.jar" si.safeer.tv.YoutubeNaDotikTestKt
java -cp "$OUT/splet.jar" si.safeer.tv.os.DlnaPravilaTestKt
java -cp "$OUT/splet.jar" si.safeer.tv.os.ZvocnikPravilaTestKt
