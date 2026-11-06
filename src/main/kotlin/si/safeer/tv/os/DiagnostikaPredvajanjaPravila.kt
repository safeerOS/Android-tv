package si.safeer.tv.os

/**
 * Diagnostika predvajanja (lastnik 5. 11. 2026: »Naj bo dejavno samo, ko sporocimo, da potrebujemo.«): kratek dnevnik
 * zadnjih neuspelih poskusov predvajanja v Medijskem centru. Privzeto izklopljena; vklop v »Pravno in o aplikaciji« velja
 * [TRAJANJE_MS], nato se sama izklopi in dnevnik izbrise. V vnos gre naslov, vrsta, vrsta vira, gostitelj in razlog -
 * nikoli kljuci, zetoni, celi naslovi URL ali podatki racuna ([cisti], [gostitelj]). Cista pravila -
 * tests/DiagnostikaPredvajanjaPravilaTest.kt.
 */
object DiagnostikaPredvajanjaPravila {
    const val TRAJANJE_MS = 24L * 3600 * 1000
    const val NAJVEC_VNOSOV = 50
    const val NAJVEC_ZNAKOV = 300
    const val OZNAKA = "SafeerDiag"

    /** Vklop velja, ce je rok v prihodnosti in najvec [TRAJANJE_MS] naprej (premaknjena ura ga ne podaljsa). */
    fun velja(doMs: Long, zdajMs: Long): Boolean = doMs > zdajMs && doMs - zdajMs <= TRAJANJE_MS

    /** Gostitelji, kjer je prvi del imena uporabnisko ime (osebni primerek dodatka) - ta del skrijemo. */
    private val OSEBNI_GOSTITELJI = listOf("elfhosted.com", "elfhosted.cc", "herokuapp.com", "duckdns.org", "ngrok.io", "ngrok-free.app")
    private val URL = Regex("(?i)\\b[a-z][a-z0-9+.-]{1,15}://[^\\s\"'<>]+")
    private val GOSTITELJ = Regex("(?i)^[a-z][a-z0-9+.-]{1,15}://(?:[^@/?#]*@)?(\\[[0-9a-f:.]+]|[^:/?#\\s]+)")
    private val EPOSTA = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val KLJUC_VREDNOST = Regex("(?i)\\b(api[_-]?key|apikey|key|token|access[_-]?token|auth|password|passwd|pass|geslo|kljuc|zeton|secret|session|user|username|uporabnik|sig|signature)\\s*[=:]\\s*\\S+")
    private val ZETON = Regex("[A-Za-z0-9_\\-]{20,}")
    private val PREVEC_PRESLEDKOV = Regex("\\s+")

    /** Del imena, ki je videti kot zeton (dolg, s stevkami in crkami ali sestnajstisko). */
    private fun jeZeton(del: String): Boolean =
        del.length >= 16 && (del.any { it.isDigit() } && del.any { it.isLetter() } || del.all { it.isLetterOrDigit() && it.lowercaseChar() in "0123456789abcdef" })

    /** Samo gostitelj naslova (brez sheme, uporabnika, vrat, poti in poizvedbe), osebni del in zetoni skriti; "" ce ga ni. */
    fun gostitelj(naslov: String?): String {
        val g = GOSTITELJ.find(naslov?.trim().orEmpty())?.groupValues?.get(1)?.lowercase(java.util.Locale.ROOT)?.trimEnd('.') ?: return ""
        // Lasten pomocnik na tej napravi (Usenet, torrent, sprotna pretvorba).
        if (g == "localhost" || g.startsWith("127.") || g == "[::1]") return "ta-naprava"
        if (g.startsWith("[") || Regex("^[0-9.]+$").matches(g)) return if (domacNaslov(g)) "domace-omrezje" else g
        val deli = g.split('.').map { if (jeZeton(it)) "*" else it }.toMutableList()
        val osebni = OSEBNI_GOSTITELJI.any { g.endsWith(".$it") }
        if (osebni && deli.size > 2) deli[0] = "*"
        return deli.joinToString(".").take(80)
    }

    /** Naslov v domacem omrezju (naprava v Linku, NAS): naslov IP ne gre v dnevnik. */
    private fun domacNaslov(g: String): Boolean {
        val d = g.trim('[', ']')
        return d.startsWith("10.") || d.startsWith("192.168.") || d.startsWith("127.") || d.startsWith("169.254.") ||
            Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(d) || d.startsWith("fe80") || d.startsWith("fd") || d == "::1"
    }

    /** Prosto besedilo (naslov, razlog) brez URL-jev (ostane gostitelj), e-naslovov, parov kljuc=vrednost in zetonov. */
    fun cisti(besedilo: String?, najvec: Int = 120): String {
        var t = besedilo.orEmpty()
        t = URL.replace(t) { gostitelj(it.value).ifEmpty { "*" } }
        t = EPOSTA.replace(t, "*")
        t = KLJUC_VREDNOST.replace(t) { it.groupValues[1] + "=*" }
        t = ZETON.replace(t) { if (jeZeton(it.value)) "*" else it.value }
        return PREVEC_PRESLEDKOV.replace(t.replace('|', '/'), " ").trim().take(najvec)
    }

    /** En poskus: vrsta vira (usenet, debrid, webtor, p2p, neposredno, splet ...) in gostitelj dodatka ali streznika. */
    data class Poskus(val vir: String, val gostitelj: String, val razlog: String)

    fun poskus(vir: String, naslovVira: String?, razlog: String): Poskus =
        Poskus(cisti(vir, 20).lowercase(java.util.Locale.ROOT), gostitelj(naslovVira), cisti(razlog, 80))

    /** Vrstica dnevnika (ena vrstica, brez tabulatorjev in novih vrstic). [cas] = npr. "2026-11-05 19:25". */
    fun vrstica(cas: String, naslov: String?, vrsta: String?, poskusi: List<Poskus>, razlog: String): String {
        val viri = poskusi.takeLast(8).joinToString(", ") { p ->
            p.vir + (if (p.gostitelj.isNotEmpty()) "@" + p.gostitelj else "") + (if (p.razlog.isNotEmpty()) " (" + p.razlog + ")" else "")
        }
        return "${cisti(cas, 20)} | ${cisti(vrsta, 20).ifEmpty { "?" }} | ${cisti(naslov, 80).ifEmpty { "?" }} | viri: ${viri.ifEmpty { "-" }} | razlog: ${cisti(razlog, 80)}".take(NAJVEC_ZNAKOV)
    }

    /** Doda vnos in obdrzi zadnjih [NAJVEC_VNOSOV]. */
    fun dodaj(dnevnik: List<String>, vnos: String): List<String> = (dnevnik + vnos).takeLast(NAJVEC_VNOSOV)

    /**
     * Kaj zapisati ob neuspehu: null = nic (izklopljena, dnevnik ze prazen), prazen seznam = izbrisi (rok je potekel),
     * sicer nov dnevnik z vnosom. [vnos] se izracuna samo, ce je diagnostika vklopljena.
     */
    fun zapis(doMs: Long, zdajMs: Long, dnevnik: List<String>, vnos: () -> String): List<String>? = when {
        velja(doMs, zdajMs) -> dodaj(dnevnik, vnos())
        dnevnik.isNotEmpty() || doMs != 0L -> emptyList()
        else -> null
    }

    /** Poskusi zadnjih [OKNO_POSKUSOV_MS] pred neuspehom sodijo k istemu predvajanju. */
    const val OKNO_POSKUSOV_MS = 5L * 60 * 1000
}
