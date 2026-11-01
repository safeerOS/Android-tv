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
                val cookies = CookieManager.getInstance().getCookie("https://www.youtube.com").orEmpty()
                if (cookies.isBlank() || !jePrijavljen(ctx)) {
                    val s = ctx.getString(R.string.yt_uvoz_ni_prijave)
                    glavna.post { onKoncano(false, s) }
                    return@execute
                }
                val sNapredek = ctx.getString(R.string.yt_uvoz_pridobivam)
                glavna.post { onNapredek(sNapredek) }

                val imeVseckov = ctx.getString(R.string.yt_uvoz_vsecki)
                // vsecki (LL) so vedno zadnji; uspeh se meri po dejansko uvozenih seznamih, ne po stevilu najdenih
                val seznami = poisciUporabnikovePlayliste(cookies) + PlaylistZapis("LL", imeVseckov)

                var uvozenih = 0
                var skupajSkladb = 0
                for (s in seznami) {
                    val sUvazam = ctx.getString(R.string.yt_uvoz_uvazam, s.naslov)
                    glavna.post { onNapredek(sUvazam) }
                    val st = uvoziPosamezno(ctx, s.id, s.naslov, cookies)
                    if (st > 0) {
                        uvozenih++
                        skupajSkladb += st
                    }
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

                val sUspeh = ctx.getString(R.string.yt_uvoz_uspeh, uvozenih, skupajSkladb)
                glavna.post { onKoncano(true, sUspeh) }
            } catch (e: Throwable) {
                Log.w(TAG, "Napaka pri uvozu YouTube seznamov: ${e.message}")
                // surovega besedila izjeme ne kazemo uporabniku; podrobnosti so v dnevniku
                val s = ctx.getString(R.string.yt_uvoz_napaka)
                glavna.post { onKoncano(false, s) }
            }
        }
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

    private fun zahtevaInnerTube(browseId: String, cookies: String): JSONObject? {
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
                setRequestProperty("X-YouTube-Client-Version", "2.20250101.00.00")
                setRequestProperty("Origin", "https://www.youtube.com")
                sapisidHash(cookies)?.let { setRequestProperty("Authorization", it) }
            }
            val telo = JSONObject().put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB").put("clientVersion", "2.20250101.00.00").put("hl", "sl"))).put("browseId", browseId)
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
                // Preišči ključe v globino
                val it = obj.keys()
                while (it.hasNext()) {
                    izlusciPlayliste(obj.opt(it.next()), najdeni, globina + 1)
                }
            }
        }
    }
}
