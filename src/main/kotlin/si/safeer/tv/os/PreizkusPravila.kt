package si.safeer.tv.os

/**
 * Naloga 135: samodejni preizkus po gradnji (tests/preizkus_po_gradnji.py) zazene film ali epizodo prek
 * [PreizkusActivity]. Ciste odlocitve (preizkus: tests/PreizkusPravilaTest.kt).
 */
object PreizkusPravila {
    /**
     * Oznaka v id-ju preizkusnega predvajanja: tak ogled ne gre med »Nazadnje predvajano« in ne v shranjeno mesto
     * (preizkus na lastnikovem TV ne sme mesati njegove zgodovine).
     */
    const val OZNAKA = "#preizkus"

    fun jePreizkus(id: String): Boolean = OZNAKA in id

    data class Vnos(val tip: String, val imdb: String, val sezona: Int, val epizoda: Int, val naslov: String)

    private val FILM = Regex("""tt\d{5,10}""")
    private val EPIZODA = Regex("""(tt\d{5,10}):(\d{1,4}):(\d{1,4})""")

    /** Iz dodatkov namena: film `tt…` ali epizoda `tt…:S:E`; naslov obrezan (najvec 80 znakov, brez nadzornih znakov). null = neveljavno. */
    fun vnos(imdb: String?, naslov: String?): Vnos? {
        val v = imdb?.trim().orEmpty()
        val ime = naslov.orEmpty().filter { !it.isISOControl() }.trim().take(80).ifEmpty { "Preizkus" }
        EPIZODA.matchEntire(v)?.let { m ->
            return Vnos("series", m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3].toInt(), ime)
        }
        return if (FILM.matches(v)) Vnos("movie", v, 0, 0, ime) else null
    }
}
