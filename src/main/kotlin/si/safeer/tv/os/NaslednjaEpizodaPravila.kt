package si.safeer.tv.os

/**
 * Lastnik 5. 11. 2026: »naslednja epizoda iste serije naj se zacne sama, ko je konec epizode«. Ciste funkcije (preizkus
 * tests/NaslednjaEpizodaPravilaTest.kt). Kartica je v PredvajanjeActivity, izbira toka v GlasbaActivity po isti poti kot
 * »Nadaljuj« (ista pravila in filter, ki jih je izbral uporabnik). Dodatki Stremio ne povedo, kdaj se zacne odjavna
 * spica: kartica se pokaze ob zacetku odjavne, ce ga pozna TheIntroDB ([PreskokPravila]), sicer v zadnjih
 * [KARTICA_PRED_KONCEM_MS]; samodejni zacetek je sele ob naravnem koncu epizode ([zacniZdaj]).
 *
 * Lastnik 14. 11. 2026 (epizoda serije, Usenet): naslednja epizoda se ni zacela. Dogodki TV: ob koncu epizode se je
 * storitev ustavila in zaslon predvajanja se je zaprl 90 ms za tem - priprava torej ni vec tekla in kartice ni bilo:
 * edini poskus (60 s pred koncem, rok 20 s, brez ponovitve) ni dal izida (razlog ni bil zapisan). Zato: priprava 5 min pred koncem ali
 * pri 90 % ([iskati]), rok poskusa po zgornji meji notranjih rokov ([PRIPRAVA_ROK_MS]), ob neuspehu en ponovni poskus
 * ([ponoviti]), izid velja do konca epizode.
 *
 * Lastnik 14. 11. 2026 (drugi primer): uporabnik je med odjavno spico pritisnil Nazaj in naslednjo epizodo zagnal rocno.
 * Zato: Nazaj pri koncu epizode ([odgledanaObNazaj]) jo steje kot odgledano, Medijski center odpre okno serije s fokusom na
 * naslednji epizodi ([fokusPoNazaj]) in OK nanjo predvaja iz ze pripravljenega izida ([pripravljenVelja]). Samodejnega zagona
 * ob Nazaj ni (Nazaj pomeni »ven«).
 */
object NaslednjaEpizodaPravila {
    /** Kartica je vidna vsaj toliko, preden se naslednja epizoda zacne sama (odstevanje kartice, ki se pokaze sele ob koncu). */
    const val ODSTEVANJE_MS = 10_000L

    /**
     * Lastnik 14. 11. 2026: kartica brez znanega zacetka odjavne spice je vidna zadnjih toliko (prej zadnjih 10 s - uporabnik
     * je med odjavno spico odsel z Nazaj, preden se je pokazala).
     */
    const val KARTICA_PRED_KONCEM_MS = 60_000L

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
     * [PreskokPravila.uporabni]), a najpozneje [ODSTEVANJE_MS] pred koncem; brez znane odjavne zadnjih [KARTICA_PRED_KONCEM_MS].
     */
    fun zacetekKartice(trajanjeMs: Long, odjavnaOdMs: Long? = null): Long =
        if (odjavnaOdMs != null && odjavnaOdMs >= 0) minOf(odjavnaOdMs, trajanjeMs - ODSTEVANJE_MS) else trajanjeMs - KARTICA_PRED_KONCEM_MS

    /** Kartica: ob koncu posnetka, od zacetka odjavne spice ali v zadnjih [KARTICA_PRED_KONCEM_MS]. */
    fun pokazi(trajanjeMs: Long, polozajMs: Long, koncano: Boolean, odjavnaOdMs: Long? = null): Boolean =
        koncano || (trajanjeMs >= NAJMANJ_TRAJANJA_MS && polozajMs >= zacetekKartice(trajanjeMs, odjavnaOdMs))

    /**
     * Mesto za zapis napredka (Nadaljuj). Epizoda, ki jo je uporabnik koncal - naslednja se je zacela s kartice ali je pri
     * koncu pritisnil Nazaj ([odgledanaObNazaj]) - steje kot odgledana od [odgledanoOdMs] naprej: zapise se do konca, tudi ce
     * predvajalnik se stoji na tem mestu ali predvaja naprej (zapis vsakih 5 s bi jo sicer vrnil v »Nadaljuj« pri npr. 92 %).
     * Previjanje pred to mesto ali ogled znova oznako razveljavi ([oznakaOdgledanoVelja]). null = ni oznake; brez znanega
     * trajanja ostane trenutno mesto.
     */
    fun polozajZaNapredek(polozajMs: Long, trajanjeMs: Long, odgledanoOdMs: Long?): Long =
        if (odgledanoOdMs != null && trajanjeMs > 0 && oznakaOdgledanoVelja(polozajMs, odgledanoOdMs)) trajanjeMs else polozajMs

    /** Oznaka »odgledano od« velja, dokler predvajanje ni pred tem mestom. */
    fun oznakaOdgledanoVelja(polozajMs: Long, odgledanoOdMs: Long): Boolean = polozajMs >= odgledanoOdMs

    /** Nazaj od tolikega dela epizode naprej (ali po zacetku znane odjavne spice) pomeni, da je uporabnik epizodo koncal. */
    const val ODGLEDANO_OB_NAZAJ_DELEZ = 0.90

    /**
     * Lastnik 14. 11. 2026: Nazaj pri koncu epizode - epizoda steje kot odgledana (prej sele pri 95 %, [OsPravila.odgledano]:
     * Nazaj med odjavno spico pri 90-95 % jo je pustil v »Nadaljuj«). [odjavnaOdMs] = zacetek odjavne iz TheIntroDB ali null.
     */
    fun odgledanaObNazaj(trajanjeMs: Long, polozajMs: Long, odjavnaOdMs: Long? = null): Boolean =
        trajanjeMs >= NAJMANJ_TRAJANJA_MS && (polozajMs >= (trajanjeMs * ODGLEDANO_OB_NAZAJ_DELEZ).toLong() ||
            (odjavnaOdMs != null && odjavnaOdMs >= 0 && polozajMs >= odjavnaOdMs))

    /**
     * Epizoda, na katero gre fokus v oknu serije po Nazaj pri koncu epizode ([sezona], [epizoda]): naslednja med PRIKAZANIMI
     * ([prikazane] = sezona to epizoda; skrite niso na voljo). null = naslednje ni - ne zgodi se nic novega.
     */
    fun fokusPoNazaj(prikazane: List<Pair<Int, Int>>, sezona: Int, epizoda: Int): Pair<Int, Int>? =
        OsPravila.naslednjaEpizoda(prikazane, sezona, epizoda)

    /**
     * Zahteva zaslona predvajanja »Nazaj pri koncu epizode« velja toliko: Medijski center jo porabi ob vrnitvi; seznam epizod,
     * ki pride pozneje (pocasen dodatek), okna ne odpre vec - uporabnik je medtem ze drugje.
     */
    const val NAZAJ_ZAHTEVA_VELJA_MS = 5_000L

    fun zahtevaNazajVelja(zahtevaMs: Long, zdajMs: Long): Boolean = zdajMs - zahtevaMs in 0..NAZAJ_ZAHTEVA_VELJA_MS

    /**
     * CodeRabbit in slepi pregled 14. 11. 2026 (N9): okno serije po Nazaj se odpre samo, ce uporabnik v Medijskem centru od
     * zahteve ni pritisnil tipke ali se dotaknil zaslona ([zadnjaTipkaMs], uptime) - sicer je ze drugje (drug razdelek).
     */
    fun oknoPoNazajVelja(zahtevaMs: Long, zdajMs: Long, zadnjaTipkaMs: Long): Boolean =
        zahtevaNazajVelja(zahtevaMs, zdajMs) && zadnjaTipkaMs <= zahtevaMs

    /**
     * Pripravljen izid naslednje epizode (izbrani in preverjeni tokovi) velja za ISTO epizodo najvec toliko od priprave:
     * priprava je do ~10 min pred koncem epizode in kartica ga uporabi ob koncu; po Nazaj se uporabnik odloci v nekaj minutah.
     * Starejsi izid se zavrze in klik isce znova (povezave debrida in dodatkov zastarajo; iskanje Useneta je 30 min v
     * predpomnilniku, zato novo iskanje ne porabi kvote indeksatorja).
     */
    const val PRIPRAVLJEN_VELJA_MS = 15 * 60_000L

    /** Kljuc epizode serije za primerjavo pripravljenega izida z izbrano epizodo (id serije brez oznake toka za »#«). */
    fun kljucEpizode(idSerije: String, sezona: Int, epizoda: Int): String = idSerije.substringBefore('#') + "|S" + sezona + "E" + epizoda

    fun pripravljenVelja(kljucPripravljene: String?, kljucIzbrane: String, pripravljenoMs: Long, zdajMs: Long): Boolean =
        kljucPripravljene != null && kljucPripravljene == kljucIzbrane && izidSvez(pripravljenoMs, zdajMs)

    /** Slepi pregled 14. 11. 2026 (N2): izid za kartico velja enako dolgo kot v oknu serije; starejsi se poisce znova. */
    fun izidSvez(pripravljenoMs: Long, zdajMs: Long): Boolean = zdajMs - pripravljenoMs in 0..PRIPRAVLJEN_VELJA_MS

    /** Tipke daljinca, ko je kartica naslednje epizode odprta. */
    enum class TipkaKartice { OK, LEVO_DESNO, GOR_DOL, NAZAJ }

    /** Kaj naredi tipka pri odprti kartici. */
    enum class OdzivKartice { GUMBOM, ZACNI, PREDVAJALNIKU, POGOLTNI, NAZAJ_VEN }

    /**
     * Slepi pregled 14. 11. 2026 (S1, S2) in lastnikovi pravili (»OK na kartici zacne takoj, Nazaj zapre kartico brez
     * zagona«; »Nazaj pri >= 90 % = odgledano + okno serije«). Kartica je zdaj vidna ze minuto ali vso odjavno spico, zato
     * pred koncem ([konec] = false) ne sme vzeti daljinca: levo/desno previjata (prizor po odjavni, vrnitev), pavza je na
     * tipki predvajaj/pavza, OK zacne naslednjo epizodo (lastnikovo pravilo). Nazaj je vedno en pritisk »ven«: zapre
     * kartico IN naredi, kar Nazaj naredi na zaslonu predvajanja (oznaka odgledano, okno serije s fokusom) - prej je pred
     * koncem le skril kartico in je bil potreben drugi Nazaj. Po koncu (slike ni vec) gresta levo/desno in OK gumboma.
     */
    fun odzivKartice(t: TipkaKartice, konec: Boolean): OdzivKartice = when (t) {
        TipkaKartice.NAZAJ -> OdzivKartice.NAZAJ_VEN
        TipkaKartice.OK -> if (konec) OdzivKartice.GUMBOM else OdzivKartice.ZACNI
        TipkaKartice.LEVO_DESNO -> if (konec) OdzivKartice.GUMBOM else OdzivKartice.PREDVAJALNIKU
        TipkaKartice.GOR_DOL -> OdzivKartice.POGOLTNI
    }

    /** Vrstica dnevnika ob Nazaj pri koncu epizode: »naslednja epizoda: Nazaj pri 93 % -> fokus S1E2, pripravljen=da«. */
    fun dnevnikNazaj(polozajMs: Long, trajanjeMs: Long, fokus: Pair<Int, Int>?, pripravljen: Boolean): String {
        val odstotek = if (trajanjeMs > 0) (polozajMs * 100 / trajanjeMs).coerceIn(0L, 100L) else 0L
        return "naslednja epizoda: Nazaj pri $odstotek % -> " + (if (fokus == null) "ni naslednje epizode"
            else "fokus S${fokus.first}E${fokus.second}, pripravljen=${if (pripravljen) "da" else "ne"}")
    }

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

    /** Rok poskusa na zaslonu predvajanja: [PRIPRAVA_ROK_MS] in rezerva za vrsto delavca Medijskega centra. */
    const val ROK_POSKUSA_MS = PRIPRAVA_ROK_MS + 5_000L

    /**
     * Slepi pregled 14. 11. 2026 (V1): po koncu epizode je zaslon na izid cakal le [CAKAJ_SEZNAM_MS] (20 s), poskus pa
     * traja do [ROK_POSKUSA_MS] - izid drugega poskusa (ali prvega, zacetega tik pred koncem) je prisel na zaprt zaslon in
     * kartice ni bilo. Zdaj zaslon caka, dokler poskus, ki tece ([poskusOdMs], uptime zacetka; null = ne tece), ne doseze
     * roka, in vsaj [CAKAJ_SEZNAM_MS].
     */
    fun cakajPoKoncuMs(zdajMs: Long, poskusOdMs: Long?): Long =
        if (poskusOdMs == null) CAKAJ_SEZNAM_MS else maxOf(CAKAJ_SEZNAM_MS, poskusOdMs + ROK_POSKUSA_MS + 1_000L - zdajMs)

    /** Iskanje podnapisov v pripravi naslednje epizode najvec toliko (podnapise najde se predvajalnik sam, ko slika tece). */
    const val PODNAPISI_V_PRIPRAVI_MS = 15_000L
    const val PODNAPISI_V_PRIPRAVI_NAJMANJ_MS = 2_000L

    /**
     * Slepi pregled 14. 11. 2026 (N3): podnapisi so se iskali PO preverbi roka in brez meje - uspesna priprava je lahko
     * presegla rok zaslona predvajanja in bila zavrzena kot ROK. Cas za podnapise je preostanek [PRIPRAVA_ROK_MS] (najvec
     * [PODNAPISI_V_PRIPRAVI_MS]); manj kot [PODNAPISI_V_PRIPRAVI_NAJMANJ_MS] = ne iscemo (0).
     */
    fun rokPodnapisovVPripravi(zacetekMs: Long, zdajMs: Long): Long {
        val ostane = (zacetekMs + PRIPRAVA_ROK_MS - zdajMs).coerceIn(0L, PODNAPISI_V_PRIPRAVI_MS)
        return if (ostane < PODNAPISI_V_PRIPRAVI_NAJMANJ_MS) 0L else ostane
    }

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

    /**
     * Zacni ponovni poskus: [PONOVI_PO_MS] po neuspehu ali ob koncu epizode, ce je prej. Slepi pregled 14. 11. 2026 (N2, N4):
     * samo v oknu priprave ([vOknu], [iskati]) - po previjanju na sredino ne; ob koncu takoj samo, ce razlog ni kvota
     * indeksatorja ([ponovitiObKoncu]: omejevalnik steje na minuto, takojsen poskus bi jo spet zadel).
     */
    fun zdajPonoviti(neuspehMs: Long, zdajMs: Long, koncano: Boolean, vOknu: Boolean = true, razlog: Razlog = Razlog.NAPAKA): Boolean =
        (koncano && ponovitiObKoncu(razlog)) || ((koncano || vOknu) && zdajMs - neuspehMs >= PONOVI_PO_MS)

    /** Po koncu epizode (storitev ustavljena, zaslon caka) takoj ponovimo samo, ce razlog ni kvota indeksatorja. */
    fun ponovitiObKoncu(razlog: Razlog): Boolean = razlog != Razlog.KVOTA

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

    /**
     * Kartica, ki se brez odziva zapre (zaslon se sme ugasniti): vprasanje in ponujena epizoda, obe sele PO KONCU predvajanja.
     * 14. 11. 2026: kartica je vidna ze zadnjih 60 s ali od zacetka odjavne - rok vprasanja od odprtja kartice bi zaslon
     * predvajanja zaprl pred koncem epizode (prej se je vprasanje pokazalo 10 s pred koncem).
     */
    fun rokBrezOdziva(vrsta: Int, konec: Boolean): Boolean = konec && (vrsta == VPRASAJ || vrsta == PONUDI)

    /** Stevec samodejnih epizod: samodejna +1, izbira uporabnika (gumb, tipka) ga postavi na nic. */
    fun zaporednihPo(prej: Int, samodejno: Boolean): Int = if (samodejno) prej + 1 else 0

    /** Odsteto po enem koraku; med pavzo odstevanje stoji. */
    fun odsteto(prejMs: Long, korakMs: Long, pavza: Boolean): Long = if (pavza) prejMs else prejMs + korakMs

    /** Znotraj tolikega preostanka velja epizoda za koncano (en tik): predvajalnik pride do konca pred naslednjim tikom. */
    const val KONEC_BLIZU_MS = 1_000L

    /**
     * Sekund do samodejnega zacetka (zaokrozeno navzgor, za napis »cez N s«): vecje od preostanka predvajanja
     * [preostaloPredvajanjaMs] (0 = konec) in preostanka odstevanja kartice.
     */
    fun preostaloSekund(odstetoMs: Long, preostaloPredvajanjaMs: Long): Int =
        ((maxOf((ODSTEVANJE_MS - odstetoMs).coerceIn(0L, ODSTEVANJE_MS), preostaloPredvajanjaMs.coerceAtLeast(0L)) + 999L) / 1000L).toInt()

    /**
     * Lastnik 14. 11. 2026: kartica je vidna prej (zadnjih 60 s ali od zacetka odjavne), samodejni zacetek pa ostane sele ob
     * NARAVNEM KONCU epizode: kartica je bila vidna vsaj [ODSTEVANJE_MS] (pavza ne steje) IN epizoda je koncana ([konec])
     * ali do konca manjka najvec [KONEC_BLIZU_MS]. Zastoj pred koncem zato ne preskoci preostanka (prej je odstevanje teklo
     * tudi med zastojem). Med pavzo ([pavza]) se ne zacne nikoli - tudi ne v zadnji sekundi (uporabnik bere ali je odsel).
     */
    fun zacniZdaj(odstetoMs: Long, preostaloPredvajanjaMs: Long, konec: Boolean, pavza: Boolean = false): Boolean =
        !pavza && odstetoMs >= ODSTEVANJE_MS && (konec || preostaloPredvajanjaMs <= KONEC_BLIZU_MS)

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
