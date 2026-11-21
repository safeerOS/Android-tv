import si.safeer.tv.os.YoutubeGlasba as G
import si.safeer.tv.os.YoutubeGlasba.Vrsta
import org.json.JSONObject
import java.io.File

private var napakGlasba = 0
private fun preveriGlasba(ime: String, ok: Boolean) { if (!ok) { napakGlasba++; println("FAIL $ime") } }

/** Preizkus na pravih odgovorih (pot v spremenljivki SAFEER_YTM_ISKANJE / SAFEER_YTM_ALBUM); brez njih le zahteve. */
fun main() {
    preveriGlasba("zahteva iskanja", JSONObject(G.zahtevaIskanja("Nirvana")).getString("query") == "Nirvana")
    preveriGlasba("zahteva odjemalec", JSONObject(G.zahtevaAlbuma("MPREb_x1234567")).getJSONObject("context").getJSONObject("client").getString("clientName") == "WEB_REMIX")
    System.getenv("SAFEER_YTM_ISKANJE")?.let { pot ->
        val r = G.razclenIskanje(JSONObject(File(pot).readText()))
        preveriGlasba("iskanje ima skladbe", r.any { it.vrsta == Vrsta.SKLADBA && it.id.length == 11 })
        preveriGlasba("iskanje ima album", r.any { it.vrsta == Vrsta.ALBUM && it.id.startsWith("MPRE") })
        preveriGlasba("iskanje ima izvajalca", r.any { it.vrsta == Vrsta.IZVAJALEC && it.id.startsWith("UC") && it.naslov == "Nirvana" })
        preveriGlasba("brez epizod in seznamov", r.none { it.naslov.contains("Baby", ignoreCase = true) && it.vrsta != Vrsta.SKLADBA })
        println("iskanje: " + r.take(4).joinToString { "${it.vrsta}:${it.naslov}[${it.podnaslov}]" })
    }
    System.getenv("SAFEER_YTM_ALBUM")?.let { pot ->
        val a = G.razclenAlbum(JSONObject(File(pot).readText()), "MPREb_fOBl6NeUrW5")
        preveriGlasba("album obstaja", a != null)
        if (a != null) {
            preveriGlasba("album naslov", a.naslov.startsWith("In Utero"))
            preveriGlasba("album 12 skladb po vrsti", a.skladbe.size == 12 && a.skladbe[0].naslov == "Serve The Servants" && a.skladbe[11].naslov == "All Apologies")
            preveriGlasba("album trajanje", a.skladbe[0].sekund == 218 && a.skladbe[2].sekund == 282)
            preveriGlasba("album leto", a.leto == "1993")
            println("album: ${a.naslov} | ${a.izvajalec} | ${a.leto} | ${a.skladbe.size}")
        }
    }
    println(if (napakGlasba == 0) "YoutubeGlasbaTest: VSE V REDU" else "YoutubeGlasbaTest: $napakGlasba napak")
    if (napakGlasba > 0) kotlin.system.exitProcess(1)
}
