package si.safeer.tv.os

import org.json.JSONArray
import org.json.JSONObject

/**
 * Katalog YouTube Music brez prijave (lastnik 21. 11. 2026, ideja iz projekta Musike, licenca MIT, avtor Ernani1234:
 * iskanje skladb, albumov in izvajalcev ter album, predvajan po vrsti). Napisano na novo, brez prevzete kode in brez
 * Nodea ali yt-dlp: neposredno InnerTube (odjemalec WEB_REMIX, odgovor v angleščini, da so vrste »Song«, »Album«,
 * »Artist« stalne). Isto na Linuxu: core/youtube_glasba.py.
 *
 * Razclenjevanje je loceno od omrezja, da ga lahko preizkusimo na pravih odgovorih (tests/YoutubeGlasbaTest.kt).
 */
object YoutubeGlasba {
    enum class Vrsta { SKLADBA, ALBUM, IZVAJALEC }

    /** [id]: videoId (skladba) ali browseId (album »MPRE…«, izvajalec »UC…«). [podnaslov]: npr. »Nirvana • 1993«. */
    data class Element(val vrsta: Vrsta, val id: String, val naslov: String, val podnaslov: String, val slika: String = "")
    data class AlbumSkladba(val id: String, val naslov: String, val sekund: Int)
    data class Album(val id: String, val naslov: String, val izvajalec: String, val leto: String, val skladbe: List<AlbumSkladba>)

    const val NAJVEC_ELEMENTOV = 30
    private const val ODJEMALEC = "WEB_REMIX"
    private const val RAZLICICA = "1.20261110.01.00"
    private val ID_ALBUMA = Regex("^MPRE[\\w-]{6,60}$")
    private val ID_IZVAJALCA = Regex("^UC[\\w-]{22}$")
    private val ID_POSNETKA = Regex("^[\\w-]{11}$")

    fun jeIdAlbuma(id: String): Boolean = ID_ALBUMA.matches(id)

    private fun kontekst() = JSONObject().put("client", JSONObject()
        .put("clientName", ODJEMALEC).put("clientVersion", RAZLICICA).put("hl", "en"))

    fun zahtevaIskanja(q: String): String = JSONObject().put("context", kontekst()).put("query", q.take(200)).toString()

    fun zahtevaAlbuma(id: String): String = JSONObject().put("context", kontekst()).put("browseId", id).toString()

    private fun besedilo(o: JSONObject?): String {
        val runs = o?.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        return sb.toString()
    }

    /** Vsi objekti `musicResponsiveListItemRenderer` odgovora po vrsti. */
    private fun vrstice(d: JSONObject): List<JSONObject> {
        val izhod = ArrayList<JSONObject>()
        fun hodi(x: Any?, globina: Int) {
            if (globina > 40) return
            when (x) {
                is JSONArray -> for (i in 0 until x.length()) hodi(x.opt(i), globina + 1)
                is JSONObject -> {
                    x.optJSONObject("musicResponsiveListItemRenderer")?.let { izhod += it; return }
                    val k = x.keys()
                    while (k.hasNext()) hodi(x.opt(k.next()), globina + 1)
                }
            }
        }
        hodi(d, 0)
        return izhod
    }

    private fun stolpec(v: JSONObject, i: Int): String =
        besedilo(v.optJSONArray("flexColumns")?.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"))

    /** Naslovnica vrstice (najvecja slicica, samo https). */
    private fun slikaVrstice(v: JSONObject): String {
        val s = v.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails") ?: return ""
        val url = s.optJSONObject(s.length() - 1)?.optString("url").orEmpty()
        return if (url.startsWith("https://")) url else ""
    }

    /** Skladbe, albumi in izvajalci iz odgovora iskanja; ostalo (videi, epizode, seznami, podcasti, profili) izpusti. */
    fun razclenIskanje(d: JSONObject): List<Element> {
        val izhod = ArrayList<Element>()
        for (v in vrstice(d)) {
            if (izhod.size >= NAJVEC_ELEMENTOV) break
            val naslov = stolpec(v, 0).trim()
            val deli = stolpec(v, 1).split(" • ").map { it.trim() }.filter { it.isNotEmpty() }
            if (naslov.isEmpty() || deli.isEmpty()) continue
            val vrsta = deli.first()
            val podnaslov = deli.drop(1).joinToString(" • ")
            val brskanje = v.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
            val posnetek = v.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
            when {
                vrsta == "Song" && ID_POSNETKA.matches(posnetek) -> izhod += Element(Vrsta.SKLADBA, posnetek, naslov, podnaslov)
                (vrsta == "Album" || vrsta == "EP" || vrsta == "Single") && ID_ALBUMA.matches(brskanje) ->
                    izhod += Element(Vrsta.ALBUM, brskanje, naslov, podnaslov, slikaVrstice(v))
                vrsta == "Artist" && ID_IZVAJALCA.matches(brskanje) -> izhod += Element(Vrsta.IZVAJALEC, brskanje, naslov, podnaslov)
            }
        }
        return izhod
    }

    /** Album z vsemi skladbami po vrsti (skladbe brez predvajalnega id-ja so nerazpolozljive in jih ni). */
    fun razclenAlbum(d: JSONObject, id: String): Album? {
        val glava = ArrayList<JSONObject>()
        fun poisci(x: Any?, globina: Int) {
            if (globina > 40 || glava.isNotEmpty()) return
            when (x) {
                is JSONArray -> for (i in 0 until x.length()) poisci(x.opt(i), globina + 1)
                is JSONObject -> {
                    x.optJSONObject("musicResponsiveHeaderRenderer")?.let { glava += it; return }
                    val k = x.keys()
                    while (k.hasNext()) poisci(x.opt(k.next()), globina + 1)
                }
            }
        }
        poisci(d, 0)
        val g = glava.firstOrNull()
        val naslov = besedilo(g?.optJSONObject("title")).trim()
        val skladbe = vrstice(d).mapNotNull { v ->
            val vid = v.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
            val ime = stolpec(v, 0).trim()
            if (!ID_POSNETKA.matches(vid) || ime.isEmpty()) return@mapNotNull null
            val cas = besedilo(v.optJSONArray("fixedColumns")?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text"))
            AlbumSkladba(vid, ime, YoutubeKanalVrsta.trajanje(cas) ?: 0)
        }
        if (naslov.isEmpty() || skladbe.isEmpty()) return null
        // Podnaslov glave: »Album • 1993«; izvajalec je v vrstici pod naslovom (straplineTextOne).
        val izvajalec = besedilo(g?.optJSONObject("straplineTextOne")).trim()
        val leto = besedilo(g?.optJSONObject("subtitle")).split(" • ").map { it.trim() }.lastOrNull { Regex("^(19|20)\\d\\d$").matches(it) }.orEmpty()
        return Album(id, naslov, izvajalec, leto, skladbe)
    }
}
