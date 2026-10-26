package si.safeer.tv.os

/**
 * Diagnostika razvijalca (lastnik 25. 10. 2026: meritve samo po samodejni poti, brez rocne izbire vira): lastnost `debug.*`
 * nastavi samo lupina adb, ob ponovnem zagonu izgine (ni persist). Vrednost = cas izteka v sekundah od 1970; velja samo,
 * ce je v prihodnosti in najvec [NAJVEC_S] naprej (pozabljena lastnost se sama izklopi). Enako kot WebtorPravila.diagnostikaVelja
 * (pregled 26, T3) na veji webtor-popravek. Cista pravila - tests/DiagnostikaPravilaTest.kt.
 */
object DiagnostikaPravila {
    const val NAJVEC_S = 6L * 3600
    /** Z3 v zivo: samodejna izbira vzame torrent na tej napravi (opozorilo pred rojem ostane). */
    const val P2P_SAMO = "debug.safeer.p2p_samo"

    /** [vrednost] lastnosti, [zdajS] = ura naprave v sekundah. */
    fun velja(vrednost: String?, zdajS: Long): Boolean {
        val doS = vrednost?.trim()?.toLongOrNull() ?: return false
        return doS > zdajS && doS - zdajS <= NAJVEC_S
    }

    /** Ime lastnosti diagnostike: samo `debug.safeer.*` (lastnosti drugih aplikacij ne beremo). */
    fun dovoljenaLastnost(ime: String): Boolean = Regex("^debug\\.safeer\\.[a-z0-9_]{1,40}$").matches(ime)

    /**
     * Pregled Grok 32, G4/G6: diagnostika je vklopljena samo za dovoljeno ime, pri vklopljenem razvijalskem adb
     * ([adbVklopljen]) in z veljavnim rokom. [beri] (getprop - nov proces) se klice SAMO, ce prvi dve veljata: brez adb
     * takoj false (navaden uporabnik ne placa procesa ob vsaki izbiri toka; lastnosti brez adb ne nastavi nihce).
     */
    fun vklopljena(lastnost: String, zdajS: Long, adbVklopljen: () -> Boolean, beri: (String) -> String?): Boolean =
        dovoljenaLastnost(lastnost) && adbVklopljen() && velja(beri(lastnost), zdajS)
}
