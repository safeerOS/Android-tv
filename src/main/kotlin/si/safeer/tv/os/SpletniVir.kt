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
import android.webkit.RenderProcessGoneDetail
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
    private const val TAG = "SafeerSpletniVir"
    private const val KATALOG_VELJA_MS = 15 * 60 * 1000L
    private const val PRAZEN_KATALOG_VELJA_MS = 5 * 60 * 1000L
    private val glavna = Handler(Looper.getMainLooper())
    private val straniSlik = ConcurrentHashMap<String, String>()

    fun jeEnota(s: Jamendo.Skladba) = s.id.startsWith(PREDPONA)

    // ------------------------------------------------------------------ iskanje

    /**
     * Vsi uporabnikovi viri; klice se z delovne niti, najdlje [rok] ms. API-ji (brez WebViewa) tecejo
     * hkrati, spletne aplikacije pa najvec dve naenkrat (televizor z 2 GB ne zmore sestih skritih
     * Chromiumov hkrati - 22. 9. 2026 so vsi potekli). Naslednji vir dobi le preostanek roka.
     */
    fun isciVse(a: Activity, viri: List<MedijskiViri.Vir>, beseda: String, rok: Long = 11_000): List<Pair<MedijskiViri.Vir, Jamendo.Skladba>> {
        val izid = java.util.Collections.synchronizedList(ArrayList<Pair<MedijskiViri.Vir, Jamendo.Skladba>>())
        val konec = System.currentTimeMillis() + rok
        val mesta = java.util.concurrent.Semaphore(2)
        val niti = viri.take(6).map { v -> Thread { try {
            if (v.tip == MedijskiViri.API) isciApi(a, v, beseda).forEach { izid.add(v to it) }
            else if (mesta.tryAcquire(konec - System.currentTimeMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                try {
                    val ostane = konec - System.currentTimeMillis()
                    if (ostane > 2_500) isci(a, v, beseda, ostane).forEach { izid.add(v to it) }
                } finally { mesta.release() }
            }
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
    fun priljubljeno(a: Activity, viri: List<MedijskiViri.Vir>, rok: Long = 15_000): List<Jamendo.Skladba> {
        val po = java.util.concurrent.ConcurrentHashMap<String, List<Jamendo.Skladba>>()
        val konec = System.currentTimeMillis() + rok
        // Android TV z malo pomnilnika: natanko en zacasni WebView naenkrat. Novejsi uporabnikovi
        // viri imajo prednost pri prvem branju; naslednja odprtja so hitra zaradi predpomnilnika.
        viri.filter { it.jeSplet }.takeLast(6).asReversed().forEach { v ->
            if (Thread.currentThread().isInterrupted) return@forEach
            val ostane = konec - System.currentTimeMillis()
            if (ostane < 700) return@forEach
            try {
                val enote = beriStran(a, v, v.naslov, ostane).map { it.copy(izvajalec = v.ime) }
                po[v.naslov] = enote
                android.util.Log.i(TAG, "vir=${v.ime}, enote=${enote.size}")
            } catch (e: Exception) {
                android.util.Log.w(TAG, "vir=${v.ime}, napaka=${e.javaClass.simpleName}")
            }
        }
        val seznami = viri.mapNotNull { po[it.naslov] }
        // Dvojnikov tu namenoma se ne zavrzemo. GlasbaActivity jih zdruzi po IMDb/TMDB oziroma
        // normaliziranem naslovu in letu, pri tem pa potrebuje vse kandidate, da lahko izbere
        // najkakovostnejsi vir. Enako stran istega vira vseeno obdrzimo samo enkrat.
        return (0 until (seznami.maxOfOrNull { it.size } ?: 0)).flatMap { i -> seznami.mapNotNull { it.getOrNull(i) } }
            .distinctBy { it.povezava }
    }

    /** Ocena kandidata za isto vsebino. Vec pomeni bolj neposreden, kakovosten in dobro opisan vir. */
    fun ocenaKandidata(s: Jamendo.Skladba): Int {
        val vse = (s.naslov + " " + s.povezava + " " + s.zvok).lowercase()
        val q = when {
            s.quality > 0 -> s.quality
            Regex("(?:2160p?|4k|uhd)").containsMatchIn(vse) -> 2160
            Regex("(?:1440p?|2k)").containsMatchIn(vse) -> 1440
            Regex("1080p?|full[- ]?hd|fhd").containsMatchIn(vse) -> 1080
            Regex("720p?|\bhd\b").containsMatchIn(vse) -> 720
            Regex("480p?|\bsd\b").containsMatchIn(vse) -> 480
            else -> 0
        }
        return q * 10 +
            (if (s.zvok.isNotBlank()) 900 else 0) +
            (if (s.mime.contains("dash") || s.mime.contains("mpegurl")) 500 else 0) +
            (if (s.povezava.startsWith("https://")) 120 else 0) +
            (if (s.imdbId.isNotBlank() || s.tmdbId.isNotBlank()) 90 else 0) +
            (if (s.mediaType.isNotBlank()) 60 else 0) +
            (if (s.slika.isNotBlank()) 30 else 0)
    }

    fun najboljsiKandidati(v: List<Jamendo.Skladba>): List<Jamendo.Skladba> =
        v.distinctBy { it.povezava }.sortedByDescending(::ocenaKandidata)

    /** Stabilni kljuc iste vsebine med razlicnimi viri; epizod iste serije ne zdruzi med seboj. */
    fun kljucVsebine(s: Jamendo.Skladba): String {
        if (s.imdbId.isNotBlank()) return "imdb:${s.imdbId.lowercase()}"
        if (s.tmdbId.isNotBlank()) return "tmdb:${s.tmdbId}:${vrstaVsebine(s).orEmpty()}"
        val n = s.naslov.lowercase()
            .replace(Regex("\\b(19|20)\\d{2}\\b"), "")
            .replace(Regex("(?i)\\b(4k|uhd|fhd|full.?hd|1080p?|720p?|hd|watch|online)\\b"), "")
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
        val ep = if (s.season > 0 || s.episode > 0) ":s${s.season}e${s.episode}" else ""
        return "$n:${s.year.takeIf { it > 0 } ?: "?"}:${vrstaVsebine(s).orEmpty()}$ep"
    }

    /** Ena skupina na vsebino; znotraj skupine je najkakovostnejsi vir vedno prvi. */
    fun zdruziEnako(v: List<Jamendo.Skladba>): List<List<Jamendo.Skladba>> =
        v.groupBy(::kljucVsebine).values.map(::najboljsiKandidati)

    /** Film, serija ali videospot po standardnih oznakah in naslovu; null = ne vemo. */
    fun vrstaVsebine(s: Jamendo.Skladba): String? {
        when (s.mediaType.lowercase()) {
            "movie", "film" -> return FILM
            "tvseries", "tvseason", "tvepisode", "series" -> return SERIJA
            "musicvideoobject", "musicvideo", "music video" -> return VIDEOSPOT
        }
        val u = s.povezava.lowercase()
        return when {
            Regex("[/_-](series|serie|serija|serije|shows?|tv-?shows?|episodes?|epizod[ae]|seasons?|sezon[ae])([/_?-]|$)|s\\d{1,2}e\\d{1,3}").containsMatchIn(u) -> SERIJA
            Regex("[/_-](movies?|films?|filmi)([/_?-]|$)").containsMatchIn(u) -> FILM
            Regex("[/_-](music[-_ ]?videos?|videospoti?|official[-_ ]?videos?)([/_?-]|$)").containsMatchIn(u) ||
                Regex("\\bofficial (music )?video\\b", RegexOption.IGNORE_CASE).containsMatchIn(s.naslov) -> VIDEOSPOT
            else -> null
        }
    }
    const val FILM = "film"
    const val SERIJA = "serija"
    const val VIDEOSPOT = "videospot"

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

    @Synchronized
    private fun beriStran(a: Activity, vir: MedijskiViri.Vir, url: String, rok: Long): List<Jamendo.Skladba> {
        val nast = a.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)
        val kljuc = "katalog5:" + url
        val casKljuc = "katalog5_cas:" + url
        val shranjeno = nast.getString(kljuc, null)
        val veljavnost = if (shranjeno != null && try { JSONArray(shranjeno).length() == 0 } catch (_: Exception) { false })
            PRAZEN_KATALOG_VELJA_MS else KATALOG_VELJA_MS
        if (shranjeno != null && System.currentTimeMillis() - nast.getLong(casKljuc, 0L) < veljavnost) {
            android.util.Log.i(TAG, "${URL(url).host}: predpomnilnik")
            return pretvori(vir, shranjeno)
        }
        val izid = AtomicReference("[]")
        val gotovo = CountDownLatch(1)
        val wv = AtomicReference<WebView?>()
        val zacetek = System.currentTimeMillis()
        glavna.post {
            if (a.isFinishing) { gotovo.countDown(); return@post }
            val w = nevidni(a) { gotovo.countDown() }; wv.set(w); w.loadUrl(url)
            glavna.postDelayed({ wv.get()?.evaluateJavascript(SOGLASJE_JS, null) }, 1_200)
            var prej = -1
            var mirujeOd = 0L
            fun poglej() {
                val ziv = wv.get() ?: return
                ziv.evaluateJavascript(ZADETKI_JS) { r ->
                    val n = try { JSONArray(r ?: "[]").length() } catch (_: Exception) { 0 }
                    if (n > 0) izid.set(r)
                    val zdaj = System.currentTimeMillis()
                    if (n != prej) mirujeOd = zdaj else if (mirujeOd == 0L) mirujeOd = zdaj
                    // Dinamicna stran je pripravljena po 1,5 s brez spremembe stevila kartic,
                    // vendar nikoli ne cakamo dlje od roka.
                    if ((n > 0 && zdaj - mirujeOd >= 1_500) || (n == 0 && zdaj - mirujeOd >= 4_000) || zdaj - zacetek > rok - 700) {
                        // Prazen vir: ena vrstica v dnevnik, kje je stran obstala (soglasje, prijava, drugacna stran).
                        if (n == 0) ziv.evaluateJavascript("(function(){return location.href+' | '+document.readyState+' | a='+document.querySelectorAll('a[href]').length+' img='+document.images.length;})()") {
                            android.util.Log.i(TAG, "prazno: $it") }
                        gotovo.countDown()
                    }
                    else { prej = n; glavna.postDelayed({ poglej() }, 500) }
                }
            }
            glavna.postDelayed({ poglej() }, 1_500)
        }
        var prekinjeno = false
        try { gotovo.await(rok, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {
            prekinjeno = true
            Thread.currentThread().interrupt()
        }
        glavna.post { wv.getAndSet(null)?.let { odstrani(it) } }
        // Prekinjen pregled (uporabnik je zaprl ali zamenjal zaslon) ne sme zapisati praznega
        // predpomnilnika in nato nadaljevati skozi ostale vire.
        if (prekinjeno) return emptyList()
        val surovo = izid.get()
        val d = try { JSONArray(surovo) } catch (_: Exception) { return emptyList() }
        nast.edit().putString(kljuc, surovo).putLong(casKljuc, System.currentTimeMillis()).apply()
        android.util.Log.i(TAG, "${java.net.URL(url).host}: ${d.length()} zadetkov v ${System.currentTimeMillis() - zacetek} ms")
        return pretvori(vir, surovo)
    }

    private fun pretvori(vir: MedijskiViri.Vir, surovo: String): List<Jamendo.Skladba> {
        val d = try { JSONArray(surovo) } catch (_: Exception) { return emptyList() }
        return (0 until d.length()).mapNotNull { d.optJSONObject(it) }.map { o ->
            val (surovNaslov, izvajalec) = Relevantnost.razdeli(o.optString("t"), "")
            // Odstranimo splosni SEO ovoj strani; uporabnik vidi naslov vsebine, ne spletnega oglasa.
            val naslov = surovNaslov
                .replace(Regex("(?i)^\\s*(watch|stream|play)\\s+"), "")
                .replace(Regex("(?i)\\s+(watch|stream)\\s+online(?:\\s+(?:HD|FHD|4K|UHD))?\\s*$"), "")
                .replace(Regex("(?i)\\s+online(?:\\s+(?:HD|FHD|4K|UHD))?\\s*$"), "")
                .trim().ifBlank { surovNaslov }
            val genres = o.optJSONArray("g")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } } ?: emptyList()
            val slika = o.optString("i")
            if (slika.isNotBlank()) straniSlik[slika] = o.optString("h").ifBlank { vir.naslov }
            Jamendo.Skladba(PREDPONA + o.optString("h"), naslov, izvajalec.ifBlank { vir.ime }, slika, "",
                o.optString("h"), video = o.optBoolean("v"), mediaType = o.optString("mt"), genres = genres,
                year = o.optInt("y"), season = o.optInt("sn"), episode = o.optInt("en"),
                imdbId = o.optString("imdb"), tmdbId = o.optString("tmdb"),
                quality = o.optInt("q"), rating = o.optDouble("r"))
        }
    }

    /**
     * Naslov iskanja v viru (predloga s {searchTerms}): OpenSearch, nato obrazec GET z iskalnim poljem;
     * mobilna poddomena (m.) ju pogosto nima, zato poskusimo se glavno domeno. Najdeno si zapomnimo.
     * Ce ni nicesar, ugibamo /search?q= - a tega si ne zapomnimo (22. 9. 2026: m.youtube.com je tako
     * obvisel na prazni strani).
     */
    private fun predloga(c: Context, naslov: String): String? {
        val kljuc = "iskanje2:$naslov"
        val nast = c.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)
        nast.getString(kljuc, null)?.let { return it }
        val osnova = try { URL(naslov) } catch (_: Exception) { return null }
        val najdena = najdiPredlogo(c, naslov)
            ?: osnova.host.takeIf { it.startsWith("m.") }?.let { najdiPredlogo(c, "${osnova.protocol}://www.${it.removePrefix("m.")}/") }
        if (najdena != null) { nast.edit().putString(kljuc, najdena).apply(); return najdena }
        return "${osnova.protocol}://${osnova.host}/search?q={searchTerms}"
    }

    /** OpenSearch ali iskalni obrazec GET na naslovu; null, ce ju stran nima. */
    private fun najdiPredlogo(c: Context, naslov: String): String? {
        val osnova = try { URL(naslov) } catch (_: Exception) { return null }
        // Namizni UA: mobilne razlicice strani (m.) opisa OpenSearch v glavi pogosto nimajo.
        val namizni = si.safeer.tv.ChromiumEngineView.DESKTOP_USER_AGENT
        val html = beri(c, naslov, null, namizni) ?: ""
        fun atr(oznaka: String, ime: String) =
            Regex("""(?i)\b$ime\s*=\s*["']([^"']*)["']""").find(oznaka)?.groupValues?.get(1)?.replace("&amp;", "&")
        fun polni(n: String) = try { URL(osnova, n).toString() } catch (_: Exception) { null }
        val openSearch = Regex("""(?i)<link[^>]+>""").findAll(html).map { it.value }
            .firstOrNull { atr(it, "rel")?.lowercase() == "search" }?.let { atr(it, "href") }?.let { polni(it) }
            ?.let { beri(c, it, null, namizni) }?.let { xml ->
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
        return obrazec?.takeIf { it.contains("{searchTerms}") }
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
        val tokovi = ConcurrentHashMap<String, String>()
        var izbiraNacrtovana = false
        fun ocenaToka(u: String): Int {
            val l = u.lowercase()
            return when {
                Regex("(?:2160p?|4k|uhd)").containsMatchIn(l) -> 21600
                Regex("(?:1440p?|2k)").containsMatchIn(l) -> 14400
                Regex("1080p?|full[-_ ]?hd|fhd").containsMatchIn(l) -> 10800
                Regex("720p?|\bhd\b").containsMatchIn(l) -> 7200
                Regex("480p?|\bsd\b").containsMatchIn(l) -> 4800
                else -> 0
            } + when {
                l.substringBefore('?').endsWith(".mpd") -> 700
                l.substringBefore('?').endsWith(".m3u8") -> 650
                l.substringBefore('?').endsWith(".mp4") -> 400
                else -> 0
            }
        }
        fun zakljuci(tok: String?, mime: String) {
            if (konec) return
            konec = true
            w.evaluateJavascript(OPIS_JS) { r ->
                val o = try { JSONObject(r ?: "{}") } catch (_: Exception) { JSONObject() }
                odstrani(w)
                if (tok == null) { koncano(null); return@evaluateJavascript }
                glaveDomene[domena(tok)] = mapOf("Referer" to sk.povezava)
                val (naslov, izvajalec) = Relevantnost.razdeli(o.optString("t").ifBlank { sk.naslov }, o.optString("a"))
                val q = when {
                    tok.contains(Regex("(?:2160p?|4k|uhd)", RegexOption.IGNORE_CASE)) -> 2160
                    tok.contains(Regex("(?:1440p?|2k)", RegexOption.IGNORE_CASE)) -> 1440
                    tok.contains(Regex("1080p?|full[-_ ]?hd|fhd", RegexOption.IGNORE_CASE)) -> 1080
                    tok.contains(Regex("720p?", RegexOption.IGNORE_CASE)) -> 720
                    else -> sk.quality
                }
                koncano(sk.copy(naslov = naslov, izvajalec = izvajalec.ifBlank { sk.izvajalec }, slika = o.optString("i").ifBlank { sk.slika },
                    zvok = tok, video = o.optBoolean("v", sk.video), mime = mime, quality = q))
            }
        }
        fun izberiTok() {
            if (konec) return
            val najboljsi = tokovi.keys.maxByOrNull(::ocenaToka)
            zakljuci(najboljsi, najboljsi?.let { tokovi[it] }.orEmpty())
        }
        fun kandidat(u: String, mime: String) {
            tokovi[u] = mime
            if (!izbiraNacrtovana) {
                izbiraNacrtovana = true
                // Stran pogosto najprej zahteva 480p ali oglasni nadomestek, nato adaptivni/HD tok.
                // Kratek zbirni interval omogoci izbiro dejansko najboljsega toka brez vidne zamude.
                glavna.postDelayed({ izberiTok() }, 2_200)
            }
        }
        w.webViewClient = object : Zaprt({ if (!konec) { konec = true; koncano(null) } }) {
            override fun shouldInterceptRequest(view: WebView?, req: WebResourceRequest?): WebResourceResponse? {
                val u = req?.url?.toString() ?: return null
                if (req.method == "GET") vrstaToka(u)?.let { m -> glavna.post { kandidat(u, m) } }
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
                    src.startsWith("http") -> { kandidat(src, vrstaToka(src) ?: ""); glavna.postDelayed({ poglej() }, 500) }
                    src == "blob" && tokovi.isEmpty() -> zakljuci(null, "")
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

    /** Slika spletnega vira z enakim kontekstom kot stran (Referer, piskotki, brskalniski UA). */
    fun bajtiSlike(c: Context, naslov: String): ByteArray? = try {
        val p = URL(naslov).openConnection() as HttpURLConnection
        p.connectTimeout = 8_000; p.readTimeout = 10_000
        if (ua.isBlank()) ua = try { WebSettings.getDefaultUserAgent(c) } catch (_: Exception) { "SafeerOS" }
        p.setRequestProperty("User-Agent", ua)
        straniSlik[naslov]?.let { p.setRequestProperty("Referer", it) }
        try { CookieManager.getInstance().getCookie(naslov) } catch (_: Exception) { null }?.let { p.setRequestProperty("Cookie", it) }
        try { if (p.responseCode in 200..299) p.inputStream.use { it.readBytes() } else null } finally { p.disconnect() }
    } catch (_: Exception) { null }

    // ------------------------------------------------------------------ nevidni WebView

    /** WebView pod vsebino zaslona (prosojen): stran tece kot vidna, uporabnik je ne vidi in ne doseze. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun nevidni(a: Activity, obZrusitvi: (() -> Unit)? = null): WebView = WebView(a).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        // Enaka stran na TV in tablici: telefonski Chrome (televizijski UA dobi drugacne, TV strani).
        settings.userAgentString = si.safeer.tv.ChromiumEngineView.MOBILE_USER_AGENT
        isFocusable = false
        webViewClient = Zaprt(obZrusitvi)
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
    private open class Zaprt(private val obZrusitvi: (() -> Unit)? = null) : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean =
            req?.url?.scheme?.startsWith("http") != true

        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            // Chromium lahko na televizorju z malo pomnilnika zapre izrisovalnik. Dogodek obravnavamo,
            // odstranimo mrtvi pogled in klicatelju omogocimo takoj zakljuciti namesto zrusitve aplikacije.
            try { (view?.parent as? ViewGroup)?.removeView(view); view?.destroy() } catch (_: Exception) { }
            obZrusitvi?.invoke()
            return true
        }
    }

    private fun odstrani(w: WebView) {
        try { w.stopLoading(); w.loadUrl("about:blank"); (w.parent as? ViewGroup)?.removeView(w); w.destroy() } catch (_: Exception) { }
    }

    private fun beri(c: Context, naslov: String, glava: Pair<String, String>? = null, agent: String? = null): String? {
        val p = try { URL(naslov).openConnection() as HttpURLConnection } catch (_: Exception) { return null }
        p.connectTimeout = 5_000; p.readTimeout = 6_000
        if (ua.isBlank()) ua = try { WebSettings.getDefaultUserAgent(c) } catch (_: Exception) { "" }
        p.setRequestProperty("User-Agent", agent ?: ua)
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
        if(u&&/Movie|TVSeries|TVSeason|TVEpisode|VideoObject|MusicVideoObject/i.test(mt)){var ids=idji(x),g=arr(x.genre).map(function(q){return typeof q==='string'?q:(q&&q.name)||'';}).filter(Boolean);
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
        var z=po[u];if(!z){var m=meta(u),pot='';try{pot=new URL(u).pathname;}catch(e){}
          var mt=m.mt||'',vid=/Movie|TVSeries|TVSeason|TVEpisode|VideoObject|MusicVideoObject/i.test(mt)||/(^|\/)(movie|movies|film|films|tv|series|shows?|watch|video|videos|episode|episodes|music-video|music-videos)(\/|$)/i.test(pot);
          z=po[u]={h:u,t:'',n:'',i:'',v:vid,mt:mt,g:m.g||[],y:m.y||0,sn:m.sn||0,en:m.en||0,imdb:m.imdb||'',tmdb:m.tmdb||'',q:0,r:0};red.push(z);}
        var card=a.closest('article,li,[class*=card],[class*=item],[class*=movie],[class*=poster]')||a,ct=(card.innerText||'').replace(/\s+/g,' ');
        var qm=ct.match(/(?:2160p?|4k|uhd|1440p?|2k|1080p?|full\s*hd|fhd|720p?|480p?)/i);if(qm){var q=(''+qm[0]).toLowerCase();z.q=/2160|4k|uhd/.test(q)?2160:/1440|2k/.test(q)?1440:/1080|full|fhd/.test(q)?1080:/720/.test(q)?720:480;}
        if(!z.y){var ym=ct.match(/\b(19\d{2}|20\d{2})\b/);if(ym)z.y=parseInt(ym[1])||0;}
        var rm=ct.match(/(?:★|⭐|rating\s*:?)\s*(10(?:\.0)?|[0-9](?:\.[0-9])?)/i);if(rm)z.r=parseFloat(rm[1])||0;
        var ti=a.querySelector('h1,h2,h3,h4,[class*=title],[id*=title]')||(a.matches('[class*=title],[id*=title]')?a:null);
        var tn=ti?(ti.innerText||ti.getAttribute('title')||'').replace(/\s+/g,' ').trim():'';if(!z.n&&smiselno(tn))z.n=tn.slice(0,140);
        var t=besedilo(a);if(smiselno(t)&&t.length>z.t.length)z.t=t.slice(0,140);
        if(!z.i){var k=a,img=null;for(var j=0;j<3&&k&&!img;j++){img=k.querySelector('img');k=k.parentElement;}
          if(img){var r=img.getBoundingClientRect(),set=img.getAttribute('srcset')||img.getAttribute('data-srcset')||'',
            ss=img.getAttribute('data-src')||img.getAttribute('data-lazy-src')||img.getAttribute('data-original')||img.getAttribute('data-poster')||img.getAttribute('data-image')||'';
            if(!ss&&set)ss=set.split(',').pop().trim().split(/\s+/)[0];if(!ss){var bg=getComputedStyle(img).backgroundImage.match(/url\(["']?([^"')]+)/);ss=(bg&&bg[1])||img.currentSrc||img.src||'';}ss=abs(ss);
            if(r.width>=48&&r.height>=32&&/^https?:/.test(ss)){z.i=ss;z.v=z.v||r.width/r.height>1.3;if(!z.t&&smiselno(img.alt||''))z.t=img.alt;}}}
      }
      var vse=red.filter(function(z){return z.i&&(z.n||z.t);}).map(function(z){if(z.n)z.t=z.n;return z;});
      function skupina(z){var p='';try{p=new URL(z.h).pathname;}catch(e){}
        if(/TVSeries|TVSeason|TVEpisode/i.test(z.mt)||/(^|\/)(tv|series|shows?|episodes?)(\/|$)/i.test(p))return 1;
        if(/MusicVideo/i.test(z.mt)||/(^|\/)(music-video|music-videos|videospoti?)(\/|$)/i.test(p))return 2;
        if(/Movie/i.test(z.mt)||/(^|\/)(movie|movies|film|films)(\/|$)/i.test(p))return 0;return 3;}
      var g=[[],[],[],[]];vse.forEach(function(z){g[skupina(z)].push(z);});var ven=[];
      for(var k=0;ven.length<48;k++){var kaj=false;for(var q=0;q<g.length&&ven.length<48;q++)if(g[q][k]){ven.push(g[q][k]);kaj=true;}if(!kaj)break;}
      return ven;}catch(e){return [];}})()"""

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
