package si.safeer.tv.os

/**
 * Naloga 117: predvajanje na drugi napravi v Safeer Linku se upravlja kot lokalno (premor, naprej, nazaj, cas, glasnost,
 * vrsta) - ideja kot Spotify Connect, koda nasa. Ciste odlocitve brez Androida in brez JSON (preizkus:
 * tests/OddaljenoPravilaTest.kt); posiljanje in izvedba sta v [OddaljenoPredvajanje].
 *
 * Ukaz gre kot `control.command` z dejanjem [DEJANJE] in parametrom `action`; odgovor je vedno stanje predvajanja na cilju,
 * zato daljinec po vsakem ukazu takoj vidi, kaj se je zgodilo.
 */
object OddaljenoPravila {
    const val DEJANJE = "play.control"

    const val STANJE = "state"
    const val PREMOR = "pause"
    const val NADALJUJ = "resume"
    const val PREKLOPI = "toggle"
    const val NASLEDNJA = "next"
    const val PREJSNJA = "previous"
    const val SKOCI = "seek"
    const val GLASNOST = "volume"
    const val IZ_VRSTE = "queue.play"

    val UKAZI = setOf(STANJE, PREMOR, NADALJUJ, PREKLOPI, NASLEDNJA, PREJSNJA, SKOCI, GLASNOST, IZ_VRSTE)

    /** Najvec skladb vrste v enem odgovoru (okno okoli trenutne). */
    const val NAJVEC_V_VRSTI = 50
    /** Korak glasnosti (odstotki) za »Glasneje« / »Tisje«. */
    const val KORAK_GLASNOSTI = 5

    /**
     * Preverjen ukaz. [ms] za SKOCI, [glasnost] (0-100) ali [korak] (-100..100) za GLASNOST, [indeks] za IZ_VRSTE.
     * Za ostale ukaze so polja 0 / -1.
     */
    data class Ukaz(val vrsta: String, val ms: Long = 0L, val glasnost: Int = -1, val korak: Int = 0, val indeks: Int = -1)

    /**
     * Ukaz iz parametrov (cilj ga dobi od druge naprave, zato vse preveri): neznano dejanje, skok na negativen cas,
     * glasnost brez vrednosti in brez koraka ter skladba vrste z negativnim indeksom vrnejo null. Glasnost in korak sta
     * omejena na veljavno obmocje.
     */
    fun preberiUkaz(akcija: String, ms: Long = -1L, glasnost: Int = -1, korak: Int = 0, indeks: Int = -1): Ukaz? {
        val a = akcija.trim().lowercase()
        return when (a) {
            !in UKAZI -> null
            SKOCI -> if (ms < 0) null else Ukaz(a, ms = ms)
            GLASNOST -> when {
                glasnost >= 0 -> Ukaz(a, glasnost = glasnost.coerceAtMost(100))
                korak != 0 -> Ukaz(a, korak = korak.coerceIn(-100, 100))
                else -> null
            }
            IZ_VRSTE -> if (indeks < 0) null else Ukaz(a, indeks = indeks)
            else -> Ukaz(a)
        }
    }

    /** Nova glasnost v odstotkih: [vrednost] (ce je >= 0) ali [trenutna] + [korak]; vedno 0-100. */
    fun novaGlasnost(trenutna: Int, vrednost: Int, korak: Int): Int =
        if (vrednost >= 0) vrednost.coerceIn(0, 100) else (trenutna.coerceAtLeast(0) + korak).coerceIn(0, 100)

    /** Odstotek glasnosti v stopnjo naprave (0..[najvec]) in nazaj; zaokrozeno, da 100 % vedno da najvec. */
    fun vStopnjo(odstotek: Int, najvec: Int): Int = if (najvec <= 0) 0 else ((odstotek.coerceIn(0, 100) * najvec + 50) / 100)
    fun vOdstotek(stopnja: Int, najvec: Int): Int = if (najvec <= 0) -1 else ((stopnja.coerceIn(0, najvec) * 100 + najvec / 2) / najvec)

    /**
     * Stanje predvajanja na cilju. [prejetoMs] je cas daljinca, ko je stanje prispelo - iz njega [polozajZdaj] napove cas
     * med osvezitvami (drsnik tece gladko, ne v skokih po sekundo).
     */
    data class Stanje(
        val naslov: String = "",
        val izvajalec: String = "",
        val igra: Boolean = false,
        val polozajMs: Long = 0L,
        val trajanjeMs: Long = 0L,
        /** Glasnost v odstotkih, -1 = neznana. */
        val glasnost: Int = -1,
        /** Mesto trenutne skladbe v [vrsta] (-1 = ni vrste). */
        val indeks: Int = -1,
        /** Naslovi skladb v oknu vrste; [odVrste] je indeks prve v celotni vrsti. */
        val vrsta: List<String> = emptyList(),
        val odVrste: Int = 0,
        /** Ime zvocnika, ce cilj igra na zvocniku v omrezju (prazno sicer). */
        val zvocnik: String = "",
        val prejetoMs: Long = 0L,
    ) {
        val kajIgra: Boolean get() = naslov.isNotBlank()
    }

    /** Napovedan cas na cilju: med igranjem tece od prejema, a ne cez trajanje; med premorom stoji. */
    fun polozajZdaj(s: Stanje, zdajMs: Long): Long {
        if (!s.igra) return s.polozajMs
        val p = s.polozajMs + (zdajMs - s.prejetoMs).coerceAtLeast(0L)
        return if (s.trajanjeMs > 0) p.coerceAtMost(s.trajanjeMs) else p
    }

    /**
     * Stanje takoj po ukazu, preden cilj odgovori (gumb se odzove takoj - kot lokalno). Naprej, nazaj in skladba vrste
     * pocakajo na odgovor (ne vemo, kaj bo igralo); STANJE nic ne spremeni.
     */
    fun poUkazu(s: Stanje, u: Ukaz, zdajMs: Long): Stanje {
        val zdaj = polozajZdaj(s, zdajMs)
        return when (u.vrsta) {
            PREMOR -> s.copy(igra = false, polozajMs = zdaj, prejetoMs = zdajMs)
            NADALJUJ -> s.copy(igra = s.kajIgra, polozajMs = zdaj, prejetoMs = zdajMs)
            PREKLOPI -> s.copy(igra = !s.igra && s.kajIgra, polozajMs = zdaj, prejetoMs = zdajMs)
            SKOCI -> s.copy(polozajMs = if (s.trajanjeMs > 0) u.ms.coerceAtMost(s.trajanjeMs) else u.ms, prejetoMs = zdajMs)
            GLASNOST -> s.copy(glasnost = novaGlasnost(s.glasnost, u.glasnost, u.korak))
            else -> s
        }
    }

    /** Okno vrste za odgovor: najvec [najvec] skladb, trenutna cim bolj na sredini. Prazna vrsta: prazno okno. */
    fun oknoVrste(velikost: Int, indeks: Int, najvec: Int = NAJVEC_V_VRSTI): IntRange {
        if (velikost <= 0 || najvec <= 0) return IntRange.EMPTY
        if (velikost <= najvec) return 0 until velikost
        val zacetek = (indeks.coerceIn(0, velikost - 1) - najvec / 2).coerceIn(0, velikost - najvec)
        return zacetek until zacetek + najvec
    }

    /** Toliko osvezitev brez vidnega zaslona z daljincem (po skritju okna, dotiku docka ali ukazu), nato nobene vec. */
    const val NAJVEC_OSVEZITEV_V_OZADJU = 3
    /** Stanje, mlajse od tega, je dovolj sveze za prikaz (dock ga ne zahteva znova). */
    const val SVEZE_MS = 10_000L

    /**
     * Osvezitev stanja: okno daljinca vidno in igra - vsako sekundo; vidno v premoru - na 3 s. Brez vidnega okna (pregled C2,
     * 25. 10. 2026) samo se [NAJVEC_OSVEZITEV_V_OZADJU]-krat na 10 s ([vOzadju] = ze opravljene), nato 0 = nic vec (v ozadju
     * brez zahtev v omrezje; dock ali okno jih spet zbudi).
     */
    fun razmikOsvezitve(vidno: Boolean, igra: Boolean, vOzadju: Int = 0): Long = when {
        !vidno -> if (vOzadju < NAJVEC_OSVEZITEV_V_OZADJU) 10_000L else 0L
        igra -> 1_000L
        else -> 3_000L
    }

    /** Toliko zaporednih neodgovorov in toliko casa brez odgovora pomeni, da cilja ni vec (seja daljinca se konca). */
    const val NAJVEC_NAPAK = 3
    const val BREZ_ODGOVORA_MS = 15_000L

    fun sejaKoncana(zaporednihNapak: Int, zadnjiOdgovorMs: Long, zdajMs: Long): Boolean =
        zaporednihNapak >= NAJVEC_NAPAK && zdajMs - zadnjiOdgovorMs >= BREZ_ODGOVORA_MS

    /** Zakaj se je seja daljinca koncala (pregled C2/C7, 25. 10. 2026). */
    const val KONEC_UPORABNIK = "uporabnik"
    const val KONEC_NI_ODZIVA = "ni_odziva"
    const val KONEC_NE_DELI = "ne_deli"
    const val KONEC_STARA = "stara"
    const val KONEC_BREZ_PREDVAJANJA = "brez_predvajanja"
    /** Ta naprava predvaja sama ali jo upravlja druga naprava. */
    const val KONEC_TU = "tu"

    /**
     * Konec seje po odgovoru cilja (null: seja tece): cilj ukaza ne pozna (starejsi Safeer), dostop te naprave je zaprt
     * (koda "ni_dovoljeno") ali je cilj izklopil deljenje predvajanja (odgovor "shared": false) - takoj, ne sele po napakah.
     */
    fun konecPoOdgovoru(ok: Boolean, koda: String, deli: Boolean): String? = when {
        koda == "neznano_dejanje" -> KONEC_STARA
        koda == "ni_dovoljeno" -> KONEC_NE_DELI
        ok && !deli -> KONEC_NE_DELI
        else -> null
    }

    /** Cilj brez vsebine toliko casa (od zacetka seje ali zadnjega igranja) - seja se konca. */
    const val BREZ_VSEBINE_MS = 3 * 60_000L
    /** Cilj v premoru toliko casa od zadnjega igranja - seja se konca. */
    const val V_PREMORU_MS = 60 * 60_000L

    /**
     * Pregled C2: seja se konca, ko cilj predolgo ne predvaja: nima vsebine [BREZ_VSEBINE_MS] (tam so zavrnili ponudbo ali
     * ustavili predvajanje) ali je v premoru [V_PREMORU_MS]. [zadnjeIgranjeMs] je zacetek seje ali zadnji odgovor, ko je igralo.
     */
    fun brezPredvajanja(s: Stanje, zadnjeIgranjeMs: Long, zdajMs: Long): Boolean {
        if (s.igra) return false
        val ze = zdajMs - zadnjeIgranjeMs
        return if (s.kajIgra) ze >= V_PREMORU_MS else ze >= BREZ_VSEBINE_MS
    }

    /** Sporocilo uporabniku samo, ce je sejo koncal cilj (ne odgovarja, ne deli vec, ne zna) - ne ob uporabnikovem koncu. */
    fun javiKonec(razlog: String): Boolean = razlog == KONEC_NI_ODZIVA || razlog == KONEC_NE_DELI || razlog == KONEC_STARA ||
        // Pregled 9: okno daljinca se zapre samo - uporabnik izve zakaj (na cilju ne tece nic vec).
        razlog == KONEC_BREZ_PREDVAJANJA

    /**
     * Ali gumbi predvajalnika te naprave upravljajo cilj: seja daljinca je dejavna, cilj nekaj predvaja in tu ni nalozeno
     * nic. Pregled C1 (25. 10. 2026): dokler je tu nalozena skladba - tudi v premoru ali med nalaganjem - gumbi (zaslon,
     * obvestilo, dock) upravljajo to napravo; prej je »Predvajaj« po premoru tu upravljal drugo napravo in lokalne skladbe
     * ni bilo mogoce nadaljevati. Cilj se tedaj upravlja v oknu »Upravljaj napravo«.
     */
    fun upravljaCilj(sejaDejavna: Boolean, ciljKajIgra: Boolean, tuNalozeno: Boolean): Boolean = sejaDejavna && ciljKajIgra && !tuNalozeno

    /** Pregled C6: okno daljinca ponudi ±10 s samo, ce ima posnetek na cilju trajanje (ne v zivo). */
    fun previjanjeNaCilju(s: Stanje): Boolean = s.kajIgra && s.trajanjeMs > 0
}
