package si.safeer.tv.os

import android.app.Activity
import android.content.Context
import android.view.View
import si.safeer.tv.R

/**
 * Videz Safeer OS: uporabnik izbere ozadje. Slike so nase lastne risbe (vektor, nekaj kilobajtov,
 * ostre na vsakem zaslonu) - nobene tuje fotografije, zato tudi nobenih tujih pravic.
 *
 * Nad sliko je temna zavesa, ki je levo skoraj neprozorna: drobno besedilo mora ostati berljivo z
 * dveh metrov, tudi kadar je slika svetla. Kdor ima rad mir, izbere "Brez slike".
 */
object Tema {
    private const val PREFS = "safeer_os"
    private const val KLJUC = "tema"

    /** [oznaka] se shrani v nastavitve, zato se nikoli ne spremeni. */
    class Izbira(val oznaka: String, val imeRes: Int, val ozadje: Int)

    val VSE = listOf(
        Izbira("brez", R.string.os_tema_brez, 0),
        Izbira("sij", R.string.os_tema_sij, R.drawable.os_tema_sij),
        Izbira("gozd", R.string.os_tema_gozd, R.drawable.os_tema_gozd),
        Izbira("mesto", R.string.os_tema_mesto, R.drawable.os_tema_mesto),
        Izbira("zora", R.string.os_tema_zora, R.drawable.os_tema_zora),
    )

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun izbrana(c: Context): Izbira {
        val oznaka = prefs(c).getString(KLJUC, null) ?: return VSE[0]
        return VSE.firstOrNull { it.oznaka == oznaka } ?: VSE[0]
    }

    fun nastavi(c: Context, izbira: Izbira) {
        prefs(c).edit().putString(KLJUC, izbira.oznaka).apply()
    }

    fun ime(c: Context): String = c.getString(izbrana(c).imeRes)

    /** Ozadje zaslona po izbiri uporabnika; "Brez slike" pusti mirno temno ploskev. */
    fun uporabi(a: Activity, koren: View) {
        val t = izbrana(a)
        if (t.ozadje == 0) koren.setBackgroundResource(R.color.os_ozadje)
        else koren.setBackgroundResource(t.ozadje)
    }
}
