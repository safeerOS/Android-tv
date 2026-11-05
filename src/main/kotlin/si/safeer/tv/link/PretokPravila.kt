package si.safeer.tv.link

/**
 * Kam sme pomocnik sprotne pretvorbe (Pretok) brati izvirnik - cista pravila brez Androida (tests/PretokPravilaTest.kt).
 *
 * Vodja 5. 11. 2026: Pretok vira ni zavrnil, ce je bil 127.0.0.1, localhost ali povezavno-lokalen naslov; Linux
 * core/link_sprotno.vrsta_naslova ga zavrne (`nedovoljen_cilj`). Brez tega bi naprava v Linku prek pomocnika brala
 * storitve, ki poslusajo samo na tej napravi (krajevni streznik Useneta, torrent motor, ADB), in metapodatke oblaka
 * (169.254.169.254). Isto pravilo kot Linux: zanka, povezavno-lokalni, vecvrstni, nedoloceni in rezervirani naslovi
 * so prepovedani - tudi zaviti v IPv6 (::ffff:127.0.0.1, ::127.0.0.1, 6to4 2002::/16, NAT64 64:ff9b::/96).
 */
object PretokPravila {
    private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

    /** Ali je ime gostitelja ta naprava sama (localhost in *.localhost, kot RFC 6761). */
    fun jeImeZanke(gost: String): Boolean {
        val g = gost.trim().lowercase().trimEnd('.')
        return g == "localhost" || g.endsWith(".localhost")
    }

    /** Bajti naslova IP iz zapisa (brez DNS); null = ni zapis IP. */
    fun bajti(naslov: String): ByteArray? {
        val n = naslov.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
        if (IPV4.matches(n)) {
            val d = n.split('.').map { it.toInt() }
            if (d.any { it > 255 }) return null
            return ByteArray(4) { d[it].toByte() }
        }
        if (':' !in n || !n.all { it.isLetterOrDigit() || it == ':' || it == '.' }) return null
        return try { java.net.InetAddress.getByName(n).address } catch (_: Exception) { null }
    }

    private fun v4IzV6(b: ByteArray): ByteArray? {
        if (b.size != 16) return null
        val u = IntArray(16) { b[it].toInt() and 0xFF }
        if ((0 until 10).all { u[it] == 0 } && u[10] == 0xFF && u[11] == 0xFF) return b.copyOfRange(12, 16)   // ::ffff:a.b.c.d
        if (u[0] == 0x20 && u[1] == 0x02) return b.copyOfRange(2, 6)                                         // 6to4
        if (u[0] == 0 && u[1] == 0x64 && u[2] == 0xFF && u[3] == 0x9B && (4 until 12).all { u[it] == 0 }) return b.copyOfRange(12, 16) // NAT64
        if ((0 until 12).all { u[it] == 0 }) return b.copyOfRange(12, 16)                                      // ::a.b.c.d, :: in ::1
        return null
    }

    /** Prepovedan naslov IP (bajti): zanka, povezavno-lokalni, vecvrstni, nedoloceni, rezervirani. */
    fun prepovedan(b: ByteArray): Boolean {
        val v4 = if (b.size == 4) b else v4IzV6(b)
        if (v4 != null) {
            val a = v4[0].toInt() and 0xFF; val c = v4[1].toInt() and 0xFF
            return a == 127 || a == 0 || (a == 169 && c == 254) || a >= 224
        }
        val u0 = b[0].toInt() and 0xFF; val u1 = b[1].toInt() and 0xFF
        return u0 == 0xFF || (u0 == 0xFE && (u1 and 0xC0) == 0x80)          // ff00::/8 vecvrstni, fe80::/10 povezavno-lokalni
    }

    /** Gostitelj iz naslova vira ni dovoljen ze po zapisu (brez DNS): ime zanke ali prepovedan naslov IP. */
    fun gostiteljPrepovedan(gost: String): Boolean {
        if (gost.isBlank() || jeImeZanke(gost)) return true
        val b = bajti(gost) ?: return false
        return prepovedan(b)
    }

    /** Vsi razreseni naslovi imena morajo biti dovoljeni (kot Linux dovoljeni_naslovi: ce ni eden, ni nobeden). */
    fun razreseniDovoljeni(naslovi: List<ByteArray>): Boolean = naslovi.isNotEmpty() && naslovi.none { prepovedan(it) }
}
