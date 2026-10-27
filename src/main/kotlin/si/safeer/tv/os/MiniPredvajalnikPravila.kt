package si.safeer.tv.os

/**
 * Naloga 118: mini predvajalnik na domacem zaslonu (telefon, tablica, TV), ko glasba igra na tej napravi, na zvocniku v
 * omrezju ali na drugi napravi (seja daljinca, naloga 117). Skrije se kot »dock« ob robu - ostane le rocica - in se pokaze
 * z enim potegom ali gumbom (OK na daljincu). Mesto (zgoraj, spodaj, levo, desno) izbere uporabnik; »v seznamu« vrne
 * prejsnji kompakten predvajalnik med odseki. Ciste odlocitve brez Androida (preizkus: tests/MiniPredvajalnikPravilaTest.kt).
 */
object MiniPredvajalnikPravila {
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

    /** Gumbi obvestila po vrsti. Nazaj ima vsaka skladba (zacetek posnetka); radio nima ne Nazaj ne Naprej. */
    fun dejanja(imaNaslednjo: Boolean, radio: Boolean): List<String> =
        listOfNotNull(NAZAJ.takeIf { !radio }, PREKLOPI, NAPREJ.takeIf { imaNaslednjo && !radio }, USTAVI)

    /** Indeksi gumbov v strnjenem obvestilu (najvec trije): Nazaj, Predvajaj/Pavza, Naprej - Ustavi samo v razsirjenem. */
    fun strnjeno(dejanja: List<String>): IntArray =
        dejanja.withIndex().filter { it.value != USTAVI }.map { it.index }.take(3).toIntArray()
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

    /** Postavke izbire: (vrsta, indeks v svojem seznamu). Sistemski izhod je vedno zadnji - tudi, ce v omrezju ni nic. */
    fun izbire(zvocnikov: Int, naprav: Int): List<Pair<String, Int>> =
        (0 until zvocnikov.coerceAtLeast(0)).map { ZVOCNIK to it } +
            (0 until naprav.coerceAtLeast(0)).map { NAPRAVA to it } + (SISTEM to 0)
}
