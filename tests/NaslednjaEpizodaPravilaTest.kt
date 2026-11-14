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
    enako("kartica: 11 s pred koncem se ne", false, NaslednjaEpizodaPravila.pokazi(min40, min40 - 11_000, koncano = false))
    enako("kartica: 10 s pred koncem", true, NaslednjaEpizodaPravila.pokazi(min40, min40 - 10_000, koncano = false))
    enako("kartica: ob koncu vedno", true, NaslednjaEpizodaPravila.pokazi(0, 0, koncano = true))
    enako("kartica: kratek posnetek pred koncem ne", false, NaslednjaEpizodaPravila.pokazi(120_000, 115_000, koncano = false))
    // Lastnik 14. 11. 2026: kartica ob zacetku odjavne spice, ce ga TheIntroDB pozna; sicer zadnjih 10 s.
    val odjavna = min40 - 75_000
    enako("kartica: ob zacetku odjavne", true, NaslednjaEpizodaPravila.pokazi(min40, odjavna, koncano = false, odjavnaOdMs = odjavna))
    enako("kartica: tik pred odjavno ne", false, NaslednjaEpizodaPravila.pokazi(min40, odjavna - 1, koncano = false, odjavnaOdMs = odjavna))
    enako("kartica: brez odjavne zadnjih 10 s", min40 - 10_000, NaslednjaEpizodaPravila.zacetekKartice(min40, null))
    enako("kartica: odjavna v zadnjih 10 s ne skrajsa odstevanja", min40 - 10_000, NaslednjaEpizodaPravila.zacetekKartice(min40, min40 - 5_000))
    enako("kartica: neveljaven zacetek odjavne ne velja", min40 - 10_000, NaslednjaEpizodaPravila.zacetekKartice(min40, -1))
    // Zacetek naslednje s kartice ob zacetku odjavne (lahko ze pri 80 %): epizoda se zapise kot odgledana.
    enako("napredek: naslednja zacela -> do konca", min40, NaslednjaEpizodaPravila.polozajZaNapredek(min40 * 8 / 10, min40, naslednjaZacela = true))
    enako("napredek: brez naslednje ostane mesto", min40 * 8 / 10, NaslednjaEpizodaPravila.polozajZaNapredek(min40 * 8 / 10, min40, naslednjaZacela = false))
    enako("napredek: brez trajanja ostane mesto", 5_000L, NaslednjaEpizodaPravila.polozajZaNapredek(5_000, 0, naslednjaZacela = true))
    enako("napredek med pripravo nove epizode se ne zapise (ne pobrise »odgledano«)", false, NaslednjaEpizodaPravila.zapisiNapredekZdaj(pripravljamNovega = true, naslednjaZacela = false))
    enako("napredek ob zacetku naslednje se zapise", true, NaslednjaEpizodaPravila.zapisiNapredekZdaj(pripravljamNovega = true, naslednjaZacela = true))
    enako("napredek med predvajanjem se zapise", true, NaslednjaEpizodaPravila.zapisiNapredekZdaj(pripravljamNovega = false, naslednjaZacela = false))
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
    enako("rok: vprasanje", true, NaslednjaEpizodaPravila.rokBrezOdziva(NaslednjaEpizodaPravila.VPRASAJ, false))
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
        enako("potek: naslednja pripravljena pred kartico (zadnjih 10 s)", true, pripravljena in 0 until NaslednjaEpizodaPravila.zacetekKartice(min48))
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

    // Odstevanje 10 s.
    enako("zacetek: cez 10 s", 10, NaslednjaEpizodaPravila.preostaloSekund(0))
    enako("po 1 s: cez 9 s", 9, NaslednjaEpizodaPravila.preostaloSekund(1_000))
    enako("po 9,5 s: cez 1 s", 1, NaslednjaEpizodaPravila.preostaloSekund(9_500))
    enako("po 10 s: 0", 0, NaslednjaEpizodaPravila.preostaloSekund(10_000))
    enako("pred 10 s se ne zacne", false, NaslednjaEpizodaPravila.zacniZdaj(9_999))
    enako("po 10 s se zacne", true, NaslednjaEpizodaPravila.zacniZdaj(10_000))
    enako("pavza ustavi odstevanje", 2_000L, NaslednjaEpizodaPravila.odsteto(2_000, 1_000, pavza = true))
    enako("predvajanje odsteva", 3_000L, NaslednjaEpizodaPravila.odsteto(2_000, 1_000, pavza = false))

    // CodeRabbit 6. 11. 2026: kartica, ki jo odpre tik, prvi napis »cez 10 s« in zacetek po polnih 10 s (prej 9).
    run {
        var odprta = false; var odsteto = 0L; var zacela = -1
        val napisi = ArrayList<Int>()
        for (sekunda in 1..12) {               // vsak tik (1 s) kot PredvajanjeActivity.preveriNaslednjo(odstej = true)
            val prej = odprta
            if (!odprta) { odprta = true; odsteto = 0L }      // pokaziKartico v tem tiku
            if (NaslednjaEpizodaPravila.odstejZdaj(true, prej, odprta)) {
                odsteto = NaslednjaEpizodaPravila.odsteto(odsteto, 1_000L, pavza = false)
                if (NaslednjaEpizodaPravila.zacniZdaj(odsteto) && zacela < 0) zacela = sekunda
            }
            napisi += NaslednjaEpizodaPravila.preostaloSekund(odsteto)
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
