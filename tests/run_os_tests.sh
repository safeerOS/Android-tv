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
    "$TEST_DIR/DiagnostikaPravilaTest.kt" \
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
    "$TEST_DIR/DebridZaporaTest.kt" \
    "$SRC/si/safeer/tv/os/UsenetPravila.kt" \
    "$TEST_DIR/UsenetPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/UsenetPonudnikPravila.kt" \
    "$TEST_DIR/UsenetPonudnikPravilaTest.kt" \
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
    "$SRC/si/safeer/tv/os/YoutubeVrstaPravila.kt" \
    "$TEST_DIR/YoutubeVrstaPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/SondaHlsPravila.kt" \
    "$TEST_DIR/SondaHlsPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/SondaVeriga.kt" \
    "$TEST_DIR/SondaVerigaTest.kt" \
    "$TEST_DIR/IptvVarovaloTest.kt" \
    "$TEST_DIR/IptvRacunTest.kt" \
    "$TEST_DIR/IptvPrijavaRacunaTest.kt" \
    "$SRC/si/safeer/tv/os/PriljubljeniKanaliPravila.kt" \
    "$TEST_DIR/PriljubljeniKanaliPravilaTest.kt" \
    "$SRC/si/safeer/tv/os/DovoljenjaPravila.kt" \
    "$TEST_DIR/DovoljenjaPravilaTest.kt" \
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
    -include-runtime -d "$OUT/os.jar"

java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.OsPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.GalerijaPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.VarnaSlikaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.PredajaStraniTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.JezikiVsebineTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.ZapiskiShrambaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.SpletMostPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.TokIzbiraTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.ObvestilaTokovTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SeznamiPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.ZacasniPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.DiagnostikaPravilaTestKt
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
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.AplikacijePredpomnilnikTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SporocilaPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.OddaljenoPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.MiniPredvajalnikPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.TorBoxPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.DebridPravilaTestKt
# Pregled 104, S1/S2 (26. 10. 2026): zavrnjen ali zaklenjen kljuc (prejem, ponoven vpis) dobi zaporo - brez novih klicev.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.DebridZaporaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.UsenetPravilaTestKt "$SRC/si/safeer/tv/os/Usenet.kt" "$SRC/si/safeer/tv/os/Debrid.kt"
# Ponudnik Usenet: ista pravila kot core/usenet_ponudnik.py (skupni primeri iz reference, naloga 111).
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.UsenetPonudnikPravilaTestKt "$TEST_DIR/skupni-primeri/usenet-ponudnik.jsonl"
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SerijePravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.NapredekKljucTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.KakovostPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.PreizkusPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SkupniPrimeriTestKt "$TEST_DIR/skupni-primeri/debrid-serije.json"
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SkupniPrimeriTestKt "$TEST_DIR/skupni-primeri/webtor-rezerva.json"
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SkupniPrimeriTestKt "$TEST_DIR/skupni-primeri/kljuci-preverba.json"
# Razclenitev seznamov M3U (iptv-m3u.json, lastnik Safeer-linux): [Not 24/7], .uk = GB, vejica v atributih.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SkupniPrimeriTestKt "$TEST_DIR/skupni-primeri/iptv-m3u.json"
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SkupniPrimeriTestKt "$TEST_DIR/skupni-primeri/iptv-racun.json"
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.DodatkiProtokolTestKt "$PROJECT_DIR/docs/DODATKI-PROTOKOL.md"
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.DatotekePravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.NedelujocePravilaTestKt
# Naloga 113: seznam YouTube - Media3 pred spletno stranjo, rok za stran, ki ne zacne igrati.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.YoutubeVrstaPravilaTestKt
python3 "$TEST_DIR/preveri_youtube_vrsta_pot.py"
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SondaHlsPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.SondaVerigaTestKt
# Lastnik, 26. 10. 2026 (nevarnost zaklepa racuna): seznam Xtream s 5000 kanali in seznam z druge naprave - nobene povezave.
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.IptvVarovaloTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.IptvRacunTestKt
# Pregled 105, S2 in S3 (26. 10. 2026): prejet vir z racunom pod nadomestkom, ena prijava na racun (ne na naslov).
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.IptvPrijavaRacunaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.PriljubljeniKanaliPravilaTestKt
java -cp "$OUT/os.jar:$OKHTTP_LIBS" si.safeer.tv.os.DovoljenjaPravilaTestKt
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
# Naloga 42: besedila v sestih jezikih, viri, kontrast teme Pokrajina, beta samo ob vklopu.
python3 "$TEST_DIR/preveri_zaganjalnik_beta.py"
