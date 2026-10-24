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
}
