#!/usr/bin/env bash
# Pregled zaslonov Aplikacije, Datoteke, Naprave, Sporocila, Zapiski (5. 11. 2026): prenos APK (preusmeritve, paket
# ponudnika, Aptoide) in odlocitve zaslonov (fokus, drsenje, prikazanih) v navadnem JVM, brez Androida.
#   KOTLINC=/pot/do/kotlinc tests/run_pregled_zaslonov_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
OS="$PROJECT_DIR/src/main/kotlin/si/safeer/tv/os"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" "$OS/NamestiApkPravila.kt" "$OS/Preusmeritve.kt" "$OS/ZasloniOsPravila.kt" \
  "$TEST_DIR/NamestiApkPravilaTest.kt" "$TEST_DIR/ZasloniOsPravilaTest.kt" -include-runtime -d "$OUT/zasloni.jar"
java -cp "$OUT/zasloni.jar" si.safeer.tv.os.NamestiApkPravilaTestKt "$OS/NamestiApk.kt" "$OS/TrgovineAplikacij.kt"
java -cp "$OUT/zasloni.jar" si.safeer.tv.os.ZasloniOsPravilaTestKt "$OS"
