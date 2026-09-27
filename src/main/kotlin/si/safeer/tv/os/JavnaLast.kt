package si.safeer.tv.os

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Filmi z Internet Archive, ki imajo izrecno oznaceno licenco javne lasti. */
object JavnaLast {
    private const val PREDPONA = "archive:"
    private const val ROK_MS = 8_000
    private const val POIZVEDBA = "collection:feature_films AND mediatype:movies AND licenseurl:*publicdomain*"
    private val sumljivo = Regex("torrent|\\b(dvd|br|web|hd|bd)?rip\\b|\\bhdcam\\b|\\bx26[45]\\b|\\bxvid\\b|\\bcam\\b", RegexOption.IGNORE_CASE)
    private val videoFormati = setOf("h.264", "512Kb MPEG4", "MPEG4", "h.264 IA")

    fun jeEnota(s: Jamendo.Skladba) = s.id.startsWith(PREDPONA)

    /** Najbolj priljubljeni filmi ali iskanje po naslovu; brez izrecne licence ni zadetka. */
    fun isci(beseda: String = ""): List<Jamendo.Skladba> {
        val cista = beseda.replace(Regex("[^\\p{L}\\p{N} ]+"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val q = POIZVEDBA + if (cista.isNotBlank()) " AND title:($cista)" else ""
        val parametri = listOf(
            "q" to q,
            "fl[]" to "identifier", "fl[]" to "title", "fl[]" to "year",
            "fl[]" to "description", "fl[]" to "format",
            "sort[]" to "downloads desc", "rows" to "40", "output" to "json",
        ).joinToString("&") { (k, v) -> kodiraj(k) + "=" + kodiraj(v) }
        val dokumenti = JSONObject(beri("https://archive.org/advancedsearch.php?$parametri"))
            .optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        return (0 until dokumenti.length()).mapNotNull { i -> kartica(dokumenti.optJSONObject(i)) }
    }

    private fun kartica(v: JSONObject?): Jamendo.Skladba? {
        if (v == null) return null
        val ident = v.optString("identifier").trim()
        val naslov = besedilo(v.opt("title")).trim()
        if (ident.isBlank() || naslov.isBlank() || sumljivo.containsMatchIn("$naslov $ident")) return null
        val formati = when (val f = v.opt("format")) {
            is JSONArray -> (0 until f.length()).map { f.optString(it) }
            else -> listOf(f?.toString().orEmpty())
        }
        if (formati.none { it in videoFormati }) return null
        val leto = besedilo(v.opt("year")).take(4).toIntOrNull() ?: 0
        return Jamendo.Skladba(
            id = PREDPONA + ident,
            naslov = naslov.take(200),
            izvajalec = "Internet Archive",
            slika = "https://archive.org/services/img/${kodirajPot(ident)}",
            zvok = "",
            povezava = "https://archive.org/details/${kodirajPot(ident)}",
            video = true,
            mediaType = "Movie",
            year = leto,
        )
    }

    /** Ob predvajanju se enkrat preveri licenca in izbere najvisji MP4 do 1080p. */
    fun razresi(s: Jamendo.Skladba): Jamendo.Skladba? {
        if (!jeEnota(s)) return null
        val ident = s.id.removePrefix(PREDPONA)
        val podatki = JSONObject(beri("https://archive.org/metadata/${kodirajPot(ident)}"))
        val licenca = besedilo(podatki.optJSONObject("metadata")?.opt("licenseurl"))
        if (!licenca.contains("publicdomain", ignoreCase = true)) return null
        val datoteke = podatki.optJSONArray("files") ?: return null
        val kandidati = (0 until datoteke.length()).mapNotNull { i ->
            val f = datoteke.optJSONObject(i) ?: return@mapNotNull null
            val ime = f.optString("name")
            if (!ime.endsWith(".mp4", ignoreCase = true)) return@mapNotNull null
            val visina = f.optInt("height", f.optString("height").toIntOrNull() ?: 0)
            if (visina > 1080) null else visina to ime
        }
        val (visina, ime) = kandidati.maxByOrNull { it.first } ?: return null
        return s.copy(
            zvok = "https://archive.org/download/${kodirajPot(ident)}/${kodirajDatoteko(ime)}",
            mime = "video/mp4",
            quality = visina,
        )
    }

    private fun besedilo(v: Any?): String = when (v) {
        is JSONArray -> (0 until v.length()).joinToString(" ") { v.optString(it) }
        null, JSONObject.NULL -> ""
        else -> v.toString()
    }

    private fun kodiraj(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun kodirajPot(s: String) = kodiraj(s).replace("+", "%20")
    private fun kodirajDatoteko(s: String) = s.split('/').joinToString("/") { kodirajPot(it) }

    private fun beri(naslov: String): String {
        val povezava = URL(naslov).openConnection() as HttpURLConnection
        povezava.connectTimeout = ROK_MS
        povezava.readTimeout = ROK_MS
        povezava.setRequestProperty("User-Agent", "SafeerOS/1.0 (+https://safeer.si)")
        povezava.setRequestProperty("Accept", "application/json")
        try {
            if (povezava.responseCode != 200) throw java.io.IOException("HTTP ${povezava.responseCode}")
            return povezava.inputStream.bufferedReader().use { it.readText() }
        } finally {
            povezava.disconnect()
        }
    }
}
