package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 42 E: ena razclemba ukazov lupe in glasu ([LupaPravila]) in zadetki lupe. Lazne naprave. */
fun main() {
    println("Preizkus lupe in glasovnih ukazov")
    val krog = listOf("n-1" to "Dnevna soba", "n-2" to "Pisarna", "n-3" to "TV", "n-4" to "Dnevna soba TV")
    val u = { niz: String -> LupaPravila.razcleni(niz, krog) }
    val brez = { niz: String -> LupaPravila.razcleni(niz) }

    println("\n== <kaj> na <napravo>: po imenu v krogu ==")
    preveriEnako("Sintel na Dnevna soba", LupaPravila.Ukaz("prikazi_na", "Sintel", "n-1"), u("Sintel na Dnevna soba"))
    preveriEnako("daljse ime ima prednost", LupaPravila.Ukaz("prikazi_na", "Sintel", "n-4"), u("Sintel na dnevna soba TV"))
    preveriEnako("ime TV pred vrsto TV", LupaPravila.Ukaz("prikazi_na", "Sintel", "n-3"), u("Sintel na TV"))
    preveriEnako("glagol odpade, sumniki v imenu", LupaPravila.Ukaz("prikazi_na", "Jungle Book", "n-2"), u("Predvajaj Jungle Book na pisarni".replace("pisarni", "Pisarna")))
    preveriEnako("en: on the", LupaPravila.Ukaz("prikazi_na", "Sintel", "n-2"), u("play Sintel on the Pisarna"))
    preveriEnako("de: auf dem", LupaPravila.Ukaz("prikazi_na", "Sintel", "n-1"), u("Sintel auf dem Dnevna Soba"))
    preveriEnako("ime brez predloga ni cilj", LupaPravila.Ukaz("isci", "Pisarna"), u("Pisarna"))

    println("\n== po vrsti naprave (brez imena) ==")
    preveriEnako("sl na TV", LupaPravila.Ukaz("prikazi_na", "Sintel", "", listOf("tv")), brez("Sintel na TV"))
    preveriEnako("sl na racunalniku", LupaPravila.Ukaz("prikazi_na", "Sintel", "", listOf("windows", "linux", "mac")), brez("Sintel na računalniku"))
    preveriEnako("en on my phone", LupaPravila.Ukaz("prikazi_na", "Big Buck Bunny", "", listOf("phone")), brez("show Big Buck Bunny on my phone"))
    preveriEnako("es en la tele", LupaPravila.Ukaz("prikazi_na", "Sintel", "", listOf("tv")), brez("pon Sintel en la tele"))
    preveriEnako("fr sur la télé", LupaPravila.Ukaz("prikazi_na", "Sintel", "", listOf("tv")), brez("Sintel sur la télé"))
    preveriEnako("it sulla TV", LupaPravila.Ukaz("prikazi_na", "Sintel", "", listOf("tv")), brez("Sintel sulla TV"))
    preveriEnako("de auf dem Fernseher", LupaPravila.Ukaz("prikazi_na", "Sintel", "", listOf("tv")), brez("Sintel auf dem Fernseher"))

    println("\n== vreme ==")
    preveriEnako("vreme", LupaPravila.Ukaz("vreme", ""), brez("vreme"))
    preveriEnako("vreme na TV (lastnik)", LupaPravila.Ukaz("vreme", "", "", listOf("tv")), brez("vreme na TV"))
    preveriEnako("vreme na napravi po imenu", LupaPravila.Ukaz("vreme", "", "n-1"), u("vreme na Dnevna soba"))
    preveriEnako("vreme v kraju", LupaPravila.Ukaz("vreme", "Mariboru"), brez("Vreme v Mariboru"))
    preveriEnako("weather in Paris", LupaPravila.Ukaz("vreme", "Paris"), brez("weather in Paris"))
    preveriEnako("Wetter auf dem Fernseher", LupaPravila.Ukaz("vreme", "", "", listOf("tv")), brez("Wetter auf dem Fernseher"))
    preveriEnako("météo", LupaPravila.Ukaz("vreme", ""), brez("Météo"))

    println("\n== splet in iskanje ==")
    preveriEnako("isci v spletu", LupaPravila.Ukaz("isci_splet", "recept za potico"), brez("Išči v spletu recept za potico"))
    preveriEnako("search the web for", LupaPravila.Ukaz("isci_splet", "Sintel"), brez("search the web for Sintel"))
    preveriEnako("cerca sul web", LupaPravila.Ukaz("isci_splet", "meteo Roma"), brez("cerca sul web meteo Roma"))
    preveriEnako("naslov", LupaPravila.Ukaz("splet", "https://safeer.si"), brez("safeer.si"))
    preveriEnako("naslov s shemo", LupaPravila.Ukaz("splet", "https://example.org/a"), brez("https://example.org/a"))
    preveriEnako("navadno iskanje", LupaPravila.Ukaz("isci", "Big Buck Bunny"), brez("Big Buck Bunny"))
    preveriEnako("pomisljaj ne zamakne besed", LupaPravila.Ukaz("prikazi_na", "Sintel", "", listOf("tv")), brez("Sintel – na TV"))
    preveriEnako("prazno", LupaPravila.Ukaz("isci", ""), brez("   "))

    println("\n== zadetki lupe ==")
    preveriEnako("zacetek imena", 3, LupaPravila.ujemanje("Nastavitve", "nas"))
    preveriEnako("zacetek besede", 2, LupaPravila.ujemanje("Safeer Link", "lin"))
    // Preverba TV 19:31: samo zacetek besede ali (od 4 znakov) zacetek imena brez presledkov, nikoli cez mejo besed.
    preveriEnako("sredina besede ne ujema", 0, LupaPravila.ujemanje("YouTube", "tub"))
    preveriEnako("rtv -> RTV SLO", 3, LupaPravila.ujemanje("RTV SLO", "rtv"))
    preveriEnako("rtv -> RTV 365", 3, LupaPravila.ujemanje("RTV 365", "rtv"))
    preveriEnako("rtv ne najde Safeer TV Browser", 0, LupaPravila.ujemanje("Safeer TV Browser", "rtv"))
    preveriEnako("rtvslo -> RTV SLO (brez presledkov)", 1, LupaPravila.ujemanje("RTV SLO", "rtvslo"))
    preveriEnako("kratko brez presledkov ne", 0, LupaPravila.ujemanje("RT V", "rtv"))
    preveriEnako("rtv med aplikacijami", listOf("RTV 365", "RTV SLO"),
        LupaPravila.najboljsi(listOf("Safeer TV Browser", "RTV SLO", "RTV 365", "Netflix"), "rtv", 8) { it })
    preveriEnako("brez sumnikov", 3, LupaPravila.ujemanje("Čas in datum", "cas"))
    preveriEnako("ne ujema", 0, LupaPravila.ujemanje("Netflix", "xyz"))
    preveriEnako("prazna poizvedba", 0, LupaPravila.ujemanje("Netflix", " "))
    preveriEnako("najboljsi po ujemanju in imenu (Ponastavi ni zacetek besede)", listOf("Nastavitve", "Safeer Nastavitve"),
        LupaPravila.najboljsi(listOf("Ponastavi", "Safeer Nastavitve", "Nastavitve", "Netflix"), "nastav", 5) { it })
    preveriEnako("najvec", 1, LupaPravila.najboljsi(listOf("a1", "a2"), "a", 1) { it }.size)

    println("\n== sprotni predlogi iz spleta ==")
    preveriEnako("Google/DDG oblika", listOf("sintel film", "sintel blender"),
        LupaPravila.predlogi("""["sintel",["sintel","sintel film","sintel blender",""],[],{"x":1}]""", "Sintel"))
    preveriEnako("najvec 5", 5, LupaPravila.predlogi("""["a",["a1","a2","a3","a4","a5","a6"]]""", "a").size)
    preveriEnako("pokvarjen odgovor", emptyList<String>(), LupaPravila.predlogi("<html>", "a"))
    preveriEnako("brez seznama", emptyList<String>(), LupaPravila.predlogi("""["a"]""", "a"))
    preveriEnako("kratka poizvedba ne gre iskalniku", false, LupaPravila.predlogeIskati("si", true))
    preveriEnako("naslov ne gre iskalniku", false, LupaPravila.predlogeIskati("safeer.si", true))
    preveriEnako("izklopljeno ne gre", false, LupaPravila.predlogeIskati("sintel", false))
    preveriEnako("sicer da", true, LupaPravila.predlogeIskati("sintel", true))

    // 2. 11. 2026: izbira, kje lupa isce - privzeto Splet, neznana vrednost ne pokvari lupe
    // Lastnik 2. 11. 2026 18:37-19:03: ena vrstica »Vse · Splet · Video vsebine · YouTube · Aplikacije · Datoteke · Nastavitve«
    // izbere, KAJ lupa prikaze; neznana vrednost = Vse.
    preveriEnako("privzeto vse", LupaPravila.OBSEG_VSE, LupaPravila.obseg(null))
    preveriEnako("neznano -> vse", LupaPravila.OBSEG_VSE, LupaPravila.obseg("mediji"))
    preveriEnako("veljavna splet", LupaPravila.OBSEG_SPLET, LupaPravila.obseg("splet"))
    preveriEnako("veljavna video", LupaPravila.OBSEG_VIDEO, LupaPravila.obseg("video"))
    preveriEnako("veljavna youtube", LupaPravila.OBSEG_YOUTUBE, LupaPravila.obseg("youtube"))
    preveriEnako("vrstni red gumbov", listOf("vse", "splet", "video", "youtube", "aplikacije", "datoteke", "nastavitve"), LupaPravila.OBSEGI)
    preveriEnako("vse: danasnje skupine po vrsti, nove za njimi, splet zadnji",
        listOf("aplikacije", "novice", "datoteke", "nastavitve", "videoteke", "video", "youtube", "splet"), LupaPravila.skupine("vse"))
    preveriEnako("vse: vsaka skupina enkrat (aplikacija ni dvakrat)", LupaPravila.SKUPINE_VSE.size, LupaPravila.SKUPINE_VSE.toSet().size)
    preveriEnako("aplikacije: vse aplikacije uporabnika, tudi videoteke in novice", listOf("aplikacije", "videoteke", "novice"),
        LupaPravila.skupine("aplikacije"))
    preveriEnako("video: videoteke nad zadetki Medijskega centra", listOf("videoteke", "video"), LupaPravila.skupine("video"))
    preveriEnako("datoteke: samo datoteke", listOf("datoteke"), LupaPravila.skupine("datoteke"))
    preveriEnako("nastavitve: samo nastavitve", listOf("nastavitve"), LupaPravila.skupine("nastavitve"))
    preveriEnako("youtube: samo youtube", listOf("youtube"), LupaPravila.skupine("youtube"))
    preveriEnako("neznano: vse skupine", LupaPravila.SKUPINE_VSE, LupaPravila.skupine("x"))
    // Lastnik 19:03: vsako novo iskanje zacne na Vse; med urejanjem v isti lupi izbira ostane.
    preveriEnako("med urejanjem izbira ostane", "datoteke", LupaPravila.obsegPo("datoteke", lupaZaprta = false))
    preveriEnako("zaprta lupa -> novo iskanje na Vse", "vse", LupaPravila.obsegPo("datoteke", lupaZaprta = true))
    preveriEnako("neveljavna med urejanjem -> Vse", "vse", LupaPravila.obsegPo("x", lupaZaprta = false))
    // OK na polju (lastnik 19:03)
    val ok = LupaPravila.OkPolja.values().associateBy { it.name }
    preveriEnako("splet s stranjo odpre stran", ok["ODPRI_STRAN"], LupaPravila.okNaPolju("splet", true, true))
    preveriEnako("splet brez strani isce", ok["ISCI_SPLET"], LupaPravila.okNaPolju("splet", true, false))
    preveriEnako("youtube isce na youtubu", ok["ISCI_YOUTUBE"], LupaPravila.okNaPolju("youtube", true, false))
    preveriEnako("video isce v Medijskem centru", ok["ISCI_VIDEO"], LupaPravila.okNaPolju("video", false, false))
    preveriEnako("vse z zadetkom: fokus na prvi (nic se ne zazene)", ok["FOKUS_NA_PRVI"], LupaPravila.okNaPolju("vse", true, false))
    preveriEnako("vse brez zadetka: splet", ok["ISCI_SPLET"], LupaPravila.okNaPolju("vse", false, false))
    preveriEnako("aplikacije: fokus na prvi", ok["FOKUS_NA_PRVI"], LupaPravila.okNaPolju("aplikacije", true, false))
    preveriEnako("datoteke brez zadetka: fokus (na »Isci v Vse«)", ok["FOKUS_NA_PRVI"], LupaPravila.okNaPolju("datoteke", false, false))
    preveriEnako("nastavitve: fokus na prvi", ok["FOKUS_NA_PRVI"], LupaPravila.okNaPolju("nastavitve", true, true))
    preveriEnako("vse isce v spletu", "splet", LupaPravila.iskalniObseg("vse"))
    preveriEnako("datoteke brez zadetka (druga okna) isce v spletu", "splet", LupaPravila.iskalniObseg("datoteke"))
    preveriEnako("video isce v video", "video", LupaPravila.iskalniObseg("video"))
    preveriEnako("vrstica iskanja samo v eni spletni skupini", listOf(false, true, true, true, false, false, false),
        LupaPravila.OBSEGI.map { LupaPravila.vrsticaIskanja(it) })
    // Preverba TV 19:43: zgodovina brez smeti (napol natipkano z vrstico znakov), brez ponovitev in zacetkov.
    preveriEnako("smet z narekovaji ne gre v zgodovino", null, LupaPravila.zaZgodovino("„ DDV po„llll,l.-e"))
    preveriEnako("zacetek smeti ne", null, LupaPravila.zaZgodovino("„ DDV"))
    preveriEnako("samo locila ne", null, LupaPravila.zaZgodovino(" .,-„ "))
    preveriEnako("ena crka ne", null, LupaPravila.zaZgodovino("a"))
    preveriEnako("pravo iskanje, ocisceno", "Oppenheimer 2023", LupaPravila.zaZgodovino("  Oppenheimer   2023 "))
    preveriEnako("pomisljaj sme", "vreme - Maribor", LupaPravila.zaZgodovino("vreme - Maribor"))
    preveriEnako("naslov sme", "safeer.si", LupaPravila.zaZgodovino("safeer.si"))
    preveriEnako("ob branju ocisceno", listOf("test", "Oppenheimer"),
        LupaPravila.ocistiZgodovino(listOf("test", "Oppenheimer", "„ DDV po„llll,l.-e", "„ DDV po„llll,l.-", "„ DDV")))
    preveriEnako("zacetek istega iskanja odpade, ponovitev tudi", listOf("Oppenheimer", "test"),
        LupaPravila.ocistiZgodovino(listOf("Opp", "Oppenheimer", "test", "TEST")))
    preveriEnako("novo na vrh, smet se ne shrani", listOf("test"), LupaPravila.dodajVZgodovino(listOf("test"), "„ x,y"))
    preveriEnako("novo na vrh", listOf("sintel", "test"), LupaPravila.dodajVZgodovino(listOf("test"), "sintel"))
    preveriEnako("najvec 5", 5, LupaPravila.dodajVZgodovino(listOf("aa", "bb", "cc", "dd", "ee"), "ff").size)
    // Fokus: polje -> zgodovina -> polje (brez filtrov); z zadetki polje -> filter; zaprta plosca: ostane.
    preveriEnako("DOL s polja v zgodovino", LupaPravila.Fokus.PRVI, LupaPravila.dolSPolja(true, false, true))
    preveriEnako("GOR iz zgodovine na polje", LupaPravila.Fokus.POLJE, LupaPravila.gorIzSeznama(false))
    preveriEnako("DOL s polja na filter (zadetki)", LupaPravila.Fokus.FILTER, LupaPravila.dolSPolja(true, true, true))
    preveriEnako("GOR iz zadetkov na filter", LupaPravila.Fokus.FILTER, LupaPravila.gorIzSeznama(true))
    preveriEnako("zaprta plosca: privzeto", LupaPravila.Fokus.OSTANE, LupaPravila.dolSPolja(false, false, false))
    preveriEnako("prazna zgodovina: privzeto", LupaPravila.Fokus.OSTANE, LupaPravila.dolSPolja(true, false, false))
    // Preverba TV 19:31: plosca zadetkov vsaj 16 dp nad spodnjo vrstico (izmerjen vrh), nikoli cez njo.
    preveriEnako("rob nad vrstico", 1080 - 930 + 32, LupaPravila.spodnjiRobPlosce(1080, 930, 32))
    preveriEnako("vrstica pod okvirjem: samo razmik", 32, LupaPravila.spodnjiRobPlosce(1000, 1200, 32))
    // Stanja seznama (lastnik 19:03): zadetki, Iscem …, prazno; »Isci v Vse« samo pri ozki izbiri.
    preveriEnako("zadetki", LupaPravila.Stanje.ZADETKI, LupaPravila.stanje(2, 1))
    preveriEnako("se iscem", LupaPravila.Stanje.ISCEM, LupaPravila.stanje(0, 1))
    preveriEnako("prazno", LupaPravila.Stanje.PRAZNO, LupaPravila.stanje(0, 0))
    preveriEnako("prazno v datotekah ponudi Vse", true, LupaPravila.ponudiVse("datoteke"))
    preveriEnako("prazno pod Vse ne ponudi Vse", false, LupaPravila.ponudiVse("vse"))
    // Sedem gumbov v eni vrstici: najvecja velikost, ki gre; null, ce ne gre niti najmanjsa.
    preveriEnako("gre pri 15 sp", 15f, LupaPravila.velikostGumbov(listOf(100f, 100f), 20f, 240f))
    preveriEnako("gre sele pri 13 sp", 13f, LupaPravila.velikostGumbov(listOf(150f, 150f), 20f, 300f))
    preveriEnako("ne gre", null, LupaPravila.velikostGumbov(listOf(500f, 500f), 20f, 300f))
    // Zasebnost: natipkano gre iskalniku samo s privolitvijo; YouTube pod Vse samo z Googlom kot iskalnikom.
    preveriEnako("splet zivo brez privolitve ne", false, LupaPravila.zivo("splet", "splet", "sintel", false, true))
    preveriEnako("splet zivo s privolitvijo", true, LupaPravila.zivo("splet", "vse", "sintel", true, false))
    preveriEnako("youtube pod vse z DuckDuckGo ne", false, LupaPravila.zivo("youtube", "vse", "sintel", true, false))
    preveriEnako("youtube izrecno s privolitvijo", true, LupaPravila.zivo("youtube", "youtube", "sintel", true, false))
    preveriEnako("video izrecno brez privolitve (dodatki uporabnika)", true, LupaPravila.zivo("video", "video", "sintel", false, false))
    preveriEnako("video pod vse brez privolitve ne", false, LupaPravila.zivo("video", "vse", "sintel", false, false))
    preveriEnako("skupina izven izbire ne", false, LupaPravila.zivo("splet", "aplikacije", "sintel", true, true))
    preveriEnako("kratko ne gre ven", false, LupaPravila.zivo("video", "video", "si", true, true))
    // Lastnik 18:44-18:48: videoteke in novice samo kot razvrstitev aplikacij, ki jih uporabnik ima.
    preveriEnako("kategorija video", "videoteke", LupaPravila.vrstaAplikacije("x.y", "Nekaj", true, false))
    preveriEnako("kategorija novice", "novice", LupaPravila.vrstaAplikacije("x.y", "Nekaj", false, true))
    preveriEnako("Netflix po paketu", "videoteke", LupaPravila.vrstaAplikacije("com.netflix.ninja", "N", false, false))
    preveriEnako("FILMBOX+ po paketu s televizorja", "videoteke", LupaPravila.vrstaAplikacije("com.spiintl.tv.filmbox", "X", false, false))
    preveriEnako("RTV 365 po paketu s televizorja (ne novice)", "videoteke",
        LupaPravila.vrstaAplikacije("si.rtvslo.rtv4dandroidtv_ab", "Y", false, true))
    preveriEnako("RTV 365 je videoteka, ne novice", "videoteke", LupaPravila.vrstaAplikacije("a.b", "RTV 365", false, true))
    preveriEnako("FILMBOX+ po imenu", "videoteke", LupaPravila.vrstaAplikacije("a.b", "FILMBOX+", false, false))
    preveriEnako("RTV SLO po imenu je novice", "novice", LupaPravila.vrstaAplikacije("a.b", "RTV SLO", false, false))
    preveriEnako("navadna aplikacija", "aplikacije", LupaPravila.vrstaAplikacije("com.android.tv.settings", "Nastavitve", false, false))
    preveriEnako("spletna 24ur.com je novice", "novice", LupaPravila.vrstaSpletne("https://www.24ur.com/", "24ur"))
    preveriEnako("poddomena rtvslo.si", "novice", LupaPravila.vrstaSpletne("https://m.rtvslo.si/tv", "Spored"))
    preveriEnako("podniz domene ni novice", "aplikacije", LupaPravila.vrstaSpletne("https://24ur.com.zlo.si/", "Zlo"))
    preveriEnako("ne24ur.com ni 24ur.com", "aplikacije", LupaPravila.vrstaSpletne("https://ne24ur.com/", "Ne"))
    run {
        data class A(val ime: String, val url: String = "", val paket: String = "")
        val moje = listOf(A("RTV SLO", paket = "a.b"), A("Netflix", paket = "com.netflix.ninja"), A("Vreme", "https://www.arso.gov.si/"))
        val vrsta = { a: A -> if (a.url.isNotEmpty()) LupaPravila.vrstaSpletne(a.url, a.ime) else LupaPravila.vrstaAplikacije(a.paket, a.ime, false, false) }
        val sk = LupaPravila.aplikacijePoSkupinah(moje, "24ur", 8, { it.ime }, vrsta)
        preveriEnako("znana stran 24ur, ki je uporabnik nima, ni med aplikacijami/novicami", emptyMap<String, List<A>>(), sk)
        preveriEnako("rtv slo pod novice", listOf("RTV SLO"), LupaPravila.aplikacijePoSkupinah(moje, "rtv", 8, { it.ime }, vrsta)["novice"]?.map { it.ime })
        preveriEnako("netflix pod videoteke", listOf("Netflix"), LupaPravila.aplikacijePoSkupinah(moje, "net", 8, { it.ime }, vrsta)["videoteke"]?.map { it.ime })
    }
    // Splet: neposredni zadetki strani; znane strani novic so samo tu (strani), nikoli med aplikacijami.
    preveriEnako("rtv slo -> rtvslo.si", listOf("rtvslo.si" to "https://rtvslo.si"), LupaPravila.strani("rtv slo", emptyList()))
    preveriEnako("24ur -> 24ur.com", "https://24ur.com", LupaPravila.strani("24ur", emptyList()).firstOrNull()?.second)
    preveriEnako("spletna aplikacija uporabnika po imenu", "https://www.arso.gov.si/",
        LupaPravila.strani("vreme arso", listOf("Vreme ARSO" to "https://www.arso.gov.si/")).firstOrNull()?.second)
    preveriEnako("ista domena enkrat", 1, LupaPravila.strani("24ur", listOf("24ur" to "https://www.24ur.com/")).size)
    preveriEnako("kratek podniz ne najde strani", emptyList<Pair<String, String>>(), LupaPravila.strani("el", emptyList()))
    preveriEnako("brez ujemanja nic", emptyList<Pair<String, String>>(), LupaPravila.strani("sintel", emptyList()))
    preveriEnako("youtube predlogi kodirani", "https://suggestqueries.google.com/complete/search?client=chrome&ds=yt&q=a+%C5%A1", LupaPravila.youtubePredlogi(" a š "))
    preveriEnako("youtube iskanje kodirano", "https://www.youtube.com/results?search_query=linkin+park+%C5%A1", LupaPravila.youtubeIskanje(" linkin park š "))

    preveriEnako("OK z besedilom isce", true, LupaPravila.okIsce("sintel"))
    preveriEnako("OK s praznim ne isce (odpre tipkovnico)", false, LupaPravila.okIsce("  "))
    // Naloga 49 C: v obsegu Splet in YouTube isce ze en znak, drugje (Video vsebine) sele dva.
    preveriEnako("splet: en znak isce", true, LupaPravila.okIsce("a", LupaPravila.OBSEG_SPLET))
    preveriEnako("youtube: en znak isce", true, LupaPravila.okIsce(" a ", LupaPravila.OBSEG_YOUTUBE))
    preveriEnako("video: en znak ne isce", false, LupaPravila.okIsce("a", LupaPravila.OBSEG_VIDEO))
    preveriEnako("video: dva znaka isceta", true, LupaPravila.okIsce("ab", LupaPravila.OBSEG_VIDEO))
    if (napak == 0) println("LupaPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
