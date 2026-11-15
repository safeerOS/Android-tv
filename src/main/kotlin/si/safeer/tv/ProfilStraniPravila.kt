package si.safeer.tv

/** Ciste odlocitve za SiteProfile (popravki TV-2, 15. 11. 2026); preizkus tests/PopravkiTv2Test.kt. */
object ProfilStraniPravila {
    /**
     * Stran gledalca deljenega zaslona (Safeer Link, sredisce: /cast/screen/<id>/view?k=<kljuc>). P3/SL-S1: prej podniz
     * kjerkoli v naslovu - tuja stran je s history.replaceState(null, '', '/cast/screen/x/view') sama skrila naslovno
     * vrstico. replaceState ne more zamenjati izvora, zato zdaj odloca gostitelj: sredisce je v lokalnem omrezju
     * (zasebni IPv4, localhost, ime brez pike ali .local/.lan/.home.arpa/.internal), pot pa mora biti tocno taka.
     */
    fun deljenZaslon(url: String?): Boolean {
        val n = DashVarnostPravila.razcleni(url) ?: return false
        if (!lokalniGostitelj(n.gostitelj)) return false
        val deli = n.pot.split('/')
        if (deli.size != 5 || deli[0] != "" || deli[1] != "cast" || deli[2] != "screen" || deli[4] != "view") return false
        val id = deli[3]
        if (id.isEmpty() || id.length > 128 || !id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }) return false
        return n.poizvedba.split('&').any { it.startsWith("k=") && it.length > 2 }
    }

    fun lokalniGostitelj(g: String): Boolean {
        if (g.startsWith("[")) {
            val a = g.trim('[', ']')
            return a == "::1" || a.startsWith("fc") || a.startsWith("fd") || a.startsWith("fe8") || a.startsWith("fe9") ||
                a.startsWith("fea") || a.startsWith("feb")
        }
        if (g == "localhost") return true
        val deli = g.split('.')
        if (deli.size == 4 && deli.all { d -> d.isNotEmpty() && d.length <= 3 && d.all { it in '0'..'9' } && (d == "0" || !d.startsWith("0")) }) {
            val b = deli.map { it.toInt() }
            if (b.any { it > 255 }) return false
            return b[0] == 10 || b[0] == 127 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) ||
                (b[0] == 169 && b[1] == 254)
        }
        // Stevilski zapisi, ki niso strogi d.d.d.d, niso dovoljeni (lahko pomenijo javni naslov).
        if (deli.last().all { it in '0'..'9' } || deli.last().startsWith("0x")) return false
        if (deli.size == 1) return true
        return g.endsWith(".local") || g.endsWith(".lan") || g.endsWith(".home.arpa") || g.endsWith(".internal")
    }

    enum class OgledOk { NIC, PREDVAJAJ, CELOZASLONSKO }

    data class OgledOkIzid(val dejanje: OgledOk, val zadnjiKlik: Long)

    const val DVOJNI_OK_MS = 380L

    /**
     * OK na strani za ogled (P10): ukrepa samo NOV pritisk (DOWN, repeatCount 0). Prej je drzan OK (ponovitve vsakih
     * ~50 ms, vse pod 380 ms) izmenicno preklapljal celozaslonski nacin, dokler kazalec ni prevzel tipke.
     */
    fun okNaOgledu(dol: Boolean, ponovitev: Int, zdaj: Long, zadnjiKlik: Long): OgledOkIzid = when {
        !dol || ponovitev > 0 -> OgledOkIzid(OgledOk.NIC, zadnjiKlik)
        zdaj - zadnjiKlik < DVOJNI_OK_MS -> OgledOkIzid(OgledOk.CELOZASLONSKO, zdaj)
        else -> OgledOkIzid(OgledOk.PREDVAJAJ, zdaj)
    }

    /**
     * Besedilo za niz JS v enojnih narekovajih (P10, handleSearch). Prej samo \ in ': iskanje z novo vrstico (lepljenje,
     * glasovni vnos) je dalo neveljaven JS, ki se je tiho izgubil. Vse kontrolne znake, U+2028/U+2029 in < ubezemo.
     */
    fun jsNiz(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003c")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> if (c < ' ' || c == '\u007f') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        return sb.toString()
    }
}
