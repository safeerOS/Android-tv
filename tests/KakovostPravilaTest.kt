package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 133: kakovost glede na hitrost povezave ([KakovostPravila]). */
fun main() {
    println("Preizkus kakovosti glede na hitrost")
    preveriEnako("film 130 min, epizoda 50 min", 130 to 50, KakovostPravila.minut(false) to KakovostPravila.minut(true))
    // 77,38 GB v 130 min = ~79 Mb/s (Inception 4K REMUX s TV-ja)
    preveriEnako("bitna hitrost REMUX", 79_364_102L, KakovostPravila.bitnaHitrost(77.38, 130))
    preveriEnako("neznana velikost: 0", 0L, KakovostPravila.bitnaHitrost(0.0, 130))
    preveriEnako("REMUX pri 50 Mb/s ne ustreza", false, KakovostPravila.ustreza(77.38, 130, 50_000_000))
    preveriEnako("REMUX pri 200 Mb/s ustreza", true, KakovostPravila.ustreza(77.38, 130, 200_000_000))
    preveriEnako("1080p 2,5 GB pri 10 Mb/s ustreza", true, KakovostPravila.ustreza(2.5, 130, 10_000_000))
    preveriEnako("brez meritve vse ustreza", true, KakovostPravila.ustreza(77.38, 130, 0))
    preveriEnako("neznana velikost ustreza", true, KakovostPravila.ustreza(0.0, 130, 1_000_000))
    // Vrstni red: [REMUX 77 GB, 4K 20 GB, 1080p 2,5 GB, neznano] pri 25 Mb/s
    preveriEnako("ustrezni naprej v svojem redu, ostali od najmanjsega", listOf(2, 3, 1, 0),
        KakovostPravila.urediZaHitrost(listOf(77.38, 20.0, 2.5, 0.0), 130, 25_000_000))
    preveriEnako("brez meritve red ostane", listOf(0, 1, 2), KakovostPravila.urediZaHitrost(listOf(77.38, 20.0, 2.5), 130, 0))
    preveriEnako("hitra povezava: red ostane", listOf(0, 1, 2), KakovostPravila.urediZaHitrost(listOf(77.38, 20.0, 2.5), 130, 300_000_000))
    preveriEnako("vzorec 2 MB velja", true, KakovostPravila.vzorecVelja(2L * 1024 * 1024))
    preveriEnako("vzorec 100 kB ne velja", false, KakovostPravila.vzorecVelja(100_000))
    preveriEnako("vzorec 25 MB v 2 s = 100 Mb/s", 100_000_000L, KakovostPravila.hitrostIzVzorca(25_000_000, 2_000))
    preveriEnako("prekratek vzorec", 0L, KakovostPravila.hitrostIzVzorca(25_000_000, 500))
    preveriEnako("premajhen vzorec", 0L, KakovostPravila.hitrostIzVzorca(100_000, 5_000))
    preveriEnako("prva meritev", 50L, KakovostPravila.zgladi(0, 50))
    preveriEnako("glajenje 70/30", 79L, KakovostPravila.zgladi(100, 30))
    preveriEnako("meritev velja teden", true, KakovostPravila.velja(0, KakovostPravila.VELJA_MS - 1))
    preveriEnako("starejsa ne velja", false, KakovostPravila.velja(0, KakovostPravila.VELJA_MS))
    if (napak == 0) println("KakovostPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
