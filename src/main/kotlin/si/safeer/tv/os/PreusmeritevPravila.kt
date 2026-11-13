package si.safeer.tv.os

import java.net.URL

/**
 * Preusmeritve odgovorov dodatkov (neodvisni pregled 12. 11. 2026, S1): glava X-Safeer-Config lahko nosi skrivne nastavitve
 * dodatka, HttpURLConnection pa jo ob preusmeritvi poslje tudi tujemu gostitelju. Preusmeritve sledimo rocno (najvec
 * [NAJVEC_SKOKOV]), skrivne glave samo prvotnemu gostitelju; https se nikoli ne spusti na http.
 */
object PreusmeritevPravila {
    const val NAJVEC_SKOKOV = 5

    fun jePreusmeritev(koda: Int): Boolean = koda == 301 || koda == 302 || koda == 303 || koda == 307 || koda == 308

    /** Naslov naslednjega skoka (relativen Location se razresi proti [trenutni]); null, ce ni veljaven, ali bi https spustil na http. */
    fun naslednji(trenutni: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        val cilj = try { URL(URL(trenutni), location.trim()) } catch (_: Exception) { return null }
        val shema = cilj.protocol.lowercase()
        if (shema != "http" && shema != "https") return null
        if (trenutni.startsWith("https://", ignoreCase = true) && shema != "https") return null
        if (!cilj.userInfo.isNullOrEmpty()) return null
        return cilj.toString()
    }

    /** Ali glava z nastavitvami smeda na [cilj]: samo isti gostitelj in ista vrata kot prvotni naslov. */
    fun glavaSmeNaprej(prvotni: String, cilj: String): Boolean = try {
        val a = URL(prvotni); val b = URL(cilj)
        a.host.equals(b.host, ignoreCase = true) && a.port == b.port && a.protocol.equals(b.protocol, ignoreCase = true)
    } catch (_: Exception) { false }

    /**
     * Rocno sledenje preusmeritvam za vsako povezavo (dodatki Stremio, slike plakatov; pregled 12. 11. 2026, S1 in S2).
     * [odpri] dobi naslov skoka ter dve oznaki, ki ju ODLOCA to pravilo, ne klicatelj: [skrivne] (glava z nastavitvami dodatka:
     * samo prvotni gostitelj, shema in vrata) in [prviSkok] (piskotek WebView in Referer: samo prvi skok, nikoli po preusmeritvi).
     * Zasebni naslovi (domace omrezje, 127.0.0.1) se NE blokirajo: osebni dodatki so tam po zasnovi (lokalni Usenet).
     * Preusmeritve ne izklopimo: dodatki jih uporabljajo. Zadnji odgovor vrne odprt; vmesne zapre [zapri].
     */
    fun <C> sledi(prvotni: String, odpri: (naslov: String, skrivne: Boolean, prviSkok: Boolean) -> C,
                  koda: (C) -> Int, location: (C) -> String?, zapri: (C) -> Unit): C {
        var naslov = prvotni
        var skok = 0
        while (true) {
            val c = odpri(naslov, glavaSmeNaprej(prvotni, naslov), skok == 0)
            if (!jePreusmeritev(koda(c)) || skok >= NAJVEC_SKOKOV) return c
            val naprej = naslednji(naslov, location(c)) ?: return c
            zapri(c)
            naslov = naprej
            skok++
        }
    }
}
