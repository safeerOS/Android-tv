package si.safeer.tv.os

object PripravaPravila {
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
