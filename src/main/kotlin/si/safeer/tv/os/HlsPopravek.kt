package si.safeer.tv.os

/**
 * Seznami HLS, ki jih strogi razclenjevalnik predvajalnika zavrne, drugi predvajalniki pa jih predvajajo. Cista
 * funkcija (preizkus: tests/HlsPopravekTest.kt).
 *
 * Izmerjeno 5. 10. 2026 na televizorju: kanal v zivo iz dodatka ima v seznamu vrstico
 * `#EXT-X-PROGRAM-DATE-TIME:10/05/2026T15:35:59.070-04:00` (mesec/dan/leto namesto ISO 8601). Media3 ob tem vrze
 * ParserException in kanal je crn zaslon. Oznaka je neobvezna (pove le uro na steni za posamezen kos), zato vrstico z
 * neveljavnim datumom izpustimo; veljavne ostanejo, kot so.
 */
object HlsPopravek {
    /** Oblika, ki jo Media3 sprejme (xs:dateTime): 2026-10-05T15:35:59[.070][Z|+02:00|-0400]. */
    private val DATUM = Regex("""\d{4}-\d{2}-\d{2}[Tt]\d{2}:\d{2}:\d{2}([.,]\d+)?([Zz]|[+-]\d?\d:?\d{2})?""")
    private val DATUM_ATRIBUT = Regex("""(?:START-DATE|END-DATE)="([^"]*)"""")

    fun veljavenDatum(d: String): Boolean = DATUM.matches(d.trim())

    /** Popravljen seznam ali null, kadar ni kaj popravljati (ni seznam HLS ali so vsi datumi veljavni). */
    fun pocisti(seznam: String): String? {
        if (!seznam.trimStart('\uFEFF', ' ', '\n', '\r', '\t').startsWith("#EXTM3U")) return null
        var spremenjeno = false
        val vrstice = seznam.split('\n').filter { v ->
            val cista = v.trim()
            val slaba = when {
                cista.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> !veljavenDatum(cista.substringAfter(':'))
                cista.startsWith("#EXT-X-DATERANGE:") -> DATUM_ATRIBUT.findAll(cista).any { !veljavenDatum(it.groupValues[1]) }
                else -> false
            }
            if (slaba) spremenjeno = true
            !slaba
        }
        return if (spremenjeno) vrstice.joinToString("\n") else null
    }
}
