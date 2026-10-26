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
            // Krajevni streznik je na 127.0.0.1 - sonda ga v preizkusu sme (privzeto zasebnih ciljev ne odpre).
            try { SondaVeriga.preveri(v.url, { emptyMap() }, izIzjeme, SondaVeriga.Nastavitve(zaseben = { false })); sond++ }
            finally { vrata.sprosti(g, System.nanoTime() / 1_000_000L) }
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
    // 5) Grok 52, Ñ1 (fail-closed): kanal dodatka se preveri samo, ce je dodatek pozitivno znan javen (PregledKanalov.smeSonda);
    // dodatek z racunom v nastavitvah (base64 v poti) ali na strezniku ne odpre nobene povezave.
    fun sondaDodatka(dodatek: String, tokUrl: String): Int {
        if (!IptvPravila.javniDodatek(dodatek)) return 0
        if (IptvPravila.jeVirSPoverilnico(tokUrl, seznam = false)) return 0
        val g = IptvPravila.gostitelj(tokUrl)
        val vrata = IptvPravila.VrataGostiteljev(premorMs = 0L)
        if (vrata.zasedi(g, System.currentTimeMillis(), System.nanoTime() / 1_000_000L) != IptvPravila.VrataGostiteljev.Izid.DA) return 0
        try { SondaVeriga.preveri(tokUrl, { emptyMap() }, izIzjeme, SondaVeriga.Nastavitve(zaseben = { false })); return 1 }
        finally { vrata.sprosti(g, System.nanoTime() / 1_000_000L) }
    }
    val b64 = "eyJ1IjoidXBvcmFibmlrNyIsInAiOiJnZXNsbzEyMyJ9"        // {"u":"uporabnik7","p":"geslo123"}
    zahtev.set(0)
    var sondDodatka = 0
    for (i in 0 until 50) sondDodatka += sondaDodatka("https://iptv-posrednik.primer.test/$b64", "$tok/p/$b64/$i/index.m3u8")
    preveriEnako("dodatek z racunom v nastavitvah (base64 v poti), 50 kanalov: sond", 0, sondDodatka)
    preveriEnako("dodatek z racunom v nastavitvah: zahtev na strezniku", 0, zahtev.get())
    sondDodatka = 0
    for (i in 0 until 50) sondDodatka += sondaDodatka("https://iptv-posrednik.primer.test", "$tok/stream/$i.m3u8")
    preveriEnako("dodatek neznane oblike brez vidne poverilnice, 50 kanalov: sond", 0, sondDodatka)
    preveriEnako("dodatek neznane oblike: zahtev na strezniku", 0, zahtev.get())
    preveriEnako("nadzor: znan javni dodatek, javni tok: sond", 1, sondaDodatka("https://v3-cinemeta.strem.io", "$tok/stream/1.m3u8"))
    preveriEnako("nadzor: zahtev na strezniku", 1, zahtev.get())
    // 6) Grok 52, Ñ2: vir z racunom, prejet z druge naprave (lists.get, vrsta »url«), se ne bere: 0 povezav; javni vir se bere kot doslej.
    // [razvrsti] je tu pravo branje naslova (kot MedijskiViri.razvrsti: eno GET), ki ga streznik steje.
    fun branje(naslov: String): IptvPravila.ZapisVira? {
        val p = java.net.URL(naslov).openConnection() as java.net.HttpURLConnection
        try { p.responseCode } finally { p.disconnect() }
        return IptvPravila.ZapisVira("seznam", "prebran", naslov)
    }
    val prejeti = listOf("$tok/get.php?username=uporabnik&password=geslo&type=m3u_plus", "$tok/playlist/uporabnik/geslo/m3u_plus",
        "$tok/live/uporabnik/geslo/101.m3u8", "$tok/uporabnik/geslo/101.ts", "$tok/moj-seznam.m3u?token=Zx81kqP0aa", "$tok/neznana-oblika/seznam.m3u")
    zahtev.set(0)
    val vrste = prejeti.map { IptvPravila.prejetNaslov(it) { branje(it) }?.tip }
    preveriEnako("prejem 6 virov z racunom (url) z druge naprave: zahtev na strezniku", 0, zahtev.get())
    preveriEnako("prejem: vrste po obliki naslova", listOf("seznam", "seznam", "tok", "tok", "seznam", "seznam"), vrste)
    preveriEnako("prejem: ime = gostitelj, naslov nespremenjen", IptvPravila.ZapisVira("seznam", "127.0.0.1", prejeti[0]), IptvPravila.prejetNaslov(prejeti[0]) { branje(prejeti[0]) })
    var branj = 0
    val izJavnega = IptvPravila.prejetNaslov(javni) { branj++; IptvPravila.ZapisVira("seznam", "si", javni) }
    preveriEnako("nadzor: javni seznam se bere (vrsto doloci branje)", 1, branj)
    preveriEnako("nadzor: javni seznam ohrani vrsto iz branja", "si", izJavnega?.ime)
    preveriEnako("prejem: osvezitev seznama po prejemu samo za javni", listOf(false, false, false, true),
        listOf(IptvPravila.osveziPoPrejemu("seznam", prejeti[0]), IptvPravila.osveziPoPrejemu("url", prejeti[1]),
            IptvPravila.osveziPoPrejemu("seznam", "http://tvh.primer.test:9981/playlist"), IptvPravila.osveziPoPrejemu("seznam", javni)))
    // Odprtje TV v zivo / 24-urna osvezitev: seznam z racunom samo na napravi, kjer je bil dodan; drugje ob klicu uporabnika.
    preveriEnako("samodejni prenos: racun, ni dodan tu, brez klika - ne", false, IptvPravila.smeSamodejnoPrenesti(prejeti[0], dodanTu = false, izrecno = false))
    preveriEnako("samodejni prenos: racun, dodan tu - da", true, IptvPravila.smeSamodejnoPrenesti(prejeti[0], dodanTu = true, izrecno = false))
    preveriEnako("samodejni prenos: racun, uporabnikov klik - da (po premoru I5)", true, IptvPravila.smeSamodejnoPrenesti(prejeti[0], dodanTu = false, izrecno = true))
    preveriEnako("samodejni prenos: javni seznam z druge naprave - da", true, IptvPravila.smeSamodejnoPrenesti(javni, dodanTu = false, izrecno = false))
    preveriEnako("samodejni prenos: nadomestek (sifriran vir), ni dodan tu - ne", false,
        IptvPravila.smeSamodejnoPrenesti("http://ponudnik.primer.test:8080/" + SkrivnostiPravila.OZNAKA + "0123456789abcdef", dodanTu = false, izrecno = false))
    // Premor I5 velja za uporabnikov klik: po zavrnitvi 10 min ni novega prenosa.
    val poZavrnitvi = IptvPravila.poNapaki(null, 1_000_000L, zavrnjen = true)
    preveriEnako("uporabnikov klik po zavrnitvi racuna: 10 min premora", false, IptvPravila.smePrenesti(poZavrnitvi, 1_000_000L + 5 * 60_000L))
    preveriEnako("uporabnikov klik po izteku premora: sme", true, IptvPravila.smePrenesti(poZavrnitvi, 1_000_000L + 11 * 60_000L))
    streznik.stop(0)
    if (napak == 0) println("IptvVarovaloTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
