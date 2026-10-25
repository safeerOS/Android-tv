package si.safeer.tv.os

/**
 * Pregled Grok 4: kljuc zapisa »Nadaljuj gledanje« ([MediaNapredek]) - vsebina + sezona + epizoda, ne samo naslov.
 * Prej je bil kljuc naslov brez letnice: »Dune (2021)« in »Dune (2024)« sta si delila mesto in oznako »odgledano«, epizode
 * z enakim naslovom tudi. Id vsebine je id brez oznak toka (#<hash>, #t…, #tn…, #preizkus) in pri Stremiu brez naslova
 * dodatka (isti film iz dveh dodatkov je ena vsebina). Brez Androida (preizkus: tests/NapredekKljucTest.kt).
 *
 * Zdruzljivost: zapisi do 2.1.231 nimajo sezone in epizode (samo naslov, ce je v njem SxEy). Tak zapis velja tudi za
 * vsebino z istim naslovom in isto vsebino (ali brez id-ja) - shranjeno mesto se ne izgubi.
 */
object NapredekKljuc {
    data class Opis(val id: String, val naslov: String, val sezona: Int = 0, val epizoda: Int = 0)

    /** Kljuc do 2.1.231: naslov brez letnice in locil (male crke). Ostane za stare oznake »odgledano« in zapise brez id-ja. */
    fun naslova(naslov: String): String = naslov.lowercase().replace(Regex("\\b(19|20)\\d{2}\\b"), "")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private val OZNAKA_TOKA = Regex("#(?:preizkus|tn?-?\\d+|-?\\d+)$")

    /** Id vsebine iz id-ja predvajane skladbe; prazno, ce ga ni. */
    fun vsebine(id: String): String {
        var i = id.trim()
        while (true) { val m = OZNAKA_TOKA.find(i) ?: break; i = i.substring(0, m.range.first) }
        if (i.startsWith("stremio|")) {
            val d = i.split('|')
            if (d.size >= 3) i = d[0] + "|" + d[1] + "|" + d[2]
        }
        return i
    }

    /** (sezona, epizoda): iz polj, sicer iz naslova (»… · S1E2 · …«); null = ni epizoda. */
    fun epizoda(o: Opis): Pair<Int, Int>? =
        if (o.sezona > 0 || o.epizoda > 0) o.sezona to o.epizoda else SerijePravila.epizodaIzNaslova(o.naslov)

    fun kljuc(o: Opis): String {
        val v = vsebine(o.id)
        val e = epizoda(o)?.let { (s, k) -> "|S${s}E$k" }.orEmpty()
        return if (v.isNotEmpty()) "v|$v$e" else "n|" + naslova(o.naslov) + e
    }

    /** Ali shranjeni [zapis] velja za vsebino [s]. */
    fun ujema(zapis: Opis, s: Opis): Boolean {
        if (kljuc(zapis) == kljuc(s)) return true
        // Star zapis brez sezone in epizode: isti naslov in ista vsebina (ali ena brez id-ja).
        if (epizoda(zapis) != null) return false
        val n = naslova(s.naslov)
        if (n.isEmpty() || naslova(zapis.naslov) != n) return false
        val a = vsebine(zapis.id); val b = vsebine(s.id)
        return a.isEmpty() || b.isEmpty() || a == b
    }

    /** Ali je [s] v seznamu odgledanih: nov kljuc ali star kljuc naslova (oznake do 2.1.231). */
    fun odgledano(seznam: List<String>, s: Opis): Boolean = kljuc(s) in seznam || naslova(s.naslov) in seznam

    /** Seznam odgledanih po oznaki [da] za [s] (najnovejsi prvi, najvec [najvec]); »ne« pobrise tudi staro oznako naslova. */
    fun oznaci(seznam: List<String>, s: Opis, da: Boolean, najvec: Int): List<String> {
        val k = kljuc(s)
        return if (da) (listOf(k) + seznam.filter { it != k }).take(najvec)
        else seznam.filter { it != k && it != naslova(s.naslov) }
    }
}
