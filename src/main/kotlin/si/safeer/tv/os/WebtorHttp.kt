package si.safeer.tv.os

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Pregled 26 (Grok, T1, 25. 10. 2026): zahteve toka Webtor brez TOCTOU in brez DNS rebindinga. Sonda in predvajalnik
 * gresta prek OkHttp, ki (1) NIKOLI ne sledi preusmeritvam sam (vsak 3xx vidi klicatelj - sonda ga preveri, predvajalnik ga
 * zavrne) in (2) se poveze SAMO na naslove IP, ki so bili preverjeni (javni): sonda vsako ime razresi enkrat ([Preverjeni]),
 * predvajalnik pa dobi pripete naslove koncnega gostitelja ([pripet]). Prej: HttpURLConnection je ime razresil se enkrat
 * po preverbi, Media3 je s koncnega naslova sledil preusmeritvam istega protokola.
 * Cista JVM (OkHttp, brez Androida) - preizkus tests/WebtorHttpTest.kt z lokalnim streznikom.
 */
object WebtorHttp {
    /**
     * Razresi vsako ime enkrat in vrne samo, ce so VSI naslovi javni ([zaseben] - [WebtorPravila.zasebenNaslov]); sicer
     * UnknownHostException (fail-closed). Ista sonda za isto ime dobi iste naslove (povezava gre tja, kamor je kazala preverba).
     */
    class Preverjeni(
        private val zaseben: (ByteArray) -> Boolean = WebtorPravila::zasebenNaslov,
        private val razresi: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ) : Dns {
        private val naslovi = ConcurrentHashMap<String, List<InetAddress>>()

        override fun lookup(hostname: String): List<InetAddress> {
            val h = kljuc(hostname)
            naslovi[h]?.let { return it }
            val n = try { razresi(h) } catch (e: UnknownHostException) { throw e } catch (e: Exception) { throw UnknownHostException("DNS") }
            if (n.isEmpty()) throw UnknownHostException("DNS: brez naslova")
            if (n.any { zaseben(it.address) }) throw ZasebenNaslov()
            naslovi[h] = n
            return n
        }

        /**
         * Gostitelj, ki je naslov IP: OkHttp zanj ne vprasa DNS, zato ga preverimo tu enako kot odgovor DNS (zaseben =
         * [ZasebenNaslov]) in zapomnimo za predvajalnik. null = gostitelj ni naslov IP.
         */
        fun preveriIp(gostitelj: String): List<InetAddress>? {
            val ip = ipNaslov(gostitelj) ?: return null
            if (zaseben(ip.address)) throw ZasebenNaslov()
            val n = listOf(ip)
            naslovi[kljuc(gostitelj)] = n
            return n
        }

        /** Preverjeni naslovi imena [gostitelj] (za predvajalnik); prazno = ime se ni bilo razreseno. */
        fun za(gostitelj: String): List<InetAddress> = naslovi[kljuc(gostitelj)].orEmpty()

        private fun kljuc(g: String) = g.lowercase().trimEnd('.').trim('[', ']')
    }

    /** Naslov IP v obliki besedila (IPv4 s pikami ali IPv6) - brez poizvedbe DNS; null = ni naslov IP. */
    fun ipNaslov(gostitelj: String): InetAddress? {
        val g = gostitelj.trim('[', ']')
        val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(g)
        if (!ipv4 && (':' !in g || !g.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' })) return null
        return try { InetAddress.getByName(g) } catch (_: Exception) { null }
    }

    /** Cilj je v domacem omrezju ali na tej napravi (pregled U4) - zavrnjeno pred povezavo. */
    class ZasebenNaslov : UnknownHostException("zaseben naslov")

    /** DNS, ki pozna samo [gostitelj] in zanj vrne samo [naslovi] (ze preverjene): druga imena in nov DNS odgovor ne pridejo v igro. */
    fun pripet(gostitelj: String, naslovi: List<InetAddress>): Dns {
        val g = gostitelj.lowercase().trimEnd('.')
        val n = naslovi.toList()
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                if (hostname.lowercase().trimEnd('.') == g && n.isNotEmpty()) n else throw UnknownHostException("ni pripet")
        }
    }

    /** Odjemalec brez samodejnih preusmeritev in ponovitev, z [dns]; [rokMs] = rok povezave in branja (klic dobi svoj rok). */
    fun klient(dns: Dns, rokMs: Long): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .dns(dns)
        .connectTimeout(rokMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        .readTimeout(rokMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        .build()

    /** Glava Range za Media3 DataSpec ([od] bajt, [dolzina] -1 = do konca); null = cela datoteka. */
    fun obseg(od: Long, dolzina: Long): String? = when {
        od <= 0L && dolzina < 0L -> null
        dolzina < 0L -> "bytes=$od-"
        else -> "bytes=$od-${od + dolzina - 1}"
    }
}
