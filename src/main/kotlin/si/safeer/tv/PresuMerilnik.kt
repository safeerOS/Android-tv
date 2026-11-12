package si.safeer.tv

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.LongAdder

/**
 * Merilnik cene shouldInterceptRequest (H56, 12. 11. 2026): na TV 24ur.com porabi 2x vec procesorja kot TV Bro, vzrok ni dokazan.
 * Za vsak klic (nitni bazen WebView) zapisemo trajanje; en izpis na stran (po 8 s od zacetka strani ali ob naslednji strani).
 * Samo stevila in imena niti: nobenega naslova ali gostitelja (zasebnost). Brez spremembe vedenja: izid klica je nespremenjen.
 *
 * PRIVZETO IZKLOPLJEN (pregled 12. 11. 2026: merilnik ne sme popaciti meritve, ki jo meri): vroca pot je ena preverba
 * [vklopljen]. Vklop samo prek A/B stikala presu=on (AbStikala). Vklopljen: brez globalne kljucavnice (LongAdder, CAS za
 * najvec, ime niti v ThreadLocal); kljucavnica samo ob izpisu in ob novi strani.
 *
 * Brez Androida (cista Kotlin), da jo preizkus pozene v JVM. Izpis nastavi aplikacija ([izpis]).
 */
object PresuMerilnik {
    /** Vrste merjenih klicev. */
    enum class Kat(val ime: String) { VSE("vse"), SCIT("scit"), CAKANJE("cakanje"), ADBLOCK("adblock"), SW("sw") }

    const val OBDOBJE_MS = 8_000L
    private const val NAJVEC_NITI = 16

    /** Vklop merilnika (privzeto izklopljen). */
    @Volatile @JvmField var vklopljen: Boolean = false

    /** Ura v ns (preizkus jo zamenja). */
    @Volatile var uraNs: () -> Long = { System.nanoTime() }
    /** Ura v ms za obdobje (preizkus jo zamenja). */
    @Volatile var uraMs: () -> Long = { System.currentTimeMillis() }
    /** Kam gre izpis (aplikacija: logcat). */
    @Volatile var izpis: (String) -> Unit = { }

    private class NitStat(val ime: String) { val n = LongAdder(); val skupajNs = LongAdder(); val najvecNs = AtomicLong() }

    private val lock = Any()
    private val stN = Array(Kat.values().size) { LongAdder() }
    private val stSkupaj = Array(Kat.values().size) { LongAdder() }
    private val stNajvec = AtomicLongArray(Kat.values().size)
    private val niti = ConcurrentLinkedQueue<NitStat>()
    private val nitiSt = java.util.concurrent.atomic.AtomicInteger(0)
    private val nitLokalno = ThreadLocal<NitStat?>()
    private val nitBrez = NitStat("")
    private val zacetekMs = AtomicLong(-1L)
    private val porocano = AtomicBoolean(false)
    @Volatile private var stranN = 0

    /** Izmeri [f] in vrne njen izid; izjema gre naprej, cas se zapise vseeno. Izklopljen: samo [f]. */
    inline fun <T> izmeri(k: Kat, f: () -> T): T {
        if (!vklopljen) return f()
        val t0 = uraNs()
        try {
            return f()
        } finally {
            zabelezi(k, uraNs() - t0)
        }
    }

    private fun nitStat(): NitStat? {
        nitLokalno.get()?.let { return if (it === nitBrez) null else it }
        // Najvec NAJVEC_NITI niti ima svoje stevce; ostale se v nitih ne stejejo (v skupnih stevcih ze).
        val s = if (nitiSt.incrementAndGet() <= NAJVEC_NITI) NitStat(Thread.currentThread().name.take(40)).also { niti.add(it) } else nitBrez
        nitLokalno.set(s)
        return if (s === nitBrez) null else s
    }

    private fun naj(a: AtomicLong, v: Long) { while (true) { val c = a.get(); if (v <= c || a.compareAndSet(c, v)) return } }

    fun zabelezi(k: Kat, ns: Long) {
        val d = if (ns < 0) 0 else ns
        val i = k.ordinal
        stN[i].increment(); stSkupaj[i].add(d)
        while (true) { val c = stNajvec.get(i); if (d <= c || stNajvec.compareAndSet(i, c, d)) break }
        if (k == Kat.VSE) {
            nitStat()?.let { it.n.increment(); it.skupajNs.add(d); naj(it.najvecNs, d) }
        }
        // Promet servisnega delavca ne zacne ure strani in ne sprozi izpisa (pregled 12. 11. 2026, B).
        if (k == Kat.SW) return
        val zdaj = uraMs()
        zacetekMs.compareAndSet(-1L, zdaj)
        val zac = zacetekMs.get()
        if (zac >= 0 && zdaj - zac >= OBDOBJE_MS && porocano.compareAndSet(false, true)) {
            val vrstica = synchronized(lock) { sestavi() }
            try { izpis(vrstica) } catch (_: Throwable) { }
        }
    }

    /** Nova stran: ce prejsnja se ni bila porocana (kratka stran), se izpise zdaj; nato stevci od zacetka. */
    fun novaStran() {
        var vrstica: String? = null
        synchronized(lock) {
            if (!porocano.get() && stN[Kat.VSE.ordinal].sum() > 0) vrstica = sestavi()
            for (i in stN.indices) { stN[i].reset(); stSkupaj[i].reset(); stNajvec.set(i, 0) }
            for (s in niti) { s.n.reset(); s.skupajNs.reset(); s.najvecNs.set(0) }
            zacetekMs.set(-1L)
            porocano.set(false)
            stranN++
        }
        vrstica?.let { try { izpis(it) } catch (_: Throwable) { } }
    }

    private fun ms(ns: Long): String = "%.1f".format(java.util.Locale.ROOT, ns / 1_000_000.0)

    /** Klic samo pod [lock]. */
    private fun sestavi(): String {
        val sb = StringBuilder("H56 shouldIntercept stran#$stranN")
        for (k in Kat.values()) {
            val n = stN[k.ordinal].sum()
            if (n > 0) sb.append(" | ").append(k.ime).append(" n=").append(n).append(" skupaj=").append(ms(stSkupaj[k.ordinal].sum()))
                .append("ms najvec=").append(ms(stNajvec.get(k.ordinal))).append("ms")
        }
        val nitiN = niti.filter { it.n.sum() > 0 }
        if (nitiN.isNotEmpty()) {
            sb.append(" | niti:")
            for (s in nitiN) sb.append(' ').append(s.ime).append(" n=").append(s.n.sum()).append(" skupaj=").append(ms(s.skupajNs.sum())).append("ms")
        }
        return sb.toString()
    }
}
