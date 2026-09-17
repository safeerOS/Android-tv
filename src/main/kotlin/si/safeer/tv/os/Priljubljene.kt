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
    private const val KLJUC_OCISCENO = "priljubljene_prazne"

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

    /**
     * Ena sama otoplitev za nazaj: prva razlicica je priljubljene izbrala sama (prvih nekaj
     * aplikacij televizorja). To ni bila uporabnikova izbira, zato jo enkrat pobrisemo - domaci
     * zaslon zacne prazen in aplikacije nanj da uporabnik sam, iz kartice "Ostalo".
     */
    fun pocistiSamodejne(c: Context) {
        val p = prefs(c)
        if (p.getBoolean(KLJUC_OCISCENO, false)) return
        p.edit().putBoolean(KLJUC_OCISCENO, true).apply()
        if (p.getBoolean(KLJUC_PRVIC, false)) shrani(c, emptyList())
    }

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

}
