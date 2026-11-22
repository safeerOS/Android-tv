package si.safeer.tv.os

/**
 * Cista pravila za usklajenost zvoka in slike (A/V sync) pri prenosu zaslona racunalnika na TV.
 * Brez Androida, da se da preizkusiti na navadnem JVM.
 */
object ZaslonSinhro {
    /** Pod tem odmikom (vkljucno) ne popravljamo, da drobna nihanja ne povzrocajo trzanja. */
    const val MRTVO_OBMOCJE_MS = 10L

    /** Najvecji dovoljeni popravek ali rocni zamik v obe smeri (ms). */
    const val NAJVEC_ZAMIK_MS = 400L

    /** Ciljna meja usklajenosti: tokova sta usklajena, ce je |odmik| strogo manjsi od te vrednosti. */
    const val CILJ_MS = 20L

    /** Korak rocnega zamika zvoka ob enem pritisku gumba na daljincu (ms). */
    const val KORAK_ROCNO_MS = 10L

    /** Najmanjse stevilo meritev, preden glajenje vrne mediano. */
    const val NAJMANJ_MERITEV = 5

    /**
     * Izracuna trenutni odmik med prikazano slicico in predvajanim zvocnim vzorcem (oba v ms iste ure zajema).
     *
     * Predznak:
     * - pozitivno: slika je NAPREJ (prikazana slicica je novejsa od zvoka, zvok kasni),
     * - negativno: zvok je NAPREJ (zvok je novejsi od prikazane slicice).
     */
    fun odmik(zigSlikeMs: Long, zigZvokaMs: Long): Long = zigSlikeMs - zigZvokaMs

    /** Vrne true, ce sta slika in zvok usklajena znotraj ciljne meje (|odmik| < [CILJ_MS]). */
    fun usklajen(odmikMs: Long): Boolean = odmikMs > -CILJ_MS && odmikMs < CILJ_MS

    /**
     * Izracuna, koliko ms je treba zakasniti enega od tokov, da se slika in zvok uskladita ob upostevanju
     * rocnega zamika zvoka.
     *
     * Predznak rezultata:
     * - pozitivna vrednost (+N): za N ms je treba ZAKASNITI SLIKO (slika je prevec naprej),
     * - negativna vrednost (-N): za N ms je treba ZAKASNITI ZVOK (zvok je prevec naprej),
     * - 0: ostanek je znotraj mrtvega obmocja (|ostanek| <= [MRTVO_OBMOCJE_MS]), popravek ni potreben.
     *
     * Predznak rocnega zamika:
     * - [rocniZamikMs] > 0 pomeni, da je zvok pomaknjen kasneje glede na sliko, zato ga odstejemo od odmika
     *   (ostanek = [odmikMs] - [rocniZamikMs]).
     *
     * Primer: izmerjen odmik je +80 ms (slika je 80 ms naprej), rocni zamik zvoka je +60 ms
     * -> ostanek je 80 - 60 = +20 ms, zato vrne +20 ms (sliko zakasnimo za 20 ms).
     *
     * Rezultat je vedno omejen na obmocje [-[NAJVEC_ZAMIK_MS], [NAJVEC_ZAMIK_MS]].
     */
    fun potrebnaZakasnitev(odmikMs: Long, rocniZamikMs: Long): Long {
        val ostanek = odmikMs - rocniZamikMs
        if (ostanek in -MRTVO_OBMOCJE_MS..MRTVO_OBMOCJE_MS) return 0L
        return ostanek.coerceIn(-NAJVEC_ZAMIK_MS, NAJVEC_ZAMIK_MS)
    }

    /** Omeji rocni zamik zvoka na dovoljeno obmocje [-[NAJVEC_ZAMIK_MS], [NAJVEC_ZAMIK_MS]]. */
    fun omejiRocniZamik(ms: Long): Long = ms.coerceIn(-NAJVEC_ZAMIK_MS, NAJVEC_ZAMIK_MS)

    /** Premakne rocni zamik zvoka za en korak ([KORAK_ROCNO_MS]) gor ali dol in ga omeji na dovoljene meje. */
    fun rocniKorak(trenutno: Long, gor: Boolean): Long =
        omejiRocniZamik(if (gor) trenutno + KORAK_ROCNO_MS else trenutno - KORAK_ROCNO_MS)

    /**
     * Drsece okno zadnjih [velikost] meritev odmika z racunanjem mediane.
     * Mediana je odporna na posamezne osamelce (npr. enkraten skok zaradi zastoja omrezja ali medpomnilnika).
     */
    class Glajenje(val velikost: Int = 15) {
        private val vzorci = LongArray(velikost.also { require(it >= NAJMANJ_MERITEV) { "velikost mora biti vsaj $NAJMANJ_MERITEV, sicer mediana nikoli ne vrne popravka" } })
        private var stevilo = 0
        private var naslednji = 0

        /** Doda novo meritev odmika v drsece okno; ce je okno polno, prepise najstarejso. */
        fun dodaj(odmikMs: Long) {
            vzorci[naslednji] = odmikMs
            naslednji = (naslednji + 1) % velikost
            if (stevilo < velikost) stevilo++
        }

        /**
         * Vrne mediano zadnjih meritev ali null, dokler ni zbranih vsaj [NAJMANJ_MERITEV] (5) meritev.
         */
        fun mediana(): Long? {
            if (stevilo < NAJMANJ_MERITEV) return null
            val urejeno = vzorci.copyOf(stevilo)
            urejeno.sort()
            val sredina = stevilo / 2
            return if (stevilo % 2 == 1) {
                urejeno[sredina]
            } else {
                urejeno[sredina - 1] + (urejeno[sredina] - urejeno[sredina - 1]) / 2L
            }
        }

        /** Izbrise vse zbrane meritve. */
        fun ponastavi() {
            stevilo = 0
            naslednji = 0
        }
    }
}
