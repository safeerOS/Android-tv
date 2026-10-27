package si.safeer.tv

private var napak = 0
private fun enako(opis: String, pricakovano: Any?, dobljeno: Any?) {
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }
}

/** Lastnik 27. 10. 2026: zanka pojavnih oken (vsakih 0,3 s) je TV napolnila do OutOfMemoryError. */
fun main() {
    println("Preizkus meje pojavnih oken")
    val casi = ArrayDeque<Long>()
    val dovoljeni = (0 until 20).count { PojavnaOknaMeja.dovoli(casi, 1_000L + it * 300L) }
    enako("zanka 20 oken v 6 s: dovoljena samo 4", PojavnaOknaMeja.NAJVEC, dovoljeni)
    enako("po 10 s spet dovoljeno", true, PojavnaOknaMeja.dovoli(casi, 1_000L + 19 * 300L + PojavnaOknaMeja.OKNO_MS))
    val prijava = ArrayDeque<Long>()
    enako("eno okno za prijavo: dovoljeno", true, PojavnaOknaMeja.dovoli(prijava, 5_000L))
    enako("drugo cez minuto: dovoljeno", true, PojavnaOknaMeja.dovoli(prijava, 65_000L))
    val nazaj = ArrayDeque<Long>().apply { addLast(10_000L) }
    enako("ura nazaj (ponovni zagon): star zapis ne blokira", true, PojavnaOknaMeja.dovoli(nazaj, 1_000L, najvec = 1))
    if (napak == 0) println("PojavnaOknaMejaTest: OK") else { println("Napak: $napak"); kotlin.system.exitProcess(1) }
}
