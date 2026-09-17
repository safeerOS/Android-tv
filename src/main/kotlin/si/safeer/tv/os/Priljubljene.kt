package si.safeer.tv.os

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * Priljubljene aplikacije televizorja. Na domacem zaslonu je bila prej vrsta z **vsemi**
 * aplikacijami: na tem televizorju jih je toliko, da se je vrsta lomila cez rob zaslona in med
 * njimi ni bilo mogoce nic najti. Zato na domacem zaslonu stojijo samo tiste, ki jih je uporabnik
 * izbral, in v vrstnem redu, ki si ga sam doloci; vse ostalo je za karticio "Ostalo".
 *
 * Shranimo imena paketov, ne ikon: ce uporabnik aplikacijo odstrani, preprosto izpade.
 */
object Priljubljene {
    private const val TAG = "SafeerOsPriljubljene"
    private const val PREFS = "safeer_os"
    private const val KLJUC = "priljubljene_tv"
    private const val KLJUC_PRVIC = "priljubljene_zacetne"

    /**
     * Koliko aplikacij prenesemo v priljubljene ob prvem zagonu. Stiri, ker je peta kartica v
     * vrsti "Ostalo" - tako je na zaslonu videti cela vrsta, brez odrezane kartice na robu.
     */
    private const val ZACETNIH = 4

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun seznam(c: Context): List<String> {
        val surovo = prefs(c).getString(KLJUC, null) ?: return emptyList()
        return try {
            val polje = JSONArray(surovo)
            (0 until polje.length()).map { polje.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Throwable) { Log.w(TAG, "Seznama ni bilo mogoce prebrati: ${e.message}"); emptyList() }
    }

    private fun shrani(c: Context, paketi: List<String>) {
        val polje = JSONArray()
        for (p in paketi.distinct()) polje.put(p)
        prefs(c).edit().putString(KLJUC, polje.toString()).apply()
    }

    fun je(c: Context, paket: String): Boolean = seznam(c).contains(paket)

    fun dodaj(c: Context, paket: String) {
        val s = seznam(c)
        if (s.contains(paket)) return
        shrani(c, s + paket)
    }

    fun odstrani(c: Context, paket: String) = shrani(c, seznam(c).filterNot { it == paket })

    /** Premik za [zamik] mest (-1 levo, +1 desno); vrne true, ce se je vrstni red res spremenil. */
    fun premakni(c: Context, paket: String, zamik: Int): Boolean {
        val s = seznam(c).toMutableList()
        val i = s.indexOf(paket)
        if (i < 0) return false
        val j = i + zamik
        if (j < 0 || j >= s.size) return false
        s.add(j, s.removeAt(i))
        shrani(c, s)
        return true
    }

    /**
     * Prvi zagon po posodobitvi: uporabnik je doslej videl vse aplikacije, zato mu prvih [ZACETNIH]
     * (po abecedi, kot so bile) prenesemo v priljubljene - domaci zaslon ostane tak, kot ga pozna,
     * naprej pa si ga uredi sam. Naredi se natanko enkrat.
     */
    fun prviKrat(c: Context, vsi: List<String>) {
        val p = prefs(c)
        if (p.getBoolean(KLJUC_PRVIC, false)) return
        p.edit().putBoolean(KLJUC_PRVIC, true).apply()
        if (seznam(c).isNotEmpty() || vsi.isEmpty()) return
        shrani(c, vsi.take(ZACETNIH))
    }
}
