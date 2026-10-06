#!/usr/bin/env bash
# Preizkus streznika Safeer Huba v navadnem JVM (Androidovi razredi so v tests/stubs).
#   KOTLINC=/pot/do/kotlinc tests/run_hub_tests.sh
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
    "$SRC/si/safeer/tv/cast/HubObramba.kt" \
    "$TEST_DIR/HubStreznikTest.kt" \
    -include-runtime -d "$OUT/hub.jar"

java -cp "$OUT/hub.jar" si.safeer.tv.cast.HubStreznikTestKt

# Obrambni mehanizem sredisca (HubObramba): pravila z lazno uro in pravo sredisce na zanki.
"$KOTLINC" -J-Xmx2g \
    "$TEST_DIR/stubs/Log.kt" \
    "$SRC/si/safeer/tv/cast/HubStreznik.kt" \
    "$SRC/si/safeer/tv/cast/HubObramba.kt" \
    "$TEST_DIR/HubObrambaTest.kt" \
    -include-runtime -d "$OUT/obramba.jar"

java -cp "$OUT/obramba.jar" si.safeer.tv.cast.HubObrambaTestKt

# Varovalka kode za povezavo (HubVarovalka): skupna omejitev ugibanja 6-mestne kode, pravila z lazno uro.
"$KOTLINC" -J-Xmx2g \
    "$SRC/si/safeer/tv/cast/HubVarovalka.kt" \
    "$TEST_DIR/HubVarovalkaTest.kt" \
    -include-runtime -d "$OUT/varovalka.jar"

java -cp "$OUT/varovalka.jar" si.safeer.tv.cast.HubVarovalkaTestKt

# Dostop naprav do vsebin (DostopPravila): kaj zahteva katero dejanje in kdo ob uvedbi dovoljenj obdrzi dostop.
"$KOTLINC" -J-Xmx2g \
    "$SRC/si/safeer/tv/cast/DostopPravila.kt" \
    "$TEST_DIR/DostopPravilaTest.kt" \
    -include-runtime -d "$OUT/dostop.jar"

java -cp "$OUT/dostop.jar" si.safeer.tv.cast.DostopPravilaTestKt
