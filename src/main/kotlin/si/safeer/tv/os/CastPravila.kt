package si.safeer.tv.os

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date

/**
 * Google Cast v2 brez Googlovih storitev Play (lastnik, 25. 10. 2026: odprt protokol, deluje tudi na napravah brez Googla)
 * - cista pravila brez Androida (preizkus: tests/CastPravilaTest.kt). Omrezje: [CastSeja].
 *
 * Protokol je odprt (Chromium in openscreen, licenca BSD): TLS na vratih 8009; sporocilo = 4 bajti dolzine (big-endian) +
 * protobuf CastMessage (cast_channel.proto: 1 protocol_version, 2 source_id, 3 destination_id, 4 namespace,
 * 5 payload_type, 6 payload_utf8, 7 payload_binary). Protobuf pisemo rocno (sedem polj), brez knjiznice.
 *
 * Pristnost naprave (deviceauth). Potrdilo TLS sprejemnika je samopodpisano in velja 48 h (izmerjeno na JBL BAR 300
 * 25. 10. 2026: notBefore 24. 10. 04:49, notAfter 26. 10. 04:49 GMT), zato bi se pripet odtis pokvaril vsak dan ali dva.
 * Kot Chromium (cast_auth_util): posljemo izziv z nakljucnim nonce, naprava podpise (nonce || potrdilo TLS) s potrdilom
 * naprave, ki ga preverimo do Googlovega korena Cast. Izmerjeno na JBL: RSASSA-PKCS1-v1_5 s SHA-256 nad nonce || TLS
 * (izracun brez nonce ali s SHA-1 se s podpisom ne ujema), veriga naprava -> »HARMAN ... JBL_BAR_300_PRO« -> »MediaTek.Inc
 * Cast Audio« -> Cast Root CA. Pred preverbo gre ven SAMO izziv; sporocil naprave pred preverbo ne obdelamo ([CastSeja]).
 */
object CastPravila {
    const val VRATA = 8009
    const val NS_POVEZAVA = "urn:x-cast:com.google.cast.tp.connection"
    const val NS_UTRIP = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val NS_SPREJEMNIK = "urn:x-cast:com.google.cast.receiver"
    const val NS_MEDIJ = "urn:x-cast:com.google.cast.media"
    const val NS_PRISTNOST = "urn:x-cast:com.google.cast.tp.deviceauth"
    const val SPREJEMNIK = "receiver-0"
    /** Default Media Receiver: Googlov splosni sprejemnik za zvok po naslovu (na JBL izmerjeno: AAC, MP3, Opus, FLAC, HLS z zvokom). */
    const val DMR = "CC1AD845"
    /** Najvecje sporocilo Cast (64 KiB, cast_channel); daljsi okvir je pokvarjen ali sovrazen. */
    const val NAJVEC_SPOROCILA = 64 * 1024

    // ------------------------------------------------------------------ protobuf

    private fun varint(out: ByteArrayOutputStream, v: Long) {
        var n = v
        while (true) {
            val b = (n and 0x7F).toInt()
            n = n ushr 7
            if (n == 0L) { out.write(b); return }
            out.write(b or 0x80)
        }
    }

    private fun stevilo(out: ByteArrayOutputStream, polje: Int, v: Long) { varint(out, (polje shl 3).toLong()); varint(out, v) }

    private fun bajti(out: ByteArrayOutputStream, polje: Int, b: ByteArray) {
        varint(out, ((polje shl 3) or 2).toLong()); varint(out, b.size.toLong()); out.write(b)
    }

    /**
     * Okvir za poslati: 4 bajti dolzine + CastMessage z [besedilo] (JSON, payload_type STRING) ali [binarno]
     * (deviceauth, payload_type BINARY).
     */
    fun okvir(izvor: String, cilj: String, ns: String, besedilo: String? = null, binarno: ByteArray? = null): ByteArray {
        val m = ByteArrayOutputStream()
        stevilo(m, 1, 0)                                            // protocol_version CASTV2_1_0
        bajti(m, 2, izvor.toByteArray(Charsets.UTF_8))
        bajti(m, 3, cilj.toByteArray(Charsets.UTF_8))
        bajti(m, 4, ns.toByteArray(Charsets.UTF_8))
        if (binarno != null) { stevilo(m, 5, 1); bajti(m, 7, binarno) }
        else { stevilo(m, 5, 0); bajti(m, 6, (besedilo ?: "").toByteArray(Charsets.UTF_8)) }
        val telo = m.toByteArray()
        require(telo.size <= NAJVEC_SPOROCILA) { "sporocilo Cast je predolgo" }
        return glava(telo.size) + telo
    }

    fun glava(n: Int): ByteArray = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

    /** Dolzina iz glave okvirja; -1, ce je izven meja (pokvarjen okvir - povezavo zapremo). */
    fun dolzina(g: ByteArray): Int {
        if (g.size < 4) return -1
        val n = ((g[0].toInt() and 0xFF) shl 24) or ((g[1].toInt() and 0xFF) shl 16) or ((g[2].toInt() and 0xFF) shl 8) or (g[3].toInt() and 0xFF)
        return if (n in 1..NAJVEC_SPOROCILA) n else -1
    }

    /** Polja protobufa: stevilka polja -> vrednosti (Long za varint in fiksne, ByteArray za dolzinske); null = pokvarjeno. */
    fun polja(b: ByteArray): Map<Int, List<Any>>? {
        val out = HashMap<Int, MutableList<Any>>()
        var i = 0
        fun beriVarint(): Long? {
            var n = 0L
            var sh = 0
            while (i < b.size && sh < 64) {
                val c = b[i++].toInt() and 0xFF
                n = n or ((c and 0x7F).toLong() shl sh)
                if (c and 0x80 == 0) return n
                sh += 7
            }
            return null
        }
        while (i < b.size) {
            val kljuc = beriVarint() ?: return null
            val polje = (kljuc ushr 3).toInt()
            if (polje <= 0) return null
            val v: Any = when ((kljuc and 7).toInt()) {
                0 -> beriVarint() ?: return null
                1 -> { if (i + 8 > b.size) return null; i += 8; 0L }
                2 -> {
                    val d = beriVarint() ?: return null
                    if (d < 0 || d > b.size - i) return null
                    b.copyOfRange(i, i + d.toInt()).also { i += d.toInt() }
                }
                5 -> { if (i + 4 > b.size) return null; i += 4; 0L }
                else -> return null
            }
            out.getOrPut(polje) { ArrayList() }.add(v)
        }
        return out
    }

    class Sporocilo(val izvor: String, val cilj: String, val ns: String, val besedilo: String?, val binarno: ByteArray?) {
        /** JSON sporocila (null, ce ni besedilo ali ni JSON). */
        fun json(): JSONObject? = besedilo?.let { try { JSONObject(it) } catch (_: Exception) { null } }
    }

    /** Telo okvirja (brez glave) v sporocilo; null = pokvarjeno. */
    fun razcleni(telo: ByteArray): Sporocilo? {
        val p = polja(telo) ?: return null
        fun niz(n: Int) = (p[n]?.firstOrNull() as? ByteArray)?.toString(Charsets.UTF_8)
        val ns = niz(4) ?: return null
        val binarno = (p[5]?.firstOrNull() as? Long) == 1L
        return Sporocilo(niz(2).orEmpty(), niz(3).orEmpty(), ns, if (binarno) null else niz(6).orEmpty(),
            if (binarno) (p[7]?.firstOrNull() as? ByteArray ?: ByteArray(0)) else null)
    }

    // ------------------------------------------------------------------ pristnost naprave (deviceauth)

    /** DeviceAuthMessage { challenge { signature_algorithm RSASSA_PKCS1v15, sender_nonce, hash_algorithm SHA256 } }. */
    fun izziv(nonce: ByteArray): ByteArray {
        val c = ByteArrayOutputStream()
        stevilo(c, 1, 1)
        bajti(c, 2, nonce)
        stevilo(c, 3, 1)
        val m = ByteArrayOutputStream()
        bajti(m, 1, c.toByteArray())
        return m.toByteArray()
    }

    class OdgovorPristnosti(val podpis: ByteArray, val naprava: ByteArray, val vmesna: List<ByteArray>,
                            val algoritem: Int, val nonce: ByteArray?, val zgoscevanje: Int)

    /** AuthResponse iz DeviceAuthMessage; null = ni odgovor (tudi AuthError) ali je pokvarjen. */
    fun odgovorPristnosti(b: ByteArray): OdgovorPristnosti? {
        val r = polja(b)?.get(2)?.firstOrNull() as? ByteArray ?: return null
        val p = polja(r) ?: return null
        val podpis = p[1]?.firstOrNull() as? ByteArray ?: return null
        val naprava = p[2]?.firstOrNull() as? ByteArray ?: return null
        return OdgovorPristnosti(podpis, naprava, p[3].orEmpty().filterIsInstance<ByteArray>(),
            ((p[4]?.firstOrNull() as? Long) ?: 1L).toInt(),                     // privzeto RSASSA_PKCS1v15
            p[5]?.firstOrNull() as? ByteArray,
            ((p[6]?.firstOrNull() as? Long) ?: 0L).toInt())                     // privzeto SHA1
    }

    enum class Pristnost { OK, ODGOVOR, TLS, VERIGA, NONCE, PODPIS }

    /** Potrdilo TLS sprejemnika sme veljati najvec toliko casa naprej (openscreen cast_auth_util: kMaxSelfSignedCertLifetimeInDays = 4). */
    const val NAJVEC_TLS_MS = 4 * 24 * 3600_000L

    /**
     * Ali je odgovor [o] na nas izziv [nonce] podpisala prava naprava Cast nad potrdilom TLS [tls], ki ga je ta povezava
     * res dobila. [zdajMs]: ura za veljavnost potrdil.
     *
     * Strozje od openscreen (pregled Grok 25, Z3): zahtevamo nas nonce v odgovoru in SHA-256 - JBL BAR 300 oboje poslje
     * (izmerjeno 25. 10. 2026: sender_nonce enak, hash_algorithm 1). Odgovor brez nonce bi bil vezan samo na potrdilo TLS,
     * SHA-1 pa ni vec varen. Seznama preklicev (polje 7, JBL ga poslje) ne preverjamo - openscreen ga ob privzeti politiki
     * (kCrlOptional) prav tako prezre.
     */
    fun preveri(o: OdgovorPristnosti, nonce: ByteArray, tls: ByteArray, zdajMs: Long,
                korenine: List<X509Certificate> = korenine()): Pristnost {
        val zdaj = Date(zdajMs)
        val t = potrdilo(tls) ?: return Pristnost.TLS
        try { t.checkValidity(zdaj) } catch (_: Exception) { return Pristnost.TLS }
        if (t.notAfter.time - zdajMs > NAJVEC_TLS_MS) return Pristnost.TLS
        if (o.vmesna.size > 4) return Pristnost.VERIGA
        val naprava = potrdilo(o.naprava) ?: return Pristnost.VERIGA
        val vmesna = o.vmesna.map { potrdilo(it) ?: return Pristnost.VERIGA }
        if (!veriga(naprava, vmesna, korenine, zdaj)) return Pristnost.VERIGA
        val n = o.nonce ?: return Pristnost.NONCE
        if (!MessageDigest.isEqual(n, nonce)) return Pristnost.NONCE
        if (o.algoritem != 1 || o.zgoscevanje != 1) return Pristnost.PODPIS    // samo PKCS#1 v1.5 s SHA-256 (kot izziv)
        return try {
            val s = Signature.getInstance("SHA256withRSA")
            s.initVerify(naprava.publicKey)
            s.update(nonce + tls)
            if (s.verify(o.podpis)) Pristnost.OK else Pristnost.PODPIS
        } catch (_: Exception) { Pristnost.PODPIS }
    }

    fun potrdilo(der: ByteArray): X509Certificate? = try {
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    } catch (_: Exception) { null }

    /** [izdajatelj] je podpisal [potrdilo] (ime izdajatelja se ujema in podpis drzi). */
    fun izdal(izdajatelj: X509Certificate, potrdilo: X509Certificate): Boolean =
        izdajatelj.subjectX500Principal == potrdilo.issuerX500Principal &&
            try { potrdilo.verify(izdajatelj.publicKey); true } catch (_: Exception) { false }

    /**
     * Veriga od potrdila naprave do enega od [korenine]: potrdilo naprave ni CA in ima keyUsage digitalSignature, vsako
     * vmesno je CA, sme podpisovati potrdila in njegov pathLen dovoli CA pod njim (tudi koren), vsa veljajo [zdaj], nobeno
     * nima kriticne razsiritve, ki je ne poznamo, in nobeno ni podpisano z MD2/MD5. Najvec stiri vmesna. Prava veriga JBL
     * (izmerjeno 25. 10. 2026): naprava (digitalSignature) -> HARMAN (pathlen 0) -> MediaTek (pathlen 1) -> Cast Root CA
     * (pathlen 2), brez kriticnih razsiritev, vse sha256WithRSA.
     */
    fun veriga(naprava: X509Certificate, vmesna: List<X509Certificate>, korenine: List<X509Certificate>, zdaj: Date): Boolean {
        if (!uporabno(naprava, zdaj) || naprava.basicConstraints >= 0) return false
        if (naprava.keyUsage?.getOrNull(0) != true) return false                // digitalSignature (obvezen)
        var tren = naprava
        val porabljena = HashSet<Int>()
        repeat(5) {
            // CA nad [porabljena.size] vmesnimi CA: njegov pathLen jih mora dovoliti (RFC 5280; basicConstraints = pathLen).
            if (korenine.any { velja(it, zdaj) && izdal(it, tren) && it.basicConstraints >= porabljena.size }) return true
            val i = vmesna.indices.firstOrNull { it !in porabljena && izdal(vmesna[it], tren) } ?: return false
            val izd = vmesna[i]
            if (!uporabno(izd, zdaj) || izd.basicConstraints < porabljena.size) return false
            izd.keyUsage?.let { if (it.size < 6 || !it[5]) return false }      // keyCertSign
            porabljena += i
            tren = izd
        }
        return false
    }

    private fun velja(c: X509Certificate, zdaj: Date): Boolean = try { c.checkValidity(zdaj); true } catch (_: Exception) { false }

    /** Velja, nima neznane kriticne razsiritve in ni podpisano z MD2/MD5. */
    private fun uporabno(c: X509Certificate, zdaj: Date): Boolean = velja(c, zdaj) && !c.hasUnsupportedCriticalExtension() &&
        !c.sigAlgName.uppercase().let { it.contains("MD5") || it.contains("MD2") }

    /** Kaj je pokazal poskus povezave Cast na zvocnik. */
    enum class Poskus { OK, PREVERBA, NI_CASTA }
    /** Kam gre tok YouTube: Cast, DLNA, nikamor (naprava ni pristna) ali nikamor (pristna naprava Cast ta hip ni dosegljiva). */
    enum class Pot { CAST, DLNA, NI_PRISTNA, CAST_NEDOSEGLJIV }

    /**
     * Pot po poskusu Cast (lastnik 25. 10. 2026, pregled Grok 25 Z1): naprava, ki preverbo pristnosti AKTIVNO pade (podpis,
     * nonce, veriga, TLS), ne dobi nicesar - ne po DLNA ne prek posrednika. Naprava, ki se je ze kdaj izkazala kot pristna
     * naprava Cast ([bilaPristna], po udn), ob izgubi Casta ne gre tiho na DLNA. Na DLNA gre samo naprava, ki Casta ni
     * nikoli ponudila (cisti DLNA, kot doslej).
     */
    fun pot(poskus: Poskus, bilaPristna: Boolean): Pot = when (poskus) {
        Poskus.OK -> Pot.CAST
        Poskus.PREVERBA -> Pot.NI_PRISTNA
        Poskus.NI_CASTA -> if (bilaPristna) Pot.CAST_NEDOSEGLJIV else Pot.DLNA
    }

    /**
     * Googlova korena Cast. Vir: openscreen (chromium.googlesource.com/openscreen), cast/common/certificate/
     * cast_root_ca_cert_der-inc.h in eureka_root_ca_der-inc.h, Copyright 2019 The Chromium Authors, licenca BSD (enaka
     * kot v Chromium components/cast_certificate). Preneseno 25. 10. 2026. SHA-256 (preizkus to preveri):
     * Cast Root CA 80:9A:F1:47:00:B3:FE:26:11:AD:59:7E:B1:58:4D:63:54:31:3B:64:CC:DB:33:90:F0:97:FA:3E:58:26:D6:CA (do 2034),
     * Eureka Root CA CA:F6:D1:E3:7B:53:22:03:E0:1A:76:BF:07:18:7B:B7:31:CC:D3:88:01:56:5A:B2:21:1A:2C:0A:B7:F3:BC:46 (do 2032).
     */
    const val KOREN_CAST = "MIIDxTCCAq2gAwIBAgIBAjANBgkqhkiG9w0BAQUFADB1MQswCQYDVQQGEwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTEWMBQGA1UEBwwNTW91bnRhaW4gVmlldzETMBEGA1UECgwKR29vZ2xlIEluYzENMAsGA1UECwwEQ2FzdDEVMBMGA1UEAwwMQ2FzdCBSb290IENBMB4XDTE0MDQwMjE3MzQyNloXDTM0MDMyODE3MzQyNlowdTELMAkGA1UEBhMCVVMxEzARBgNVBAgMCkNhbGlmb3JuaWExFjAUBgNVBAcMDU1vdW50YWluIFZpZXcxEzARBgNVBAoMCkdvb2dsZSBJbmMxDTALBgNVBAsMBENhc3QxFTATBgNVBAMMDENhc3QgUm9vdCBDQTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALrZZZ3aOdPBd/bU0K6PWAhoOUqV7XDP/XkIqarl6binLaBnR4qeyc9wswWHaRHscJiXw+bDw+u9xrA9/E/BXjif2s9zMAZbeTfBXoyHR5SaQZIq1pXEcVwnXQixgMaSvRvjQZeh7HWfVZ4+n48cx2VkB9OzlqEEn5HE3gp7bNnIwHgxoBlCqeiD48788c7CLiRGlQkZysBGsuUButdP87/2aa2ZBPqgBzkO5t9RRwfA5KlcS5TFL7OgMH/nlWuyrzIN8YzVbct7R6cIq8sno03PSlrxBdH4YsUQKnRpquZLlvub2GPkWGbTrYpu/3te+aVWHi2CMVvw4iTmQUofrhMCAwEAAaNgMF4wDwYDVR0TBAgwBgEB/wIBAjAdBgNVHQ4EFgQUfJoefd95VLzXzF7KmYZFeWV0KBkwHwYDVR0jBBgwFoAUfJoefd95VLzXzF7KmYZFeWV0KBkwCwYDVR0PBAQDAgEGMA0GCSqGSIb3DQEBBQUAA4IBAQCA9Fr7PSgZUSDX1PsSl0pl8lg1kncwavHXtlEaf5rNx3sDQq1VagCv8OEGwr1reHXb/kERU0o5u5o6xlk0Lywz47LWXH/deOtxWznag5DFMeI/I+/a6ystd17ew0PSyWtZgsrV7fqhZFvL8Q0aYuGc6KcYcPBfF5b47Ybbrh3gzz5dLu4WbZUrPP2X8wVaJGhNObb45Fi69eAmeFHFW11OCeVsR4t6Wi6JU+bMNlsmPPhyQwKC0ivN8NOj7BM+UtWDPQfcHUNlejMCAaPOt9ZgUTsJwiOKMv6YGWBik4XNNEbb1SMPedp3ACoCbYNYzgN3NeGjIJPCSqKkRhx1LB9N"
    const val KOREN_EUREKA = "MIIDwzCCAqugAwIBAgIBATANBgkqhkiG9w0BAQUFADB8MQswCQYDVQQGEwJVUzETMBEGA1UECAwKQ2FsaWZvcm5pYTEWMBQGA1UEBwwNTW91bnRhaW4gVmlldzETMBEGA1UECgwKR29vZ2xlIEluYzESMBAGA1UECwwJR29vZ2xlIFRWMRcwFQYDVQQDDA5FdXJla2EgUm9vdCBDQTAeFw0xMjEyMTcyMjM5MzNaFw0zMjEyMTIyMjM5MzNaMHwxCzAJBgNVBAYTAlVTMRMwEQYDVQQIDApDYWxpZm9ybmlhMRYwFAYDVQQHDA1Nb3VudGFpbiBWaWV3MRMwEQYDVQQKDApHb29nbGUgSW5jMRIwEAYDVQQLDAlHb29nbGUgVFYxFzAVBgNVBAMMDkV1cmVrYSBSb290IENBMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuRHQ6hLcMuHfXDNrGXMdnZ7QOXa/pYQJpv1ubencjzZO6YgCvZ/06ET9TPWaAlZqRypjbFhFzHxmJNx5ecMqpLKLoPeitc0Gftu+7AyG8g0kYHSEyikjhALYp+078ewmR1TjsS3mZA/2csXpmFIXwPzyLCDIQPhHyTKeO5exi/WYJHBjZhnBUugEBT1fjbzYS693mG8feNG2UCdN5OwUaWcfWK+poBEmPJQyB3/X6Wkfrj9PY4qPidbyGXhcIY6xtlfYwOHufW7d8ToKavG6//mDL9y1pCAXYzbvyGIZzFbOsuoxiUt4WMG/AxOZ4BLyiKqblNrddnkXHjTRCsQHRQIDAQABo1AwTjAdBgNVHQ4EFgQURE4qR1jYuUiR9k/OdKkdMpqNjekwHwYDVR0jBBgwFoAURE4qR1jYuUiR9k/OdKkdMpqNjekwDAYDVR0TBAUwAwEB/zANBgkqhkiG9w0BAQUFAAOCAQEAP8gmoG5cBUB5oZipM95odIXurrccM1mwEd6f9E/T61EJfUd+blGF9FTNg5glsbqwV+yT2xLi7FFJepZzm8iWbYWM0+E8+jLiWAx3bYcMNAGqMKl24MDn214b6RAwpOAJSSa5WM1aB+VQdd6aO/ZTfrFTXkUnTxfjCDOyUAq79PwllyneQXUw+nc4qmWKc0/qEXvrfBdgJw68PnZS2IvtGvjrN7sR/a5wFwr+4K0Gsx9pinIEwsAzC9YvY0wzERS4YjaIxQNlARmj7wC7bw6S/zQcodYx0Fxen5l9x8q9fHIL9Fylfm4EqNKZLFEBFP6iSPB+voQNtNPi8w593ov1Mw=="

    @Volatile private var korenineZ: List<X509Certificate>? = null

    fun korenine(): List<X509Certificate> = korenineZ ?: listOf(KOREN_CAST, KOREN_EUREKA)
        .mapNotNull { potrdilo(Base64.getDecoder().decode(it)) }.also { korenineZ = it }

    // ------------------------------------------------------------------ sporocila (JSON)

    fun povezi(): String = JSONObject().put("type", "CONNECT").toString()
    fun zapri(): String = JSONObject().put("type", "CLOSE").toString()
    fun ping(): String = JSONObject().put("type", "PING").toString()
    fun pong(): String = JSONObject().put("type", "PONG").toString()

    /**
     * LOAD za Default Media Receiver: zvok po naslovu, glasbeni metapodatki (naslov, izvajalec, naslovnica samo https).
     * [odS]: zacetek v sekundah (0 = od zacetka; nadaljevanje Zvocniki poslje kot preskok, kot pri DLNA).
     */
    fun nalozi(rid: Int, url: String, mime: String, naslov: String, izvajalec: String, slika: String, igraj: Boolean, odS: Double = 0.0): String {
        val meta = JSONObject().put("metadataType", 3).put("title", naslov).put("artist", izvajalec)
        if (slika.startsWith("https://")) meta.put("images", JSONArray().put(JSONObject().put("url", slika)))
        return JSONObject().put("type", "LOAD").put("requestId", rid).put("autoplay", igraj).put("currentTime", odS)
            .put("media", JSONObject().put("contentId", url).put("contentType", mime).put("streamType", "BUFFERED").put("metadata", meta))
            .toString()
    }

    data class Aplikacija(val appId: String, val seja: String, val prenos: String)
    data class StanjeSprejemnika(val aplikacije: List<Aplikacija>, val glasnost: Double, val utisan: Boolean)

    /** RECEIVER_STATUS; null za drugo sporocilo. */
    fun stanjeSprejemnika(j: JSONObject?): StanjeSprejemnika? {
        if (j == null || j.optString("type") != "RECEIVER_STATUS") return null
        val s = j.optJSONObject("status") ?: return null
        val a = s.optJSONArray("applications")
        val apps = (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }
            .map { Aplikacija(it.optString("appId"), it.optString("sessionId"), it.optString("transportId")) }
        val v = s.optJSONObject("volume")
        return StanjeSprejemnika(apps, v?.optDouble("level", -1.0) ?: -1.0, v?.optBoolean("muted", false) ?: false)
    }

    data class StanjeMedija(val seja: Int, val stanje: String, val razlogMirovanja: String, val casMs: Long, val trajanjeMs: Long, val naslov: String)

    /** Stanja iz MEDIA_STATUS (ali odgovora na zahtevo medija); prazno za drugo sporocilo. */
    fun stanjaMedija(j: JSONObject?): List<StanjeMedija> {
        if (j == null || j.optString("type") != "MEDIA_STATUS") return emptyList()
        val a = j.optJSONArray("status") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { s ->
            val m = s.optJSONObject("media")
            StanjeMedija(s.optInt("mediaSessionId"), s.optString("playerState"), s.optString("idleReason"),
                (s.optDouble("currentTime", 0.0) * 1000).toLong().coerceAtLeast(0L),
                ((m?.optDouble("duration", 0.0) ?: 0.0) * 1000).toLong().coerceAtLeast(0L), m?.optString("contentId").orEmpty())
        }
    }

    /** Zadnja seja medija (najvecja stevilka) - stara seja ob novem LOAD pride se z INTERRUPTED. */
    fun zadnje(j: JSONObject?): StanjeMedija? = stanjaMedija(j).maxByOrNull { it.seja }

    /**
     * Stanje Cast v besedisce DLNA, ki ga razume [ZvocnikPravila.poOdcitku]: PLAYING, PAUSED_PLAYBACK, TRANSITIONING
     * (BUFFERING, LOADING), STOPPED (IDLE) in NO_MEDIA_PRESENT (ni seje medija).
     */
    fun vDlna(s: StanjeMedija?): String = when (s?.stanje) {
        null, "" -> "NO_MEDIA_PRESENT"
        "PLAYING" -> "PLAYING"
        "PAUSED" -> "PAUSED_PLAYBACK"
        "BUFFERING", "LOADING" -> "TRANSITIONING"
        else -> "STOPPED"
    }

    /** Glasnost sprejemnika (0,0-1,0) v lestvico 0-100, ki jo uporablja Zvocniki; -1 = ni znana. */
    fun glasnost(level: Double): Int = if (level < 0.0 || level.isNaN()) -1 else Math.round(level.coerceIn(0.0, 1.0) * 100).toInt()

    /** Odgovor, ki pomeni, da sprejemnik zahteve ni izvedel. */
    fun zavrnitev(j: JSONObject?): Boolean = (j?.optString("type") ?: "") in setOf("LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST",
        "INVALID_PLAYER_STATE", "LAUNCH_ERROR", "ERROR")
}
