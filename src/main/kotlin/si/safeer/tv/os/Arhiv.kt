package si.safeer.tv.os

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Internet Archive (archive.org): samo zbirke z jasnimi pravicami - posnetki koncertov, ki jih
 * skupine dovolijo (etree), zvocne knjige v javni domeni (LibriVox), stari radio (Old Time Radio),
 * filmi v javni domeni (feature_films) in arhiv Prelinger. Nalozenih kopij tuje glasbe ne ponujamo.
 * Iskanje po naslovu in avtorju (preverjeno 22. 9. 2026; brez polj je vracalo nepovezane filme).
 *
 * Enota je [Jamendo.Skladba] z id "arhiv:<identifier>" in praznim zvokom: datoteke vprasamo ob predvajanju.
 */
object Arhiv {
    private const val PREDPONA = "arhiv:"
    private const val ZBIRKE = "collection:(etree OR librivoxaudio OR oldtimeradio OR feature_films OR prelinger)"

    fun jeEnota(s: Jamendo.Skladba) = s.id.startsWith(PREDPONA)

    fun isci(beseda: String, stevilo: Int = 12): List<Jamendo.Skladba> {
        val q = beseda.trim().replace(Regex("[()\"\\\\:]"), " ").trim()
        if (q.length < 2) return emptyList()
        val poizvedba = "(title:($q) OR creator:($q)) AND $ZBIRKE"
        val url = "https://archive.org/advancedsearch.php?q=" + URLEncoder.encode(poizvedba, "UTF-8") +
            "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=mediatype&rows=$stevilo&output=json&sort[]=downloads+desc"
        val d = JSONObject(beri(url)).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        return (0 until d.length()).map { d.getJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("identifier")
            val vrsta = o.optString("mediatype")
            if (id.isBlank() || vrsta !in setOf("audio", "movies", "etree")) return@mapNotNull null
            Jamendo.Skladba(PREDPONA + id, o.optString("title").ifBlank { id }, besedilo(o.opt("creator")),
                "https://archive.org/services/img/$id", "", "https://archive.org/details/$id", video = vrsta == "movies")
        }
    }

    /** Datoteke enote za predvajanje: video MP4 ali zvok MP3/OGG (brez dvojnikov v drugih zapisih), po vrsti. */
    fun datoteke(s: Jamendo.Skladba): List<Jamendo.Skladba> {
        val id = s.id.removePrefix(PREDPONA)
        val m = JSONObject(beri("https://archive.org/metadata/" + URLEncoder.encode(id, "UTF-8").replace("+", "%20")))
        val f = m.optJSONArray("files") ?: return emptyList()
        val vse = (0 until f.length()).map { f.getJSONObject(it) }
        fun konca(o: JSONObject, vararg k: String) = o.optString("name").lowercase().let { n -> k.any { n.endsWith(it) } }
        val izbrane = if (s.video) vse.filter { konca(it, ".mp4") }.take(1)
            else vse.filter { konca(it, ".mp3") }.let { mp3 ->
                // Ista skladba je pogosto v vec zapisih (VBR MP3, 64Kbps MP3): vzamemo en zapis.
                val zapis = listOf("VBR MP3", "128Kbps MP3", "MP3", "64Kbps MP3").firstOrNull { z -> mp3.any { it.optString("format") == z } }
                if (zapis != null) mp3.filter { it.optString("format") == zapis } else mp3
            }.ifEmpty { vse.filter { konca(it, ".ogg") } }
        return izbrane.sortedBy { it.optString("track").substringBefore('/').toIntOrNull() ?: Int.MAX_VALUE }.map { o ->
            val ime = o.optString("name")
            val pot = ime.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            Jamendo.Skladba("arhivd:$id/$ime", o.optString("title").ifBlank { ime.substringAfterLast('/').substringBeforeLast('.') },
                s.naslov, s.slika, "https://archive.org/download/$id/$pot", s.povezava, video = s.video,
                mime = if (s.video) "video/mp4" else if (ime.lowercase().endsWith(".ogg")) "audio/ogg" else "audio/mpeg")
        }
    }

    private fun besedilo(v: Any?): String = when (v) {
        is org.json.JSONArray -> (0 until v.length()).joinToString(", ") { v.optString(it) }
        null -> ""
        else -> v.toString()
    }

    private fun beri(naslov: String): String {
        val p = URL(naslov).openConnection() as HttpURLConnection
        p.connectTimeout = 5_000; p.readTimeout = 10_000
        p.setRequestProperty("User-Agent", "SafeerOS/0.4 (+https://safeer.si)")
        try {
            if (p.responseCode != 200) throw java.io.IOException("HTTP ${p.responseCode}")
            return p.inputStream.bufferedReader().use { it.readText() }
        } finally { p.disconnect() }
    }
}
