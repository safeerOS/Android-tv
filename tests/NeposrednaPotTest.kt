package si.safeer.tv.link

import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

// Global Link - neposredna pot od zunaj (NeposrednaPot.kt), isti protokol kot core/link_neposredno.py na racunalniku.
// Oglas preverjamo na vektorju, ki ga je podpisal Python (sestavi_oglas, zacasni kljuc samo za ta preizkus); povezavo
// s pravimi vticnicami na 127.0.0.1 proti laznemu vhodu (isti koraki kot VhodNeposredno: trk, TLS, izziv).

// Python: core/link_neposredno.sestavi_oglas(hub, "203.0.113.7", 48990, kljuc, podpisi, preverjeno=zdaj-60, zdaj=zdaj)
const val VEKTOR_KLJUC = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEKkrJgz0CgH77Syq8bWsueZ6ak/OkJRiGc5ZMtV998Et/ExTnAd9Cy4i3ol7bD4E/KeJ5Te0UzRA70qLYp0Q1Xw=="
const val VEKTOR_HUB = "n-7322e573a45f48ee"
const val VEKTOR_OGLAS = "sdv1.eyJleHAiOjE4MDAwMDA5MDAsImZwIjoiNzMyMmU1NzNhNDVmNDhlZWFiYWY5MzgwN2Q0OGU0MjcxMjM3NDA4YmFkYjQ0MmViNDY4Mjc4YmY1YjNjNmUxNSIsImh1YiI6Im4tNzMyMmU1NzNhNDVmNDhlZSIsImlwIjoiMjAzLjAuMTEzLjciLCJvayI6MTc5OTk5OTk0MCwicG9ydCI6NDg5OTAsInYiOjF9.MEUCIEwS0nro1Msd8ybO9CMXvXMxdnZ-Lwo-VE4wLaDojP2QAiEA_NBDOd0YbZCQK56BN_fibJSBByD0-xcybHGtWQglH7I"
const val VEKTOR_ZDAJ = 1800000000L

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private fun primer(ime: String, f: () -> Unit) { println(ime); f() }

private class Naprava {
    val par = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val kljuc: String = Base64.getEncoder().encodeToString(par.public.encoded)
    val id = NeposrednaPot.idIzKljuca(kljuc)
    fun podpisi(b: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(par.private); update(b); sign() }
}

private fun preveriPodpis(k: PublicKey, b: ByteArray, p: ByteArray) = try {
    Signature.getInstance("SHA256withECDSA").run { initVerify(k); update(b); verify(p) }
} catch (_: Throwable) { false }

private fun beri(v: InputStream, n: Int): ByteArray {
    val o = ByteArray(n); var i = 0
    while (i < n) { val r = v.read(o, i, n - i); if (r < 0) throw java.io.EOFException(); i += r }
    return o
}

/** Lazni vhod sredisca: kljuc EC iz keytool, clani kroga po id-ju; tunel je odmev. */
private class LazniVhod(mapa: File, clani: List<Naprava>) {
    val ctx: SSLContext
    val kljuc: String
    val hubId: String
    val ss = ServerSocket(0, 50, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val vrata = ss.localPort
    @Volatile var sprejetih = 0
    @Volatile var preizkusov = 0
    private val kljuci = clani.associate { it.id to it.par.public }

    init {
        val shramba = File(mapa, "hub-${System.nanoTime()}.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(keytool, "-genkeypair", "-alias", "a", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=safeer-preizkus", "-validity", "2", "-storetype", "PKCS12", "-keystore", shramba.path,
            "-storepass", "preizkus", "-keypass", "preizkus").redirectErrorStream(true).start()
        val izpis = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "keytool: $izpis" }
        val ks = KeyStore.getInstance("PKCS12").apply { shramba.inputStream().use { load(it, "preizkus".toCharArray()) } }
        kljuc = Base64.getEncoder().encodeToString(ks.getCertificate("a").publicKey.encoded)
        hubId = NeposrednaPot.idIzKljuca(kljuc)
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "preizkus".toCharArray()) }
        ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
        Thread({
            while (true) {
                val s = try { ss.accept() } catch (_: Throwable) { break }
                Thread({ obdelaj(s) }, "lazni-vhod").apply { isDaemon = true }.start()
            }
        }, "lazni-vhod-sprejem").apply { isDaemon = true }.start()
    }

    private fun obdelaj(s: Socket) {
        try {
            s.soTimeout = 5000
            val g = ByteBuffer.wrap(beri(s.getInputStream(), 47))
            val magija = ByteArray(4).also { g.get(it) }
            val id = String(ByteArray(18).also { g.get(it) }, Charsets.US_ASCII)
            val cas = g.long
            val nonce = ByteArray(16).also { g.get(it) }
            val dolzina = g.get().toInt() and 0xff
            val podpis = beri(s.getInputStream(), dolzina)
            val k = kljuci[id]
            if (!magija.contentEquals("SFN1".toByteArray()) || k == null ||
                !preveriPodpis(k, NeposrednaPot.podatkiTrka(hubId, id, cas, nonce), podpis)) { s.close(); return }
            val t = ctx.socketFactory.createSocket(s, null, s.port, true) as SSLSocket
            t.useClientMode = false
            t.startHandshake()
            val odtis = NeposrednaPot.sha256Hex(t.session.localCertificates[0].encoded)
            val izziv = ByteArray(32).also { SecureRandom().nextBytes(it) }
            t.outputStream.apply { write(izziv); flush() }
            val nl = beri(t.inputStream, 2)
            val p2 = beri(t.inputStream, nl[1].toInt() and 0xff)
            if (!preveriPodpis(k, NeposrednaPot.podatkiIzziva(hubId, id, izziv, odtis, nl[0].toInt()), p2)) { t.close(); return }
            t.outputStream.apply { write(1); flush() }
            if (nl[0].toInt() == NeposrednaPot.NAMEN_PREIZKUS) { preizkusov++; t.close(); return }
            sprejetih++
            t.soTimeout = 0
            val buf = ByteArray(65536)
            while (true) { val n = t.inputStream.read(buf); if (n < 0) break; t.outputStream.write(buf, 0, n); t.outputStream.flush() }
            t.close()
        } catch (_: Throwable) { try { s.close() } catch (_: Throwable) { } }
    }
}

private fun odmev(v: java.net.Socket, sporocilo: ByteArray): ByteArray {
    v.getOutputStream().apply { write(sporocilo); flush() }
    v.soTimeout = 5000
    return beri(v.getInputStream(), sporocilo.size)
}

private fun mrtvaVrata(): Int = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }

fun main() {
    val mapa = kotlin.io.path.createTempDirectory("neposredna-pot").toFile()
    val a = Naprava()
    val tujec = Naprava()
    val vhod = LazniVhod(mapa, listOf(a))

    primer("Oglas, ki ga je podpisal Python, se preveri tudi na Androidu") {
        val o = NeposrednaPot.preveriOglas(VEKTOR_OGLAS, VEKTOR_HUB, VEKTOR_KLJUC, VEKTOR_ZDAJ) { true }
        preveri("naslov, vrata, rok in preverjenost", o != null && o.ip == "203.0.113.7" && o.vrata == 48990 &&
            o.exp == VEKTOR_ZDAJ + 900 && o.preverjeno)
        preveri("dokumentacijski naslov ni javen (privzeta preverba)",
            NeposrednaPot.preveriOglas(VEKTOR_OGLAS, VEKTOR_HUB, VEKTOR_KLJUC, VEKTOR_ZDAJ) == null)
        preveri("kljuc drugega clana -> zavrnjen", NeposrednaPot.preveriOglas(VEKTOR_OGLAS, VEKTOR_HUB, a.kljuc, VEKTOR_ZDAJ) { true } == null)
        preveri("drug id -> zavrnjen", NeposrednaPot.preveriOglas(VEKTOR_OGLAS, "n-0000000000000000", VEKTOR_KLJUC, VEKTOR_ZDAJ) { true } == null)
        preveri("potekel -> zavrnjen", NeposrednaPot.preveriOglas(VEKTOR_OGLAS, VEKTOR_HUB, VEKTOR_KLJUC, VEKTOR_ZDAJ + 901) { true } == null)
        preveri("predalec v prihodnosti -> zavrnjen", NeposrednaPot.preveriOglas(VEKTOR_OGLAS, VEKTOR_HUB, VEKTOR_KLJUC, VEKTOR_ZDAJ - 3000) { true } == null)
        val d = VEKTOR_OGLAS.split(".")
        val ponarejen = d[0] + "." + d[1].replaceFirst("e", "f") + "." + d[2]
        preveri("spremenjen tovor -> zavrnjen", NeposrednaPot.preveriOglas(ponarejen, VEKTOR_HUB, VEKTOR_KLJUC, VEKTOR_ZDAJ) { true } == null)
        preveri("smeti -> zavrnjen", NeposrednaPot.preveriOglas("smeti", VEKTOR_HUB, VEKTOR_KLJUC, VEKTOR_ZDAJ) { true } == null)
    }

    primer("Javni IPv4") {
        preveri("javni", NeposrednaPot.jeJavniIpv4("1.1.1.1") && NeposrednaPot.jeJavniIpv4("8.8.8.8"))
        preveri("zasebni, CGNAT, zanka, dokumentacijski niso javni", listOf("10.1.2.3", "192.168.1.5", "172.16.0.1",
            "100.64.1.1", "127.0.0.1", "169.254.1.1", "203.0.113.7", "198.51.100.1", "192.0.2.1", "224.0.0.1", "0.0.0.0",
            "255.255.255.255", "1.2.3", "1.2.3.256", "a.b.c.d").none { NeposrednaPot.jeJavniIpv4(it) })
    }

    primer("Trk: oblika kot na racunalniku") {
        val nonce = ByteArray(16) { it.toByte() }
        val trk = NeposrednaPot.sestaviTrk(vhod.hubId, a.id, a::podpisi, 1_800_000_000L, nonce)
        preveri("magija, id, cas, nonce", String(trk, 0, 4) == "SFN1" && String(trk, 4, 18) == a.id &&
            ByteBuffer.wrap(trk, 22, 8).long == 1_800_000_000L && trk.copyOfRange(30, 46).contentEquals(nonce))
        preveri("podpis nad podatki trka", preveriPodpis(a.par.public,
            "safeer-neposredno-trk-v1\n${vhod.hubId}\n${a.id}\n1800000000\n000102030405060708090a0b0c0d0e0f".toByteArray(),
            trk.copyOfRange(47, trk.size)))
    }

    primer("Clan kroga: neposredna povezava, bajti tecejo") {
        val p = NeposrednaPot.odpri("127.0.0.1", vhod.vrata, vhod.hubId, vhod.kljuc, a.id, a::podpisi)
        preveri("odmev skozi tunel", odmev(p.vticnica, ByteArray(100_000) { 7 }).all { it == 7.toByte() })
        p.vticnica.close()
        preveri("preizkus (namen 2) uspe in ne odpre tunela", NeposrednaPot.preizkusi("127.0.0.1", vhod.vrata, vhod.hubId, vhod.kljuc, a.id, a::podpisi) != null)
    }

    primer("Zavrnitve") {
        val zac = System.nanoTime()
        val r1 = runCatching { NeposrednaPot.odpri("127.0.0.1", vhod.vrata, vhod.hubId, vhod.kljuc, tujec.id, tujec::podpisi) }
        preveri("neseznanjena naprava ne pride skozi", r1.exceptionOrNull() is NeposrednaPot.Napaka)
        val r2 = runCatching { NeposrednaPot.odpri("127.0.0.1", vhod.vrata, vhod.hubId, a.kljuc, a.id, a::podpisi) }
        preveri("sredisce z drugim kljucem (ni ta clan kroga) -> zavrnjeno", r2.exceptionOrNull()?.message == "kljuc sredisca se ne ujema s krogom")
        val r3 = runCatching { NeposrednaPot.odpri("127.0.0.1", mrtvaVrata(), vhod.hubId, vhod.kljuc, a.id, a::podpisi) }
        preveri("zaprta vrata -> napaka", r3.exceptionOrNull()?.message == "ni povezave")
        preveri("vse to v manj kot 6 s", (System.nanoTime() - zac) / 1_000_000 < 6000)
    }

    fun odZunaj(oglas: NeposrednaPot.Oglas?, omrezje: () -> String = { "mobilno" }) = NeposrednaPot.OdZunaj(
        vhod.hubId, kljuc = { vhod.kljuc }, nasId = { a.id }, podpisi = a::podpisi, poisci = { _, _ -> oglas },
        omrezje = { omrezje() }, vOzadju = { it() })

    primer("Vrstni red: preverjen oglas -> neposredno") {
        val o = odZunaj(NeposrednaPot.Oglas("127.0.0.1", vhod.vrata, System.currentTimeMillis() / 1000 + 600, true, "x"))
        val p = o.odpri()
        preveri("neposredno", p != null && o.zadnjaPot == NeposrednaPot.POT_NEPOSREDNO && o.zadnjiMs != null)
        p?.vticnica?.close()
    }

    primer("Rezerva: neposredno ne gre -> null hitro, nato premor; drugo omrezje ga izbrise") {
        var om = "mobilno"
        val o = odZunaj(NeposrednaPot.Oglas("127.0.0.1", mrtvaVrata(), System.currentTimeMillis() / 1000 + 600, true, "x")) { om }
        val zac = System.nanoTime()
        preveri("prvi poskus vrne null (klicatelj gre na rele)", o.odpri() == null)
        preveri("v manj kot ROK_POVEZAVE_MS + 500 ms", (System.nanoTime() - zac) / 1_000_000 < NeposrednaPot.ROK_POVEZAVE_MS + 500)
        preveri("premor: neposredno se ne poskusi", o.konec() == null)
        om = "wifi-v-kavarni"
        preveri("drugo omrezje: spet na voljo", o.konec() != null)
    }

    primer("Nepreverjen oglas: najprej preizkus, sele nato pot") {
        val dober = odZunaj(NeposrednaPot.Oglas("127.0.0.1", vhod.vrata, System.currentTimeMillis() / 1000 + 600, false, "x"))
        val pred = vhod.preizkusov
        dober.isci()
        preveri("preizkus je tekel in uspel -> pot na voljo", vhod.preizkusov == pred + 1 && dober.konec() != null)
        val slab = odZunaj(NeposrednaPot.Oglas("127.0.0.1", mrtvaVrata(), System.currentTimeMillis() / 1000 + 600, false, "x"))
        slab.isci()
        preveri("preizkus ni uspel -> pot ni na voljo (uporabnik ne caka nanjo)", slab.konec() == null && slab.odpri() == null)
        preveri("brez oglasa -> pot ni na voljo", odZunaj(null).konec() == null)
    }

    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("Vse OK")
    kotlin.system.exitProcess(0)
}
