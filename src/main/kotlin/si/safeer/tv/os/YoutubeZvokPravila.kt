package si.safeer.tv.os

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * Zvok posnetka YouTube za zvocnik v omrezju (lastnik, 25. 10. 2026 16:50: »Neposredno od YouTuba«) - cista pravila brez
 * Androida (preizkus: tests/YoutubeZvokPravilaTest.kt; tedenska sonda, ki pove, ce YouTube spremeni odjemalca:
 * tests/YoutubeZvokSonda.kt).
 *
 * Logika odjemalca InnerTube je preucena iz yt-dlp 2026.10.21 (licenca Unlicense - javna last;
 * yt_dlp/extractor/youtube/_base.py INNERTUBE_CLIENTS['visionos'], _video.py _extract_player_response). Nic ni iz
 * NewPipeExtractor (GPL). Izmerjeno 25. 10. 2026 s prenosnika (outputs/pregled-zdruzeno/yt-zvocnik-25okt.md, razdelek 8):
 *  - odjemalec VISIONOS z visitorData da tokova itag 140 (AAC) in 251 (Opus) z neposrednim naslovom - brez PO tokena, brez
 *    podpisa in brez JavaScripta; vsi obsegi Range 206 (zacetek, sredina, konec);
 *  - brez visitorData YouTube odgovori »Sign in to confirm you're not a bot« (3/3);
 *  - visitorData da youtubei/v1/visitor_id (854 B, 180 ms) - prva stran youtube.com bi bila 955 kB.
 * Najpogostejse spremembe pri YouTubu (glej porocilo): kateri odjemalec dela brez PO tokena, razlicice odjemalcev, SABR.
 *
 * Zascitene vsebine ne odklepamo: posnetek z DRM (drmFamilies, licenseInfos), brez neposrednega naslova ali s stanjem, ki
 * ni OK, se zvocniku ne ponudi.
 */
object YoutubeZvokPravila {
    const val API = "https://www.youtube.com/youtubei/v1/"
    const val PLAYER = API + "player?prettyPrint=false"
    const val VISITOR_ID = API + "visitor_id?prettyPrint=false"

    // VISIONOS (yt-dlp 2026.10.21 INNERTUBE_CLIENTS['visionos']: REQUIRE_JS_PLAYER False, brez pravil PO tokena).
    const val ODJEMALEC = "VISIONOS"
    const val RAZLICICA = "1.02"
    const val ODJEMALEC_ST = "101"
    const val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    // visitor_id zahteva odjemalca WEB (yt-dlp INNERTUBE_CLIENTS['web']).
    const val SPLET_RAZLICICA = "2.20260708.00.00"

    /** Odgovor player je pri glasbenem posnetku ~100-300 kB; vec ne beremo (omrezje ni zaupanja vredno). */
    const val NAJVEC_ODGOVORA = 4 * 1024 * 1024
    /** visitorData velja dolgo; obnovimo ga po tem casu ali takoj ob preverbi »not a bot«. */
    const val OBISK_VELJA_MS = 12 * 3600_000L
    /** Naslov toka velja ~6 h (parameter expire, izmerjeno 359 min); uporabimo ga najvec do 30 min pred iztekom. */
    const val REZERVA_IZTEKA_S = 30 * 60L
    /** Najprej AAC (zvocniki ga vsi znajo; JBL Cast in DLNA izmerjeno), nato Opus (JBL Cast izmerjeno, DLNA ne). */
    val ITAGI = listOf(140, 251)

    private val ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** ID posnetka iz povezave YouTube (watch, youtu.be, shorts, embed, live, music); sicer null. */
    fun videoId(povezava: String): String? {
        val u = try { URI(povezava.trim()) } catch (_: Exception) { return null }
        if ((u.scheme ?: "").lowercase() !in setOf("http", "https")) return null
        val g = (u.host ?: return null).lowercase().removePrefix("www.").removePrefix("m.").removePrefix("music.")
        val pot = u.rawPath.orEmpty()
        val id = when (g) {
            "youtu.be" -> pot.trim('/').substringBefore('/')
            "youtube.com", "youtube-nocookie.com" -> when {
                pot == "/watch" -> parametri(u.rawQuery)["v"]
                listOf("/shorts/", "/embed/", "/live/", "/v/").any { pot.startsWith(it) } -> pot.split('/').getOrNull(2)
                else -> null
            }
            else -> null
        } ?: return null
        return id.takeIf { ID.matches(it) }
    }

    /** Parametri poizvedbe (prvi pojav imena velja). */
    fun parametri(poizvedba: String?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (del in poizvedba.orEmpty().split('&')) {
            if (del.isEmpty()) continue
            val i = del.indexOf('=')
            val k = dekodiraj(if (i < 0) del else del.substring(0, i))
            if (k !in out) out[k] = if (i < 0) "" else dekodiraj(del.substring(i + 1))
        }
        return out
    }

    private fun dekodiraj(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }

    // ------------------------------------------------------------------ zahteve

    fun telesoVisitorId(): String = JSONObject().put("context", JSONObject().put("client", JSONObject()
        .put("clientName", "WEB").put("clientVersion", SPLET_RAZLICICA).put("hl", "en").put("gl", "US"))).toString()

    fun glaveVisitorId(): Map<String, String> = linkedMapOf("Content-Type" to "application/json", "User-Agent" to UA,
        "X-YouTube-Client-Name" to "1", "X-YouTube-Client-Version" to SPLET_RAZLICICA, "Origin" to "https://www.youtube.com")

    /** visitorData iz odgovora visitor_id; samo znaki, ki jih YouTube uporablja (gre nazaj v glavo zahteve). */
    fun visitorIzOdgovora(json: String): String? = try {
        JSONObject(json).optJSONObject("responseContext")?.optString("visitorData")
            ?.takeIf { v -> v.length in 10..1000 && v.all { it.isLetterOrDigit() || it in "-_%=" } }
    } catch (_: Exception) { null }

    fun telesoPlayer(videoId: String, visitorData: String): String {
        require(ID.matches(videoId)) { "neveljaven ID posnetka" }
        val klient = JSONObject().put("clientName", ODJEMALEC).put("clientVersion", RAZLICICA).put("hl", "en").put("gl", "US")
            .put("userAgent", UA).put("deviceMake", "Apple").put("deviceModel", "RealityDevice17,1")
            .put("osName", "visionOS").put("osVersion", "26.5.23O471")
        if (visitorData.isNotEmpty()) klient.put("visitorData", visitorData)
        return JSONObject().put("context", JSONObject().put("client", klient)).put("videoId", videoId)
            .put("playbackContext", JSONObject().put("contentPlaybackContext", JSONObject().put("html5Preference", "HTML5_PREF_WANTS")))
            .put("contentCheckOk", true).put("racyCheckOk", true).toString()
    }

    fun glavePlayer(visitorData: String): Map<String, String> {
        val g = linkedMapOf("Content-Type" to "application/json", "User-Agent" to UA, "X-YouTube-Client-Name" to ODJEMALEC_ST,
            "X-YouTube-Client-Version" to RAZLICICA, "Origin" to "https://www.youtube.com")
        if (visitorData.isNotEmpty()) g["X-Goog-Visitor-Id"] = visitorData
        return g
    }

    // ------------------------------------------------------------------ odgovor

    enum class Razlog { OK, BOT, NI_OK, DRM, BREZ_TOKA, NAPAKA }

    /** Zvocni tok: [ipV6] = naslov je vezan na naslov IPv6 te naprave (zvocnik ga sam ne more brati - posrednik). */
    data class Tok(val url: String, val mime: String, val itag: Int, val bajtov: Long, val poteceS: Long, val ipV6: Boolean,
                   /** Trajanje iz approxDurationMs (0 = ni znano): zaslon in konec skladbe ne cakata na oceno zvocnika. */
                   val trajanjeMs: Long = 0L)
    data class Izid(val razlog: Razlog, val tok: Tok? = null, val opis: String = "")

    /**
     * Izbere zvocni tok iz odgovora player: AAC (140), sicer Opus (251); izvirna zvocna sled (ne sinhronizacija), brez
     * stiskanja dinamike (DRC). [zdajS]: ura v sekundah (za expire).
     */
    fun izberi(json: String, zdajS: Long): Izid {
        val d = try { JSONObject(json) } catch (_: Exception) { return Izid(Razlog.NAPAKA, opis = "odgovor ni JSON") }
        val ps = d.optJSONObject("playabilityStatus")
        val status = ps?.optString("status").orEmpty()
        if (status != "OK") {
            val bot = status == "LOGIN_REQUIRED" && ps?.optString("reason").orEmpty().contains("bot", ignoreCase = true)
            return Izid(if (bot) Razlog.BOT else Razlog.NI_OK, opis = status.ifEmpty { "brez stanja" })
        }
        val sd = d.optJSONObject("streamingData") ?: return Izid(Razlog.BREZ_TOKA, opis = "ni streamingData")
        if (sd.has("drmFamilies") || (sd.optJSONArray("licenseInfos")?.length() ?: 0) > 0) return Izid(Razlog.DRM)
        val af = sd.optJSONArray("adaptiveFormats") ?: return Izid(Razlog.BREZ_TOKA, opis = "ni adaptiveFormats")
        val oblike = (0 until af.length()).mapNotNull { af.optJSONObject(it) }
        if (oblike.any { it.has("drmFamilies") }) return Izid(Razlog.DRM)
        for (itag in ITAGI) {
            val kandidati = oblike.filter { it.optInt("itag") == itag && it.optString("url").isNotEmpty() }
                .sortedWith(compareBy({ jeDrc(it) }, { !izvirnaSled(it) }))
            for (f in kandidati) {
                val url = f.optString("url")
                if (!jeGooglevideo(url)) continue
                val q = parametri(try { URI(url).rawQuery } catch (_: Exception) { null })
                // Parameter n (izziv JavaScripta) pomeni dusenje ali 403 brez izvajanja kode strani - takega toka ne vzamemo.
                if ("n" in q) continue
                val poteka = q["expire"]?.toLongOrNull() ?: continue
                if (poteka - zdajS < REZERVA_IZTEKA_S) continue
                return Izid(Razlog.OK, Tok(url, f.optString("mimeType").substringBefore(';').trim().ifEmpty { if (itag == 140) "audio/mp4" else "audio/webm" },
                    itag, f.optString("contentLength").toLongOrNull() ?: -1L, poteka, q["ip"].orEmpty().contains(':'),
                    f.optString("approxDurationMs").toLongOrNull() ?: 0L))
            }
        }
        // Samo signatureCipher (zahteva JavaScript strani) ali SABR brez naslovov.
        return Izid(Razlog.BREZ_TOKA, opis = if (oblike.any { it.has("signatureCipher") }) "signatureCipher" else "brez naslova")
    }

    private fun jeDrc(f: JSONObject): Boolean = f.optBoolean("isDrc", false) || f.optString("url").contains("xtags=drc", true)

    private fun izvirnaSled(f: JSONObject): Boolean = f.optJSONObject("audioTrack")?.optBoolean("audioIsDefault", true) ?: true

    /** Samo https na googlevideo.com (tok YouTuba) - drugam ne posredujemo in zvocniku ne posljemo. */
    fun jeGooglevideo(url: String): Boolean = try {
        val u = URI(url)
        val g = u.host?.lowercase().orEmpty()
        u.scheme == "https" && u.rawUserInfo == null && (u.port == -1 || u.port == 443) &&
            (g == "googlevideo.com" || g.endsWith(".googlevideo.com"))
    } catch (_: Exception) { false }

    /** Tok je se uporaben (do [REZERVA_IZTEKA_S] pred iztekom). */
    fun velja(t: Tok, zdajS: Long): Boolean = t.poteceS - zdajS >= REZERVA_IZTEKA_S
}
