package si.safeer.tv.os

object PripravaPravila {
    /** Kartica serije brez izbrane epizode (klik pokaze izbiro epizod); epizoda serije (episode > 0) ni taka. */
    fun serijaBrezEpizode(tip: String, epizoda: Int): Boolean = tip == "series" && epizoda <= 0

    fun odpriTakoj(video: Boolean, rocno: Boolean, jeSerija: Boolean = false): Boolean {
        if (jeSerija) return false
        return video && !rocno
    }

    fun ostaniVPripravi(zagonovObOdprtju: Int, zagonovZdaj: Int, casVPripraviMs: Long): Boolean {
        return zagonovZdaj == zagonovObOdprtju && casVPripraviMs < 45_000L
    }

    fun jePotekelCas(casVPripraviMs: Long): Boolean {
        return casVPripraviMs >= 45_000L
    }
}
