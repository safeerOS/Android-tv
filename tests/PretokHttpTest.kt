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
private fun seznam(telo: String) =
    "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${telo.length}\r\nConnection: close\r\n\r\n$telo"

private fun zadnji(b: ByteArray, vrednosti: List<Int>) =
    b.size == 4 && b[0].toInt() == 127 && b[1].toInt() == 0 && b[2].toInt() == 0 && b[3].toInt() in vrednosti

/** Preizkus: 127.0.0.2 in 127.0.0.3 sta lazna javna vira, 127.0.0.4 lazno domace omrezje; drugo po pravih pravilih. */
private val NASTAVITVE = PretokHttp.Nastavitve(
    razresi = { ime ->
        when (ime) {
            "zanka.preizkus" -> listOf(InetAddress.getByName("127.0.0.1"))
            "lan.preizkus" -> listOf(InetAddress.getByName("127.0.0.4"))
            else -> InetAddress.getAllByName(ime).toList()
        }
    },
    prepovedan = { b -> !zadnji(b, listOf(2, 3, 4)) && PretokPravila.prepovedan(b) },
    zaseben = { b -> zadnji(b, listOf(4)) || (!zadnji(b, listOf(2, 3)) && si.safeer.tv.os.SondaVeriga.zasebenCilj(b)) })

/** Koda napake iz PretokHttp.odpri ali "" (ni napake); [vir] nosi glave toka in stanje zaupanja. */
private fun odpri(vir: PretokHttp.Vir, url: String = vir.prvi): String {
    val klient = PretokHttp.klient(NASTAVITVE, 3, 3)
    return try {
        PretokHttp.odpri(klient, vir, url, null).odgovor.close(); ""
    } catch (e: PretokHttp.NedovoljenCilj) { e.koda } catch (e: Exception) { "izjema " + e.javaClass.simpleName }
    finally { klient.zapri() }
}

private fun odpri(url: String, glave: Map<String, String> = emptyMap()) = odpri(PretokHttp.Vir(url, glave))

/**
 * Vodja 5. 11. 2026: Media3 DefaultHttpDataSource je v Pretok preusmeritvam sledil sam - 302 na 127.0.0.1 ali
 * povezavno-lokalen naslov je obsel PretokPravila. Vsak skok preveri PretokHttp (kot Linux core/link_sprotno._odpri).
 * Dopolnitev: domace omrezje samo z varnimi glavami (v_domace_omrezje), tuj seznam HLS odvzame zaupanje (_Vir.izpeljan).
 */
fun main() {
    // CodeRabbit 6. 11. 2026: gostitelj vira z razclenjevalnikom OkHttp (java.net.URI je zavrnil veljaven vir).
    preveriEnako("gostitelj: presledek in [ ] v poti", "cdn.example", PretokHttp.gostiteljVira("http://cdn.example/My Film [1080p].mkv"))
    preveriEnako("gostitelj: | v poti", "cdn.example", PretokHttp.gostiteljVira("https://cdn.example/a|b.mkv"))
    preveriEnako("gostitelj: podcrtaj v imenu", "moj_streznik.example", PretokHttp.gostiteljVira("https://moj_streznik.example/f.mkv"))
    preveriEnako("gostitelj: IPv6 brez oklepajev", "::1", PretokHttp.gostiteljVira("http://[::1]:8080/f"))
    preveriEnako("gostitelj: brez gostitelja", "", PretokHttp.gostiteljVira("http://"))
    preveriEnako("gostitelj: presledek v imenu gostitelja", "", PretokHttp.gostiteljVira("https://a b.example/f"))
    preveriEnako("gostitelj: ni http", "", PretokHttp.gostiteljVira("file:///etc/passwd"))
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

    // Drug izvor: glave toka samo A, C (127.0.0.3) dobi samo privzeti UA.
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

    domaceOmrezje(toka)
    tujSeznam()

    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("Vse v redu.")
}

/** Vodja 5. 11. 2026 (Linux v_domace_omrezje, VARNE_GLAVE): v domace omrezje samo tok z varnimi glavami. */
private fun domaceOmrezje(toka: Map<String, String>) {
    println("Domace omrezje")
    val varne = mapOf("User-Agent" to "Igralec/1", "Referer" to "https://stran.example/", "Origin" to "https://stran.example",
        "Accept" to "*/*", "Accept-Language" to "sl")
    preveriEnako("varne glave (seznam kot Linux)", true, PretokHttp.vDomaceOmrezje(varne))
    preveriEnako("brez glav", true, PretokHttp.vDomaceOmrezje(emptyMap()))
    for (k in listOf("Authorization", "Cookie", "X-Api-Key", "X-Safeer-Token", "Proxy-Authorization", "X-Emby-Token"))
        preveriEnako("ni varna: $k", false, PretokHttp.vDomaceOmrezje(varne + (k to "x")))
    val pravo = PretokHttp.Nastavitve()
    val lan = byteArrayOf(192.toByte(), 168.toByte(), 1, 20)
    preveriEnako("LAN brez varnih glav ne", false, PretokHttp.nasloviDovoljeni(listOf(lan), false, pravo))
    preveriEnako("LAN z varnimi glavami da", true, PretokHttp.nasloviDovoljeni(listOf(lan), true, pravo))
    preveriEnako("javni brez varnih glav da", true, PretokHttp.nasloviDovoljeni(listOf(byteArrayOf(8, 8, 8, 8)), false, pravo))
    preveriEnako("en LAN med javnimi -> nobeden", false,
        PretokHttp.nasloviDovoljeni(listOf(byteArrayOf(8, 8, 8, 8), byteArrayOf(10, 0, 0, 5)), false, pravo))
    preveriEnako("zanka tudi z varnimi ne", false, PretokHttp.nasloviDovoljeni(listOf(byteArrayOf(127, 0, 0, 1)), true, pravo))
    preveriEnako("zapis LAN, poverilnice", true, PretokHttp.ciljNedovoljen("192.168.1.20", false, pravo))
    preveriEnako("zapis LAN, varne glave", false, PretokHttp.ciljNedovoljen("192.168.1.20", true, pravo))
    preveriEnako("zapis ULA, poverilnice", true, PretokHttp.ciljNedovoljen("fd00::1", false, pravo))

    // D (127.0.0.4) je lazni NAS v domacem omrezju.
    val d = LazniStreznik("127.0.0.4") { V_REDU }
    preveriEnako("LAN z Authorization zavrnjen", "nedovoljen_cilj", odpri("http://127.0.0.4:${d.vrata}/film.mkv", toka))
    preveriEnako("LAN po imenu z Authorization zavrnjen", "nedovoljen_cilj", odpri("http://lan.preizkus:${d.vrata}/film.mkv", toka))
    preveriEnako("D ni dobil zahteve s poverilnicami", 0, d.zahteve.size)
    preveriEnako("LAN z varnimi glavami", "", odpri("http://127.0.0.4:${d.vrata}/film.mkv", mapOf("Referer" to "https://stran.example/")))
    preveriEnako("D je dobil Referer", true, d.zahteve.firstOrNull()?.contains("Referer: https://stran.example/") == true)
    // Javni vir drugega izvora ne sme poslati v domace omrezje (Linux: izpeljan vir drugega izvora zasebni=False).
    val a = LazniStreznik("127.0.0.2") { preusmeri("http://127.0.0.4:${d.vrata}/nas") }
    preveriEnako("javni 302 v LAN zavrnjen", "nedovoljen_cilj", odpri("http://127.0.0.2:${a.vrata}/film.mkv"))
    preveriEnako("D ni dobil preusmerjene zahteve", 1, d.zahteve.size)
    a.zapri(); d.zapri()
}

/** Vodja 5. 11. 2026 (Linux _Vir.izpeljan): kos, ki ga navede seznam z drugega izvora, ne dobi glav toka. */
private fun tujSeznam() {
    println("Seznam predvajanja z drugega izvora")
    preveriEnako("HLS je seznam", true, PretokHttp.jeSeznam("#EXTM3U\n#EXTINF:4,\nkos.ts".toByteArray()))
    preveriEnako("HLS z BOM", true, PretokHttp.jeSeznam(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "#EXTM3U".toByteArray()))
    preveriEnako("DASH je seznam", true, PretokHttp.jeSeznam("<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash\">".toByteArray()))
    preveriEnako("video ni seznam", false, PretokHttp.jeSeznam(byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70)))
    val izvor = "https://izvor.example/film/master.m3u8"
    val tuj = "https://cdn.example/v/tuj.m3u8"
    preveriEnako("absoluten kos na izvoru", true, PretokHttp.seznamNavajaIzvor("#EXTM3U\n#EXTINF:4,\nhttps://izvor.example/k1.ts\n", tuj, izvor))
    preveriEnako("relativni kosi na tujem", false, PretokHttp.seznamNavajaIzvor("#EXTM3U\n#EXTINF:4,\nk1.ts\n/k2.ts\n", tuj, izvor))
    preveriEnako("kljuc URI na izvoru", true, PretokHttp.seznamNavajaIzvor(
        "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://%69zvor.example/kljuc\"\n#EXTINF:4,\nk1.ts\n", tuj, izvor))
    preveriEnako("ime izvora v odstotnem zapisu", true, PretokHttp.seznamNavajaIzvor(
        "#EXTM3U\nhttps://%69zvor.example/k.ts\n", tuj, izvor))
    preveriEnako("DASH BaseURL z imenom izvora", true, PretokHttp.seznamNavajaIzvor(
        "<MPD><BaseURL>https://IZVOR.example/</BaseURL></MPD>", tuj, izvor))
    preveriEnako("vrstica z \\r", true, PretokHttp.seznamNavajaIzvor("#EXTM3U\rhttps://izvor.example/k.ts", tuj, izvor))

    // A (127.0.0.2) je izvor: master -> 302 na C (127.0.0.3), ki vrne seznam z absolutnim kosom na A.
    var vrataA = 0
    val c = LazniStreznik("127.0.0.3") { seznam("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nhttp://127.0.0.2:$vrataA/kos.ts\n") }
    val a = LazniStreznik("127.0.0.2") { i -> if (i == 1) preusmeri("http://127.0.0.3:${c.vrata}/tuj.m3u8") else V_REDU }
    vrataA = a.vrata
    val glave = mapOf("X-Api-Key" to "kljuc-vira", "Authorization" to "Bearer skrivno")
    val vir = PretokHttp.Vir("http://127.0.0.2:${a.vrata}/master.m3u8", glave)
    val klient = PretokHttp.klient(NASTAVITVE, 3, 3)
    val o = PretokHttp.odpri(klient, vir, vir.prvi, null)
    o.odgovor.close()
    preveriEnako("tuj seznam prebran", true, o.telo?.let { String(it).contains("kos.ts") } == true)
    preveriEnako("tuj seznam odvzame zaupanje", true, vir.zaupanjeIzgubljeno)
    preveriEnako("kos na izvoru po tujem seznamu", "", odpri(vir, "http://127.0.0.2:${a.vrata}/kos.ts"))
    preveriEnako("A je dobil 2 zahtevi", 2, a.zahteve.size)
    preveriEnako("master je dobil kljuc", true, a.zahteve.firstOrNull()?.contains("X-Api-Key: kljuc-vira") == true)
    preveriEnako("kos ni dobil kljuca", false, a.zahteve.getOrNull(1)?.contains("X-Api-Key") == true)
    preveriEnako("kos ni dobil Authorization", false, a.zahteve.getOrNull(1)?.contains("Authorization") == true)
    klient.zapri(); a.zapri(); c.zapri()

    // Seznam na izvoru, ki navaja svoj kos: zaupanje ostane, kos dobi glave toka.
    val a2 = LazniStreznik("127.0.0.2") { i -> if (i == 1) seznam("#EXTM3U\n#EXTINF:4,\nkos.ts\n") else V_REDU }
    val vir2 = PretokHttp.Vir("http://127.0.0.2:${a2.vrata}/master.m3u8", glave)
    preveriEnako("lasten seznam", "", odpri(vir2))
    preveriEnako("lasten seznam zaupanje obdrzi", false, vir2.zaupanjeIzgubljeno)
    preveriEnako("kos lastnega seznama", "", odpri(vir2, "http://127.0.0.2:${a2.vrata}/kos.ts"))
    preveriEnako("kos lastnega seznama dobi kljuc", true, a2.zahteve.getOrNull(1)?.contains("X-Api-Key: kljuc-vira") == true)
    a2.zapri()
}
