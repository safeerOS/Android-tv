package si.safeer.tv.os

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.Executors

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

private fun odgovori(x: HttpExchange, koda: Int, telo: String, glave: Map<String, String> = emptyMap()) {
    try {
        glave.forEach { (k, v) -> x.responseHeaders.add(k, v) }
        val b = telo.toByteArray()
        x.sendResponseHeaders(koda, if (b.isEmpty()) -1 else b.size.toLong())
        if (b.isNotEmpty()) x.responseBody.use { it.write(b) }
    } catch (_: IOException) { } finally { x.close() }
}

private fun spi(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) { }

/**
 * Sonda toka proti pravemu krajevnemu strezniku HTTP ([SondaVeriga]) - pregled 25. 10. 2026: S1 (rok sredi razlicic),
 * S2 (pocasen prvi bajt surovega toka), S3 (kapljanje - trd rok), N1 (streznik brez Range), N3 (kos 404 prve
 * razlicice), preusmeritev z relativnimi potmi, glave za vsako zahtevo, DRM, geografsko zaprt kos.
 */
fun main() {
    println("Preizkus sonde toka (krajevni streznik HTTP)")
    val M = OsPravila.IzidSonde.MRTEV
    val Z = OsPravila.IzidSonde.ZIV
    val X = OsPravila.IzidSonde.NEZNANO
    val streznik = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    streznik.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    val osnova = "http://127.0.0.1:${streznik.address.port}"
    val kosi = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:7\n#EXTINF:6,\nk1.ts\n#EXTINF:6,\nk2.ts\n"
    fun glavni(vararg r: String) = "#EXTM3U\n" + r.mapIndexed { i, p -> "#EXT-X-STREAM-INF:BANDWIDTH=${(i + 1) * 1000}\n$p\n" }.joinToString("")

    streznik.createContext("/") { x ->
        val pot = x.requestURI.path
        val range = x.requestHeaders.getFirst("Range") != null
        when {
            // S2: glave takoj, prvi bajt toka sele cez 2,5 s (tvheadend med uglasevanjem).
            pot == "/surov" -> try {
                x.sendResponseHeaders(200, 0); x.responseBody.flush(); spi(2500)
                x.responseBody.use { it.write(ByteArray(188) { 0x47 }) }
            } catch (_: IOException) { } finally { x.close() }
            // S3: seznam kaplja po bajt na 300 ms (vsako branje je pod rokom zahteve, celota ne).
            pot == "/kaplja.m3u8" -> try {
                x.sendResponseHeaders(200, 0)
                val o = x.responseBody
                o.write("#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray()); o.flush()
                repeat(60) { o.write('#'.code); o.flush(); spi(300) }
            } catch (_: IOException) { } finally { x.close() }
            // S1: prva razlicica 404, ostale odgovorijo sele po roku sonde.
            pot == "/s1/master.m3u8" -> odgovori(x, 200, glavni("v0.m3u8", "v1.m3u8", "v2.m3u8", "v3.m3u8"))
            pot == "/s1/v0.m3u8" -> odgovori(x, 404, "")
            pot.startsWith("/s1/v") -> { spi(3000); odgovori(x, 200, kosi) }
            pot == "/s1b/master.m3u8" -> odgovori(x, 200, glavni("v0.m3u8", "http://127.0.0.1:1/zaprto.m3u8", "v2.m3u8", "v3.m3u8"))
            pot.startsWith("/s1b/v") -> odgovori(x, 404, "")
            pot == "/mrtev/master.m3u8" -> odgovori(x, 200, glavni("a.m3u8", "b.m3u8", "c.m3u8", "d.m3u8"))
            pot.startsWith("/mrtev/") -> odgovori(x, 404, "")
            // N1: kos z Range 403, brez Range 200.
            pot == "/n1/index.m3u8" -> odgovori(x, 200, kosi)
            pot.startsWith("/n1/k") -> if (range) odgovori(x, 403, "") else odgovori(x, 200, "x".repeat(4000))
            pot == "/geo/index.m3u8" -> odgovori(x, 200, kosi)
            pot.startsWith("/geo/k") -> odgovori(x, 403, "")
            // N3: kosi prve razlicice 404, druge 206.
            pot == "/n3/master.m3u8" -> odgovori(x, 200, glavni("prva/index.m3u8", "druga/index.m3u8"))
            pot.endsWith("/index.m3u8") && pot.startsWith("/n3/") -> odgovori(x, 200, kosi)
            pot.startsWith("/n3/prva/k") -> odgovori(x, 404, "")
            pot.startsWith("/n3/druga/k") -> odgovori(x, 206, "x".repeat(2048))
            // Preusmeritev: relativne poti po ciljnem naslovu (DvdVir/getUri, 25. 10.).
            pot == "/pre" -> odgovori(x, 302, "", mapOf("Location" to "/cilj/master.m3u8"))
            pot == "/cilj/master.m3u8" -> odgovori(x, 200, glavni("v/index.m3u8"))
            pot == "/cilj/v/index.m3u8" -> odgovori(x, 200, kosi)
            pot.startsWith("/cilj/v/k") -> odgovori(x, 206, "x".repeat(2048))
            // N4: brez glave predvajalnika 403.
            pot.startsWith("/glave/") && x.requestHeaders.getFirst("X-Preizkus") != "1" -> odgovori(x, 403, "")
            pot == "/glave/index.m3u8" -> odgovori(x, 200, kosi)
            pot.startsWith("/glave/k") -> odgovori(x, 206, "x".repeat(2048))
            pot == "/drm.m3u8" -> odgovori(x, 200, "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES-CTR,URI=\"data:text/plain;base64,AA\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\"\n#EXTINF:6,\na.mp4\n")
            pot == "/stran" -> odgovori(x, 200, "<html><body>prijava</body></html>")
            else -> odgovori(x, 404, "")
        }
    }
    streznik.start()
    val izIzjeme: (IOException, String) -> Pair<OsPravila.IzidSonde, String> = { e, _ -> X to "napaka ${e.javaClass.simpleName}" }
    fun sonda(pot: String, rokMs: Long = 4000, rokZahtevkaMs: Int = 1500, glave: Map<String, String> = emptyMap()) =
        SondaVeriga.preveri(osnova + pot, { glave }, izIzjeme, SondaVeriga.Nastavitve(rokMs = rokMs, rokZahtevkaMs = rokZahtevkaMs))
    fun cas(blok: () -> Pair<OsPravila.IzidSonde, String>): Pair<Pair<OsPravila.IzidSonde, String>, Long> {
        val od = System.nanoTime(); val r = blok(); return r to (System.nanoTime() - od) / 1_000_000
    }

    val (s2, t2) = cas { sonda("/surov", rokMs = 6000, rokZahtevkaMs = 1000) }
    preveriEnako("S2: surov tok, prvi bajt po 2,5 s - ziv (${s2.second}, $t2 ms)", Z, s2.first)

    val (s3, t3) = cas { sonda("/kaplja.m3u8", rokMs = 2000, rokZahtevkaMs = 1500) }
    preveriEnako("S3: kapljanje - rok sonde, ne vemo (${s3.second})", X, s3.first)
    preveriEnako("S3: trd rok 2 s (izmerjeno $t3 ms < 2600)", true, t3 < 2600)

    val (s1, t1) = cas { sonda("/s1/master.m3u8", rokMs = 2000, rokZahtevkaMs = 1500) }
    preveriEnako("S1: prva razlicica 404, ostale po roku - ne vemo, ne mrtev (${s1.second})", X, s1.first)
    preveriEnako("S1: rok drzi (izmerjeno $t1 ms < 2600)", true, t1 < 2600)
    preveriEnako("S1b: ena razlicica ni dosegljiva, ostale 404 - ne vemo", X, sonda("/s1b/master.m3u8").first)
    val mrtev = sonda("/mrtev/master.m3u8")
    preveriEnako("vse 4 razlicice 404 - mrtev (${mrtev.second})", M, mrtev.first)

    preveriEnako("N1: kos z Range 403, brez Range 200 - ziv", Z, sonda("/n1/index.m3u8").first)
    val geo = sonda("/geo/index.m3u8")
    preveriEnako("geografsko zaprt kos (403 tudi brez Range) - mrtev (${geo.second})", M, geo.first)
    preveriEnako("N3: kos prve razlicice 404, druge 206 - ziv", Z, sonda("/n3/master.m3u8").first)
    preveriEnako("preusmeritev, relativna razlicica po cilju - ziv", Z, sonda("/pre").first)
    preveriEnako("N4: glave za vsako zahtevo - ziv", Z, sonda("/glave/index.m3u8", glave = mapOf("X-Preizkus" to "1")).first)
    preveriEnako("N4: brez glave - mrtev (403)", M, sonda("/glave/index.m3u8").first)
    preveriEnako("DRM - mrtev", M, sonda("/drm.m3u8").first)
    preveriEnako("2xx, ni seznam (stran) - ziv kot doslej", Z, sonda("/stran").first)
    preveriEnako("404 prvega naslova - mrtev", M, sonda("/ni-ga").first)
    preveriEnako("ni http - ne vemo", X, SondaVeriga.preveri("rtmp://a/b", { emptyMap() }, izIzjeme).first)

    streznik.stop(0)
    println(if (napak == 0) "Sonda toka: vse v redu" else "Sonda toka: $napak napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
