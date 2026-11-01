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
}
