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
    private const val NASTAVITVE = "safeer_youtube_zvok"
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
    fun pripravi(ctx: Context, sk: Jamendo.Skladba, koncano: () -> Unit) {
        val id = videoId(sk) ?: return
        if (znanTok(sk) != null || znanoNe(sk)) return
        val zdaj = System.currentTimeMillis()
        if (zdaj - (poskusi[id] ?: 0L) < 60_000L || !vTeku.add(id)) return
        if (poskusi.size > NAJVEC_ZAPISOV) poskusi.clear()
        poskusi[id] = zdaj
        val app = ctx.applicationContext
        delavec.execute {
            try { razresiId(app, id) } catch (e: Exception) { Log.i(TAG, "priprava: ${e.javaClass.simpleName}") }
            finally { vTeku.remove(id); glavna.post { try { koncano() } catch (_: Exception) {} } }
        }
    }

    /** Tok za zvocnik; klic z delavca (omrezje, do ~1 s). [NiToka], ce ga ni. */
    fun razresi(ctx: Context, sk: Jamendo.Skladba): YoutubeZvokPravila.Tok {
        val id = videoId(sk) ?: throw NiToka(YoutubeZvokPravila.Razlog.NAPAKA)
        return razresiId(ctx.applicationContext, id)
    }

    private fun razresiId(app: Context, id: String): YoutubeZvokPravila.Tok {
        zapisi[id]?.izid?.tok?.takeIf { YoutubeZvokPravila.velja(it, zdajS()) }?.let { return it }
        var izid = try { poizvedi(id, obiskovalec(app, false)) } catch (e: IOException) {
            Log.w(TAG, "player: ${e.message}")
            throw NiToka(YoutubeZvokPravila.Razlog.NAPAKA)
        }
        // Preverba »not a bot«: visitorData je potekel ali ga YouTube ne sprejme vec - nov in se en poskus.
        if (izid.razlog == YoutubeZvokPravila.Razlog.BOT) izid = try { poizvedi(id, obiskovalec(app, true)) } catch (e: IOException) {
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

    /** visitorData (12 h, v zasebnih nastavitvah aplikacije); [nov] = obvezno nov (po preverbi »not a bot«). */
    private fun obiskovalec(app: Context, nov: Boolean): String = synchronized(zaklepObiska) {
        val zdaj = System.currentTimeMillis()
        if (!nov) {
            if (obisk.isNotEmpty() && zdaj - obiskOd < YoutubeZvokPravila.OBISK_VELJA_MS) return obisk
            val p = app.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)
            val v = p.getString("visitor", "").orEmpty()
            val od = p.getLong("visitor_od", 0L)
            if (v.isNotEmpty() && zdaj - od in 0 until YoutubeZvokPravila.OBISK_VELJA_MS) { obisk = v; obiskOd = od; return v }
        }
        val v = try {
            YoutubeZvokPravila.visitorIzOdgovora(post(YoutubeZvokPravila.VISITOR_ID, YoutubeZvokPravila.telesoVisitorId(),
                YoutubeZvokPravila.glaveVisitorId())).orEmpty()
        } catch (e: IOException) { Log.w(TAG, "visitor_id: ${e.message}"); "" }
        if (v.isNotEmpty()) {
            obisk = v; obiskOd = zdaj
            app.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().putString("visitor", v).putLong("visitor_od", zdaj).apply()
        }
        v
    }

    /** POST na youtube.com: samo https, brez preusmeritev, omejen odgovor. */
    private fun post(url: String, telo: String, glave: Map<String, String>): String {
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
                }
            }
            return out.toString("UTF-8")
        } finally { c.disconnect() }
    }
}
