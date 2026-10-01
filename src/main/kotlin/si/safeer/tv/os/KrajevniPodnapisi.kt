package si.safeer.tv.os

import android.content.Context

/**
 * Podnapisi, ki jih uporabnik doda videu z naprave (izbira datoteke, brez dostopa do vseh datotek).
 * Android 13+ aplikaciji z dovoljenjem za videe ne pokaze datotek .srt ob videu, zato jih uporabnik izbere
 * enkrat, predvajalnik pa si izbiro zapomni (trajno dovoljenje za branje tiste ene datoteke).
 */
object KrajevniPodnapisi {
    private const val PREF = "safeer_krajevni_podnapisi"

    fun shrani(c: Context, idVidea: String, uri: String, ime: String) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(idVidea, "$uri\n$ime").apply()
    }

    fun za(c: Context, idVidea: String): List<Podnapisi.Podnapis> {
        val v = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(idVidea, null) ?: return emptyList()
        val uri = v.substringBefore('\n'); val ime = v.substringAfter('\n', "")
        return listOf(Podnapisi.Podnapis(uri, ime, Podnapisi.jezik("", ime).first, "", Podnapisi.mime(ime)))
    }
}
