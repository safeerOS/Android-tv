package si.safeer.tv.os

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Video s PeerTuba: odprtokodno, brez oglasov in brez sledenja. Zacnemo z izbranimi strezniki
 * (preverjeno 21. 9. 2026: tilvids.com, framatube.org), samo javni posnetki brez obcutljive vsebine.
 * Iskanje na streznikih zajame tudi posnetke iz povezanih streznikov (federacija); datoteko
 * vedno vprasamo pri izvornem strezniku.
 */
object PeerTube {
    private val STREZNIKI = listOf("tilvids.com", "framatube.org")

    data class Video(
        val uuid: String,
        val naslov: String,
        val kanal: String,
        val slika: String,
        val stran: String,
        val streznik: String,
        val ogledi: Int,
    )

    /** Najbolj gledani s vsakega streznika, izmenicno - en velik streznik ne sme zasesti vsega. */
    fun priljubljeni(): List<Video> = izmenicno(STREZNIKI.map { s ->
        try { seznam(s, "/api/v1/videos?sort=-views&count=24&nsfw=false&isLocal=true") } catch (_: Exception) { emptyList() }
    })

    private fun izmenicno(seznami: List<List<Video>>): List<Video> =
        (0 until (seznami.maxOfOrNull { it.size } ?: 0)).flatMap { i -> seznami.mapNotNull { it.getOrNull(i) } }

    fun isci(beseda: String): List<Video> = STREZNIKI.flatMap { s ->
        try {
            seznam(s, "/api/v1/search/videos?search=${URLEncoder.encode(beseda, "UTF-8")}&sort=-views&nsfw=false&count=18&searchTarget=local")
        } catch (_: Exception) { emptyList() }
    }.distinctBy { it.uuid }.sortedByDescending { it.ogledi }

    /** Naslov datoteke za predvajanje: najboljsa kakovost do 1080p (samostojna datoteka MP4, brez HLS). */
    fun datoteka(v: Video): String? {
        val j = JSONObject(beri("https://${v.streznik}/api/v1/videos/${v.uuid}"))
        val datoteke = mutableListOf<Pair<Int, String>>()
        fun dodaj(a: org.json.JSONArray?) {
            if (a == null) return
            for (i in 0 until a.length()) {
                val f = a.getJSONObject(i)
                val visina = f.optJSONObject("resolution")?.optInt("id") ?: 0
                val url = f.optString("fileUrl")
                if (url.startsWith("https://") && visina in 1..1080) datoteke += visina to url
            }
        }
        dodaj(j.optJSONArray("files"))
        val seznami = j.optJSONArray("streamingPlaylists")
        if (seznami != null) for (i in 0 until seznami.length()) dodaj(seznami.getJSONObject(i).optJSONArray("files"))
        return datoteke.maxByOrNull { it.first }?.second
    }

    private fun seznam(streznik: String, pot: String): List<Video> {
        val r = JSONObject(beri("https://$streznik$pot")).optJSONArray("data") ?: return emptyList()
        return (0 until r.length()).map { r.getJSONObject(it) }.mapNotNull { v ->
            if (v.optBoolean("nsfw") || v.optBoolean("isLive")) return@mapNotNull null
            val stran = v.optString("url")
            val izvor = Uri.parse(stran).host ?: streznik
            val slika = v.optString("previewPath").ifBlank { v.optString("thumbnailPath") }
            Video(v.optString("uuid"), v.optString("name"),
                v.optJSONObject("channel")?.optString("displayName").orEmpty(),
                if (slika.startsWith("/")) "https://$streznik$slika" else slika,
                stran, izvor, v.optInt("views"))
        }.filter { it.uuid.isNotBlank() && it.naslov.isNotBlank() }
    }

    private fun beri(naslov: String): String {
        val p = URL(naslov).openConnection() as HttpURLConnection
        p.connectTimeout = 10_000; p.readTimeout = 15_000
        p.setRequestProperty("User-Agent", "SafeerOS")
        p.setRequestProperty("Accept", "application/json")
        try {
            if (p.responseCode != 200) throw java.io.IOException("HTTP ${p.responseCode}")
            return p.inputStream.bufferedReader().use { it.readText() }
        } finally { p.disconnect() }
    }
}
