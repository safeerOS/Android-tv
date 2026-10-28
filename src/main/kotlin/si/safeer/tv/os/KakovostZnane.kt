package si.safeer.tv.os

import android.content.Context
import org.json.JSONObject

/**
 * Najboljsa znana kakovost filma po id-ju (lastnik 28. 10. 2026, razdelek 5): v pomnilniku in na disku (najvec
 * [KakovostPravila.NAJVEC_ZNANIH], najstarejse izpadejo). Polni se samo iz tokov, ki jih aplikacija tako ali tako dobi -
 * priprava kartice ([GlasbaActivity.tokoviVzporedno]) in izbira toka ob predvajanju. Indeksatorja nikoli ne klicemo
 * samo zaradi oznake. Pravila so v [KakovostPravila] (oznaka, zdruzi, kljuc).
 */
object KakovostZnane {
    private const val DATOTEKA = "kakovost_znana"
    private const val KLJUC = "oznake"

    /** Vrstni red dostopa (LRU): najnovejsi na koncu. */
    private val znane = object : LinkedHashMap<String, KakovostPravila.Oznaka>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, KakovostPravila.Oznaka>?) =
            size > KakovostPravila.NAJVEC_ZNANIH
    }
    @Volatile private var nalozeno = false

    private fun nalozi(c: Context) {
        if (nalozeno) return
        synchronized(znane) {
            if (nalozeno) return
            try {
                val o = JSONObject(c.getSharedPreferences(DATOTEKA, Context.MODE_PRIVATE).getString(KLJUC, "{}") ?: "{}")
                for (k in o.keys()) KakovostPravila.Oznaka.izNapisa(o.optString(k))?.let { znane[k] = it }
            } catch (_: Exception) { }
            nalozeno = true
        }
    }

    fun oznaka(c: Context, id: String): KakovostPravila.Oznaka? {
        val k = KakovostPravila.kljuc(id) ?: return null
        nalozi(c)
        return synchronized(znane) { znane[k] }
    }

    /** Zabelezi tokove filma ali epizode serije ([serija]: id serije, velja zadnja epizoda); vrne novo oznako, ce se je spremenila (kartico je treba osveziti), sicer null. */
    fun zabelezi(c: Context, id: String, imenaTokov: List<String>, serija: Boolean = false): KakovostPravila.Oznaka? {
        val k = KakovostPravila.kljuc(id) ?: return null
        val nova = KakovostPravila.najboljsa(imenaTokov) ?: return null
        nalozi(c)
        val (sprememba, posnetek) = synchronized(znane) {
            val stara = znane[k]
            val zdruzena = KakovostPravila.posodobi(stara, nova, serija)
            if (zdruzena == null || zdruzena == stara) return null
            znane[k] = zdruzena
            zdruzena to JSONObject().apply { for ((kk, v) in znane) put(kk, v.napis) }.toString()
        }
        c.getSharedPreferences(DATOTEKA, Context.MODE_PRIVATE).edit().putString(KLJUC, posnetek).apply()
        return sprememba
    }
}
