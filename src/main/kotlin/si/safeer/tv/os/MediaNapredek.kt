package si.safeer.tv.os

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Lokalna zgodovina Safeer Media. Brez oblaka: polozaj in izbrani vir ostaneta na napravi. */
object MediaNapredek {
    private const val PREF = "safeer_media_napredek"
    private const val KEY = "vnosi"
    /** Pregled B-S1: kljuci odgledanih vsebin (>= 95 %, [OsPravila.odgledano]), najnovejsi prvi. */
    private const val KEY_ODGLEDANO = "odgledano"
    private const val NAJVEC_ODGLEDANIH = 300
    /** [naprava] = id naprave v Linku, s katere je datoteka (streznik datotek); prazno za splet in to napravo. */
    data class Vnos(val skladba: Jamendo.Skladba, val polozaj: Long, val trajanje: Long, val cas: Long, val naprava: String = "")

    /** Pregled Grok 4: kljuc je vsebina + sezona + epizoda ([NapredekKljuc]), ne samo naslov brez letnice. */
    private fun opis(s: Jamendo.Skladba) = NapredekKljuc.Opis(s.id, s.naslov, s.season, s.episode, s.imdbId,
        film = s.mediaType.equals("movie", ignoreCase = true) || SpletniVir.vrstaVsebine(s) == SpletniVir.FILM)
    private fun kljuc(s: Jamendo.Skladba) = NapredekKljuc.kljuc(opis(s))

    /** Ali shranjeni zapis [o] velja za [s] (tudi star zapis brez sezone in epizode - [NapredekKljuc.ujema]). */
    private fun ujema(o: JSONObject, s: Jamendo.Skladba): Boolean = try {
        NapredekKljuc.ujema(opis(skladba(o.getJSONObject("s"))), opis(s))
    } catch (_: Exception) { false }

    /** Videi s te naprave (kot VLC): zapomnimo si mesto ze od 1 min dolzine naprej. */
    private fun najkrajse(s: Jamendo.Skladba) = if (s.id.startsWith("krajevno:")) 60_000L else 600_000L

    fun zapisi(c: Context, s: Jamendo.Skladba, polozaj: Long, trajanje: Long, naprava: String = "") {
        // Ogled vsebine zasebnega dodatka se ne zapise (ZasebniDodatki): ni v Nadaljuj, Zate in ne gre drugi napravi.
        Stremio.pripravi(c)
        if (Stremio.jeZasebna(s)) return
        // Naloga 135: samodejni preizkus po gradnji ne zapise mesta ogleda.
        if (PreizkusPravila.jePreizkus(s.id)) return
        val vrsta = SpletniVir.vrstaVsebine(s)
        val jeSerijaAliFilm = vrsta == SpletniVir.SERIJA || vrsta == SpletniVir.FILM || s.season > 0 || s.episode > 0
        // Kratkih glasbenih videospotov, pesmi in vsebin pod 10 min ne vnasamo med filme in serije za nadaljevanje ogleda
        if (!s.video || s.radio || Stremio.jeVZivo(s) || s.id.startsWith("tv:") || vrsta == SpletniVir.VIDEOSPOT || s.mediaType.equals("MusicVideo", ignoreCase = true) ||
            (!jeSerijaAliFilm && trajanje < najkrajse(s)) || polozaj < 15_000 || trajanje <= 0) return
        // Pregled B-S1: izrecna oznaka »odgledano« (>= 95 %); ogled znova od zacetka jo pobrise. Samo po njej »Nadaljuj«
        // odpre naslednjo epizodo - manjkajoc zapis mesta (pod 15 s, zasebni dodatek, brez trajanja) ne pomeni »odgledano«.
        oznaciOdgledano(c, s, OsPravila.odgledano(polozaj, trajanje))
        val vsi = preberiJson(c).filter { !ujema(it, s) }.toMutableList()
        // Zadnjih ~95 % ne ponujamo kot "Nadaljuj"; ogled je prakticno koncan.
        if (!OsPravila.odgledano(polozaj, trajanje)) vsi.add(0, json(s, polozaj, trajanje, naprava))
        shrani(c, vsi.take(30))
    }

    fun seznam(c: Context): List<Vnos> = seznam(c, MedijskiViri.vsi(c).filter { it.jeStremio }.map { it.naslov })

    private fun seznam(c: Context, dodatki: List<String>): List<Vnos> = preberiJson(c).also { Stremio.pripravi(c) }.mapNotNull { o ->
        try {
            val sk = skladba(o.getJSONObject("s"))
            if (Stremio.jeZasebna(sk) || Stremio.brezDodatka(sk, dodatki)) return@mapNotNull null
            val trajanje = o.getLong("d")
            val vrsta = SpletniVir.vrstaVsebine(sk)
            val jeSerijaAliFilm = vrsta == SpletniVir.SERIJA || vrsta == SpletniVir.FILM || sk.season > 0 || sk.episode > 0
            if (!sk.video || sk.radio || Stremio.jeVZivo(sk) || sk.id.startsWith("tv:") || vrsta == SpletniVir.VIDEOSPOT || sk.mediaType.equals("MusicVideo", ignoreCase = true) || (!jeSerijaAliFilm && trajanje < najkrajse(sk))) null
            else Vnos(sk, o.getLong("p"), trajanje, o.optLong("t"), o.optString("np"))
        } catch (_: Exception) { null }
    }.sortedByDescending { it.cas }

    fun polozaj(c: Context, s: Jamendo.Skladba): Long = seznam(c).firstOrNull {
        NapredekKljuc.ujema(opis(it.skladba), opis(s))
    }?.polozaj ?: 0L

    /**
     * Pregled Grok 4: odstrani samo zapis te vsebine (kljuc, ista povezava ali isti id) - prej tudi vsak zapis, katerega naslov
     * je vseboval naslov [s] ali obratno (»It« je pobrisal druge vnose v Nadaljuj).
     */
    fun odstrani(c: Context, s: Jamendo.Skladba) {
        val p = s.povezava
        val id = s.id
        shrani(c, preberiJson(c).filterNot { o ->
            ujema(o, s) || try {
                val sObj = o.getJSONObject("s")
                (p.isNotBlank() && sObj.optString("u") == p) || (id.isNotBlank() && sObj.optString("id") == id)
            } catch (_: Exception) { false }
        })
    }

    /** Pregled B-S1: ali je bila vsebina [s] nazadnje odgledana do konca (>= 95 %). */
    fun odgledano(c: Context, s: Jamendo.Skladba): Boolean = NapredekKljuc.odgledano(odgledani(c), opis(s))

    private val zaklepOdgledanih = Any()

    private fun odgledani(c: Context): List<String> = try {
        val a = JSONArray(c.getSharedPreferences(PREF, 0).getString(KEY_ODGLEDANO, "[]"))
        (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    } catch (_: Exception) { emptyList() }

    private fun oznaciOdgledano(c: Context, s: Jamendo.Skladba, da: Boolean) = synchronized(zaklepOdgledanih) {
        val prej = odgledani(c)
        val potem = NapredekKljuc.oznaci(prej, opis(s), da, NAJVEC_ODGLEDANIH)
        if (potem != prej) c.getSharedPreferences(PREF, 0).edit().putString(KEY_ODGLEDANO, JSONArray(potem).toString()).apply()
    }

    /** Pregled Grok 4: »Pocisti« pobrise tudi oznake »odgledano« (prej so ostale). */
    fun pocisti(c: Context) {
        synchronized(zaklepOdgledanih) { c.getSharedPreferences(PREF, 0).edit().remove(KEY).remove(KEY_ODGLEDANO).apply() }
    }

    private fun json(s: Jamendo.Skladba, p: Long, d: Long, naprava: String) = JSONObject().put("k", kljuc(s)).put("p", p).put("d", d)
        .put("t", System.currentTimeMillis()).put("np", naprava).put("s", JSONObject().put("id",s.id).put("n",s.naslov).put("a",s.izvajalec)
            .put("i",s.slika).put("z",s.zvok).put("u",s.povezava).put("r",s.radio).put("v",s.video).put("m",s.mime).put("st",s.streznik).put("ka",s.kanal).put("mt",s.mediaType).put("je",s.language)
            // Pregled Grok 4: sezona in epizoda - kljuc in »Nadaljuj« naslednje epizode ne slonita na naslovu (SxEy).
            .put("season", s.season).put("episode", s.episode).put("imdbId", s.imdbId))
    private fun skladba(o: JSONObject) = Jamendo.Skladba(o.optString("id"),o.optString("n"),o.optString("a"),o.optString("i"),o.optString("z"),o.optString("u"),o.optBoolean("r"),o.optBoolean("v"),o.optString("m"),o.optString("st"),o.optString("ka"),mediaType=o.optString("mt"),language=o.optString("je"),season=o.optInt("season"),episode=o.optInt("episode"),imdbId=o.optString("imdbId"))
    private fun preberiJson(c: Context): List<JSONObject> = try { val a=JSONArray(c.getSharedPreferences(PREF,0).getString(KEY,"[]")); (0 until a.length()).mapNotNull{a.optJSONObject(it)} } catch (_:Exception){ emptyList() }
    private fun shrani(c: Context, l: List<JSONObject>) { val a=JSONArray(); l.forEach{a.put(it)}; c.getSharedPreferences(PREF,0).edit().putString(KEY,a.toString()).apply() }
}
