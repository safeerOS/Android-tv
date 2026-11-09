package si.safeer.tv.os

/**
 * Naloga 118: mini predvajalnik na domacem zaslonu (telefon, tablica, TV), ko glasba igra na tej napravi, na zvocniku v
 * omrezju ali na drugi napravi (seja daljinca, naloga 117). Skrije se kot »dock« ob robu - ostane le rocica - in se pokaze
 * z enim potegom ali gumbom (OK na daljincu). Mesto (zgoraj, spodaj, levo, desno) izbere uporabnik; »v seznamu« vrne
 * prejsnji kompakten predvajalnik med odseki. Ciste odlocitve brez Androida (preizkus: tests/MiniPredvajalnikPravilaTest.kt).
 */
object MiniPredvajalnikPravila {
    /** Lastnik 28. 10. 2026: gumb »Zatemni zaslon« - kadar na tej napravi igra zvok (ne video, ne druga naprava). */
    fun gumbZatemni(imaPredvajanje: Boolean, video: Boolean, oddaljeno: Boolean): Boolean = imaPredvajanje && !video && !oddaljeno

    /**
     * Tocka 28 (lastnik 7. 11. 2026, TV Domov): s fokusom ni bilo mogoce na mini predvajalnik. DOL na dno vsebine (ni
     * naslednjega cilja) gre na vidni dock, ki se ga da fokusirati; ce ima dock ze fokus ali ima vsebina se kaj spodaj, ne.
     */
    fun dolNaDock(vsebinaImaNaslednjega: Boolean, dockVidenInFokusiran: Boolean, dockImaFokus: Boolean): Boolean =
        !vsebinaImaNaslednjega && dockVidenInFokusiran && !dockImaFokus

    const val ZGORAJ = "zgoraj"
    const val SPODAJ = "spodaj"
    const val LEVO = "levo"
    const val DESNO = "desno"
    /** Brez docka: kompakten predvajalnik ostane v seznamu odsekov (kot pred 118). */
    const val V_SEZNAMU = "seznam"

    val MESTA = listOf(SPODAJ, ZGORAJ, LEVO, DESNO, V_SEZNAMU)
    const val PRIVZETO = SPODAJ

    /** Shranjena izbira ali privzeto (neznana vrednost iz starejse ali pokvarjene shrambe ne skrije predvajalnika). */
    fun mesto(shranjeno: String?): String = shranjeno?.trim()?.lowercase()?.takeIf { it in MESTA } ?: PRIVZETO

    /** Dock je ob robu (ne v seznamu). */
    fun jeDock(mesto: String): Boolean = mesto(mesto) != V_SEZNAMU

    /** Ob levem ali desnem robu je dock pokoncen (gumbi drug pod drugim), zgoraj in spodaj vodoraven. */
    fun pokoncen(mesto: String): Boolean = mesto(mesto) == LEVO || mesto(mesto) == DESNO

    /**
     * Ali je predvajalnik sploh viden: tu je nalozena skladba (igra ali v premoru - tudi, ce igra zvocnik) ali pa ta
     * naprava upravlja drugo, ki nekaj predvaja.
     */
    fun viden(imaSkladbo: Boolean, oddaljenoKajIgra: Boolean): Boolean = imaSkladbo || oddaljenoKajIgra

    /** Kaj kaze mali predvajalnik (lastnik 8. 11. 2026 06:30: tudi glasbo drugih aplikacij, npr. SmartTube). */
    const val VIR_NIC = "nic"
    const val VIR_NASE = "nase"
    const val VIR_ODDALJENO = "oddaljeno"
    const val VIR_TUJE = "tuje"

    /** Druga aplikacija, ki IGRA, ima prednost pred nasim predvajanjem na premoru; sicer nase, druga naprava, tuje. */
    fun virDocka(imaNase: Boolean, naseIgra: Boolean, oddaljenoKajIgra: Boolean, imaTuje: Boolean, tujeIgra: Boolean): String = when {
        imaTuje && tujeIgra && !(imaNase && naseIgra) -> VIR_TUJE
        imaNase -> VIR_NASE
        oddaljenoKajIgra -> VIR_ODDALJENO
        imaTuje -> VIR_TUJE
        else -> VIR_NIC
    }

    /**
     * Stanja PlaybackState (android.media.session): PAUSED 2, PLAYING 3, FAST_FORWARDING 4, REWINDING 5, BUFFERING 6,
     * CONNECTING 8, SKIPPING_TO_PREVIOUS 9, SKIPPING_TO_NEXT 10, SKIPPING_TO_QUEUE_ITEM 11 - vse razen premora in
     * ustavitve je »igra« (slepi pregled: med skladbami bi se aplikacija sicer zaprla).
     */
    fun tujaIgra(stanje: Int): Boolean = stanje in setOf(3, 4, 5, 6, 8, 9, 10, 11)

    /** Premor, ki ga je uporabnik dal v malem predvajalniku, zasciti aplikacijo toliko casa (nadaljuje z istim gumbom). */
    const val PREMOR_IZ_DOCKA_MS = 10L * 60_000L

    /** Aplikacija s sejo ostane: igra ali jo je uporabnik v malem predvajalniku dal na premor pred manj kot 10 min. */
    fun tujaZascitena(igra: Boolean, premorIzDockaMs: Long, zdajMs: Long): Boolean =
        igra || (premorIzDockaMs > 0L && zdajMs - premorIzDockaMs in 0 until PREMOR_IZ_DOCKA_MS)

    /** Seja druge aplikacije je za prikaz, ce ima naslov in igra ali je na premoru (ustavljena/brez stanja ne). */
    /** Naslov tuje seje; brez njega (SmartTube) ime aplikacije, da mali predvajalnik vseeno pokaze, kaj igra. */
    fun naslovTuje(naslov: String, imeAplikacije: String): String = naslov.ifBlank { imeAplikacije }

    /** Lastnik 9. 11. 2026: X v malem predvajalniku ustavi tujo sejo; aplikacijo, ki ne predvaja vec, zapremo po tem casu. */
    const val ZAPRI_PO_USTAVITVI_MS = 1_500L

    /** Seje, ki jo je uporabnik ustavil z X, ne prikazujemo, dokler spet ne igra. */
    fun prikaziTujo(paket: String, igra: Boolean, ustavljenIzDocka: String?): Boolean = igra || paket != ustavljenIzDocka

    fun tujaZaPrikaz(naslov: String, stanje: Int): Boolean = naslov.isNotBlank() && (tujaIgra(stanje) || stanje == 2)

    /** Katera seja drugih aplikacij: prva, ki igra, sicer prva na premoru; -1 = nobena. */
    fun izberiTujo(stanja: List<Int>): Int {
        val igra = stanja.indexOfFirst { tujaIgra(it) }
        return if (igra >= 0) igra else stanja.indexOfFirst { it == 2 }
    }

    const val NIC = 0
    const val ODPRI = 1
    const val ZAPRI = 2

    /**
     * Poteg [dx], [dy] (px, desno in dol pozitivno) na docku ob robu [mesto]: proti sredini zaslona ga odpre, proti robu
     * zapre. Kratek poteg (pod [prag]) ali poteg vzdolz roba (pravokotni premik vecji) ne naredi nicesar - tako drsenje
     * seznama ali dotik gumba ne odpre ali zapre docka po pomoti.
     */
    fun poPotegu(mesto: String, dx: Float, dy: Float, prag: Float): Int {
        val m = mesto(mesto)
        if (m == V_SEZNAMU) return NIC
        val vzdolz = if (pokoncen(m)) dx else dy
        val precno = if (pokoncen(m)) dy else dx
        if (kotlin.math.abs(vzdolz) < prag || kotlin.math.abs(vzdolz) <= kotlin.math.abs(precno)) return NIC
        // Smer proti sredini zaslona: spodaj gor (negativen dy), zgoraj dol, levo desno, desno levo.
        val protiSredini = when (m) {
            SPODAJ -> vzdolz < 0
            ZGORAJ -> vzdolz > 0
            LEVO -> vzdolz > 0
            else -> vzdolz < 0
        }
        return if (protiSredini) ODPRI else ZAPRI
    }

    /** Odprt dock se sam skrije po toliko casa brez dotika (z daljincem: dokler ima fokus, ostane odprt). */
    const val SAMODEJNO_SKRIJ_MS = 8_000L

    /** Lastnik 27. 10. 2026: film ali epizoda, ki ne igra - »Predvajaj« odpre celozaslonski predvajalnik (isti tok). */
    const val ODPRI_PREDVAJALNIK = "odpri_predvajalnik"

    /** Lastnik 27. 10. 2026: stikali v predvajalniku, privzeto vklopljeni. */
    const val KLJUC_SAMODEJNO_NASLEDNJA = "samodejno_naslednja_skladba"
    const val KLJUC_SAMODEJNO_ALBUM = "samodejno_naslednji_album"
    const val PO_KONCU_NASLEDNJA = "naslednja"
    const val PO_KONCU_MIX = "mix"
    const val PO_KONCU_KONEC = "konec"

    /**
     * Kaj po koncu skladbe: naslednja v vrsti (ce je in je samodejno naslednja vklopljeno), samodejno naprej po zadnji
     * skladbi albuma ali seznama (mix - ce sta vklopljeni obe stikali) ali konec.
     */
    fun poKoncuSkladbe(naslednja: Boolean, album: Boolean, imaNaslednjo: Boolean): String = when {
        !naslednja -> PO_KONCU_KONEC
        imaNaslednjo -> PO_KONCU_NASLEDNJA
        album -> PO_KONCU_MIX
        else -> PO_KONCU_KONEC
    }
    const val PREKLOPI_ZVOK = "preklopi"
    const val ODPRI_MEDIJE = "odpri_medije"

    /**
     * »Predvajaj/Nadaljuj« v docku in v obvestilu: video (film, epizoda), ki ne igra, gre v celozaslonski predvajalnik na
     * istem mestu (prej samo zvok v ozadju, slike ni bilo); Pavza ostane pavza; glasba se preklopi kot doslej.
     */
    fun dejanjePredvajaj(video: Boolean, igra: Boolean): String = if (video && !igra) ODPRI_PREDVAJALNIK else PREKLOPI_ZVOK

    /** Klik na naslov v docku: video v celozaslonski predvajalnik, glasba v Medijski center. */
    fun klikNaslova(video: Boolean): String = if (video) ODPRI_PREDVAJALNIK else ODPRI_MEDIJE

    const val TIPKA_PLAY = "play"
    const val TIPKA_PREKLOPI = "preklopi"

    /**
     * Zunanji pregled 3. 11. 2026 (Philips, Android 11, MediaTek): tipka Predvajaj (ali Predvajaj/Pavza na pavzi) na
     * domacem zaslonu je video te naprave zagnala brez zaslona predvajanja - dekoder brez povrsine, zvok brez slike. Video
     * na pavzi zato odpre celozaslonski predvajalnik, ki predvaja sele po pripeti povrsini; vse drugo kot doslej.
     */
    fun tipkaNaDomu(tipka: String, video: Boolean, igra: Boolean, naZvocniku: Boolean, zaslonOdprt: Boolean): String =
        if (video && !igra && !naZvocniku && !zaslonOdprt && (tipka == TIPKA_PLAY || tipka == TIPKA_PREKLOPI)) ODPRI_PREDVAJALNIK
        else PREKLOPI_ZVOK

    /**
     * Zunanji pregled 3. 11. 2026: onStop domacega zaslona ustavi casovnik samodejnega skrivanja, onResume ga ni zagnal -
     * odprt dock po vrnitvi na dom ni nikoli vec izginil. Odprt in viden dock brez casovnika ga dobi znova.
     */
    fun znovaSkrivanje(odprt: Boolean, viden: Boolean, casovnikTece: Boolean): Boolean = odprt && viden && !casovnikTece

    /**
     * Vodja 27. 10. 2026 (TV beta 14): na zaslonu z lastno vrstico ob robu (Pokrajina: spodnja vrstica) dock ne sme lezati
     * cez njo - dodaten odmik na strani, kjer je dock. Zgoraj nic (tam sta ura in lupa, dock ZGORAJ izbere uporabnik).
     */
    fun dodatniRob(mesto: String, spodaj: Int, levo: Int = 0, desno: Int = 0): Int = when (mesto) {
        ZGORAJ -> 0
        LEVO -> maxOf(0, levo)
        DESNO -> maxOf(0, desno)
        else -> maxOf(0, spodaj)
    }

    fun samodejnoSkrij(odprt: Boolean, imaFokus: Boolean, zadnjiDotikMs: Long, zdajMs: Long): Boolean =
        odprt && !imaFokus && zdajMs - zadnjiDotikMs >= SAMODEJNO_SKRIJ_MS

    /**
     * Pregled C4 (25. 10. 2026): dock se zgradi znova samo, ce se ni zgrajen ([zgrajenoMesto] null), ni vec na zaslonu
     * ([priklopljen] false - nov setContentView) ali je uporabnik izbral drugo mesto; sicer osvezitev spremeni le besedilo in
     * ikone (fokus daljinca ostane, samodejno skrivanje tece naprej).
     */
    fun zgradiZnova(zgrajenoMesto: String?, mesto: String, priklopljen: Boolean): Boolean =
        !priklopljen || zgrajenoMesto == null || mesto(zgrajenoMesto) != mesto(mesto)

    /**
     * Naloga 42 B (27. 10. 2026, lastnik: »kot na racunalniku«): gumbi docka v tem vrstnem redu - prejsnja, premor ali
     * predvajaj, naslednja, zvocnik, tisje, glasneje, skrij. Glasnost brez zapuscanja domacega zaslona.
     */
    val GUMBI = listOf("prejsnja", "predvajaj", "naslednja", "zvocnik", "tisje", "glasneje", "skrij")

    const val GLASNOST_TU = "tu"
    const val GLASNOST_ZVOCNIK = "zvocnik"
    const val GLASNOST_DRUGA = "druga"
    /** Korak glasnosti druge naprave (odstotki, kot drsnik v ZvocnikIzbira). */
    const val KORAK_DRUGA = 5

    /**
     * Cigava glasnost se spremeni: ta naprava upravlja drugo (seja daljinca, naloga 117) - glasnost tiste naprave; glasba
     * igra na zvocniku v omrezju - glasnost zvocnika; sicer glasnost te naprave (kot tipki za glasnost na daljincu).
     */
    fun ciljGlasnosti(naDrugiNapravi: Boolean, naZvocniku: Boolean): String = when {
        naDrugiNapravi -> GLASNOST_DRUGA
        naZvocniku -> GLASNOST_ZVOCNIK
        else -> GLASNOST_TU
    }

    /**
     * Lastnik 27. 10. 2026: v docku ni bilo mogoce premakniti predvajanja naprej in ne predvajati na zvocniku (v
     * celozaslonskem predvajalniku oboje dela). Drsnik casa: samo za skladbo s trajanjem na tej napravi ali zvocniku
     * (radio in tok v zivo nimata konca; drugo napravo upravlja okno »Upravljaj napravo«).
     */
    fun drsnik(trajanjeMs: Long, radio: Boolean, oddaljeno: Boolean): Boolean = trajanjeMs > 0 && !radio && !oddaljeno

    /** Gumb »Predvajaj na zvocniku« (ali upravljanje zvocnika, ki ze igra): kadar glasba igra na tej napravi ali zvocniku. */
    fun gumbZvocnik(imaSkladbo: Boolean, oddaljeno: Boolean): Boolean = imaSkladbo && !oddaljeno

    const val DRSNIK_KORAKOV = 1000

    /** Polozaj drsnika (0..[DRSNIK_KORAKOV]) iz casa; brez trajanja 0. */
    fun korakDrsnika(polozajMs: Long, trajanjeMs: Long): Int =
        if (trajanjeMs <= 0) 0 else (polozajMs.coerceIn(0L, trajanjeMs) * DRSNIK_KORAKOV / trajanjeMs).toInt()

    /** Cas (ms) za korak drsnika - kamor uporabnik premakne. */
    fun casIzDrsnika(korak: Int, trajanjeMs: Long): Long =
        if (trajanjeMs <= 0) 0L else korak.coerceIn(0, DRSNIK_KORAKOV).toLong() * trajanjeMs / DRSNIK_KORAKOV

    const val KARTICA = "kartica"
    const val ZNAK = "znak"
    const val MC_NIC = "nic"

    fun prikazMc(naDomu: Boolean, imaSkladbo: Boolean, podstranOdprta: Boolean): String = when {
        !imaSkladbo -> MC_NIC
        naDomu && !podstranOdprta -> KARTICA
        else -> ZNAK
    }

    /** Kartica na Domu MC kaze zadnjo nedokoncano vsebino z gumbom Nadaljuj (nic ne igra). */
    const val NADALJUJ = "nadaljuj"
    /** Nic ne igra in ni nedokoncanega: pas kaze izbor dneva (Kinema hero.ts) - Dom nikoli brez pasu. */
    const val IZBOR = "izbor"

    /**
     * Stanje kartice na Domu Medijskega centra (lastnik 6. 11. 2026 04:25 in 8. 11. 2026 02:58): mali predvajalnik je na
     * Domu VES CAS - ko kaj igra, kaze to (KARTICA, video v okencu); ko nic ne igra, kaze zadnjo nedokoncano vsebino z
     * gumbom Nadaljuj (NADALJUJ); brez obojega je ni (MC_NIC). Zunaj Doma ali na podstrani je kartice ni (znak: [prikazMc]).
     */
    fun stanjeKartice(naDomu: Boolean, podstranOdprta: Boolean, imaSkladbo: Boolean, imaNadaljevanje: Boolean,
                      imaIzbor: Boolean = false): String = when {
        !naDomu || podstranOdprta -> MC_NIC
        imaSkladbo -> KARTICA
        imaNadaljevanje -> NADALJUJ
        imaIzbor -> IZBOR
        else -> MC_NIC
    }

    /**
     * Izbor dneva za pas na vrhu Doma (po Kinemi, src/ui/hero.ts, MIT): med naslovi s sliko vsak koledarski dan drug,
     * stabilen ves dan (ponovno odprtje ga ne premesa), razvrscen po id-ju, da vrstni red vrstic nanj ne vpliva.
     * [kandidati] = id-ji; vrne indeks v [kandidati] ali -1. [dan] = dni od epohe v krajevnem casu.
     */
    fun izborDneva(kandidati: List<String>, dan: Long): Int {
        if (kandidati.isEmpty()) return -1
        val urejeni = kandidati.withIndex().sortedBy { it.value }
        return urejeni[Math.floorMod(dan, urejeni.size.toLong()).toInt()].index
    }

    /** Dni od epohe v krajevnem casu (izbor se zamenja ob polnoci tu, ne po UTC). */
    fun krajevniDan(zdajMs: Long, zamikMs: Int): Long = Math.floorDiv(zdajMs + zamikMs, 86_400_000L)

    /** Zadnja nedokoncana vsebina za Nadaljuj: video z napredkom med 2 % in 95 % (odgledan film ali komaj zacet ne steje). */
    fun jeNedokoncano(video: Boolean, polozajMs: Long, trajanjeMs: Long): Boolean =
        video && trajanjeMs > 0 && polozajMs * 100 >= trajanjeMs * 2 && polozajMs * 100 <= trajanjeMs * 95

    /**
     * Video v okencu kartice je zaslon predvajanja: ko kartica z zivo sliko igra na Domu, celozaslonskega predvajalnika ne
     * odpiramo sami ([GlasbaActivity.videoBrezZaslona] - pravilo lastnika 11. 10. 2026 »video brez slike nima smisla« velja
     * se naprej, kadar slike ni nikjer).
     */
    /**
     * Ali sme celozaslonski predvajalnik ob zapiranju pustiti video teci za okence Doma: Dom MC mora biti zagnan
     * (onStart pred onStop zaslona predvajanja = cilj vrnitve), ne koncuje, na Domu, brez podstrani in s kartico.
     * CodeRabbit 8. 11. 2026: z Datotekami vmes je Dom ustavljen - video bi tekel brez zaslona.
     */
    fun okenceNaDomu(zagnan: Boolean, koncuje: Boolean, naDomu: Boolean, podstranOdprta: Boolean, imaKartico: Boolean,
                     povrsinaMozna: Boolean = true): Boolean =
        zagnan && !koncuje && naDomu && !podstranOdprta && imaKartico && povrsinaMozna

    /**
     * Okence sme pripeti sliko predvajalnika: zaslon predvajanja ni odprt (slepi pregled 8. 11. 2026, najdba 1: vsak
     * setVideoSurfaceView vzame sliko trenutnemu lastniku - celozaslonski zaslon bi ostal crn) in predvajalnik zna
     * povrsino (najdba 4: SpletniIgralec nima COMMAND_SET_VIDEO_SURFACE - okence bi bilo crna luknja).
     */
    fun okenceSmePripeti(zaslonOdprt: Boolean, povrsinaMozna: Boolean): Boolean = !zaslonOdprt && povrsinaMozna

    /** Predvajaj na pasu: video, ki ne igra in ga pas ne more pokazati (ni okenca ali povrsine), gre na cel zaslon. */
    fun predvajajNaCelZaslon(video: Boolean, igra: Boolean, vPasu: Boolean, smePripeti: Boolean): Boolean =
        video && !igra && !(vPasu && smePripeti)

    /**
     * Okence, ki je kazalo video, izgine (drug razdelek, podstran, onStop): video brez slike nima smisla (lastnik 11. 10.
     * 2026), zato pavza - razen ce sliko prevzema zaslon predvajanja, gre za menjavo konfiguracije ali YouTube zvok v ozadju.
     */
    fun pavzaObSkritjuOkenca(video: Boolean, tece: Boolean, zaslonOdprt: Boolean, menjavaKonfig: Boolean, zvokVOzadju: Boolean): Boolean =
        video && tece && !zaslonOdprt && !menjavaKonfig && !zvokVOzadju

    fun videoVOkencu(naDomu: Boolean, podstranOdprta: Boolean, karticaVidna: Boolean, video: Boolean): Boolean =
        naDomu && !podstranOdprta && karticaVidna && video

    /** Kartica »Poglej, kaj igra« (tocka 8, lastnik 7. 11. 2026): samo, ce druga naprava res igra - ne za »nazadnje«. */
    fun pokaziKajIgra(ponudbeIgrajo: List<Boolean>): Boolean = ponudbeIgrajo.any { it }

    fun casNapredka(polozajMs: Long, trajanjeMs: Long): String {
        if (trajanjeMs <= 0) return ""
        val uP = polozajMs / 3600000
        val mP = (polozajMs % 3600000) / 60000
        val sP = (polozajMs % 60000) / 1000
        val uT = trajanjeMs / 3600000
        val mT = (trajanjeMs % 3600000) / 60000
        val sT = (trajanjeMs % 60000) / 1000
        return if (uT > 0) String.format("%d:%02d:%02d / %d:%02d:%02d", uP, mP, sP, uT, mT, sT)
        else String.format("%d:%02d / %d:%02d", mP, sP, mT, sT)
    }

    fun gumbiKartice(video: Boolean, radio: Boolean): List<String> =
        if (video || radio) listOf("predvajaj") else listOf("prejsnja", "predvajaj", "naslednja")
}

/**
 * Lastnik 27. 10. 2026: »ne morem upravljati predvajalnika, ko Safeer OS ni odprt (kot pri Spotifyju)«. Obvestilo je bilo
 * navadno (brez Notification.MediaStyle in seje), zato ga Android ni pokazal kot predvajalnik na zaklenjenem zaslonu in v
 * hitrih nastavitvah; manjkal je tudi gumb Nazaj. Ciste odlocitve (preizkus: tests/MiniPredvajalnikPravilaTest.kt).
 */
object ObvestiloPredvajanjaPravila {
    const val NAZAJ = "nazaj"
    const val PREKLOPI = "preklopi"
    const val NAPREJ = "naprej"
    const val USTAVI = "ustavi"
    /** Lastnik 27. 10. 2026: »Predvajaj na napravi« (zvocniki v omrezju, naprave Linka, Bluetooth) tudi brez odprtega OS. */
    const val NA_NAPRAVI = "na_napravi"

    /** Gumbi obvestila po vrsti. Nazaj ima vsaka skladba (zacetek posnetka); radio nima ne Nazaj ne Naprej. */
    fun dejanja(imaNaslednjo: Boolean, radio: Boolean): List<String> =
        listOfNotNull(NAZAJ.takeIf { !radio }, PREKLOPI, NAPREJ.takeIf { imaNaslednjo && !radio }, NA_NAPRAVI, USTAVI)

    /** Indeksi gumbov v strnjenem obvestilu (najvec trije): Nazaj, Predvajaj/Pavza, Naprej - ostalo samo v razsirjenem. */
    fun strnjeno(dejanja: List<String>): IntArray =
        dejanja.withIndex().filter { it.value != USTAVI && it.value != NA_NAPRAVI }.map { it.index }.take(3).toIntArray()
}

/**
 * Lastnik 27. 10. 2026: »pogresam moznost, da poslji skladbo na zvocnik prek LAN - zdaj lahko samo prek Bluetootha z gumbom
 * Ta telefon« (sistemski izbirnik izhoda Androida pozna samo svoje izhode). En gumb »Predvajaj na napravi« zato ponudi
 * vse po vrsti: zvocnike v omrezju (DLNA), naprave Safeer v Linku in na koncu Bluetooth/izhode naprave (sistem).
 */
object PredvajajNaNapraviPravila {
    const val ZVOCNIK = "zvocnik"
    const val NAPRAVA = "naprava"
    const val SISTEM = "sistem"
    /** Lastnik 27. 10. 2026: brez najdenega zvocnika uporabnik vidi, da Safeer isce v omrezju (in lahko isce znova). */
    const val ISCI_ZNOVA = "isci_znova"

    /** Postavke izbire: (vrsta, indeks v svojem seznamu). Sistemski izhod je vedno zadnji - tudi, ce v omrezju ni nic. */
    fun izbire(zvocnikov: Int, naprav: Int): List<Pair<String, Int>> =
        (if (zvocnikov > 0) (0 until zvocnikov).map { ZVOCNIK to it } else listOf(ISCI_ZNOVA to 0)) +
            (0 until naprav.coerceAtLeast(0)).map { NAPRAVA to it } + (SISTEM to 0)

}
