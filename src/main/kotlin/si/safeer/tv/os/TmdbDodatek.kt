package si.safeer.tv.os

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import si.safeer.tv.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Safeer katalog (TMDB) kot vgrajen dodatek Stremio: manifest in katalogi filmov in serij s slovenskimi naslovi in plakati.
 * Id vnosa je IMDb (`tt...`), zato iskanje tokov (Usenet, debrid) deluje kot pri Cinemeti; meta podatke in epizode da
 * dalje Cinemeta. Vir: TMDB (navedba v O aplikaciji). Podatki TMDB se ne dajo Lini ali agentu (pogoji TMDB).
 */
object TmdbDodatek {
    private const val TAG = "SafeerTmdb"
    private const val TTL_MS = 6 * 3_600_000L
    private val kljuc: String get() = BuildConfig.TMDB_KLJUC

    val naVoljo: Boolean get() = kljuc.matches(Regex("[0-9a-f]{32}"))

    private val delavci = Executors.newFixedThreadPool(6) { r -> Thread(r, "safeer-tmdb").apply { isDaemon = true } }
    private val predpomnilnik = ConcurrentHashMap<String, Pair<Long, JSONObject>>()
    private val imdbId = ConcurrentHashMap<String, String>()
    /** Meje strani po poti ([TmdbPravila.stran]): skip, pri katerem se zacne naslednja stran. */
    private val meje = ConcurrentHashMap<String, MutableList<Int>>()

    /** Odgovor na zahtevo na [TmdbPravila.BASE]; null = ni (ni kljuca, napaka, neznano). Delovna nit. */
    fun odgovori(url: String): JSONObject? {
        if (!naVoljo) return null
        if (TmdbPravila.jeManifest(url)) return manifest()
        val z = TmdbPravila.zahteva(url) ?: return null
        val pot = TmdbPravila.pot(z.tip, z.katalog) ?: return null
        val kljucPred = "$pot|${z.skip}|${Locale.getDefault().toLanguageTag()}"
        predpomnilnik[kljucPred]?.let { (t, o) -> if (System.currentTimeMillis() - t < TTL_MS) return o }
        val o = try { katalog(z.tip, pot, z.skip) } catch (e: Exception) { Log.w(TAG, "katalog: ${e.javaClass.simpleName}"); null }
        if (o != null) predpomnilnik[kljucPred] = System.currentTimeMillis() to o
        return o
    }

    private fun manifest(): JSONObject {
        fun kat(tip: String, id: String, ime: String) = JSONObject().put("type", tip).put("id", id).put("name", ime)
            .put("extra", JSONArray().put(JSONObject().put("name", "skip")))
        val k = JSONArray()
        for (t in listOf("movie" to "Filmi", "series" to "Serije")) {
            k.put(kat(t.first, "tmdb.popularno", "${t.second}: priljubljeno (TMDB)"))
            k.put(kat(t.first, "tmdb.najbolje", "${t.second}: najbolje ocenjeno (TMDB)"))
            k.put(kat(t.first, "tmdb.novo", "${t.second}: novo (TMDB)"))
        }
        return JSONObject().put("id", "si.safeer.tmdb").put("version", "1.0.0").put("name", "Safeer katalog (TMDB)")
            .put("description", "Filmi in serije iz TMDB. This product uses TMDB and the TMDB APIs but is not endorsed, certified, or otherwise approved by TMDB.")
            .put("resources", JSONArray().put("catalog")).put("types", JSONArray().put("movie").put("series"))
            .put("catalogs", k)
    }

    private fun beri(pot: String, parametri: String): JSONObject? {
        val c = URL("https://api.themoviedb.org/3/$pot?api_key=$kljuc$parametri").openConnection() as HttpURLConnection
        c.connectTimeout = 8_000; c.readTimeout = 8_000
        c.setRequestProperty("User-Agent", "Safeer-Predvajalnik/1.0 (+https://safeer.si)")
        c.setRequestProperty("Accept", "application/json")
        return try {
            if (c.responseCode !in 200..299) null
            else {
                val bajti = c.inputStream.use { it.readBytes() }
                if (bajti.size > 2_000_000) null else JSONObject(String(bajti, Charsets.UTF_8))
            }
        } catch (_: Exception) { null } finally { c.disconnect() }
    }

    private fun katalog(tip: String, pot: String, skip: Int): JSONObject? {
        val jezik = TmdbPravila.jezik(Locale.getDefault().language)
        val m = meje.getOrPut(pot) { java.util.Collections.synchronizedList(ArrayList()) }
        val stran = synchronized(m) { TmdbPravila.stran(skip, m.toList()) }
        val seznam = beri(pot, "&language=$jezik&page=$stran")?.optJSONArray("results") ?: return null
        val vrsta = if (tip == "movie") "movie" else "tv"
        val naloge = ArrayList<Callable<JSONObject?>>()
        for (i in 0 until seznam.length()) {
            val e = seznam.optJSONObject(i) ?: continue
            naloge.add(Callable { vnos(e, tip, vrsta) })
        }
        val metas = JSONArray()
        for (f in delavci.invokeAll(naloge, 15, TimeUnit.SECONDS)) {
            val m = try { f.get() } catch (_: Exception) { null }
            if (m != null) metas.put(m)
        }
        synchronized(m) {
            while (m.size < stran - 1) m.add(-1)
            if (stran - 1 < m.size) m[stran - 1] = skip + metas.length() else m.add(skip + metas.length())
        }
        Log.i(TAG, "katalog $pot: ${metas.length()} od ${seznam.length()} (z IMDb id)")
        return JSONObject().put("metas", metas)
    }

    private fun vnos(e: JSONObject, tip: String, vrsta: String): JSONObject? {
        val id = e.optLong("id", 0L).takeIf { it > 0 } ?: return null
        val imdb = imdbId.getOrPut("$vrsta:$id") {
            beri("$vrsta/$id/external_ids", "")?.optString("imdb_id").orEmpty()
        }
        if (!TmdbPravila.veljavenImdb(imdb)) { imdbId.remove("$vrsta:$id"); return null }
        val ime = (if (vrsta == "movie") e.optString("title") else e.optString("name"))
            .ifBlank { e.optString("original_title").ifBlank { e.optString("original_name") } }
        if (ime.isBlank()) return null
        val datum = if (vrsta == "movie") e.optString("release_date") else e.optString("first_air_date")
        return JSONObject().put("id", imdb).put("type", tip).put("name", ime.take(200))
            .put("poster", TmdbPravila.slika(e.optString("poster_path").takeIf { it.isNotEmpty() }, "w342"))
            .put("background", TmdbPravila.slika(e.optString("backdrop_path").takeIf { it.isNotEmpty() }, "w780"))
            .put("description", e.optString("overview").take(600))
            .put("releaseInfo", TmdbPravila.leto(datum))
    }
}
