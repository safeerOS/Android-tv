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

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

"$KOTLINC" -J-Xmx2g \
    "$TEST_DIR/stubs/Log.kt" \
    "$SRC/si/safeer/tv/cast/HubStreznik.kt" \
    "$SRC/si/safeer/tv/cast/JsonLahki.kt" \
    "$SRC/si/safeer/tv/cast/HubTokovi.kt" \
    "$SRC/si/safeer/tv/cast/SafeerLog.kt" \
    "$SRC/si/safeer/tv/cast/Spake2.kt" \
    "$SRC/si/safeer/tv/cast/HubUsmerjevalnik.kt" \
    "$SRC/si/safeer/tv/cast/RegisterNaprav.kt" \
    "$SRC/si/safeer/tv/cast/KrogZaupanja.kt" \
    "$TEST_DIR/UsmerjevalnikTest.kt" \
    -include-runtime -d "$OUT/usmerjevalnik.jar"

java -cp "$OUT/usmerjevalnik.jar" si.safeer.tv.cast.UsmerjevalnikTestKt

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
