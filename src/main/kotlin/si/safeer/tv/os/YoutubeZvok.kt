package si.safeer.tv.os

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Zvocni tok posnetka YouTube za zvocnik v omrezju (lastnik, 25. 10. 2026 16:50: »Neposredno od YouTuba«): ta naprava
 * vprasa YouTube (InnerTube, odjemalec VISIONOS - [YoutubeZvokPravila]) za tok AAC/Opus, zvocnik ga potegne sam (Cast,
 * DLNA) ali prek posrednika te naprave ([ZvocnikStreznik]). Brez racuna in brez kljucev; posnetkov z DRM ne ponudimo.
 *
 * Izid hranimo po ID posnetka (tok velja ~6 h), da meni »Predvajaj na zvocniku« ve, ali gumb sme pokazati, in da
 * naslednja skladba v vrsti stece brez cakanja. Naslovov tokov ne zapisujemo v dnevnik (vsebujejo javni IP).
 */
object YoutubeZvok {
    private const val TAG = "SafeerYoutubeZvok"
    /** Ena zahteva na YouTube skupaj (povezava, poslano, prebrano) - delavec zvocnikov ne sme obviseti (Grok 25, Z5). */
    private const val ROK_ZAHTEVE_MS = 12_000L
    /** Znan »ne« (DRM, ni toka, posnetek ni na voljo) velja toliko; potem poskusimo znova. */
    private const val NE_VELJA_MS = 30 * 60_000L
    private const val NAJVEC_ZAPISOV = 300

    /** Posnetka ni mogoce poslati na zvocnik (DRM, brez neposrednega naslova, YouTube ga ne da). */
    class NiToka(val razlog: YoutubeZvokPravila.Razlog) : Exception("YouTube: zvocnega toka ni ($razlog)")

    private class Zapis(val izid: YoutubeZvokPravila.Izid, val kdajMs: Long)

    private val zapisi = ConcurrentHashMap<String, Zapis>()
    private val vTeku = ConcurrentHashMap.newKeySet<String>()
    /** Zadnji poskus v ozadju po ID: meni, ki se ob izidu osvezi, ne sme ob napaki omrezja sprasevati v zanki. */
    private val poskusi = ConcurrentHashMap<String, Long>()
    private val delavec = Executors.newSingleThreadExecutor { r -> Thread(r, "safeer-youtube-zvok").apply { isDaemon = true } }
    private val glavna = Handler(Looper.getMainLooper())
    @Volatile private var obisk = ""
    @Volatile private var obiskOd = 0L
    private val zaklepObiska = Any()

    /** ID posnetka, ce je skladba posnetek YouTube s strani (enota spletnega vira); sicer null. */
    fun videoId(sk: Jamendo.Skladba): String? =
        if (SpletniVir.jeEnota(sk) && !sk.radio) YoutubeZvokPravila.videoId(sk.povezava) else null

    fun jeYoutube(sk: Jamendo.Skladba): Boolean = videoId(sk) != null

    private fun zdajS() = System.currentTimeMillis() / 1000

    /** Ze razresen in se veljaven tok (za meni: gumb samo, ko vemo, da gre). */
    fun znanTok(sk: Jamendo.Skladba): YoutubeZvokPravila.Tok? {
        val id = videoId(sk) ?: return null
        return zapisi[id]?.izid?.tok?.takeIf { YoutubeZvokPravila.velja(it, zdajS()) }
    }

    /** Nedavno ugotovljeno, da posnetka ni mogoce poslati (DRM, ni toka, ni na voljo). */
    fun znanoNe(sk: Jamendo.Skladba): Boolean {
        val id = videoId(sk) ?: return false
        val z = zapisi[id] ?: return false
        return z.izid.tok == null && System.currentTimeMillis() - z.kdajMs < NE_VELJA_MS &&
            z.izid.razlog in setOf(YoutubeZvokPravila.Razlog.DRM, YoutubeZvokPravila.Razlog.NI_OK, YoutubeZvokPravila.Razlog.BREZ_TOKA)
    }

    /** Razresi v ozadju (meni, naslednja v vrsti); [koncano] na glavni niti, ko je izid znan. */
    @Suppress("UNUSED_PARAMETER")       // ctx: klicatelji so zasloni in storitev; visitorData je samo v pomnilniku
    fun pripravi(ctx: Context, sk: Jamendo.Skladba, koncano: () -> Unit) {
        val id = videoId(sk) ?: return
        if (znanTok(sk) != null || znanoNe(sk)) return
        val zdaj = System.currentTimeMillis()
        if (zdaj - (poskusi[id] ?: 0L) < 60_000L || !vTeku.add(id)) return
        if (poskusi.size > NAJVEC_ZAPISOV) poskusi.clear()
        poskusi[id] = zdaj
        delavec.execute {
            try { razresiId(id) } catch (e: Exception) { Log.i(TAG, "priprava: ${e.javaClass.simpleName}") }
            finally { vTeku.remove(id); glavna.post { try { koncano() } catch (_: Exception) {} } }
        }
    }

    /** Tok za zvocnik; klic z delavca (omrezje, do ~1 s). [NiToka], ce ga ni. */
    @Suppress("UNUSED_PARAMETER")
    fun razresi(ctx: Context, sk: Jamendo.Skladba): YoutubeZvokPravila.Tok {
        val id = videoId(sk) ?: throw NiToka(YoutubeZvokPravila.Razlog.NAPAKA)
        return razresiId(id)
    }

    private fun razresiId(id: String): YoutubeZvokPravila.Tok {
        zapisi[id]?.izid?.tok?.takeIf { YoutubeZvokPravila.velja(it, zdajS()) }?.let { return it }
        var izid = try { poizvedi(id, obiskovalec(false)) } catch (e: IOException) {
            Log.w(TAG, "player: ${e.javaClass.simpleName}")
            throw NiToka(YoutubeZvokPravila.Razlog.NAPAKA)
        }
        // Preverba »not a bot«: visitorData je potekel ali ga YouTube ne sprejme vec - nov in se en poskus.
        if (izid.razlog == YoutubeZvokPravila.Razlog.BOT) izid = try { poizvedi(id, obiskovalec(true)) } catch (e: IOException) {
            throw NiToka(YoutubeZvokPravila.Razlog.NAPAKA)
        }
        if (zapisi.size > NAJVEC_ZAPISOV) zapisi.clear()
        zapisi[id] = Zapis(izid, System.currentTimeMillis())
        val t = izid.tok
        Log.i(TAG, "posnetek: ${izid.razlog}" + (if (t != null) " itag=${t.itag} ${t.mime}${if (t.ipV6) " IPv6" else ""}" else " ${izid.opis}"))
        return t ?: throw NiToka(izid.razlog)
    }

    private fun poizvedi(id: String, visitorData: String): YoutubeZvokPravila.Izid =
        YoutubeZvokPravila.izberi(post(YoutubeZvokPravila.PLAYER, YoutubeZvokPravila.telesoPlayer(id, visitorData),
            YoutubeZvokPravila.glavePlayer(visitorData)), zdajS())

    /**
     * visitorData: samo v pomnilniku, najvec 12 h (pregled Grok 25, Z6: na disku bi bil trajen psevdonim, ki poveze vse
     * posnetke; po ponovnem zagonu aplikacije nov stane eno zahtevo visitor_id, ~180 ms). [nov] = obvezno nov (po preverbi
     * »not a bot«).
     */
    private fun obiskovalec(nov: Boolean): String = synchronized(zaklepObiska) {
        val zdaj = System.currentTimeMillis()
        if (!nov && obisk.isNotEmpty() && zdaj - obiskOd < YoutubeZvokPravila.OBISK_VELJA_MS) return obisk
        val v = try {
            YoutubeZvokPravila.visitorIzOdgovora(post(YoutubeZvokPravila.VISITOR_ID, YoutubeZvokPravila.telesoVisitorId(),
                YoutubeZvokPravila.glaveVisitorId())).orEmpty()
        } catch (e: IOException) { Log.w(TAG, "visitor_id: ${e.javaClass.simpleName}"); "" }
        if (v.isNotEmpty()) { obisk = v; obiskOd = zdaj }
        v
    }

    /**
     * POST na youtube.com: samo https, brez preusmeritev, omejen odgovor in skupni rok [ROK_ZAHTEVE_MS] (preverjen med
     * branjem; posamezno branje caka najvec readTimeout). Piskotkov ne posljemo: aplikacija ne nastavi CookieHandler.setDefault
     * (preverjeno 25. 10. 2026), HttpURLConnection jih brez njega ne shranjuje in ne posilja.
     */
    private fun post(url: String, telo: String, glave: Map<String, String>): String {
        val konec = System.currentTimeMillis() + ROK_ZAHTEVE_MS
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 6_000; c.readTimeout = 8_000
        c.requestMethod = "POST"; c.doOutput = true
        glave.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            c.outputStream.use { it.write(telo.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            val out = ByteArrayOutputStream()
            c.inputStream.use { vhod ->
                val kos = ByteArray(16 * 1024)
                while (true) {
                    val n = vhod.read(kos); if (n < 0) break
                    out.write(kos, 0, n)
                    if (out.size() > YoutubeZvokPravila.NAJVEC_ODGOVORA) throw IOException("odgovor je prevelik")
                    if (System.currentTimeMillis() > konec) throw IOException("odgovor ni prisel v roku")
                }
            }
            return out.toString("UTF-8")
        } finally { c.disconnect() }
    }
}
