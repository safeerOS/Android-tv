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
    "$SRC/si/safeer/tv/scit/ScitStanjePravila.kt" \
    "$TEST_DIR/ScitStanjePravilaTest.kt" \
    "$SRC/si/safeer/tv/scit/ScitOdlocitev.kt" \
    "$SRC/si/safeer/tv/ScitGostitelji.kt" \
    "$SRC/si/safeer/tv/GooglePravila.kt" \
    "$SRC/si/safeer/tv/cast/JsonLahki.kt" \
    "$SRC/si/safeer/tv/scit/ScitViri.kt" \
    "$SRC/si/safeer/tv/scit/AbuseChPravila.kt" \
    "$SRC/si/safeer/tv/scit/ScitPoMeriPravila.kt" \
    "$SRC/com/safeer/threatfeed/ThreatListAgent.kt" \
    "$TEST_DIR/ScitTest.kt" \
    "$TEST_DIR/ScitPregledTest.kt" \
    "$TEST_DIR/TcpDnsTest.kt" \
    "$TEST_DIR/ScitSkupniPrimeriTest.kt" \
    "$TEST_DIR/DomenskiNaborVirTest.kt" \
    "$TEST_DIR/ScitViriTest.kt" \
    "$TEST_DIR/ScitPoMeriNaslovTest.kt" \
    -include-runtime -d "$OUT/scit.jar"

java -Xmx512m -cp "$OUT/scit.jar" si.safeer.tv.scit.ScitTestKt
# Lastnikov pregled Scita (3. in 4. 11. 2026): vrata 53, brez tuje rezerve, zasebni DNS, zadnja imena, izjeme, privzeti vklop, meni brskalnika.
java -Xmx512m -cp "$OUT/scit.jar" si.safeer.tv.scit.ScitPregledTestKt
# Skupni primeri z Linuxom (4. 11. 2026, en Scit za vse OS): ista datoteka v Safeer-linux (tests/test_scit_skupni_primeri.py).
java -Xmx512m -cp "$OUT/scit.jar" si.safeer.tv.scit.ScitSkupniPrimeriTestKt "$TEST_DIR/skupni-primeri/scit-pravila.json"
# Nabor se gradi samo ob spremembi seznamov (4. 11. 2026): kljuc vira, pretocni zapis, isti format.
java -Xmx512m -cp "$OUT/scit.jar" si.safeer.tv.scit.DomenskiNaborVirTestKt
# Proces :scit gradi samo nabor Scita (4. 11. 2026): brez EasyList, drevesa grozenj in kazala brskalnika.
java -Xmx512m -cp "$OUT/scit.jar" si.safeer.tv.scit.ScitViriTestKt "$SRC"
# Pregled Nastavitev in Scita (5. 11. 2026): prekinjen ni »Vklopljeno«, ponovni vklop, SERVFAIL brez odgovora, nabor enkrat.
java -Xmx512m -cp "$OUT/scit.jar" si.safeer.tv.scit.ScitStanjePravilaTestKt "$SRC"
# Naslov filtra po meri (15. 11. 2026, N-SCIT): isti nabor pravil kot Linux (krajevna imena, IP-literali, { }, oznake, vrata).
java -Xmx512m -cp "$OUT/scit.jar" si.safeer.tv.scit.ScitPoMeriNaslovTestKt
