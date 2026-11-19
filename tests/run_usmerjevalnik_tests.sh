#!/usr/bin/env bash
# Preizkus bralca JSON, usmerjevalnika Safeer Huba in seznanjanja SPAKE2 v navadnem JVM
# (Androidovi razredi so v tests/stubs; HubTls je samo za Android in tu ni vkljucen).
#   KOTLINC=/pot/do/kotlinc tests/run_usmerjevalnik_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"

# HubTokovi uporablja org.json (na Androidu vgrajen); na JVM ga dodamo iz predpomnilnika Gradla.
JSON_JAR="${JSON_JAR:-$(for f in "$HOME"/.gradle/caches/modules-2/files-2.1/org.json/json/*/*/json-*.jar; do case "$f" in *sources*) ;; *) [ -f "$f" ] && { echo "$f"; break; } ;; esac; done)}"
[ -n "$JSON_JAR" ] || { echo "Manjka org.json: nastavi JSON_JAR (json-*.jar) ali zgradi projekt z Gradlom (predpomnilnik). Glej tests/vse.sh." >&2; exit 2; }
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

"$KOTLINC" -J-Xmx2g \
    "$TEST_DIR/stubs/Log.kt" \
    "$TEST_DIR/stubs/DatotekeStreznik.kt" \
    "$SRC/si/safeer/tv/cast/HubStreznik.kt" \
    "$SRC/si/safeer/tv/cast/ViriNaprave.kt" \
    "$SRC/si/safeer/tv/cast/HubObramba.kt" \
    "$SRC/si/safeer/tv/cast/HubVarovalka.kt" \
    "$SRC/si/safeer/tv/cast/JsonLahki.kt" \
    "$SRC/si/safeer/tv/cast/HubTokovi.kt" \
    "$SRC/si/safeer/tv/cast/LinkVticniki.kt" \
    "$SRC/si/safeer/tv/cast/SafeerLog.kt" \
    "$SRC/si/safeer/tv/cast/Spake2.kt" \
    "$SRC/si/safeer/tv/cast/HubUsmerjevalnik.kt" \
    "$SRC/si/safeer/tv/cast/OznakaDnevnik.kt" \
    "$SRC/si/safeer/tv/cast/SeznamSprememb.kt" \
    "$SRC/si/safeer/tv/cast/DostopPravila.kt" \
    "$SRC/si/safeer/tv/cast/HubHttp.kt" \
    "$SRC/si/safeer/tv/cast/RegisterNaprav.kt" \
    "$SRC/si/safeer/tv/cast/KrogZaupanja.kt" \
    "$SRC/si/safeer/tv/link/KatalogAplikacij.kt" \
    "$SRC/si/safeer/tv/os/LinaVmesnikPravila.kt" \
    "$SRC/si/safeer/tv/os/UskladitevKljucevPravila.kt" \
    "$SRC/si/safeer/tv/os/NapraveKrogaPravila.kt" \
    "$SRC/si/safeer/tv/os/SkrivnostiPravila.kt" \
    "$TEST_DIR/UsmerjevalnikTest.kt" \
    ${JSON_JAR:+-cp "$JSON_JAR"} \
    -include-runtime -d "$OUT/usmerjevalnik.jar"

java -cp "$OUT/usmerjevalnik.jar${JSON_JAR:+:$JSON_JAR}" si.safeer.tv.cast.UsmerjevalnikTestKt

# Link Mesh (docs/LINK-MESH.md): trije usmerjevalniki kot sosedje.
"$KOTLINC" -J-Xmx2g \
    "$TEST_DIR/stubs/Log.kt" \
    "$TEST_DIR/stubs/DatotekeStreznik.kt" \
    "$SRC/si/safeer/tv/cast/HubStreznik.kt" \
    "$SRC/si/safeer/tv/cast/ViriNaprave.kt" \
    "$SRC/si/safeer/tv/cast/HubObramba.kt" \
    "$SRC/si/safeer/tv/cast/HubVarovalka.kt" \
    "$SRC/si/safeer/tv/cast/JsonLahki.kt" \
    "$SRC/si/safeer/tv/cast/HubTokovi.kt" \
    "$SRC/si/safeer/tv/cast/LinkVticniki.kt" \
    "$SRC/si/safeer/tv/cast/SafeerLog.kt" \
    "$SRC/si/safeer/tv/cast/Spake2.kt" \
    "$SRC/si/safeer/tv/cast/HubUsmerjevalnik.kt" \
    "$SRC/si/safeer/tv/cast/OznakaDnevnik.kt" \
    "$SRC/si/safeer/tv/cast/SeznamSprememb.kt" \
    "$SRC/si/safeer/tv/cast/DostopPravila.kt" \
    "$SRC/si/safeer/tv/cast/HubHttp.kt" \
    "$SRC/si/safeer/tv/cast/RegisterNaprav.kt" \
    "$SRC/si/safeer/tv/cast/KrogZaupanja.kt" \
    "$SRC/si/safeer/tv/link/PomocPravila.kt" \
    "$SRC/si/safeer/tv/cast/KabelNaslovi.kt" \
    "$TEST_DIR/MeshTest.kt" \
    ${JSON_JAR:+-cp "$JSON_JAR"} \
    -include-runtime -d "$OUT/mesh.jar"
java -cp "$OUT/mesh.jar${JSON_JAR:+:$JSON_JAR}" si.safeer.tv.cast.MeshTestKt

# Seznam naprav kot posnetek + spremembe (SeznamSprememb): skupna pogodba z racunalnikom (tests/fixtures), sredisce,
# sosedje, vrzel in zdruzevanje.
"$KOTLINC" -J-Xmx2g \
    "$TEST_DIR/stubs/Log.kt" \
    "$TEST_DIR/stubs/DatotekeStreznik.kt" \
    "$SRC/si/safeer/tv/cast/HubStreznik.kt" \
    "$SRC/si/safeer/tv/cast/ViriNaprave.kt" \
    "$SRC/si/safeer/tv/cast/HubObramba.kt" \
    "$SRC/si/safeer/tv/cast/HubVarovalka.kt" \
    "$SRC/si/safeer/tv/cast/JsonLahki.kt" \
    "$SRC/si/safeer/tv/cast/HubTokovi.kt" \
    "$SRC/si/safeer/tv/cast/LinkVticniki.kt" \
    "$SRC/si/safeer/tv/cast/SafeerLog.kt" \
    "$SRC/si/safeer/tv/cast/Spake2.kt" \
    "$SRC/si/safeer/tv/cast/HubUsmerjevalnik.kt" \
    "$SRC/si/safeer/tv/cast/OznakaDnevnik.kt" \
    "$SRC/si/safeer/tv/cast/SeznamSprememb.kt" \
    "$SRC/si/safeer/tv/cast/DostopPravila.kt" \
    "$SRC/si/safeer/tv/cast/HubHttp.kt" \
    "$SRC/si/safeer/tv/cast/RegisterNaprav.kt" \
    "$SRC/si/safeer/tv/cast/KrogZaupanja.kt" \
    "$SRC/si/safeer/tv/link/PomocPravila.kt" \
    "$TEST_DIR/SeznamSpremembTest.kt" \
    ${JSON_JAR:+-cp "$JSON_JAR"} \
    -include-runtime -d "$OUT/seznam.jar"
java -cp "$OUT/seznam.jar${JSON_JAR:+:$JSON_JAR}" si.safeer.tv.cast.SeznamSpremembTestKt "$TEST_DIR/fixtures/seznam_sprememb.json"

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

# Link Core (kopija na telefonu = vir) je svoj korak v tests/vse.sh in svoja naloga v CI (tedenski pregled 4. 11. 2026:
# tu se je brez ../safeer-browser tiho preskocila).
