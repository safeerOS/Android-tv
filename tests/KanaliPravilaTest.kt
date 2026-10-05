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
    if (napak == 0) println("KanaliPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
