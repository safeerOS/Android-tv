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
    println("== Not 24/7, .uk = GB (lastnik 25. 10. 2026; kot Linux) ==")
    preveriEnako("Not 24/7: kanal izlocen", null, IptvPravila.vnos("#EXTINF:-1 tvg-id=\"U.si@SD\" group-title=\"General\",Televizija U (1080p) [Not 24/7]", "https://t.primer.test/u.m3u8", "SI", ""))
    preveriEnako("Not 24/7: male crke in presledki", null, IptvPravila.vnos("#EXTINF:-1 tvg-id=\"U.si\",Kanal [ not 24 / 7 ]", "https://t.primer.test/u.m3u8", "SI", ""))
    preveriEnako("Not 24/7: samo v tvg-name", null, IptvPravila.vnos("#EXTINF:-1 tvg-name=\"K [Not 24/7]\" tvg-id=\"K.si\",K", "https://t.primer.test/k.m3u8", "SI", ""))
    preveriEnako("Not 24/7: Geo-blocked ostane", "K [Geo-blocked]", IptvPravila.vnos("#EXTINF:-1 tvg-id=\"K.si\",K [Geo-blocked]", "https://t.primer.test/k.m3u8", "SI", "")?.ime)
    preveriEnako("Not 24/7: dodatek", true, KanaliPravila.jeNe247("Kanal X [Not 24/7]"))
    preveriEnako("Not 24/7: ime brez oznake", false, KanaliPravila.jeNe247("24/7 Movies"))
    preveriEnako(".uk -> GB (tvg-id)", "GB", IptvPravila.vnos("#EXTINF:-1 tvg-id=\"BBCNews.uk@SD\" group-title=\"News\",BBC News", "https://t.primer.test/bbc.m3u8", "", "en")?.drzava)
    preveriEnako(".uk -> GB (tvg-country)", "GB", IptvPravila.vnos("#EXTINF:-1 tvg-country=\"UK\",Kanal", "https://t.primer.test/k.m3u8", "", "")?.drzava)
    preveriEnako(".uk -> GB (countries/uk.m3u)", "GB" to "", IptvPravila.izNaslovaSeznama("https://iptv-org.github.io/iptv/countries/uk.m3u"))
    preveriEnako("vejica v http-user-agent: ime za vejico zunaj narekovajev", "H2 HD",
        IptvPravila.imeIzExtinf("#EXTINF:-1 http-user-agent=\"Mozilla/5.0 (KHTML, like Gecko) Chrome/124\" group-title=\"Documentary\",H2 HD"))

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
    // Grok 52, Ñ1 (fail-closed): dodatek, ki ni na pozitivnem seznamu, se ne pregleda, tudi ce naslov ne kaze racuna
    // (racun na strezniku dodatka, nastavitve v base64 brez kljucnega polja, domaci strezniki).
    preveriEnako("pregled: dodatek neznane oblike ne", "dodatek ni znan javni", IptvPravila.zakajNePregleda(false, "https://iptv.primer.test/manifest.json"))
    preveriEnako("pregled: dodatek z nastavitvami v poti ne", "dodatek ni znan javni",
        IptvPravila.zakajNePregleda(false, "https://iptv.primer.test/eyJ1IjoiYSIsInAiOiJiIn0/manifest.json"))
    preveriEnako("pregled: dodatek na domacem streznik ne", "dodatek ni znan javni", IptvPravila.zakajNePregleda(false, "http://192.168.1.5:7000/manifest.json"))
    preveriEnako("pregled: znan javni dodatek (Cinemeta) da", "", IptvPravila.zakajNePregleda(false, "https://v3-cinemeta.strem.io/manifest.json"))
    preveriEnako("javni dodatek: osnova brez manifesta", true, IptvPravila.javniDodatek("https://v3-cinemeta.strem.io"))
    preveriEnako("javni dodatek: lazni gostitelj", false, IptvPravila.javniDodatek("https://v3-cinemeta.strem.io.primer.test/manifest.json"))
    preveriEnako("javni dodatek: z uporabnikom", false, IptvPravila.javniDodatek("https://u:g@v3-cinemeta.strem.io/manifest.json"))
    preveriEnako("javni dodatek: pot ..", false, IptvPravila.javniDodatek("https://v3-cinemeta.strem.io/../x/manifest.json"))
    preveriEnako("javni dodatek: stremio:// ne razumemo - ni javen", false, IptvPravila.javniDodatek("stremio://v3-cinemeta.strem.io/manifest.json"))
    preveriEnako("javni dodatek: nadomestek", false, IptvPravila.javniDodatek("https://v3-cinemeta.strem.io/" + SkrivnostiPravila.OZNAKA + "0123456789abcdef"))

    println()
    println("== J4: regularni izrazi #EXTINF in imena v linearnem casu ==")
    // Linux pred 84f82f5 (izmerjeno 26. 10. 2026): vrstica 20 KB crk 5,7 s, 1 MB ni koncal. Nit z rokom: kvadraten izraz
    // preizkusa ne ustavi za ure, ampak pade.
    val casiJ4 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    val nitJ4 = Thread {
        for ((ime, znak) in listOf("crke" to "a", "presledki" to " ", "narekovaji" to "a=\"")) for (velikost in listOf(20_000, 1_000_000)) {
            val telo = znak.repeat(velikost / znak.length)
            val e = "#EXTINF:-1 tvg-id=\"x.si\" $telo,Kanal $telo (720p)"
            val z = System.nanoTime()
            IptvPravila.atributi(e)
            val v = IptvPravila.vnos(e, "http://p.primer.test/k.m3u8", "", "")
            KanaliPravila.imeKanala(v?.ime ?: "")
            KanaliPravila.kljucImena("Kanal $telo [Geo-blocked]")
            casiJ4["$ime ${velikost / 1000} KB"] = (System.nanoTime() - z) / 1_000_000
        }
    }.apply { isDaemon = true; start() }
    nitJ4.join(90_000)
    preveriEnako("J4: vseh 6 vrstic (20 KB in 1 MB) v roku 90 s", 6, casiJ4.size)
    for ((ime, ms) in casiJ4.toSortedMap()) preveri("J4: $ime v $ms ms (< 3000)", ms < 3_000)
    preveriEnako("J4: atributi po popravku", mapOf("tvg-id" to "a", "group-title" to "B"), IptvPravila.atributi("#EXTINF:-1 tvg-id=\"a\" group-title=\"B\",Ime"))
    preveriEnako("J4: locljivost in opomba po popravku", "Kanal", KanaliPravila.imeKanala("Kanal  (720p) [ Not 24 / 7 ]"))

    println()
    println("== vrata preverb po gostitelju (1 hkrati, premor, dnevna meja) ==")
    val D = IptvPravila.VrataGostiteljev.Izid.DA
    val C = IptvPravila.VrataGostiteljev.Izid.CAKAJ
    val M = IptvPravila.VrataGostiteljev.Izid.MEJA
    val dan0 = 20_000L * 86_400_000L
    val vr = IptvPravila.VrataGostiteljev(premorMs = 3_000L, najvecNaDan = 3)
    preveriEnako("vrata: prva preverba gostitelja sme", D, vr.zasedi("a", dan0, 0))
    preveriEnako("vrata: druga hkrati na istem gostitelju caka", C, vr.zasedi("a", dan0, 10))
    preveriEnako("vrata: drug gostitelj hkrati sme", D, vr.zasedi("b", dan0, 10))
    vr.sprosti("a", 1_000)
    preveriEnako("vrata: med premorom caka", C, vr.zasedi("a", dan0, 3_999))
    preveriEnako("vrata: po premoru sme", D, vr.zasedi("a", dan0, 4_000))
    vr.sprosti("a", 4_100)
    preveriEnako("vrata: tretja danes sme", D, vr.zasedi("a", dan0, 7_100))
    vr.sprosti("a", 7_200)
    preveriEnako("vrata: cetrta danes - meja", M, vr.zasedi("a", dan0, 99_000))
    preveriEnako("vrata: stevec odprtij", 3, vr.odprtij("a", dan0))
    val shranjeno = vr.vNiz()
    val vr2 = IptvPravila.VrataGostiteljev(premorMs = 3_000L, najvecNaDan = 3)
    vr2.izNiza(shranjeno, dan0 + 1_000)
    preveriEnako("vrata: meja velja po ponovnem zagonu (iz shrambe)", M, vr2.zasedi("a", dan0 + 1_000, 0))
    preveriEnako("vrata: nov dan (UTC) - meja znova", D, vr2.zasedi("a", dan0 + 86_400_000L, 0))
    val vr3 = IptvPravila.VrataGostiteljev(premorMs = 3_000L, najvecNaDan = 3)
    vr3.izNiza(shranjeno, dan0 + 86_400_000L)
    preveriEnako("vrata: shramba prejsnjega dne se ne uporabi", 0, vr3.odprtij("a", dan0 + 86_400_000L))
    vr3.izNiza("pokvarjeno", dan0)
    preveriEnako("vrata: pokvarjena shramba", D, vr3.zasedi("a", dan0, 0))
    preveriEnako("vrata: privzeta meja kot Linux", 200, IptvPravila.NAJVEC_NA_GOSTITELJA_DAN)
    preveriEnako("vrata: privzeti premor kot Linux", 3_000L, IptvPravila.PREMOR_GOSTITELJA_MS)
    preveriEnako("gostitelj: male crke, brez vrat in uporabnika", "cdn.primer.test", IptvPravila.gostitelj("https://u:g@CDN.Primer.test:8443/a.m3u8"))
    preveriEnako("gostitelj: ni http", "", IptvPravila.gostitelj("rtmp://cdn.primer.test/a"))

    println()
    println("== I5: zavrnjen seznam se ne prenasa ob vsakem odprtju (10 min, 1 h, 6 h, 24 h) ==")
    val t0 = 1_000_000_000L
    val n1 = IptvPravila.poNapaki(null, t0, zavrnjen = true)
    preveriEnako("I5: prva napaka - 10 min", t0 + 600_000L, n1.naslednjiOb)
    preveriEnako("I5: med premorom ne", false, IptvPravila.smePrenesti(n1, t0 + 599_000L))
    preveriEnako("I5: po premoru da", true, IptvPravila.smePrenesti(n1, t0 + 600_000L))
    val n2 = IptvPravila.poNapaki(n1, t0 + 600_000L, true)
    val n3 = IptvPravila.poNapaki(n2, t0, true)
    val n4 = IptvPravila.poNapaki(n3, t0, true)
    val n5 = IptvPravila.poNapaki(n4, t0, true)
    preveriEnako("I5: premori 1 h, 6 h, 24 h, 24 h", listOf(3_600_000L, 21_600_000L, 86_400_000L, 86_400_000L),
        listOf(n2.naslednjiOb - (t0 + 600_000L), n3.naslednjiOb - t0, n4.naslednjiOb - t0, n5.naslednjiOb - t0))
    preveriEnako("I5: ura nazaj ne zaklene", true, IptvPravila.smePrenesti(n5, t0 - 3 * 86_400_000L))
    preveriEnako("I5: rocno takoj", true, IptvPravila.smePrenesti(n5, t0 + 61_000L, rocno = true))
    preveriEnako("I5: rocno najvec enkrat na minuto", false, IptvPravila.smePrenesti(n5, t0 + 30_000L, rocno = true))
    preveriEnako("I5: shramba", n4, IptvPravila.NapakaPrenosa.izNiza(n4.vNiz()))
    preveriEnako("I5: pokvarjena shramba", null, IptvPravila.NapakaPrenosa.izNiza("1;x"))
    preveriEnako("I5: Xtream 403 - zavrnjen", true, IptvPravila.zavrnjen(403, 0, xtream))
    preveriEnako("I5: Xtream 200 brez kanalov - zavrnjen (potekel racun)", true, IptvPravila.zavrnjen(200, 0, xtream))
    preveriEnako("I5: Xtream napaka povezave - premor", true, IptvPravila.napakaPrenosa(0, 0, xtream))
    preveriEnako("I5: Xtream 200 s kanali - ni napaka", false, IptvPravila.napakaPrenosa(200, 5000, xtream))
    preveriEnako("I5: javni seznam ob izpadu omrezja ne caka", false, IptvPravila.napakaPrenosa(0, 0, "https://iptv-org.github.io/iptv/countries/si.m3u"))
    preveriEnako("I5: javni seznam 404 - premor", true, IptvPravila.napakaPrenosa(404, 0, "https://iptv-org.github.io/iptv/countries/si.m3u"))
    preveriEnako("I5: javni seznam 404 ni zavrnitev racuna", false, IptvPravila.zavrnjen(404, 0, "https://iptv-org.github.io/iptv/countries/si.m3u"))
    preveriEnako("prikaz: Xtream samo gostitelj (geslo ne na zaslon)", "ponudnik.primer.test", IptvPravila.zaPrikaz(xtream))
    preveriEnako("prikaz: javni seznam", "iptv-org.github.io/iptv/countries/si.m3u", IptvPravila.zaPrikaz("https://iptv-org.github.io/iptv/countries/si.m3u"))

    println()
    println("== v krogu preverja samo naprava, kjer je bil vir dodan ==")
    val virA = "https://iptv-org.github.io/iptv/countries/si.m3u"
    val virB = "https://iptv-org.github.io/iptv/languages/eng.m3u"
    preveriEnako("tu dodan vir je nas", setOf(virA), IptvPravila.viriTukaj(emptySet(), setOf(virA), emptySet(), prejeto = false, jeTv = false))
    preveriEnako("prejet vir z druge naprave ni nas", emptySet<String>(), IptvPravila.viriTukaj(emptySet(), setOf(virA), emptySet(), prejeto = true, jeTv = true))
    preveriEnako("odstranjen vir izpade", setOf(virB), IptvPravila.viriTukaj(setOf(virA, virB), setOf(virB), setOf(virA, virB), prejeto = false, jeTv = false))
    preveriEnako("prehod: TV vzame obstojece vire", setOf(virA, virB), IptvPravila.viriTukaj(setOf(virA), setOf(virA, virB), null, prejeto = false, jeTv = true))
    preveriEnako("prehod: telefon obstojecih ne vzame, novega da", setOf(virB), IptvPravila.viriTukaj(setOf(virA), setOf(virA, virB), null, prejeto = false, jeTv = false))
    preveriEnako("prehod: prejet ob prehodu ni nas niti na TV", setOf(virA), IptvPravila.viriTukaj(setOf(virA), setOf(virA, virB), null, prejeto = true, jeTv = true))
    preveriEnako("v ozadju: javni seznam, dodan tu", "", IptvPravila.zakajNeVOzadju(true, virA, dodanTu = true, rocno = false))
    preveriEnako("v ozadju: javni seznam z druge naprave ne", "vir z druge naprave", IptvPravila.zakajNeVOzadju(true, virA, dodanTu = false, rocno = false))
    preveriEnako("rocno: javni seznam z druge naprave sme", "", IptvPravila.zakajNeVOzadju(true, virA, dodanTu = false, rocno = true))
    preveriEnako("rocno: Xtream, dodan tu, nikoli", "seznam s poverilnico", IptvPravila.zakajNeVOzadju(true, xtream, dodanTu = true, rocno = true))

    if (napak == 0) println("IptvPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
