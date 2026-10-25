package si.safeer.tv.os

/**
 * Webtor rezerva (pot A, 25. 10. 2026; raziskava in meritve: porocilo-rezerva.md): lastnikov osebni dodatek Stremio
 * Webtor je rezerva za torrente, ki jih nas TorBox nima pripravljenih. Dodatek vrne tok »url« =
 * `.../token/<zeton>/stremio/resolve/<JWT>` (JWT 72 h), ki s 302 preusmeri na izvirno datoteko (progresivno, Range) -
 * predvaja nas predvajalnik. Ime toka ima predpono `[⚡TB]` (pri TorBoxu v Webtor profilu pripravljen - podvaja nas
 * TorBox), `[⚡WT]` (Webtor ga ima pripravljenega), `[WT]` (Webtor ga prenasa sproti) ali `[P2P]` (brezplacni racun:
 * posnetek z oglasom za paket). Polje infoHash ostane, `fileIdx` je v JWT (`hash`, `idx`).
 *
 * Vrstni red samodejne izbire (lastnik, 25. 10. 2026 07:25 - [koraki]): (1) Usenet, ki ga ima TorBox ze pripravljenega
 * (prve [USENET_VZPOREDNO] izdaje hkrati s preverbo ⚡, najvec [USENET_ROK_MS]) - najboljsa kakovost in hitrost, uporabnik
 * ga placa; (2) takoj predvajljivo: ⚡ torrent pri nasem debridu, ziva neposredna povezava, `[⚡WT]`; (3) Usenet s cakanjem
 * na prenos; (4) Webtor `[WT]` (ista izdaja: infoHash + fileIdx); (5) TorBox s cakanjem. Ob zacetku toka Webtor gre ta
 * torrent v ozadju v TorBox (naslednjic je ⚡). Napaka 402/404/429 ali prvi bajt po vec kot 15 s: naslednji vir.
 *
 * Koncni naslov po preusmeritvi je vezan na IP naprave (Webtor primerja /24): drugim napravam vedno samo naslov
 * `/resolve/` (v aplikaciji nadomestek brez zetona, [SkrivnostiPravila]), nikoli koncni.
 * Ciste funkcije - preizkus tests/WebtorPravilaTest.kt in skupni primeri tests/skupni-primeri/webtor-rezerva.json.
 */
object WebtorPravila {
    /**
     * Pregled U4: naslov IP, kamor sonda toka ne sme (preusmeritev iz interneta v domace omrezje - CSRF ali slepi SSRF proti
     * usmerjevalniku): loopback, zasebni (RFC 1918, 100.64/10), link-local, nedoloceni, ULA fc00::/7 in IPv4 v IPv6.
     */
    fun zasebenNaslov(b: ByteArray): Boolean {
        fun u(i: Int) = b[i].toInt() and 0xFF
        if (b.size == 4) return u(0) == 0 || u(0) == 10 || u(0) == 127 || (u(0) == 169 && u(1) == 254) ||
            (u(0) == 172 && u(1) in 16..31) || (u(0) == 192 && u(1) == 168) || (u(0) == 100 && u(1) in 64..127) || u(0) >= 224
        if (b.size != 16) return true
        if (b.all { it.toInt() == 0 } || (b.take(15).all { it.toInt() == 0 } && u(15) == 1)) return true      // :: in ::1
        if ((u(0) and 0xFE) == 0xFC) return true                                                         // ULA fc00::/7
        if (u(0) == 0xFE && (u(1) and 0xC0) == 0x80) return true                                         // fe80::/10
        if (u(0) == 0xFF) return true                                                                    // vecvrstno
        if (b.take(10).all { it.toInt() == 0 } && u(10) == 0xFF && u(11) == 0xFF) return zasebenNaslov(b.copyOfRange(12, 16))
        return false
    }

    const val ID_MANIFESTA = "org.stremio.webtor.io"
    const val GOSTITELJ = "webtor.io"
    /** [Stremio.Tok.vir] za tokove dodatka Webtor. */
    const val VIR = "webtor"
    /** Oznaka v vmesniku (ime storitve, ne prevajamo). */
    const val IME = "Webtor"
    /** Prvi bajt toka pozneje od tega: naslednji vir. Hkrati skupni rok vseh sond Webtor ene izbire. */
    const val PRVI_BAJT_MS = 15_000L
    /** Ena izbira toka poskusi najvec toliko tokov Webtor (pregled N3: tudi prek rocno izbranega torrenta). */
    const val NAJVEC_POSKUSOV = 2

    /** Gostitelj naslova (male crke, brez vrat in uporabnika); "" = ni naslov http(s). */
    fun gostitelj(naslov: String): String {
        val n = naslov.trim()
        val i = n.indexOf("://")
        if (i <= 0) return ""
        val s = n.substring(0, i).lowercase()
        if (s != "http" && s != "https") return ""
        return n.substring(i + 3).substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
            .substringBefore(':').lowercase().trimEnd('.')
    }

    /** Samo `id` manifesta (dodatek si ga izbere sam) - za odlocitev uporabi [jeWebtorDodatek]. */
    fun jeWebtor(idManifesta: String?): Boolean = idManifesta?.trim() == ID_MANIFESTA

    /**
     * Pregled V1: dodatek je Webtor samo, ce ima `id` Webtorja IN je osnova dodatka (prava ali nadomestek) na
     * `https://webtor.io` (natanko ta gostitelj). Lazen `id` na drugem gostitelju je navaden dodatek.
     */
    fun jeWebtorDodatek(idManifesta: String?, osnova: String): Boolean =
        jeWebtor(idManifesta) && osnova.trim().lowercase().startsWith("https://") && gostitelj(osnova) == GOSTITELJ

    /** Naslov `https://webtor.io/.../resolve/...` (zacetek preusmeritve, iz katere se Scit sme uciti gostitelja toka). */
    fun jeResolveNaslov(url: String): Boolean =
        url.trim().lowercase().startsWith("https://") && gostitelj(url) == GOSTITELJ && "/resolve/" in url.substringBefore('?')

    /**
     * Pregled V1: gostitelja toka se Scit nauci samo iz zahteve, ki se je ZACELA na `https://webtor.io/.../resolve/`, samo
     * KONCNEGA gostitelja (po vseh preusmeritvah, https) in sele po odgovoru 2xx. null = nicesar se ne naucimo. Nadaljnje
     * preverbe (javna pripona, seznami groženj in oglasov, najvec 20, 30 dni) so v [si.safeer.tv.scit.ScitIzjeme.dodaj].
     */
    fun naucenGostitelj(zacetni: String, koncni: String, koda: Int): String? {
        if (!jeResolveNaslov(zacetni) || koda !in 200..299) return null
        if (!koncni.trim().lowercase().startsWith("https://")) return null
        val g = gostitelj(koncni)
        return if (g.isEmpty() || g == GOSTITELJ) null else g
    }

    enum class Vrsta {
        /** `[⚡TB]`: pripravljen pri TorBoxu, ki ga ima lastnik v Webtor profilu. */
        TORBOX,
        /** `[⚡WT]`: Webtor ga ima pripravljenega (hiter zacetek). */
        PREDPOMNJEN,
        /** `[WT]` ali brez predpone: Webtor ga prenasa sproti. */
        PRENOS,
        /** `[P2P]`: brezplacni racun - Webtor da posnetek z oglasom za paket, ne vsebine. */
        P2P
    }

    data class Oznaka(val vrsta: Vrsta, val ime: String)

    private val RX_PREDPONA = Regex("^\\s*\\[\\s*(⚡️?)?\\s*(TB|WT|P2P)\\s*]\\s*", RegexOption.IGNORE_CASE)

    /** Predpona imena toka -> vrsta in ime brez predpone. */
    fun razcleni(ime: String): Oznaka {
        val m = RX_PREDPONA.find(ime) ?: return Oznaka(Vrsta.PRENOS, ime.trim())
        val strela = m.groupValues[1].isNotEmpty()
        val v = when (m.groupValues[2].uppercase()) {
            "TB" -> Vrsta.TORBOX
            "P2P" -> Vrsta.P2P
            else -> if (strela) Vrsta.PREDPOMNJEN else Vrsta.PRENOS
        }
        return Oznaka(v, ime.substring(m.range.last + 1).trim())
    }

    /** Vrsta iz shranjenega imena ([Vrsta.name]); neznano = prenos. */
    fun vrstaIz(ime: String): Vrsta = Vrsta.values().firstOrNull { it.name == ime } ?: Vrsta.PRENOS

    /**
     * Tok skrijemo: `[⚡TB]` podvaja nas TorBox (le, ce ga imamo - brez njega je to edina pot do TorBoxa), `[P2P]` je
     * posnetek z oglasom za paket, ne film.
     */
    fun skrij(v: Vrsta, imamoTorBox: Boolean): Boolean = v == Vrsta.P2P || (v == Vrsta.TORBOX && imamoTorBox)

    /** Tok, ki se zacne takoj (korak »takoj predvajljivo«): `[⚡WT]` in `[⚡TB]` (ce ni skrit). */
    fun takoj(v: Vrsta): Boolean = v == Vrsta.PREDPOMNJEN || v == Vrsta.TORBOX

    /** Izdaja: infoHash (40 hex, male crke) in datoteka (fileIdx; -1 = ni znana / najvecji video). */
    data class Kljuc(val hash: String, val idx: Int)

    private val RX_HASH = Regex("^[0-9a-f]{40}$")

    fun kljuc(hash: String?, idx: Int?): Kljuc? {
        val h = hash?.trim()?.lowercase() ?: return null
        if (!RX_HASH.matches(h)) return null
        return Kljuc(h, if (idx == null || idx < 0) -1 else idx)
    }

    /**
     * (hash, idx) iz JWT v naslovu `.../resolve/<JWT>` - samo za ujemanje izdaje, kadar tok nima polja infoHash
     * (podpis preveri Webtor sam). null = ni JWT ali ni hasha.
     */
    fun izJwt(url: String): Kljuc? {
        val jwt = url.substringBefore('?').substringBefore('#').substringAfter("/resolve/", "").substringBefore('/')
        val deli = jwt.split('.')
        if (deli.size != 3 || deli[1].isEmpty()) return null
        val telo = try { String(java.util.Base64.getUrlDecoder().decode(deli[1].trimEnd('=')), Charsets.UTF_8) } catch (_: Exception) { return null }
        val h = Regex("\"hash\"\\s*:\\s*\"([0-9a-fA-F]{40})\"").find(telo)?.groupValues?.get(1)
        val i = Regex("\"idx\"\\s*:\\s*\"?(-?\\d{1,6})").find(telo)?.groupValues?.get(1)?.toIntOrNull()
        return kljuc(h, i ?: -1)
    }

    /**
     * Vrstni red tokov Webtor (indeksi v [webtor]): najprej tisti, ki se ujemajo z NASIMI torrenti, v vrstnem redu nasih
     * torrentov (ista izdaja, kot bi jo izbrali sami - kakovost, jezik, ⚡); nato ostali, `[⚡WT]` pred `[WT]` (hitrejsi
     * zacetek), sicer v danem redu (kakovost). Skriti ([skrij]) izpadejo. Ujemanje: isti hash IN ista datoteka (paket z vec
     * filmi ali epizodami - napacna datoteka bi bila napacen film). [samoTakoj]: samo `[⚡WT]`/`[⚡TB]` (korak »takoj«).
     */
    fun vrstniRed(nasi: List<Kljuc?>, webtor: List<Pair<Kljuc?, Vrsta>>, imamoTorBox: Boolean, samoTakoj: Boolean = false): List<Int> {
        val vidni = webtor.indices.filter { !skrij(webtor[it].second, imamoTorBox) && (!samoTakoj || takoj(webtor[it].second)) }
        val izbrani = LinkedHashSet<Int>()
        for (k in nasi) {
            if (k == null) continue
            vidni.firstOrNull { it !in izbrani && webtor[it].first == k }?.let { izbrani += it }
        }
        val ostali = vidni.filter { it !in izbrani }
        return izbrani.toList() + ostali.filter { takoj(webtor[it].second) } + ostali.filter { !takoj(webtor[it].second) }
    }

    /** Tok Webtor za izdajo [nas] (torrent, ki ga je uporabnik izbral): indeks v [webtor] ali -1. */
    fun zaIzdajo(nas: Kljuc?, webtor: List<Pair<Kljuc?, Vrsta>>, imamoTorBox: Boolean): Int =
        if (nas == null) -1 else webtor.indices.firstOrNull { webtor[it].first == nas && !skrij(webtor[it].second, imamoTorBox) } ?: -1

    enum class Izid { PREDVAJAJ, NI_PAKETA, NI_TOKA, OMEJITEV, PREPOCASI, NAPAKA }

    /**
     * Sonda toka (zahteva za prvi bajt): [koda] HTTP (0 = ni odgovora), [prviBajtMs] cas do prvega bajta (null = ga ni
     * bilo), [potekel] = rok je potekel. 402 = ni paketa, 404/410 = Webtor toka nima, 429 = omejitev ali drug naslov IP;
     * vse razen [Izid.PREDVAJAJ] pomeni: naslednji vir.
     */
    fun odlocitev(koda: Int, prviBajtMs: Long?, potekel: Boolean): Izid = when {
        koda == 402 -> Izid.NI_PAKETA
        koda == 404 || koda == 410 -> Izid.NI_TOKA
        koda == 429 -> Izid.OMEJITEV
        potekel -> Izid.PREPOCASI
        koda in 200..299 && prviBajtMs != null -> if (prviBajtMs <= PRVI_BAJT_MS) Izid.PREDVAJAJ else Izid.PREPOCASI
        else -> Izid.NAPAKA
    }

    /**
     * Pregled N3: koliko ms ima naslednja sonda Webtor v eni izbiri toka: skupaj najvec [PRVI_BAJT_MS] za vse sonde in
     * najvec [NAJVEC_POSKUSOV] sond. 0 = Webtorja ne poskusamo vec. [zacetekPrve] = ura zacetka prve sonde (null = se ni bilo).
     */
    fun rokSonde(zacetekPrve: Long?, zdaj: Long, poskusov: Int): Long {
        if (poskusov >= NAJVEC_POSKUSOV) return 0L
        if (zacetekPrve == null) return PRVI_BAJT_MS
        return (PRVI_BAJT_MS - (zdaj - zacetekPrve)).coerceAtLeast(0L)
    }

    /**
     * Ob zacetku toka Webtor dodamo torrent TorBoxu (v ozadju, brez cakanja), da je naslednjic takoj predvajljiv (⚡):
     * samo s kljucem TorBox, ce uporabnik ni izbral »Brez debrida« in ce ga TorBox se nima. To je namerno predpomnjenje -
     * tudi ob »Samo takoj predvajljivo« (uporabnik nanj ne caka; ta nastavitev pove, da ne zeli CAKATI na prenos).
     */
    fun dodajVTorBox(imamoTorBox: Boolean, debridVklopljen: Boolean, predpomnjen: Boolean, hash: String?): Boolean =
        imamoTorBox && debridVklopljen && !predpomnjen && hash != null && RX_HASH.matches(hash.lowercase())

    /** Javni sledilniki za magnet brez lastnih (TorBox najde vire tudi po DHT; sledilniki pospesijo metapodatke). */
    val PRIVZETI_SLEDILNIKI = listOf("udp://tracker.opentrackr.org:1337/announce", "udp://open.demonii.com:1337/announce",
        "udp://tracker.torrent.eu.org:451/announce")

    /**
     * Magnet za izdajo, za katero nimamo svojega: sledilniki toka (`sources` »tracker:...«), sicer [PRIVZETI_SLEDILNIKI].
     */
    fun magnet(hash: String, sledilniki: List<String> = emptyList()): String {
        val tr = sledilniki.map { it.removePrefix("tracker:").trim() }
            .filter { it.length in 8..300 && (it.startsWith("udp://") || it.startsWith("http://") || it.startsWith("https://")) }
            .distinct().take(10).ifEmpty { PRIVZETI_SLEDILNIKI }
        return "magnet:?xt=urn:btih:" + hash.lowercase() + tr.joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, "UTF-8") }
    }

    // ------------------------------------------------------------------ vrstni red virov (lastnik, 25. 10. 2026 07:25)

    enum class Korak { USENET_PRIPRAVLJEN, TAKOJ, USENET_CAKANJE, WEBTOR, CAKANJE_DEBRID }

    /** Koraki samodejne izbire; brez Useneta (ni indeksatorja ali zadetkov) odpadeta oba koraka Useneta. */
    fun koraki(imaUsenet: Boolean): List<Korak> =
        if (imaUsenet) listOf(Korak.USENET_PRIPRAVLJEN, Korak.TAKOJ, Korak.USENET_CAKANJE, Korak.WEBTOR, Korak.CAKANJE_DEBRID)
        else listOf(Korak.TAKOJ, Korak.WEBTOR, Korak.CAKANJE_DEBRID)

    enum class Takoj { TORRENT, POVEZAVA, WEBTOR }

    /** Znotraj koraka »takoj«: ⚡ torrent pri nasem debridu, nato ziva neposredna povezava, nato `[⚡WT]`. null = nic. */
    fun takojPrvi(pripravljenTorrent: Boolean, zivaPovezava: Boolean, webtorTakoj: Boolean): Takoj? = when {
        pripravljenTorrent -> Takoj.TORRENT
        zivaPovezava -> Takoj.POVEZAVA
        webtorTakoj -> Takoj.WEBTOR
        else -> null
    }

    /** Prvih toliko izdaj Usenet (po kakovosti) preverimo pri TorBoxu hkrati. */
    const val USENET_VZPOREDNO = 3
    /** Skupni rok vzporedne preverbe »Usenet pripravljen« (izmerjeno 2-6 s na izdajo). */
    const val USENET_ROK_MS = 6_000L
    const val CAKAJ = -1
    const val NOBENA = -2

    /**
     * Vzporedna preverba »Usenet pripravljen«: [izidi] v vrstnem redu kakovosti (true = pripravljen, false = ni, null = se
     * ni odgovoril). Izbrana je NAJBOLJSA pripravljena izdaja: prva true, pred katero so vse ze odgovorile false - ali po
     * izteku roka prva true med odgovorjenimi. [CAKAJ] = boljsa izdaja se ni odgovorila, [NOBENA] = nobena ni pripravljena.
     */
    fun izberiUsenet(izidi: List<Boolean?>, rokPotekel: Boolean): Int {
        for ((i, r) in izidi.withIndex()) {
            when (r) {
                true -> return i
                null -> if (!rokPotekel) return CAKAJ
                false -> {}
            }
        }
        return NOBENA
    }
}
