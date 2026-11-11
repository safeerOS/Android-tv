#!/usr/bin/env bash
# Varnost Safeer Linka (pregled 10. 11. 2026): pravi prehod interneta in pravi krog zaupanja v navadnem JVM.
# Androidovi razredi so v tests/stubs/prehod; org.json je pravi (na Androidu vgrajen).
#   KOTLINC=/pot/do/kotlinc JSON_JAR=/pot/do/json.jar tests/run_link_varnost_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"

JSON_JAR="${JSON_JAR:-$(for f in "$HOME"/.gradle/caches/modules-2/files-2.1/org.json/json/*/*/json-*.jar; do case "$f" in *sources*) ;; *) [ -f "$f" ] && { echo "$f"; break; } ;; esac; done)}"
[ -n "$JSON_JAR" ] || { echo "Manjka org.json: nastavi JSON_JAR (json-*.jar). Glej tests/vse.sh." >&2; exit 2; }
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# Prehod interneta: umik naprave iz kroga zapre njene odprte tokove in zbrise dovoljenje interneta (2.5); odpiranje in
# poizvedba iz preverjene seje, nezasciteno v imenu naprave, ki zascito zna, zavrnjeno (B).
"$KOTLINC" -J-Xmx1g -cp "$JSON_JAR" \
    "$TEST_DIR/stubs/Log.kt" \
    "$TEST_DIR/stubs/prehod/Context.kt" \
    "$TEST_DIR/stubs/prehod/Network.kt" \
    "$TEST_DIR/stubs/prehod/Base64.kt" \
    "$TEST_DIR/stubs/prehod/Link.kt" \
    "$TEST_DIR/stubs/prehod/Cast.kt" \
    "$SRC/si/safeer/tv/cast/JsonLahki.kt" \
    "$SRC/si/safeer/tv/cast/KrogZaupanja.kt" \
    "$SRC/si/safeer/tv/link/InternetPoti.kt" \
    "$SRC/si/safeer/tv/link/InternetPretok.kt" \
    "$SRC/si/safeer/tv/link/AndroidApplicationGateway.kt" \
    "$TEST_DIR/PrehodInternetaTest.kt" \
    -include-runtime -d "$OUT/prehod.jar"
java -cp "$OUT/prehod.jar:$JSON_JAR" si.safeer.tv.link.PrehodInternetaTestKt "$SRC"

# Krog s sredisca (najdba A): nepodpisan novejsi vnos ne izbrise umika; vrnjena naprava zacne brez dovoljenj.
"$KOTLINC" -J-Xmx1g -cp "$JSON_JAR" \
    "$TEST_DIR/stubs/prehod/Cast.kt" \
    "$TEST_DIR/stubs/prehod/Context.kt" \
    "$SRC/si/safeer/tv/cast/JsonLahki.kt" \
    "$SRC/si/safeer/tv/cast/KrogZaupanja.kt" \
    "$SRC/si/safeer/tv/cast/DostopPravila.kt" \
    "$TEST_DIR/KrogUmikTest.kt" \
    -include-runtime -d "$OUT/krog-umik.jar"
java -cp "$OUT/krog-umik.jar:$JSON_JAR" si.safeer.tv.cast.KrogUmikTestKt "$SRC"
