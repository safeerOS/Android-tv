package si.safeer.tv

import android.content.Context
import java.net.IDN
import java.net.InetAddress
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Eno polje za iskanje in naslove. Odloci, ali je vnos spletni naslov ali iskanje, in nikoli
 * ne sestavi cudnega URL-ja: kar ni zanesljivo naslov, gre v izbrani iskalnik.
 */
object SmartOmnibox {

    enum class Iskalnik(val oznaka: String, val ime: String, val iskanje: String, val predlogi: String?) {
        GOOGLE("google", "Google", "https://www.google.com/search?q=",
            "https://suggestqueries.google.com/complete/search?client=chrome&q="),
        DUCKDUCKGO("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=", "https://duckduckgo.com/ac/?type=list&q="),
        BING("bing", "Bing", "https://www.bing.com/search?q=", null),
        STARTPAGE("startpage", "Startpage", "https://www.startpage.com/do/search?q=", null),
        ECOSIA("ecosia", "Ecosia", "https://www.ecosia.org/search?q=", null),
        BRAVE("brave", "Brave Search", "https://search.brave.com/search?q=", null)
    }

    /** url = kam gremo; iskanje = vnos je iskanje; preveri = gostitelj, ki ga preverimo v DNS, preden ga odpremo. */
    data class Odlocitev(val url: String, val iskanje: Boolean, val preveri: String? = null)

    private const val PREFS = "safeer_ui_prefs"
    private const val KLJUC = "iskalnik"
    private val SHEMA_SPLET = Regex("^https?://", RegexOption.IGNORE_CASE)
    private val NEVARNE = Regex("^(javascript|vbscript|data|intent|blob|content|jar):", RegexOption.IGNORE_CASE)
    private val OZNAKA = Regex("^[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]{0,61}[\\p{L}\\p{N}])?$")
    private val TLD = Regex("^(\\p{L}{2,63}|xn--[a-z0-9-]{1,59})$")
    private val IPV4 = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")
    /** Domena v lokalnem omrezju: brez DNS preverjanja in prek http (tiskalniki, NAS, usmerjevalniki). */
    private val LOKALNE = setOf("local", "lan", "home", "internal", "localdomain", "arpa")
    private val dns = Executors.newCachedThreadPool { r -> Thread(r, "omnibox-dns").apply { isDaemon = true } }

    fun iskalnik(c: Context): Iskalnik {
        val v = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KLJUC, null)
        return Iskalnik.values().firstOrNull { it.oznaka == v } ?: Iskalnik.GOOGLE
    }

    fun nastaviIskalnik(c: Context, i: Iskalnik) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KLJUC, i.oznaka).apply()
    }

    fun iskanje(c: Context, poizvedba: String, i: Iskalnik = iskalnik(c)): String =
        i.iskanje + URLEncoder.encode(poizvedba.trim(), "UTF-8")

    fun razresi(c: Context, vnos: String, i: Iskalnik = iskalnik(c)): Odlocitev? = razresi(vnos, i.iskanje)

    /** Cista odlocitev brez Androida (da jo lahko preizkusimo). */
    fun razresi(vnos: String, predlogaIskanja: String): Odlocitev? {
        val t = vnos.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return null
        val isci = Odlocitev(predlogaIskanja + URLEncoder.encode(t, "UTF-8"), true)

        if (SHEMA_SPLET.containsMatchIn(t)) {
            val brez = t.substringAfter("://")
            val gostitelj = brez.split('/', '?', '#').first().substringAfterLast('@').substringBefore(':')
            return if (gostitelj.isEmpty() || gostitelj.contains(' ')) isci else Odlocitev(t.replace(" ", "%20"), false)
        }
        if (t.startsWith("file://", true) || t.startsWith("about:", true)) return Odlocitev(t, false)
        if (NEVARNE.containsMatchIn(t) || t.contains(' ') || t.contains('@')) return isci

        val konec = t.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) t.length else it }
        val gostiteljVrata = t.substring(0, konec)
        val ostalo = t.substring(konec)
        val gostitelj = gostiteljVrata.substringBefore(':').lowercase(Locale.ROOT).trimEnd('.')
        val vrata = if (gostiteljVrata.contains(':')) gostiteljVrata.substringAfter(':') else null
        if (vrata != null && (vrata.toIntOrNull() ?: 0) !in 1..65535) return isci
        val del = if (vrata != null) ":$vrata" else ""

        if (gostitelj == "localhost" || IPV4.matches(gostitelj)) return Odlocitev("http://$gostitelj$del$ostalo", false)

        val oznake = gostitelj.split('.')
        if (oznake.size < 2 || oznake.any { !OZNAKA.matches(it) }) return isci
        val tld = oznake.last()
        if (!TLD.matches(tld)) return isci
        val ascii = try { IDN.toASCII(gostitelj, IDN.ALLOW_UNASSIGNED) } catch (_: Exception) { return isci }
        if (tld in LOKALNE) return Odlocitev("http://$ascii$del$ostalo", false)
        return Odlocitev("https://$ascii$del$ostalo", false, preveri = ascii)
    }

    /**
     * Ali gostitelj obstaja. Neznan (tipkarska napaka, npr. youtube.cmo) -> false; ce DNS ne odgovori
     * v roku ali ni povezave, vrnemo true, da ne pokvarimo pravih naslovov.
     */
    fun obstaja(gostitelj: String, rokMs: Long = 1500): Boolean = try {
        dns.submit<Boolean> {
            try { InetAddress.getAllByName(gostitelj).isNotEmpty() } catch (_: java.net.UnknownHostException) { false }
        }.get(rokMs, TimeUnit.MILLISECONDS)
    } catch (_: Exception) {
        true
    }
}
