#!/usr/bin/env bash
# Preizkus tokov Safeer Huba (deljenje zaslona, prenos datotek) v navadnem JVM.
#   KOTLINC=/pot/do/kotlinc tests/run_tokovi_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$HOME/Namizje/Neimenovana mapa/streamN-TV2/android_tv/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"

OUT="$(mktemp -d)"
# org.json (na Androidu vgrajen): za preizkus v JVM iz predpomnilnika Gradla.
JSON_JAR="${JSON_JAR:-$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.json" -name 'json-*.jar' 2>/dev/null | sort | tail -1)}"
trap 'rm -rf "$OUT"' EXIT

"$KOTLINC" -J-Xmx2g ${JSON_JAR:+-cp "$JSON_JAR"} \
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
    "$SRC/si/safeer/tv/cast/DostopPravila.kt" \
    "$SRC/si/safeer/tv/cast/HubHttp.kt" \
    "$SRC/si/safeer/tv/cast/RegisterNaprav.kt" \
    "$SRC/si/safeer/tv/cast/KrogZaupanja.kt" \
    "$TEST_DIR/TokoviTest.kt" \
    -include-runtime -d "$OUT/tokovi.jar"

java -cp "$OUT/tokovi.jar${JSON_JAR:+:$JSON_JAR}" si.safeer.tv.cast.TokoviTestKt
