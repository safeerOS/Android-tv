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

    /** Toliko ponovitev drzanega OK (~1 s) vklopi kazalec v nacinu D-pad. */
    const val PONOVITEV_ZA_KAZALEC = 12

    /** Drzan OK na strani (ne v vrstici zgoraj) v nacinu D-pad vklopi kazalec; v nacinu miske je drzan OK vlecenje. */
    fun drzanOkVkljuciKazalec(keyCode: Int, ponovitev: Int, kazalecViden: Boolean, vVrstici: Boolean): Boolean =
        (keyCode == 23 || keyCode == 66) && ponovitev == PONOVITEV_ZA_KAZALEC && !kazalecViden && !vVrstici

    /**
     * Ali GOR s strani gre v naslovno vrstico. [odgovor]: kar vrne window._safeer_navigate_spatial('UP') (»-1« = v strani
     * ni cilja visje in stran je na vrhu; »null« ali prazno = skripta daljinca v dokumentu se ni). Zunanji pregled fokusa
     * 3. 11. 2026: v nacinu aplikacije se skripta vstavi sele pri 35 % nalaganja (onProgressChanged) - prvi GOR v se
     * praznem dokumentu je zapustil aplikacijo. Med nalaganjem ([napredek] < 100) GOR v nacinu aplikacije zato ne odide;
     * ko je stran nalozena in skripte ni (stran brez nase navigacije), GOR vrstico pokaze kot prej - uporabnik ni ujet.
     */
    fun gorVNaslovnoVrstico(odgovor: String?, nacinAplikacije: Boolean, napredek: Int): Boolean {
        val o = (odgovor ?: "").trim().trim('"')
        if (o == "-1") return true
        if (o.isEmpty() || o == "null") return !(nacinAplikacije && napredek < 100)
        return false
    }
}
