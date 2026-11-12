package si.safeer.tv

/**
 * Merilnik cene shouldInterceptRequest (H56, 12. 11. 2026): na TV 24ur.com porabi 2x vec procesorja kot TV Bro, vzrok ni dokazan.
 * Za vsak klic (nitni bazen WebView) zapisemo trajanje; en izpis na stran (po 8 s od zacetka strani ali ob naslednji strani).
 * Samo stevila in imena niti: nobenega naslova ali gostitelja (zasebnost). Brez spremembe vedenja: izid klica je nespremenjen.
 *
 * Brez Androida (cista Kotlin), da jo preizkus pozene v JVM. Izpis nastavi aplikacija ([izpis]).
 */
object PresuMerilnik {
    /** Vrste merjenih klicev. */
    enum class Kat(val ime: String) { VSE("vse"), SCIT("scit"), CAKANJE("cakanje"), ADBLOCK("adblock"), SW("sw") }

    const val OBDOBJE_MS = 8_000L
    private const val NAJVEC_NITI = 16

    /** Ura v ns (preizkus jo zamenja). */
    @Volatile var uraNs: () -> Long = { System.nanoTime() }
    /** Ura v ms za obdobje (preizkus jo zamenja). */
    @Volatile var uraMs: () -> Long = { System.currentTimeMillis() }
    /** Kam gre izpis (aplikacija: logcat). */
    @Volatile var izpis: (String) -> Unit = { }

    private class Stat { var n = 0L; var skupajNs = 0L; var najvecNs = 0L }

    private val lock = Any()
    private val kat = Array(Kat.values().size) { Stat() }
    private val niti = LinkedHashMap<String, Stat>()
    private var zacetekMs = -1L
    private var porocano = false
    private var stranN = 0

    /** Izmeri [f] in vrne njen izid; izjema gre naprej, cas se zapise vseeno. */
    inline fun <T> izmeri(k: Kat, f: () -> T): T {
        val t0 = uraNs()
        try {
            return f()
        } finally {
            zabelezi(k, uraNs() - t0)
        }
    }

    fun zabelezi(k: Kat, ns: Long) {
        var vrstica: String? = null
        synchronized(lock) {
            val d = if (ns < 0) 0 else ns
            if (zacetekMs < 0) zacetekMs = uraMs()
            val s = kat[k.ordinal]
            s.n++; s.skupajNs += d; if (d > s.najvecNs) s.najvecNs = d
            if (k == Kat.VSE) {
                val ime = Thread.currentThread().name.take(40)
                val ns2 = niti[ime] ?: if (niti.size < NAJVEC_NITI) Stat().also { niti[ime] = it } else null
                if (ns2 != null) { ns2.n++; ns2.skupajNs += d; if (d > ns2.najvecNs) ns2.najvecNs = d }
            }
            if (!porocano && uraMs() - zacetekMs >= OBDOBJE_MS) { porocano = true; vrstica = sestavi() }
        }
        vrstica?.let { try { izpis(it) } catch (_: Throwable) { } }
    }

    /** Nova stran: ce prejsnja se ni bila porocana (kratka stran), se izpise zdaj; nato stevci od zacetka. */
    fun novaStran() {
        var vrstica: String? = null
        synchronized(lock) {
            if (!porocano && kat[Kat.VSE.ordinal].n > 0) vrstica = sestavi()
            for (s in kat) { s.n = 0; s.skupajNs = 0; s.najvecNs = 0 }
            niti.clear()
            zacetekMs = -1L
            porocano = false
            stranN++
        }
        vrstica?.let { try { izpis(it) } catch (_: Throwable) { } }
    }

    private fun ms(ns: Long): String = "%.1f".format(java.util.Locale.ROOT, ns / 1_000_000.0)

    /** Klic samo pod [lock]. */
    private fun sestavi(): String {
        val sb = StringBuilder("H56 shouldIntercept stran#$stranN")
        for (k in Kat.values()) {
            val s = kat[k.ordinal]
            if (s.n > 0) sb.append(" | ").append(k.ime).append(" n=").append(s.n).append(" skupaj=").append(ms(s.skupajNs))
                .append("ms najvec=").append(ms(s.najvecNs)).append("ms")
        }
        if (niti.isNotEmpty()) {
            sb.append(" | niti:")
            for ((ime, s) in niti) sb.append(' ').append(ime).append(" n=").append(s.n).append(" skupaj=").append(ms(s.skupajNs)).append("ms")
        }
        return sb.toString()
    }
}
