package si.safeer.tv.os

import kotlin.system.exitProcess

private var napak = 0

private fun enako(opis: String, pricakovano: Any?, dobljeno: Any?) {
    if (pricakovano == dobljeno) println("  ok  $opis")
    else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }
}

/** Pregled 5. 11. 2026: vrnitev na zivo ob BEHIND_LIVE_WINDOW z mejo (3 v 60 s za isti posnetek). */
fun main() {
    val P = ZaOknomPravila
    var casi: List<Long> = emptyList()
    for (i in 0 until 3) {
        val n = P.dovoli("a", casi, "a", 1_000L + i * 1_000L)
        enako("vrnitev ${i + 1} dovoljena", true, n != null)
        casi = n ?: casi
    }
    enako("cetrta v 60 s zavrnjena", null, P.dovoli("a", casi, "a", 4_000L))
    enako("po izteku okna spet dovoljena", true, P.dovoli("a", casi, "a", 61_500L) != null)
    enako("drug posnetek zacne znova", listOf(5_000L), P.dovoli("a", casi, "b", 5_000L))
    enako("cas nazaj (ura) ne steje", listOf(500L), P.dovoli("a", listOf(9_000L, 9_500L, 9_900L), "a", 500L))
    if (napak > 0) { println("ZaOknomPravila: $napak napak"); exitProcess(1) }
    println("ZaOknomPravila: vse ok")
}
