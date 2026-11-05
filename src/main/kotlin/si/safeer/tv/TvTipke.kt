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
    /**
     * OK na polju za datoteko: [odgovor] je, kar vrne window._safeer_click_focused_card('[izziv]') (tv_spatial.js
     * SAFEER_OK_DATOTEKA). TV 4. 11. 2026 (w3schools tryhtml5_input_type_file): klik iz skripta nima uporabnikove
     * aktivacije, Chromium izbirnika datotek ne odpre. Pri polju za datoteko ima polje fokus in aplikacija poslje pravo
     * tipko Enter. Drugi krog pregleda 5. 11. 2026: stran je funkcijo lahko zasedla pred nasim skriptom in sama vrnila
     * "datoteka" - velja samo "datoteka:" + [odtisZetona] zetona strani ([zeton], pozna ga samo nas skript) in izziva tega
     * OK (tv_spatial.js SAFEER_DOKAZ). Vse drugo (golo "datoteka", true, false, null) ostane navaden klik skripta.
     */
    fun okNaDatoteki(odgovor: String?, zeton: String, izziv: String): Boolean {
        if (zeton.length < 32 || !IZZIV.matches(izziv)) return false
        val o = (odgovor ?: "").trim().trim('"')
        val pricakovan = "datoteka:" + odtisZetona(zeton, "datoteka|$izziv")
        return java.security.MessageDigest.isEqual(o.toByteArray(Charsets.UTF_8), pricakovan.toByteArray(Charsets.UTF_8))
    }

    private val IZZIV = Regex("[0-9a-f]{16,64}")

    /** Nakljucen izziv za en OK (32 sestnajstiskih znakov). */
    fun novIzziv(): String {
        val b = ByteArray(16)
        java.security.SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    /**
     * Isti odtis kot tv_spatial.js odtisZetona (FNV-1a, 32 bitov, po znakih UTF-16, base36): zeton na obeh koncih, da javni
     * znak namestitve (odtis samega zetona) ni zacetno stanje za podaljsanje. Zeton ima 192 bitov, izziv je vsakic nov.
     */
    fun odtisZetona(zeton: String, niz: String): String {
        var h = -2128831035   // 2166136261 kot Int
        for (c in "$zeton|$niz|$zeton") { h = h xor c.code; h *= 16777619 }
        return "safeer-" + (h.toLong() and 0xffffffffL).toString(36)
    }

    fun gorVNaslovnoVrstico(odgovor: String?, nacinAplikacije: Boolean, napredek: Int): Boolean {
        val o = (odgovor ?: "").trim().trim('"')
        if (o == "-1") return true
        if (o.isEmpty() || o == "null") return !(nacinAplikacije && napredek < 100)
        return false
    }
}
