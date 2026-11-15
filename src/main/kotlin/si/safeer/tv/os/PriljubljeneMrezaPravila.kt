package si.safeer.tv.os

/**
 * Lastnik 15. 11. 2026: »Priljubljene serije in filmi bi morali biti enostavno dosegljivi pri filtru, kjer je zdaj
 * Priporoceno«. Izbira »Priljubljene« v razvrstitvi mreze Filmi | Serije pokaze samo filme (ali serije), ki jih je
 * uporabnik oznacil s srcem (shramba: [MedijskiViri.priljubljene]); ni nova shramba. Cista pravila brez Androida
 * (preizkus: tests/PriljubljeneMrezaPravilaTest.kt).
 */
object PriljubljeneMrezaPravila {
    /** Izbira je ponujena samo, ce je v tej mrezi kaj priljubljenega - prazna izbira bi bila lazna. */
    fun ponudi(stevilo: Int): Boolean = stevilo > 0

    /**
     * Lastnik 15. 11. 2026 (dosegljivost, sicer ni koristi): izbira Priljubljene je v filtru PRVA, pred Priporoceno, da je
     * vidna takoj ob odprtju okna izbire; brez priljubljenih je ni.
     */
    fun <T> vrstniRed(moznosti: List<T>, priljubljena: T, stevilo: Int): List<T> =
        if (ponudi(stevilo)) listOf(priljubljena) + moznosti else moznosti

    /** Koliko premikov v oknu izbire loci [izbrana] od [cilj] (okno se odpre na izbrani); -1, ce cilja ni v redu. */
    fun <T> premikov(red: List<T>, izbrana: T, cilj: T): Int {
        val a = red.indexOf(izbrana); val b = red.indexOf(cilj)
        return if (a < 0 || b < 0) -1 else kotlin.math.abs(a - b)
    }

    /**
     * Razvrstitev, ki jo mreza res uporabi: izbrane »Priljubljene«, ko ni vec nobene (uporabnik je zadnjo odstranil), se
     * vrnejo na [privzeta]; sicer ostane izbrana.
     */
    fun veljavna(izbrana: Int, priljubljena: Int, stevilo: Int, privzeta: Int): Int =
        if (izbrana == priljubljena && !ponudi(stevilo)) privzeta else izbrana

    /**
     * Cela vsebina (film ali serija, ne posamezna epizoda): epizoda iste serije bi v mrezi serij odprla epizodo namesto
     * serije. Jeziki in drugi filtri mreze veljajo naprej.
     */
    fun jeCelaVsebina(sezona: Int, epizoda: Int): Boolean = sezona <= 0 && epizoda <= 0

    /**
     * Kartice mreze v vrstnem redu priljubljenih (shramba ima najnovejse oznacene prvo). Kartica iz kataloga ima prednost
     * pred shranjeno kopijo (svezi podatki, plakat); priljubljena, ki je katalog zdaj ne vrne, ostane s shranjeno kopijo,
     * da je dosegljiva tudi, ko se katalog se nalaga. Isti id se ne ponovi.
     */
    fun <T> uredi(katalog: List<T>, priljubljene: List<T>, id: (T) -> String, jeTaVrste: (T) -> Boolean): List<T> {
        val izKataloga = HashMap<String, T>()
        for (k in katalog) izKataloga.putIfAbsent(id(k), k)
        val videni = HashSet<String>()
        val izhod = ArrayList<T>()
        for (p in priljubljene) {
            if (!jeTaVrste(p) || !videni.add(id(p))) continue
            izhod += izKataloga[id(p)] ?: p
        }
        return izhod
    }
}
