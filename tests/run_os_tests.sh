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
# Sonda toka (SondaVeriga) gre prek OkHttp: preverjeni naslovi IP brez DNS rebindinga (26. 10. 2026, J2).
OKHTTP_LIBS="$PROJECT_DIR/libs/okhttp-4.12.0.jar:$PROJECT_DIR/libs/okio-jvm-3.6.0.jar"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

"$KOTLINC" -J-Xmx1g -cp "$OKHTTP_LIBS" \
    "$TEST_DIR/stubs/R.kt" \
    "$TEST_DIR/stubs/BitmapFactory.kt" \
    "$TEST_DIR/stubs/JSONObject.kt" \
    "$TEST_DIR/stubs/Sosed.kt" \
    "$TEST_DIR/stubs/Log.kt" \
    "$SRC/si/safeer/tv/os/OsPravila.kt" \
    "$SRC/si/safeer/tv/os/GalerijaPravila.kt" \
    "$SRC/si/safeer/tv/os/AplikacijePravila.kt" \
    "$SRC/si/safeer/tv/os/KompletPravila.kt" \
    "$SRC/si/safeer/tv/os/JezikiVsebine.kt" \
    "$SRC/si/safeer/tv/os/ZapiskiShramba.kt" \
    "$SRC/si/safeer/tv/os/VarnaSlika.kt" \
    "$SRC/si/safeer/tv/os/TokIzbira.kt" \
    "$SRC/si/safeer/tv/os/ObvestilaTokov.kt" \
    "$SRC/si/safeer/tv/os/SeznamiPravila.kt" \
    "$SRC/si/safeer/tv/os/ZacasniPravila.kt" \
    "$SRC/si/safeer/tv/os/DiagnostikaPravila.kt" \
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
    "$SRC/si/safeer/tv/SpanjePravila.kt" \
    "$SRC/si/safeer/tv/link/PretokKonec.kt" \
    "$SRC/si/safeer/tv/link/DeljenjePravila.kt" \
    "$SRC/si/safeer/tv/link/OmejenoNastavitvePravila.kt" \
    "$SRC/si/safeer/tv/link/VnosVklopPravila.kt" \
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
    "$SRC/si/safeer/tv/os/PripravaPravila.kt" \
    "$TEST_DIR/PripravaPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/KatalogPravila.kt" \
    "$TEST_DIR/KatalogPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/AdbLokalnoPravila.kt" \
    "$TEST_DIR/AdbLokalnoPravilaTest.kt" \
    "$TEST_DIR/OsPravilaTest.kt" \
    "$TEST_DIR/GalerijaPravilaTest.kt" \
    "$TEST_DIR/AplikacijePravilaTest.kt" \
    "$TEST_DIR/KompletPravilaTest.kt" \
    "$TEST_DIR/VarnaSlikaTest.kt" \
    "$TEST_DIR/PredajaStraniTest.kt" \
    "$TEST_DIR/JezikiVsebineTest.kt" \
    "$TEST_DIR/ZapiskiShrambaTest.kt" \
    "$TEST_DIR/SpletMostPravilaTest.kt" \
    "$TEST_DIR/TokIzbiraTest.kt" \
    "$TEST_DIR/ObvestilaTokovTest.kt" \
    "$TEST_DIR/SeznamiPravilaTest.kt" \
    "$TEST_DIR/ZacasniPravilaTest.kt" \
    "$TEST_DIR/DiagnostikaPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/DiagnostikaPredvajanjaPravila.kt" \
    "$TEST_DIR/DiagnostikaPredvajanjaPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/SejaPredvajanjaPravila.kt" \
    "$TEST_DIR/SejaPredvajanjaPravilaTest.kt" \
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
    "$TEST_DIR/SpanjePravilaTest.kt" \
    "$TEST_DIR/PretokKonecTest.kt" \
    "$TEST_DIR/DeljenjePravilaTest.kt" \
    "$TEST_DIR/DeljenjeVnosNaDaljavoTest.kt" \
    "$TEST_DIR/DeljenjeZaslonaPotTest.kt" \
    "$TEST_DIR/OmejenoNastavitvePravilaTest.kt" \
    "$TEST_DIR/VnosVklopPravilaTest.kt" \
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
    "$SRC/si/safeer/tv/os/EpizodePravila.kt" \
    "$TEST_DIR/EpizodePravilaTest.kt" \
    "$SRC/si/safeer/tv/os/AplikacijePredpomnilnik.kt" \
    "$TEST_DIR/AplikacijePredpomnilnikTest.kt" \
    "$SRC/si/safeer/tv/os/SporocilaPravila.kt" \
    "$TEST_DIR/SporocilaPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/OddaljenoPravila.kt" \
    "$TEST_DIR/OddaljenoPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/MiniPredvajalnikPravila.kt" \
    "$TEST_DIR/MiniPredvajalnikPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/Niti.kt" \
    "$SRC/si/safeer/tv/os/DomPasPravila.kt" \
    "$TEST_DIR/DomPasPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/McVidezPravila.kt" \
    "$TEST_DIR/McVidezPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/MojSeznamPravila.kt" \
    "$TEST_DIR/MojSeznamPravilaTest.kt" \
    "$TEST_DIR/NitiTest.kt" \
    "$SRC/si/safeer/tv/cast/MeshIskanjePravila.kt" \
    "$TEST_DIR/MeshIskanjePravilaTest.kt" \
    "$SRC/si/safeer/tv/os/TorBoxPravila.kt" \
    "$TEST_DIR/TorBoxPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/DebridPravila.kt" \
    "$TEST_DIR/DebridPravilaTest.kt" \
    "$TEST_DIR/DebridZaporaTest.kt" \
    "$SRC/si/safeer/tv/os/UsenetPravila.kt" \
    "$TEST_DIR/UsenetPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/UsenetPonudnikPravila.kt" \
    "$TEST_DIR/UsenetPonudnikPravilaTest.kt" \
    "$TEST_DIR/stubs/Looper.kt" \
    "$SRC/si/safeer/tv/os/UsenetYenc.kt" \
    "$SRC/si/safeer/tv/os/UsenetNzb.kt" \
    "$SRC/si/safeer/tv/os/UsenetTok.kt" \
    "$SRC/si/safeer/tv/os/UsenetRar.kt" \
    "$SRC/si/safeer/tv/os/UsenetBralnik.kt" \
    "$SRC/si/safeer/tv/os/UsenetUsbPisalec.kt" \
    "$SRC/si/safeer/tv/os/UsenetStreznik.kt" \
    "$TEST_DIR/UsenetTestnaPomoc.kt" \
    "$TEST_DIR/UsenetYencTest.kt" \
    "$TEST_DIR/UsenetNzbTest.kt" \
    "$TEST_DIR/UsenetTokTest.kt" \
    "$SRC/si/safeer/tv/os/UsenetLastniPravila.kt" \
    "$SRC/si/safeer/tv/os/UsenetGlasbaPravila.kt" \
    "$SRC/si/safeer/tv/PojavnaOknaMeja.kt" \
    "$TEST_DIR/PojavnaOknaMejaTest.kt" \
    "$TEST_DIR/UsenetGlasbaPravilaTest.kt" \
    "$TEST_DIR/UsenetLastniPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/UsenetNntp.kt" \
    "$TEST_DIR/UsenetNntpTest.kt" \
    "$SRC/si/safeer/tv/os/SerijePravila.kt" \
    "$TEST_DIR/SerijePravilaTest.kt" \
    "$SRC/si/safeer/tv/os/NaslednjaEpizodaPravila.kt" \
    "$TEST_DIR/NaslednjaEpizodaPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/NapredekKljuc.kt" \
    "$TEST_DIR/NapredekKljucTest.kt" \
    "$SRC/si/safeer/tv/os/KakovostPravila.kt" \
    "$TEST_DIR/KakovostPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/FilterViraPravila.kt" \
    "$TEST_DIR/FilterViraPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/FilterTorBoxPravila.kt" \
    "$TEST_DIR/FilterTorBoxPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/PregledNovostiPravila.kt" \
    "$TEST_DIR/PregledNovostiPravilaTest.kt" \
    "$SRC/si/safeer/tv/link/GlobalLinkPravila.kt" \
    "$TEST_DIR/GlobalLinkPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/UsenetIskanjePravila.kt" \
    "$TEST_DIR/UsenetIskanjePravilaTest.kt" \
    "$SRC/si/safeer/tv/os/UsenetPomocPravila.kt" \
    "$TEST_DIR/UsenetPomocPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/SprotnaPomocPravila.kt" \
    "$TEST_DIR/SprotnaPomocPravilaTest.kt" \
    "$SRC/si/safeer/tv/link/PretokPravila.kt" \
    "$TEST_DIR/PretokPravilaTest.kt" \
    "$SRC/si/safeer/tv/link/PretokHttp.kt" \
    "$TEST_DIR/PretokHttpTest.kt" \
    "$TEST_DIR/SkupniPrimeriTest.kt" \
    "$SRC/si/safeer/tv/os/PreizkusPravila.kt" \
    "$TEST_DIR/PreizkusPravilaTest.kt" \
    "$SRC/si/safeer/tv/cast/JsonLahki.kt" \
    "$SRC/si/safeer/tv/os/DodatkiProtokol.kt" \
    "$TEST_DIR/DodatkiProtokolTest.kt" \
    "$SRC/si/safeer/tv/os/DatotekePravila.kt" \
    "$SRC/si/safeer/tv/os/DrsenjePravila.kt" \
    "$SRC/si/safeer/tv/os/RadioOpisPravila.kt" \
    "$TEST_DIR/RadioOpisPravilaTest.kt" \
    "$TEST_DIR/DrsenjePravilaTest.kt" \
    "$TEST_DIR/DatotekePravilaTest.kt" \
    "$SRC/si/safeer/tv/os/NedelujocePravila.kt" \
    "$TEST_DIR/NedelujocePravilaTest.kt" \
    "$SRC/si/safeer/tv/os/YoutubeVrstaPravila.kt" \
    "$TEST_DIR/YoutubeVrstaPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/LahkiPoolPravila.kt" \
    "$TEST_DIR/LahkiPoolPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/SondaHlsPravila.kt" \
    "$TEST_DIR/SondaHlsPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/SondaVeriga.kt" \
    "$TEST_DIR/SondaVerigaTest.kt" \
    "$TEST_DIR/IptvVarovaloTest.kt" \
    "$TEST_DIR/IptvRacunTest.kt" \
    "$TEST_DIR/IptvPrijavaRacunaTest.kt" \
    "$SRC/si/safeer/tv/os/PriljubljeniKanaliPravila.kt" \
    "$TEST_DIR/PriljubljeniKanaliPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/MeniVsebinePravila.kt" \
    "$SRC/si/safeer/tv/os/MedijskiCenterNastavitvePravila.kt" \
    "$SRC/si/safeer/tv/os/DomovVrsticePravila.kt" \
    "$TEST_DIR/MedijskiCenterNastavitvePravilaTest.kt" \
    "$TEST_DIR/MeniVsebinePravilaTest.kt" \
    "$SRC/si/safeer/tv/os/DovoljenjaPravila.kt" \
    "$TEST_DIR/DovoljenjaPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/NastavitvePravila.kt" \
    "$SRC/si/safeer/tv/os/Preusmeritve.kt" \
    "$SRC/si/safeer/tv/os/NamestiApkPravila.kt" \
    "$TEST_DIR/NastavitvePravilaTest.kt" \
    "$SRC/si/safeer/tv/os/Zasebno.kt" \
    "$TEST_DIR/ZasebnoTest.kt" \
    "$SRC/si/safeer/tv/os/SkrivnostiPravila.kt" \
    "$TEST_DIR/SkrivnostiPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/WebtorPravila.kt" \
    "$TEST_DIR/WebtorPravilaTest.kt" \
    "$SRC/si/safeer/tv/scit/ScitIzjeme.kt" \
    "$TEST_DIR/ScitIzjemeTest.kt" \
    "$SRC/si/safeer/tv/os/UskladitevKljucevPravila.kt" \
    "$SRC/si/safeer/tv/os/NapraveKrogaPravila.kt" \
    "$TEST_DIR/UskladitevKljucevPravilaTest.kt" \
    "$TEST_DIR/NapraveKrogaPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/NamestitevPravila.kt" \
    "$TEST_DIR/NamestitevPravilaTest.kt" \
    "$SRC/si/safeer/tv/cast/SafeerLog.kt" \
    "$TEST_DIR/SafeerLogTest.kt" \
    "$SRC/si/safeer/tv/os/PokrajinaPravila.kt" \
    "$SRC/si/safeer/tv/os/VremePravila.kt" \
    "$TEST_DIR/PokrajinaPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/SporocilaHitroPravila.kt" \
    "$TEST_DIR/SporocilaHitroPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/LupaPravila.kt" \
    "$TEST_DIR/LupaPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/ZaganjalnikBetaPravila.kt" \
    "$TEST_DIR/ZaganjalnikBetaPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/TokiPravila.kt" \
    "$TEST_DIR/TokiPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/ZdravjeDodatkov.kt" \
    "$TEST_DIR/ZdravjeDodatkovTest.kt" \
    -include-runtime -d "$OUT/os.jar"

java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.OsPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.KatalogPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.GalerijaPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.AplikacijePravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" KompletPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.VarnaSlikaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.PredajaStraniTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.PojavnaOknaMejaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.JezikiVsebineTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.ZapiskiShrambaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.SpletMostPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.TokIzbiraTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.TokiPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.ZdravjeDodatkovTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.ObvestilaTokovTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SeznamiPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.ZacasniPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.DiagnostikaPravilaTestKt
# Diagnostika predvajanja (lastnik 5. 11. 2026): privzeto izklopljena, vnos brez kljucev in celih URL-jev.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.DiagnostikaPredvajanjaPravilaTestKt
# Seja predvajanja samo ob pravi spremembi (TV 5. 11. 2026: 6 stanj v 54 ms).
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SejaPredvajanjaPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.ZasebniDodatkiTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.KnjiznicaKrogaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.RazpolozljivostPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.DomPreverjanjePravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.TempoDodatkaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.ObnovaPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.IzvirniJezikTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.JavnaLastPoizvedbaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.cast.ObvestiloZaslonaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.cast.PoDeljenjuTestKt
# Brskalnik po Domov ali izhodu vrne pomnilnik (TV 3. 11. 2026: 270 MB + 65 MB izrisovalnika je ostalo).
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.SpanjePravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.link.PretokKonecTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.link.DeljenjePravilaTestKt
# Pregled 100, N1 in N2 (26. 10. 2026): »Vedno dovoli« ne po vnosu z daljave; znak pred koncem cakanja na odklep.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.link.DeljenjeVnosNaDaljavoTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.link.DeljenjeZaslonaPotTestKt "$SRC/si/safeer/tv/link/DeljenjeZaslonaStoritev.kt" "$SRC/si/safeer/tv/link/Daljinec.kt"
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.link.OmejenoNastavitvePravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.link.VnosVklopPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.HlsPopravekTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.KanaliPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.IptvPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.PredpomnilnikPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.BesedilaPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.ZaslonPovecavaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.ZaslonPogledTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.ZaslonKodekTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.OmrezjePravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.PotDoNapraveTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.ZvokPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.ProsnjaDostopaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.ZaganjalnikPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.VrsticaPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SeznamZaslonovTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.link.OzjiKrogPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.PripravaPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.AdbLokalnoPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.EpizodePravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.AplikacijePredpomnilnikTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SporocilaPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.OddaljenoPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.MiniPredvajalnikPravilaTestKt
# Revizija RAM/CPU 8. 11. 2026: bazeni niti sprostijo mirujoce niti (#8), iskanje sosedov mesh z umikom (#2).
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.NitiTestKt
# Pas na vrhu Doma MC (DomPas, Freebuff 8. 11. 2026): napisi, cas, odstotek, mere.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.DomPasPravilaTestKt
# Videz Doma MC na TV (8. 11. 2026): stolpci, fokus, naslovi seznamov, monogram postaje, ploscica dejanja, pomoc.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.McVidezPravilaTestKt
# Moj seznam (lastnik 8. 11. 2026, zgled KefinTweaks): dodaj/odstrani, meja, odstranitev po ogledu.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.MojSeznamPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.cast.MeshIskanjePravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.TorBoxPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.DebridPravilaTestKt
# Pregled 104, S1/S2 (26. 10. 2026): zavrnjen ali zaklenjen kljuc (prejem, ponoven vpis) dobi zaporo - brez novih klicev.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.DebridZaporaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.UsenetPravilaTestKt "$SRC/si/safeer/tv/os/Usenet.kt" "$SRC/si/safeer/tv/os/Debrid.kt"
# Ponudnik Usenet: ista pravila kot core/usenet_ponudnik.py (skupni primeri iz reference, naloga 111).
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.UsenetPonudnikPravilaTestKt "$TEST_DIR/skupni-primeri/usenet-ponudnik.jsonl"
# Naloga 42 G: prijava NNTP s shranjenim ponudnikom - lazni streznik na 127.0.0.1, odzivi kot core/usenet_nntp.py.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.UsenetYencTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.UsenetNzbTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.UsenetNntpTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.UsenetTokTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SkupniPrimeriTestKt "$TEST_DIR/skupni-primeri/usenet-pogon.json"
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.UsenetLastniPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.UsenetGlasbaPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SerijePravilaTestKt
# Lastnik 5. 11. 2026: ime posnetka izgine po 3 s (pavza -> play brez imena), samodejna naslednja epizoda z mejo 3
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.NaslednjaEpizodaPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.NapredekKljucTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.KakovostPravilaTestKt
# Filter vira Usenet (lastnik 5. 11. 2026): naslov v filtru samo, ce so izpolnjeni vsi pogoji za ogled
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.FilterViraPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.FilterTorBoxPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.PregledNovostiPravilaTestKt
# Pregled 9. 11. 2026: zanki Global Linka brez meje (polna vrsta posiljanja, ponovni poskusi) - meje v cistih pravilih
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.link.GlobalLinkPravilaTestKt
# Lastnik 7. 11. 2026: iskanje ob kliku - meja indeksatorja ni »ni izdaj« (razlog, ponovitev, pozaba zapisa)
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.UsenetIskanjePravilaTestKt
# Pomocnik za Usenet v Linku (lastnik 5. 11. 2026, zakon solidarnosti): isti indeksator, sme pomagati, meja osvezitev
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.UsenetPomocPravilaTestKt
# Vodja 5. 11. 2026: pravi naslov in glave vira z zetonom samo pomocniku v ozjem krogu
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SprotnaPomocPravilaTestKt
# Vodja 5. 11. 2026: pomocnik Android ne bere zanke in povezavno-lokalnih naslovov (kot Linux link_sprotno)
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.link.PretokPravilaTestKt
# Vodja 5. 11. 2026: vsak skok preusmeritve vira Pretok preverjen (302 na 127.0.0.1, DNS rebinding, glave samo izvoru)
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.link.PretokHttpTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.PreizkusPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SkupniPrimeriTestKt "$TEST_DIR/skupni-primeri/debrid-serije.json"
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SkupniPrimeriTestKt "$TEST_DIR/skupni-primeri/webtor-rezerva.json"
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SkupniPrimeriTestKt "$TEST_DIR/skupni-primeri/kljuci-preverba.json"
# Oznaka kakovosti ob imenu filma (lastnik 28. 10. 2026) - ista datoteka v Safeer-linux in Safeer-windows.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SkupniPrimeriTestKt "$TEST_DIR/skupni-primeri/kakovost-oznake.json"
# Razclenitev seznamov M3U (iptv-m3u.json, lastnik Safeer-linux): [Not 24/7], .uk = GB, vejica v atributih.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SkupniPrimeriTestKt "$TEST_DIR/skupni-primeri/iptv-m3u.json"
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SkupniPrimeriTestKt "$TEST_DIR/skupni-primeri/iptv-racun.json"
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.DodatkiProtokolTestKt "$PROJECT_DIR/docs/DODATKI-PROTOKOL.md"
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.DatotekePravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.DrsenjePravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.RadioOpisPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.NedelujocePravilaTestKt
# Naloga 113: seznam YouTube - Media3 pred spletno stranjo, rok za stran, ki ne zacne igrati.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.YoutubeVrstaPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.LahkiPoolPravilaTestKt
python3 "$TEST_DIR/preveri_youtube_vrsta_pot.py"
python3 "$TEST_DIR/preveri_placljivi_kanali.py"
python3 "$TEST_DIR/preveri_glasba_pregled.py"
python3 "$TEST_DIR/preveri_kljuci_sorodnik.py"
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SondaHlsPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SondaVerigaTestKt
# Lastnik, 26. 10. 2026 (nevarnost zaklepa racuna): seznam Xtream s 5000 kanali in seznam z druge naprave - nobene povezave.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.IptvVarovaloTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.IptvRacunTestKt
# Pregled 105, S2 in S3 (26. 10. 2026): prejet vir z racunom pod nadomestkom, ena prijava na racun (ne na naslov).
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.IptvPrijavaRacunaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.PriljubljeniKanaliPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.MedijskiCenterNastavitvePravilaTestKt
# Meni vsebine, prazna Glasba, Nedavno in vrstice Dom (lastnik 6. 11. 2026 04:34-04:56).
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.MeniVsebinePravilaTestKt "$PROJECT_DIR"
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.DovoljenjaPravilaTestKt
# Pregled Nastavitev in Scita (5. 11. 2026): meje branja, skoki preusmeritev, fokus, diagnostika, Safeer Vnos.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.NastavitvePravilaTestKt "$SRC"
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.ZasebnoTestKt
# Webtor rezerva (25. 10. 2026): skrivni naslovi dodatkov, pravila Webtor, izjeme Scita.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SkrivnostiPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.WebtorPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.scit.ScitIzjemeTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.UskladitevKljucevPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.NamestitevPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.cast.SafeerLogTestKt
# Naloga 42 A (27. 10. 2026): nov TV zaganjalnik (beta) - tema Pokrajina, ura in datum v jeziku vmesnika, vreme.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.PokrajinaPravilaTestKt
# Naloga 42 C: Sporocila na hitro (kopiraj, odgovori, poslji kopirano) in tiho opozorilo brez ustavitve predvajanja.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SporocilaHitroPravilaTestKt
# Naloga 42 D: naprave ozjega kroga (ista pot kot kartica Zaslon), zaupanje se uskladi samo v krogu, nikoli iz sporocila.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.NapraveKrogaPravilaTestKt
# Naloga 42 E: ena razclemba ukazov lupe in glasu (naprava po imenu v krogu, vreme, splet), zadetki in predlogi.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.LupaPravilaTestKt
# Naloga 42 F: beta (pripona -beta.N, stikalo v Nastavitvah, vrnitev brez izgube nastavitev).
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.ZaganjalnikBetaPravilaTestKt

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

# Datoteke telefona v Linku (LinkDatotekePravila): deli velike galerije, datum posnetka, raven dostopa (delni dostop
# Androida 14), navidezne mape, poti fs: (Android/data, skrite, povezave ven) in slicice (rob, ETag, predpomnilnik).
"$KOTLINC" -J-Xmx1g \
    "$SRC/si/safeer/tv/link/LinkDatotekePravila.kt" \
    "$TEST_DIR/LinkDatotekePravilaTest.kt" \
    -include-runtime -d "$OUT/linkdatoteke.jar"
java -cp "$OUT/linkdatoteke.jar" si.safeer.tv.link.LinkDatotekePravilaTestKt

# Pregled 99, F1, V1, V2: kopija brez lokacije (predpona zacasne datoteke, XMP v JPEG/PNG/WebP), vrste z lokacijo po glavi
# (TIFF/RAW, Matroska, AVI, QuickTime) in neznane slike niso NEPOSREDNO.
"$KOTLINC" -J-Xmx1g \
    "$SRC/si/safeer/tv/link/LinkDatotekePravila.kt" \
    "$TEST_DIR/LinkDatotekeLokacijaTest.kt" \
    -include-runtime -d "$OUT/linklokacija.jar"
java -cp "$OUT/linklokacija.jar" si.safeer.tv.link.LinkDatotekeLokacijaTestKt

# Pregled 26 (T1): zahteve toka Webtor prek OkHttp - brez preusmeritev, samo preverjeni javni naslovi IP (brez DNS
# rebindinga), pripet DNS za predvajalnik; lokalni streznik HTTP iz JDK.
WEBTOR_LIBS="$PROJECT_DIR/libs/okhttp-4.12.0.jar:$PROJECT_DIR/libs/okio-jvm-3.6.0.jar"
"$KOTLINC" -J-Xmx1g -cp "$WEBTOR_LIBS" \
    "$SRC/si/safeer/tv/os/WebtorPravila.kt" \
    "$SRC/si/safeer/tv/os/WebtorHttp.kt" \
    "$TEST_DIR/WebtorHttpTest.kt" \
    -include-runtime -d "$OUT/webtorhttp.jar"
java -cp "$OUT/webtorhttp.jar:$WEBTOR_LIBS" si.safeer.tv.os.WebtorHttpTestKt

python3 "$TEST_DIR/preveri_tv_ikone.py"
python3 "$TEST_DIR/preveri_naslovno_vrstico.py"
python3 "$TEST_DIR/preveri_dostop_v_ozadju.py"
python3 "$TEST_DIR/preveri_preizkus_po_gradnji.py"
# Grok 60: pot vira z racunom IPTV - pravi razredi aplikacije klicejo odlocitve, ki jih dokazujejo preizkusi pravil.
python3 "$TEST_DIR/preveri_iptv_pot.py"
# Usenet: Shrani odklene tudi trajni zapah pogona (pomocnik 28. 10. 2026)
python3 "$TEST_DIR/preveri_usenet_zapah.py"
# Daljinec v brskalniku: levi rob in gumbi o piskotkih v modalnem oknu (Facebook, 28. 10. 2026)
node "$TEST_DIR/tv_levi_rob_test.js"
node "$TEST_DIR/tv_modalno_okno_test.js"
node "$TEST_DIR/tv_en_klik_test.js"
# Cloudflare preverjanje (zaprt shadow DOM) in GOR, ki ne ostane ujet v drsenju (3. 11. 2026)
node "$TEST_DIR/tv_izziv_test.js"
node "$TEST_DIR/tv_pritrjeni_robovi_test.js"
node "$TEST_DIR/tv_odrezan_cilj_test.js"
# Okno s piskotki ob strani (claude.ai/login, 3. 11. 2026): vsi gumbi dosegljivi, nic samodejno
node "$TEST_DIR/tv_plast_izbire_test.js"
# Pravi dotik samo z zetonom glavnega okvirja (pregled Gemini 3. 11. 2026)
node "$TEST_DIR/tv_zeton_dotika_test.js"
# Neodvisni pregled Groka 5. 11. 2026: izvirniki metod, dokaz tipke, en dotik, tuj okvir, zastavica, predpomnilnik
node "$TEST_DIR/tv_pregled_groka_test.js"
# Neodvisni slepi varnostni pregled 5. 11. 2026: strogi nacin, pravi dotik samo za namene, nakljucna imena izvozov
node "$TEST_DIR/tv_neodvisni_pregled_test.js"
# Nisem robot (reCAPTCHA, hCaptcha): pravi dotik samo na okvir s potrditvenim poljem pravega izvora (lastnik 5. 11. 2026)
node "$TEST_DIR/tv_nisem_robot_test.js"
# Tretji neodvisni slepi pregled 5. 11. 2026: brez pravega dotika v tuj okvir, druga preverba cilja, izvirni most, N7, N8
node "$TEST_DIR/tv_tretji_pregled_test.js"
# Posrednik predvajalnika v okvirjih (SpletniIgralec.POSREDNIK_JS): zastavica, posiljatelj ukaza, kljuc stanja
node "$TEST_DIR/igralec_posrednik_test.js"
# En fokus na kartici clanka (lastnik 5. 11. 2026 17:49, rtvslo.si: rubrika in naslov)
node "$TEST_DIR/tv_ena_kartica_test.js"
node "$TEST_DIR/tv_brez_samodejnih_piskotkov_test.js"
# Naloga 42: besedila v sestih jezikih, viri, kontrast teme Pokrajina, beta samo ob vklopu.
python3 "$TEST_DIR/preveri_zaganjalnik_beta.py"
# Pregled zaslonov Aplikacije, Datoteke, Naprave, Sporocila, Zapiski (5. 11. 2026): prenos APK in odlocitve zaslonov.
KOTLINC="$KOTLINC" bash "$TEST_DIR/run_pregled_zaslonov_tests.sh"
