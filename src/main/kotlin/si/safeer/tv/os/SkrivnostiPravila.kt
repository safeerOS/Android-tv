package si.safeer.tv.os

import java.security.MessageDigest

/**
 * Webtor rezerva (pot A, 25. 10. 2026): naslovi dodatkov, ki nosijo poverilnico - osebni zeton Webtorja
 * (»https://webtor.io/token/<zeton>/stremio«, kratka povezava »/s/<koda>«), Torrentio z debrid kljucem v nastavitvah
 * (»/realdebrid=<kljuc>|sort=...«), nastavitve v base64 (Comet, MediaFusion), uporabnik in geslo v naslovu. Tak naslov
 * je geslo racuna:
 *  - shranjen je sifriran ([SkrivniDodatki], Android Keystore - kot debrid kljuci);
 *  - v aplikaciji (id-ji kartic, zgodovina, seznami, Link, dnevnik) velja NADOMESTEK brez skrivnosti
 *    »https://<gostitelj>/safeer-skrito-<odtis>/...«: pravi naslov se vstavi sele tik pred omreznim klicem;
 *  - drugim napravam gre samo v ozji krog, po isti poti kot debrid kljuci (s potrditvijo tam), nikoli z navadnim
 *    usklajevanjem virov ([MedijskiViri] VRSTE_ZA_USKLADITEV).
 * Ciste funkcije (tests/SkrivnostiPravilaTest.kt, skupni primeri tests/skupni-primeri/webtor-rezerva.json).
 */
object SkrivnostiPravila {
    const val OZNAKA = "safeer-skrito-"
    private val RX_NADOMESTEK = Regex("^(https?://[^/?#@\\s]+/" + OZNAKA + "[0-9a-f]{16})(?=$|[/?#])")

    /** Imena polj v nastavitvah dodatka, katerih vrednost je skrivnost (»realdebrid=...«, »"apiKey":"..."«). */
    private val RX_KLJUC = Regex("(?i)(?:^|[|&;,{\\s\"'])\"?(?:realdebrid|real-debrid|alldebrid|torbox|premiumize|debridlink|debrid-link|" +
        "offcloud|putio|easydebrid|api[_-]?key|access[_-]?token|token|password|passwd|secret|auth|cookie|session|key)\"?\\s*[=:]")
    private val RX_NEPROZOREN = Regex("[A-Za-z0-9_\\-+/=.~]+")
    /** Odsek za temi je skrivnost (»/token/<zeton>/«, »/key/<kljuc>/«). */
    private val PREDHODNIKI = setOf("token", "key", "apikey", "api_key", "api-key", "auth", "secret")
    /** Zeton, kljuc, base64 nastavitve: dolg niz brez locil, s crkami in stevkami. */
    private const val NAJMANJ_DOLG = 20

    /** Odsek poti (brez »/«) je skrivnost. [prejsnji] = odsek pred njim, [webtor] = gostitelj webtor.io (»/s/<koda>«). */
    fun jeSkrivenOdsek(odsek: String, prejsnji: String = "", webtor: Boolean = false): Boolean {
        if (odsek.isEmpty() || odsek.startsWith(OZNAKA)) return false
        if (prejsnji.lowercase() in PREDHODNIKI) return true
        if (webtor && prejsnji == "s") return true
        val d = odkodiraj(odsek)
        if (RX_KLJUC.containsMatchIn(d)) return true
        return d.length >= NAJMANJ_DOLG && RX_NEPROZOREN.matches(d) && d.any { it.isDigit() } && d.any { it.isLetter() }
    }

    fun jeWebtorGostitelj(gostitelj: String): Boolean {
        val g = gostitelj.lowercase().substringBefore(':').trimEnd('.')
        return g == "webtor.io" || g.endsWith(".webtor.io")
    }

    private class Deli(val shema: String, val zUporabnikom: Boolean, val gostitelj: String, val zacetekPoti: Int, val pot: String)

    private fun razcleni(naslov: String): Deli? {
        val i = naslov.indexOf("://")
        if (i <= 0) return null
        val shema = naslov.substring(0, i).lowercase()
        if (shema != "http" && shema != "https") return null
        val zacetek = i + 3
        var konecAvt = naslov.length
        for (j in zacetek until naslov.length) if (naslov[j] == '/' || naslov[j] == '?' || naslov[j] == '#') { konecAvt = j; break }
        val avtoriteta = naslov.substring(zacetek, konecAvt)
        val afna = avtoriteta.lastIndexOf('@')
        val gostitelj = (if (afna >= 0) avtoriteta.substring(afna + 1) else avtoriteta).lowercase()
        if (gostitelj.isEmpty() || gostitelj.any { it <= ' ' }) return null
        var konecPoti = naslov.length
        for (j in konecAvt until naslov.length) if (naslov[j] == '?' || naslov[j] == '#') { konecPoti = j; break }
        return Deli(shema, afna >= 0, gostitelj, konecAvt, naslov.substring(konecAvt, konecPoti))
    }

    /**
     * Del naslova do vkljucno PRVEGA skrivnega odseka poti (»https://webtor.io/token/<zeton>«) - ta del zamenja nadomestek,
     * ostanek (»/stremio/resolve/<JWT>«) ostane. Uporabnik/geslo v naslovu brez skrivnega odseka: »shema://u:g@gostitelj«.
     * null = naslov nima skrivnosti (ali je ze nadomestek). Poizvedbe ne gledamo: dodatku jo [MedijskiViri.preveriDodatek]
     * odreze, tokov z zetonom v poizvedbi (podpis CDN) pa ne zakrivamo.
     */
    fun skrivnaPredpona(naslov: String): String? {
        val n = naslov.trim()
        if (RX_NADOMESTEK.containsMatchIn(n)) return null
        val d = razcleni(n) ?: return null
        val webtor = jeWebtorGostitelj(d.gostitelj)
        var prejsnji = ""
        var konec = d.zacetekPoti
        for ((k, o) in d.pot.split('/').withIndex()) {
            if (k == 0) continue
            konec += 1 + o.length
            if (jeSkrivenOdsek(o, prejsnji, webtor)) return n.substring(0, konec)
            if (o.isNotEmpty()) prejsnji = o
        }
        return if (d.zUporabnikom) n.substring(0, d.zacetekPoti) else null
    }

    fun jeSkrivenNaslov(naslov: String): Boolean = skrivnaPredpona(naslov) != null

    /** Prvih 16 znakov SHA-256 (hex): enak na vseh napravah, iz njega se zetona ne da izracunati. */
    fun odtis(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(16)

    /** Nadomestna osnova za skrivno predpono: »https://webtor.io/safeer-skrito-<odtis>« (brez uporabnika, gesla, zetona). */
    fun nadomestek(predpona: String): String? {
        val d = razcleni(predpona) ?: return null
        return "${d.shema}://${d.gostitelj}/$OZNAKA${odtis(predpona)}"
    }

    /** Naslov s skrivnostjo -> isti naslov z nadomestkom (ostanek poti ostane); brez skrivnosti nespremenjen. */
    fun vNadomestek(naslov: String): String {
        val n = naslov.trim()
        val p = skrivnaPredpona(n) ?: return n
        val z = nadomestek(p) ?: return n
        return z + n.substring(p.length)
    }

    /** Nadomestna osnova v naslovu (»https://h/safeer-skrito-<odtis>«) ali null. */
    fun nadomestnaOsnova(naslov: String): String? = RX_NADOMESTEK.find(naslov.trim())?.groupValues?.get(1)

    fun jeNadomestek(naslov: String): Boolean = nadomestnaOsnova(naslov) != null

    /** Vir sme z navadnim usklajevanjem virov na vse naprave v Linku: ne nosi skrivnosti in ni nadomestek. */
    fun smeMedVseNaprave(naslov: String): Boolean = !jeSkrivenNaslov(naslov) && !jeNadomestek(naslov)

    /** Za zaslon in dnevnik: »https://webtor.io/…/stremio/manifest.json« - skrivnost ali nadomestek zakrit z »…«. */
    fun zaPrikaz(naslov: String): String {
        val n = naslov.trim()
        val z = nadomestnaOsnova(n)
        val osnova = z ?: skrivnaPredpona(n) ?: return n
        val d = razcleni(osnova) ?: return "…"
        return "${d.shema}://${d.gostitelj}/…" + n.substring(osnova.length).substringBefore('?').substringBefore('#')
    }

    const val NAJVEC_PREJETIH = 20

    /** Skrivni dodatki, ki jih je poslala druga naprava v ozjem krogu: samo veljavni naslovi s skrivnostjo, najvec 20. */
    fun prejetiDodatki(seznam: List<String?>): List<String> = seznam.asSequence().filterNotNull().map { it.trim() }
        .filter { it.length in 12..2048 && (it.startsWith("https://") || it.startsWith("http://")) && it.none { c -> c < ' ' } && jeSkrivenNaslov(it) }
        .distinct().take(NAJVEC_PREJETIH).toList()

    private fun hex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** Odstotkovno kodiranje (%XX, UTF-8) brez »+« -> presledek; neveljavno ostane, kot je. */
    fun odkodiraj(s: String): String {
        if ('%' !in s) return s
        val sb = StringBuilder()
        val bajti = java.io.ByteArrayOutputStream()
        fun izprazni() { if (bajti.size() > 0) { sb.append(String(bajti.toByteArray(), Charsets.UTF_8)); bajti.reset() } }
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && i + 2 < s.length && hex(s[i + 1]) && hex(s[i + 2])) {
                bajti.write(s.substring(i + 1, i + 3).toInt(16)); i += 3
            } else { izprazni(); sb.append(s[i]); i++ }
        }
        izprazni()
        return sb.toString()
    }
}
