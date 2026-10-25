package si.safeer.tv.os

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Povezava Google Cast v2 z enim sprejemnikom (zvocnikom) - odprt protokol brez storitev Google Play ([CastPravila]).
 * Klice jo SAMO delavec [Zvocniki] (vse po vrsti na eni niti), zato ni zaklepanja. Povezava, ki jo je sprejemnik zaprl
 * (manjkajoc utrip med spanjem naprave), se ob naslednjem klicu odpre znova - s ponovno preverbo pristnosti.
 *
 * Uporablja samo Default Media Receiver ([CastPravila.DMR]): zvocnik sam prebere zvok po naslovu (izmerjeno na JBL BAR 300
 * 25. 10. 2026: zagon sprejemnika 1,4-2,2 s, LOAD -> PLAYING 49-602 ms, odmik -324 ppm). Sprejemnika med odcitki NE
 * zaganjamo - ce ga je zamenjala druga aplikacija, je zvocnik prevzel kdo drug ([tujaAplikacija]).
 */
class CastSeja(val naslov: String) {
    /** Naprava ni dokazala, da je prava naprava Cast: ne posljemo ji nicesar (rezerva je DLNA). */
    class Preverba(val razlog: CastPravila.Pristnost) : IOException("Cast: naprava ni opravila preverbe pristnosti ($razlog)")
    /** Sprejemnik je zahtevo zavrnil (npr. INVALID_PLAYER_STATE med nalaganjem) - Zvocniki jo sme ponoviti. */
    class Zavrnjeno(val tip: String) : IllegalStateException("Cast: $tip")
    /** LOAD ni uspel (oblika, nedosegljiv naslov). */
    class NalaganjeNiUspelo(val tip: String) : IllegalStateException("Cast: nalaganje ni uspelo ($tip)")

    private var vticnica: SSLSocket? = null
    private var vhod: DataInputStream? = null
    private var izhod: OutputStream? = null
    private val izvor = "sender-safeer-" + ByteArray(4).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private var rid = 1
    private var zadnjiUtrip = 0L
    /** Kdaj je prislo zadnje sporocilo sprejemnika: povezava brez prometa dlje od [TIHA_MS] je mrtva ali zaprta. */
    private var zadnjiPrejem = 0L
    /** Pristnost je preverjena: sele zdaj smemo posiljati kaj drugega kot izziv (tudi utrip). */
    private var preverjena = false

    /** transportId zagnanega Default Media Receiver ("" = ni povezan nanj). */
    var prenos = ""
        private set
    private var sejaAplikacije = ""
    /** mediaSessionId zadnje seje medija (0 = je ni). */
    var sejaMedija = 0
        private set
    /** Glasnost sprejemnika 0,0-1,0 (-1 = ni znana) iz zadnjega RECEIVER_STATUS. */
    var glasnost = -1.0
        private set
    /** Sprejemnik je zamenjala druga aplikacija (ali drug posiljatelj): nase predvajanje je koncano. */
    var tujaAplikacija = false
        private set

    private val odprta: Boolean get() = vticnica?.isClosed == false

    companion object {
        /** Ves okvir (glava in telo) mora priti v tem casu - pocasna naprava ne sme zadrzati delavca zvocnikov (Grok 25, Z5). */
        const val ROK_OKVIRJA_MS = 5_000L
        /**
         * Sprejemnik brez nasega utripa povezavo zapre (vticnica pa je pri nas videti odprta); straza Zvocnikov med sejo
         * sprasuje vsakih 0,5-5 s, zato povezavo, ki je bila tiho dlje od tega, pred uporabo odpremo znova (s preverbo).
         */
        const val TIHA_MS = 8_000L
        /** Straza, ki zapre vticnico, ce povezava, TLS in preverba pristnosti skupaj ne koncajo v roku. */
        private val straza = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "safeer-cast-straza").apply { isDaemon = true } }
    }

    /**
     * Zaupanje TLS je tu samo prehod: potrdilo TLS sprejemnika je samopodpisano (velja 48 h), pristnost pa takoj nato
     * preveri [odpri] z izzivom deviceauth do Googlovega korena Cast - PRED vsakim drugim sporocilom.
     */
    private object PrehodTls : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("odjemalec ni predviden")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw CertificateException("sprejemnik ni poslal potrdila")
            chain[0].checkValidity()
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * TLS, izziv deviceauth in preverba; sele po njej CONNECT. Ob neuspehu [Preverba] in povezava je zaprta. Vse skupaj
     * najvec 2 x [rokMs] + 4 s (straza vticnico zapre - pocasno rokovanje TLS ne zadrzi delavca; obicajno ~0,2 s).
     */
    fun odpri(rokMs: Int = 4_000) {
        if (odprta && System.currentTimeMillis() - zadnjiPrejem < TIHA_MS) return
        zapri()
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(PrehodTls), SecureRandom())
        val s = ctx.socketFactory.createSocket() as SSLSocket
        val rok = straza.schedule({ try { s.close() } catch (_: Exception) {} }, rokMs * 2 + 4_000L, TimeUnit.MILLISECONDS)
        try {
            // Samo TLS 1.2 in 1.3 (Grok 25, Z2); JBL BAR 300 govori TLS 1.2+.
            val protokoli = s.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }
            if (protokoli.isEmpty()) throw IOException("Cast: ni TLS 1.2")
            s.enabledProtocols = protokoli.toTypedArray()
            s.connect(InetSocketAddress(naslov, CastPravila.VRATA), rokMs)
            s.soTimeout = rokMs
            s.startHandshake()
            val tls = s.session.peerCertificates.firstOrNull()?.encoded ?: throw IOException("Cast: ni potrdila TLS")
            vticnica = s
            vhod = DataInputStream(BufferedInputStream(s.inputStream))
            izhod = s.outputStream
            val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            posljiOkvir(CastPravila.okvir("sender-0", CastPravila.SPREJEMNIK, CastPravila.NS_PRISTNOST, binarno = CastPravila.izziv(nonce)))
            // Naprava je TLS na vratih Cast sprejela, pristnosti pa ne dokaze: to je neuspela preverba, ne »ni Casta«.
            val m = beriDo(rokMs + 2_000L) { it.ns == CastPravila.NS_PRISTNOST } ?: throw Preverba(CastPravila.Pristnost.ODGOVOR)
            val o = m.binarno?.let { CastPravila.odgovorPristnosti(it) }
            val izid = if (o == null) CastPravila.Pristnost.ODGOVOR else CastPravila.preveri(o, nonce, tls, System.currentTimeMillis())
            if (izid != CastPravila.Pristnost.OK) throw Preverba(izid)
            preverjena = true
            pristna = true
            poslji(CastPravila.SPREJEMNIK, CastPravila.NS_POVEZAVA, CastPravila.povezi())
        } catch (e: Exception) {
            zapri()
            // Neuspela preverba: o seji te naprave ne vemo nic zanesljivega.
            if (e is Preverba) sejaAplikacije = ""
            try { s.close() } catch (_: Exception) {}
            throw if (e is IOException) e else IOException("Cast: ${e.message}", e)
        } finally { rok.cancel(false) }
    }

    /** Naprava je na tej seji ze dokazala pristnost (ostane tudi po zaprtju povezave - Zvocniki si jo zapomnijo po udn). */
    var pristna = false
        private set

    fun zapri() {
        try { if (odprta && preverjena) poslji(CastPravila.SPREJEMNIK, CastPravila.NS_POVEZAVA, CastPravila.zapri()) } catch (_: Exception) {}
        try { vticnica?.close() } catch (_: Exception) {}
        vticnica = null; vhod = null; izhod = null
        prenos = ""; preverjena = false
        sejaMedija = 0; glasnost = -1.0; tujaAplikacija = false
    }

    private fun posljiOkvir(b: ByteArray) {
        val o = izhod ?: throw IOException("Cast: povezava ni odprta")
        try { o.write(b); o.flush() } catch (e: IOException) { zapri(); throw e }
    }

    private fun poslji(cilj: String, ns: String, besedilo: String) = posljiOkvir(CastPravila.okvir(izvor, cilj, ns, besedilo))

    /** Eno sporocilo; null, ce v [rokMs] ni nicesar (nobeden bajt ni porabljen - okvir ostane cel). */
    private fun beriEno(rokMs: Long): CastPravila.Sporocilo? {
        val s = vticnica ?: throw IOException("Cast: povezava ni odprta")
        val v = vhod ?: throw IOException("Cast: povezava ni odprta")
        try {
            s.soTimeout = rokMs.coerceIn(50L, 10_000L).toInt()
            val prvi = try { v.read() } catch (_: SocketTimeoutException) { return null }
            if (prvi < 0) throw IOException("Cast: sprejemnik je zaprl povezavo")
            // Ostanek okvirja v skupnem roku (prej 5 s na vsako branje - naprava, ki kaplja po bajtih, je drzala delavca).
            val konec = System.currentTimeMillis() + ROK_OKVIRJA_MS
            val g = ByteArray(4)
            g[0] = prvi.toByte()
            beriTocno(s, v, g, 1, konec)
            val n = CastPravila.dolzina(g)
            if (n < 0) throw IOException("Cast: pokvarjen okvir")
            val telo = ByteArray(n)
            beriTocno(s, v, telo, 0, konec)
            zadnjiPrejem = System.currentTimeMillis()
            return CastPravila.razcleni(telo) ?: throw IOException("Cast: pokvarjeno sporocilo")
        } catch (e: IOException) { zapri(); throw e }
    }

    /** Napolni [b] od [od] naprej; vsako branje caka najvec do [konec] (SocketTimeoutException, ce okvir ne pride ves). */
    private fun beriTocno(s: SSLSocket, v: DataInputStream, b: ByteArray, od: Int, konec: Long) {
        var i = od
        while (i < b.size) {
            val ostane = konec - System.currentTimeMillis()
            if (ostane <= 0L) throw SocketTimeoutException("Cast: okvir ni prisel v roku")
            s.soTimeout = ostane.coerceIn(1L, ROK_OKVIRJA_MS).toInt()
            val r = v.read(b, i, b.size - i)
            if (r < 0) throw IOException("Cast: sprejemnik je zaprl povezavo")
            i += r
        }
    }

    /** Odgovora ni bilo v roku: povezavo zapremo (naslednji klic odpre novo s preverbo) - prej je mrtva povezava ostala. */
    private fun brezOdgovora(opis: String): Nothing {
        zapri()
        throw SocketTimeoutException(opis)
    }

    /** Utrip: sprejemnik zapre povezavo brez njega; posljemo ga, ce ga ni bilo 5 s. */
    private fun utrip() {
        val zdaj = System.currentTimeMillis()
        if (zdaj - zadnjiUtrip < 5_000L) return
        zadnjiUtrip = zdaj
        poslji(CastPravila.SPREJEMNIK, CastPravila.NS_UTRIP, CastPravila.ping())
    }

    /** Bere do sporocila, ki ustreza [pogoj], ali do roka (null). Utrip, CLOSE in stanja obdela sproti. */
    private fun beriDo(rokMs: Long, pogoj: (CastPravila.Sporocilo) -> Boolean): CastPravila.Sporocilo? {
        val konec = System.currentTimeMillis() + rokMs
        while (true) {
            val ostane = konec - System.currentTimeMillis()
            if (ostane <= 0L) return null
            if (preverjena) utrip()
            val m = beriEno(minOf(ostane, 1_000L)) ?: continue
            // Pred preverbo pristnosti sporocil naprave ne obdelamo (Grok 25, Z4): sprejmemo samo odgovor na izziv.
            if (!preverjena) { if (m.ns == CastPravila.NS_PRISTNOST && pogoj(m)) return m else continue }
            val j = m.json()
            if (m.ns == CastPravila.NS_UTRIP) {
                if (preverjena && j?.optString("type") == "PING") poslji(m.izvor.ifEmpty { CastPravila.SPREJEMNIK }, CastPravila.NS_UTRIP, CastPravila.pong())
                continue
            }
            if (m.ns == CastPravila.NS_POVEZAVA && j?.optString("type") == "CLOSE") {
                // Sprejemnik je zaprl virtualno povezavo do aplikacije (zamenjala jo je druga ali se je ustavila).
                if (m.izvor == prenos && prenos.isNotEmpty()) { prenos = ""; sejaMedija = 0; tujaAplikacija = true }
                continue
            }
            CastPravila.stanjeSprejemnika(j)?.let { st ->
                if (st.glasnost >= 0.0) glasnost = st.glasnost
                if (sejaAplikacije.isNotEmpty() && st.aplikacije.none { it.seja == sejaAplikacije }) {
                    prenos = ""; sejaMedija = 0; tujaAplikacija = true
                }
            }
            // Seja medija samo iz nasega sprejemnika (transportId), ne iz tujega sporocila.
            if (prenos.isNotEmpty() && m.izvor == prenos) CastPravila.zadnje(j)?.let { if (it.seja > 0) sejaMedija = maxOf(sejaMedija, it.seja) }
            if (pogoj(m)) return m
        }
    }

    /** Zahteva z requestId in odgovor nanjo (JSON). */
    private fun zahteva(cilj: String, ns: String, tip: String, rokMs: Long = 6_000L, vec: JSONObject.() -> Unit = {}): JSONObject {
        val r = ++rid
        poslji(cilj, ns, JSONObject().put("type", tip).put("requestId", r).apply(vec).toString())
        val m = beriDo(rokMs) { it.json()?.optInt("requestId", -1) == r } ?: brezOdgovora("Cast: ni odgovora na $tip")
        return m.json() ?: throw IOException("Cast: odgovor ni JSON")
    }

    private fun stanjeSprejemnika(): CastPravila.StanjeSprejemnika {
        odpri()
        return CastPravila.stanjeSprejemnika(zahteva(CastPravila.SPREJEMNIK, CastPravila.NS_SPREJEMNIK, "GET_STATUS"))
            ?: throw IOException("Cast: ni stanja sprejemnika")
    }

    /**
     * Povezava na Default Media Receiver. [zazeni] = true samo ob izbiri skladbe: med odcitki ga ne zaganjamo (zvocnik bi
     * vzeli nazaj tistemu, ki ga je prevzel).
     */
    fun zagotoviDmr(zazeni: Boolean): Boolean {
        odpri()
        if (prenos.isNotEmpty()) return true
        var app = stanjeSprejemnika().aplikacije.firstOrNull { it.appId == CastPravila.DMR && it.prenos.isNotEmpty() }
            ?.takeIf { zazeni || it.seja == sejaAplikacije }
        if (app == null) {
            if (!zazeni) return false
            val r = ++rid
            poslji(CastPravila.SPREJEMNIK, CastPravila.NS_SPREJEMNIK,
                JSONObject().put("type", "LAUNCH").put("requestId", r).put("appId", CastPravila.DMR).toString())
            val m = beriDo(20_000L) { s ->
                val j = s.json()
                (j?.optInt("requestId", -1) == r && CastPravila.zavrnitev(j)) ||
                    CastPravila.stanjeSprejemnika(j)?.aplikacije?.any { it.appId == CastPravila.DMR && it.prenos.isNotEmpty() } == true
            } ?: brezOdgovora("Cast: sprejemnik se ni zagnal")
            val j = m.json()
            if (CastPravila.zavrnitev(j)) throw Zavrnjeno(j?.optString("type").orEmpty())
            app = CastPravila.stanjeSprejemnika(j)?.aplikacije?.first { it.appId == CastPravila.DMR && it.prenos.isNotEmpty() }
        }
        if (app == null) return false
        sejaAplikacije = app.seja
        prenos = app.prenos
        tujaAplikacija = false
        poslji(prenos, CastPravila.NS_POVEZAVA, CastPravila.povezi())
        return true
    }

    /** Naslov na zvocnik (LOAD). Vrne stanje seje medija; [NalaganjeNiUspelo], ce ga sprejemnik zavrne. */
    fun nalozi(url: String, mime: String, naslov: String, izvajalec: String, slika: String, igraj: Boolean): CastPravila.StanjeMedija? {
        zagotoviDmr(true)
        val r = ++rid
        poslji(prenos, CastPravila.NS_MEDIJ, CastPravila.nalozi(r, url, mime, naslov, izvajalec, slika, igraj))
        val m = beriDo(15_000L) { s ->
            val j = s.json()
            j?.optInt("requestId", -1) == r && (j.optString("type") == "MEDIA_STATUS" || CastPravila.zavrnitev(j))
        } ?: brezOdgovora("Cast: LOAD brez odgovora")
        val j = m.json()
        if (CastPravila.zavrnitev(j)) throw NalaganjeNiUspelo(j?.optString("type").orEmpty())
        val st = CastPravila.zadnje(j)
        if (st != null && st.seja > 0) sejaMedija = st.seja
        return st
    }

    /** PLAY, PAUSE, STOP ali SEEK zadnje seje medija. */
    fun ukaz(tip: String, vec: JSONObject.() -> Unit = {}) {
        if (!zagotoviDmr(false)) throw Zavrnjeno("ni sprejemnika")
        if (sejaMedija == 0) stanjeMedija()
        if (sejaMedija == 0) throw Zavrnjeno("ni seje medija")
        val j = zahteva(prenos, CastPravila.NS_MEDIJ, tip) { put("mediaSessionId", sejaMedija); vec() }
        if (CastPravila.zavrnitev(j)) throw Zavrnjeno(j.optString("type"))
    }

    fun preskoci(ciljMs: Long) = ukaz("SEEK") { put("currentTime", ciljMs / 1000.0) }

    /** Stanje zadnje seje medija; null = sprejemnika ni (ali ga je zamenjala druga aplikacija) ali seje medija ni. */
    fun stanjeMedija(rokMs: Long = 4_000L): CastPravila.StanjeMedija? {
        if (!zagotoviDmr(false)) return null
        val st = CastPravila.zadnje(zahteva(prenos, CastPravila.NS_MEDIJ, "GET_STATUS", rokMs))
        if (st != null && st.seja > 0) sejaMedija = st.seja
        return st
    }

    /** Glasnost sprejemnika 0-100 (-1 = ni znana). */
    fun beriGlasnost(): Int = CastPravila.glasnost(stanjeSprejemnika().glasnost.also { if (it >= 0.0) glasnost = it })

    fun nastaviGlasnost(v: Int) {
        odpri()
        zahteva(CastPravila.SPREJEMNIK, CastPravila.NS_SPREJEMNIK, "SET_VOLUME") {
            put("volume", JSONObject().put("level", v.coerceIn(0, 100) / 100.0))
        }
    }

    /** Konec seje: ustavi nas sprejemnik (zvocnik je spet prost). Ce ga je ze zamenjala druga aplikacija, nicesar. */
    fun ustaviAplikacijo() {
        odpri()
        val nasa = sejaAplikacije
        if (nasa.isEmpty() || stanjeSprejemnika().aplikacije.none { it.seja == nasa }) { prenos = ""; sejaMedija = 0; return }
        val j = zahteva(CastPravila.SPREJEMNIK, CastPravila.NS_SPREJEMNIK, "STOP") { put("sessionId", nasa) }
        if (CastPravila.zavrnitev(j)) throw Zavrnjeno(j.optString("type"))
        prenos = ""; sejaMedija = 0; sejaAplikacije = ""
    }
}
