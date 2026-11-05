package si.safeer.tv.os

import java.net.URL

/**
 * Pravila prenosa APK (os/NamestiApk, os/TrgovineAplikacij) - brez Androida, preizkus tests/NamestiApkPravilaTest.kt.
 *
 * Pregled zaslonov 5. 11. 2026: HttpURLConnection s samodejnimi preusmeritvami je rocno glavo Cookie prvega naslova
 * poslal tudi na drug streznik, APK ponudnika pa se je namestil brez preverbe, da je res pricakovani paket.
 */
object NamestiApkPravila {
    /** Najvec skokov preusmeritve (GitHub: github.com -> release-assets.githubusercontent.com je en skok). */
    const val NAJVEC_SKOKOV = 5

    /** Domene Aptoide: vmesnik ws75.aptoide.com, APK s pool.apk.aptoide.com (getMeta, preverjeno 5. 11. 2026). */
    val DOMENE_APTOIDE = setOf("aptoide.com")

    private val PREUSMERITVE = setOf(301, 302, 303, 307, 308)

    sealed class Skok {
        /** Odgovor 2xx na naslovu https: beremo telo. */
        object Koncaj : Skok()
        /** Preusmeritev na [url] (vedno https, absoluten). */
        class Naprej(val url: String) : Skok()
        class Napaka(val razlog: String) : Skok()
    }

    /**
     * Kaj storiti z odgovorom [koda] na naslovu [trenutni] po [skokov] ze opravljenih preusmeritvah. Vsak skok je samo
     * https (preusmeritev na http bi APK lahko zamenjal kdorkoli na poti); relativna lokacija se razresi proti trenutnemu.
     */
    fun naslednji(trenutni: String, koda: Int, lokacija: String?, skokov: Int): Skok {
        if (koda in 200..299) return if (jeHttps(trenutni)) Skok.Koncaj else Skok.Napaka("ni_https")
        if (koda !in PREUSMERITVE) return Skok.Napaka("HTTP $koda")
        if (skokov >= NAJVEC_SKOKOV) return Skok.Napaka("prevec_preusmeritev")
        val l = lokacija?.trim().orEmpty()
        if (l.isEmpty()) return Skok.Napaka("brez_lokacije")
        val cilj = try { URL(URL(trenutni), l) } catch (_: Exception) { return Skok.Napaka("neveljavna_lokacija") }
        if (!cilj.protocol.equals("https", ignoreCase = true) || cilj.host.isNullOrBlank()) return Skok.Napaka("ni_https")
        // Uporabnik in geslo v naslovu (https://a:b@...) nista del prenosa aplikacije.
        if (cilj.userInfo != null) return Skok.Napaka("neveljavna_lokacija")
        return Skok.Naprej(cilj.toString())
    }

    fun jeHttps(url: String): Boolean = try {
        val u = URL(url)
        u.protocol.equals("https", ignoreCase = true) && !u.host.isNullOrBlank()
    } catch (_: Exception) { false }

    /** APK od ponudnika mora biti natanko pricakovani paket; brez pricakovanja (prenos iz brskalnika) odloci uporabnik. */
    fun pravPaket(pricakovan: String?, dejanski: String?): Boolean =
        pricakovan.isNullOrBlank() || (!dejanski.isNullOrBlank() && pricakovan == dejanski)

    /** Naslov https na eni od [domene] ali njeni poddomeni (ne »aptoide.com.zlo.si« in ne »zloaptoide.com«). */
    fun naDomeni(url: String, domene: Collection<String>): Boolean {
        if (!jeHttps(url)) return false
        val gostitelj = try { URL(url).host.lowercase().trimEnd('.') } catch (_: Exception) { return false }
        return domene.any { d -> val x = d.lowercase(); gostitelj == x || gostitelj.endsWith(".$x") }
    }
}
