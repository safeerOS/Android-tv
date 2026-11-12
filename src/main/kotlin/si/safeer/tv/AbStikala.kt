package si.safeer.tv

/**
 * A/B stikala za meritev hitrosti strani (lastnik 11. 11. 2026: 24ur.com na TV porabi 2x vec procesorja kot TV Bro).
 *
 * Z istim APK-jem izmerimo, kaj razloziti razliko. VARNOST: stikala bere SAMO iz sistemske lastnosti debug.safeer.ab, ki jo
 * lahko nastavi samo `adb shell setprop` (ne intent, ne shranjene nastavitve, ne stran). Branje enkrat ob prvi rabi v procesu.
 * Brez lastnosti (ali neznana/prazna vrednost) ni nobenega vedenjskega stikala. Format: `ua=android,wide=off,css=off`.
 *
 *   ua=android|tv   uporabniski agent Chrome Android oz. pametne TV namesto namiznega (privzeto namizni)
 *   wide=off        brez useWideViewPort/loadWithOverviewMode (viewport TV namesto 1280 px)
 *   css=off         brez kozmeticnega CSS (skrivanje oglasnih elementov)
 *   prevod=dvonitni prevod seznamov pravil (EasyList) v dveh nitih (privzeto ENONITNI do meritve na TV; logcat »prevod N ms«)
 *   presu=on        merilnik shouldInterceptRequest (PresuMerilnik, logcat SafeerPerf H56); privzeto izklopljen
 *   cssseznam=off   kozmeticni CSS samo s splosnimi pravili: brez pravil s seznamov (EasyList), vezanih na domeno strani (24ur.com)
 *   cssyt=off       kozmeticni CSS brez YouTube selektorjev (ytp-, ytm-, ytd-): 63 namesto 114 pravil (11. 11. 2026, namizje)
 *   spatial=off     brez tv_spatial.js (daljinec) - samo za meritev
 *   scit=off        brskalnik ne filtrira zahtevkov (AdBlockEngine, ThreatBlockEngine) - samo za meritev; DNS Scit je loceno
 *   anim=off        CSS: animation/transition none na vsaki strani
 */
object AbStikala {
    const val LASTNOST = "debug.safeer.ab"

    data class Stikala(
        val ua: String = "",
        val wideIzklop: Boolean = false,
        val cssIzklop: Boolean = false,
        val cssSeznamIzklop: Boolean = false,
        val prevodDvonitni: Boolean = false,
        val presuVklop: Boolean = false,
        val cssYtIzklop: Boolean = false,
        val spatialIzklop: Boolean = false,
        val scitIzklop: Boolean = false,
        val animIzklop: Boolean = false,
    ) {
        /** Ali je kaj spremenjeno proti privzetemu vedenju. */
        val karkoli: Boolean get() = this != Stikala()
    }

    /** Cisto razclenjevanje; neznan kljuc ali vrednost se prezre (privzeto vse izklopljeno). */
    fun razclenji(niz: String?): Stikala {
        if (niz.isNullOrBlank() || niz.length > 200) return Stikala()
        var s = Stikala()
        for (del in niz.split(',')) {
            val i = del.indexOf('=')
            if (i <= 0) continue
            val k = del.substring(0, i).trim().lowercase()
            val v = del.substring(i + 1).trim().lowercase()
            s = when {
                k == "ua" && (v == "android" || v == "tv") -> s.copy(ua = v)
                k == "wide" && v == "off" -> s.copy(wideIzklop = true)
                k == "css" && v == "off" -> s.copy(cssIzklop = true)
                k == "cssseznam" && v == "off" -> s.copy(cssSeznamIzklop = true)
                k == "prevod" && v == "dvonitni" -> s.copy(prevodDvonitni = true)
                k == "presu" && v == "on" -> s.copy(presuVklop = true)
                k == "cssyt" && v == "off" -> s.copy(cssYtIzklop = true)
                k == "spatial" && v == "off" -> s.copy(spatialIzklop = true)
                k == "scit" && v == "off" -> s.copy(scitIzklop = true)
                k == "anim" && v == "off" -> s.copy(animIzklop = true)
                else -> s
            }
        }
        return s
    }

    /** Selektor YouTube (ytp-, ytm-, ytd-): za stikalo cssyt=off. */
    internal fun jeYouTubeSelektor(sel: String): Boolean = sel.contains("ytp-") || sel.contains("ytm-") || sel.contains("ytd-")

    /**
     * Izbor kozmeticnih selektorjev po stikalih (cista funkcija). Brez stikal: splosna + seznamska pravila, brez podvojenih,
     * v istem vrstnem redu kot doslej (vedenje NESPREMENJENO).
     */
    fun izberiSelektorje(splosna: List<String>, seznam: List<String>, s: Stikala): List<String> {
        val sp = if (s.cssYtIzklop) splosna.filterNot(::jeYouTubeSelektor) else splosna
        val sz = if (s.cssSeznamIzklop) emptyList() else seznam
        return (sp + sz).distinct()
    }

    private fun preberiLastnost(): String? = try {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java, String::class.java).invoke(null, LASTNOST, "") as? String
    } catch (_: Throwable) { null }

    /** Enkrat ob prvi rabi v procesu. */
    val stikala: Stikala by lazy { razclenji(preberiLastnost()) }
}
