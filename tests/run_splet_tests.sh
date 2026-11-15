#!/usr/bin/env bash
# Pravila brez Androida: most strani v brskalniku, Google/YouTube in predvajanje DASH po gostitelju, varnost brskalnika (namere, domaca stran, content://, TLS), ikone strani, YouTube na dotik in zvocnik v omrezju (DLNA: razclenjevanje,
# cas, glasnost; predvajalnik upravlja zvocnik - preskok, premor, konec skladbe, prevzem zvocnika).
#   KOTLINC=/pot/do/kotlinc tests/run_splet_tests.sh
set -euo pipefail
TEST_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$TEST_DIR")"
TOOLS_DIR="${SAFEER_TOOLS_DIR:-$PROJECT_DIR/.tools}"
KOTLINC="${KOTLINC:-$TOOLS_DIR/kotlinc/bin/kotlinc}"
command -v "$KOTLINC" >/dev/null 2>&1 || KOTLINC="kotlinc"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/SpletMostPravila.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/SpletVarnostPravila.kt" \
  "$PROJECT_DIR/src/main/kotlin/com/safeer/threatfeed/FilterListEngine.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/GooglePravila.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/tv/GoogleTipke.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/tv/DashPravila.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/link/QrPravila.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/SpletIkonePravila.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/YoutubeNaDotik.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/os/DlnaPravila.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/os/ZvocnikPravila.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/PdfPregledovalnikPravila.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/KazalecPravila.kt" "$TEST_DIR/KazalecPravilaTest.kt" \
  "$TEST_DIR/KazalecPopravki3Test.kt" "$TEST_DIR/KazalecPopravki3NacinTest.kt" "$TEST_DIR/KazalecPopravki4Test.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/DashVarnostPravila.kt" "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/ProfilStraniPravila.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/UsmerjevalnikTipkPravila.kt" "$TEST_DIR/PopravkiTv2Test.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/KazalecGibanje.kt" "$TEST_DIR/KazalecGibanjeTest.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/KazalecOblika.kt" "$TEST_DIR/KazalecOblikaTest.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/AplikacijaPovezave.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/OmniboxTipke.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/TvTipke.kt" \
  "$PROJECT_DIR/src/main/kotlin/si/safeer/tv/ZavihkiPravila.kt" "$TEST_DIR/ZavihkiPravilaTest.kt" \
  "$TEST_DIR/SpletMostPravilaTest.kt" "$TEST_DIR/SpletVarnostPravilaTest.kt" "$TEST_DIR/GooglePravilaTest.kt" "$TEST_DIR/GoogleTipkeTest.kt" "$TEST_DIR/DashPravilaTest.kt" "$TEST_DIR/QrPravilaTest.kt" "$TEST_DIR/SpletIkonePravilaTest.kt" "$TEST_DIR/YoutubeNaDotikTest.kt" "$TEST_DIR/DlnaPravilaTest.kt" "$TEST_DIR/ZvocnikPravilaTest.kt" "$TEST_DIR/PdfPregledovalnikPravilaTest.kt" "$TEST_DIR/AplikacijaPovezaveTest.kt" "$TEST_DIR/OmniboxTipkeTest.kt" "$TEST_DIR/TvTipkeTest.kt" \
  -include-runtime -d "$OUT/splet.jar"
java -cp "$OUT/splet.jar" si.safeer.tv.SpletMostPravilaTestKt
java -cp "$OUT/splet.jar" si.safeer.tv.ZavihkiPravilaTestKt "$PROJECT_DIR/src/main/kotlin"
java -cp "$OUT/splet.jar" si.safeer.tv.SpletVarnostPravilaTestKt "$PROJECT_DIR/src/main/kotlin"
java -cp "$OUT/splet.jar" si.safeer.tv.GooglePravilaTestKt
java -cp "$OUT/splet.jar" si.safeer.tv.GoogleTipkeTestKt
java -cp "$OUT/splet.jar" si.safeer.tv.DashPravilaTestKt
java -cp "$OUT/splet.jar" si.safeer.tv.link.QrPravilaTestKt
java -cp "$OUT/splet.jar" si.safeer.tv.SpletIkonePravilaTestKt
java -cp "$OUT/splet.jar" si.safeer.tv.YoutubeNaDotikTestKt
java -cp "$OUT/splet.jar" si.safeer.tv.os.DlnaPravilaTestKt
java -cp "$OUT/splet.jar" si.safeer.tv.os.ZvocnikPravilaTestKt
java -cp "$OUT/splet.jar" si.safeer.tv.PdfPregledovalnikPravilaTestKt
java -cp "$OUT/splet.jar" si.safeer.tv.KazalecPravilaTestKt
# Popravki slepega pregleda TV3 (15. 11. 2026): N1 gostitelj brez java.net.URI, N2 bliznjice ogleda, N5/N6/I4 pravila kazalca.
java -cp "$OUT/splet.jar" si.safeer.tv.KazalecPopravki3TestKt
java -cp "$OUT/splet.jar" si.safeer.tv.KazalecPopravki3NacinTestKt
# Popravki slepega pregleda TV4 (15. 11. 2026): TV4-2 zastarela zastavica OK, TV4-3 YouTube za TV po shemi, gostitelju in poti.
java -cp "$OUT/splet.jar" si.safeer.tv.KazalecPopravki4TestKt
# Popravki TV-2 (15. 11. 2026): piskotki DASH samo za cilj, deljeni zaslon, laurl brez ReDoS, generacija, Nazaj po sloju, OK na ogledu.
java -cp "$OUT/splet.jar" si.safeer.tv.PopravkiTv2TestKt
# Isti popravki na pravem DashPrevzem.kt (nadomestki Androida, lokalni streznik HTTPS).
bash "$TEST_DIR/run_dash_jvm_test.sh"
# Gibanje kazalca kot cista funkcija casa (Ai Lina tv browser, 14. 11. 2026): korak, pospesek, potisk ob rob, varovalo.
java -cp "$OUT/splet.jar" si.safeer.tv.KazalecGibanjeTestKt
# Oblika kazalca: puscica s tocko klika v konici, berljivost, stanja, rob okna (lastnik 15. 11. 2026).
java -cp "$OUT/splet.jar" si.safeer.tv.KazalecOblikaTestKt
java -cp "$OUT/splet.jar" si.safeer.tv.AplikacijaPovezaveTestKt
# Daljinec v plosci predlogov (TV 3. 11. 2026: predloga ni bilo mogoce izbrati).
java -cp "$OUT/splet.jar" si.safeer.tv.OmniboxTipkeTestKt
java -cp "$OUT/splet.jar" si.safeer.tv.TvTipkeTestKt
# Fokus na spletni strani v TV brskalniku (pregled fokusa 5. 11. 2026): en fokus, zavrnjen cilj, vstop v plast, varnost OK.
node "$TEST_DIR/tv_pregled_fokusa_test.js"
