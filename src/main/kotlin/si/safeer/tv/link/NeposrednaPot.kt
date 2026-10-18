package si.safeer.tv.link

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Global Link - neposredna pot od zunaj (INTERNET_DIRECT), odjemalec na Androidu. Isti protokol kot
 * core/link_neposredno.py na racunalniku (tam je tudi vhod na sredisci in podroben opis).
 *
 * Telefon zdoma je za simetricnim CGNAT-om; ce pa ima dom javni IPv4 in na usmerjevalniku ENA preusmerjena vrata,
 * telefon odpre navadno odhodno povezavo TCP naravnost do sredisca. Vrstni red: LAN -> neposredno -> rele.
 *
 * Povezava: trk (podpis kljuca naprave nad id-jem sredisca, nasim id-jem, casom in noncem) -> TLS, kjer sprejmemo
 * samo potrdilo s kljucem tega clana kroga -> izziv (podpis nad izzivom, odtisom potrdila in namenom) -> potrditev.
 * Nato tece skozi nespremenjen TLS Safeer Linka (pripet kljuc, vstopnica) - kot skozi rele.
 *
 * Oglas (javni naslov in vrata) pride iz prisotnosti na link.safeer.si, ki jo vidijo samo clani kroga, in je podpisan
 * s kljucem sredisca: preverimo ga s kljucem iz kroga, ne zaupamo releju. Nepreverjen oglas (sredisce se ni dobilo
 * prave povezave od zunaj) uporabimo samo za preizkus v ozadju, nikoli na poti, na katero uporabnik caka.
 *
 * Brez Androida (samo java.*), da ga preizkusi navaden JVM (tests/NeposrednaPotTest.kt).
 */
object NeposrednaPot {
    const val ROK_POVEZAVE_MS = 1500
    const val ROK_ROKOVANJA_MS = 3000
    const val NAMEN_TUNEL = 1
    const val NAMEN_PREIZKUS = 2
    const val NAJDALJSI_TTL_S = 3600L
    const val PREVERJENO_VELJA_S = 7L * 86400
    const val PRISOTNOST_VSAKIH_MS = 600_000L
    const val PREMOR_MS = 300_000L
    const val NAJDALJSI_PREMOR_MS = 1_800_000L
    const val POT_NEPOSREDNO = "neposredno"
    const val POT_RELE = "rele"

    private val MAGIJA = "SFN1".toByteArray(Charsets.US_ASCII)
    private val ID = Regex("^n-[0-9a-f]{16}$")
    private val nakljucje = SecureRandom()

    class Napaka(sporocilo: String) : Exception(sporocilo)

    data class Oglas(val ip: String, val vrata: Int, val exp: Long, val preverjeno: Boolean, val niz: String)

    fun jedro(id: String?): String? = id?.take(18)?.takeIf { ID.matches(it) }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    fun sha256Hex(b: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(b))

    fun odtisKljuca(kljucB64: String): String = sha256Hex(Base64.getDecoder().decode(kljucB64))

    fun idIzKljuca(kljucB64: String): String = "n-" + odtisKljuca(kljucB64).take(16)

    fun podatkiTrka(hubId: String, odjemalecId: String, cas: Long, nonce: ByteArray): ByteArray =
        "safeer-neposredno-trk-v1\n$hubId\n$odjemalecId\n$cas\n${hex(nonce)}".toByteArray(Charsets.US_ASCII)

    fun podatkiIzziva(hubId: String, odjemalecId: String, izziv: ByteArray, odtisPotrdila: String, namen: Int): ByteArray =
        "safeer-neposredno-izziv-v1\n$hubId\n$odjemalecId\n${hex(izziv)}\n${odtisPotrdila.lowercase()}\n$namen"
            .toByteArray(Charsets.US_ASCII)

    fun podatkiOglasa(tovorB64: String): ByteArray = "safeer-neposredno-oglas-v1\n$tovorB64".toByteArray(Charsets.US_ASCII)

    /** MAGIJA(4) | id odjemalca (18) | cas (8, BE) | nonce (16) | dolzina podpisa (1) | podpis DER. */
    fun sestaviTrk(hubId: String, nasId: String, podpisi: (ByteArray) -> ByteArray,
                   cas: Long = System.currentTimeMillis() / 1000, nonce: ByteArray = ByteArray(16).also { nakljucje.nextBytes(it) }): ByteArray {
        val podpis = podpisi(podatkiTrka(hubId, nasId, cas, nonce))
        if (podpis.size !in 8..80) throw Napaka("podpis ni ECDSA P-256")
        return ByteBuffer.allocate(4 + 18 + 8 + 16 + 1 + podpis.size).put(MAGIJA).put(nasId.toByteArray(Charsets.US_ASCII))
            .putLong(cas).put(nonce).put(podpis.size.toByte()).put(podpis).array()
    }

    /** Ali je IPv4 javen (kot ipaddress.is_global v Pythonu za IPv4): brez zasebnih, CGNAT, zanke, dokumentacijskih ... */
    fun jeJavniIpv4(ip: String): Boolean {
        val deli = ip.split(".")
        if (deli.size != 4) return false
        val b = deli.map { d -> if (d.isEmpty() || d.length > 3 || !d.all { it.isDigit() }) return false; d.toInt().also { if (it > 255) return false } }
        val (a, c) = b[0] to b[1]
        return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 100 && c in 64..127) || (a == 169 && c == 254) || (a == 172 && c in 16..31) ||
            (a == 192 && c == 168) || (a == 192 && c == 0 && b[2] in setOf(0, 2)) || (a == 198 && c in 18..19) ||
            (a == 198 && c == 51 && b[2] == 100) || (a == 203 && c == 0 && b[2] == 113))
    }

    private fun preveriPodpis(kljucB64: String, podatki: ByteArray, podpis: ByteArray): Boolean = try {
        val kljuc = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(kljucB64)))
        Signature.getInstance("SHA256withECDSA").run { initVerify(kljuc); update(podatki); verify(podpis) }
    } catch (_: Throwable) { false }

    private fun b64u(s: String): ByteArray = Base64.getUrlDecoder().decode(s + "=".repeat((4 - s.length % 4) % 4))

    /** Polja ploscatega JSON-a oglasa (niz ali celo stevilo); oglas je podpisan, zato zadosca strogi vzorec. */
    private fun polja(json: String): Map<String, String> {
        val t = json.trim()
        if (!t.startsWith("{") || !t.endsWith("}")) return emptyMap()
        val izid = HashMap<String, String>()
        Regex("\"([a-z]+)\":(\"([^\"\\\\]*)\"|(-?\\d{1,15}))").findAll(t).forEach { m ->
            izid[m.groupValues[1]] = if (m.groupValues[3].isNotEmpty() || m.groupValues[2].startsWith("\"")) m.groupValues[3] else m.groupValues[4]
        }
        return izid
    }

    /** Preverjen oglas ali null. [kljucB64] = kljuc sredisca iz KROGA ZAUPANJA (nikoli iz oglasa ali releja). */
    fun preveriOglas(niz: String, hubId: String, kljucB64: String, zdajS: Long = System.currentTimeMillis() / 1000,
                     jeJavni: (String) -> Boolean = ::jeJavniIpv4): Oglas? {
        try {
            if (niz.length > 512) return null
            val d = niz.split(".")
            if (d.size != 3 || d[0] != "sdv1") return null
            if (!preveriPodpis(kljucB64, podatkiOglasa(d[1]), b64u(d[2]))) return null
            val p = polja(String(b64u(d[1]), Charsets.UTF_8))
            if (p["v"] != "1" || p["hub"] != hubId || p["fp"] != odtisKljuca(kljucB64)) return null
            val exp = p["exp"]?.toLongOrNull() ?: return null
            if (exp <= zdajS || exp - zdajS > NAJDALJSI_TTL_S) return null
            val ip = p["ip"] ?: return null
            val vrata = p["port"]?.toIntOrNull() ?: return null
            if (!jeJavni(ip) || vrata !in 1024..65535) return null
            val ok = p["ok"]?.toLongOrNull() ?: 0L
            return Oglas(ip, vrata, exp, ok > 0 && zdajS - ok < PREVERJENO_VELJA_S, niz)
        } catch (_: Throwable) {
            return null
        }
    }

    /** Sprejme samo potrdilo s kljucem [kljucB64] (clan kroga); verige do overitelja ni. */
    @Suppress("CustomX509TrustManager")
    private class PripetKljuc(private val kljucB64: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("odjemalskih potrdil ne preverjamo")
        }
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty()) throw CertificateException("brez potrdila")
            val k = Base64.getEncoder().encodeToString(chain[0].publicKey.encoded)
            if (!MessageDigest.isEqual(k.toByteArray(), kljucB64.toByteArray())) throw CertificateException("kljuc sredisca se ne ujema s krogom")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private fun beri(vhod: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var i = 0
        while (i < n) {
            val r = vhod.read(out, i, n - i)
            if (r < 0) throw Napaka("konec")
            i += r
        }
        return out
    }

    /** Rezultat: odprta vticnica (skozi njo tece TLS Linka) in ms do TCP. */
    class Povezava(val vticnica: SSLSocket, val msTcp: Long, val msSkupaj: Long)

    /** Trk + TLS (kljuc iz kroga) + izziv. Ob vsaki napaki [Napaka]; vticnica je takrat zaprta. */
    fun odpri(ip: String, vrata: Int, hubId: String, kljucHubaB64: String, nasId: String, podpisi: (ByteArray) -> ByteArray,
              namen: Int = NAMEN_TUNEL, rokPovezaveMs: Int = ROK_POVEZAVE_MS, rokRokovanjaMs: Int = ROK_ROKOVANJA_MS): Povezava {
        val hub = jedro(hubId) ?: throw Napaka("ni id iz kljuca")
        val nas = jedro(nasId) ?: throw Napaka("ni id iz kljuca")
        val t0 = System.nanoTime()
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(ip, vrata), rokPovezaveMs)
        } catch (e: Throwable) {
            try { s.close() } catch (_: Throwable) { }
            throw Napaka("ni povezave")
        }
        val msTcp = (System.nanoTime() - t0) / 1_000_000
        var t: SSLSocket? = null
        try {
            s.soTimeout = rokRokovanjaMs
            s.getOutputStream().apply { write(sestaviTrk(hub, nas, podpisi)); flush() }
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(PripetKljuc(kljucHubaB64)), SecureRandom())
            t = ctx.socketFactory.createSocket(s, ip, vrata, true) as SSLSocket
            t.useClientMode = true
            t.enabledProtocols = t.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
            t.soTimeout = rokRokovanjaMs
            t.startHandshake()
            val potrdilo = t.session.peerCertificates[0] as X509Certificate
            val odtis = sha256Hex(potrdilo.encoded)
            val vhod = t.inputStream
            val izziv = beri(vhod, 32)
            val podpis = podpisi(podatkiIzziva(hub, nas, izziv, odtis, namen))
            t.outputStream.apply { write(byteArrayOf(namen.toByte(), podpis.size.toByte()) + podpis); flush() }
            if (beri(vhod, 1)[0] != 1.toByte()) throw Napaka("zavrnjeno")
            t.soTimeout = 0
            return Povezava(t, msTcp, (System.nanoTime() - t0) / 1_000_000)
        } catch (e: Throwable) {
            try { (t ?: s).close() } catch (_: Throwable) { }
            if (e is Napaka) throw e
            throw Napaka(if (e is javax.net.ssl.SSLHandshakeException && (e.message ?: "").contains("kljuc")) "kljuc sredisca se ne ujema s krogom" else "rokovanje ni uspelo")
        }
    }

    /** Preizkus dosegljivosti (namen 2): ms ali null. Sredisce ga steje kot preverjanje od zunaj (z javnega naslova). */
    fun preizkusi(ip: String, vrata: Int, hubId: String, kljucHubaB64: String, nasId: String, podpisi: (ByteArray) -> ByteArray): Long? =
        try {
            val p = odpri(ip, vrata, hubId, kljucHubaB64, nasId, podpisi, NAMEN_PREIZKUS)
            try { p.vticnica.close() } catch (_: Throwable) { }
            p.msSkupaj
        } catch (_: Napaka) { null }

    /**
     * Stanje poti do enega sredisca (zunaj doma): oglas, premor po neuspehu, nas lastni uspeh (preizkus ali tunel).
     * Odvisnosti so vstavljene (kljuc iz kroga, podpis, iskanje oglasa, omrezje, ura), da je preizkusljivo na JVM.
     */
    class OdZunaj(
        val cilj: String,
        private val kljuc: () -> String?,
        private val nasId: () -> String,
        private val podpisi: (ByteArray) -> ByteArray,
        private val poisci: (String, String) -> Oglas?,
        private val omrezje: (String) -> String = { "" },
        private val ura: () -> Long = System::currentTimeMillis,
        private val vOzadju: (() -> Unit) -> Unit = { f -> Thread(f, "safeer-neposredno").apply { isDaemon = true }.start() },
    ) {
        @Volatile var oglas: Oglas? = null; private set
        @Volatile var zadnjaPot: String = ""; private set
        @Volatile var zadnjiMs: Long? = null; private set
        @Volatile var zadnjaNapaka: String = ""; private set
        private var iskanoOb = 0L
        @Volatile private var iscem = false
        private var premorDo = 0L
        private var premor = 0L
        private var omrezjeObNeuspehu = ""
        private val preizkuseno = HashMap<String, String>()
        private val nasUspeh = HashSet<String>()

        private fun kljucOglasa(o: Oglas) = "${o.ip}:${o.vrata}"

        /** Oglas iz prisotnosti najvec vsakih PRISOTNOST_VSAKIH_MS, v ozadju (uporabnik nanj ne caka). */
        fun isci() {
            val k = kljuc() ?: return
            synchronized(this) {
                if (iscem || (iskanoOb != 0L && ura() - iskanoOb < PRISOTNOST_VSAKIH_MS)) return
                iscem = true; iskanoOb = ura()
            }
            vOzadju {
                try {
                    val o = try { poisci(cilj, k) } catch (_: Throwable) { null }
                    synchronized(this) {
                        if (o != null) oglas = o
                        else if ((oglas?.exp ?: 0L) * 1000 <= ura()) oglas = null
                    }
                    if (o != null && !o.preverjeno) preizkus(o, k)
                } finally { iscem = false }
            }
        }

        private fun preizkus(o: Oglas, k: String) {
            val om = omrezje(o.ip)
            synchronized(this) {
                if (preizkuseno[kljucOglasa(o)] == om) return
                preizkuseno[kljucOglasa(o)] = om
            }
            if (NeposrednaPot.preizkusi(o.ip, o.vrata, cilj, k, nasId(), podpisi) != null) synchronized(this) { nasUspeh.add(kljucOglasa(o)) }
        }

        /** (ip, vrata), ce smemo zdaj poskusiti neposredno; sicer null. */
        fun konec(): Pair<String, Int>? {
            isci()
            synchronized(this) {
                val o = oglas ?: return null
                if (o.exp * 1000 <= ura()) return null
                if (!(o.preverjeno || kljucOglasa(o) in nasUspeh)) return null
                if (premorDo > ura()) {
                    if (omrezje(o.ip) == omrezjeObNeuspehu) return null
                    premorDo = 0L; premor = 0L      // drugo omrezje: morda gre zdaj
                }
                return o.ip to o.vrata
            }
        }

        /** Neposredna povezava ali null (takrat klicatelj gre prek releja). */
        fun odpri(): Povezava? {
            val k = konec() ?: return null
            val kljucHuba = kljuc() ?: return null
            return try {
                NeposrednaPot.odpri(k.first, k.second, cilj, kljucHuba, nasId(), podpisi).also {
                    synchronized(this) { premorDo = 0L; premor = 0L; nasUspeh.add("${k.first}:${k.second}") }
                    zadnjaPot = POT_NEPOSREDNO; zadnjiMs = it.msTcp
                }
            } catch (e: Napaka) {
                synchronized(this) {
                    premor = if (premor == 0L) PREMOR_MS else minOf(premor * 2, NAJDALJSI_PREMOR_MS)
                    premorDo = ura() + premor
                    omrezjeObNeuspehu = omrezje(k.first)
                }
                zadnjaNapaka = e.message ?: ""
                null
            }
        }

        fun prekRele() { zadnjaPot = POT_RELE }
    }
}
