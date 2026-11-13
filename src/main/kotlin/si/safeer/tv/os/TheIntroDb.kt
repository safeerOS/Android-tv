package si.safeer.tv.os

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Casi odsekov iz TheIntroDB (glej [PreskokPravila]). Klic gre neposredno s te naprave (pogoji TheIntroDB), samo https
 * na [PreskokPravila.GOSTITELJ], brez preusmeritev, najvec [PreskokPravila.NAJVEC_BAJTOV] bajtov, brez piskotkov in
 * brez podatkov o napravi. Odgovor ostane samo v pomnilniku procesa za to sejo (predpomnjenje seje je dovoljeno).
 */
object TheIntroDb {
    /** Ena nit, vrsta najvec 4: pocasen streznik ne nabira nalog (neodvisni pregled 13. 11. 2026, N5); preseg se zavrze. */
    private val izvajalec = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(4),
        { r -> Thread(r, "safeer-theintrodb").apply { isDaemon = true } }) { r, ex ->
        // Polna vrsta: najstarejsa naloga odpade, a njen klicatelj dobi prazen odgovor (drugi slepi pregled 13. 11.).
        (ex.queue.poll() as? Naloga)?.izid?.invoke(emptyList())
        if (!ex.isShutdown) ex.execute(r)
    }

    private class Naloga(val izid: (List<PreskokPravila.Odsek>) -> Unit, telo: Runnable) : Runnable by telo
    /** Straza skupnega roka: prekine povezavo tudi med glavami (readTimeout velja za posamezno branje). */
    private val straza = java.util.concurrent.ScheduledThreadPoolExecutor(1) { r ->
        Thread(r, "safeer-theintrodb-straza").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }      // preklicana straza ne drzi povezave v vrsti
    /** Skupni rok poizvedbe (readTimeout velja samo za posamezno branje - streznik po bajt bi drzal nit ure). */
    private const val SKUPNI_ROK_MS = 8_000L
    /** Naslov poizvedbe -> odseki (prazen seznam = ni podatkov). Najvec 200 vnosov, potem se seja zacne znova. */
    private val seja = ConcurrentHashMap<String, List<PreskokPravila.Odsek>>()

    /** Odseki za [naslov] (iz [PreskokPravila.poizvedba]) v ozadju; [izid] se klice v niti ozadja. */
    fun pridobi(naslov: String, izid: (List<PreskokPravila.Odsek>) -> Unit) {
        seja[naslov]?.let { izid(it); return }
        izvajalec.execute(Naloga(izid) {
            // Klicatelj dobi odgovor v vsakem primeru (tudi ob napaki razclenjevanja ali Error) - finally.
            var odseki: List<PreskokPravila.Odsek>? = null
            try {
                odseki = beri(naslov)?.let { PreskokPravila.razcleni(it) }
                if (odseki != null) {
                    if (seja.size >= 200) seja.clear()
                    seja[naslov] = odseki
                }
            } catch (e: RuntimeException) {
                odseki = null
            } catch (e: StackOverflowError) {
                odseki = null
            } finally {
                izid(odseki ?: emptyList())
            }
        })
    }

    /** Najvec [PreskokPravila.NAJVEC_BAJTOV] bajtov in skupni rok; vec ali pozneje = null. */
    private fun beriZRokom(vhod: java.io.InputStream, dolzina: Long, rokNs: Long): String? {
        if (dolzina > PreskokPravila.NAJVEC_BAJTOV) return null
        val izhod = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            if (System.nanoTime() > rokNs) return null
            val n = vhod.read(buf, 0, minOf(buf.size, PreskokPravila.NAJVEC_BAJTOV + 1 - izhod.size()))
            if (n < 0) break
            izhod.write(buf, 0, n)
            if (izhod.size() > PreskokPravila.NAJVEC_BAJTOV) return null
        }
        return izhod.toString("UTF-8")
    }

    private fun beri(naslov: String): String? = try {
        val url = URL(naslov)
        if (url.protocol != "https" || url.host != PreskokPravila.GOSTITELJ) null else {
            val p = url.openConnection() as HttpURLConnection
            p.connectTimeout = PreskokPravila.ROK_MS; p.readTimeout = PreskokPravila.ROK_MS
            p.instanceFollowRedirects = false
            p.useCaches = false
            p.setRequestProperty("Accept", "application/json")
            p.setRequestProperty("User-Agent", "Safeer OS (https://safeer.si)")
            val prekini = straza.schedule({ p.disconnect() }, SKUPNI_ROK_MS, TimeUnit.MILLISECONDS)
            try {
                if (p.responseCode != 200) null
                else p.inputStream.use { beriZRokom(it, p.contentLengthLong, System.nanoTime() + SKUPNI_ROK_MS * 1_000_000L) }
            } finally { prekini.cancel(false); p.disconnect() }
        }
    } catch (e: Exception) {
        android.util.Log.i("SafeerOsMedia", "preskok: TheIntroDB ni odgovoril (${e.javaClass.simpleName})")
        null
    }
}
