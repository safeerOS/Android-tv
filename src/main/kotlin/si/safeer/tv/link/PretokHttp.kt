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
 * novega razresevanja. Naslova IP v zapisu OkHttp ne poslje v Dns, zato ga preverimo pred vsako zahtevo ([ciljNedovoljen]).
 *
 * Vodja 5. 11. 2026 (dve razliki od Linuxa):
 * - domace omrezje (zasebni naslovi) samo, ce ima tok same varne glave ([VARNE_GLAVE], kot Linux v_domace_omrezje) in
 *   samo za zaupan skok; poverilnice (Authorization, Cookie, kljuci X-...) gredo samo javnemu naslovu;
 * - seznam predvajanja (HLS, DASH) z drugega izvora, ki navaja naslov na izvoru prvega vira, odvzame izvoru zaupanje
 *   za ves tok ([Vir.zaupanjeIzgubljeno]): kos, ki ga je izbral tuj seznam, ne sme dobiti glav toka (Linux _Vir.izpeljan).
 */
object PretokHttp {
    const val NAJVEC_SKOKOV = 5
    const val GLAVA_ZETONA = "X-Safeer-Token"
    const val PRIVZETI_UA = "Safeer OS"
    /** Kot Linux NAJVEC_SEZNAM: vecji seznam z drugega izvora zavrnemo (ne beremo v nedogled). */
    const val NAJVEC_SEZNAM = 2 * 1024 * 1024
    private val PREUSMERITVE = setOf(301, 302, 303, 307, 308)

    /** Enako kot Linux core/link_sprotno.VARNE_GLAVE: glave brez poverilnic, s katerimi tok sme v domace omrezje. */
    val VARNE_GLAVE = setOf("user-agent", "referer", "origin", "accept", "accept-language")

    /** Cilj ni dovoljen (koda kot Linux: nedovoljen_cilj) ali veriga preusmeritev ni veljavna. */
    class NedovoljenCilj(val koda: String) : IOException(koda)

    /** [razresi], [prepovedan] in [zaseben] zamenja samo preizkus (lazni strezniki na zanki); produkcija uporabi privzete. */
    class Nastavitve(
        val razresi: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
        val prepovedan: (ByteArray) -> Boolean = { PretokPravila.prepovedan(it) },
        /** Ni javni naslov (domace omrezje, CGNAT, ULA ...); prepovedane preveri ze [prepovedan]. */
        val zaseben: (ByteArray) -> Boolean = { si.safeer.tv.os.SondaVeriga.zasebenCilj(it) },
    )

    /**
     * En tok: naslov, ki ga je izbrala naprava, in njegove glave. [domace] = glave so varne za domace omrezje.
     * [zaupanjeIzgubljeno]: tuj seznam je navedel naslov na izvoru prvega vira - od tedaj noben kos glav toka ne dobi.
     */
    class Vir(val prvi: String, val glave: Map<String, String>) {
        val domace = vDomaceOmrezje(glave)
        @Volatile var zaupanjeIzgubljeno = false
    }

    /** Odlocitev o skoku: [url] in [zaupan] (glave toka smejo naprej) ali [napaka] (prazno = skok dovoljen). */
    class Skok(val url: String, val zaupan: Boolean, val napaka: String)

    /** [telo]: prebrani seznam z drugega izvora (odgovor je ze zaprt), sicer null in se bere iz [odgovor]. */
    class Odgovor(val odgovor: Response, val url: String, val zaupan: Boolean, val telo: ByteArray? = null)

    /**
     * Gostitelj naslova vira z istim razclenjevalnikom kot [odpri] (OkHttp). CodeRabbit 6. 11. 2026: java.net.URI je vrgel
     * izjemo za presledek ali [ ] | v poti in vrnil null za podcrtaj v imenu - prazen gostitelj je zavrnil veljaven vir.
     * "" = naslov ni veljaven (klicatelj ga zavrne).
     */
    fun gostiteljVira(url: String): String = url.toHttpUrlOrNull()?.host.orEmpty()

    /** Kot Linux v_domace_omrezje: vse glave toka so brez poverilnic. */
    fun vDomaceOmrezje(glave: Map<String, String>): Boolean = glave.keys.all { it.lowercase() in VARNE_GLAVE }

    /** Gostitelj po zapisu (brez DNS): ime zanke ali prepovedan naslov IP. */
    fun ciljPrepovedan(gost: String, prepovedan: (ByteArray) -> Boolean = { PretokPravila.prepovedan(it) }): Boolean {
        if (gost.isBlank() || PretokPravila.jeImeZanke(gost)) return true
        val b = PretokPravila.bajti(gost) ?: return false
        return prepovedan(b)
    }

    /** Kot [ciljPrepovedan], le da je zaseben naslov IP v zapisu dovoljen samo z [domace]. */
    fun ciljNedovoljen(gost: String, domace: Boolean, n: Nastavitve): Boolean {
        if (ciljPrepovedan(gost, n.prepovedan)) return true
        val b = PretokPravila.bajti(gost) ?: return false
        return !domace && n.zaseben(b)
    }

    /** Razreseni naslovi vira: noben prepovedan, zaseben samo z [domace] (kot Linux dovoljeni_naslovi). */
    fun nasloviDovoljeni(naslovi: List<ByteArray>, domace: Boolean, n: Nastavitve = Nastavitve()): Boolean =
        naslovi.isNotEmpty() && naslovi.none { n.prepovedan(it) || (!domace && n.zaseben(it)) }

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

    /** Ali je zacetek odgovora seznam predvajanja (HLS #EXTM3U ali DASH <MPD), po vsebini, ne po vrsti ali koncnici. */
    fun jeSeznam(zacetek: ByteArray): Boolean {
        val s = String(zacetek, Charsets.ISO_8859_1).trimStart('\uFEFF', '\u00EF', '\u00BB', '\u00BF', ' ', '\t', '\r', '\n')
        return s.startsWith("#EXTM3U") || s.contains("<MPD", ignoreCase = true)
    }

    private val KONEC_VRSTICE = Regex("\r\n|[\r\n\u0000]")
    private val ATRIBUT_URI = Regex("URI\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)

    /**
     * Ali seznam [besedilo] (z naslova [osnova]) navaja naslov na izvoru [prvi]. HLS: vrstice z naslovom in atributi URI
     * (KEY, MAP, MEDIA ...), razreseni glede na [osnova]. Previdno (kot Linux, ki tuj seznam vgnezdi): tudi vsako
     * omembo imena gostitelja prvega vira (DASH BaseURL, drugacno razclenjevanje v predvajalniku) stejemo kot navedbo.
     */
    fun seznamNavajaIzvor(besedilo: String, osnova: String, prvi: String): Boolean {
        val gost = prvi.toHttpUrlOrNull()?.host ?: return true
        if (besedilo.contains(gost, ignoreCase = true)) return true
        val baza = osnova.toHttpUrlOrNull() ?: return true
        for (surova in besedilo.split(KONEC_VRSTICE)) {
            val v = surova.trim()
            if (v.isEmpty()) continue
            val naslovi = if (v.startsWith("#")) ATRIBUT_URI.findAll(v).map { it.groupValues[1].trim() }.toList() else listOf(v)
            for (u in naslovi) {
                val r = baza.resolve(u) ?: continue
                if (istiIzvor(r.toString(), prvi)) return true
            }
        }
        return false
    }

    /** Razresi ime in vrne naslove samo, ce so vsi dovoljeni (kot Linux dovoljeni_naslovi: ce ni eden, ni nobeden). */
    class PreverjenDns(private val n: Nastavitve, private val domace: Boolean) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (PretokPravila.jeImeZanke(hostname)) throw NedovoljenCilj("nedovoljen_cilj")
            val r = try { n.razresi(hostname) } catch (e: IOException) { throw e } catch (_: Exception) { throw UnknownHostException(hostname) }
            if (r.isEmpty()) throw UnknownHostException(hostname)
            if (!nasloviDovoljeni(r.map { it.address }, domace, n)) throw NedovoljenCilj("nedovoljen_cilj")
            return r
        }
    }

    /**
     * Dva odjemalca s skupnim bazenom povezav: [javni] (samo javni naslovi) in [domace] (tudi domace omrezje). OkHttp
     * povezave loci po Dns, zato povezave v domace omrezje ne dobi zahteva, ki tja ne sme. Brez samodejnih preusmeritev
     * in brez posrednika (proxy): posrednik bi ime razresil sam, mimo preverbe.
     */
    class Klient(val n: Nastavitve, povezavaS: Long, branjeS: Long) {
        private val osnova = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).proxy(Proxy.NO_PROXY)
            .connectTimeout(povezavaS, TimeUnit.SECONDS).readTimeout(branjeS, TimeUnit.SECONDS).build()
        val javni: OkHttpClient = osnova.newBuilder().dns(PreverjenDns(n, false)).build()
        val domace: OkHttpClient = osnova.newBuilder().dns(PreverjenDns(n, true)).build()
        fun zapri() { osnova.connectionPool.evictAll(); osnova.dispatcher.executorService.shutdown() }
    }

    fun klient(n: Nastavitve = Nastavitve(), povezavaS: Long = 8, branjeS: Long = 20) = Klient(n, povezavaS, branjeS)

    /**
     * Zahteva na [url] toka [vir] s skoki preusmeritev. Odgovor, ki ni preusmeritev, vrne klicatelju (kode ne preverja).
     * Seznam predvajanja z drugega izvora prebere v [Odgovor.telo] in ob navedbi izvora prvega vira odvzame zaupanje.
     * Nedovoljen cilj ali skok -> [NedovoljenCilj].
     */
    fun odpri(klient: Klient, vir: Vir, url: String, obseg: String?, metoda: String = "GET"): Odgovor {
        val n = klient.n
        var trenutni = url
        var zaupan = istiIzvor(url, vir.prvi) && !vir.zaupanjeIzgubljeno
        var skok = 0
        while (true) {
            val cilj = trenutni.toHttpUrlOrNull() ?: throw NedovoljenCilj("napaka")
            // Domace omrezje samo zaupan skok toka z varnimi glavami (Linux: izpeljan vir drugega izvora ima zasebni=False).
            val domace = zaupan && vir.domace
            if (ciljNedovoljen(cilj.host, domace, n)) throw NedovoljenCilj("nedovoljen_cilj")
            val z = Request.Builder().url(cilj).method(metoda, null)
            for ((k, v) in glaveZahteve(vir.glave, zaupan)) try { z.header(k, v) } catch (_: IllegalArgumentException) { }
            if (obseg != null) z.header("Range", obseg)
            val r = (if (domace) klient.domace else klient.javni).newCall(z.build()).execute()
            if (r.code !in PREUSMERITVE) return if (zaupan) Odgovor(r, trenutni, true) else tuj(r, trenutni, vir)
            val lokacija = r.header("Location")
            r.close()
            val s = naslednjiSkok(trenutni, lokacija, skok, zaupan, vir.prvi, n.prepovedan)
            if (s.napaka.isNotEmpty()) throw NedovoljenCilj(s.napaka)
            trenutni = s.url; zaupan = s.zaupan; skok++
        }
    }

    /** Odgovor drugega izvora: ce je seznam predvajanja, ga preberemo (najvec [NAJVEC_SEZNAM]) in preverimo navedbe. */
    private fun tuj(r: Response, url: String, vir: Vir): Odgovor {
        val telo = r.body ?: return Odgovor(r, url, false)
        if (r.code !in 200..299) return Odgovor(r, url, false)
        try {
            val tok = telo.source()
            tok.request(512)
            val zacetek = tok.buffer.copy().readByteArray(minOf(tok.buffer.size, 512L))
            if (!jeSeznam(zacetek)) return Odgovor(r, url, false)
            tok.request(NAJVEC_SEZNAM + 1L)
            if (tok.buffer.size > NAJVEC_SEZNAM) throw NedovoljenCilj("prevelik_seznam")
            val bajti = tok.readByteArray()
            if (seznamNavajaIzvor(String(bajti, Charsets.UTF_8), url, vir.prvi)) vir.zaupanjeIzgubljeno = true
            r.close()
            return Odgovor(r, url, false, bajti)
        } catch (e: IOException) {
            r.close(); throw e
        }
    }
}
