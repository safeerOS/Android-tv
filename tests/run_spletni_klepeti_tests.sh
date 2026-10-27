#!/usr/bin/env bash
# Spletne storitve v Sporocilih (katalog po vzoru Ferdium, neprebrano iz naslova, lasten naslov) - cista pravila v
# navadnem JVM, brez Androida. Preveri tudi pravi katalog assets/sporocila/spletni-klepeti.json.
#   KOTLINC=/pot/do/kotlinc JSON_JAR=/pot/do/json.jar tests/run_spletni_klepeti_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin/si/safeer/tv/os"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"
JSON_JAR="${JSON_JAR:-$( { find "$HOME/.gradle/caches/modules-2/files-2.1/org.json" -name 'json-*.jar' 2>/dev/null | sort | tail -1 || true; } )}"
[ -n "$JSON_JAR" ] || { echo "Manjka org.json: nastavi JSON_JAR (json-*.jar). Glej tests/vse.sh." >&2; exit 2; }
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

"$KOTLINC" -J-Xmx1g -cp "$JSON_JAR" "$SRC/SpletniKlepetiPravila.kt" "$TEST_DIR/SpletniKlepetiPravilaTest.kt" \
    -include-runtime -d "$OUT/splet.jar"
java -Dkatalog="$PROJECT_DIR/assets/sporocila/spletni-klepeti.json" -cp "$OUT/splet.jar:$JSON_JAR" si.safeer.tv.os.SpletniKlepetiPravilaTestKt
