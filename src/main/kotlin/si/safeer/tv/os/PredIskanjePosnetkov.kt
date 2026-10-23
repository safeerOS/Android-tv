package si.safeer.tv.os

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Naloga 89 (zamisel iz BitChord: seznami Spotify kot predvajljive strani brez cakanja): posnetke za uvozene skladbe brez
 * posnetka (»isci:«, [UvozSeznama]) iscemo v ozadju za VSE sezname - ne le enkrat ob uvozu ([GlasbaActivity.poisciPosnetke]).
 * Prekinjeno iskanje, neuspeli zadetki in seznami z drugih naprav (SeznamiSink) so prej ostali neiskani in vsako predvajanje
 * je cakalo na iskanje.
 *
 * - Vrstni red: izmenicno po polozaju ([OsPravila.vrstniRedIskanja]) - vsak seznam je takoj predvajljiv od zacetka.
 * - Najvec [NA_TEK] skladb na tek, [NITI] hkrati, nizka prednost; en tek hkrati; ne na omejenem omrezju (mobilni podatki).
 * - Neuspeh si zapomnimo (shramba, najvec [NAJVEC_NEUSPEHOV]); isti ne iscemo znova [OsPravila.ISKANJE_ZNOVA_MS].
 */
object PredIskanjePosnetkov {
    private const val PREFS = "safeer_uvoz_neuspehi"
    private const val NA_TEK = 60
    private const val NITI = 2
    private const val NAJVEC_NEUSPEHOV = 2000
    /** Tek najvec na toliko casa (odpiranje Music ga ne sprozi vsakic). */
    private const val NAJVEC_NA_MS = 10 * 60_000L

    private val tece = AtomicBoolean(false)
    @Volatile private var zadnjiTek = 0L

    /** Zacne tek v ozadju, ce je kaj za iskati; [poSpremembi] (iz ozadja) ob vsakem shranjenem delu. */
    fun zacni(c: Context, poSpremembi: () -> Unit = {}) {
        val zdaj = android.os.SystemClock.elapsedRealtime()
        if (zadnjiTek != 0L && zdaj - zadnjiTek in 0 until NAJVEC_NA_MS) return
        if (!tece.compareAndSet(false, true)) return
        zadnjiTek = zdaj
        val app = c.applicationContext
        Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try { tek(app, poSpremembi) } catch (_: Throwable) { } finally { tece.set(false) }
        }, "safeer-uvoz-vnaprej").apply { isDaemon = true }.start()
    }

    private fun tek(app: Context, poSpremembi: () -> Unit) {
        val omrezje = app.getSystemService(android.net.ConnectivityManager::class.java)
        if (omrezje == null || omrezje.activeNetwork == null || omrezje.isActiveNetworkMetered) return
        val shramba = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val neuspehi = shramba.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()
        val zdaj = System.currentTimeMillis()
        val seznami = MedijskiViri.seznami(app)
        val poId = HashMap<String, Jamendo.Skladba>()
        val kandidati = seznami.map { sz ->
            sz.skladbe.filter { UvozSeznama.jeIskana(it) && OsPravila.poskusiZnovaIskanje(neuspehi[it.id], zdaj) }
                .onEach { poId[it.id] = it }.map { it.id }
        }
        val vrsta = OsPravila.vrstniRedIskanja(kandidati, NA_TEK)
        if (vrsta.isEmpty()) return
        android.util.Log.i("SafeerOsMedia", "uvoz vnaprej: iscem posnetke za ${vrsta.size} skladb (seznamov ${kandidati.count { it.isNotEmpty() }})")
        val bazen = java.util.concurrent.Executors.newFixedThreadPool(NITI)
        val najdene = ConcurrentHashMap<String, Jamendo.Skladba>()
        val novi = ConcurrentHashMap<String, Long>()
        val narejenih = AtomicInteger(0)
        fun shraniDel() {
            val del = HashMap(najdene)
            del.keys.forEach { najdene.remove(it) }
            if (del.isNotEmpty()) { MedijskiViri.zamenjaj(app, del); try { poSpremembi() } catch (_: Throwable) { } }
        }
        val gotovo = java.util.concurrent.CountDownLatch(vrsta.size)
        for (id in vrsta) {
            val sk = poId[id]
            if (sk == null) { gotovo.countDown(); continue }
            try {
                bazen.execute {
                    try {
                        val n = try { UvozSeznama.najdi(sk) } catch (_: Exception) { null }
                        if (n != null) najdene[sk.id] = n else novi[sk.id] = System.currentTimeMillis()
                        if (narejenih.incrementAndGet() % 10 == 0) shraniDel()
                    } finally { gotovo.countDown() }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { gotovo.countDown() }
        }
        try { gotovo.await(10, java.util.concurrent.TimeUnit.MINUTES) } catch (_: InterruptedException) { }
        bazen.shutdownNow()
        shraniDel()
        if (novi.isNotEmpty()) {
            // Najvec [NAJVEC_NEUSPEHOV] zapisov: najstarejsi gredo (shramba je cela v pomnilniku procesa).
            val vsi = (neuspehi + novi).entries.sortedByDescending { it.value }.take(NAJVEC_NEUSPEHOV)
            shramba.edit().clear().apply { vsi.forEach { putLong(it.key, it.value) } }.apply()
        }
        android.util.Log.i("SafeerOsMedia", "uvoz vnaprej: najdenih ${vrsta.size - novi.size}, brez zadetka ${novi.size}")
    }
}
