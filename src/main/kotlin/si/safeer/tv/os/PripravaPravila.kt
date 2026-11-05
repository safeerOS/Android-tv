package si.safeer.tv.os

object PripravaPravila {
    /** Kartica serije brez izbrane epizode (klik pokaze izbiro epizod); epizoda serije (episode > 0) ni taka. */
    // TV 1. 11. 2026 19:17 (»Silo«): kartice serij imajo mediaType »tvseries« (Stremio.kotSkladba), ne »series« - serija se je
    // odprla kot film. Velja oboje.
    fun serijaBrezEpizode(tip: String, epizoda: Int): Boolean = (tip == "series" || tip == "tvseries") && epizoda <= 0

    fun odpriTakoj(video: Boolean, rocno: Boolean, jeSerija: Boolean = false): Boolean {
        if (jeSerija) return false
        return video && !rocno
    }

    /**
     * TV 1. 11. 2026 18:26 (Gospodar prstanov prek Useneta): zagon se je stel, predvajalnik pa se ni bil nastavljen (null) -
     * zaslon se je zaprl in film je obstal v mini predvajalniku. V pripravi ostanemo, dokler predvajalnik in skladba nista
     * na voljo (do roka 45 s).
     */
    fun ostaniVPripravi(zagonovObOdprtju: Int, zagonovZdaj: Int, casVPripraviMs: Long, predvajalnikNaVoljo: Boolean = true): Boolean {
        return casVPripraviMs < 45_000L && (zagonovZdaj == zagonovObOdprtju || !predvajalnikNaVoljo)
    }

    fun jePotekelCas(casVPripraviMs: Long): Boolean {
        return casVPripraviMs >= 45_000L
    }

    /**
     * Pregled Medijskega centra 5. 11. 2026 (2): kljuc priprave je vsebina S sezono in epizodo. Prej samo id - sk.copy(season,
     * episode) id ohrani, zato so imele vse epizode serije isti kljuc (klik na drugo epizodo med pripravo ni naredil nic).
     */
    fun kljucPriprave(id: String, povezava: String, naslov: String, sezona: Int, epizoda: Int): String {
        val osnova = id.ifBlank { povezava.ifBlank { naslov } }
        return if (sezona > 0 || epizoda > 0) "$osnova#S${sezona}E$epizoda" else osnova
    }

    /**
     * Pregled Medijskega centra 5. 11. 2026 (1, 2): priprave v teku z zetonom posamezne priprave. Isti klik med pripravo ne
     * zazene se ene; po preklicu stara priprava ([koncaj] vrne false) ne odpre nicesar in ne konca nove priprave iste vsebine.
     */
    class Priprave {
        private val vTeku = java.util.concurrent.ConcurrentHashMap<String, String>()
        private val stevec = java.util.concurrent.atomic.AtomicLong()

        /** Zeton nove priprave ali null, ce ista vsebina ze tece. */
        fun zacni(kljuc: String): String? {
            val zeton = kljuc + LOCILO + stevec.incrementAndGet()
            return if (vTeku.putIfAbsent(kljuc, zeton) == null) zeton else null
        }

        /** Konca pripravo; true samo, ce je bila ta priprava se v teku (ni preklicana ali zamenjana). */
        fun koncaj(zeton: String): Boolean = vTeku.remove(zeton.substringBeforeLast(LOCILO), zeton)

        fun aktivna(zeton: String): Boolean = vTeku[zeton.substringBeforeLast(LOCILO)] == zeton

        fun preklici() = vTeku.clear()

        val size: Int get() = vTeku.size
        fun isEmpty(): Boolean = vTeku.isEmpty()
        fun isNotEmpty(): Boolean = !isEmpty()

        private companion object { const val LOCILO = '\u0000' }
    }
}
