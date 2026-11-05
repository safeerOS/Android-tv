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
    println("HubPreusmeritveTest: OK")
}
