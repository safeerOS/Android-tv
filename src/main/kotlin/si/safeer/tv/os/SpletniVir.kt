// Media3: ResolvingDataSource in DefaultHttpDataSource sta oznacena kot @UnstableApi.
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package si.safeer.tv.os

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Uporabnikova spletna aplikacija kot vir Safeer Media: v njej poiscemo in predvajamo v nasem
 * predvajalniku, okno aplikacije se ne odpre. Brez receptov za posamezne strani:
 *  - iskanje najdemo po standardu OpenSearch (`<link rel="search">`) ali po iskalnem obrazcu strani;
 *  - zadetke preberemo z izrisane strani (povezave s sliko in naslovom);
 *  - tok ujamemo, ko ga stran sama zahteva (HLS, DASH ali datoteka) - v nevidnem WebViewu, z
 *    uporabnikovo ze odobreno storitvijo (njegovi piskotki, njegova prijava).
 * Zascitene vsebine ne odklepamo: kar se ne da ujeti, predvaja stran sama v skritem pogledu, upravlja
 * pa jo nas predvajalnik ([SpletniIgralec]).
 */
object SpletniVir {
    private const val PREDPONA = "splet:"
    private const val NASTAVITVE = "safeer_mediji"
    private val glavna = Handler(Looper.getMainLooper())

    fun jeEnota(s: Jamendo.Skladba) = s.id.startsWith(PREDPONA)

    // ------------------------------------------------------------------ iskanje

    /** Vsi spletni viri hkrati; klice se z delovne niti, najdlje [rok] ms. */
    fun isciVse(a: Activity, viri: List<MedijskiViri.Vir>, beseda: String, rok: Long = 7_500): List<Pair<MedijskiViri.Vir, Jamendo.Skladba>> {
        val izid = java.util.Collections.synchronizedList(ArrayList<Pair<MedijskiViri.Vir, Jamendo.Skladba>>())
        val niti = viri.take(6).map { v -> Thread { try {
            (if (v.tip == MedijskiViri.API) isciApi(a, v, beseda) else isci(a, v, beseda, rok)).forEach { izid.add(v to it) }
        } catch (_: Exception) { } }.apply { start() } }
        try { niti.forEach { it.join(rok + 500) } } catch (_: InterruptedException) { }
        return ArrayList(izid)
    }

    private fun isci(a: Activity, vir: MedijskiViri.Vir, beseda: String, rok: Long): List<Jamendo.Skladba> {
        val predloga = predloga(a, vir.naslov) ?: return emptyList()
        return beriStran(a, vir, predloga.replace("{searchTerms}", URLEncoder.encode(beseda, "UTF-8")), rok)
    }

    /**
     * Priljubljeno v uporabnikovih virih (gumb Video/Glasba brez iskanja): zacetna stran vsake spletne
     * aplikacije pokaze, kar je pri njej priljubljeno. Zdruzimo izmenicno, brez dvojnikov po naslovu;
     * vsaka enota ohrani oznako vira. Klice se z delovne niti.
     */
    fun priljubljeno(a: Activity, viri: List<MedijskiViri.Vir>, rok: Long = 8_000): List<Jamendo.Skladba> {
        val po = java.util.concurrent.ConcurrentHashMap<String, List<Jamendo.Skladba>>()
        // TV z 2 GB RAM-a ne sme hkrati odpreti 6 skritih Chromium/WebView instanc. Vire obdelamo
        // po dva: odziv ostane vzporeden, vrh porabe pomnilnika in zatikanje UI pa sta bistveno nizja.
        viri.filter { it.jeSplet }.take(6).chunked(2).forEach { skupina ->
            val niti = skupina.map { v -> Thread {
                try { po[v.naslov] = beriStran(a, v, v.naslov, rok).map { it.copy(izvajalec = v.ime) } } catch (_: Exception) { }
            }.apply { start() } }
            try { niti.forEach { it.join(rok + 500) } } catch (_: InterruptedException) { return@forEach }
        }
        val seznami = viri.mapNotNull { po[it.naslov] }
        val videni = HashSet<String>()
        return (0 until (seznami.maxOfOrNull { it.size } ?: 0)).flatMap { i -> seznami.mapNotNull { it.getOrNull(i) } }
            .filter { videni.add(it.naslov.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")) }
    }

    /** Film ali serija po splosnih oznakah v naslovu enote (brez receptov za strani); null = ne vemo. */
    fun vrstaVsebine(s: Jamendo.Skladba): String? {
        when (s.mediaType.lowercase()) {
            "movie", "film" -> return FILM
            "tvseries", "tvseason", "tvepisode", "series" -> return SERIJA
        }
        val u = s.povezava.lowercase()
        return when {
            Regex("[/_-](series|serie|serija|serije|shows?|tv-?shows?|episodes?|epizod[ae]|seasons?|sezon[ae])([/_?-]|$)|s\\d{1,2}e\\d{1,3}").containsMatchIn(u) -> SERIJA
            Regex("[/_-](movies?|films?|filmi)([/_?-]|$)").containsMatchIn(u) -> FILM
            else -> null
        }
    }
    const val FILM = "film"
    const val SERIJA = "serija"

    /** Groba, ponudniku neodvisna zvrst iz naslova/URL-ja. Vir ostane avtoriteta; ne ugibamo, ce ni signala. */
    fun zvrstVsebine(s: Jamendo.Skladba): String? {
        val strukturirana = s.genres.asSequence().map { it.lowercase() }.mapNotNull { g ->
            when {
                "comedy" in g || "komed" in g -> "Komedija"
                "horror" in g || "grozljiv" in g -> "Grozljivke"
                "drama" in g -> "Drama"
                "action" in g || "akcij" in g -> "Akcija"
                "fantasy" in g || "sci-fi" in g || "science fiction" in g || "fantast" in g -> "Fantastika"
                "crime" in g || "thriller" in g || "kriminal" in g -> "Kriminalke"
                "documentary" in g || "dokument" in g -> "Dokumentarci"
                "animation" in g || "anime" in g || "animacij" in g -> "Animacija"
                "family" in g || "druz" in g || "children" in g || "kids" in g -> "Druzinski"
                "romance" in g || "romantik" in g -> "Romantika"
                else -> null
            }
        }.firstOrNull()
        if (strukturirana != null) return strukturirana
        val t = (s.naslov + " " + s.povezava).lowercase()
        val zvrsti = listOf(
            "Komedija" to Regex("comedy|komedij|sitcom"),
            "Grozljivke" to Regex("horror|grozljiv|slasher"),
            "Drama" to Regex("drama|dramatic"),
            "Akcija" to Regex("action|akcij|martial"),
            "Fantastika" to Regex("fantasy|fantastik|sci[- ]?fi|science[- ]?fiction"),
            "Kriminalke" to Regex("crime|kriminal|detective|thriller"),
            "Dokumentarci" to Regex("documentary|dokumentar"),
            "Animacija" to Regex("animation|animated|anime|animacij"),
            "Druzinski" to Regex("family|druzinsk|kids|children"),
            "Romantika" to Regex("romance|romantic|romantik")
        )
        return zvrsti.firstOrNull { it.second.containsMatchIn(t) }?.first
    }

    private fun beriStran(a: Activity, vir: MedijskiViri.Vir, url: String, rok: Long): List<Jamendo.Skladba> {
        val izid = AtomicReference("[]")
        val gotovo = CountDownLatch(1)
        val wv = AtomicReference<WebView?>()
        val zacetek = System.currentTimeMillis()
        glavna.post {
            if (a.isFinishing) { gotovo.countDown(); return@post }
            val w = nevidni(a); wv.set(w); w.loadUrl(url)
            glavna.postDelayed({ wv.get()?.evaluateJavascript(SOGLASJE_JS, null) }, 1_200)
            var prej = -1
            fun poglej() {
                val ziv = wv.get() ?: return
                ziv.evaluateJavascript(ZADETKI_JS) { r ->
                    val n = try { JSONArray(r ?: "[]").length() } catch (_: Exception) { 0 }
                    if (n > 0) izid.set(r)
                    // Stran je izrisana, ko se stevilo zadetkov ustali (dinamicne strani rastejo).
                    if ((n >= 4 && n == prej) || System.currentTimeMillis() - zacetek > rok - 700) {
                        gotovo.countDown()
                    }
                    else { prej = n; glavna.postDelayed({ poglej() }, 700) }
                }
            }
            glavna.postDelayed({ poglej() }, 1_500)
        }
        try { gotovo.await(rok, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
        glavna.post { wv.getAndSet(null)?.let { odstrani(it) } }
        val d = try { JSONArray(izid.get()) } catch (_: Exception) { return emptyList() }
        android.util.Log.i("SafeerSplet", "${java.net.URL(url).host}: ${d.length()} zadetkov v ${System.currentTimeMillis() - zacetek} ms")
        return (0 until d.length()).mapNotNull { d.optJSONObject(it) }.map { o ->
            val (naslov, izvajalec) = Relevantnost.razdeli(o.optString("t"), "")
            val genres = o.optJSONArray("g")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } } ?: emptyList()
            Jamendo.Skladba(PREDPONA + o.optString("h"), naslov, izvajalec.ifBlank { vir.ime }, o.optString("i"), "",
                o.optString("h"), video = o.optBoolean("v"), mediaType = o.optString("mt"), genres = genres,
                year = o.optInt("y"), season = o.optInt("sn"), episode = o.optInt("en"),
                imdbId = o.optString("imdb"), tmdbId = o.optString("tmdb"))
        }
    }

    /**
     * Naslov iskanja v viru (predloga s {searchTerms}), enkrat poiskan in zapomnjen: OpenSearch,
     * nato obrazec GET z iskalnim poljem, nato obicajna pot /search?q=.
     */
    private fun predloga(c: Context, naslov: String): String? {
        val kljuc = "iskanje:$naslov"
        val nast = c.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)
        nast.getString(kljuc, null)?.let { return it }
        val osnova = try { URL(naslov) } catch (_: Exception) { return null }
        val html = beri(c, naslov) ?: ""
        fun atr(oznaka: String, ime: String) =
            Regex("""(?i)\b$ime\s*=\s*["']([^"']*)["']""").find(oznaka)?.groupValues?.get(1)?.replace("&amp;", "&")
        fun polni(n: String) = try { URL(osnova, n).toString() } catch (_: Exception) { null }
        val openSearch = Regex("""(?i)<link[^>]+>""").findAll(html).map { it.value }
            .firstOrNull { atr(it, "rel")?.lowercase() == "search" }?.let { atr(it, "href") }?.let { polni(it) }
            ?.let { beri(c, it) }?.let { xml ->
                Regex("""(?i)<Url[^>]+>""").findAll(xml).map { it.value }
                    .firstOrNull { atr(it, "type")?.lowercase() == "text/html" }?.let { atr(it, "template") }
            }?.replace(Regex("""\{[^}]*\?\}"""), "")
        val obrazec = openSearch ?: Regex("""(?is)<form[^>]*>.*?</form>""").findAll(html).map { it.value }.firstNotNullOfOrNull { f ->
            val glava = Regex("""(?i)<form[^>]*>""").find(f)?.value ?: return@firstNotNullOfOrNull null
            if ((atr(glava, "method") ?: "get").lowercase() != "get") return@firstNotNullOfOrNull null
            val polje = Regex("""(?i)<input[^>]+>""").findAll(f).map { it.value }.firstOrNull { i ->
                atr(i, "type")?.lowercase() == "search" || atr(i, "name")?.lowercase() in setOf("q", "query", "search", "search_query", "s", "term", "keyword")
            } ?: return@firstNotNullOfOrNull null
            val ime = atr(polje, "name") ?: return@firstNotNullOfOrNull null
            val cilj = polni(atr(glava, "action").orEmpty().ifBlank { osnova.path }) ?: return@firstNotNullOfOrNull null
            cilj.substringBefore('#') + (if (cilj.contains('?')) "&" else "?") + ime + "={searchTerms}"
        }
        val izid = (obrazec ?: "${osnova.protocol}://${osnova.host}/search?q={searchTerms}").takeIf { it.contains("{searchTerms}") } ?: return null
        nast.edit().putString(kljuc, izid).apply()
        return izid
    }

    // ------------------------------------------------------------------ uporabnikov API

    /**
     * Uporabnikov API: naslov iskanja z {q} (ali {searchTerms}), po zelji "| Glava: vrednost" za kljuc
     * (npr. "Authorization: Bearer ..."). Odgovor JSON preberemo splosno: vsak predmet z naslovom in
     * naslovom toka (ali strani) je zadetek. Tok s streznika API dobi isto glavo.
     */
    private fun isciApi(c: Context, vir: MedijskiViri.Vir, beseda: String): List<Jamendo.Skladba> {
        val (predloga, glava) = razdeliApi(vir.naslov)
        val q = URLEncoder.encode(beseda, "UTF-8")
        val telo = beri(c, predloga.replace("{q}", q).replace("{searchTerms}", q), glava) ?: return emptyList()
        val izid = ArrayList<Jamendo.Skladba>()
        fun hodi(x: Any?, globina: Int) {
            if (globina > 7 || izid.size >= 30) return
            when (x) {
                is JSONArray -> for (i in 0 until x.length()) hodi(x.opt(i), globina + 1)
                is JSONObject -> apiZadetek(x, vir, glava)?.let { izid.add(it) } ?: x.keys().forEach { hodi(x.opt(it), globina + 1) }
            }
        }
        hodi(try { org.json.JSONTokener(telo).nextValue() } catch (_: Exception) { return emptyList() }, 0)
        return izid
    }

    /** "naslov | Glava: vrednost" -> naslov in glava (ali null). */
    private fun razdeliApi(vnos: String): Pair<String, Pair<String, String>?> {
        val deli = vnos.split('|', limit = 2)
        val g = deli.getOrNull(1)?.split(':', limit = 2)?.takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
        return deli[0].trim() to g
    }

    private fun apiZadetek(o: JSONObject, vir: MedijskiViri.Vir, glava: Pair<String, String>?): Jamendo.Skladba? {
        val polja = HashMap<String, Any?>()
        o.keys().forEach { polja[it.lowercase().replace("_", "")] = o.opt(it) }
        fun beri(vararg k: String): String = k.firstNotNullOfOrNull { ime ->
            when (val v = polja[ime]) { is String -> v.takeIf { it.isNotBlank() }; is JSONObject -> v.optString("url").ifBlank { v.optString("name") }.takeIf { it.isNotBlank() }
                is JSONArray -> (v.opt(v.length() - 1) as? JSONObject)?.optString("url")?.takeIf { it.isNotBlank() } ?: v.optString(0).takeIf { it.isNotBlank() }
                else -> null }
        }.orEmpty()
        val naslov = beri("title", "name", "trackname", "track", "songname")
        if (naslov.isBlank()) return null
        val izvajalec = beri("artist", "artistname", "author", "creator", "channel", "uploader", "album")
        val slika = beri("image", "thumbnail", "thumbnails", "artwork", "artworkurl", "artworkurl100", "artworkurl60", "cover", "coverart", "covermedium", "coverbig", "poster", "picture", "favicon").takeIf { it.startsWith("http") }.orEmpty()
        // Polje, ki je po imenu tok (stream, audio, preview ...), velja tudi brez koncnice (radijski tokovi);
        // splosno polje (url, src, file) le, ce je naslov ocitno medij.
        val izrecen = beri("streamurl", "stream", "urlresolved", "audio", "audiourl", "mp3", "mediaurl", "downloadurl", "previewurl", "preview").takeIf { it.startsWith("http") }
        val tok = izrecen ?: beri("file", "fileurl", "src", "url").takeIf { it.startsWith("http") && vrstaToka(it) != null }
        val stran = beri("link", "permalink", "permalinkurl", "page", "weburl", "htmlurl", "trackviewurl", "homepage", "url").takeIf { it.startsWith("http") }
        val video = beri("type", "kind", "mediatype").lowercase().contains("video")
        val m = tok?.let { vrstaToka(it) ?: if (izrecen != null) "" else null }
        return when {
            tok != null && m != null -> {
                if (glava != null) glaveDomene[domena(tok)] = mapOf(glava)
                Jamendo.Skladba("api:$tok", naslov, izvajalec.ifBlank { vir.ime }, slika, tok, stran ?: tok, video = video, mime = m)
            }
            stran != null -> Jamendo.Skladba(PREDPONA + stran, naslov, izvajalec.ifBlank { vir.ime }, slika, "", stran, video = video)
            else -> null
        }
    }

    // ------------------------------------------------------------------ predvajanje

    /**
     * Enoto odpremo v nevidnem WebViewu in pocakamo, da stran zahteva svoj tok; tega predvaja nas
     * predvajalnik (stran pred tem utisamo in zapremo). [koncano] dobi skladbo ali null (na glavni niti).
     */
    fun razresi(a: Activity, sk: Jamendo.Skladba, koncano: (Jamendo.Skladba?) -> Unit) {
        val w = nevidni(a)
        var konec = false
        fun zakljuci(tok: String?, mime: String) {
            if (konec) return
            konec = true
            w.evaluateJavascript(OPIS_JS) { r ->
                val o = try { JSONObject(r ?: "{}") } catch (_: Exception) { JSONObject() }
                odstrani(w)
                if (tok == null) { koncano(null); return@evaluateJavascript }
                glaveDomene[domena(tok)] = mapOf("Referer" to sk.povezava)
                val (naslov, izvajalec) = Relevantnost.razdeli(o.optString("t").ifBlank { sk.naslov }, o.optString("a"))
                koncano(sk.copy(naslov = naslov, izvajalec = izvajalec.ifBlank { sk.izvajalec }, slika = o.optString("i").ifBlank { sk.slika },
                    zvok = tok, video = o.optBoolean("v", sk.video), mime = mime))
            }
        }
        w.webViewClient = object : Zaprt() {
            override fun shouldInterceptRequest(view: WebView?, req: WebResourceRequest?): WebResourceResponse? {
                val u = req?.url?.toString() ?: return null
                if (req.method == "GET") vrstaToka(u)?.let { m -> glavna.post { zakljuci(u, m) } }
                return null
            }
            override fun onPageFinished(view: WebView?, url: String?) { view?.evaluateJavascript(SOGLASJE_JS, null); view?.evaluateJavascript(ZACNI_JS, null) }
        }
        var krog = 0
        fun poglej() {
            if (konec) return
            if (++krog > 20) { zakljuci(null, ""); return }
            w.evaluateJavascript(MEDIJ_JS) { r ->
                val src = r?.trim('"').orEmpty()
                when {
                    src.startsWith("http") -> zakljuci(src, vrstaToka(src) ?: "")
                    src == "blob" -> zakljuci(null, "")
                    else -> glavna.postDelayed({ poglej() }, 750)
                }
            }
        }
        w.loadUrl(sk.povezava)
        glavna.postDelayed({ poglej() }, 1_500)
    }

    /** Naslov je tok, ki ga zna nas predvajalnik: HLS, DASH ali zvocna/video datoteka (ne oglas, ne segment). */
    private fun vrstaToka(u: String): String? {
        val l = u.lowercase()
        if (Regex("""doubleclick|googlesyndication|imasdk|adservice|/ads?/|[./]ads\.""").containsMatchIn(l)) return null
        val pot = l.substringBefore('?')
        return when {
            pot.endsWith(".m3u8") -> MedijskiViri.MIME_HLS
            pot.endsWith(".mpd") -> "application/dash+xml"
            listOf(".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".flac", ".wav", ".mp4", ".webm").any { pot.endsWith(it) } &&
                !l.contains("range=") -> ""
            else -> null
        }
    }

    // ------------------------------------------------------------------ omrezje predvajalnika

    /** Glave za tok po njegovi domeni: Referer strani ali kljuc uporabnikovega API-ja (+ piskotki seje). */
    private val glaveDomene = ConcurrentHashMap<String, Map<String, String>>()
    @Volatile private var ua = ""

    private fun domena(url: String) = try { URL(url).host.split('.').takeLast(2).joinToString(".") } catch (_: Exception) { "" }

    /** Vir podatkov za nas predvajalnik: tokovom iz spletnih aplikacij doda Referer, piskotke in UA brskalnika. */
    fun virPodatkov(c: Context): DataSource.Factory {
        if (ua.isBlank()) ua = try { WebSettings.getDefaultUserAgent(c) } catch (_: Exception) { "" }
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).apply { if (ua.isNotBlank()) setUserAgent(ua) }
        return ResolvingDataSource.Factory(DefaultDataSource.Factory(c, http)) { spec ->
            val glave = HashMap(glaveDomene[domena(spec.uri.toString())] ?: return@Factory spec)
            try { CookieManager.getInstance().getCookie(spec.uri.toString()) } catch (_: Exception) { null }?.let { glave["Cookie"] = it }
            spec.withAdditionalHeaders(glave)
        }
    }

    // ------------------------------------------------------------------ nevidni WebView

    /** WebView pod vsebino zaslona (prosojen): stran tece kot vidna, uporabnik je ne vidi in ne doseze. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun nevidni(a: Activity): WebView = WebView(a).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        isFocusable = false
        webViewClient = Zaprt()
        alpha = 0f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        ua = settings.userAgentString
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        // Brskalnik ob odhodu v ozadje ustavi casovnike JS za VSE poglede procesa (pauseTimers);
        // brez njih se strani ne izrisejo. Ko delamo, jih zazenemo.
        resumeTimers()
        (a.window.decorView as ViewGroup).addView(this, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Vse ostane v nevidnem pogledu: sheme, ki niso http (intent:, aplikacije), se ne odprejo nikjer. */
    private open class Zaprt : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean =
            req?.url?.scheme?.startsWith("http") != true
    }

    private fun odstrani(w: WebView) {
        try { w.stopLoading(); w.loadUrl("about:blank"); (w.parent as? ViewGroup)?.removeView(w); w.destroy() } catch (_: Exception) { }
    }

    private fun beri(c: Context, naslov: String, glava: Pair<String, String>? = null): String? {
        val p = try { URL(naslov).openConnection() as HttpURLConnection } catch (_: Exception) { return null }
        p.connectTimeout = 5_000; p.readTimeout = 6_000
        if (ua.isBlank()) ua = try { WebSettings.getDefaultUserAgent(c) } catch (_: Exception) { "" }
        p.setRequestProperty("User-Agent", ua)
        glava?.let { p.setRequestProperty(it.first, it.second) }
        return try { if (p.responseCode in 200..299) p.inputStream.bufferedReader().use { it.readText().take(1_000_000) } else null }
        catch (_: Exception) { null } finally { p.disconnect() }
    }

    // ------------------------------------------------------------------ skripte (splosne, za vsako stran)

    /** Zadetki izrisane strani: povezave na istem mestu z opazno sliko in besedilom, brez navigacije. */
    private const val ZADETKI_JS = """(function(){try{
      var po={},red=[],h=location.hostname.replace(/^www\./,'');
      function arr(x){return x==null?[]:(Array.isArray(x)?x:[x]);}
      function abs(u){try{return new URL(u,location.href).href.split('#')[0];}catch(e){return '';}}
      function tip(x){var t=x&&x['@type'];return Array.isArray(t)?(t[0]||''):(t||'');}
      function idji(x){var r={imdb:'',tmdb:''},v=[]; arr(x&&x.sameAs).forEach(function(z){if(typeof z==='string')v.push(z);});
        arr(x&&x.identifier).forEach(function(z){if(typeof z==='string')v.push(z);else if(z){var p=(z.propertyID||z.name||'')+':'+(z.value||'');v.push(p);}});
        v.forEach(function(z){var m=(''+z).match(/(?:imdb\.com\/title\/|IMDb:?)\s*(tt\d+)/i);if(m)r.imdb=m[1];
          m=(''+z).match(/(?:themoviedb\.org\/(?:movie|tv)\/|TMDB:?)\s*(\d+)/i);if(m)r.tmdb=m[1];});return r;}
      var strukturirani={};
      function dodajLD(x){if(!x||typeof x!=='object')return;if(Array.isArray(x)){x.forEach(dodajLD);return;}if(x['@graph'])dodajLD(x['@graph']);
        var mt=tip(x),u=abs(x.url||(x.mainEntityOfPage&&x.mainEntityOfPage['@id'])||x['@id']||'');
        if(u&&/Movie|TVSeries|TVSeason|TVEpisode|VideoObject/i.test(mt)){var ids=idji(x),g=arr(x.genre).map(function(q){return typeof q==='string'?q:(q&&q.name)||'';}).filter(Boolean);
          var d=x.datePublished||'',yy=parseInt((''+d).slice(0,4))||0,sn=parseInt(x.seasonNumber||(x.partOfSeason&&x.partOfSeason.seasonNumber))||0,en=parseInt(x.episodeNumber)||0;
          strukturirani[u]={mt:mt,g:g,y:yy,sn:sn,en:en,imdb:ids.imdb,tmdb:ids.tmdb};}
        ['itemListElement','mainEntity','subjectOf','video','episode','partOfSeries'].forEach(function(k){if(x[k]&&typeof x[k]==='object')dodajLD(x[k]);});}
      document.querySelectorAll('script[type="application/ld+json"]').forEach(function(e){try{dodajLD(JSON.parse(e.textContent));}catch(q){}});
      function meta(u){if(strukturirani[u])return strukturirani[u];var best=null;Object.keys(strukturirani).some(function(k){if(k.split('?')[0]===u.split('?')[0]){best=strukturirani[k];return true;}});return best||{};}
      function besedilo(a){return (a.getAttribute('title')||a.getAttribute('aria-label')||a.innerText||'').replace(/\s+/g,' ').trim();}
      function smiselno(t){return t.length>=2&&!/^[\d:\s.,]+$/.test(t);}
      var as=document.querySelectorAll('a[href]');
      for(var i=0;i<as.length;i++){
        var a=as[i],u=a.href.split('#')[0];
        if(!/^https?:/.test(u))continue;
        var uh='';try{uh=new URL(u).hostname.replace(/^www\./,'');}catch(e){continue;}
        if(uh.indexOf(h)<0&&h.indexOf(uh)<0)continue;
        if(/[?&](q|query|search|search_query)=|\/(search|login|signin|signup|register|account|settings|help|about|privacy|terms|cookies?|download|premium)(\/|\?|$)/i.test(u))continue;
        var z=po[u];if(!z){var m=meta(u);z=po[u]={h:u,t:'',n:'',i:'',v:false,mt:m.mt||'',g:m.g||[],y:m.y||0,sn:m.sn||0,en:m.en||0,imdb:m.imdb||'',tmdb:m.tmdb||''};red.push(z);}
        var ti=a.querySelector('h1,h2,h3,h4,[class*=title],[id*=title]')||(a.matches('[class*=title],[id*=title]')?a:null);
        var tn=ti?(ti.innerText||ti.getAttribute('title')||'').replace(/\s+/g,' ').trim():'';if(!z.n&&smiselno(tn))z.n=tn.slice(0,140);
        var t=besedilo(a);if(smiselno(t)&&t.length>z.t.length)z.t=t.slice(0,140);
        if(!z.i){var k=a,img=null;for(var j=0;j<3&&k&&!img;j++){img=k.querySelector('img');k=k.parentElement;}
          if(img){var r=img.getBoundingClientRect(),ss=img.currentSrc||img.src||'';
            if(r.width>=48&&r.height>=32&&/^https?:/.test(ss)){z.i=ss;z.v=r.width/r.height>1.3;if(!z.t&&smiselno(img.alt||''))z.t=img.alt;}}}
      }
      return red.filter(function(z){return z.i&&(z.n||z.t);}).map(function(z){if(z.n)z.t=z.n;return z;}).slice(0,24);}catch(e){return [];}})()"""

    /**
     * Okno za piskotke/soglasje zapre predvajanje: izberemo najbolj zasebno moznost (zavrni, nadaljuj
     * brez sprejemanja) - po besedilu gumba, za vsako stran enako. Tudi v okvirjih istega izvora.
     */
    internal const val SOGLASJE_JS = """(function(){try{var vz=/continue without accepting|reject all|reject|decline|refuse|only necessary|necessary only|use necessary|zavrni|nadaljuj brez|samo nujn|ablehnen|refuser|rifiuta|rechazar/i;
      function isci(d){var b=[].slice.call(d.querySelectorAll('button,[role=button],a'));for(var i=0;i<b.length;i++){var t=(b[i].innerText||b[i].getAttribute('aria-label')||'').trim();
        if(t.length<40&&vz.test(t)){b[i].click();return true;}}var f=d.querySelectorAll('iframe');for(var j=0;j<f.length;j++){try{if(isci(f[j].contentDocument))return true;}catch(e){}}return false;}
      var n=0;(function k(){if(!isci(document)&&++n<8)setTimeout(k,1000);})();}catch(e){}})()"""

    /** Utisaj stran in jo prosi za predvajanje: najprej medij sam, sicer najvecji gumb »play«. */
    private const val ZACNI_JS = """(function(){try{
      var P=HTMLMediaElement.prototype;if(!P._safeerTiho){P._safeerTiho=1;var p=P.play;P.play=function(){this.muted=true;return p.apply(this,arguments);};}
      function igraj(){var m=document.querySelectorAll('video,audio');for(var i=0;i<m.length;i++){m[i].muted=true;try{m[i].play();}catch(e){}}
        if(m.length&&!m[0].paused)return;
        var g=[].slice.call(document.querySelectorAll('button,[role=button],a')).filter(function(b){
          var n=(b.getAttribute('aria-label')||b.getAttribute('title')||b.className||'')+'';
          var r=b.getBoundingClientRect();return /\bplay\b|predvajaj/i.test(n)&&r.width>0&&r.height>0;});
        g.sort(function(x,y){var a=x.getBoundingClientRect(),b=y.getBoundingClientRect();return b.width*b.height-a.width*a.height;});
        if(g[0])g[0].click();}
      igraj();setTimeout(igraj,2500);setTimeout(igraj,5000);}catch(e){}})()"""

    /** Neposreden naslov medija, ce ga stran ima (blob: ni naslov - tega ujamemo med zahtevami). */
    private const val MEDIJ_JS = """(function(){var m=document.querySelectorAll('video,audio');
      for(var i=0;i<m.length;i++){var s=m[i].currentSrc||m[i].src||'';if(/^https?:/.test(s))return s;
        if(/^blob:/.test(s)&&m[i].readyState>=2)return 'blob';}return '';})()"""

    /** Kaj igra: MediaSession strani, sicer Open Graph in naslov strani; video ali samo zvok. */
    private const val OPIS_JS = """(function(){try{
      var md=navigator.mediaSession&&navigator.mediaSession.metadata;
      function og(p){var e=document.querySelector('meta[property="og:'+p+'"]');return e?e.content:'';}
      var v=document.querySelector('video');
      return {t:(md&&md.title)||og('title')||document.title||'',a:(md&&md.artist)||'',
        i:(md&&md.artwork&&md.artwork.length&&md.artwork[md.artwork.length-1].src)||og('image')||'',
        v:!!v&&(v.videoWidth>0||!document.querySelector('audio'))};}catch(e){return {};}})()"""
}
