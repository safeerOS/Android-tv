package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Moj seznam ([MojSeznamPravila]): ponudba, dodaj/odstrani, meja, odstranitev po ogledu. */
fun main() {
    println("Preizkus Mojega seznama")
    val P = MojSeznamPravila
    preveriEnako("film gre na seznam", true, P.ponudi(video = true, radio = false, kanal = false))
    preveriEnako("glasba ne", false, P.ponudi(video = false, radio = false, kanal = false))
    preveriEnako("kanal v zivo ne", false, P.ponudi(video = true, radio = false, kanal = true))
    preveriEnako("radio ne", false, P.ponudi(video = true, radio = true, kanal = false))
    preveriEnako("dodaj na zacetek", listOf("b", "a"), P.preklopi(listOf("a"), "b"))
    preveriEnako("ze na seznamu -> odstrani", listOf("a"), P.preklopi(listOf("b", "a"), "b"))
    preveriEnako("prazen id nic", listOf("a"), P.preklopi(listOf("a"), ""))
    val poln = (1..P.NAJVEC).map { "x$it" }
    val nov = P.preklopi(poln, "novi")
    preveriEnako("meja: dolzina ostane", P.NAJVEC, nov.size)
    preveriEnako("meja: odpade najstarejsi", false, "x${P.NAJVEC}" in nov)
    preveriEnako("meja: novi je prvi", "novi", nov.first())
    preveriEnako("90 % je odgledano", true, P.odgledano(90_000L, 100_000L))
    preveriEnako("89 % se ni", false, P.odgledano(89_000L, 100_000L))
    preveriEnako("brez trajanja nikoli", false, P.odgledano(5_000L, 0L))
    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("MojSeznamPravilaTest: OK")
}
