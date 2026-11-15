package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Priljubljene v mrezi Filmi | Serije ([PriljubljeneMrezaPravila]). */
fun main() {
    println("Preizkus priljubljenih v mrezi")
    val P = PriljubljeneMrezaPravila
    preveriEnako("brez priljubljenih izbire ni", false, P.ponudi(0))
    preveriEnako("s priljubljenimi je izbira", true, P.ponudi(2))
    preveriEnako("zadnja odstranjena: nazaj na Priporoceno", 0, P.veljavna(5, 5, 0, 0))
    preveriEnako("priljubljene ostanejo, ce so", 5, P.veljavna(5, 5, 1, 0))
    preveriEnako("druga razvrstitev se ne dotakne", 3, P.veljavna(3, 5, 0, 0))
    preveriEnako("film je cela vsebina", true, P.jeCelaVsebina(0, 0))
    preveriEnako("epizoda ni cela vsebina", false, P.jeCelaVsebina(1, 3))
    val izbire = listOf("priporoceno", "najnovejse", "ime")
    val red = P.vrstniRed(izbire, "priljubljene", 3)
    preveriEnako("Priljubljene so PRVE, pred Priporoceno", listOf("priljubljene", "priporoceno", "najnovejse", "ime"), red)
    preveriEnako("iz privzete izbire (Priporoceno) en premik", 1, P.premikov(red, "priporoceno", "priljubljene"))
    preveriEnako("najvec en premik iz privzete izbire (okno se odpre na izbrani)", true, P.premikov(red, "priporoceno", "priljubljene") in 0..1)
    preveriEnako("brez priljubljenih izbira ni v redu", izbire, P.vrstniRed(izbire, "priljubljene", 0))
    preveriEnako("premik do cilja, ki ga ni, je -1", -1, P.premikov(izbire, "priporoceno", "priljubljene"))
    val katalog = listOf("a|nov", "b|nov", "c|nov")
    val prilj = listOf("c|star", "x|star", "a|star", "c|star")
    val r = P.uredi(katalog, prilj, { it.substringBefore('|') }, { true })
    preveriEnako("red priljubljenih, kartica iz kataloga ima prednost, zunaj kataloga ostane, brez ponovitve",
        listOf("c|nov", "x|star", "a|nov"), r)
    preveriEnako("samo ta vrsta", listOf("x|star"),
        P.uredi(katalog, prilj, { it.substringBefore('|') }, { it.startsWith("x") }))
    preveriEnako("nic priljubljenega -> prazno", emptyList<String>(), P.uredi(katalog, emptyList(), { it }, { true }))
    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("PriljubljeneMrezaPravilaTest: OK")
}
