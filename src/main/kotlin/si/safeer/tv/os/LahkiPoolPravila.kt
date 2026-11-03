package si.safeer.tv.os

/**
 * Ciste odlocitve poola WebView ([LahkiWebViewPool]) brez Androida - preizkus tests/LahkiPoolPravilaTest.kt.
 * Zunanji pregled 3. 11. 2026 (Philips, Android 11, 2 GB): v procesu najvec en zivi WebView; ko igra Media3, nobenega.
 */
object LahkiPoolPravila {
    /**
     * Media3 je zacel igrati: pogled poola obdrzimo samo, ce v njem igra stran ([LahkaStran], stopnja 2). Polni pogon
     * (SpletniIgralec, stopnja 3) ima svoj WebView, najet pogled za iskanje toka pa ni vec potreben (prej je cakal na
     * vrnitev - izmerjeno ~90 MB izrisovalnika cez cel film).
     */
    fun obdrziObNativnem(lahkaStranIgra: Boolean): Boolean = lahkaStranIgra

    /** Nevidno branje sme najeti pogled samo, ce ga nima ze drug lastnik in ne igra stran v polnem pogonu. */
    fun smeNajeti(zasedenPriDrugem: Boolean, polniPogonIgra: Boolean): Boolean = !zasedenPriDrugem && !polniPogonIgra

    /** Izrisovalnik (skupen vsem WebView-jem procesa) koncamo samo brez zivih WebView-jev in od Androida 10 (API 29). */
    fun koncajIzrisovalnik(zivih: Int, api: Int): Boolean = api >= 29 && zivih == 0
}
