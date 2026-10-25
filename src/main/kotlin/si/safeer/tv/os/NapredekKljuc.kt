package si.safeer.tv.os

/**
 * Pregled Grok 4: kljuc zapisa »Nadaljuj gledanje« ([MediaNapredek]) - vsebina + sezona + epizoda, ne samo naslov.
 * Prej je bil kljuc naslov brez letnice: »Dune (2021)« in »Dune (2024)« sta si delila mesto in oznako »odgledano«, epizode
 * z enakim naslovom tudi. Id vsebine je id brez oznak toka (#<hash>, #t…, #tn…, #preizkus - samo pri Stremiu in prenosih
 * racunalnika) in pri Stremiu brez naslova dodatka (isti film iz dveh dodatkov je ena vsebina); ce je znan IMDb, je vsebina
 * IMDb (isti film iz dveh katalogov). Videi kanala in epizode brez stevilk imajo isti id (kanal oziroma serija) - zanje je v
 * kljucu se naslov videa. Brez Androida (preizkus: tests/NapredekKljucTest.kt).
 *
 * Zdruzljivost: zapisi do 2.1.231 nimajo sezone in epizode (samo naslov, ce je v njem SxEy). Tak zapis velja tudi za
 * vsebino z istim naslovom in isto vsebino (ali brez id-ja) - shranjeno mesto se ne izgubi.
 */
object NapredekKljuc {
    data class Opis(val id: String, val naslov: String, val sezona: Int = 0, val epizoda: Int = 0, val imdb: String = "")

    /** Kljuc do 2.1.231: naslov brez letnice in locil (male crke). Ostane za stare oznake »odgledano« in zapise brez id-ja. */
    fun naslova(naslov: String): String = naslov.lowercase().replace(Regex("\\b(19|20)\\d{2}\\b"), "")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private val OZNAKA_TOKA = Regex("#(?:preizkus|tn?-?\\d+|-?\\d+)$")
    private val IMDB = Regex("^tt\\d+$")

    /** Pregled 8: oznake toka imajo samo id-ji Stremia in prenosov racunalnika - drugje je »#2« lahko del id-ja (pot). */
    private fun imaOznakeToka(id: String) = id.startsWith("stremio|") || id.startsWith(KnjiznicaKroga.PREDPONA_PRENOSA)

    /** Id vsebine iz id-ja predvajane skladbe; prazno, ce ga ni. */
    fun vsebine(id: String): String {
        var i = id.trim()
        if (imaOznakeToka(i)) while (true) { val m = OZNAKA_TOKA.find(i) ?: break; i = i.substring(0, m.range.first) }
        if (i.startsWith("stremio|")) {
            val d = i.split('|')
            if (d.size >= 3) i = d[0] + "|" + d[1] + "|" + d[2]
        }
        return i
    }

    /** Vsebina opisa: IMDb, ce je znan (isti film iz dveh katalogov), sicer [vsebine] id-ja. */
    fun vsebine(o: Opis): String = o.imdb.trim().takeIf { IMDB.matches(it) }?.let { "imdb|$it" } ?: vsebine(o.id)

    /** (sezona, epizoda): iz polj, sicer iz naslova (»… · S1E2 · …«); null = ni epizoda. */
    fun epizoda(o: Opis): Pair<Int, Int>? =
        if (o.sezona > 0 || o.epizoda > 0) o.sezona to o.epizoda else SerijePravila.epizodaIzNaslova(o.naslov)

    /** Pregled 1: Stremio, ki ni film (kanal, serija) - brez stevilke epizode id ne loci videov, zato v kljuc gre se naslov. */
    private fun potrebujeNaslov(o: Opis) = o.id.startsWith("stremio|") && o.id.removePrefix("stremio|").substringBefore('|') != "movie"

    fun kljuc(o: Opis): String {
        val v = vsebine(o)
        val e = epizoda(o)?.let { (s, k) -> "|S${s}E$k" }.orEmpty()
        if (v.isEmpty()) return "n|" + naslova(o.naslov) + e
        val t = if (e.isEmpty() && potrebujeNaslov(o)) "|" + naslova(o.naslov) else ""
        return "v|$v$e$t"
    }

    /** Kljuc in kljuc po id-ju (brez IMDb) - zapis brez IMDb (do 2.1.231) se ujema z istim id-jem, ki ima IMDb. */
    private fun kljuci(o: Opis): Set<String> = setOf(kljuc(o), kljuc(o.copy(imdb = "")))

    /** Ali shranjeni [zapis] velja za vsebino [s]. */
    fun ujema(zapis: Opis, s: Opis): Boolean {
        if (kljuci(zapis).any { it in kljuci(s) }) return true
        // Star zapis brez sezone in epizode: isti naslov in ista vsebina (po id-ju ali IMDb) ali ena brez id-ja.
        if (epizoda(zapis) != null) return false
        val n = naslova(s.naslov)
        if (n.isEmpty() || naslova(zapis.naslov) != n) return false
        val a = setOf(vsebine(zapis.id), vsebine(zapis)) - ""
        val b = setOf(vsebine(s.id), vsebine(s)) - ""
        return a.isEmpty() || b.isEmpty() || a.any { it in b }
    }

    /** Ali je [s] v seznamu odgledanih: nov kljuc ali star kljuc naslova (oznake do 2.1.231). */
    fun odgledano(seznam: List<String>, s: Opis): Boolean = kljuci(s).any { it in seznam } || naslova(s.naslov) in seznam

    /**
     * Seznam odgledanih po oznaki [da] za [s] (najnovejsi prvi, najvec [najvec]). Stara oznaka naslova te vsebine izgine v obeh
     * primerih (pregled 7: ob »da« se preseli na nov kljuc in ne velja vec za drugo vsebino z istim naslovom).
     */
    fun oznaci(seznam: List<String>, s: Opis, da: Boolean, najvec: Int): List<String> {
        val k = kljuc(s)
        val brez = seznam.filter { it != k && it != naslova(s.naslov) }
        return if (da) (listOf(k) + brez).take(najvec) else brez
    }
}
