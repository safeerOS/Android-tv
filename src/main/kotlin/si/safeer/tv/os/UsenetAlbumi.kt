package si.safeer.tv.os

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Priljubljeni albumi z Useneta (lastnik 27. 10. 2026). Samo ime in velikost izdaje - nikoli povezava NZB (nosi kljuc
 * indeksatorja, pregled Grok 41) ali kljuc. Najvec [NAJVEC]; najnovejsi prvi. Samo na tej napravi.
 */
object UsenetAlbumi {
    private const val KLJUC = "usenet_priljubljeni_albumi"
    const val NAJVEC = 100
    const val NAJDALJSE_IME = 80

    /** [ime]: ime, ki ga je dal uporabnik (lastnik 27. 10. 2026: »da si ga lazje zapomni«); prazno = ime izdaje. */
    data class Album(val naslov: String, val velikost: Long, val ime: String = "")

    private fun shramba(c: Context) = c.getSharedPreferences("safeer_os", Context.MODE_PRIVATE)

    fun seznam(c: Context): List<Album> = try {
        val a = JSONArray(shramba(c).getString(KLJUC, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            o.optString("n").takeIf { it.isNotBlank() }?.let { Album(it.take(300), o.optLong("v", 0L), o.optString("i").take(NAJDALJSE_IME)) }
        }
    } catch (_: Exception) { emptyList() }

    fun je(c: Context, naslov: String): Boolean = seznam(c).any { UsenetGlasbaPravila.istiAlbum(it.naslov, naslov) }

    /** Doda ali odstrani; true = zdaj je med priljubljenimi. */
    @Synchronized
    fun preklopi(c: Context, naslov: String, velikost: Long): Boolean {
        val zdaj = seznam(c)
        val bil = zdaj.any { UsenetGlasbaPravila.istiAlbum(it.naslov, naslov) }
        val nov = if (bil) zdaj.filterNot { UsenetGlasbaPravila.istiAlbum(it.naslov, naslov) }
            else (listOf(Album(naslov, velikost)) + zdaj).take(NAJVEC)
        shrani(c, nov)
        return !bil
    }

    /** Lastno ime albuma; prazno vrne ime izdaje. */
    @Synchronized
    fun preimenuj(c: Context, naslov: String, ime: String) {
        val cisto = ime.replace(Regex("\\s+"), " ").trim().take(NAJDALJSE_IME)
        shrani(c, seznam(c).map { if (UsenetGlasbaPravila.istiAlbum(it.naslov, naslov)) it.copy(ime = cisto) else it })
    }

    private fun shrani(c: Context, seznam: List<Album>) {
        val a = JSONArray()
        seznam.forEach { a.put(JSONObject().put("n", it.naslov).put("v", it.velikost).put("i", it.ime)) }
        shramba(c).edit().putString(KLJUC, a.toString()).apply()
    }
}
