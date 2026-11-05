package si.safeer.tv.cast

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

// S6 (pregled 5. 11. 2026, preneseno s telefona ae93970): odjemalec Huba z X-Safeer-Token ne sledi preusmeritvi - zeton ne pride na drug naslov.
// Krajevna streznika: A odgovori 302 na B (http, kjer bi OkHttp privzeto sledil), B zabelezi, ali je prejel zahtevo.
fun main() {
    val b = ServerSocket(0)
    val prejelB = AtomicReference<String?>(null)
    val nitB = thread(isDaemon = true) {
        try {
            b.accept().use { s ->
                val glave = s.getInputStream().bufferedReader().let { r ->
                    generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.joinToString("\n")
                }
                prejelB.set(glave)
                s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
            }
        } catch (_: Exception) { }
    }
    val a = ServerSocket(0)
    thread(isDaemon = true) {
        try {
            a.accept().use { s ->
                val r = s.getInputStream().bufferedReader()
                generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.count()
                s.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${b.localPort}/kradem\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
            }
        } catch (_: Exception) { }
    }
    val odjemalec = HubPreusmeritve.brez(okhttp3.OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)).build()
    val zahteva = okhttp3.Request.Builder().url("http://127.0.0.1:${a.localPort}/cast/ticket")
        .addHeader("X-Safeer-Token", "skrivni-zeton").build()
    val koda = odjemalec.newCall(zahteva).execute().use { it.code }
    nitB.join(500)
    check(prejelB.get() == null) { "zeton je sledil preusmeritvi na drug naslov: ${prejelB.get()}" }
    check(koda == 302) { "3xx mora priti do klicatelja (ni 200 = napaka), dobil $koda" }
    check(!odjemalec.followRedirects && !odjemalec.followSslRedirects)
    // Preverba naslova: preusmeritev pomeni, da tu ni Huba.
    for (k in listOf(301, 302, 303, 307, 308)) check(HubPreusmeritve.kodaPreverbe(k) == 0) { "koda $k" }
    for (k in listOf(0, 200, 401, 404, 405)) check(HubPreusmeritve.kodaPreverbe(k) == k)
    a.close(); b.close()
    odjemalec.dispatcher.executorService.shutdown()
    odjemalec.connectionPool.evictAll()
    preveriVelikost()
    println("HubPreusmeritveTest: OK")
}

/** Ena preusmeritev: A odgovori 302 na B; vrne glave, ki jih je prejel B (null = B ni dobil zahteve). */
private fun prekoPreusmeritve(zahteva: (String) -> okhttp3.Request, odjemalec: okhttp3.OkHttpClient): String? {
    val b = ServerSocket(0)
    val prejelB = AtomicReference<String?>(null)
    val nitB = thread(isDaemon = true) {
        try {
            b.accept().use { s ->
                prejelB.set(s.getInputStream().bufferedReader().let { r -> generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.joinToString("\n") })
                s.getOutputStream().write("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-0/1234\r\nContent-Length: 1\r\nConnection: close\r\n\r\nx".toByteArray())
            }
        } catch (_: Exception) { }
    }
    val a = ServerSocket(0)
    thread(isDaemon = true) {
        try {
            a.accept().use { s ->
                val r = s.getInputStream().bufferedReader()
                generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.count()
                s.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${b.localPort}/drugje\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
            }
        } catch (_: Exception) { }
    }
    try { odjemalec.newCall(zahteva("http://127.0.0.1:${a.localPort}/tok")).execute().close() } catch (_: Exception) { }
    nitB.join(1000)
    a.close(); b.close()
    return prejelB.get()
}

// Preverba TV 5. 11. 2026 (P1): poizvedba velikosti v link/Pretok - zeton samo napravi s pripetim odtisom, zunanji vir brez njega.
private fun preveriVelikost() {
    val glave = mapOf("User-Agent" to "Safeer OS", "x-safeer-token" to "iz-glav-toka")
    val zunanji = HubPreusmeritve.zahtevaVelikosti("https://cdn.example/f.mkv", "", "skrivni-zeton", glave)
    check(zunanji.header(HubPreusmeritve.GLAVA_ZETONA) == null) { "zunanji vir je dobil zeton" }
    check(zunanji.header("User-Agent") == "Safeer OS" && zunanji.header("Range") == "bytes=0-0")
    val naprava = HubPreusmeritve.zahtevaVelikosti("https://10.0.0.5:8443/f.mkv", "ab:cd", "skrivni-zeton", emptyMap())
    check(naprava.header(HubPreusmeritve.GLAVA_ZETONA) == "skrivni-zeton") { "naprava s pripetim odtisom brez zetona" }
    // Zunanji vir: navaden odjemalec sledi preusmeritvi (CDN) - na drugem gostitelju zetona ne sme biti.
    val navaden = okhttp3.OkHttpClient.Builder().connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build()
    val prejel = prekoPreusmeritve({ u -> HubPreusmeritve.zahtevaVelikosti(u, "", "skrivni-zeton", glave) }, navaden)
    check(prejel != null) { "zunanji vir: preusmeritev naj velja (CDN)" }
    check(!prejel.contains("skrivni-zeton", ignoreCase = true) && !prejel.contains("iz-glav-toka")) { "zeton je sel na drug naslov: $prejel" }
    navaden.dispatcher.executorService.shutdown(); navaden.connectionPool.evictAll()
    println("  ok  P1 zeton samo napravi s pripetim odtisom")
}
