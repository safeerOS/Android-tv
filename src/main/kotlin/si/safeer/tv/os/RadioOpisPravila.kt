package si.safeer.tv.os

import java.util.Locale

/**
 * Opis radijske postaje iz Radio Browserja (»Slovenia · rock«) v jeziku vmesnika (preverba TV 4. 11. 2026: v slovenskem
 * vmesniku je bil podnaslov menija postaje v anglescini). Drzava: anglesko ime iz imenika -> koda ISO -> ime v jeziku
 * vmesnika (java.util.Locale); zvrst: prevod da klicatelj (nizi vmesnika). Neznano ostane, kot je.
 * Ciste odlocitve, brez Androida (preizkus: tests/RadioOpisPravilaTest.kt).
 */
object RadioOpisPravila {
    private const val LOCILO = " · "

    /** Anglesko ime drzave (male crke, brez »the «) -> koda ISO 3166-1. */
    private val KODE: Map<String, String> by lazy {
        Locale.getISOCountries().associateBy { kljuc(Locale("", it).getDisplayCountry(Locale.ENGLISH)) }
    }

    private fun kljuc(ime: String): String = ime.trim().lowercase(Locale.ROOT).removePrefix("the ")

    /** Ime drzave (anglesko, kot ga da Radio Browser) v jeziku [jezik]; null, ce drzave ne poznamo. */
    fun prevediDrzavo(ime: String, jezik: Locale): String? {
        val koda = KODE[kljuc(ime)] ?: return null
        return Locale("", koda).getDisplayCountry(jezik).takeIf { it.isNotBlank() && it != koda }
    }

    /** »Drzava · zvrst« v jeziku [jezik]; [zvrst] vrne prevod oznake zvrsti ali null. */
    fun prevediOpis(opis: String, jezik: Locale, zvrst: (String) -> String?): String {
        if (opis.isBlank()) return opis
        val deli = opis.split(LOCILO)
        return deli.mapIndexed { i, d ->
            val t = d.trim()
            when {
                t.isEmpty() -> t
                i == 0 && deli.size <= 2 -> prevediDrzavo(t, jezik) ?: zvrst(t) ?: t
                else -> zvrst(t) ?: t
            }
        }.joinToString(LOCILO)
    }
}
