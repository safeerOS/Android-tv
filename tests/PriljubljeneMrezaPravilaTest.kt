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
    preveriEnako("zastavica vklopljena, priljubljene so: mreza samo Priljubljene", true, P.samoPriljubljene(true, 2))
    preveriEnako("zadnja odstranjena: ni vec Priljubljenih", false, P.samoPriljubljene(true, 0))
    preveriEnako("zastavica izklopljena: ne", false, P.samoPriljubljene(false, 5))
    preveriEnako("vrednost 5 (izbira mreze) zunaj 5 nacinov police -> prva, ne IndexOutOfBounds", 0, P.varnaRazvrstitev(5, 5))
    preveriEnako("veljavna razvrstitev police ostane", 3, P.varnaRazvrstitev(3, 5))
    preveriEnako("negativna razvrstitev -> prva", 0, P.varnaRazvrstitev(-1, 5))
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
    // Scenarij s TV (15. 11. 2026): Serije + filter Usenet, mreza v Priporoceno porabi proracun preverb (40 + 40 vnaprej),
    // uporabnik izbere Priljubljene - priljubljen naslov (prvi v novem vrstnem redu) MORA dobiti preverbo, sicer je mreza prazna.
    val F = FilterViraPravila
    var pr = F.proracun(null, false, 0)
    pr = pr.copy(ostalo = 0)
    pr = F.proracun(pr, true, 0)               // vnaprej
    pr = pr.copy(ostalo = 0)
    preveriEnako("pred izbiro Priljubljene je proracun porabljen", 0, F.proracun(pr, true, P.zahtevaFiltra(0, false)).ostalo)
    val poIzbiri = F.proracun(pr, true, P.zahtevaFiltra(0, true))
    preveriEnako("izbira Priljubljene da nov proracun: priljubljen kandidat je preverjen in viden", true, poIzbiri.ostalo > 0)
    preveriEnako("zahteva za Priljubljene se razlikuje od navadne", true, P.zahtevaFiltra(3, true) != P.zahtevaFiltra(3, false))
    preveriEnako("zahteva se ne zaleti z naslednjo zahtevo uporabnika", true, P.zahtevaFiltra(3, true) != P.zahtevaFiltra(4, false))
    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("PriljubljeneMrezaPravilaTest: OK")
}
