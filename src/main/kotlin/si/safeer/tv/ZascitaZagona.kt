package si.safeer.tv

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Zascita od prve zahteve hladnega zagona (raziskava 4. 11. 2026).
 *
 * Trije deli: drevo grozenj (vgrajeno + seznami agenta), podpisani vir Safeer Threat Intelligence in EasyList. Drevo in
 * podpisani vir se odpreta iz kazala (mmap) sinhrono v onCreate, se preden se odpre prvi zavihek; ce kazala ni (prvi
 * zagon, nov seznam), se zgradita v ozadju. EasyList se vedno prevede v ozadju (TV ~1,5 s).
 *
 * Pravilo cakanja v shouldInterceptRequest (nitni bazen WebView, nikoli glavna nit):
 *  - vsaka zahteva pocaka na drevo grozenj in podpisani vir,
 *  - zahteve PODVIROV pocakajo se na EasyList (pravila EasyList glavnega okvirja nikoli ne blokirajo),
 *  - nihce ne caka dlje kot do [NAJDLJE_OD_ZAGONA_MS] od zacetka nalaganja (zataknjeno nalaganje ne ustavi brskanja).
 * Prej (23c4dd28) je vsaka zahteva cakala najvec 500 ms; na TV so prve zahteve po 501 ms sle brez seznamov (4. 11. 2026),
 * zato zdaj velja samo rok od zacetka.
 *
 * Brez Androida (cista Kotlin), da jo preizkus pozene v JVM.
 */
object ZascitaZagona {
    /** Po tem casu od zacetka nalaganja nobena zahteva vec ne caka. */
    const val NAJDLJE_OD_ZAGONA_MS = 3_000L

    enum class Del { DREVO, PODPISANI, EASYLIST }

    /**
     * Cista funkcija pravila: koliko ms sme zahteva se cakati na del, ki ni [pripravljeno].
     * [zacetoOb] < 0: nalaganje se ni zaceto (ta proces nima agenta seznamov) - ni cakanja. Glavna nit nikoli ne caka (ANR).
     */
    fun kolikoCakati(pripravljeno: Boolean, zacetoOb: Long, zdaj: Long, glavnaNit: Boolean): Long {
        if (pripravljeno || glavnaNit || zacetoOb < 0) return 0
        val doRoka = zacetoOb + NAJDLJE_OD_ZAGONA_MS - zdaj
        return if (doRoka <= 0) 0 else doRoka
    }

    @Volatile private var grozenj = CountDownLatch(2)
    @Volatile private var easyList = CountDownLatch(1)
    @Volatile private var koncani = AtomicLongArray(3)   // 0 = ni koncan, sicer cas od zacetka + 1
    @Volatile private var prvaPorocana = AtomicBoolean(false)
    @Volatile private var zacetoOb = -1L

    /** Ura (preizkus jo zamenja). */
    @Volatile var ura: () -> Long = { System.currentTimeMillis() }

    val grozenjPripravljeno: Boolean get() = grozenj.count == 0L
    val easyListPripravljen: Boolean get() = easyList.count == 0L
    val pripravljeno: Boolean get() = grozenjPripravljeno && easyListPripravljen

    /** Nalaganje se je zacelo (enkrat na proces). */
    @Synchronized fun zacni(zdaj: Long = ura()) {
        if (zacetoOb < 0) zacetoOb = zdaj
    }

    /**
     * Del je pripravljen. Vrne cas od zacetka nalaganja za ta del (ms; -1, ce nalaganje ni bilo zaceto) ob prvem klicu,
     * ob ponovnem klicu null (ze porocano). Prej je dnevnik ob podpisanem viru izpisal skupni cas, ki se ni bil znan
     * (»pripravljena=false po -1 ms«), ker je bil podpisani vir pripravljen pred seznami.
     */
    fun koncan(del: Del, zdaj: Long = ura()): Long? {
        val zac = zacetoOb
        val cas = if (zac < 0) -1L else maxOf(0L, zdaj - zac)
        if (!koncani.compareAndSet(del.ordinal, 0L, cas + 2)) return null
        if (del == Del.EASYLIST) easyList.countDown() else grozenj.countDown()
        return cas
    }

    /** Cas od zacetka do priprave dela (ms), -1 ce ni pripravljen ali nalaganje ni bilo zaceto. */
    fun casDela(del: Del): Long {
        val v = koncani.get(del.ordinal)
        return if (v <= 1L) -1L else v - 2
    }

    /** Cas, ko je bilo pripravljeno vse (najpoznejsi del), ali -1. */
    val pripravljenoPoMs: Long get() = if (!pripravljeno) -1L else Del.values().maxOf { casDela(it) }

    /** Kratko porocilo za dnevnik (logcat): cas vsakega dela posebej. */
    fun porocilo(): String = "drevo ${casDela(Del.DREVO)} ms, podpisani ${casDela(Del.PODPISANI)} ms, EasyList ${casDela(Del.EASYLIST)} ms" +
        (if (pripravljeno) ", vse po $pripravljenoPoMs ms" else ", se ni vse pripravljeno")

    fun seznamiKoncani(): Long? = koncan(Del.DREVO)
    fun podpisaniKoncan(): Long? = koncan(Del.PODPISANI)
    fun easyListKoncan(): Long? = koncan(Del.EASYLIST)

    /** Izid cakanja: koliko je zahteva cakala in ali je bilo takrat pripravljeno to, na kar je cakala. */
    class Izid(val cakalaMs: Long, val pripravljeno: Boolean, val odZacetkaMs: Long, val prva: Boolean)

    /**
     * Pocaka (omejeno z rokom od zacetka) na drevo in podpisani vir, zahteva podvira ([glavniOkvir] false) tudi na EasyList.
     * Na glavni niti se vrne takoj. Po pripravi vsega ni nobenega cakanja vec.
     */
    fun pocakaj(glavnaNit: Boolean, glavniOkvir: Boolean = false): Izid? {
        if (grozenj.count == 0L && easyList.count == 0L && prvaPorocana.get()) return null
        val zac = zacetoOb
        if (zac < 0) return null
        val zdaj = ura()
        val g = grozenj
        val cakajG = kolikoCakati(g.count == 0L, zac, zdaj, glavnaNit)
        if (cakajG > 0) await(g, cakajG)
        if (!glavniOkvir) {
            val e = easyList
            val cakajE = kolikoCakati(e.count == 0L, zac, ura(), glavnaNit)
            if (cakajE > 0) await(e, cakajE)
        }
        val konec = ura()
        val ok = grozenjPripravljeno && (glavniOkvir || easyListPripravljen)
        val prva = prvaPorocana.compareAndSet(false, true)
        return Izid(konec - zdaj, ok, konec - zac, prva)
    }

    private fun await(zapah: CountDownLatch, ms: Long) {
        try { zapah.await(ms, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    /** Samo za preizkuse v JVM: zacetno stanje procesa. */
    internal fun ponastaviZaPreizkus() {
        grozenj = CountDownLatch(2)
        easyList = CountDownLatch(1)
        koncani = AtomicLongArray(3)
        prvaPorocana = AtomicBoolean(false)
        zacetoOb = -1L
        ura = { System.currentTimeMillis() }
    }
}
