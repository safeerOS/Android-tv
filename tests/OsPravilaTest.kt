package si.safeer.tv.os

/**
 * Pravila Safeer OS brez televizorja: varne mere ikon, vrstica Nadaljuj in njene ikone,
 * kartica "Zaslon racunalnika". Pade, ce kdo spremeni vedenje, ki ga uporabnik vidi.
 */

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    preveri("$opis (pričakovano=$pricakovano, dobljeno=$dobljeno)", pricakovano == dobljeno)

private data class V(val kljuc: String, val ime: String = kljuc)

private fun preizkusMer() {
    println("\n== mere ikon ==")
    preveriEnako("majhna ikona ostane cela", 1, OsPravila.vzorec(128, 128, 512))
    preveriEnako("ikona tocno na meji ostane cela", 1, OsPravila.vzorec(512, 512, 512))
    preveriEnako("1024 se pomanjsa na polovico", 2, OsPravila.vzorec(1024, 1024, 512))
    preveriEnako("visoka ozka slika se pomanjsa po daljsi stranici", 8, OsPravila.vzorec(64, 4000, 512))
    preveriEnako("sirina 8192 je se sprejeta (pomanjsana)", 16, OsPravila.vzorec(8192, 8192, 512))
    preveriEnako("ogromna slika se ne dekodira", 0, OsPravila.vzorec(20000, 20000, 512))
    preveriEnako("ogromna v eni smeri se ne dekodira", 0, OsPravila.vzorec(100, 50000, 512))
    preveriEnako("nicelne mere (ni slika) se ne dekodirajo", 0, OsPravila.vzorec(0, 0, 512))
    preveriEnako("negativne mere se ne dekodirajo", 0, OsPravila.vzorec(-1, 10, 512))
    for ((s, v) in listOf(513 to 513, 999 to 10, 4096 to 4096, 8192 to 1, 1 to 8192)) {
        val n = OsPravila.vzorec(s, v, 512)
        preveri("$s x $v: daljsa stranica po pomanjsanju najvec 512", n > 0 && maxOf(s, v) / n <= 512)
    }
}

private fun preizkusNadaljuj() {
    println("\n== vrstica Nadaljuj ==")
    val stari = listOf(V("a"), V("b"), V("c"))
    preveriEnako("nov vnos gre na vrh", listOf("d", "a", "b", "c"),
        OsPravila.naVrh(V("d"), stari, 6) { it.kljuc }.map { it.kljuc })
    // Ponovni zagon programa (tudi s pripete kartice): se ne podvoji, ampak gre na vrh.
    preveriEnako("ponovno odprt program se premakne na vrh, ne podvoji", listOf("c", "a", "b"),
        OsPravila.naVrh(V("c", "novo ime"), stari, 6) { it.kljuc }.map { it.kljuc })
    preveriEnako("na vrhu je novi zapis (sveze ime)", "novo ime",
        OsPravila.naVrh(V("c", "novo ime"), stari, 6) { it.kljuc }.first().ime)
    val polno = (1..6).map { V("p$it") }
    val po = OsPravila.naVrh(V("nov"), polno, 6) { it.kljuc }
    preveriEnako("vrstica ne zraste cez najvec", 6, po.size)
    preveriEnako("najstarejsi izpade", false, po.any { it.kljuc == "p6" })
    preveriEnako("prazna vrstica dobi en vnos", 1, OsPravila.naVrh(V("x"), emptyList(), 6) { it.kljuc }.size)
}

private fun preizkusIkon() {
    println("\n== ikone vrstice Nadaljuj ==")
    val a = OsPravila.imeIkone("program|pc|app:kalkulator")
    val b = OsPravila.imeIkone("program|pc|app:igra")
    preveri("ime ikone je stabilno", a == OsPravila.imeIkone("program|pc|app:kalkulator"))
    preveri("razlicni programi, razlicni datoteki", a != b)
    preveri("ime je varno ime datoteke", Regex("[0-9a-f]+\\.png").matches(a))
    preveriEnako("ikona odstranjene vrstice se izbrise", listOf(b),
        OsPravila.odvecneIkone(listOf(a, b), listOf("program|pc|app:kalkulator")))
    preveriEnako("tuje datoteke v mapi se pobrisejo", listOf("smet.tmp"),
        OsPravila.odvecneIkone(listOf(a, "smet.tmp"), listOf("program|pc|app:kalkulator")))
    preveriEnako("prazna vrstica pobrise vse", listOf(a, b), OsPravila.odvecneIkone(listOf(a, b), emptyList()))
}

private fun preizkusZaslona() {
    println("\n== kartica Zaslon racunalnika ==")
    preveri("celo namizje zapise kartico Zaslon", OsPravila.zapisiZaslon(naLocenemZaslonu = false))
    preveri("program na locenem zaslonu NE zapise kartice Zaslon", !OsPravila.zapisiZaslon(naLocenemZaslonu = true))
}

private fun pasMedVideom() {
    // Daljinec: odprta vrsta kartic pas drzi, sicer se skrije (tudi med pavzo - kot doslej).
    preveri("daljinec: brez vrste se pas skrije", OsPravila.pasSeSkrije(dotik = false, vrstaOdprta = false, tece = true))
    preveri("daljinec: brez vrste se pas skrije tudi med pavzo", OsPravila.pasSeSkrije(dotik = false, vrstaOdprta = false, tece = false))
    preveri("daljinec: odprta vrsta pas drzi", !OsPravila.pasSeSkrije(dotik = false, vrstaOdprta = true, tece = true))
    // Dotik: ko video tece, se pas skrije tudi z odprto vrsto kartic; med pavzo ostane.
    preveri("dotik: video tece, vrsta odprta - pas se skrije", OsPravila.pasSeSkrije(dotik = true, vrstaOdprta = true, tece = true))
    preveri("dotik: video tece, vrsta zaprta - pas se skrije", OsPravila.pasSeSkrije(dotik = true, vrstaOdprta = false, tece = true))
    preveri("dotik: pavza - pas ostane", !OsPravila.pasSeSkrije(dotik = true, vrstaOdprta = true, tece = false) &&
        !OsPravila.pasSeSkrije(dotik = true, vrstaOdprta = false, tece = false))
    preveri("po pavzi se pas skrije prej kot sicer", OsPravila.pasZamik(poPavzi = true) < OsPravila.pasZamik(poPavzi = false) &&
        OsPravila.pasZamik(poPavzi = true) >= 1_000L)
    // Odhod z zaslona predvajanja: video se ustavi, razen ce se je medtem zacelo novo predvajanje ali ga kaze drug zaslon.
    preveri("odhod (Nazaj, Domov): video se ustavi", !OsPravila.novoPredvajanjeObOdhodu(zagonovObOdhodu = 7, zagonovZdaj = 7, drugihZaslonov = 0))
    preveri("nov posnetek, zagnan med odhodom starega zaslona, se ne ustavi",
        OsPravila.novoPredvajanjeObOdhodu(zagonovObOdhodu = 7, zagonovZdaj = 8, drugihZaslonov = 0))
    preveri("video ze kaze drug zaslon predvajanja: se ne ustavi",
        OsPravila.novoPredvajanjeObOdhodu(zagonovObOdhodu = 7, zagonovZdaj = 7, drugihZaslonov = 1))
    preveri("onPause ni zabelezen: staro vedenje (video se ustavi)",
        !OsPravila.novoPredvajanjeObOdhodu(zagonovObOdhodu = -1, zagonovZdaj = 3, drugihZaslonov = 0))
    preveri("stevec na zacetku (0 zagonov) ne pomeni novega predvajanja",
        !OsPravila.novoPredvajanjeObOdhodu(zagonovObOdhodu = 0, zagonovZdaj = 0, drugihZaslonov = 0))
}

private fun preizkusIkonNaprav() {
    println("\n== ikone naprav v Safeer Linku ==")
    preveriEnako("telefon", "telefon", OsPravila.ikonaNaprave("phone", false))
    preveriEnako("telefon, ki deli datoteke, ostane telefon", "telefon", OsPravila.ikonaNaprave("phone", true))
    preveriEnako("tablica, ki deli datoteke, ostane tablica (prej ikona racunalnika)", "tablica", OsPravila.ikonaNaprave("tablet", true))
    preveriEnako("racunalnik z Linuxom", "racunalnik", OsPravila.ikonaNaprave("linux", true))
    preveriEnako("racunalnik z Windows brez deljenih datotek", "racunalnik", OsPravila.ikonaNaprave("windows", false))
    preveriEnako("televizor", "naprava", OsPravila.ikonaNaprave("tv", true))
    preveriEnako("naprava brez platforme, ki deli datoteke, je racunalnik (kot doslej)", "racunalnik", OsPravila.ikonaNaprave("", true))
    preveriEnako("naprava brez platforme in brez datotek", "naprava", OsPravila.ikonaNaprave("", false))
}

private fun preizkusOpisaNaprav() {
    println("\n== podnapis naprav v Safeer Linku ==")
    fun opis(platforma: String, id: String, ime: String, prikazano: String = ime, naslov: String = "10.0.0.7") =
        OsPravila.opisNaprave(platforma, id, ime, prikazano, naslov)
    preveriEnako("racunalnik s Safeer Control: program v podnapisu, ker ga prikazano ime nima",
        "racunalnik" to "Safeer Control", opis("windows", "n-1-control", "Safeer Control (PISARNA)", "PISARNA"))
    preveriEnako("racunalnik z brskalnikom: ime ze pove program", "racunalnik" to "",
        opis("linux", "n-2", "Safeer Browser (dnevna-soba)"))
    preveriEnako("preimenovan racunalnik z brskalnikom", "racunalnik" to "Safeer Browser", opis("linux", "n-2", "Stari prenosnik"))
    preveriEnako("telefon s Safeer OS ni vec »Safeer Browser · Android«", "telefon" to "", opis("phone", "n-3", "Safeer OS (LE2113)"))
    preveriEnako("telefon s Safeer OS Mobile", "telefon" to "", opis("phone", "n-4", "Safeer OS Mobile (SM-S931B)"))
    preveriEnako("preimenovan telefon: programa ne ugibamo", "telefon" to "", opis("phone", "n-5", "Lastnikov telefon"))
    preveriEnako("telefon s Safeer Browserjem", "telefon" to "Safeer Browser", opis("phone", "n-6", "Safeer (Pixel 8)"))
    preveriEnako("preimenovana tablica", "tablica" to "Safeer OS", opis("tablet", "n-7", "Tablica v kuhinji"))
    preveriEnako("tablica s privzetim imenom", "tablica" to "", opis("tablet", "n-8", "Safeer OS Tablet (SM-X210)"))
    preveriEnako("televizor", "tv" to "Safeer Link", opis("tv", "n-9", "Safeer TV (Philips)"))
    preveriEnako("starejsi televizor brez platforme", "tv" to "Safeer Link", opis("", "tv-philips", "Televizor"))
    preveriEnako("sredisce brez platforme (naslov zanke)", "tv" to "Safeer Link", opis("", "x1", "Dnevna soba", naslov = "127.0.0.1"))
    preveriEnako("starejsi racunalnik brez platforme", "racunalnik" to "Safeer Control", opis("", "pc-abc-control", "Pisarna"))
    preveriEnako("starejsi telefon brez platforme", "telefon" to "Safeer Browser", opis("", "phone-pixel", "Pixel"))
    preveriEnako("neznana naprava ostane brez podnapisa", "" to "", opis("", "n-10", "Nekaj"))
    preveriEnako("program telefona: privzeto ime huba", "Safeer Browser", OsPravila.programTelefona("Safeer telefon (Pixel 8)"))
    preveriEnako("program telefona: »Safeer« sredi imena ni dovolj", "", OsPravila.programTelefona("Moj Safeer (Pixel 8)"))
}

private fun preizkusPovezave() {
    println("\n== prejeto besedilo: ena sama povezava ==")
    preveriEnako("https povezava", true, OsPravila.jePovezava("https://safeer.si/prenos"))
    preveriEnako("http povezava s presledki okoli", true, OsPravila.jePovezava("  http://10.0.0.7:8080/a?b=1\n"))
    preveriEnako("stavek s povezavo ni povezava", false, OsPravila.jePovezava("poglej https://safeer.si"))
    preveriEnako("povezava in se kaj za njo", false, OsPravila.jePovezava("https://safeer.si in se nekaj"))
    preveriEnako("dve vrstici", false, OsPravila.jePovezava("https://safeer.si\nhttps://example.org"))
    preveriEnako("samo shema", false, OsPravila.jePovezava("https://"))
    preveriEnako("navadno besedilo", false, OsPravila.jePovezava("Kupi kruh"))
    preveriEnako("druga shema", false, OsPravila.jePovezava("javascript:alert(1)"))
    preveriEnako("prazno", false, OsPravila.jePovezava(""))
}

private fun preizkusTipkMenija() {
    println("\n== stranska vrstica ob brskalniku: tipke daljinca ==")
    preveriEnako("GOR gre meniju (prej v naslovno vrstico strani)", OsPravila.TipkaMenija.MENIJU, OsPravila.tipkaVMeniju(19, true, 0))
    preveriEnako("DOL gre meniju", OsPravila.TipkaMenija.MENIJU, OsPravila.tipkaVMeniju(20, true, 0))
    preveriEnako("OK gre meniju (pritisk)", OsPravila.TipkaMenija.MENIJU, OsPravila.tipkaVMeniju(23, true, 0))
    preveriEnako("OK gre meniju (spust - brez njega ni klika)", OsPravila.TipkaMenija.MENIJU, OsPravila.tipkaVMeniju(23, false, 0))
    preveriEnako("Enter gre meniju", OsPravila.TipkaMenija.MENIJU, OsPravila.tipkaVMeniju(66, true, 0))
    preveriEnako("LEVO ne naredi nicesar (prej premik v strani in zaprt meni)", OsPravila.TipkaMenija.NIC, OsPravila.tipkaVMeniju(21, true, 0))
    preveriEnako("DESNO vrne v stran", OsPravila.TipkaMenija.V_VSEBINO, OsPravila.tipkaVMeniju(22, true, 0))
    preveriEnako("spust tipke DESNO ne naredi nicesar", OsPravila.TipkaMenija.NIC, OsPravila.tipkaVMeniju(22, false, 0))
    preveriEnako("NAZAJ zapusti Splet (prej zgodovina strani)", OsPravila.TipkaMenija.IZHOD, OsPravila.tipkaVMeniju(4, true, 0))
    preveriEnako("drzanje NAZAJ ne steje", OsPravila.TipkaMenija.NIC, OsPravila.tipkaVMeniju(4, true, 3))
    preveriEnako("spust tipke NAZAJ ne steje (pritisk je meni morda sele odprl)", OsPravila.TipkaMenija.NIC, OsPravila.tipkaVMeniju(4, false, 0))
    preveriEnako("predvajaj/pavza ni za meni", OsPravila.TipkaMenija.DRUGAM, OsPravila.tipkaVMeniju(85, true, 0))
    preveriEnako("rdeca tipka ni za meni", OsPravila.TipkaMenija.DRUGAM, OsPravila.tipkaVMeniju(183, true, 0))
    check(OsPravila.TipkaMenija.values().size == 5)
}

private fun preizkusGlasbenegaImena() {
    println("\n== glasbeni dodatek ==")
    for (ime in listOf("Music - Charts", "Vse-Music", "Live Concerts", "Koncerti", "Glasbeni videospoti", "Música en vivo", "Top Songs"))
        preveri("glasbeno ime: $ime", OsPravila.glasbenoIme(ime))
    for (ime in listOf("Musical", "Top filmi", "Movies - Popular", "Kanali News", "Amusical Night", ""))
        preveri("ni glasbeno ime: »$ime«", !OsPravila.glasbenoIme(ime))
}

private fun preizkusPolic() {
    println("\n== police razdelka: vir, ki tokrat ne odgovori ==")
    fun dop(stare: String, sveze: String) = OsPravila.dopolniPolice(stare.map { it.toString() }, sveze.map { it.toString() }) { it }.joinToString("")
    preveriEnako("prva polica manjka: ostane spredaj", "abc", dop("abc", "bc"))
    preveriEnako("srednja polica manjka: ostane med sosedama", "abc", dop("abc", "ac"))
    preveriEnako("zadnja polica manjka: ostane zadaj", "abc", dop("abc", "ab"))
    preveriEnako("nic ne manjka: sveze, kot so (tudi nova polica)", "abd", dop("ab", "abd"))
    preveriEnako("nov vrstni red svezih ostane, manjkajoca pride za prejsnjo sosedo", "cab", dop("abc", "ca"))
    preveriEnako("dve manjkata zapored", "abcd", dop("abcd", "ad"))
    preveriEnako("brez starega pogleda: sveze", "ab", dop("", "ab"))
    val sveze = listOf("a", "b")
    preveri("nic ne manjka: vrne isti seznam (klicatelj po tem loci popoln pogled)", OsPravila.dopolniPolice(listOf("a"), sveze) { it } === sveze)
}

private fun preizkusStabilnegaReda() {
    println("\n== mreza se dopolnjuje brez premescanja ==")
    fun red(naZaslonu: String, novi: String) = OsPravila.stabilenRed(naZaslonu.map { it.toString() }, novi.map { it.toString() }) { it }.joinToString("")
    preveriEnako("prazna mreza: vrstni red vira", "abcd", red("", "abcd"))
    preveriEnako("nove kartice pridejo za prikazanimi, ne vmes", "bdace", red("bd", "abcde"))
    preveriEnako("drugacen vrstni red vira prikazanih ne premesca", "abc", red("abc", "cba"))
    preveriEnako("kartica, ki je vir nima vec, izpade; ostale ostanejo na mestu", "acbd", red("axc", "bcda"))
    preveriEnako("nic od prikazanega ni vec v viru: vrstni red vira", "abc", red("xy", "abc"))
    preveriEnako("podvojen id v viru: ostane prvi", "ab", red("a", "aba").let { it.toList().distinct().joinToString("") })
}

private fun preizkusStalneGlave() {
    println("\n== kartica zdruzene vsebine se ne zamenja ==")
    fun glava(poOceni: String, naZaslonu: String) =
        OsPravila.stalnaGlava(poOceni.map { it.toString() }, naZaslonu.map { it.toString() }.toSet()) { it }.joinToString("")
    preveriEnako("nic se ni na zaslonu: najboljsa razlicica je prva", "abc", glava("abc", ""))
    preveriEnako("na zaslonu je najboljsa: red ostane", "abc", glava("abc", "a"))
    preveriEnako("na zaslonu je najslabsa: ostane kartica, ostale za njo po kakovosti", "cab", glava("abc", "c"))
    preveriEnako("pozneje potrjena boljsa razlicica kartice ne prevzame", "bac", glava("abc", "b"))
    preveriEnako("na zaslonu ni nobene od teh razlicic: najboljsa je prva", "abc", glava("abc", "xy"))
    preveriEnako("ena sama razlicica", "a", glava("a", "a"))
    val red = listOf("a", "b")
    preveri("brez spremembe vrne isti seznam", OsPravila.stalnaGlava(red, setOf("a")) { it } === red)
}

private fun preizkusLogotipa() {
    println("\n== logotip kanala ==")
    val prozoren = 0x00000000; val crn = 0xFF101010.toInt(); val bel = 0xFFF0F0F0.toInt(); val rdec = 0xFFE02020.toInt()
    fun slika(vidni: Int, barva: Int) = IntArray(256) { if (it < vidni) barva else prozoren }
    preveri("temen logotip na prozornem ozadju: svetla podlaga", OsPravila.svetlaPodlaga(slika(100, crn)))
    preveri("svetel logotip na prozornem ozadju: ostane temna", !OsPravila.svetlaPodlaga(slika(100, bel)))
    preveri("barven (rdec) logotip na prozornem ozadju: svetlost 89 -> svetla podlaga", OsPravila.svetlaPodlaga(slika(100, rdec)))
    preveri("temen logotip s svojo (neprozorno) podlago: ostane, kot je", !OsPravila.svetlaPodlaga(slika(256, crn)))
    preveri("skoraj neprozorna slika (manj kot petina prozorne): ostane", !OsPravila.svetlaPodlaga(slika(230, crn)))
    preveri("povsem prozorna slika: ostane temna", !OsPravila.svetlaPodlaga(slika(0, crn)))
    preveri("prazen seznam", !OsPravila.svetlaPodlaga(IntArray(0)))
}

private fun preizkusKanalov() {
    println("\n== kanal, ki ne stece ==")
    preveriEnako("404: kanala pri viru ni", true, OsPravila.mrtevKanal(2004, 404))
    preveriEnako("410: kanala pri viru ni vec", true, OsPravila.mrtevKanal(2004, 410))
    preveriEnako("403: vir zavraca", true, OsPravila.mrtevKanal(2004, 403))
    preveriEnako("429: vir omejuje - zacasno", false, OsPravila.mrtevKanal(2004, 429))
    preveriEnako("401: zahteva prijavo - ni dokaz", false, OsPravila.mrtevKanal(2004, 401))
    preveriEnako("503: izpad streznika - zacasno", false, OsPravila.mrtevKanal(2004, 503))
    preveriEnako("izpad omrezja te naprave ni mrtev kanal", false, OsPravila.mrtevKanal(2001, 0))
    preveriEnako("casovna omejitev ni mrtev kanal", false, OsPravila.mrtevKanal(2002, 0))
    preveriEnako("seznama ni mogoce prebrati", true, OsPravila.mrtevKanal(3002, 0))
    preveriEnako("neznan vsebnik", true, OsPravila.mrtevKanal(3003, 0))
    preveriEnako("napaka dekodirnika ni mrtev kanal (pomaga naprava v Linku)", false, OsPravila.mrtevKanal(4001, 0))
    preveriEnako("zaostanek za prenosom v zivo ni mrtev kanal", false, OsPravila.mrtevKanal(1002, 0))
    preveriEnako("imenik: imena ni, kontrola odgovori -> kanala pri viru ni", true, OsPravila.mrtevGostitelj(false, true))
    preveriEnako("imenik: imena ni, kontrola brez odgovora -> lahko je omrezje te naprave", false, OsPravila.mrtevGostitelj(false, null))
    preveriEnako("imenik: imena ni, kontrole tudi ni -> imenik te mreze ne pove resnice", false, OsPravila.mrtevGostitelj(false, false))
    preveriEnako("imenik: brez odgovora za ime -> ni dokaz", false, OsPravila.mrtevGostitelj(null, true))
    preveriEnako("imenik: ime obstaja -> kanal ni mrtev", false, OsPravila.mrtevGostitelj(true, true))
    preveriEnako("gostitelj iz sporocila sistema", "tok.primer.test",
        OsPravila.gostiteljIzNapake("Unable to resolve host \"Tok.Primer.test\": No address associated with hostname"))
    preveriEnako("sporocilo brez gostitelja", "", OsPravila.gostiteljIzNapake("timeout"))
    preveriEnako("brez sporocila", "", OsPravila.gostiteljIzNapake(null))
    preveriEnako("streznik se ne odziva, imenik dela -> kanala ta cas ni", true, OsPravila.mrtevNeodziven(zacelo = false, kontrola = true))
    preveriEnako("streznik se ne odziva, imenik molci -> lahko je omrezje te naprave", false, OsPravila.mrtevNeodziven(zacelo = false, kontrola = null))
    preveriEnako("streznik se ne odziva, kontrolnega imena ni -> ni dokaz", false, OsPravila.mrtevNeodziven(zacelo = false, kontrola = false))
    preveriEnako("izpad med predvajanjem ni mrtev kanal", false, OsPravila.mrtevNeodziven(zacelo = true, kontrola = true))
    preveriEnako("sonda: 200 -> tok je", true, OsPravila.sondaPoOdgovoru(200))
    preveriEnako("sonda: 206 -> tok je", true, OsPravila.sondaPoOdgovoru(206))
    preveriEnako("sonda: 404 -> toka ni", false, OsPravila.sondaPoOdgovoru(404))
    preveriEnako("sonda: 403 -> vir zavraca", false, OsPravila.sondaPoOdgovoru(403))
    preveriEnako("sonda: 429 -> omejitev, ne vemo", null, OsPravila.sondaPoOdgovoru(429))
    preveriEnako("sonda: 503 -> izpad streznika, ne vemo", null, OsPravila.sondaPoOdgovoru(503))
    preveriEnako("sonda: brez odgovora -> ne vemo", null, OsPravila.sondaPoOdgovoru(0))
    preveriEnako("kljuc kartice: predvajana enota brez oznake toka", "stremio|tv|kanal1|https://dodatek.test", OsPravila.kljucKanala("stremio|tv|kanal1|https://dodatek.test#123456"))
    preveriEnako("kljuc kartice: negativna oznaka toka", "stremio|tv|kanal1|https://dodatek.test", OsPravila.kljucKanala("stremio|tv|kanal1|https://dodatek.test#-98765"))
    preveriEnako("kljuc kartice: torrent ostane", "stremio|movie|tt1|x#t3", OsPravila.kljucKanala("stremio|movie|tt1|x#t3"))
    preveriEnako("kljuc kartice: uradni kanal ostane", "tv:slo1", OsPravila.kljucKanala("tv:slo1"))
    preveri("daljinec: ob napaki pas z razlago ostane (prej crn zaslon)", !OsPravila.pasSeSkrije(dotik = false, vrstaOdprta = false, tece = false, napaka = true))
    preveri("daljinec: brez napake se pas skrije kot doslej", OsPravila.pasSeSkrije(dotik = false, vrstaOdprta = false, tece = true, napaka = false))
}

/** Mreze namesto polic (lastnik, 5. in 7. 10. 2026): blok na strani z vec bloki nima drsenja v desno. */
private fun preizkusBlokov() {
    println("\n== bloki kartic: mreza namesto police ==")
    val v = OsPravila.VRSTIC_BLOKA
    preveriEnako("blok ima najvec dve vrstici", 2, v)
    // telefon, glasba: 2 v vrsto -> 4 mesta
    preveriEnako("kar gre v blok, ostane vse", 4, OsPravila.vsebineVBloku(4, 0, 2, v, false))
    preveriEnako("24 skladb, 2 v vrsto: 3 in kartica »Pokazi vse«", 3, OsPravila.vsebineVBloku(24, 0, 2, v, false))
    // telefon, plakati: 3 v vrsto -> 6 mest
    preveriEnako("12 videov, 3 v vrsto: 5 in »Pokazi vse«", 5, OsPravila.vsebineVBloku(12, 0, 3, v, false))
    // Nedavno: 5 nedavnih + »Pocisti« gre natanko v dve vrstici po tri
    preveriEnako("5 nedavnih in »Pocisti« v 3 x 2", 5, OsPravila.vsebineVBloku(5, 1, 3, v, false))
    preveriEnako("7 kartic in dejanje v 3 x 2: 4, »Pokazi vse« in dejanje", 4, OsPravila.vsebineVBloku(7, 1, 3, v, false))
    // blok s svojo kartico za vse (katalog »Pokazi vse«): druge ne dobi
    preveriEnako("blok s svojo kartico za vse: 5 in ta kartica", 5, OsPravila.vsebineVBloku(60, 1, 3, v, true))
    // televizor: 7 v vrsto
    preveriEnako("televizor, 24 skladb, 7 v vrsto: 13 in »Pokazi vse«", 13, OsPravila.vsebineVBloku(24, 0, 7, v, false))
    preveriEnako("televizor, 14 skladb: vse", 14, OsPravila.vsebineVBloku(14, 0, 7, v, false))
    // robovi: vsaj ena kartica vsebine, nikoli vec kot jih je, neveljavne mere ne podrejo
    preveriEnako("ena v vrsto: ena kartica in »Pokazi vse«", 1, OsPravila.vsebineVBloku(9, 0, 1, v, false))
    preveriEnako("vec dejanj kot mest: vsaj ena kartica vsebine", 1, OsPravila.vsebineVBloku(9, 5, 2, v, false))
    preveriEnako("prazen blok", 0, OsPravila.vsebineVBloku(0, 1, 3, v, false))
    preveriEnako("nic v vrsto se steje kot ena", 1, OsPravila.vsebineVBloku(9, 0, 0, v, false))
    for (vseh in 0..40) for (n in 1..8) for (d in 0..2) for (imaVse in listOf(false, true)) {
        val ostane = OsPravila.vsebineVBloku(vseh, d, n, v, imaVse)
        val kartic = ostane + d + (if (ostane < vseh && !imaVse) 1 else 0)
        if (ostane !in 0..vseh || (vseh > 0 && ostane == 0) || (kartic > n * v && ostane > 1)) {
            preveri("blok vseh=$vseh dejanj=$d naVrsto=$n imaVse=$imaVse -> $ostane (kartic $kartic)", false); return
        }
    }
    preveri("blok nikoli ne preseze dveh vrstic (razen z eno samo kartico vsebine) in nikoli ni prazen", true)

    println("\n== sirina kartic mreze ==")
    // Predvajalnik na telefonu: vsebina 344 dp, 2 kartici po 116 dp + rob 26 = 284 dp -> 60 dp praznega na desni
    preveriEnako("dve kartici zapolnita vrstico", 146, OsPravila.sirinaKarticeMreze(344, 2, 116, 26))
    preveriEnako("kartice, ki vrstico ze zapolnijo, ostanejo", 116, OsPravila.sirinaKarticeMreze(284, 2, 116, 26))
    preveriEnako("ozja vsebina kartic ne zmanjsa", 116, OsPravila.sirinaKarticeMreze(250, 2, 116, 26))
    preveriEnako("raztegnejo se najvec za polovico", 174, OsPravila.sirinaKarticeMreze(2000, 2, 116, 26))
    preveriEnako("tablica: pet v vrsto", 134, OsPravila.sirinaKarticeMreze(800, 5, 116, 26))
    preveriEnako("nic v vrsto se steje kot ena (ne deli z nic)", 174, OsPravila.sirinaKarticeMreze(344, 0, 116, 26))
}

private fun preizkusPodstrani() {
    println("\n== podstrani: Nazaj vrne na stran, s katere je bila odprta, na isto mesto ==")
    val p = OsPravila.Podstrani<String>()
    preveri("na zacetku ni odprta nobena", !p.odprta && p.globina == 0 && p.vrh == null && p.zapri() == null)
    // Domov (drsnik 840, izbrana kartica seznama 2) -> seznam predvajanja
    p.odpri("seznam:Moja vrsta", 840, "k:Seznami predvajanja#2")
    preveri("po odpiranju je odprta", p.odprta && p.globina == 1 && p.vrh == "seznam:Moja vrsta")
    preveriEnako("Nazaj s seznama: stran razdelka, isti polozaj in ista kartica",
        OsPravila.Podstrani.Vrnitev<String>(null, 840, "k:Seznami predvajanja#2"), p.zapri())
    preveri("po vrnitvi ni odprta nobena", !p.odprta)
    // Gnezdenje: zadetki (300, podkast 5) -> »Pokazi vse« podkastov (1200, podkast 17) -> epizode
    p.odpri("blok:Podkasti", 300, "k:Podkasti#5")
    p.odpri("epizode:Oddaja", 1200, "k:Podkasti#17")
    preveriEnako("globina 2", 2, p.globina)
    preveriEnako("Nazaj z epizod: blok podkastov na istem mestu (ne stran zadetkov)",
        OsPravila.Podstrani.Vrnitev("blok:Podkasti", 1200, "k:Podkasti#17"), p.zapri())
    preveriEnako("Nazaj iz bloka: stran zadetkov na istem mestu",
        OsPravila.Podstrani.Vrnitev<String>(null, 300, "k:Podkasti#5"), p.zapri())
    // Drugi seznam iz istega bloka: blok si zapomni zadnje mesto, stran razdelka svojega
    p.odpri("blok:Seznami", 100, null)
    p.odpri("seznam:A", 50, "k:Seznami#1")
    p.zapri()
    p.odpri("seznam:B", 70, "k:Seznami#4")
    preveriEnako("drugi seznam iz istega bloka: vrne na zadnje mesto v bloku",
        OsPravila.Podstrani.Vrnitev("blok:Seznami", 70, "k:Seznami#4"), p.zapri())
    preveriEnako("mesto na strani razdelka se pri tem ne spremeni", OsPravila.Podstrani.Vrnitev<String>(null, 100, null), p.zapri())
    // Drug razdelek ali novo iskanje: podstrani ni vec
    p.odpri("blok:X", 10, "k"); p.odpri("seznam:Y", 20, "k2")
    p.pocisti()
    preveri("po menjavi razdelka ni odprta nobena in Nazaj ne vraca nikamor", !p.odprta && p.zapri() == null)
    p.odpri("blok:Z", 0, null)
    preveriEnako("staro mesto razdelka se ne prenese", OsPravila.Podstrani.Vrnitev<String>(null, 0, null), p.zapri())

    // Mesto je kartica in njen odmik od vrha vidnega dela (stran nad njo se lahko spremeni); brez kartice velja polozaj drsnika.
    p.odpri("seznam:M", 840, "k:Seznami predvajanja#3", 212)
    preveriEnako("odmik kartice se vrne z mestom", OsPravila.Podstrani.Vrnitev<String>(null, 840, "k:Seznami predvajanja#3", 212), p.zapri())
    p.odpri("seznam:N", 5, null)
    preveriEnako("brez kartice ni odmika", OsPravila.Podstrani.NI_ODMIKA, p.zapri()?.odmik)

    println("\n== nedavno: brez podvojenih prikazov, a razlicne stvari ostanejo razlicne ==")
    preveriEnako("dve razlicici istega naslova sta en vnos",
        OsPravila.kljucNedavnega("a1", "the love hypothesis", false, false), OsPravila.kljucNedavnega("b2", "the love hypothesis", false, false))
    preveri("dve postaji z enakim imenom sta dve postaji",
        OsPravila.kljucNedavnega("r1", "radio 1", true, false) != OsPravila.kljucNedavnega("r2", "radio 1", true, false))
    preveri("dve datoteki naprave z enakim imenom sta dve datoteki",
        OsPravila.kljucNedavnega("f1", "track 01", false, true) != OsPravila.kljucNedavnega("f2", "track 01", false, true))
    preveri("brez precisenega naslova odloca oznaka", OsPravila.kljucNedavnega("x", "", false, false) != OsPravila.kljucNedavnega("y", "", false, false))
    preveri("postaja in skladba z enakim imenom nista isti vnos",
        OsPravila.kljucNedavnega("r1", "energy", true, false) != OsPravila.kljucNedavnega("s1", "energy", false, false))
}

private fun preizkusVrsteKartic() {
    println("\n== zaslon predvajanja na dotik: omejena vrsta kartic in »Pokazi vse« ==")
    // Sirina vrste, sirina kartice in razmik v istih enotah (dp).
    preveriEnako("ozek telefon pokonci (344, kartica 130): tri kartice", 3, OsPravila.karticVVrsti(344, 130, 12))
    preveriEnako("tablica lezece (1168)", 8, OsPravila.karticVVrsti(1168, 130, 12))
    preveriEnako("telefon lezece, manjse kartice (789, kartica 86)", 8, OsPravila.karticVVrsti(789, 86, 12))
    preveriEnako("tablica pokonci (688)", 5, OsPravila.karticVVrsti(688, 130, 12))
    preveriEnako("nikoli manj kot tri", 3, OsPravila.karticVVrsti(200, 130, 12))
    preveriEnako("sirina 0 (pred prvo postavitvijo) racuna ne podre", 3, OsPravila.karticVVrsti(0, 130, 12))
    preveriEnako("prazna vrsta", OsPravila.OknoVrste(0, 0, false), OsPravila.oknoVrste(0, -1, 3))
    preveriEnako("ena skladba: sama, brez »Pokazi vse«", OsPravila.OknoVrste(0, 1, false), OsPravila.oknoVrste(1, 0, 3))
    preveriEnako("toliko skladb kot mest: vse, brez »Pokazi vse«", OsPravila.OknoVrste(0, 3, false), OsPravila.oknoVrste(3, 2, 3))
    preveriEnako("vec skladb kot mest: zadnje mesto je »Pokazi vse«", OsPravila.OknoVrste(0, 2, true), OsPravila.oknoVrste(4, 0, 3))
    preveriEnako("okno se zacne pri skladbi, ki igra", OsPravila.OknoVrste(5, 3, true), OsPravila.oknoVrste(20, 5, 4))
    preveriEnako("naslednja skladba je se v oknu: kartice ostanejo na mestu", OsPravila.OknoVrste(5, 3, true), OsPravila.oknoVrste(20, 6, 4, 5))
    preveriEnako("... tudi zadnja v oknu", OsPravila.OknoVrste(5, 3, true), OsPravila.oknoVrste(20, 7, 4, 5))
    preveriEnako("skladba za oknom: okno se premakne nanjo", OsPravila.OknoVrste(8, 3, true), OsPravila.oknoVrste(20, 8, 4, 5))
    preveriEnako("skladba pred oknom (prejsnja): okno se premakne nanjo", OsPravila.OknoVrste(4, 3, true), OsPravila.oknoVrste(20, 4, 4, 5))
    preveriEnako("konec seznama: vrsta ostane polna", OsPravila.OknoVrste(17, 3, true), OsPravila.oknoVrste(20, 19, 4))
    preveriEnako("predlogi (iz njih nic ne igra): od zacetka", OsPravila.OknoVrste(0, 3, true), OsPravila.oknoVrste(12, -1, 4, 6))
    preveriEnako("staro okno, ki ga krajsi seznam nima vec: znova pri skladbi, ki igra", OsPravila.OknoVrste(1, 3, true), OsPravila.oknoVrste(6, 1, 4, 9))
    preveriEnako("manj kot dve mesti ni vrsta: racunamo z dvema", OsPravila.OknoVrste(2, 1, true), OsPravila.oknoVrste(5, 2, 1))
}

fun main() {
    println("Preizkus pravil Safeer OS")
    preizkusBlokov()
    preizkusPodstrani()
    preizkusVrsteKartic()
    preizkusKanalov()
    preizkusLogotipa()
    preizkusStabilnegaReda()
    preizkusStalneGlave()
    preizkusPolic()
    preizkusGlasbenegaImena()
    preizkusTipkMenija()
    preizkusIkonNaprav()
    preizkusOpisaNaprav()
    preizkusPovezave()
    preizkusMer()
    preizkusNadaljuj()
    preizkusIkon()
    preizkusZaslona()
    pasMedVideom()
    println()
    if (napak == 0) println("Vse v redu.") else { println("Napak: $napak"); System.exit(1) }
}
