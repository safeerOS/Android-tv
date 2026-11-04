package si.safeer.tv

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Zascita od prve zahteve hladnega zagona (raziskava 4. 11. 2026).
 *
 * Ob zagonu sta v pomnilniku le seme (~70 domen) in prazen nabor EasyList; seznami (agent seznamov in podpisani vir)
 * se nalozijo v ozadju, MainActivity pa takoj odpre zavihek z naslovom iz namere VIEW. Prve zahteve so tako sle mimo
 * seznamov. Zdaj zahteva v shouldInterceptRequest (nitni bazen WebView, nikoli glavna nit) omejeno pocaka, da je prvo
 * nalaganje koncano. Po tem ni nobenega cakanja vec (eno branje spremenljivke volatile).
 *
 * Brez Androida (cista Kotlin), da jo preizkus pozene v JVM.
 */
object ZascitaZagona {
    /** Najdlje cakanje ene zahteve. */
    const val CAKAJ_NA_ZAHTEVO_MS = 500L
    /** Po tem casu od zacetka nalaganja nobena zahteva vec ne caka (ce se nalaganje zatakne, brskanje ne stoji). */
    const val NAJDLJE_OD_ZAGONA_MS = 3_000L

    /**
     * Cista funkcija pravila: koliko ms sme zahteva se cakati na prvo nalaganje seznamov.
     * [zacetoOb] < 0: nalaganje se ni zaceto (ta proces nima agenta seznamov) - ni cakanja, kot prej.
     * Glavna nit nikoli ne caka (ANR).
     */
    fun kolikoCakati(pripravljeno: Boolean, zacetoOb: Long, zdaj: Long, glavnaNit: Boolean): Long {
        if (pripravljeno || glavnaNit || zacetoOb < 0) return 0
        val doRoka = zacetoOb + NAJDLJE_OD_ZAGONA_MS - zdaj
        if (doRoka <= 0) return 0
        return minOf(CAKAJ_NA_ZAHTEVO_MS, doRoka)
    }

    /** Dela prvega nalaganja: seznami agenta (vgrajeni v drevo in EasyList) in podpisani vir Safeer Threat Intelligence. */
    private val zapah = CountDownLatch(2)
    private val seznami = AtomicBoolean(false)
    private val podpisani = AtomicBoolean(false)
    private val prvaPorocana = AtomicBoolean(false)

    @Volatile private var zacetoOb = -1L
    @Volatile var pripravljenoPoMs = -1L
        private set

    val pripravljeno: Boolean get() = zapah.count == 0L

    /** Nalaganje se je zacelo (enkrat na proces). */
    @Synchronized fun zacni(zdaj: Long = System.currentTimeMillis()) {
        if (zacetoOb < 0) zacetoOb = zdaj
    }

    fun seznamiKoncani() { if (seznami.compareAndSet(false, true)) koncanDel() }
    fun podpisaniKoncan() { if (podpisani.compareAndSet(false, true)) koncanDel() }

    private fun koncanDel() {
        zapah.countDown()
        if (zapah.count == 0L && zacetoOb >= 0) pripravljenoPoMs = System.currentTimeMillis() - zacetoOb
    }

    /** Izid cakanja: koliko je zahteva cakala in ali je bila zascita takrat pripravljena. */
    class Izid(val cakalaMs: Long, val pripravljeno: Boolean, val odZacetkaMs: Long, val prva: Boolean)

    /** Pocaka (omejeno) na prvo nalaganje. Na glavni niti ([glavnaNit]) se vrne takoj. */
    fun pocakaj(glavnaNit: Boolean): Izid? {
        if (zapah.count == 0L && prvaPorocana.get()) return null
        val zac = zacetoOb
        val zdaj = System.currentTimeMillis()
        val cakaj = kolikoCakati(pripravljeno, zac, zdaj, glavnaNit)
        if (cakaj > 0) {
            try { zapah.await(cakaj, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        if (zac < 0) return null
        val konec = System.currentTimeMillis()
        val prva = prvaPorocana.compareAndSet(false, true)
        return Izid(konec - zdaj, pripravljeno, konec - zac, prva)
    }
}
