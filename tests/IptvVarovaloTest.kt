package si.safeer.tv.os

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lastnik, 26. 10. 2026: »Nevarnost zaklepa racuna je prioriteta.« Pot pregleda kanalov seznama IPTV z istimi
 * odlocitvami kot PregledKanalov ([IptvPravila.zakajNeVOzadju] za vir, [IptvPravila.preverbaDovoljena] za kanal,
 * [IptvPravila.VrataGostiteljev], sonda [SondaVeriga]) proti pravemu krajevnemu strezniku HTTP, ki steje vsako zahtevo.
 * Seznam Xtream s 5000 kanali in seznam, prejet z druge naprave, ne smeta odpreti niti ene povezave.
 */
private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

fun main() {
    println("Preizkus varovala racunov IPTV (krajevni streznik HTTP steje zahteve)")
    val zahtev = AtomicInteger(0)
    val streznik = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    streznik.executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    streznik.createContext("/") { x ->
        zahtev.incrementAndGet()
        try { val b = ByteArray(188) { 0x47 }; x.sendResponseHeaders(200, b.size.toLong()); x.responseBody.use { it.write(b) } } catch (_: Exception) { } finally { x.close() }
    }
    streznik.start()
    val tok = "http://127.0.0.1:${streznik.address.port}"
    val izIzjeme = { _: java.io.IOException, _: String -> OsPravila.IzidSonde.NEZNANO to "napaka" }

    /** Pregled seznama [seznam] po poti PregledKanalov; vrne stevilo sond (zahteve steje streznik). */
    fun pregled(seznam: String, vrstice: List<String>, dodanTu: Boolean, rocno: Boolean): Int {
        if (IptvPravila.zakajNeVOzadju(true, seznam, dodanTu, rocno).isNotEmpty()) return 0
        val vrata = IptvPravila.VrataGostiteljev(premorMs = 0L)
        val (d, j) = IptvPravila.izNaslovaSeznama(seznam)
        val b = IptvPravila.Bralnik(seznam, d, j)
        var sond = 0
        for (l in vrstice) {
            val v = b.vrstica(l) ?: continue
            if (!IptvPravila.preverbaDovoljena(seznam, v.url)) continue
            val g = IptvPravila.gostitelj(v.url)
            if (vrata.zasedi(g, System.currentTimeMillis(), System.nanoTime() / 1_000_000L) != IptvPravila.VrataGostiteljev.Izid.DA) continue
            try { SondaVeriga.preveri(v.url, { emptyMap() }, izIzjeme); sond++ } finally { vrata.sprosti(g, System.nanoTime() / 1_000_000L) }
        }
        return sond
    }

    fun kanali(n: Int, naslov: (Int) -> String) = (0 until n).flatMap { i ->
        listOf("#EXTINF:-1 tvg-id=\"K$i.si\" tvg-name=\"Kanal $i\" group-title=\"SI\",Kanal $i", naslov(i))
    }

    // 1) Placljiv seznam Xtream (get.php m3u_plus) s 5000 kanali: v ozadju in rocno nobene povezave.
    val xtream = "http://ponudnik.primer.test:8080/get.php?username=uporabnik&password=geslo&type=m3u_plus"
    val xt = kanali(5000) { "$tok/live/uporabnik/geslo/$it.ts" }
    zahtev.set(0)
    preveriEnako("Xtream 5000 kanalov, dodan tu, v ozadju: sond", 0, pregled(xtream, xt, dodanTu = true, rocno = false))
    preveriEnako("Xtream 5000 kanalov, rocno: sond", 0, pregled(xtream, xt, dodanTu = true, rocno = true))
    preveriEnako("Xtream 5000 kanalov: zahtev na strezniku", 0, zahtev.get())
    // 2) Tokovi Xtream v seznamu na javnem gostitelju: kanal z racunom v naslovu toka se ne odpre.
    zahtev.set(0)
    preveriEnako("javni seznam s tokovi Xtream: sond", 0, pregled("https://iptv-org.github.io/iptv/countries/si.m3u", xt.take(400), dodanTu = true, rocno = false))
    preveriEnako("javni seznam s tokovi Xtream: zahtev", 0, zahtev.get())
    // 3) Javni seznam, prejet z druge naprave: v ozadju ga tu ne preverjamo (preverja naprava, kjer je bil dodan).
    val javni = "https://iptv-org.github.io/iptv/countries/si.m3u"
    val prosti = kanali(5) { "$tok/prosti/$it.m3u8" }
    zahtev.set(0)
    preveriEnako("javni seznam z druge naprave, v ozadju: sond", 0, pregled(javni, prosti, dodanTu = false, rocno = false))
    preveriEnako("javni seznam z druge naprave: zahtev", 0, zahtev.get())
    // 4) Nadzor (preizkus res steje): javni seznam, dodan tu - en gostitelj, 5 kanalov, 5 sond.
    zahtev.set(0)
    preveriEnako("nadzor: javni seznam, dodan tu: sond", 5, pregled(javni, prosti, dodanTu = true, rocno = false))
    preveriEnako("nadzor: zahtev na strezniku", 5, zahtev.get())
    streznik.stop(0)
    if (napak == 0) println("IptvVarovaloTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
