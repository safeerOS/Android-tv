package si.safeer.tv.os

import java.net.URI
import java.net.URLEncoder

/**
 * Naloga 141: Usenet prek TorBoxa. NZB najde uporabnikov indeksator (Newznab), prenese in pretaka ga TorBox (paket Pro).
 * Ciste odlocitve brez Androida in omrezja (preizkus: tests/UsenetPravilaTest.kt); odjemalec je [Usenet], prenos [TorBox.usenet].
 *
 * Iz dokumentacije (ne ugibano):
 * - Newznab (specifikacija nZEDb docs/newznab_api_specification.txt, prebrano 24. 10. 2026): `t=movie&imdbid=0058935`
 *   (IMDb brez »tt«), `t=tvsearch&imdbid=…&season=13&ep=13`, `apikey` v naslovu; izid je RSS - `item` z `title`, `guid`,
 *   `enclosure url` (naslov NZB, ki vsebuje kljuc uporabnika) in `newznab:attr name="size"`; napaka je
 *   `<error code="…" description="…"/>`: 100 napacni podatki, 101 racun zaustavljen, 102 ni pravic, 500 meja zahtev,
 *   501 meja prenosov, 900 neznano. `imdbid` ni v vsakem indeksatorju (Servarr/Torznab: priporoceno, ne obvezno).
 * - TorBox (uradni OpenAPI api.torbox.app, prebrano 24. 10. 2026): `usenet/createusenetdownload` (multipart: `file` ali
 *   `link`, `name`, `add_only_if_cached`), `usenet/mylist?id=`, `usenet/requestdl?token&usenet_id&file_id`.
 *   TorBox Search (search-api.torbox.app) ne obstaja vec (ime se ne razresi; odstranitev porocana 10. 10. 2026).
 *
 * Zasebnost: kljuc indeksatorja ostane na napravi - NZB prenese naprava sama in TorBoxu poslje samo datoteko (tudi
 * zato, ker nekateri indeksatorji streznike TorBoxa blokirajo). Povezave NZB (s kljucem) niso v toku ne v dnevniku.
 */
object UsenetPravila {
    const val VRSTA = "usenet"
    const val PREDPONA = "usenet:"

    /** Indeksator ni vrnil NZB (napacen kljuc, meja, napaka) - za sporocilo uporabniku. */
    const val NAPAKA_INDEKSATOR = "indeksator"

    /** Najvec zadetkov iskanja, ki gredo med tokove (TokIzbira jih uredi). */
    const val NAJVEC_ZADETKOV = 40

    /** NZB datoteka je nekaj sto kB do nekaj MB; vec ne sprejmemo. */
    const val NAJVEC_NZB_BAJTOV = 20 * 1024 * 1024

    /** Pregled A11: NZB (ali napovedana dolzina, -1 = ni znana) nad mejo zavrnemo z napako - odrezan NZB bi bil pokvarjen. */
    fun nzbPrevelik(bajtov: Long): Boolean = bajtov > NAJVEC_NZB_BAJTOV

    /** Rok branja odgovora indeksatorja pri iskanju (vzpostavitev povezave najvec [ISKANJE_POVEZAVA_MS]). */
    const val ISKANJE_ROK_MS = 8_000
    const val ISKANJE_POVEZAVA_MS = 5_000

    /**
     * Pregled A8: tako dolgo pocakamo na iskanje Usenet pri izbiri toka (povezava + branje), da je »Usenet prvi« zanesljiv -
     * tudi ce dodatki odgovorijo prej.
     */
    const val ISKANJE_CAKAJ_MS = (ISKANJE_POVEZAVA_MS + ISKANJE_ROK_MS + 500).toLong()

    /** Usenet prenos pri TorBoxu je hiter (streznik do streznika), a velik film traja - cakamo do 10 min z napredkom. */
    const val CAKAJ_PRENOSA_MS = 600_000L

    private fun jeLokalniGostitelj(h: String): Boolean {
        val g = h.lowercase().trimEnd('.')
        if (g == "localhost" || g.endsWith(".local")) return true
        // Pregled A10: samo pravi naslov IPv4 (stiri stevila 0-255) - ime »10.example.com« ni domace omrezje.
        val d = g.split('.')
        if (d.size != 4 || d.any { it.isEmpty() || it.length > 3 || !it.all { c -> c in '0'..'9' } }) return false
        val b = d.map { it.toInt() }
        if (b.any { it > 255 }) return false
        return b[0] == 127 || b[0] == 10 || (b[0] == 192 && b[1] == 168) || (b[0] == 172 && b[1] in 16..31)
    }

    // Pregled Grok 31, H1 (26. 10. 2026): kljuc, prilepljen v polje za naslov, ne sme postati gostitelj https://<kljuc> - ime
    // gostitelja gre pred povezavo necifrirano v DNS (tudi s pripono omrezja, npr. <kljuc>.home). Kljuci: Newznab 32 heks,
    // TorBox UUID, Real-Debrid 52 znakov, AllDebrid ~20 znakov - nobeden nima pike.
    private val RX_UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val RX_HEKS_DOLG = Regex("^[0-9a-f]{24,}$")

    /** Del imena, ki je kljuc in ne ime (v imenu s piko): UUID ali vsaj 24 heks znakov. */
    private fun oznakaJeKljuc(oznaka: String): Boolean = oznaka.lowercase().let { RX_UUID.matches(it) || RX_HEKS_DOLG.matches(it) }

    /**
     * Gostitelj indeksatorja je ime: s piko (javni CA ne izda potrdila za ime brez pike, zato tako ime tudi s https ne bi
     * delalo) in brez oznake, ki je kljuc - ali localhost oziroma naslov IPv6 v oklepajih (IPv4 ima piko).
     */
    private fun jeImeGostitelja(gost: String): Boolean {
        val g = gost.lowercase().trimEnd('.')
        if (g == "localhost" || (g.startsWith("[") && g.endsWith("]"))) return true
        return '.' in g && g.split('.').none { it.isEmpty() || oznakaJeKljuc(it) }
    }

    /**
     * Ali je v polju za naslov videti kljuc (za sporocilo »prilepi ga v polje Kljuc«): gostitelj brez pike, dolg vsaj 16
     * znakov s stevko (32 heks, UUID, zeton), ali oznaka imena, ki je kljuc ([oznakaJeKljuc]).
     */
    fun naslovJeKljuc(vnos: String): Boolean {
        val v0 = vnos.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        val gost = v0.substringAfter("://").substringBefore('/').substringAfterLast('@').substringBefore(':').lowercase()
        if ('.' in gost) return gost.split('.').any { oznakaJeKljuc(it) }
        return RX_UUID.matches(gost) || (gost.length >= 16 && gost.any { it.isDigit() } &&
            gost.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' })
    }

    /** Kaj je narobe z vnosom indeksatorja - za sporocilo uporabniku (pregled Grok 31, H1). */
    enum class NapakaVnosa { NASLOV_JE_KLJUC, NASLOV, KLJUC }

    /** null = vnos je veljaven ([izVnosa] ga sprejme). */
    fun napakaVnosa(naslov: String, kljuc: String): NapakaVnosa? = when {
        naslovJeKljuc(naslov) -> NapakaVnosa.NASLOV_JE_KLJUC
        osnova(naslov) == null -> NapakaVnosa.NASLOV
        kljucIzVnosa(kljuc) == null -> NapakaVnosa.KLJUC
        else -> null
    }

    /**
     * Naslov indeksatorja, kot ga uporabnik vpise (»https://api.indeksator.example/api?«): osnova brez `/api`, poizvedbe in
     * koncne posevnice. Samo https - kljuc gre v naslovu; http samo do naprave v domacem omrezju (NZBHydra, Prowlarr).
     * Brez uporabniskega imena v naslovu. null = ni veljaven.
     * Lastnik, 25. 10. 2026: uporabnik vpise kar ime (»nzbfinder.ws«) - gola domena brez sheme dobi https:// sama
     * (http:// za internet ostane zavrnjen, ker gre kljuc API v naslovu).
     * Pregled Grok 31, H1/H4: gostitelj mora biti ime s piko brez oznake, ki je kljuc ([jeImeGostitelja]) - kljuc v polju
     * za naslov in tipkarske napake (»http//x«, »https:/x«, »nzbfinder«) so zavrnjeni, preden gre karkoli v DNS.
     */
    fun osnova(vnos: String): String? {
        val v0 = vnos.trim().substringBefore('?').substringBefore('#').trimEnd('/')
        val v = if (v0.isNotEmpty() && "://" !in v0) "https://$v0" else v0
        val u = try { URI(v) } catch (_: Exception) { return null }
        val gost = u.host ?: return null
        if (u.rawUserInfo != null) return null
        if (!jeImeGostitelja(gost)) return null
        val shema = u.scheme?.lowercase() ?: return null
        if (shema != "https" && !(shema == "http" && jeLokalniGostitelj(gost))) return null
        return shema + v.substring(u.scheme.length).removeSuffix("/api").trimEnd('/')
    }

    /**
     * Indeksator, ki ga je poslala druga naprava v ozjem krogu (skupaj s kljuci debrid, naloga 129): velja samo, ce sta
     * naslov in kljuc veljavna po istih pravilih kot vnos na tej napravi. null = ni poslan ali ni veljaven.
     */
    fun prejetIndeksator(naslov: String?, kljuc: String?): Pair<String, String>? =
        // Lastnik 25. 10. 2026: tudi vzorec kljuca (kot pri vnosu) ni indeksator.
        if (naslov == null || kljuc == null) null else izVnosa(naslov, kljuc)

    /** Kljuc API indeksatorja: 8-128 znakov, crke, stevke, '-', '_' (Newznab: obicajno 32 heks). */
    fun ocistiKljuc(vnos: String): String? {
        val k = vnos.trim()
        return k.takeIf { it.length in 8..128 && it.all { c -> (c.isLetterOrDigit() && c.code < 128) || c == '-' || c == '_' } }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------------------ preverba kljuca indeksatorja (25. 10. 2026)
    // Kot namizje (core/usenet_pravila.py, 2b05609) in kljuci debrid (DebridPravila.Preverba): isti primeri v
    // tests/skupni-primeri/kljuci-preverba.json.

    /** Kljuc indeksatorja iz vnosa ali z druge naprave: oblika in ne vzorec (»your-indexer-api-key«); null = ni kljuc. */
    fun kljucIzVnosa(vnos: String): String? = ocistiKljuc(vnos)?.takeUnless { DebridPravila.jeVzorecKljuca(it) }

    /**
     * Preverba ob vnosu: eno iskanje z enim zadetkom (izmerjeno 25. 10. 2026 na nzbfinder.ws: napacen kljuc -> HTTP 401 in
     * `<error code="100">`; `t=caps` kljuca ne preveri). POZOR: vsebuje kljuc - samo za klic, nikoli v dnevnik.
     */
    fun naslovPreverbe(osnova: String, kljuc: String): String = "$osnova/api?t=search&q=safeer&limit=1&apikey=${enc(kljuc)}"

    /**
     * Odgovor [naslovPreverbe] -> izid. Zavrnjen kljuc je samo 401 ali napaka Newznab 100-102; 403 brez napake Newznab je
     * lahko Cloudflare (lastnik 25. 10. 2026: nzbfinder.ws streznike zavrne s 403) - kljuca zaradi tega ne zavrnemo.
     * Meja (429, Newznab 500/501) in vse drugo (5xx, stran HTML, preusmeritev, brez povezave) kljuca ne zavrne.
     */
    fun preverbaIndeksatorja(http: Int, zacetek: String): DebridPravila.Preverba {
        val n = napaka(zacetek)
        return when {
            // Varovalo racunov (skupni primeri v2): Newznab 101 »account suspended« = racun zaklenjen (brez ponovitev).
            n == TorBoxPravila.NAPAKA_ZAKLENJEN -> DebridPravila.Preverba.ZAKLENJEN
            n == TorBoxPravila.NAPAKA_KLJUC || http == 401 -> DebridPravila.Preverba.NE_VELJA
            n == TorBoxPravila.NAPAKA_MEJA || http == 429 -> DebridPravila.Preverba.MEJA
            http in 200..299 && n == null && RX_RSS.containsMatchIn(zacetek) -> DebridPravila.Preverba.VELJA
            else -> DebridPravila.Preverba.NI_ODGOVORA
        }
    }

    private val RX_RSS = Regex("<(rss|channel)\\b", RegexOption.IGNORE_CASE)

    /** Vnos indeksatorja: (osnova, kljuc) ali null - naslov ni https (ali domace omrezje) ali kljuc ni kljuc oz. je vzorec. */
    fun izVnosa(naslov: String, kljuc: String): Pair<String, String>? {
        val o = osnova(naslov) ?: return null
        val k = kljucIzVnosa(kljuc) ?: return null
        return o to k
    }

    /** Odlocitev ob vpisu: [shrani] = (osnova, kljuc), ki ju shranimo s stanjem [stanje]; null = nic se ne shrani. */
    class Vpis(val izid: DebridPravila.Preverba, val shrani: Pair<String, String>?, val stanje: DebridPravila.StanjeKljuca?)

    /**
     * Vpis indeksatorja (lastnik 25. 10. 2026, kot kljuci debrid): ne-kljuc ali vzorec se zavrne brez klica; [preveri] vprasa
     * indeksator s te naprave; zavrnjen kljuc se ne shrani, izpad ali meja ga shrani kot nepreverjenega (ne gre na druge naprave).
     */
    fun vpis(naslov: String, kljuc: String, preveri: (String, String) -> DebridPravila.Preverba): Vpis {
        val ix = izVnosa(naslov, kljuc) ?: return Vpis(DebridPravila.Preverba.OBLIKA, null, null)
        val izid = preveri(ix.first, ix.second)
        val s = DebridPravila.stanjePoPreverbi(izid)
        return Vpis(izid, if (s != null) ix else null, s)
    }

    /** Odlocitev za prejeti indeksator: [ENAK] = ze shranjen in potrjen, [SHRANI] = potrjen s te naprave, [ZAVRNI] = nic. */
    enum class Sprejem { ENAK, SHRANI, ZAVRNI }

    /**
     * Prejeti indeksator (usklajevanje v ozjem krogu ali »Poslji kljuce«; [prejet] iz [prejetIndeksator]). Lastnik 25. 10.
     * 2026: uporabi se samo, ce ga [preveri] potrdi tudi s te naprave (DebridPravila.sprejmiPrejetega) - zavrnjen ali
     * nepreverjen nikoli ne prepise shranjenega. Enak shranjenemu, ki je ze potrjen ([shranjenVelja]), se ne preverja znova.
     */
    fun sprejem(prejet: Pair<String, String>?, shranjen: Pair<String, String>?, shranjenVelja: Boolean,
                preveri: (String, String) -> DebridPravila.Preverba): Sprejem {
        val p = prejet ?: return Sprejem.ZAVRNI
        if (p == shranjen && shranjenVelja) return Sprejem.ENAK
        return if (DebridPravila.sprejmiPrejetega(preveri(p.first, p.second))) Sprejem.SHRANI else Sprejem.ZAVRNI
    }

    /**
     * Naslov iskanja za id Stremio: film `tt1375666` -> t=movie, epizoda `tt0108778:1:1` -> t=tvsearch s sezono in epizodo.
     * Drugi id-ji (tmdb:, kitsu: ...) -> null: indeksatorji iscejo po IMDb.
     */
    fun naslovIskanja(osnova: String, kljuc: String, id: String): String? {
        val (imdb, sezona, epizoda) = deliId(id) ?: return null
        val skupno = "$osnova/api?apikey=${enc(kljuc)}&imdbid=$imdb&limit=100&extended=1"
        return if (sezona == null) "$skupno&t=movie" else "$skupno&t=tvsearch&season=$sezona&ep=$epizoda"
    }

    /** Ali za id Stremio sploh iscemo (film ali epizoda po IMDb). */
    fun iscemo(id: String): Boolean = deliId(id) != null

    /** IMDb brez »tt«, sezona in epizoda (null pri filmu); null = id ni za iskanje. */
    private fun deliId(id: String): Triple<String, Int?, Int?>? {
        val d = id.trim().split(':')
        val imdb = d[0].lowercase().takeIf { it.length in 3..12 && it.startsWith("tt") && it.drop(2).all { c -> c.isDigit() } }?.drop(2) ?: return null
        return when (d.size) {
            1 -> Triple(imdb, null, null)
            3 -> {
                val s = d[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                val e = d[2].toIntOrNull()?.takeIf { it >= 1 } ?: return null
                Triple(imdb, s, e)
            }
            else -> null
        }
    }

    data class Zadetek(val naslov: String, val povezava: String, val velikost: Long)

    /**
     * Pregled Grok 3: vrsta napake, ko indeksator ni vrnil NZB - iz napake Newznab v odgovoru, sicer iz kode HTTP (401/403
     * kljuc, 429 meja). Gre v izid predvajanja ([PONUDNIK]): zavrnjen kljuc ponudi »Popravi«, meja ne.
     */
    fun napakaNzb(koda: Int, zacetek: String, istiGostitelj: Boolean = true): String = napaka(zacetek) ?: when (koda) {
        // Pregled Grok 36, D2 (namizje 54ec334): 401/403 DRUGEGA gostitelja po preusmeritvi (CDN, potekel podpisan naslov) ni
        // zavrnjen kljuc indeksatorja - sicer bi indeksator dobil »ne velja« in ga Safeer ne bi vec klical.
        401, 403 -> if (istiGostitelj) TorBoxPravila.NAPAKA_KLJUC else NAPAKA_INDEKSATOR
        429 -> TorBoxPravila.NAPAKA_MEJA
        else -> NAPAKA_INDEKSATOR
    }

    /** Pregled Grok 3: ponudnik v izidu, ko je napaka pri indeksatorju (kljuc, meja) - okno imenuje indeksator, ne TorBoxa. */
    const val PONUDNIK = VRSTA

    /** Napaka Newznab (`<error code=…>`) v nase vrste napak; null = ni napake. */
    fun napaka(xml: String): String? {
        val m = Regex("<error\\s+code=\"(\\d+)\"").find(xml) ?: return null
        return when (m.groupValues[1].toIntOrNull()) {
            100, 102 -> TorBoxPravila.NAPAKA_KLJUC
            101 -> TorBoxPravila.NAPAKA_ZAKLENJEN
            500, 501 -> TorBoxPravila.NAPAKA_MEJA
            else -> NAPAKA_INDEKSATOR
        }
    }

    /**
     * Zadetki iz odgovora RSS. DOCTYPE/ENTITY zavrnemo pred razclenitvijo (XXE). Povezava NZB je `enclosure url`, sicer
     * `link`; samo https, ali isti gostitelj kot indeksator ([osnova] - npr. http v domacem omrezju). Brez naslova ali
     * povezave zadetek odpade; ponovljene povezave enkrat; najvec [NAJVEC_ZADETKOV].
     */
    fun zadetki(xml: String, osnova: String): List<Zadetek> {
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) return emptyList()
        val dok = try {
            val f = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) { }
            f.isExpandEntityReferences = false
            f.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
        } catch (_: Exception) { return emptyList() }
        val gostIndeksatorja = try { URI(osnova).host?.lowercase() } catch (_: Exception) { null }
        val elementi = dok.getElementsByTagName("item")
        val izid = ArrayList<Zadetek>()
        val videne = HashSet<String>()
        for (i in 0 until elementi.length) {
            val el = elementi.item(i) as? org.w3c.dom.Element ?: continue
            fun besedilo(ime: String) = el.getElementsByTagName(ime).item(0)?.textContent?.trim().orEmpty()
            val prilozeno = (el.getElementsByTagName("enclosure").item(0) as? org.w3c.dom.Element)?.getAttribute("url")?.trim().orEmpty()
            val povezava = prilozeno.ifBlank { besedilo("link") }
            val naslov = besedilo("title").replace(Regex("\\s+"), " ").take(200)
            if (naslov.isBlank() || !dovoljenaPovezava(povezava, gostIndeksatorja) || !videne.add(povezava)) continue
            var velikost = 0L
            val atributi = el.getElementsByTagName("newznab:attr")
            for (j in 0 until atributi.length) {
                val a = atributi.item(j) as? org.w3c.dom.Element ?: continue
                if (a.getAttribute("name") == "size") velikost = a.getAttribute("value").toLongOrNull() ?: 0L
            }
            if (velikost <= 0) velikost = (el.getElementsByTagName("enclosure").item(0) as? org.w3c.dom.Element)
                ?.getAttribute("length")?.toLongOrNull() ?: 0L
            // Indeksator isto izdajo pogosto vrne veckrat (izmerjeno 24. 10. 2026): enak naslov in velikost = enkrat.
            if (!videne.add(naslov + "|" + velikost)) continue
            izid += Zadetek(naslov, povezava, velikost.coerceAtLeast(0))
            if (izid.size >= NAJVEC_ZADETKOV) break
        }
        return izid
    }

    /**
     * Povezava NZB (zadetek ali skok preusmeritve): https; http samo do istega gostitelja v domacem omrezju. Pregled Grok 36,
     * D5 (namizje ca50504 - isto vedenje): indeksator z interneta ne sme dati povezave v domace omrezje ali na to napravo
     * (https://192.168.…, https://127.0.0.1:…) - naprava bi poslala slepo zahtevo GET v domace omrezje. Lokalni indeksator sme.
     */
    private fun dovoljenaPovezava(p: String, gostIndeksatorja: String?): Boolean {
        val u = try { URI(p) } catch (_: Exception) { return false }
        val gost = u.host?.lowercase()?.trimEnd('.') ?: return false
        if (u.rawUserInfo != null) return false
        val ix = gostIndeksatorja?.lowercase()?.trimEnd('.').orEmpty()
        return when (u.scheme?.lowercase()) {
            "https" -> !niJavni(gost) || niJavni(ix)
            "http" -> gost == ix && jeLokalniGostitelj(gost)
            else -> false
        }
    }

    /**
     * Ime ali naslov IP, ki ni na internetu (kot namizje _ni_javni): ta naprava, domace omrezje, povezava (169.254 - tudi
     * metapodatki oblaka), CGNAT 100.64/10, rezervirano, dokumentacija, skupinsko. Ime, ki se v DNS razresi v tak naslov, tu
     * ni zaznano (DNS se ne sprasuje).
     */
    fun niJavni(gost: String): Boolean {
        val g = gost.lowercase().trimEnd('.').removePrefix("[").removeSuffix("]")
        if (g == "localhost" || g.endsWith(".local") || g.endsWith(".localhost")) return true
        if (':' in g) return try {
            // Naslov IPv6 (ime ne more vsebovati dvopicja - brez DNS).
            when (val a = java.net.InetAddress.getByName(g)) {
                is java.net.Inet4Address -> niJavni(a.hostAddress ?: return true)
                else -> a.isLoopbackAddress || a.isAnyLocalAddress || a.isLinkLocalAddress || a.isSiteLocalAddress ||
                    a.isMulticastAddress || (a.address[0].toInt() and 0xfe) == 0xfc ||
                    (a.address[0].toInt() == 0x20 && a.address[1].toInt() == 0x01 && a.address[2].toInt() == 0x0d && (a.address[3].toInt() and 0xff) == 0xb8)
            }
        } catch (_: Exception) { true }
        val d = g.split('.')
        if (d.size != 4 || d.any { it.isEmpty() || it.length > 3 || !it.all { c -> c in '0'..'9' } }) return false
        val b = d.map { it.toInt() }
        if (b.any { it > 255 }) return false
        val (a, x, y) = Triple(b[0], b[1], b[2])
        return a == 0 || a == 10 || a == 127 || (a == 100 && x in 64..127) || (a == 169 && x == 254) || (a == 172 && x in 16..31) ||
            (a == 192 && x == 168) || (a == 192 && x == 0 && (y == 0 || y == 2)) || (a == 198 && x in 18..19) ||
            (a == 198 && x == 51 && y == 100) || (a == 203 && x == 0 && y == 113) || a >= 224
    }

    private fun vrata(u: URI): Int? = if (u.port >= 0) u.port else when (u.scheme?.lowercase()) { "http" -> 80; "https" -> 443; else -> null }

    /**
     * Preusmeritev prenosa NZB (naslov nosi kljuc indeksatorja; AGENTS.md: prenosi preverijo VSAK skok; namizje ca50504):
     * cilj mora biti dovoljena povezava NZB glede na gostitelja prejsnjega skoka, nikoli s https na http (Location lahko nosi
     * kljuc), http samo na ista vrata istega lokalnega gostitelja.
     */
    fun dovoljenSkok(od: String, na: String): Boolean {
        val a = try { URI(od) } catch (_: Exception) { return false }
        val b = try { URI(na) } catch (_: Exception) { return false }
        if (a.scheme?.lowercase() == "https" && b.scheme?.lowercase() != "https") return false
        if (b.scheme?.lowercase() == "http" && (vrata(a) == null || vrata(a) != vrata(b))) return false
        return dovoljenaPovezava(na, a.host)
    }

    /** Najvec skokov preusmeritve pri prenosu NZB. */
    const val NAJVEC_SKOKOV = 5

    // ------------------------------------------------------------------ povezave NZB v pomnilniku (pregled Grok 41, Z1)

    /**
     * Pregled Grok 41, Z1 (kot namizje 37509ce, Grokov pregled 40, S1): povezava NZB nosi kljuc indeksatorja v naslovu
     * (apikey) iz casa iskanja. Prej jo je prenos vzel iz pomnilnika brez preverbe: po zaklepu racuna (Newznab 101),
     * zamenjavi ali odstranitvi kljuca je klik na vir v ze odprtem seznamu poslal stari ali zaklenjen kljuc indeksatorju.
     * Zato je vsaka povezava vezana na odtis kljuca, s katerim je bila najdena ([DebridPravila.odtisStanja] naslova in
     * kljuca), in gre ven samo s tem kljucem, dokler je shranjen. Najvec [najvec] povezav, najstarejse odpadejo.
     */
    class Povezave(private val najvec: Int = 500) {
        private val m = object : LinkedHashMap<String, Pair<String, String>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>?) = size > najvec
        }

        /** Povezava [povezava] zadetka [idToka], najdena s kljucem z odtisom [odtis]. */
        @Synchronized fun dodaj(idToka: String, povezava: String, odtis: String) { m[idToka] = povezava to odtis }

        /**
         * Povezava za prenos samo, ce je bila najdena s kljucem [odtisZdaj] (shranjen indeksator; null = ga ni). Sicer
         * null - in povezav drugih kljucev ni vec (klic z zamenjanim ali odstranjenim kljucem ne gre ven; vir je treba poiskati znova).
         */
        @Synchronized fun zaPrenos(idToka: String, odtisZdaj: String?): String? {
            val (povezava, odtis) = m[idToka] ?: return null
            if (odtisZdaj != null && odtis == odtisZdaj) return povezava
            obdrzi(odtisZdaj)
            return null
        }

        /** Ostanejo samo povezave kljuca [odtis] (null = nobena): zamenjava ali »Odstrani« indeksatorja. */
        @Synchronized fun obdrzi(odtis: String?) { m.values.removeAll { it.second != odtis } }

        /** Povezave kljuca [odtis], ki ga je indeksator zavrnil ali zaklenil, gredo iz pomnilnika. */
        @Synchronized fun pozabi(odtis: String) { m.values.removeAll { it.second == odtis } }

        @Synchronized fun velikost(): Int = m.size
    }

    /** Napaka za kljuc, ki ga ne klicemo: zaklenjen racun ali kljuc, ki ne velja. */
    fun napakaStanja(s: DebridPravila.StanjeKljuca?): String =
        if (s == DebridPravila.StanjeKljuca.ZAKLENJEN) TorBoxPravila.NAPAKA_ZAKLENJEN else TorBoxPravila.NAPAKA_KLJUC

    /**
     * Pregled Grok 41, Z1: ali prenos NZB in TorBox Usenet smeta klicati storitev - preveri se pred VSAKIM prenosom (tudi
     * klik na vir v ze odprtem seznamu), ne samo pred iskanjem. Lastnik 26. 10. 2026: s kljucem »ne velja« ali »zaklenjen«
     * Safeer storitve ne klice - zato tudi indeksator ne dobi zahteve, ce TorBoxa ne smemo klicati (NZB ne bi imel kam).
     * (napaka, ponudnik) za izid brez klica ali null = sme.
     */
    fun oviraPrenosa(imaTorBox: Boolean, stanjeTorBox: DebridPravila.StanjeKljuca?, imaIndeksator: Boolean,
                     stanjeIndeksatorja: DebridPravila.StanjeKljuca?): Pair<String, String>? = when {
        !imaTorBox -> TorBoxPravila.NAPAKA_KLJUC to DebridPravila.TORBOX
        !DebridPravila.smeKlicati(stanjeTorBox) -> napakaStanja(stanjeTorBox) to DebridPravila.TORBOX
        !imaIndeksator -> NAPAKA_INDEKSATOR to PONUDNIK
        !DebridPravila.smeKlicati(stanjeIndeksatorja) -> napakaStanja(stanjeIndeksatorja) to PONUDNIK
        else -> null
    }

    /** Id toka za zadetek: »usenet:« + prvih 16 heks SHA-1 povezave - povezava (s kljucem) ostane samo v pomnilniku. */
    fun idToka(povezava: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-1").digest(povezava.toByteArray(Charsets.UTF_8))
        return PREDPONA + d.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun jeUsenet(url: String): Boolean = url.startsWith(PREDPONA)

    /** Opis toka v obliki dodatkov (»… 💾 12.7 GB«), da ga TokIzbira razbere (locljivost, kodek, velikost). */
    fun opis(z: Zadetek): String =
        if (z.velikost <= 0) z.naslov else z.naslov + "\n💾 " + String.format(java.util.Locale.ROOT, "%.1f GB", z.velikost / 1_073_741_824.0)

    // ------------------------------------------------------------------ TorBox (Usenet)

    const val OSNOVA_TORBOX = "https://api.torbox.app/v1/api/usenet"
    fun naslovUstvari(): String = "$OSNOVA_TORBOX/createusenetdownload"
    fun naslovSeznam(id: Long): String = "$OSNOVA_TORBOX/mylist?id=$id&bypass_cache=true"
    fun naslovVsi(): String = "$OSNOVA_TORBOX/mylist?bypass_cache=true"

    /** POZOR: vsebuje kljuc TorBox (token) - samo za klic, nikoli v dnevnik, predvajalnik ali zgodovino. */
    fun naslovPrenosa(kljuc: String, id: Long, datotekaId: Long): String =
        "$OSNOVA_TORBOX/requestdl?token=${enc(kljuc)}&usenet_id=$id&file_id=$datotekaId"

    /**
     * Odgovor createusenetdownload z `add_only_if_cached`, ko prenosa ni v predpomnilniku: TorBox vrne neuspeh. Kljuc,
     * paket in meja ostanejo svoje napake (vrstaNapake TorBoxa); vse drugo pri »samo takoj« pomeni »ni pripravljeno«.
     */
    fun niPredpomnjeno(samoTakoj: Boolean, vrstaNapake: String): Boolean =
        samoTakoj && vrstaNapake !in setOf(TorBoxPravila.NAPAKA_KLJUC, TorBoxPravila.NAPAKA_PAKET, TorBoxPravila.NAPAKA_MEJA, TorBoxPravila.NAPAKA_ZAKLENJEN)

    /**
     * Samodejna izbira (lastnik, 24. 10. 2026: »automatsko se zažene najboljši vir … v našem primeru usenet«): Usenet gre
     * prvi, z cakanjem na prenos pri TorBoxu. Izmerjeno na TV 24. 10. 2026: 2,2 GB v 36 s - zato najvec [NAJVEC_GB_SAMODEJNO]
     * (nekaj minut); vecje izdaje (4K REMUX 50+ GB) ostanejo za rocno izbiro. Neznana velikost (0) je dovoljena.
     * [gb] po vrstnem redu kakovosti; vrne indekse za poskuse (najvec [POSKUSOV_SAMODEJNO]).
     */
    fun zaSamodejno(gb: List<Double>): List<Int> =
        gb.indices.filter { gb[it] <= NAJVEC_GB_SAMODEJNO }.take(POSKUSOV_SAMODEJNO)

    const val NAJVEC_GB_SAMODEJNO = 15.0
    const val POSKUSOV_SAMODEJNO = 2

    /** Ali je odgovor res NZB (XML z elementom `nzb`) - indeksator lahko namesto datoteke vrne napako ali stran HTML. */
    fun jeNzb(zacetek: String): Boolean = zacetek.contains("<nzb", ignoreCase = true) && !zacetek.contains("<!ENTITY", ignoreCase = true)
}
