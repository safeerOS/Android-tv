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
                k == "spatial" && v == "off" -> s.copy(spatialIzklop = true)
                k == "scit" && v == "off" -> s.copy(scitIzklop = true)
                k == "anim" && v == "off" -> s.copy(animIzklop = true)
                else -> s
            }
        }
        return s
    }

    private fun preberiLastnost(): String? = try {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java, String::class.java).invoke(null, LASTNOST, "") as? String
    } catch (_: Throwable) { null }

    /** Enkrat ob prvi rabi v procesu. */
    val stikala: Stikala by lazy { razclenji(preberiLastnost()) }
}
