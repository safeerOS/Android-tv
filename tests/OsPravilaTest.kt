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

fun main() {
    println("Preizkus pravil Safeer OS")
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
