package si.safeer.tv.os

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Internetni radio iz Radio Browserja (radio-browser.info): javni, brezplacni imenik postaj brez
 * registracije. Samo postaje, ki delujejo (hidebroken), s sifrirano povezavo (is_https) in brez HLS
 * (za HLS predvajalnik nima modula). Najprej postaje iz drzave televizorja, nato najbolj poslusane
 * na svetu.
 *
 * Jamendo radiji niso uporabni: streaming.jamendo.com vraca potrdilo za drugo ime (preverjeno
 * 21. 9. 2026), sifrirane povezave pa ne obidemo.
 */
object Radio {
    private const val OSNOVA = "https://de1.api.radio-browser.info/json/stations/search"

    fun postaje(): List<Jamendo.Skladba> {
        val drzava = Locale.getDefault().country.takeIf { it.length == 2 }
        val domace = drzava?.let { try { iskanje("countrycode=${it.lowercase(Locale.ROOT)}&limit=24") } catch (_: Exception) { emptyList() } }.orEmpty()
        val svet = iskanje("limit=60")
        return (domace + svet).distinctBy { it.zvok }.take(60)
    }

    private fun iskanje(filter: String): List<Jamendo.Skladba> {
        val p = URL("$OSNOVA?order=clickcount&reverse=true&hidebroken=true&is_https=true&$filter").openConnection() as HttpURLConnection
        p.connectTimeout = 10_000; p.readTimeout = 15_000
        p.setRequestProperty("User-Agent", "SafeerOS/0.4 (+https://safeer.si)")
        val telo = try {
            if (p.responseCode != 200) throw java.io.IOException("HTTP ${p.responseCode}")
            p.inputStream.bufferedReader().use { it.readText() }
        } finally { p.disconnect() }
        val a = JSONArray(telo)
        return (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.optInt("hls") == 0 && it.optString("url_resolved").startsWith("https://") }
            .map {
                val opis = listOf(it.optString("country"), it.optString("tags").split(',').firstOrNull().orEmpty())
                    .filter { s -> s.isNotBlank() }.joinToString(" · ")
                Jamendo.Skladba(it.optString("stationuuid"), it.optString("name").trim(), opis, it.optString("favicon"),
                    it.optString("url_resolved"), it.optString("homepage"), radio = true)
            }
            .filter { it.naslov.isNotBlank() }
    }

}
