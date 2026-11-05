package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Pomocnik za Usenet v Linku (lastnik 5. 11. 2026, zakon solidarnosti). */
fun main() {
    println("Preizkus pomocnika za Usenet")
    val P = UsenetPomocPravila
    val moj = "55b27847b81bca8a"
    preveriEnako("ustreza: sme, kodirnik, isti indeksator", true, P.ustreza(true, 5, moj, moj))
    preveriEnako("ne: drug indeksator (guid ne velja tam)", false, P.ustreza(true, 5, "0000000000000000", moj))
    preveriEnako("ne: pomocnik ta trenutek ne sme (baterija, obremenitev)", false, P.ustreza(false, 5, moj, moj))
    preveriEnako("ne: brez kodirnika (ocena 0)", false, P.ustreza(true, 0, moj, moj))
    preveriEnako("ne: pomocnik brez Useneta", false, P.ustreza(true, 5, null, moj))
    preveriEnako("ne: TV brez indeksatorja", false, P.ustreza(true, 5, moj, null))
    val t0 = 10_000_000L
    preveriEnako("osvezitev: prva sme", true, P.smemOsveziti(0L, t0))
    preveriEnako("osvezitev: po 30 s ne (zanka brez meje)", false, P.smemOsveziti(t0, t0 + 30_000))
    preveriEnako("osvezitev: po minuti sme", true, P.smemOsveziti(t0, t0 + 60_000))
    preveriEnako("osvezitev: ura nazaj sme", true, P.smemOsveziti(t0, t0 - 1))
    preveriEnako("seznam: velja 3 min", listOf("a"), P.zdaj(listOf("a"), t0, t0 + 3 * 60_000, setOf("a")))
    preveriEnako("seznam: star ne velja", emptyList<String>(), P.zdaj(listOf("a"), t0, t0 + 3 * 60_000 + 1, setOf("a")))
    preveriEnako("seznam: odsla naprava takoj izgine", listOf("b"), P.zdaj(listOf("a", "b"), t0, t0 + 1, setOf("b", "c")))
    preveriEnako("seznam: brez osvezitve prazen", emptyList<String>(), P.zdaj(listOf("a"), 0L, t0, setOf("a")))
    preveriEnako("identiteta: veljavna", Triple("abc123def456", "Film 2160p", moj), P.identiteta("abc123def456", " Film\n 2160p ", moj))
    preveriEnako("identiteta: guid z & ni", null, P.identiteta("abc&apikey=x", "Film", moj))
    preveriEnako("identiteta: brez odtisa ni", null, P.identiteta("abc123def456", "Film", null))
    preveriEnako("identiteta: odtis ni heks", null, P.identiteta("abc123def456", "Film", "https://nzbfinder.ws"))
    preveriEnako("identiteta: ime najvec 200", 200, P.identiteta("abc123def456", "x".repeat(500), moj)?.second?.length)
    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("Vse v redu.")
}
