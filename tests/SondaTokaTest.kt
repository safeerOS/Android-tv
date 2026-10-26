package si.safeer.tv.os

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Krajevni streznik preizkusa: 127.0.0.1 in samo ta naslov (vse drugo zasebno ostane zavrnjeno kot v produkciji). */
private val KRAJEVNI = byteArrayOf(127, 0, 0, 1)

/**
 * Naloga 114: sonda toka ([SondaToka.preveriZRazlogom]) proti PRAVEMU krajevnemu strezniku HTTP - primeri s televizorja
 * (naloge 93-108): surov MPEG-TS, preusmeritev http -> drug gostitelj in vrata, 403, 404, 503, zaprta vrata, zanka
 * preusmeritev. Razlog gre skozi [NedelujocePravila.izSonde] kot v aplikaciji.
 * Naloga 91: sonda se privzeto ne poveze na to napravo (J2). Kot v tests/SondaVerigaTest.kt (Nastavitve.zaseben) preizkus
 * zamenja preverbo zasebnega cilja - prek [SondaVeriga.zasebenZaPreizkus], ker gre klic skozi [SondaToka] - in dovoli
 * samo 127.0.0.1; pred in po primerih preveri, da privzeta zascita velja in streznik ne dobi zahteve.
 */
fun main() {
    println("Preizkus sonde toka (krajevni streznik)")
    val ts = ByteArray(188 * 50) { if (it % 188 == 0) 0x47 else 0 }
    val drugi = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    drugi.createContext("/auth/tok") { x -> x.responseHeaders.add("Content-Type", "video/mp2t"); x.sendResponseHeaders(200, ts.size.toLong()); x.responseBody.use { it.write(ts) } }
    drugi.start()
    val prvi = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val vrata2 = drugi.address.port
    prvi.createContext("/play/zeton") { x -> x.responseHeaders.add("Location", "http://127.0.0.1:$vrata2/auth/tok"); x.sendResponseHeaders(302, -1); x.close() }
    prvi.createContext("/ts") { x -> x.responseHeaders.add("Content-Type", "application/octet-stream"); x.sendResponseHeaders(200, ts.size.toLong()); x.responseBody.use { it.write(ts) } }
    for (koda in listOf(401, 403, 404, 503)) prvi.createContext("/$koda") { x -> x.sendResponseHeaders(koda, -1); x.close() }
    prvi.createContext("/zanka") { x -> x.responseHeaders.add("Location", "/zanka"); x.sendResponseHeaders(302, -1); x.close() }
    prvi.createContext("/brez-cilja") { x -> x.sendResponseHeaders(302, -1); x.close() }
    val zahtevStevca = AtomicInteger(0)
    prvi.createContext("/stevec") { x -> zahtevStevca.incrementAndGet(); x.sendResponseHeaders(200, ts.size.toLong()); x.responseBody.use { it.write(ts) } }
    prvi.start()
    val o = "http://127.0.0.1:${prvi.address.port}"
    val agent = "Safeer-preizkus"
    fun sonda(pot: String) = SondaToka.preveriZRazlogom(o + pot, emptyMap(), agent)
    // J2 privzeto: sonda prek SondaToka na to napravo ne gre - ne vemo, razlog zaseben naslov, streznik brez zahteve.
    fun preveriZascito(kdaj: String) {
        val (izid, razlog) = sonda("/stevec")
        preveriEnako("J2 $kdaj: privzeto 127.0.0.1 ne - ne vemo, zaseben naslov ($razlog)", OsPravila.IzidSonde.NEZNANO to true,
            izid to razlog.contains("zaseben"))
        preveriEnako("J2 $kdaj: streznik ni dobil zahteve", 0, zahtevStevca.get())
    }
    try {
        preveriZascito("pred primeri")
        SondaVeriga.zasebenZaPreizkus = { b -> !b.contentEquals(KRAJEVNI) && SondaVeriga.zasebenCilj(b) }
        try {
            val (izidPreusmeritve, razlogPreusmeritve) = sonda("/play/zeton")
            preveriEnako("302 na drug gostitelj in vrata -> TS 200: ziv", OsPravila.IzidSonde.ZIV, izidPreusmeritve)
            preveriEnako("razlog: po 1 preusmeritvi", "HTTP 200 po 1 preusmeritvah", razlogPreusmeritve)
            preveriEnako("octet-stream 200: ziv (sonda ne gleda vrste vsebine)", OsPravila.IzidSonde.ZIV, sonda("/ts").first)
            preveriEnako("403: mrtev", OsPravila.IzidSonde.MRTEV, sonda("/403").first)
            preveriEnako("404: mrtev", OsPravila.IzidSonde.MRTEV, sonda("/404").first)
            preveriEnako("404: razlog http:404", "http:404", NedelujocePravila.izSonde(sonda("/404").second))
            preveriEnako("401 (prijava): ne vemo", OsPravila.IzidSonde.NEZNANO, sonda("/401").first)
            preveriEnako("503: negotovo", OsPravila.IzidSonde.NEGOTOVO, sonda("/503").first)
            // Razlog s stevilom preusmeritev (SondaVeriga, pregled 25. 10. 2026).
            preveriEnako("zanka preusmeritev: ne vemo", OsPravila.IzidSonde.NEZNANO to "prevec preusmeritev po 5 preusmeritvah", sonda("/zanka"))
            preveriEnako("302 brez cilja: ne vemo", OsPravila.IzidSonde.NEZNANO to "preusmeritev brez cilja po 0 preusmeritvah", sonda("/brez-cilja"))

            // Zaprta vrata: zavrnjena povezava je dokaz samo, ce imenik isti hip odgovori za kontrolno ime (omrezje dela).
            val zaprta = ServerSocket(0).use { it.localPort }
            Imenik.odgovori = { imena -> imena.map { true } }
            val (izidZaprta, razlogZaprta) = SondaToka.preveriZRazlogom("http://127.0.0.1:$zaprta/x", emptyMap(), agent)
            preveriEnako("zaprta vrata ob delujocem omrezju: mrtev", OsPravila.IzidSonde.MRTEV, izidZaprta)
            preveriEnako("zaprta vrata: razlog neodziven", NedelujocePravila.NEODZIVEN, NedelujocePravila.izSonde(razlogZaprta))
            Imenik.odgovori = { imena -> imena.map { null } }
            preveriEnako("zaprta vrata, imenik ne odgovori: ne vemo", OsPravila.IzidSonde.NEZNANO,
                SondaToka.preveriZRazlogom("http://127.0.0.1:$zaprta/x", emptyMap(), agent).first)
            preveriEnako("ne-http: ne vemo", OsPravila.IzidSonde.NEZNANO, SondaToka.preveriZRazlogom("rtsp://127.0.0.1/x", emptyMap(), agent).first)
            // Dovoljena je samo 127.0.0.1: drug naslov te naprave ostane zavrnjen tudi med preizkusom.
            val drugNaslov = SondaToka.preveriZRazlogom("http://127.0.0.2:${prvi.address.port}/stevec", emptyMap(), agent)
            preveriEnako("J2 med primeri: 127.0.0.2 ne - ne vemo, zaseben naslov (${drugNaslov.second})", OsPravila.IzidSonde.NEZNANO to true,
                drugNaslov.first to drugNaslov.second.contains("zaseben"))
        } finally {
            SondaVeriga.zasebenZaPreizkus = null
        }
        preveriZascito("po primerih")
    } finally { prvi.stop(0); drugi.stop(0) }

    if (napak == 0) println("SondaTokaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
