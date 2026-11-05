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

    /**
     * Pregled Medijskega centra 5. 11. 2026 (7): kje se okno epizod odpre - pri zadnji izbrani ali gledani epizodi serije
     * ([zadnja] = sezona to epizoda), ce je med prikazanimi, sicer pri prvi. [epizode] so prikazane (sezona to epizoda),
     * [sezone] vrstni red sezon. Vrne indeks sezone in indeks epizode v tej sezoni.
     */
    fun zacetnaIzbira(epizode: List<Pair<Int, Int>>, sezone: List<Int>, zadnja: Pair<Int, Int>?): Pair<Int, Int> {
        if (zadnja == null) return 0 to 0
        val iSezone = sezone.indexOf(zadnja.first)
        if (iSezone < 0) return 0 to 0
        val iEpizode = epizode.filter { it.first == zadnja.first }.indexOfFirst { it.second == zadnja.second }
        return if (iEpizode < 0) 0 to 0 else iSezone to iEpizode
    }

    /**
     * Pregled Medijskega centra 5. 11. 2026 (6): preverjanje v ozadju je odstranilo epizodo - izbira ostane na isti epizodi
     * (po id; prej je ostala na istem mestu in OK je izbral drugo epizodo). Ce je odstranjena prav izbrana, ostane ista
     * vrstica (omejena na seznam). -1 = seznam je prazen.
     */
    fun izborPoOdstranitvi(prej: List<String>, zdaj: List<String>, izbran: Int): Int {
        if (zdaj.isEmpty()) return -1
        val i = prej.getOrNull(izbran)?.let { zdaj.indexOf(it) } ?: -1
        return if (i >= 0) i else izbran.coerceIn(0, zdaj.size - 1)
    }

    /**
     * Pregled Medijskega centra 5. 11. 2026 (6): sezone brez epizod izpadejo iz seznama. Vrne ostale sezone in indeks
     * trenutne sezone med njimi (ce je trenutna izpadla, sosednja); -1 = ni vec sezon.
     */
    fun sezonePoOdstranitvi(sezone: List<Int>, sezoneEpizod: List<Int>, trenutni: Int): Pair<List<Int>, Int> {
        val ostale = sezone.filter { it in sezoneEpizod }
        if (ostale.isEmpty()) return ostale to -1
        val i = sezone.getOrNull(trenutni)?.let { ostale.indexOf(it) } ?: -1
        return ostale to (if (i >= 0) i else trenutni.coerceIn(0, ostale.size - 1))
    }

    /** Pregled Medijskega centra 5. 11. 2026 (13): DESNO s seznama sezon vrne na zadnjo izbrano epizodo sezone, ne na prvo. */
    fun izborObVrnitvi(zadnji: Int, stevilo: Int): Int = if (stevilo <= 0) -1 else zadnji.coerceIn(0, stevilo - 1)

    /** Pregled Medijskega centra 5. 11. 2026 (12): stevilo epizod sezone iz prikazanih (skrite niso na voljo), ne iz vseh. */
    fun steviloVSezoni(sezoneEpizod: List<Int>, sezona: Int): Int = sezoneEpizod.count { it == sezona }
}
