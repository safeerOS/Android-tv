package si.safeer.tv.os

import org.json.JSONObject

private var napak = 0
private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

private fun vnos(id: String, naslov: String, kanal: String) =
    """{"playlistPanelVideoRenderer":{"videoId":"$id","title":{"simpleText":"$naslov"},"shortBylineText":{"runs":[{"text":"$kanal"}]}}}"""

fun main() {
    println("Samodejni mix po koncu vrste (lastnik 27. 10. 2026)")
    val P = YoutubeMixPravila
    preveriEnako("zadnja glasba z YouTuba: naprej z mixom", true, P.samodejnoNaprej(imaNaslednjo = false, jeYoutube = true, video = false))
    preveriEnako("naslednja obstaja: vrsta, brez mixa", false, P.samodejnoNaprej(true, true, false))
    preveriEnako("video: brez mixa", false, P.samodejnoNaprej(false, true, true))
    preveriEnako("ni YouTube: konec kot doslej", false, P.samodejnoNaprej(false, false, false))

    val z = JSONObject(P.zahteva("dQw4w9WgXcQ"))
    preveriEnako("zahteva: seznam RD<id>", "RDdQw4w9WgXcQ", z.optString("playlistId"))
    preveriEnako("zahteva: videoId", "dQw4w9WgXcQ", z.optString("videoId"))
    preveriEnako("zahteva: odjemalec WEB", "WEB", z.getJSONObject("context").getJSONObject("client").optString("clientName"))

    val odgovor = JSONObject("""{"contents":{"twoColumnWatchNextResults":{"playlist":{"playlist":{"contents":[
        ${vnos("dQw4w9WgXcQ", "Seme", "Kanal")},
        ${vnos("aaaaaaaaaa1", "Prva", "Izvajalec A")},
        ${vnos("bbbbbbbbbb2", "Druga", "Izvajalec B")},
        ${vnos("aaaaaaaaaa1", "Prva podvojena", "Izvajalec A")},
        ${vnos("cccccccccc3", "Ze v vrsti", "C")},
        ${vnos("neveljaven", "Slab id", "X")},
        ${vnos("ddddddddd_4", "", "Brez naslova")},
        {"playlistPanelVideoRenderer":{"videoId":"eeeeeeeee-5","title":{"runs":[{"text":"Pe"},{"text":"ta"}]},"longBylineText":{"simpleText":"E"}}}
    ]}}}}}""")
    val m = P.izMixa(odgovor, seme = "dQw4w9WgXcQ", zeVVrsti = setOf("cccccccccc3"))
    preveriEnako("brez semena, ze v vrsti, podvojenih, slabih id in brez naslova",
        listOf("aaaaaaaaaa1", "bbbbbbbbbb2", "eeeeeeeee-5"), m.map { it.id })
    preveriEnako("naslov in kanal", YoutubeMixPravila.Vnos("aaaaaaaaaa1", "Prva", "Izvajalec A"), m.first())
    preveriEnako("naslov iz runs, kanal iz longBylineText", YoutubeMixPravila.Vnos("eeeeeeeee-5", "Peta", "E"), m.last())
    preveriEnako("prazen odgovor: nic", 0, P.izMixa(JSONObject("{}"), "dQw4w9WgXcQ", emptySet()).size)

    println(if (napak == 0) "VSE OK" else "NAPAK: $napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
