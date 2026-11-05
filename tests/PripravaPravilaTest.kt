package si.safeer.tv.os

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

fun main() {
    println("Preizkus pravil za pripravo tokov (odpri takoj)")
    preveri("video, ni rocno -> da", PripravaPravila.odpriTakoj(true, false))
    preveri("zvok, ni rocno -> ne", !PripravaPravila.odpriTakoj(false, false))
    preveri("video, rocno -> ne", !PripravaPravila.odpriTakoj(true, true))
    preveri("zvok, rocno -> ne", !PripravaPravila.odpriTakoj(false, true))
    preveri("video, ni rocno, je serija -> ne", !PripravaPravila.odpriTakoj(true, false, true))
    // Klik na EPIZODO serije (lastnik 1. 11.): predvajalnik s »Pripravljam« takoj; kartica serije brez epizode ne.
    preveri("serija brez epizode", PripravaPravila.serijaBrezEpizode("series", 0))
    preveri("epizoda serije ni serija brez epizode", !PripravaPravila.serijaBrezEpizode("series", 3))
    preveri("film ni serija", !PripravaPravila.serijaBrezEpizode("movie", 0))
    preveri("tvseries (kartica kataloga, Silo) je serija", PripravaPravila.serijaBrezEpizode("tvseries", 0))
    preveri("epizoda tvseries ni serija brez epizode", !PripravaPravila.serijaBrezEpizode("tvseries", 1))
    preveri("epizoda: odpri takoj", PripravaPravila.odpriTakoj(true, false, PripravaPravila.serijaBrezEpizode("series", 3)))
    // TV 1. 11. 2026: zagon stet, predvajalnik se ni nastavljen -> ostani v pripravi (ne zapri zaslona)
    preveri("zagon brez predvajalnika: ostani", PripravaPravila.ostaniVPripravi(1, 2, 3_000L, false))
    preveri("zagon s predvajalnikom: predvajaj", !PripravaPravila.ostaniVPripravi(1, 2, 3_000L, true))
    preveri("rok potekel: ne caka vec", !PripravaPravila.ostaniVPripravi(1, 2, 46_000L, false))

    println("Preizkus pravil ostaniVPripravi in cas")
    preveri("enak zagon, pod 45s -> ostani", PripravaPravila.ostaniVPripravi(5, 5, 10_000L))
    preveri("nov zagon, pod 45s -> ne ostani", !PripravaPravila.ostaniVPripravi(5, 6, 10_000L))
    preveri("enak zagon, nad 45s -> ne ostani", !PripravaPravila.ostaniVPripravi(5, 5, 46_000L))
    preveri("potekel cas -> da", PripravaPravila.jePotekelCas(45_000L))
    preveri("ni potekel cas -> ne", !PripravaPravila.jePotekelCas(44_999L))

    // Pregled Medijskega centra 5. 11. 2026 (2): kljuc epizode ni kljuc serije
    preveri("E1 in E2 imata razlicen kljuc", PripravaPravila.kljucPriprave("tt1", "", "X", 1, 1) != PripravaPravila.kljucPriprave("tt1", "", "X", 1, 2))
    preveri("serija brez epizode in epizoda razlicna", PripravaPravila.kljucPriprave("tt1", "", "X", 0, 0) != PripravaPravila.kljucPriprave("tt1", "", "X", 1, 1))
    preveri("film: kljuc je id", PripravaPravila.kljucPriprave("tt2", "u", "X", 0, 0) == "tt2")
    preveri("brez id: povezava", PripravaPravila.kljucPriprave("", "u", "X", 0, 0) == "u")
    // (1, 2): zeton priprave - preklic in nova priprava iste vsebine
    val pr = PripravaPravila.Priprave()
    val z1 = pr.zacni("a") ?: ""
    preveri("prva priprava se zacne", z1.isNotEmpty())
    preveri("isti klik med pripravo -> nic", pr.zacni("a") == null)
    pr.preklici()
    preveri("po preklicu stara priprava ni aktivna", !pr.aktivna(z1))
    val z2 = pr.zacni("a") ?: ""
    preveri("po preklicu nova priprava iste vsebine", z2.isNotEmpty())
    preveri("stara priprava po preklicu ne konca nove", !pr.koncaj(z1))
    preveri("nova priprava je se v teku", pr.isNotEmpty() && pr.aktivna(z2))
    preveri("nova priprava se konca", pr.koncaj(z2) && pr.isEmpty())
    preveri("drugic ne konca", !pr.koncaj(z2))

    if (napak > 0) kotlin.system.exitProcess(1)
    println("PripravaPravilaTest: OK")
}
