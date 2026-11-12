#!/usr/bin/env bash
# Preizkus zaupanja izvora vrnitve (S1) v navadnem JVM.   KOTLINC=/pot/do/kotlinc tests/run_safeer_izvor_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="${SRC_DIR:-$PROJECT_DIR/src/main/kotlin}"
KOTLINC="${KOTLINC:-${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" -J-Xmx1g "$SRC/si/safeer/tv/SafeerIzvor.kt" "$TEST_DIR/SafeerIzvorTest.kt" -include-runtime -d "$OUT/izvor.jar"
java -Dmain.vir="$SRC/si/safeer/tv/MainActivity.kt" -cp "$OUT/izvor.jar" si.safeer.tv.SafeerIzvorTestKt
