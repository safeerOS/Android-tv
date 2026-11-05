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

    /** Odtis kot stevilo (0..2^32-1): tv_spatial.js dokaz tipke primerja kot stevilo, brez toString, ki ga prepise stran. */
    fun odtisStevilo(zeton: String, niz: String): Long {
        var h = -2128831035
        for (c in "$zeton|$niz|$zeton") { h = h xor c.code; h *= 16777619 }
        return h.toLong() and 0xffffffffL
    }

    /**
     * Pregled Groka 5. 11. 2026 (najdbe 3, 7, 10): _safeer_navigate_spatial, _safeer_click_focused_card in _safeer_highlight
     * doseze tudi stran - z njimi je premikala izbiro. Pred vsakim klicem ob tipki daljinca aplikacija v istem skriptu poslje
     * nakljucen izziv in odtis zetona strani (en dokaz = en klic, tv_spatial.js SAFEER_TIPKA). Brez zetona prazno.
     * Neodvisni pregled 5. 11. 2026 (najdbi 5 in 6): dokaz pokrije strogo narascajoc [stevec] pogleda (starejsi ujeti par ne
     * velja vec), ime izvoza ima nakljucni del strani [nonce] (stran ga ne more vnaprej zasesti).
     */
    fun ukazTipke(zeton: String?, nonce: String?, stevec: Long): String {
        if (zeton == null || zeton.length < 32 || !jeNonce(nonce) || stevec <= 0L) return ""
        val izziv = novIzziv()
        val f = izvoz(nonce, "_safeer_tipka")
        return "$f&&$f('$izziv',$stevec,${odtisStevilo(zeton, "tipka|$stevec|$izziv")});"
    }

    private val NONCE = Regex("[0-9a-f]{16,64}")
    private fun jeNonce(n: String?): Boolean = n != null && NONCE.matches(n)

    /** Ime izvoza tv_spatial.js na window z nakljucnim delom strani; brez veljavnega dela staro ime (ga ni - klic ne naredi nic). */
    fun izvoz(nonce: String?, ime: String): String = if (jeNonce(nonce)) "window.${ime}_$nonce" else "window.$ime"

    fun gorVNaslovnoVrstico(odgovor: String?, nacinAplikacije: Boolean, napredek: Int): Boolean {
        val o = (odgovor ?: "").trim().trim('"')
        if (o == "-1") return true
        if (o.isEmpty() || o == "null") return !(nacinAplikacije && napredek < 100)
        return false
    }
}
