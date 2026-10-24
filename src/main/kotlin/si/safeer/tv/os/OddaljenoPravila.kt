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

    /**
     * Osvezitev stanja: okno daljinca vidno in igra - vsako sekundo; vidno v premoru - na 3 s; sicer na 10 s (tipke in
     * obvestilo morajo vedeti, ali cilj igra - npr. ko tam sprejmejo ponudbo).
     */
    fun razmikOsvezitve(vidno: Boolean, igra: Boolean): Long = when {
        !vidno -> 10_000L
        igra -> 1_000L
        else -> 3_000L
    }

    /** Toliko zaporednih neodgovorov in toliko casa brez odgovora pomeni, da cilja ni vec (seja daljinca se konca). */
    const val NAJVEC_NAPAK = 3
    const val BREZ_ODGOVORA_MS = 15_000L

    fun sejaKoncana(zaporednihNapak: Int, zadnjiOdgovorMs: Long, zdajMs: Long): Boolean =
        zaporednihNapak >= NAJVEC_NAPAK && zdajMs - zadnjiOdgovorMs >= BREZ_ODGOVORA_MS

    /**
     * Ali gumbi predvajalnika te naprave upravljajo cilj: seja daljinca je dejavna, cilj nekaj predvaja in ta naprava
     * ne igra sama (ce uporabnik tu spet zacne, gumbi upravljajo to napravo - nic se ne zgodi drugje po pomoti).
     */
    fun upravljaCilj(sejaDejavna: Boolean, ciljKajIgra: Boolean, tuIgra: Boolean): Boolean = sejaDejavna && ciljKajIgra && !tuIgra
}
