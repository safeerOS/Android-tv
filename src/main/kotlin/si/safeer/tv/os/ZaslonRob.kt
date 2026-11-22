package si.safeer.tv.os

/**
 * Cista pravila za nastavljiv rob slike (overscan) pri prenosu zaslona na TV.
 * Brez Androida, da se da preizkusiti na JVM.
 */
object ZaslonRob {
    /** Najvecji dovoljeni rob na vsaki strani v promilih sirine oziroma visine (80 promilov = 8 %). */
    const val NAJVEC_PROMILOV = 80

    /** Korak nastavitve roba z daljincem v promilih (5 promilov = 0,5 %). */
    const val KORAK = 5

    private const val PREDPONA_KLJUCA = "zaslon_rob_"
    private const val PRIVZETI_KLJUC = "zaslon_rob_privzeto"
    private const val NAJMANJSA_MERA = 2

    /** Omeji vrednost roba v promilih na veljavno obmocje [0, NAJVEC_PROMILOV]. */
    fun omeji(promilov: Int): Int = promilov.coerceIn(0, NAJVEC_PROMILOV)

    /** Povecaj rob za en [KORAK], omejeno na [0, NAJVEC_PROMILOV]. */
    fun zvecaj(trenutno: Int): Int = omeji(omeji(trenutno) + KORAK)

    /** Zmanjsaj rob za en [KORAK], omejeno na [0, NAJVEC_PROMILOV]. */
    fun zmanjsaj(trenutno: Int): Int = omeji(omeji(trenutno) - KORAK)

    /**
     * Izracuna rob slike [levo, zgoraj, desno, spodaj] v pikslih.
     * Levo in desno sta izracunana iz sirine, zgoraj in spodaj iz visine, zaokrozeno navzdol
     * na sodo stevilo pikslov (zaradi zahtev video dekodirnika).
     * Pri neveljavnih merah (<= 0) vrne [0, 0, 0, 0].
     */
    fun okvir(sirina: Int, visina: Int, promilov: Int): IntArray {
        if (sirina <= 0 || visina <= 0) return intArrayOf(0, 0, 0, 0)
        val p = omeji(promilov)
        if (p == 0) return intArrayOf(0, 0, 0, 0)
        val vodoravno = sodoNavzdol(((sirina.toLong() * p) / 1000L).toInt())
        val navpicno = sodoNavzdol(((visina.toLong() * p) / 1000L).toInt())
        return intArrayOf(vodoravno, navpicno, vodoravno, navpicno)
    }

    /**
     * Vrne mere slike znotraj roba (sirina - levo - desno, visina - zgoraj - spodaj),
     * nikoli manj od 2x2 piksla.
     */
    fun velikostSlike(sirina: Int, visina: Int, promilov: Int): Pair<Int, Int> {
        val rob = okvir(sirina, visina, promilov)
        val novaSirina = (sirina - rob[0] - rob[2]).coerceAtLeast(NAJMANJSA_MERA)
        val novaVisina = (visina - rob[1] - rob[3]).coerceAtLeast(NAJMANJSA_MERA)
        return novaSirina to novaVisina
    }

    /**
     * Sestavi kljuc za shranjevanje roba za dolocen zaslon (npr. "3840x2160" -> "zaslon_rob_3840x2160").
     * Dovoljeni so samo ASCII stevilke in crke (vkljucno z 'x'); prazen niz ali kateri koli drug znak
     * vrne privzeti kljuc "zaslon_rob_privzeto".
     */
    fun kljuc(zaslon: String): String =
        if (zaslon.isNotEmpty() && zaslon.all { c -> jeAlfanumericen(c) }) {
            PREDPONA_KLJUCA + zaslon
        } else {
            PRIVZETI_KLJUC
        }

    private fun sodoNavzdol(vrednost: Int): Int = (vrednost / 2) * 2

    private fun jeAlfanumericen(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z'
}
