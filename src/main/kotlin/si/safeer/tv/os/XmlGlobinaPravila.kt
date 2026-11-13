package si.safeer.tv.os

/**
 * Globina gnezdenja XML pred razclenjevanjem odgovora tujega streznika (RSS indeksatorja Useneta). Neodvisni pregled
 * 12. 11. 2026 (N1): DOM nad odgovorom z nekaj tisoc gnezdenimi elementi (<a><a><a>...) sesuje nit s StackOverflowError (ni
 * Exception - catch ga ne ujame). En prehod, brez rekurzije (kot [JsonGlobinaPravila]); komentarji, CDATA, PI in DOCTYPE se preskocijo.
 */
object XmlGlobinaPravila {
    /** RSS ima ~5 ravni; 32 je ze obilno. */
    const val NAJVEC_GLOBINA = 32

    fun globina(xml: String): Int {
        var trenutna = 0
        var najvec = 0
        var i = 0
        val n = xml.length
        while (i < n) {
            if (xml[i] != '<') { i++; continue }
            when {
                xml.startsWith("<!--", i) -> { val k = xml.indexOf("-->", i + 4); if (k < 0) return najvec; i = k + 3 }
                xml.startsWith("<![CDATA[", i) -> { val k = xml.indexOf("]]>", i + 9); if (k < 0) return najvec; i = k + 3 }
                xml.startsWith("<?", i) -> { val k = xml.indexOf("?>", i + 2); if (k < 0) return najvec; i = k + 2 }
                xml.startsWith("<!", i) -> { val k = xml.indexOf('>', i + 2); if (k < 0) return najvec; i = k + 1 }
                xml.startsWith("</", i) -> {
                    val k = xml.indexOf('>', i + 2); if (k < 0) return najvec
                    if (trenutna > 0) trenutna--
                    i = k + 1
                }
                else -> {
                    // zacetna oznaka: do '>' zunaj narekovajev atributov
                    var j = i + 1
                    var narekovaj = 0.toChar()
                    while (j < n) {
                        val c = xml[j]
                        if (narekovaj != 0.toChar()) { if (c == narekovaj) narekovaj = 0.toChar() }
                        else if (c == '"' || c == '\'') narekovaj = c
                        else if (c == '>') break
                        j++
                    }
                    if (j >= n) return najvec
                    val samozaprta = xml[j - 1] == '/'
                    if (!samozaprta) { trenutna++; if (trenutna > najvec) najvec = trenutna }
                    i = j + 1
                }
            }
        }
        return najvec
    }
}
