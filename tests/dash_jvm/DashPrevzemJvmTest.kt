// Popravki TV-2 (15. 11. 2026): pravi DashPrevzem.kt na JVM z nadomestki Androida (dash_jvm/stubs) in lokalnim streznikom HTTPS
// (bank.test = stran uporabnika s sejnim piskotkom, napadalec.test = tuj vir na tej strani). Na izvorni datoteki 9a0a8ca8 PADEJO
// T1-T5 in T7; T6 (preusmeritev) na JVM ne pade, ker JDK sam odvrze Cookie ob preusmeritvi - na Androidu (OkHttp) NEPREVERJENO.
// Nadomestki ne preverjajo DashPravila, PredajaStrani, CookieManager (SameSite, tretje strani) in Handler kot na napravi.
//   tests/run_dash_jvm_test.sh   (DASH_PREVZEM=<pot> preveri drugo razlicico datoteke)
package si.safeer.tv

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.*
import kotlin.system.exitProcess

private var padlo = 0
private fun p(pogoj: Boolean, opis: String) { if (pogoj) println("OK   $opis") else { padlo++; println("PADE $opis") } }

data class Zahteva(val gostitelj: String, val pot: String, val piskotek: String?, val cas: Long)

fun main(args: Array<String>) {
    val ks = KeyStore.getInstance("PKCS12").apply { java.io.FileInputStream(args[0]).use { load(it, "geslo1".toCharArray()) } }
    val kmf = KeyManagerFactory.getInstance("SunX509").apply { init(ks, "geslo1".toCharArray()) }
    val ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    val zaupaj = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>?, a: String?) {}
        override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
    })
    val odj = SSLContext.getInstance("TLS").apply { init(null, zaupaj, null) }
    HttpsURLConnection.setDefaultSSLSocketFactory(odj.socketFactory)
    HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }

    val zahteve = CopyOnWriteArrayList<Zahteva>()
    val srv = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 16)
    srv.httpsConfigurator = HttpsConfigurator(ssl)
    srv.executor = java.util.concurrent.Executors.newCachedThreadPool()
    val P = srv.address.port
    val enc = "<MPD><ContentProtection schemeIdUri='urn:mpeg:dash:mp4protection:2011'/></MPD>"
    srv.createContext("/") { ex ->
        val pot = ex.requestURI.path
        zahteve.add(Zahteva(ex.requestHeaders.getFirst("Host") ?: "", pot, ex.requestHeaders.getFirst("Cookie"), System.nanoTime()))
        var koda = 200
        val telo = when {
            pot == "/r.mpd" -> { ex.responseHeaders.add("Location", "https://napadalec.test:$P/preusmerjen.mpd"); koda = 302; "" }
            pot.startsWith("/pocasi") -> { Thread.sleep(1500); "<MPD><ContentProtection/><dashif:Laurl>https://napadalec.test:$P/lic</dashif:Laurl></MPD>" }
            pot == "/slika.mpd" -> "<html>slika</html>"
            pot == "/lic.mpd" -> "<MPD><ContentProtection/><dashif:Laurl>https://bank.test:$P/skrivna/pot/lic?zeton=ABC</dashif:Laurl></MPD>"
            pot == "/redos.mpd" -> "<MPD><ContentProtection/>laurl" + " ".repeat(270 * 1024) + "</MPD>"
            pot.endsWith(".mpd") -> enc
            else -> "ok"
        }
        val b = telo.toByteArray()
        ex.sendResponseHeaders(koda, if (b.isEmpty()) -1 else b.size.toLong())
        if (b.isNotEmpty()) ex.responseBody.use { it.write(b) } else ex.close()
    }
    srv.start()
    android.webkit.CookieManager.POSODA["bank.test"] = "seja=TAJNO"
    android.webkit.CookieManager.POSODA["napadalec.test"] = "napad=1"
    val seje = CopyOnWriteArrayList<DashSeja>()
    DashPrevzem.listener = { seje.add(it) }
    fun cakaj(ms: Long, pogoj: () -> Boolean): Boolean { val k = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < k) { if (pogoj()) return true; Thread.sleep(20) }; return pogoj() }
    fun naGostitelju(g: String, pot: String) = zahteve.filter { it.gostitelj.startsWith(g) && it.pot == pot }

    // T1 P1: vir .mpd na tujem gostitelju na prijavljeni strani
    DashPrevzem.resetAll(); DashPrevzem.naStrani("https://bank.test/racun")
    DashPrevzem.observe("https://napadalec.test:$P/a.mpd", "GET", emptyMap())
    cakaj(3000) { naGostitelju("napadalec.test", "/a.mpd").isNotEmpty() }
    val t1 = naGostitelju("napadalec.test", "/a.mpd")
    p(t1.isNotEmpty() && t1.none { (it.piskotek ?: "").contains("TAJNO") }, "T1 P1: napadalec.test ni dobil piskotka strani (Cookie: ${t1.map { it.piskotek }})")

    // T2 nova najdba 1: namig licence widevine+license na tujem gostitelju
    seje.clear(); DashPrevzem.resetAll(); DashPrevzem.naStrani("https://bank.test/tv")
    DashPrevzem.observe("https://bank.test:$P/enc.mpd", "GET", emptyMap())
    DashPrevzem.observe("https://napadalec.test:$P/widevine/license", "GET", emptyMap())
    cakaj(3000) { seje.isNotEmpty() }
    val t2 = seje.firstOrNull()
    p(t2 != null && t2.licenseHeaders.values.none { it.contains("TAJNO") }, "T2 Nova 1: licenca ${t2?.licenseUrl} brez piskotka strani (glave ${t2?.licenseHeaders?.get("Cookie")})")

    // T3 P3: zahtevek .mpd, ki ni MPD
    DashPrevzem.resetAll(); DashPrevzem.naStrani("https://napadalec.test/")
    DashPrevzem.observe("https://napadalec.test:$P/slika.mpd", "GET", emptyMap())
    val takoj = DashPrevzem.imaSejo()
    cakaj(1500) { naGostitelju("napadalec.test", "/slika.mpd").isNotEmpty() }; Thread.sleep(400)
    p(!takoj && !DashPrevzem.imaSejo(), "T3 P3: brez potrjenega MPD ni seje (takoj=$takoj, po odgovoru=${DashPrevzem.imaSejo()})")

    // T4 P5.1: pocasen manifest strani A, nato stran B
    seje.clear(); DashPrevzem.resetAll(); DashPrevzem.naStrani("https://napadalec.test/")
    DashPrevzem.observe("https://napadalec.test:$P/pocasi.mpd", "GET", emptyMap())
    Thread.sleep(200)
    DashPrevzem.reset(); DashPrevzem.naStrani("https://bank.test/tv")
    DashPrevzem.observe("https://bank.test:$P/kanal/enc.mpd", "GET", emptyMap())
    Thread.sleep(3500)
    p(seje.isNotEmpty() && seje.none { it.licenseUrl.contains("napadalec") }, "T4 P5.1: tok strani B ni dobil licence strani A (seje: ${seje.map { it.mpdUrl.substringAfter(":$P") + " lic=" + it.licenseUrl }})")

    // T5 P11: dnevnik brez poti licence in imen glav
    seje.clear(); DashPrevzem.resetAll(); DashPrevzem.naStrani("https://bank.test/tv")
    DashPrevzem.observe("https://bank.test:$P/lic.mpd", "GET", emptyMap())
    cakaj(3000) { seje.isNotEmpty() }
    val log = android.util.Log.SPOROCILA.lastOrNull() ?: ""
    p(seje.isNotEmpty() && !log.contains("skrivna") && !log.contains("hdrNames") && SafeerDbg.ZAPISI.none { it.contains("skrivna") }, "T5 P11: Log.i brez poti licence in imen glav ($log)")

    // T6 P1/P2: preusmeritev z gostitelja strani na tujega
    DashPrevzem.resetAll(); DashPrevzem.naStrani("https://bank.test/tv")
    DashPrevzem.observe("https://bank.test:$P/r.mpd", "GET", emptyMap())
    cakaj(3000) { naGostitelju("napadalec.test", "/preusmerjen.mpd").isNotEmpty() }
    val t6 = naGostitelju("napadalec.test", "/preusmerjen.mpd")
    p(t6.none { (it.piskotek ?: "").contains("TAJNO") }, "T6 P1: preusmeritev na napadalec.test brez piskotka strani (zahtev ${t6.size}, Cookie ${t6.map { it.piskotek }}; JVM HttpURLConnection, ne Android)")

    // T7 P4 (zadnji: stara razlicica tu zasede edino nit): zlonameren MPD, nato pravi tok
    seje.clear(); DashPrevzem.resetAll(); DashPrevzem.naStrani("https://bank.test/tv")
    val z = System.nanoTime()
    DashPrevzem.observe("https://bank.test:$P/redos.mpd", "GET", emptyMap())
    Thread.sleep(100)
    DashPrevzem.observe("https://bank.test:$P/drug/enc.mpd", "GET", emptyMap())
    val prisel = cakaj(5000) { seje.any { it.mpdUrl.contains("/drug/") } }
    p(prisel, "T7 P4: po zlonamernem MPD (270 KiB) je pravi tok razclenjen v 5 s (${(System.nanoTime() - z) / 1_000_000} ms)")

    println("DashPrevzemJvmTest: padlo $padlo")
    exitProcess(if (padlo > 0) 1 else 0)
}
