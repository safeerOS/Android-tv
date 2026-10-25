package si.safeer.tv.os

import com.sun.net.httpserver.HttpServer
import okhttp3.Dns
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

private var napakHttp = 0

private fun preveriHttp(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napakHttp++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Vsi naslovi so »javni« (lokalni streznik v preizkusu); prava preverba je [WebtorPravila.zasebenNaslov]. */
private val nicZasebno: (ByteArray) -> Boolean = { false }

/**
 * Pregled 26 (Grok, T1): zahteve toka Webtor prek OkHttp ([WebtorHttp]) - (1) nikoli same ne sledijo preusmeritvi (tudi
 * istega protokola), (2) vsako ime razresijo enkrat in se povezejo samo na preverjene javne naslove (brez rebindinga), (3)
 * predvajalnik pozna samo pripete naslove koncnega gostitelja. Lokalni streznik HTTP (JDK), brez interneta.
 */
fun main() {
    println("Preizkus zahtev toka Webtor (OkHttp)")
    val streznik = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    val vDomace = AtomicInteger()
    streznik.createContext("/tok") { x ->
        // Streznik koncnega naslova predvajalniku (ali sondi) vrne preusmeritev v »domace omrezje« (pregled 26, T1 scenarij).
        x.responseHeaders.add("Location", "/usmerjevalnik")
        x.sendResponseHeaders(302, -1); x.close()
    }
    streznik.createContext("/usmerjevalnik") { x ->
        vDomace.incrementAndGet()
        val b = "admin".toByteArray(); x.sendResponseHeaders(200, b.size.toLong()); x.responseBody.use { it.write(b) }
    }
    streznik.createContext("/film") { x ->
        val b = ByteArray(1000) { (it % 251).toByte() }
        val obseg = x.requestHeaders.getFirst("Range")
        if (obseg == "bytes=100-199") {
            x.responseHeaders.add("Content-Range", "bytes 100-199/1000"); x.sendResponseHeaders(206, 100)
            x.responseBody.use { it.write(b, 100, 100) }
        } else { x.sendResponseHeaders(200, b.size.toLong()); x.responseBody.use { it.write(b) } }
    }
    streznik.start()
    val vrata = streznik.address.port
    try {
        // (1) Brez sledenja preusmeritvam: klicatelj vidi 302, zahteve na cilj preusmeritve ni.
        val k = WebtorHttp.klient(WebtorHttp.Preverjeni(zaseben = nicZasebno), 5_000)
        k.newCall(Request.Builder().url("http://127.0.0.1:$vrata/tok").build()).execute().use { r ->
            preveriHttp("302 ostane 302 (ne sledi)", 302, r.code)
        }
        preveriHttp("cilj preusmeritve ni dobil zahteve", 0, vDomace.get())

        // (2) Zasebni naslov po DNS (localhost -> 127.0.0.1): zavrnjen pred povezavo (fail-closed).
        val prava = WebtorHttp.Preverjeni()
        val zavrnjen = try {
            WebtorHttp.klient(prava, 5_000).newCall(Request.Builder().url("http://localhost:$vrata/film").build()).execute().use { "povezal: ${it.code}" }
        } catch (e: WebtorHttp.ZasebenNaslov) { "zasebno" } catch (e: Exception) { "druga napaka ${e.javaClass.simpleName}" }
        preveriHttp("localhost je zaseben - brez povezave", "zasebno", zavrnjen)
        preveriHttp("zasebenNaslov: 127.0.0.1", true, WebtorPravila.zasebenNaslov(byteArrayOf(127, 0, 0, 1)))

        // (3) Brez rebindinga: ime se razresi enkrat - drugi DNS odgovor (zaseben naslov) ne pride v igro.
        val klici = AtomicInteger()
        val javni = InetAddress.getByAddress("cdn.example", byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        val zasebni = InetAddress.getByAddress("cdn.example", byteArrayOf(192.toByte(), 168.toByte(), 1, 1))
        val menjava = WebtorHttp.Preverjeni(razresi = { _ -> if (klici.incrementAndGet() == 1) listOf(javni) else listOf(zasebni) })
        preveriHttp("prvi DNS odgovor", listOf(javni), menjava.lookup("cdn.example"))
        preveriHttp("drugi lookup: isti preverjeni naslov", listOf(javni), menjava.lookup("CDN.example."))
        preveriHttp("DNS samo enkrat", 1, klici.get())
        preveriHttp("za(): preverjeni naslovi gostitelja", listOf(javni), menjava.za("cdn.example"))
        val mesan = WebtorHttp.Preverjeni(razresi = { _ -> listOf(javni, zasebni) })
        val mesanIzid = try { mesan.lookup("x.example"); "sprejet" } catch (_: WebtorHttp.ZasebenNaslov) { "zasebno" }
        preveriHttp("javni + zasebni naslov skupaj: zavrnjen", "zasebno", mesanIzid)

        // (3b) Gostitelj kot naslov IP gre v OkHttp mimo DNS - preveri ga sonda enako (preveriIp).
        val ipp = WebtorHttp.Preverjeni()
        val ipZaseben = try { ipp.preveriIp("192.168.1.1"); "sprejet" } catch (_: WebtorHttp.ZasebenNaslov) { "zasebno" }
        preveriHttp("IP 192.168.1.1: zaseben", "zasebno", ipZaseben)
        val ip6Zaseben = try { ipp.preveriIp("[::1]"); "sprejet" } catch (_: WebtorHttp.ZasebenNaslov) { "zasebno" }
        preveriHttp("IP [::1]: zaseben", "zasebno", ip6Zaseben)
        preveriHttp("IP javen: sprejet in zapomnjen", 1, ipp.preveriIp("93.184.216.34")?.size)
        preveriHttp("IP javen: za() ga pozna", 1, ipp.za("93.184.216.34").size)
        preveriHttp("ime ni naslov IP", null, ipp.preveriIp("cdn.example"))
        val desetiski = try { WebtorHttp.Preverjeni().lookup("3232235777"); "sprejet" } catch (_: WebtorHttp.ZasebenNaslov) { "zasebno" } catch (_: UnknownHostException) { "dns" }
        preveriHttp("desetiski IP 3232235777 (192.168.1.1) prek DNS: zaseben", "zasebno", desetiski)

        // (4) Pripet DNS (predvajalnik): samo koncni gostitelj in samo preverjeni naslovi.
        val pripet: Dns = WebtorHttp.pripet("cdn.example", listOf(javni))
        preveriHttp("pripet: koncni gostitelj", listOf(javni), pripet.lookup("cdn.example"))
        val drug = try { pripet.lookup("drug.example"); "razresen" } catch (_: UnknownHostException) { "ni" }
        preveriHttp("pripet: drug gostitelj ni razresen", "ni", drug)
        val prazen = try { WebtorHttp.pripet("cdn.example", emptyList()).lookup("cdn.example"); "razresen" } catch (_: UnknownHostException) { "ni" }
        preveriHttp("pripet brez naslovov: nic", "ni", prazen)

        // (5) Obseg (Range) za Media3 DataSpec in branje z obsegom.
        preveriHttp("obseg: cela datoteka", null, WebtorHttp.obseg(0, -1))
        preveriHttp("obseg: od 100 do konca", "bytes=100-", WebtorHttp.obseg(100, -1))
        preveriHttp("obseg: 100 bajtov od 100", "bytes=100-199", WebtorHttp.obseg(100, 100))
        k.newCall(Request.Builder().url("http://127.0.0.1:$vrata/film").header("Range", WebtorHttp.obseg(100, 100)!!).build()).execute().use { r ->
            preveriHttp("206 z obsegom", 206, r.code)
            preveriHttp("100 bajtov", 100, r.body?.bytes()?.size)
        }
    } finally {
        streznik.stop(0)
    }
    if (napakHttp == 0) println("WebtorHttpTest: OK") else { println("Napak: $napakHttp"); System.exit(1) }
}
