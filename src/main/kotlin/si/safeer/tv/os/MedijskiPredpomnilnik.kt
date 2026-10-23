package si.safeer.tv.os

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Trajni predpomnilnik medijskega centra: police (seznami skladb, postaj, videov) in naslovnice.
 * Ob odprtju se zadnji znani pogled pokaze takoj, sveze podatke pa aplikacija dobi v ozadju -
 * uporabnik ne gleda praznega zaslona in nima obcutka, da aplikacija ne deluje.
 *
 * Naloga 87 (+ 57b, 57c): vrstni red branja RAM (klicatelj) -> notranja baza SQLite -> stare datoteke (do 87, samo branje,
 * pocisti jih TTL) -> baza na USB ali naprava v Safeer Linku (izbrano mesto) -> omrezje (klicatelj). Pisanje: notranja baza
 * in izbrano mesto ([UsbPredpomnilnik], [LinkPredpomnilnik]). Vsebina zasebnih dodatkov in kategorij nikoli na drugo napravo.
 */
object MedijskiPredpomnilnik {
    private const val NASLOVNICA_VELJA_MS = 14L * 24 * 60 * 60 * 1000
    /** Naslovnica v mrezi: najvec toliko pik po daljsi stranici (kot dekodiranje v GlasbaActivity.naloziSliko). */
    const val NASLOVNICA_PX = 280
    private const val NASLOVNICA_KAKOVOST = 80

    private fun odtis(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Mapa polic. Ime nosi razlicico vsebine: "-2" od 3. 10. 2026, ko zasebni dodatki niso vec v skupnih policah in
     * mrezah (ZasebniDodatki) - stare police bi jih ob prvem odprtju se enkrat pokazale, zato jih zavrzemo.
     */
    private fun mapaSeznamov(c: Context): File {
        val stara = File(c.cacheDir, "mediji-police")
        if (stara.exists()) try { stara.deleteRecursively() } catch (_: Exception) { }
        return File(c.cacheDir, "mediji-police-2").apply { mkdirs() }
    }
    private fun mapaNaslovnic(c: Context) = File(c.cacheDir, "mediji-naslovnice").apply { mkdirs() }

    // ---------------------------------------------------------------- police

    fun skladbaVJson(s: Jamendo.Skladba): JSONObject = JSONObject()
        .put("id", s.id).put("naslov", s.naslov).put("izvajalec", s.izvajalec).put("slika", s.slika)
        .put("zvok", s.zvok).put("povezava", s.povezava).put("radio", s.radio).put("video", s.video)
        .put("mime", s.mime).put("streznik", s.streznik).put("kanal", s.kanal).put("mediaType", s.mediaType)
        .put("genres", JSONArray(s.genres)).put("year", s.year).put("season", s.season).put("episode", s.episode)
        .put("imdbId", s.imdbId).put("tmdbId", s.tmdbId).put("quality", s.quality).put("rating", s.rating)
        .put("language", s.language)

    fun skladbaIzJson(o: JSONObject): Jamendo.Skladba = Jamendo.Skladba(
        id = o.optString("id"), naslov = o.optString("naslov"), izvajalec = o.optString("izvajalec"),
        slika = o.optString("slika"), zvok = o.optString("zvok"), povezava = o.optString("povezava"),
        radio = o.optBoolean("radio"), video = o.optBoolean("video"), mime = o.optString("mime"),
        streznik = o.optString("streznik"), kanal = o.optString("kanal"), mediaType = o.optString("mediaType"),
        genres = o.optJSONArray("genres")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList(),
        year = o.optInt("year"), season = o.optInt("season"), episode = o.optInt("episode"),
        imdbId = o.optString("imdbId"), tmdbId = o.optString("tmdbId"), quality = o.optInt("quality"),
        rating = o.optDouble("rating", 0.0), language = o.optString("language"),
    )

    /** Shrani police razdelka: seznam (naslov police, video?, skladbe). */
    fun shraniPolice(c: Context, kljuc: String, police: List<Triple<String, Boolean, List<Jamendo.Skladba>>>) {
        try {
            val a = JSONArray()
            police.forEach { (naslov, video, skladbe) ->
                a.put(JSONObject().put("naslov", naslov).put("video", video)
                    .put("skladbe", JSONArray().apply { skladbe.forEach { put(skladbaVJson(it)) } }))
            }
            val zdaj = System.currentTimeMillis()
            val bajti = JSONObject().put("cas", zdaj).put("police", a).toString().toByteArray()
            // 57b: polica z vsebino zasebnega dodatka ali zasebne kategorije nikoli na drugo napravo.
            val zasebno = police.any { p -> p.third.any { Stremio.jeZasebna(it) || ZasebniKanali.jeZaseben(it.id) } }
            if (!zasebno) zapomniJavneSlike(police.flatMap { p -> p.third.map { it.slika } })
            vOzadju { shrani(c, PredpomnilnikPravila.POLICE, odtis(kljuc), bajti, zdaj, zasebno) }
        } catch (_: Exception) {
        }
    }

    /** Zadnje shranjene police razdelka ali null. */
    fun beriPolice(c: Context, kljuc: String): List<Triple<String, Boolean, List<Jamendo.Skladba>>>? = beriPoliceSCasom(c, kljuc)?.second

    /** Zadnje shranjene police razdelka s casom, ko so bile shranjene (System.currentTimeMillis; 0 = neznan), ali null. */
    fun beriPoliceSCasom(c: Context, kljuc: String): Pair<Long, List<Triple<String, Boolean, List<Jamendo.Skladba>>>>? = try {
        val besedilo = beri(c, PredpomnilnikPravila.POLICE, odtis(kljuc))?.second?.let { String(it) }
            ?: File(mapaSeznamov(c), odtis(kljuc) + ".json").takeIf { it.isFile }?.readText()
        if (besedilo == null) null else {
            val o = JSONObject(besedilo)
            val a = o.getJSONArray("police")
            List(a.length()) { i ->
                val p = a.getJSONObject(i)
                val s = p.optJSONArray("skladbe") ?: JSONArray()
                Triple(p.optString("naslov"), p.optBoolean("video"), List(s.length()) { skladbaIzJson(s.getJSONObject(it)) })
            }.takeIf { it.isNotEmpty() }?.let { o.optLong("cas") to it }
        }
    } catch (_: Exception) { null }

    fun pozabiPolice(c: Context, kljuc: String) {
        try { File(mapaSeznamov(c), odtis(kljuc) + ".json").delete() } catch (_: Exception) {}
        val k = PredpomnilnikPravila.kljucBaze(PredpomnilnikPravila.POLICE, odtis(kljuc))
        vOzadju { UsbPredpomnilnik.notranja(c)?.pozabi(k); UsbPredpomnilnik.usb(c)?.pozabi(k) }
    }

    // ---------------------------------------------------------------- katalogi spletnih virov

    /*
     * Prebrani katalog spletnega vira (SpletniVir.beriStran: priljubljeno in vsako iskanje) kot datoteka. Do 0.5.96 je
     * bil v SharedPreferences »safeer_mediji« (»katalog6:<naslov>«): vsako iskanje v vsakem viru je dodalo kljuc, ki ni
     * nikoli izginil, Android pa celo datoteko nastavitev drzi v Java heapu procesa in jo ob vsakem apply() zapise
     * znova (naloga 54). Do 87 ena datoteka na naslov; zdaj vnos v bazi (TTL 24 h, [PredpomnilnikPravila.ttl]).
     */
    private fun mapaKatalogov(c: Context) = File(c.cacheDir, "mediji-katalogi").apply { mkdirs() }

    /** (cas zapisa, surovi JSON) ali null. */
    fun katalog(c: Context, naslov: String): Pair<Long, String>? = try {
        beri(c, PredpomnilnikPravila.KATALOGI, odtis(naslov))?.let { (cas, b) -> cas to String(b) }
            ?: File(mapaKatalogov(c), odtis(naslov) + ".json").takeIf { it.isFile }?.let { it.lastModified() to it.readText() }
    } catch (_: Exception) { null }

    fun shraniKatalog(c: Context, naslov: String, surovo: String) {
        val bajti = surovo.toByteArray()
        vOzadju { shrani(c, PredpomnilnikPravila.KATALOGI, odtis(naslov), bajti, System.currentTimeMillis(), zasebno = false) }
    }

    // ---------------------------------------------------------------- naslovnice

    fun naslovnica(c: Context, naslov: String): ByteArray? = try {
        beri(c, PredpomnilnikPravila.NASLOVNICE, odtis(naslov))?.second ?: run {
            val f = File(mapaNaslovnic(c), odtis(naslov))
            if (f.isFile && System.currentTimeMillis() - f.lastModified() < NASLOVNICA_VELJA_MS) f.readBytes() else null
        }
    } catch (_: Exception) { null }

    /**
     * Naslovnica za mrezo (naloga 56): najvec [stranica] pik po daljsi stranici, natancno (ne le na potenco 2 kot
     * [VarnaSlika.izBajtov]). Vrne isto sliko, ce je ze dovolj majhna; sicer novo, izvirnik pa sprosti.
     */
    fun pomanjsaj(b: android.graphics.Bitmap, stranica: Int = NASLOVNICA_PX): android.graphics.Bitmap {
        val (s, v) = OsPravila.meraNaslovnice(b.width, b.height, stranica) ?: return b
        if (s == b.width && v == b.height) return b
        val m = try { android.graphics.Bitmap.createScaledBitmap(b, s, v, true) } catch (_: Throwable) { return b }
        if (m !== b) b.recycle()
        return m
    }

    /**
     * Naslovnica za pomnilnik (naloga 77): slika brez prosojnosti gre iz ARGB_8888 v RGB_565 - polovica bajtov v heapu in v
     * GPU (z rahlim razprsevanjem, da ni pasov). Prosojna slika (logotip) ostane, kot je: 565 bi prosojnost zamenjal s crnino.
     * Izvirnik sprosti. Klic po [stisniNaslovnico], da disk dobi sliko brez dodatne izgube.
     */
    fun zaPomnilnik(b: android.graphics.Bitmap): android.graphics.Bitmap {
        if (b.hasAlpha() || b.config != android.graphics.Bitmap.Config.ARGB_8888) return b
        return try {
            val k = android.graphics.Bitmap.createBitmap(b.width, b.height, android.graphics.Bitmap.Config.RGB_565)
            android.graphics.Canvas(k).drawBitmap(b, 0f, 0f, android.graphics.Paint(android.graphics.Paint.DITHER_FLAG))
            b.recycle()
            k
        } catch (_: Throwable) { b }
    }

    /**
     * Naslovnica za disk: WebP z izgubo (kakovost [NASLOVNICA_KAKOVOST]) iz ze pomanjsane slike - namesto izvirnih bajtov
     * (JPEG/PNG s streznika, pogosto 100-400 KB). Starejse datoteke (izvirni bajti) ostanejo berljive: BitmapFactory
     * prepozna obliko sam. null = stiskanje ni uspelo (takrat ne shranimo nicesar).
     */
    fun stisniNaslovnico(b: android.graphics.Bitmap): ByteArray? = try {
        @Suppress("DEPRECATION")
        val oblika = if (android.os.Build.VERSION.SDK_INT >= 30) android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
            else android.graphics.Bitmap.CompressFormat.WEBP
        val izhod = java.io.ByteArrayOutputStream(16 * 1024)
        if (b.compress(oblika, NASLOVNICA_KAKOVOST, izhod)) izhod.toByteArray().takeIf { it.isNotEmpty() } else null
    } catch (_: Throwable) { null }

    fun shraniNaslovnico(c: Context, naslov: String, bajti: ByteArray) {
        if (bajti.size > 1_500_000) return
        // 57b: na napravo v Linku samo naslovnice javnih polic (naslovnica zasebnega dodatka se tam nikoli ne pojavi).
        val zasebno = !jeJavnaSlika(naslov)
        vOzadju { shrani(c, PredpomnilnikPravila.NASLOVNICE, odtis(naslov), bajti, System.currentTimeMillis(), zasebno) }
    }

    // ---------------------------------------------------------------- besedila pesmi (naloga 89)

    /** Zapis besedila ([Besedila]) s casom zapisa ali null; notranja baza, nato USB (ne naprava v Linku). Klic iz ozadja. */
    fun besedilo(c: Context, kljuc: String): Pair<Long, String>? = try {
        beri(c, PredpomnilnikPravila.BESEDILA, odtis(kljuc))?.let { (cas, b) -> cas to String(b) }
    } catch (_: Exception) { null }

    /** Shrani zapis besedila (tudi »ni besedila«). Zasebno: besedila ne gredo na drugo napravo ([PredpomnilnikPravila.smeNaLink]). */
    fun shraniBesedilo(c: Context, kljuc: String, zapis: String) {
        val bajti = zapis.toByteArray()
        vOzadju { shrani(c, PredpomnilnikPravila.BESEDILA, odtis(kljuc), bajti, System.currentTimeMillis(), zasebno = true) }
    }

    // ---------------------------------------------------------------- strani kanalov (samo USB)

    /**
     * Naloga 87: stran kataloga kanalov z USB s casom prenosa (ce velja se [veljaMs], kot v pomnilniku). Samo USB - na TV so strani velike
     * in pogoste; po Linku ne gredo. Klic iz ozadja; null = ni ali ni USB.
     */
    fun stranKanalov(c: Context, url: String, veljaMs: Long): Pair<Long, List<Jamendo.Skladba>>? = try {
        UsbPredpomnilnik.usb(c)?.daj(PredpomnilnikPravila.kljucBaze(PredpomnilnikPravila.KANALI, odtis(url)))
            ?.takeIf { System.currentTimeMillis() - it.first in 0 until veljaMs }
            ?.let { (cas, b) -> val a = JSONArray(String(b)); cas to List(a.length()) { skladbaIzJson(a.getJSONObject(it)) } }
    } catch (_: Exception) { null }

    /** Naloga 87: stran kataloga kanalov na USB (ce je v uporabi). Klic iz ozadja. */
    fun shraniStranKanalov(c: Context, url: String, stran: List<Jamendo.Skladba>) {
        try {
            val usb = UsbPredpomnilnik.usb(c) ?: return
            val bajti = JSONArray().apply { stran.forEach { put(skladbaVJson(it)) } }.toString().toByteArray()
            usb.shrani(PredpomnilnikPravila.kljucBaze(PredpomnilnikPravila.KANALI, odtis(url)), PredpomnilnikPravila.KANALI, bajti) { UsbPredpomnilnik.zapisano(c, it) }
        } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------- naloga 87: nivoji

    /** Pisanje in branje baze nikoli na glavni niti: klic z glavne niti gre na to nit. */
    private val ozadje = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "safeer-predpomnilnik-z").apply { isDaemon = true } }

    private fun vOzadju(delo: () -> Unit) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { try { delo() } catch (_: Throwable) { }; return }
        try { ozadje.execute { try { delo() } catch (_: Throwable) { } } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }

    /** Notranja baza -> USB -> naprava v Linku (veljavnost po vrsti). Na glavni niti null (klicatelj bere stare datoteke). */
    private fun beri(c: Context, vrsta: String, odtis: String): Pair<Long, ByteArray>? {
        val k = PredpomnilnikPravila.kljucBaze(vrsta, odtis)
        UsbPredpomnilnik.notranja(c)?.daj(k)?.let { return it }
        UsbPredpomnilnik.usb(c)?.daj(k)?.let { return it }
        return LinkPredpomnilnik.beri(c, vrsta, odtis)?.takeIf { PredpomnilnikPravila.veljavenOdgovor(vrsta, it.first, System.currentTimeMillis()) }
    }

    /** Notranja baza (samo vrednosti do [PredpomnilnikPravila.BLOB_NAJVEC] - TV ostane majhen) in izbrano mesto (USB ali Link). */
    private fun shrani(c: Context, vrsta: String, odtis: String, bajti: ByteArray, ustvarjeno: Long, zasebno: Boolean) {
        val k = PredpomnilnikPravila.kljucBaze(vrsta, odtis)
        if (bajti.size <= PredpomnilnikPravila.BLOB_NAJVEC) UsbPredpomnilnik.notranja(c)?.shrani(k, vrsta, bajti, ustvarjeno) { UsbPredpomnilnik.zapisano(c, it) }
        val usb = UsbPredpomnilnik.usb(c)
        if (usb != null) usb.shrani(k, vrsta, bajti, ustvarjeno) { UsbPredpomnilnik.zapisano(c, it) }
        else LinkPredpomnilnik.shrani(c, vrsta, odtis, bajti, ustvarjeno, zasebno)
    }

    /** Naslovnice javnih polic (LRU, najvec 4000): samo te smejo na napravo v Linku. */
    private val javneSlike = object : LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 4000
    }

    private fun zapomniJavneSlike(slike: List<String>) = synchronized(javneSlike) { slike.filter { it.isNotBlank() }.forEach { javneSlike[it] = true } }

    private fun jeJavnaSlika(naslov: String): Boolean = synchronized(javneSlike) { javneSlike[naslov] == true }
}
