package si.safeer.tv

/**
 * Lastnik 27. 10. 2026 (TV beta 14, OutOfMemoryError): stran je odpirala pojavna okna v zanki - Splet je za vsako ustvaril
 * cel spletni pogled (ChromiumEngineView) in ga po 40 ms zavrgel, vsakih 0,3 s. Na stran najvec [NAJVEC] oken v [OKNO_MS];
 * za ostala se pogled sploh ne ustvari. Ciste funkcije (tests/PojavnaOknaMejaTest.kt).
 */
object PojavnaOknaMeja {
    const val NAJVEC = 4
    const val OKNO_MS = 10_000L

    /** Ali stran sme odpreti se eno okno; [casi] so zacetki prejsnjih (pocisti stare in doda tega, ce je dovoljen). */
    fun dovoli(casi: ArrayDeque<Long>, zdajMs: Long, najvec: Int = NAJVEC, oknoMs: Long = OKNO_MS): Boolean {
        while (casi.isNotEmpty() && (zdajMs - casi.first() >= oknoMs || zdajMs < casi.first())) casi.removeFirst()
        if (casi.size >= najvec) return false
        casi.addLast(zdajMs)
        return true
    }
}
