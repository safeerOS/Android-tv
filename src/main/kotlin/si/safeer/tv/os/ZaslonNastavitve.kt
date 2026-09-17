package si.safeer.tv.os

import android.content.Context
import si.safeer.tv.R

/**
 * Kakovost slike pri zrcaljenju zaslona. Ime kakovosti pozna tudi racunalnik (`screen.start`),
 * zato se oznake ne spreminjajo; kaj pomenijo, doloci racunalnik (core/link_zaslon.py).
 */
object ZaslonNastavitve {
    private const val PREFS = "safeer_os"
    private const val KLJUC = "zaslon_kakovost"

    val OZNAKE = listOf("nizka", "srednja", "visoka", "najvisja")
    val IMENA = listOf(R.string.os_zaslon_nizka, R.string.os_zaslon_srednja,
                       R.string.os_zaslon_visoka, R.string.os_zaslon_najvisja)

    fun kakovost(c: Context): String =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KLJUC, "visoka").takeIf { OZNAKE.contains(it) } ?: "visoka"

    fun nastavi(c: Context, oznaka: String) {
        if (!OZNAKE.contains(oznaka)) return
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KLJUC, oznaka).apply()
    }

    fun ime(c: Context): String = c.getString(IMENA[OZNAKE.indexOf(kakovost(c))])
}
