package si.safeer.tv.os

import java.util.Locale

/**
 * Poizvedba Internet Archive za filme v javni lasti (brez odvisnosti od Androida - preizkus na JVM).
 * Jezik in zvrst izbere ze Internet Archive: vecina filmov v javni lasti nima jezika ali zvrsti v podatkih, zato je
 * izbira »Komedija · Anglescina« po prejetih zadetkih pustila prazno mrezo (»V tem jeziku tukaj ni vsebine«, lastnik 10. 10. 2026).
 */
object JavnaLastPoizvedba {
    const val OSNOVA = "collection:feature_films AND mediatype:movies AND licenseurl:*publicdomain*"

    /** Imena jezika, kot jih ima Internet Archive v polju `language` (en -> eng, English); prazno = brez pogoja. */
    fun imenaJezika(jezik: String): List<String> {
        if (!Regex("[a-z]{2}").matches(jezik)) return emptyList()
        val l = Locale(jezik)
        val iso3 = try { l.isO3Language } catch (_: Exception) { "" }
        val ime = l.getDisplayLanguage(Locale.ENGLISH)
        return listOf(iso3, ime).filter { it.isNotBlank() && !it.equals(jezik, ignoreCase = true) }.distinct()
    }

    /** `predmeti`: imena zvrsti v anglescini (Zvrsti.Zvrst.katalog, npr. »Comedy«) za polje `subject`. */
    fun q(beseda: String = "", jezik: String = "", predmeti: List<String> = emptyList()): String {
        val cista = beseda.replace(Regex("[^\\p{L}\\p{N} ]+"), " ").replace(Regex("\\s+"), " ").trim()
        val jeziki = imenaJezika(jezik)
        val zvrsti = predmeti.map { it.replace(Regex("[^\\p{L}\\p{N} &-]+"), "").trim() }.filter { it.isNotEmpty() }.distinct()
        return OSNOVA +
            (if (cista.isNotBlank()) " AND title:($cista)" else "") +
            (if (jeziki.isNotEmpty()) " AND language:(" + jeziki.joinToString(" OR ") { "\"$it\"" } + ")" else "") +
            (if (zvrsti.isNotEmpty()) " AND subject:(" + zvrsti.joinToString(" OR ") { "\"$it\"" } + ")" else "")
    }
}
