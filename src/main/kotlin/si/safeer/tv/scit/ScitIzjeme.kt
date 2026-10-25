package si.safeer.tv.scit

/**
 * Webtor rezerva (25. 10. 2026): domene, ki jih Scit ne blokira, tudi ce so na seznamu (seznami pogosto vsebujejo
 * cele vrhnje domene, npr. `.buzz`). Stalne: webtor.io (API, razresevanje toka) in cosmic-crab.buzz (Webtorjeva domena
 * za pretakanje, `abra--<id>.api.cosmic-crab.buzz`). Gostitelj toka se lahko spremeni: [dodaj] doda gostitelja, na
 * katerega je preusmeril tok Webtor (izvedeno iz odgovora 302 na `/resolve/`), za [VELJA_MS], najvec [NAJVEC] hkrati -
 * zapis v datoteki `scit/izjeme.txt` (vrstica: domena TAB velja-do-ms), ki jo bere proces Scita.
 * Ciste funkcije - preizkus tests/ScitIzjemeTest.kt.
 */
object ScitIzjeme {
    val STALNE = listOf("webtor.io", "cosmic-crab.buzz")
    const val NAJVEC = 20
    const val VELJA_MS = 30L * 24 * 3600 * 1000

    fun normaliziraj(ime: String): String = ime.trim().lowercase().trim('.')

    /** Veljavna domena (ne naslov IP, brez praznih delov). */
    fun veljavna(d: String): Boolean = d.length in 4..253 && '.' in d && !d.contains("..") && !d.startsWith("-") &&
        d.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' } && d.any { it in 'a'..'z' }

    /** Domena ali katera od nadrejenih je med izjemami (stalnimi ali [dodatne]). */
    fun dovoljena(ime: String, dodatne: Collection<String>): Boolean {
        val d = normaliziraj(ime)
        if (d.isEmpty()) return false
        return (STALNE.asSequence() + dodatne.asSequence()).any { d == it || d.endsWith(".$it") }
    }

    /** Zapis iz datoteke -> veljavne dodatne izjeme (domena -> velja do). */
    fun preberi(besedilo: String?, zdaj: Long): Map<String, Long> {
        val m = LinkedHashMap<String, Long>()
        for (v in besedilo.orEmpty().lineSequence()) {
            val d = v.split('\t')
            if (d.size != 2) continue
            val g = normaliziraj(d[0]); val t = d[1].trim().toLongOrNull() ?: continue
            if (t > zdaj && veljavna(g)) m[g] = t
        }
        return m
    }

    /** Doda [gostitelj] (velja [VELJA_MS]); null = ni spremembe (neveljaven, ze stalna izjema ali nedavno dodan). */
    fun dodaj(zdaj: Map<String, Long>, gostitelj: String, ura: Long): Map<String, Long>? {
        val g = normaliziraj(gostitelj)
        if (!veljavna(g) || dovoljena(g, emptyList())) return null
        val nov = LinkedHashMap(zdaj.filterValues { it > ura })
        val prej = nov[g]
        if (prej != null && prej - ura > VELJA_MS / 2) return null
        nov[g] = ura + VELJA_MS
        while (nov.size > NAJVEC) nov.remove(nov.minByOrNull { it.value }!!.key)
        return nov
    }

    fun zapis(m: Map<String, Long>): String = m.entries.joinToString("\n") { "${it.key}\t${it.value}" }
}
