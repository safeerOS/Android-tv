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
     * 26. 10. 2026 (kot SondaVeriga.zasebenCilj, b65fb95): NAT64 (64:ff9b::/96, 64:ff9b:1::/48) in 6to4 (2002::/16) po
     * vgrajenem IPv4 - v omrezju NAT64 64:ff9b::c0a8:101 vodi na 192.168.1.1; sicer IPv6 samo globalni unicast 2000::/3
     * brez dokumentacije (2001:db8::/32) in Teredo (2001::/32, skrit IPv4).
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
        if (u(0) == 0 && u(1) == 0x64 && u(2) == 0xFF && u(3) == 0x9B && ((4..11).all { u(it) == 0 } || (u(4) == 0 && u(5) == 1)))
            return zasebenNaslov(b.copyOfRange(12, 16))                                                  // NAT64
        if (u(0) == 0x20 && u(1) == 0x02) return zasebenNaslov(b.copyOfRange(2, 6))                      // 6to4
        if ((u(0) and 0xE0) != 0x20) return true                                                         // ni 2000::/3
        return u(0) == 0x20 && u(1) == 0x01 && ((u(2) == 0x0D && u(3) == 0xB8) || (u(2) == 0 && u(3) == 0))
    }

    const val ID_MANIFESTA = "org.stremio.webtor.io"
    const val GOSTITELJ = "webtor.io"
    /** [Stremio.Tok.vir] za tokove dodatka Webtor. */
    const val VIR = "webtor"
    /** Oznaka v vmesniku (ime storitve, ne prevajamo). */
    const val IME = "Webtor"
    /**
     * Prvi bajt toka pozneje od tega: naslednji vir. Hkrati skupni rok vseh sond Webtor ene izbire. Izmerjeno 25. 10. 2026
     * (OnePlus 9, 16 sond): najpocasnejsi uspesen prvi bajt 14,0 s (prenos pri Webtorju, 6 sejalcev), TorBox CDN do 6,2 s.
     */
    const val PRVI_BAJT_MS = 15_000L
    /** Ena izbira toka naredi najvec toliko krogov sond Webtor (krog = do [VZPOREDNO] kandidatov hkrati; pregled N3). */
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

    /** Pot predvajalnika ([potPredvajalnika]). */
    enum class Pot { WEBTOR, NAVADNA, ZAVRNI }

    /**
     * Pregled 26, T1 (fail-closed): kako predvajalnik odpre tok. [izvirni] = naslov v aplikaciji (lahko nadomestek), [pravi]
     * = po SkrivniDodatki.dejanski, [neznan] = nadomestek, ki ga ta naprava ne zna razresiti. WEBTOR = samo prek sonde in
     * preverjenega koncnega naslova (brez preusmeritev); ZAVRNI = neznan nadomestek ali tok na webtor.io (in poddomenah),
     * ki ni `/resolve/` po https - nikoli stara pot, kjer bi predvajalnik sledil preusmeritvam sam; NAVADNA = vse drugo.
     */
    fun potPredvajalnika(izvirni: String, pravi: String, neznan: Boolean): Pot {
        if (neznan) return Pot.ZAVRNI
        if (jeResolveNaslov(pravi) || jeResolveNaslov(izvirni)) return Pot.WEBTOR
        fun webtorGostitelj(n: String) = gostitelj(n).let { it == GOSTITELJ || it.endsWith(".$GOSTITELJ") }
        return if (webtorGostitelj(pravi) || webtorGostitelj(izvirni)) Pot.ZAVRNI else Pot.NAVADNA
    }

    /** Pregled 26, T3: diagnostika velja najvec toliko sekund naprej (vrednost lastnosti = cas izteka). */
    const val DIAGNOSTIKA_NAJVEC_S = 6L * 3600

    /**
     * Pregled 26, T3: vrednost lastnosti diagnostike (`debug.safeer.webtor_*`) = cas izteka v sekundah od 1970; velja samo,
     * ce je v prihodnosti in najvec [DIAGNOSTIKA_NAJVEC_S] naprej (pozabljena lastnost se sama izklopi). [zdajS] = ura naprave.
     */
    fun diagnostikaVelja(vrednost: String?, zdajS: Long): Boolean {
        val doS = vrednost?.trim()?.toLongOrNull() ?: return false
        return doS > zdajS && doS - zdajS <= DIAGNOSTIKA_NAJVEC_S
    }

    /** getprop odgovori v nekaj ms; dlje ne cakamo (izbira toka tece na glavni niti). */
    const val DIAGNOSTIKA_ROK_MS = 500L

    /** Ime lastnosti diagnostike: samo `debug.safeer.*` (lastnosti drugih aplikacij ne beremo; kot DiagnostikaPravila). */
    fun diagnostikaLastnost(ime: String): Boolean = Regex("^debug\\.safeer\\.[a-z0-9_]{1,40}$").matches(ime)

    /**
     * Pregled Grok 32, G4/G6 (isto kot DiagnostikaPravila.vklopljena na veji z3-sejanje): vklopljena samo za dovoljeno ime,
     * pri vklopljenem razvijalskem adb ([adbVklopljen]) in z veljavnim rokom. [beri] (getprop - nov proces) se klice SAMO,
     * ce prvi dve veljata: brez adb takoj false.
     */
    fun diagnostikaVklopljena(lastnost: String, zdajS: Long, adbVklopljen: () -> Boolean, beri: (String) -> String?): Boolean =
        diagnostikaLastnost(lastnost) && adbVklopljen() && diagnostikaVelja(beri(lastnost), zdajS)

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

    // ------------------------------------------------------------------ datoteka toka (izmerjeno 25. 10. 2026 popoldne)

    /**
     * Datoteka toka Webtor je samo `idx` v JWT - to je tisto, kar `/resolve` predvaja. Izvorna koda webtor-io/web-ui
     * (832df83, services/stremio/types.go): `FileIdx int` z `omitempty` - tok dodatka BREZ `fileIdx` (Stremio: najvecja
     * datoteka) postane indeks 0, JWT dobi `idx: 0` in `/resolve` vrne PRVO datoteko torrenta (OnePlus 9, 25. 10.:
     * Kolesarski tatovi -> image/jpeg); hkrati odgovor Webtorja polje `fileIdx` 0 izpusti, zato se prej nasa izdaja z
     * `fileIdx` 0 ni ujela s tokom Webtor, izdaja BREZ `fileIdx` pa se je (napacno). JWT brez `idx` = Webtor ob kliku izbere
     * najvecji video (PickPrimaryFileIdx).
     */
    const val IDX_NEZNAN = -2

    /** Izdaja toka Webtor: hash in datoteka iz JWT (pravilo zgoraj); brez JWT [IDX_NEZNAN] - nikoli enaka nasi izdaji. */
    fun kljucWebtor(hashToka: String?, url: String): Kljuc? {
        val j = izJwt(url)
        if (j != null) return j
        val h = hashToka?.trim()?.lowercase() ?: return null
        return if (RX_HASH.matches(h)) Kljuc(h, IDX_NEZNAN) else null
    }

    /**
     * Oblika naslova toka za dnevnik, kadar JWT ni berljiv - BREZ vrednosti (zeton, JWT, hash): ali ima `/resolve/`,
     * koliko delov ima zadnji del poti, ali se telo razbere in katera polja ima. Npr. »resolve=da delov=3 telo=da polja=exp,hash,idx«.
     */
    fun oblikaJwt(url: String): String {
        val pot = url.substringBefore('?').substringBefore('#')
        val resolve = "/resolve/" in pot
        val del = if (resolve) pot.substringAfter("/resolve/").substringBefore('/') else pot.trimEnd('/').substringAfterLast('/')
        val deli = del.split('.')
        val telo = if (deli.size == 3) try { String(java.util.Base64.getUrlDecoder().decode(deli[1].trimEnd('=')), Charsets.UTF_8) }
            catch (_: Exception) { null } else null
        val polja = telo?.let { Regex("\"([A-Za-z_]{1,20})\"\\s*:").findAll(it).map { m -> m.groupValues[1] }.distinct().sorted().joinToString(",") }
        return "resolve=${if (resolve) "da" else "ne"} delov=${deli.size} dolzina=${del.length} telo=${if (telo != null) "da" else "ne"}" +
            (polja?.let { " polja=$it" } ?: "") + " poizvedba=${if ('?' in url) "da" else "ne"}"
    }

    /** Kljuc iz shranjenega toka ([kljucWebtor] -> Tok.hash, Tok.datoteka) brez izgube [IDX_NEZNAN]. */
    fun kljucToka(hash: String?, idx: Int): Kljuc? {
        val h = hash?.trim()?.lowercase() ?: return null
        if (!RX_HASH.matches(h)) return null
        return Kljuc(h, if (idx == IDX_NEZNAN) IDX_NEZNAN else if (idx < 0) -1 else idx)
    }

    enum class Datoteka {
        /** Ime datoteke (tok Webtor ali nas dodatek za isti hash in indeks) je glavni video. */
        VIDEO,
        /** JWT brez `idx`: Webtor izbere najvecji video sam. */
        NAJVECJI,
        /** Indeks brez imena in brez nasega toka z istim indeksom (npr. 0 iz izpuscenega `fileIdx`): lahko naslovnica. */
        NEGOTOVA,
        /** Ime pove, da ni glavni video (slika, glasba, podnapisi, vzorec, vsebnik, ki ga predvajalnik ne zna). */
        NI_VIDEO
    }

    /** Koncnice, ki jih Media3 1.11 razcleni kot video (ASF/WMV, VOB, ISO, RMVB ne). */
    private val VIDEO_KONCNICE = setOf("mkv", "webm", "mp4", "m4v", "mov", "avi", "divx", "ts", "m2ts", "mts", "mpg", "mpeg",
        "flv", "3gp")
    private val RX_VZOREC = Regex("(^|[^a-z])sample([^a-z]|$)")

    /** true = ime glavnega videa, false = ni (druga koncnica ali vzorec), null = ime tega ne pove (prazno, brez koncnice). */
    fun jeVideoIme(ime: String): Boolean? {
        val n = ime.trim().lowercase().substringAfterLast('/').substringAfterLast('\\')
        if (n.isEmpty()) return null
        if (RX_VZOREC.containsMatchIn(n)) return false
        val k = n.substringAfterLast('.', "")
        if (k.isEmpty() || k.length > 5 || k.any { !it.isLetterOrDigit() }) return null
        return k in VIDEO_KONCNICE
    }

    /**
     * Kaj bo `/resolve` predvajal: [idx] iz JWT ([IDX_NEZNAN], -1 = brez idx), [imeToka] = `behaviorHints.filename` toka
     * Webtor (dodatek, ki je dal ime, je dal tudi indeks), [nasIdx]/[nasIme] = nas tok za isti hash (null = ga nimamo).
     */
    fun datoteka(idx: Int, imeToka: String, nasIdx: Int?, nasIme: String): Datoteka {
        if (idx == IDX_NEZNAN) return Datoteka.NEGOTOVA
        jeVideoIme(imeToka)?.let { return if (it) Datoteka.VIDEO else Datoteka.NI_VIDEO }
        if (idx < 0) return Datoteka.NAJVECJI
        if (nasIdx != null && nasIdx == idx) return if (jeVideoIme(nasIme) == false) Datoteka.NI_VIDEO else Datoteka.VIDEO
        return Datoteka.NEGOTOVA
    }

    /**
     * Vrstni red tokov Webtor (indeksi v [webtor]): najprej tisti, ki se ujemajo z NASIMI torrenti, v vrstnem redu nasih
     * torrentov (ista izdaja, kot bi jo izbrali sami - kakovost, jezik, ⚡); nato ostali, `[⚡WT]` pred `[WT]` (hitrejsi
     * zacetek), sicer v danem redu (kakovost). Skriti ([skrij]) izpadejo. Ujemanje: isti hash IN ista datoteka (paket z vec
     * filmi ali epizodami - napacna datoteka bi bila napacen film). [samoTakoj]: samo `[⚡WT]`/`[⚡TB]` (korak »takoj«).
     * [datoteke] ([datoteka], null = vse VIDEO): NI_VIDEO izpade, NEGOTOVA ni »takoj« in je na koncu (le s sondo vsebine).
     */
    fun vrstniRed(nasi: List<Kljuc?>, webtor: List<Pair<Kljuc?, Vrsta>>, imamoTorBox: Boolean, samoTakoj: Boolean = false,
                  datoteke: List<Datoteka>? = null): List<Int> {
        fun d(i: Int) = datoteke?.getOrNull(i) ?: Datoteka.VIDEO
        val vidni = webtor.indices.filter { !skrij(webtor[it].second, imamoTorBox) && d(it) != Datoteka.NI_VIDEO &&
            (!samoTakoj || (takoj(webtor[it].second) && d(it) != Datoteka.NEGOTOVA)) }
        val izbrani = LinkedHashSet<Int>()
        for (k in nasi) {
            if (k == null) continue
            vidni.firstOrNull { it !in izbrani && webtor[it].first == k && d(it) != Datoteka.NEGOTOVA }?.let { izbrani += it }
        }
        val ostali = vidni.filter { it !in izbrani }
        val gotovi = ostali.filter { d(it) != Datoteka.NEGOTOVA }
        return izbrani.toList() + gotovi.filter { takoj(webtor[it].second) } + gotovi.filter { !takoj(webtor[it].second) } +
            ostali.filter { d(it) == Datoteka.NEGOTOVA }
    }

    /** Tok Webtor za izdajo [nas] (torrent, ki ga je uporabnik izbral): indeks v [webtor] ali -1 (NEGOTOVA in NI_VIDEO ne). */
    fun zaIzdajo(nas: Kljuc?, webtor: List<Pair<Kljuc?, Vrsta>>, imamoTorBox: Boolean, datoteke: List<Datoteka>? = null): Int =
        if (nas == null) -1 else webtor.indices.firstOrNull {
            val d = datoteke?.getOrNull(it) ?: Datoteka.VIDEO
            webtor[it].first == nas && !skrij(webtor[it].second, imamoTorBox) && d != Datoteka.NEGOTOVA && d != Datoteka.NI_VIDEO
        } ?: -1

    // ------------------------------------------------------------------ vsebina toka (sonda prvih bajtov)

    /**
     * Prvih toliko bajtov prebere sonda: posoda, glava AVI (hdrl) in sledi MKV (Tracks). Izmerjeno 25. 10. 2026: mkvmerge
     * pusti za SeekHead ~4 KB praznine (Void), zato so Tracks za 4096 bajti - 64 KiB jih zajame.
     */
    const val GLAVA_BAJTOV = 65_536

    enum class Posoda { MKV, MP4, TS, PS, AVI, FLV, ASF, HEIF, M4A, JPEG, PNG, GIF, WEBP, ZVOK, FLAC, OGG, WAV, PDF, ZIP, RAR,
        SEDEMZ, BESEDILO, NEZNANO }

    /** Posoda po prvih bajtih (magicna stevila). */
    fun posoda(g: ByteArray): Posoda {
        fun u(i: Int) = if (i < g.size) g[i].toInt() and 0xFF else -1
        fun ascii(o: Int, s: String) = s.indices.all { u(o + it) == s[it].code }
        fun brand() = (8..11).joinToString("") { if (u(it) in 32..126) u(it).toChar().toString() else "?" }
        return when {
            u(0) == 0x1A && u(1) == 0x45 && u(2) == 0xDF && u(3) == 0xA3 -> Posoda.MKV
            ascii(4, "ftyp") -> when (brand()) {
                "heic", "heix", "heim", "heis", "mif1", "msf1", "avif", "avis" -> Posoda.HEIF
                "M4A ", "M4B ", "M4P " -> Posoda.M4A
                else -> Posoda.MP4
            }
            ascii(4, "moov") || ascii(4, "mdat") || ascii(4, "free") || ascii(4, "wide") || ascii(4, "skip") -> Posoda.MP4
            ascii(0, "RIFF") && ascii(8, "AVI ") -> Posoda.AVI
            ascii(0, "RIFF") && ascii(8, "WAVE") -> Posoda.WAV
            ascii(0, "RIFF") && ascii(8, "WEBP") -> Posoda.WEBP
            u(0) == 0x47 && u(188) == 0x47 -> Posoda.TS
            u(4) == 0x47 && u(196) == 0x47 -> Posoda.TS          // M2TS (4 bajti casa pred vsakim paketom)
            u(0) == 0 && u(1) == 0 && u(2) == 1 && u(3) == 0xBA -> Posoda.PS
            ascii(0, "FLV") -> Posoda.FLV
            u(0) == 0x30 && u(1) == 0x26 && u(2) == 0xB2 && u(3) == 0x75 -> Posoda.ASF
            u(0) == 0xFF && u(1) == 0xD8 && u(2) == 0xFF -> Posoda.JPEG
            u(0) == 0x89 && ascii(1, "PNG") -> Posoda.PNG
            ascii(0, "GIF8") -> Posoda.GIF
            ascii(0, "ID3") || (u(0) == 0xFF && u(1) >= 0 && (u(1) and 0xE0) == 0xE0) -> Posoda.ZVOK   // MP3, AAC ADTS
            ascii(0, "fLaC") -> Posoda.FLAC
            ascii(0, "OggS") -> Posoda.OGG
            ascii(0, "%PDF") -> Posoda.PDF
            ascii(0, "PK") && u(2) == 3 && u(3) == 4 -> Posoda.ZIP
            ascii(0, "Rar!") -> Posoda.RAR
            u(0) == 0x37 && u(1) == 0x7A && u(2) == 0xBC && u(3) == 0xAF -> Posoda.SEDEMZ
            g.isNotEmpty() && (0 until minOf(g.size, 64)).all { u(it) in 32..126 || u(it) in setOf(9, 10, 13) || u(it) >= 0x80 } -> Posoda.BESEDILO
            else -> Posoda.NEZNANO
        }
    }

    private val VIDEO_POSODE = setOf(Posoda.MKV, Posoda.MP4, Posoda.TS, Posoda.PS, Posoda.AVI, Posoda.FLV)

    /**
     * Kodek videa v glavi AVI (strf -> BITMAPINFOHEADER.biCompression za tok `vids`, FourCC kot v datoteki); "" = ga v
     * prvih bajtih ni. Media3 AviExtractor sled s kodekom, ki ga ne pozna, IZPUSTI - ostane samo zvok (Grob krejsnic).
     */
    fun aviKodek(g: ByteArray): String {
        fun ascii(o: Int, s: String) = o >= 0 && o + s.length <= g.size && s.indices.all { (g[o + it].toInt() and 0xFF) == s[it].code }
        var i = 12
        while (i + 64 <= g.size) {
            if (ascii(i, "strh") && ascii(i + 8, "vids")) {
                val dolzina = (g[i + 4].toInt() and 0xFF) or ((g[i + 5].toInt() and 0xFF) shl 8) or
                    ((g[i + 6].toInt() and 0xFF) shl 16) or ((g[i + 7].toInt() and 0xFF) shl 24)
                val f = i + 8 + dolzina + (dolzina and 1)
                if (dolzina < 0 || f + 8 + 20 > g.size || !ascii(f, "strf")) return ""
                val o = f + 8 + 16
                return (0..3).map { (g[o + it].toInt() and 0xFF) }.joinToString("") { if (it in 32..126) it.toChar().toString() else "?" }
            }
            i++
        }
        return ""
    }

    /**
     * Kodiranje sledi MKV, ki ga Media3 1.11.1 (MatroskaExtractor.integerElement) ne zna - tak tok se sploh ne zacne
     * (izmerjeno 25. 10. 2026, Mezda strahu 28,7 GB: »ContentCompAlgo 0 not supported«, zlib na sledi). Pregleda Tracks v
     * prvih bajtih: ContentEncodingOrder != 0, ContentEncodingScope != 1, ContentCompAlgo != 3 (samo odstranjena glava),
     * ContentEncAlgo != 5. Vrne npr. »ContentCompAlgo=0«; "" = ni tega ali Tracks ni v prvih bajtih.
     */
    fun mkvNepodprto(g: ByteArray): String {
        fun u(i: Int) = g[i].toInt() and 0xFF
        // ID: 1-4 bajti, dolzina iz vodilnih nicel prvega bajta (oznaka ostane v ID-ju, kot v specifikaciji).
        fun id(i: Int): Pair<Int, Int>? {
            if (i >= g.size) return null
            val b = u(i)
            val n = when { b and 0x80 != 0 -> 1; b and 0x40 != 0 -> 2; b and 0x20 != 0 -> 3; b and 0x10 != 0 -> 4; else -> return null }
            if (i + n > g.size) return null
            var v = 0
            for (k in 0 until n) v = (v shl 8) or u(i + k)
            return v to n
        }
        // Velikost: 1-8 bajtov brez oznake dolzine; same enice = neznana (-1).
        fun vel(i: Int): Pair<Long, Int>? {
            if (i >= g.size) return null
            val b = u(i)
            var n = 1
            while (n <= 8 && (b and (0x80 shr (n - 1))) == 0) n++
            if (n > 8 || i + n > g.size) return null
            var v = (b and (0xFF shr n)).toLong()
            var enice = v == (0xFF shr n).toLong()
            for (k in 1 until n) { v = (v shl 8) or u(i + k).toLong(); if (u(i + k) != 0xFF) enice = false }
            return (if (enice) -1L else v) to n
        }
        val VSEBNIKI = setOf(0x18538067, 0x1654AE6B, 0xAE, 0x6D80, 0x6240, 0x5034, 0x5035)   // Segment, Tracks, TrackEntry, ...
        var i = 0
        while (i < g.size) {
            val (eid, ni) = id(i) ?: return ""
            val (dolzina, nv) = vel(i + ni) ?: return ""
            val zacetek = i + ni + nv
            when {
                eid == 0x1F43B675 -> return ""                                   // Cluster: sledi so ze mimo
                eid in VSEBNIKI -> { i = zacetek; continue }                      // vstopi v vsebnik
                eid == 0x5031 || eid == 0x5032 || eid == 0x4254 || eid == 0x47E1 -> {
                    if (dolzina !in 1..8 || zacetek + dolzina > g.size) return ""
                    var v = 0L
                    for (k in 0 until dolzina.toInt()) v = (v shl 8) or u(zacetek + k).toLong()
                    val ime = when (eid) { 0x5031 -> "ContentEncodingOrder"; 0x5032 -> "ContentEncodingScope"; 0x4254 -> "ContentCompAlgo"; else -> "ContentEncAlgo" }
                    val dovoljeno = when (eid) { 0x5031 -> 0L; 0x5032 -> 1L; 0x4254 -> 3L; else -> 5L }
                    if (v != dovoljeno) return "$ime=$v"
                }
            }
            if (dolzina < 0) return ""                                            // neznana velikost izven vsebnika
            val naprej = zacetek.toLong() + dolzina
            if (naprej >= g.size) return ""                                       // naslednji element ni vec v prvih bajtih
            i = naprej.toInt()
        }
        return ""
    }

    /** MIME kodeka AVI, kot ga poimenuje Media3 1.11.1 (StreamFormatChunk.getMimeTypeFromCompression); null = sled izpusti. */
    fun aviMime(fourcc: String): String? = when (fourcc) {
        "MP42" -> "video/mp42"
        "MP43" -> "video/mp43"
        "H264", "avc1", "AVC1" -> "video/avc"
        "3VID", "divx", "DIVX", "DX50", "FMP4", "xvid", "XVID" -> "video/mp4v-es"
        "MJPG", "mjpg" -> "video/mjpeg"
        else -> null
    }

    enum class Vsebina { VIDEO, NI_VIDEO, NEPODPRTA, PREMAJHNA }

    data class Pregled(val vsebina: Vsebina, val posoda: Posoda, val kodek: String = "")

    /**
     * Ali je odgovor sonde glavni video, ki ga ta naprava predvaja: [vrsta] = Content-Type, [glava] = prvi bajti, [skupaj] =
     * velikost datoteke (Content-Range, -1 = neznana), [pricakovanoGb] = velikost iz opisa toka (0 = ni), [kodekPodprt] =
     * ima naprava dekoder za MIME (MediaCodecList). Vsebnik po bajtih je odlocilen; Content-Type audio/image/text zavrne.
     */
    fun pregled(vrsta: String?, glava: ByteArray, skupaj: Long, pricakovanoGb: Double, kodekPodprt: (String) -> Boolean): Pregled {
        val p = posoda(glava)
        val ct = vrsta.orEmpty().substringBefore(';').trim().lowercase()
        if (p == Posoda.ASF) return Pregled(Vsebina.NEPODPRTA, p)
        if (p !in VIDEO_POSODE) {
            // Neznan vsebnik z video/* pustimo predvajalniku; vse drugo (slika, glasba, besedilo, arhiv) ni film.
            return if (p == Posoda.NEZNANO && ct.startsWith("video/")) Pregled(Vsebina.VIDEO, p) else Pregled(Vsebina.NI_VIDEO, p)
        }
        if (ct.startsWith("audio/") || ct.startsWith("image/") || ct.startsWith("text/")) return Pregled(Vsebina.NI_VIDEO, p)
        var kodek = ""
        if (p == Posoda.MKV) {
            val nepodprto = mkvNepodprto(glava)
            if (nepodprto.isNotEmpty()) return Pregled(Vsebina.NEPODPRTA, p, nepodprto)
        }
        if (p == Posoda.AVI) {
            kodek = aviKodek(glava)
            if (kodek.isNotEmpty()) {
                val mime = aviMime(kodek)
                if (mime == null || !kodekPodprt(mime)) return Pregled(Vsebina.NEPODPRTA, p, kodek)
            }
        }
        val mib = 1024L * 1024L
        if (skupaj in 0 until 10 * mib) return Pregled(Vsebina.PREMAJHNA, p, kodek)
        // Vzorec: opis toka pove velikost filma (ne paketa nad 100 GB), datoteka je pod 10 % in pod 200 MiB.
        if (skupaj > 0 && pricakovanoGb > 0.0 && pricakovanoGb <= 100.0 && skupaj < 200 * mib &&
            skupaj < pricakovanoGb * 1024.0 * mib * 0.1) return Pregled(Vsebina.PREMAJHNA, p, kodek)
        return Pregled(Vsebina.VIDEO, p, kodek)
    }

    /** Velikost datoteke iz glav odgovora: `Content-Range: bytes 0-4095/123` (206) ali Content-Length (200); -1 = neznano. */
    fun skupnaVelikost(koda: Int, contentRange: String?, contentLength: Long): Long {
        if (koda == 206) return contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull() ?: -1L
        return if (koda in 200..299 && contentLength >= 0) contentLength else -1L
    }

    enum class Izid { PREDVAJAJ, NI_PAKETA, NI_TOKA, OMEJITEV, PREPOCASI, NAPAKA, NAPACNA_VSEBINA }

    /**
     * Sonda toka (zahteva za prve bajte): [koda] HTTP (0 = ni odgovora), [prviBajtMs] cas do prvega bajta (null = ga ni
     * bilo), [potekel] = rok je potekel, [vsebina] = [pregled] prvih bajtov (null = ni pregledana - samo stari skupni
     * primeri). 402 = ni paketa, 404/410 = Webtor toka nima, 429 = omejitev ali drug naslov IP; 206 s sliko, glasbo,
     * vzorcem ali kodekom, ki ga naprava ne zna, je [Izid.NAPACNA_VSEBINA]. Vse razen [Izid.PREDVAJAJ]: naslednji vir.
     */
    fun odlocitev(koda: Int, prviBajtMs: Long?, potekel: Boolean, vsebina: Vsebina? = null): Izid = when {
        koda == 402 -> Izid.NI_PAKETA
        koda == 404 || koda == 410 -> Izid.NI_TOKA
        koda == 429 -> Izid.OMEJITEV
        potekel -> Izid.PREPOCASI
        koda in 200..299 && prviBajtMs != null -> when {
            prviBajtMs > PRVI_BAJT_MS -> Izid.PREPOCASI
            vsebina != null && vsebina != Vsebina.VIDEO -> Izid.NAPACNA_VSEBINA
            else -> Izid.PREDVAJAJ
        }
        else -> Izid.NAPAKA
    }

    /**
     * Hitra zavrnitev vsebine ne steje kot poskus (skupno pravilo v4, pomocnik Windows 25. 10. 2026, naloga 21 B1): pri
     * torrentih YTS CDN TorBox za idx filma vrne .srt; sonda ga zavrne v ~0,3-0,7 s, a je porabila enega od 2 poskusov.
     */
    const val HITRA_ZAVRNITEV_MS = 3_000L

    /**
     * Ali sonda steje med [NAJVEC_POSKUSOV]: [Izid.NAPACNA_VSEBINA] v manj kot [HITRA_ZAVRNITEV_MS] ne (skupni rok 15 s velja
     * naprej - naslednji tok dobi preostanek). Vse drugo (tudi hitra 404/402/429) steje. Enako namizje (webtor.steje_kot_poskus).
     */
    fun stejeKotPoskus(izid: Izid, trajanjeMs: Long): Boolean = !(izid == Izid.NAPACNA_VSEBINA && trajanjeMs in 0 until HITRA_ZAVRNITEV_MS)

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
     * Izmerjeno 25. 10. 2026 (OnePlus 9, 3 filmi, 16 sond kandidatov): prvi bajt prek TorBox CDN 2,9-6,2 s, prek
     * Webtorjevih streznikov (prenos) 5,8-14,0 s; 4 od 16 kandidatov so vrnili napacno datoteko (slika BMP, besedilo,
     * vzorec 17 MB, neznana datoteka pod 1 MB). Zato ena sonda hkrati preveri [VZPOREDNO] kandidatov: uporabnik caka na
     * prvega pravega, ne na vsoto napacnih.
     */
    const val VZPOREDNO = 2
    /** Ko je pripravljen slabsi kandidat, na boljsega (visje v vrstnem redu) pocakamo se najvec toliko. */
    const val POCAKAJ_BOLJSEGA_MS = 3_000L

    /**
     * Izbira med vzporednimi sondami: [izidi] v vrstnem redu kandidatov (true = glavni video pripravljen, false = ne,
     * null = se ni odgovora), [msOdPrvega] = koliko ms je minilo, odkar je prvi kandidat pripravljen (null = noben),
     * [rokPotekel]. Izbran je najboljsi pripravljen, pred katerim so vsi ze odgovorili »ne«; sicer na boljse cakamo najvec
     * [POCAKAJ_BOLJSEGA_MS]. [CAKAJ] = cakaj, [NOBENA] = noben ni pripravljen.
     */
    fun izberiSondo(izidi: List<Boolean?>, msOdPrvega: Long?, rokPotekel: Boolean): Int {
        val i = izidi.indexOfFirst { it == true }
        if (i >= 0) {
            if ((0 until i).all { izidi[it] == false }) return i
            return if (rokPotekel || (msOdPrvega != null && msOdPrvega >= POCAKAJ_BOLJSEGA_MS)) i else CAKAJ
        }
        return if (rokPotekel || izidi.none { it == null }) NOBENA else CAKAJ
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
