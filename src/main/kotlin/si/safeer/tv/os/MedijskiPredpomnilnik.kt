package si.safeer.tv.os

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Trajni predpomnilnik medijskega centra: police (seznami skladb, postaj, videov) in naslovnice.
 * Ob odprtju se zadnji znani pogled pokaze takoj, sveze podatke pa aplikacija dobi v ozadju -
 * uporabnik ne gleda praznega zaslona in nima obcutka, da aplikacija ne deluje.
 */
object MedijskiPredpomnilnik {
    private const val NASLOVNIC_NAJVEC = 400
    private const val NASLOVNICA_VELJA_MS = 14L * 24 * 60 * 60 * 1000

    private fun odtis(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /**
     * Mapa polic. Ime nosi razlicico vsebine: "-2" od 3. 10. 2026, ko zasebni dodatki niso vec v skupnih policah in
     * mrezah (ZasebniDodatki) - stare police bi jih ob prvem odprtju se enkrat pokazale, zato jih zavrzemo.
     */
    private fun mapaSeznamov(c: Context): File {
        val stara = File(c.cacheDir, "mediji-police")
        if (stara.exists()) try { stara.deleteRecursively() } catch (_: Exception) { }
        return File(c.cacheDir, "mediji-police-2").apply { mkdirs() }
    }
    private fun mapaNaslovnic(c: Context) = File(c.cacheDir, "mediji-naslovnice").apply { mkdirs() }

    // ---------------------------------------------------------------- police

    fun skladbaVJson(s: Jamendo.Skladba): JSONObject = JSONObject()
        .put("id", s.id).put("naslov", s.naslov).put("izvajalec", s.izvajalec).put("slika", s.slika)
        .put("zvok", s.zvok).put("povezava", s.povezava).put("radio", s.radio).put("video", s.video)
        .put("mime", s.mime).put("streznik", s.streznik).put("kanal", s.kanal).put("mediaType", s.mediaType)
        .put("genres", JSONArray(s.genres)).put("year", s.year).put("season", s.season).put("episode", s.episode)
        .put("imdbId", s.imdbId).put("tmdbId", s.tmdbId).put("quality", s.quality).put("rating", s.rating)
        .put("language", s.language)

    fun skladbaIzJson(o: JSONObject): Jamendo.Skladba = Jamendo.Skladba(
        id = o.optString("id"), naslov = o.optString("naslov"), izvajalec = o.optString("izvajalec"),
        slika = o.optString("slika"), zvok = o.optString("zvok"), povezava = o.optString("povezava"),
        radio = o.optBoolean("radio"), video = o.optBoolean("video"), mime = o.optString("mime"),
        streznik = o.optString("streznik"), kanal = o.optString("kanal"), mediaType = o.optString("mediaType"),
        genres = o.optJSONArray("genres")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList(),
        year = o.optInt("year"), season = o.optInt("season"), episode = o.optInt("episode"),
        imdbId = o.optString("imdbId"), tmdbId = o.optString("tmdbId"), quality = o.optInt("quality"),
        rating = o.optDouble("rating", 0.0), language = o.optString("language"),
    )

    /** Shrani police razdelka: seznam (naslov police, video?, skladbe). */
    fun shraniPolice(c: Context, kljuc: String, police: List<Triple<String, Boolean, List<Jamendo.Skladba>>>) {
        try {
            val a = JSONArray()
            police.forEach { (naslov, video, skladbe) ->
                a.put(JSONObject().put("naslov", naslov).put("video", video)
                    .put("skladbe", JSONArray().apply { skladbe.forEach { put(skladbaVJson(it)) } }))
            }
            val datoteka = File(mapaSeznamov(c), odtis(kljuc) + ".json")
            val zacasna = File(datoteka.path + ".tmp")
            zacasna.writeText(JSONObject().put("cas", System.currentTimeMillis()).put("police", a).toString())
            zacasna.renameTo(datoteka)
        } catch (_: Exception) {
        }
    }

    /** Zadnje shranjene police razdelka ali null. */
    fun beriPolice(c: Context, kljuc: String): List<Triple<String, Boolean, List<Jamendo.Skladba>>>? = try {
        val datoteka = File(mapaSeznamov(c), odtis(kljuc) + ".json")
        if (!datoteka.isFile) null else {
            val a = JSONObject(datoteka.readText()).getJSONArray("police")
            List(a.length()) { i ->
                val p = a.getJSONObject(i)
                val s = p.optJSONArray("skladbe") ?: JSONArray()
                Triple(p.optString("naslov"), p.optBoolean("video"), List(s.length()) { skladbaIzJson(s.getJSONObject(it)) })
            }.takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) { null }

    fun pozabiPolice(c: Context, kljuc: String) {
        try { File(mapaSeznamov(c), odtis(kljuc) + ".json").delete() } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- naslovnice

    fun naslovnica(c: Context, naslov: String): ByteArray? = try {
        val f = File(mapaNaslovnic(c), odtis(naslov))
        if (f.isFile && System.currentTimeMillis() - f.lastModified() < NASLOVNICA_VELJA_MS) f.readBytes() else null
    } catch (_: Exception) { null }

    fun shraniNaslovnico(c: Context, naslov: String, bajti: ByteArray) {
        try {
            if (bajti.size > 1_500_000) return
            val mapa = mapaNaslovnic(c)
            File(mapa, odtis(naslov)).writeBytes(bajti)
            val datoteke = mapa.listFiles() ?: return
            if (datoteke.size > NASLOVNIC_NAJVEC) {
                datoteke.sortedBy { it.lastModified() }.take(datoteke.size - NASLOVNIC_NAJVEC).forEach { it.delete() }
            }
        } catch (_: Exception) {
        }
    }
}
