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

    /** Prvih toliko bajtov prebere sonda (Range 0-4095): dovolj za posodo in glavo AVI (hdrl). */
    const val GLAVA_BAJTOV = 4096

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
