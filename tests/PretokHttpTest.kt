package si.safeer.tv.link

import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Lazni streznik na [naslov]: zabelezi glave vsake zahteve, odgovori z [odgovor](stevilka zahteve). */
private class LazniStreznik(naslov: String, val odgovor: (Int) -> String) {
    val vticnica = ServerSocket(0, 50, InetAddress.getByName(naslov))
    val vrata = vticnica.localPort
    val zahteve: MutableList<String> = Collections.synchronizedList(mutableListOf())
    init {
        thread(isDaemon = true) {
            try {
                while (true) {
                    vticnica.accept().use { s ->
                        val r = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val glave = generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.joinToString("\n")
                        zahteve.add(glave)
                        s.getOutputStream().write(odgovor(zahteve.size).toByteArray(Charsets.ISO_8859_1))
                    }
                }
            } catch (_: Exception) { }
        }
    }
    fun zapri() = vticnica.close()
}

private fun preusmeri(kam: String) = "HTTP/1.1 302 Found\r\nLocation: $kam\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
private const val V_REDU = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"

/** Preizkus dovoli samo 127.0.0.2 in 127.0.0.3 (lazna »javna« vira); vse drugo po pravem pravilu, tudi 127.0.0.1. */
private fun prepovedanPreizkus(b: ByteArray): Boolean {
    val lazniJavni = b.size == 4 && b[0].toInt() == 127 && b[1].toInt() == 0 && b[2].toInt() == 0 &&
        (b[3].toInt() == 2 || b[3].toInt() == 3)
    return !lazniJavni && PretokPravila.prepovedan(b)
}

/** Koda napake iz PretokHttp.odpri ali "" (ni napake); [glave] gredo kot glave toka. */
private fun odpri(url: String, glave: Map<String, String> = emptyMap()): String {
    val n = PretokHttp.Nastavitve(
        razresi = { ime -> if (ime == "zanka.preizkus") listOf(InetAddress.getByName("127.0.0.1")) else InetAddress.getAllByName(ime).toList() },
        prepovedan = { prepovedanPreizkus(it) })
    val klient = PretokHttp.klient(n, 3, 3)
    return try {
        PretokHttp.odpri(klient, url, url, glave, null, n = n).odgovor.close(); ""
    } catch (e: PretokHttp.NedovoljenCilj) { e.koda } catch (e: Exception) { "izjema " + e.javaClass.simpleName }
    finally { klient.connectionPool.evictAll(); klient.dispatcher.executorService.shutdown() }
}

/**
 * Vodja 5. 11. 2026: Media3 DefaultHttpDataSource je v Pretok preusmeritvam sledil sam - 302 na 127.0.0.1 ali
 * povezavno-lokalen naslov je obsel PretokPravila. Vsak skok preveri PretokHttp (kot Linux core/link_sprotno._odpri).
 */
fun main() {
    println("Preizkus skokov preusmeritve sprotne pretvorbe")
    val prvi = "http://cdn.example/film.mkv"
    fun skok(trenutni: String, lok: String?, st: Int = 0, zaupan: Boolean = true) = PretokHttp.naslednjiSkok(trenutni, lok, st, zaupan, prvi)
    for (lok in listOf("http://127.0.0.1:8080/x", "http://localhost/x", "http://169.254.169.254/latest/meta-data/",
            "http://[::ffff:127.0.0.1]/x", "http://[::1]:9/x", "http://0.0.0.0/x", "http://[fe80::1]/x"))
        preveriEnako("skok na $lok", "nedovoljen_cilj", skok(prvi, lok).napaka)
    preveriEnako("https -> http ne", "nedovoljen_cilj", skok("https://cdn.example/a", "http://cdn.example/a").napaka)
    preveriEnako("http -> https da", "", skok(prvi, "https://cdn.example/a").napaka)
    preveriEnako("najvec 5 skokov", "prevec_preusmeritev", skok(prvi, "/b", PretokHttp.NAJVEC_SKOKOV).napaka)
    preveriEnako("4. skok se dovoljen", "", skok(prvi, "/b", PretokHttp.NAJVEC_SKOKOV - 1).napaka)
    preveriEnako("prazna Location", "napaka", skok(prvi, " ").napaka)
    preveriEnako("brez Location", "napaka", skok(prvi, null).napaka)
    preveriEnako("ftp ne", "napaka", skok(prvi, "ftp://cdn.example/a").napaka)
    val rel = skok(prvi, "/drugo.mkv")
    preveriEnako("relativna pot", "http://cdn.example/drugo.mkv", rel.url)
    preveriEnako("isti izvor ostane zaupan", true, rel.zaupan)
    preveriEnako("drug izvor ni zaupan", false, skok(prvi, "http://cdn2.example/a").zaupan)
    preveriEnako("druga vrata niso isti izvor", false, skok(prvi, "http://cdn.example:8080/a").zaupan)
    preveriEnako("vrnitev na izvor ostane nezaupana", false, skok("http://cdn2.example/a", prvi, 1, false).zaupan)
    val toka = mapOf("Authorization" to "Bearer skrivno", "X-Api-Key" to "kljuc-vira", "X-Safeer-Token" to "zeton", "User-Agent" to "Igralec/1")
    preveriEnako("zaupan: glave toka brez zetona", mapOf("User-Agent" to "Igralec/1", "Authorization" to "Bearer skrivno",
        "X-Api-Key" to "kljuc-vira", "Accept-Encoding" to "identity"), PretokHttp.glaveZahteve(toka, true))
    preveriEnako("nezaupan: samo privzeti UA", mapOf("User-Agent" to "Safeer OS", "Accept-Encoding" to "identity"),
        PretokHttp.glaveZahteve(toka, false))

    // Lazni streznik A (127.0.0.2, »javni« vir) odgovori 302 na B na 127.0.0.1 - B ne sme dobiti zahteve.
    val b = LazniStreznik("127.0.0.1") { V_REDU }
    val a = LazniStreznik("127.0.0.2") { preusmeri("http://127.0.0.1:${b.vrata}/kradem") }
    preveriEnako("302 na 127.0.0.1 zavrnjen", "nedovoljen_cilj", odpri("http://127.0.0.2:${a.vrata}/film.mkv"))
    preveriEnako("B (127.0.0.1) ni dobil zahteve", 0, b.zahteve.size)
    a.zapri()

    // DNS rebinding: ime v Location se razresi v 127.0.0.1 - povezava ne sme steci.
    val a2 = LazniStreznik("127.0.0.2") { preusmeri("http://zanka.preizkus:${b.vrata}/kradem") }
    preveriEnako("302 na ime, ki kaze na 127.0.0.1", "nedovoljen_cilj", odpri("http://127.0.0.2:${a2.vrata}/film.mkv"))
    preveriEnako("B ni dobil zahteve po imenu", 0, b.zahteve.size)
    a2.zapri(); b.zapri()

    // Drug izvor: glave toka (Authorization) samo A, C (127.0.0.3) dobi samo privzeti UA.
    val c = LazniStreznik("127.0.0.3") { V_REDU }
    val a3 = LazniStreznik("127.0.0.2") { preusmeri("http://127.0.0.3:${c.vrata}/kos") }
    preveriEnako("preusmeritev na dovoljen drug izvor", "", odpri("http://127.0.0.2:${a3.vrata}/film.mkv", toka))
    preveriEnako("A je dobil Authorization", true, a3.zahteve.firstOrNull()?.contains("Authorization: Bearer skrivno") == true)
    preveriEnako("A ni dobil zetona Linka", false, a3.zahteve.firstOrNull()?.contains("X-Safeer-Token") == true)
    preveriEnako("C je dobil eno zahtevo", 1, c.zahteve.size)
    preveriEnako("C ni dobil Authorization", false, c.zahteve.firstOrNull()?.contains("Authorization") == true)
    // OkHttp sam odstrani samo Authorization; druge glave toka (kljuc ponudnika) bi sle naprej.
    preveriEnako("A je dobil X-Api-Key", true, a3.zahteve.firstOrNull()?.contains("X-Api-Key: kljuc-vira") == true)
    preveriEnako("C ni dobil X-Api-Key", false, c.zahteve.firstOrNull()?.contains("X-Api-Key") == true)
    preveriEnako("C ni dobil zetona", false, c.zahteve.firstOrNull()?.contains("X-Safeer-Token") == true)
    preveriEnako("C dobi privzeti UA", true, c.zahteve.firstOrNull()?.contains("User-Agent: Safeer OS") == true)
    a3.zapri(); c.zapri()

    // Neskoncna veriga: A preusmerja nase - najvec 1 + 5 zahtev, nato napaka.
    val a4 = LazniStreznik("127.0.0.2") { i -> preusmeri("/zanka/$i") }
    preveriEnako("neskoncna veriga", "prevec_preusmeritev", odpri("http://127.0.0.2:${a4.vrata}/film.mkv"))
    preveriEnako("zahtev v verigi", PretokHttp.NAJVEC_SKOKOV + 1, a4.zahteve.size)
    a4.zapri()

    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("Vse v redu.")
}
