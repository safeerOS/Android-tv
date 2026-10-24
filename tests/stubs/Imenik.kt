package si.safeer.tv.os

/**
 * Nadomestek imenika (DNS) za preizkus sonde v JVM (tests/run_sonda_tests.sh) - pravi [Imenik] rabi android.net.DnsResolver.
 * Preizkus sam nastavi, kaj imenik »odgovori« ([odgovori]).
 */
object Imenik {
    const val KONTROLA = "safeer.si"
    @Volatile var odgovori: (List<String>) -> List<Boolean?> = { imena -> imena.map { null } }
    fun obstaja(imena: List<String>, rokMs: Long): List<Boolean?> = odgovori(imena)
}
