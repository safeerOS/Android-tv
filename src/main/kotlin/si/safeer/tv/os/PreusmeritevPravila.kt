package si.safeer.tv.os

import java.net.URL

/**
 * Preusmeritve odgovorov dodatkov (neodvisni pregled 12. 11. 2026, S1): glava X-Safeer-Config lahko nosi skrivne nastavitve
 * dodatka, HttpURLConnection pa jo ob preusmeritvi poslje tudi tujemu gostitelju. Preusmeritve sledimo rocno (najvec
 * [NAJVEC_SKOKOV]), skrivne glave samo prvotnemu gostitelju; https se nikoli ne spusti na http.
 */
object PreusmeritevPravila {
    const val NAJVEC_SKOKOV = 3

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
}
