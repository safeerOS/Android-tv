package si.safeer.tv.os

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Dolzina filma po IMDb iz Cinemete (javni katalog, ki ga Safeer ze uporablja za kartice). Iz naprave gre samo id IMDb
 * filma, ki se predvaja. Odgovor ostane v pomnilniku, dokler aplikacija tece. Klic iz ozadja (omrezje).
 */
object TrajanjeFilma {
    private val znani = ConcurrentHashMap<String, Int>()

    fun minut(imdb: String): Int? {
        if (!Regex("^tt\\d{3,12}$").matches(imdb)) return null
        znani[imdb]?.let { return it.takeIf { m -> m > 0 } }
        val m = try {
            val c = URL("https://v3-cinemeta.strem.io/meta/movie/$imdb.json").openConnection() as HttpURLConnection
            c.connectTimeout = 6_000; c.readTimeout = 8_000
            try {
                if (c.responseCode != 200) null
                else org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText().take(400_000) })
                    .optJSONObject("meta")?.optString("runtime").let { OsPravila.minutIzOpisa(it) }
            } finally { c.disconnect() }
        } catch (_: Exception) { null }
        znani[imdb] = m ?: 0
        return m
    }
}
