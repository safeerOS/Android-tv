package si.safeer.tv.os

private var napak = 0
private var uspehov = 0
private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) { uspehov++; println("PASS $opis") } else { napak++; println("FAIL $opis") }
}

fun main() {
    val R = Relevantnost
    val q = "Pink Floyd Time"
    // Ta naprava: pravi zapis (naslov + izvajalec) je najboljsi.
    val lokalno = R.ocena(q, "Time", "Pink Floyd")
    // Racunalnik: datoteka brez oznak, izvajalec je v imenu mape.
    val pc = R.ocena(q, "04 - Time.flac", "", "Pink Floyd/The Dark Side of the Moon")
    val jamendo = R.ocena(q, "Time", "Nekdo Drug")
    preveri("natancno ujemanje izvajalec + naslov je najvisje", lokalno > pc && lokalno >= 1.4)
    preveri("mapa izvajalca na racunalniku steje za dober zadetek", pc >= R.DOBER)
    preveri("samo naslov brez izvajalca ni dober zadetek", jamendo < R.DOBER && jamendo < R.SPODNJA)
    preveri("sumniki in koncnica ne motijo", R.normaliziraj("Čudežna Šola.MP3") == "cudezna sola")
    preveri("kljuc dvojnikov ne glede na vrstni red in stevilko", R.kljuc("Pink Floyd - Time", "") == R.kljuc("04 Time", "Pink Floyd"))
    preveri("kratka beseda se ne ujema kot predpona", R.ocena("ab", "abeceda", "") == 0.0)
    preveri("predpona dalse besede se ujema", R.ocena("floy", "Time", "Pink Floyd") > 0.0)

    val z = listOf(
        Relevantnost.Zadetek("pc", "Pink Floyd - Time.flac", "", "Lastnik-PC", 1),
        Relevantnost.Zadetek("lokal", "Time", "Pink Floyd", "Ta TV", 0),
        Relevantnost.Zadetek("jam", "Time", "Nekdo Drug", "Jamendo", 3),
        Relevantnost.Zadetek("radio", "Classic Rock Radio", "", "Radio", 5),
    )
    val r = R.razvrsti(q, z)
    preveri("dvojnik (TV in racunalnik) ostane enkrat, obdrzi se ta naprava",
        r.count { it.first.stvar == "pc" || it.first.stvar == "lokal" } == 1 && r.first().first.stvar == "lokal")
    preveri("slabsi zadetki so za dobrimi", r.map { it.first.stvar }.indexOf("jam") > 0)
    preveri("en dober zadetek ni dovolj - splet kot rezerva", R.potrebujemSplet(r))
    val dovolj = R.razvrsti("time", listOf(Relevantnost.Zadetek(1, "Time", "A", "x", 0), Relevantnost.Zadetek(2, "Time", "B", "x", 0), Relevantnost.Zadetek(3, "Time", "C", "x", 0)))
    preveri("trije dobri zadetki - splet ni potreben", !R.potrebujemSplet(dovolj))

    preveri("ime datoteke razdeljeno na naslov in izvajalca", R.razdeli("JOHN LENNON - IMAGINE.ogg", "") == ("IMAGINE" to "JOHN LENNON"))
    preveri("obstojeci izvajalec ostane", R.razdeli("Time", "Pink Floyd") == ("Time" to "Pink Floyd"))
    preveri("brez locila ostane naslov", R.razdeli("Imagine.mp3", "") == ("Imagine" to ""))
    preveri("clen The ne odvzame bonusa prve besede", R.ocena("lord", "The Lord of the Rings", "") == R.ocena("lord", "Lord of War", ""))

    // Lastnik 1. 11. 2026 (TV): risanka 1978 ima naslov enak poizvedbi (ocena 1,5), Bratovscina prstana 1,2 - prej je bila
    // risanka prva. Ista stopnja (vse besede v naslovu): odloca priljubljenost.
    val lotrRes = R.razvrsti("Lord of the Rings", listOf(
        Relevantnost.Zadetek("1978", "The Lord of the Rings", "", "Cinemeta", 2, glasov = 100_000, ocenaVira = 7.6),
        Relevantnost.Zadetek("2001", "The Lord of the Rings: The Fellowship of the Ring", "", "Cinemeta", 2, glasov = 2_000_000, ocenaVira = 8.9)
    ))
    preveri("Gospodar prstanov: najbolj priljubljen prvi", lotrRes[0].first.stvar == "2001")
    // Brez podatkov o priljubljenosti ostane vrstni red po ujemanju.
    val brez = R.razvrsti("Lord of the Rings", listOf(
        Relevantnost.Zadetek("a", "The Lord of the Rings: The Fellowship of the Ring", "", "x", 2),
        Relevantnost.Zadetek("b", "The Lord of the Rings", "", "x", 2)))
    preveri("brez priljubljenosti: natancen naslov prvi", brez[0].first.stvar == "b")
    // Slabse ujemanje (ne vse besede) ne prehiti dobrega, tudi ce je bolj priljubljeno.
    val slabse = R.razvrsti("Lord of the Rings", listOf(
        Relevantnost.Zadetek("dobro", "The Lord of the Rings", "", "x", 2, glasov = 10),
        Relevantnost.Zadetek("slabo", "Rings", "", "x", 2, glasov = 5_000_000)))
    preveri("slabse ujemanje ne prehiti", slabse[0].first.stvar == "dobro")
    // TV 1. 11. 2026 18:25: Cinemeta pri iskanju ne da ocen, odgovor pa je razvrscen po priljubljenosti (2001 prvi, 1978 sesti).
    val poRangu = R.razvrsti("Lord of the Rings", listOf(
        Relevantnost.Zadetek("1978", "The Lord of the Rings", "", "Cinemeta", 2, rangVira = 5),
        Relevantnost.Zadetek("2001", "The Lord of the Rings: The Fellowship of the Ring", "", "Cinemeta", 2, rangVira = 0)))
    preveri("Cinemeta brez ocen: mesto v odgovoru vira", poRangu[0].first.stvar == "2001")

    println("Relevantnost: $uspehov passed, $napak failed")
    if (napak > 0) kotlin.system.exitProcess(1)
}
