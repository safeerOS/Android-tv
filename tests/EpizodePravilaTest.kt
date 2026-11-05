package si.safeer.tv.os

private var napakEpizod = 0
private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napakEpizod++ }
}

fun main() {
    println("\nPreizkus pravil epizod")
    
    // Oznaka
    preveri("oznaka 1/1 je S01E01", EpizodePravila.oznakaEpizode(1, 1) == "S01E01")
    preveri("oznaka 12/5 je S12E05", EpizodePravila.oznakaEpizode(12, 5) == "S12E05")
    preveri("oznaka 0/5 je S00E05", EpizodePravila.oznakaEpizode(0, 5) == "S00E05")
    preveri("oznaka 0/0 je prazna", EpizodePravila.oznakaEpizode(0, 0) == "")
    preveri("oznaka -1/-1 je prazna", EpizodePravila.oznakaEpizode(-1, -1) == "")
    
    // Pokazi levi stolpec
    preveri("več različnih sezon -> pokaži", EpizodePravila.pokaziLeviStolpec(listOf(1, 2, 3)))
    preveri("ena sezona -> skrij", !EpizodePravila.pokaziLeviStolpec(listOf(1)))
    preveri("več enakih sezon -> skrij", !EpizodePravila.pokaziLeviStolpec(listOf(1, 1, 1)))
    preveri("brez sezon -> skrij", !EpizodePravila.pokaziLeviStolpec(emptyList()))
    
    // Smer fokusa
    preveri("DESNO na sezonah -> gre na epizode", EpizodePravila.smerFokusa(true, false) == false)
    preveri("LEVO na sezonah -> nič", EpizodePravila.smerFokusa(true, true) == null)
    preveri("LEVO na epizodah -> gre na sezone", EpizodePravila.smerFokusa(false, true) == true)
    preveri("DESNO na epizodah -> nič", EpizodePravila.smerFokusa(false, false) == null)

    // Pregled Medijskega centra 5. 11. 2026 (7): okno se odpre pri zadnji epizodi
    val ep = listOf(1 to 1, 1 to 2, 2 to 1, 2 to 2, 2 to 3)
    preveri("brez zapisa -> S1E1", EpizodePravila.zacetnaIzbira(ep, listOf(1, 2), null) == (0 to 0))
    preveri("zadnja S2E3 -> sezona 1, epizoda 2", EpizodePravila.zacetnaIzbira(ep, listOf(1, 2), 2 to 3) == (1 to 2))
    preveri("zadnja ni prikazana -> prva", EpizodePravila.zacetnaIzbira(ep, listOf(1, 2), 3 to 1) == (0 to 0))
    // (6): odstranjena epizoda pred izbrano - izbira ostane na isti epizodi
    preveri("izbrana c ostane c", EpizodePravila.izborPoOdstranitvi(listOf("a", "b", "c"), listOf("b", "c"), 2) == 1)
    preveri("odstranjena izbrana -> ista vrstica", EpizodePravila.izborPoOdstranitvi(listOf("a", "b", "c"), listOf("a", "c"), 1) == 1)
    preveri("odstranjena zadnja izbrana -> zadnja", EpizodePravila.izborPoOdstranitvi(listOf("a", "b"), listOf("a"), 1) == 0)
    preveri("prazno -> -1", EpizodePravila.izborPoOdstranitvi(listOf("a"), emptyList(), 0) == -1)
    preveri("prazna sezona izpade, trenutna ostane", EpizodePravila.sezonePoOdstranitvi(listOf(1, 2, 3), listOf(1, 3), 2) == (listOf(1, 3) to 1))
    preveri("trenutna izpade -> sosednja", EpizodePravila.sezonePoOdstranitvi(listOf(1, 2, 3), listOf(1, 3), 1) == (listOf(1, 3) to 1))
    preveri("ni sezon -> -1", EpizodePravila.sezonePoOdstranitvi(listOf(1), emptyList(), 0).second == -1)
    // (13): DESNO vrne na zadnjo izbrano epizodo
    preveri("vrnitev na 4. epizodo", EpizodePravila.izborObVrnitvi(3, 10) == 3)
    preveri("vrnitev omejena na seznam", EpizodePravila.izborObVrnitvi(7, 5) == 4)
    // (12): stevilo epizod iz prikazanih
    preveri("2 prikazani v sezoni 1", EpizodePravila.steviloVSezoni(ep.map { it.first }, 1) == 2)

    // Preverba TV 5. 11. 2026 (N9): z zapisom zadnje epizode fokus nanjo, ne na »Sezona 1«.
    preveri("N9 zadnja S2E3 prikazana -> fokus na epizodi", EpizodePravila.fokusNaEpizodi(ep, 2 to 3))
    preveri("N9 brez zapisa -> fokus ostane", !EpizodePravila.fokusNaEpizodi(ep, null))
    preveri("N9 zadnja ni prikazana -> fokus ostane", !EpizodePravila.fokusNaEpizodi(ep, 3 to 1))

    if (napakEpizod != 0) { println("Napak epizod: $napakEpizod"); kotlin.system.exitProcess(1) }
}
