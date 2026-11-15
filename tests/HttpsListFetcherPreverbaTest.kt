// Slepi pregled TV3 (15. 11. 2026), N3 / »Scit DNS pinning«: HttpsListFetcher mora vsak skok preusmeritve in vsako razresitev imena
// preveriti proti zasebnim naslovom in se povezati na preverjeni naslov. Streznika sta HTTPS na povratni zanki (samopodpisan
// certifikat, zaupanje samo prek clientHook); produkcijski nacin (brez allowPlainHttpForTests). Vsak scenarij brez popravka PADE:
// stari HttpURLConnection je sledil preusmeritvi na zasebni naslov (izpis sonde ogled/tv-slepi-pregled3-1511/izpisi/kt/sonda2.out).
//   tests/run_threat_policy_tests.sh
package com.safeer.threatfeed

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.system.exitProcess

private var padlo = 0
private var preverb = 0

private fun p(pogoj: Boolean, opis: String) {
    preverb++
    if (pogoj) println("OK   $opis") else { padlo++; println("PADE $opis") }
}

/** Samopodpisan certifikat z naslovi 127.0.0.1 in 127.0.0.2 (keytool iz JDK); mapa se pospravi ob koncu. */
private fun naredi_kljuc(mapa: File): SSLContext {
    val keytool = File(System.getProperty("java.home"), "bin/keytool").path
    val ks = File(mapa, "ks.p12")
    val r = ProcessBuilder(keytool, "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=test", "-validity", "2",
        "-storetype", "PKCS12", "-keystore", ks.path, "-storepass", "changeit", "-ext", "SAN=ip:127.0.0.1,ip:127.0.0.2,dns:localhost")
        .redirectErrorStream(true).start()
    val izpis = r.inputStream.readBytes().decodeToString()
    check(r.waitFor() == 0) { "keytool: $izpis" }
    val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "changeit".toCharArray()) } }
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "changeit".toCharArray()) }
    return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
}

private class Streznik(naslov: String, ssl: SSLContext) {
    val zadetkov = AtomicInteger()
    @Volatile var preusmeri: String? = null
    private val s = HttpsServer.create(InetSocketAddress(InetAddress.getByName(naslov), 0), 0)
    val vrata get() = s.address.port

    init {
        s.httpsConfigurator = HttpsConfigurator(ssl)
        s.createContext("/") { ex ->
            zadetkov.incrementAndGet()
            val pot = ex.requestURI.path
            when {
                pot == "/loop" -> { ex.responseHeaders.add("Location", "/loop"); ex.sendResponseHeaders(302, -1) }
                pot == "/rel" -> { ex.responseHeaders.add("Location", "/list"); ex.sendResponseHeaders(302, -1) }
                pot == "/pojdi" -> { ex.responseHeaders.add("Location", preusmeri ?: "/list"); ex.sendResponseHeaders(302, -1) }
                pot == "/velik" -> { ex.sendResponseHeaders(200, 5_000_000); ex.responseBody.close() }
                ex.requestHeaders.getFirst("If-None-Match") == "\"v1\"" -> ex.sendResponseHeaders(304, -1)
                else -> {
                    val telo = "evil-one.example\nevil-two.example\n".toByteArray()
                    ex.responseHeaders.add("ETag", "\"v1\"")
                    ex.sendResponseHeaders(200, telo.size.toLong())
                    ex.responseBody.use { it.write(telo) }
                }
            }
            ex.close()
        }
        s.start()
    }

    fun ustavi() = s.stop(0)
}

private fun blokiran(blok: () -> Unit): Boolean = try { blok(); false } catch (e: Throwable) {
    generateSequence(e) { it.cause }.any { it is PrivateTargetException }
}

private fun napaka(blok: () -> Unit): String? = try { blok(); null } catch (e: IOException) { e.message ?: e.javaClass.simpleName }

fun main() {
    val mapa = java.nio.file.Files.createTempDirectory("safeer-fetcher").toFile()
    val strezniki = ArrayList<Streznik>()
    try {
        val ssl = naredi_kljuc(mapa)
        val zaupaj = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        val odjemalec = SSLContext.getInstance("TLS").apply { init(null, arrayOf(zaupaj), SecureRandom()) }
        val hook: (okhttp3.OkHttpClient.Builder) -> Unit = { b -> b.sslSocketFactory(odjemalec.socketFactory, zaupaj).hostnameVerifier { _, _ -> true } }
        val a = Streznik("127.0.0.2", ssl).also { strezniki += it }
        val b = Streznik("127.0.0.1", ssl).also { strezniki += it }
        // V preizkusu je »zaseben« samo 127.0.0.1; 127.0.0.2 posnema javni streznik (povezava nanj je dovoljena).
        val samoPrvi: (ByteArray) -> Boolean = { x -> x.size == 4 && x[0] == 127.toByte() && x[3] == 1.toByte() }
        fun fetcher(razresi: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }, javni: (ByteArray) -> Boolean = samoPrvi) =
            HttpsListFetcher(totalTimeoutMs = 30_000, resolver = razresi, isNonPublic = javni, clientHook = hook)
        val A = "https://127.0.0.2:${a.vrata}"
        val B = "https://127.0.0.1:${b.vrata}"

        // 0) delujoc prenos, ETag, pogojni zahtevek, relativna preusmeritev
        var odg: ConditionalResponse? = fetcher().fetch("$A/list", 1000, null, null)
        p(odg?.body?.decodeToString()?.startsWith("evil-one.example") == true && odg?.etag == "\"v1\"", "javni cilj: prenos in ETag")
        p(fetcher().fetch("$A/list", 1000, "\"v1\"", null).notModified, "javni cilj: 304 ob ETag")
        odg = fetcher().fetch("$A/rel", 1000, null, null)
        p(odg?.body != null, "relativna preusmeritev (Location: /list) deluje")
        // 1) IP-literal zasebnega cilja neposredno: nobene povezave (privzeta preverba brez injekcije)
        b.zadetkov.set(0)
        for (u in listOf("$B/x", "https://[::1]:${b.vrata}/x", "https://[::ffff:127.0.0.1]:${b.vrata}/x", "https://10.0.0.1/x", "https://192.168.1.1:8443/x",
            "https://169.254.169.254/x", "https://[fe80::1]/x", "https://[fd00::1]/x", "https://100.64.0.1/x", "https://0.0.0.0/x"))
            p(blokiran { HttpsListFetcher(totalTimeoutMs = 30_000, clientHook = hook).fetch(u, 1000, null, null) }, "zasebni IP-literal zavrnjen pred povezavo: ${u.take(40)}")
        p(b.zadetkov.get() == 0, "zasebni IP-literal: 0 zadetkov na ciljnem strezniku")

        // 1b) Slepi pregled TV4 (TV4-1): OkHttp za gostitelja iz samih stevk in pik NE vprasa Dns, ampak ga sam razresi (InetAddress.getByName),
        // zato so 127.1, 127.0.1, 2130706433, 0, 0x7f.1 in desetiski zapis LAN naslova obsli preverbo besedila. Preverba je zdaj tudi NA POVEZAVI.
        // Brez popravka vsi spodnji zapisi na 127.0.0.1 pridejo do streznika (zadetkov 1; izpis: ogled/tv-popravki3-1511/izpisi/tv4/).
        val lan = (192L shl 24) or (168L shl 16) or (1L shl 8) or 1L   // 192.168.1.1 kot celo stevilo
        b.zadetkov.set(0)
        for (g in listOf("127.1", "127.0.1", "2130706433", "0", "0x7f.1", "0x7f000001", "017700000001", "127.0.0.01", "127.0.0.1.", lan.toString(), "192.168.257", "10.1", "0.0.0.0", "1.2.3.4.5", "999.1.1.1"))
            p(blokiran { HttpsListFetcher(totalTimeoutMs = 30_000, clientHook = hook).fetch("https://$g:${b.vrata}/x", 1000, null, null) }, "TV4-1: zapis gostitelja zavrnjen: $g")
        p(b.zadetkov.get() == 0, "TV4-1: nobeden od zapisov ni prisel do streznika (zadetkov ${b.zadetkov.get()})")
        // preusmeritev javnega streznika na take zapise: zavrnjena, ciljni streznik ne dobi zahteve
        for (g in listOf("127.1", "2130706433", "0x7f.1", "127.0.1")) {
            a.preusmeri = "https://$g:${b.vrata}/skrivnost"; a.zadetkov.set(0); b.zadetkov.set(0)
            p(blokiran { fetcher().fetch("$A/pojdi", 1000, null, null) } && b.zadetkov.get() == 0, "TV4-1: preusmeritev na $g zavrnjena, zasebni cilj 0 zadetkov (je ${b.zadetkov.get()})")
        }
        // povezava sama: brez preverbe besedila gostitelja se vtic na povezavi ustavi PRED prvim paketom (surov strezniski vticnik steje sprejete povezave)
        java.net.ServerSocket(0, 5, InetAddress.getByName("127.0.0.1")).use { surov ->
            val sprejeto = AtomicInteger()
            val nit = Thread { try { while (true) { surov.accept().close(); sprejeto.incrementAndGet() } } catch (_: Exception) {} }.also { it.isDaemon = true; it.start() }
            val vtic = JavnaPovezava(ThreatListNetwork::isNonPublicAddress).createSocket()
            p(blokiran { vtic.connect(InetSocketAddress("127.0.0.1", surov.localPort), 2000) }, "TV4-1: vtic zavrne connect() na 127.0.0.1")
            p(blokiran { JavnaPovezava(ThreatListNetwork::isNonPublicAddress).createSocket().connect(InetSocketAddress(InetAddress.getByName("2130706433"), surov.localPort), 2000) }, "TV4-1: vtic zavrne tudi naslov, razresen iz 2130706433")
            p(blokiran { JavnaPovezava(ThreatListNetwork::isNonPublicAddress).createSocket().connect(InetSocketAddress.createUnresolved("javni.example", 443), 2000) }, "TV4-1: vtic zavrne nerazresen naslov (fail-closed)")
            Thread.sleep(200)
            p(sprejeto.get() == 0, "TV4-1: surov strezniski vticnik ni sprejel nobene povezave (sprejeto ${sprejeto.get()})")
            JavnaPovezava { false }.createSocket().use { s -> s.connect(InetSocketAddress("127.0.0.1", surov.localPort), 2000) }
            Thread.sleep(200)
            p(sprejeto.get() == 1, "TV4-1: nasprotni preizkus: ko preverba naslov dovoli, se vtic poveze (sprejeto ${sprejeto.get()})")
            surov.close(); nit.join(1000)
        }
        // preverba zapisa gostitelja: samo strogi d.d.d.d ali ime; kratki, celi, osmiski, sestnajstiski zapisi ne
        fun ok(g: String) = ThreatListNetwork.gostiteljJeDovoljenZapis(g)
        p(ok("8.8.8.8") && ok("example.com") && ok("sub.example.co.uk") && ok("example.com.") && ok("[2606:4700:4700::1111]") && ok("a1.example") && ok("xn--bcher-kva.example"), "TV4-1: imena, strogi d.d.d.d in IPv6 dovoljeni")
        p(!ok("127.1") && !ok("127.0.1") && !ok("2130706433") && !ok("0") && !ok("0x7f.1") && !ok("0x7f000001") && !ok("08.8.8.8") && !ok("010.1.1.1") && !ok("1.2.3.256") && !ok("1.2.3.4.5") && !ok("1.2.3") && !ok("1.2.3.4.") && !ok("example.0x7f") && !ok("example.123") && !ok(""), "TV4-1: kratki, celi, osmiski, sestnajstiski in neveljavni zapisi zavrnjeni")

        // 2) preusmeritev javnega streznika na zasebni IP-literal: skok se zavrne, ciljni streznik ne dobi zahteve
        a.preusmeri = "$B/skrivnost"; a.zadetkov.set(0); b.zadetkov.set(0)
        p(blokiran { fetcher().fetch("$A/pojdi", 1000, null, null) }, "preusmeritev na zasebni IP-literal zavrnjena")
        p(a.zadetkov.get() == 1 && b.zadetkov.get() == 0, "preusmeritev: prvi streznik 1 zadetek, zasebni cilj 0 (je ${a.zadetkov.get()}/${b.zadetkov.get()})")
        // 3) preusmeritev na ime, ki se razresi na zasebni naslov (DNS rebinding): zavrnjeno pred povezavo
        a.preusmeri = "https://rebind.example:${b.vrata}/skrivnost"; a.zadetkov.set(0); b.zadetkov.set(0)
        val razresiRebind: (String) -> List<InetAddress> = { h -> if (h == "rebind.example") listOf(InetAddress.getByName("127.0.0.1")) else InetAddress.getAllByName(h).toList() }
        p(blokiran { fetcher(razresiRebind).fetch("$A/pojdi", 1000, null, null) }, "preusmeritev na ime z zasebnim naslovom zavrnjena")
        p(b.zadetkov.get() == 0, "rebinding: zasebni cilj 0 zadetkov (je ${b.zadetkov.get()})")
        // 4) ime z mesanimi odgovori (javni + zaseben): zavrnjeno, povezave sploh ni
        a.zadetkov.set(0)
        val mesano: (String) -> List<InetAddress> = { listOf(InetAddress.getByName("127.0.0.2"), InetAddress.getByName("127.0.0.1")) }
        p(blokiran { fetcher(mesano).fetch("https://mix.example:${a.vrata}/list", 1000, null, null) }, "mesani odgovori (kateri koli zaseben) zavrnjeni")
        p(a.zadetkov.get() == 0, "mesani odgovori: 0 zadetkov (je ${a.zadetkov.get()})")
        // 5) pripenjanje: ime se razresi ENKRAT na skok; drugi odgovor (rebinding po preverbi) se ne uporabi
        val klici = AtomicInteger()
        val ena: (String) -> List<InetAddress> = { klici.incrementAndGet().let { n -> if (n == 1) listOf(InetAddress.getByName("127.0.0.2")) else listOf(InetAddress.getByName("127.0.0.1")) } }
        a.zadetkov.set(0); b.zadetkov.set(0)
        odg = try { fetcher(ena).fetch("https://pin.example:${a.vrata}/list", 1000, null, null) } catch (e: IOException) { println("     (izjema: ${e.javaClass.simpleName})"); null }
        p(odg?.body != null && klici.get() == 1 && a.zadetkov.get() == 1 && b.zadetkov.get() == 0, "pripenjanje: 1 razresitev, povezava na preverjeni naslov (klicev ${klici.get()})")
        // 6) protokol: https -> http se zavrne, zacetni http se zavrne, zanka preusmeritev se konca, prevelik odgovor
        a.preusmeri = "http://127.0.0.2:${a.vrata}/list"
        p(napaka { fetcher().fetch("$A/pojdi", 1000, null, null) }?.contains("away from HTTPS") == true, "preusmeritev https -> http zavrnjena")
        p(napaka { fetcher().fetch("http://127.0.0.2:${a.vrata}/list", 1000, null, null) }?.contains("only HTTPS") == true, "zacetni http zavrnjen")
        a.zadetkov.set(0)
        p(napaka { fetcher().fetch("$A/loop", 1000, null, null) }?.contains("too many redirects") == true && a.zadetkov.get() == 6, "zanka preusmeritev: najvec 5 skokov (zadetkov ${a.zadetkov.get()})")
        p(napaka { fetcher().fetch("$A/velik", 1000, null, null) }?.contains("too large") == true, "prevelik odgovor (Content-Length) zavrnjen")
        // 7) privzeta preverba naslovov: ista kot Linux in TV (ThreatListNetwork), vkljucno z NAT64 in 6to4 z vgrajenim zasebnim IPv4
        fun zas(s: String) = ThreatListNetwork.isNonPublicAddress(InetAddress.getByName(s).address)
        p(zas("10.1.2.3") && zas("127.0.0.1") && zas("169.254.169.254") && zas("172.16.0.1") && zas("192.168.0.1") && zas("100.64.0.1") && zas("0.0.0.0") && zas("224.0.0.1"), "privzeto: zasebni IPv4 zavrnjeni")
        p(zas("::1") && zas("fe80::1") && zas("fd00::1") && zas("ff02::1") && zas("64:ff9b::7f00:1") && zas("2002:c0a8:1::") && zas("2001:db8::1") && zas("::"), "privzeto: zasebni IPv6, NAT64 in 6to4 z zasebnim IPv4 zavrnjeni")
        p(!zas("8.8.8.8") && !zas("1.1.1.1") && !zas("2606:4700:4700::1111") && !zas("64:ff9b::808:808") && !zas("2002:808:808::"), "privzeto: javni naslovi (tudi NAT64/6to4 z javnim IPv4) dovoljeni")
    } finally {
        strezniki.forEach { it.ustavi() }
        mapa.deleteRecursively()
    }
    println("HttpsListFetcherPreverbaTest: $preverb preverb, $padlo padlo")
    if (padlo > 0) exitProcess(1)
}
