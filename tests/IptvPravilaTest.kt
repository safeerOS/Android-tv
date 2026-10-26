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
    // Seznam drzave brez tvg-language: jezik iz drzave kanala (TV SLO 1 v si.m3u -> sl).
    val slo = "#EXTINF:-1 tvg-id=\"TVSLO1.si@HD\" tvg-logo=\"https://i.imgur.com/E3YmlxF.png\" group-title=\"Undefined\",TV SLO 1 HD"
    preveriEnako("vnos: jezik iz drzave (si.m3u)", "sl", IptvPravila.vnos(slo, "https://dash2.antik.sk/live/test_slo1_tizen/playlist.m3u8", "SI", "")?.jezik)
    preveriEnako("jezik drzave SI", "sl", IptvPravila.jezikDrzave("SI"))
    preveriEnako("jezik drzave US", "en", IptvPravila.jezikDrzave("US"))
    preveriEnako("jezik drzave HR", "hr", IptvPravila.jezikDrzave("hr"))
    preveriEnako("jezik drzave neznana", "", IptvPravila.jezikDrzave(""))
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

    println()
    // ---- naloga 86 (M7): branje vrstic z mejo dolzine
    fun vr(vhod: String, meja: Int = 10, skupaj: Long = 1_000): List<String> =
        ArrayList<String>().also { l -> IptvPravila.vrstice(java.io.StringReader(vhod), meja, skupaj) { l += it } }
    preveriEnako("86 vrstice: \\n, \\r\\n in \\r", listOf("a", "b", "c", "d"), vr("a\nb\r\nc\rd"))
    preveriEnako("86 vrstice: prazna vrstica ostane", listOf("a", "", "b"), vr("a\n\nb\n"))
    preveriEnako("86 vrstice: predolga se preskoci, naslednja ostane", listOf("kratka", "zadnja"), vr("kratka\n" + "x".repeat(50) + "\nzadnja"))
    preveriEnako("86 vrstice: tocno na meji ostane", listOf("x".repeat(10)), vr("x".repeat(10)))
    preveriEnako("86 vrstice: predolga zadnja brez preloma odpade", listOf("a"), vr("a\n" + "y".repeat(11)))
    preveriEnako("86 vrstice: skupna meja - nedokoncana vrstica odpade", listOf("aaaa"), vr("aaaa\nbbbb\ncccc", skupaj = 8))
    // Ogromna vrstica brez preloma: pomnilnik ostane omejen (hrani se najvec meja znakov).
    var n = 0
    IptvPravila.vrstice(object : java.io.Reader() {
        var ostane = 50_000_000L
        override fun read(b: CharArray, o: Int, d: Int): Int { if (ostane <= 0) return -1; val k = minOf(d.toLong(), ostane).toInt(); java.util.Arrays.fill(b, o, o + k, 'z'); ostane -= k; return k }
        override fun close() {}
    }, IptvPravila.NAJVEC_VRSTICA, 100_000_000L) { n++ }
    preveriEnako("86 vrstice: 50 M znakov brez preloma - nobene vrstice, brez OOM", 0, n)

    println()
    println("== zascita racuna (lastnik, 26. 10. 2026: nevarnost zaklepa racuna je prioriteta) ==")
    val xtream = "http://ponudnik.primer.test:8080/get.php?username=uporabnik&password=geslo&type=m3u_plus"
    val s = IptvPravila::jeVirSPoverilnico
    // Isti primeri kot Linux (tests/test_kanali_iptv.py, ZascitaRacuna) in osnutek varovalo-racunov.json (iptv_ima_poverilnico).
    preveriEnako("poverilnica: Xtream get.php", true, s(xtream, true))
    preveriEnako("poverilnica: player_api.php", true, s("http://p.primer.test/player_api.php?a=1", true))
    preveriEnako("poverilnica: /live/<u>/<g>/", true, s("http://p.primer.test:8080/live/uporabnik/geslo/123.ts", false))
    preveriEnako("poverilnica: /movie/<u>/<g>/", true, s("http://p.primer.test/movie/u/p/5.mkv", false))
    preveriEnako("poverilnica: /series/<u>/<g>/", true, s("http://p.primer.test/series/u/p/7.mp4", false))
    preveriEnako("poverilnica: /timeshift/ z velikimi crkami", true, s("http://p.primer.test/TimeShift/u/p/60/2026/1.ts", false))
    preveriEnako("poverilnica: kodirana pot %2F", true, s("http://p.primer.test/live%2Fu%2Fp%2F1.ts", false))
    preveriEnako("poverilnica: uporabnik:geslo@", true, s("http://uporabnik:geslo@tvh.primer.test:9981/stream/channel/1", false))
    preveriEnako("poverilnica: samo uporabnik@", true, s("https://uporabnik@p.primer.test/seznam.m3u", true))
    preveriEnako("poverilnica: token= v seznamu", true, s("https://p.primer.test/seznam.m3u?token=abc", true))
    preveriEnako("poverilnica: key= v seznamu", true, s("https://p.primer.test/seznam.m3u?a=1&KEY=abc", true))
    preveriEnako("poverilnica: kodiran parameter us%65rname", true, s("http://p.primer.test/s.m3u?us%65rname=u", true))
    preveriEnako("poverilnica: password= v kanalu", true, s("https://p.primer.test/k.m3u8?password=x", false))
    preveriEnako("ni poverilnice: podpis CDN (token) v kanalu", false, s("https://cdn.primer.test/live/kanal/index.m3u8?token=abc", false))
    preveriEnako("ni poverilnice: iptv-org", false, s("https://iptv-org.github.io/iptv/countries/si.m3u", true))
    preveriEnako("ni poverilnice: /live/ z eno ravnjo", false, s("https://cdn.primer.test/live/kanal.m3u8", false))
    preveriEnako("nerazumljiv naslov steje kot poverilnica", true, s("rtmp://p.primer.test/live", false))
    preveriEnako("prazen naslov steje kot poverilnica", true, s("", true))
    val j = IptvPravila::javniBrezplacni
    preveriEnako("javni: iptv-org.github.io", true, j("https://iptv-org.github.io/iptv/countries/si.m3u"))
    preveriEnako("javni: raw.githubusercontent.com/iptv-org", true, j("https://raw.githubusercontent.com/iptv-org/iptv/master/streams/si.m3u"))
    preveriEnako("javni: Free-TV/IPTV", true, j("https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"))
    // Grok 39, I1 (fail-closed): seznam z racunom je lahko kjerkoli - samo pozitivno znani javni seznami se preverjajo.
    preveriEnako("ni javni: *.github.io", false, j("https://kdo.github.io/seznam.m3u"))
    preveriEnako("ni javni: gist", false, j("https://gist.githubusercontent.com/kdo/abc/raw/seznam.m3u"))
    preveriEnako("ni javni: drug uporabnik na raw", false, j("https://raw.githubusercontent.com/kdo/zasebno/main/s.m3u"))
    preveriEnako("ni javni: raw z ../ mimo iptv-org", false, j("https://raw.githubusercontent.com/iptv-org/../kdo/s.m3u"))
    preveriEnako("ni javni: raw s %2e%2e mimo iptv-org", false, j("https://raw.githubusercontent.com/iptv-org/%2e%2e/kdo/s.m3u"))
    preveriEnako("ni javni: codeberg", false, j("https://codeberg.org/kdo/s/raw/branch/main/s.m3u"))
    preveriEnako("ni javni: iptv-org z zetonom", false, j("https://iptv-org.github.io/iptv/index.m3u?token=abc"))
    preveriEnako("poverilnica: Stalker mac=", true, s("http://portal.primer.test/c/?mac=00:1A:79:00:00:01", false))
    preveriEnako("ni javni: Xtream", false, j(xtream))
    preveriEnako("ni javni: domaci streznik", false, j("http://tvh.primer.test:9981/playlist"))
    preveriEnako("ni javni: neznan gostitelj", false, j("https://ponudnik.primer.test/moj-seznam.m3u"))
    preveriEnako("ni javni: iptv-org z uporabnikom", false, j("https://u:g@iptv-org.github.io/iptv/countries/si.m3u"))
    preveriEnako("ni javni: lazni gostitelj iptv-org.github.io.primer.test", false, j("https://iptv-org.github.io.primer.test/s.m3u"))
    preveriEnako("dovoljena: javni seznam, javni tok", true,
        IptvPravila.preverbaDovoljena("https://iptv-org.github.io/iptv/countries/si.m3u", "https://cdn.primer.test/k/index.m3u8?token=x"))
    preveriEnako("ni dovoljena: javni seznam, tok Xtream", false,
        IptvPravila.preverbaDovoljena("https://iptv-org.github.io/iptv/countries/si.m3u", "http://p.primer.test/live/u/g/1.ts"))
    preveriEnako("ni dovoljena: seznam Xtream", false, IptvPravila.preverbaDovoljena(xtream, "https://cdn.primer.test/k.m3u8"))
    preveriEnako("dodatek z zetonom v poti", true,
        IptvPravila.dodatekSPoverilnico("https://dodatek.primer.test/realdebrid=ABCDEFGHIJKLMNOPQRSTUVWXYZ123456/manifest.json"))
    preveriEnako("dodatek: nadomestek skrivnega", true,
        IptvPravila.dodatekSPoverilnico("https://dodatek.primer.test/" + SkrivnostiPravila.OZNAKA + "0123456789abcdef/manifest.json"))
    preveriEnako("dodatek: javni brez poverilnice", false, IptvPravila.dodatekSPoverilnico("https://iptv.primer.test/manifest.json"))
    // Seznam Xtream s 5000 kanali (prava oblika get.php m3u_plus): noben kanal ne sme v preverbo.
    val bx = IptvPravila.Bralnik(xtream, "", "")
    var vXtream = 0
    var zaPreverbo = 0
    for (i in 0 until 5000) {
        bx.vrstica("#EXTINF:-1 tvg-id=\"K$i.si\" tvg-name=\"Kanal $i\" group-title=\"SI\",Kanal $i")
        bx.vrstica("http://ponudnik.primer.test:8080/live/uporabnik/geslo/$i.ts")?.let { vXtream++; if (IptvPravila.preverbaDovoljena(xtream, it.url)) zaPreverbo++ }
    }
    preveriEnako("Xtream 5000 kanalov: vsi razclenjeni (vidni)", 5000, vXtream)
    preveriEnako("Xtream 5000 kanalov: nobeden v preverbo", 0, zaPreverbo)
    preveriEnako("pregled: seznam Xtream ne (tudi rocno)", "seznam s poverilnico", IptvPravila.zakajNePregleda(true, xtream))
    preveriEnako("pregled: seznam ponudnika brez poverilnice v naslovu ne", "ni javni brezplacni seznam",
        IptvPravila.zakajNePregleda(true, "https://ponudnik.primer.test/seznam.m3u"))
    preveriEnako("pregled: javni seznam da", "", IptvPravila.zakajNePregleda(true, "https://iptv-org.github.io/iptv/countries/si.m3u"))
    preveriEnako("pregled: dodatek z zetonom ne", "dodatek s poverilnico",
        IptvPravila.zakajNePregleda(false, "https://torrentio.strem.fun/realdebrid=ABCDEFGHIJKLMNOPQRSTUVWXYZ123456/manifest.json"))
    preveriEnako("pregled: javni dodatek da", "", IptvPravila.zakajNePregleda(false, "https://iptv.primer.test/manifest.json"))

    if (napak == 0) println("IptvPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
