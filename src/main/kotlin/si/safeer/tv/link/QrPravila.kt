package si.safeer.tv.link

/**
 * Cista pravila kode QR (prijava s QR in pridruzitev) - brez Androida, preverljiva v JVM (tests/QrPravilaTest.kt).
 *
 * Zunanji pregled kode 8. 10. 2026: prijava s QR je sprejela odtis potrdila s 16 do 64 znaki in ga primerjala s
 * startsWith (predpona je sibkejsa od enakosti), zaupnik HubTls pa je zahteval cel odtis - koda s 16 znaki (tako jo je
 * delal racunalnik) zato rokovanja ni prestala. Zasebni naslov je bil predpona niza (»10.evil.com« se zacne z »10.«).
 */
object QrPravila {
    private val ODTIS = Regex("^[0-9a-f]{64}$")

    /** Odtis potrdila v kodi: vedno cel SHA-256 (64 malih sestnajstiskih znakov). */
    fun veljavenOdtis(odtis: String): Boolean = ODTIS.matches(odtis)

    /** Videni odtis potrdila se mora z odtisom iz kode ujemati v celoti (primerjava v stalnem casu). */
    fun odtisUstreza(videni: String?, izKode: String): Boolean {
        if (videni == null || !veljavenOdtis(izKode)) return false
        val v = videni.lowercase()
        return veljavenOdtis(v) && java.security.MessageDigest.isEqual(v.toByteArray(Charsets.US_ASCII), izKode.toByteArray(Charsets.US_ASCII))
    }

    /** Zasebni naslov IPv4 (10/8, 172.16/12, 192.168/16): stiri desetiske oktete brez vodilnih nicel, ne predpona niza. */
    fun jeZasebniIpv4(h: String?): Boolean {
        val deli = h?.split('.') ?: return false
        if (deli.size != 4) return false
        val o = IntArray(4)
        for ((i, d) in deli.withIndex()) {
            if (d.isEmpty() || d.length > 3 || !d.all { it in '0'..'9' } || (d.length > 1 && d[0] == '0')) return false
            o[i] = d.toInt()
            if (o[i] > 255) return false
        }
        return o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
    }

    /**
     * Naslov sredisca ali Huba v kodi (»a«): zasebni IPv4 in vrata 1-65535. Pregled 28. 10. 2026: povezava
     * safeer://link/qr?j=... je napravo brez vprasanja preselila v tuj Hub, tudi na javnem naslovu.
     */
    fun zasebniNaslovSredisca(naslov: String): Boolean {
        val gostitelj = naslov.substringBeforeLast(':', "")
        val vrata = naslov.substringAfterLast(':', "")
        if (vrata.isEmpty() || vrata.length > 5 || !vrata.all { it in '0'..'9' } || (vrata.length > 1 && vrata[0] == '0')) return false
        return vrata.toInt() in 1..65535 && jeZasebniIpv4(gostitelj)
    }

    /** Pridruzitev bi napravo preselila iz Safeer Linka, ki mu ze zaupa (drug odtis potrdila) - uporabnika na to opozorimo. */
    fun preselitev(imaZeton: Boolean, pripetiOdtis: String?, odtisIzKode: String): Boolean =
        imaZeton && !pripetiOdtis.isNullOrBlank() && !pripetiOdtis.equals(odtisIzKode, ignoreCase = true)

    /** Odtis za prikaz uporabniku: prvih 16 znakov v skupinah po stiri (»a1b2 c3d4 e5f6 a7b8«). */
    fun kratekOdtis(odtis: String): String = odtis.take(16).chunked(4).joinToString(" ")
}
