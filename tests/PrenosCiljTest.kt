package si.safeer.tv.link

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Pregled 10. 11. 2026 (1.1): cilj prenosa v Shrambi (`storage.put`) in Pretvorbi (`video.transcode`) - ista preverba kot
 * Pretok; v stanje gre koda napake, ne besedilo izjeme.
 */
private var preverb = 0

private fun preveri(opis: String, pogoj: Boolean) {
    check(pogoj) { "NAPAKA: $opis" }
    preverb++
}

private fun zapis() {
    for (u in listOf("https://127.0.0.1:8443/d/1", "https://127.8.9.10/d/1", "https://localhost:8443/d/1", "https://LOCALHOST./d/1",
            "https://tv.localhost/d/1", "https://[::ffff:127.0.0.1]:8443/d/1", "https://[::ffff:7f00:1]/d/1", "https://169.254.169.254/latest",
            "https://169.254.10.20:8443/d/1", "https://[::1]:8443/d/1", "https://[::]/d/1", "https://0.0.0.0/d/1", "https://[fe80::1]/d/1",
            "https://[::ffff:169.254.169.254]/d/1", "https://[64:ff9b::7f00:1]/d/1", "https://224.0.0.1/d/1", "https://a b/d/1", "https://", ""))
        preveri("cilj ni dovoljen: $u", PretokHttp.ciljPrenosaNedovoljen(u))
    for (u in listOf("https://192.168.1.20:8443/d/1", "https://10.0.0.5:39001/d/abc", "https://[fd00::5]:8443/d/1", "https://racunalnik.lan:8443/d/1"))
        preveri("cilj v domacem omrezju je dovoljen: $u", !PretokHttp.ciljPrenosaNedovoljen(u))
}

private fun vrze(blok: () -> Unit): Throwable? = try { blok(); null } catch (t: Throwable) { t }

private fun razresenaImena() {
    val naZanko = PretokHttp.Nastavitve(razresi = { listOf(InetAddress.getByAddress(it, byteArrayOf(127, 0, 0, 1))) })
    preveri("ime, ki kaze na zanko, ni dovoljeno", vrze { PretokHttp.dnsPripeteNaprave(naZanko).lookup("racunalnik.lan") } is PretokHttp.NedovoljenCilj)
    val meta = PretokHttp.Nastavitve(razresi = { listOf(InetAddress.getByAddress(it, byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte()))) })
    preveri("ime, ki kaze na povezavno-lokalni naslov, ni dovoljeno", vrze { PretokHttp.dnsPripeteNaprave(meta).lookup("racunalnik.lan") } is PretokHttp.NedovoljenCilj)
    val mesano = PretokHttp.Nastavitve(razresi = { listOf(InetAddress.getByAddress(it, byteArrayOf(192.toByte(), 168.toByte(), 1, 20)),
        InetAddress.getByAddress(it, byteArrayOf(127, 0, 0, 1))) })
    preveri("en prepovedan naslov med razresenimi: noben", vrze { PretokHttp.dnsPripeteNaprave(mesano).lookup("racunalnik.lan") } is PretokHttp.NedovoljenCilj)
    preveri("localhost ni dovoljen niti brez razresevanja", vrze { PretokHttp.dnsPripeteNaprave().lookup("localhost") } is PretokHttp.NedovoljenCilj)
    val doma = PretokHttp.Nastavitve(razresi = { listOf(InetAddress.getByAddress(it, byteArrayOf(192.toByte(), 168.toByte(), 1, 20))) })
    preveri("ime v domacem omrezju je dovoljeno", PretokHttp.dnsPripeteNaprave(doma).lookup("racunalnik.lan").size == 1)
}

/** Odjemalec s preverjenim DNS (kot v Shramba.prenesi) se na streznik na zanki sploh ne poveze. */
private fun povezava() {
    val streznik = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    streznik.soTimeout = 500
    try {
        val naZanko = PretokHttp.Nastavitve(razresi = { listOf(InetAddress.getByAddress(it, byteArrayOf(127, 0, 0, 1))) })
        val odjemalec = OkHttpClient.Builder().dns(PretokHttp.dnsPripeteNaprave(naZanko)).followRedirects(false)
            .connectTimeout(2, TimeUnit.SECONDS).build()
        val napaka = vrze { odjemalec.newCall(Request.Builder().url("http://racunalnik.lan:${streznik.localPort}/d/1").build()).execute().close() }
        preveri("zahteva na ime, ki kaze na zanko, pade", napaka != null)
        preveri("koda napake je nedovoljen_cilj", PrenosPravila.kodaNapake(napaka!!) { it is PretokHttp.NedovoljenCilj } == "nedovoljen_cilj")
        preveri("streznik na zanki ni dobil povezave", vrze { streznik.accept().close() } is java.net.SocketTimeoutException)
        odjemalec.dispatcher.executorService.shutdown()
        odjemalec.connectionPool.evictAll()
    } finally { streznik.close() }
}

private fun kode() {
    val cilj: (Throwable) -> Boolean = { it is PretokHttp.NedovoljenCilj }
    preveri("nasa koda ostane", PrenosPravila.kodaNapake(PrenosPravila.Napaka("ne_ujema"), cilj) == "ne_ujema")
    preveri("nedovoljen cilj, zavit v drugo izjemo", PrenosPravila.kodaNapake(java.io.IOException("x", PretokHttp.NedovoljenCilj("nedovoljen_cilj")), cilj) == "nedovoljen_cilj")
    // Besedilo izjeme (zavrnjena povezava, iztek casa, koda HTTP) ne gre v stanje: ena koda za vse.
    val omrezne = listOf(java.net.ConnectException("Connection refused (192.168.1.7:22)"), java.net.SocketTimeoutException("connect timed out"),
        java.net.UnknownHostException("skrito.lan"), javax.net.ssl.SSLHandshakeException("odtis se ne ujema"),
        IllegalStateException("racunalnik je vrnil 404"), java.io.IOException("unexpected end of stream"))
    for (e in omrezne) {
        val koda = PrenosPravila.kodaNapake(e, cilj)
        preveri("${e.javaClass.simpleName}: ena koda brez besedila", koda == "prenos_ni_uspel" && !koda.contains(e.message.orEmpty()))
    }
    preveri("veriga vzrokov brez konca se ustavi", PrenosPravila.kodaNapake(RuntimeException("a", RuntimeException("b")), cilj) == "prenos_ni_uspel")
}

/** Shramba in Pretvorba pravila res uporabljata (razreda rabita Android in tu ne teceta). */
private fun uporaba(izvorna: String) {
    if (izvorna.isEmpty()) return
    for (ime in listOf("Shramba", "Pretvorba")) {
        val vir = java.io.File(izvorna, "si/safeer/tv/link/$ime.kt").readText()
        preveri("$ime zavrne nedovoljen cilj", vir.contains("if (PretokHttp.ciljPrenosaNedovoljen(url)) return Daljinec.Izid(false, \"Vir ni dovoljen\", koda = PrenosPravila.KODA_CILJ)"))
        preveri("$ime prenasa s preverjenim DNS", vir.contains("PripetiVir.odjemalecZaStreznik(odtis, PretokHttp.dnsPripeteNaprave())"))
        preveri("$ime v stanje ne da besedila izjeme", !vir.contains("e.message") && vir.contains("PrenosPravila.kodaNapake(e) { it is PretokHttp.NedovoljenCilj }"))
        preveri("$ime v dnevnik ne pise imena datoteke", !Regex("Log\\.[iwde]\\([^\\n]*o\\.ime").containsMatchIn(vir))
    }
}

fun main(args: Array<String>) {
    zapis()
    razresenaImena()
    povezava()
    kode()
    uporaba(args.firstOrNull().orEmpty())
    println("PrenosCiljTest: OK ($preverb preverb)")
}
