package si.safeer.tv.os

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * Vrstni red kartic, ki si ga uporabnik doloci sam (vrsta "Zacni" na domacem zaslonu). Hranimo
 * samo oznake kartic; kar je novega (nova kartica v posodobitvi), se pripne na konec, kar je
 * izginilo (kartica, ki je ta trenutek ni), pa tiho izpade - uporabnikov red se ne pokvari.
 */
object Vrstni {
    private const val TAG = "SafeerOsVrstni"
    private const val PREFS = "safeer_os"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun shranjen(c: Context, kljuc: String): List<String> {
        val surovo = prefs(c).getString(kljuc, null) ?: return emptyList()
        return try {
            val polje = JSONArray(surovo)
            (0 until polje.length()).map { polje.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Throwable) { Log.w(TAG, "Reda ni bilo mogoce prebrati: ${e.message}"); emptyList() }
    }

    private fun shrani(c: Context, kljuc: String, oznake: List<String>) {
        val polje = JSONArray()
        for (o in oznake.distinct()) polje.put(o)
        prefs(c).edit().putString(kljuc, polje.toString()).apply()
    }

    /** Red, kot ga vidi uporabnik: najprej njegov, nato kar je novega, v privzetem zaporedju. */
    fun red(c: Context, kljuc: String, privzeti: List<String>): List<String> {
        val moj = shranjen(c, kljuc).filter { privzeti.contains(it) }
        return moj + privzeti.filterNot { moj.contains(it) }
    }

    /** Premik za [zamik] mest (-1 levo, +1 desno); vrne true, ce se je red res spremenil. */
    fun premakni(c: Context, kljuc: String, privzeti: List<String>, oznaka: String, zamik: Int): Boolean {
        val s = red(c, kljuc, privzeti).toMutableList()
        val i = s.indexOf(oznaka)
        if (i < 0) return false
        val j = i + zamik
        if (j < 0 || j >= s.size) return false
        s.add(j, s.removeAt(i))
        shrani(c, kljuc, s)
        return true
    }
}
