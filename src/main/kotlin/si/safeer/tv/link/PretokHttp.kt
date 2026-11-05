package si.safeer.tv.link

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Branje izvirnika za sprotno pretvorbo (Pretok) z navadnega HTTP(S) vira - brez Androida (tests/PretokHttpTest.kt).
 *
 * Vodja 5. 11. 2026: Media3 DefaultHttpDataSource je preusmeritvam sledil sam, zato je 302 na http://127.0.0.1:... ali
 * na 169.254.169.254 obsel PretokPravila (preverjen je bil samo prvi naslov). Kot Linux core/link_sprotno (_odpri,
 * _Vir.preusmeritev): preusmeritvam sledimo sami, najvec [NAJVEC_SKOKOV], vsak skok preverimo ([naslednjiSkok]);
 * https -> http ne; glave toka samo izvoru prvega naslova, dokler je vsak skok ostal na njem.
 * DNS rebinding: OkHttp se poveze na naslove, ki jih vrne [PreverjenDns] - isti seznam, ki smo ga preverili, brez
 * novega razresevanja. Naslova IP v zapisu OkHttp ne poslje v Dns, zato ga preverimo pred vsako zahtevo ([ciljPrepovedan]).
 */
object PretokHttp {
    const val NAJVEC_SKOKOV = 5
    const val GLAVA_ZETONA = "X-Safeer-Token"
    const val PRIVZETI_UA = "Safeer OS"
    private val PREUSMERITVE = setOf(301, 302, 303, 307, 308)

    /** Cilj ni dovoljen (koda kot Linux: nedovoljen_cilj) ali veriga preusmeritev ni veljavna. */
    class NedovoljenCilj(val koda: String) : IOException(koda)

    /** [razresi] in [prepovedan] zamenja samo preizkus (lazni streznik na zanki); produkcija uporabi privzeta. */
    class Nastavitve(
        val razresi: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
        val prepovedan: (ByteArray) -> Boolean = { PretokPravila.prepovedan(it) },
    )

    /** Odlocitev o skoku: [url] in [zaupan] (glave toka smejo naprej) ali [napaka] (prazno = skok dovoljen). */
    class Skok(val url: String, val zaupan: Boolean, val napaka: String)

    class Odgovor(val odgovor: Response, val url: String, val zaupan: Boolean)

    /** Gostitelj po zapisu (brez DNS): ime zanke ali prepovedan naslov IP. */
    fun ciljPrepovedan(gost: String, prepovedan: (ByteArray) -> Boolean = { PretokPravila.prepovedan(it) }): Boolean {
        if (gost.isBlank() || PretokPravila.jeImeZanke(gost)) return true
        val b = PretokPravila.bajti(gost) ?: return false
        return prepovedan(b)
    }

    /** Shema, ime in vrata (privzeta vrata stejejo kot navedena), kot Linux _isti_izvor. */
    fun istiIzvor(a: String, b: String): Boolean {
        val x = a.toHttpUrlOrNull() ?: return false
        val y = b.toHttpUrlOrNull() ?: return false
        return x.scheme == y.scheme && x.host.equals(y.host, ignoreCase = true) && x.port == y.port
    }

    /**
     * Cista odlocitev o skoku preusmeritve iz [trenutni] na [lokacija] (glava Location). [skok] = stevilo ze opravljenih
     * skokov; [prvi] = naslov, ki ga je izbrala naprava. Razresena imena preveri [PreverjenDns] ob povezavi.
     */
    fun naslednjiSkok(trenutni: String, lokacija: String?, skok: Int, zaupan: Boolean, prvi: String,
                      prepovedan: (ByteArray) -> Boolean = { PretokPravila.prepovedan(it) }): Skok {
        if (skok >= NAJVEC_SKOKOV) return Skok("", false, "prevec_preusmeritev")
        val t = trenutni.toHttpUrlOrNull() ?: return Skok("", false, "napaka")
        val lok = lokacija?.trim().orEmpty()
        if (lok.isEmpty()) return Skok("", false, "napaka")
        val n = t.resolve(lok) ?: return Skok("", false, "napaka")
        // Z https na http ne: zahteva bi sla nezascitena, glave toka bi videl vsak v omrezju.
        if (t.isHttps && !n.isHttps) return Skok("", false, "nedovoljen_cilj")
        if (ciljPrepovedan(n.host, prepovedan)) return Skok("", false, "nedovoljen_cilj")
        val url = n.toString()
        // Ko veriga enkrat zapusti izvor prvega naslova, glav toka ne dobi vec nihce (tudi ob vrnitvi ne): preusmeritev
        // drugega izvora ne sme odlocati, kam gredo (kot Linux _Vir.izpeljan).
        return Skok(url, zaupan && istiIzvor(url, prvi), "")
    }

    /**
     * Glave zahteve: zaupan skok dobi glave toka (brez zetona Linka - ta gre samo pripetemu viru, HubPreusmeritve),
     * drug izvor samo privzeti User-Agent. Accept-Encoding identity: obseg (Range) se nanasa na bajte datoteke.
     */
    fun glaveZahteve(glave: Map<String, String>, zaupan: Boolean): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        m["User-Agent"] = PRIVZETI_UA
        if (zaupan) for ((k, v) in glave) {
            if (k.equals(GLAVA_ZETONA, true) || k.equals("Range", true) || k.equals("Host", true) ||
                k.equals("Accept-Encoding", true)) continue
            if (k.equals("User-Agent", true)) m["User-Agent"] = v else m[k] = v
        }
        m["Accept-Encoding"] = "identity"
        return m
    }

    /** Razresi ime in vrne naslove samo, ce so vsi dovoljeni (kot Linux dovoljeni_naslovi: ce ni eden, ni nobeden). */
    class PreverjenDns(private val n: Nastavitve) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (PretokPravila.jeImeZanke(hostname)) throw NedovoljenCilj("nedovoljen_cilj")
            val r = try { n.razresi(hostname) } catch (e: IOException) { throw e } catch (_: Exception) { throw UnknownHostException(hostname) }
            if (r.isEmpty()) throw UnknownHostException(hostname)
            if (r.any { n.prepovedan(it.address) }) throw NedovoljenCilj("nedovoljen_cilj")
            return r
        }
    }

    /** Odjemalec brez samodejnih preusmeritev in brez posrednika (proxy): posrednik bi ime razresil sam, mimo preverbe. */
    fun klient(n: Nastavitve = Nastavitve(), povezavaS: Long = 8, branjeS: Long = 20): OkHttpClient =
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).proxy(Proxy.NO_PROXY)
            .dns(PreverjenDns(n)).connectTimeout(povezavaS, TimeUnit.SECONDS).readTimeout(branjeS, TimeUnit.SECONDS).build()

    /**
     * Zahteva na [url] s skoki preusmeritev; [prvi] je naslov, ki ga je izbrala naprava (izvor za glave toka). Odgovor, ki
     * ni preusmeritev, vrne klicatelju (kode ne preverja). Nedovoljen skok -> [NedovoljenCilj].
     */
    fun odpri(klient: OkHttpClient, url: String, prvi: String, glave: Map<String, String>, obseg: String?,
              metoda: String = "GET", n: Nastavitve = Nastavitve()): Odgovor {
        var trenutni = url
        val zacetni = trenutni.toHttpUrlOrNull() ?: throw NedovoljenCilj("napaka")
        if (ciljPrepovedan(zacetni.host, n.prepovedan)) throw NedovoljenCilj("nedovoljen_cilj")
        var zaupan = istiIzvor(url, prvi)
        var skok = 0
        while (true) {
            val z = Request.Builder().url(trenutni).method(metoda, null)
            for ((k, v) in glaveZahteve(glave, zaupan)) try { z.header(k, v) } catch (_: IllegalArgumentException) { }
            if (obseg != null) z.header("Range", obseg)
            val r = klient.newCall(z.build()).execute()
            if (r.code !in PREUSMERITVE) return Odgovor(r, trenutni, zaupan)
            val lokacija = r.header("Location")
            r.close()
            val s = naslednjiSkok(trenutni, lokacija, skok, zaupan, prvi, n.prepovedan)
            if (s.napaka.isNotEmpty()) throw NedovoljenCilj(s.napaka)
            trenutni = s.url; zaupan = s.zaupan; skok++
        }
    }
}
