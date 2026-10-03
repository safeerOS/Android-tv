package si.safeer.tv.os

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Lokalna zgodovina Safeer Media. Brez oblaka: polozaj in izbrani vir ostaneta na napravi. */
object MediaNapredek {
    private const val PREF = "safeer_media_napredek"
    private const val KEY = "vnosi"
    /** [naprava] = id naprave v Linku, s katere je datoteka (streznik datotek); prazno za splet in to napravo. */
    data class Vnos(val skladba: Jamendo.Skladba, val polozaj: Long, val trajanje: Long, val cas: Long, val naprava: String = "")

    private fun kljuc(s: Jamendo.Skladba) = s.naslov.lowercase().replace(Regex("\\b(19|20)\\d{2}\\b"), "")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** Videi s te naprave (kot VLC): zapomnimo si mesto ze od 1 min dolzine naprej. */
    private fun najkrajse(s: Jamendo.Skladba) = if (s.id.startsWith("krajevno:")) 60_000L else 600_000L

    fun zapisi(c: Context, s: Jamendo.Skladba, polozaj: Long, trajanje: Long, naprava: String = "") {
        // Ogled vsebine zasebnega dodatka se ne zapise (ZasebniDodatki): ni v Nadaljuj, Zate in ne gre drugi napravi.
        Stremio.pripravi(c)
        if (Stremio.jeZasebna(s)) return
        val vrsta = SpletniVir.vrstaVsebine(s)
        val jeSerijaAliFilm = vrsta == SpletniVir.SERIJA || vrsta == SpletniVir.FILM || s.season > 0 || s.episode > 0
        // Kratkih glasbenih videospotov, pesmi in vsebin pod 10 min ne vnasamo med filme in serije za nadaljevanje ogleda
        if (!s.video || s.radio || Stremio.jeVZivo(s) || s.id.startsWith("tv:") || vrsta == SpletniVir.VIDEOSPOT || s.mediaType.equals("MusicVideo", ignoreCase = true) ||
            (!jeSerijaAliFilm && trajanje < najkrajse(s)) || polozaj < 15_000 || trajanje <= 0) return
        val k = kljuc(s)
        val vsi = preberiJson(c).filter { it.optString("k") != k }.toMutableList()
        // Zadnjih ~95 % ne ponujamo kot "Nadaljuj"; ogled je prakticno koncan.
        if (polozaj < trajanje * 95 / 100) vsi.add(0, json(s, polozaj, trajanje, naprava))
        shrani(c, vsi.take(30))
    }

    fun seznam(c: Context): List<Vnos> = preberiJson(c).also { Stremio.pripravi(c) }.mapNotNull { o ->
        try {
            val sk = skladba(o.getJSONObject("s"))
            if (Stremio.jeZasebna(sk)) return@mapNotNull null
            val trajanje = o.getLong("d")
            val vrsta = SpletniVir.vrstaVsebine(sk)
            val jeSerijaAliFilm = vrsta == SpletniVir.SERIJA || vrsta == SpletniVir.FILM || sk.season > 0 || sk.episode > 0
            if (!sk.video || sk.radio || Stremio.jeVZivo(sk) || sk.id.startsWith("tv:") || vrsta == SpletniVir.VIDEOSPOT || sk.mediaType.equals("MusicVideo", ignoreCase = true) || (!jeSerijaAliFilm && trajanje < najkrajse(sk))) null
            else Vnos(sk, o.getLong("p"), trajanje, o.optLong("t"), o.optString("np"))
        } catch (_: Exception) { null }
    }.sortedByDescending { it.cas }

    fun polozaj(c: Context, s: Jamendo.Skladba): Long = seznam(c).firstOrNull { kljuc(it.skladba) == kljuc(s) }?.polozaj ?: 0L

    fun odstrani(c: Context, s: Jamendo.Skladba) {
        val k = kljuc(s)
        val p = s.povezava
        val id = s.id
        val naslovCist = s.naslov.trim().lowercase()
        shrani(c, preberiJson(c).filterNot { o ->
            if (o.optString("k") == k) return@filterNot true
            try {
                val sObj = o.getJSONObject("s")
                val n = sObj.optString("n").trim().lowercase()
                sObj.optString("u") == p || sObj.optString("id") == id ||
                    (n.isNotBlank() && (n == naslovCist || n.contains(naslovCist) || naslovCist.contains(n)))
            } catch (_: Exception) { false }
        })
    }

    fun pocisti(c: Context) {
        c.getSharedPreferences(PREF, 0).edit().remove(KEY).apply()
    }

    private fun json(s: Jamendo.Skladba, p: Long, d: Long, naprava: String) = JSONObject().put("k", kljuc(s)).put("p", p).put("d", d)
        .put("t", System.currentTimeMillis()).put("np", naprava).put("s", JSONObject().put("id",s.id).put("n",s.naslov).put("a",s.izvajalec)
            .put("i",s.slika).put("z",s.zvok).put("u",s.povezava).put("r",s.radio).put("v",s.video).put("m",s.mime).put("st",s.streznik).put("ka",s.kanal).put("mt",s.mediaType).put("je",s.language))
    private fun skladba(o: JSONObject) = Jamendo.Skladba(o.optString("id"),o.optString("n"),o.optString("a"),o.optString("i"),o.optString("z"),o.optString("u"),o.optBoolean("r"),o.optBoolean("v"),o.optString("m"),o.optString("st"),o.optString("ka"),mediaType=o.optString("mt"),language=o.optString("je"))
    private fun preberiJson(c: Context): List<JSONObject> = try { val a=JSONArray(c.getSharedPreferences(PREF,0).getString(KEY,"[]")); (0 until a.length()).mapNotNull{a.optJSONObject(it)} } catch (_:Exception){ emptyList() }
    private fun shrani(c: Context, l: List<JSONObject>) { val a=JSONArray(); l.forEach{a.put(it)}; c.getSharedPreferences(PREF,0).edit().putString(KEY,a.toString()).apply() }
}
