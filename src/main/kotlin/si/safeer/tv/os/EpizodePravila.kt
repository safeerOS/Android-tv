package si.safeer.tv.os

object EpizodePravila {

    /**
     * Oblikuje oznako sezone in epizode z vodilnimi ničlami (npr. S01E01).
     * Če sta sezona in epizoda 0 ali manjša (film/album), vrne prazno.
     */
    fun oznakaEpizode(sezona: Int, epizoda: Int): String {
        if (sezona <= 0 && epizoda <= 0) return ""
        val s = sezona.toString().padStart(2, '0')
        val e = epizoda.toString().padStart(2, '0')
        return "S${s}E${e}"
    }

    /**
     * Določi, ali je treba prikazati levi stolpec (sezone).
     * Prikazati ga je treba le, če obstaja več kot ena različna sezona.
     */
    fun pokaziLeviStolpec(sezone: List<Int>): Boolean {
        return sezone.distinct().size > 1
    }

    /**
     * Določi naslednji fokusiran stolpec ob pritisku D-pad (LEVO ali DESNO).
     * Vrne true za prehod v stolpec sezon (LEVO), false za epizode (DESNO),
     * ali null, če se ne zgodi nič (invalidna smer).
     */
    fun smerFokusa(jeNaSezonah: Boolean, jeLevo: Boolean): Boolean? {
        if (jeNaSezonah && !jeLevo) return false // DESNO na sezonah -> gre na epizode
        if (!jeNaSezonah && jeLevo) return true  // LEVO na epizodah -> gre nazaj na sezone
        return null
    }
}
