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

    private val RX_ATRIBUT = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

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

    /** Drzava in jezik iz naslova seznama: ».../countries/si.m3u« -> (SI, ""), ».../languages/eng.m3u« -> ("", en). */
    fun izNaslovaSeznama(url: String): Pair<String, String> {
        val drzava = RX_DRZAVA_SEZNAMA.find(url)?.groupValues?.get(1)?.uppercase(java.util.Locale.ROOT).orEmpty()
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
        val tvgId = a["tvg-id"].orEmpty()
        val drzava = a["tvg-country"]?.split(';', ',')?.firstOrNull()?.trim()?.takeIf { it.length == 2 }?.uppercase(java.util.Locale.ROOT)
            ?: RX_DRZAVA_ID.find(tvgId)?.groupValues?.get(1)?.uppercase(java.util.Locale.ROOT) ?: drzavaSeznama
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
}
