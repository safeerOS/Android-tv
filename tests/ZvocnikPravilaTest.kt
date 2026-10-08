package si.safeer.tv.os

import si.safeer.tv.os.ZvocnikPravila.Dejanje

private fun preveri(pogoj: Boolean, sporocilo: String) = check(pogoj) { sporocilo }

/** Predvajalnik upravlja zvocnik: pravila iz meritev na pravem zvocniku (7. 10. 2026). */
fun main() {
    val p = ZvocnikPravila
    val igra = "PLAYING"; val premor = "PAUSED_PLAYBACK"; val prehod = "TRANSITIONING"; val stoji = "STOPPED"

    // --- zacetek skladbe: dokler zvocnik ne potrdi predvajanja, cas stoji
    var s = p.zacetek(1_000L)
    preveri(s.zeliIgrati && !s.igral && p.polozajZdaj(s, 1_000L) == 0L && p.polozajZdaj(s, 9_000L) == 0L, "zacetek stoji pri 0")
    preveri(p.intervalMs(s, 1_500L) == p.INTERVAL_ZAGON_MS, "ob zagonu odcitava pogosto")
    var o = p.poOdcitku(s, prehod, 0L, 0L, 1_500L, true, false)
    preveri(o.dejanje == Dejanje.NIC && !o.stanje.igral, "prehod se ni predvajanje")
    o = p.poOdcitku(o.stanje, igra, 0L, 200_000L, 2_000L, true, false)
    s = o.stanje
    preveri(o.dejanje == Dejanje.NIC && s.igral && s.trajanjeMs == 200_000L, "predvajanje potrjeno")
    preveri(p.polozajZdaj(s, 2_000L) == 0L && p.polozajZdaj(s, 5_000L) == 3_000L, "po potrditvi cas tece: ${p.polozajZdaj(s, 5_000L)}")
    preveri(p.intervalMs(s, 5_000L) == p.INTERVAL_IGRA_MS, "med predvajanjem redni odcitki")

    // --- zvocnik javlja cele sekunde: odcitek znotraj sekunde ne premakne casa nazaj, velik odmik pa velja
    val enako = p.poOdcitku(s, igra, 3_000L, 200_000L, 5_600L, true, false).stanje     // nas cas 3,6 s, zvocnik »3«
    preveri(enako.polozajMs == s.polozajMs && enako.veljaOd == s.veljaOd, "odcitek v isti sekundi nicesar ne spremeni")
    val odmik = p.poOdcitku(s, igra, 30_000L, 200_000L, 5_600L, true, false).stanje    // nekdo je premaknil na zvocniku
    preveri(p.polozajZdaj(odmik, 5_600L) == 30_000L && p.polozajZdaj(odmik, 6_600L) == 31_000L, "velik odmik velja")
    // odcitek, narocen pred nasim zadnjim ukazom, je zastarel
    preveri(p.poOdcitku(s, premor, 99_000L, 1L, 9_000L, false, false).stanje.copy(slisanMs = s.slisanMs) == s, "zastarel odcitek ne velja")

    // --- premor: cas zamrzne takoj; odcitki tik po ukazu (zvocnik se javlja staro) ga ne vrnejo v predvajanje
    s = p.premor(s, 10_000L)
    preveri(!s.zeliIgrati && p.polozajZdaj(s, 10_000L) == 8_000L && p.polozajZdaj(s, 60_000L) == 8_000L, "premor zamrzne cas")
    preveri(p.intervalMs(s, 11_000L) == p.INTERVAL_PREMOR_MS, "med premorom redkejsi odcitki")
    preveri(!p.poOdcitku(s, igra, 8_000L, 200_000L, 10_500L, true, false).stanje.zeliIgrati, "star odcitek tik po premoru ne nadaljuje")
    preveri(!p.poOdcitku(s, premor, 8_000L, 200_000L, 14_000L, true, false).stanje.zeliIgrati, "premor ostane premor")
    // nekdo nadaljuje na zvocniku samem (po miru; premor je zvocnik prej potrdil): sledimo zvocniku
    val zunaj = p.poOdcitku(p.poOdcitku(s, premor, 8_000L, 200_000L, 13_000L, true, false).stanje, igra, 9_000L, 200_000L, 14_000L, true, false)
    preveri(zunaj.stanje.zeliIgrati && zunaj.dejanje == Dejanje.NIC && p.polozajZdaj(zunaj.stanje, 15_000L) == 10_000L, "nadaljevanje od zunaj")

    // --- nadaljevanje brez preskoka
    o = p.nadaljuj(s, 20_000L)
    preveri(o.dejanje == Dejanje.NIC && o.stanje.zeliIgrati && p.polozajZdaj(o.stanje, 20_000L) == 8_000L, "nadaljuj")
    preveri(p.polozajZdaj(o.stanje, 20_000L + p.NADALJEVANJE_MS + 1_000L) == 9_000L, "po nadaljevanju cas tece")
    s = o.stanje

    // --- preskok MED PREDVAJANJEM: gre takoj; zaslon kaze cilj, dokler zvok ne pristane (~1,8 s)
    o = p.preskok(s, 60_000L, 30_000L)
    s = o.stanje
    preveri(o.dejanje == Dejanje.PRESKOK && o.ciljMs == 60_000L, "preskok med predvajanjem gre takoj")
    preveri(p.polozajZdaj(s, 30_100L) == 60_000L && p.polozajZdaj(s, 30_000L + p.PRISTANEK_MS) == 60_000L, "do pristanka kaze cilj")
    preveri(p.polozajZdaj(s, 30_000L + p.PRISTANEK_MS + 1_000L) == 61_000L, "po pristanku tece od cilja")
    preveri(p.poOdcitku(s, igra, 20_000L, 200_000L, 30_900L, true, false).stanje.polozajMs == 60_000L, "odcitek med pristajanjem ne velja")
    preveri(p.poOdcitku(s, stoji, 0L, 0L, 30_900L, true, false).dejanje == Dejanje.NIC, "prehodni STOPPED tik po ukazu ni konec")
    // meje: ne cez konec (zadnji 1,5 s) in ne pred zacetek
    preveri(p.preskok(s, 999_999L, 40_000L).ciljMs == 200_000L - p.ROB_KONCA_MS, "preskok ne gre cez konec")
    preveri(p.preskok(s, -5_000L, 40_000L).ciljMs == 0L, "preskok ne gre pred zacetek")

    // --- preskok MED PREMOROM: zvocnik bi ga izvedel narobe (~5 s prezgodaj), zato pocaka na nadaljevanje
    s = p.premor(s, 50_000L)
    val predPreskokom = p.polozajZdaj(s, 50_000L)
    o = p.preskok(s, 120_000L, 51_000L)
    preveri(o.dejanje == Dejanje.NIC && o.stanje.cakajociPreskokMs == 120_000L && !o.stanje.zeliIgrati, "preskok med premorom pocaka")
    preveri(p.polozajZdaj(o.stanje, 55_000L) == 120_000L, "zaslon ze kaze cilj")
    // +10 s se sesteva na cakajoci cilj
    o = p.preskok(o.stanje, p.polozajZdaj(o.stanje, 56_000L) + 10_000L, 56_000L)
    preveri(o.dejanje == Dejanje.NIC && o.stanje.cakajociPreskokMs == 130_000L, "zaporedni preskoki med premorom se sestejejo")
    // odcitek med premorom cakajocega cilja ne povozi
    preveri(p.poOdcitku(o.stanje, premor, predPreskokom, 200_000L, 58_000L, true, false).stanje.cakajociPreskokMs == 130_000L, "odcitek ne povozi cilja")
    o = p.nadaljuj(o.stanje, 60_000L)
    s = o.stanje
    preveri(o.dejanje == Dejanje.PRESKOK && o.ciljMs == 130_000L && s.zeliIgrati && s.cakajociPreskokMs == -1L, "ob nadaljevanju gre preskok")
    val pristane = 60_000L + p.PREMOR_PRED_PRESKOKOM_MS + p.PRISTANEK_MS
    preveri(p.polozajZdaj(s, 60_100L) == 130_000L && p.polozajZdaj(s, pristane + 1_000L) == 131_000L, "po nadaljevanju s preskokom")

    // --- neznano trajanje (radio, prenos v zivo): preskoka ni
    val zivo = p.poOdcitku(p.zacetek(0L), igra, 0L, 0L, 1_000L, true, true).stanje
    o = p.preskok(zivo, 60_000L, 5_000L)
    preveri(o.dejanje == Dejanje.NIC && o.stanje == zivo, "v zivo ni preskoka")

    // --- prenos na zvocnik sredi skladbe: nadaljuje na istem mestu (Play, potrditev, preskok)
    s = p.zacetek(1_000L, 90_000L, 200_000L)
    preveri(p.polozajZdaj(s, 1_000L) == 90_000L && s.trajanjeMs == 200_000L, "ob prenosu zaslon kaze mesto s telefona")
    o = p.poOdcitku(s, igra, 0L, 200_000L, 2_000L, true, false)
    preveri(o.dejanje == Dejanje.PRESKOK && o.ciljMs == 90_000L && o.stanje.cakajociPreskokMs == -1L, "ko stece, gre preskok na mesto")
    val pristane2 = 2_000L + p.PREMOR_PRED_PRESKOKOM_MS + p.PRISTANEK_MS
    preveri(p.polozajZdaj(o.stanje, 2_100L) == 90_000L && p.polozajZdaj(o.stanje, pristane2 + 1_000L) == 91_000L, "po prenosu tece od mesta")
    preveri(p.zacetek(1_000L, 3_000L).cakajociPreskokMs == -1L, "prvih nekaj sekund ne prenasamo (zacne od zacetka)")
    preveri(p.odPolozaja(90_000L, 200_000L, false) == 90_000L && p.odPolozaja(3_000L, 200_000L, false) == 0L, "mesto za prenos")
    preveri(p.odPolozaja(199_500L, 200_000L, false) == 0L && p.odPolozaja(90_000L, 0L, true) == 0L, "tik pred koncem in v zivo od zacetka")
    // premor, preden skladba stece: mesto pocaka, preskok gre sele ob nadaljevanju
    s = p.premor(p.zacetek(1_000L, 90_000L, 200_000L), 1_200L)
    o = p.poOdcitku(s, igra, 0L, 200_000L, 1_500L, true, false)
    preveri(o.dejanje == Dejanje.PREMOR && o.stanje.igral && o.stanje.cakajociPreskokMs == 90_000L, "ko stece, dobi premor (ne preskoka)")
    preveri(!p.poOdcitku(o.stanje, igra, 1_000L, 200_000L, 2_500L, true, false).stanje.zeliIgrati, "tik po tem premoru odcitek ne nadaljuje")
    o = p.nadaljuj(o.stanje, 6_000L)
    preveri(o.dejanje == Dejanje.PRESKOK && o.ciljMs == 90_000L, "po nadaljevanju gre preskok na mesto")

    // --- premor od zunaj (daljinec zvocnika)
    s = p.poOdcitku(p.zacetek(0L), igra, 0L, 200_000L, 1_000L, true, false).stanje
    o = p.poOdcitku(s, premor, 42_000L, 200_000L, 43_500L, true, false)
    preveri(!o.stanje.zeliIgrati && p.polozajZdaj(o.stanje, 60_000L) == 42_000L, "premor od zunaj")

    // --- konec skladbe ali je zvocnik prevzel kdo drug
    s = p.poOdcitku(p.zacetek(0L), igra, 0L, 200_000L, 1_000L, true, false).stanje      // tece od 1,0 s
    preveri(p.poOdcitku(s.copy(slisanMs = 198_000L), stoji, 0L, 0L, 199_500L, true, false).dejanje == Dejanje.KONEC_SKLADBE, "STOPPED ob koncu = konec skladbe")
    preveri(p.poOdcitku(s.copy(slisanMs = 240_000L), stoji, 0L, 0L, 280_000L, true, false).dejanje == Dejanje.KONEC_SKLADBE, "odcitek kmalu po koncu = konec skladbe")
    preveri(p.poOdcitku(s, stoji, 0L, 0L, 60_000L, true, false).dejanje == Dejanje.PREVZET, "STOPPED sredi skladbe = zvocnik je prevzel kdo drug")
    preveri(p.poOdcitku(s, "NO_MEDIA_PRESENT", 0L, 0L, 60_000L, true, false).dejanje == Dejanje.PREVZET, "brez vira sredi skladbe = prevzet")
    preveri(p.poOdcitku(p.premor(s, 199_500L), stoji, 0L, 0L, 205_000L, true, false).dejanje == Dejanje.PREVZET, "STOPPED med premorom ni konec skladbe")
    val radio = p.poOdcitku(p.zacetek(0L), igra, 0L, 0L, 1_000L, true, true).stanje
    preveri(p.poOdcitku(radio, stoji, 0L, 0L, 60_000L, true, true).dejanje == Dejanje.PREVZET, "radio se ne »konca«")
    preveri(p.poOdcitku(radio, stoji, 0L, 0L, 60_000L, true, false).dejanje == Dejanje.KONEC_SKLADBE, "skladba brez znanega trajanja: konec")
    preveri(p.intervalMs(s, 199_500L) == 1_800L && p.intervalMs(s, 250_000L) == 300L, "ob koncu skladbe odcita takoj: ${p.intervalMs(s, 199_500L)}")

    // --- skladba ne stece (zvocnik je ne zmore): po nekaj odcitkih odnehamo
    s = p.zacetek(0L)
    for (i in 1 until p.NAJVEC_BREZ_ZAGONA) { o = p.poOdcitku(s, stoji, 0L, 0L, 500L * i, true, false); s = o.stanje; preveri(o.dejanje == Dejanje.NIC, "se cakamo $i") }
    preveri(p.poOdcitku(s, stoji, 0L, 0L, 9_000L, true, false).dejanje == Dejanje.NI_ZACELO, "ni steklo")
    preveri(p.poOdcitku(s, prehod, 0L, 0L, 9_000L, true, false).stanje.brezZagona == 0, "nalaganje ni neuspeh")

    // --- glasnost: navzgor najvec za korak naenkrat (drzana tipka ne sme poskociti), navzdol brez omejitve
    preveri(p.novaGlasnost(24, 2, 100) == 26 && p.novaGlasnost(24, 30, 100) == 24 + p.najvecDvig(100), "glasnost navzgor omejena")
    preveri(p.novaGlasnost(24, -30, 100) == 0 && p.novaGlasnost(99, 4, 100) == 100 && p.novaGlasnost(10, 2, 30) == 11, "glasnost meje")
    preveri(p.glasnostIzKretnje(24, 0.5f, 100) == 24 + p.obsegKretnje(100) / 2 && p.glasnostIzKretnje(24, -1f, 100) == 0, "kretnja")
    preveri(p.glasnostIzKretnje(90, 1f, 100) == 100, "kretnja ne gre cez najvec")
    // izbrana glasnost (drsnik, kretnja): navzgor po korakih, navzdol takoj
    preveri(p.korakProtiCilju(24, 60, 100) == 24 + p.najvecDvig(100) && p.korakProtiCilju(24, 26, 100) == 26, "proti cilju navzgor po korakih")
    // koraki sledijo lestvici zvocnika (0..100, 0..40, 0..15): na kratki lestvici isti korak pomeni veliko vec
    preveri(p.korakGlasnosti(100) == 2 && p.najvecDvig(100) == 4 && p.obsegKretnje(100) == 40, "lestvica 0..100")
    preveri(p.korakGlasnosti(40) == 1 && p.najvecDvig(40) == 2 && p.obsegKretnje(40) == 16, "lestvica 0..40")
    preveri(p.korakGlasnosti(15) == 1 && p.najvecDvig(15) == 1 && p.obsegKretnje(15) == 6, "lestvica 0..15")
    preveri(p.novaGlasnost(10, 30, 40) == 12 && p.korakProtiCilju(10, 40, 40) == 12 && p.glasnostIzKretnje(10, 1f, 40) == 26, "omejitve po lestvici")
    preveri(p.korakProtiCilju(60, 24, 100) == 24 && p.korakProtiCilju(98, 500, 100) == 100 && p.korakProtiCilju(5, -3, 100) == 0, "proti cilju navzdol takoj, meje")

    // --- prejsnja: po prvih sekundah na zacetek skladbe
    preveri(p.nazajNaZacetek(6_000L, true) && !p.nazajNaZacetek(2_000L, true) && p.nazajNaZacetek(2_000L, false), "prejsnja")

    // --- kaj sme na zvocnik
    preveri(p.primerna("https://primer.example/a.mp3", false) && p.primerna("content://media/external/audio/1", false), "primerna")
    preveri(!p.primerna("https://primer.example/a.mp4", true) && !p.primerna("https://192.168.1.20:8443/d/x.mp3", false), "video in pripeti vir ne")

    // --- budnost naprave: samo, kadar mora ta naprava med predvajanjem kaj narediti
    val tece = p.poOdcitku(p.zacetek(0L), igra, 0L, 200_000L, 1_000L, true, false).stanje
    preveri(p.potrebujeBudnost(tece, true, false, false, false, 2_000L) && p.potrebujeBudnost(tece, false, true, false, false, 2_000L), "budnost: datoteka te naprave ali vrsta")
    val potrjenPremor = p.poOdcitku(p.premor(tece, 5_000L), premor, 4_000L, 200_000L, 8_000L, true, false).stanje
    preveri(!p.potrebujeBudnost(tece, false, false, false, false, 2_000L) && !p.potrebujeBudnost(potrjenPremor, true, true, false, true, 8_000L), "budnost: brez dela in (potrjen) premor ne")
    preveri(!p.potrebujeBudnost(tece, false, true, true, false, 2_000L), "budnost: radio iz seznama postaj nima naslednje (tok se ne konca)")
    preveri(p.potrebujeBudnost(tece, false, false, true, true, 2_000L), "budnost: casovnik izklopa mora docakati svoj cas")

    // --- kdaj morajo seja, obvestilo in zasloni izvedeti za spremembo
    preveri(p.bistvena(tece, p.premor(tece, 5_000L), 5_000L), "premor je sprememba")
    preveri(!p.bistvena(tece, p.poOdcitku(tece, igra, 4_000L, 200_000L, 5_200L, true, false).stanje, 5_200L), "droben popravek casa ni")
    preveri(p.bistvena(tece, p.poOdcitku(tece, igra, 90_000L, 200_000L, 5_200L, true, false).stanje, 5_200L), "skok casa je")

    // ================= po neodvisnem pregledu (krog 121) =================
    // --- nadaljuj med predvajanjem ne sme premakniti casa nazaj; premor med premorom ne spremeni nicesar
    s = p.poOdcitku(p.zacetek(0L), igra, 0L, 200_000L, 1_000L, true, false).stanje
    o = p.nadaljuj(s, 50_000L)
    preveri(o.stanje == s && o.dejanje == Dejanje.NIC && p.polozajZdaj(o.stanje, 50_000L) == 49_000L, "nadaljuj med predvajanjem je brez ucinka")
    val naPremoru = p.premor(s, 60_000L)
    preveri(p.premor(naPremoru, 70_000L) == naPremoru, "premor med premorom je brez ucinka")

    // --- cakajoci preskok se izvede tudi, ce je prvi odcitek po zagonu »premor« (sicer bi obvisel za vedno)
    s = p.poOdcitku(p.zacetek(0L, 90_000L, 200_000L), premor, 0L, 200_000L, 600L, true, false).stanje
    preveri(s.cakajociPreskokMs == 90_000L && s.zeliIgrati, "cilj se caka")
    o = p.poOdcitku(s, igra, 1_000L, 200_000L, 1_200L, true, false)
    preveri(o.dejanje == Dejanje.PRESKOK && o.ciljMs == 90_000L && o.stanje.cakajociPreskokMs == -1L, "cakajoci preskok se izvede ob prvem predvajanju")

    // --- neznano ali prazno stanje prenosa ni ne konec ne prevzem
    s = p.poOdcitku(p.zacetek(0L), igra, 0L, 200_000L, 1_000L, true, false).stanje
    o = p.poOdcitku(s, "RECORDING", 0L, 0L, 60_000L, true, false)
    preveri(o.dejanje == Dejanje.NIC && o.stanje.copy(slisanMs = s.slisanMs) == s, "neznano stanje ne spremeni nicesar")
    preveri(p.poOdcitku(s, "", 0L, 0L, 60_000L, true, false).dejanje == Dejanje.NIC, "prazno stanje ne spremeni nicesar")

    // --- radio: trajanje, ki ga zvocnik morda javi, ne velja (ni preskoka, ni »konca«, redni odcitki)
    val radio2 = p.poOdcitku(p.zacetek(0L), igra, 0L, 30_000L, 1_000L, true, true).stanje
    preveri(radio2.trajanjeMs == 0L && p.preskok(radio2, 10_000L, 5_000L).dejanje == Dejanje.NIC, "radio nima trajanja")
    preveri(p.intervalMs(radio2, 40_000L) == p.INTERVAL_IGRA_MS, "radio: redni odcitki")
    preveri(p.poOdcitku(radio2, stoji, 0L, 0L, 40_000L, true, true).dejanje == Dejanje.PREVZET, "radio se ne konca niti z javljenim trajanjem")
    // javljen polozaj cez javljeno trajanje: trajanje ne velja (sicer bi zvocnik sprasevali brez prestanka)
    s = p.poOdcitku(p.zacetek(0L), igra, 0L, 10_000L, 1_000L, true, false).stanje
    s = p.poOdcitku(s, igra, 60_000L, 10_000L, 61_000L, true, false).stanje
    preveri(s.trajanjeMs == 0L && p.intervalMs(s, 62_000L) == p.INTERVAL_IGRA_MS, "napacno trajanje ne velja: ${s.trajanjeMs}")

    // --- skladba neznanega trajanja, ki se ustavi takoj po zacetku, se ni »koncala« (sicer bi jo posiljali v krogu)
    val kratka = p.poOdcitku(p.zacetek(0L), igra, 0L, 0L, 1_000L, true, false).stanje
    preveri(p.poOdcitku(kratka, stoji, 0L, 0L, 4_000L, true, false).dejanje == Dejanje.PREVZET, "takoj ustavljena ni konec")
    preveri(p.poOdcitku(kratka, stoji, 0L, 0L, 30_000L, true, false).dejanje == Dejanje.KONEC_SKLADBE, "po daljsem predvajanju je konec")

    // --- nalaganje brez konca: po roku odnehamo (ne vrtimo se v nedogled)
    s = p.zacetek(0L)
    o = p.poOdcitku(s, prehod, 0L, 0L, p.NAJVEC_ZAGON_MS - 1_000L, true, false)
    preveri(o.dejanje == Dejanje.NIC, "se nalaga")
    preveri(p.poOdcitku(o.stanje, prehod, 0L, 0L, p.NAJVEC_ZAGON_MS + 1_000L, true, false).dejanje == Dejanje.NI_ZACELO, "nalaganje brez konca")
    preveri(p.poOdcitku(o.stanje, "", 0L, 0L, p.NAJVEC_ZAGON_MS + 1_000L, true, false).dejanje == Dejanje.NI_ZACELO, "brez stanja in brez zagona")

    // --- skladba, nalozena med premorom (naprej/nazaj med premorom): zvocnik caka; ustavljen zvocnik takrat ni neuspeh
    s = p.zacetek(0L, 0L, 0L, false)
    preveri(!s.zeliIgrati && p.intervalMs(s, 1_000L) == p.INTERVAL_PREMOR_MS, "nalozena na premoru")
    for (i in 1..20) { o = p.poOdcitku(s, stoji, 0L, 0L, 4_000L * i, true, false); s = o.stanje; preveri(o.dejanje == Dejanje.NIC, "na premoru ustavljen zvocnik ni neuspeh") }
    o = p.nadaljuj(s, 100_000L)
    preveri(o.stanje.zeliIgrati && o.dejanje == Dejanje.NIC, "nadaljevanje jo zazene")
    preveri(p.poOdcitku(o.stanje, prehod, 0L, 0L, 100_000L + p.NAJVEC_ZAGON_MS - 1_000L, true, false).dejanje == Dejanje.NIC, "rok zagona tece od nadaljevanja")
    o = p.poOdcitku(o.stanje, igra, 0L, 180_000L, 101_000L, true, false)
    preveri(o.stanje.igral && p.polozajZdaj(o.stanje, 103_000L) == 2_000L, "stece in cas tece")

    // ================= po drugem pregledu pred izdajo (krog 121: F2, F3, F4, F6, F7) =================
    // --- premor mora zvocnik POTRDITI: dokler ga ne, ga posljemo znova; po nekaj poskusih zvocnik ustavimo (casovnik izklopa!)
    s = p.premor(p.poOdcitku(p.zacetek(0L), igra, 0L, 200_000L, 1_000L, true, false).stanje, 10_000L)
    preveri(s.premorNepotrjen && s.ponovitevPremora == 0, "premor caka na potrditev")
    preveri(p.poOdcitku(s, igra, 9_000L, 200_000L, 11_000L, true, false).let { it.dejanje == Dejanje.NIC && it.stanje.premorNepotrjen }, "med mirom po ukazu ne sklepamo")
    preveri(p.poOdcitku(s, prehod, 9_000L, 200_000L, 14_000L, true, false).let { it.dejanje == Dejanje.NIC && it.stanje.premorNepotrjen }, "prehod ni ne potrditev ne zavrnitev")
    var cas = 13_000L
    for (i in 1..p.NAJVEC_PONOVITEV_PREMORA) {
        o = p.poOdcitku(s, igra, 12_000L, 200_000L, cas, true, false)
        preveri(o.dejanje == Dejanje.PREMOR && !o.stanje.zeliIgrati && o.stanje.ponovitevPremora == i, "premor znova $i")
        preveri(p.poOdcitku(o.stanje, igra, 12_000L, 200_000L, cas + 500L, true, false).dejanje == Dejanje.NIC, "po ponovitvi spet mir $i")
        s = o.stanje; cas += p.MIR_PO_UKAZU_MS + 100L
    }
    o = p.poOdcitku(s, igra, 20_000L, 200_000L, cas, true, false)
    preveri(o.dejanje == Dejanje.USTAVI && !o.stanje.zeliIgrati, "premora ne izvede: ustavimo")
    // potrditev: po njej velja staro pravilo (nekdo nadaljuje na zvocniku -> sledimo)
    s = p.premor(p.poOdcitku(p.zacetek(0L), igra, 0L, 200_000L, 1_000L, true, false).stanje, 10_000L)
    val potrjen = p.poOdcitku(s, premor, 9_000L, 200_000L, 13_000L, true, false).stanje
    preveri(!potrjen.premorNepotrjen && !potrjen.zeliIgrati, "PAUSED_PLAYBACK potrdi premor")
    preveri(p.poOdcitku(potrjen, igra, 9_000L, 200_000L, 20_000L, true, false).let { it.stanje.zeliIgrati && it.dejanje == Dejanje.NIC }, "po potrjenem premoru sledimo zvocniku")
    preveri(!p.nadaljuj(s, 11_000L).stanje.premorNepotrjen, "nadaljuj preklice cakanje na potrditev")
    // premor med nalaganjem: ko skladba stece, dobi premor (prvi pravi ukaz) - ne steje kot ponovitev
    o = p.poOdcitku(p.premor(p.zacetek(0L), 300L), igra, 0L, 200_000L, 1_000L, true, false)
    preveri(o.dejanje == Dejanje.PREMOR && o.stanje.premorNepotrjen && o.stanje.ponovitevPremora == 0, "premor ob zagonu")

    // --- budnost: po premoru ostane naprava budna, dokler zvocnik premora ne potrdi (najvec PREMOR_BUDNOST_MS)
    val poPremoru = p.premor(tece, 5_000L)
    preveri(p.potrebujeBudnost(poPremoru, false, false, false, false, 6_000L), "nepotrjen premor: budna")
    preveri(!p.potrebujeBudnost(poPremoru, true, true, false, true, 5_000L + p.PREMOR_BUDNOST_MS), "nepotrjen premor: budnost ima rok")

    // --- zvocnik se ne odziva: seja ostane (zvocnik igra naprej); ko se spet oglasi, ga upravljamo naprej
    preveri(!p.poNeuspehu(tece, 2, 30_000L).nedosegljiv && !p.poNeuspehu(tece, 5, 10_000L).nedosegljiv, "nekaj neuspelih odcitkov se ni nedosegljiv")
    val brez = p.poNeuspehu(tece, p.NEDOSEGLJIV_PO_ODCITKIH, p.NEDOSEGLJIV_PO_MS)
    preveri(brez.nedosegljiv && brez.zeliIgrati && brez.igral, "nedosegljiv: seja ostane")
    preveri(p.bistvena(tece, brez, 30_000L) && p.intervalMs(brez, 30_000L) == p.INTERVAL_NEDOSEGLJIV_MS, "nedosegljiv je sprememba, odcitki redkejsi")
    preveri(p.potrebujeBudnost(brez, false, false, false, true, 30_000L), "nedosegljiv: casovnik izklopa caka")
    // A1 (tretji pregled): izpad je obicajno kratek in zvocnik medtem igra naprej - naprava ostane budna, da mu streze svojo
    // datoteko in mu po koncu skladbe poslje naslednjo. Prej je po 20 s izpada zaspala in vrsta je obstala.
    preveri(p.potrebujeBudnost(brez, true, false, false, false, 30_000L) && p.potrebujeBudnost(brez, false, true, false, false, 30_000L), "nedosegljiv: se budna za datoteko te naprave in za vrsto")
    preveri(!p.potrebujeBudnost(brez, false, false, false, false, 30_000L), "nedosegljiv brez dela: ne")
    // Rok: do konca skladbe in se toliko, kolikor sme konec zamuditi; potem naprava lahko zaspi (seja ostane).
    val rokBudnosti = 1_000L + 200_000L + p.NAJVEC_VRZEL_ZA_KONEC_MS
    preveri(p.potrebujeBudnost(brez, false, true, false, false, rokBudnosti - 1L) && !p.potrebujeBudnost(brez, false, true, false, false, rokBudnosti), "nedosegljiv: budnost do konca skladbe in vrzeli")
    // Dolg posnetek: najvec NEDOSEGLJIV_BUDNOST_MS od zadnjega odgovora zvocnika.
    val dolg = p.poNeuspehu(p.poOdcitku(p.zacetek(0L), igra, 0L, 3_600_000L, 1_000L, true, false).stanje, p.NEDOSEGLJIV_PO_ODCITKIH, p.NEDOSEGLJIV_PO_MS)
    preveri(p.potrebujeBudnost(dolg, false, true, false, false, 1_000L + p.NEDOSEGLJIV_BUDNOST_MS - 1L) && !p.potrebujeBudnost(dolg, false, true, false, false, 1_000L + p.NEDOSEGLJIV_BUDNOST_MS), "nedosegljiv: budnost ima rok")
    preveri(p.potrebujeBudnost(dolg, false, false, false, true, 1_000L + p.NEDOSEGLJIV_BUDNOST_MS), "casovnik izklopa caka ne glede na rok")
    val spet = p.poUspehu(brez)
    preveri(!spet.nedosegljiv && p.poUspehu(tece) == tece, "spet dosegljiv")
    o = p.poOdcitku(spet, igra, 80_000L, 200_000L, 81_000L, true, false)
    preveri(o.dejanje == Dejanje.NIC && o.stanje.zeliIgrati && p.polozajZdaj(o.stanje, 81_000L) == 80_000L, "po vrnitvi: isti tok, pravi cas")
    // Skladba se je med izpadom koncala: po kratkem izpadu gre vrsta naprej, po dolgem ne (A1 - glasba ne zacne sama cez ure).
    preveri(p.poOdcitku(spet.copy(slisanMs = 170_000L), stoji, 0L, 0L, 250_000L, true, false).dejanje == Dejanje.KONEC_SKLADBE, "skladba se je med kratkim izpadom koncala: naslednja")
    // Zvocnik med izpadom omrezja igra naprej: steje, kako dolgo ze molci (od pricakovanega konca), ne kako dolg je bil izpad.
    preveri(p.poOdcitku(spet, stoji, 0L, 0L, 250_000L, true, false).dejanje == Dejanje.KONEC_SKLADBE, "dolg izpad, zvocnik je igral do konca: vrsta gre naprej")
    preveri(p.poOdcitku(spet, stoji, 0L, 0L, 350_000L, true, false).dejanje == Dejanje.KONEC_POZEN, "skladba se je koncala davno: naslednje ne posljemo")

    // --- tipka glasnosti preklice cilj drsnika (prej je »tisje« med dvigovanjem izginil in glasnost je sla se navzgor)
    val g = ZvocnikPravila.CakajocaGlasnost()
    preveri(!g.caka(), "nic ne caka")
    g.naCilj(80)
    preveri(g.vzemi() == (0 to 80), "delavec vzame cilj")
    g.nadaljuj(80)                         // korak narejen, cilj se ni dosezen
    g.tipka(-2)                            // uporabnik pritisne »tisje« med korakoma
    preveri(g.vzemi() == (-2 to -1), "tipka preklice cilj")
    preveri(!g.caka(), "dvig se ne nadaljuje")
    g.naCilj(80); g.vzemi(); g.tipka(-2); g.nadaljuj(80)     // tipka pride med korakom (ukaz zvocniku je v teku)
    preveri(g.vzemi() == (-2 to -1), "tipka med korakom: cilj se ne vrne")
    g.tipka(2); g.tipka(2); g.naCilj(10)
    preveri(g.vzemi() == (0 to 10), "cilj zamenja tipke")
    g.tipka(2); g.pocisti()
    preveri(!g.caka(), "konec seje pocisti")

    // --- neznana lestvica glasnosti (zvocnik je ne pove ali je nismo mogli prebrati): najmanjsi koraki
    preveri(p.korakGlasnosti(100, false) == 1 && p.najvecDvig(100, false) == 1, "neznana lestvica: korak 1")
    preveri(p.novaGlasnost(10, 30, 100, false) == 11 && p.korakProtiCilju(10, 90, 100, false) == 11, "neznana lestvica: navzgor po 1")
    preveri(p.novaGlasnost(10, -30, 100, false) == 0 && p.korakProtiCilju(10, 3, 100, false) == 3, "neznana lestvica: navzdol takoj")
    preveri(p.obsegKretnje(100, false) == 10 && p.glasnostIzKretnje(20, 1f, 100, false) == 30, "neznana lestvica: kretnja ima majhen obseg")

    // --- kaj sme na zvocnik: seznam kosov (HLS, DASH) ni zvocni tok
    preveri(!p.primerna("https://primer.example/radio/tok.m3u8", false) && !p.primerna("https://primer.example/a.mpd?x=1", false), "HLS in DASH ne")
    preveri(!p.primerna("https://primer.example/tok", false, "application/vnd.apple.mpegurl") && !p.primerna("https://primer.example/tok", false, "application/dash+xml"), "HLS in DASH po vrsti vsebine ne")
    preveri(p.primerna("https://primer.example/tok.mp3?m3u8=1", false) && p.primerna("https://primer.example/tok", false, "audio/aac"), "navaden tok da")

    // --- L1 (meritev 7. 10. 2026): zvocnik trajanje ocenjuje in ga med predvajanjem spreminja (skladba 3:24 -> 5:55, 3:42, 3:30, 3:33)
    // trajanje, znano s te naprave (prenos na zvocnik), velja naprej
    s = p.poOdcitku(p.zacetek(0L, 0L, 204_000L), igra, 0L, 355_000L, 1_000L, true, false).stanje
    preveri(s.trajanjeMs == 204_000L, "znano trajanje ostane: ${s.trajanjeMs}")
    s = p.poOdcitku(s, igra, 100_000L, 213_000L, 101_000L, true, false).stanje
    preveri(s.trajanjeMs == 204_000L, "znano trajanje ostane tudi pozneje: ${s.trajanjeMs}")
    // ... razen ce se izkaze za napacno (zvocnik je ze dlje v skladbi): potem velja, kar javi zvocnik
    s = p.poOdcitku(s, igra, 209_000L, 213_000L, 210_000L, true, false).stanje
    preveri(s.trajanjeMs == 213_000L, "napacno znano trajanje popusti zvocniku: ${s.trajanjeMs}")
    // neznano trajanje: velja zvocnikova ocena; skladba, ki se konca nekaj sekund pred ocenjenim koncem, se je KONCALA (vrsta gre naprej)
    s = p.poOdcitku(p.zacetek(0L), igra, 0L, 213_000L, 1_000L, true, false).stanje          // ocena 3:33, resnica 3:24
    preveri(p.poOdcitku(s.copy(slisanMs = 203_000L), stoji, 0L, 0L, 205_000L, true, false).dejanje == Dejanje.KONEC_SKLADBE, "konec 9 s pred ocenjenim trajanjem je konec skladbe")
    preveri(p.poOdcitku(s, stoji, 0L, 0L, 150_000L, true, false).dejanje == Dejanje.PREVZET, "sredi skladbe ostane prevzem")
    // kratka skladba: okno ostane 3 s (5 % od 30 s je manj); zelo dolga: najvec 15 s
    s = p.poOdcitku(p.zacetek(0L), igra, 0L, 30_000L, 1_000L, true, false).stanje
    preveri(p.poOdcitku(s, stoji, 0L, 0L, 25_000L, true, false).dejanje == Dejanje.PREVZET, "kratka skladba, 6 s pred koncem: prevzem")
    preveri(p.poOdcitku(s, stoji, 0L, 0L, 28_500L, true, false).dejanje == Dejanje.KONEC_SKLADBE, "kratka skladba, 2,5 s pred koncem: konec")
    s = p.poOdcitku(p.zacetek(0L), igra, 0L, 3_600_000L, 1_000L, true, false).stanje
    preveri(p.poOdcitku(s, stoji, 0L, 0L, 3_581_000L, true, false).dejanje == Dejanje.PREVZET, "ura posnetka, 20 s pred koncem: prevzem")
    preveri(p.poOdcitku(s.copy(slisanMs = 3_585_000L), stoji, 0L, 0L, 3_587_000L, true, false).dejanje == Dejanje.KONEC_SKLADBE, "ura posnetka, 14 s pred koncem: konec")
    // --- pravo trajanje, izvedeno naknadno (skladba, ki se zacne neposredno na zvocniku): velja namesto ocene zvocnika
    s = p.poOdcitku(p.zacetek(0L), igra, 0L, 228_000L, 1_000L, true, false).stanje                  // zvocnik ugiba 3:48
    preveri(s.trajanjeMs == 228_000L && !s.trajanjeZnano, "brez znanega trajanja velja ocena zvocnika")
    s = p.sTrajanjem(s, 204_000L, false)                                                              // glava datoteke: 3:24
    preveri(s.trajanjeMs == 204_000L && s.trajanjeZnano, "naknadno izvedeno trajanje velja")
    s = p.poOdcitku(s, igra, 10_000L, 218_000L, 11_000L, true, false).stanje                         // zvocnik ugiba naprej
    preveri(s.trajanjeMs == 204_000L, "ocena zvocnika znanega trajanja ne prepise")
    preveri(p.sTrajanjem(s, 0L, false) == s && p.sTrajanjem(s, -5L, false) == s, "neznano trajanje ne spremeni nicesar")
    val vZivo = p.poOdcitku(p.zacetek(0L), igra, 0L, 0L, 1_000L, true, true).stanje
    preveri(p.sTrajanjem(vZivo, 204_000L, true) == vZivo, "prenos v zivo nima trajanja")
    val pozno = p.poOdcitku(p.zacetek(0L), igra, 210_000L, 228_000L, 1_000L, true, false).stanje
    preveri(pozno.polozajMs == 210_000L, "polozaj iz odcitka")
    preveri(p.sTrajanjem(pozno, 204_000L, false) == pozno, "trajanje, ki ga polozaj ze presega, ne velja")

    // ================= po tretjem pregledu pred izdajo (krog 121: A1, A6) =================
    // --- A1: ustavljen zvocnik pri koncu skladbe je »konec skladbe« samo, ce smo zvocnik slisali nedavno. Po dolgi vrzeli
    // (zvocnik ni bil dosegljiv, naprava je spala ali sla iz omrezja) je konec lahko star ure: naslednje skladbe ne posljemo.
    preveri(p.zacetek(5_000L).slisanMs == 5_000L, "nova skladba: zvocnik je pravkar odgovoril")
    preveri(p.poOdcitku(tece, igra, 100_000L, 200_000L, 101_000L, true, false).stanje.slisanMs == 101_000L, "uspel odcitek: slisan")
    preveri(p.poOdcitku(tece, igra, 100_000L, 200_000L, 101_000L, false, false).stanje.slisanMs == 101_000L, "tudi zastarel odcitek pove, da je zvocnik dosegljiv")
    preveri(p.poNeuspehu(tece, 3, 30_000L).slisanMs == tece.slisanMs, "neuspel odcitek ni »slisan«")
    // R6-A1b: pri znanem trajanju steje molk od pricakovanega konca skladbe (1 000 + 200 000 ms), ne od zadnjega odgovora.
    preveri(p.poOdcitku(tece.copy(slisanMs = 150_000L), stoji, 0L, 0L, 201_000L + p.NAJVEC_VRZEL_ZA_KONEC_MS, true, false).dejanje == Dejanje.KONEC_SKLADBE, "kratek molk po koncu skladbe: vrsta gre naprej")
    preveri(p.poOdcitku(tece.copy(slisanMs = 150_000L), stoji, 0L, 0L, 201_001L + p.NAJVEC_VRZEL_ZA_KONEC_MS, true, false).dejanje == Dejanje.KONEC_POZEN, "dolg molk po koncu skladbe: pozen konec")
    preveri(p.poOdcitku(tece, stoji, 0L, 0L, 3 * 3_600_000L, true, false).dejanje == Dejanje.KONEC_POZEN, "ure pozneje: naslednje ne posljemo")
    preveri(p.poOdcitku(tece.copy(slisanMs = 150_000L), stoji, 0L, 0L, 160_000L, true, false).dejanje == Dejanje.PREVZET, "sredi skladbe je prevzem ne glede na vrzel")
    preveri(p.poOdcitku(p.premor(tece, 199_500L), stoji, 0L, 0L, 3 * 3_600_000L, true, false).dejanje == Dejanje.PREVZET, "med premorom ni ne konca ne poznega konca")
    preveri(p.poOdcitku(kratka, stoji, 0L, 0L, 200_000L, true, false).dejanje == Dejanje.KONEC_POZEN, "neznano trajanje, dolga vrzel: pozen konec")

    // --- A6: cigav je naslov, ki ga zvocnik javlja. Dokler nasa skladba ni potrjena, drugacnega naslova ne vzamemo kar tako
    // za svojega: skladba, ki je zvocniku samo nalozena (naprej/nazaj med premorom - Play ni bil poslan), ne more igrati; ce
    // zvocnik takrat igra nekaj drugega, ga je prevzel kdo drug (drug vhod, druga naprava) in ne posljemo mu nicesar.
    val nas = "http://primer.example/a.mp3"; val preusmerjen = "http://cdn.primer.example/a.mp3"; val tuj = "http://drugje.example/tv"
    val poslana = p.zacetek(0L)                                  // Play poslan ob 0
    val nalozena = p.zacetek(0L, 0L, 0L, false)                  // samo nalozena: Play ni bil poslan
    preveri(poslana.playOdMs == 0L && nalozena.playOdMs < 0L, "kdaj je bil skladbi poslan Play")
    preveri(p.cigavNaslov(nas, nas, "", igra, poslana, 2_000L) == ZvocnikPravila.Naslov.POTRDI, "nas naslov ob prvem predvajanju potrdimo")
    preveri(p.cigavNaslov(preusmerjen, nas, "", igra, poslana, 2_000L) == ZvocnikPravila.Naslov.POTRDI, "drugacen naslov tik po nasem Play sprejmemo (zvocnik javi preusmerjenega)")
    preveri(p.cigavNaslov(preusmerjen, nas, "", igra, poslana, p.POTRDITEV_NASLOVA_MS + 1L) == ZvocnikPravila.Naslov.TUJ, "drugacen naslov dolgo po nasem Play je tuj")
    preveri(p.cigavNaslov(preusmerjen, nas, "", prehod, poslana, 2_000L) == ZvocnikPravila.Naslov.NAS && p.cigavNaslov(tuj, nas, "", premor, nalozena, 99_000L) == ZvocnikPravila.Naslov.NAS, "brez predvajanja nic ne sklepamo")
    preveri(p.cigavNaslov(tuj, nas, "", igra, nalozena, 2_000L) == ZvocnikPravila.Naslov.TUJ, "nalozena skladba ne igra: tuje predvajanje ni nase")
    preveri(p.cigavNaslov(nas, nas, "", igra, nalozena, 2_000L) == ZvocnikPravila.Naslov.POTRDI, "nas naslov je nas, tudi ce ga je zagnal kdo na zvocniku samem")
    preveri(p.cigavNaslov("", nas, "", igra, nalozena, 2_000L) == ZvocnikPravila.Naslov.NAS, "brez javljenega naslova nic ne sklepamo")
    val nadaljevana = p.nadaljuj(nalozena, 50_000L).stanje       // uporabnik pritisne »predvajaj«: Play gre zdaj
    preveri(nadaljevana.playOdMs == 50_000L && p.cigavNaslov(preusmerjen, nas, "", igra, nadaljevana, 52_000L) == ZvocnikPravila.Naslov.POTRDI, "po nadaljevanju velja okno po Play")
    preveri(p.cigavNaslov(preusmerjen, nas, "", igra, p.premor(poslana, 300L), 2_000L) == ZvocnikPravila.Naslov.POTRDI, "premor med nalaganjem ne naredi skladbe tuje")
    // Ko je naslov potrjen, je vse drugo tuje - v vsakem stanju prenosa.
    preveri(p.cigavNaslov(tuj, nas, preusmerjen, igra, poslana, 2_000L) == ZvocnikPravila.Naslov.TUJ && p.cigavNaslov(tuj, nas, nas, premor, poslana, 2_000L) == ZvocnikPravila.Naslov.TUJ, "po potrditvi je drugacen naslov tuj")
    preveri(p.cigavNaslov(preusmerjen, nas, preusmerjen, premor, poslana, 999_000L) == ZvocnikPravila.Naslov.NAS && p.cigavNaslov(nas, nas, preusmerjen, igra, poslana, 999_000L) == ZvocnikPravila.Naslov.NAS, "potrjeni in poslani naslov sta nasa")

    // ================= po cetrtem pregledu (krog 121: R6-A1a) =================
    // Premor tik pred koncem, nadaljevanje ure pozneje (zaklenjen zaslon, slusalke): skladba se iztece cez 3 s - to je konec
    // skladbe, ne »pozen« konec (prej je vrsta obstala in naslednji »predvajaj« je zacel igrati na telefonu).
    val tikPredKoncem = p.premor(tece, 198_000L)                                                  // polozaj 197 s od 200 s
    val cez = 3 * 3_600_000L
    o = p.nadaljuj(tikPredKoncem, cez)
    preveri(o.stanje.slisanMs == cez, "nadaljevanje: zvocnik je pravkar dobil ukaz")
    preveri(p.poOdcitku(o.stanje, stoji, 0L, 0L, cez + 3_500L, true, false).dejanje == Dejanje.KONEC_SKLADBE, "R6-A1: nadaljevanje po dolgem premoru, konec skladbe: naslednja")
    // enako pri neznanem trajanju (steje zadnji stik z zvocnikom)
    o = p.nadaljuj(p.premor(kratka, 20_000L), cez)
    preveri(p.poOdcitku(o.stanje, stoji, 0L, 0L, cez + 8_000L, true, false).dejanje == Dejanje.KONEC_SKLADBE, "R6-A1: neznano trajanje, nadaljevanje po dolgem premoru")
    println("ZvocnikPravilaTest: OK")
}
