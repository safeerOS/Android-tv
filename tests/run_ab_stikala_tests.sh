#!/usr/bin/env bash
# Preizkus razclenjevanja A/B stikal v navadnem JVM.   KOTLINC=/pot/do/kotlinc tests/run_ab_stikala_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="${SRC_DIR:-$PROJECT_DIR/src/main/kotlin}"
KOTLINC="${KOTLINC:-${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" -J-Xmx1g "$SRC/si/safeer/tv/AbStikala.kt" "$TEST_DIR/AbStikalaTest.kt" -include-runtime -d "$OUT/ab.jar"
java -Dab.vir="$SRC/si/safeer/tv/AbStikala.kt" -cp "$OUT/ab.jar" si.safeer.tv.AbStikalaTestKt
