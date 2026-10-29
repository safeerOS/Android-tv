package si.safeer.tv.os

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
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
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    private val delavec = Executors.newSingleThreadExecutor { r -> Thread(r, "safeer-yt-uvoz").apply { isDaemon = true } }
    private val glavna = Handler(Looper.getMainLooper())

    data class PlaylistZapis(val id: String, val naslov: String)

    fun jePrijavljen(ctx: Context): Boolean {
        val prefs = ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KLJUC_PRIJAVLJEN, false)) return true
        val cookies = try { CookieManager.getInstance().getCookie("https://www.youtube.com") } catch (_: Throwable) { null }
        return cookies?.contains("LOGIN_INFO") == true || cookies?.contains("SAPISID") == true || cookies?.contains("SSID") == true
    }

    fun racunIme(ctx: Context): String =
        ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).getString(KLJUC_RACUN_IME, "").orEmpty()

    fun zabeleziPrijavo(ctx: Context, ime: String = "") {
        ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit()
            .putBoolean(KLJUC_PRIJAVLJEN, true)
            .putString(KLJUC_RACUN_IME, ime)
            .apply()
    }

    fun odjavi(ctx: Context) {
        ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit().clear().apply()
        try {
            val cm = CookieManager.getInstance()
            cm.setCookie("https://www.youtube.com", "LOGIN_INFO=; Max-Age=0")
            cm.setCookie("https://www.youtube.com", "SAPISID=; Max-Age=0")
            cm.setCookie("https://www.youtube.com", "SSID=; Max-Age=0")
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
                if (cookies.isBlank()) {
                    glavna.post { onKoncano(false, "Ni veljavne prijave v YouTube") }
                    return@execute
                }
                glavna.post { onNapredek("Pridobivam sezname predvajanja z vašega YouTube računa...") }

                val seznami = poisciUporabnikovePlayliste(cookies)
                if (seznami.isEmpty()) {
                    // Poskusimo uvoziti vsaj Všečke (Liked Videos playlist "LL")
                    val vsec = uvoziPosamezno(ctx, "LL", "Všečki (YouTube)")
                    if (vsec > 0) {
                        zabeleziPrijavo(ctx)
                        glavna.post { onKoncano(true, "Uvožen seznam Všečki ($vsec skladb).") }
                    } else {
                        glavna.post { onKoncano(false, "Na vašem računu ni bilo najdenih seznamov.") }
                    }
                    return@execute
                }

                var uvozenih = 0
                var skupajSkladb = 0
                for (s in seznami) {
                    glavna.post { onNapredek("Uvažam: ${s.naslov}...") }
                    val st = uvoziPosamezno(ctx, s.id, s.naslov)
                    if (st > 0) {
                        uvozenih++
                        skupajSkladb += st
                    }
                }

                zabeleziPrijavo(ctx)
                ctx.getSharedPreferences(NASTAVITVE, Context.MODE_PRIVATE).edit()
                    .putLong(KLJUC_ZADNJI_UVOZ, System.currentTimeMillis())
                    .apply()

                glavna.post {
                    onKoncano(true, "Uspešno uvoženih $uvozenih YouTube seznamov ($skupajSkladb skladb).")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Napaka pri uvozu YouTube seznamov: ${e.message}")
                glavna.post { onKoncano(false, "Napaka pri uvozu: ${e.message ?: "neznana napaka"}") }
            }
        }
    }

    private fun uvoziPosamezno(ctx: Context, playlistId: String, naslov: String): Int {
        val u = try { UvozSeznama.uvozi("https://www.youtube.com/playlist?list=$playlistId") } catch (_: Throwable) { null }
        if (u != null && u.skladbe.isNotEmpty()) {
            val ime = if (naslov.isNotBlank()) naslov else u.ime
            MedijskiViri.shraniSeznam(ctx, ime.take(60), u.skladbe, "YouTube")
            return u.skladbe.size
        }
        return 0
    }

    /**
     * Z InnerTube poisce playliste v uporabnikovi knjiznici (FElibrary ali FEplaylist_aggregator).
     */
    private fun poisciUporabnikovePlayliste(cookies: String): List<PlaylistZapis> {
        val najdeni = LinkedHashMap<String, String>()
        // Vedno dodaj Všečke (Liked Videos)
        najdeni["LL"] = "Všečki (YouTube)"

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
                        ?: "Seznam predvajanja"
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
