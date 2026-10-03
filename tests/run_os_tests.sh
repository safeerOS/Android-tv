#!/usr/bin/env bash
# Pravila Safeer OS (mere ikon, vrstica Nadaljuj, kartica Zaslon), shramba zapiskov in varno dekodiranje ikon
# v navadnem JVM, brez Androida (BitmapFactory je nadomestek v tests/stubs).
#   KOTLINC=/pot/do/kotlinc tests/run_os_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$HOME/Namizje/Neimenovana mapa/streamN-TV2/android_tv/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

"$KOTLINC" -J-Xmx1g \
    "$TEST_DIR/stubs/BitmapFactory.kt" \
    "$SRC/si/safeer/tv/os/OsPravila.kt" \
    "$SRC/si/safeer/tv/os/GalerijaPravila.kt" \
    "$SRC/si/safeer/tv/os/JezikiVsebine.kt" \
    "$SRC/si/safeer/tv/os/ZapiskiShramba.kt" \
    "$SRC/si/safeer/tv/os/VarnaSlika.kt" \
    "$SRC/si/safeer/tv/os/TokIzbira.kt" \
    "$SRC/si/safeer/tv/os/ObvestilaTokov.kt" \
    "$SRC/si/safeer/tv/os/SeznamiPravila.kt" \
    "$SRC/si/safeer/tv/SpletMostPravila.kt" \
    "$SRC/si/safeer/tv/tv/PredajaStrani.kt" \
    "$TEST_DIR/OsPravilaTest.kt" \
    "$TEST_DIR/GalerijaPravilaTest.kt" \
    "$TEST_DIR/VarnaSlikaTest.kt" \
    "$TEST_DIR/PredajaStraniTest.kt" \
    "$TEST_DIR/JezikiVsebineTest.kt" \
    "$TEST_DIR/ZapiskiShrambaTest.kt" \
    "$TEST_DIR/SpletMostPravilaTest.kt" \
    "$TEST_DIR/TokIzbiraTest.kt" \
    "$TEST_DIR/ObvestilaTokovTest.kt" \
    "$TEST_DIR/SeznamiPravilaTest.kt" \
    -include-runtime -d "$OUT/os.jar"

java -cp "$OUT/os.jar" si.safeer.tv.os.OsPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.GalerijaPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.VarnaSlikaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.PredajaStraniTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.JezikiVsebineTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.ZapiskiShrambaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.SpletMostPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.TokIzbiraTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.ObvestilaTokovTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.SeznamiPravilaTestKt
python3 "$TEST_DIR/preveri_tv_ikone.py"
