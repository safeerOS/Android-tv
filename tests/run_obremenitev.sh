#!/usr/bin/env bash
# Meritev ozkega grla huba (vihar prijav, zataknjena naprava) v navadnem JVM.
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$HOME/Namizje/Neimenovana mapa/streamN-TV2/android_tv/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"

# HubTokovi in KrogZaupanja uporabljata org.json (na Androidu vgrajen); na JVM ga dodamo iz predpomnilnika Gradla.
JSON_JAR="${JSON_JAR:-$(ls "$HOME"/.gradle/caches/modules-2/files-2.1/org.json/json/*/*/json-*.jar 2>/dev/null | grep -v sources | head -1)}"
OUT="${OBREMENITEV_OUT:-$(mktemp -d)}"
mkdir -p "$OUT"

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
    "$SRC/si/safeer/tv/cast/DostopPravila.kt" \
    "$SRC/si/safeer/tv/cast/HubHttp.kt" \
    "$SRC/si/safeer/tv/cast/RegisterNaprav.kt" \
    "$SRC/si/safeer/tv/cast/KrogZaupanja.kt" \
    "$TEST_DIR/ObremenitevTest.kt" \
    ${JSON_JAR:+-cp "$JSON_JAR"} \
    -include-runtime -d "$OUT/obremenitev.jar"

java -cp "$OUT/obremenitev.jar${JSON_JAR:+:$JSON_JAR}" si.safeer.tv.cast.ObremenitevTest
