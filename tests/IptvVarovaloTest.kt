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

    // ------------------------------------------------------------------ Grok 60, Ů1
    println("== Grok 60, Ů1: odprtje seznama in TV v zivo ne smeta biti nova prijava v racun ob vsakem odprtju ==")
    // V aplikaciji je vir z racunom nadomestek; prijava = zahteva s pravim naslovom. Odlocitev je ista cista funkcija, ki jo klicejo
    // IptvSeznami.pripravi (odlociPripravo) in MedijskiViri.osveziSeznam (smeNaOmrezjeSeznam); stanje (zadnji prenos, napaka, zadnji
    // poskus) se zapise kot v IptvSeznami.prenesi/poIzidu/zapisiPoskus.
    val racunAp = "http://ponudnik.primer.test:8080/" + SkrivnostiPravila.OZNAKA + "0123456789abcdef"
    val t0 = 1_800_000_000_000L
    val minuta = 60_000L
    class Seznam(var prenesenOb: Long?, var napaka: IptvPravila.NapakaPrenosa?, var zadnjiPoskus: Long)
    fun prenos(s: Seznam, zdaj: Long, koda: Int) {
        s.zadnjiPoskus = zdaj
        if (koda in 200..299) { s.prenesenOb = zdaj; s.napaka = null }
        else s.napaka = IptvPravila.poNapaki(s.napaka, zdaj, IptvPravila.zavrnjen(koda, 0, racunAp))
    }
    /** Mreza TV v zivo odprta vsako minuto (IptvSeznami.pripravi); vrne stevilo prijav v racun. */
    fun tvVZivo(dodanTu: Boolean, minut: Int, koda: Int): Int {
        val s = Seznam(null, null, 0L)
        var prijav = 0
        for (m in 0..minut) {
            val zdaj = t0 + m * minuta
            if (IptvPravila.odlociPripravo(racunAp, dodanTu, false, s.prenesenOb, s.napaka, s.zadnjiPoskus, zdaj) != IptvPravila.Priprava.BREZ) { prenos(s, zdaj, koda); prijav++ }
        }
        return prijav
    }
    /** Seznam odprt vsako minuto (MedijskiViri.osveziSeznam); prvo uspesno branje shrani skladbe. */
    fun odprtjeSeznama(minut: Int, koda: Int): Int {
        val s = Seznam(null, null, 0L)
        var shranjeno = false
        var prijav = 0
        for (m in 0..minut) {
            val zdaj = t0 + m * minuta
            if (IptvPravila.smeNaOmrezjeSeznam(if (shranjeno) s.prenesenOb else null, s.napaka, s.zadnjiPoskus, zdaj, izrecno = false)) {
                prenos(s, zdaj, koda); prijav++
                if (koda in 200..299) shranjeno = true
            }
        }
        return prijav
    }
    /** Uporabnik klika »Poskusi znova« na vsakih [razmakS] sekund (izrecna osvezitev). */
    fun kliki(dodanTu: Boolean, stevilo: Int, razmakS: Long, koda: Int): Int {
        val s = Seznam(null, null, 0L)
        var prijav = 0
        for (i in 0 until stevilo) {
            val zdaj = t0 + i * razmakS * 1000L
            if (IptvPravila.odlociPripravo(racunAp, dodanTu, true, s.prenesenOb, s.napaka, s.zadnjiPoskus, zdaj) != IptvPravila.Priprava.BREZ) { prenos(s, zdaj, koda); prijav++ }
        }
        return prijav
    }
    val dan = 24 * 60
    preveriEnako("TV v zivo odprta vsako minuto 24 ur, racun deluje: prva prijava in ena osvezitev po 24 urah", 2, tvVZivo(true, dan, 200))
    preveriEnako("TV v zivo odprta vsako minuto 24 ur, ponudnik zavrne: samo premor I5 (10 min, 1 h, 6 h)", 4, tvVZivo(true, dan, 403))
    preveriEnako("TV v zivo na napravi brez dokaza (prejeta kopija): nobene prijave", 0, tvVZivo(false, dan, 200))
    // Odlocitev lastnika 26. 10. 12:10: prejet iz ozjega kroga = uporaben takoj (prva povezava ob uporabi), a isti predpomnilnik 24 h in premor I5.
    preveriEnako("TV v zivo, vir iz ozjega kroga, 24 ur: prva prijava ob uporabi in ena osvezitev po 24 urah", 2, tvVZivo(IptvPravila.smeUporabiti(false, true), dan, 200))
    preveriEnako("TV v zivo, vir iz ozjega kroga, ponudnik zavrne: samo premor I5 (nikoli zaklep)", 4, tvVZivo(IptvPravila.smeUporabiti(false, true), dan, 403))
    preveriEnako("TV v zivo, tuja kopija brez dovoljenja: nobene prijave", 0, tvVZivo(IptvPravila.smeUporabiti(false, false), dan, 200))
    preveriEnako("izrecni kliki na vir iz ozjega kroga: najvec 1 na minuto", 10, kliki(IptvPravila.smeUporabiti(false, true), 60, 10, 200))
    // Grok 68, Ą2: vir iz ozjega kroga (uporaba dovoljena, brez dokaza »dodan tu«): prva povezava samo ob uporabnikovem odprtju seznama.
    // Stanje kot v IptvSeznami: datoteka (prenesenOb), uspelo odprtje seznama (osvezenOb), napaka I5, zadnji poskus.
    fun krogBrezUporabnika(minut: Int, koda: Int, odpreMinuta: Int?): Int {
        val s = Seznam(null, null, 0L)
        var osvezenOb: Long? = null
        var prijav = 0
        for (m in 0..minut) {
            val zdaj = t0 + m * minuta
            if (odpreMinuta == m && IptvPravila.smeNaOmrezjeSeznam(osvezenOb, s.napaka, s.zadnjiPoskus, zdaj, izrecno = false)) {
                prenos(s, zdaj, koda); prijav++      // MedijskiViri.osveziSeznam: uporabnikovo odprtje
                s.prenesenOb = null                   // odprtje piše svojo shrambo skladb, ne datoteke mreze TV v zivo
                if (koda in 200..299) osvezenOb = zdaj
            }
            val uporabljen = s.prenesenOb != null || osvezenOb != null
            // IptvSeznami.pripravi iz mreze TV v zivo (samodejni klic, rocno = false), vir iz ozjega kroga brez dokaza.
            if (IptvPravila.odlociPripravo(racunAp, true, false, s.prenesenOb, s.napaka, s.zadnjiPoskus, zdaj,
                    dodanTuDokaz = false, uporabljen = uporabljen) != IptvPravila.Priprava.BREZ) { prenos(s, zdaj, koda); prijav++ }
        }
        return prijav
    }
    preveriEnako("Ą2: vir iz kroga, TV v zivo odprta vsako minuto 24 ur, uporabnik seznama ne odpre: nobene povezave", 0, krogBrezUporabnika(dan, 200, null))
    preveriEnako("Ą2: ... tudi ce ponudnik zavrne: nobene povezave", 0, krogBrezUporabnika(dan, 403, null))
    preveriEnako("Ą2: uporabnik odpre seznam: odprtje + ena priprava mreze TV v zivo, nato predpomnilnik 24 h", 2, krogBrezUporabnika(dan, 200, 10))
    preveriEnako("Ą2: odprtje zavrne ponudnik: samodejna priprava ne gre mimo (en poskus, nikoli zaklep)", 1, krogBrezUporabnika(dan, 403, 10))
    preveriEnako("Ą2: vir z dokazom »dodan tu«: prva povezava kot doslej (brez odprtja)", 2,
        run { val s = Seznam(null, null, 0L); var p = 0
            for (m in 0..dan) { val z = t0 + m * minuta
                if (IptvPravila.odlociPripravo(racunAp, true, false, s.prenesenOb, s.napaka, s.zadnjiPoskus, z, dodanTuDokaz = true, uporabljen = false) != IptvPravila.Priprava.BREZ) { prenos(s, z, 200); p++ } }
            p })
    preveriEnako("odprtje seznama vsako minuto 24 ur, racun deluje: prva prijava in ena osvezitev po 24 urah", 2, odprtjeSeznama(dan, 200))
    preveriEnako("odprtje seznama vsako minuto 24 ur, ponudnik zavrne: samo premor I5", 4, odprtjeSeznama(dan, 403))
    preveriEnako("izrecni kliki vsakih 10 s (10 min): najvec 1 prijava na minuto", 10, kliki(false, 60, 10, 200))
    preveriEnako("izrecni kliki vsakih 10 s po zavrnitvi: najvec 1 prijava na minuto", 10, kliki(false, 60, 10, 403))
    preveriEnako("izrecni kliki na vir z dokazom: enako, najvec 1 na minuto", 10, kliki(true, 60, 10, 200))
    preveriEnako("meja 1 na minuto: 59,9 s je prezgodaj", false, IptvPravila.smeRocnoOsveziti(t0, t0 + 59_999L))
    preveriEnako("meja 1 na minuto: po 60 s sme", true, IptvPravila.smeRocnoOsveziti(t0, t0 + 60_000L))
    preveriEnako("meja 1 na minuto: ura, prestavljena nazaj, ne zaklene", true, IptvPravila.smeRocnoOsveziti(t0, t0 - 5 * minuta))
    // Stara meja (smePrenesti z rocno) je veljala samo po napaki: po uspehu (napaka = null) je izrecna osvezitev smela vsakic.
    preveriEnako("nadzor: stara meja po uspehu ni omejevala", true, IptvPravila.smePrenesti(null, t0, rocno = true))

    // ------------------------------------------------------------------ Grok 60, Ů4
    println("== Grok 60, Ů4: pogled seznama bere z mejo velikosti in roka ==")
    // Zivi tok (MPEG-TS z zetonom v poti) ne konca: bralnik, ki za vsak klic dobi se 8 KiB, steje, koliko je bilo prebranega.
    var u4Prebrano = 0L
    val u4Neskoncni = object : java.io.Reader() {
        override fun read(cbuf: CharArray, off: Int, len: Int): Int { java.util.Arrays.fill(cbuf, off, off + len, 'G'); u4Prebrano += len; return len }
        override fun close() {}
    }
    val u4IzToka = IptvPravila.omejenoBesedilo(u4Neskoncni, 100_000, 30_000L)
    preveriEnako("neskoncen tok: prebrano natanko do meje", 100_000, u4IzToka.length)
    preveriEnako("neskoncen tok: iz povezave ni prebranega vec kot meja", 100_000L, u4Prebrano)
    preveriEnako("meja seznama je 2 milijona znakov", 2_000_000, IptvPravila.NAJVEC_ZNAKOV_SEZNAMA)
    preveriEnako("navaden seznam se prebere cel", "#EXTM3U\n#EXTINF:-1,A\nhttp://a/1\n",
        IptvPravila.omejenoBesedilo(java.io.StringReader("#EXTM3U\n#EXTINF:-1,A\nhttp://a/1\n"), IptvPravila.NAJVEC_ZNAKOV_SEZNAMA, IptvPravila.NAJVEC_BRANJE_MS))
    // Pocasen tok (kapljica): po roku se ustavi, ne caka na mejo. Ura je lazna in napreduje 1 s na branje.
    var u4Ura = 0L
    var u4Branj = 0
    val u4Kapljica = object : java.io.Reader() {
        override fun read(cbuf: CharArray, off: Int, len: Int): Int { u4Branj++; u4Ura += 1_000L; cbuf[off] = 'x'; return 1 }
        override fun close() {}
    }
    val u4IzKapljice = IptvPravila.omejenoBesedilo(u4Kapljica, 2_000_000, 30_000L) { u4Ura }
    preveriEnako("pocasen tok: ustavi se po roku 30 s", 30, u4Branj)
    preveriEnako("pocasen tok: prebrano samo do roka", 30, u4IzKapljice.length)

    // ------------------------------------------------------------------ konec
    streznik.stop(0)
    if (napak == 0) println("IptvVarovaloTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
