#!/usr/bin/env bash
# Zvocnik v omrezju: YouTube (zvocni tok iz InnerTube, odjemalec VISIONOS) in Google Cast (okvirji, pristnost naprave do
# Googlovega korena, stanja, tok z glavami) - cista pravila v navadnem JVM, brez Androida.
#   KOTLINC=/pot/do/kotlinc JSON_JAR=/pot/do/json.jar tests/run_zvocnik_cast_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin/si/safeer/tv/os"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"
# org.json (na Androidu vgrajen): za JVM iz predpomnilnika Gradla ali JSON_JAR (CI: json-20180813, pripeta vsota).
JSON_JAR="${JSON_JAR:-$( { find "$HOME/.gradle/caches/modules-2/files-2.1/org.json" -name 'json-*.jar' 2>/dev/null | sort | tail -1 || true; } )}"
[ -n "$JSON_JAR" ] || { echo "Manjka org.json: nastavi JSON_JAR (json-*.jar). Glej tests/vse.sh." >&2; exit 2; }
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

"$KOTLINC" -J-Xmx1g -cp "$JSON_JAR" \
    "$SRC/YoutubeZvokPravila.kt" "$SRC/YoutubeMixPravila.kt" "$SRC/CastPravila.kt" "$SRC/ZvocnikPravila.kt" "$SRC/DlnaPravila.kt" \
    "$TEST_DIR/YoutubeZvokPravilaTest.kt" "$TEST_DIR/YoutubeMixPravilaTest.kt" "$TEST_DIR/CastPravilaTest.kt" \
    -include-runtime -d "$OUT/zvocnik.jar"
java -cp "$OUT/zvocnik.jar:$JSON_JAR" si.safeer.tv.os.YoutubeZvokPravilaTestKt
java -cp "$OUT/zvocnik.jar:$JSON_JAR" si.safeer.tv.os.YoutubeMixPravilaTestKt
java -cp "$OUT/zvocnik.jar:$JSON_JAR" si.safeer.tv.os.CastPravilaTestKt
