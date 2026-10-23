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
    /**
     * Naloga 84: strezniki imenika. Prej samo de1 z rokom 10 s za povezavo in 15 s za branje - ko ta ni odgovarjal, je
     * Radio cakal 25 s. Zdaj krajsi rok in naslednje zrcalo; zadnji dober je prvi. (Ne »all.api...«: to je samo DNS, potrdilo
     * streznika velja za njegovo ime.)
     */
    private val STREZNIKI = listOf("https://de1.api.radio-browser.info", "https://fi1.api.radio-browser.info", "https://de2.api.radio-browser.info")
    private const val POT = "/json/stations/search"
    @Volatile private var zadnjiDober: String? = null

    /** Domace postaje (drzava televizorja) in najbolj poslusane na svetu, brez podvojenih. */
    fun postajeLocene(): Pair<List<Jamendo.Skladba>, List<Jamendo.Skladba>> {
        val drzava = Locale.getDefault().country.takeIf { it.length == 2 }
        val domace = drzava?.let { try { iskanje("countrycode=${it.lowercase(Locale.ROOT)}&limit=30") } catch (_: Exception) { emptyList() } }.orEmpty()
        val svet = iskanje("limit=60").filterNot { s -> domace.any { it.zvok == s.zvok } }
        return domace.distinctBy { it.zvok } to svet.distinctBy { it.zvok }
    }

    /**
     * Postaje po imenu in po oznaki (izvajalec ali zvrst, npr. "eminem", "jazz"), najbolj poslusane
     * najprej; dolga "imena" so seznami oznak za iskalnike, ne postaje.
     */
    fun isci(beseda: String): List<Jamendo.Skladba> {
        val b = java.net.URLEncoder.encode(beseda.trim(), "UTF-8")
        val poImenu = try { iskanje("name=$b&limit=30") } catch (_: Exception) { emptyList() }
        val poOznaki = try { iskanje("tag=${java.net.URLEncoder.encode(beseda.trim().lowercase(), "UTF-8")}&limit=30") } catch (_: Exception) { emptyList() }
        return (poImenu + poOznaki).filter { it.naslov.length <= 60 }.distinctBy { it.zvok }.take(24)
    }

    /**
     * Stran postaj po poslusanosti: [drzava] (ISO 3166-1, prazno = ves svet), [oznaka] (zvrst Radio Browserja, prazno =
     * vse), [odmik] = koliko postaj preskociti. Za mrezo Radia z izbirama »kje« in »zvrst«.
     */
    fun stran(drzava: String, oznaka: String, stevilo: Int, odmik: Int): List<Jamendo.Skladba> =
        iskanje(listOfNotNull(
            drzava.takeIf { it.length == 2 }?.let { "countrycode=${it.lowercase(Locale.ROOT)}" },
            oznaka.takeIf { it.isNotBlank() }?.let { "tag=${java.net.URLEncoder.encode(it, "UTF-8")}" },
            "limit=$stevilo", "offset=$odmik").joinToString("&")).filter { it.naslov.length <= 60 }

    /** Najbolj poslusane postaje ene glasbene zvrsti (oznaka Radio Browserja, npr. "jazz", "hip hop"). */
    fun poZvrsti(oznaka: String, stevilo: Int = 30): List<Jamendo.Skladba> =
        try {
            iskanje("tag=${java.net.URLEncoder.encode(oznaka, "UTF-8")}&limit=${stevilo + 10}")
                .filter { it.naslov.length <= 60 }.distinctBy { it.zvok }.take(stevilo)
        } catch (_: Exception) { emptyList() }

    /** Odgovor imenika: prvi streznik, ki odgovori ([OsPravila.vrstniRedStreznikov]); izjema, ce noben. */
    private fun prenesi(filter: String): String {
        var napaka: Exception? = null
        for (osnova in OsPravila.vrstniRedStreznikov(STREZNIKI, zadnjiDober)) {
            if (Thread.currentThread().isInterrupted) break
            val p = URL("$osnova$POT?order=clickcount&reverse=true&hidebroken=true&is_https=true&$filter").openConnection() as HttpURLConnection
            p.connectTimeout = 4_000; p.readTimeout = 8_000
            p.setRequestProperty("User-Agent", "SafeerOS/0.4 (+https://safeer.si)")
            try {
                if (p.responseCode != 200) throw java.io.IOException("HTTP ${p.responseCode}")
                val telo = p.inputStream.bufferedReader().use { it.readText() }
                zadnjiDober = osnova
                return telo
            } catch (e: Exception) { napaka = e } finally { p.disconnect() }
        }
        throw napaka ?: java.io.IOException("imenik ne odgovarja")
    }

    private fun iskanje(filter: String): List<Jamendo.Skladba> {
        val telo = prenesi(filter)
        val a = JSONArray(telo)
        val vnosi = (0 until a.length()).map { a.getJSONObject(it) }
            // HLS predvajalnik zdaj zna (Media3 HLS), zato tudi te postaje.
            .filter { it.optString("url_resolved").startsWith("https://") }
        // Ista postaja z vec tokovi (Rock Radio 128k MP3 in »HQ« 160k AAC): samo najboljsi tok (lastnik, 23. 10. 2026).
        return OsPravila.najboljsiTokovi(vnosi, { it.optString("name").trim() }, { it.optInt("bitrate") })
            .map {
                val opis = listOf(it.optString("country"), it.optString("tags").split(',').firstOrNull().orEmpty())
                    .filter { s -> s.isNotBlank() }.joinToString(" · ")
                Jamendo.Skladba(it.optString("stationuuid"), it.optString("name").trim(), opis, it.optString("favicon"),
                    it.optString("url_resolved"), it.optString("homepage"), radio = true,
                    mime = if (it.optInt("hls") == 1) MedijskiViri.MIME_HLS else "")
            }
            .filter { it.naslov.isNotBlank() }
    }

}
