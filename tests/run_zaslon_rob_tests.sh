#!/usr/bin/env bash
# Preizkus cistih pravil za nastavljiv rob slike (ZaslonRob) v navadnem JVM, brez Androida.
#   KOTLINC=~/android-studio/plugins/Kotlin/kotlinc/bin/kotlinc bash tests/run_zaslon_rob_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
OS="$PROJECT_DIR/src/main/kotlin/si/safeer/tv/os"
DEFAULT_KOTLINC="$HOME/android-studio/plugins/Kotlin/kotlinc/bin/kotlinc"
if [ -n "${KOTLINC:-}" ]; then
  KOTLINC_BIN="$KOTLINC"
elif [ -x "$DEFAULT_KOTLINC" ]; then
  KOTLINC_BIN="$DEFAULT_KOTLINC"
elif [ -x "${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}/kotlinc/bin/kotlinc" ]; then
  KOTLINC_BIN="${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}/kotlinc/bin/kotlinc"
else
  KOTLINC_BIN="kotlinc"
fi
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
export MALLOC_ARENA_MAX=2
"$KOTLINC_BIN" -J-Xmx512m -J-XX:+UseSerialGC -J-XX:CompressedClassSpaceSize=64m -J-XX:ReservedCodeCacheSize=64m \
  "$OS/ZaslonRob.kt" "$TEST_DIR/ZaslonRobTest.kt" -include-runtime -d "$OUT/zaslon_rob.jar"
java -Xmx128m -XX:+UseSerialGC -XX:CompressedClassSpaceSize=32m -XX:ReservedCodeCacheSize=32m \
  -cp "$OUT/zaslon_rob.jar" si.safeer.tv.os.ZaslonRobTestKt
