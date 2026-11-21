package si.safeer.tv.os

/**
 * Naročnine YouTube (lastnik 20. 11. 2026: pod glasbo mora biti tudi seznam všečkanih kanalov): kanale prebere
 * YouTubeUvoz s prijavo, nove videe vsakega kanala pa javni vir RSS (brez prijave, stabilen zapis).
 * Tu so samo čista pravila brez Androida; preizkus: tests/YouTubeNarocnineTest.kt.
 */
object YouTubeNarocnine {
    const val NAJVEC_KANALOV = 25
    const val VIDEOV_NA_KANAL = 3

    class Video(val id: String, val naslov: String, val kanal: String)

    private val ID_KANALA = Regex("^UC[A-Za-z0-9_-]{22}$")
    private val ID_VIDEA = Regex("^[A-Za-z0-9_-]{11}$")

    fun jeIdKanala(s: String) = ID_KANALA.matches(s)

    fun jeIdVidea(s: String) = ID_VIDEA.matches(s)

    /** Naslov feeda za kanal; id mora biti veljaven (v naslov ne pride nic drugega). */
    fun naslovFeeda(idKanala: String): String? =
        if (jeIdKanala(idKanala)) "https://www.youtube.com/feeds/videos.xml?channel_id=$idKanala" else null

    private fun odkodiraj(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&").trim()

    /** Najnovejši videi iz RSS feeda kanala (največ [VIDEOV_NA_KANAL]); neveljavni vnosi se preskočijo. */
    fun videiIzFeeda(xml: String, najvec: Int = VIDEOV_NA_KANAL): List<Video> {
        val izhod = ArrayList<Video>()
        val vnosi = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
        for (m in vnosi) {
            if (izhod.size >= najvec) break
            val b = m.groupValues[1]
            val id = Regex("<yt:videoId>([^<]*)</yt:videoId>").find(b)?.groupValues?.get(1)?.trim().orEmpty()
            if (!ID_VIDEA.matches(id)) continue
            val naslov = Regex("<title>([^<]*)</title>").find(b)?.groupValues?.get(1)?.let { odkodiraj(it) }.orEmpty()
            if (naslov.isBlank()) continue
            val kanal = Regex("<name>([^<]*)</name>").find(b)?.groupValues?.get(1)?.let { odkodiraj(it) }.orEmpty()
            izhod.add(Video(id, naslov.take(200), kanal.take(100)))
        }
        return izhod
    }
}
