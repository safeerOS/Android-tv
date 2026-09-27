package si.safeer.tv.os

import java.util.Locale

/** Pravila jezikovnega filtra so brez Androida, da jih lahko preverimo tudi z navadnim JVM-testom. */
object JezikiVsebine {
    private val znaneOznake by lazy {
        buildMap {
            Locale.getAvailableLocales().filter { it.language.isNotBlank() }.forEach { locale ->
                val koda = locale.language.lowercase(Locale.ROOT)
                put(koda, koda)
                try { put(locale.isO3Language.lowercase(Locale.ROOT), koda); Unit } catch (_: Exception) { }
                put(locale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT), koda)
                Unit
            }
        }
    }

    /** Dvo- in tricrkovne oznake poenotimo v dvocrkovno obliko, kadar jo Java pozna. */
    fun oznaka(surova: String): String {
        val jezik = surova.trim().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        if (jezik.isBlank()) return ""
        return znaneOznake[jezik] ?: jezik
    }

    /** Neznan jezik ni razlog za skrivanje: vnosi brez oznake ostanejo vedno vidni. */
    fun <T> filtriraj(vnosi: List<T>, izklopljeni: Set<String>, jezik: (T) -> String): List<T> =
        vnosi.filter { oznaka(jezik(it)).let { koda -> koda.isBlank() || koda !in izklopljeni } }
}
