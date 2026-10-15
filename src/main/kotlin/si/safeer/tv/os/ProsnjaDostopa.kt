package si.safeer.tv.os

/**
 * Kaj pomeni zavrnitev, ko ta naprava prosi drugo (racunalnik) za zaslon, datoteke ... Cista pravila brez Androida
 * (tests/ProsnjaDostopaTest.kt); besedila in ponovni poskusi so v [DostopNaprave].
 *
 * Ozadje (lastnik, 15. 10. 2026): telefon je ob odpiranju zaslona racunalnika pokazal »racunalnik zaslona ne deli,
 * vklopi Zaslon za televizor«, racunalnik pa je v resnici cakal, da uporabnik na njem potrdi dostop te naprave
 * (core/link_dostop.py: obvestilo »Dovoli / Zavrni«). Oba primera imata kodo "ni_dovoljeno"; razlikuje ju polje
 * "access_request", ki ga racunalnik doda samo zavrnitvi zaradi dostopa naprave:
 *  - "pending": prosnja caka na racunalniku - povemo, kje jo potrdi, in zahtevo ponavljamo, dokler je ne;
 *  - "declined": uporabnik jo je na racunalniku zavrnil (24 ur ga ne sprasuje vec);
 *  - "none": prosnje ni mogoce pokazati (npr. starejsa pot brez zascite) - dostop se dovoli rocno na racunalniku.
 * Brez polja in brez razloga "access" je zavrnitev stara: racunalnik zaslona sploh ne deli (stikalo izklopljeno).
 */
object ProsnjaDostopa {
    enum class Stanje {
        /** Racunalnik caka na potrditev uporabnika. */
        CAKA,
        /** Uporabnik je na racunalniku pritisnil Zavrni. */
        ZAVRNJENO,
        /** Napravi dostop ni odprt in prosnje ni (dovoli se rocno na racunalniku). */
        NI_DOSTOPA,
        /** Stara zavrnitev brez dostopa naprave: deljenje je na racunalniku izklopljeno. */
        IZKLOPLJENO,
    }

    const val KODA_NI_DOVOLJENO = "ni_dovoljeno"

    /**
     * Parameter ukaza za zahteve, ki jih naprava poslje v ozadju (predpomnjenje, stanje kartic) in ne na uporabnikov
     * ukaz: racunalnik ob zavrnitvi zanje ne odpre obvestila »prosi za dostop« (core/link_dostop.py, v_ozadju).
     * Parameter obvestilo samo prepreci, dostopa nikoli ne odpre.
     */
    const val V_OZADJU = "background"

    /** Na toliko ponovimo zahtevo, dokler prosnja caka. */
    const val PONOVI_MS = 3_000L

    /** Tako dolgo cakamo na potrditev; potem povemo, da je ni bilo (prosnja na racunalniku ostane). */
    const val NAJDLJE_MS = 120_000L

    /**
     * Stanje zavrnitve iz polj odgovora: [koda] ("code"), [razlog] ("data.reason") in [prosnja] ("access_request" v
     * odgovoru ali v "data"). null: odgovor ni zavrnitev dostopa (uspeh ali druga napaka).
     */
    fun stanje(koda: String?, razlog: String?, prosnja: String?): Stanje? {
        val p = prosnja?.trim()?.lowercase().orEmpty()
        val dostop = razlog?.trim()?.lowercase() == "access"
        if (koda?.trim() != KODA_NI_DOVOLJENO && !dostop && p != "pending" && p != "declined") return null
        return when (p) {
            "pending" -> Stanje.CAKA
            "declined" -> Stanje.ZAVRNJENO
            // "none" ali neznana vrednost: dostop naprave manjka, vprasanja ni.
            "" -> if (dostop) Stanje.NI_DOSTOPA else Stanje.IZKLOPLJENO
            else -> Stanje.NI_DOSTOPA
        }
    }

    /** Cez koliko ms naj gre naslednji poskus; null, ko je cakanja konec. Ura, ki skoci nazaj, ne podaljsa cakanja. */
    fun naslednjiPoskus(zacetek: Long, zdaj: Long): Long? {
        val minilo = zdaj - zacetek
        if (minilo < 0 || minilo >= NAJDLJE_MS) return null
        return minOf(PONOVI_MS, NAJDLJE_MS - minilo)
    }

    /** Preostali cas cakanja za prikaz ("1:57"). */
    fun preostalo(zacetek: Long, zdaj: Long): String {
        val s = ((NAJDLJE_MS - (zdaj - zacetek)).coerceIn(0L, NAJDLJE_MS) + 999L) / 1000L
        return "%d:%02d".format(s / 60, s % 60)
    }
}
