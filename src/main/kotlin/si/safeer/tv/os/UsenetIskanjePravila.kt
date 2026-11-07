package si.safeer.tv.os

/**
 * Iskanje ob kliku uporabnika (lastnik 7. 11. 2026 03:51, Primetime): indeksator je kliku vrnil 4x »ni odgovora (meja)«,
 * ceprav je imel film 40 izdaj - preverbe filtra v ozadju so porabile minuto kvote. Pravila locujejo RAZLOG izida:
 * [Razlog.MEJA] (indeksator omejuje hitrost) ni [Razlog.NI_IZDAJ] (indeksator je odgovoril in nima izdaje). Meja se
 * ponovi po premoru, ki ga postavi [FilterViraPravila.Omejevalnik] (5 s), najvec [NAJVEC_POSKUSOV] poskusov.
 *
 * Zapis filtra se pozabi samo pri pravih 0 izdajah ([pozabiZapis]) - sicer kartica izgine, ceprav izdaje obstajajo.
 * Cista pravila brez Androida (preizkus tests/UsenetIskanjePravilaTest.kt); iskanje in omrezje sta v [Usenet].
 */
object UsenetIskanjePravila {
    /** Kaj je indeksator res rekel: [OK] izdaje, [NI_IZDAJ] 0 izdaj, [MEJA] omejuje, [NI_ODGOVORA] napaka/rok. */
    enum class Razlog { OK, NI_IZDAJ, MEJA, NI_ODGOVORA, NI_INDEKSATORJA }

    /** Skupaj s prvim iskanjem: 1. poskus, premor, 2. poskus, premor, 3. poskus. */
    const val NAJVEC_POSKUSOV = 3

    /** Cakanje med poskusi ob meji - enako prvemu premoru [FilterViraPravila.PRVI_PREMOR_MS] (5 s). */
    const val PREMOR_MS = 5_000L

    /** Pregled US-5: koliko klic caka na iskanje z vsemi ponovitvami ob meji ([NAJVEC_POSKUSOV] iskanj in premori vmes). */
    fun cakajSPonovitvami(enoIskanjeMs: Long): Long = NAJVEC_POSKUSOV * enoIskanjeMs + (NAJVEC_POSKUSOV - 1) * PREMOR_MS

    /**
     * Ali po koncanem poskusu [poskus] (1 = prvi) ob meji se poskusimo znova. Po [NAJVEC_POSKUSOV] nehamo in uporabniku
     * povemo, da indeksator ne odgovarja - sele takrat, ne ze ob prvi meji.
     */
    fun ponoviPoMeji(poskus: Int): Boolean = poskus in 1 until NAJVEC_POSKUSOV

    /** Razlog iz izida iskanja: null izdaje = ni odgovora; [meja] loci omejevanje od napake. */
    fun razlog(izdaj: Int?, meja: Boolean): Razlog = when {
        izdaj == null && meja -> Razlog.MEJA
        izdaj == null -> Razlog.NI_ODGOVORA
        izdaj == 0 -> Razlog.NI_IZDAJ
        else -> Razlog.OK
    }

    /** Ali se ob tem razlogu zapis filtra pozabi (kartica izgine): samo ob pravih 0 izdajah. */
    fun pozabiZapis(razlog: Razlog): Boolean = razlog == Razlog.NI_IZDAJ

    /** Ali obvestilo pove »indeksator ne odgovarja, poskusi čez minuto« namesto »ni na voljo«. */
    fun neOdgovarja(razlog: Razlog): Boolean = razlog == Razlog.MEJA || razlog == Razlog.NI_ODGOVORA

    // ------------------------------------------------------------------ Del C: izdaje, ki jih je nasel filter

    /** Koliko casa velja seznam izdaj, ki ga je nasel filter (klik v tem casu ne vprasa indeksatorja). */
    const val PREDPOMNILNIK_VELJA_MS = 30 * 60_000L

    /** Najvec naslovov v predpomnilniku (LRU) - pomnilnik na TV je skop, vec ne pomaga. */
    const val PREDPOMNILNIK_NAJVEC = 200

    /** Kljuc predpomnilnika: enak kot pri zapisu filtra (id s sezono in epizodo + odtis indeksatorja). */
    fun kljucIzdaj(id: String, odtis: String): String = "$odtis|$id"

    /**
     * Izdaje, ki jih je filter v ozadju ze nasel (lastnik 7. 11. 2026, klik na film z 40 izdajami je dobil »ni izdaj«).
     * SAMO V POMNILNIKU: povezave NZB vsebujejo kljuc indeksatorja - nikoli v SharedPreferences, datoteko ali dnevnik.
     * Zadetek, mlajsi od [veljaMs] in z ISTIM odtisom indeksatorja, pomeni, da klik ne vprasa indeksatorja znova.
     */
    class PredpomnilnikIzdaj(private val veljaMs: Long = PREDPOMNILNIK_VELJA_MS,
                            private val najvec: Int = PREDPOMNILNIK_NAJVEC) {
        private class Zapis(val cas: Long, val odtis: String, val izdaje: List<UsenetPravila.Zadetek>)

        private val vnosi = object : LinkedHashMap<String, Zapis>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Zapis>?): Boolean = size > najvec
        }

        /** Izdaje za [id] z odtisom [odtis], ce so mlajse od [veljaMs]; null = klik naj vprasa indeksator. */
        @Synchronized fun poisci(id: String, odtis: String, zdaj: Long): List<UsenetPravila.Zadetek>? {
            val k = kljucIzdaj(id, odtis)
            val z = vnosi[k] ?: return null
            val starost = zdaj - z.cas
            // Ura naprave se lahko premakne nazaj: zapis iz prihodnosti ni svez. Drug odtis = drug indeksator ali kljuc.
            if (z.odtis != odtis || starost < 0 || starost > veljaMs) { vnosi.remove(k); return null }
            return z.izdaje
        }

        /** Zapomni odgovor indeksatorja (tudi 0 izdaj je odgovor - takrat klik ne vprasa znova). */
        @Synchronized fun shrani(id: String, odtis: String, izdaje: List<UsenetPravila.Zadetek>, zdaj: Long) {
            vnosi[kljucIzdaj(id, odtis)] = Zapis(zdaj, odtis, izdaje)
        }

        /** Zamenjava ali »Odstrani« indeksatorja: povezave starega kljuca ne ostanejo v pomnilniku. */
        @Synchronized fun pocisti() { vnosi.clear() }
        @Synchronized fun velikost(): Int = vnosi.size
    }

    /** Izid iskanja s predpomnilnikom: [izPredpomnilnika] = ni bilo zahteve indeksatorju. */
    class Iskanje(val izdaje: List<UsenetPravila.Zadetek>?, val izPredpomnilnika: Boolean)

    /**
     * Iskanje izdaj ob kliku: najprej predpomnilnik filtra, sicer [poizvedi]. Odgovor (tudi 0 izdaj) se zapomni za
     * [veljaMs]; null = indeksator ni odgovoril in se ne zapomni (naslednji klik poskusi znova).
     */
    fun izPredpomnilnika(p: PredpomnilnikIzdaj, id: String, odtis: String, zdaj: Long,
                         poizvedi: () -> List<UsenetPravila.Zadetek>?): Iskanje {
        p.poisci(id, odtis, zdaj)?.let { return Iskanje(it, true) }
        val izdaje = poizvedi()
        if (izdaje != null) p.shrani(id, odtis, izdaje, zdaj)
        return Iskanje(izdaje, false)
    }
}
