package si.safeer.tv

/**
 * Barvni gumbi daljinca v brskalniku. Brez Androida, preizkus v tests/TvTipkeTest.kt.
 *
 * TV 3. 11. 2026 (lastnik): na Cloudflare/captcha strani ni mogel vklopiti kazalca - ZELENI gumb na njegovem
 * Philipsu odpre sistemske Info/Vire (izmerjeno: getevent KEY_GREEN pride, aplikacija ga ne dobi). RDECEGA ne
 * potrebuje, zato kazalec vklopi tudi RDECI; MENU ostane hitri izbor strani.
 */
object TvTipke {
    enum class Dejanje { KAZALEC, HITRE_STRANI, ZAZNAMKI, NIC }

    private const val PROG_RED = 183
    private const val PROG_GREEN = 184
    private const val PROG_YELLOW = 185
    private const val MENU = 82
    private const val INFO = 165
    private const val BOOKMARK = 174

    fun dejanje(keyCode: Int): Dejanje = when (keyCode) {
        PROG_RED, PROG_GREEN, INFO -> Dejanje.KAZALEC
        MENU -> Dejanje.HITRE_STRANI
        PROG_YELLOW, BOOKMARK -> Dejanje.ZAZNAMKI
        else -> Dejanje.NIC
    }
}
