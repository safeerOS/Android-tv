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
        { r -> Thread(r, "safeer-theintrodb").apply { isDaemon = true } }, ThreadPoolExecutor.DiscardOldestPolicy())
    /** Skupni rok poizvedbe (readTimeout velja samo za posamezno branje - streznik po bajt bi drzal nit ure). */
    private const val SKUPNI_ROK_MS = 8_000L
    /** Naslov poizvedbe -> odseki (prazen seznam = ni podatkov). Najvec 200 vnosov, potem se seja zacne znova. */
    private val seja = ConcurrentHashMap<String, List<PreskokPravila.Odsek>>()

    /** Odseki za [naslov] (iz [PreskokPravila.poizvedba]) v ozadju; [izid] se klice v niti ozadja. */
    fun pridobi(naslov: String, izid: (List<PreskokPravila.Odsek>) -> Unit) {
        seja[naslov]?.let { izid(it); return }
        izvajalec.execute {
            // Napaka razclenjevanja ne sme pustiti posnetka brez odgovora (slepi pregled 13. 11. 2026, enako kot Linux).
            val odseki = try { beri(naslov)?.let { PreskokPravila.razcleni(it) } } catch (e: RuntimeException) { null }
            if (odseki != null) {
                if (seja.size >= 200) seja.clear()
                seja[naslov] = odseki
            }
            izid(odseki ?: emptyList())
        }
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
            try {
                if (p.responseCode != 200) null
                else p.inputStream.use { beriZRokom(it, p.contentLengthLong, System.nanoTime() + SKUPNI_ROK_MS * 1_000_000L) }
            } finally { p.disconnect() }
        }
    } catch (e: Exception) {
        android.util.Log.i("SafeerOsMedia", "preskok: TheIntroDB ni odgovoril (${e.javaClass.simpleName})")
        null
    }
}
