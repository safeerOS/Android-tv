package si.safeer.tv.os

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Lokalna zgodovina Safeer Media. Brez oblaka: polozaj in izbrani vir ostaneta na napravi. */
object MediaNapredek {
    private const val PREF = "safeer_media_napredek"
    private const val KEY = "vnosi"
    data class Vnos(val skladba: Jamendo.Skladba, val polozaj: Long, val trajanje: Long, val cas: Long)

    private fun kljuc(s: Jamendo.Skladba) = s.naslov.lowercase().replace(Regex("\\b(19|20)\\d{2}\\b"), "")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    fun zapisi(c: Context, s: Jamendo.Skladba, polozaj: Long, trajanje: Long) {
        val vrsta = SpletniVir.vrstaVsebine(s)
        val jeSerijaAliFilm = vrsta == SpletniVir.SERIJA || vrsta == SpletniVir.FILM || s.season > 0 || s.episode > 0
        // Kratkih glasbenih videospotov, pesmi in vsebin pod 10 min ne vnasamo med filme in serije za nadaljevanje ogleda
        if (!s.video || vrsta == SpletniVir.VIDEOSPOT || s.mediaType.equals("MusicVideo", ignoreCase = true) ||
            (!jeSerijaAliFilm && trajanje < 600_000L) || polozaj < 15_000 || trajanje <= 0) return
        val k = kljuc(s)
        val vsi = preberiJson(c).filter { it.optString("k") != k }.toMutableList()
        // Zadnjih ~95 % ne ponujamo kot "Nadaljuj"; ogled je prakticno koncan.
        if (polozaj < trajanje * 95 / 100) vsi.add(0, json(s, polozaj, trajanje))
        shrani(c, vsi.take(30))
    }

    fun seznam(c: Context): List<Vnos> = preberiJson(c).mapNotNull { o ->
        try {
            val sk = skladba(o.getJSONObject("s"))
            val trajanje = o.getLong("d")
            val vrsta = SpletniVir.vrstaVsebine(sk)
            val jeSerijaAliFilm = vrsta == SpletniVir.SERIJA || vrsta == SpletniVir.FILM || sk.season > 0 || sk.episode > 0
            if (vrsta == SpletniVir.VIDEOSPOT || sk.mediaType.equals("MusicVideo", ignoreCase = true) || (!jeSerijaAliFilm && trajanje < 600_000L)) null
            else Vnos(sk, o.getLong("p"), trajanje, o.optLong("t"))
        } catch (_: Exception) { null }
    }.sortedByDescending { it.cas }

    fun polozaj(c: Context, s: Jamendo.Skladba): Long = seznam(c).firstOrNull { kljuc(it.skladba) == kljuc(s) }?.polozaj ?: 0L

    fun odstrani(c: Context, s: Jamendo.Skladba) {
        val k = kljuc(s)
        val p = s.povezava
        val id = s.id
        shrani(c, preberiJson(c).filterNot { o ->
            if (o.optString("k") == k) return@filterNot true
            try {
                val sObj = o.getJSONObject("s")
                sObj.optString("u") == p || sObj.optString("id") == id ||
                    (sObj.optString("n").isNotBlank() && sObj.optString("n").equals(s.naslov, ignoreCase = true))
            } catch (_: Exception) { false }
        })
    }

    fun pocisti(c: Context) {
        c.getSharedPreferences(PREF, 0).edit().remove(KEY).apply()
    }

    private fun json(s: Jamendo.Skladba, p: Long, d: Long) = JSONObject().put("k", kljuc(s)).put("p", p).put("d", d)
        .put("t", System.currentTimeMillis()).put("s", JSONObject().put("id",s.id).put("n",s.naslov).put("a",s.izvajalec)
            .put("i",s.slika).put("z",s.zvok).put("u",s.povezava).put("r",s.radio).put("v",s.video).put("m",s.mime).put("st",s.streznik).put("ka",s.kanal).put("mt",s.mediaType))
    private fun skladba(o: JSONObject) = Jamendo.Skladba(o.optString("id"),o.optString("n"),o.optString("a"),o.optString("i"),o.optString("z"),o.optString("u"),o.optBoolean("r"),o.optBoolean("v"),o.optString("m"),o.optString("st"),o.optString("ka"),mediaType=o.optString("mt"))
    private fun preberiJson(c: Context): List<JSONObject> = try { val a=JSONArray(c.getSharedPreferences(PREF,0).getString(KEY,"[]")); (0 until a.length()).mapNotNull{a.optJSONObject(it)} } catch (_:Exception){ emptyList() }
    private fun shrani(c: Context, l: List<JSONObject>) { val a=JSONArray(); l.forEach{a.put(it)}; c.getSharedPreferences(PREF,0).edit().putString(KEY,a.toString()).apply() }
}
