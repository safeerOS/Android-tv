package si.safeer.tv.os

import java.security.MessageDigest

/**
 * Webtor rezerva (pot A, 25. 10. 2026): naslovi dodatkov, ki nosijo poverilnico - osebni zeton Webtorja
 * (»https://webtor.io/token/<zeton>/stremio«, kratka povezava »/s/<koda>«), Torrentio z debrid kljucem v nastavitvah
 * (»/realdebrid=<kljuc>|sort=...«), nastavitve JSON ali base64 s kljucem (»tmdbApiKey«, »rpdbkey«, »traktAccessToken«),
 * uporabnik in geslo v naslovu. Tak naslov je geslo racuna:
 *  - shranjen je sifriran ([SkrivniDodatki], Android Keystore - kot debrid kljuci);
 *  - v aplikaciji (id-ji kartic, zgodovina, seznami, Link, dnevnik) velja NADOMESTEK brez skrivnosti
 *    »https://<gostitelj>/safeer-skrito-<odtis>/...«: pravi naslov se vstavi sele tik pred omreznim klicem;
 *  - drugim napravam gre samo v ozji krog (UskladitevKljucev), nikoli z navadnim usklajevanjem virov.
 *
 * Odlocitev je po zgradbi in znanih imenih (pregled 25. 10. 2026, S2/S3): ime polja s kljucno besedo (key, token,
 * secret, password, auth ... - tudi kot del imena: »tmdbApiKey«) ali debrid storitve; odsek za »/token/«, »/realdebrid/«
 * ...; dolg nerazberljiv zeton. NI skrivnost: UUID, hash (32/40/64 hex), nastavitve base64/JSON brez kljucnega polja.
 * Pri dodatku je skrita CELA osnova (vse pred »/manifest.json«), pri toku del do ZADNJEGA skrivnega odseka, pri zetonu v
 * poizvedbi cel naslov.
 * Ciste funkcije (tests/SkrivnostiPravilaTest.kt, skupni primeri tests/skupni-primeri/webtor-rezerva.json).
 */
object SkrivnostiPravila {
    const val OZNAKA = "safeer-skrito-"
    private val RX_NADOMESTEK = Regex("^(https?://[^/?#@\\s]+/" + OZNAKA + "[0-9a-f]{16})(?=$|[/?#])")

    /** Debrid in podobne storitve: ime polja z njihovim kljucem ali odsek poti pred kljucem (»/realdebrid/<kljuc>/«). */
    private val STORITVE = setOf("realdebrid", "real-debrid", "alldebrid", "torbox", "premiumize", "debridlink", "debrid-link",
        "offcloud", "putio", "easydebrid", "stremthru")
    /** Besede v imenu polja (po delih imena: »tmdbApiKey« = tmdb, api, key). */
    private val BESEDE = setOf("key", "apikey", "token", "secret", "password", "passwd", "pass", "auth", "cookie", "session",
        "credential", "credentials", "jwt", "bearer", "sig", "signature", "authorization")
    /** Konec imena polja, ki je kljuc (»rpdbkey«, »mdblistapikey«, »accesstoken«). */
    private val KONCNICE = listOf("key", "token", "secret", "password")
    /** Odsek za temi je skrivnost (»/token/<zeton>/«, »/key/<kljuc>/«, »/realdebrid/<kljuc>/«). */
    private val PREDHODNIKI = setOf("token", "key", "apikey", "api_key", "api-key", "auth", "secret") + STORITVE
    private const val NAJMANJ_DOLG = 20

    private val RX_POLJE = Regex("(?<![A-Za-z0-9_-])([A-Za-z][A-Za-z0-9_-]{0,63})\"?\\s*[=:]\\s*\"?([^\"|&;,}\\]\\s]*)")
    private val RX_UUID = Regex("^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$")
    private val RX_HEX = Regex("^[0-9a-fA-F]+$")
    private val RX_ZETON = Regex("^[A-Za-z0-9_\\-+/=.~]+$")

    /** Deli imena polja: »tmdbApiKey« -> tmdb, api, key; »rpdb_key« -> rpdb, key. */
    private fun deliImena(ime: String): List<String> =
        ime.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").split(' ', '_', '-').map { it.lowercase() }.filter { it.isNotEmpty() }

    /** Ime polja pove, da je vrednost skrivnost. */
    fun jeKljucnoIme(ime: String): Boolean {
        val m = ime.lowercase()
        if (m in STORITVE) return true
        val deli = deliImena(ime)
        if (deli.any { it in BESEDE || it in STORITVE }) return true
        return KONCNICE.any { k -> m.endsWith(k) } || deli.any { d -> KONCNICE.any { k -> d.endsWith(k) } }
    }

    /** Besedilo (nastavitve dodatka, poizvedba) ima polje s kljucnim imenom in neprazno vrednostjo. */
    fun imaKljucnoPolje(besedilo: String): Boolean = RX_POLJE.findAll(besedilo).any { m ->
        val v = m.groupValues[2].trim()
        jeKljucnoIme(m.groupValues[1]) && v.isNotEmpty() && v.lowercase() !in setOf("null", "none", "false", "true", "0", "undefined")
    }

    /**
     * Base64 (navaden ali za naslove, z dopolnilom ali brez), ki se razbere v berljivo besedilo (nastavitve) - to besedilo;
     * sicer null (nakljucni zeton, binarno).
     */
    fun base64Besedilo(s: String): String? {
        val t = s.trim().trimEnd('=')
        if (t.length < 8 || !t.all { it.isLetterOrDigit() || it in "+/-_" }) return null
        val bajti = try {
            if (t.any { it == '-' || it == '_' }) java.util.Base64.getUrlDecoder().decode(t) else java.util.Base64.getDecoder().decode(t)
        } catch (_: Exception) { return null }
        val b = try {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bajti)).toString()
        } catch (_: Exception) { return null }
        if (b.isEmpty()) return null
        val berljivih = b.count { it == '\n' || it == '\t' || (it >= ' ' && it != '\u007f' && it != '\uFFFD') }
        return if (berljivih * 10 >= b.length * 9 && b.any { it.isLetter() }) b else null
    }

    /**
     * Dolg nerazberljiv zeton (kljuc, zeton, sifrirane nastavitve). Ni zeton: UUID (id), hash (32/40/64 hex), niz brez crk ali
     * brez stevk, base64 z berljivim besedilom - to so nastavitve in odloca kljucno polje v njih.
     */
    fun jeNeprozorenZeton(s: String): Boolean {
        if (s.length < NAJMANJ_DOLG || !RX_ZETON.matches(s)) return false
        if (RX_UUID.matches(s)) return false
        if (RX_HEX.matches(s) && s.length in setOf(32, 40, 64)) return false
        if (!s.any { it.isDigit() } || !s.any { it.isLetter() }) return false
        val besedilo = base64Besedilo(s)
        return if (besedilo != null) imaKljucnoPolje(besedilo) else true
    }

    /** Odsek poti (brez »/«) je skrivnost. [prejsnji] = odsek pred njim, [webtor] = gostitelj webtor.io (»/s/<koda>«). */
    fun jeSkrivenOdsek(odsek: String, prejsnji: String = "", webtor: Boolean = false): Boolean {
        if (odsek.isEmpty() || odsek.startsWith(OZNAKA)) return false
        if (prejsnji.lowercase() in PREDHODNIKI) return true
        if (webtor && prejsnji == "s") return true
        val d = odkodiraj(odsek)
        if (imaKljucnoPolje(d)) return true
        return jeNeprozorenZeton(d)
    }

    fun jeWebtorGostitelj(gostitelj: String): Boolean {
        val g = gostitelj.lowercase().substringBefore(':').trimEnd('.')
        return g == "webtor.io" || g.endsWith(".webtor.io")
    }

    private class Deli(val shema: String, val zUporabnikom: Boolean, val gostitelj: String, val zacetekPoti: Int, val pot: String,
                       val konecPoti: Int, val poizvedba: String, val konecPoizvedbe: Int)

    private fun razcleni(naslov: String): Deli? {
        val i = naslov.indexOf("://")
        if (i <= 0) return null
        val shema = naslov.substring(0, i).lowercase()
        if (shema != "http" && shema != "https") return null
        val zacetek = i + 3
        var konecAvt = naslov.length
        for (j in zacetek until naslov.length) if (naslov[j] == '/' || naslov[j] == '?' || naslov[j] == '#') { konecAvt = j; break }
        val avtoriteta = naslov.substring(zacetek, konecAvt)
        val afna = avtoriteta.lastIndexOf('@')
        val gostitelj = (if (afna >= 0) avtoriteta.substring(afna + 1) else avtoriteta).lowercase()
        if (gostitelj.isEmpty() || gostitelj.any { it <= ' ' }) return null
        var konecPoti = naslov.length
        for (j in konecAvt until naslov.length) if (naslov[j] == '?' || naslov[j] == '#') { konecPoti = j; break }
        var konecPoizvedbe = konecPoti
        if (konecPoti < naslov.length && naslov[konecPoti] == '?') {
            konecPoizvedbe = naslov.indexOf('#', konecPoti).let { if (it < 0) naslov.length else it }
        }
        val poizvedba = if (konecPoizvedbe > konecPoti) naslov.substring(konecPoti + 1, konecPoizvedbe) else ""
        return Deli(shema, afna >= 0, gostitelj, konecAvt, naslov.substring(konecAvt, konecPoti), konecPoti, poizvedba, konecPoizvedbe)
    }

    /** Konci skrivnih odsekov poti (indeks v naslovu za odsekom). */
    private fun skrivniKonci(n: String, d: Deli): List<Int> {
        val webtor = jeWebtorGostitelj(d.gostitelj)
        val izid = ArrayList<Int>()
        var prejsnji = ""
        var konec = d.zacetekPoti
        for ((k, o) in d.pot.split('/').withIndex()) {
            if (k == 0) continue
            konec += 1 + o.length
            if (jeSkrivenOdsek(o, prejsnji, webtor)) izid += konec
            if (o.isNotEmpty()) prejsnji = o
        }
        return izid
    }

    /** Pot brez »/manifest.json« in brez koncnih »/«: nastavitve dodatka. */
    private fun potNastavitev(pot: String): String {
        var p = pot.trimEnd('/')
        if (p.lowercase().endsWith("/manifest.json")) p = p.substring(0, p.length - "/manifest.json".length)
        return p.trimEnd('/')
    }

    /**
     * Dodatek (naslov manifesta ali osnova): ce kjerkoli nosi skrivnost (odsek, nastavitve base64, razsekane z »/«,
     * uporabnik/geslo), je skrivna CELA osnova - vse pred »/manifest.json« (brez poizvedbe). null = ni skrivnosti ali je
     * ze nadomestek.
     */
    fun skrivnaPredponaDodatka(naslov: String): String? {
        val n = naslov.trim()
        if (RX_NADOMESTEK.containsMatchIn(n)) return null
        val d = razcleni(n) ?: return null
        val nastavitve = potNastavitev(d.pot)
        val osnova = n.substring(0, d.zacetekPoti) + nastavitve
        if (d.zUporabnikom) return osnova
        if (d.poizvedba.isNotEmpty() && imaKljucnoPolje(odkodiraj(d.poizvedba))) return osnova
        // Nastavitve v base64 (lahko razsekane z »/« - navaden base64 ga vsebuje; Uri.Builder.path odkodira %2F): odloca
        // samo kljucno polje v razbranem besedilu (pregled S3: nastavitve brez kljuca niso skrivnost).
        val cele = odkodiraj(nastavitve.trim('/'))
        val besedilo = if (cele.length >= 8) base64Besedilo(cele) else null
        if (besedilo != null) return if (imaKljucnoPolje(besedilo)) osnova else null
        return if (skrivniKonci(n, d).isNotEmpty()) osnova else null
    }

    /**
     * Tok (in drugi naslovi): del do vkljucno ZADNJEGA skrivnega odseka poti; zeton v poizvedbi (»?token=«, »?api_key=«):
     * cel naslov brez fragmenta; uporabnik/geslo: vsaj »shema://u:g@gostitelj«. null = brez skrivnosti ali ze nadomestek.
     */
    fun skrivnaPredponaToka(naslov: String): String? {
        val n = naslov.trim()
        if (RX_NADOMESTEK.containsMatchIn(n)) return null
        val d = razcleni(n) ?: return null
        if (d.poizvedba.isNotEmpty() && imaKljucnoPolje(odkodiraj(d.poizvedba))) return n.substring(0, d.konecPoizvedbe)
        val konci = skrivniKonci(n, d)
        if (konci.isNotEmpty()) return n.substring(0, konci.last())
        return if (d.zUporabnikom) n.substring(0, d.zacetekPoti) else null
    }

    /** Naslov dodatka ali toka nosi skrivnost. */
    fun jeSkrivenNaslov(naslov: String): Boolean = skrivnaPredponaDodatka(naslov) != null || skrivnaPredponaToka(naslov) != null

    /** Prvih 16 znakov SHA-256 (hex): enak na vseh napravah, iz njega se zetona ne da izracunati. */
    fun odtis(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { b -> HEX[(b.toInt() shr 4) and 0xf].toString() + HEX[b.toInt() and 0xf] }.take(16)
    private const val HEX = "0123456789abcdef"

    /**
     * Pregled U8: odtis s skrivnim kljucem naprave [kljucHex] (HMAC-SHA256, prvih 16 znakov hex) - za lokalne odtise
     * vrednosti, ki ne gredo med naprave; iz odtisa se kratke skrivnosti ne da preveriti brez kljuca.
     */
    fun odtisSKljucem(kljucHex: String, s: String): String {
        val k = kljucHex.chunked(2).mapNotNull { it.toIntOrNull(16)?.toByte() }.toByteArray()
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(if (k.isEmpty()) byteArrayOf(0) else k, "HmacSHA256"))
        return mac.doFinal(s.toByteArray(Charsets.UTF_8)).joinToString("") { b -> HEX[(b.toInt() shr 4) and 0xf].toString() + HEX[b.toInt() and 0xf] }.take(16)
    }

    /** Nadomestna osnova za skrivno predpono: »https://webtor.io/safeer-skrito-<odtis>« (brez uporabnika, gesla, zetona). */
    fun nadomestek(predpona: String): String? {
        val d = razcleni(predpona) ?: return null
        return "${d.shema}://${d.gostitelj}/$OZNAKA${odtis(predpona)}"
    }

    /** Naslov dodatka -> isti naslov z nadomestkom cele osnove (»/manifest.json« ostane); brez skrivnosti nespremenjen. */
    fun vNadomestek(naslov: String): String {
        val n = naslov.trim()
        val p = skrivnaPredponaDodatka(n) ?: return n
        val z = nadomestek(p) ?: return n
        return z + n.substring(p.length).substringBefore('?').substringBefore('#')
    }

    /** Nadomestna osnova v naslovu (»https://h/safeer-skrito-<odtis>«) ali null. */
    fun nadomestnaOsnova(naslov: String): String? = RX_NADOMESTEK.find(naslov.trim())?.groupValues?.get(1)

    fun jeNadomestek(naslov: String): Boolean = nadomestnaOsnova(naslov) != null

    /** Vir sme z navadnim usklajevanjem virov na vse naprave v Linku: ne nosi skrivnosti in ni nadomestek. */
    fun smeMedVseNaprave(naslov: String): Boolean = skrivnaPredponaDodatka(naslov) == null && !jeNadomestek(naslov)

    /**
     * Pregled S3: tok kartice se ob shranjevanju (zgodovina, priljubljene, seznami) izbrise samo pri kartici dodatka s
     * poverilnico in samo, ce ima tok se pravo skrivnost (nadomestek ostane); navadnim dodatkom (CDN z dolgim id-jem) nikoli.
     */
    fun odstraniTokObShranjevanju(osnovaKartice: String, tok: String): Boolean =
        (jeNadomestek(osnovaKartice) || skrivnaPredponaDodatka(osnovaKartice) != null) &&
            tok.isNotBlank() && !jeNadomestek(tok) && skrivnaPredponaToka(tok) != null

    /** Za zaslon in dnevnik: »https://webtor.io/…/stremio/manifest.json« - skrivnost ali nadomestek zakrit z »…«. */
    fun zaPrikaz(naslov: String): String {
        val n = naslov.trim()
        val osnova = nadomestnaOsnova(n) ?: skrivnaPredponaDodatka(n) ?: skrivnaPredponaToka(n) ?: return n
        val d = razcleni(osnova) ?: return "…"
        return "${d.shema}://${d.gostitelj}/…" + n.substring(minOf(osnova.length, n.length)).substringBefore('?').substringBefore('#')
    }

    const val NAJVEC_PREJETIH = 20

    /** Skrivni dodatki z druge naprave v ozjem krogu: samo veljavni naslovi s skrivnostjo, najvec 20. */
    fun prejetiDodatki(seznam: List<String?>): List<String> = seznam.asSequence().filterNotNull().map { it.trim() }
        .filter { veljavenSkrivniDodatek(it) }.distinct().take(NAJVEC_PREJETIH).toList()

    /** Pregled U5: dodatek s poverilnico samo po https - zeton v naslovu po http bi potoval necifriran. */
    fun veljavenSkrivniDodatek(n: String): Boolean = n.length in 12..2048 && n.startsWith("https://") &&
        n.none { c -> c < ' ' } && skrivnaPredponaDodatka(n) != null

    // ------------------------------------------------------------------ prehod (pregled S1)

    /** Pregled U12: vnosi iz [novo], ki se razlikujejo od [staro] (nespremenjenih ne zapisemo znova). */
    fun spremenjeniVnosi(staro: Map<String, *>, novo: Map<String, *>): Map<String, Any?> =
        novo.filter { (k, v) -> !staro.containsKey(k) || staro[k] != v }

    /** Zamenja prave skrivne predpone z nadomestki (daljse najprej; org.json pise »/« tudi kot »\/«). */
    fun zamenjaj(besedilo: String, zamenjave: Map<String, String>): String {
        var r = besedilo
        for ((k, v) in zamenjave.entries.sortedByDescending { it.key.length }) {
            if (k.isEmpty()) continue
            r = r.replace(k, v).replace(k.replace("/", "\\/"), v.replace("/", "\\/"))
        }
        return r
    }

    fun vsebujeKatero(besedilo: String, pravi: Collection<String>): Boolean =
        pravi.any { it.isNotEmpty() && (besedilo.contains(it) || besedilo.contains(it.replace("/", "\\/"))) }

    /**
     * Prehod ene datoteke nastavitev: kljuci in vrednosti (niz, nabor nizov) s pravo predpono dobijo nadomestek. null = ni
     * spremembe. Druge vrste vrednosti ostanejo (pod novim kljucem, ce se kljuc spremeni).
     */
    fun preseliVnose(vnosi: Map<String, Any?>, zamenjave: Map<String, String>): Map<String, Any?>? {
        if (zamenjave.isEmpty()) return null
        var spremenjeno = false
        val izid = LinkedHashMap<String, Any?>()
        for ((k, v) in vnosi) {
            val nk = zamenjaj(k, zamenjave)
            val nv: Any? = when (v) {
                is String -> zamenjaj(v, zamenjave)
                is Set<*> -> v.map { if (it is String) zamenjaj(it, zamenjave) else it }.toSet()
                else -> v
            }
            if (nk != k || nv != v) spremenjeno = true
            izid[nk] = nv
        }
        return if (spremenjeno) izid else null
    }

    private fun hex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** Odstotkovno kodiranje (%XX, UTF-8) brez »+« -> presledek; neveljavno ostane, kot je. */
    fun odkodiraj(s: String): String {
        if ('%' !in s) return s
        val sb = StringBuilder()
        val bajti = java.io.ByteArrayOutputStream()
        fun izprazni() { if (bajti.size() > 0) { sb.append(String(bajti.toByteArray(), Charsets.UTF_8)); bajti.reset() } }
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length && hex(s[i + 1]) && hex(s[i + 2])) {
                bajti.write(s.substring(i + 1, i + 3).toInt(16)); i += 3
            } else { izprazni(); sb.append(s[i]); i++ }
        }
        izprazni()
        return sb.toString()
    }
}
