package si.safeer.tv

/**
 * Lastnik 9. 11. 2026 (24ur.com): stran, ki ni narejena za TV, naj daljinec sam preklopi v kazalec (miska). Izbira lastnika:
 * privzeto kazalec, izjeme so strani za TV (nase strani, YouTube za TV, pregledovalnik PDF). Rocni preklop (rdeca/INFO,
 * gumb kazalca, drzan OK) si zapomni brskalnik in spletna aplikacija za gostitelja (en vir) - naslednjic stran odpre tako, kot jo je uporabnik zelel.
 */
object KazalecPravila {
    /** Konec oblasti naslova: prvi od teh znakov (Chromium bere »\« kot »/«). */
    private val MEJA_OBLASTI = charArrayOf('/', '?', '#', '\\')

    /**
     * Gostitelj http(s) naslova (malo, brez pike na koncu, IPv6 v oglatih oklepajih) ali null.
     * Slepi pregled TV3 (N1): prej java.net.URI, ki pri { | ^ \ ali presledku v poizvedbi ali sidru vrne null; stran je
     * lahko z location.replace('/?a={') prisilila puscice (privzetoKazalec ob null gostitelju vrne false PRED zapomnjeno izbiro).
     * Zdaj gostitelja razberemo samo iz OBLASTI (do prvega / ? # \), kot [GooglePravila.gostitelj] in Linux napaka_naslova:
     * poizvedba, sidro in pot nanj ne vplivajo. Neveljavni znaki v SAMEM gostitelju (presledek, nadzorni znaki, { } | ^ < > " `)
     * dajo null: neznan gostitelj je po zasnovi fail-safe (puscice, zapomnjene izbire ni kam vezati).
     */
    fun gostitelj(naslov: String?): String? {
        val n = (naslov ?: "").trim()
        val i = n.indexOf("://")
        if (i <= 0) return null
        val shema = n.substring(0, i)
        if (!shema.equals("https", true) && !shema.equals("http", true)) return null
        val konec = n.indexOfAny(MEJA_OBLASTI, i + 3).let { if (it < 0) n.length else it }
        var h = n.substring(i + 3, konec).substringAfterLast('@').lowercase()
        val vrata: String
        if (h.startsWith("[")) {
            val k = h.indexOf(']')
            if (k < 0) return null
            vrata = h.substring(k + 1).removePrefix(":").takeIf { h.length == k + 1 || h[k + 1] == ':' } ?: return null
            h = h.substring(0, k + 1)
        } else {
            vrata = h.substringAfter(':', "")
            h = h.substringBefore(':')
        }
        // Neveljavna vrata (www.youtube.com:evil): Chromium naslova ne odpre; neznan gostitelj, fail-safe.
        if (vrata.isNotEmpty() && (vrata.length > 5 || !vrata.all { it in '0'..'9' } || vrata.toInt() > 65535)) return null
        h = h.trimEnd('.')
        if (h.isEmpty() || h.any { it.isWhitespace() || it.code < 0x21 || it.code == 0x7F || it in "{}|^<>\"`" }) return null
        return h
    }

    /** Pot http(s) naslova (male crke, brez poizvedbe in sidra; »\« je »/«); "" = brez poti; null = ni spletni naslov. */
    private fun pot(naslov: String?): String? {
        val n = (naslov ?: "").trim()
        val i = n.indexOf("://")
        if (i <= 0) return null
        val z = n.indexOfAny(MEJA_OBLASTI, i + 3)
        if (z < 0) return ""
        return n.substring(z).replace('\\', '/').substringBefore('?').substringBefore('#').lowercase()
    }

    /**
     * Ali naj bo kazalec ob odprtju [naslov] vklopljen. [zapomnjeno] = uporabnikova izbira za gostitelja (null = ni je).
     * [zaTv] = stran je narejena za daljinec (nasa stran, YouTube za TV, pregledovalnik PDF). [miska] = stran brez daljinca
     * ne gre (Facebook ..., [si.safeer.tv.os.OsPravila.miskaZaAplikacijo]).
     * Lastnik 15. 11. 2026 (Ai Lina tv browser): »kazalec je ze zasnovan v tem brskalniku, samo izboljsati ga je treba; naj se
     * uporabi na strani, ki ni prilagojena za TV«. Privzeto je zato kazalec povsod, kjer stran NI narejena za TV; nase strani,
     * YouTube za TV in pregledovalnik PDF ostanejo na puscicah. Uporabnikova zapomnjena izbira za gostitelja ima se vedno
     * prednost (v obe smeri). Prej (9. 11. 2026 15:xx, RTV SLO »kot aplikacija«) so bile puscice privzete, ker je kazalec
     * do roba rabil ~40 pritiskov; to odpravi gibanje po slicicah s pospeskom in drsenjem ob robu ([KazalecGibanje]).
     * [miska] je ohranjen zaradi klicev: stran, ki jo je izbrala miska (Facebook ...), ni za TV, zato je ze zajeta.
     */
    @Suppress("UNUSED_PARAMETER")
    fun privzetoKazalec(jeTelevizor: Boolean, naslov: String, zaTv: Boolean, zapomnjeno: Boolean?, miska: Boolean = false): Boolean {
        if (!jeTelevizor) return false
        if (gostitelj(naslov) == null) return false   // nasa domaca stran (file://, about:), brez gostitelja
        zapomnjeno?.let { return it }
        return !zaTv
    }

    /**
     * Skrivanje navideznega kazalca (Ai Lina tv browser, lastnik 15. 11. 2026, tocki K7 in K8). Prej (11. 11. 2026) se kazalec
     * ni skrival nikoli, ker je rocno vklopljen kazalec po 30 s izginil, brskalnik pa si ga je zapomnil kot vklopljenega. Zdaj se
     * skrije samo NA ZASLONU, nacin kazalca pa ostane (zapomnjena izbira, smerne tipke ga se vedno vodijo):
     *  - po [MIROVANJE_SKRIJ_MS] brez tipke (pri drzani smerni tipki, vlecenju in gibanju se ne);
     *  - takoj, ko se oglasi prava miska (zracna ali USB): dva kazalca hkrati sta zmeda.
     * Smerna tipka ga spet pokaze na isti legi in ga takoj premakne; prvi OK ob skritem kazalcu ga samo pokaze
     * ([tipkaSamoPokaze]): klik na mestu, ki ga uporabnik ne vidi, bi bil nepredviden.
     * Mirovanje je izhodisce, ki ga je treba izmeriti na pravem TV (NEPREVERJENO); vsi casi so parametri.
     */
    const val MIROVANJE_SKRIJ_MS = 5_000L

    /** Stanje skrivanja: [skrit] = kazalec ni narisan; [zadnjaAktivnostMs] = uptime zadnje tipke, gibanja ali klika. */
    data class Skrivanje(val skrit: Boolean = false, val zadnjaAktivnostMs: Long = 0L)

    /** Tipka kazalca (smer, OK): kazalec je viden in mirovanje se zacne znova. */
    @Suppress("UNUSED_PARAMETER")
    fun skrivanjeTipka(s: Skrivanje, zdajMs: Long): Skrivanje = Skrivanje(skrit = false, zadnjaAktivnostMs = zdajMs)

    /** Gibanje, klik ali vlecenje, ki ga vodi tipka: podaljsa mirovanje; skritega kazalca ne pokaze. */
    fun skrivanjeAktivnost(s: Skrivanje, zdajMs: Long): Skrivanje = if (s.skrit) s else s.copy(zadnjaAktivnostMs = zdajMs)

    /** Prava miska: navidezni kazalec se skrije (tipka daljinca ga spet pokaze). */
    fun skrivanjeMiska(s: Skrivanje): Skrivanje = s.copy(skrit = true)

    /** Casovni preizkus: skrije po mirovanju, razen med drzano smerno tipko in vlecenjem. */
    fun skrivanjeCas(s: Skrivanje, zdajMs: Long, tipkaDrzana: Boolean, vlecem: Boolean): Skrivanje =
        if (!s.skrit && !tipkaDrzana && !vlecem && zdajMs - s.zadnjaAktivnostMs >= MIROVANJE_SKRIJ_MS) s.copy(skrit = true) else s

    /**
     * Cez koliko ms naj tece naslednji casovni preizkus: do izteka mirovanja; ce je rok ze potekel (drzana tipka, vlecenje) ali se je
     * ura vrnila, cel rok - brez zanke na 1 ms (2 GB TV: en sam odlozen preizkus, v mirovanju ni casovnika).
     */
    fun doSkritjaMs(s: Skrivanje, zdajMs: Long): Long {
        val minilo = zdajMs - s.zadnjaAktivnostMs
        return if (minilo in 0 until MIROVANJE_SKRIJ_MS) MIROVANJE_SKRIJ_MS - minilo else MIROVANJE_SKRIJ_MS
    }

    /** Prvi OK ob skritem kazalcu ga samo pokaze (brez klika in brez vlecenja); smerna tipka ga pokaze in takoj premakne. */
    fun tipkaSamoPokaze(bilSkrit: Boolean, jeOk: Boolean): Boolean = bilSkrit && jeOk

    /**
     * Slepi pregled TV3 (N5): prvi OK, ki skritega kazalca samo pokaze ([tipkaSamoPokaze]), je pogoltnjen ob SPUSTU tipke
     * (keydown ni sel v stran) - stran ne sme dobiti samega keyup Enter (oddaja iskanja ob onkeyup bi ukrepala ob dejanju,
     * ki je uporabniku pomenilo samo »pokazi kazalec«). Klik ob spustu ima prednost pred pogoltnjenim spustom.
     * [pogoltniSpust] postavi samo veja »samo pokaze«; spust OK po drugih poteh (drzanje, vlecenje) ostane kot je bil.
     */
    enum class OkSpust { KLIK, POGOLTNI, PREPUSTI }

    fun okSpust(cakaKlik: Boolean, pogoltniSpust: Boolean): OkSpust = when {
        cakaKlik -> OkSpust.KLIK
        pogoltniSpust -> OkSpust.POGOLTNI
        else -> OkSpust.PREPUSTI
    }

    /**
     * Slepi pregled TV3 (N6): prava miska skrije navidezni kazalec, razen ko je kazalec ze skrit, ne vidno vklopljen, med
     * vlecenjem in med pritiskom OK, ki caka na klik ([cakaKlik]) - sicer bi spust OK kliknil na legi, ki je uporabnik ne vidi
     * (ravno to, kar K7/K8 prepoveduje). Skrije se ob naslednjem dogodku miske po spustu (lebdenje ~60 na sekundo).
     */
    fun smeSkritiZaMisko(vidno: Boolean, vlecem: Boolean, cakaKlik: Boolean, skrit: Boolean): Boolean =
        vidno && !vlecem && !cakaKlik && !skrit

    /**
     * Slepi pregled TV3 (I4): kam postaviti kazalec ob prvi postavitvi pogleda. Samo PRVIC (ko legi se nikoli nismo postavili) in samo,
     * ce je lega se (0, 0): v sredino. Prej se je kazalec, ki ga je uporabnik pripeljal tocno v kot (0, 0), ob vsaki spremembi
     * velikosti pogleda (vrtenje, vrstica) prestavil v sredino. Vrne null = lege ne spreminjaj.
     */
    fun zacetnaLega(zePostavljena: Boolean, x: Float, y: Float, sirina: Int, visina: Int): Pair<Float, Float>? =
        if (!zePostavljena && sirina > 0 && visina > 0 && x == 0f && y == 0f) Pair(sirina / 2f, visina / 2f) else null

    /** InputDevice.SOURCE_MOUSE (SOURCE_CLASS_POINTER | 0x2000); dotik na zaslonu (0x1002) nima teh bitov. */
    const val IZVOR_MISKA = 0x2002

    /**
     * Ali dogodek pripada pravi miski: izvor miska in dejanje premik/lebdenje/kolesce/pritisk (MotionEvent.ACTION_DOWN 0, MOVE 2,
     * SCROLL 8, HOVER_MOVE 7, HOVER_ENTER 9, BUTTON_PRESS 11). Odhod miske (HOVER_EXIT) in spust ne skrijeta. Nasi sinteticni dogodki
     * (VirtualPointerView) gredo neposredno v pogled, ne skozi dejavnost, zato kazalec ne skrije samega sebe.
     */
    fun jeDogodekMiske(izvor: Int, dejanje: Int): Boolean =
        (izvor and IZVOR_MISKA) == IZVOR_MISKA && (dejanje == 7 || dejanje == 9 || dejanje == 8 || dejanje == 2 || dejanje == 0 || dejanje == 11)

    /** Premiki kazalca sporocajo strani lebdenje (mouseover/mousemove) v brskalniku in v spletni aplikaciji enako. */
    fun lebdenje(): Boolean = true

    /** Lebdenje najvec vsakih toliko ms med gibanjem in enkrat ob ustavitvi (Ai Lina nacrt B8, 2 GB TV). */
    const val LEBDENJE_MS = 100L

    /** Nadomestni faktor drsenja (tock na zarezo kolesca), ce ga sistem ne pove (ViewConfiguration.scaledVerticalScrollFactor). */
    const val PX_NA_ZAREZO = 64f

    /**
     * Strani za ogled videa po gostitelju: pot, ki pomeni predvajalnik (ne poljubna stran s tako potjo). Pot z koncnim »/« velja kot
     * predpona, sicer je to pot sama ali njene podpoti (/watch, /watch/...; /watchlist NE). Slepi pregled TV3 (N2): po F1 je bil
     * samo YouTube, zato so Netflix, Facebook Watch, Twitch in Dailymotion /watch izgubili bliznjice OK/levo/desno/nazaj.
     * To je bila presoja popravka F1, ne lastnikova odlocitev (STANJE-ZDAJ, zapisnik: nikjer ni zapisano »samo YouTube«), zato se
     * bliznjice obnovijo - z ISTO preverbo gostitelja kot za YouTube (stran na tujem gostitelju jih ne more sprozit).
     * Seznam je razsirljiv po gostitelju; NEPREVERJENO na napravi, ali Netflix v WebView sploh deluje.
     */
    private class OgledGostitelji(val domene: List<String>, val poti: List<String>)
    private val OGLED = listOf(
        OgledGostitelji(listOf("youtube.com", "youtube-nocookie.com"), listOf("/watch", "/shorts/", "/embed/")),
        OgledGostitelji(listOf("netflix.com", "facebook.com", "twitch.tv", "dailymotion.com"), listOf("/watch")),
    )

    /**
     * Slepi pregled TV2 (F1, srednja): stran je lahko BREZ tipke preklopila nacin kazalec/puscice z history.replaceState('#/watch'),
     * ker je prejsnje pravilo iskalo "/watch" v CELEM naslovu (tudi v poizvedbi, sidru in poti tujega gostitelja). Zdaj: znan
     * gostitelj ([OGLED]) in POT; poizvedba in sidro ne stejeta (YouTube za TV, youtube.com/tv#/watch, ima pot /tv in ni stran
     * za ogled). Pot s segmentom . ali .. ali s kodiranim %2e/%2f ni stran za ogled (fail-safe; Chromium jo ze normalizira).
     */
    fun jeStranOgleda(naslov: String?): Boolean {
        val g = gostitelj(naslov) ?: return false
        val pravila = OGLED.firstOrNull { r -> r.domene.any { g == it || g.endsWith(".$it") } } ?: return false
        val pot = pot(naslov) ?: return false
        if ("%2e" in pot || "%2f" in pot || pot.split('/').any { it == ".." || it == "." }) return false
        return pravila.poti.any { p -> if (p.endsWith("/")) pot.startsWith(p) else pot == p || pot.startsWith("$p/") }
    }

    /**
     * Uporabnikova izbira za stran: izbira za gostitelja velja VEDNO (tudi na strani za ogled); izbira, narejena na strani za ogled
     * ([zapisOgled]), velja samo tam in ima tam prednost pred izbiro za gostitelja. Prej je bil kljuc "gostitelj#ogled" loceno
     * zapisan in izbira gostitelja na strani za ogled ni veljala (slepi pregled TV2, F1).
     */
    fun zapomnjenaZaStran(zapisOgled: Boolean?, zapisGostitelj: Boolean?, ogled: Boolean): Boolean? =
        if (ogled) zapisOgled ?: zapisGostitelj else zapisGostitelj

    /**
     * Ali je stran narejena za daljinec (puscice): stran za ogled, nasa stran, YouTube za TV, pregledovalnik PDF in Googlove strani.
     * Googlove strani imajo lastne tipke za prijavo in iskanje (puscice, GoogleTipke); kazalec jih obide (slepi pregled TV2, F5),
     * zato so privzeto na puscicah; uporabnikova izbira ima se vedno prednost ([privzetoKazalec]).
     */
    fun stranZaTv(ogled: Boolean, nasaStran: Boolean, youtubeZaTv: Boolean, pdf: Boolean, google: Boolean): Boolean =
        ogled || nasaStran || youtubeZaTv || pdf || google

    /** Kljuc, ob katerem se privzeti kazalec preracuna: gostitelj, stran za ogled istega gostitelja loceno. */
    fun kljucStrani(gostitelj: String?, ogled: Boolean): String? = gostitelj?.let { if (ogled) "$it#ogled" else it }

    /**
     * Lastnik 9. 11. 2026 (24ur.com na TV »prevelika, tezko se premikas gor in dol«): pri 960 dp televizorja je vecina strani
     * v postavitvi za tablico (ogromne slike, glava in spodnja vrstica pokrijeta pol zaslona). Na TV dobi vsaka stran sirino
     * [PRIVZETA_SIRINA] kot na racunalniku in se pomanjsa na zaslon; na napravi z dotikom ne. Izrecna sirina (vir Medijskega
     * centra) ostane.
     */
    const val PRIVZETA_SIRINA = 1280

    fun sirinaStrani(izrecna: Int, jeTelevizor: Boolean): Int = when {
        izrecna > 0 -> izrecna
        jeTelevizor -> PRIVZETA_SIRINA
        else -> 0
    }

    /** Strani s svojo postavitvijo za TV ali mobilno identiteto (YouTube za TV, Googlova prijava, PDF) ostanejo kot so. */
    fun brezSirine(dotik: Boolean, youtubeZaTv: Boolean, androidIdentiteta: Boolean, pregledovalnik: Boolean, http: Boolean): Boolean =
        dotik || youtubeZaTv || androidIdentiteta || pregledovalnik || !http

    /** Zacetna povecava: namizna samo za navadne strani; Google (prijava) in izjeme 100, da ne ostane povecava prejsnje strani. */
    fun zacetnaPovecava(namiznaSirina: Int, brezSirine: Boolean, google: Boolean, namizna: Int): Int =
        if (namiznaSirina > 0 && !brezSirine && !google) namizna else 100

    /** Zarez kolesca miske na pritisk ob robu (lastnik 9. 11. 2026, 24ur.com); izmeri na TV po gradnji. */
    const val KORAK_KOLESCA = 3f

    /** Tipka gor gre v naslovno vrstico samo, ko je kazalec na samem vrhu (konica je lahko na robu 0, KazalecOblika.omejiKonico) in stran navzgor ne more. */
    fun vNaslovnoVrstico(kazalecY: Float, stranLahkoGor: Boolean): Boolean = kazalecY <= 10.5f && !stranLahkoGor
}
