package si.safeer.tv.os

/**
 * Preizkus [IptvPravila] (naloga 80): vnosi seznamov IPTV (iptv-org) kot kanali TV v zivo - atributi #EXTINF, zvrst iz
 * group-title, jezik in drzava (vnos, tvg-id, naslov seznama), tokovno branje, zapis na disk, izbira in domaci kanali.
 */

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    preveri("$opis (pricakovano=$pricakovano, dobljeno=$dobljeno)", pricakovano == dobljeno)

fun main() {
    println("== IptvPravila ==")
    val e1 = """#EXTINF:-1 tvg-id="PopTV.si@SD" tvg-logo="https://i.imgur.com/pop.png" group-title="General",POP TV (1080p)"""
    preveriEnako("atributi: tvg-id", "PopTV.si@SD", IptvPravila.atributi(e1)["tvg-id"])
    preveriEnako("atributi: group-title", "General", IptvPravila.atributi(e1)["group-title"])
    preveriEnako("ime za vejico", "POP TV (1080p)", IptvPravila.imeIzExtinf(e1))
    preveriEnako("ime z vejico v atributu", "Kanal A", IptvPravila.imeIzExtinf("""#EXTINF:-1 group-title="News,Sport",Kanal A"""))
    preveriEnako("seznam drzave", "SI" to "", IptvPravila.izNaslovaSeznama("https://iptv-org.github.io/iptv/countries/si.m3u"))
    preveriEnako("seznam jezika (639-3)", "" to "en", IptvPravila.izNaslovaSeznama("https://iptv-org.github.io/iptv/languages/eng.m3u"))
    preveriEnako("seznam brez oznake", "" to "", IptvPravila.izNaslovaSeznama("https://example.test/moj.m3u"))
    preveriEnako("zvrst: News;Sports", "novice", IptvPravila.zvrst("News;Sports"))
    preveriEnako("zvrst: Undefined -> ostalo", "ostalo", IptvPravila.zvrst("Undefined"))
    preveriEnako("zvrst: neznana", "", IptvPravila.zvrst("Xyz"))
    preveriEnako("jezik: ime", "sl", IptvPravila.jezik("Slovenian"))
    preveriEnako("jezik: 639-3", "de", IptvPravila.jezik("deu"))
    preveriEnako("jezik: 639-1", "fr", IptvPravila.jezik("fr"))
    val v1 = IptvPravila.vnos(e1, "https://cdn.test/pop/index.m3u8", "", "en")
    preveriEnako("vnos: drzava iz tvg-id", "SI", v1?.drzava)
    preveriEnako("vnos: jezik seznama", "en", v1?.jezik)
    preveriEnako("vnos: zvrst", "splosni", v1?.zvrst)
    preveriEnako("vnos: logo", "https://i.imgur.com/pop.png", v1?.logo)
    val e2 = """#EXTINF:-1 tvg-country="GB" tvg-language="English;Welsh" group-title="Music",Hits"""
    preveriEnako("vnos: tvg-country in tvg-language", "GB" to "en", IptvPravila.vnos(e2, "http://x.test/a.m3u8", "SI", "sl")?.let { it.drzava to it.jezik })
    preveriEnako("vnos: radio brez oznak ni kanal", null, IptvPravila.vnos("#EXTINF:-1,Radio Ena", "http://radio.test/stream", "", ""))
    preveriEnako("vnos: HLS brez oznak je kanal", true, IptvPravila.vnos("#EXTINF:-1,Kanal", "http://tv.test/live.m3u8", "", "") != null)
    preveriEnako("vnos: ni http", null, IptvPravila.vnos(e1, "rtmp://x.test/a", "", ""))
    preveriEnako("vnos: logo samo http(s)", "", IptvPravila.vnos("""#EXTINF:-1 tvg-logo="javascript:x" group-title="News",N""", "http://t.test/a.m3u8", "", "")?.logo)

    // Tokovno branje: vrstica za vrstico, relativni naslovi, vnos brez #EXTINF izpade.
    val b = IptvPravila.Bralnik("https://iptv-org.github.io/iptv/countries/si.m3u", "SI", "")
    val vrstice = listOf("#EXTM3U", e1, "pop.m3u8", "", "#EXTINF:-1 tvg-id=\"Kanal.si\" group-title=\"News\",Kanal A", "#EXTVLCOPT:http-user-agent=x",
        "https://cdn.test/a.m3u8", "https://cdn.test/brez-extinf.m3u8")
    val vnosi = vrstice.mapNotNull { b.vrstica(it) }
    preveriEnako("bralnik: dva kanala", 2, vnosi.size)
    preveriEnako("bralnik: relativni naslov", "https://iptv-org.github.io/iptv/countries/pop.m3u8", vnosi.firstOrNull()?.url)
    preveriEnako("bralnik: drzava seznama", "SI", vnosi.getOrNull(1)?.drzava)
    preveriEnako("bralnik: stevec", 2, b.kanalov)

    val v = IptvPravila.Vnos("Ime\tz\ntab", "https://t.test/a.m3u8", "", "novice", "sl", "SI", "x")
    val nazaj = IptvPravila.izVrstice(IptvPravila.vVrstico(v))
    preveriEnako("disk: tja in nazaj (tabulator in prelom izgineta)", "Ime z tab", nazaj?.ime)
    preveriEnako("disk: polja", listOf("https://t.test/a.m3u8", "novice", "sl", "SI"), nazaj?.let { listOf(it.url, it.zvrst, it.jezik, it.drzava) })
    preveriEnako("disk: pokvarjena vrstica", null, IptvPravila.izVrstice("samo\tdva"))
    preveriEnako("izbira: vse", true, IptvPravila.ustreza(v, "", ""))
    preveriEnako("izbira: zvrst", true, IptvPravila.ustreza(v, "novice", ""))
    preveriEnako("izbira: drug jezik", false, IptvPravila.ustreza(v, "", "en"))
    preveriEnako("domac: drzava naprave", true, IptvPravila.jeDomac(v, "SI", "en"))
    preveriEnako("domac: jezik vmesnika", true, IptvPravila.jeDomac(v.copy(drzava = ""), "AT", "sl"))
    preveriEnako("domac: ne", false, IptvPravila.jeDomac(v.copy(drzava = "GB", jezik = "en"), "SI", "sl"))
    val dan = 24 * 3_600_000L
    preveriEnako("zastarel: nov ni", false, IptvPravila.zastarel(10 * dan, 10 * dan + 3_600_000L))
    preveriEnako("zastarel: 24 h je", true, IptvPravila.zastarel(10 * dan, 11 * dan))
    preveriEnako("zastarel: nikoli prenesen", true, IptvPravila.zastarel(0, 10 * dan))

    println()
    println("== KanaliPravila.rangVira (53c) ==")
    preveriEnako("General je kategorija", 0, KanaliPravila.rangVira("General"))
    preveriEnako("News je kategorija", 0, KanaliPravila.rangVira("News"))
    preveriEnako("KR Tv je drzava", 1, KanaliPravila.rangVira("KR Tv"))
    preveriEnako("Slovenia je drzava", 1, KanaliPravila.rangVira("Slovenia"))
    preveriEnako("All je ostalo", 2, KanaliPravila.rangVira("All"))
    preveriEnako("English je ostalo", 2, KanaliPravila.rangVira("English"))
    val k = listOf(KanaliPravila.Katalog("f", "FayaTV", "FayaTV KR Tv"), KanaliPravila.Katalog("f", "FayaTV", "FayaTV News"),
        KanaliPravila.Katalog("a", "All IPTV", "All IPTV All"), KanaliPravila.Katalog("f", "FayaTV", "FayaTV General"))
    preveriEnako("razvrsti: kategorije, drzave, ostalo (stabilno)", listOf(1, 3, 0, 2),
        KanaliPravila.razvrstiVire(k.indices.map { KanaliPravila.Vir(it) }, k).map { it.katalog })

    if (napak == 0) println("IptvPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
