package si.safeer.tv.os

import si.safeer.tv.os.NaslednjaEpizodaPravila.Razlog

private var napak = 0

private fun enako(opis: String, pricakovano: Any?, dobljeno: Any?) {
    if (pricakovano == dobljeno) println("  ok  $opis")
    else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }
}

/** Lastnik 5. 11. 2026: ime posnetka izgine po 3 s, samodejna naslednja epizoda ([NaslednjaEpizodaPravila]). */
fun main() {
    // Izbira naslednje epizode (OsPravila.naslednjaEpizoda): naslednja v sezoni, sicer prva v naslednji.
    val serija = listOf(1 to 1, 1 to 2, 1 to 3, 2 to 1, 2 to 2)
    enako("naslednja v isti sezoni", 1 to 2, OsPravila.naslednjaEpizoda(serija, 1, 1))
    enako("konec sezone: prva v naslednji", 2 to 1, OsPravila.naslednjaEpizoda(serija, 1, 3))
    enako("manjkajoca epizoda se preskoci", 1 to 3, OsPravila.naslednjaEpizoda(listOf(1 to 1, 1 to 3), 1, 1))
    enako("zadnja epizoda: nic", null, OsPravila.naslednjaEpizoda(serija, 2, 2))
    enako("posebne (sezona 0) ne preidejo v sezono 1", null, OsPravila.naslednjaEpizoda(listOf(0 to 1, 1 to 1), 0, 1))

    // Kdaj kartica.
    val min40 = 40 * 60_000L
    enako("epizoda", true, NaslednjaEpizodaPravila.jeEpizoda(video = true, vZivo = false, epizoda = 2))
    enako("film (epizoda 0) ne", false, NaslednjaEpizodaPravila.jeEpizoda(video = true, vZivo = false, epizoda = 0))
    enako("v zivo ne", false, NaslednjaEpizodaPravila.jeEpizoda(video = true, vZivo = true, epizoda = 2))
    enako("zvok ne", false, NaslednjaEpizodaPravila.jeEpizoda(video = false, vZivo = false, epizoda = 2))
    // Lastnik 14. 11. 2026: priprava 5 min pred koncem ali pri 90 %, kar je prej (prej 60 s pred koncem).
    val min48 = 48 * 60_000L
    val min60 = 60 * 60_000L
    enako("priprava: 40 min, 5:01 pred koncem se ne", false, NaslednjaEpizodaPravila.iskati(min40, min40 - 301_000))
    enako("priprava: 40 min, 5:00 pred koncem", true, NaslednjaEpizodaPravila.iskati(min40, min40 - 300_000))
    enako("priprava: 48 min, 4:30 pred koncem (prej sele 60 s)", true, NaslednjaEpizodaPravila.iskati(min48, min48 - 270_000))
    enako("priprava: 60 min, 90 % je prej kot 5 min (6:00)", true, NaslednjaEpizodaPravila.iskati(min60, min60 - 360_000))
    enako("priprava: 60 min, 6:01 pred koncem se ne", false, NaslednjaEpizodaPravila.iskati(min60, min60 - 361_000))
    enako("prag: 22 min -> 5 min", 300_000L, NaslednjaEpizodaPravila.pragPriprave(22 * 60_000L))
    enako("prag: 90 min -> 9 min", 540_000L, NaslednjaEpizodaPravila.pragPriprave(90 * 60_000L))
    enako("priprava: 60 s pred koncem se vedno", true, NaslednjaEpizodaPravila.iskati(min40, min40 - 60_000))
    enako("priprava: po koncu (polozaj za trajanjem) ne", false, NaslednjaEpizodaPravila.iskati(min40, min40 + 1_000))
    enako("priprava: kratek posnetek (4 min) ne", false, NaslednjaEpizodaPravila.iskati(4 * 60_000L, 3 * 60_000L))
    enako("iskanje: brez trajanja ne", false, NaslednjaEpizodaPravila.iskati(0, 0))
    // Lastnik 14. 11. 2026: kartica brez znane odjavne spice zadnjih 60 s (prej 10 s).
    enako("kartica: 61 s pred koncem se ne", false, NaslednjaEpizodaPravila.pokazi(min40, min40 - 61_000, koncano = false))
    enako("kartica: 60 s pred koncem", true, NaslednjaEpizodaPravila.pokazi(min40, min40 - 60_000, koncano = false))
    enako("kartica: 11 s pred koncem (prej se ne)", true, NaslednjaEpizodaPravila.pokazi(min40, min40 - 11_000, koncano = false))
    enako("kartica: ob koncu vedno", true, NaslednjaEpizodaPravila.pokazi(0, 0, koncano = true))
    enako("kartica: kratek posnetek pred koncem ne", false, NaslednjaEpizodaPravila.pokazi(120_000, 115_000, koncano = false))
    // Lastnik 14. 11. 2026: kartica ob zacetku odjavne spice, ce ga TheIntroDB pozna; sicer zadnjih 60 s.
    val odjavna = min40 - 75_000
    enako("kartica: ob zacetku odjavne", true, NaslednjaEpizodaPravila.pokazi(min40, odjavna, koncano = false, odjavnaOdMs = odjavna))
    enako("kartica: tik pred odjavno ne", false, NaslednjaEpizodaPravila.pokazi(min40, odjavna - 1, koncano = false, odjavnaOdMs = odjavna))
    enako("kartica: brez odjavne zadnjih 60 s", min40 - 60_000, NaslednjaEpizodaPravila.zacetekKartice(min40, null))
    enako("kartica: odjavna v zadnjih 10 s ne skrajsa odstevanja", min40 - 10_000, NaslednjaEpizodaPravila.zacetekKartice(min40, min40 - 5_000))
    enako("kartica: odjavna 30 s pred koncem - ob odjavni, ne 60 s prej", min40 - 30_000, NaslednjaEpizodaPravila.zacetekKartice(min40, min40 - 30_000))
    enako("kartica: neveljaven zacetek odjavne ne velja", min40 - 60_000, NaslednjaEpizodaPravila.zacetekKartice(min40, -1))
    // Epizoda, ki jo uporabnik konca (naslednja s kartice ze pri 80 %, Nazaj pri koncu), se zapise kot odgledana od tega
    // mesta naprej - tudi pri zapisih, ki sledijo, dokler predvajalnik stoji tam ali predvaja naprej.
    val pri80 = min40 * 8 / 10
    enako("napredek: z oznako na istem mestu -> do konca", min40, NaslednjaEpizodaPravila.polozajZaNapredek(pri80, min40, pri80))
    enako("napredek: z oznako pozneje (predvaja naprej) -> do konca", min40, NaslednjaEpizodaPravila.polozajZaNapredek(pri80 + 60_000, min40, pri80))
    enako("napredek: brez oznake ostane mesto", pri80, NaslednjaEpizodaPravila.polozajZaNapredek(pri80, min40, null))
    enako("napredek: pred oznako (previto nazaj, ogled znova) ostane mesto", 20_000L, NaslednjaEpizodaPravila.polozajZaNapredek(20_000, min40, pri80))
    enako("napredek: brez trajanja ostane mesto", 5_000L, NaslednjaEpizodaPravila.polozajZaNapredek(5_000, 0, 1_000))
    enako("oznaka velja na mestu", true, NaslednjaEpizodaPravila.oznakaOdgledanoVelja(pri80, pri80))
    enako("oznaka ne velja pred mestom", false, NaslednjaEpizodaPravila.oznakaOdgledanoVelja(pri80 - 1, pri80))
    enako("odgledano po zapisu z oznako (95 % iz OsPravila)", true, OsPravila.odgledano(NaslednjaEpizodaPravila.polozajZaNapredek(min40 * 92 / 100, min40, min40 * 92 / 100), min40))
    enako("brez oznake pri 92 % ni odgledano", false, OsPravila.odgledano(NaslednjaEpizodaPravila.polozajZaNapredek(min40 * 92 / 100, min40, null), min40))

    // Lastnik 14. 11. 2026: Nazaj pri koncu epizode (>= 90 % ali po zacetku znane odjavne) = epizoda je odgledana.
    val min53 = 53 * 60_000L
    enako("Nazaj pri 93 % (49:19 od 53:00): odgledana", true, NaslednjaEpizodaPravila.odgledanaObNazaj(min53, 49 * 60_000L + 19_000))
    enako("Nazaj pri 90 %: odgledana", true, NaslednjaEpizodaPravila.odgledanaObNazaj(min40, min40 * 9 / 10))
    enako("Nazaj tik pod 90 %: ne", false, NaslednjaEpizodaPravila.odgledanaObNazaj(min40, min40 * 9 / 10 - 1))
    enako("Nazaj sredi epizode: ne", false, NaslednjaEpizodaPravila.odgledanaObNazaj(min40, min40 / 2))
    enako("Nazaj po zacetku znane odjavne (80 %): odgledana", true, NaslednjaEpizodaPravila.odgledanaObNazaj(min40, pri80 + 1_000, odjavnaOdMs = pri80))
    enako("Nazaj pred znano odjavno (79 %): ne", false, NaslednjaEpizodaPravila.odgledanaObNazaj(min40, pri80 - 1_000, odjavnaOdMs = pri80))
    enako("Nazaj: kratek posnetek ne", false, NaslednjaEpizodaPravila.odgledanaObNazaj(4 * 60_000L, 4 * 60_000L - 1_000))
    enako("Nazaj: brez trajanja ne", false, NaslednjaEpizodaPravila.odgledanaObNazaj(0, 50_000))
    // Fokus v oknu serije po Nazaj: naslednja med prikazanimi epizodami; brez naslednje se ne zgodi nic novega.
    enako("fokus po Nazaj: naslednja v sezoni", 1 to 2, NaslednjaEpizodaPravila.fokusPoNazaj(serija, 1, 1))
    enako("fokus po Nazaj: konec sezone -> prva v naslednji", 2 to 1, NaslednjaEpizodaPravila.fokusPoNazaj(serija, 1, 3))
    enako("fokus po Nazaj: skrita naslednja -> naslednja prikazana", 1 to 3, NaslednjaEpizodaPravila.fokusPoNazaj(listOf(1 to 1, 1 to 3, 2 to 1), 1, 1))
    enako("fokus po Nazaj: zadnja epizoda -> nic", null, NaslednjaEpizodaPravila.fokusPoNazaj(serija, 2, 2))
    enako("fokus po Nazaj: prazen seznam -> nic", null, NaslednjaEpizodaPravila.fokusPoNazaj(emptyList(), 1, 1))
    enako("zahteva Nazaj: takoj velja", true, NaslednjaEpizodaPravila.zahtevaNazajVelja(100_000, 100_300))
    enako("zahteva Nazaj: po 5 s se velja", true, NaslednjaEpizodaPravila.zahtevaNazajVelja(100_000, 105_000))
    enako("zahteva Nazaj: po 5 s ne vec (pozen seznam epizod)", false, NaslednjaEpizodaPravila.zahtevaNazajVelja(100_000, 105_001))
    enako("zahteva Nazaj: ura nazaj ne velja", false, NaslednjaEpizodaPravila.zahtevaNazajVelja(100_000, 99_000))
    // Pripravljen izid: ista epizoda iste serije, najvec 15 min od priprave; sicer novo iskanje.
    val kS1E2 = NaslednjaEpizodaPravila.kljucEpizode("stremio|series|tt0000001|dodatek#123", 1, 2)
    enako("kljuc epizode brez oznake toka", "stremio|series|tt0000001|dodatek|S1E2", kS1E2)
    enako("kljuc: isti z in brez oznake toka", kS1E2, NaslednjaEpizodaPravila.kljucEpizode("stremio|series|tt0000001|dodatek", 1, 2))
    enako("pripravljen: ista epizoda po 2 min velja", true, NaslednjaEpizodaPravila.pripravljenVelja(kS1E2, kS1E2, 1_000, 121_000))
    enako("pripravljen: po 15 min se velja", true, NaslednjaEpizodaPravila.pripravljenVelja(kS1E2, kS1E2, 1_000, 901_000))
    enako("pripravljen: po 15 min ne vec", false, NaslednjaEpizodaPravila.pripravljenVelja(kS1E2, kS1E2, 1_000, 901_001))
    enako("pripravljen: druga epizoda ne", false, NaslednjaEpizodaPravila.pripravljenVelja(kS1E2, NaslednjaEpizodaPravila.kljucEpizode("stremio|series|tt0000001|dodatek", 1, 3), 1_000, 2_000))
    enako("pripravljen: ista epizoda druge sezone ne", false, NaslednjaEpizodaPravila.pripravljenVelja(kS1E2, NaslednjaEpizodaPravila.kljucEpizode("stremio|series|tt0000001|dodatek", 2, 2), 1_000, 2_000))
    enako("pripravljen: druga serija ne", false, NaslednjaEpizodaPravila.pripravljenVelja(kS1E2, NaslednjaEpizodaPravila.kljucEpizode("stremio|series|tt0000002|dodatek", 1, 2), 1_000, 2_000))
    enako("pripravljen: ni izida", false, NaslednjaEpizodaPravila.pripravljenVelja(null, kS1E2, 1_000, 2_000))
    enako("pripravljen: ura nazaj ne velja", false, NaslednjaEpizodaPravila.pripravljenVelja(kS1E2, kS1E2, 5_000, 1_000))
    enako("dnevnik Nazaj s fokusom", "naslednja epizoda: Nazaj pri 93 % -> fokus S1E2, pripravljen=da",
        NaslednjaEpizodaPravila.dnevnikNazaj(49 * 60_000L + 19_000, min53, 1 to 2, true))
    enako("dnevnik Nazaj brez pripravljenega", "naslednja epizoda: Nazaj pri 90 % -> fokus S2E1, pripravljen=ne",
        NaslednjaEpizodaPravila.dnevnikNazaj(min40 * 9 / 10, min40, 2 to 1, false))
    enako("dnevnik Nazaj brez naslednje", "naslednja epizoda: Nazaj pri 95 % -> ni naslednje epizode",
        NaslednjaEpizodaPravila.dnevnikNazaj(min40 * 95 / 100, min40, null, false))
    // Podnapisi epizode, ki se konca, ne gredo v naslednjo (prej n.serija.podnapisi + pripravljeni): preverba v izvoru.
    run {
        val izvor = System.getProperty("izvor.os")
        if (izvor == null) { napak++; println("  NAPAKA  manjka -Dizvor.os (mapa src/main/kotlin/si/safeer/tv/os)") }
        else {
            val g = java.io.File(izvor, "GlasbaActivity.kt").readText()
            enako("izvor: predvajajNaslednjoEpizodo obstaja", true, g.contains("fun predvajajNaslednjoEpizodo("))
            enako("podnapisi prejsnje epizode ne gredo v naslednjo", false, g.contains("n.serija.podnapisi"))
            enako("naslednja epizoda dobi samo svoje podnapise", true, g.contains("podnapisi = n.podnapisi.orEmpty()"))
            // Vezava pravil v zaslonih (Android razredov preizkus JVM ne more pognati): klic pravila mora ostati v kodi.
            val p = java.io.File(izvor, "PredvajanjeActivity.kt").readText()
            val m = java.io.File(izvor, "MediaNapredek.kt").readText()
            fun telo(vir: String, glava: String): String = vir.substringAfter(glava, "").substringBefore("\n    }\n")
            val nazaj = telo(p, "private fun nazajPriKoncuEpizode(")
            enako("Nazaj: zaslon predvajanja vprasa pravilo", true, nazaj.contains("NaslednjaEpizodaPravila.odgledanaObNazaj("))
            enako("Nazaj: epizoda se oznaci kot odgledana", true, nazaj.contains("oznaciOdgledano()"))
            enako("Nazaj: zahteva za Medijski center", true, nazaj.contains("GlasbaActivity.poNazaj = "))
            enako("Nazaj: brez samodejnega zagona naslednje", false, nazaj.contains("zacniNaslednjo(") || nazaj.contains("predvajajNaslednjoEpizodo("))
            enako("Nazaj: klic iz onBackPressed", true, p.contains("nazajPriKoncuEpizode(vMedijskiCenter = !vMapo)"))
            enako("samodejni zacetek: konec epizode in pavza gresta v pravilo", true,
                p.contains("NaslednjaEpizodaPravila.zacniZdaj(odstetoMs, preostaloPredvajanja(p), konecEpizode, pavza)"))
            enako("napis kartice: preostanek predvajanja gre v pravilo", true, p.contains("NaslednjaEpizodaPravila.preostaloSekund(odstetoMs, preostaloPredvajanja("))
            val okno = telo(g, "private fun pokaziNaslednjoPoNazaj(")
            enako("okno serije po Nazaj: fokus po pravilu", true, okno.contains("NaslednjaEpizodaPravila.fokusPoNazaj("))
            enako("okno serije po Nazaj: zahteva se velja", true, okno.contains("NaslednjaEpizodaPravila.zahtevaNazajVelja("))
            enako("okno serije po Nazaj: vrstica dnevnika", true, okno.contains("NaslednjaEpizodaPravila.dnevnikNazaj("))
            enako("okno serije po Nazaj: brez samodejnega zagona", false,
                okno.contains("predvajajNaslednjoEpizodo(") || okno.contains("izberiTok(") || okno.contains("performItemClick"))
            enako("klik na epizodo: pripravljen izid po pravilu", true,
                g.contains("pripravljenaNaslednja?.takeIf { !rocno && pripravljenaVelja(sk.id, e.sezona, e.epizoda) }") &&
                    g.contains("NaslednjaEpizodaPravila.pripravljenVelja("))
            enako("napredek: oznaka odgledano gre skozi pravilo", true,
                m.contains("NaslednjaEpizodaPravila.polozajZaNapredek(polozajZdaj, trajanje, oznaka)"))
        }
    }
    // Odjavna iz odsekov TheIntroDB: samo smiseln odsek ODJAVNA (zadnja cetrtina, sega do konca).
    val odsekOdjavna = PreskokPravila.Odsek(PreskokPravila.Vrsta.ODJAVNA, odjavna, null)
    enako("odjavna iz odsekov", odjavna, NaslednjaEpizodaPravila.odjavnaOd(listOf(PreskokPravila.Odsek(PreskokPravila.Vrsta.UVOD, 60_000, 120_000), odsekOdjavna), min40))
    enako("odjavna sredi epizode ni smiselna", null, NaslednjaEpizodaPravila.odjavnaOd(listOf(PreskokPravila.Odsek(PreskokPravila.Vrsta.ODJAVNA, min40 / 2, null)), min40))
    enako("brez odjavne v odsekih", null, NaslednjaEpizodaPravila.odjavnaOd(listOf(PreskokPravila.Odsek(PreskokPravila.Vrsta.UVOD, 60_000, 120_000)), min40))

    // Meja: 3 samodejne zapored, nato »Se gledas?«.
    enako("prva: odstevanje", NaslednjaEpizodaPravila.ODSTEVAJ, NaslednjaEpizodaPravila.vrsta(0, tokPotrjen = true))
    enako("po dveh samodejnih: odstevanje", NaslednjaEpizodaPravila.ODSTEVAJ, NaslednjaEpizodaPravila.vrsta(2, tokPotrjen = true))
    enako("po treh samodejnih: vprasaj", NaslednjaEpizodaPravila.VPRASAJ, NaslednjaEpizodaPravila.vrsta(3, tokPotrjen = true))
    enako("samodejno izklopljeno: ponudi brez odstevanja", NaslednjaEpizodaPravila.PONUDI, NaslednjaEpizodaPravila.vrsta(0, tokPotrjen = true, samodejno = false))
    // 14. 11. 2026: kartica je vidna ze zadnjih 60 s ali od zacetka odjavne - rok vprasanja tece sele od konca epizode.
    enako("rok: vprasanje med predvajanjem ne (prej da)", false, NaslednjaEpizodaPravila.rokBrezOdziva(NaslednjaEpizodaPravila.VPRASAJ, false))
    enako("rok: vprasanje po koncu", true, NaslednjaEpizodaPravila.rokBrezOdziva(NaslednjaEpizodaPravila.VPRASAJ, true))
    enako("rok: ponudi med predvajanjem ne", false, NaslednjaEpizodaPravila.rokBrezOdziva(NaslednjaEpizodaPravila.PONUDI, false))
    enako("rok: ponudi po koncu", true, NaslednjaEpizodaPravila.rokBrezOdziva(NaslednjaEpizodaPravila.PONUDI, true))
    enako("rok: odstevanje ne", false, NaslednjaEpizodaPravila.rokBrezOdziva(NaslednjaEpizodaPravila.ODSTEVAJ, true))
    enako("izklopljeno brez toka: nic", NaslednjaEpizodaPravila.NIC, NaslednjaEpizodaPravila.vrsta(0, tokPotrjen = false, samodejno = false))
    enako("brez potrjenega toka: kartice ni", NaslednjaEpizodaPravila.NIC, NaslednjaEpizodaPravila.vrsta(0, tokPotrjen = false))
    enako("brez potrjenega toka tudi po treh: kartice ni", NaslednjaEpizodaPravila.NIC, NaslednjaEpizodaPravila.vrsta(3, tokPotrjen = false))
    enako("s potrjenim tokom: kartica", NaslednjaEpizodaPravila.ODSTEVAJ, NaslednjaEpizodaPravila.vrsta(0, tokPotrjen = true))

    // Potrjen tok (vodja 6. 11. 2026): pokazemo samo, kar si uporabnik res lahko ogleda.
    enako("nic pripravljenega: ni potrjen", false, NaslednjaEpizodaPravila.tokPotrjen(false, samoUsenet = false, torrentovPripravljenih = 0, neposrednih = 0))
    enako("pripravljen Usenet: potrjen", true, NaslednjaEpizodaPravila.tokPotrjen(true, samoUsenet = true, torrentovPripravljenih = 0, neposrednih = 0))
    enako("samo Usenet, Usenet ni pripravljen, torrent je: ni potrjen", false,
        NaslednjaEpizodaPravila.tokPotrjen(false, samoUsenet = true, torrentovPripravljenih = 2, neposrednih = 1))
    enako("⚡ torrent pri debridu: potrjen", true, NaslednjaEpizodaPravila.tokPotrjen(false, samoUsenet = false, torrentovPripravljenih = 1, neposrednih = 0))
    enako("neposredna povezava: potrjen", true, NaslednjaEpizodaPravila.tokPotrjen(false, samoUsenet = false, torrentovPripravljenih = 0, neposrednih = 1))
    // Rok poskusa = zgornja meja notranjih rokov poti (meta 12 s + tokovi z Usenetom 50,5 s + TorBox 10 s); prej 20 s.
    enako("predpriprava: 30 s je v roku (prej po roku)", true, NaslednjaEpizodaPravila.vRoku(1_000, 31_000))
    enako("predpriprava v roku 75 s", true, NaslednjaEpizodaPravila.vRoku(1_000, 76_000))
    enako("predpriprava po roku ne velja", false, NaslednjaEpizodaPravila.vRoku(1_000, 76_001))
    enako("rok pokrije notranje roke poti", true, NaslednjaEpizodaPravila.PRIPRAVA_ROK_MS >= 12_000L +
        UsenetIskanjePravila.cakajSPonovitvami(UsenetPravila.ISKANJE_CAKAJ_MS) + 10_000L)
    // Ponovni poskus: en, samo za razloge, ki se lahko spremenijo.
    for (r in listOf(Razlog.KVOTA, Razlog.ROK, Razlog.NAPAKA, Razlog.NI_TOKA)) {
        enako("ponovi po prvem neuspehu: $r", true, NaslednjaEpizodaPravila.ponoviti(r, 1))
        enako("po drugem neuspehu ne vec: $r", false, NaslednjaEpizodaPravila.ponoviti(r, 2))
    }
    for (r in listOf(Razlog.OK, Razlog.NI_EPIZODE, Razlog.NI_SERIJE, Razlog.NI_MEDIJSKEGA_CENTRA))
        enako("brez ponovitve: $r", false, NaslednjaEpizodaPravila.ponoviti(r, 1))
    enako("ponovitev: 59,9 s po neuspehu se ne", false, NaslednjaEpizodaPravila.zdajPonoviti(10_000, 69_900, koncano = false))
    enako("ponovitev: 60 s po neuspehu", true, NaslednjaEpizodaPravila.zdajPonoviti(10_000, 70_000, koncano = false))
    enako("ponovitev: ob koncu epizode takoj", true, NaslednjaEpizodaPravila.zdajPonoviti(10_000, 10_500, koncano = true))
    // Potek epizode (48 min): prvi poskus 5 min pred koncem preseze rok, drugi uspe - kartica je pripravljena
    // pred zadnjimi 10 s (prej: en poskus 60 s pred koncem, rok 20 s, brez ponovitve -> kartice ni bilo).
    run {
        var poskusov = 0; var neuspeh = 0L; var iscem = false; var zacetekPoskusa = 0L; var pripravljena = -1L; var prviPoskus = -1L
        val trajanja = listOf(80_000L, 30_000L)      // prvi poskus 80 s (cez rok), drugi 30 s
        var polozaj = 0L
        while (polozaj <= min48) {
            val zdaj = polozaj
            if (iscem && zdaj - zacetekPoskusa >= trajanja[poskusov - 1]) {
                iscem = false
                if (NaslednjaEpizodaPravila.vRoku(zacetekPoskusa, zdaj)) pripravljena = zdaj
                else if (NaslednjaEpizodaPravila.ponoviti(Razlog.ROK, poskusov)) neuspeh = zdaj
            }
            val zacni = if (poskusov == 0) NaslednjaEpizodaPravila.iskati(min48, polozaj)
                else NaslednjaEpizodaPravila.zdajPonoviti(neuspeh, zdaj, polozaj >= min48)
            if (!iscem && pripravljena < 0 && poskusov < NaslednjaEpizodaPravila.NAJVEC_POSKUSOV && zacni) {
                poskusov++; iscem = true; zacetekPoskusa = zdaj; if (prviPoskus < 0) prviPoskus = zdaj
            }
            polozaj += 1_000L
        }
        enako("potek: prvi poskus 5 min pred koncem", min48 - 300_000, prviPoskus)
        enako("potek: dva poskusa", 2, poskusov)
        enako("potek: naslednja pripravljena pred kartico (zadnjih 60 s)", true, pripravljena in 0 until NaslednjaEpizodaPravila.zacetekKartice(min48))
        enako("potek: kartica 60 s pred koncem", min48 - 60_000, NaslednjaEpizodaPravila.zacetekKartice(min48))
    }
    // Brez odprtih virov (vodja 6. 11. 2026): lasten pogon se v predpripravi ne odpre - nic za zapreti.
    enako("lasten pogon: predpriprava ne odpre seje", false, NaslednjaEpizodaPravila.usenetOdpriVPredpripravi(lastniPogon = true))
    enako("TorBox in pomocnik: predpriprava sme preveriti (brez virov na napravi)", true, NaslednjaEpizodaPravila.usenetOdpriVPredpripravi(lastniPogon = false))
    var zap = 0
    zap = NaslednjaEpizodaPravila.zaporednihPo(zap, samodejno = true)
    zap = NaslednjaEpizodaPravila.zaporednihPo(zap, samodejno = true)
    zap = NaslednjaEpizodaPravila.zaporednihPo(zap, samodejno = true)
    enako("trikrat samodejno = 3", 3, zap)
    enako("izbira uporabnika na nic", 0, NaslednjaEpizodaPravila.zaporednihPo(zap, samodejno = false))

    // Odstevanje 10 s po koncu epizode (kartica, ki se pokaze sele ob koncu).
    enako("zacetek: cez 10 s", 10, NaslednjaEpizodaPravila.preostaloSekund(0, 0))
    enako("po 1 s: cez 9 s", 9, NaslednjaEpizodaPravila.preostaloSekund(1_000, 0))
    enako("po 9,5 s: cez 1 s", 1, NaslednjaEpizodaPravila.preostaloSekund(9_500, 0))
    enako("po 10 s: 0", 0, NaslednjaEpizodaPravila.preostaloSekund(10_000, 0))
    enako("pred 10 s se ne zacne", false, NaslednjaEpizodaPravila.zacniZdaj(9_999, 0, konec = true))
    enako("po 10 s se zacne", true, NaslednjaEpizodaPravila.zacniZdaj(10_000, 0, konec = true))
    // Lastnik 14. 11. 2026: kartica je vidna ze prej, samodejni zacetek pa sele ob naravnem koncu epizode.
    enako("kartica vidna 30 s, do konca 30 s: se ne", false, NaslednjaEpizodaPravila.zacniZdaj(30_000, 30_000, konec = false))
    enako("kartica vidna 59 s, do konca 1 s: zacne (zadnji tik)", true, NaslednjaEpizodaPravila.zacniZdaj(59_000, 1_000, konec = false))
    enako("kartica vidna 59 s, do konca 2 s: se ne", false, NaslednjaEpizodaPravila.zacniZdaj(59_000, 2_000, konec = false))
    enako("zastoj 5 s pred koncem: se ne zacne (prej po 10 s odstevanja)", false, NaslednjaEpizodaPravila.zacniZdaj(120_000, 5_000, konec = false))
    enako("konec epizode, kartica vidna dovolj: zacne", true, NaslednjaEpizodaPravila.zacniZdaj(60_000, 0, konec = true))
    enako("konec epizode, kartica vidna 3 s: se ne", false, NaslednjaEpizodaPravila.zacniZdaj(3_000, 0, konec = true))
    // Pavza ustavi tudi zacetek v zadnji sekundi (odstevanje je ze prej doseglo 10 s, ker je kartica vidna 60 s).
    enako("pavza v zadnji sekundi: se ne zacne", false, NaslednjaEpizodaPravila.zacniZdaj(59_500, 500, konec = false, pavza = true))
    enako("brez pavze v zadnji sekundi: zacne", true, NaslednjaEpizodaPravila.zacniZdaj(59_500, 500, konec = false, pavza = false))
    enako("napis: 58 s do konca", 58, NaslednjaEpizodaPravila.preostaloSekund(2_000, 58_000))
    enako("napis: 9,2 s do konca, kartica vidna 51 s", 10, NaslednjaEpizodaPravila.preostaloSekund(51_000, 9_200))
    enako("napis: kartica odprta 3 s pred koncem - se 10 s", 10, NaslednjaEpizodaPravila.preostaloSekund(0, 3_000))
    enako("napis: negativen preostanek ne skoduje", 4, NaslednjaEpizodaPravila.preostaloSekund(6_000, -500))
    // Potek zadnje minute (tik 1 s): kartica 60 s pred koncem, napisi 59..1, zacetek ob koncu - ne 10 s po odprtju kartice.
    run {
        var odprta = false; var odsteto = 0L; var zacela = -1L
        val napisi = ArrayList<Int>()
        var polozaj = min40 - 65_000
        while (polozaj <= min40 + 3_000 && zacela < 0) {
            val konec = polozaj >= min40
            val prej = odprta
            if (!odprta && NaslednjaEpizodaPravila.pokazi(min40, polozaj, konec)) { odprta = true; odsteto = 0L }
            val preostalo = (min40 - polozaj).coerceAtLeast(0L)
            if (NaslednjaEpizodaPravila.odstejZdaj(true, prej, odprta)) {
                odsteto = NaslednjaEpizodaPravila.odsteto(odsteto, 1_000L, pavza = false)
                if (NaslednjaEpizodaPravila.zacniZdaj(odsteto, preostalo, konec)) zacela = polozaj
            }
            if (odprta && zacela < 0) napisi += NaslednjaEpizodaPravila.preostaloSekund(odsteto, preostalo)
            polozaj += 1_000L
        }
        enako("zadnja minuta: prvi napis cez 60 s", 60, napisi.first())
        enako("zadnja minuta: napisi padajo za 1 do 2 s", (60 downTo 2).toList(), napisi)
        enako("zadnja minuta: zacetek v zadnji sekundi epizode (ne 10 s po odprtju kartice)", min40 - 1_000, zacela)
    }
    enako("pavza ustavi odstevanje", 2_000L, NaslednjaEpizodaPravila.odsteto(2_000, 1_000, pavza = true))
    enako("predvajanje odsteva", 3_000L, NaslednjaEpizodaPravila.odsteto(2_000, 1_000, pavza = false))

    // CodeRabbit 6. 11. 2026: kartica, ki jo odpre tik (po koncu epizode), prvi napis »cez 10 s« in zacetek po polnih 10 s (prej 9).
    run {
        var odprta = false; var odsteto = 0L; var zacela = -1
        val napisi = ArrayList<Int>()
        for (sekunda in 1..12) {               // vsak tik (1 s) kot PredvajanjeActivity.preveriNaslednjo(odstej = true)
            val prej = odprta
            if (!odprta) { odprta = true; odsteto = 0L }      // pokaziKartico v tem tiku
            if (NaslednjaEpizodaPravila.odstejZdaj(true, prej, odprta)) {
                odsteto = NaslednjaEpizodaPravila.odsteto(odsteto, 1_000L, pavza = false)
                if (NaslednjaEpizodaPravila.zacniZdaj(odsteto, 0, konec = true) && zacela < 0) zacela = sekunda
            }
            napisi += NaslednjaEpizodaPravila.preostaloSekund(odsteto, 0)
        }
        enako("odstevanje: prvi napis cez 10 s", 10, napisi.first())
        enako("odstevanje: zacetek 10 s po odprtju (tik 11, kartica v tiku 1)", 11, zacela)
        enako("odstevanje: kartica ze odprta prej odsteva", true, NaslednjaEpizodaPravila.odstejZdaj(true, true, true))
        enako("odstevanje: brez tika (izid iskanja) ne", false, NaslednjaEpizodaPravila.odstejZdaj(false, true, true))
    }
    // Pregled 6. 11. 2026 (E1): »Se gledas?« brez odziva 60 s -> zaslon se sprosti in zapre.
    enako("vprasanje: rok 60 s", 60_000L, NaslednjaEpizodaPravila.VPRASANJE_ROK_MS)
    enako("vprasanje: 59,9 s se caka", false, NaslednjaEpizodaPravila.vprasanjeBrezOdziva(1_000, 60_900))
    enako("vprasanje: 60 s brez tipke", true, NaslednjaEpizodaPravila.vprasanjeBrezOdziva(1_000, 61_000))
    enako("vprasanje: tipka pri 50 s rok znova", false, NaslednjaEpizodaPravila.vprasanjeBrezOdziva(51_000, 61_000))

    // Besedila.
    enako("oznaka z imenom", "S1E2 Pilot", NaslednjaEpizodaPravila.oznaka(1, 2, " Pilot "))
    enako("oznaka brez imena", "S1E2", NaslednjaEpizodaPravila.oznaka(1, 2, ""))
    enako("naslov nove epizode", "Serija · S1E2 · Drugi", NaslednjaEpizodaPravila.naslovEpizode("Serija · S1E1 · Pilot", 1, 2, "Drugi"))
    enako("naslov nove epizode brez imena", "Serija · S2E1", NaslednjaEpizodaPravila.naslovEpizode("Serija · S1E3", 2, 1, ""))

    // Ime posnetka: 3 s od zacetka predvajanja ali zadnje tipke; pavza -> play brez imena (lastnik 5. 11. 2026 22:42).
    enako("pas se skrije po 3 s", 3_000L, OsPravila.PAS_SKRIJ_MS)
    enako("ime se skrije po 3 s", 3_000L, OsPravila.IME_SKRIJ_MS)
    enako("nova epizoda pokaze ime", true, OsPravila.imeObPrviSliki("s|1|2", "s|1|1"))
    enako("ista epizoda (previjanje) ne", false, OsPravila.imeObPrviSliki("s|1|2", "s|1|2"))
    enako("brez posnetka ne", false, OsPravila.imeObPrviSliki(null, null))
    val od = 10_000L
    enako("nova epizoda: ime vidno 2,999 s", true, OsPravila.imeVidno(video = true, vednoVidno = false, tece = true, odMs = od, zdajMs = 12_999))
    enako("nova epizoda: ime po 3 s izgine", false, OsPravila.imeVidno(video = true, vednoVidno = false, tece = true, odMs = od, zdajMs = 13_000))
    enako("se 2 s imena", 2_000L, OsPravila.preostaloImena(od, 11_000))
    enako("po 3 s nic vec", 0L, OsPravila.preostaloImena(od, 14_000))
    enako("brez imena nic", 0L, OsPravila.preostaloImena(-1, 14_000))
    // Pavza pri 11 s (ime se kaze), nato play pri 20 s.
    val poPavzi = OsPravila.imeOdObPavzi(od, igra = false)
    enako("med pavzo ni imena", false, OsPravila.imeVidno(video = true, vednoVidno = false, tece = false, odMs = poPavzi, zdajMs = 11_000))
    val tipkaPlay = OsPravila.imeOdPoTipki(poPavzi, 20_000, tece = false)
    val poPlay = OsPravila.imeOdObPavzi(tipkaPlay, igra = true)
    enako("pavza -> play: ni imena", false, OsPravila.imeVidno(video = true, vednoVidno = false, tece = true, odMs = poPlay, zdajMs = 20_100))
    enako("hitra pavza -> play (pod 3 s): ni imena", false, OsPravila.imeVidno(video = true, vednoVidno = false, tece = true,
        odMs = OsPravila.imeOdObPavzi(OsPravila.imeOdObPavzi(od, igra = false), igra = true), zdajMs = 11_000))
    // Tipka med predvajanjem: ime za 3 s po zadnji tipki.
    val tipka = OsPravila.imeOdPoTipki(-1, 30_000, tece = true)
    enako("tipka med predvajanjem: ime", true, OsPravila.imeVidno(video = true, vednoVidno = false, tece = true, odMs = tipka, zdajMs = 32_000))
    enako("3 s po zadnji tipki: brez imena", false, OsPravila.imeVidno(video = true, vednoVidno = false, tece = true, odMs = tipka, zdajMs = 33_000))
    enako("nalaganje: ime vidno", true, OsPravila.imeVidno(video = true, vednoVidno = true, tece = false, odMs = -1, zdajMs = 0))
    enako("glasba: ime vedno", true, OsPravila.imeVidno(video = false, vednoVidno = false, tece = false, odMs = -1, zdajMs = 0))

    if (napak > 0) { println("NaslednjaEpizodaPravila: $napak napak"); kotlin.system.exitProcess(1) }
    println("NaslednjaEpizodaPravila: vse ok")
}
