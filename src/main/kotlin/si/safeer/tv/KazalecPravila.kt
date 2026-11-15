package si.safeer.tv

/**
 * Lastnik 9. 11. 2026 (24ur.com): stran, ki ni narejena za TV, naj daljinec sam preklopi v kazalec (miska). Izbira lastnika:
 * privzeto kazalec, izjeme so strani za TV (nase strani, YouTube za TV, pregledovalnik PDF). Rocni preklop (rdeca/INFO,
 * gumb kazalca, drzan OK) si zapomni brskalnik in spletna aplikacija za gostitelja (en vir) - naslednjic stran odpre tako, kot jo je uporabnik zelel.
 */
object KazalecPravila {
    /** Gostitelj http(s) naslova (malo, brez pike na koncu) ali null. */
    fun gostitelj(naslov: String?): String? = try {
        val u = java.net.URI((naslov ?: "").trim())
        if (!u.scheme.equals("https", true) && !u.scheme.equals("http", true)) null
        else u.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }

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

    /** Gostitelji, na katerih pot /watch, /shorts/ in /embed/ pomeni stran za ogled videa (ne poljubna stran s tako potjo). */
    private val OGLED_GOSTITELJI = listOf("youtube.com", "youtube-nocookie.com")

    /**
     * Slepi pregled TV2 (F1, srednja): stran je lahko BREZ tipke preklopila nacin kazalec/puscice z history.replaceState('#/watch'),
     * ker je prejsnje pravilo iskalo "/watch" v CELEM naslovu (tudi v poizvedbi, sidru in poti tujega gostitelja). Zdaj: znan
     * gostitelj (youtube.com, youtube-nocookie.com in poddomene) in POT /watch, /shorts/..., /embed/...; poizvedba in sidro ne
     * stejeta (YouTube za TV, youtube.com/tv#/watch, ima pot /tv in ni stran za ogled).
     */
    fun jeStranOgleda(naslov: String?): Boolean {
        val g = gostitelj(naslov) ?: return false
        if (OGLED_GOSTITELJI.none { g == it || g.endsWith(".$it") }) return false
        val pot = try { java.net.URI((naslov ?: "").trim()).path?.lowercase() } catch (_: Exception) { null } ?: return false
        return pot == "/watch" || pot.startsWith("/watch/") || pot.startsWith("/shorts/") || pot.startsWith("/embed/")
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
