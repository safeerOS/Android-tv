package si.safeer.tv.os

/**
 * Usklajevanje seznamov predvajanja med napravami v Safeer Linku - cista pravila (brez Androida), enaka kot v
 * Safeer OS za racunalnik (core/os_media.py). Seznam je dolocen z imenom; velja zadnja sprememba (`cas`, ms).
 * Izbris si naprava zapomni s casom, da se seznam ne vrne z naprave, ki ga se ima. Preverja tests/SeznamiPravilaTest.kt.
 */
object SeznamiPravila {
    /** Skladba v skupnem zapisu: posnetek (YouTube) ali naslov + izvajalec (posnetek se poisce ob predvajanju) ali neposredni naslov. */
    data class Zapis(val naslov: String, val izvajalec: String, val youtube: String = "", val sekund: Int = 0,
                     val slika: String = "", val url: String = "", val video: Boolean = false,
                     /** Poln zapis naprave, ki je skladbo dodala (Android): druge naprave ga le prenesejo naprej. */
                     val izvirnik: String = "") {
        /** Ista skladba: izvajalec in naslov; brez njiju posnetek ali naslov datoteke. */
        val kljuc: String get() = when {
            naslov.isNotBlank() -> "n:" + izvajalec.trim().lowercase() + "|" + naslov.trim().lowercase()
            youtube.isNotBlank() -> "yt:$youtube"
            else -> "u:$url"
        }
    }

    /** Seznam brez skladb: ime, zadnja sprememba in stevilo skladb. */
    data class Glava(val ime: String, val cas: Long, val stevilo: Int)

    enum class Korak { NIC, PREVZEMI, ZDRUZI }

    /**
     * Kaj storiti s seznamom druge naprave [tuj]. [moj] = seznam z istim imenom tukaj (ali null), [izbrisan] = kdaj smo
     * ga tukaj izbrisali (0 = nikoli). Novejsi zmaga; ob enakem casu in razlicnem stevilu skladb seznama zdruzimo
     * (seznami iz casa pred usklajevanjem imajo cas 0).
     */
    fun korak(moj: Glava?, izbrisan: Long, tuj: Glava): Korak = when {
        tuj.stevilo <= 0 -> Korak.NIC
        moj == null -> if (izbrisan > 0 && izbrisan >= tuj.cas) Korak.NIC else Korak.PREVZEMI
        tuj.cas > moj.cas -> Korak.PREVZEMI
        tuj.cas == moj.cas && tuj.stevilo != moj.stevilo -> Korak.ZDRUZI
        else -> Korak.NIC
    }

    /** Izbris na drugi napravi velja tudi tukaj, ce seznama od takrat nismo spremenili. */
    /**
     * Cas nove krajevne spremembe ali izbrisa: nikoli starejsi od prejsnjega stanja seznama ([prej]) in od znanega
     * izbrisa ([izbrisan]). Ure naprav niso enake - brez tega bi naprava z zaostalo uro spremenila seznam »v
     * preteklosti« in bi druga naprava njeno spremembo prezrla ali znova uveljavila star izbris.
     */
    fun novCas(zdaj: Long, prej: Long, izbrisan: Long): Long = maxOf(zdaj, prej + 1, izbrisan + 1)

    fun izbrisVelja(mojCas: Long, casIzbrisa: Long) = casIzbrisa > 0 && casIzbrisa >= mojCas

    /** Moje skladbe in za njimi tuje, ki jih se nimam (vrstni red obeh ostane). */
    fun zdruzi(moje: List<Zapis>, tuje: List<Zapis>, najvec: Int): List<Zapis> {
        val imam = moje.map { it.kljuc }.toHashSet()
        return (moje + tuje.filter { imam.add(it.kljuc) }).take(najvec)
    }

    /**
     * Prevzeti seznam: tuje skladbe, a posnetek, ki smo ga za isto skladbo tukaj ze nasli (tuja ga se nima), ostane -
     * naprava ne isce znova tistega, kar ze ve.
     */
    fun prevzemi(moje: List<Zapis>, tuje: List<Zapis>, najvec: Int): List<Zapis> {
        val znani = moje.filter { it.youtube.isNotBlank() }.associateBy { it.kljuc }
        return tuje.map { t ->
            val m = if (t.youtube.isBlank() && t.url.isBlank()) znani[t.kljuc] else null
            if (m != null) t.copy(youtube = m.youtube, slika = m.slika.ifBlank { t.slika }) else t
        }.distinctBy { it.kljuc }.take(najvec)
    }

    private val RX_POSNETEK = Regex("(?:[?&]v=|youtu\\.be/|/embed/|/shorts/)([A-Za-z0-9_-]{6,20})")

    /** Id posnetka iz naslova strani YouTuba (watch?v=, youtu.be/, embed, shorts); prazno, ce to ni posnetek. */
    fun youtubePosnetek(url: String): String {
        val gostitelj = Regex("^https?://([^/:?#]+)").find(url.trim())?.groupValues?.get(1)?.lowercase() ?: return ""
        if (gostitelj != "youtu.be" && gostitelj != "youtube.com" && !gostitelj.endsWith(".youtube.com")) return ""
        return RX_POSNETEK.find(url)?.groupValues?.get(1).orEmpty()
    }
}
