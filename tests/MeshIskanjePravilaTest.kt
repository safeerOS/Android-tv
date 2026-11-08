package si.safeer.tv.cast

/** Revizija RAM/CPU 8. 11. 2026 (#2): presledek iskanja sosedov mesh se ob mirovanju podaljsuje, ob spremembi skrajsa. */
private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) {
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA $opis: pricakovano $pricakovano, dobljeno $dobljeno") }
}

fun main() {
    val z = MeshIskanjePravila.ZACETNO_MS
    preveriEnako("po spremembi zacetni presledek", z, MeshIskanjePravila.naslednje(120_000L, sprememba = true))
    preveriEnako("brez spremembe se podvoji", 30_000L, MeshIskanjePravila.naslednje(z, sprememba = false))
    preveriEnako("podvajanje se ustavi pri najvec", MeshIskanjePravila.NAJVEC_MS, MeshIskanjePravila.naslednje(90_000L, sprememba = false))
    preveriEnako("najvec ostane najvec", MeshIskanjePravila.NAJVEC_MS, MeshIskanjePravila.naslednje(MeshIskanjePravila.NAJVEC_MS, sprememba = false))
    preveriEnako("prekratek prejsnji (0 ob zagonu) da zacetnega", z, MeshIskanjePravila.naslednje(0L, sprememba = false))
    preveriEnako("klic kandidata je sprememba", true, MeshIskanjePravila.sprememba(setOf("a"), setOf("a"), klicanih = 1))
    preveriEnako("odhod soseda je sprememba", true, MeshIskanjePravila.sprememba(setOf("a", "b"), setOf("a"), klicanih = 0))
    preveriEnako("nov sosed je sprememba", true, MeshIskanjePravila.sprememba(setOf("a"), setOf("a", "b"), klicanih = 0))
    preveriEnako("isti sosedje brez klicev ni sprememba", false, MeshIskanjePravila.sprememba(setOf("a", "b"), setOf("b", "a"), klicanih = 0))
    // Zaporedje ob mirovanju: 15, 30, 60, 120, 120 s - najvec 4 iskanja v prvih 4 min namesto 16.
    var p = z; val zaporedje = mutableListOf<Long>()
    repeat(5) { zaporedje += p; p = MeshIskanjePravila.naslednje(p, sprememba = false) }
    preveriEnako("zaporedje ob mirovanju", listOf(15_000L, 30_000L, 60_000L, 120_000L, 120_000L), zaporedje)
    if (napak == 0) println("MeshIskanjePravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
