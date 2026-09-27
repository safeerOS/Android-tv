package si.safeer.tv.os

import org.json.JSONArray
import org.json.JSONObject
import si.safeer.tv.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Postaje v zivo iz neuradnega javnega TuneIn OPML vmesnika. Oddaj, podkastov, premium vsebin in
 * oglasnih povezav ne odpiramo; ce se odgovor spremeni, vir preprosto ne prispeva postaj.
 */
object TuneIn {
    private const val OSNOVA = "https://opml.radiotime.com"
    private val USER_AGENT = "SafeerOS/${BuildConfig.VERSION_NAME} (+https://safeer.si)"
    private const val ROK = 6_000
    private const val VELJAVNOST = 30 * 60 * 1_000L
    private const val FORMATI = "mp3,aac,ogg,hls"

    private data class Vnos(val ob: Long, val vrednost: Any?)
    private val predpomnilnik = HashMap<String, Vnos>()

    /** Iskanje vrne samo radijske postaje v zivo, ne oddaj in tem. */
    fun isci(beseda: String): List<Jamendo.Skladba> {
        val q = beseda.trim()
        if (q.length < 2) return emptyList()
        val kljuc = "isci:${q.lowercase()}"
        return pomni(kljuc) {
            val pot = "/Search.ashx?query=${kodiraj(q)}&render=json&formats=$FORMATI"
            postaje(zahteva(pot)?.optJSONArray("body"))
        }
    }

    /** Postaje, ki jih TuneIn doloci iz javnega naslova IP; skupine razpremo v en seznam. */
    fun lokalne(): List<Jamendo.Skladba> = pomni("lokalne") {
        postaje(zahteva("/Browse.ashx?c=local&render=json")?.optJSONArray("body"))
    }

    /** Ob kliku poisce dejanski tok postaje. */
    fun razresi(sk: Jamendo.Skladba): Jamendo.Skladba? {
        val guideId = sk.id.removePrefix("tunein:").takeIf { sk.id.startsWith("tunein:") && it.isNotBlank() }
            ?: return null
        return pomni("tok:$guideId") {
            val telo = zahteva("/Tune.ashx?id=${kodiraj(guideId)}&render=json&formats=$FORMATI")
            val body = telo?.optJSONArray("body") ?: return@pomni null
            var tok: String? = null
            for (i in 0 until body.length()) {
                val v = body.optJSONObject(i) ?: continue
                val url = v.optString("url")
                if (v.optString("element") == "audio" && jeHttp(url)) { tok = url; break }
            }
            tok?.let { razresiSeznam(it) }?.let { url ->
                sk.copy(zvok = url, mime = if (pot(url).endsWith(".m3u8")) MedijskiViri.MIME_HLS else "")
            }
        }
    }

    fun jeEnota(sk: Jamendo.Skladba) = sk.id.startsWith("tunein:")

    private fun postaje(body: JSONArray?): List<Jamendo.Skladba> {
        if (body == null) return emptyList()
        val vsi = ArrayList<JSONObject>()
        fun dodaj(a: JSONArray) {
            for (i in 0 until a.length()) {
                val v = a.optJSONObject(i) ?: continue
                val formati = v.optString("formats").lowercase().split(',').map { it.trim() }.filter { it.isNotBlank() }
                val podprt = formati.isEmpty() || formati.any { it == "mp3" || it == "aac" || it == "ogg" || it == "hls" }
                if (v.optString("type") == "audio" && v.optString("item") == "station" && podprt) vsi += v
                v.optJSONArray("children")?.let(::dodaj)
            }
        }
        dodaj(body)
        return vsi.mapNotNull { v ->
            val id = v.optString("guide_id").trim()
            val naslov = v.optString("text").trim()
            if (id.isBlank() || naslov.isBlank()) return@mapNotNull null
            Jamendo.Skladba(
                id = "tunein:$id",
                naslov = naslov,
                izvajalec = v.optString("subtext").trim(),
                slika = varnaSlika(v.optString("image")),
                zvok = "",
                povezava = "https://tunein.com/radio/$id/",
                radio = true,
            )
        }.distinctBy { it.id }
    }

    /** Navadni M3U/PLS je le kazalec; HLS M3U8 ostane neposreden tok. */
    private fun razresiSeznam(naslov: String): String? {
        val konec = pot(naslov)
        if (!konec.endsWith(".pls") && !konec.endsWith(".m3u")) return naslov
        val p = povezava(naslov) ?: return null
        return try {
            if (p.responseCode !in 200..299) null
            else MedijskiViri.beriSeznam(p.inputStream.bufferedReader().use { it.readText().take(2_000_000) }, naslov)
                .firstOrNull()?.zvok
        } catch (_: Exception) { null } finally { p.disconnect() }
    }

    private fun zahteva(pot: String): JSONObject? {
        val p = povezava(OSNOVA + pot) ?: return null
        return try {
            if (p.responseCode !in 200..299) null
            else JSONObject(p.inputStream.bufferedReader().use { it.readText().take(2_000_000) })
        } catch (_: Exception) { null } finally { p.disconnect() }
    }

    private fun povezava(naslov: String): HttpURLConnection? = try {
        (URL(naslov).openConnection() as HttpURLConnection).apply {
            connectTimeout = ROK
            readTimeout = ROK
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, audio/x-scpls, audio/x-mpegurl, */*")
        }
    } catch (_: Exception) { null }

    @Suppress("UNCHECKED_CAST")
    private fun <T> pomni(kljuc: String, nalozi: () -> T): T {
        val zdaj = System.currentTimeMillis()
        synchronized(predpomnilnik) {
            predpomnilnik[kljuc]?.takeIf { zdaj - it.ob < VELJAVNOST }?.let { return it.vrednost as T }
        }
        val vrednost = nalozi()
        synchronized(predpomnilnik) {
            predpomnilnik.entries.removeAll { zdaj - it.value.ob >= VELJAVNOST }
            predpomnilnik[kljuc] = Vnos(zdaj, vrednost)
        }
        return vrednost
    }

    private fun kodiraj(v: String) = URLEncoder.encode(v, "UTF-8")
    private fun jeHttp(v: String) = v.startsWith("https://") || v.startsWith("http://")
    private fun pot(v: String) = v.substringBefore('?').substringBefore('#').lowercase()
    private fun varnaSlika(v: String) = if (v.startsWith("http://")) "https://${v.removePrefix("http://")}" else v
}
