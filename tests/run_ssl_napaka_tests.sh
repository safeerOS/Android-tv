#!/usr/bin/env bash
# Stran napake neveljavnega potrdila (4. 11. 2026): razlog iz SslError, glavni okvir, kodiranje, brez »vseeno nadaljuj«.
#   KOTLINC=/pot/do/kotlinc tests/run_ssl_napaka_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/SslNapaka.kt" "$TEST_DIR/SslNapakaTest.kt" -include-runtime -d "$OUT/ssl.jar"
java -cp "$OUT/ssl.jar" si.safeer.tv.SslNapakaTestKt "${1:-$PROJECT_DIR/src/main/kotlin}"
