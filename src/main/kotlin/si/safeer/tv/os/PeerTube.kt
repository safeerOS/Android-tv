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

    /**
     * Iskanje po streznikih. PeerTube isce ohlapno ("Eminem" je 21. 9. 2026 vrnil videe o bananah),
     * zato obdrzimo samo posnetke, pri katerih so vse besede iskanja v naslovu, kanalu, opisu ali oznakah.
     */
    fun isci(strezniki: List<String>, beseda: String): List<Jamendo.Skladba> {
        val besede = normaliziraj(beseda).split(' ').filter { it.length >= 2 }
        // Strezniki hkrati in z rokom 5 s: tilvids.com je 21. 9. 2026 odgovoril v 14 s ali sploh ne,
        // framatube.org v 1,7 s - pocasen streznik ne sme zadrzati ostalih.
        val bazen = java.util.concurrent.Executors.newCachedThreadPool()
        val opravila = strezniki.map { s -> bazen.submit<List<Jamendo.Skladba>> {
            try {
                seznam(s, "/api/v1/search/videos?search=${URLEncoder.encode(beseda, "UTF-8")}&sort=-views&nsfw=false&count=30&searchTarget=local") { v ->
                    val besedilo = normaliziraj(listOf(v.optString("name"), v.optJSONObject("channel")?.optString("displayName").orEmpty(),
                        v.optJSONObject("account")?.optString("displayName").orEmpty(), v.optString("description"), v.optString("truncatedDescription"),
                        v.optJSONArray("tags")?.join(" ").orEmpty()).joinToString(" "))
                    besede.all { besedilo.contains(it) }
                }
            } catch (_: Exception) { emptyList() }
        } }
        bazen.shutdown()
        val rok = System.currentTimeMillis() + 5_000
        return opravila.flatMap { f ->
            try { f.get((rok - System.currentTimeMillis()).coerceAtLeast(1), java.util.concurrent.TimeUnit.MILLISECONDS) }
            catch (_: Exception) { f.cancel(true); emptyList() }
        }.distinctBy { it.id }
    }

    private fun normaliziraj(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Ali na tem naslovu tece PeerTube; vrne ime streznika ali null. */
    fun imeStreznika(streznik: String): String? = try {
        val j = JSONObject(beri("https://$streznik/api/v1/config"))
        j.optJSONObject("instance")?.optString("name")?.ifBlank { streznik }
    } catch (_: Exception) { null }

    /**
     * Datoteka za predvajanje: najboljsa kakovost do 1080p (samostojna MP4, brez HLS). Najprej
     * vprasamo izvorni streznik, nato streznike, kjer smo video nasli - izvor je vcasih nedosegljiv
     * (preverjeno 21. 9. 2026: tinkerbetter.tube), povezani streznik pa pozna iste datoteke.
     */
    fun razresi(v: Jamendo.Skladba, rezervni: List<String> = VGRAJENI): Jamendo.Skladba? {
        for (s in (listOf(v.streznik) + rezervni).filter { it.isNotBlank() }.distinct()) {
            val r = try { razresiPri(s, v) } catch (_: Exception) { null }
            if (r != null) return r
        }
        return null
    }

    private fun razresiPri(streznik: String, v: Jamendo.Skladba): Jamendo.Skladba? {
        val j = JSONObject(beri("https://$streznik/api/v1/videos/${v.id}"))
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
        val kanal = j.optJSONObject("channel")?.let { "${it.optString("name")}@${it.optString("host")}" }.orEmpty()
        return v.copy(zvok = url, mime = "video/mp4", kanal = kanal, streznik = streznik)
    }

    /**
     * Predlogi pod videom: najbolj gledani s tega kanala in posnetki o isti temi (najdaljsa beseda
     * naslova), izmenicno, brez tega videa. Preverjeno 21. 9. 2026 na tilvids.com in framatube.org.
     */
    fun predlogi(v: Jamendo.Skladba): List<Jamendo.Skladba> {
        val s = v.streznik.ifBlank { return emptyList() }
        val kanal = try {
            if (v.kanal.contains('@')) seznam(s, "/api/v1/video-channels/${v.kanal}/videos?sort=-views&count=12&nsfw=false") else emptyList()
        } catch (_: Exception) { emptyList() }
        val beseda = v.naslov.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }.maxByOrNull { it.length }
        val tema = beseda?.let { isci(listOf(s), it) }.orEmpty()
        return (0 until maxOf(kanal.size, tema.size)).flatMap { listOfNotNull(kanal.getOrNull(it), tema.getOrNull(it)) }
            .distinctBy { it.id }.filter { it.id != v.id }.take(20)
    }

    private fun seznam(streznik: String, pot: String, ustreza: (JSONObject) -> Boolean = { true }): List<Jamendo.Skladba> {
        val r = JSONObject(beri("https://$streznik$pot")).optJSONArray("data") ?: return emptyList()
        return (0 until r.length()).map { r.getJSONObject(it) }.mapNotNull { v ->
            if (v.optBoolean("nsfw") || v.optBoolean("isLive") || !ustreza(v)) return@mapNotNull null
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
