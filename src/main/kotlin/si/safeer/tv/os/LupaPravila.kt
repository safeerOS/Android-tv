package si.safeer.tv.os

import si.safeer.tv.cast.JsonLahki
import java.text.Normalizer

/**
 * Naloga 42 E (27. 10. 2026): lupa kot na racunalniku in glasovni ukazi. ENA razclemba ukazov za lupo in glas (prej je
 * »… na TV« razclenjeval PrikaziNa po vrsti naprave): »<kaj> na <napravo>« - ciljna naprava PO IMENU v ozjem krogu
 * (»Sintel na Dnevna soba«), sicer po vrsti (»Sintel na TV«, »weather on my phone«); »vreme« (»vreme v Mariboru«,
 * »vreme na TV«); »isci v spletu …«; spletni naslov; vse drugo je iskanje. Jeziki: sl, en, de, es, fr, it.
 * Ciste funkcije (tests/LupaPravilaTest.kt).
 */
object LupaPravila {
    const val ISCI = "isci"
    const val PRIKAZI_NA = "prikazi_na"
    const val VREME = "vreme"
    const val ISCI_SPLET = "isci_splet"
    const val SPLET = "splet"

    /**
     * Ena vrstica filtrov pod lupo (lastnik 2. 11. 2026, 18:37-19:03): uporabnik izbere, KAJ lupa prikaze - vse ali samo eno
     * skupino zadetkov - in s tem tudi, kaj naredi OK na polju ([okNaPolju]). Prej (»Isci v: Splet · Video · YouTube«) je
     * izbira odlocala samo, kje isce Enter, in se je zapomnila na napravi; zdaj vsako novo iskanje zacne na »Vse« ([obsegPo]).
     */
    const val OBSEG_VSE = "vse"
    const val OBSEG_SPLET = "splet"
    const val OBSEG_VIDEO = "video"
    const val OBSEG_YOUTUBE = "youtube"
    const val OBSEG_APLIKACIJE = "aplikacije"
    const val OBSEG_DATOTEKE = "datoteke"
    const val OBSEG_NASTAVITVE = "nastavitve"
    /** Vrstni red gumbov v vrstici (lastnik 18:41). */
    val OBSEGI = listOf(OBSEG_VSE, OBSEG_SPLET, OBSEG_VIDEO, OBSEG_YOUTUBE, OBSEG_APLIKACIJE, OBSEG_DATOTEKE, OBSEG_NASTAVITVE)

    /** Podskupine brez lastnega gumba (lastnik 18:44, 18:45): videoteke (Netflix, RTV 365 …) in novice (RTV SLO, 24ur …). */
    const val SKUPINA_VIDEOTEKE = "videoteke"
    const val SKUPINA_NOVICE = "novice"

    /**
     * Skupine zadetkov pod »Vse« v danasnjem vrstnem redu (Aplikacije, Datoteke, Nastavitve), nove skupine za njimi;
     * splet ostane zadnji kot do zdaj (lastnik 18:39: »Vse« ostane razdeljeno v skupine). Novice so takoj za aplikacijami,
     * videoteke pred zadetki Medijskega centra (lastnik 18:44: »nad zadetki filmov in serij«).
     */
    val SKUPINE_VSE = listOf(OBSEG_APLIKACIJE, SKUPINA_NOVICE, OBSEG_DATOTEKE, OBSEG_NASTAVITVE, SKUPINA_VIDEOTEKE, OBSEG_VIDEO,
        OBSEG_YOUTUBE, OBSEG_SPLET)

    /**
     * Spodnji rob plosce zadetkov v okvirju visine [visinaOkvirjaPx]: vsaj [razmikPx] nad vrhom spodnje vrstice
     * ([vrhDockaPx], izmerjen ob postavitvi). Preverba TV 19:31: plosca je segla do y=975, vrstica se zacne pri ~930.
     */
    fun spodnjiRobPlosce(visinaOkvirjaPx: Int, vrhDockaPx: Int, razmikPx: Int): Int =
        maxOf(razmikPx, visinaOkvirjaPx - vrhDockaPx + razmikPx)

    /** Veljavna izbira ali Vse (neznana ali prazna vrednost). */
    fun obseg(izbira: String?): String = izbira?.takeIf { it in OBSEGI } ?: OBSEG_VSE

    /**
     * Izbira po dogodku: med urejanjem poizvedbe v isti lupi ostane, zaprta lupa (novo iskanje) zacne na Vse - lastnik
     * 19:03 po neodvisnem pregledu: zapomnjena ozka izbira je naslednje iskanje nepricakovano izpraznila.
     */
    fun obsegPo(trenutni: String, lupaZaprta: Boolean): String = if (lupaZaprta) OBSEG_VSE else obseg(trenutni)

    /**
     * Skupine, ki jih izbira prikaze (po vrsti). »Aplikacije« pokaze VSE ujemajoce se aplikacije uporabnika, tudi videoteke
     * in novice, v podskupinah (lastnik 18:47: »ce bo uporabnik iskal rtv slo, se mu prikaze pod Aplikacije«); pod »Vse« je
     * vsaka aplikacija enkrat, v svoji skupini. »Video vsebine«: videoteke nad zadetki Medijskega centra.
     */
    fun skupine(obseg: String): List<String> = when (val o = obseg(obseg)) {
        OBSEG_VSE -> SKUPINE_VSE
        OBSEG_APLIKACIJE -> listOf(OBSEG_APLIKACIJE, SKUPINA_VIDEOTEKE, SKUPINA_NOVICE)
        OBSEG_VIDEO -> listOf(SKUPINA_VIDEOTEKE, OBSEG_VIDEO)
        else -> listOf(o)
    }

    // ------------------------------------------------------------------ vrsta aplikacije (videoteka, novice, drugo)

    /**
     * Znani paketi videotek kot rezerva, ce aplikacija ne pove kategorije (ApplicationInfo.category). Samo splosno znani
     * paketi - brez ugibanja (lastnik 18:44).
     */
    val PAKETI_VIDEO = setOf(
        "com.netflix.ninja", "com.netflix.mediaclient",
        "com.disney.disneyplus",
        "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient",
        "com.wbd.stream", "com.hbo.hbonow",
        "com.google.android.youtube.tv", "com.google.android.youtube",
        "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta",
        "org.xbmc.kodi", "com.plexapp.android",
        // Preverjeno na televizorju 2. 11. 2026 (pm list packages -3).
        "com.spiintl.tv.filmbox", "si.rtvslo.rtv4dandroidtv_ab",
    )

    /** Imena videotek, ki jih je lastnik nastel (paketov ne ugibamo): ime po [kljuc] brez presledkov in znakov. */
    val IMENA_VIDEO = setOf("netflix", "max", "hbomax", "disney", "primevideo", "filmbox", "rtv365", "voyo", "youtube", "smarttube",
        "skyshowtime", "kodi", "plex")

    /** Novice (lastnik 18:45: »RTV SLO spada pod Novice, tako kot 24ur.com in Zurnal ali Svet24«). */
    val IMENA_NOVICE = setOf("rtvslo", "24ur", "24urcom", "zurnal24", "zurnal24si", "svet24", "svet24si", "siolnet")
    val GOSTITELJI_NOVICE = setOf("rtvslo.si", "24ur.com", "zurnal24.si", "svet24.si", "siol.net", "delo.si", "vecer.com", "dnevnik.si")

    private fun imeKljuc(ime: String): String = besede(ime).joinToString("") { kljuc(it) }

    /**
     * Gostitelj spada pod domeno iz seznama samo kot ista domena ali njena poddomena (»www.24ur.com«, »m.rtvslo.si«),
     * nikoli po podnizu (»24ur.com.zlo.si« ali »ne24ur.com« ni 24ur.com).
     */
    fun podDomeno(gostitelj: String, domene: Set<String>): Boolean {
        val g = gostitelj.lowercase().trimEnd('.')
        return domene.any { g == it || g.endsWith(".$it") }
    }

    fun gostitelj(url: String): String = try { java.net.URI(url.trim()).host.orEmpty() } catch (_: Throwable) { "" }

    /**
     * Namescena aplikacija: videoteka ([SKUPINA_VIDEOTEKE]), novice ([SKUPINA_NOVICE]) ali navadna ([OBSEG_APLIKACIJE]).
     * Kategorija, ki jo aplikacija pove sama (ApplicationInfo.CATEGORY_VIDEO / CATEGORY_NEWS, Android 8+), ima prednost;
     * nato znani paketi in imena. RTV 365 je videoteka (preverjeno pred novicami).
     */
    fun vrstaAplikacije(paket: String, ime: String, kategorijaVideo: Boolean, kategorijaNovice: Boolean): String {
        val k = imeKljuc(ime)
        return when {
            kategorijaVideo || paket in PAKETI_VIDEO || k in IMENA_VIDEO -> SKUPINA_VIDEOTEKE
            kategorijaNovice || k in IMENA_NOVICE -> SKUPINA_NOVICE
            else -> OBSEG_APLIKACIJE
        }
    }

    /**
     * Zadetki aplikacij po skupinah (aplikacije, videoteke, novice): samo iz [seznam] - aplikacij, ki jih uporabnik IMA
     * (namescene in njegove spletne aplikacije); znani seznami jih samo razvrstijo (lastnik 18:48). [vrsta] se klice samo za
     * ujemajoce se (branje kategorije paketa ni za vsako aplikacijo ob vsaki crki). Najvec [najvec] na skupino.
     */
    fun <T> aplikacijePoSkupinah(seznam: List<T>, poizvedba: String, najvec: Int, ime: (T) -> String, vrsta: (T) -> String): Map<String, List<T>> =
        najboljsi(seznam, poizvedba, Int.MAX_VALUE, ime).groupBy(vrsta).mapValues { it.value.take(najvec) }

    /** Spletna aplikacija (plosca z naslovom): novice po domeni ali imenu, sicer navadna aplikacija. */
    fun vrstaSpletne(url: String, ime: String): String =
        if (podDomeno(gostitelj(url), GOSTITELJI_NOVICE) || imeKljuc(ime) in IMENA_NOVICE) SKUPINA_NOVICE else OBSEG_APLIKACIJE

    /**
     * Neposredni zadetki strani v skupini Splet (lastnik 18:47: »rtv slo« odpre rtvslo.si): spletne aplikacije uporabnika
     * ([viri] = ime, naslov) in znane strani novic. Ujemanje po imenu, po gostitelju in po njegovi prvi oznaki
     * (»24ur« -> 24ur.com, »rtv slo« -> rtvslo.si). Samo podnizno ujemanje (1) velja sele od 4 znakov, sicer bi »de«
     * nasel delo.si. Vrne (ime, https naslov), vsaka domena enkrat. Znane strani, ki jih uporabnik nima, so SAMO tu,
     * nikoli med aplikacijami (lastnik 18:48).
     */
    fun strani(poizvedba: String, viri: List<Pair<String, String>>, najvec: Int = 3): List<Pair<String, String>> {
        val q = poizvedba.trim()
        val dolzina = besede(q).joinToString("") { kljuc(it) }.length
        if (dolzina < 2 || jeNaslov(q)) return emptyList()
        val vsi = viri.filter { gostitelj(it.second).isNotEmpty() } + GOSTITELJI_NOVICE.map { it to "https://$it" }
        return vsi.map { (ime, url) ->
            val g = gostitelj(url).lowercase().removePrefix("www.")
            Triple(ime, url, maxOf(ujemanje(ime, q), ujemanje(g, q), ujemanje(g.substringBefore('.'), q)))
        }.filter { it.third >= 2 || (it.third == 1 && dolzina >= 4) }
            .sortedByDescending { it.third }
            .distinctBy { gostitelj(it.second).lowercase().removePrefix("www.") }
            .take(najvec).map { it.first to it.second }
    }

    /** Kje isce izvedi() (OK, ki ga okno ne prevzame, in druga okna z lupo): Video in YouTube po izbiri, drugo v spletu. */
    fun iskalniObseg(obseg: String): String = when (val o = obseg(obseg)) {
        OBSEG_VIDEO, OBSEG_YOUTUBE -> o
        else -> OBSEG_SPLET
    }

    /**
     * Vrstica »Isci v spletu / v Video vsebinah / na YouTubu: <q>« je samo v pogledu ene spletne skupine: pod »Vse« bi
     * bile spet tri dodatne vrstice, ki jih je lastnik 2. 11. 2026 umaknil (00503096) - tam so samo pravi zadetki.
     */
    fun vrsticaIskanja(obseg: String): Boolean = obseg(obseg) in setOf(OBSEG_SPLET, OBSEG_VIDEO, OBSEG_YOUTUBE)

    /**
     * Sprotni zadetki skupine [skupina] iz omrezja. Splet in YouTube gresta iskalniku samo z vklopljenimi sprotnimi zadetki
     * (zasebnost: natipkano ne gre ven brez privolitve); YouTube pod »Vse« samo, ce je iskalnik uporabnika Google - sicer bi
     * sli znaki Googlu mimo izbire iskalnika. Video vsebine (dodatki uporabnika, isto kot iskanje v Medijskem centru):
     * izrecna izbira »Video vsebine« ali »Vse« z vklopljenimi sprotnimi zadetki.
     */
    fun zivo(skupina: String, obseg: String, poizvedba: String, vklopljeno: Boolean, iskalnikGoogle: Boolean): Boolean {
        val o = obseg(obseg)
        if (skupina !in skupine(o)) return false
        val q = poizvedba.trim()
        if (q.length < SPLET_OD_ZNAKOV || jeNaslov(q)) return false
        return when (skupina) {
            OBSEG_SPLET -> vklopljeno
            OBSEG_YOUTUBE -> vklopljeno && (o == OBSEG_YOUTUBE || iskalnikGoogle)
            OBSEG_VIDEO -> o == OBSEG_VIDEO || vklopljeno
            else -> false
        }
    }

    /** Kaj naredi OK na polju lupe (lastnik 19:03). */
    enum class OkPolja { FOKUS_NA_PRVI, ODPRI_STRAN, ISCI_SPLET, ISCI_YOUTUBE, ISCI_VIDEO }

    /**
     * OK na polju: Splet odpre neposredno stran (»rtv slo« -> rtvslo.si), ce jo ima, sicer isce v spletu; YouTube isce na
     * YouTubu; Video vsebine v Medijskem centru. Vse, Aplikacije, Datoteke, Nastavitve fokus premaknejo na prvi zadetek in
     * nicesar ne zazenejo (uporabnik vidi, kaj bo odprl). Brez zadetka: krajevna izbira gre na »Isci v Vse« (prva vrstica
     * praznega stanja), Vse isce v spletu kot do zdaj.
     */
    fun okNaPolju(obseg: String, imaZadetek: Boolean, imaStran: Boolean): OkPolja = when (obseg(obseg)) {
        OBSEG_SPLET -> if (imaStran) OkPolja.ODPRI_STRAN else OkPolja.ISCI_SPLET
        OBSEG_YOUTUBE -> OkPolja.ISCI_YOUTUBE
        OBSEG_VIDEO -> OkPolja.ISCI_VIDEO
        OBSEG_VSE -> if (imaZadetek) OkPolja.FOKUS_NA_PRVI else OkPolja.ISCI_SPLET
        else -> OkPolja.FOKUS_NA_PRVI
    }

    /** Stanje seznama: zadetki, »Iscem …« (sprotni se cakajo, nic se ni) ali prazno (»Ni zadetkov za …«). */
    enum class Stanje { ZADETKI, ISCEM, PRAZNO }

    fun stanje(steviloVrstic: Int, cakam: Int): Stanje = when {
        steviloVrstic > 0 -> Stanje.ZADETKI
        cakam > 0 -> Stanje.ISCEM
        else -> Stanje.PRAZNO
    }

    /** Prazno stanje ponudi »Isci v Vse« samo pri ozki izbiri (pod Vse ni kam siriti). */
    fun ponudiVse(obseg: String): Boolean = obseg(obseg) != OBSEG_VSE

    /**
     * Velikost crk gumbov, pri kateri gre vseh sedem v eno vrstico brez drsenja (lastnik 19:03): [sirinePri15] = sirine
     * napisov v px pri 15 sp, [dodatekPx] = notranji rob in razmik enega gumba, [prostorPx] = sirina vrstice. Prva velikost
     * iz [velikosti], ki gre; null = ne gre niti najmanjsa (klicatelj vzame najmanjso in to zapise v dnevnik).
     */
    fun velikostGumbov(sirinePri15: List<Float>, dodatekPx: Float, prostorPx: Float,
                       velikosti: List<Float> = listOf(15f, 14f, 13f, 12f)): Float? =
        velikosti.firstOrNull { v -> sirinePri15.sumOf { (it * v / 15f + dodatekPx).toDouble() } <= prostorPx }

    /** OK na polju lupe isce, ce je vpisano vsaj toliko, kot zahteva DomacaLupa.izvedi (Video vsebine od dveh znakov). */
    fun okIsce(niz: String, obseg: String = OBSEG_VSE): Boolean {
        val l = niz.trim().length
        return if (obseg(obseg) == OBSEG_VIDEO) l >= 2 else l >= 1
    }

    /** YouTubovi predlogi (isti odgovor kot client=chrome, [predlogi]). */
    fun youtubePredlogi(q: String): String =
        "https://suggestqueries.google.com/complete/search?client=chrome&ds=yt&q=" + java.net.URLEncoder.encode(q.trim(), "UTF-8")

    /** Iskanje na YouTubu (stran z zadetki v brskalniku). */
    fun youtubeIskanje(q: String): String =
        "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(q.trim(), "UTF-8")

    /**
     * [naprava] = id naprave v ozjem krogu, izbrane po imenu; [vrsteNaprav] = platforme, ce je cilj vrsta (»na TV«);
     * oboje prazno = ta naprava.
     */
    data class Ukaz(val vrsta: String, val vsebina: String, val naprava: String = "", val vrsteNaprav: List<String> = emptyList())

    private val PC = listOf("windows", "linux", "mac")

    /** Vrsta naprave (normalizirane besede, brez predloga) -> platforme. Isto kot PrikaziNa/delovna.js ciljMedija. */
    private val VRSTE: Map<String, List<String>> = buildMap {
        for (b in listOf("tv", "televiziji", "televizorju", "televizor", "televizijo", "television", "telly", "fernseher", "fernsehen",
            "tele", "televisao", "televisor", "televisione")) put(b, listOf("tv"))
        for (b in listOf("racunalniku", "racunalnik", "pc", "prenosniku", "computer", "laptop", "rechner", "ordenador", "computador",
            "computadora", "portatil", "lordinateur", "ordinateur")) put(b, PC)
        for (b in listOf("tablici", "tablico", "tablica", "tablet", "tableta", "tablette")) put(b, listOf("tablet"))
        for (b in listOf("telefonu", "telefon", "mobitelu", "mobitel", "phone", "mobile", "handy", "smartphone", "movil", "telefono",
            "celular", "telemovel", "telefone", "telephone", "portable", "cellulare")) put(b, listOf("phone"))
    }

    /** Predlogi pred ciljem (»na«, »on«, »auf« …) in cleni ali svojilni zaimki, ki lahko sledijo. */
    private val PREDLOGI = setOf("na", "v", "on", "to", "auf", "im", "am", "en", "sur", "sulla", "sul", "su", "in", "no", "nella", "nel")
    private val CLENI = setOf("the", "my", "this", "dem", "den", "meinem", "meinen", "la", "el", "mi", "le", "ma", "mon", "il", "lo", "mio", "mia", "o", "a")

    private val GLAGOLI = setOf("prikazi", "pokazi", "predvajaj", "odpri", "zavrti", "show", "play", "open", "put", "zeige",
        "spiele", "offne", "muestra", "reproduce", "pon", "abre", "mostra", "reproduz", "toca", "passa",
        "montre", "joue", "ouvre", "lance", "riproduci", "apri", "metti")

    private val VREME_BESEDE = setOf("vreme", "vremenska", "napoved", "weather", "forecast", "wetter", "tiempo", "clima", "meteo", "tempo")

    /** Zacetki »isci v spletu« (normalizirane besede); ostanek je poizvedba. */
    private val SPLET_ZACETKI = listOf(
        listOf("isci", "v", "spletu"), listOf("isci", "na", "spletu"), listOf("poisci", "na", "spletu"), listOf("isci", "splet"),
        listOf("search", "the", "web", "for"), listOf("search", "the", "web"), listOf("search", "web", "for"), listOf("web", "search"),
        listOf("im", "web", "suchen"), listOf("websuche"), listOf("suche", "im", "web", "nach"), listOf("suche", "im", "web"),
        listOf("buscar", "en", "la", "web"), listOf("busca", "en", "la", "web"), listOf("busca", "en", "internet"),
        listOf("chercher", "sur", "le", "web"), listOf("cherche", "sur", "le", "web"), listOf("recherche", "web"),
        listOf("cerca", "sul", "web"), listOf("cerca", "in", "rete"),
    )

    fun kljuc(beseda: String): String =
        Normalizer.normalize(beseda.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .replace("đ", "d").replace("ł", "l").replace("ø", "o").replace("ß", "ss")
            .replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun besede(niz: String): List<String> = niz.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

    /** Konec [k] (normalizirane besede) je ime naprave [ime]? Vrne stevilo besed cilja s predlogom in clenom, sicer 0. */
    private fun konecZImenom(k: List<String>, ime: List<String>): Int {
        if (ime.isEmpty() || k.size < ime.size + 2) return 0
        if (k.subList(k.size - ime.size, k.size) != ime) return 0
        var i = k.size - ime.size - 1
        if (k[i] in CLENI && i > 0 && k[i - 1] in PREDLOGI) i--
        return if (k[i] in PREDLOGI && i >= 1) k.size - i else 0
    }

    private fun brezGlagola(b: List<String>): List<String> = if (b.isNotEmpty() && kljuc(b[0]) in GLAGOLI) b.drop(1) else b

    /**
     * Razclemba vnosa lupe ali glasu. [naprave] = (id, ime) naprav v OZJEM KROGU (brez te naprave); ime ima prednost pred
     * vrsto (»na TV« je naprava z imenom »TV«, ce taka obstaja). Daljse ime ima prednost (»Dnevna soba TV« pred »TV«).
     */
    fun razcleni(niz: String, naprave: List<Pair<String, String>> = emptyList()): Ukaz {
        // Besede brez crk in stevk (»–«, »…«) odpadejo, da se b in k (normalizirane) ujemata po mestih.
        val b = besede(niz).filter { kljuc(it).isNotEmpty() }
        val k = b.map { kljuc(it) }
        if (b.isEmpty()) return Ukaz(ISCI, "")
        // »isci v spletu …«
        for (z in SPLET_ZACETKI) if (k.size > z.size && k.subList(0, z.size) == z) return Ukaz(ISCI_SPLET, b.drop(z.size).joinToString(" "))
        // Cilj po imenu naprave v krogu, nato po vrsti.
        var naprava = ""
        var vrste: List<String> = emptyList()
        var dolzinaCilja = 0
        for ((id, ime) in naprave.sortedByDescending { besede(it.second).size }) {
            val n = konecZImenom(k, besede(ime).map { kljuc(it) }.filter { it.isNotEmpty() })
            if (n > 0) { naprava = id; dolzinaCilja = n; break }
        }
        if (naprava.isEmpty() && k.size >= 3) {
            val zadnja = k.last()
            VRSTE[zadnja]?.let { v ->
                val n = konecZImenom(k, listOf(zadnja))
                if (n > 0) { vrste = v; dolzinaCilja = n }
            }
        }
        val ostanek = brezGlagola(b.take(b.size - dolzinaCilja))
        val ok = ostanek.map { kljuc(it) }
        // Vreme: »vreme«, »vreme v Mariboru«, »weather in Paris«, »vreme na TV«.
        if (ok.isNotEmpty() && ok[0] in VREME_BESEDE) {
            var kraj = ostanek.drop(1)
            if (kraj.isNotEmpty() && kljuc(kraj[0]) in PREDLOGI + setOf("za", "for", "fur", "para", "pour", "per", "a", "de", "di")) kraj = kraj.drop(1)
            return Ukaz(VREME, kraj.joinToString(" "), naprava, vrste)
        }
        if (naprava.isNotEmpty() || vrste.isNotEmpty()) return Ukaz(PRIKAZI_NA, ostanek.joinToString(" "), naprava, vrste)
        val t = b.joinToString(" ")
        if (jeNaslov(t)) return Ukaz(SPLET, if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t")
        return Ukaz(ISCI, t)
    }

    /** Spletni naslov (domena s piko, brez presledkov) - gre v brskalnik, ne v iskanje medijev (isto kot DomacaLupa). */
    fun jeNaslov(q: String): Boolean =
        !q.contains(' ') && (q.startsWith("http://") || q.startsWith("https://") ||
            Regex("^[\\p{L}\\p{N}-]+(\\.[\\p{L}\\p{N}-]+)*\\.[a-z]{2,}(/.*)?$", RegexOption.IGNORE_CASE).matches(q))

    // ------------------------------------------------------------------ zgodovina iskanja

    /** Toliko zadnjih iskanj pokaze zgodovina pod prazno lupo. */
    const val NAJVEC_ZGODOVINE = 5

    private val SAMO_LOCILA = Regex("^[^\\p{L}\\p{N}&+#@\\-\u2013]+$")
    private val NAREKOVAJ_V_BESEDI = Regex("[\\p{L}\\p{N}][\u201E\u201C\u201D\u00AB\u00BB\u201A\u2018\"][\\p{L}\\p{N}]")
    private val VEJICA_MED_CRKAMA = Regex("\\p{L},\\p{L}")
    private val PONOVLJENA_CRKA = Regex("(\\p{L})\\1{3,}")

    /**
     * Iskanje, ki gre v zgodovino, ali null (preverba TV 2. 11. 2026 19:43: v zgodovini so ostale vrstice kot
     * »„ DDV po„llll,l.-e« - napol natipkano z vrstico znakov). Shrani se samo pravo iskanje (OK/oddaja), ocisceno in z vsaj
     * dvema crkama ali stevkama, ki so vsaj 60 % znakov. Ne: beseda iz samih locil (»„«, »…«; pomisljaj sme), narekovaj ali vejica sredi
     * besede, ista crka stirikrat zapored. Isto pravilo ocisti staro zgodovino ob branju ([ocistiZgodovino]).
     */
    fun zaZgodovino(niz: String): String? {
        val q = niz.trim().replace(Regex("\\s+"), " ")
        val znaki = q.filter { !it.isWhitespace() }
        val crke = znaki.count { it.isLetterOrDigit() }
        if (crke < 2 || crke * 10 < znaki.length * 6) return null
        if (q.split(' ').any { SAMO_LOCILA.matches(it) }) return null
        if (NAREKOVAJ_V_BESEDI.containsMatchIn(q) || VEJICA_MED_CRKAMA.containsMatchIn(q) || PONOVLJENA_CRKA.containsMatchIn(q)) return null
        return q.take(120)
    }

    private fun kljucIskanja(q: String): String = besede(q).joinToString(" ") { kljuc(it) }

    /**
     * Zgodovina (najnovejse prvo) brez smeti ([zaZgodovino]), brez ponovitev in brez zacetkov istega iskanja (»Opp« ob
     * »Oppenheimer« odpade, ostane daljse); najvec [najvec].
     */
    fun ocistiZgodovino(seznam: List<String>, najvec: Int = NAJVEC_ZGODOVINE): List<String> {
        val ciste = seznam.mapNotNull { zaZgodovino(it) }.distinctBy { kljucIskanja(it) }
        return ciste.filter { e ->
            val k = kljucIskanja(e)
            ciste.none { f -> f !== e && kljucIskanja(f).let { it != k && it.startsWith(k) } }
        }.take(najvec)
    }

    /** Novo iskanje na vrh zgodovine; smeti se ne shranijo (zgodovina ostane, kot je bila - ocisti se). */
    fun dodajVZgodovino(seznam: List<String>, niz: String, najvec: Int = NAJVEC_ZGODOVINE): List<String> =
        ocistiZgodovino(listOfNotNull(zaZgodovino(niz)) + seznam, najvec)

    /** Kam gre fokus v plosci lupe (preverba TV 19:43: DOL s polja je zgodovino preskocil in pristal na ploscici zadaj). */
    enum class Fokus { POLJE, FILTER, PRVI, OSTANE }

    /** DOL s polja: na izbrani filter, ce je vrstica filtrov vidna (zadetki), sicer na prvo vrstico (zgodovina). */
    fun dolSPolja(ploscaOdprta: Boolean, filtriVidni: Boolean, imaVrstico: Boolean): Fokus = when {
        !ploscaOdprta -> Fokus.OSTANE
        filtriVidni -> Fokus.FILTER
        imaVrstico -> Fokus.PRVI
        else -> Fokus.OSTANE
    }

    /** GOR iz seznama na vrh (iz prve vrstice): na izbrani filter, brez filtrov (zgodovina) na polje. */
    fun gorIzSeznama(filtriVidni: Boolean): Fokus = if (filtriVidni) Fokus.FILTER else Fokus.POLJE

    /**
     * Plosca lupe je modalna (lovec napak TV 3. 11. 2026, 320c1ed8): ko je odprta, ploscice in spodnja vrstica za njo
     * nimajo fokusa. Prej so ga imele in FocusFinder (isce po celem oknu) je iz polja DESNO izbral ploscico RTV SLO pod
     * plosco, iz filtra Datoteke pa ploscico namesto filtra Nastavitve - filtri so tak cilj zavrnili in fokus je obstal.
     */
    fun ozadjeFokusno(ploscaOdprta: Boolean): Boolean = !ploscaOdprta

    /**
     * Cilj fokusa iz dela plosce (polje, filtri, seznam): najprej najblizji v istem delu ([vDelu], iskanje samo v njem),
     * nato cilj iz celega okna ([drugje]) samo, ce je v plosci; sicer null - fokus ostane, nikoli na pogled za plosco.
     */
    fun <T> ciljVPlosci(vDelu: T?, drugje: T?, drugjeVPlosci: Boolean): T? = vDelu ?: drugje?.takeIf { drugjeVPlosci }

    /** Kaj naredi smerna tipka s polja lupe ([izPolja]). */
    enum class IzPolja { PRIVZETO, ZAPRI_PLOSCO, V_PLOSCI }

    /**
     * Smerna tipka s polja: zaprta plosca - privzeto. Prazno polje (samo zgodovina, odpre se ze s fokusom): GOR, LEVO in
     * DESNO gredo s polja kot prej, plosca se zapre PRED iskanjem cilja (ploscice spet fokusne) - sicer bi uporabnik na
     * domacem zaslonu obtical na polju in rabil Nazaj. Z vpisanim besedilom (zadetki, filtri) fokus ostane v plosci.
     */
    fun izPolja(ploscaOdprta: Boolean, poljePrazno: Boolean, dol: Boolean): IzPolja = when {
        !ploscaOdprta -> IzPolja.PRIVZETO
        poljePrazno && !dol -> IzPolja.ZAPRI_PLOSCO
        else -> IzPolja.V_PLOSCI
    }

    /**
     * GOR v seznamu: ScrollView ga porabi samo, ce je nad fokusom se vrstica; s prve vrstice gre fokus na filter ali polje
     * ([gorIzSeznama]). Lovec napak 3. 11. 2026: ko je bil seznam malo podrsan (glava skupine skrita), je ScrollView GOR
     * s prve vrstice porabil za drsenje in fokus je obstal na njej.
     */
    fun seznamPorabiGor(vrsticaNad: Boolean): Boolean = vrsticaNad

    // ------------------------------------------------------------------ zadetki lupe

    /**
     * Ujemanje imena (program, nastavitev, datoteka) s poizvedbo: 3 = ime se zacne s poizvedbo, 2 = beseda v imenu se
     * zacne z njo, 1 = ime brez presledkov se zacne s poizvedbo brez presledkov (sele od 4 znakov: »rtvslo« -> »RTV SLO«),
     * 0 = ne ujema. Brez naglasov in velikih crk (»nas« najde »Nastavitve«, »cas« »Čas«). Preverba TV 2. 11. 2026 19:31:
     * prej je 1 pomenil »vsebuje kjerkoli« in »rtv« je cez mejo besed nasel »SafeeR TV Browser«.
     */
    fun ujemanje(ime: String, poizvedba: String): Int {
        val q = besede(poizvedba).joinToString(" ") { kljuc(it) }.trim()
        if (q.isEmpty()) return 0
        val i = besede(ime).joinToString(" ") { kljuc(it) }
        return when {
            i.startsWith(q) -> 3
            i.split(' ').any { it.startsWith(q) } -> 2
            q.replace(" ", "").length >= 4 && i.replace(" ", "").startsWith(q.replace(" ", "")) -> 1
            else -> 0
        }
    }

    /** Najboljsi zadetki: po ujemanju, nato po imenu; brez neujemajocih; najvec [najvec]. */
    fun <T> najboljsi(seznam: List<T>, poizvedba: String, najvec: Int, ime: (T) -> String): List<T> =
        seznam.map { it to ujemanje(ime(it), poizvedba) }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<T, Int>> { it.second }.thenBy { ime(it.first).lowercase() })
            .take(najvec).map { it.first }

    /** Sprotni zadetki iz spleta niso za kratko poizvedbo (vsaka crka bi sla iskalniku) - sele od toliko znakov. */
    const val SPLET_OD_ZNAKOV = 3
    const val NAJVEC_PREDLOGOV = 5

    fun predlogeIskati(poizvedba: String, vklopljeno: Boolean): Boolean =
        vklopljeno && poizvedba.trim().length >= SPLET_OD_ZNAKOV && !jeNaslov(poizvedba.trim())

    /**
     * Predlogi iskalnika (Google client=chrome, DuckDuckGo type=list): `["poizvedba", ["predlog", ...], ...]`. Brez
     * ponovitve poizvedbe, brez praznih in kontrolnih znakov, najvec [NAJVEC_PREDLOGOV]. Pokvarjen odgovor: prazno.
     */
    fun predlogi(json: String, poizvedba: String): List<String> {
        val vrh = JsonLahki.elementi(json.trim()) ?: return emptyList()
        val drugi = vrh.getOrNull(1) ?: return emptyList()
        val q = poizvedba.trim().lowercase()
        return (JsonLahki.elementi(drugi) ?: return emptyList())
            .mapNotNull { JsonLahki.niz(it) }
            .map { it.replace(Regex("\\p{Cntrl}"), " ").trim().take(120) }
            .filter { it.isNotEmpty() && it.lowercase() != q }
            .distinct().take(NAJVEC_PREDLOGOV)
    }
}
