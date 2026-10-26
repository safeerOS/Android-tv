package si.safeer.tv.os

/**
 * Naloga 80: seznami IPTV (.m3u, npr. iptv-org) kot vir kanalov TV v zivo - cista pravila brez Androida
 * (tests/IptvPravilaTest.kt). Branje je tokovno: [Bralnik] dobi vrstico za vrstico in vrne vnos, ko je ta popoln - cel
 * seznam (iptv-org »eng«: 3003 vnosov) nikoli ni v pomnilniku.
 */
object IptvPravila {
    /**
     * Kanal seznama: [ime] (iz #EXTINF po vejici ali tvg-name), [url] toka, [logo] (tvg-logo), [zvrst] (kljuc kategorije
     * iz group-title, [KanaliPravila.kategorija]; "" = neznana), [jezik] (ISO 639-1), [drzava] (ISO 3166, velike crke).
     */
    data class Vnos(val ime: String, val url: String, val logo: String = "", val zvrst: String = "", val jezik: String = "",
                    val drzava: String = "", val tvgId: String = "")

    // J4 (pregled 30, 26. 10. 2026): ime atributa zacne samo na zacetku niza znakov imena (lookbehind) - brez tega je bil cas
    // kvadraten v dolzini vrstice (Linux: 20 KB crk 5,7 s, 1 MB ni koncal; Android bere vrstice do 64 KB).
    private val RX_ATRIBUT = Regex("""(?<![A-Za-z0-9_-])([A-Za-z0-9_-]+)="([^"]*)"""")

    /** Mesto vejice, ki loci atribute od imena: prva zunaj narekovajev (vejica v group-title="News,Sport" ne steje); -1 = je ni. */
    private fun vejicaImena(extinf: String): Int {
        var vNarekovajih = false
        for (i in extinf.indices) { val c = extinf[i]; if (c == '"') vNarekovajih = !vNarekovajih else if (c == ',' && !vNarekovajih) return i }
        return -1
    }

    /** Atributi vrstice #EXTINF (tvg-id="..." group-title="..."), imena z malimi crkami; samo pred imenom kanala. */
    fun atributi(extinf: String): Map<String, String> {
        val i = vejicaImena(extinf)
        val glava = if (i < 0) extinf else extinf.substring(0, i)
        return RX_ATRIBUT.findAll(glava).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
    }

    /** Ime iz vrstice #EXTINF: vse za vejico, ki loci atribute ([vejicaImena]); "" = ga ni. */
    fun imeIzExtinf(extinf: String): String {
        val i = vejicaImena(extinf)
        return if (i < 0) "" else extinf.substring(i + 1).trim()
    }

    private val ATRIBUTI_KANALA = setOf("tvg-id", "tvg-name", "tvg-logo", "group-title", "tvg-country", "tvg-language")

    /** Ali je vnos kanal TV v zivo: ima oznake seznama IPTV (tvg-*, group-title) ali je tok HLS (.m3u8). */
    fun jeKanal(atributi: Map<String, String>, url: String): Boolean =
        atributi.keys.any { it in ATRIBUTI_KANALA } || url.substringBefore('?').lowercase().endsWith(".m3u8")

    /** ISO 639-2/3 -> 639-1 za sezname po jezikih (iptv-org »languages/eng.m3u«). */
    private val JEZIKI3 = mapOf(
        "eng" to "en", "slv" to "sl", "deu" to "de", "ger" to "de", "fra" to "fr", "fre" to "fr", "ita" to "it", "spa" to "es",
        "por" to "pt", "hrv" to "hr", "srp" to "sr", "bos" to "bs", "mkd" to "mk", "mac" to "mk", "bul" to "bg", "rus" to "ru",
        "ukr" to "uk", "pol" to "pl", "ces" to "cs", "cze" to "cs", "slk" to "sk", "slo" to "sk", "hun" to "hu", "ron" to "ro",
        "rum" to "ro", "nld" to "nl", "dut" to "nl", "swe" to "sv", "nor" to "no", "dan" to "da", "fin" to "fi", "ell" to "el",
        "gre" to "el", "tur" to "tr", "ara" to "ar", "heb" to "he", "hin" to "hi", "zho" to "zh", "chi" to "zh", "jpn" to "ja",
        "kor" to "ko", "sqi" to "sq", "alb" to "sq", "fas" to "fa", "per" to "fa", "urd" to "ur", "vie" to "vi", "tha" to "th",
        "ind" to "id", "msa" to "ms", "may" to "ms", "cat" to "ca", "eus" to "eu", "baq" to "eu", "glg" to "gl", "lit" to "lt",
        "lav" to "lv", "est" to "et", "isl" to "is", "ice" to "is", "gle" to "ga", "cym" to "cy", "wel" to "cy")

    /** Jezik iz oznake seznama: ime (»Slovenian«), dvoclenska ali troclenska koda; null = neznan. */
    /**
     * Glavni jezik drzave (ISO 3166 -> ISO 639-1); "" = ne vemo. Najprej izrecna preslikava (Locale ima za vecino drzav tudi
     * en_XX, de_IT ... - izmerjeno 23. 10. 2026 v testu: SI je dal »en«), sicer jezik z enako oznako kot drzava, sicer prvi ne-angleski.
     */
    private val JEZIK_DRZAVE = mapOf("SI" to "sl", "HR" to "hr", "RS" to "sr", "BA" to "bs", "ME" to "sr", "MK" to "mk",
        "XK" to "sq", "AL" to "sq", "AT" to "de", "DE" to "de", "CH" to "de", "IT" to "it", "HU" to "hu", "CZ" to "cs",
        "SK" to "sk", "PL" to "pl", "FR" to "fr", "BE" to "nl", "NL" to "nl", "LU" to "fr", "ES" to "es", "PT" to "pt",
        "BR" to "pt", "MX" to "es", "AR" to "es", "DK" to "da", "SE" to "sv", "FI" to "fi", "GR" to "el", "CY" to "el",
        "TR" to "tr", "RO" to "ro", "BG" to "bg", "UA" to "uk", "RU" to "ru", "US" to "en", "GB" to "en", "CA" to "en",
        "AU" to "en", "IE" to "en", "NZ" to "en", "IN" to "hi", "JP" to "ja", "CN" to "zh", "KR" to "ko")

    fun jezikDrzave(drzava: String): String {
        val d = drzava.trim().uppercase(java.util.Locale.ROOT)
        if (d.length != 2) return ""
        JEZIK_DRZAVE[d]?.let { return it }
        val kandidati = java.util.Locale.getAvailableLocales().asSequence()
            .filter { it.country == d && it.language.length == 2 && it.variant.isEmpty() && it.script.isEmpty() }
            .map { it.language }.distinct().sorted().toList()
        return kandidati.firstOrNull { it == d.lowercase(java.util.Locale.ROOT) } ?: kandidati.firstOrNull { it != "en" }
            ?: kandidati.firstOrNull().orEmpty()
    }

    fun jezik(oznaka: String): String? {
        val o = oznaka.trim()
        if (o.isEmpty()) return null
        KanaliPravila.jezik(o)?.let { return it }
        val k = o.lowercase(java.util.Locale.ROOT)
        return when {
            k.length == 2 && k.all { it in 'a'..'z' } -> k
            k.length == 3 -> JEZIKI3[k]
            else -> null
        }
    }

    private val RX_DRZAVA_SEZNAMA = Regex("/countries/([a-z]{2})(?:\\.m3u8?)?(?:[?#]|$)", RegexOption.IGNORE_CASE)
    private val RX_JEZIK_SEZNAMA = Regex("/languages/([a-z]{2,3})(?:\\.m3u8?)?(?:[?#]|$)", RegexOption.IGNORE_CASE)
    private val RX_DRZAVA_ID = Regex("\\.([a-z]{2})(?:@|$)", RegexOption.IGNORE_CASE)

    /** iptv-org pise Zdruzeno kraljestvo kot ».uk« (tvg-id, countries/uk.m3u); ISO 3166-1 je GB (kot Linux _DRZAVA_POPRAVKI). */
    private val DRZAVA_POPRAVKI = mapOf("UK" to "GB")
    private fun popraviDrzavo(d: String) = DRZAVA_POPRAVKI[d] ?: d

    /** Drzava in jezik iz naslova seznama: ».../countries/si.m3u« -> (SI, ""), ».../languages/eng.m3u« -> ("", en). */
    fun izNaslovaSeznama(url: String): Pair<String, String> {
        val drzava = popraviDrzavo(RX_DRZAVA_SEZNAMA.find(url)?.groupValues?.get(1)?.uppercase(java.util.Locale.ROOT).orEmpty())
        val jezik = RX_JEZIK_SEZNAMA.find(url)?.groupValues?.get(1)?.let { jezik(it) }.orEmpty()
        return drzava to jezik
    }

    /** Prva znana kategorija iz group-title (»News;Sports« -> novice); "" = nobene. */
    fun zvrst(groupTitle: String): String =
        groupTitle.split(';', '|', '/').firstNotNullOfOrNull { KanaliPravila.kategorija(it) }.orEmpty()

    /**
     * Kanal iz vrstice #EXTINF in naslova toka (ze polnega). Drzava: tvg-country, sicer pripona tvg-id (»PopTV.si@SD«),
     * sicer [drzavaSeznama]; jezik: tvg-language, sicer [jezikSeznama]. null = vnos ni kanal ali nima naslova http(s).
     */
    fun vnos(extinf: String, url: String, drzavaSeznama: String, jezikSeznama: String): Vnos? {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val a = atributi(extinf)
        if (!jeKanal(a, url)) return null
        val ime = imeIzExtinf(extinf).ifBlank { a["tvg-name"].orEmpty() }.ifBlank { return null }
        // Lastnik, 25. 10. 2026: kanalov [Not 24/7] ne rabimo - izlocijo se ze pri branju (ne prikaz, ne preverba).
        if (KanaliPravila.jeNe247(ime) || KanaliPravila.jeNe247(a["tvg-name"].orEmpty())) return null
        val tvgId = a["tvg-id"].orEmpty()
        val drzava = popraviDrzavo(a["tvg-country"]?.split(';', ',')?.firstOrNull()?.trim()?.takeIf { it.length == 2 }?.uppercase(java.util.Locale.ROOT)
            ?: RX_DRZAVA_ID.find(tvgId)?.groupValues?.get(1)?.uppercase(java.util.Locale.ROOT) ?: drzavaSeznama)
        // Seznami drzav iptv-org (countries/si.m3u) nimajo tvg-language: brez jezika kanal ni ustrezal nobenemu filtru jezika
        // (izmerjeno 23. 10. 2026: filter Slovenian ni pokazal TV SLO 1/2). Jezik tedaj vzamemo iz drzave kanala.
        val jezik = a["tvg-language"]?.split(';', ',')?.firstNotNullOfOrNull { jezik(it) } ?: jezikSeznama.ifEmpty { jezikDrzave(drzava) }
        val logo = a["tvg-logo"].orEmpty().takeIf { it.startsWith("http://") || it.startsWith("https://") }.orEmpty()
        return Vnos(ime, url, logo, zvrst(a["group-title"].orEmpty()), jezik, drzava, tvgId)
    }

    /**
     * Tokovni bralnik seznama .m3u: [vrstica] za vsako vrstico, vrne kanal, ko je vnos popoln. Relativni naslovi glede na
     * [osnova]. Najvec [NAJVEC_VNOSOV] kanalov (varovalka).
     */
    class Bralnik(private val osnova: String, private val drzava: String, private val jezik: String) {
        private var extinf = ""
        var kanalov = 0
            private set
        fun vrstica(surova: String): Vnos? {
            val l = surova.trim()
            when {
                l.startsWith("#EXTINF", ignoreCase = true) -> { extinf = l; return null }
                l.isEmpty() || l.startsWith("#") -> return null
            }
            val e = extinf
            extinf = ""
            if (e.isEmpty() || kanalov >= NAJVEC_VNOSOV) return null
            val url = try { java.net.URL(java.net.URL(osnova), l).toString() } catch (_: Exception) { return null }
            return vnos(e, url, drzava, jezik)?.also { kanalov++ }
        }
    }

    const val NAJVEC_VNOSOV = 20_000

    /** Naloga 86 (M7): najdaljsa vrstica seznama, ki jo preberemo (#EXTINF z dolgim logotipom ima nekaj sto znakov). */
    const val NAJVEC_VRSTICA = 64 * 1024

    /**
     * Naloga 86 (M7): vrstice iz [r] brez prelomov (\n, \r\n, \r); vrstica, daljsa od [najvecZnakov], se preskoci v celoti
     * (njeni znaki se ne hranijo), branje neha po [najvecZnakovSkupaj] znakih (nedokoncana zadnja vrstica odpade).
     * Pomnilnik je omejen ne glede na vhod.
     */
    fun vrstice(r: java.io.Reader, najvecZnakov: Int, najvecZnakovSkupaj: Long, obVrstici: (String) -> Unit) {
        val sb = StringBuilder()
        var predolga = false
        var skupaj = 0L
        val buf = CharArray(8192)
        var prejsnjiCr = false
        fun konec() { if (!predolga) obVrstici(sb.toString()); sb.setLength(0); predolga = false }
        while (true) {
            val n = r.read(buf)
            if (n < 0) break
            for (i in 0 until n) {
                val c = buf[i]
                if (++skupaj > najvecZnakovSkupaj) return      // nedokoncana vrstica ne steje (kot prej: odrezan vnos ni vnos)
                if (c == '\n') { if (!prejsnjiCr) konec(); prejsnjiCr = false; continue }
                prejsnjiCr = false
                if (c == '\r') { konec(); prejsnjiCr = true; continue }
                if (predolga) continue
                if (sb.length >= najvecZnakov) { predolga = true; sb.setLength(0); continue }
                sb.append(c)
            }
        }
        if (sb.isNotEmpty() || predolga) { if (!predolga) obVrstici(sb.toString()); sb.setLength(0) }
    }

    /** Zapis vnosa v eno vrstico datoteke na disku (polja loci tabulator; tabulatorji in prelomi iz vsebine izginejo). */
    fun vVrstico(v: Vnos): String = listOf(v.ime, v.url, v.logo, v.zvrst, v.jezik, v.drzava, v.tvgId)
        .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') }

    /** Vnos iz vrstice [vVrstico]; null = pokvarjena vrstica. */
    fun izVrstice(s: String): Vnos? {
        val d = s.split('\t')
        if (d.size != 7 || d[0].isBlank() || !(d[1].startsWith("http://") || d[1].startsWith("https://"))) return null
        return Vnos(d[0], d[1], d[2], d[3], d[4], d[5], d[6])
    }

    /** Ali vnos ustreza izbiri mreze ([zvrst], [jezik]: prazno = vse). */
    fun ustreza(v: Vnos, zvrst: String, jezik: String): Boolean =
        (zvrst.isEmpty() || v.zvrst == zvrst) && (jezik.isEmpty() || v.jezik == jezik)

    /** Domac kanal: iz drzave naprave ali v jeziku vmesnika (»Domaci« so v mrezi prvi). */
    fun jeDomac(v: Vnos, drzavaNaprave: String, jezikNaprave: String): Boolean =
        (drzavaNaprave.isNotEmpty() && v.drzava.equals(drzavaNaprave, ignoreCase = true)) ||
            (jezikNaprave.isNotEmpty() && v.jezik.equals(jezikNaprave, ignoreCase = true))

    /** Seznam je zastarel (prenesemo ga znova v ozadju): starejsi od [OSVEZI_MS] ali ura nazaj. */
    const val OSVEZI_MS = 24 * 3_600_000L
    fun zastarel(prenesenOb: Long, zdaj: Long): Boolean = prenesenOb <= 0L || zdaj - prenesenOb !in 0 until OSVEZI_MS

    // ------------------------------------------------------------------ zascita racuna

    /**
     * Lastnik, 26. 10. 2026: »Nevarnost zaklepa racuna je prioriteta.« Preverba kanala odpre tok pri ponudniku. Placljivi
     * racuni IPTV (Xtream get.php?username=&password=, .../live/<uporabnik>/<geslo>/..., uporabnik:geslo@) imajo pogosto
     * najvec 1-2 hkratni povezavi: pregled 300 kanalov na krog na vsaki napravi kroga je zaklep. Takih virov v ozadju NIKOLI
     * ne preverjamo - kanal je viden, o delovanju odloci samo pravo predvajanje. V ozadju preverjamo samo sezname, ki so
     * POZITIVNO znani kot javni brezplacni ([JAVNI_SEZNAMI]); vse drugo velja kot racun (fail-closed, pregled Grok 39, I1:
     * prepoznava racuna je hevristika - kratka oblika Xtream /<u>/<g>/<id>, Stalker mac=, zaseben seznam na gist ali
     * github.io brez zetona v naslovu - in ob neznani obliki ne sme odpreti). Ista pravila kot Linux (core/kanali_iptv).
     */
    /** Znani javni brezplacni seznami: (gostitelj, zacetek poti z malimi crkami). Samo ti se preverjajo v ozadju. */
    val JAVNI_SEZNAMI = listOf("iptv-org.github.io" to "/", "raw.githubusercontent.com" to "/iptv-org/",
        "raw.githubusercontent.com" to "/free-tv/iptv/")
    private val RX_XTREAM_POT = Regex("/(?:live|movie|movies|series|timeshift)/[^/]+/[^/]+/", RegexOption.IGNORE_CASE)
    private val XTREAM_DATOTEKE = listOf("/get.php", "/player_api.php", "/xmltv.php", "/panel_api.php")
    /** Parametri, ki nosijo racun. Zetoni samo v naslovu seznama (zaseben seznam): javni CDN tok pogosto podpise z zetonom. */
    private val RACUN_PARAMETRI = setOf("username", "password", "user", "pass", "passwd", "pwd", "login", "mac")
    private val ZETON_PARAMETRI = setOf("token", "access_token", "private_token", "auth", "key", "apikey", "api_key")

    private class Deli(val gostitelj: String, val https: Boolean, val uporabnik: Boolean, val pot: String, val kljuci: Set<String>)

    /** Odkodiran %XX (in + pri poizvedbi); null = pokvarjeno kodiranje. */
    private fun odkodiraj(s: String, plus: Boolean): String? = try {
        java.net.URLDecoder.decode(if (plus) s else s.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) { null }

    /** Naslov http(s) po delih brez Androida; null = ni http(s) ali ga ne razumemo. */
    private fun deli(naslov: String): Deli? {
        val n = naslov.trim()
        val i = n.indexOf("://")
        if (i <= 0) return null
        val shema = n.substring(0, i).lowercase(java.util.Locale.ROOT)
        if (shema != "http" && shema != "https") return null
        val ostalo = n.substring(i + 3).substringBefore('#')
        val konec = ostalo.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) ostalo.length else it }
        val oblast = ostalo.substring(0, konec)
        // »uporabnik:geslo@« steje tudi za posevnico nazaj (brskalnik jo bere kot /).
        val uporabnik = '@' in oblast
        val gostitelj = oblast.substringAfterLast('@').let { g ->
            if (g.startsWith("[")) g.substringBefore(']') + "]" else g.substringBefore(':').substringBefore('\\')
        }.lowercase(java.util.Locale.ROOT).trimEnd('.')
        if (gostitelj.isEmpty() || gostitelj.any { it <= ' ' }) return null
        val pot = ostalo.substring(konec).substringBefore('?')
        val kljuci = ostalo.substring(konec).substringAfter('?', "").split('&', ';').filter { it.isNotEmpty() }
            .map { odkodiraj(it.substringBefore('='), plus = true)?.trim()?.lowercase(java.util.Locale.ROOT) ?: return null }.toSet()
        return Deli(gostitelj, shema == "https", uporabnik, pot, kljuci)
    }

    /**
     * Ali naslov nosi racun pri ponudniku: uporabnik:geslo@, pot Xtream (/live|movie|series|timeshift/<u>/<g>/,
     * get.php, player_api.php ...), parameter racuna (username, password ...); pri naslovu [seznam] tudi zeton (token=,
     * key= ...). Naslova, ki ga ne razumemo (ali ni http(s)), ne preverjamo: true. Nadomestek vira z racunom
     * (»https://<gostitelj>/safeer-skrito-<odtis>«, [skrivnostVira]) je racun: true.
     */
    fun jeVirSPoverilnico(naslov: String, seznam: Boolean = true): Boolean {
        if (SkrivnostiPravila.jeNadomestek(naslov)) return true
        val d = deli(naslov) ?: return true
        if (d.uporabnik) return true
        val poti = listOfNotNull(d.pot, odkodiraj(d.pot, plus = false) ?: return true).map { it.lowercase(java.util.Locale.ROOT) }
        if (poti.any { p -> RX_XTREAM_POT.containsMatchIn(p) || XTREAM_DATOTEKE.any { p.endsWith(it) } }) return true
        return d.kljuci.any { it in RACUN_PARAMETRI || (seznam && it in ZETON_PARAMETRI) }
    }

    /**
     * Seznam, ki ga smemo preverjati v ozadju: brez poverilnice IN na seznamu znanih javnih ([JAVNI_SEZNAMI]). Vse drugo
     * (ponudnik, domaci streznik, gist, github.io, GitLab, neznan gostitelj) velja kot racun - kot Linux.
     */
    fun javniBrezplacni(naslovSeznama: String): Boolean {
        if (jeVirSPoverilnico(naslovSeznama, seznam = true)) return false
        val d = deli(naslovSeznama) ?: return false
        val pot = (odkodiraj(d.pot, plus = false) ?: return false).lowercase(java.util.Locale.ROOT)
        if (".." in pot || "//" in pot) return false
        return JAVNI_SEZNAMI.any { (g, p) -> d.gostitelj == g && pot.startsWith(p) }
    }

    /** Kanal seznama sme v preverbo v ozadju: javni brezplacni seznam in naslov toka brez racuna. */
    fun preverbaDovoljena(naslovSeznama: String, naslovKanala: String): Boolean =
        javniBrezplacni(naslovSeznama) && !jeVirSPoverilnico(naslovKanala, seznam = false)

    /**
     * Dodatek s poverilnico (osebni zeton, kljuc debrida, racun Xtream v nastavitvah - [SkrivnostiPravila]) ali njegov
     * nadomestek: njegovih kanalov v ozadju ne sprasujemo (vsak klic gre na uporabnikov racun).
     */
    fun dodatekSPoverilnico(naslovDodatka: String): Boolean =
        SkrivnostiPravila.jeNadomestek(naslovDodatka) || SkrivnostiPravila.skrivnaPredponaDodatka(naslovDodatka) != null ||
            jeVirSPoverilnico(naslovDodatka, seznam = true)

    /**
     * Zakaj se kanali vira [naslov] ne pregledajo ("" = smejo): seznam, ki ni javni brezplacni, in dodatek s poverilnico
     * nikoli - tudi z rocnim »Preveri kanale zdaj« ne (seznam Xtream s 5000 kanali je 5000 odprtij racuna).
     */
    fun zakajNePregleda(jeSeznam: Boolean, naslov: String): String = when {
        jeSeznam && jeVirSPoverilnico(naslov, seznam = true) -> "seznam s poverilnico"
        jeSeznam && !javniBrezplacni(naslov) -> "ni javni brezplacni seznam"
        !jeSeznam && dodatekSPoverilnico(naslov) -> "dodatek s poverilnico"
        else -> ""
    }

    /**
     * Zakaj pregled kanalov vira zdaj ne tece ("" = tece): vir s poverilnico ali ni znani javni ([zakajNePregleda]) nikoli;
     * vir, ki ni bil dodan na tej napravi ([viriTukaj] - prejet z druge naprave), samo na izrecno zahtevo ([rocno]).
     */
    fun zakajNeVOzadju(jeSeznam: Boolean, naslov: String, dodanTu: Boolean, rocno: Boolean): String =
        zakajNePregleda(jeSeznam, naslov).ifEmpty { if (!rocno && !dodanTu) "vir z druge naprave" else "" }

    /** Naslov seznama za zaslon: s poverilnico (ali neznan) samo gostitelj - geslo Xtream ne gre na zaslon TV. */
    fun zaPrikaz(naslov: String): String =
        if (jeVirSPoverilnico(naslov, seznam = true)) gostitelj(naslov) else naslov.removePrefix("https://").removePrefix("http://")

    /**
     * Pregled Grok 39, I5: seznam, ki ga ponudnik zavrne (401/403, potekel racun), ali prenos, ki ne uspe, se ne prenasa
     * znova ob vsakem odprtju TV v zivo - premor 10 min, 1 h, 6 h, nato 24 h (vsak poskus je prijava v racun; ponudniki
     * Xtream zaklenejo racun ali IP po poplavi napacnih prijav). Uspeh premor izbrise.
     */
    val PREMORI_PRENOSA_MS = listOf(10 * 60_000L, 3_600_000L, 6 * 3_600_000L, 24 * 3_600_000L)

    /** Zaporedne napake prenosa seznama, cas naslednjega dovoljenega poskusa in ali je ponudnik seznam zavrnil. */
    data class NapakaPrenosa(val zaporednih: Int, val naslednjiOb: Long, val zavrnjen: Boolean, val zadnjiOb: Long) {
        fun vNiz() = "$zaporednih;$naslednjiOb;${if (zavrnjen) 1 else 0};$zadnjiOb"
        companion object {
            fun izNiza(s: String?): NapakaPrenosa? {
                val d = s.orEmpty().split(';')
                if (d.size != 4) return null
                return NapakaPrenosa(d[0].toIntOrNull() ?: return null, d[1].toLongOrNull() ?: return null, d[2] == "1", d[3].toLongOrNull() ?: return null)
            }
        }
    }

    /**
     * Ali je izid prenosa napaka s premorom: odgovor ni 2xx; pri seznamu, ki ni znani javni, tudi 2xx brez kanalov (Xtream
     * potekel racun pogosto vrne prazen seznam) in vsaka napaka povezave ([koda] 0). Javni seznam ob izpadu omrezja ne caka.
     */
    fun napakaPrenosa(koda: Int, kanalov: Int, naslovSeznama: String): Boolean = when {
        koda in 200..299 -> kanalov == 0 && !javniBrezplacni(naslovSeznama)
        koda == 0 -> !javniBrezplacni(naslovSeznama)
        else -> true
    }

    /** Ponudnik je seznam zavrnil (racun, geslo, potekla narocnina) - uporabnik to vidi na kartici vira. */
    fun zavrnjen(koda: Int, kanalov: Int, naslovSeznama: String): Boolean =
        koda == 401 || koda == 403 || (koda in 200..299 && kanalov == 0 && !javniBrezplacni(naslovSeznama))

    /** Napaka po neuspelem prenosu ob [zdaj]: naslednji premor iz [PREMORI_PRENOSA_MS]. */
    fun poNapaki(prej: NapakaPrenosa?, zdaj: Long, zavrnjen: Boolean): NapakaPrenosa {
        val n = (prej?.zaporednih ?: 0) + 1
        return NapakaPrenosa(n, zdaj + PREMORI_PRENOSA_MS[minOf(n, PREMORI_PRENOSA_MS.size) - 1], zavrnjen, zdaj)
    }

    /**
     * Ali se seznam ta hip sme prenesti: brez napake ali po premoru; ura, prestavljena nazaj (premor bi trajal dlje od
     * najdaljsega), ne zaklene. [rocno] (»Poskusi znova«): takoj, a najvec enkrat na minuto.
     */
    fun smePrenesti(napaka: NapakaPrenosa?, zdaj: Long, rocno: Boolean = false): Boolean = when {
        napaka == null -> true
        rocno -> zdaj - napaka.zadnjiOb !in 0 until 60_000L
        else -> zdaj >= napaka.naslednjiOb || napaka.naslednjiOb - zdaj > PREMORI_PRENOSA_MS.last()
    }

    /** Gostitelj naslova http(s) z malimi crkami ("" = ni naslov http(s)) - kljuc vrat preverb ([VrataGostiteljev]). */
    fun gostitelj(naslov: String): String = deli(naslov)?.gostitelj.orEmpty()

    /** Med dvema preverbama istega gostitelja (lastnik, 26. 10. 2026; Linux PREMOR_GOSTITELJA_S). */
    const val PREMOR_GOSTITELJA_MS = 3_000L
    /** Na dan (UTC) najvec toliko odprtij toka na gostitelja (Linux NAJVEC_NA_GOSTITELJA_DAN); kar ostane, je neznano in vidno. */
    const val NAJVEC_NA_GOSTITELJA_DAN = 200

    /**
     * Vrata preverb javnih seznamov po gostitelju (lastnik, 26. 10. 2026, kot Linux J3): hkrati najvec ena preverba, med
     * dvema [premorMs], na dan (UTC, po stenski uri) najvec [najvecNaDan] odprtij. Kljuc je odtis gostitelja (ime ne gre
     * na disk), premor tece po monotoni uri. Velja za vse preverbe naprave (pregled v ozadju in mreza TV v zivo). Niti varno.
     */
    class VrataGostiteljev(private val premorMs: Long = PREMOR_GOSTITELJA_MS, private val najvecNaDan: Int = NAJVEC_NA_GOSTITELJA_DAN) {
        enum class Izid { DA, CAKAJ, MEJA }

        private var dan = Long.MIN_VALUE
        private val odprtij = HashMap<String, Int>()
        private val zasedeni = HashSet<String>()
        private val prostOd = HashMap<String, Long>()

        /** DA = preverba sme (po njej [sprosti]); CAKAJ = gostitelja preverja drug ali tece premor; MEJA = danes ne vec. */
        @Synchronized fun zasedi(odtis: String, zdajMs: Long, monotonoMs: Long): Izid {
            novDan(zdajMs)
            if ((odprtij[odtis] ?: 0) >= najvecNaDan) return Izid.MEJA
            if (odtis in zasedeni || (prostOd[odtis] ?: Long.MIN_VALUE) > monotonoMs) return Izid.CAKAJ
            zasedeni += odtis
            odprtij[odtis] = (odprtij[odtis] ?: 0) + 1
            return Izid.DA
        }

        /** Preverba je koncana: premor do naslednje na tem gostitelju zacne teci zdaj. */
        @Synchronized fun sprosti(odtis: String, monotonoMs: Long) {
            zasedeni -= odtis
            prostOd[odtis] = monotonoMs + premorMs
            if (prostOd.size > 2_000) prostOd.entries.removeAll { it.value <= monotonoMs }
        }

        /** Odprtja danes na gostitelja (za dnevnik in preizkus). */
        @Synchronized fun odprtij(odtis: String, zdajMs: Long): Int { novDan(zdajMs); return odprtij[odtis] ?: 0 }

        /** Stevci za shrambo (»dan;odtis=n,...«) - meja velja tudi po ponovnem zagonu aplikacije. */
        @Synchronized fun vNiz(): String = "$dan;" + odprtij.entries.joinToString(",") { "${it.key}=${it.value}" }

        /** Stevci iz shrambe; drug dan ali pokvarjen zapis se ne uporabi. */
        @Synchronized fun izNiza(s: String?, zdajMs: Long) {
            val d = s?.substringBefore(';')?.toLongOrNull() ?: return
            if (d != Math.floorDiv(zdajMs, DAN_MS)) return
            novDan(zdajMs)
            for (par in s.substringAfter(';', "").split(',')) {
                val n = par.substringAfter('=', "").toIntOrNull() ?: continue
                val k = par.substringBefore('=')
                if (k.isNotEmpty() && n > 0) odprtij[k] = maxOf(odprtij[k] ?: 0, n)
            }
        }

        private fun novDan(zdajMs: Long) {
            val d = Math.floorDiv(zdajMs, DAN_MS)
            if (d != dan) { dan = d; odprtij.clear() }
        }
    }

    private const val DAN_MS = 86_400_000L

    /**
     * Lastnik, 26. 10. 2026: v krogu naprav kanale vira v ozadju preverja samo ena naprava - tista, na kateri je bil vir
     * dodan. Prej je vsaka naprava kroga ob odhodu v ozadje (in ob prejemu dodatka) preverjala iste kanale hkrati, z istim
     * racunom in z istega javnega IP. Odlocitev je krajevna (brez Linka, brez dogovora): prejet vir preverba ne zajame
     * nikoli, tudi ce Link ali druga naprava ne odgovarja - varno ob vsakem izpadu.
     *
     * Viri po shranjevanju, dodani na tej napravi: [prej]/[zdaj] = naslovi pred in po, [tukaj] = dosedanji zapis (null =
     * zapisa se ni: viri iz casa pred tem pravilom - TV, kjer je IPTV doma, jih vzame za svoje, telefon in tablica ne),
     * [prejeto] = sprememba je prevzem z druge naprave. Odstranjeni izpadejo.
     */
    fun viriTukaj(prej: Set<String>, zdaj: Set<String>, tukaj: Set<String>?, prejeto: Boolean, jeTv: Boolean): Set<String> {
        val osnova = tukaj ?: if (jeTv) prej else emptySet()
        val novi = if (prejeto) emptySet() else zdaj - prej
        return (osnova + novi) intersect zdaj
    }

    // ------------------------------------------------------------------ viri z racunom - sifrirano

    /**
     * Lastnik, 26. 10. 2026 (varovalo racunov): vir z racunom (Xtream get.php?username=&password=, /live/<u>/<g>/, u:g@,
     * token=, key=, mac= - [jeVirSPoverilnico]) je geslo uporabnikovega placljivega racuna. Do zdaj je sel z navadnim
     * usklajevanjem virov (lists.get) VSEM napravam v Linku in bil v nastavitvah nesifriran. Zdaj enako kot dodatki z
     * zetonom ([SkrivnostiPravila], SkrivniDodatki):
     *  - pravi naslov je shranjen sifriran (Android Keystore, SporocilaSkrivnosti); v nastavitvah virov je samo NADOMESTEK
     *    »<shema>://<gostitelj>/safeer-skrito-<odtis>« - gostitelj in oznaka racuna, brez uporabnika, gesla, poti in poizvedbe;
     *    pravi naslov se vstavi sele tik pred omreznim klicem (prenos seznama, predvajalnik);
     *  - drugim napravam gre samo v ozji krog (secrets.sync, kljuc »iptv:<odtis>«, [prejetRacun]); navadno usklajevanje ga
     *    ne poslje in ga ne sprejme ([smeZNavadnoUskladitvijo]);
     *  - na zaslonu in v dnevniku je samo gostitelj ([zaPrikaz], [varnoIme]).
     */
    private val VRSTE_RACUNA = Regex("^(seznam|tok(-hls)?(-video)?)$")

    /** Vrsta vira, ki lahko nosi racun IPTV: seznam (.m3u, Xtream) ali tok (kanal, dodan po naslovu). */
    fun jeVrstaRacuna(tip: String): Boolean = VRSTE_RACUNA.matches(tip)

    /**
     * Pravi naslov vira z racunom - to je skrivnost (http(s), brez fragmenta); null = vrsta brez racuna, naslov brez
     * poverilnice ali ze nadomestek. Nerazumljiv naslov http(s) velja kot racun (fail-closed, [jeVirSPoverilnico]).
     */
    fun skrivnostVira(tip: String, naslov: String): String? {
        if (!jeVrstaRacuna(tip)) return null
        val n = naslov.trim().substringBefore('#')
        if (!n.startsWith("http://") && !n.startsWith("https://")) return null
        if (SkrivnostiPravila.jeNadomestek(n) || !jeVirSPoverilnico(n, seznam = true)) return null
        return n
    }

    /** Vir z racunom v kateri koli obliki (pravi naslov ali nadomestek). */
    fun virZRacunom(tip: String, naslov: String): Boolean =
        jeVrstaRacuna(tip) && (SkrivnostiPravila.jeNadomestek(naslov) || skrivnostVira(tip, naslov) != null)

    /**
     * Vir ali grob vira (vrsta, naslov) sme z navadnim usklajevanjem virov (lists.get: »sources«, »sources_deleted«) - v obe
     * smeri, z vsako napravo v Linku. Ne smeta: vir z racunom IPTV ([virZRacunom]) in dodatek s poverilnico ali njegov
     * nadomestek (gresta samo v ozji krog). Vrsta »url« (naslov z racunalnika, ki vrste ne pozna) velja kot seznam.
     */
    fun smeZNavadnoUskladitvijo(tip: String, naslov: String): Boolean {
        if (tip == "stremio") return SkrivnostiPravila.smeMedVseNaprave(naslov)
        return !virZRacunom(if (tip == "url") "seznam" else tip, naslov)
    }

    /** Odseki pravega naslova, ki so lahko del racuna: uporabnik, geslo, odseki poti (tudi brez koncnice), vrednosti poizvedbe. */
    private fun deliRacuna(naslov: String): Set<String> {
        val n = naslov.trim().substringBefore('#')
        val i = n.indexOf("://")
        if (i <= 0) return emptySet()
        val ostalo = n.substring(i + 3)
        val konec = ostalo.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) ostalo.length else it }
        val oblast = ostalo.substring(0, konec)
        val izid = HashSet<String>()
        if ('@' in oblast) oblast.substringBeforeLast('@').split(':').forEach { izid += it }
        val pot = ostalo.substring(konec).substringBefore('?')
        for (o in pot.split('/')) { val d = odkodiraj(o, plus = false) ?: o; izid += d; izid += d.substringBeforeLast('.') }
        for (par in ostalo.substring(konec).substringAfter('?', "").split('&', ';'))
            par.substringAfter('=', "").let { izid += odkodiraj(it, plus = true) ?: it }
        return izid.map { it.trim().lowercase(java.util.Locale.ROOT) }.filter { it.length >= 3 }.toSet()
    }

    /**
     * Ime vira z racunom za nastavitve, zaslon in Link: samodejno ime (zadnji odsek poti, npr. pri ».../<uporabnik>/<geslo>«)
     * je lahko geslo. Ce ime vsebuje kateri del racuna iz [skrivnost] ([deliRacuna]) ali je prazno, je ime gostitelj
     * (stevilo kanalov » (N)« ostane); sicer ostane, najvec 200 znakov, brez kontrolnih znakov.
     */
    fun varnoIme(ime: String, skrivnost: String): String {
        val cisto = ime.filter { it >= ' ' }.trim().take(200)
        val m = cisto.lowercase(java.util.Locale.ROOT)
        if (cisto.isNotEmpty() && deliRacuna(skrivnost).none { m.contains(it) }) return cisto
        val stevilo = Regex(" \\(\\d{1,6}\\)$").find(cisto)?.value.orEmpty()
        return gostitelj(skrivnost).ifEmpty { "IPTV" } + stevilo
    }

    /** Vir v navadnih nastavitvah (Moji viri): vrsta, ime, naslov. */
    data class ZapisVira(val tip: String, val ime: String, val naslov: String)

    /**
     * Zapis vira za navadne nastavitve. Vir z racunom: [zavaruj] skrivnost (pravi naslov) shrani sifrirano in vrne nadomestek;
     * zapis dobi nadomestek in [varnoIme]. Ce sifriranje ne uspe (null): ze shranjen vir ([zeShranjen], iz casa pred prehodom)
     * ostane, kot je - ne izgubi se, a ne gre nikamor ([smeZNavadnoUskladitvijo]); nov se ne shrani (null).
     */
    fun zaShrambo(z: ZapisVira, zeShranjen: Boolean, zavaruj: (String) -> String?): ZapisVira? {
        val s = skrivnostVira(z.tip, z.naslov) ?: return z
        val n = zavaruj(s) ?: return if (zeShranjen) z else null
        return ZapisVira(z.tip, varnoIme(z.ime, s), n)
    }

    /**
     * Prehod iz razlicice pred 26. 10. 2026: viri z racunom v nastavitvah s pravim naslovom. Vrne zamenjave pravi naslov ->
     * nadomestek (iz [zavaruj], ki skrivnost shrani sifrirano) - te veljajo za VSE nastavitve aplikacije
     * ([SkrivnostiPravila.preseliVnose]: Moji viri, casi, »dodani tu«, pripeti, seznam kanalov, nedavno ...). Vir, ki ga ni
     * bilo mogoce zavarovati, ni v zamenjavah (ostane).
     */
    fun zamenjaveRacunov(viri: List<ZapisVira>, zavaruj: (String) -> String?): Map<String, String> {
        val izid = LinkedHashMap<String, String>()
        for (v in viri) {
            val s = skrivnostVira(v.tip, v.naslov) ?: continue
            if (s in izid) continue
            zavaruj(s)?.let { izid[s] = it }
        }
        return izid
    }

    /**
     * Prehod: zapisi Mojih virov po [zamenjaveRacunov] - vir z racunom dobi nadomestek in [varnoIme] (samodejno ime je bil
     * lahko zadnji odsek poti = geslo; samo zamenjava naslova ga ne bi odstranila). Ostali ostanejo, kot so.
     */
    fun preseliZapise(viri: List<ZapisVira>, zamenjave: Map<String, String>): List<ZapisVira> = viri.map { z ->
        val s = skrivnostVira(z.tip, z.naslov)
        val n = s?.let { zamenjave[it] }
        if (s == null || n == null) z else ZapisVira(z.tip, varnoIme(z.ime, s), n)
    }

    /**
     * Prehod: grobovi virov (kljuc »vrsta|naslov« -> cas) brez tistih s PRAVIM naslovom z racunom - izbrisan vir je imel
     * geslo v kljucu, grob pa je sel z navadnim usklajevanjem vsem napravam. Grob z nadomestkom ostane (samo krajevno).
     */
    fun brezGrobovZRacunom(izbrisani: Map<String, Long>): Map<String, Long> = izbrisani.filterKeys { k ->
        val tip = k.substringBefore('|').let { if (it == "url") "seznam" else it }
        skrivnostVira(tip, k.substringAfter('|', "")) == null
    }

    /**
     * Skladba (kanal, tok dodatka) nosi racun, ce ga nosi kateri od njenih naslovov http(s) ([naslovi]: tok, povezava,
     * seznam): pot ali parameter racuna ([jeVirSPoverilnico] kot za tok), nadomestek ali nerazumljiv naslov.
     */
    fun skladbaZRacunom(naslovi: List<String>): Boolean = naslovi.any { n ->
        val t = n.trim()
        (t.startsWith("http://") || t.startsWith("https://")) && jeVirSPoverilnico(t, seznam = false)
    }

    /**
     * Skladba seznama predvajanja za napravo ZUNAJ ozjega kroga (lists.get; lastnik, 26. 10. 2026, kot Linux 0f3f368): ce
     * nosi racun ([skladbaZRacunom] za [naslovi] in zapisani tok), gre brez toka in brez polnega zapisa - ime, izvajalec,
     * trajanje in slika (brez racuna) ostanejo. Napravi v ozjem krogu gre cel zapis.
     */
    fun zapisZaNavadniKrog(z: SeznamiPravila.Zapis, naslovi: List<String>): SeznamiPravila.Zapis =
        if (!skladbaZRacunom(naslovi + z.url)) z
        else SeznamiPravila.Zapis(z.naslov, z.izvajalec, "", z.sekund, if (skladbaZRacunom(listOf(z.slika))) "" else z.slika, "", z.video, "")

    /** Odtis vira z racunom (kljuc »iptv:<odtis>« v ozjem krogu) = odtis nadomestka ([SkrivnostiPravila.nadomestek]). */
    fun odtisRacuna(skrivnost: String): String = SkrivnostiPravila.odtis(skrivnost.trim())

    /**
     * Vir z racunom z naprave v OZJEM KROGU (secrets.sync, kljuc »iptv:<odtis>«): veljaven samo, ce je vrsta racuna, naslov
     * http(s) 12-2048 znakov brez presledkov in kontrolnih znakov, res nosi racun (ni nadomestek, brez fragmenta) in se
     * njegov odtis ujema s kljucem. Ime dobi [varnoIme]. null = zavrni.
     */
    fun prejetRacun(odtis: String, tip: String, naslov: String, ime: String): ZapisVira? {
        if (!jeVrstaRacuna(tip) || naslov.length !in 12..2048 || naslov.any { it <= ' ' }) return null
        if (skrivnostVira(tip, naslov) != naslov || SkrivnostiPravila.nadomestek(naslov) == null) return null
        if (odtisRacuna(naslov) != odtis) return null
        return ZapisVira(tip, varnoIme(ime, naslov), naslov)
    }
}
