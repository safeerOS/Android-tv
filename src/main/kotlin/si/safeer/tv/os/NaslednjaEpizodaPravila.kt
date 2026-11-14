package si.safeer.tv.os

/**
 * Lastnik 5. 11. 2026: »naslednja epizoda iste serije naj se zacne sama, ko je konec epizode«. Ciste funkcije (preizkus
 * tests/NaslednjaEpizodaPravilaTest.kt). Kartica je v PredvajanjeActivity, izbira toka v GlasbaActivity po isti poti kot
 * »Nadaljuj« (ista pravila in filter, ki jih je izbral uporabnik). Dodatki Stremio ne povedo, kdaj se zacne odjavna
 * spica: kartica se pokaze ob zacetku odjavne, ce ga pozna TheIntroDB ([PreskokPravila]), sicer v zadnjih [ODSTEVANJE_MS].
 *
 * Lastnik 14. 11. 2026 (Breaking Bad S01E02, Usenet): naslednja epizoda se ni zacela. Dogodki TV: konec epizode 17:01:14,
 * storitev se je ustavila in zaslon predvajanja se je zaprl 90 ms za tem - priprava torej ni vec tekla in kartice ni bilo:
 * edini poskus (60 s pred koncem, rok 20 s, brez ponovitve) ni dal izida (razlog ni bil zapisan). Zato: priprava 5 min pred koncem ali
 * pri 90 % ([iskati]), rok poskusa po zgornji meji notranjih rokov ([PRIPRAVA_ROK_MS]), ob neuspehu en ponovni poskus
 * ([ponoviti]), izid velja do konca epizode.
 */
object NaslednjaEpizodaPravila {
    /** Kartica odsteva toliko, preden se naslednja epizoda zacne sama. */
    const val ODSTEVANJE_MS = 10_000L

    /**
     * Lastnik 14. 11. 2026: priprava naslednje epizode se zacne dovolj zgodaj, da uporabnik ne caka - 5 min pred koncem ali
     * pri 90 % posnetka, kar je prej (prej 60 s pred koncem: en sam poskus z rokom 20 s ni dal izida in kartice ni bilo).
     */
    const val PRIPRAVA_PRED_KONCEM_MS = 5 * 60_000L
    const val PRIPRAVA_DELEZ = 0.90

    /** Krajsi posnetek (napovednik, povzetek) kartice pred koncem nima - samo ob koncu. */
    const val NAJMANJ_TRAJANJA_MS = 5 * 60_000L

    /** Po koncu epizode zaslon na seznam epizod caka najvec toliko, nato se zapre kot prej. */
    const val CAKAJ_SEZNAM_MS = 20_000L

    /**
     * Odlocitev 5. 11. 2026: po toliko samodejnih epizodah zapored brez tipke daljinca vprasamo »Se gledas?« in ne
     * odstevamo (TV ne predvaja celo noc; tako delajo tudi drugi predvajalniki).
     */
    const val NAJVEC_ZAPOREDNIH = 3

    /** Vrsta kartice. */
    const val NIC = 0
    const val ODSTEVAJ = 1
    const val VPRASAJ = 2
    /** Lastnik 8. 11. 2026: samodejna naslednja epizoda izklopljena - kartica ponudi epizodo brez odstevanja. */
    const val PONUDI = 3
    /** Nastavitev Medijskega centra (privzeto vklopljeno, kot doslej). */
    const val KLJUC_SAMODEJNO_EPIZODA = "samodejno_naslednja_epizoda"

    /** Posnetek je epizoda serije (video, ne v zivo, ima stevilko epizode). */
    fun jeEpizoda(video: Boolean, vZivo: Boolean, epizoda: Int): Boolean = video && !vZivo && epizoda >= 1

    /** Preostanek posnetka, od katerega naprej pripravljamo: vecje od [PRIPRAVA_PRED_KONCEM_MS] in 10 % trajanja. */
    fun pragPriprave(trajanjeMs: Long): Long =
        maxOf(PRIPRAVA_PRED_KONCEM_MS, trajanjeMs - (trajanjeMs * PRIPRAVA_DELEZ).toLong())

    /** Cas za pripravo naslednje epizode (dovolj dolg posnetek, preostanek najvec [pragPriprave]). */
    fun iskati(trajanjeMs: Long, polozajMs: Long): Boolean =
        trajanjeMs >= NAJMANJ_TRAJANJA_MS && trajanjeMs - polozajMs in 0..pragPriprave(trajanjeMs)

    /**
     * Polozaj, od katerega je kartica vidna: zacetek odjavne spice [odjavnaOdMs] (TheIntroDB, ze preverjen s
     * [PreskokPravila.uporabni]), ce je pred zadnjimi [ODSTEVANJE_MS], sicer zadnjih [ODSTEVANJE_MS] posnetka.
     */
    fun zacetekKartice(trajanjeMs: Long, odjavnaOdMs: Long? = null): Long {
        val zadnjih = trajanjeMs - ODSTEVANJE_MS
        return if (odjavnaOdMs != null && odjavnaOdMs in 0 until zadnjih) odjavnaOdMs else zadnjih
    }

    /** Kartica: ob koncu posnetka, od zacetka odjavne spice ali v zadnjih [ODSTEVANJE_MS]. */
    fun pokazi(trajanjeMs: Long, polozajMs: Long, koncano: Boolean, odjavnaOdMs: Long? = null): Boolean =
        koncano || (trajanjeMs >= NAJMANJ_TRAJANJA_MS && polozajMs >= zacetekKartice(trajanjeMs, odjavnaOdMs))

    /**
     * Mesto, ki ga zapisemo za Nadaljuj, ko se naslednja epizoda zacne s kartice: kartica je lahko ze ob zacetku odjavne
     * (TheIntroDB dovoli od 75 % naprej), zato epizodo zapisemo kot odgledano (do konca), sicer bi ostala v »Nadaljuj«
     * pri npr. 80 %. Brez znanega trajanja ostane trenutno mesto.
     */
    fun polozajZaNapredek(polozajMs: Long, trajanjeMs: Long, naslednjaZacela: Boolean): Long =
        if (naslednjaZacela && trajanjeMs > 0) trajanjeMs else polozajMs

    /**
     * Napredek sproti (tik, onStop): med pripravo novega posnetka je trenutna se stara epizoda (ustavljena pri npr. 80 %) -
     * njen zapis bi pobrisal oznako »odgledano« iz [polozajZaNapredek] in jo vrnil v Nadaljuj. Takrat ne zapisujemo.
     */
    fun zapisiNapredekZdaj(pripravljamNovega: Boolean, naslednjaZacela: Boolean): Boolean = naslednjaZacela || !pripravljamNovega

    /** Zacetek odjavne spice iz odsekov TheIntroDB (samo smiselni odseki, [PreskokPravila.uporabni]); null = ne vemo. */
    fun odjavnaOd(odseki: List<PreskokPravila.Odsek>, trajanjeMs: Long): Long? =
        PreskokPravila.uporabni(odseki, trajanjeMs).firstOrNull { it.vrsta == PreskokPravila.Vrsta.ODJAVNA }?.odMs

    /**
     * Rok enega poskusa priprave = zgornja meja notranjih rokov poti (14. 11. 2026, iz kode): seznam epizod (meta) 12 s,
     * tokovi z Usenetom do 50,5 s (3 iskanja po 13,5 s ob meji indeksatorja in 2 premora po 5 s, UsenetIskanjePravila),
     * preverba TorBox do 10 s = 72,5 s. Prej 20 s: ze eno pocasno iskanje Useneta in seznam epizod sta ga presegla, izid
     * je bil zavrzen in ponovitve ni bilo. Izmerjeni p95 na TV se dopise, ko bo izmerjen (dnevnik »naslednja epizoda: poskus«).
     */
    const val PRIPRAVA_ROK_MS = 75_000L

    fun vRoku(zacetekMs: Long, zdajMs: Long): Boolean = zdajMs - zacetekMs in 0..PRIPRAVA_ROK_MS

    /** Zakaj priprava ni dala epizode s potrjenim tokom; od tega je odvisen ponovni poskus ([ponoviti]). */
    enum class Razlog { OK, NI_SERIJE, NI_EPIZODE, KVOTA, NI_TOKA, ROK, NAPAKA, NI_MEDIJSKEGA_CENTRA }

    /** Ob neuspehu en ponovni poskus (lastnik 14. 11. 2026); skupaj najvec toliko poskusov na epizodo. */
    const val NAJVEC_POSKUSOV = 2

    /** Ponovni poskus po tolikem premoru (omejevalnik indeksatorja steje na minuto) ali takoj ob koncu epizode. */
    const val PONOVI_PO_MS = 60_000L

    /**
     * Ponovimo samo, kar se lahko spremeni: kvota indeksatorja (omejevalnik na minuto), rok, napaka omrezja in »ni
     * potrjenega toka« (dodatek ali TorBox ni odgovoril). Ni naslednje epizode, ni serija ali ni Medijskega centra - ne.
     * Iskanje Useneta drugic ne porabi kvote: izdaje so 30 min v predpomnilniku ([Usenet.isci]).
     */
    fun ponoviti(razlog: Razlog, poskusov: Int): Boolean =
        poskusov < NAJVEC_POSKUSOV && (razlog == Razlog.KVOTA || razlog == Razlog.ROK || razlog == Razlog.NAPAKA || razlog == Razlog.NI_TOKA)

    /** Zacni ponovni poskus: [PONOVI_PO_MS] po neuspehu ali ob koncu epizode, ce je prej. */
    fun zdajPonoviti(neuspehMs: Long, zdajMs: Long, koncano: Boolean): Boolean = koncano || zdajMs - neuspehMs >= PONOVI_PO_MS

    /**
     * Vodja 6. 11. 2026 (brez odprtih virov): sme predpriprava Usenet tudi odpreti? TorBox (pripravljeno pri TorBoxu, CDN) in
     * pomocnik kroga ne drzita nicesar na tej napravi. Lasten pogon odpre sejo (streznik 127.0.0.1, bazen NNTP, mesto v meji
     * racuna) in UsenetLastni.predvajaj pred tem zapre sejo, ki se igra - zato ga predpriprava nikoli ne odpre: ni seje, ki
     * bi jo bilo treba zapreti ob preklicu, Nazaj, previjanju, poznem izidu ali drugem filmu. Sejo odpre sele zagon.
     */
    fun usenetOdpriVPredpripravi(lastniPogon: Boolean): Boolean = !lastniPogon

    /**
     * Vodja 6. 11. 2026 (lastnik: pokazemo samo, kar si uporabnik res lahko ogleda): tok naslednje epizode je potrjen, ce je
     * pripravljen Usenet ([usenetPripravljen], po filtru in pomocniku kroga) ali - kadar uporabnik ni izbral samo Useneta -
     * torrent, ki ga ima debrid ze pripravljenega (⚡), ali neposredna povezava. Torrent, ki bi ga debrid sele prenasal, ali
     * roj nista potrjena.
     */
    fun tokPotrjen(usenetPripravljen: Boolean, samoUsenet: Boolean, torrentovPripravljenih: Int, neposrednih: Int): Boolean =
        usenetPripravljen || (!samoUsenet && (torrentovPripravljenih > 0 || neposrednih > 0))

    /** Odstevanje ali vprasanje »Se gledas?« po [NAJVEC_ZAPOREDNIH] samodejnih epizodah; brez potrjenega toka kartice ni. */
    fun vrsta(zaporednih: Int, tokPotrjen: Boolean, samodejno: Boolean = true): Int = when {
        !tokPotrjen -> NIC
        !samodejno -> PONUDI
        zaporednih >= NAJVEC_ZAPOREDNIH -> VPRASAJ
        else -> ODSTEVAJ
    }

    /** Kartica, ki se brez odziva zapre (zaslon se sme ugasniti): vprasanje vedno, ponujena epizoda po koncu predvajanja. */
    fun rokBrezOdziva(vrsta: Int, konec: Boolean): Boolean = vrsta == VPRASAJ || (vrsta == PONUDI && konec)

    /** Stevec samodejnih epizod: samodejna +1, izbira uporabnika (gumb, tipka) ga postavi na nic. */
    fun zaporednihPo(prej: Int, samodejno: Boolean): Int = if (samodejno) prej + 1 else 0

    /** Odsteto po enem koraku; med pavzo odstevanje stoji. */
    fun odsteto(prejMs: Long, korakMs: Long, pavza: Boolean): Long = if (pavza) prejMs else prejMs + korakMs

    /** Sekund do samodejnega zacetka (zaokrozeno navzgor, za napis »cez 10 s«). */
    fun preostaloSekund(odstetoMs: Long): Int = (((ODSTEVANJE_MS - odstetoMs).coerceIn(0L, ODSTEVANJE_MS) + 999L) / 1000L).toInt()

    fun zacniZdaj(odstetoMs: Long): Boolean = odstetoMs >= ODSTEVANJE_MS

    /**
     * CodeRabbit 6. 11. 2026: tik, ki je kartico sele odprl, je v istem klicu ze odstel sekundo - prvi napis je bil
     * »cez 9 s« in epizoda se je zacela po 9 s. Odsteva samo kartica, ki je bila odprta ze PRED tem klicem.
     */
    fun odstejZdaj(odstej: Boolean, odprtaPrej: Boolean, odprtaZdaj: Boolean): Boolean = odstej && odprtaPrej && odprtaZdaj

    /**
     * Pregled 6. 11. 2026 (E1): kartica »Se gledas?« brez odziva je ostala vso noc z vklopljenim zaslonom (KEEP_SCREEN_ON).
     * Po toliko casa brez tipke ali dotika zaslon predvajanja sprosti zaslon in se zapre kot ob koncu epizode.
     */
    const val VPRASANJE_ROK_MS = 60_000L

    /** [odMs] = odprtje vprasanja ali zadnja tipka/dotik med njim (uptime). */
    fun vprasanjeBrezOdziva(odMs: Long, zdajMs: Long): Boolean = zdajMs - odMs >= VPRASANJE_ROK_MS

    /** »S1E2 Ime« za kartico. */
    fun oznaka(sezona: Int, epizoda: Int, ime: String): String =
        "S${sezona}E$epizoda" + (if (ime.isNotBlank()) " " + ime.trim() else "")

    /** Naslov nove epizode v isti obliki kot seznam epizod in »Nadaljuj« (»Serija · S1E2 · ime«). */
    fun naslovEpizode(naslovZdaj: String, sezona: Int, epizoda: Int, ime: String): String =
        OsPravila.naslovBrezEpizode(naslovZdaj) + " · S${sezona}E$epizoda" + (if (ime.isNotBlank()) " · " + ime.trim() else "")
}
