package si.safeer.tv.os

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Video s PeerTuba: odprtokodno, brez oglasov in brez sledenja. Vgrajeni strezniki (preverjeno
 * 21. 9. 2026: tilvids.com, framatube.org) in strezniki, ki jih uporabnik doda sam ([MedijskiViri]).
 * Samo javni posnetki brez obcutljive vsebine in brez prenosov v zivo. Iskanje na strezniku zajame
 * tudi povezane streznike (federacija); za datoteko vedno vprasamo izvorni streznik.
 */
object PeerTube {
    val VGRAJENI = listOf("tilvids.com", "framatube.org")

    /** Najbolj gledani posnetki enega streznika. */
    fun najboljGledani(streznik: String, stevilo: Int = 24): List<Jamendo.Skladba> =
        seznam(streznik, "/api/v1/videos?sort=-views&count=$stevilo&nsfw=false&isLocal=true")

    fun isci(strezniki: List<String>, beseda: String): List<Jamendo.Skladba> = strezniki.flatMap { s ->
        try {
            seznam(s, "/api/v1/search/videos?search=${URLEncoder.encode(beseda, "UTF-8")}&sort=-views&nsfw=false&count=18&searchTarget=local")
        } catch (_: Exception) { emptyList() }
    }.distinctBy { it.id }

    /** Ali na tem naslovu tece PeerTube; vrne ime streznika ali null. */
    fun imeStreznika(streznik: String): String? = try {
        val j = JSONObject(beri("https://$streznik/api/v1/config"))
        j.optJSONObject("instance")?.optString("name")?.ifBlank { streznik }
    } catch (_: Exception) { null }

    /** Datoteka za predvajanje: najboljsa kakovost do 1080p (samostojna MP4, brez HLS). */
    fun razresi(v: Jamendo.Skladba): Jamendo.Skladba? {
        val j = JSONObject(beri("https://${v.streznik}/api/v1/videos/${v.id}"))
        val datoteke = mutableListOf<Pair<Int, String>>()
        fun dodaj(a: JSONArray?) {
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
        val url = datoteke.maxByOrNull { it.first }?.second ?: return null
        return v.copy(zvok = url, mime = "video/mp4")
    }

    private fun seznam(streznik: String, pot: String): List<Jamendo.Skladba> {
        val r = JSONObject(beri("https://$streznik$pot")).optJSONArray("data") ?: return emptyList()
        return (0 until r.length()).map { r.getJSONObject(it) }.mapNotNull { v ->
            if (v.optBoolean("nsfw") || v.optBoolean("isLive")) return@mapNotNull null
            val stran = v.optString("url")
            val slika = v.optString("previewPath").ifBlank { v.optString("thumbnailPath") }
            Jamendo.Skladba(v.optString("uuid"), v.optString("name"),
                v.optJSONObject("channel")?.optString("displayName").orEmpty().ifBlank { streznik },
                if (slika.startsWith("/")) "https://$streznik$slika" else slika,
                "", stran, video = true, streznik = Uri.parse(stran).host ?: streznik)
        }.filter { it.id.isNotBlank() && it.naslov.isNotBlank() }
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
