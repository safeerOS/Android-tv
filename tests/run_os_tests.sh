#!/usr/bin/env bash
# Pravila Safeer OS (mere ikon, vrstica Nadaljuj, kartica Zaslon), shramba zapiskov in varno dekodiranje ikon
# v navadnem JVM, brez Androida (BitmapFactory je nadomestek v tests/stubs).
#   KOTLINC=/pot/do/kotlinc tests/run_os_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
SRC="$PROJECT_DIR/src/main/kotlin"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}"   # orodja: SAFEER_TOOLS_DIR ali .tools (lahko povezava) v projektu
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
    "$SRC/si/safeer/tv/os/JavnaLastPoizvedba.kt" \
    "$SRC/si/safeer/tv/SpletMostPravila.kt" \
    "$SRC/si/safeer/tv/tv/PredajaStrani.kt" \
    "$SRC/si/safeer/tv/cast/ObvestiloZaslona.kt" \
    "$SRC/si/safeer/tv/cast/PoDeljenju.kt" \
    "$SRC/si/safeer/tv/link/PretokKonec.kt" \
    "$SRC/si/safeer/tv/link/DeljenjePravila.kt" \
    "$SRC/si/safeer/tv/link/OmejenoNastavitvePravila.kt" \
    "$SRC/si/safeer/tv/os/HlsPopravek.kt" \
    "$SRC/si/safeer/tv/os/KanaliPravila.kt" \
    "$SRC/si/safeer/tv/os/IptvPravila.kt" \
    "$SRC/si/safeer/tv/os/PredpomnilnikPravila.kt" \
    "$SRC/si/safeer/tv/os/BesedilaPravila.kt" \
    "$SRC/si/safeer/tv/os/ZaslonPovecava.kt" \
    "$SRC/si/safeer/tv/os/ZaslonPogled.kt" \
    "$SRC/si/safeer/tv/os/ZaslonKodek.kt" \
    "$SRC/si/safeer/tv/os/OmrezjePravila.kt" \
    "$SRC/si/safeer/tv/os/PotDoNaprave.kt" \
    "$SRC/si/safeer/tv/os/ZvokPravila.kt" \
    "$SRC/si/safeer/tv/os/ProsnjaDostopa.kt" \
    "$SRC/si/safeer/tv/os/ZaganjalnikPravila.kt" \
    "$SRC/si/safeer/tv/os/VrsticaPravila.kt" \
    "$SRC/si/safeer/tv/os/SeznamZaslonov.kt" \
    "$SRC/si/safeer/tv/link/OzjiKrogPravila.kt" \
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
    "$TEST_DIR/JavnaLastPoizvedbaTest.kt" \
    "$TEST_DIR/ObvestiloZaslonaTest.kt" \
    "$TEST_DIR/PoDeljenjuTest.kt" \
    "$TEST_DIR/PretokKonecTest.kt" \
    "$TEST_DIR/DeljenjePravilaTest.kt" \
    "$TEST_DIR/OmejenoNastavitvePravilaTest.kt" \
    "$TEST_DIR/HlsPopravekTest.kt" \
    "$TEST_DIR/KanaliPravilaTest.kt" \
    "$TEST_DIR/IptvPravilaTest.kt" \
    "$TEST_DIR/PredpomnilnikPravilaTest.kt" \
    "$TEST_DIR/BesedilaPravilaTest.kt" \
    "$TEST_DIR/ZaslonPovecavaTest.kt" \
    "$TEST_DIR/ZaslonPogledTest.kt" \
    "$TEST_DIR/ZaslonKodekTest.kt" \
    "$TEST_DIR/OmrezjePravilaTest.kt" \
    "$TEST_DIR/PotDoNapraveTest.kt" \
    "$TEST_DIR/ZvokPravilaTest.kt" \
    "$TEST_DIR/ProsnjaDostopaTest.kt" \
    "$TEST_DIR/ZaganjalnikPravilaTest.kt" \
    "$TEST_DIR/VrsticaPravilaTest.kt" \
    "$TEST_DIR/SeznamZaslonovTest.kt" \
    "$TEST_DIR/OzjiKrogPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/AplikacijePredpomnilnik.kt" \
    "$TEST_DIR/AplikacijePredpomnilnikTest.kt" \
    "$SRC/si/safeer/tv/os/SporocilaPravila.kt" \
    "$TEST_DIR/SporocilaPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/OddaljenoPravila.kt" \
    "$TEST_DIR/OddaljenoPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/MiniPredvajalnikPravila.kt" \
    "$TEST_DIR/MiniPredvajalnikPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/TorBoxPravila.kt" \
    "$TEST_DIR/TorBoxPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/DebridPravila.kt" \
    "$TEST_DIR/DebridPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/UsenetPravila.kt" \
    "$TEST_DIR/UsenetPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/SerijePravila.kt" \
    "$TEST_DIR/SerijePravilaTest.kt" \
    "$SRC/si/safeer/tv/os/NapredekKljuc.kt" \
    "$TEST_DIR/NapredekKljucTest.kt" \
    "$SRC/si/safeer/tv/os/KakovostPravila.kt" \
    "$TEST_DIR/KakovostPravilaTest.kt" \
    "$TEST_DIR/SkupniPrimeriTest.kt" \
    "$SRC/si/safeer/tv/os/PreizkusPravila.kt" \
    "$TEST_DIR/PreizkusPravilaTest.kt" \
    "$SRC/si/safeer/tv/cast/JsonLahki.kt" \
    "$SRC/si/safeer/tv/os/DodatkiProtokol.kt" \
    "$TEST_DIR/DodatkiProtokolTest.kt" \
    "$SRC/si/safeer/tv/os/DatotekePravila.kt" \
    "$TEST_DIR/DatotekePravilaTest.kt" \
    "$SRC/si/safeer/tv/os/NedelujocePravila.kt" \
    "$TEST_DIR/NedelujocePravilaTest.kt" \
    "$SRC/si/safeer/tv/os/Zasebno.kt" \
    "$TEST_DIR/ZasebnoTest.kt" \
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
java -cp "$OUT/os.jar" si.safeer.tv.os.JavnaLastPoizvedbaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.cast.ObvestiloZaslonaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.cast.PoDeljenjuTestKt
java -cp "$OUT/os.jar" si.safeer.tv.link.PretokKonecTestKt
java -cp "$OUT/os.jar" si.safeer.tv.link.DeljenjePravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.link.OmejenoNastavitvePravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.HlsPopravekTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.KanaliPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.IptvPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.PredpomnilnikPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.BesedilaPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.ZaslonPovecavaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.ZaslonPogledTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.ZaslonKodekTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.OmrezjePravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.PotDoNapraveTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.ZvokPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.ProsnjaDostopaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.ZaganjalnikPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.VrsticaPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.SeznamZaslonovTestKt
java -cp "$OUT/os.jar" si.safeer.tv.link.OzjiKrogPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.AplikacijePredpomnilnikTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.SporocilaPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.OddaljenoPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.MiniPredvajalnikPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.TorBoxPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.DebridPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.UsenetPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.SerijePravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.NapredekKljucTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.KakovostPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.PreizkusPravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.SkupniPrimeriTestKt "$TEST_DIR/skupni-primeri/debrid-serije.json"
java -cp "$OUT/os.jar" si.safeer.tv.os.DodatkiProtokolTestKt "$PROJECT_DIR/docs/DODATKI-PROTOKOL.md"
java -cp "$OUT/os.jar" si.safeer.tv.os.DatotekePravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.NedelujocePravilaTestKt
java -cp "$OUT/os.jar" si.safeer.tv.os.ZasebnoTestKt

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

# Global Link - neposredna pot od zunaj (isti protokol kot core/link_neposredno.py): oglas iz Pythona, prave vticnice.
"$KOTLINC" -J-Xmx1g \
    "$SRC/si/safeer/tv/link/NeposrednaPot.kt" \
    "$TEST_DIR/NeposrednaPotTest.kt" \
    -include-runtime -d "$OUT/neposredna.jar"
java -cp "$OUT/neposredna.jar" si.safeer.tv.link.NeposrednaPotTestKt

python3 "$TEST_DIR/preveri_tv_ikone.py"
python3 "$TEST_DIR/preveri_naslovno_vrstico.py"
python3 "$TEST_DIR/preveri_dostop_v_ozadju.py"
python3 "$TEST_DIR/preveri_preizkus_po_gradnji.py"
