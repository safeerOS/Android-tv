package si.safeer.tv.link

/**
 * Kam sme pomocnik sprotne pretvorbe (Pretok) brati izvirnik - cista pravila brez Androida (tests/PretokPravilaTest.kt).
 *
 * Vodja 5. 11. 2026: Pretok vira ni zavrnil, ce je bil 127.0.0.1, localhost ali povezavno-lokalen naslov; Linux
 * core/link_sprotno.vrsta_naslova ga zavrne (`nedovoljen_cilj`). Brez tega bi naprava v Linku prek pomocnika brala
 * storitve, ki poslusajo samo na tej napravi (krajevni streznik Useneta, torrent motor, ADB), in metapodatke oblaka
 * (169.254.169.254). Isto pravilo kot Linux: zanka, povezavno-lokalni, vecvrstni, nedoloceni in rezervirani naslovi
 * so prepovedani - tudi zaviti v IPv6 (::ffff:127.0.0.1, ::127.0.0.1, 6to4 2002::/16, NAT64 64:ff9b::/96 in lokalna
 * predpona NAT64 64:ff9b:1::/48, RFC 8215 - kot SondaVeriga.zasebenCilj; pregled 6. 11. 2026, P4).
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
        // Lokalna predpona NAT64 (64:ff9b:1::/48): prevajalnik v omrezju jo lahko preslika na 127.0.0.1 ali 169.254.169.254.
        if (u[0] == 0 && u[1] == 0x64 && u[2] == 0xFF && u[3] == 0x9B && u[4] == 0 && u[5] == 1) return b.copyOfRange(12, 16)
        if ((0 until 12).all { u[it] == 0 }) return b.copyOfRange(12, 16)                                      // ::a.b.c.d, :: in ::1
        return null
    }

    /** Isti naslov IP; preslikani IPv4 v IPv6 (::ffff:a.b.c.d) je enak svojemu IPv4. */
    fun enakNaslov(a: ByteArray, b: ByteArray): Boolean =
        (v4IzV6(a) ?: a).contentEquals(v4IzV6(b) ?: b)

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

    /**
     * Zapis zahteve video.stream: http(s), odtis 64 znakov. Pregled 6. 11. 2026 (P3): z odtisom (vir na pripeti napravi,
     * zahteva nosi zeton X-Safeer-Token) samo https - po http bi zeton videl vsak v omrezju in pripetega potrdila ni.
     */
    fun zahtevaVeljavna(url: String, odtis: String): Boolean {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return false
        if (odtis.isNotBlank() && (odtis.length != 64 || !url.startsWith("https://"))) return false
        return true
    }

    /**
     * Premalo prostora za tok: [prostor] (usableSpace mape, -1 = ni meritve) manj kot ocena toka + rezerva naprave.
     * Brez meritve (prvi tok po zagonu) odloci nit ozadja (U3, pregled 6. 11. 2026).
     */
    fun premaloProstora(prostor: Long, ocena: Long, rezerva: Long): Boolean = prostor >= 0 && rezerva >= 0 && prostor < ocena + rezerva

    /** Tok za izrinjenje: [nastal] = zaporedna stevilka nastanka (vecja = novejsi). */
    class Opis(val id: String, val nastal: Long, val koncano: Boolean, val bralcev: Int, val zadnjiBralecMs: Long)

    /**
     * Pregled 6. 11. 2026 (P2): prej `tokovi.keys.firstOrNull()` na ConcurrentHashMap - nakljucen vrstni red, lahko je vzel
     * pravkar dodani tok. Zdaj: koncani tokovi brez bralca vsaj [brezBralcaMs] gredo ven, nato najstarejsi po nastanku,
     * dokler jih ni najvec [najvec]. Novi tok [novi] nikoli.
     */
    fun izrini(tokovi: List<Opis>, novi: String, najvec: Int, zdajMs: Long, brezBralcaMs: Long): List<String> {
        val koncani = tokovi.filter { it.id != novi && it.koncano && it.bralcev <= 0 && zdajMs - it.zadnjiBralecMs >= brezBralcaMs }
            .map { it.id }.toSet()
        val ostali = tokovi.filter { it.id !in koncani }
        val prevec = (ostali.size - najvec).coerceAtLeast(0)
        return koncani.toList() + ostali.filter { it.id != novi }.sortedBy { it.nastal }.take(prevec).map { it.id }
    }
}
