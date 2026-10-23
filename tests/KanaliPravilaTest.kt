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

    if (napak == 0) println("KanaliPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
