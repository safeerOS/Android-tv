package si.safeer.tv.os

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Glasba z Jamenda (api.jamendo.com v3.0): skladbe neodvisnih izvajalcev, brez
 * oglasov. Pogoji API-ja: nekomercialna raba (Safeer), ob vsaki skladbi izvajalec, Jamendo in
 * povezava na stran skladbe; brez shranjevanja za poslusanje brez povezave.
 * Radio je v [Radio] (Jamendo radiji imajo pokvarjeno potrdilo).
 *
 * Ključ aplikacije (client_id) je javen - posilja se z vsako zahtevo. Skrivnega ključa ne rabimo.
 * Iskanje skladb po besedi in oznakah s tem ključem vrača prazen seznam (preverjeno 21. 9. 2026),
 * zato iscemo po izvajalcih; seznami so razvrsceni po priljubljenosti.
 */
object Jamendo {
    private const val OSNOVA = "https://api.jamendo.com/v3.0"
    private const val KLJUC = "8d37f069"

    /** Ena enota za predvajanje: skladba, radijska postaja, video ali datoteka z naprave. */
    data class Skladba(
        val id: String,
        val naslov: String,
        val izvajalec: String,
        val slika: String,
        val zvok: String,
        val povezava: String,
        val radio: Boolean = false,
        /** Video (slika na zaslonu); pri PeerTubu je [zvok] prazen, dokler ga ne razresimo. */
        val video: Boolean = false,
        val mime: String = "",
        /** Streznik PeerTube, pri katerem vprasamo za datoteko videa. */
        val streznik: String = "",
        /** Kanal PeerTube (ime@streznik) za "se s tega kanala"; znan po razresitvi. */
        val kanal: String = "",
        /** Widevine podatki za uporabnikovo vsebino (Safeer Media, si.safeer.tv.media); navadni viri pustijo prazno. */
        val licenseUrl: String = "",
        val licenseHeadersJson: String = "",
    )

    data class Izvajalec(val id: String, val ime: String, val slika: String)

    /** Najbolj poslusane skladbe (razvrscene po priljubljenosti). */
    fun priljubljene(stevilo: Int = 48): List<Skladba> =
        // Jamendo obcasno vrne prazen seznam (preverjeno na tablici 21. 9. 2026); drugi poskus ga dobi.
        skladbe("order=popularity_total&limit=$stevilo").ifEmpty { Thread.sleep(800); skladbe("order=popularity_total&limit=$stevilo") }

    /** Skladbe izvajalca, najbolj poslusane najprej. */
    fun odIzvajalca(id: String): List<Skladba> =
        skladbe("artist_id=${kodiraj(id)}&order=popularity_total&limit=48")

    fun isciIzvajalce(beseda: String): List<Izvajalec> {
        val j = zahteva("/artists/?namesearch=${kodiraj(beseda)}&order=popularity_total&limit=36")
        val r = j.optJSONArray("results") ?: return emptyList()
        return (0 until r.length()).map { r.getJSONObject(it) }.map {
            Izvajalec(it.optString("id"), it.optString("name"), it.optString("image"))
        }.filter { it.id.isNotBlank() && it.ime.isNotBlank() }
    }

    private fun skladbe(poizvedba: String): List<Skladba> {
        val r = zahteva("/tracks/?$poizvedba&audioformat=mp32").optJSONArray("results") ?: return emptyList()
        return (0 until r.length()).map { r.getJSONObject(it) }.map {
            Skladba(it.optString("id"), it.optString("name"), it.optString("artist_name"),
                it.optString("image").ifBlank { it.optString("album_image") }, it.optString("audio"),
                it.optString("shorturl").ifBlank { it.optString("shareurl") })
        }.filter { it.zvok.startsWith("https://") }
    }

    private fun zahteva(pot: String): JSONObject {
        val locilo = if (pot.contains("?")) "&" else "?"
        val povezava = URL("$OSNOVA$pot${locilo}client_id=$KLJUC&format=json").openConnection() as HttpURLConnection
        povezava.connectTimeout = 10_000
        povezava.readTimeout = 15_000
        povezava.setRequestProperty("User-Agent", "SafeerOS")
        try {
            if (povezava.responseCode != 200) throw java.io.IOException("HTTP ${povezava.responseCode}")
            val telo = povezava.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(telo)
            val glava = j.optJSONObject("headers")
            if (glava != null && glava.optString("status") != "success") throw java.io.IOException(glava.optString("error_message"))
            return j
        } finally {
            povezava.disconnect()
        }
    }

    /** Slika (naslovnica) kot bajti; majhne slike, zato brez predpomnilnika na disku. */
    fun bajti(naslov: String): ByteArray? = try {
        val p = URL(naslov).openConnection() as HttpURLConnection
        p.connectTimeout = 8_000; p.readTimeout = 10_000
        p.setRequestProperty("User-Agent", "SafeerOS")
        try { if (p.responseCode == 200) p.inputStream.use { it.readBytes() } else null } finally { p.disconnect() }
    } catch (_: Exception) { null }

    private fun kodiraj(s: String) = URLEncoder.encode(s, "UTF-8")
}