package si.safeer.tv.os

/**
 * Odlocitve zaslonov Sporocila, Zapiski, Naprave in Datoteke (pregled zaslonov 5. 11. 2026) - brez Androida, preizkus
 * tests/ZasloniOsPravilaTest.kt.
 */
object ZasloniOsPravila {
    /** Zamik iskanja (Sporocila): branje baze sele, ko uporabnik ~200 ms ne tipka, ne ob vsakem znaku. */
    const val ZAMIK_ISKANJA_MS = 200L

    /**
     * Fokus po ponovnem izrisu seznama: ista postavka po oznaki, sicer (postavka je izginila) soseda na istem mestu,
     * sicer zadnja. null = fokusa ni bilo v seznamu ali je seznam prazen.
     */
    fun fokusPoOznaki(prej: List<String>, fokusiran: String?, zdaj: List<String>): String? {
        if (fokusiran == null || zdaj.isEmpty()) return null
        if (fokusiran in zdaj) return fokusiran
        val i = prej.indexOf(fokusiran)
        if (i < 0) return null
        return zdaj[minOf(i, zdaj.lastIndex)]
    }

    enum class Drsenje { ZADETEK, OHRANI, DNO }

    /**
     * Po izrisu sporocil pogovora: zadetek iskanja pokazemo, fokus v seznamu sporocil ostane (uporabnik bere starejsa),
     * sicer na dno (novo sporocilo).
     */
    fun poIzrisuSporocil(zadetek: Boolean, fokusVSeznamu: Boolean): Drsenje = when {
        zadetek -> Drsenje.ZADETEK
        fokusVSeznamu -> Drsenje.OHRANI
        else -> Drsenje.DNO
    }

    /** Vrstica Naprav »<id>#zaslon« je zaslon naprave <id>: preimenuje se naprava, ne neobstojec »<id>#zaslon«. */
    fun napravaVrstice(id: String): String = id.substringBefore('#')

    /** Zapisek v oknu urejanja je spremenjen (naslov se shrani obrezan, zato presledki na robu niso sprememba). */
    fun zapisekSpremenjen(prejNaslov: String, prejVsebina: String, naslov: String, vsebina: String): Boolean =
        prejNaslov.trim() != naslov.trim() || prejVsebina != vsebina

    /** Zbirka z vec datotekami, kot jih pokazemo: koliko jih pokazemo; null = pokazemo vse. */
    fun prikazanih(vseh: Int, najvec: Int): Int? = if (vseh > najvec) najvec else null
}
