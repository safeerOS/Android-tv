package si.safeer.tv.os

/**
 * Lastnik 5. 11. 2026: »naslednja epizoda iste serije naj se zacne sama, ko je konec epizode«. Ciste funkcije (preizkus
 * tests/NaslednjaEpizodaPravilaTest.kt). Kartica je v PredvajanjeActivity, izbira toka v GlasbaActivity po isti poti kot
 * »Nadaljuj« (ista pravila in filter, ki jih je izbral uporabnik). Dodatki Stremio ne povedo, kdaj se zacne odjavna
 * spica, zato se kartica pokaze v zadnjih [ODSTEVANJE_MS] posnetka oziroma ob koncu.
 */
object NaslednjaEpizodaPravila {
    /** Kartica odsteva toliko, preden se naslednja epizoda zacne sama. */
    const val ODSTEVANJE_MS = 10_000L

    /** Seznam epizod vprasamo dodatek minuto pred koncem: ob koncu je kartica takoj pripravljena. */
    const val ISCI_PRED_KONCEM_MS = 60_000L

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

    /** Cas, da dodatek vprasamo za seznam epizod (zadnja minuta dovolj dolgega posnetka). */
    fun iskati(trajanjeMs: Long, polozajMs: Long): Boolean =
        trajanjeMs >= NAJMANJ_TRAJANJA_MS && trajanjeMs - polozajMs in 0..ISCI_PRED_KONCEM_MS

    /** Kartica: ob koncu posnetka ali v njegovih zadnjih [ODSTEVANJE_MS]. */
    fun pokazi(trajanjeMs: Long, polozajMs: Long, koncano: Boolean): Boolean =
        koncano || (trajanjeMs >= NAJMANJ_TRAJANJA_MS && trajanjeMs - polozajMs <= ODSTEVANJE_MS)

    /** Predpriprava naslednje epizode (seznam, tokovi, izbira in preverba toka) ima toliko casa; pozen izid ne velja. */
    const val PRIPRAVA_ROK_MS = 20_000L

    fun vRoku(zacetekMs: Long, zdajMs: Long): Boolean = zdajMs - zacetekMs in 0..PRIPRAVA_ROK_MS

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
