package si.safeer.tv.os

import org.json.JSONArray
import org.json.JSONObject

private fun preveri(pogoj: Boolean, sporocilo: String) = check(pogoj) { sporocilo }

/** Ura v sekundah za preizkus (oktober 2026). */
private const val ZDAJ = 1_793_000_000L

/** Naslov toka v obliki, kot jo je 25. 10. 2026 vrnil odjemalec VISIONOS (izmisljene vrednosti, dokumentacijski IP). */
private fun naslov(itag: Int, dodatno: String = "", expire: Long = ZDAJ + 6 * 3600, ip: String = "203.0.113.7") =
    "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=$expire&ei=x&ip=$ip&id=o-A&itag=$itag&source=youtube$dodatno&sig=AJ"

private fun oblika(itag: Int, url: String?, mime: String, drc: Boolean = false, privzeta: Boolean? = null,
                   cipher: Boolean = false, drm: Boolean = false): JSONObject {
    val f = JSONObject().put("itag", itag).put("mimeType", mime).put("contentLength", "3449447").put("approxDurationMs", "213041")
    if (url != null) f.put("url", url)
    if (cipher) f.put("signatureCipher", "s=AB&sp=sig&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback")
    if (drc) f.put("isDrc", true)
    if (privzeta != null) f.put("audioTrack", JSONObject().put("audioIsDefault", privzeta))
    if (drm) f.put("drmFamilies", JSONArray().put("WIDEVINE"))
    return f
}

private fun odgovor(status: String = "OK", razlog: String = "", oblike: List<JSONObject> = emptyList(), drm: Boolean = false): String {
    val d = JSONObject().put("playabilityStatus", JSONObject().put("status", status).put("reason", razlog))
    if (status == "OK") {
        val sd = JSONObject().put("adaptiveFormats", JSONArray(oblike))
        if (drm) sd.put("drmFamilies", JSONArray().put("WIDEVINE"))
        d.put("streamingData", sd)
    }
    return d.toString()
}

private fun izberi(json: String) = YoutubeZvokPravila.izberi(json, ZDAJ)

fun main() {
    // ID posnetka iz vseh oblik povezav YouTube - in nic drugega.
    listOf(
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        "https://m.youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=10",
        "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDAMVM",
        "https://youtu.be/dQw4w9WgXcQ?t=3",
        "https://www.youtube.com/shorts/dQw4w9WgXcQ",
        "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
    ).forEach { preveri(YoutubeZvokPravila.videoId(it) == "dQw4w9WgXcQ", "videoId $it") }
    listOf(
        "https://www.youtube.com/results?search_query=x", "https://example.com/watch?v=dQw4w9WgXcQ", "javascript:alert(1)",
        "https://www.youtube.com/watch?v=kratek", "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ", "ftp://youtu.be/dQw4w9WgXcQ",
        "https://www.youtube.com/watch?v=dQw4w9WgXc%22",
    ).forEach { preveri(YoutubeZvokPravila.videoId(it) == null, "ni posnetek: $it") }

    // Zahteva player: odjemalec VISIONOS, visitorData v kontekstu in v glavi; neveljaven ID ne gre v zahtevo.
    val t = JSONObject(YoutubeZvokPravila.telesoPlayer("dQw4w9WgXcQ", "CgtABC"))
    val k = t.getJSONObject("context").getJSONObject("client")
    preveri(k.getString("clientName") == "VISIONOS" && k.getString("clientVersion") == "1.02", "odjemalec")
    preveri(k.getString("visitorData") == "CgtABC" && t.getString("videoId") == "dQw4w9WgXcQ", "visitorData in ID")
    preveri(t.getBoolean("contentCheckOk") && t.getBoolean("racyCheckOk"), "checkOk")
    val g = YoutubeZvokPravila.glavePlayer("CgtABC")
    preveri(g["X-Goog-Visitor-Id"] == "CgtABC" && g["X-YouTube-Client-Name"] == "101" && g["User-Agent"] == YoutubeZvokPravila.UA, "glave")
    preveri("X-Goog-Visitor-Id" !in YoutubeZvokPravila.glavePlayer(""), "brez visitorData ni glave")
    preveri(runCatching { YoutubeZvokPravila.telesoPlayer("x\"}", "") }.isFailure, "neveljaven ID")
    preveri(JSONObject(YoutubeZvokPravila.telesoVisitorId()).getJSONObject("context").getJSONObject("client").getString("clientName") == "WEB", "visitor_id WEB")

    // visitorData iz odgovora visitor_id: samo znaki, ki jih YouTube uporablja (gre v glavo zahteve).
    preveri(YoutubeZvokPravila.visitorIzOdgovora("""{"responseContext":{"visitorData":"CgtYV2xQWkJ3%3D%3D"}}""") == "CgtYV2xQWkJ3%3D%3D", "visitor")
    preveri(YoutubeZvokPravila.visitorIzOdgovora("""{"responseContext":{"visitorData":"abcdefghijk\r\nX-Evil: 1"}}""") == null, "visitor z glavo")
    preveri(YoutubeZvokPravila.visitorIzOdgovora("ni json") == null && YoutubeZvokPravila.visitorIzOdgovora("{}") == null, "visitor ni")

    // AAC brez stiskanja dinamike ima prednost pred Opus in pred DRC.
    var i = izberi(odgovor(oblike = listOf(
        oblika(251, naslov(251), "audio/webm; codecs=\"opus\""),
        oblika(140, naslov(140, "&xtags=drc%3D1"), "audio/mp4", drc = true),
        oblika(140, naslov(140), "audio/mp4; codecs=\"mp4a.40.2\""))))
    var tk = i.tok
    preveri(i.razlog == YoutubeZvokPravila.Razlog.OK && tk != null && tk.itag == 140 && !tk.url.contains("drc") && tk.mime == "audio/mp4" && !tk.ipV6, "AAC brez DRC $i")
    preveri(tk!!.trajanjeMs == 213_041L && tk.bajtov == 3_449_447L && tk.poteceS == ZDAJ + 6 * 3600, "trajanje, velikost, iztek")
    // Samo Opus.
    tk = izberi(odgovor(oblike = listOf(oblika(251, naslov(251), "audio/webm; codecs=\"opus\"")))).tok
    preveri(tk != null && tk.itag == 251 && tk.mime == "audio/webm", "Opus")
    // Sinhronizirana sled (dub): izvirna ima prednost.
    tk = izberi(odgovor(oblike = listOf(oblika(140, naslov(140, "&lang=de"), "audio/mp4", privzeta = false),
        oblika(140, naslov(140, "&lang=en"), "audio/mp4", privzeta = true)))).tok
    preveri(tk != null && tk.url.contains("lang=en"), "izvirna sled")
    // Vrsta zvoka gre v glavo Content-Type posrednika: brez koncev vrstic (Grok 25, Z7).
    tk = izberi(odgovor(oblike = listOf(oblika(140, naslov(140), "audio/mp4\r\nX-Vrinjena: 1")))).tok
    preveri(tk != null && tk.mime == "audio/mp4", "mime brez vrinjene glave")
    preveri(YoutubeZvokPravila.mimeZvoka("AUDIO/WEBM; codecs=\"opus\"", 251) == "audio/webm" &&
        YoutubeZvokPravila.mimeZvoka("video/mp4", 251) == "audio/webm" && YoutubeZvokPravila.mimeZvoka("", 140) == "audio/mp4", "mimeZvoka")
    // Naslov za IPv6 te naprave: zvocnik ga sam ne prebere (rezerva je posrednik).
    tk = izberi(odgovor(oblike = listOf(oblika(140, naslov(140, ip = "2001%3Adb8%3A%3A1"), "audio/mp4")))).tok
    preveri(tk != null && tk.ipV6, "IPv6")

    // DRM, preverba »not a bot«, posnetek ni na voljo.
    preveri(izberi(odgovor(oblike = listOf(oblika(140, naslov(140), "audio/mp4")), drm = true)).razlog == YoutubeZvokPravila.Razlog.DRM, "DRM tok")
    preveri(izberi(odgovor(oblike = listOf(oblika(140, naslov(140), "audio/mp4", drm = true)))).razlog == YoutubeZvokPravila.Razlog.DRM, "DRM oblika")
    preveri(izberi(odgovor("LOGIN_REQUIRED", "Sign in to confirm you’re not a bot")).razlog == YoutubeZvokPravila.Razlog.BOT, "bot")
    preveri(izberi(odgovor("LOGIN_REQUIRED", "This video is private")).razlog == YoutubeZvokPravila.Razlog.NI_OK, "zaseben")
    preveri(izberi(odgovor("UNPLAYABLE")).razlog == YoutubeZvokPravila.Razlog.NI_OK, "ni predvajljiv")
    preveri(izberi("ni json").razlog == YoutubeZvokPravila.Razlog.NAPAKA, "ni json")

    // Brez uporabnega naslova: samo signatureCipher (JavaScript strani), parameter n, tuj gostitelj, http, kmalu potece.
    val brez = listOf(
        listOf(oblika(140, null, "audio/mp4", cipher = true)),
        listOf(oblika(140, naslov(140, "&n=abcdef"), "audio/mp4")),
        listOf(oblika(140, "https://evil.example/videoplayback?expire=${ZDAJ + 9999}&itag=140", "audio/mp4")),
        listOf(oblika(140, naslov(140).replace("https://", "http://"), "audio/mp4")),
        listOf(oblika(140, naslov(140, expire = ZDAJ + 600), "audio/mp4")),
        listOf(oblika(18, naslov(18), "video/mp4")),
    )
    brez.forEachIndexed { n, o -> preveri(izberi(odgovor(oblike = o)).let { it.razlog == YoutubeZvokPravila.Razlog.BREZ_TOKA && it.tok == null }, "brez toka $n") }
    preveri(izberi(odgovor(oblike = listOf(oblika(140, null, "audio/mp4", cipher = true)))).opis == "signatureCipher", "opis signatureCipher")

    // Posrednik in zvocnik dobita samo https na googlevideo.com.
    preveri(YoutubeZvokPravila.jeGooglevideo("https://rr1---sn-x.googlevideo.com/videoplayback?a=1"), "googlevideo")
    listOf("https://googlevideo.com.evil.example/x", "https://user@rr1.googlevideo.com/x", "https://rr1.googlevideo.com:8443/x",
        "http://rr1.googlevideo.com/x", "https://evilgooglevideo.com/x", "ni naslov")
        .forEach { preveri(!YoutubeZvokPravila.jeGooglevideo(it), "ni googlevideo: $it") }

    // Iztek: tok se uporablja do 30 min pred iztekom.
    val tok = YoutubeZvokPravila.Tok("u", "audio/mp4", 140, 1, ZDAJ + 1800, false)
    preveri(YoutubeZvokPravila.velja(tok, ZDAJ) && !YoutubeZvokPravila.velja(tok, ZDAJ + 1), "iztek")
    println("YoutubeZvokPravilaTest: OK")
}
