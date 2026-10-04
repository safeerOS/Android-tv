package si.safeer.tv.os

/**
 * Obnova torrentov ob zagonu motorja ([MagnetMotor]).
 *
 * Naprava si ob vsakem torrentu shrani njegov opis (`<hash>.torrent`) in podatke za nadaljevanje (`<hash>.resume`):
 * ponovni zagon zato ne potrebuje ne omrezja ne ponovnega branja ze prenesenih datotek. Torrent brez podatkov za
 * nadaljevanje (prvi zagon po posodobitvi, izgubljena datoteka) mora datoteke na disku enkrat preveriti. Polno
 * preverjanje naenkrat obremeni shrambo toliko, da majhna naprava obstane (testni telefon s 4 GB pomnilnika in 7 GB
 * obdrzanih filmov, 4. 10. 2026: sistem je aplikacijo ubil). Zato preverjanje tece v kratkih korakih s premori, en
 * torrent naenkrat; premor se podaljsa, kadar naprava zamuja ali ji zmanjkuje pomnilnika.
 *
 * Cista pravila brez Androida (tests/ObnovaPravilaTest.kt).
 */
object ObnovaPravila {
    /** Koliko casa naenkrat preverjanje tece. */
    const val TECE_MS = 300L
    /** Najkrajsi premor med koraki: preverjanje tece najvec slabo tretjino casa. */
    const val PREMOR_MS = 700L
    const val PREMOR_NAJVEC_MS = 5_000L
    /** Zamuda niti, po kateri sklepamo, da naprava zastaja. */
    const val ZAMUDA_MS = 200L
    /** Kako pogosto se shrani stanje preverjanja: prekinjeno preverjanje se ob naslednjem zagonu nadaljuje, ne zacne znova. */
    const val SHRANI_MS = 30_000L
    /** Torrent, ki ga je kdo bral v zadnjih toliko ms, velja za predvajanega: preverja se s krajšimi premori. */
    const val V_RABI_MS = 15_000L
    const val PREMOR_V_RABI_MS = 150L
    /** Preverjanje enega torrenta najvec toliko casa (napaka diska ga ne sme drzati v zanki). */
    const val NAJVEC_MS = 3L * 3_600_000

    /** Naslednji premor: [zamuda] = za koliko je nit zamudila zadnji premor, [maloPomnilnika] = sistem javlja stisko. */
    fun premor(prejsnji: Long, zamuda: Long, maloPomnilnika: Boolean): Long = when {
        maloPomnilnika -> PREMOR_NAJVEC_MS
        zamuda > ZAMUDA_MS -> (prejsnji * 2).coerceIn(PREMOR_MS, PREMOR_NAJVEC_MS)
        else -> (prejsnji - 100).coerceIn(PREMOR_MS, PREMOR_NAJVEC_MS)
    }

    /**
     * Stanje, shranjeno MED preverjanjem, ima na repu lahko luknje: kosi, ki so bili ob shranjevanju se v delu, so
     * zapisani kot »nimamo«, stetje preverjenih pa jih ze vsebuje. Ce bi tako stanje vzeli dobesedno, bi preverjanje
     * nadaljevali ZA njimi in bi motor te kose prenesel znova, ceprav so na disku (testni telefon, 4. 10. 2026:
     * v 8 od 12 prekinjenih preverjanj 1-7 kosov). Zato stanje ob obnovi odrezemo pri prvi luknji na repu - od tam
     * naprej se preveri znova. Rep je toliko kosov, kolikor jih je lahko hkrati v delu (z rezervo).
     */
    fun repPreverjanja(dolzinaKosa: Int): Int =
        if (dolzinaKosa <= 0) 1024 else (64L * 1024 * 1024 / dolzinaKosa).toInt().coerceIn(16, 1024)

    /** Koliko kosov shranjenega stanja velja: do prve luknje med zadnjimi [rep] kosi ([imamo] = ali je kos zapisan kot cel). */
    fun veljavnihKosov(velikost: Int, rep: Int, imamo: (Int) -> Boolean): Int {
        if (velikost <= 0) return 0
        for (i in (velikost - rep).coerceAtLeast(0) until velikost) if (!imamo(i)) return i
        return velikost
    }

    /** Po preverjanju: koncan torrent brez »Deli naprej« ostane ustavljen (ne oddajamo), vse drugo tece naprej. */
    fun tecePoPreverjanju(koncan: Boolean, deliNaprej: Boolean): Boolean = !koncan || deliNaprej

    private val HASH = Regex("^[0-9a-f]{40}$")
    private val VRSTE = setOf("torrent", "resume")

    /** Ime datoteke stanja sme nastati samo iz veljavnega hasha (40 malih sestnajstiskih znakov). */
    fun veljavenHash(h: String): Boolean = HASH.matches(h)

    /**
     * Datoteke stanja, ki jih smemo odstraniti: prekinjeni zapisi (`.tmp`) in stanje torrentov, ki niso vec shranjeni.
     * Datotek, ki jih ne poznamo, se ne dotikamo.
     */
    fun ostanki(imena: List<String>, shranjeni: Set<String>): List<String> = imena.filter { ime ->
        val deli = ime.split('.')
        when {
            deli.size == 3 && deli[2] == "tmp" && deli[1] in VRSTE && veljavenHash(deli[0]) -> true
            deli.size == 2 && deli[1] in VRSTE && veljavenHash(deli[0]) -> deli[0] !in shranjeni
            else -> false
        }
    }
}
