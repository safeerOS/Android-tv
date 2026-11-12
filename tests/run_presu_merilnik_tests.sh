#!/usr/bin/env bash
# Preizkus merilnika shouldInterceptRequest (H56) v navadnem JVM.   KOTLINC=/pot/do/kotlinc tests/run_presu_merilnik_tests.sh
# PRESU_SRC=<mapa z ChromiumEngineView.kt ...> samo za preverbo, da preizkus brez povezave pade.
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="${SRC_DIR:-$PROJECT_DIR/src/main/kotlin}"
KOTLINC="${KOTLINC:-${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" -J-Xmx1g "$SRC/si/safeer/tv/PresuMerilnik.kt" "$TEST_DIR/PresuMerilnikTest.kt" -include-runtime -d "$OUT/presu.jar"
java -Dpresu.src="${PRESU_SRC:-$SRC/si/safeer/tv}" -cp "$OUT/presu.jar" si.safeer.tv.PresuMerilnikTestKt
