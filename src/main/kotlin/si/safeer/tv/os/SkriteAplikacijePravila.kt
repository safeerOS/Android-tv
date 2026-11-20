package si.safeer.tv.os

/**
 * Aplikacije, ki jih je lastnik skril z domaceg zaslona (Lastnik 20. 11. 2026: »zaganjalnik more imeti moznost odstrani
 * iz domacega zaslona za vsako app«). Skritje je samo prikaz: aplikacija ostane nameščena in jo je mogoce vrniti.
 * Cista logika brez Androida (preizkus: tests/SkriteAplikacijePravilaTest.kt).
 */
object SkriteAplikacijePravila {
    private const val LOCILO = "\n"

    fun razcleni(surovo: String?): Set<String> =
        surovo.orEmpty().split(LOCILO).map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())

    fun kodiraj(paketi: Collection<String>): String = paketi.map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(LOCILO)

    /** Paketa ni mogoce skriti, ce je prazen ali je sam zaganjalnik (ne bi ostalo nic za vrniti). */
    fun smeSkriti(paket: String, lastniPaket: String): Boolean = paket.isNotBlank() && paket != lastniPaket

    fun dodaj(skrite: Set<String>, paket: String, lastniPaket: String): Set<String> =
        if (smeSkriti(paket, lastniPaket)) LinkedHashSet(skrite).also { it.add(paket) } else skrite

    fun odstrani(skrite: Set<String>, paket: String): Set<String> = LinkedHashSet(skrite).also { it.remove(paket) }

    fun <T> filtriraj(vnosi: List<T>, paket: (T) -> String, skrite: Set<String>): List<T> =
        if (skrite.isEmpty()) vnosi else vnosi.filter { paket(it) !in skrite }
}
