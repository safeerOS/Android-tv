package si.safeer.tv.os

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Odjemalec javnega protokola dodatkov Stremio (enako kot Safeer za Windows, stremio_dodatki.py):
 * manifest.json -> katalogi (/catalog/{tip}/{id}.json, iskanje /catalog/{tip}/{id}/search=....json)
 * -> meta (/meta/{tip}/{id}.json, epizode serij) -> tokovi (/stream/{tip}/{id}.json).
 *
 * Safeer ne prilaga nobenega dodatka; naslove vnese uporabnik. Kot v Stremiu tokove za izbran film ali
 * epizodo vprasamo VSE uporabnikove dodatke, ki ponujajo vir "stream" za ta tip (katalog ima lahko en
 * dodatek, tokove drug). Predvajamo `url` (http/https); `externalUrl`/`ytId` odpre brskalnik; `infoHash`
 * odpre Magnet povezave. Brez obvodov DRM. Vsi klici so sinhroni - klicatelj jih pozene v niti.
 */
object Stremio {
    private const val PREDPONA = "stremio|"
    private const val CAS = 15_000

    data class Katalog(val dodatek: String, val imeDodatka: String, val tip: String, val id: String, val ime: String,
                       val iskanje: Boolean, val obvezni: List<String>,
                       /** Obvezni dodatni parametri s prvo ponujeno moznostjo (genre=Action), kot jih Stremio pokaze v Discover. */
                       val privzeti: List<Pair<String, String>> = emptyList()) {
        /** Ali katalog brez uporabnikovega filtra sploh vrne vsebino (vsi obvezni parametri imajo privzeto moznost). */
        val prikazen: Boolean get() = obvezni.all { o -> privzeti.any { it.first == o } }
    }

    data class Manifest(val osnova: String, val ime: String, val viri: Set<String>, val tipi: Set<String>,
                        val predpone: List<String>, val katalogi: List<Katalog>)

    data class Epizoda(val id: String, val ime: String, val sezona: Int, val epizoda: Int)

    data class Tok(val vrsta: String, val url: String, val ime: String, val opis: String, val dodatek: String,
                   /** Torrent: katera datoteka (fileIdx), -1 = najvecji video. */
                   val datoteka: Int = -1,
                   /** `behaviorHints.proxyHeaders.request`: glave, ki jih tok zahteva (User-Agent, Referer, Origin, Cookie ...). */
                   val glave: Map<String, String> = emptyMap())

    private val manifesti = ConcurrentHashMap<String, Manifest>()

    fun osnova(naslov: String): String {
        var n = naslov.trim()
        if (n.startsWith("stremio://")) n = "https://" + n.removePrefix("stremio://")
        if (n.endsWith("/manifest.json")) n = n.removeSuffix("/manifest.json")
        return n.trimEnd('/')
    }

    private fun json(url: String): JSONObject? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = CAS; c.readTimeout = CAS
            c.setRequestProperty("User-Agent", "Safeer-Predvajalnik/1.0 (+https://safeer.si)")
            c.setRequestProperty("Accept", "application/json")
            try {
                if (c.responseCode !in 200..299) return null
                JSONObject(c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) })
            } finally { c.disconnect() }
        } catch (_: Exception) { null }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun manifest(naslov: String): Manifest? {
        val o = osnova(naslov)
        manifesti[o]?.let { return it }
        val m = json("$o/manifest.json") ?: return null
        val ime = m.optString("name").ifBlank { o }
        val viri = mutableSetOf<String>()
        m.optJSONArray("resources")?.let { a -> for (i in 0 until a.length()) {
            val r = a.opt(i); viri += if (r is JSONObject) r.optString("name") else r.toString() } }
        val tipi = mutableSetOf<String>()
        m.optJSONArray("types")?.let { a -> for (i in 0 until a.length()) tipi += a.optString(i) }
        val predpone = mutableListOf<String>()
        m.optJSONArray("idPrefixes")?.let { a -> for (i in 0 until a.length()) predpone += a.optString(i) }
        val katalogi = mutableListOf<Katalog>()
        m.optJSONArray("catalogs")?.let { a -> for (i in 0 until a.length()) {
            val k = a.optJSONObject(i) ?: continue
            val tip = k.optString("type"); val id = k.optString("id")
            if (tip.isBlank() || id.isBlank()) continue
            val podprti = mutableSetOf<String>(); val obvezni = mutableListOf<String>()
            val privzeti = mutableListOf<Pair<String, String>>()
            k.optJSONArray("extra")?.let { e -> for (j in 0 until e.length()) {
                val x = e.optJSONObject(j) ?: continue
                val imeX = x.optString("name")
                podprti += imeX
                if (x.optBoolean("isRequired")) {
                    obvezni += imeX
                    // Stremio v Discover obvezni parameter nastavi na prvo moznost (npr. zvrst): enako tu.
                    x.optJSONArray("options")?.optString(0)?.takeIf { it.isNotBlank() }?.let { privzeti += imeX to it }
                } } }
            k.optJSONArray("extraSupported")?.let { e -> for (j in 0 until e.length()) podprti += e.optString(j) }
            k.optJSONArray("extraRequired")?.let { e -> for (j in 0 until e.length()) obvezni += e.optString(j) }
            katalogi += Katalog(o, ime, tip, id, k.optString("name").ifBlank { id }, "search" in podprti, obvezni.distinct(), privzeti)
        } }
        return Manifest(o, ime, viri, tipi, predpone, katalogi).also { manifesti[o] = it }
    }

    /** Ime dodatka, ce je manifest ze nalozen (brez omrezja - za glavno nit). */
    fun imeIzPredpomnilnika(naslov: String): String? = manifesti[osnova(naslov)]?.ime

    fun jeEnota(s: Jamendo.Skladba) = s.id.startsWith(PREDPONA)
    /** (osnova kataloskega dodatka, tip, id vnosa) iz id-ja kartice. */
    fun razstavi(s: Jamendo.Skladba): Triple<String, String, String>? {
        val d = s.id.removePrefix(PREDPONA).split('|', limit = 3)
        return if (d.size == 3) Triple(d[2].substringBefore('#'), d[0], d[1]) else null
    }
    fun jeSerija(s: Jamendo.Skladba) = razstavi(s)?.second == "series"

    private fun vnos(m: JSONObject, osnova: String): Jamendo.Skladba? {
        val id = m.optString("id").ifBlank { return null }
        val tip = m.optString("type").ifBlank { "movie" }
        val leto = m.optString("releaseInfo").ifBlank { m.optString("year") }
        return Jamendo.Skladba(PREDPONA + tip + "|" + id + "|" + osnova, m.optString("name").ifBlank { id }, leto,
            m.optString("poster"), "", "$osnova/meta/${enc(tip)}/${enc(id)}.json", video = true,
            // FILM / SERIJA za oznako na kartici in za filter Filmi | Serije (SpletniVir.vrstaVsebine).
            mediaType = when (tip) { "movie" -> "movie"; "series" -> "tvseries"; else -> "" },
            year = leto.take(4).toIntOrNull() ?: 0)
    }

    /** Stran kataloga (`skip` = koliko vnosov preskociti, kot v Stremiu); obvezni parametri s privzeto moznostjo, iskanje kot `search=`. */
    fun katalog(k: Katalog, iskanje: String = "", skip: Int = 0): List<Jamendo.Skladba> {
        var pot = "${k.dodatek}/catalog/${enc(k.tip)}/${enc(k.id)}"
        val dodatno = (if (iskanje.isNotBlank()) listOf("search" to iskanje) else emptyList()) +
            k.privzeti.filter { it.first != "search" && (iskanje.isBlank() || it.first in k.obvezni) } +
            (if (skip > 0) listOf("skip" to skip.toString()) else emptyList())
        if (dodatno.isNotEmpty()) pot += "/" + dodatno.joinToString("&") { enc(it.first) + "=" + enc(it.second) }
        val d = json("$pot.json") ?: return emptyList()
        val a = d.optJSONArray("metas") ?: d.optJSONArray("metasDetailed") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { m -> vnos(m, k.dodatek) } }
    }

    /** Javni katalog metapodatkov Stremia (Cinemeta: filmi in serije po priljubljenosti, epizode) - samo
     *  metapodatki, tokove dajo uporabnikovi dodatki. Isti vir, kot ga Stremio sam kaze v Discover. */
    const val CINEMETA = "https://v3-cinemeta.strem.io/manifest.json"

    /**
     * Uporabnikovi dodatki + javni katalog Cinemeta (kot v Stremiu, kjer je Cinemeta vedno namescena): ko dodas
     * dodatke Stremio, so filmi in serije na voljo, uporabnik ne raziskuje, kateri dodatek je katalog (Matej,
     * 1. 10. 2026). Uporabnikovi katalogi so pred Cinemeto.
     */
    fun zKatalogom(naslovi: List<String>): List<String> {
        if (naslovi.isEmpty()) return naslovi
        val cinemeta = osnova(CINEMETA)
        return if (naslovi.any { osnova(it) == cinemeta }) naslovi else naslovi + CINEMETA
    }

    /** Katalogi za prikaz (filmi, nato serije); brez tistih, ki brez filtra ne vrnejo nicesar. */
    fun prikazniKatalogi(naslovi: List<String>): List<Katalog> = naslovi.mapNotNull { manifest(it) }
        .flatMap { m -> m.katalogi.filter { it.prikazen && (it.tip == "movie" || it.tip == "series") } }
        .sortedBy { if (it.tip == "movie") 0 else 1 }

    /** Katalogi TV kanalov v zivo (tip "tv") iz uporabnikovih dodatkov - gredo v razdelek TV v zivo (Matej, 1. 10. 2026). */
    fun katalogiTv(naslovi: List<String>): List<Katalog> = naslovi.mapNotNull { manifest(it) }
        .flatMap { m -> m.katalogi.filter { it.prikazen && it.tip == "tv" } }

    /** Iskanje po imenu v vseh katalogih z iskanjem (vzporedno klice klicatelj prek niti). */
    fun isci(naslovi: List<String>, beseda: String): List<Jamendo.Skladba> = naslovi.mapNotNull { manifest(it) }
        .flatMap { m -> m.katalogi.filter { it.iskanje && (it.tip == "movie" || it.tip == "series") } }
        .distinctBy { it.dodatek + it.tip }
        .flatMap { k -> katalog(k, beseda).take(20) }

    fun epizode(s: Jamendo.Skladba): List<Epizoda> {
        val (osnova, tip, id) = razstavi(s) ?: return emptyList()
        val m = json("$osnova/meta/${enc(tip)}/${enc(id)}.json")?.optJSONObject("meta") ?: return emptyList()
        val v = m.optJSONArray("videos") ?: return emptyList()
        return (0 until v.length()).mapNotNull { i ->
            val x = v.optJSONObject(i) ?: return@mapNotNull null
            val vid = x.optString("id").ifBlank { return@mapNotNull null }
            Epizoda(vid, x.optString("title").ifBlank { x.optString("name") }, x.optInt("season"), x.optInt("episode"))
        }.filter { it.sezona > 0 || it.epizoda > 0 }.sortedWith(compareBy({ it.sezona }, { it.epizoda }))
    }

    /** Tokovi za film ali epizodo iz vseh dodatkov, ki ponujajo vir "stream" za ta tip in predpono id-ja. */
    fun tokovi(naslovi: List<String>, tip: String, id: String): List<Tok> = naslovi.mapNotNull { manifest(it) }
        .filter { m -> "stream" in m.viri && (m.tipi.isEmpty() || tip in m.tipi) &&
            (m.predpone.isEmpty() || m.predpone.any { id.startsWith(it) }) }
        .flatMap { m ->
            val d = json("${m.osnova}/stream/${enc(tip)}/${enc(id)}.json") ?: return@flatMap emptyList<Tok>()
            val a = d.optJSONArray("streams") ?: JSONArray()
            (0 until a.length()).mapNotNull { i ->
                val s = a.optJSONObject(i) ?: return@mapNotNull null
                val ime = s.optString("name").ifBlank { m.ime }
                val opis = s.optString("title").ifBlank { s.optString("description") }
                // proxyHeaders.request (SDK: behaviorHints): glave, brez katerih streznik toka ne odgovori.
                val glave = LinkedHashMap<String, String>()
                s.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { h ->
                    for (k in h.keys()) { val v = h.optString(k); if (k.isNotBlank() && v.isNotBlank()) glave[k] = v }
                }
                when {
                    s.optString("url").startsWith("http") -> Tok("url", s.optString("url"), ime, opis, m.ime, glave = glave)
                    s.optString("externalUrl").isNotBlank() -> Tok("zunanji", s.optString("externalUrl"), ime, opis, m.ime)
                    s.optString("ytId").isNotBlank() -> Tok("zunanji", "https://www.youtube.com/watch?v=" + s.optString("ytId"), ime, opis, m.ime)
                    s.optString("infoHash").isNotBlank() -> {
                        // Sledilniki iz `sources` ("tracker:udp://..."), da racunalnik hitreje najde vire.
                        val viri = s.optJSONArray("sources")
                        val tr = (0 until (viri?.length() ?: 0)).mapNotNull { viri?.optString(it) }
                            .filter { it.startsWith("tracker:") }.joinToString("") { "&tr=" + enc(it.removePrefix("tracker:")) }
                        val ime2 = s.optJSONObject("behaviorHints")?.optString("filename").orEmpty()
                        Tok("torrent", "magnet:?xt=urn:btih:" + s.optString("infoHash") + (if (ime2.isNotBlank()) "&dn=" + enc(ime2) else "") + tr,
                            ime, opis, m.ime, s.optInt("fileIdx", -1))
                    }
                    else -> null
                }
            }
        }
}
