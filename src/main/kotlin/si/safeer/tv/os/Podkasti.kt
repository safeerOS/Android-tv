package si.safeer.tv.os

import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Podkasti: iskanje oddaj prek imenika iTunes (javen, brez kljuca; preverjeno 22. 9. 2026, ~1 s) in
 * epizode iz RSS oddaje (odprt standard - isti RSS lahko uporabnik doda tudi kot svoj vir).
 *
 * Oddaja je [Jamendo.Skladba] z id "podkast:<rss>" in praznim zvokom: klik odpre seznam epizod.
 */
object Podkasti {
    private const val PREDPONA = "podkast:"

    fun jeOddaja(s: Jamendo.Skladba) = s.id.startsWith(PREDPONA)

    fun oddaja(rss: String, ime: String, avtor: String, slika: String) =
        Jamendo.Skladba(PREDPONA + rss, ime, avtor, slika, "", rss)

    fun isci(beseda: String, stevilo: Int = 12): List<Jamendo.Skladba> {
        val q = beseda.trim()
        if (q.length < 2) return emptyList()
        val r = JSONObject(beri("https://itunes.apple.com/search?media=podcast&limit=$stevilo&term=" +
            URLEncoder.encode(q, "UTF-8"))).optJSONArray("results") ?: return emptyList()
        return (0 until r.length()).map { r.getJSONObject(it) }.mapNotNull { o ->
            val rss = o.optString("feedUrl")
            if (!rss.startsWith("https://")) return@mapNotNull null
            oddaja(rss, o.optString("collectionName"), o.optString("artistName"),
                o.optString("artworkUrl600").ifBlank { o.optString("artworkUrl100") })
        }.filter { it.naslov.isNotBlank() }
    }

    /** Ime oddaje in epizode (najnovejse prve, kot v RSS), samo zvok ali video po https. */
    fun epizode(rss: String, najvec: Int = 100): Pair<String, List<Jamendo.Skladba>> {
        val p = android.util.Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(beri(rss, 20_000).reader())
        var oddaja = ""
        var slikaOddaje = ""
        val izid = ArrayList<Jamendo.Skladba>()
        var vEpizodi = false
        var naslov = ""; var zvok = ""; var vrsta = ""; var slika = ""; var guid = ""
        var dogodek = p.eventType
        while (dogodek != XmlPullParser.END_DOCUMENT && izid.size < najvec) {
            if (dogodek == XmlPullParser.START_TAG) {
                when (p.name) {
                    "item" -> { vEpizodi = true; naslov = ""; zvok = ""; vrsta = ""; slika = ""; guid = "" }
                    "title" -> p.nextText().trim().let { if (vEpizodi) naslov = it else if (oddaja.isBlank()) oddaja = it }
                    "guid" -> if (vEpizodi) guid = p.nextText().trim()
                    "enclosure" -> if (vEpizodi) { zvok = p.getAttributeValue(null, "url").orEmpty(); vrsta = p.getAttributeValue(null, "type").orEmpty() }
                    "itunes:image" -> p.getAttributeValue(null, "href").orEmpty().let { if (vEpizodi) slika = it else if (slikaOddaje.isBlank()) slikaOddaje = it }
                }
            } else if (dogodek == XmlPullParser.END_TAG && p.name == "item") {
                vEpizodi = false
                if (zvok.startsWith("https://") && naslov.isNotBlank()) {
                    val video = vrsta.startsWith("video/")
                    izid += Jamendo.Skladba("epizoda:" + guid.ifBlank { zvok }, naslov, oddaja, slika.ifBlank { slikaOddaje }, zvok, rss,
                        video = video, mime = if (vrsta.startsWith("audio/") || video) vrsta else "")
                }
            }
            dogodek = p.next()
        }
        return oddaja to izid
    }

    /** Ali je na naslovu RSS podkasta (za dodajanje vira); vrne ime oddaje ali null. */
    fun imeOddaje(rss: String): String? = try {
        val (ime, ep) = epizode(rss, 3)
        if (ep.isNotEmpty()) ime.ifBlank { URL(rss).host } else null
    } catch (_: Exception) { null }

    private fun beri(naslov: String, beriMs: Int = 10_000): String {
        val p = URL(naslov).openConnection() as HttpURLConnection
        p.connectTimeout = 5_000; p.readTimeout = beriMs
        p.setRequestProperty("User-Agent", "SafeerOS/0.4 (+https://safeer.si)")
        try {
            if (p.responseCode != 200) throw java.io.IOException("HTTP ${p.responseCode}")
            return p.inputStream.bufferedReader().use { it.readText() }
        } finally { p.disconnect() }
    }
}
