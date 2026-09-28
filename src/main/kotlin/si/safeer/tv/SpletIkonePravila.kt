package si.safeer.tv

/** Cista razclenitev ikon iz HTML-ja; brez Androida, da jo lahko preverimo z JVM-testom. */
object SpletIkonePravila {
    data class Kandidat(val href: String, val velikost: Int)

    private val link = Regex("<link\\b[^>]*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val atribut = Regex(
        """([:\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""",
        RegexOption.IGNORE_CASE
    )

    /** Vrne ikone od najboljse do najslabse: SVG, deklarirana velikost, Apple 180, sicer 16. */
    fun kandidati(html: String): List<Kandidat> {
        val najdeni = ArrayList<Kandidat>()
        for (oznaka in link.findAll(html)) {
            val a = HashMap<String, String>()
            for (ujemanje in atribut.findAll(oznaka.value)) {
                a[ujemanje.groupValues[1].lowercase()] = ujemanje.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty()
            }
            val rel = a["rel"].orEmpty().lowercase().split(Regex("\\s+"))
            val href = a["href"].orEmpty().trim()
            if (href.isEmpty() || rel.none { it == "icon" || it == "apple-touch-icon" || it == "apple-touch-icon-precomposed" }) continue
            var velikost = 0
            for (del in a["sizes"].orEmpty().lowercase().split(Regex("\\s+"))) {
                val stranica = del.substringBefore('x').toIntOrNull() ?: continue
                if ('x' in del) velikost = maxOf(velikost, stranica)
            }
            if (rel.any { it.startsWith("apple-touch-icon") } && velikost == 0) velikost = 180
            if (href.substringBefore('?').lowercase().endsWith(".svg") || a["type"].orEmpty().lowercase().contains("svg")) {
                velikost = maxOf(velikost, 256)
            }
            najdeni += Kandidat(href, velikost.coerceAtLeast(16))
        }
        return najdeni.sortedByDescending { it.velikost }.distinctBy { it.href }
    }
}
