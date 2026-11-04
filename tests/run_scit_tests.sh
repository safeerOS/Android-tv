#!/usr/bin/env bash
# Preizkus jedra Safeer Scita (nabor domen, paketi DNS) v navadnem JVM.
#   KOTLINC=/pot/do/kotlinc tests/run_scit_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

"$KOTLINC" -J-Xmx2g \
    "$SRC/si/safeer/tv/scit/DomenskiNabor.kt" \
    "$SRC/si/safeer/tv/scit/DnsPaket.kt" \
    "$SRC/si/safeer/tv/scit/TcpPaket.kt" \
    "$SRC/si/safeer/tv/scit/TcpDns.kt" \
    "$SRC/si/safeer/tv/scit/ScitIzjeme.kt" \
    "$SRC/si/safeer/tv/scit/ScitPregled.kt" \
    "$TEST_DIR/ScitTest.kt" \
    "$TEST_DIR/ScitPregledTest.kt" \
    "$TEST_DIR/TcpDnsTest.kt" \
    -include-runtime -d "$OUT/scit.jar"

java -Xmx512m -cp "$OUT/scit.jar" si.safeer.tv.scit.ScitTestKt
# Lastnikov pregled Scita (3. in 4. 11. 2026): vrata 53, brez tuje rezerve, zasebni DNS, zadnja imena, izjeme, privzeti vklop, meni brskalnika.
java -Xmx512m -cp "$OUT/scit.jar" si.safeer.tv.scit.ScitPregledTestKt
