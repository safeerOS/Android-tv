package si.safeer.tv

/**
 * Tokovi, ki jih domaci predvajalnik (ExoPlayer) na tej strani ni zmogel - npr. licence ni dobil,
 * ker jo stran poda samo v svojem JavaScriptu. Take predvaja stran sama v WebViewu.
 *
 * Varnost pred zanko (predaja -> ponovno nalaganje -> nov prevzem -> ista napaka -> ...) ne sme
 * biti odvisna samo od primerjave naslovov: CDN lahko ob vsakem nalaganju strani vrne nov naslov
 * manifesta (nov zeton ali nova seja v poti). Zato je predaj na enem izvoru najvec [NAJVEC]; potem
 * stran do odhoda na drug izvor predvaja vse sama.
 *
 * Brez Androida, da je preizkusena v navadnem JVM (tests/PredajaStraniTest.kt).
 */
class PredajaStrani(private val istiTok: (String, String) -> Boolean) {
    private val tokovi = ArrayList<String>()

    /** Koliko tokov smo na tem izvoru ze vrnili strani. */
    var predaj: Int = 0
        private set

    fun prepusti(mpd: String) {
        tokovi.add(mpd)
        predaj++
    }

    fun jePrepuscen(mpd: String): Boolean = predaj >= NAJVEC || tokovi.any { istiTok(it, mpd) }

    /** Uporabnik je sel na drug izvor: tam spet poskusi domaci predvajalnik. */
    fun pocisti() {
        tokovi.clear()
        predaj = 0
    }

    companion object {
        const val NAJVEC = 2
    }
}
