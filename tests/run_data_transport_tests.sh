#!/usr/bin/env bash
# Safeer Data Transport (v0.26) v navadnem JVM - brez Androida (isto nacelo kot
# run_usmerjevalnik_tests.sh: KrogZaupanja in JsonLahki ne poznata Androida).
#   KOTLINC=/pot/do/kotlinc tests/run_data_transport_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$HOME/Namizje/Neimenovana mapa/streamN-TV2/android_tv/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# KrogZaupanja referira HubUsmerjevalnik.Shramba (privzeti parameter konstruktorja), zato je
# treba prevesti isto verigo kot run_usmerjevalnik_tests.sh, ceprav DataTransport sam ne uporablja
# nobenega od teh razredov razen JsonLahki in KrogZaupanja.
"$KOTLINC" -J-Xmx2g \
    "$TEST_DIR/stubs/Log.kt" \
    "$SRC/si/safeer/tv/cast/HubStreznik.kt" \
    "$SRC/si/safeer/tv/cast/JsonLahki.kt" \
    "$SRC/si/safeer/tv/cast/HubTokovi.kt" \
    "$SRC/si/safeer/tv/cast/SafeerLog.kt" \
    "$SRC/si/safeer/tv/cast/Spake2.kt" \
    "$SRC/si/safeer/tv/cast/HubUsmerjevalnik.kt" \
    "$SRC/si/safeer/tv/cast/HubHttp.kt" \
    "$SRC/si/safeer/tv/cast/RegisterNaprav.kt" \
    "$SRC/si/safeer/tv/cast/KrogZaupanja.kt" \
    "$SRC/si/safeer/tv/cast/DataTransport.kt" \
    "$TEST_DIR/DataTransportTest.kt" \
    -include-runtime -d "$OUT/data_transport.jar"

java -cp "$OUT/data_transport.jar" si.safeer.tv.cast.DataTransportTestKt
