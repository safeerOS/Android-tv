#!/usr/bin/env bash
# SDK dodatkov (naloga 125): primer dodatka kot lazni dodatek na krajevnih vratih in orodje za preverjanje proti njemu;
# skupni primeri protokola (sdk/skupni-primeri/protokol-v1.json) s kodo aplikacije; Python del SDK (isti v Safeer-linux
# in Safeer-windows: pravila, orodje, predlogi, skupne kopije).
#   KOTLINC=/pot/do/kotlinc tests/run_dodatki_sdk_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}"   # orodja: SAFEER_TOOLS_DIR ali .tools (lahko povezava) v projektu
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" -J-Xmx1g \
    "$SRC/si/safeer/tv/cast/JsonLahki.kt" \
    "$SRC/si/safeer/tv/os/DodatkiProtokol.kt" \
    "$SRC/si/safeer/tv/os/OsPravila.kt" \
    "$SRC/si/safeer/tv/os/NedelujocePravila.kt" \
    "$SRC/si/safeer/tv/os/JezikiVsebine.kt" \
    "$SRC/si/safeer/tv/os/SkrivnostiPravila.kt" \
    "$PROJECT_DIR/sdk/primer/PrimerDodatka.kt" \
    "$PROJECT_DIR/sdk/orodja/PreveriDodatek.kt" \
    "$TEST_DIR/DodatkiSdkTest.kt" \
    "$TEST_DIR/DodatkiProtokolPrimeriTest.kt" \
    -include-runtime -d "$OUT/sdk.jar"
java -cp "$OUT/sdk.jar" si.safeer.tv.sdk.DodatkiSdkTestKt
java -cp "$OUT/sdk.jar" si.safeer.tv.sdk.DodatkiProtokolPrimeriTestKt "$PROJECT_DIR/sdk/skupni-primeri/protokol-v1.json"
if command -v python3 >/dev/null 2>&1; then
    (cd "$PROJECT_DIR" && PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s sdk/python -p "*_test.py")
else
    echo "python3 ni namescen - Python del SDK preskocen" >&2
fi
