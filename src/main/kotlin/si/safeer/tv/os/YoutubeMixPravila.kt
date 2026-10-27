package si.safeer.tv.os

import org.json.JSONArray
import org.json.JSONObject

/**
 * Lastnik 27. 10. 2026 (testni telefon): »ko se glasba konca, se predvajalnik ugasne in skrije - moral bi predvajati naprej
 * na naslednjo glasbo«. Glasba z YouTuba se po zadnji skladbi v vrsti (ali na Naprej) nadaljuje s samodejnim mixom sorodnih
 * skladb (YouTube »RD<id>«, isti kot ga da YouTube Music). Ciste odlocitve in razclenjevanje odgovora youtubei/v1/next brez
 * omrezja (preizkus: tests/YoutubeMixPravilaTest.kt, v tests/run_zvocnik_cast_tests.sh).
 */
object YoutubeMixPravila {
    /** Koliko skladb mixa dodamo naenkrat (YouTube vrne okoli 25). */
    const val NAJVEC = 25
    private val ID = Regex("^[A-Za-z0-9_-]{11}$")

    data class Vnos(val id: String, val naslov: String, val kanal: String)

    /** Ali se po koncu vrste (ali na Naprej) samodejno nadaljuje z mixom: samo glasba z YouTuba, ko naslednje ni. */
    fun samodejnoNaprej(imaNaslednjo: Boolean, jeYoutube: Boolean, video: Boolean): Boolean = !imaNaslednjo && jeYoutube && !video

    /** Telo zahteve youtubei/v1/next (odjemalec WEB, kot ga poslje stran ob »Mix«). */
    fun zahteva(videoId: String, razlicica: String = "2.20250101.00.00"): String =
        JSONObject().put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "WEB").put("clientVersion", razlicica).put("hl", "en")))
            .put("videoId", videoId).put("playlistId", "RD$videoId").toString()

    /** Skladbe mixa iz odgovora (playlistPanelVideoRenderer), brez semena in skladb, ki so ze v vrsti, brez podvojenih. */
    fun izMixa(d: JSONObject, seme: String, zeVVrsti: Set<String>): List<Vnos> {
        val izid = LinkedHashMap<String, Vnos>()
        fun besedilo(o: JSONObject?): String = o?.optString("simpleText")?.takeIf { it.isNotBlank() }
            ?: o?.optJSONArray("runs")?.let { r -> (0 until r.length()).joinToString("") { r.optJSONObject(it)?.optString("text").orEmpty() } }.orEmpty()
        fun hodi(x: Any?, globina: Int) {
            if (globina > 60 || izid.size >= NAJVEC) return
            when (x) {
                is JSONArray -> for (i in 0 until x.length()) hodi(x.opt(i), globina + 1)
                is JSONObject -> {
                    val v = x.optJSONObject("playlistPanelVideoRenderer")
                    if (v != null) {
                        val id = v.optString("videoId")
                        val naslov = besedilo(v.optJSONObject("title")).trim()
                        val kanal = besedilo(v.optJSONObject("shortBylineText") ?: v.optJSONObject("longBylineText")).trim()
                        if (ID.matches(id) && id != seme && id !in zeVVrsti && naslov.isNotBlank()) izid.putIfAbsent(id, Vnos(id, naslov, kanal))
                        return
                    }
                    val k = x.keys()
                    while (k.hasNext()) hodi(x.opt(k.next()), globina + 1)
                }
            }
        }
        hodi(d, 0)
        return izid.values.take(NAJVEC)
    }
}
