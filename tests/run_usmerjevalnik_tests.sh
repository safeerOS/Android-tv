#!/usr/bin/env bash
# Preizkus bralca JSON, usmerjevalnika Safeer Huba in seznanjanja SPAKE2 v navadnem JVM
# (Androidovi razredi so v tests/stubs; HubTls je samo za Android in tu ni vkljucen).
#   KOTLINC=/pot/do/kotlinc tests/run_usmerjevalnik_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$HOME/Namizje/Neimenovana mapa/streamN-TV2/android_tv/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"

# HubTokovi uporablja org.json (na Androidu vgrajen); na JVM ga dodamo iz predpomnilnika Gradla.
JSON_JAR="${JSON_JAR:-$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/org.json/json/*/*/json-*.jar 2>/dev/null | grep -v sources | head -1)}"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

"$KOTLINC" -J-Xmx2g \
    "$TEST_DIR/stubs/Log.kt" \
    "$TEST_DIR/stubs/DatotekeStreznik.kt" \
    "$SRC/si/safeer/tv/cast/HubStreznik.kt" \
    "$SRC/si/safeer/tv/cast/HubObramba.kt" \
    "$SRC/si/safeer/tv/cast/HubVarovalka.kt" \
    "$SRC/si/safeer/tv/cast/JsonLahki.kt" \
    "$SRC/si/safeer/tv/cast/HubTokovi.kt" \
    "$SRC/si/safeer/tv/cast/SafeerLog.kt" \
    "$SRC/si/safeer/tv/cast/Spake2.kt" \
    "$SRC/si/safeer/tv/cast/HubUsmerjevalnik.kt" \
    "$SRC/si/safeer/tv/cast/HubHttp.kt" \
    "$SRC/si/safeer/tv/cast/RegisterNaprav.kt" \
    "$SRC/si/safeer/tv/cast/KrogZaupanja.kt" \
    "$TEST_DIR/UsmerjevalnikTest.kt" \
    ${JSON_JAR:+-cp "$JSON_JAR"} \
    -include-runtime -d "$OUT/usmerjevalnik.jar"

java -cp "$OUT/usmerjevalnik.jar${JSON_JAR:+:$JSON_JAR}" si.safeer.tv.cast.UsmerjevalnikTestKt

# Link Mesh (docs/LINK-MESH.md): trije usmerjevalniki kot sosedje.
"$KOTLINC" -J-Xmx2g \
    "$TEST_DIR/stubs/Log.kt" \
    "$TEST_DIR/stubs/DatotekeStreznik.kt" \
    "$SRC/si/safeer/tv/cast/HubStreznik.kt" \
    "$SRC/si/safeer/tv/cast/HubObramba.kt" \
    "$SRC/si/safeer/tv/cast/HubVarovalka.kt" \
    "$SRC/si/safeer/tv/cast/JsonLahki.kt" \
    "$SRC/si/safeer/tv/cast/HubTokovi.kt" \
    "$SRC/si/safeer/tv/cast/SafeerLog.kt" \
    "$SRC/si/safeer/tv/cast/Spake2.kt" \
    "$SRC/si/safeer/tv/cast/HubUsmerjevalnik.kt" \
    "$SRC/si/safeer/tv/cast/HubHttp.kt" \
    "$SRC/si/safeer/tv/cast/RegisterNaprav.kt" \
    "$SRC/si/safeer/tv/cast/KrogZaupanja.kt" \
    "$TEST_DIR/MeshTest.kt" \
    ${JSON_JAR:+-cp "$JSON_JAR"} \
    -include-runtime -d "$OUT/mesh.jar"
java -cp "$OUT/mesh.jar${JSON_JAR:+:$JSON_JAR}" si.safeer.tv.cast.MeshTestKt

# Testni vektor RFC 9382 (isti kot za spake2.py v brskalniku za Linux).
"$KOTLINC" -J-Xmx2g \
    "$SRC/si/safeer/tv/cast/Spake2.kt" \
    "$TEST_DIR/Spake2Test.kt" \
    -include-runtime -d "$OUT/spake2.jar"
java -cp "$OUT/spake2.jar" si.safeer.tv.cast.Spake2TestKt

# Izvolitev huba (prioritete, izenacenje po id, umik).
"$KOTLINC" -J-Xmx2g \
    "$SRC/si/safeer/tv/cast/IzvolitevHuba.kt" \
    "$TEST_DIR/IzvolitevTest.kt" \
    -include-runtime -d "$OUT/izvolitev.jar"
java -cp "$OUT/izvolitev.jar" si.safeer.tv.cast.IzvolitevTestKt

# Link Core: kopija na telefonu mora biti enaka viru (ce je telefon na tem racunalniku).
if [[ -d "$PROJECT_DIR/../safeer-browser" ]]; then
    bash "$PROJECT_DIR/tools/link-core-sync.sh" --preveri "$PROJECT_DIR/../safeer-browser"
fi
