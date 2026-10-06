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
    "$SRC/si/safeer/tv/os/ZacasniPravila.kt" \
    "$SRC/si/safeer/tv/os/ZasebniDodatki.kt" \
    "$SRC/si/safeer/tv/os/KnjiznicaKroga.kt" \
    "$SRC/si/safeer/tv/os/RazpolozljivostPravila.kt" \
    "$SRC/si/safeer/tv/os/DomPreverjanjePravila.kt" \
    "$SRC/si/safeer/tv/os/TempoDodatka.kt" \
    "$SRC/si/safeer/tv/os/ObnovaPravila.kt" \
    "$SRC/si/safeer/tv/os/IzvirniJezik.kt" \
    "$SRC/si/safeer/tv/SpletMostPravila.kt" \
    "$SRC/si/safeer/tv/tv/PredajaStrani.kt" \
    "$SRC/si/safeer/tv/cast/ObvestiloZaslona.kt" \
    "$SRC/si/safeer/tv/cast/PoDeljenju.kt" \
    "$SRC/si/safeer/tv/os/HlsPopravek.kt" \
    "$SRC/si/safeer/tv/os/KanaliPravila.kt" \
    "$SRC/si/safeer/tv/os/ZaslonPovecava.kt" \
    "$SRC/si/safeer/tv/os/ZaslonPogled.kt" \
    "$SRC/si/safeer/tv/os/ZaslonKodek.kt" \
    "$SRC/si/safeer/tv/os/OmrezjePravila.kt" \
    "$SRC/si/safeer/tv/os/PotDoNaprave.kt" \
    "$SRC/si/safeer/tv/os/ZvokPravila.kt" \
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
    "$TEST_DIR/ZacasniPravilaTest.kt" \
    "$TEST_DIR/ZasebniDodatkiTest.kt" \
    "$TEST_DIR/KnjiznicaKrogaTest.kt" \
    "$TEST_DIR/RazpolozljivostPravilaTest.kt" \
    "$TEST_DIR/DomPreverjanjePravilaTest.kt" \
    "$TEST_DIR/TempoDodatkaTest.kt" \
    "$TEST_DIR/ObnovaPravilaTest.kt" \
    "$TEST_DIR/IzvirniJezikTest.kt" \
    "$TEST_DIR/ObvestiloZaslonaTest.kt" \
    "$TEST_DIR/PoDeljenjuTest.kt" \
    "$TEST_DIR/HlsPopravekTest.kt" \
    "$TEST_DIR/KanaliPravilaTest.kt" \
    "$TEST_DIR/ZaslonPovecavaTest.kt" \
    "$TEST_DIR/ZaslonPogledTest.kt" \
    "$TEST_DIR/ZaslonKodekTest.kt" \
    "$TEST_DIR/OmrezjePravilaTest.kt" \
    "$TEST_DIR/PotDoNapraveTest.kt" \
    "$TEST_DIR/ZvokPravilaTest.kt" \
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
java -cp "$OUT/os.jar" si.safeer.tv.os.ZacasniPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.ZasebniDodatkiTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.KnjiznicaKrogaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.RazpolozljivostPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.DomPreverjanjePravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.TempoDodatkaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.ObnovaPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.IzvirniJezikTestKt
java -cp "$OUT/os.jar" si.safeer.tv.cast.ObvestiloZaslonaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.cast.PoDeljenjuTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.HlsPopravekTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.KanaliPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.ZaslonPovecavaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.ZaslonPogledTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.ZaslonKodekTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.OmrezjePravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.PotDoNapraveTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.ZvokPravilaTestKt

# Neposredna povezava TLS s pripetim potrdilom do naprave z vec naslovi (docs/LINK-MESH.md, pravilo 8):
# prave vticnice na naslovih zanke, potrdila naredi keytool iz JDK.
"$KOTLINC" -J-Xmx1g \
    "$TEST_DIR/stubs/Log.kt" \
    "$TEST_DIR/stubs/R.kt" \
    "$SRC/si/safeer/tv/os/Pin.kt" \
    "$SRC/si/safeer/tv/os/NeposrednaPovezava.kt" \
    "$TEST_DIR/NeposrednaPovezavaTest.kt" \
    -include-runtime -d "$OUT/povezava.jar"
java -cp "$OUT/povezava.jar" si.safeer.tv.os.NeposrednaPovezavaTestKt

python3 "$TEST_DIR/preveri_tv_ikone.py"
python3 "$TEST_DIR/preveri_naslovno_vrstico.py"
