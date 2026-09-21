package si.safeer.tv.os

import java.text.Normalizer

/**
 * Enotno iskanje Safeer Media (»dodaj vir in pozabi nanj«): zadetki z vseh virov - ta naprava,
 * naprave v Safeer Linku, dodani viri, Jamendo, PeerTube, radio - dobijo isto oceno ujemanja s
 * poizvedbo, isti kljuc za dvojnike in skupen vrstni red. Splet je le rezerva, kadar dobrih
 * zadetkov ni dovolj. Brez Androida (test: tests/RelevantnostTest.kt).
 */
object Relevantnost {
    /** Od te ocene naprej je zadetek dober (vse besede poizvedbe so v naslovu, izvajalcu ali poti). */
    const val DOBER = 0.75
    /** Toliko dobrih zadetkov iz lastnih virov je dovolj, da splet ni potreben. */
    const val DOVOLJ_DOBRIH = 3
    /** Pod to oceno zadetek ne sodi med najboljse (samo ena od vec besed se ujema). */
    const val SPODNJA = 0.5

    private val OZNAKE = Regex("\\p{Mn}+")
    private val NE_CRKA = Regex("[^\\p{L}\\p{N}]+")
    private val KONCNICA = Regex("\\.(mp3|flac|m4a|ogg|oga|opus|wav|aac|wma|mp4|mkv|webm|avi|mov|m4v|ts|mpg|mpeg)$", RegexOption.IGNORE_CASE)
    /** Besede, ki ne povedo, kaj uporabnik isce (pri kljucu za dvojnike in pri poizvedbi). */
    private val SUM = setOf("the", "a", "an", "feat", "ft", "official", "video", "audio", "lyrics", "hd", "remastered", "remaster", "mv")

    fun normaliziraj(s: String): String =
        NE_CRKA.replace(OZNAKE.replace(Normalizer.normalize(KONCNICA.replace(s.trim(), "").lowercase(), Normalizer.Form.NFD), ""), " ").trim()

    fun besede(s: String): List<String> = normaliziraj(s).split(' ').filter { it.isNotEmpty() }

    private fun poizvedba(q: String): List<String> {
        val b = besede(q)
        return b.filterNot { it in SUM }.ifEmpty { b }
    }

    private fun ujema(beseda: String, v: List<String>) = v.any { it == beseda || (beseda.length >= 3 && it.startsWith(beseda)) }

    /**
     * Ocena 0..~1.5: delez besed poizvedbe v naslovu in izvajalcu (beseda v poti ali opisu, npr. mapa
     * izvajalca na racunalniku, velja 0,8), plus bonus za natancno ujemanje (izvajalec + naslov).
     */
    fun ocena(q: String, naslov: String, izvajalec: String, dodatno: String = ""): Double {
        val iscem = poizvedba(q)
        if (iscem.isEmpty()) return 0.0
        val glavno = besede("$naslov $izvajalec")
        val vse = glavno + besede(dodatno)
        val vGlavnem = iscem.count { ujema(it, glavno) }
        val vsem = iscem.count { ujema(it, vse) }
        var o = (vGlavnem + 0.8 * (vsem - vGlavnem)) / iscem.size
        val cela = iscem.joinToString(" ")
        val n = poizvedba(naslov).joinToString(" ")
        when {
            n == cela || poizvedba("$izvajalec $naslov").joinToString(" ") == cela ||
                poizvedba("$naslov $izvajalec").joinToString(" ") == cela -> o += 0.5
            vGlavnem == iscem.size && iscem.size > 1 -> o += 0.2
        }
        if (vsem == iscem.size && iscem.size == 1 && besede(naslov).firstOrNull() == iscem[0]) o += 0.1
        return o
    }

    /**
     * Ime datoteke brez oznak (»JOHN LENNON - IMAGINE.ogg«) razdeli na izvajalca in naslov; ce
     * izvajalec ze obstaja ali locila ni, ostane tako. Vrne (naslov, izvajalec).
     */
    fun razdeli(naslov: String, izvajalec: String): Pair<String, String> {
        val cist = KONCNICA.replace(naslov.trim(), "")
        if (izvajalec.isNotBlank()) return cist to izvajalec
        val i = cist.indexOf(" - ")
        if (i <= 0 || i >= cist.length - 3) return cist to izvajalec
        return cist.substring(i + 3).trim() to cist.substring(0, i).trim().trimStart { it.isDigit() || it == '.' || it == ' ' }
    }

    /** Kljuc za dvojnike: iste besede izvajalca in naslova ne glede na vrstni red, stevilke skladb in sum. */
    fun kljuc(naslov: String, izvajalec: String): String =
        besede("$izvajalec $naslov").filter { it !in SUM && !it.all(Char::isDigit) }.sorted().joinToString(" ")

    /** Zadetek iz kateregakoli vira: [prednost] odloca med dvojniki (manjsa = blizje uporabniku). */
    data class Zadetek<T>(val stvar: T, val naslov: String, val izvajalec: String, val izvor: String,
                          val prednost: Int, val dodatno: String = "")

    /**
     * Zdruzi, odstrani dvojnike (obdrzi zadetek z najmanjso prednostjo - ta naprava pred racunalnikom,
     * racunalnik pred spletnimi viri) in razvrsti po oceni, pri enaki oceni po prednosti.
     */
    fun razvrsti(q: String, vsi: List<Zadetek<*>>): List<Pair<Zadetek<*>, Double>> {
        val ocenjeni = vsi.map { it to ocena(q, it.naslov, it.izvajalec, it.dodatno) }
        val najboljsi = LinkedHashMap<String, Pair<Zadetek<*>, Double>>()
        for (p in ocenjeni.sortedWith(compareBy<Pair<Zadetek<*>, Double>> { it.first.prednost }.thenByDescending { it.second })) {
            val k = kljuc(p.first.naslov, p.first.izvajalec).ifEmpty { p.first.izvor + ":" + p.first.naslov }
            val obstojeci = najboljsi[k]
            if (obstojeci == null || p.second > obstojeci.second + 0.3) najboljsi[k] = p
        }
        return najboljsi.values.sortedWith(compareByDescending<Pair<Zadetek<*>, Double>> { it.second }.thenBy { it.first.prednost })
    }

    /** Ali lastni viri nimajo dovolj dobrih zadetkov - takrat (in samo takrat) ponudimo splet. */
    fun potrebujemSplet(razvrsceni: List<Pair<Zadetek<*>, Double>>): Boolean =
        razvrsceni.count { it.second >= DOBER } < DOVOLJ_DOBRIH
}
