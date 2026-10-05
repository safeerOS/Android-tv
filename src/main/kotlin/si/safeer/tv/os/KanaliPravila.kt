package si.safeer.tv.os

/**
 * TV v zivo: iz katalogov uporabnikovih dodatkov sestavimo izbiri »zvrst« in »jezik« - kot pri filmih, kjer uporabnik
 * izbere zvrst in ga ne zanima, od kod vsebina pride (lastnik, 5. 10. 2026: »da bi si lahko uporabnik filtriral glede
 * na vsebino, podobno kot pri filmih«). Ciste funkcije brez Androida in omrezja (preizkus: tests/KanaliPravilaTest.kt).
 *
 * Dodatki kanale razvrscajo na dva nacina in oba morata dati isti izbiri:
 *  - en katalog na kategorijo ali jezik (»... News«, »... Sports«, »... Slovenian«, »... All«) - izmerjeno na dodatku
 *    s 84 katalogi; prej smo vzeli prvih 12 po abecedi in jih zmesali v eno polico, do novic ali slovenskih kanalov
 *    uporabnik sploh ni mogel;
 *  - en katalog z moznostmi (`genre`), ki so kategorije (»NOTICIAS«, »ESPORTES«, skupine kanalov).
 */
object KanaliPravila {
    /** Katalog kanalov, kot ga pravila potrebujejo: [skupina] = dodatek (osnovni naslov), [dodatek] = njegovo ime. */
    class Katalog(val skupina: String, val dodatek: String, val ime: String, val moznosti: List<String> = emptyList())

    /** Vir kanalov: katalog (indeks v vhodnem seznamu) in moznost zvrsti, s katero ga vprasamo (prazno = brez). */
    data class Vir(val katalog: Int, val moznost: String = "")

    /** Moznost izbire. [ime] je prazno pri znani kategoriji in pri jeziku (ime da vmesnik v jeziku uporabnika). */
    class Filter(val kljuc: String, val ime: String, val viri: List<Vir>)

    /**
     * [splosni]: viri pogleda »vse«; [zvrsti] in [jeziki]: moznosti izbir; [zasebni]: kategorije, ki jih dodatek sam
     * oznaci kot zasebne - njihovi kanali ne gredo v noben skupni seznam ([ZasebniDodatki]).
     */
    class Filtri(val splosni: List<Vir>, val zvrsti: List<Filter>, val jeziki: List<Filter>, val zasebni: List<Vir>)

    /** Kaj vprasati za izbrano zvrst in jezik. [presek]: kanal iz [glavni] ostane samo, ce je tudi v teh virih. */
    class Izbrani(val glavni: List<Vir>, val presek: List<Vir>)

    private val NAGLASI = Regex("\\p{M}+")
    private val NI_ZNAK = Regex("[^a-z0-9+]+")

    /** Oznaka za primerjavo: male crke, brez naglasov, brez pripone za » · « (»DOCUMENTARIOS · Canais«), enojni presledki. */
    fun cista(oznaka: String): String =
        java.text.Normalizer.normalize(oznaka.substringBefore(" · ").lowercase(), java.text.Normalizer.Form.NFD)
            .replace(NAGLASI, "").replace(NI_ZNAK, " ").trim()

    /** Razlikovalni del imena kataloga: brez imena dodatka spredaj (»Kanali News« -> »News«) in brez locil. */
    fun oznakaKataloga(dodatek: String, ime: String): String {
        var o = ime.trim()
        if (dodatek.isNotBlank() && o.startsWith(dodatek.trim(), ignoreCase = true)) o = o.substring(dodatek.trim().length)
        return o.trim(' ', '-', '–', '—', '•', '·', ':', '|', '/')
    }

    /** Ime kataloga, ki ne pove nicesar o vsebini (»All«, »TV ao vivo«, »Live TV«): njegovi kanali so pogled »vse«. */
    private val SPLOSNA = Regex("^(|all|all channels|vse|vsi|todos|todas|todo|alle|tous|tutti|tv|live|live tv|tv live|channels?|kanali|canais|" +
        "canales|tv ao vivo|ao vivo|tv en vivo|en vivo|en directo?|direct|iptv|tv channels|live channels)$")

    /** Kategorija, ki jo dodatek sam oznaci kot zasebno: ne gre v skupne sezname. */
    private val ZASEBNA = Regex("(^| )(xxx|adults?|adultos?|adulte|erwachsene|18\\+|\\+18|porn\\w*|erotic\\w*|erotik\\w*|hentai)( |$)")
    fun jeZasebna(oznaka: String): Boolean = ZASEBNA.containsMatchIn(cista(oznaka))

    private val JEZIKI: Map<String, String> = mapOf(
        "albanian" to "sq", "shqip" to "sq", "arabic" to "ar", "armenian" to "hy", "azerbaijani" to "az", "basque" to "eu", "belarusian" to "be",
        "bengali" to "bn", "bangla" to "bn", "bosnian" to "bs", "bosanski" to "bs", "bulgarian" to "bg", "burmese" to "my", "catalan" to "ca",
        "chinese" to "zh", "mandarin" to "zh", "croatian" to "hr", "hrvatski" to "hr", "czech" to "cs", "danish" to "da", "dutch" to "nl",
        "nederlands" to "nl", "english" to "en", "estonian" to "et", "filipino" to "tl", "finnish" to "fi", "french" to "fr", "francais" to "fr",
        "galician" to "gl", "georgian" to "ka", "german" to "de", "deutsch" to "de", "greek" to "el", "gujarati" to "gu", "hebrew" to "he",
        "hindi" to "hi", "hungarian" to "hu", "magyar" to "hu", "icelandic" to "is", "indonesian" to "id", "irish" to "ga", "italian" to "it",
        "italiano" to "it", "japanese" to "ja", "kannada" to "kn", "kazakh" to "kk", "khmer" to "km", "korean" to "ko", "kurdish" to "ku",
        "kyrgyz" to "ky", "lao" to "lo", "latvian" to "lv", "lithuanian" to "lt", "luxembourgish" to "lb", "macedonian" to "mk", "malay" to "ms",
        "malayalam" to "ml", "maltese" to "mt", "marathi" to "mr", "mongolian" to "mn", "nepali" to "ne", "norwegian" to "no", "panjabi" to "pa",
        "punjabi" to "pa", "pashto" to "ps", "persian" to "fa", "farsi" to "fa", "polish" to "pl", "polski" to "pl", "portuguese" to "pt",
        "portugues" to "pt", "romanian" to "ro", "russian" to "ru", "serbian" to "sr", "srpski" to "sr", "sinhala" to "si", "slovak" to "sk",
        "slovenian" to "sl", "slovene" to "sl", "slovenski" to "sl", "slovenscina" to "sl", "somali" to "so", "spanish" to "es", "espanol" to "es",
        "castellano" to "es", "swahili" to "sw", "swedish" to "sv", "tajik" to "tg", "tamil" to "ta", "telugu" to "te", "thai" to "th",
        "turkish" to "tr", "turkce" to "tr", "turkmen" to "tk", "ukrainian" to "uk", "urdu" to "ur", "uzbek" to "uz", "vietnamese" to "vi",
        "welsh" to "cy")

    /** Koda jezika (ISO 639-1), ce je oznaka ime jezika (»Slovenian«, »Deutsch«); sicer null. */
    fun jezik(oznaka: String): String? = JEZIKI[cista(oznaka)]

    /**
     * Znane kategorije kanalov po vrsti, kot jih pokaze izbira: kljuc in besede, po katerih jo prepoznamo (anglesko,
     * portugalsko, spansko, nemsko, francosko, italijansko, slovensko). Ista kategorija iz vec dodatkov je ena moznost.
     */
    private val KATEGORIJE: List<Pair<String, Regex>> = listOf(
        "novice" to "news|noticias|nachrichten|actualites|notizie|novice|informativos?",
        "sport" to "sports?|esportes?|deportes?|sportivi",
        "filmi" to "movies?|filmes?|peliculas|films?|cine|cinema|kino|filmi",
        "serije" to "series|serien|serie|serije",
        "otroski" to "kids|children|infantil|infantis|infantiles|kinder|enfants|bambini|otroski|desenhos|cartoons?",
        "glasba" to "music|musicas?|musik|musique|glasba|clipes",
        "dokumentarni" to "documentary|documentaries|documentarios|documentales|documentaires|documentari|doku|dokumentarni",
        "razvedrilo" to "entertainment|entretenimento|entretenimiento|unterhaltung|divertissement|intrattenimento|variedades|razvedrilo",
        "splosni" to "general|geral|generalistas?|splosni",
        "animirani" to "animation|animes?|animacao|animacion|animazione|animirani",
        "komedija" to "comedy|comedia|comedie|komodie|commedia|humor|komedija",
        "kultura" to "culture|cultura|kultur|kultura",
        "izobrazevanje" to "education|educational|educacao|educacion|bildung|educativos?|izobrazevanje",
        "druzinski" to "family|familia|familie|famille|famiglia|druzinski",
        "slog" to "lifestyle",
        "kuhanje" to "cooking|culinaria|cocina|kochen|cuisine|cucina|gastronomia|kuhanje",
        "potovanja" to "travel|viagens|viajes|reisen|voyages?|viaggi|turismo|potovanja",
        "znanost" to "science|ciencia|wissenschaft|scienza|znanost",
        "narava" to "outdoor|nature|natureza|naturaleza|natur|narava",
        "poslovni" to "business|negocios|economia|wirtschaft|finance|financas|poslovni",
        "avto" to "auto|automotive|motors?",
        "klasika" to "classic|classics|clasicos|classicos|klasika",
        "verski" to "religious|religion|religiosos?|religiosas?|religiose|verski",
        "vreme" to "weather|clima|wetter|meteo|vreme",
        "nakupovanje" to "shop|shopping|compras|nakupovanje",
        "parlament" to "legislative|parliament|parlamento|parlament",
        "javni" to "public|javni",
        "interaktivno" to "interactive|interaktivno",
        "sprostitev" to "relax|relaxar|relajacion|sprostitev",
        "ostalo" to "undefined|other|others|outros|otros|misc|uncategorized|diversos|ostalo",
    ).map { it.first to Regex("(^| )(" + it.second + ")( |$)") }

    /** Kljuci znanih kategorij po vrsti prikaza (za imena v vmesniku). */
    val KLJUCI_KATEGORIJ: List<String> = KATEGORIJE.map { it.first }

    /** Kljuc znane kategorije (»novice«, »sport« ...), ce oznaka katero pomeni; sicer null. */
    fun kategorija(oznaka: String): String? = cista(oznaka).let { c -> KATEGORIJE.firstOrNull { it.second.containsMatchIn(c) }?.first }

    private val RX_LOCLJIVOST = Regex("\\s*\\((?:\\d{3,4}[pi]|sd|hd|fhd|uhd|4k)\\)", RegexOption.IGNORE_CASE)
    private val RX_OPOMBA = Regex("\\s*\\[(?:geo-?blocked|not 24/7|offline)\\]", RegexOption.IGNORE_CASE)

    /** Ime kanala brez tehnicnih pripon seznamov IPTV (»(720p)«, »[Not 24/7]«): tako se isti kanal v dveh locljivostih zdruzi v eno kartico. */
    fun imeKanala(ime: String): String = ime.replace(RX_LOCLJIVOST, "").replace(RX_OPOMBA, "").trim().ifBlank { ime.trim() }

    private val RX_NI_V_IMENU = Regex("[^\\p{L}\\p{N}+]+")
    private val RX_PRED_PLUSOM = Regex(" +\\+")
    private val RX_KAKOVOST = Regex("(?:^| )(?:hd|fhd|uhd|sd|4k|8k|hevc|h265|h264|\\d{3,4}[pi])(?= |$)")

    /**
     * Kljuc, po katerem sta dva vnosa isti kanal: ime brez tehnicnih pripon ([imeKanala]), locil, presledkov in oznak
     * kakovosti, z malimi crkami. Vse drugo je del imena: kanal s plusom, drugo stevilko, dodatno besedo ali drugim
     * vsebinskim oklepajem je drug kanal. Vec vnosov z istim imenom (vec povezav istega kanala ali dogodka) je en kanal.
     */
    fun kljucImena(ime: String): String {
        val c = imeKanala(ime).lowercase(java.util.Locale.ROOT).replace(RX_NI_V_IMENU, " ").replace(RX_PRED_PLUSOM, "+").trim()
        return c.replace(RX_KAKOVOST, "").trim().ifEmpty { c }.replace(" ", "")
    }

    fun filtri(katalogi: List<Katalog>): Filtri {
        val splosni = ArrayList<Vir>()
        val zasebni = ArrayList<Vir>()
        val znane = HashMap<String, MutableList<Vir>>()
        val druge = LinkedHashMap<String, Pair<String, MutableList<Vir>>>()
        val jeziki = LinkedHashMap<String, MutableList<Vir>>()
        /** Vir z vsebinsko oznako: jezik, znana kategorija ali kategorija z imenom, kot ga poda dodatek. */
        fun razvrsti(oznaka: String, vir: Vir) {
            val c = cista(oznaka)
            if (c.isEmpty()) return
            if (ZASEBNA.containsMatchIn(c)) { zasebni += vir; return }
            val j = JEZIKI[c]
            if (j != null) { jeziki.getOrPut(j) { ArrayList() } += vir; return }
            val k = KATEGORIJE.firstOrNull { it.second.containsMatchIn(c) }?.first
            if (k != null) znane.getOrPut(k) { ArrayList() } += vir
            else druge.getOrPut(c) { oznaka.substringBefore(" · ").trim() to ArrayList() }.second += vir
        }
        katalogi.forEachIndexed { i, k ->
            val oznaka = oznakaKataloga(k.dodatek, k.ime)
            val splosna = SPLOSNA.matches(cista(oznaka))
            if (k.moznosti.isNotEmpty()) {
                // Katalog z moznostmi: brez moznosti je pogled »vse«, vsaka moznost je svoja izbira.
                splosni += Vir(i)
                if (!splosna) razvrsti(oznaka, Vir(i))
                k.moznosti.forEach { m -> razvrsti(m, Vir(i, m)) }
            } else if (splosna) splosni += Vir(i)
            else razvrsti(oznaka, Vir(i))
        }
        // Dodatek brez kataloga »vse« (samo kategorije): pogled »vse« sestavijo njegove kategorije.
        val zVsem = splosni.map { katalogi[it.katalog].skupina }.toSet()
        val nadomestni = (KATEGORIJE.mapNotNull { znane[it.first] }.flatten() + druge.values.flatMap { it.second })
            .filter { katalogi[it.katalog].skupina !in zVsem }
        val zvrsti = KATEGORIJE.filter { it.first != "ostalo" }.mapNotNull { (kljuc, _) -> znane[kljuc]?.let { Filter(kljuc, "", it) } } +
            druge.entries.sortedBy { it.value.first.lowercase() }.map { Filter("o:" + it.key, it.value.first, it.value.second) } +
            listOfNotNull(znane["ostalo"]?.let { Filter("ostalo", "", it) })
        return Filtri(splosni + nadomestni, zvrsti, jeziki.map { Filter(it.key, "", it.value) }, zasebni)
    }

    /**
     * Viri za izbiro. Brez izbire: splosni. Samo zvrst ali samo jezik: viri te moznosti. Oboje: dodatek sam ne zna
     * »novice v slovenscini«, zato vzamemo kanale jezika in obdrzimo tiste, ki so tudi v katalogih zvrsti ISTEGA
     * dodatka (id kanala je pri istem dodatku isti); dodatek, ki ene od izbir ne pozna, ne prispeva nicesar.
     */
    fun izbrani(f: Filtri, katalogi: List<Katalog>, zvrst: String, jezik: String): Izbrani {
        val z = if (zvrst.isEmpty()) null else f.zvrsti.firstOrNull { it.kljuc == zvrst }?.viri ?: emptyList()
        val j = if (jezik.isEmpty()) null else f.jeziki.firstOrNull { it.kljuc == jezik }?.viri ?: emptyList()
        if (z == null && j == null) return Izbrani(f.splosni, emptyList())
        if (j == null) return Izbrani(z!!, emptyList())
        if (z == null) return Izbrani(j, emptyList())
        val obe = z.map { katalogi[it.katalog].skupina }.toSet().intersect(j.map { katalogi[it.katalog].skupina }.toSet())
        return Izbrani(j.filter { katalogi[it.katalog].skupina in obe }, z.filter { katalogi[it.katalog].skupina in obe })
    }
}
