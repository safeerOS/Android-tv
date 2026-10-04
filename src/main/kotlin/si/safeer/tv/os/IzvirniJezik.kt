package si.safeer.tv.os

/**
 * Izvirni jezik filmov in serij za filter po jeziku vsebine (mreza Filmi | Serije).
 *
 * Katalogi (Cinemeta in dodatki) jezika naslova ne povedo. Vprasamo Wikidato - prosto zbirko znanja, brez kljuca in
 * brez racuna: po id-ju IMDb vrne jezike naslova (P364) in drzave izvora (P495). Naslov ima lahko vec jezikov
 * (film, v katerem govorijo anglesko, francosko in japonsko); filter velja za GLAVNEGA: jezik, ki je doma v eni od
 * drzav izvora. Za izbrani jezik zna Wikidata nasteti tudi naslove same (po prepoznavnosti), zato filter najde filme,
 * ki jih splosni katalogi na prvih straneh nimajo.
 *
 * Cista pravila brez Androida (tests/IzvirniJezikTest.kt): seznam jezikov, poizvedbi, branje odgovora (TSV) in izbira
 * glavnega jezika. Omrezje in predpomnilnik sta v [IzvirniJeziki].
 */
object IzvirniJezik {
    /** [koda] ISO 639-1; [predmeti] = jezik v Wikidati (Q...); [drzave] = drzave (Q...), kjer je to jezik domacih filmov. */
    class Jezik(val koda: String, val predmeti: List<String>, val drzave: List<String>)

    /** Jeziki, ki jih ponudi izbira. Predmeti so preverjeni v Wikidati (4. 10. 2026). */
    val JEZIKI: List<Jezik> = listOf(
        Jezik("sl", listOf("Q9063"), listOf("Q215", "Q36704", "Q83286")),
        Jezik("en", listOf("Q1860"), listOf("Q30", "Q145", "Q16", "Q408", "Q27", "Q664")),
        Jezik("de", listOf("Q188"), listOf("Q183", "Q713750", "Q16957", "Q40", "Q39")),
        Jezik("fr", listOf("Q150"), listOf("Q142", "Q31", "Q39", "Q16")),
        Jezik("es", listOf("Q1321"), listOf("Q29", "Q96", "Q414", "Q739", "Q298", "Q419", "Q717", "Q241", "Q77")),
        Jezik("it", listOf("Q652"), listOf("Q38", "Q39")),
        Jezik("pt", listOf("Q5146"), listOf("Q45", "Q155")),
        // Srbohrvascina (Q9301) je jezik starejsih jugoslovanskih filmov: sodi k hrvascini, srbscini in bosanscini.
        Jezik("hr", listOf("Q6654", "Q9301"), listOf("Q224", "Q36704", "Q83286")),
        Jezik("sr", listOf("Q9299", "Q9301"), listOf("Q403", "Q37024", "Q838261", "Q236", "Q36704", "Q83286")),
        Jezik("bs", listOf("Q9303", "Q9301"), listOf("Q225", "Q36704", "Q83286")),
        Jezik("mk", listOf("Q9296"), listOf("Q221", "Q36704", "Q83286")),
        Jezik("ru", listOf("Q7737"), listOf("Q159", "Q15180")),
        Jezik("pl", listOf("Q809"), listOf("Q36")),
        Jezik("cs", listOf("Q9056"), listOf("Q213", "Q33946")),
        Jezik("sk", listOf("Q9058"), listOf("Q214", "Q33946")),
        Jezik("hu", listOf("Q9067"), listOf("Q28")),
        Jezik("nl", listOf("Q7411"), listOf("Q55", "Q31")),
        Jezik("sv", listOf("Q9027"), listOf("Q34")),
        Jezik("da", listOf("Q9035"), listOf("Q35")),
        Jezik("no", listOf("Q9043", "Q25167", "Q25164"), listOf("Q20")),
        Jezik("fi", listOf("Q1412"), listOf("Q33")),
        Jezik("is", listOf("Q294"), listOf("Q189")),
        Jezik("tr", listOf("Q256"), listOf("Q43")),
        Jezik("el", listOf("Q9129", "Q36510"), listOf("Q41")),
        Jezik("ro", listOf("Q7913"), listOf("Q218")),
        Jezik("bg", listOf("Q7918"), listOf("Q219")),
        Jezik("sq", listOf("Q8748"), listOf("Q222", "Q1246")),
        Jezik("uk", listOf("Q8798"), listOf("Q212")),
        Jezik("ja", listOf("Q5287"), listOf("Q17")),
        Jezik("ko", listOf("Q9176"), listOf("Q884")),
        Jezik("zh", listOf("Q7850", "Q9192", "Q727694", "Q9186", "Q7033959"), listOf("Q148", "Q8646", "Q865")),
        Jezik("hi", listOf("Q1568"), listOf("Q668")),
        Jezik("ta", listOf("Q5885"), listOf("Q668")),
        Jezik("te", listOf("Q8097"), listOf("Q668")),
        Jezik("th", listOf("Q9217"), listOf("Q869")),
        Jezik("ar", listOf("Q13955"), listOf("Q79")),
        Jezik("he", listOf("Q9288"), listOf("Q801")),
        Jezik("fa", listOf("Q9168"), listOf("Q794")),
    )

    val KODE: List<String> = JEZIKI.map { it.koda }

    fun poKodi(koda: String): Jezik? = JEZIKI.firstOrNull { it.koda == koda }

    /** Razredi naslovov v Wikidati: film, animirani film; serija, miniserija, animirana serija, anime. */
    private val RAZREDI_FILM = listOf("Q11424", "Q202866", "Q29168811")
    private val RAZREDI_SERIJA = listOf("Q5398426", "Q1259759", "Q581714", "Q63952888", "Q117467246")

    /** Najvec naslovov v enem vprasanju (dolzina naslova poizvedbe ostane pod 4 kB). */
    const val NAJVEC_V_PAKETU = 80
    const val STRAN_SEZNAMA = 60

    private val IMDB = Regex("^tt[0-9]{5,10}$")
    private val KODA = Regex("^[a-z]{2,3}$")
    private val PREDMET = Regex("^Q[0-9]{1,10}$")

    fun veljavenImdb(id: String): Boolean = IMDB.matches(id)

    /**
     * Glavni jezik naslova. [jeziki] in [drzave] sta predmeta Wikidate (jeziki naslova, drzave izvora). Vrne kode
     * ponujenih jezikov, pod katere naslov sodi - obicajno eno; prazno = ne vemo ali jezika ni med ponujenimi.
     *  - jezik, ki je doma v kateri od drzav izvora, ima prednost (film ameriske izdelave z nekaj francoscine je angleski);
     *  - med vec domacimi jeziki zmaga anglescina (ameriski film z nemskim soproducentom ostane angleski);
     *  - srbohrvaski jugoslovanski film sodi pod hrvascino, srbscino in bosanscino hkrati.
     */
    fun glavni(jeziki: Collection<String>, drzave: Collection<String>): List<String> {
        val vsi = JEZIKI.filter { j -> j.predmeti.any { it in jeziki } }
        if (vsi.isEmpty()) return emptyList()
        val domaci = vsi.filter { j -> j.drzave.any { it in drzave } }
        val izbrani = when {
            domaci.isNotEmpty() -> domaci
            else -> vsi
        }
        // Vec razlicnih jezikov in anglescina med njimi: anglescina. (Vec kod istega jezika - srbohrvascina - ostane.)
        val razlicni = izbrani.map { j -> j.predmeti.filter { it in jeziki }.toSet() }.distinct().size
        return if (razlicni > 1 && izbrani.any { it.koda == "en" }) listOf("en") else izbrani.map { it.koda }
    }

    // ------------------------------------------------------------------ poizvedbi (SPARQL)

    /** Jeziki in drzave izvora za naslove z danimi id-ji IMDb. Neveljavni id-ji izpadejo. */
    fun poizvedbaPaket(idji: Collection<String>): String {
        val vrednosti = idji.filter(::veljavenImdb).distinct().take(NAJVEC_V_PAKETU).joinToString(" ") { "\"$it\"" }
        return "SELECT ?imdb ?jezik ?drzava WHERE { VALUES ?imdb { $vrednosti } ?f wdt:P345 ?imdb . " +
            "OPTIONAL { ?f wdt:P364 ?jezik } OPTIONAL { ?f wdt:P495 ?drzava } }"
    }

    /**
     * Naslovi v izbranem jeziku iz drzav, kjer je ta jezik doma, po prepoznavnosti (stevilo clankov o naslovu).
     * [tip] = "movie" ali "series". Naslov karte: v jeziku vmesnika ([vmesnik]), sicer angleski (kot v katalogih),
     * sicer domaci - slovenski uporabnik vidi »Ples v dežju«, ne »Dance in the Rain«.
     */
    fun poizvedbaSeznam(jezik: Jezik, tip: String, odmik: Int = 0, koliko: Int = STRAN_SEZNAMA, vmesnik: String = "en"): String {
        fun vrednosti(q: List<String>) = q.filter { PREDMET.matches(it) }.joinToString(" ") { "wd:$it" }
        val razredi = if (tip == "series") RAZREDI_SERIJA else RAZREDI_FILM
        val oznake = (listOf(vmesnik.takeIf { KODA.matches(it) } ?: "en", "en", jezik.koda)).distinct()
        return "SELECT ?imdb (SAMPLE(?ime) AS ?naslov) (MIN(YEAR(?d)) AS ?leto) (MAX(?clanki) AS ?pov) WHERE { " +
            "VALUES ?jezik { ${vrednosti(jezik.predmeti)} } VALUES ?drzava { ${vrednosti(jezik.drzave)} } VALUES ?razred { ${vrednosti(razredi)} } " +
            "?f wdt:P364 ?jezik ; wdt:P495 ?drzava ; wdt:P31 ?razred ; wdt:P345 ?imdb ; wikibase:sitelinks ?clanki . " +
            "OPTIONAL { ?f wdt:P577 ?d } " +
            oznake.mapIndexed { i, o -> "OPTIONAL { ?f rdfs:label ?o$i FILTER(LANG(?o$i) = \"$o\") } " }.joinToString("") +
            "BIND(COALESCE(${oznake.indices.joinToString(", ") { "?o$it" }}) AS ?ime) } " +
            "GROUP BY ?imdb ORDER BY DESC(?pov) ?imdb LIMIT ${koliko.coerceIn(1, 200)} OFFSET ${odmik.coerceAtLeast(0)}"
    }

    // ------------------------------------------------------------------ branje odgovora (TSV)

    /** Celica TSV: "niz" (z ubeznimi znaki, lahko z oznako jezika ali vrste), <naslov predmeta> ali gola vrednost. */
    internal fun celica(surova: String): String {
        val s = surova.trim()
        if (s.startsWith("<") && s.endsWith(">")) return s.substring(1, s.length - 1).substringAfterLast('/')
        if (!s.startsWith("\"")) return s
        val izhod = StringBuilder()
        var i = 1
        while (i < s.length) {
            val c = s[i]
            if (c == '"') break
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    't' -> izhod.append(' '); 'n' -> izhod.append(' '); 'r' -> { }
                    else -> izhod.append(n)
                }
                i += 2
                continue
            }
            izhod.append(c)
            i++
        }
        return izhod.toString()
    }

    private fun vrstice(tsv: String): List<List<String>> =
        tsv.lineSequence().drop(1).filter { it.isNotBlank() }.map { v -> v.split('\t').map(::celica) }.toList()

    /** Odgovor na [poizvedbaPaket]: id IMDb -> kode glavnega jezika. Naslov, ki ga Wikidata ne pozna, v izidu manjka. */
    fun izPaketa(tsv: String): Map<String, List<String>> {
        val jeziki = HashMap<String, MutableSet<String>>()
        val drzave = HashMap<String, MutableSet<String>>()
        for (v in vrstice(tsv)) {
            val id = v.getOrNull(0).orEmpty()
            if (!veljavenImdb(id)) continue
            v.getOrNull(1)?.takeIf { PREDMET.matches(it) }?.let { jeziki.getOrPut(id) { LinkedHashSet() }.add(it) }
            v.getOrNull(2)?.takeIf { PREDMET.matches(it) }?.let { drzave.getOrPut(id) { LinkedHashSet() }.add(it) }
            jeziki.getOrPut(id) { LinkedHashSet() }
        }
        return jeziki.mapValues { (id, j) -> glavni(j, drzave[id].orEmpty()) }
    }

    class Naslov(val imdb: String, val naslov: String, val leto: Int)

    /** Odgovor na [poizvedbaSeznam], v vrstnem redu odgovora; vrstice brez veljavnega id-ja ali naslova izpadejo. */
    fun izSeznama(tsv: String): List<Naslov> =
        vrstice(tsv).mapNotNull { v ->
            val id = v.getOrNull(0).orEmpty()
            val naslov = v.getOrNull(1).orEmpty().trim().take(200)
            if (!veljavenImdb(id) || naslov.isEmpty()) null
            else Naslov(id, naslov, v.getOrNull(2)?.toIntOrNull()?.takeIf { it in 1870..2100 } ?: 0)
        }.distinctBy { it.imdb }

    // ------------------------------------------------------------------ predpomnilnik

    /** Znan jezik se ne spreminja; neznanega vprasamo znova cez nekaj dni (Wikidata se dopolnjuje). */
    const val ZNAN_VELJA_MS = 180L * 86_400_000
    const val NEZNAN_VELJA_MS = 7L * 86_400_000

    /** Zapis v predpomnilniku: "kode,locene,z,vejico;cas" (prazne kode = neznan jezik). */
    fun vZapis(kode: List<String>, cas: Long): String = kode.filter { it in KODE }.joinToString(",") + ";" + cas

    /** Kode iz zapisa, ce se velja; null = zapisa ni, je poskodovan ali je potekel (vprasati je treba znova). */
    fun izZapisa(zapis: String?, zdaj: Long): List<String>? {
        val deli = zapis?.split(';') ?: return null
        if (deli.size != 2) return null
        val cas = deli[1].toLongOrNull() ?: return null
        val kode = deli[0].split(',').filter { it in KODE }
        val velja = if (kode.isEmpty()) NEZNAN_VELJA_MS else ZNAN_VELJA_MS
        return if (zdaj - cas in 0..velja) kode else null
    }
}
