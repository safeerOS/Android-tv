package si.safeer.tv.os

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * TV v zivo: samo uradni, javno objavljeni prenosi izdajateljev (preverjeno do posnetka, 27. 9. 2026),
 * brez posrednikov. Enak seznam kot Safeer OS na racunalniku (core/zakoniti_viri.py TV_V_ZIVO).
 * RTV SLO javnega toka nima: pokazemo jo samo uporabnikom iz Slovenije in odpremo uradno stran.
 */
object TvVZivo {
    private class Kanal(val id: String, val ime: String, val jezik: String, val tok: String,
                        val stran: String, val domaca: String, val samoZa: String, val drzava: String)

    private val KANALI = listOf(
        Kanal("rtvslo", "RTV SLO v živo", "sl", "", "https://365.rtvslo.si/v-zivo", "https://www.rtvslo.si", "SI", "SI"),
        Kanal("dw-en", "DW News", "en", "https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8", "", "https://www.dw.com", "", "DE"),
        Kanal("dw-de", "DW Deutsch", "de", "https://dwamdstream103.akamaized.net/hls/live/2015526/dwstream103/index.m3u8", "", "https://www.dw.com", "", "DE"),
        Kanal("dw-es", "DW Español", "es", "https://dwamdstream104.akamaized.net/hls/live/2015530/dwstream104/index.m3u8", "", "https://www.dw.com", "", "DE"),
        Kanal("tagesschau24", "tagesschau24 (ARD)", "de", "https://tagesschau.akamaized.net/hls/live/2020115/tagesschau/tagesschau_1/master.m3u8", "", "https://www.tagesschau.de", "", "DE"),
        Kanal("f24-en", "France 24 English", "en", "https://live.france24.com/hls/live/2037218/F24_EN_HI_HLS/master_5000.m3u8", "", "https://www.france24.com", "", "FR"),
        Kanal("f24-fr", "France 24 Français", "fr", "https://live.france24.com/hls/live/2037179/F24_FR_HI_HLS/master_5000.m3u8", "", "https://www.france24.com", "", "FR"),
        Kanal("f24-es", "France 24 Español", "es", "https://live.france24.com/hls/live/2037220/F24_ES_HI_HLS/master_5000.m3u8", "", "https://www.france24.com", "", "FR"),
        Kanal("aje", "Al Jazeera English", "en", "https://live-hls-apps-aje-fa.getaj.net/AJE/index.m3u8", "", "https://www.aljazeera.com", "", "QA"),
        Kanal("trt-world", "TRT World", "en", "https://tv-trtworld.medya.trt.com.tr/master.m3u8", "", "https://www.trtworld.com", "", "TR"),
        Kanal("cbs-news", "CBS News 24/7", "en", "https://cbsn-us.cbsnstream.cbsnews.com/out/v1/55a8648e8f134e82a470f83d562deeca/master.m3u8", "", "https://www.cbsnews.com", "", "US"),
        Kanal("arirang", "Arirang TV", "en", "https://amdlive-ch01-ctnd-com.akamaized.net/arirang_1ch/smil:arirang_1ch.smil/playlist.m3u8", "", "https://www.arirang.com", "", "KR"),
        Kanal("redbull", "Red Bull TV", "en", "https://rbmn-live.akamaized.net/hls/live/590964/BoRB-AT/master.m3u8", "", "https://www.redbull.com", "", "AT"),
    )

    private const val TAG = "SafeerOsTvVZivo"
    private const val PREFS = "tv_v_zivo_ikone"
    private const val VELJA_MS = 7L * 24 * 60 * 60 * 1000
    private val ozadje = Executors.newSingleThreadExecutor { r -> Thread(r, "safeer-os-tv-ikone").also { it.isDaemon = true } }
    private val glavna = Handler(Looper.getMainLooper())
    private val zaklep = Any()
    private var nalaganje = false
    private val obKoncu = ArrayList<() -> Unit>()

    private fun obvesti() {
        val klici = synchronized(zaklep) { obKoncu.toList() }
        glavna.post { klici.forEach { it() } }
    }

    /** Oblikovana, ze shranjena ikona kanala; omrezja se ta klic nikoli ne dotakne. */
    fun ikona(c: Context, id: String): Drawable? {
        val kanal = KANALI.firstOrNull { it.id == id } ?: return null
        val pot = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("pot:$id", "").orEmpty()
        if (pot.isBlank() || !File(pot).isFile) return null
        return try {
            SpletneAplikacije.ikona(c, SpletneAplikacije.Aplikacija(kanal.domaca, kanal.ime, ikona = pot))
        } catch (e: Throwable) {
            Log.w(TAG, "Ikone $id ni bilo mogoce oblikovati: ${e.message}")
            null
        }
    }

    /**
     * V ozadju osvezi ikone z uradnih domacih strani. Uspeh in neuspeh veljata sedem dni, da
     * nedosegljiva stran ob vsakem odprtju TV v zivo ne sprozi novega niza zahtev.
     */
    fun osveziIkone(c: Context, koncano: () -> Unit) {
        synchronized(zaklep) {
            obKoncu += koncano
            if (nalaganje) return
            nalaganje = true
        }
        val app = c.applicationContext
        ozadje.execute {
            try {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val zdaj = System.currentTimeMillis()
                val urejevalnik = prefs.edit()
                for (kanal in KANALI.distinctBy { it.domaca }) {
                    val ids = KANALI.filter { it.domaca == kanal.domaca }.map { it.id }
                    val cas = ids.maxOfOrNull { prefs.getLong("cas:$it", 0L) } ?: 0L
                    if (zdaj - cas < VELJA_MS) continue
                    val podatki = try { SpletneAplikacije.preberiManifest(app, kanal.domaca) }
                    catch (e: Throwable) { Log.w(TAG, "Manifest ${kanal.domaca}: ${e.message}"); null }
                    for (id in ids) {
                        urejevalnik.putLong("cas:$id", zdaj)
                        if (podatki?.ikona?.isNotBlank() == true) urejevalnik.putString("pot:$id", podatki.ikona)
                    }
                    urejevalnik.apply()
                    if (podatki?.ikona?.isNotBlank() == true) obvesti()
                }
            } finally {
                val klici = synchronized(zaklep) {
                    nalaganje = false
                    obKoncu.toList().also { obKoncu.clear() }
                }
                glavna.post { klici.forEach { it() } }
            }
        }
    }

    /** Uradna stran prenosa (kadar izdajatelj javnega toka nima) - odpre se v brskalniku. */
    fun jeStran(sk: Jamendo.Skladba) = sk.id.startsWith("tv:") && sk.zvok.isBlank() && sk.povezava.isNotBlank()

    /** Kanali po drzavah: najprej drzava naprave, nato druge po abecedi (imena drzav v jeziku naprave). */
    fun poDrzavah(): List<Pair<String, List<Jamendo.Skladba>>> {
        val moja = Locale.getDefault().country.uppercase(Locale.ROOT)
        val jezik = Locale.getDefault()
        return KANALI.filter { it.samoZa.isEmpty() || it.samoZa == moja }
            .groupBy { it.drzava }
            .map { (drzava, kanali) ->
                val ime = Locale("", drzava).getDisplayCountry(jezik).ifBlank { drzava }
                ime to kanali.map { k ->
                    val jezikIme = Locale(k.jezik).getDisplayLanguage(jezik)
                    Jamendo.Skladba("tv:${k.id}", k.ime, "$ime · $jezikIme", "", k.tok, k.stran, video = true,
                        mime = if (k.tok.isNotBlank()) MedijskiViri.MIME_HLS else "")
                } to drzava
            }
            .sortedWith(compareBy({ it.second != moja }, { it.first.first }))
            .map { it.first }
    }
}
