package si.safeer.tv.os

/**
 * Naloga 113: posnetek YouTube iz glasbenega seznama (npr. seznam z zalozniskimi posnetki, ki jih vgradni predvajalnik
 * zavrne) - ciste odlocitve brez Androida (preizkus: tests/YoutubeVrstaPravilaTest.kt).
 *
 * Prva pot je zvok prek nasega predvajalnika Media3 ([YoutubeZvok], odjemalec VISIONOS - ista pot kot »Predvajaj na
 * zvocniku«): ne potrebuje vgradnje (Error 150/152/153 ga ne zadeva), ne potrebuje strani m.youtube.com v WebView (ta na
 * Android TV obvisi brez sporocila) in ne odklepa DRM (posnetek z DRM ostane na spletni strani ali se preskoci).
 * Spletna stran ([SpletniIgralec]) je rezerva; ce v [ROK_ZASTOJA_MS] ne zacne igrati, gre vrsta naprej z obvestilom.
 */
object YoutubeVrstaPravila {
    enum class Pot { MEDIA3_ZVOK, SPLETNA_STRAN }

    /** Toliko casa ima stran (vgradni predvajalnik ali polna stran) od nalaganja, da zacne igrati. */
    const val ROK_ZASTOJA_MS = 15_000L

    /** Po toliko zaporednih zastojih vrsta obstane (ne preletimo celega seznama brez zvoka). */
    const val NAJVEC_ZAPOREDNIH_ZASTOJEV = 3

    /**
     * Kako zaceti posnetek iz vrste: posnetek YouTube gre najprej v Media3. Na zvocniku v omrezju odloca obstojeca pot
     * ([GlasbaStoritev.naZvocnikVVrsti]); posnetek, za katerega YouTube nedavno ni dal toka (DRM, ni na voljo), gre
     * naravnost na spletno stran - ne cakamo se enkrat na isti »ne«.
     */
    fun pot(jeYoutube: Boolean, naZvocniku: Boolean, znanoNe: Boolean): Pot =
        if (jeYoutube && !naZvocniku && !znanoNe) Pot.MEDIA3_ZVOK else Pot.SPLETNA_STRAN

    /**
     * Stran obvisi: od nalaganja je minil rok, predvajanje ni nikoli steklo (polozaj 0, medij ni pripravljen), uporabnik
     * pa zeli, da igra (pavza ni zastoj). Prej je tak posnetek ostal v BUFFERING za vedno, brez sporocila.
     */
    fun zastoj(odNalaganjaMs: Long, polozajMs: Long, pripravljeno: Boolean, zelja: Boolean): Boolean =
        zelja && !pripravljeno && polozajMs <= 0L && odNalaganjaMs >= ROK_ZASTOJA_MS

    /** Po zastoju: naslednja skladba, ce obstaja in nismo ze [NAJVEC_ZAPOREDNIH_ZASTOJEV]-krat zapored obstali. */
    fun naprejPoZastoju(zaporedniZastoji: Int, imaNaslednjo: Boolean): Boolean =
        imaNaslednjo && zaporedniZastoji < NAJVEC_ZAPOREDNIH_ZASTOJEV

    /**
     * Lastnik 27. 10. 2026 (testni telefon): posnetek YouTube, izbran na polici ali v zadetkih (ne iz seznama), je sel
     * mimo vrste naravnost na spletno stran (POLNI_POGON youtube.com, »stopnja=3«): sam v vrsti, zato Naprej ni delal in
     * ob koncu se je predvajalnik ugasnil. Glasba z YouTuba (ne video) zato igra kot vrsta posnetkov z zaslona - prek
     * Media3 kot seznami (naloga 113), z naslednjo skladbo ob koncu. Video ostane na strani (slika).
     */
    fun kotVrsta(jeYoutube: Boolean, video: Boolean): Boolean = jeYoutube && !video

    /**
     * Posamezen posnetek (nadaljuj, deljena povezava, predlogi): glasba z YouTuba gre najprej v Media3 (vrsta z enim
     * posnetkom), stran ostane rezerva. [dovoliPrevzem] false je ze rezerva po napaki Media3 - takrat stran, brez zanke.
     */
    fun posameznoPrekMedia3(jeYoutube: Boolean, video: Boolean, dovoliPrevzem: Boolean): Boolean =
        dovoliPrevzem && kotVrsta(jeYoutube, video)
}
