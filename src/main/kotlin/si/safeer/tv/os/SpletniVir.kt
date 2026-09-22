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
 * Zaklenjene vsebine (DRM) ne odklepamo: kar se ne da ujeti, predvaja aplikacija sama, zvok v ozadju.
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
        val url = predloga.replace("{searchTerms}", URLEncoder.encode(beseda, "UTF-8"))
        val izid = AtomicReference("[]")
        val gotovo = CountDownLatch(1)
        val wv = AtomicReference<WebView?>()
        val zacetek = System.currentTimeMillis()
        glavna.post {
            if (a.isFinishing) { gotovo.countDown(); return@post }
            val w = nevidni(a); wv.set(w); w.loadUrl(url)
            var prej = -1
            fun poglej() {
                val ziv = wv.get() ?: return
                ziv.evaluateJavascript(ZADETKI_JS) { r ->
                    val n = try { JSONArray(r ?: "[]").length() } catch (_: Exception) { 0 }
                    if (n > 0) izid.set(r)
                    // Stran je izrisana, ko se stevilo zadetkov ustali (dinamicne strani rastejo).
                    if ((n >= 4 && n == prej) || System.currentTimeMillis() - zacetek > rok - 700) gotovo.countDown()
                    else { prej = n; glavna.postDelayed({ poglej() }, 700) }
                }
            }
            glavna.postDelayed({ poglej() }, 1_500)
        }
        try { gotovo.await(rok, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
        glavna.post { wv.getAndSet(null)?.let { odstrani(it) } }
        val d = try { JSONArray(izid.get()) } catch (_: Exception) { return emptyList() }
        return (0 until d.length()).mapNotNull { d.optJSONObject(it) }.map { o ->
            val (naslov, izvajalec) = Relevantnost.razdeli(o.optString("t"), "")
            Jamendo.Skladba(PREDPONA + o.optString("h"), naslov, izvajalec.ifBlank { vir.ime }, o.optString("i"), "",
                o.optString("h"), video = o.optBoolean("v"))
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
            override fun onPageFinished(view: WebView?, url: String?) { view?.evaluateJavascript(ZACNI_JS, null) }
        }
        var krog = 0
        fun poglej() {
            if (konec) return
            if (++krog > 20) { zakljuci(null, ""); return }
            w.evaluateJavascript(MEDIJ_JS) { r ->
                val src = r?.trim('"').orEmpty()
                if (src.startsWith("http")) zakljuci(src, vrstaToka(src) ?: "") else glavna.postDelayed({ poglej() }, 750)
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
      function besedilo(a){return (a.getAttribute('title')||a.getAttribute('aria-label')||a.innerText||'').replace(/\s+/g,' ').trim();}
      function smiselno(t){return t.length>=2&&!/^[\d:\s.,]+$/.test(t);}
      var as=document.querySelectorAll('a[href]');
      for(var i=0;i<as.length;i++){
        var a=as[i],u=a.href.split('#')[0];
        if(!/^https?:/.test(u))continue;
        var uh='';try{uh=new URL(u).hostname.replace(/^www\./,'');}catch(e){continue;}
        if(uh.indexOf(h)<0&&h.indexOf(uh)<0)continue;
        if(/[?&](q|query|search|search_query)=|\/(search|login|signin|signup|register|account|settings|help|about|privacy|terms|cookies?|download|premium)(\/|\?|$)/i.test(u))continue;
        var z=po[u];if(!z){z=po[u]={h:u,t:'',i:'',v:false};red.push(z);}
        var t=besedilo(a);if(smiselno(t)&&t.length>z.t.length)z.t=t.slice(0,140);
        if(!z.i){var k=a,img=null;for(var j=0;j<3&&k&&!img;j++){img=k.querySelector('img');k=k.parentElement;}
          if(img){var r=img.getBoundingClientRect(),s=img.currentSrc||img.src||'';
            if(r.width>=48&&r.height>=32&&/^https?:/.test(s)){z.i=s;z.v=r.width/r.height>1.3;if(!z.t&&smiselno(img.alt||''))z.t=img.alt;}}}
      }
      return red.filter(function(z){return z.i&&z.t;}).slice(0,24);}catch(e){return [];}})()"""

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
      for(var i=0;i<m.length;i++){var s=m[i].currentSrc||m[i].src||'';if(/^https?:/.test(s))return s;}return '';})()"""

    /** Kaj igra: MediaSession strani, sicer Open Graph in naslov strani; video ali samo zvok. */
    private const val OPIS_JS = """(function(){try{
      var md=navigator.mediaSession&&navigator.mediaSession.metadata;
      function og(p){var e=document.querySelector('meta[property="og:'+p+'"]');return e?e.content:'';}
      var v=document.querySelector('video');
      return {t:(md&&md.title)||og('title')||document.title||'',a:(md&&md.artist)||'',
        i:(md&&md.artwork&&md.artwork.length&&md.artwork[md.artwork.length-1].src)||og('image')||'',
        v:!!v&&(v.videoWidth>0||!document.querySelector('audio'))};}catch(e){return {};}})()"""
}
