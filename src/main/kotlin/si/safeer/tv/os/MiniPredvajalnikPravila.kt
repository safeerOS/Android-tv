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
