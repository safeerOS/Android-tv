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

    private val RX_HD_V_IMENU = Regex("(?:^|[^\\p{L}\\p{N}])(?:hd|fhd|uhd|4k)(?:$|[^\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    /**
     * Razlicice istega kanala (kartica), po vrsti poskusa: najprej tiste z znanim zivim tokom, nato se neznane; dokazano
     * mrtve ([stanja] = false) izpadejo. Znotraj skupine gre prva tista z »HD« v imenu, nato prvotni vrstni red.
     * Vrne indekse v [imena]; prazen seznam = mrtve so vse razlicice in kartica se skrije.
     */
    fun urediRazlicice(imena: List<String>, stanja: List<Boolean?>): List<Int> =
        imena.indices.filter { stanja.getOrNull(it) != false }
            .sortedWith(compareBy({ if (stanja.getOrNull(it) == true) 0 else 1 }, { if (RX_HD_V_IMENU.containsMatchIn(imena[it])) 0 else 1 }, { it }))

    /** Kartica je vidna, ce deluje katera koli razlicica (ali ni nobene, ki bi jo skrila); mrtva razlicica je ne skrije. */
    fun karticaVidna(stanja: List<Boolean?>): Boolean = stanja.isEmpty() || stanja.any { it != false }

    /** Katero razlicico preverimo zdaj: prva po [urediRazlicice], ce se ne vemo zanjo; null, ce je prva ze znano ziva ali ni nobene. */
    fun razlicicaZaPreverjanje(imena: List<String>, stanja: List<Boolean?>): Int? =
        urediRazlicice(imena, stanja).firstOrNull()?.takeIf { stanja.getOrNull(it) == null }

    /**
     * Naloga 90d: tok, ki streznik odgovori pravilno (HTTP 200), a predvaja samo sliko »broadcast is temporarily unavailable«
     * (izmerjeno 23. 10. 2026: TV SLO 1/2 iz iptv-org si.m3u). Sonda toka ga steje za zivega, zato ga poznamo po naslovu.
     */
    private val NEDELUJOCI_TOKOVI = Regex("^https?://(?:[^/@]*@)?(?:dash2\\.antik\\.sk(?::\\d+)?/live/test_|streamer03\\.xploretv\\.si(?::\\d+)?(?:/|$))")

    fun znanNedelujocTok(url: String): Boolean = NEDELUJOCI_TOKOVI.containsMatchIn(url.trim().lowercase(java.util.Locale.ROOT))

    /**
     * Naloga 91a: kartica ima razlicice iz dodatkov in iz seznamov IPTV; ce dodatki ne dajo predvajljivega toka, se predvaja
     * prva ziva razlicica seznama ([urediRazlicice]). Razlicice, ki niso iz seznama ([izIptv]) ali imajo znan nedelujoc tok
     * ([nedelujoc]), ne pridejo v postev. Vrne indeks v [imena]; null = take razlicice ni.
     */
    fun rezervaIptv(imena: List<String>, stanja: List<Boolean?>, izIptv: List<Boolean>, nedelujoc: List<Boolean>): Int? =
        urediRazlicice(imena, imena.indices.map { if (izIptv.getOrNull(it) != true || nedelujoc.getOrNull(it) == true) false else stanja.getOrNull(it) }).firstOrNull()

    /**
     * Naloga 91b: jeziki izbire mreze kanalov takoj ob odprtju: sveze iz katalogov dodatkov ([dodatkov]; null ali prazno =
     * se niso odgovorili), sicer zadnji znani z diska ([shranjeni]); zraven jeziki seznamov IPTV in vgrajenih kanalov ([ostali]).
     */
    fun jezikiKanalov(dodatkov: List<String>?, shranjeni: List<String>, ostali: List<String>): List<String> =
        (dodatkov?.takeIf { it.isNotEmpty() } ?: shranjeni).plus(ostali).filter { it.isNotBlank() }.distinct()

    /**
     * Naloga 91c: nedavni vnosi (Recent) brez tistih, ki ne delujejo ([nedeluje]: skrit, mrtev, znan nedelujoc tok). Kanal je
     * ena kartica z vec razlicicami, zato tak kanal skrije tudi ostale nedavne kanale z istim imenom ([kljucImena]).
     */
    fun <T> brezNedelujocihKanalov(vnosi: List<T>, jeKanal: (T) -> Boolean, ime: (T) -> String, nedeluje: (T) -> Boolean): List<T> {
        val ne = vnosi.map { nedeluje(it) }
        val imena = vnosi.filterIndexed { i, v -> ne[i] && jeKanal(v) }.map { kljucImena(ime(it)) }.toSet()
        return vnosi.filterIndexed { i, v -> !ne[i] && !(jeKanal(v) && kljucImena(ime(v)) in imena) }
    }

    /** Kljuci ([OsPravila.kljucKanala]) kartice [id] in vseh njenih razlicic [razlicice], brez dvojnikov. */
    fun kljuciKartice(id: String, razlicice: List<String>): List<String> = (razlicice + id).map { OsPravila.kljucKanala(it) }.distinct()

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

    // ------------------------------------------------------------------ domaci kanali in ponovni poskus (naloga 73)

    /** Imena drzav v imenih katalogov (anglesko in domace) -> ISO 3166-1. Dodatki IPTV imajo katalog na drzavo. */
    private val DRZAVE: List<Pair<Regex, String>> = listOf(
        "slovenia|slovenija|slovenija tv" to "SI", "croatia|hrvatska" to "HR", "serbia|srbija" to "RS",
        "bosnia|bosnia and herzegovina|bosna i hercegovina|bih" to "BA", "montenegro|crna gora" to "ME",
        "north macedonia|macedonia|makedonija" to "MK", "kosovo" to "XK", "albania|shqiperia" to "AL",
        "austria|osterreich" to "AT", "germany|deutschland" to "DE", "switzerland|schweiz|suisse" to "CH", "italy|italia" to "IT",
        "hungary|magyarorszag" to "HU", "united kingdom|uk|great britain|england" to "GB", "united states|usa|us" to "US",
        "canada" to "CA", "france" to "FR", "spain|espana" to "ES", "portugal" to "PT", "brazil|brasil" to "BR",
        "netherlands|nederland" to "NL", "belgium|belgique|belgie" to "BE", "poland|polska" to "PL",
        "czech republic|czechia|cesko" to "CZ", "slovakia|slovensko" to "SK", "romania" to "RO", "bulgaria" to "BG",
        "greece" to "GR", "turkey|turkiye" to "TR", "russia" to "RU", "ukraine" to "UA",
    ).map { (imena, koda) -> Regex("(^| )($imena)( |$)") to koda }

    /** Drzave regije, ki jim dodatki namenjajo skupne kataloge (»Balkan«, »EX-YU«). */
    private val REGIJA_BALKAN = setOf("SI", "HR", "RS", "BA", "ME", "MK", "XK")
    private val BALKAN = Regex("(^| )(balkan|balkans|ex yu|exyu|yu|yugoslavia|jugoslavija)( |$)")

    private val RX_KODA_DRZAVE = Regex("^[a-z]{2}( tv)?$")

    /**
     * Naloga 80 (lastnik: »Live TV pri All languages kaze le 2 vrstici«): vrstni red virov mreze brez izbire - katalogi
     * kategorij (General, News, Entertainment ...) pred drzavnimi (»Slovenia«, »KR Tv«), nato ostali (»All«, jeziki).
     * [oznaka] = razlikovalni del imena ([oznakaKataloga]).
     */
    fun rangVira(oznaka: String): Int {
        val c = cista(oznaka)
        return when {
            kategorija(oznaka) != null && kategorija(oznaka) != "ostalo" -> 0
            drzava(oznaka) != null || RX_KODA_DRZAVE.matches(c) -> 1
            else -> 2
        }
    }

    /** Viri [viri] po [rangVira] (znotraj ranga v prvotnem vrstnem redu). */
    fun razvrstiVire(viri: List<Vir>, katalogi: List<Katalog>): List<Vir> =
        viri.sortedBy { rangVira(oznakaKataloga(katalogi[it.katalog].dodatek, katalogi[it.katalog].ime)) }

    /** Drzava kataloga po imenu (»Slovenia«, »Slovenija TV«) ali null. */
    fun drzava(oznaka: String): String? = cista(oznaka).let { c -> if (c.isEmpty()) null else DRZAVE.firstOrNull { it.first.containsMatchIn(c) }?.second }

    /** Katalog regije Balkan / EX-YU. */
    fun jeBalkan(oznaka: String): Boolean = BALKAN.containsMatchIn(cista(oznaka))

    /**
     * Naloga 73: domaci viri pogleda brez izbire - najprej katalogi drzave naprave (»Slovenia«, »Slovenija«), nato katalogi
     * jezika vmesnika ([Filtri.jeziki]), nato regija (Balkan / EX-YU za SI, HR, RS, BA, ME, MK, XK); vsak enkrat, brez
     * zasebnih kategorij, najvec [najvec]. Prej samo jezik: dodatek IPTV z 213 katalogi po drzavah (»Slovenia«) jezika ne
     * pove, zato domacih kanalov ni bilo.
     */
    fun domaciViri(katalogi: List<Katalog>, f: Filtri, jezik: String, drzava: String, najvec: Int = 6): List<Vir> {
        val d = drzava.uppercase(java.util.Locale.ROOT)
        fun ujema(pogoj: (String) -> Boolean): List<Vir> = katalogi.indices.flatMap { i ->
            val k = katalogi[i]
            (if (pogoj(oznakaKataloga(k.dodatek, k.ime))) listOf(Vir(i)) else emptyList()) + k.moznosti.filter(pogoj).map { Vir(i, it) }
        }
        val poDrzavi = if (d.isEmpty()) emptyList() else ujema { drzava(it) == d }
        val poJeziku = f.jeziki.firstOrNull { it.kljuc == jezik }?.viri.orEmpty()
        val poRegiji = if (d in REGIJA_BALKAN) ujema { jeBalkan(it) } else emptyList()
        return (poDrzavi + poJeziku + poRegiji).distinct().filter { it !in f.zasebni }.take(najvec)
    }

    /** Najvec kanalov iz dodatkov v pomnilniku ene mreze (ostalo pride z »Naloži več«, kot okno v mrezi Filmi). */
    const val NAJVEC_KANALOV_V_MREZI = 600
    private const val PONOVI_NAJMANJ_MS = 60_000L
    private const val PONOVI_NAJVEC_MS = 15 * 60_000L

    /**
     * Naloga 73: cez koliko ms mreza sama poskusi znova, ko noben vir ni odgovoril: najmanj do konca premora dodatka
     * ([ostanekPremoraMs], 0 = ga ni), sicer 1, 2, 4, 8 min po vrsti poskusov ([poskus] od 0), najvec 15 min.
     */
    fun zamikPonovitve(ostanekPremoraMs: Long, poskus: Int): Long {
        val postopno = PONOVI_NAJMANJ_MS shl poskus.coerceIn(0, 4)
        return maxOf(ostanekPremoraMs, postopno).coerceIn(PONOVI_NAJMANJ_MS, PONOVI_NAJVEC_MS)
    }

    /**
     * Naloga 74: napis »Nalagam« nad mrezo kanalov samo, dokler na zaslonu ni nobenega kanala ([naZaslonu]) in se viri
     * dodatkov se nalagajo. Preverjanje kanalov v ozadju napisa nima.
     */
    fun nalagamNapis(naZaslonu: Int, nalagam: Boolean, imaDodatke: Boolean): Boolean = naZaslonu == 0 && nalagam && imaDodatke

    /**
     * Naloga 74: vrste mreze, ki so na zaslonu, in naslednja. [vrhovi] = vrh vsake vrste v pikah od vrha vsebine (po vrsti),
     * [drsnikY] = zamik drsnika, [visina] = visina vidnega polja. Mreza se ni postavljena (visina 0, vsi vrhovi 0): prve
     * tri vrste (prvi zaslon televizorja). Prazna mreza: prazen obseg.
     */
    fun vidneVrste(vrhovi: List<Int>, drsnikY: Int, visina: Int): IntRange {
        if (vrhovi.isEmpty()) return IntRange.EMPTY
        if (visina <= 0 || vrhovi.all { it == 0 }) return 0..minOf(2, vrhovi.size - 1)
        val dno = drsnikY + visina
        // Prva vidna: zadnja vrsta, ki se zacne nad vrhom vidnega polja (njen spodnji del je se viden).
        var prva = 0
        for (i in vrhovi.indices) if (vrhovi[i] <= drsnikY) prva = i else break
        var zadnja = prva
        for (i in prva until vrhovi.size) if (vrhovi[i] < dno) zadnja = i else break
        return prva..minOf(zadnja + 1, vrhovi.size - 1)
    }

    /**
     * Naloga 74: katere kartice mreze ([kljuci] po vrsti, [naVrsto] v vrsti) preverimo zdaj: samo v vrstah [vrste], od vrha
     * navzdol, ki jih [potrebuje] (kanal iz dodatka, se ne vemo zanj, ni v teku), najvec [prostih].
     */
    fun zaPreverjanje(kljuci: List<String>, vrste: IntRange, naVrsto: Int, prostih: Int, potrebuje: (String) -> Boolean): List<String> {
        if (prostih <= 0 || naVrsto <= 0 || vrste.isEmpty() || kljuci.isEmpty()) return emptyList()
        val od = (vrste.first * naVrsto).coerceIn(0, kljuci.size)
        val doK = ((vrste.last + 1) * naVrsto).coerceIn(od, kljuci.size)
        val izhod = ArrayList<String>(prostih)
        for (i in od until doK) {
            if (izhod.size >= prostih) break
            if (potrebuje(kljuci[i])) izhod += kljuci[i]
        }
        return izhod
    }

    // ------------------------------------------------------------------ naloga 76: zasebne kategorije na disku

    /**
     * Naloga 76: stanje zapisa zasebnih kategorij dodatka. [VELJA]: isti katalogi, mlajsi od [ZASEBNI_VELJA_MS];
     * [ZASTAREL]: isti katalogi, starejsi (velja naprej, v ozadju se osvezi); [NI]: zapisa ni ali so se katalogi spremenili.
     */
    enum class ZasebniZapis { VELJA, ZASTAREL, NI }
    const val ZASEBNI_VELJA_MS = 24 * 3_600_000L

    /** Stanje zapisa s kljucem [zapisKljuc] (null = ga ni) in casom [zapisCas] za trenutni [kljuc]. Ura nazaj: zastarel. */
    fun stanjeZasebnih(zapisKljuc: String?, zapisCas: Long, kljuc: String, zdaj: Long): ZasebniZapis = when {
        zapisKljuc == null || zapisKljuc != kljuc || zapisCas <= 0L -> ZasebniZapis.NI
        zdaj - zapisCas in 0 until ZASEBNI_VELJA_MS -> ZasebniZapis.VELJA
        else -> ZasebniZapis.ZASTAREL
    }

    /**
     * Kljuc zapisa dodatka: odtis (SHA-256) opisov njegovih zasebnih katalogov (id in privzete moznosti), neodvisen od
     * vrstnega reda in dvojnikov. Na disku tako ni berljivih imen kategorij.
     */
    fun kljucZasebnih(katalogi: Collection<String>): String = sha(katalogi.toSortedSet().joinToString("\n"))

    /** Ime zapisa dodatka na disku (odtis osnove, brez naslova streznika). */
    fun odtisDodatka(osnova: String): String = sha(osnova.trim()).take(16)

    /** Odtis id-ja kanala: prvih 8 bajtov SHA-256 (trk pri nekaj deset tisoc id-jih je zanemarljiv). */
    fun odtisId(id: String): Long {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        var r = 0L
        for (i in 0 until 8) r = (r shl 8) or (d[i].toLong() and 0xff)
        return r
    }

    /** Urejeni odtisi brez dvojnikov (za [vOdtisih]): ~8 bajtov na kanal namesto niza v pomnilniku. */
    fun odtisi(id: Collection<String>): LongArray = id.map { odtisId(it) }.toLongArray().also { it.sort() }.distinctUrejeni()

    /** Ali je id med urejenimi odtisi [urejeni]. */
    fun vOdtisih(urejeni: LongArray, id: String): Boolean = urejeni.isNotEmpty() && java.util.Arrays.binarySearch(urejeni, odtisId(id)) >= 0

    private fun LongArray.distinctUrejeni(): LongArray {
        if (size < 2) return this
        var n = 1
        for (i in 1 until size) if (this[i] != this[n - 1]) this[n++] = this[i]
        return if (n == size) this else copyOf(n)
    }

    private fun sha(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Naloga 76: kaj pise nad prazno mrezo kanalov, dokler caka. */
    enum class NapisCakanja { NIC, NALAGAM, KATEGORIJE }

    /**
     * Nad prazno mrezo: »Preverjam kategorije dodatkov ...«, kadar mreza caka samo se na zasebne kategorije ([zasebniCakajo]),
     * sicer »Nalagam«, dokler se viri nalagajo ([nalagamNapis]); kanali na zaslonu - nic.
     */
    fun napisCakanja(naZaslonu: Int, nalagam: Boolean, zasebniCakajo: Boolean, imaDodatke: Boolean): NapisCakanja = when {
        !nalagamNapis(naZaslonu, nalagam || zasebniCakajo, imaDodatke) -> NapisCakanja.NIC
        zasebniCakajo && !nalagam -> NapisCakanja.KATEGORIJE
        else -> NapisCakanja.NALAGAM
    }

    // ------------------------------------------------------------------ naloga 82: zaklenjeni viri in prazna mreza

    /**
     * Naloga 82: viri izbire brez katalogov zaklenjenih dodatkov ([zaklenjen] po [Katalog.skupina]) - pri VSAKI izbiri
     * (brez, zvrst, jezik, oboje). Vrne (delujoci, izpusceni); vrstni red ostane.
     */
    fun brezZaklenjenih(viri: List<Vir>, katalogi: List<Katalog>, zaklenjen: (String) -> Boolean): Pair<List<Vir>, List<Vir>> =
        viri.partition { !zaklenjen(katalogi[it.katalog].skupina) }

    /** Kaj pokazati, ko v mrezi kanalov ni nobenega kanala. */
    enum class PraznaMreza { CAKAJ, NADOMESTEK, PRAZNO }

    /** Po tolikem casu brez kanala na zaslonu pokazemo nadomestek (namesto »Nalagam«), tudi ce se viri se nalagajo. */
    const val PRAZNA_NADOMESTEK_MS = 3_000L
    /** Po tolikem casu brez kanala napisa »Nalagam« ni vec: »V tej izbiri ni kanalov« (nalaganje lahko tece naprej). */
    const val PRAZNA_KONEC_MS = 45_000L

    /**
     * Naloga 82 (lastnik: zvrst »News« - 30 s cakanja in »Loading« za vedno): prazna mreza. [cakam] = viri se nalagajo,
     * [odOdprtja] = ms od odprtja mreze, [imaNadomestek] = znani so kanali brez izbire. Ko ni nicesar vec pricakovati,
     * je nadomestek takoj; med nalaganjem po [PRAZNA_NADOMESTEK_MS]; »Nalagam« nikoli dlje od [PRAZNA_KONEC_MS].
     */
    fun praznaMreza(naZaslonu: Int, cakam: Boolean, odOdprtja: Long, imaNadomestek: Boolean): PraznaMreza? = when {
        naZaslonu > 0 -> null
        cakam && odOdprtja < PRAZNA_NADOMESTEK_MS -> PraznaMreza.CAKAJ
        imaNadomestek -> PraznaMreza.NADOMESTEK
        cakam && odOdprtja < PRAZNA_KONEC_MS -> PraznaMreza.CAKAJ
        else -> PraznaMreza.PRAZNO
    }
}
