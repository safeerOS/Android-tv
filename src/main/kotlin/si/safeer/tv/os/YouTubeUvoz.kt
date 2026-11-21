package si.safeer.tv.os

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import si.safeer.tv.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Samodejni uvoz YouTube seznamov predvajanja (playlist in všečkov) ob prijavi uporabnika v YouTube.
 * Uporabnik se prijavi prek brskalnika/okna z Google računom; preberemo njegove sezname predvajanja
 * in jih shranimo v MedijskiViri.
 */
object YouTubeUvoz {
    private const val TAG = "SafeerYouTubeUvoz"
    private const val NASTAVITVE = "safeer_youtube_uvoz"
    private const val KLJUC_PRIJAVLJEN = "prijavljen"
    private const val KLJUC_ZADNJI_UVOZ = "zadnji_uvoz"
    private const val KLJUC_RACUN_IME = "racun_ime"
    private const val KLJUC_ODJAVLJEN = "odjavljen"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    private val delavec = Executors.newSingleThreadExecutor { r -> Thread(r, "safeer-yt-uvoz").apply { isDaemon = true } }
    private val glavna = Handler(Looper.getMainLooper())

    private val PRIJAVNI_PISKOTKI = listOf(
        "LOGIN_INFO", "SID", "HSID", "SSID", "APISID", "SAPISID",
        "__Secure-1PSID", "__Secure-3PSID", "__Secure-1PAPISID", "__Secure-3PAPISID"
    )

    data class PlaylistZapis(val id: String, val naslov: String)

    fun jePrijavljen(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)
        // po odjavi ignoriramo morebitne preostale piskotke, dokler se uporabnik znova ne prijavi
        if (prefs.getBoolean(KLJUC_ODJAVLJEN, false)) return false
        if (prefs.getBoolean(KLJUC_PRIJAVLJEN, false)) return true
        val cookies = try { CookieManager.getInstance().getCookie("https://www.youtube.com") } catch (_: Throwable) { null }
        return cookies?.contains("LOGIN_INFO") == true || cookies?.contains("SAPISID") == true || cookies?.contains("SSID") == true
    }

    @Volatile private var tece = false
    @Volatile private var zadnjiPoskus = 0L
    private const val SAMODEJNO_MS = 12 * 3_600_000L

    /**
     * Prijavljen uporabnik (lastnik 20. 11. 2026): seznami in narocnine se uvozijo sami ob odprtju Glasbe, ce uvoza se ni bilo
     * ali je starejsi od 12 ur - ne le po rocnem pritisku na kartico. [naKoncu] samo ob uspehu (osvezitev zaslona).
     */
    fun samodejno(ctx: Context, naKoncu: () -> Unit) {
        val a = ctx.applicationContext
        if (tece || !jePrijavljen(a)) return
        val zadnji = a.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getLong(KLJUC_ZADNJI_UVOZ, 0L)
        val zdaj = System.currentTimeMillis()
        if (zadnji != 0L && zdaj - zadnji in 0 until SAMODEJNO_MS) return
        // Neuspel uvoz se ne ponavlja ob vsakem odprtju Glasbe (omrezje, YouTube): najvec na pol ure.
        if (zdaj - zadnjiPoskus in 0 until 30 * 60_000L) return
        zadnjiPoskus = zdaj
        tece = true
        uvoziVseSezname(a, onKoncano = { uspeh, _ -> tece = false; if (uspeh) naKoncu() })
    }

    fun racunIme(ctx: Context): String =
        ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getString(KLJUC_RACUN_IME, "").orEmpty()

    fun zabeleziPrijavo(ctx: Context, ime: String = "") {
        ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit()
            .putBoolean(KLJUC_PRIJAVLJEN, true)
            .putBoolean(KLJUC_ODJAVLJEN, false)
            .putString(KLJUC_RACUN_IME, ime)
            .apply()
    }

    fun odjavi(ctx: Context) {
        ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().clear()
            .putBoolean(KLJUC_ODJAVLJEN, true).apply()
        YouTubeRacuni.odstraniVse(ctx)
        try {
            val cm = CookieManager.getInstance()
            // piskotki so nastavljeni na domeno .youtube.com/.google.com s Path=/, zato jih brisemo z enakimi atributi
            for (domena in listOf("youtube.com", "google.com")) {
                for (ime in PRIJAVNI_PISKOTKI) {
                    val varen = if (ime.startsWith("__Secure-")) "; Secure" else ""
                    cm.setCookie("https://www.$domena", "$ime=; Domain=.$domena; Path=/; Max-Age=0$varen")
                    cm.setCookie("https://www.$domena", "$ime=; Path=/; Max-Age=0$varen")
                }
            }
            cm.flush()
        } catch (_: Throwable) {}
    }

    /** Racun, ki je trenutno prijavljen v WebView (zabelezen ali osvezen v shrambi); null = nobenega ali ni mogoce shraniti. */
    fun zabeleziTrenutni(ctx: Context): YouTubeRacuni.Racun? {
        val c = try { CookieManager.getInstance().getCookie("https://www.youtube.com").orEmpty() } catch (_: Throwable) { "" }
        if (c.isBlank()) return null
        return YouTubeRacuni.zabelezi(ctx, c, ctx.getString(R.string.yt_racun_ime))
    }

    /**
     * Priprava na prijavo ENEGA VEC racuna: trenutni racun se shrani, nato se piskotki prijave v WebView izbrisejo
     * (shranjeni posnetki ostanejo), da prijavna stran ponudi prazno prijavo. false = trenutnega racuna ni mogoce shraniti
     * (brez Keystore) - brisati ga ne smemo, sicer bi ga izgubili.
     */
    fun pripraviNovRacun(ctx: Context): Boolean {
        val cm = CookieManager.getInstance()
        val trenutni = cm.getCookie("https://www.youtube.com").orEmpty()
        if (trenutni.isNotBlank() && YouTubeRacuniPravila.idRacuna(trenutni) != null && zabeleziTrenutni(ctx) == null) return false
        pocistiPrijavoWebView()
        return true
    }

    /** Izbrise piskotke prijave youtube.com in google.com iz skupne zbirke WebView (shranjeni posnetki racunov ostanejo). */
    private fun pocistiPrijavoWebView() {
        val cm = CookieManager.getInstance()
        try {
            for (domena in listOf("youtube.com", "google.com")) {
                val imena = (YouTubeRacuniPravila.imenaPiskotkov(cm.getCookie("https://www.$domena").orEmpty()) +
                    YouTubeRacuniPravila.imenaPiskotkov(cm.getCookie("https://accounts.$domena").orEmpty())).distinct()
                for (ime in imena) {
                    val varen = if (ime.startsWith("__Secure-") || ime.startsWith("__Host-")) "; Secure" else ""
                    cm.setCookie("https://www.$domena", "$ime=; Domain=.$domena; Path=/; Max-Age=0$varen")
                    cm.setCookie("https://www.$domena", "$ime=; Path=/; Max-Age=0$varen")
                    cm.setCookie("https://accounts.$domena", "$ime=; Domain=.$domena; Path=/; Max-Age=0$varen")
                    cm.setCookie("https://accounts.$domena", "$ime=; Path=/; Max-Age=0$varen")
                }
            }
            cm.flush()
        } catch (_: Throwable) { }
    }

    /** Odstrani en racun (shranjeni posnetek); uvozeni seznami ostanejo med viri. */
    fun odstraniRacun(ctx: Context, id: String) {
        // Ce je to racun, prijavljen v WebView, ga izbrisemo tudi tam - sicer bi ga naslednji uvoz znova shranil.
        val zivi = try { CookieManager.getInstance().getCookie("https://www.youtube.com").orEmpty() } catch (_: Throwable) { "" }
        if (YouTubeRacuniPravila.idRacuna(zivi) == id) pocistiPrijavoWebView()
        YouTubeRacuni.odstrani(ctx, id)
        if (YouTubeRacuni.seznam(ctx).isEmpty()) odjavi(ctx)
    }

    /**
     * V ozadju poisce vse uporabnikove YouTube playliste in jih shrani v MedijskiViri.
     */
    fun uvoziVseSezname(
        ctx: Context,
        onZacetek: () -> Unit = {},
        onNapredek: (String) -> Unit = {},
        onKoncano: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        glavna.post { onZacetek() }
        delavec.execute {
            try {
                // Lastnik 21. 11. 2026: vec racunov. Trenutni WebView racun se osvezi v shrambi, nato se uvozijo vsi shranjeni racuni.
                val zivi = if (jePrijavljen(ctx)) CookieManager.getInstance().getCookie("https://www.youtube.com").orEmpty() else ""
                val ziviId = YouTubeRacuniPravila.idRacuna(zivi)
                if (ziviId != null) YouTubeRacuni.zabelezi(ctx, zivi, ctx.getString(R.string.yt_racun_ime))
                val racuni = YouTubeRacuni.seznam(ctx)
                // Brez posnetkov (ni Keystore): vsaj trenutni racun, kot doslej.
                val vir = if (racuni.isEmpty() && ziviId != null) listOf(Triple(YouTubeRacuni.Racun(ziviId, ""), zivi, "")) else {
                    val prvo = YouTubeRacuniPravila.privzetoIme(1, ctx.getString(R.string.yt_racun_ime))
                    racuni.map { r ->
                        Triple(r, if (r.id == ziviId) zivi else YouTubeRacuni.piskotki(ctx, r.id).orEmpty(),
                            YouTubeRacuniPravila.predponaSeznama(r.ime, prvo))
                    }
                }
                if (vir.isEmpty() || vir.all { it.second.isBlank() }) {
                    val s = ctx.getString(R.string.yt_uvoz_ni_prijave)
                    glavna.post { onKoncano(false, s) }
                    return@execute
                }
                val sNapredek = ctx.getString(R.string.yt_uvoz_pridobivam)
                glavna.post { onNapredek(sNapredek) }

                var uvozenih = 0
                var skupajSkladb = 0
                val potekli = ArrayList<String>()
                for ((racun, cookies, predpona) in vir) {
                    if (cookies.isBlank()) { potekli.add(racun.ime); continue }
                    val (n, st) = uvoziRacun(ctx, cookies, predpona, onNapredek)
                    if (n == 0) potekli.add(racun.ime.ifBlank { "YouTube" })
                    uvozenih += n; skupajSkladb += st
                }

                if (uvozenih == 0) {
                    // nic uvozenega ni uspeh; prijave in casa uvoza ne zabelezimo
                    val s = ctx.getString(R.string.yt_uvoz_ni_seznamov)
                    glavna.post { onKoncano(false, s) }
                    return@execute
                }
                zabeleziPrijavo(ctx, racunIme(ctx))
                ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit()
                    .putLong(KLJUC_ZADNJI_UVOZ, System.currentTimeMillis())
                    .apply()

                var sUspeh = ctx.getString(R.string.yt_uvoz_uspeh, uvozenih, skupajSkladb)
                if (potekli.isNotEmpty()) sUspeh += "\n" + ctx.getString(R.string.yt_racun_potekel, potekli.joinToString(", "))
                glavna.post { onKoncano(true, sUspeh) }
            } catch (e: Throwable) {
                Log.w(TAG, "Napaka pri uvozu YouTube seznamov: ${e.message}")
                // surovega besedila izjeme ne kazemo uporabniku; podrobnosti so v dnevniku
                val s = ctx.getString(R.string.yt_uvoz_napaka)
                glavna.post { onKoncano(false, s) }
            }
        }
    }

    /** Seznami, vsecki in narocnine ENEGA racuna; vrne (stevilo uvozenih seznamov, skupaj skladb). Imena seznamov dobijo [predpono]. */
    private fun uvoziRacun(ctx: Context, cookies: String, predpona: String, onNapredek: (String) -> Unit): Pair<Int, Int> {
        val imeVseckov = ctx.getString(R.string.yt_uvoz_vsecki)
        // vsecki (LL) so vedno zadnji; uspeh se meri po dejansko uvozenih seznamih, ne po stevilu najdenih
        val seznami = poisciUporabnikovePlayliste(cookies) + PlaylistZapis("LL", imeVseckov)
        var uvozenih = 0
        var skupajSkladb = 0
        // Lastnik 20. 11. 2026: tudi kanali, na katere je uporabnik narocen (najnovejsi videi kanalov).
        val sNarocnine = ctx.getString(R.string.yt_uvoz_narocnine)
        glavna.post { onNapredek(predpona + sNarocnine) }
        val nar = try { uvoziNarocnine(ctx, predpona, cookies) } catch (e: Throwable) { Log.w(TAG, "Narocnine: ${e.javaClass.simpleName}"); 0 }
        if (nar > 0) { uvozenih++; skupajSkladb += nar }
        for (s in seznami) {
            val sUvazam = ctx.getString(R.string.yt_uvoz_uvazam, predpona + s.naslov)
            glavna.post { onNapredek(sUvazam) }
            val st = uvoziPosamezno(ctx, s.id, predpona + s.naslov, cookies)
            if (st > 0) { uvozenih++; skupajSkladb += st }
        }
        return uvozenih to skupajSkladb
    }

    /** Narocnine: kanale prebere s prijavo (FEchannels), videe vsakega iz javnega RSS; vrne stevilo videov v seznamu. */
    private fun uvoziNarocnine(ctx: Context, predpona: String, cookies: String): Int {
        val json = zahtevaInnerTube("FEchannels", cookies) ?: return 0
        val kanali = LinkedHashSet<String>()
        fun hodi(o: Any?, g: Int) {
            if (g > 40 || o == null || kanali.size >= YouTubeNarocnine.NAJVEC_KANALOV) return
            when (o) {
                is JSONArray -> for (i in 0 until o.length()) hodi(o.opt(i), g + 1)
                is JSONObject -> {
                    val k = o.keys()
                    while (k.hasNext()) {
                        val kljuc = k.next()
                        val v = o.opt(kljuc)
                        if (v is String && (kljuc == "browseId" || kljuc == "channelId") && YouTubeNarocnine.jeIdKanala(v)) kanali.add(v)
                        else hodi(v, g + 1)
                    }
                }
            }
        }
        hodi(json, 0)
        Log.i(TAG, "narocnine: kanalov ${kanali.size}")
        // Lastnik 21. 11. 2026: glasba, ostalo in kratki posnetki so loceni seznami; glasba je zapisana kanal za kanalom
        // in igra kot vrsta zvoka (konec skladbe -> naslednja skladba istega kanala), drugo ostane video.
        val glasba = ArrayList<Jamendo.Skladba>()
        val ostalo = ArrayList<Jamendo.Skladba>()
        val kratki = ArrayList<Jamendo.Skladba>()
        for (id in kanali) {
            var ime = ""
            val z = videiKanala(zahtevaInnerTube(id, cookies, TAB_VIDEI, RAZLICICA_KANALA)?.also { ime = imeKanala(it) }, false, VIDEOV_V_KANALU)
            val k = videiKanala(zahtevaInnerTube(id, cookies, TAB_SHORTS, RAZLICICA_KANALA), true, KRATKIH_V_KANALU)
            val vsi = if (z.isNotEmpty()) z + k else rssVidei(id)
            for (v in vsi) {
                val vrsta = YoutubeKanalVrsta.vrsta(v.naslov, v.kanal.ifBlank { ime }, v.trajanjeS, v.shorts)
                val sk = UvozSeznama.youtubeSkladba(v.id, v.naslov, v.kanal.ifBlank { ime }, video = vrsta != YoutubeKanalVrsta.Vrsta.GLASBA)
                when (vrsta) {
                    YoutubeKanalVrsta.Vrsta.GLASBA -> glasba.add(sk)
                    YoutubeKanalVrsta.Vrsta.OSTALO -> ostalo.add(sk)
                    YoutubeKanalVrsta.Vrsta.KRATKI -> kratki.add(sk)
                }
            }
        }
        var skupaj = 0
        for ((imeSeznama, skladbe) in listOf(
            R.string.yt_uvoz_narocnine_glasba to glasba, R.string.yt_uvoz_narocnine_ostalo to ostalo, R.string.yt_uvoz_narocnine_kratki to kratki)) {
            if (skladbe.isEmpty()) continue
            MedijskiViri.shraniSeznam(ctx, (predpona + ctx.getString(imeSeznama)).take(60), skladbe, "YouTube")
            skupaj += skladbe.size
        }
        return skupaj
    }

    private const val TAB_VIDEI = "EgZ2aWRlb3PyBgQKAjoA"
    private const val TAB_SHORTS = "EgZzaG9ydHPyBgUKA5oBAA%3D%3D"
    /** Zavihka kanala vrneta trajanja samo v novejsi obliki odgovora (lockupViewModel). */
    private const val RAZLICICA_KANALA = "2.20251120.01.00"
    private const val VIDEOV_V_KANALU = 12
    private const val KRATKIH_V_KANALU = 4

    private class KanalVideo(val id: String, val naslov: String, val kanal: String, val trajanjeS: Int?, val shorts: Boolean)

    private fun imeKanala(json: JSONObject): String =
        json.optJSONObject("metadata")?.optJSONObject("channelMetadataRenderer")?.optString("title").orEmpty().take(100)

    /** Rezerva, ce zavihek ne odgovori: zadnji videi iz javnega RSS (brez trajanja - samo izrazite glasbene znacilnosti). */
    private fun rssVidei(idKanala: String): List<KanalVideo> {
        val url = YouTubeNarocnine.naslovFeeda(idKanala) ?: return emptyList()
        val xml = try {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000; readTimeout = 8_000; setRequestProperty("User-Agent", UA)
            }
            if (c.responseCode in 200..299) BufferedReader(InputStreamReader(c.inputStream, "UTF-8")).use { it.readText().take(400_000) } else ""
        } catch (_: Throwable) { "" }
        return YouTubeNarocnine.videiIzFeeda(xml).map { KanalVideo(it.id, it.naslov, it.kanal, null, false) }
    }

    private val RX_TRAJANJE = Regex("""^\d{1,3}:\d{2}(:\d{2})?$""")

    /**
     * Videi iz odgovora zavihka kanala (Videos ali Shorts): novi zapis (lockupViewModel, shortsLockupViewModel) in star
     * (videoRenderer). Oblika preverjena 21. 11. 2026 na pravem odgovoru; kar ni prepoznano, se preskoci.
     */
    private fun videiKanala(json: JSONObject?, shorts: Boolean, najvec: Int): List<KanalVideo> {
        if (json == null) return emptyList()
        val izhod = ArrayList<KanalVideo>()
        val videni = HashSet<String>()
        fun trajanje(o: Any?, g: Int): Int? {
            if (g > 30 || o == null) return null
            when (o) {
                is JSONArray -> for (i in 0 until o.length()) trajanje(o.opt(i), g + 1)?.let { return it }
                is JSONObject -> {
                    val t = o.optString("text")
                    if (RX_TRAJANJE.matches(t)) return YoutubeKanalVrsta.trajanje(t)
                    val k = o.keys()
                    while (k.hasNext()) trajanje(o.opt(k.next()), g + 1)?.let { return it }
                }
            }
            return null
        }
        fun dodaj(id: String, naslov: String, tr: Int?, kratek: Boolean): Boolean {
            if (!YouTubeNarocnine.jeIdVidea(id) || naslov.isBlank() || !videni.add(id)) return false
            izhod.add(KanalVideo(id, naslov.take(200), "", tr, kratek))
            return true
        }
        fun hodi(o: Any?, g: Int) {
            if (g > 60 || o == null || izhod.size >= najvec) return
            when (o) {
                is JSONArray -> for (i in 0 until o.length()) hodi(o.opt(i), g + 1)
                is JSONObject -> {
                    val entiteta = o.optString("entityId")
                    val ok = when {
                        o.optString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO" ->
                            dodaj(o.optString("contentId"), o.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                                ?.optJSONObject("title")?.optString("content").orEmpty(), trajanje(o.opt("contentImage"), 0), shorts)
                        entiteta.startsWith("shorts-shelf-item-") ->
                            dodaj(entiteta.removePrefix("shorts-shelf-item-"), o.optJSONObject("overlayMetadata")?.optJSONObject("primaryText")
                                ?.optString("content").orEmpty(), null, true)
                        o.has("videoId") && o.has("lengthText") && o.optJSONObject("title") != null ->
                            dodaj(o.optString("videoId"), o.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                                ?: o.optJSONObject("title")?.optString("simpleText").orEmpty(),
                                YoutubeKanalVrsta.trajanje(o.optJSONObject("lengthText")?.optString("simpleText")), shorts)
                        else -> false
                    }
                    if (ok) return
                    val k = o.keys()
                    while (k.hasNext()) hodi(o.opt(k.next()), g + 1)
                }
            }
        }
        hodi(json, 0)
        return izhod
    }

    private fun uvoziPosamezno(ctx: Context, playlistId: String, naslov: String, cookies: String): Int {
        // zasebni seznami in vsecki so dostopni samo s piskotki, zato najprej InnerTube s prijavo
        val zasebne = try { skladbePrijavljeno(playlistId, cookies) } catch (_: Throwable) { emptyList() }
        if (zasebne.isNotEmpty()) {
            MedijskiViri.shraniSeznam(ctx, naslov.ifBlank { "YouTube" }.take(60), zasebne, "YouTube")
            return zasebne.size
        }
        // javni seznam: obstojec anonimen uvoz kot rezerva
        val u = try { UvozSeznama.uvozi("https://www.youtube.com/playlist?list=$playlistId") } catch (_: Throwable) { null }
        if (u != null && u.skladbe.isNotEmpty()) {
            val ime = if (naslov.isNotBlank()) naslov else u.ime
            MedijskiViri.shraniSeznam(ctx, ime.take(60), u.skladbe, "YouTube")
            return u.skladbe.size
        }
        return 0
    }

    /** Prva stran skladb seznama (VL<id>) s prijavo; nadaljevanja (nad ~100) niso podprta. */
    private fun skladbePrijavljeno(playlistId: String, cookies: String): List<Jamendo.Skladba> {
        val json = zahtevaInnerTube("VL$playlistId", cookies) ?: return emptyList()
        val izhod = ArrayList<Jamendo.Skladba>()
        val videni = HashSet<String>()
        fun hodi(o: Any?, g: Int) {
            if (g > 40 || o == null) return
            when (o) {
                is JSONArray -> for (i in 0 until o.length()) hodi(o.opt(i), g + 1)
                is JSONObject -> {
                    val r = o.optJSONObject("playlistVideoRenderer")
                    if (r != null) {
                        val id = r.optString("videoId")
                        val naslov = r.optJSONObject("title")?.let { t ->
                            t.optString("simpleText").takeIf { it.isNotBlank() }
                                ?: t.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                        }.orEmpty()
                        val kanal = r.optJSONObject("shortBylineText")?.optJSONArray("runs")
                            ?.optJSONObject(0)?.optString("text").orEmpty()
                        if (id.isNotBlank() && videni.add(id)) {
                            izhod.add(UvozSeznama.youtubeSkladba(id, naslov, kanal, video = true))
                        }
                        return
                    }
                    val k = o.keys()
                    while (k.hasNext()) hodi(o.opt(k.next()), g + 1)
                }
            }
        }
        hodi(json, 0)
        return izhod
    }

    /** InnerTube za zasebne podatke poleg piskotkov zahteva glavo SAPISIDHASH. */
    private fun sapisidHash(cookies: String): String? {
        val sapisid = cookies.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("SAPISID=") || it.startsWith("__Secure-3PAPISID=") }
            ?.substringAfter('=')?.takeIf { it.isNotBlank() } ?: return null
        val cas = System.currentTimeMillis() / 1000
        val izvor = "https://www.youtube.com"
        val bajti = MessageDigest.getInstance("SHA-1").digest("$cas $sapisid $izvor".toByteArray())
        return "SAPISIDHASH ${cas}_" + bajti.joinToString("") { "%02x".format(it) }
    }

    /**
     * Z InnerTube poisce playliste v uporabnikovi knjiznici (FElibrary ali FEplaylist_aggregator).
     */
    private fun poisciUporabnikovePlayliste(cookies: String): List<PlaylistZapis> {
        val najdeni = LinkedHashMap<String, String>()
        for (browseId in listOf("FEplaylist_aggregator", "FElibrary")) {
            val json = zahtevaInnerTube(browseId, cookies) ?: continue
            izlusciPlayliste(json, najdeni)
        }
        return najdeni.map { (id, naslov) -> PlaylistZapis(id, naslov) }
    }

    private fun zahtevaInnerTube(browseId: String, cookies: String, params: String? = null, razlicica: String = "2.20250101.00.00"): JSONObject? {
        return try {
            val url = URL("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 12_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Cookie", cookies)
                setRequestProperty("X-YouTube-Client-Name", "1")
                setRequestProperty("X-YouTube-Client-Version", razlicica)
                setRequestProperty("Origin", "https://www.youtube.com")
                sapisidHash(cookies)?.let { setRequestProperty("Authorization", it) }
            }
            val telo = JSONObject().put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB").put("clientVersion", razlicica).put("hl", "sl"))).put("browseId", browseId)
            if (params != null) telo.put("params", params)
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(telo.toString()) }
            if (conn.responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { r ->
                    JSONObject(r.readText())
                }
            } else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun izlusciPlayliste(obj: Any?, najdeni: LinkedHashMap<String, String>, globina: Int = 0) {
        if (globina > 35 || obj == null) return
        when (obj) {
            is JSONArray -> for (i in 0 until obj.length()) izlusciPlayliste(obj.opt(i), najdeni, globina + 1)
            is JSONObject -> {
                // Prepoznamo lockupViewModel ali playlistRenderer ali gridPlaylistRenderer
                val r = obj.optJSONObject("gridPlaylistRenderer") ?: obj.optJSONObject("playlistRenderer")
                if (r != null) {
                    val id = r.optString("playlistId")
                    val titleObj = r.optJSONObject("title")
                    val naslov = titleObj?.optString("simpleText")?.takeIf { it.isNotBlank() }
                        ?: titleObj?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                        ?: "YouTube"
                    if (id.isNotBlank() && id != "LL") najdeni[id] = naslov
                }
                // Nov zapis (lockupViewModel): contentId = id seznama, naslov v lockupMetadataViewModel.
                val lm = obj.optJSONObject("lockupViewModel")
                if (lm != null && lm.optString("contentType").contains("PLAYLIST")) {
                    val id = lm.optString("contentId")
                    val nasl = lm.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                        ?.optJSONObject("title")?.optString("content").orEmpty().ifBlank { "YouTube" }
                    if (id.isNotBlank() && id != "LL") najdeni[id] = nasl
                }
                // Preišči ključe v globino
                val it = obj.keys()
                while (it.hasNext()) {
                    izlusciPlayliste(obj.opt(it.next()), najdeni, globina + 1)
                }
            }
        }
    }
}
