package si.safeer.tv.os

/**
 * Naloga 112 (pregled: zeton v naslovu toka se je kazal na zaslonu in v dnevniku - naloge 96, 97, 100): ENA pot za prikaz
 * in dnevnik naslovov. Naslov toka ali vira ima lahko uporabnisko ime in geslo (»http://u:g@...«), zeton v poti
 * (»/play/<zeton>«) ali v poizvedbi (»?token=«) - nic od tega ne sme na zaslon ali v dnevnik. Ciste funkcije, brez Androida
 * (preizkus: tests/ZasebnoTest.kt); staticna preverba tests/preveri_dnevnik.py zavrne dnevnik z naslovom mimo tega.
 */
object Zasebno {
    /** Omrezne sheme z gostiteljem (content:// ima ponudnika, ne gostitelja). */
    private val OMREZNE = setOf("http", "https", "rtsp", "rtmp", "rtmps", "ws", "wss", "ftp", "udp", "rtp", "mms", "srt")

    /** Gostitelj naslova (male crke, brez uporabniskega imena, gesla in vrat); "" = ni omrezni naslov z gostiteljem. */
    fun gostitelj(naslov: String): String {
        val n = naslov.trim()
        val i = n.indexOf("://")
        if (i <= 0 || n.substring(0, i).lowercase(java.util.Locale.ROOT) !in OMREZNE) return ""
        val avtoriteta = n.substring(i + 3).substringBefore('/').substringBefore('?').substringBefore('#')
        val brezUporabnika = avtoriteta.substringAfterLast('@')
        val gost = if (brezUporabnika.startsWith("[")) brezUporabnika.substringBefore(']') + "]" else brezUporabnika.substringBefore(':')
        return gost.lowercase(java.util.Locale.ROOT)
    }

    /**
     * Naslov za dnevnik: »shema://gostitelj/…« - brez uporabnika, gesla, vrat, poti in poizvedbe (»…« pove, da je pot bila).
     * Naslov brez gostitelja (content://, file:) samo s shemo; prazno ostane prazno.
     */
    fun zaDnevnik(naslov: String): String {
        val n = naslov.trim()
        if (n.isEmpty()) return ""
        val i = n.indexOf("://")
        val shema = if (i > 0) n.substring(0, i).lowercase(java.util.Locale.ROOT) else n.substringBefore(':', "").lowercase(java.util.Locale.ROOT)
        val gost = gostitelj(n)
        if (gost.isEmpty()) return if (shema.isNotEmpty() && shema.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) "$shema:…" else "…"
        val ostanek = n.substring(i + 3).let { a -> a.substring(minOf(a.length, a.indexOfFirst { it == '/' || it == '?' || it == '#' }.takeIf { it >= 0 } ?: a.length)) }
        return "$shema://$gost" + if (ostanek.trim('/').isNotEmpty()) "/…" else ""
    }

    /** Naslov za zaslon (vir toka): samo gostitelj; brez gostitelja "" (klicatelj pokaze kaj drugega, npr. ime seznama). */
    fun prikaz(naslov: String): String = gostitelj(naslov)
}
