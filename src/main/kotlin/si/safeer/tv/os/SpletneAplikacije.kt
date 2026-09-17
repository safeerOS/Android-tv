package si.safeer.tv.os

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Spletne aplikacije Safeer OS: spletna stran, ki se obnasa kot aplikacija na televizorju.
 *
 * Zamisel je stara in dobra (Firefox OS, danes Capyloon): splet je platforma, aplikacija pa je
 * samo naslov z imenom in ikono. Nasa razlicica doda, kar je takrat manjkalo - ista zascita kot v
 * brskalniku (blokiranje oglasov in sledilcev, nevarne strani, Safeer Scit za ves televizor),
 * navigacijo z daljincem in Safeer Link do drugih naprav.
 *
 * Ime in ikono vzamemo iz manifesta spletne aplikacije (`<link rel="manifest">`, W3C Web App
 * Manifest) - natanko tistega, s katerim se strani predstavljajo kot aplikacije; ce ga ni,
 * poskusimo apple-touch-icon in favicon, sicer narisemo crko. Ikone shranimo na televizor, da
 * domaci zaslon dela tudi brez omrezja.
 */
object SpletneAplikacije {
    private const val TAG = "SafeerOsSpletne"
    private const val PREFS = "safeer_os"
    private const val KLJUC = "spletne_aplikacije"
    private const val NAJVEC = 24

    data class Aplikacija(
        val url: String,
        val ime: String,
        val ikona: String = "",
        val barva: String = "",
        /** Naslov ikone na spletu (za kartico na domacem zaslonu televizorja). */
        val ikonaUrl: String = "",
        /** Kje je uporabnik nazadnje bil v tej aplikaciji (naslov znotraj nje). */
        val zadnji: String = "",
        /** Kdaj je bil tam; po [OKNO_MS] se aplikacija spet odpre na svoji zacetni strani. */
        val zadnjiCas: Long = 0L,
    )

    /** Koliko casa velja »nadaljuj, kjer si koncal«: pol ure je ravno prav za prekinjen ogled. */
    private const val OKNO_MS = 30 * 60 * 1000L

    private val ozadje = Executors.newSingleThreadExecutor { r -> Thread(r, "safeer-os-spletne").also { it.isDaemon = true } }
    private val glavna = Handler(Looper.getMainLooper())

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun seznam(c: Context): List<Aplikacija> {
        val surovo = prefs(c).getString(KLJUC, null) ?: return emptyList()
        return try {
            val polje = JSONArray(surovo)
            (0 until polje.length()).mapNotNull { i ->
                val o = polje.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url"); if (url.isBlank()) null
                else Aplikacija(url, o.optString("ime"), o.optString("ikona"), o.optString("barva"),
                    o.optString("ikona_url"), o.optString("zadnji"), o.optLong("zadnji_cas"))
            }
        } catch (e: Throwable) { Log.w(TAG, "Seznama ni bilo mogoce prebrati: ${e.message}"); emptyList() }
    }

    private fun shrani(c: Context, seznam: List<Aplikacija>) {
        val polje = JSONArray()
        for (a in seznam.take(NAJVEC)) {
            polje.put(JSONObject().put("url", a.url).put("ime", a.ime).put("ikona", a.ikona).put("barva", a.barva)
                .put("ikona_url", a.ikonaUrl).put("zadnji", a.zadnji).put("zadnji_cas", a.zadnjiCas))
        }
        prefs(c).edit().putString(KLJUC, polje.toString()).apply()
    }

    fun jeDodana(c: Context, url: String): Boolean = seznam(c).any { istaStran(it.url, url) }

    /**
     * Kje naj se aplikacija odpre. Ce je uporabnik v zadnji pol ure gledal kaj znotraj nje, se vrne
     * tja (tako delajo aplikacije: nadaljujes, kjer si koncal); pozneje se odpre na svoji zacetni
     * strani, da ne obticis na vceraj odprti podstrani. Nadaljuje samo znotraj istega gostitelja.
     */
    fun nadaljevanje(c: Context, url: String): String {
        val a = seznam(c).firstOrNull { istaStran(it.url, url) } ?: return url
        if (a.zadnji.isBlank() || System.currentTimeMillis() - a.zadnjiCas > OKNO_MS) return url
        return if (gostitelj(a.zadnji) == gostitelj(url)) a.zadnji else url
    }

    /** Zapomni si, kje je uporabnik koncal (ob izhodu iz aplikacije ali ob odhodu z zaslona). */
    fun zapomniMesto(c: Context, url: String, zadnji: String) {
        if (url.isBlank()) return
        val app = c.applicationContext
        val seznam = seznam(app)
        if (seznam.none { istaStran(it.url, url) }) return
        val cist = if (zadnji.isBlank() || gostitelj(zadnji) != gostitelj(url)) "" else zadnji
        shrani(app, seznam.map {
            if (istaStran(it.url, url)) it.copy(zadnji = cist, zadnjiCas = if (cist.isBlank()) 0L else System.currentTimeMillis()) else it
        })
    }

    /**
     * Doda spletno aplikacijo takoj (z zacasnim imenom) in v ozadju poisce njeno pravo ime in
     * ikono; [obKoncu] poklicemo na glavni niti, ko so podatki tu, da se domaci zaslon osvezi.
     */
    fun dodaj(c: Context, url: String, ime: String, obKoncu: (() -> Unit)? = null) {
        val app = c.applicationContext
        if (jeDodana(app, url)) { obKoncu?.invoke(); return }
        shrani(app, seznam(app) + Aplikacija(url, ime))
        obKoncu?.invoke()
        ozadje.execute {
            val podatki = try { preberiManifest(app, url) } catch (e: Throwable) { Log.w(TAG, "Manifest: ${e.message}"); null }
            if (podatki != null) {
                val posodobljene = seznam(app).map { if (istaStran(it.url, url)) it.copy(ime = podatki.ime.ifBlank { it.ime }, ikona = podatki.ikona, barva = podatki.barva, ikonaUrl = podatki.ikonaUrl) else it }
                shrani(app, posodobljene)
                glavna.post { obKoncu?.invoke() }
            }
        }
    }

    fun odstrani(c: Context, url: String) {
        val app = c.applicationContext
        seznam(app).firstOrNull { istaStran(it.url, url) }?.let { a ->
            if (a.ikona.isNotEmpty()) try { File(a.ikona).delete() } catch (_: Throwable) { }
        }
        shrani(app, seznam(app).filterNot { istaStran(it.url, url) })
    }

    /** Ikona aplikacije: shranjena slika ali narisana crka v barvi strani. */
    fun ikona(c: Context, a: Aplikacija): Drawable {
        if (a.ikona.isNotEmpty()) {
            try {
                val b = BitmapFactory.decodeFile(a.ikona)
                if (b != null) return BitmapDrawable(c.resources, b)
            } catch (_: Throwable) { }
        }
        return crkaDrawable(c, a.ime.ifBlank { gostitelj(a.url) }, a.barva)
    }

    private fun crkaDrawable(c: Context, ime: String, barva: String): Drawable {
        val velikost = (c.resources.displayMetrics.density * 56).toInt().coerceAtLeast(56)
        val b = Bitmap.createBitmap(velikost, velikost, Bitmap.Config.ARGB_8888)
        val platno = Canvas(b)
        val ozadjeBarva = try { if (barva.isNotBlank()) Color.parseColor(barva) else Color.parseColor("#152129") } catch (_: Throwable) { Color.parseColor("#152129") }
        val c1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ozadjeBarva }
        platno.drawRoundRect(RectF(0f, 0f, velikost.toFloat(), velikost.toFloat()), velikost * 0.22f, velikost * 0.22f, c1)
        val crka = ime.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val c2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#57D6AD")
            textSize = velikost * 0.5f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        val y = velikost / 2f - (c2.descent() + c2.ascent()) / 2f
        platno.drawText(crka, velikost / 2f, y, c2)
        return BitmapDrawable(c.resources, b)
    }

    fun gostitelj(url: String): String = try { URL(url).host.removePrefix("www.") } catch (_: Throwable) { url }

    private fun istaStran(a: String, b: String): Boolean = a.trimEnd('/') == b.trimEnd('/')

    // ------------------------------------------------------------------ manifest spletne aplikacije

    private class Podatki(val ime: String, val ikona: String, val barva: String, val ikonaUrl: String)

    private fun odjemalec(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()

    /**
     * Prebere `<link rel="manifest">` strani in iz manifesta vzame ime, barvo in najvecjo ikono.
     * Brez manifesta poskusi apple-touch-icon in /favicon.ico. Vse gre prek navadnega HTTPS z
     * naslova same strani; nikamor drugam.
     */
    private fun preberiManifest(c: Context, url: String): Podatki? {
        val k = odjemalec()
        val html = try {
            k.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { o ->
                if (!o.isSuccessful) return null
                o.body?.source()?.let { vir -> vir.request(200_000); vir.buffer.snapshot().utf8().take(200_000) } ?: ""
            }
        } catch (e: Throwable) { Log.w(TAG, "Strani ni bilo mogoce prebrati: ${e.message}"); return null }

        var ime = Regex("<title[^>]*>([^<]{1,80})", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim().orEmpty()
        var barva = Regex("""<meta[^>]+name=["']theme-color["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.trim().orEmpty()
        var ikonaUrl = ""

        val manifestPot = Regex("""<link[^>]+rel=["'][^"']*manifest[^"']*["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""<link[^>]+href=["']([^"']+)["'][^>]+rel=["'][^"']*manifest[^"']*["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
        if (manifestPot != null) {
            val manifestUrl = razresi(url, manifestPot)
            try {
                k.newCall(Request.Builder().url(manifestUrl).header("User-Agent", UA).build()).execute().use { o ->
                    if (o.isSuccessful) {
                        val m = JSONObject(o.body?.string().orEmpty())
                        val mIme = m.optString("short_name").ifBlank { m.optString("name") }
                        if (mIme.isNotBlank()) ime = mIme
                        val mBarva = m.optString("theme_color").ifBlank { m.optString("background_color") }
                        if (mBarva.isNotBlank()) barva = mBarva
                        val ikone = m.optJSONArray("icons")
                        if (ikone != null) {
                            var najboljsa = ""; var najvecja = 0
                            for (i in 0 until ikone.length()) {
                                val o2 = ikone.optJSONObject(i) ?: continue
                                val src = o2.optString("src"); if (src.isBlank()) continue
                                val velikost = o2.optString("sizes").split(" ", "x").mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0
                                // Prevelike ikone so za telefone; na kartici je dovolj ~192 px.
                                if (najboljsa.isEmpty() || (velikost in (najvecja + 1)..512)) { najboljsa = src; najvecja = velikost }
                            }
                            if (najboljsa.isNotEmpty()) ikonaUrl = razresi(manifestUrl, najboljsa)
                        }
                    }
                }
            } catch (e: Throwable) { Log.w(TAG, "Manifesta ni bilo mogoce prebrati: ${e.message}") }
        }
        if (ikonaUrl.isEmpty()) {
            val apple = Regex("""<link[^>]+rel=["'][^"']*apple-touch-icon[^"']*["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
            ikonaUrl = if (apple != null) razresi(url, apple) else razresi(url, "/favicon.ico")
        }
        val pot = prenesiIkono(c, k, ikonaUrl, url)
        ime = ime.replace(Regex("\\s+"), " ").take(40)
        return Podatki(ime, pot, barva, ikonaUrl)
    }

    private fun razresi(osnova: String, pot: String): String = try { URL(URL(osnova), pot).toString() } catch (_: Throwable) { pot }

    private fun prenesiIkono(c: Context, k: OkHttpClient, ikonaUrl: String, stranUrl: String): String {
        if (ikonaUrl.isBlank()) return ""
        return try {
            k.newCall(Request.Builder().url(ikonaUrl).header("User-Agent", UA).build()).execute().use { o ->
                if (!o.isSuccessful) return ""
                val bajti = o.body?.bytes() ?: return ""
                if (bajti.size > 2_000_000) return ""
                val slika = BitmapFactory.decodeByteArray(bajti, 0, bajti.size) ?: return ""
                val stran = if (slika.width > 256) Bitmap.createScaledBitmap(slika, 256, 256 * slika.height / slika.width, true) else slika
                val mapa = File(c.applicationContext.filesDir, "os/ikone").apply { mkdirs() }
                val datoteka = File(mapa, Integer.toHexString(stranUrl.trimEnd('/').hashCode()) + ".png")
                datoteka.outputStream().use { stran.compress(Bitmap.CompressFormat.PNG, 100, it) }
                datoteka.absolutePath
            }
        } catch (e: Throwable) { Log.w(TAG, "Ikone ni bilo mogoce prenesti: ${e.message}"); "" }
    }

    private const val UA = "Mozilla/5.0 (Linux; Android 11; Safeer TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
}
