package si.safeer.tv.os

import android.content.Context

/** Shramba skritih aplikacij (glej [SkriteAplikacijePravila]); nikoli ne brise ali onemogoca aplikacije. */
object SkriteAplikacije {
    private const val PREFS = "safeer_os"
    private const val KLJUC = "skrite_aplikacije"

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun seznam(c: Context): Set<String> = SkriteAplikacijePravila.razcleni(prefs(c).getString(KLJUC, null))

    fun je(c: Context, paket: String): Boolean = paket in seznam(c)

    fun skrij(c: Context, paket: String) {
        val novo = SkriteAplikacijePravila.dodaj(seznam(c), paket, c.packageName)
        prefs(c).edit().putString(KLJUC, SkriteAplikacijePravila.kodiraj(novo)).apply()
    }

    fun pokazi(c: Context, paket: String) {
        prefs(c).edit().putString(KLJUC, SkriteAplikacijePravila.kodiraj(SkriteAplikacijePravila.odstrani(seznam(c), paket))).apply()
    }
}
