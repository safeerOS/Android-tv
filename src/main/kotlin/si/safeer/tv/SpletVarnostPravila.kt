package si.safeer.tv

/**
 * Cista varnostna pravila brskalnika - brez Androida, da jih preverimo v JVM (tests/SpletVarnostPravilaTest.kt).
 *
 * Zunanji pregled kode 8. 10. 2026: stran je z naslovom intent: lahko odprla nase skrite dejavnosti (komponenta in
 * izbirnik sta ostala, zastavice tudi), rezervni naslov se je nalozil brez preverbe sheme, most SafeerBridge je zaupal
 * zastavici, ki se je postavila pred menjavo dokumenta (tudi za about:blank), stran je smela vstaviti content://, okno o
 * napacnem potrdilu pa je imelo fokus na »Odpri« in je nadaljevanje ponudilo tudi za tuje potrdilo javne strani.
 */
object SpletVarnostPravila {
    /** Domaca stran brskalnika; most SafeerBridge sme brati ploscice in premikati brskalnik samo njej. */
    const val DOMACA_BRSKALNIKA = "file:///android_asset/brave_home.html"

    /** Zunanjo aplikacijo sme stran odpreti samo tako dolgo po uporabnikovi tipki ali dotiku (ali s kretnjo). */
    const val ZAGON_PO_DEJANJU_MS = 3000L

    private const val VIEW = "android.intent.action.VIEW"

    /** Sheme, ki jih stran nikoli ne preda drugi aplikaciji: krajevne vsebine, skripte in notranje sheme. */
    private val PREPOVEDANE_SHEME = setOf("content", "file", "javascript", "data", "blob", "about", "intent", "android-app", "chrome")

    /**
     * Ali sme stran odpreti drugo aplikacijo s to namero. Klicatelj je namero ze ocistil: komponenta in izbirnik sta
     * odstranjena, zastavice so samo FLAG_ACTIVITY_NEW_TASK, kategorija BROWSABLE dodana. Tu odlocimo se: samo VIEW,
     * nikoli nas lasten paket, nikoli krajevne ali skriptne sheme.
     */
    fun dovoljenaNamera(akcija: String?, shemaPodatkov: String?, paket: String?, nasPaket: String): Boolean {
        if ((akcija ?: VIEW) != VIEW) return false
        if (paket != null && paket == nasPaket) return false
        // Zunanji pregled 3. 11. 2026: intent: brez podatkov (samo paket in dodatki) ni povezava, ampak ukaz aplikaciji.
        val shema = shemaPodatkov?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        return shema !in PREPOVEDANE_SHEME
    }

    /**
     * Zunanji pregled 3. 11. 2026 (MobileFileProvider): ponudnik je sprejel ../ iz predpomnilnika (shared_prefs z zetoni),
     * mapo tri ravni nad zunanjo mapo aplikacije in zapisovanje. Edina raba je namestitev APK iz cache_files/<mapa>/<ime>:
     * vrne datoteko znotraj [koren] po kanonicni poti ali null (nikoli korena samega).
     */
    fun datotekaPonudnika(koren: java.io.File, pot: String?): java.io.File? {
        val rel = pot?.removePrefix("/")?.takeIf { it.startsWith("cache_files/") }?.removePrefix("cache_files/") ?: return null
        if (rel.isEmpty() || '\u0000' in rel) return null
        return try {
            val k = koren.canonicalFile
            val f = java.io.File(k, rel).canonicalFile
            if (f.path.startsWith(k.path + java.io.File.separator)) f else null
        } catch (_: Exception) { null }
    }

    /** browser_fallback_url iz namere: samo spletni naslov http(s) z gostiteljem (nikoli javascript:, file:, content:). */
    fun varenRezervniNaslov(url: String?): Boolean {
        if (url.isNullOrEmpty() || url != url.trim()) return false
        return try {
            val u = java.net.URI(url)
            (u.scheme.equals("https", ignoreCase = true) || u.scheme.equals("http", ignoreCase = true)) && !u.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    /** Kaj naredimo z novim oknom (target=_blank, window.open), ki ni prijava. */
    enum class PojavnoOkno { ZAVRNI, ISTI_ZAVIHEK, NOV_ZAVIHEK }

    /**
     * Lastnik 4. 11. 2026 (TV, rtvslo.si): kartica filma je <a target="_blank" href="https://365.rtvslo.si/..."> in klik ni
     * naredil nic - vratar je prepustil samo prijavna okna. Zdaj:
     *  - brez uporabnikove kretnje ostane okno zavrnjeno (samodejni oglasi, zanke oken);
     *  - samo http(s) z gostiteljem ([varenRezervniNaslov]);
     *  - ista registrirana domena (eTLD+1) kot stran, ki je okno odprla: v spletni aplikaciji v istem zavihku (aplikacija
     *    ostane), v navadnem brskanju nov zavihek (obicajno vedenje _blank);
     *  - tuja domena samo, ce je uporabnik kliknil prav povezavo na ta naslov ([povezava] = href pod fokusom ali dotikom):
     *    nov navaden zavihek. Strani s filmi ob kliku na predvajalnik odprejo oglas z window.open - ta ni povezava pod
     *    kliki in ostane zavrnjen, kot doslej.
     */
    fun pojavnoOkno(kretnja: Boolean, naslov: String, izvor: String?, povezava: String?, nacinAplikacije: Boolean): PojavnoOkno {
        if (!kretnja || !varenRezervniNaslov(naslov)) return PojavnoOkno.ZAVRNI
        val cilj = gostitelj(naslov) ?: return PojavnoOkno.ZAVRNI
        val od = izvor?.let { gostitelj(it) }
        if (od != null && com.safeer.threatfeed.FilterListEngine.registrable(cilj) == com.safeer.threatfeed.FilterListEngine.registrable(od))
            return if (nacinAplikacije) PojavnoOkno.ISTI_ZAVIHEK else PojavnoOkno.NOV_ZAVIHEK
        if (povezava != null && istiNaslov(povezava, naslov)) return PojavnoOkno.NOV_ZAVIHEK
        return PojavnoOkno.ZAVRNI
    }

    /** Gostitelj spletnega naslova (malo, brez pike na koncu) ali null. */
    private fun gostitelj(url: String): String? = try {
        val u = java.net.URI(url.trim())
        if (!u.scheme.equals("https", true) && !u.scheme.equals("http", true)) null
        else u.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

    /** Isti naslov ne glede na presledke okoli in koncno posevnico (href pred normalizacijo brskalnika). */
    private fun istiNaslov(a: String, b: String): Boolean = a.trim().trimEnd('/') == b.trim().trimEnd('/')

    /** Od kod je prisel naslov, ki ga brskalnik nalozi v zavihek (namera druge aplikacije ali sporocilo druge naprave). */
    enum class Vhod { NAMERA, LINK, SPLET }

    /**
     * Tedenski pregled 4. 11. 2026 (UXSS): MainActivity je izvozena; tuja aplikacija je z namero VIEW (ali dodatkom
     * cast_url) podtaknila javascript: in loadUrl ga je izvedel v izvoru odprte strani. Enako cast.url in share.screen iz
     * Safeer Linka (sporocilo druge naprave). Vsak zunanji naslov gre zato skozi to eno preverbo:
     * - vedno: http(s) z gostiteljem;
     * - NAMERA: se krajevni PDF (content:// ali file://...pdf izven assets), ki ga ChromiumEngineView.loadUrl preda
     *   nasemu pregledovalniku (PdfPregledovalnik.jeLokalni - ista pravila), nikoli WebViewu;
     * - LINK: se nasa domaca stran (Daljinec open_url »home«), samo natanko DOMACA ali DOMACA_BRSKALNIKA;
     * - SPLET (spletna aplikacija iz dodatka namere): samo http(s).
     * Nikoli javascript:, data:, blob:, intent:, about:, file: (razen PDF), content: iz Linka. Kontrolni znaki in presledki
     * kjerkoli v naslovu so zavrnjeni: Chromium iz naslova odstrani tabulator in novo vrstico (»java\tscript:«).
     */
    fun vhodniNaslov(url: String?, vhod: Vhod, dovoljenjeBranja: Boolean = false): Boolean {
        if (url.isNullOrEmpty() || url.length > NAJDALJSI_VHODNI) return false
        if (url.any { it <= ' ' || it == '\u007f' }) return false
        val shema = url.substringBefore(':', "").lowercase()
        if ((shema == "http" || shema == "https") && url.length > shema.length + 3) {
            val ostanek = url.substring(shema.length + 1).replace('\\', '/')
            if (!ostanek.startsWith("//")) return false
            val gostitelj = ostanek.substring(2).substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
            return gostitelj.isNotEmpty() && !gostitelj.startsWith(":")
        }
        return when (vhod) {
            Vhod.NAMERA -> jeKrajevniPdf(url) && (!url.startsWith("content://") || dovoljenjeBranja || !lastnaAvtoriteta(url))
            Vhod.LINK -> jeDomacaBrskalnika(url) || SpletMostPravila.jeDovoljenIzvor(url)
            Vhod.SPLET -> false
        }
    }

    /**
     * Zadnja vrata v ChromiumEngineView.loadUrl (tedenski pregled 4. 11. 2026): nasa koda nikoli ne nalozi skripte ali
     * dokumenta data: z loadUrl (preverjeno z grep), zato je tak naslov vedno podtaknjen - zavrnemo ga ne glede na vhod.
     * Shemo beremo kot Chromium: brez vodilnih presledkov/kontrolnih znakov ter brez tabulatorjev in novih vrstic.
     */
    fun skriptniNaslov(url: String?): Boolean {
        if (url == null) return false
        val cist = url.trimStart { it <= ' ' }.filterNot { it == '\t' || it == '\n' || it == '\r' }
        val shema = cist.substringBefore(':', "").lowercase()
        return shema == "javascript" || shema == "vbscript" || shema == "data"
    }

    /**
     * Spletna aplikacija cez cel zaslon (brez naslovne vrstice) iz dodatka namere (tedenski pregled 4. 11. 2026, S1): tuja
     * aplikacija bi sicer pokazala lazno prijavno stran, ki je ni mogoce lociti od prave. Samo http(s) in samo, ce je namera
     * prisla od Safeerja (zasciteni vhod ali neizvozena dejavnost) ali je naslov na uporabnikovem seznamu spletnih aplikacij.
     */
    enum class SpletnaAplikacija { CEZ_ZASLON, ZAVIHEK, ZAVRNI }

    fun spletnaAplikacija(url: String?, odSafeerja: Boolean, naSeznamu: () -> Boolean): SpletnaAplikacija = when {
        !vhodniNaslov(url, Vhod.SPLET) -> SpletnaAplikacija.ZAVRNI
        odSafeerja || naSeznamu() -> SpletnaAplikacija.CEZ_ZASLON
        else -> SpletnaAplikacija.ZAVIHEK
    }

    /** Najdaljsi naslov od zunaj (WebView ga sicer sprejme do 2 MB; tako dolg naslov ni povezava). */
    const val NAJDALJSI_VHODNI = 8192

    /**
     * Neodvisni pregled 4. 11. 2026 09:10: content:// iz namere tuje aplikacije bi odprl NAS ponudnik (fileprovider,
     * spletne ...) z nasimi pravicami - tuja aplikacija bi nam podtaknila nase zasebne datoteke. Avtoriteta paketa
     * si.safeer.* velja samo, ce namera nosi FLAG_GRANT_READ_URI_PERMISSION (dovoljenjeBranja): tega Android ne dovoli
     * aplikaciji, ki do ponudnika nima dostopa. Avtoriteta brez »uporabnik@« in vrat; %-kodirana se steje za naso.
     */
    internal fun lastnaAvtoriteta(url: String): Boolean {
        val a = url.removePrefix("content://").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').lowercase()
        return a == "si.safeer" || a.startsWith("si.safeer.") || '%' in a
    }

    /**
     * Mape, iz katerih tuja namera sme odpreti file://...pdf: skupna shramba in USB (pregled 4. 11. 2026, prenos s
     * telefona 371c011 in strozje). Zasebne mape (/data/, /data_mirror/, /mnt/expand/), /proc/, /sys/ in assets nikoli -
     * zato seznam dovoljenih, ne prepovedanih. Android od 7.0 file:// med aplikacijami sicer ne dovoli (FileUriExposed).
     */
    private val DOVOLJENE_PDF_MAPE = listOf("/storage/", "/sdcard/", "/mnt/sdcard/", "/mnt/media_rw/")

    /**
     * Enako kot PdfPregledovalnik.jeLokalni (content:// ali file://...pdf), od zunaj pa file:// samo v DOVOLJENE_PDF_MAPE.
     * ContentResolver.openInputStream bere ODKODIRANO pot (Uri.getPath), zato preverjamo odkodirano: %64ata, %2e%2e,
     * %2f, file:////data, file://localhost/data, /./ in \ so zavrnjeni; ostanek % po odkodiranju (dvojno kodiranje) tudi.
     */
    private fun jeKrajevniPdf(url: String): Boolean {
        if (url.startsWith("content://")) return url.length > "content://".length
        if (!url.startsWith("file://") || url.startsWith("file:///android_asset/")) return false
        // Brez gostitelja: file://localhost/... ali file://naprava/... ni nasa pot.
        val surova = url.removePrefix("file://").substringBefore('?').substringBefore('#')
        if (!surova.startsWith("/")) return false
        val odkodirana = odkodirajPot(surova) ?: return false
        if ('%' in odkodirana || '\\' in odkodirana || odkodirana.any { it < ' ' || it == '\u007f' }) return false
        // Vec posevnic (file:////data/...) je ista pot kot ena.
        val pot = odkodirana.replace(Regex("/{2,}"), "/")
        if (pot.split('/').any { it == "." || it == ".." }) return false
        val mala = pot.lowercase()
        if (DOVOLJENE_PDF_MAPE.none { mala.startsWith(it) }) return false
        return mala.endsWith(".pdf")
    }

    /** %XX v UTF-8 (kot Uri.decode); napacno kodiranje vrne null. Brez android.net, da je pravilo preizkusljivo brez Androida. */
    internal fun odkodirajPot(s: String): String? {
        if ('%' !in s) return s
        val izhod = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hex = s.substring(i + 1, i + 3)
                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                val h = hex.toInt(16)
                izhod.write(h); i += 3
            } else {
                izhod.write(c.toString().toByteArray(Charsets.UTF_8)); i++
            }
        }
        val b = izhod.toByteArray()
        val dek = Charsets.UTF_8.newDecoder()
        return try { dek.decode(java.nio.ByteBuffer.wrap(b)).toString() } catch (_: java.nio.charset.CharacterCodingException) { null }
    }

    /** Oglas ob nalaganju strani ne sme sam odpreti druge aplikacije: potrebna je kretnja ali nedavno dejanje. */
    fun dovoljenZunanjiZagon(kretnja: Boolean, msOdDejanja: Long): Boolean =
        kretnja || msOdDejanja in 0..ZAGON_PO_DEJANJU_MS

    /** Cas zadnjega dejanja, ki ga lahko porabi en zunanji zagon: po uspesnem zagonu ne velja vec, dokler ni novega dejanja. */
    class PorabljivoDejanje {
        @Volatile private var cas = 0L
        @Volatile private var porabljeno = 0L
        fun oznaci(zdaj: Long) { cas = zdaj }
        fun porabi() { porabljeno = cas }
        fun msOd(zdaj: Long): Long = if (cas == 0L || cas <= porabljeno) Long.MAX_VALUE else zdaj - cas
    }

    /**
     * Neodvisni pregled 4. 11. 2026 (TV): navigate() z domace strani je naslov podal WebViewu brez trim() in brez zavrnitve
     * praznega - telefon (ChromiumEngineView) ga od 4. 11. reze in prazen zavrne. Vrne obrezan cilj ali null.
     */
    fun ciljNavigacije(url: String?): String? = url?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Neodvisni pregled 4. 11. 2026 (HubTls.jeNaslovHuba): pripeti odtis Huba ni veljal, ce je bil naslov zapisan z vrati
     * ali brez njih (https://hub/ proti https://hub:443/: -1 proti 443) ali z velikimi crkami gostitelja. Isti izvor =
     * ista shema in gostitelj (brez razlike v velikosti crk) in ista vrata (privzeta vrata sheme, ce niso zapisana).
     */
    fun istiIzvor(url: String, zaupan: String): Boolean {
        if (zaupan.isBlank()) return false
        return try {
            val a = java.net.URL(url)
            val b = java.net.URL(zaupan)
            fun vrata(u: java.net.URL) = if (u.port != -1) u.port else u.defaultPort
            val va = vrata(a)
            a.protocol.equals(b.protocol, ignoreCase = true) && a.host.isNotEmpty() &&
                a.host.equals(b.host, ignoreCase = true) && va != -1 && va == vrata(b)
        } catch (_: Exception) {
            false
        }
    }

    /** Natanko domaca stran brskalnika (poizvedba in sidro smeta biti); about:blank, prazen naslov in drugo ne. */
    fun jeDomacaBrskalnika(url: String?): Boolean {
        val cist = url?.substringBefore('#')?.substringBefore('?') ?: return false
        return cist == DOMACA_BRSKALNIKA
    }

    /**
     * content:// (krajevne datoteke in mediji naprave) sme biti samo glavni dokument, ki ga je odprl uporabnik (priponka
     * HTML iz druge aplikacije). Kot vir strani (slika, skripta, okvir) nikoli - stran bi sicer preverjala ali brala
     * krajevne medije.
     */
    fun dovoliContentZahtevo(url: String, glavniOkvir: Boolean): Boolean =
        glavniOkvir || !url.trim().startsWith("content:", ignoreCase = true)

    /**
     * Ali okno o neveljavnem potrdilu ponudi »Odpri vseeno« (SslError: NOTYETVALID 0, EXPIRED 1, IDMISMATCH 2,
     * UNTRUSTED 3, DATE_INVALID 4, INVALID 5). Tuj izdajatelj ali napacno ime (napad v sredini) samo za naprave v
     * domacem omrezju (usmerjevalnik, NAS s samopodpisanim potrdilom); napaka casa tudi drugod.
     */
    fun sslSmeNadaljevati(napaka: Int, gostitelj: String): Boolean = when (napaka) {
        0, 1, 4 -> true
        2, 3, 5 -> jeZasebniGostitelj(gostitelj)
        else -> false
    }

    /** Kaj storiti ob neveljavnem potrdilu (ni nas Hub, ni prava banka): preklici, nadaljuj ali vprasaj uporabnika. */
    enum class SslOdlocitev { PREKLICI, NADALJUJ, VPRASAJ }

    /**
     * Vrstni red je pomemben (zunanji pregled 8. 10. 2026, F1): trda ovira - tuj izdajatelj ali napacno ime pri
     * javnem naslovu - velja PRED zapomnjeno izjemo te seje. Zapomnjena izjema (dovoljeniSsl) je vezana le na
     * gostitelja:vrata, ne na vrsto napake; brez tega vrstnega reda bi uporabnikov klik »odpri« ob pretecenem
     * potrdilu kasneje tiho spustil skozi podtaknjeno nezaupano potrdilo za istega gostitelja.
     */
    fun sslOdlocitev(napaka: Int, gostitelj: String, zeDovoljen: Boolean): SslOdlocitev = when {
        !sslSmeNadaljevati(napaka, gostitelj) -> SslOdlocitev.PREKLICI
        zeDovoljen -> SslOdlocitev.NADALJUJ
        else -> SslOdlocitev.VPRASAJ
    }

    /** Naprava v domacem omrezju: zasebni naslov IPv4/IPv6 (oktete razclenimo) ali krajevno ime (.local, ime brez pike). */
    fun jeZasebniGostitelj(gostitelj: String): Boolean {
        var h = gostitelj.trim().lowercase()
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length - 1)
        h = h.substringBefore('%')
        if (h.isEmpty()) return false
        ipv4(h)?.let { return zasebniIpv4(it) }
        if (':' in h) return zasebniIpv6(h)
        if (h == "localhost") return true
        if (!IME.matches(h)) return false
        if ('.' !in h) return !h.all { it.isDigit() }
        return KRAJEVNE_PRIPONE.any { h.endsWith(it) }
    }

    /** Kljuc odlocitve o potrdilu: gostitelj in vrata (ne samo ime). Prazen niz, ce naslov ni veljaven. */
    /**
     * Zunanji pregled 3. 11. 2026: izjema za slabo potrdilo je veljala za gostitelja in vrata - drugo (napadalcevo) potrdilo
     * na istem naslovu je bilo nato sprejeto tiho. Kljuc izjeme zdaj vsebuje SHA-256 potrdila; brez potrdila izjeme ni.
     */
    fun sslKljucSPotrdilom(kljucNaslova: String, potrdilo: ByteArray?): String? {
        if (kljucNaslova.isEmpty() || potrdilo == null || potrdilo.isEmpty()) return null
        val d = java.security.MessageDigest.getInstance("SHA-256").digest(potrdilo)
        return kljucNaslova + "#" + d.joinToString("") { "%02x".format(it) }
    }

    fun sslKljuc(url: String?): String = try {
        val u = java.net.URI(url ?: "")
        val shema = u.scheme?.lowercase()
        val host = u.host?.lowercase()
        val vrata = when {
            u.port >= 0 -> u.port
            shema == "https" -> 443
            shema == "http" -> 80
            else -> -1
        }
        if (host.isNullOrEmpty() || vrata < 0) "" else "$host:$vrata"
    } catch (_: Exception) {
        ""
    }

    private val IME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$")
    private val KRAJEVNE_PRIPONE = listOf(".local", ".lan", ".home.arpa", ".internal")

    /** Stroga oblika a.b.c.d (desetisko, brez vodilnih nicel); sicer null. */
    private fun ipv4(h: String): IntArray? {
        val deli = h.split('.')
        if (deli.size != 4) return null
        val o = IntArray(4)
        for ((i, d) in deli.withIndex()) {
            if (d.isEmpty() || d.length > 3 || !d.all { it in '0'..'9' } || (d.length > 1 && d[0] == '0')) return null
            o[i] = d.toInt()
            if (o[i] > 255) return null
        }
        return o
    }

    /**
     * Ni javni naslov (enako kot WebtorPravila.zasebenNaslov, zunanji pregled 2. 11. 2026): 0/8, 10/8, 127/8, 100.64/10
     * (CGNAT, npr. VPN v domacem krogu), 169.254/16, 172.16/12, 192.0.0/24, 192.168/16 ter vecvrstni in rezervirani (224+).
     */
    private fun zasebniIpv4(o: IntArray): Boolean =
        o[0] == 0 || o[0] == 10 || o[0] == 127 || (o[0] == 100 && o[1] in 64..127) || (o[0] == 169 && o[1] == 254) ||
            (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 0 && o[2] == 0) || (o[0] == 192 && o[1] == 168) || o[0] >= 224

    private fun zasebniIpv6(h: String): Boolean {
        // Samo zapis naslova (brez imen): getByName tak niz razcleni brez poizvedbe DNS.
        if (!Regex("^[0-9a-f:.]+$").matches(h)) return false
        val a = try { java.net.InetAddress.getByName(h) } catch (_: Exception) { return false }
        if (a is java.net.Inet4Address) return zasebniIpv4(a.address.map { it.toInt() and 0xff }.toIntArray())
        val b = a.address
        return a.isLoopbackAddress || a.isLinkLocalAddress || (b[0].toInt() and 0xfe) == 0xfc
    }

    /**
     * TV 1. 11. 2026: menjava nastavitev WebView (User-Agent) med preusmeritvijo glavnega okvirja je WebView 153 sesula
     * (Gmail -> accounts.google.com). Preusmeritev na Googlu ali z njega zato prekinemo in naslov nalozimo znova.
     * Samo http(s), samo glavni okvir, samo preusmeritev; drugod navigacija tece kot doslej.
     */
    /**
     * TV 1. 11. 2026 18:23: navigacija glavnega okvirja, pri kateri se User-Agent zamenja (Gmail »Sign in« z Googla na
     * Googlovo prijavo), se v shouldOverrideUrlLoading ne sme izvesti z menjavo nastavitev - nalozimo jo znova (loadUrl).
     */
    fun naloziZNovoIdentiteto(glavniOkvir: Boolean, url: String, identitetaSeZamenja: Boolean): Boolean =
        glavniOkvir && identitetaSeZamenja &&
            (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true))

    /** Najvec zaporednih ponovnih nalaganj (preusmeritev ali nova identiteta) v [OKNO_ZANKE_MS]. */
    const val NAJVEC_PONOVNIH_NALAGANJ = 6
    const val OKNO_ZANKE_MS = 10_000L

    /**
     * TV 2. 11. 2026: Gmail »Sign in« (povezava target=_blank) ni odprl nicesar - novo okno je naslov z drugo identiteto
     * nalozilo znova se PREDEN je vratar pojavnega okna odlocil; loadUrl vratarja ne poklice, okno je ostalo skrito in je
     * bilo po 15 s zavrzeno. Vratar zato odloci prvi - razen za dodatek Stremio, ki ga obravnava sam (Install (web)).
     */
    fun vratarNajprej(imaVratarja: Boolean, glavniOkvir: Boolean, url: String, jeDodatek: Boolean): Boolean =
        imaVratarja && glavniOkvir && url.startsWith("http", ignoreCase = true) && !jeDodatek

    /**
     * Pregled Gemini 2. 11. 2026: ponovno nalaganje (loadUrl) vsakic zacne novo navigacijo in s tem obide WebViewovo stetje
     * preusmeritev (ERR_TOO_MANY_REDIRECTS). Zanka A -> B -> A bi tekla v nedogled - po [NAJVEC_PONOVNIH_NALAGANJ] v oknu
     * jo ustavimo. Vrne novo stanje (stevec, zacetek okna) in ali je nalaganje se dovoljeno.
     */
    fun ponovnoNalaganje(stevec: Int, zacetekOkna: Long, zdaj: Long): Triple<Int, Long, Boolean> {
        val novoOkno = zdaj - zacetekOkna > OKNO_ZANKE_MS || zdaj < zacetekOkna
        val s = if (novoOkno) 1 else stevec + 1
        return Triple(s, if (novoOkno) zdaj else zacetekOkna, s <= NAJVEC_PONOVNIH_NALAGANJ)
    }

    fun naloziPoPreusmeritvi(glavniOkvir: Boolean, preusmeritev: Boolean, url: String, google: Boolean): Boolean =
        glavniOkvir && preusmeritev && google &&
            (url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true))

    /**
     * Pregled Gemini 3. 11. 2026: SafeerBridge (addJavascriptInterface) vidi VSAK okvir strani. Okvir, ki 1,5 s po OK
     * poklice triggerNativeTap pred nasim skriptom, je porabil dovoljenje in sam izbral tocko dotika. Zato dotik velja
     * samo z zetonom, ki ga dobi le nas skript glavnega okvirja (evaluateJavascript, zaprtje skripta), primerjan v
     * konstantnem casu (AGENTS.md: ZETON_MOSTU).
     */
    const val ZETON_DOTIKA_MESTO = "__SAFEER_ZETON_DOTIKA__"

    fun novZeton(): String {
        val b = ByteArray(24)
        java.security.SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    fun zetonVelja(dan: String?, pricakovan: String): Boolean =
        dan != null && pricakovan.length >= 32 && pricakovan != ZETON_DOTIKA_MESTO &&
            java.security.MessageDigest.isEqual(dan.toByteArray(Charsets.UTF_8), pricakovan.toByteArray(Charsets.UTF_8))

    /** Zeton gre samo v besedilo nasega skripta (tv_spatial.js), nikoli na window ali v dnevnik. */
    fun vstaviZeton(js: String, zeton: String): String = js.replace(ZETON_DOTIKA_MESTO, zeton)

    const val IZVIRNIKI_MESTO = "__SAFEER_IZVIRNIKI__"

    /**
     * Pregled Groka 5. 11. 2026: tv_spatial.js zaupa izvirnikom metod (__safeer_izvirniki) samo z 'da', ki ga aplikacija
     * vstavi, ce je ChromiumEngineView.IZVIRNIKI_JS registriral - sicer bi lastnost pred nasim skriptom ponaredila stran.
     */
    fun vstaviIzvirnike(js: String, registrirani: Boolean): String = js.replace(IZVIRNIKI_MESTO, if (registrirani) "da" else "ne")

    /** Sinteticni dotik (triggerNativeTap) samo znotraj pogleda; NaN ali tocka zunaj se zavrne. */
    fun dotikVPogledu(x: Float, y: Float, sirina: Int, visina: Int): Boolean =
        !x.isNaN() && !y.isNaN() && x >= 0f && y >= 0f && x < sirina && y < visina
}
