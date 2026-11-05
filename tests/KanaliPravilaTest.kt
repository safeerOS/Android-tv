package si.safeer.tv.os

import si.safeer.tv.os.KanaliPravila.Katalog
import si.safeer.tv.os.KanaliPravila.Vir

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    preveri("$opis (pričakovano=$pricakovano, dobljeno=$dobljeno)", pricakovano == dobljeno)

/**
 * Preizkus [KanaliPravila]: izbiri »zvrst« in »jezik« za TV v zivo iz katalogov dodatkov. Oblike katalogov so
 * izmerjene 5. 10. 2026 (en katalog na kategorijo ali jezik; en katalog z moznostmi), imena so izmisljena.
 */
fun main() {
    println("Preizkus pravil kanalov v zivo")

    // Dodatek A: en katalog na kategorijo ali jezik (tako kot dodatek s 84 katalogi).
    val a = "https://a.test"
    // Dodatek B: en katalog z moznostmi, imena kategorij v portugalscini in z veliko zacetnico.
    val b = "https://b.test"
    val katalogi = listOf(
        Katalog(a, "Kanali", "Kanali All"),                 // 0
        Katalog(a, "Kanali", "Kanali Animation"),           // 1
        Katalog(a, "Kanali", "Kanali Animation Bonus"),     // 2
        Katalog(a, "Kanali", "Kanali News"),                // 3
        Katalog(a, "Kanali", "Kanali Sports"),              // 4
        Katalog(a, "Kanali", "Kanali Undefined"),           // 5
        Katalog(a, "Kanali", "Kanali 18+"),                 // 6
        Katalog(a, "Kanali", "Kanali Slovenian"),           // 7
        Katalog(a, "Kanali", "Kanali English"),             // 8
        Katalog(a, "Kanali", "Kanali Panjabi"),             // 9
        Katalog(b, "Drugi", "Drugi • TV ao vivo", listOf("NOTICIAS", "NOTICIAS INTERNACIONAIS", "ESPORTES", "DOCUMENTARIOS · Canais", "SKUPINA X", "ADULTOS")),  // 10
    )
    val f = KanaliPravila.filtri(katalogi)

    println("\n== oznake ==")
    preveriEnako("ime kataloga brez imena dodatka", "News", KanaliPravila.oznakaKataloga("Kanali", "Kanali News"))
    preveriEnako("locilo za imenom dodatka odpade", "TV ao vivo", KanaliPravila.oznakaKataloga("Drugi", "Drugi • TV ao vivo"))
    preveriEnako("ime brez imena dodatka ostane", "Sports", KanaliPravila.oznakaKataloga("Kanali", "Sports"))
    preveriEnako("naglasi in pripona za piko", "documentarios", KanaliPravila.cista("DOCUMENTÁRIOS · Canais"))
    preveriEnako("jezik: Slovenian", "sl", KanaliPravila.jezik("Slovenian"))
    preveriEnako("jezik: Deutsch", "de", KanaliPravila.jezik("Deutsch"))
    preveriEnako("jezik: Panjabi (zapis dodatka)", "pa", KanaliPravila.jezik("Panjabi"))
    preveriEnako("»English Movies« ni jezik", null, KanaliPravila.jezik("English Movies"))
    preveriEnako("kategorija: News", "novice", KanaliPravila.kategorija("News"))
    preveriEnako("kategorija: NOTICIAS INTERNACIONAIS", "novice", KanaliPravila.kategorija("NOTICIAS INTERNACIONAIS"))
    preveriEnako("kategorija: ESPORTES", "sport", KanaliPravila.kategorija("ESPORTES"))
    preveriEnako("kategorija: Animation Bonus", "animirani", KanaliPravila.kategorija("Animation Bonus"))
    preveriEnako("kategorija: DESENHOS 24 HORAS", "otroski", KanaliPravila.kategorija("DESENHOS 24 HORAS"))
    preveriEnako("kategorija: Undefined", "ostalo", KanaliPravila.kategorija("Undefined"))
    preveriEnako("neznana skupina kanalov nima kategorije", null, KanaliPravila.kategorija("SKUPINA X"))
    preveriEnako("del besede ni kategorija", null, KanaliPravila.kategorija("Sportsnetwork"))
    preveri("zasebna oznaka: 18+", KanaliPravila.jeZasebna("18+"))
    preveri("zasebna oznaka: ADULTOS", KanaliPravila.jeZasebna("ADULTOS"))
    preveri("»News« ni zasebna", !KanaliPravila.jeZasebna("News"))

    println("\n== izbire ==")
    preveriEnako("pogled »vse«: katalog All in katalog z moznostmi", listOf(Vir(0), Vir(10)), f.splosni)
    preveriEnako("zvrsti po vrsti: znane, nato po imenu dodatka, na koncu ostalo",
        listOf("novice", "sport", "dokumentarni", "animirani", "o:skupina x", "ostalo"), f.zvrsti.map { it.kljuc })
    preveriEnako("novice: katalog News in obe moznosti NOTICIAS", listOf(Vir(3), Vir(10, "NOTICIAS"), Vir(10, "NOTICIAS INTERNACIONAIS")),
        f.zvrsti.first { it.kljuc == "novice" }.viri)
    preveriEnako("animirani: Animation in Animation Bonus skupaj", listOf(Vir(1), Vir(2)), f.zvrsti.first { it.kljuc == "animirani" }.viri)
    preveriEnako("dokumentarni: moznost s pripono », Canais«", listOf(Vir(10, "DOCUMENTARIOS · Canais")), f.zvrsti.first { it.kljuc == "dokumentarni" }.viri)
    preveriEnako("neznana skupina obdrzi ime dodatka", "SKUPINA X", f.zvrsti.first { it.kljuc == "o:skupina x" }.ime)
    preveriEnako("znana kategorija nima svojega imena (da ga vmesnik)", "", f.zvrsti.first { it.kljuc == "novice" }.ime)
    preveriEnako("jeziki", listOf("sl", "en", "pa"), f.jeziki.map { it.kljuc })
    preveriEnako("zasebni: katalog in moznost", listOf(Vir(6), Vir(10, "ADULTOS")), f.zasebni)
    preveri("zasebna kategorija ni med izbirami", f.zvrsti.none { z -> z.viri.any { it == Vir(6) || it.moznost == "ADULTOS" } })
    preveri("zasebna kategorija ni v pogledu »vse«", f.splosni.none { it == Vir(6) || it.moznost == "ADULTOS" })

    println("\n== viri za izbiro ==")
    fun izbrani(z: String, j: String) = KanaliPravila.izbrani(f, katalogi, z, j)
    preveriEnako("brez izbire: splosni viri", listOf(Vir(0), Vir(10)), izbrani("", "").glavni)
    preveriEnako("samo zvrst: viri zvrsti iz obeh dodatkov", listOf(Vir(4), Vir(10, "ESPORTES")), izbrani("sport", "").glavni)
    preveriEnako("samo jezik: katalog jezika", listOf(Vir(7)), izbrani("", "sl").glavni)
    preveriEnako("zvrst in jezik: kanali jezika ...", listOf(Vir(7)), izbrani("novice", "sl").glavni)
    preveriEnako("... ki so tudi v katalogu zvrsti istega dodatka (dodatek brez jezikov odpade)", listOf(Vir(3)), izbrani("novice", "sl").presek)
    preveriEnako("zvrst, ki jo pozna samo dodatek brez jezikov, z jezikom ne da nicesar", emptyList<Vir>(), izbrani("o:skupina x", "sl").glavni)
    preveriEnako("neznana zvrst: nic", emptyList<Vir>(), izbrani("ni-te-zvrsti", "").glavni)

    println("\n== dodatek brez kataloga »vse« ==")
    val samoKategorije = listOf(Katalog(a, "Kanali", "Kanali News"), Katalog(a, "Kanali", "Kanali Kids"), Katalog(a, "Kanali", "Kanali Slovenian"))
    val f2 = KanaliPravila.filtri(samoKategorije)
    preveriEnako("pogled »vse« sestavijo kategorije (jezik ne - isti kanali bi bili dvakrat)", listOf(Vir(0), Vir(1)), f2.splosni)

    println("\n== imena kanalov ==")
    preveriEnako("locljivost v oklepaju odpade", "Kanal Ena", KanaliPravila.imeKanala("Kanal Ena (720p)"))
    preveriEnako("opomba seznama odpade", "Kanal Dva", KanaliPravila.imeKanala("Kanal Dva (1080p) [Not 24/7]"))
    preveriEnako("oklepaj, ki ni locljivost, ostane", "Kanal (Maribor)", KanaliPravila.imeKanala("Kanal (Maribor)"))
    preveriEnako("ime iz same pripone ostane", "(720p)", KanaliPravila.imeKanala("(720p)"))

    println("\n== isti kanal ali drug kanal ==")
    fun isti(a: String, b: String) = KanaliPravila.kljucImena(a) == KanaliPravila.kljucImena(b)
    preveri("isto ime je isti kanal (vec povezav istega kanala je ena kartica)", isti("Liga Ena", "Liga Ena"))
    preveri("oznaka kakovosti ne naredi drugega kanala",
        isti("Kanal Ena HD", "Kanal Ena") && isti("Kanal Ena FHD", "kanal ena (720p)") && isti("Kanal Ena 1080p", "Kanal Ena 4K"))
    preveri("locila in presledki ne naredijo drugega kanala", isti("Kanal-Ena", "Kanal Ena") && isti("KanalEna", "Kanal Ena"))
    preveri("plus je del imena: drug kanal", !isti("Kanal", "Kanal+") && !isti("Kanal Ena", "Kanal Ena +1"))
    preveri("plus s presledkom ali brez je isti kanal", isti("Kanal + Sport", "Kanal+ Sport"))
    preveri("stevilka je del imena: drug kanal", !isti("Sport 1", "Sport 2"))
    preveri("beseda je del imena: drug kanal", !isti("Glasba TV", "Glasba TV Live") && !isti("Novice", "Novice Music"))
    preveri("vsebinski oklepaj je del imena: drug kanal", !isti("Kanal (Maribor)", "Kanal (Ljubljana)"))
    preveriEnako("ime iz same oznake kakovosti ostane", "hd", KanaliPravila.kljucImena("HD"))
    preveriEnako("ime s plusom in oznako kakovosti", "a+bmednarodni", KanaliPravila.kljucImena("A+B Mednarodni HD"))

    println()
    // ---- naloga 73: domaci viri (drzava, jezik, regija) in ponovni poskus
    val iptv = "https://iptv.test"; val balkan = "https://balkan.test"
    val vsi = listOf(
        Katalog(iptv, "All IPTV", "All IPTV Germany"),        // 0
        Katalog(iptv, "All IPTV", "All IPTV Slovenia"),       // 1
        Katalog(iptv, "All IPTV", "All IPTV Croatia"),        // 2
        Katalog(iptv, "All IPTV", "All IPTV Adult"),          // 3 zasebna
        Katalog(balkan, "Balkanio", "Balkanio EX-YU"),        // 4
        Katalog(balkan, "Balkanio", "Balkanio Slovenija"),    // 5
        Katalog(a, "Kanali", "Kanali Slovenian"),             // 6
    )
    val fv = KanaliPravila.filtri(vsi)
    preveriEnako("drzava: Slovenia", "SI", KanaliPravila.drzava("Slovenia"))
    preveriEnako("drzava: Slovenija (domace ime)", "SI", KanaliPravila.drzava("Slovenija"))
    preveriEnako("drzava: neznana", null, KanaliPravila.drzava("News"))
    preveriEnako("Balkan: EX-YU", true, KanaliPravila.jeBalkan("EX-YU"))
    preveriEnako("domaci viri SI: drzava, jezik, regija; brez zasebnih",
        listOf(Vir(1), Vir(5), Vir(6), Vir(4)), KanaliPravila.domaciViri(vsi, fv, "sl", "si"))
    preveriEnako("domaci viri DE: samo drzava (Balkan ne)", listOf(Vir(0)), KanaliPravila.domaciViri(vsi, fv, "de", "DE"))
    preveriEnako("domaci viri: najvec", 2, KanaliPravila.domaciViri(vsi, fv, "sl", "SI", najvec = 2).size)
    preveriEnako("domaci viri brez drzave in jezika", emptyList<Vir>(), KanaliPravila.domaciViri(vsi, fv, "", ""))
    preveriEnako("ponovi: prvic 1 min", 60_000L, KanaliPravila.zamikPonovitve(0, 0))
    preveriEnako("ponovi: tretjic 4 min", 240_000L, KanaliPravila.zamikPonovitve(0, 2))
    preveriEnako("ponovi: najvec 15 min", 900_000L, KanaliPravila.zamikPonovitve(0, 9))
    preveriEnako("ponovi: premor dodatka je daljsi", 600_000L, KanaliPravila.zamikPonovitve(600_000L, 0))
    preveriEnako("ponovi: premor nad mejo", 900_000L, KanaliPravila.zamikPonovitve(5_000_000L, 0))

    println()
    // ---- naloga 74: kanali takoj, preverjanje samo vidnih
    preveriEnako("nalagam: prazna mreza, viri se nalagajo", true, KanaliPravila.nalagamNapis(0, true, true))
    preveriEnako("nalagam: kanali so na zaslonu - ni napisa", false, KanaliPravila.nalagamNapis(12, true, true))
    preveriEnako("nalagam: viri so koncali", false, KanaliPravila.nalagamNapis(0, false, true))
    preveriEnako("nalagam: brez dodatkov", false, KanaliPravila.nalagamNapis(0, true, false))
    val vrhovi = listOf(0, 200, 400, 600, 800, 1000)
    preveriEnako("vidne: na vrhu tri vrste + naslednja", 0..3, KanaliPravila.vidneVrste(vrhovi, 0, 550))
    preveriEnako("vidne: zadnja vrsta zacne tik pod dnom ni vidna", 0..3, KanaliPravila.vidneVrste(vrhovi, 0, 600))
    preveriEnako("vidne: po drsenju (delno vidna zgornja vrsta steje)", 1..4, KanaliPravila.vidneVrste(vrhovi, 250, 500))
    preveriEnako("vidne: na koncu brez naslednje", 4..5, KanaliPravila.vidneVrste(vrhovi, 850, 500))
    preveriEnako("vidne: se ni postavljeno", 0..2, KanaliPravila.vidneVrste(listOf(0, 0, 0, 0), 0, 0))
    preveriEnako("vidne: ena vrsta", 0..0, KanaliPravila.vidneVrste(listOf(0), 0, 0))
    preveriEnako("vidne: prazna mreza", true, KanaliPravila.vidneVrste(emptyList(), 0, 500).isEmpty())
    val kartice = (0 until 20).map { "k$it" }
    preveriEnako("preveri: samo vidne vrste, od vrha, najvec prostih", listOf("k5", "k6", "k7"),
        KanaliPravila.zaPreverjanje(kartice, 1..2, 5, 3) { true })
    preveriEnako("preveri: preskoci znane in v teku", listOf("k6", "k8"),
        KanaliPravila.zaPreverjanje(kartice, 1..1, 5, 3) { it == "k6" || it == "k8" })
    preveriEnako("preveri: zadnja vrsta ni polna", listOf("k18", "k19"),
        KanaliPravila.zaPreverjanje(kartice, 3..4, 5, 9) { it.removePrefix("k").toInt() >= 18 })
    preveriEnako("preveri: ni prostih niti", emptyList<String>(), KanaliPravila.zaPreverjanje(kartice, 0..3, 5, 0) { true })
    preveriEnako("preveri: vrste za koncem mreze", emptyList<String>(), KanaliPravila.zaPreverjanje(kartice, 7..8, 5, 3) { true })
    // Mrtev kanal izgine, ostali ostanejo na svojih mestih (OsPravila.stabilenRed).
    preveriEnako("mrtev izgine brez premescanja", listOf("a", "c", "d", "e"),
        si.safeer.tv.os.OsPravila.stabilenRed(listOf("a", "b", "c", "d"), listOf("e", "d", "c", "a")) { it })

    println()
    // ---- naloga 76: zasebne kategorije na disku
    val Z = 3_600_000L
    val kz = KanaliPravila.kljucZasebnih(listOf("xxx/[]", "adult/[(genre, Adult)]"))
    preveriEnako("kljuc: neodvisen od vrstnega reda in dvojnikov", kz, KanaliPravila.kljucZasebnih(listOf("adult/[(genre, Adult)]", "xxx/[]", "xxx/[]")))
    preveriEnako("kljuc: drug katalog -> drug kljuc", false, kz == KanaliPravila.kljucZasebnih(listOf("xxx/[]")))
    preveriEnako("kljuc: brez berljivih imen", false, "adult" in kz || "xxx" in kz)
    preveriEnako("kljuc: 64 znakov hex", true, kz.length == 64 && kz.all { it in "0123456789abcdef" })
    preveriEnako("zapis: ga ni", KanaliPravila.ZasebniZapis.NI, KanaliPravila.stanjeZasebnih(null, 0, kz, 100 * Z))
    preveriEnako("zapis: drugi katalogi", KanaliPravila.ZasebniZapis.NI, KanaliPravila.stanjeZasebnih("drug", 99 * Z, kz, 100 * Z))
    preveriEnako("zapis: star 1 h velja", KanaliPravila.ZasebniZapis.VELJA, KanaliPravila.stanjeZasebnih(kz, 99 * Z, kz, 100 * Z))
    preveriEnako("zapis: star 24 h zastarel", KanaliPravila.ZasebniZapis.ZASTAREL, KanaliPravila.stanjeZasebnih(kz, 76 * Z, kz, 100 * Z))
    preveriEnako("zapis: star teden zastarel (velja do osvezitve)", KanaliPravila.ZasebniZapis.ZASTAREL, KanaliPravila.stanjeZasebnih(kz, 200 * Z - 7 * 24 * Z, kz, 200 * Z))
    preveriEnako("zapis: ura nazaj -> zastarel", KanaliPravila.ZasebniZapis.ZASTAREL, KanaliPravila.stanjeZasebnih(kz, 101 * Z, kz, 100 * Z))
    preveriEnako("zapis: cas 0 -> ga ni", KanaliPravila.ZasebniZapis.NI, KanaliPravila.stanjeZasebnih(kz, 0, kz, 100 * Z))
    val od = KanaliPravila.odtisi(listOf("stremio|tv|a", "stremio|tv|b", "stremio|tv|a"))
    preveriEnako("odtisi: brez dvojnikov", 2, od.size)
    preveriEnako("odtisi: urejeni", true, od[0] <= od[1])
    preveriEnako("odtisi: id je notri", true, KanaliPravila.vOdtisih(od, "stremio|tv|b"))
    preveriEnako("odtisi: drug id ni", false, KanaliPravila.vOdtisih(od, "stremio|tv|c"))
    preveriEnako("odtisi: prazni", false, KanaliPravila.vOdtisih(LongArray(0), "stremio|tv|a"))
    preveriEnako("odtis id: stalen", KanaliPravila.odtisId("x"), KanaliPravila.odtisId("x"))
    preveriEnako("odtis dodatka: 16 znakov, brez naslova", true, KanaliPravila.odtisDodatka("https://iptv.test").let { it.length == 16 && "iptv" !in it })
    preveriEnako("napis: kategorije, ko viri ze odgovorili", KanaliPravila.NapisCakanja.KATEGORIJE, KanaliPravila.napisCakanja(0, false, true, true))
    preveriEnako("napis: nalagam, dokler viri nalagajo", KanaliPravila.NapisCakanja.NALAGAM, KanaliPravila.napisCakanja(0, true, true, true))
    preveriEnako("napis: kanali na zaslonu - nic", KanaliPravila.NapisCakanja.NIC, KanaliPravila.napisCakanja(5, false, true, true))
    preveriEnako("napis: nic ne caka", KanaliPravila.NapisCakanja.NIC, KanaliPravila.napisCakanja(0, false, false, true))

    println()
    // ---- naloga 82: zaklenjeni viri pri vsaki izbiri, prazna mreza nikoli »Nalagam« za vedno
    val faya = "https://faya.test"
    val zk = listOf(Katalog(iptv, "All IPTV", "All IPTV News"), Katalog(faya, "FayaTV", "News"), Katalog(iptv, "All IPTV", "All IPTV Sports"))
    val fz = KanaliPravila.filtri(zk)
    val novice = KanaliPravila.izbrani(fz, zk, "novice", "").glavni
    preveriEnako("82: zvrst News - oba kataloga po imenu", listOf(Vir(0), Vir(1)), novice)
    preveriEnako("82: zaklenjen dodatek izpuscen tudi pri zvrsti", listOf(Vir(1)) to listOf(Vir(0)),
        KanaliPravila.brezZaklenjenih(novice, zk) { it == iptv })
    preveriEnako("82: brez zaklenjenih vse ostane", novice to emptyList<Vir>(), KanaliPravila.brezZaklenjenih(novice, zk) { false })
    preveriEnako("82: samo zaklenjeni - nic delujocega", emptyList<Vir>(),
        KanaliPravila.brezZaklenjenih(KanaliPravila.izbrani(fz, zk, "sport", "").glavni, zk) { it == iptv }.first)
    preveriEnako("82: kanali na zaslonu - nic", null, KanaliPravila.praznaMreza(3, true, 60_000, true))
    preveriEnako("82: prve sekunde nalaganja - cakaj", KanaliPravila.PraznaMreza.CAKAJ, KanaliPravila.praznaMreza(0, true, 1_000, true))
    preveriEnako("82: nalaganje 3 s - nadomestek", KanaliPravila.PraznaMreza.NADOMESTEK, KanaliPravila.praznaMreza(0, true, 3_000, true))
    preveriEnako("82: nic vec ne pricakujemo - nadomestek takoj", KanaliPravila.PraznaMreza.NADOMESTEK, KanaliPravila.praznaMreza(0, false, 100, true))
    preveriEnako("82: brez nadomestka med nalaganjem - cakaj", KanaliPravila.PraznaMreza.CAKAJ, KanaliPravila.praznaMreza(0, true, 20_000, false))
    preveriEnako("82: brez nadomestka, 45 s - ni vec »Nalagam«", KanaliPravila.PraznaMreza.PRAZNO, KanaliPravila.praznaMreza(0, true, 45_000, false))
    preveriEnako("82: brez nadomestka, koncano - prazno", KanaliPravila.PraznaMreza.PRAZNO, KanaliPravila.praznaMreza(0, false, 0, false))

    // TV SLO 1 in TV SLO 1 HD: dve razlicici, ena mrtva - kartica je vidna; obe mrtvi - skrita.
    val sloImena = listOf("TV SLO 1", "TV SLO 1 HD")
    preveriEnako("TV SLO: oba vnosa sta isti kanal", KanaliPravila.kljucImena(sloImena[0]), KanaliPravila.kljucImena(sloImena[1]))
    preveri("TV SLO: SD mrtev, HD neznan - kartica vidna", KanaliPravila.karticaVidna(listOf(false, null)))
    preveri("TV SLO: SD neznan, HD mrtev - kartica vidna", KanaliPravila.karticaVidna(listOf(null, false)))
    preveri("TV SLO: oba mrtva - kartica skrita", !KanaliPravila.karticaVidna(listOf(false, false)))
    preveriEnako("TV SLO: oba mrtva - ni razlicice za predvajanje", emptyList<Int>(), KanaliPravila.urediRazlicice(sloImena, listOf(false, false)))
    preveriEnako("TV SLO: SD mrtev - predvaja HD", listOf(1), KanaliPravila.urediRazlicice(sloImena, listOf(false, null)))
    preveriEnako("TV SLO: nic ne vemo - najprej HD", listOf(1, 0), KanaliPravila.urediRazlicice(sloImena, listOf(null, null)))
    preveriEnako("TV SLO: znano ziva SD pred neznanim HD", listOf(0, 1), KanaliPravila.urediRazlicice(sloImena, listOf(true, null)))
    preveriEnako("TV SLO: preverjanje zacne pri HD", 1, KanaliPravila.razlicicaZaPreverjanje(sloImena, listOf(null, null)))
    preveriEnako("TV SLO: HD mrtev - preverimo SD", 0, KanaliPravila.razlicicaZaPreverjanje(sloImena, listOf(null, false)))
    preveriEnako("TV SLO: HD znano ziv - ni treba preverjati", null, KanaliPravila.razlicicaZaPreverjanje(sloImena, listOf(null, true)))
    preveriEnako("TV SLO: oba mrtva - ni kaj preverjati", null, KanaliPravila.razlicicaZaPreverjanje(sloImena, listOf(false, false)))

    // Naloga 90d: znani nedelujoci tokovi (streznik odgovori, program je obvestilo).
    preveri("90d: dash2.antik.sk test_slo1_tizen", KanaliPravila.znanNedelujocTok("https://dash2.antik.sk/live/test_slo1_tizen/playlist.m3u8"))
    preveri("90d: dash2.antik.sk test_slo2_tizen", KanaliPravila.znanNedelujocTok("https://dash2.antik.sk/live/test_slo2_tizen/playlist.m3u8"))
    preveri("90d: velike crke in http", KanaliPravila.znanNedelujocTok("HTTP://Dash2.Antik.SK/live/test_x/index.m3u8"))
    preveri("Arena Sport si.m3u: telekomsrbije play", KanaliPravila.znanNedelujocTok("http://telekomsrbije.com:2095/play/abcDEF_123"))
    preveri("telekomsrbije brez /play ni na seznamu", !KanaliPravila.znanNedelujocTok("https://telekomsrbije.com/"))
    // Peti slepi pregled 5. 11. 2026: brez vnosa za dolocenega ponudnika - nedelujoc tok si TV zapomni sproti (24 h).
    preveri("brez imena ponudnika: streamer03 ni vec na seznamu", !KanaliPravila.znanNedelujocTok("https://streamer03.xploretv.si:8443/tvslo1/index.m3u8"))
    preveri("sproten zaznavalnik: kanal z napako je mrtev 24 h", OsPravila.MRTEV_KANAL_MS == 24 * 3_600_000L)
    preveri("90d: dash2.antik.sk/live brez test_ je v redu", !KanaliPravila.znanNedelujocTok("https://dash2.antik.sk/live/slo1/playlist.m3u8"))
    preveri("90d: vzorec v poti drugega streznika je v redu", !KanaliPravila.znanNedelujocTok("https://example.org/dash2.antik.sk/live/test_a.m3u8"))
    preveri("90d: prazen naslov", !KanaliPravila.znanNedelujocTok(""))
    preveriEnako("90d: kljuci kartice z razlicicami", listOf("iptv:a", "iptv:b"), KanaliPravila.kljuciKartice("iptv:a", listOf("iptv:a", "iptv:b#2", "iptv:b")))
    preveriEnako("90d: kljuci kartice brez razlicic", listOf("iptv:a"), KanaliPravila.kljuciKartice("iptv:a", emptyList()))

    // Naloga 91a: Arena Sport 1 Premium - razlicici iz dodatka (0, 2) in iz seznama IPTV (1, 3).
    val arena = listOf("Arena Sport 1 Premium (Slovenia)", "Arena Sport 1 Premium", "Arena Sport 1 Premium HD", "Arena Sport 1 Premium HD")
    val izIptv = listOf(false, true, false, true)
    val brez = listOf(false, false, false, false)
    preveriEnako("91a: nic ne vemo - prva razlicica seznama s HD", 3, KanaliPravila.rezervaIptv(arena, listOf(null, null, null, null), izIptv, brez))
    preveriEnako("91a: znano ziva razlicica seznama gre prva", 1, KanaliPravila.rezervaIptv(arena, listOf(null, true, null, null), izIptv, brez))
    preveriEnako("91a: mrtva razlicica seznama izpade", 1, KanaliPravila.rezervaIptv(arena, listOf(null, null, null, false), izIptv, brez))
    preveriEnako("91a: znan nedelujoc tok izpade", 1, KanaliPravila.rezervaIptv(arena, listOf(null, null, null, null), izIptv, listOf(false, false, false, true)))
    preveriEnako("91a: ziva razlicica dodatka ni rezerva", 1, KanaliPravila.rezervaIptv(arena, listOf(true, null, true, null), izIptv, listOf(false, false, false, true)))
    preveriEnako("91a: vse razlicice seznama mrtve - ni rezerve", null, KanaliPravila.rezervaIptv(arena, listOf(null, false, null, false), izIptv, brez))
    preveriEnako("91a: brez razlicic seznama - ni rezerve", null, KanaliPravila.rezervaIptv(arena, listOf(null, null, null, null), brez, brez))

    // Naloga 93: kartica z glavo iz seznama IPTV (0) in razlicicama dodatka (1, 2) - meritev 23. 10. 13:15.
    val arenaS = listOf("Arena Sport 1 Premium (Slovenia)", "Arena Sport 1 Premium (Slovenia)", "Arena Sport 1 Premium (Slovenia)")
    val dod = listOf(false, true, true)
    val sez = listOf(true, false, false)
    val nic = listOf(false, false, false)
    preveriEnako("93: nic ne vemo - glava iz seznama, brez dodatka", KanaliPravila.KlikKanala(0, emptyList(), null),
        KanaliPravila.klikKanala(arenaS, listOf(null, null, null), dod, sez, nic))
    preveriEnako("93: znano ziv dodatek gre prvi, z rezervo iz seznama", KanaliPravila.KlikKanala(1, listOf(1, 2), 0),
        KanaliPravila.klikKanala(arenaS, listOf(null, true, null), dod, sez, nic))
    preveriEnako("93: drugi dodatek znano ziv - prvi v poizvedbi je on", KanaliPravila.KlikKanala(2, listOf(2, 1), 0),
        KanaliPravila.klikKanala(arenaS, listOf(null, null, true), dod, sez, nic))
    preveriEnako("93: razlicica seznama mrtva - dodatek brez rezerve", KanaliPravila.KlikKanala(1, listOf(1, 2), null),
        KanaliPravila.klikKanala(arenaS, listOf(false, null, null), dod, sez, nic))
    preveriEnako("93: vse mrtve - ni klika", null, KanaliPravila.klikKanala(arenaS, listOf(false, false, false), dod, sez, nic))
    preveriEnako("93: znan nedelujoc tok seznama ni rezerva", KanaliPravila.KlikKanala(1, listOf(1, 2), null),
        KanaliPravila.klikKanala(arenaS, listOf(null, true, null), dod, sez, listOf(true, false, false)))
    preveriEnako("100: glava seznama z znanim nedelujocim tokom ni prva", KanaliPravila.KlikKanala(1, listOf(1, 2), null),
        KanaliPravila.klikKanala(arenaS, listOf(null, null, null), dod, sez, listOf(true, false, false)))
    preveriEnako("100: edina razlicica z znanim nedelujocim tokom - ni klika", null,
        KanaliPravila.klikKanala(listOf("TV SLO 1"), listOf(null), listOf(false), listOf(true), listOf(true)))
    val jeSeznam = { id: String -> id.startsWith("iptv:") }
    preveriEnako("93: izpad dodatka ne skrije razlicice seznama", listOf("stremio|tv|a|1", "stremio|tv|b|1"),
        KanaliPravila.mrtveObNeuspehuDodatka("stremio|tv|a|1", listOf("iptv:http://x.test/1", "stremio|tv|a|1", "stremio|tv|b|1"), jeSeznam))
    preveriEnako("93: kartica brez razlicic - samo ona", listOf("stremio|tv|a|1"), KanaliPravila.mrtveObNeuspehuDodatka("stremio|tv|a|1", emptyList(), jeSeznam))

    // Naloga 96: oznaka vira kanala seznama IPTV (»Live TV · si.m3u«, ne »PeerTube · ...«).
    preveriEnako("96: iptv-org seznam drzave", "si.m3u", KanaliPravila.imeSeznama("https://iptv-org.github.io/iptv/countries/si.m3u"))
    preveriEnako("96: brez poizvedbe", "playlist.m3u", KanaliPravila.imeSeznama("http://a.test/get/playlist.m3u?token=x#y"))
    preveriEnako("96: geslo v naslovu se ne pokaze", "a.test", KanaliPravila.imeSeznama("http://uporabnik:geslo@a.test/"))
    preveriEnako("96: samo gostitelj", "a.test", KanaliPravila.imeSeznama("https://a.test"))
    preveriEnako("96: poizvedba z geslom brez poti", "a.test", KanaliPravila.imeSeznama("http://a.test?username=u&password=p"))
    preveriEnako("96: geslo kot zadnji del poti se ne pokaze", "a.test", KanaliPravila.imeSeznama("http://a.test/playlist/uporabnik/geslo"))
    preveriEnako("100: zeton kot ime seznama se ne pokaze", "a.test", KanaliPravila.imeSeznama("http://a.test/playlist/lhpjmkfVyhON1JAiZCx7vhWLdkJ.m3u"))
    preveriEnako("100: ne-seznam s piko se ne pokaze", "a.test", KanaliPravila.imeSeznama("http://a.test/play/abc.def"))
    preveriEnako("100: stevilka narocnika se ne pokaze", "a.test", KanaliPravila.imeSeznama("http://a.test/get/123456789.m3u8"))
    preveriEnako("100: m3u8 z imenom", "index.m3u8", KanaliPravila.imeSeznama("https://a.test/live/index.m3u8"))
    preveriEnako("100: predolgo ime", "a.test", KanaliPravila.imeSeznama("https://a.test/aaaa_bbbb_cccc_dddd_eeee_ffff.m3u"))
    preveriEnako("100: samo koncnica", "a.test", KanaliPravila.imeSeznama("https://a.test/.m3u"))

    // Naloga 91b: jeziki izbire takoj ob odprtju.
    preveriEnako("91b: dodatki se niso odgovorili - shranjeni in seznami", listOf("sl", "en", "hr"),
        KanaliPravila.jezikiKanalov(null, listOf("sl", "en"), listOf("hr", "sl")))
    preveriEnako("91b: prazni sveze jeziki - shranjeni", listOf("sl", "de"), KanaliPravila.jezikiKanalov(emptyList(), listOf("sl"), listOf("de")))
    preveriEnako("91b: sveze jeziki imajo prednost pred shranjenimi", listOf("en", "hr"), KanaliPravila.jezikiKanalov(listOf("en"), listOf("sl"), listOf("hr")))
    preveriEnako("91b: prazna shramba (split) brez praznega jezika", listOf("sl"), KanaliPravila.jezikiKanalov(null, "".split(','), listOf("sl", "")))

    // Naloga 91c: Recent - (ime, je kanal, ne deluje).
    val nedavni = listOf(Triple("TV SLO 1", true, true), Triple("TV SLO 1 HD", true, false), Triple("Film", false, false),
        Triple("Radio 1", false, true), Triple("Arena Sport 1", true, false))
    preveriEnako("91c: nedelujoc kanal in njegova razlicica izpadeta, mrtva postaja tudi", listOf("Film", "Arena Sport 1"),
        KanaliPravila.brezNedelujocihKanalov(nedavni, { it.second }, { it.first }, { it.third }).map { it.first })
    preveriEnako("91c: ime ne-kanala ne skrije kanala", listOf("TV SLO 1"),
        KanaliPravila.brezNedelujocihKanalov(listOf(Triple("TV SLO 1", false, true), Triple("TV SLO 1", true, false)), { it.second }, { it.first }, { it.third }).map { it.first })
    preveriEnako("91c: vse deluje - nic ne izpade", 5,
        KanaliPravila.brezNedelujocihKanalov(nedavni, { it.second }, { it.first }, { false }).size)

    // Naloga 121: nedelujoc kanal se ne kaze nikjer
    preveriEnako("121: mrtev kanal skrit", true, KanaliPravila.skrijNedelujoc(mrtev = true, jeKanalSeznama = false, nedelujocTok = false))
    preveriEnako("121: kanal seznama z znano nedelujocim tokom skrit", true, KanaliPravila.skrijNedelujoc(mrtev = false, jeKanalSeznama = true, nedelujocTok = true))
    preveriEnako("121: film z enakim naslovom toka ni kanal - ostane", false, KanaliPravila.skrijNedelujoc(mrtev = false, jeKanalSeznama = false, nedelujocTok = true))
    preveriEnako("121: neznano stanje ostane", false, KanaliPravila.skrijNedelujoc(mrtev = false, jeKanalSeznama = true, nedelujocTok = false))

    // Zunanji pregled 3. 11. 2026: placljivi kanali v mrezi TV v zivo.
    run {
        val P = KanaliPravila
        preveriEnako("dodatek, se ne vemo: preveri (ni na zaslonu)", KanaliPravila.Prikaz.PREVERI, P.vMrezi(KanaliPravila.VrstaKanala.DODATEK, null, preverjam = true, pokaziZaklenjene = false))
        // Neodvisni pregled 4. 11. 2026: dodatek s poverilnico ozadje ne sme vprasati - kanal ne sme ostati skrit.
        preveriEnako("dodatek s poverilnico: prikazi (ne caka na preverbo)", KanaliPravila.Prikaz.PRIKAZI,
            P.vMrezi(KanaliPravila.VrstaKanala.DODATEK, null, preverjam = true, pokaziZaklenjene = false, preverljiv = false))
        preveriEnako("dodatek s predvajljivim tokom: prikazi", KanaliPravila.Prikaz.PRIKAZI, P.vMrezi(KanaliPravila.VrstaKanala.DODATEK, true, true, false))
        preveriEnako("dodatek mrtev: skrij", KanaliPravila.Prikaz.SKRIJ, P.vMrezi(KanaliPravila.VrstaKanala.DODATEK, false, true, false))
        preveriEnako("dodatek, omejeno omrezje: kot doslej", KanaliPravila.Prikaz.PRIKAZI, P.vMrezi(KanaliPravila.VrstaKanala.DODATEK, null, preverjam = false, pokaziZaklenjene = false))
        preveriEnako("dodatek, Pokazi zaklenjene: prikazi", KanaliPravila.Prikaz.PRIKAZI, P.vMrezi(KanaliPravila.VrstaKanala.DODATEK, null, true, pokaziZaklenjene = true))
        preveriEnako("vgrajen: hitro risanje ostane", KanaliPravila.Prikaz.PRIKAZI, P.vMrezi(KanaliPravila.VrstaKanala.VGRAJEN, null, true, false))
        preveriEnako("racun IPTV: ves, brez cakanja", KanaliPravila.Prikaz.PRIKAZI, P.vMrezi(KanaliPravila.VrstaKanala.IPTV_RACUN, null, true, false))
        preveriEnako("javni IPTV: hitro risanje ostane", KanaliPravila.Prikaz.PRIKAZI, P.vMrezi(KanaliPravila.VrstaKanala.IPTV_JAVNI, null, true, false))
        // Lastnik 4. 11. 2026: kanal, ki ga je skril uporabnik.
        preveriEnako("skrit kanal: ni v mrezi", true, P.mrtevZaMrezo(skrit = true, pokaziSkrite = false, mrtevDo = null, zdaj = 1000))
        preveriEnako("skrit kanal: s Pokazi skrite viden", false, P.mrtevZaMrezo(skrit = true, pokaziSkrite = true, mrtevDo = null, zdaj = 1000))
        preveriEnako("skrit in ne deluje: s Pokazi skrite ostane skrit", true, P.mrtevZaMrezo(skrit = true, pokaziSkrite = true, mrtevDo = 5000, zdaj = 1000))
        preveriEnako("oznaka mrtvega potekla", false, P.mrtevZaMrezo(skrit = false, pokaziSkrite = false, mrtevDo = 500, zdaj = 1000))
        preveriEnako("fokus po skritju: naslednja", 3, P.fokusPoSkritju(3, 10))
        preveriEnako("fokus po skritju zadnje: prejsnja", 8, P.fokusPoSkritju(9, 10))
        preveriEnako("fokus po skritju edine: nic", -1, P.fokusPoSkritju(0, 1))
        // Lastnik 4. 11. 2026 (05:08): skriti kanali v ozjem krogu.
        val a = P.odtisKanala("iptv:http://x/a"); val b = P.odtisKanala("iptv:http://x/b")
        val prazno = KanaliPravila.SkritiKanali(emptyMap(), emptyMap())
        preveriEnako("odtis brez naslova toka", false, a.contains("http"))
        preveriEnako("uskladitev: skrit tam - skrit tu", setOf(a), P.prevzemiSkrite(prazno, KanaliPravila.SkritiKanali(mapOf(a to 100L), emptyMap()), ozji = true).skriti.keys)
        preveriEnako("uskladitev: zunaj ozjega kroga nic", prazno, P.prevzemiSkrite(prazno, KanaliPravila.SkritiKanali(mapOf(a to 100L), emptyMap()), ozji = false))
        preveriEnako("umik: pokazan tam pozneje - pokazan tu", emptySet<String>(),
            P.prevzemiSkrite(KanaliPravila.SkritiKanali(mapOf(a to 100L), emptyMap()), KanaliPravila.SkritiKanali(emptyMap(), mapOf(a to 200L)), true).skriti.keys)
        preveriEnako("umik: pokazan tam prej - ostane skrit", setOf(a),
            P.prevzemiSkrite(KanaliPravila.SkritiKanali(mapOf(a to 300L), emptyMap()), KanaliPravila.SkritiKanali(emptyMap(), mapOf(a to 200L)), true).skriti.keys)
        preveriEnako("tu pokazan pozneje: ne skrijemo znova", emptySet<String>(),
            P.prevzemiSkrite(KanaliPravila.SkritiKanali(emptyMap(), mapOf(a to 500L)), KanaliPravila.SkritiKanali(mapOf(a to 400L), emptyMap()), true).skriti.keys)
        preveriEnako("zdruzevanje obeh", setOf(a, b),
            P.prevzemiSkrite(KanaliPravila.SkritiKanali(mapOf(a to 1L), emptyMap()), KanaliPravila.SkritiKanali(mapOf(b to 2L), emptyMap()), true).skriti.keys)
        preveriEnako("neveljaven kljuc (naslov) se zavrne", emptySet<String>(),
            P.prevzemiSkrite(prazno, KanaliPravila.SkritiKanali(mapOf("iptv:http://u:g@x/live" to 5L), emptyMap()), true).skriti.keys)
        // Neodvisni pregled 4. 11. 2026 09:10: cas iz prihodnosti se poreze, stevilo vnosov je omejeno.
        val zdaj = 1_000_000_000L
        val prihodnost = P.prevzemiSkrite(prazno, KanaliPravila.SkritiKanali(mapOf(a to Long.MAX_VALUE), emptyMap()), true, zdaj)
        preveriEnako("cas iz prihodnosti: porezan na zdaj", zdaj, prihodnost.skriti[a])
        preveriEnako("cas iz prihodnosti: poznejsi prikaz tu ga premaga", emptySet<String>(),
            P.prevzemiSkrite(prihodnost, KanaliPravila.SkritiKanali(emptyMap(), mapOf(a to zdaj + 1)), true, zdaj + 1).skriti.keys)
        preveriEnako("razlika ur do 10 min velja", zdaj + 5 * 60_000L,
            P.prevzemiSkrite(prazno, KanaliPravila.SkritiKanali(mapOf(a to zdaj + 5 * 60_000L), emptyMap()), true, zdaj).skriti[a])
        preveriEnako("pokazan iz prihodnosti: porezan", zdaj,
            P.prevzemiSkrite(prazno, KanaliPravila.SkritiKanali(emptyMap(), mapOf(b to Long.MAX_VALUE / 2)), true, zdaj).pokazani[b])
        val mnogo = KanaliPravila.SkritiKanali((0 until 1500).associate { P.odtisKanala("s$it") to 10L }, (0 until 1500).associate { P.odtisKanala("p$it") to 10L })
        var stanje = P.prevzemiSkrite(prazno, mnogo, true, zdaj)
        preveriEnako("skupaj najvec SKRITI_SKUPAJ", KanaliPravila.SKRITI_SKUPAJ, stanje.skriti.size + stanje.pokazani.size)
        for (krog in 0 until 3) stanje = P.prevzemiSkrite(stanje, KanaliPravila.SkritiKanali((0 until 2000).associate { P.odtisKanala("n$krog-$it") to 10L }, emptyMap()), true, zdaj)
        preveriEnako("vec sporocil ne preseze meje", KanaliPravila.SKRITI_SKUPAJ, stanje.skriti.size + stanje.pokazani.size)
        val polno = P.prevzemiSkrite(prazno, mnogo, true, zdaj)
        val znan = polno.skriti.keys.first()
        preveriEnako("pri polni meji: znan kanal se se vedno pokaze", true,
            P.prevzemiSkrite(polno, KanaliPravila.SkritiKanali(emptyMap(), mapOf(znan to 20L)), true, zdaj).pokazani.containsKey(znan))
        preveriEnako("izvoz samo v ozji krog", null, P.izvozSkritih(prazno, ozji = false))
        // Lastnik 4. 11. 2026: izbira vec kanalov.
        preveriEnako("izbira: OK oznaci", setOf("a", "b"), P.preklopiIzbiro(setOf("a"), "b"))
        preveriEnako("izbira: OK znova odznaci", setOf("a"), P.preklopiIzbiro(setOf("a", "b"), "b"))
        preveriEnako("skrij vec", setOf("x", "a", "b"), P.skrijVec(setOf("x"), listOf("a", "b")))
        preveriEnako("pokazi vec", setOf("x"), P.pokaziVec(setOf("x", "a", "b"), listOf("a", "b")))
        preveriEnako("fokus po skritju vec: najblizja (levo blizje)", 1, P.fokusPoSkritjuVec(2, 6, setOf(2, 3)))
        preveriEnako("fokus po skritju vec: enako dalec - desno", 2, P.fokusPoSkritjuVec(2, 6, setOf(2)))
        preveriEnako("fokus po skritju vec: na koncu levo", 1, P.fokusPoSkritjuVec(5, 6, setOf(2, 3, 4, 5)))
        preveriEnako("fokus po skritju vseh: nic", -1, P.fokusPoSkritjuVec(0, 2, setOf(0, 1)))
        // Odlocitev lastnika 4. 11. 2026: neznan seznam skrit in nikoli preverjen.
        preveriEnako("neznan seznam: privzeto skrit", KanaliPravila.Prikaz.SKRIJ, P.vMrezi(KanaliPravila.VrstaKanala.IPTV_NEZNAN, null, true, false))
        preveriEnako("neznan seznam: dokazano ni medij - skrij", KanaliPravila.Prikaz.SKRIJ, P.vMrezi(KanaliPravila.VrstaKanala.IPTV_NEZNAN, false, true, false))
        preveriEnako("sonda: neznan seznam nikoli", false, P.smeSondaSeznama(KanaliPravila.VrstaKanala.IPTV_NEZNAN, tokSPoverilnico = false))
        preveriEnako("sonda: javni da", true, P.smeSondaSeznama(KanaliPravila.VrstaKanala.IPTV_JAVNI, tokSPoverilnico = false))
        preveriEnako("sonda: racun nikoli", false, P.smeSondaSeznama(KanaliPravila.VrstaKanala.IPTV_RACUN, tokSPoverilnico = false))
        preveriEnako("sonda: tok z geslom nikoli", false, P.smeSondaSeznama(KanaliPravila.VrstaKanala.IPTV_NEZNAN, tokSPoverilnico = true))
        preveriEnako("neznan seznam s Pokazi zaklenjene", KanaliPravila.Prikaz.PRIKAZI, P.vMrezi(KanaliPravila.VrstaKanala.IPTV_NEZNAN, null, true, true))
        preveriEnako("seznam s poverilnico: racun", KanaliPravila.VrstaKanala.IPTV_RACUN, P.vrstaIptv(racun = true, javni = false))
        preveriEnako("iptv-org: javni", KanaliPravila.VrstaKanala.IPTV_JAVNI, P.vrstaIptv(racun = false, javni = true))
        preveriEnako("drugo: neznan", KanaliPravila.VrstaKanala.IPTV_NEZNAN, P.vrstaIptv(racun = false, javni = false))
        preveriEnako("JSON tokov: dodatek brez poverilnice", true, P.smeVprasatiTokove(sPoverilnico = false, nauceno = false, predvajanje = false))
        preveriEnako("JSON tokov: nikoli z debrid/Usenet kljucem", false, P.smeVprasatiTokove(sPoverilnico = true, nauceno = false, predvajanje = false))
        preveriEnako("JSON tokov: ne, ko je dodatek dal tok z racunom", false, P.smeVprasatiTokove(false, nauceno = true, predvajanje = false))
        preveriEnako("JSON tokov: ne med predvajanjem", false, P.smeVprasatiTokove(false, false, predvajanje = true))
        preveriEnako("privzeto: domac s tokom", true, P.vgrajenVPrivzetem(brezIzbire = true, domac = true, jeStran = false))
        preveriEnako("privzeto: tuj ne", false, P.vgrajenVPrivzetem(true, domac = false, jeStran = false))
        preveriEnako("privzeto: samo uradna stran ne", false, P.vgrajenVPrivzetem(true, domac = true, jeStran = true))
        preveriEnako("izbira: tudi tuj in stran", true, P.vgrajenVPrivzetem(brezIzbire = false, domac = false, jeStran = true))
    }

    if (napak == 0) println("KanaliPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
