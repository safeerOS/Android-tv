package si.safeer.tv

import si.safeer.tv.SpletVarnostPravila as P

/**
 * Varnost brskalnika (zunanji pregled kode 8. 10. 2026): namere iz strani, rezervni naslov, zagon zunanje aplikacije
 * brez uporabnika, domaca stran za most SafeerBridge, content:// iz strani, potrdila TLS.
 */
fun main() {
    val nas = "si.safeer.tv"
    // Lastnik 4. 11. 2026: kartica filma na rtvslo.si (target=_blank na 365.rtvslo.si) ni naredila nic.
    val rtv = "https://www.rtvslo.si/"
    val film = "https://365.rtvslo.si/arhiv/resnica/175137656"
    check(P.pojavnoOkno(true, film, rtv, null, true) == P.PojavnoOkno.ISTI_ZAVIHEK) { "ista eTLD+1 v aplikaciji: isti zavihek" }
    check(P.pojavnoOkno(true, film, rtv, null, false) == P.PojavnoOkno.NOV_ZAVIHEK) { "ista eTLD+1 v brskanju: nov zavihek" }
    check(P.pojavnoOkno(false, film, rtv, film, true) == P.PojavnoOkno.ZAVRNI) { "brez kretnje: zavrni" }
    check(P.pojavnoOkno(true, "https://oglas.example/x", rtv, null, true) == P.PojavnoOkno.ZAVRNI) { "tuja domena brez povezave (window.open)" }
    check(P.pojavnoOkno(true, "https://oglas.example/x", rtv, "https://drugo.example/", false) == P.PojavnoOkno.ZAVRNI) { "povezava drugam" }
    check(P.pojavnoOkno(true, "https://www.youtube.com/watch?v=1", rtv, "  https://www.youtube.com/watch?v=1\n ", true) == P.PojavnoOkno.NOV_ZAVIHEK) { "kliknjena tuja povezava: nov zavihek" }
    check(P.pojavnoOkno(true, "javascript:alert(1)", rtv, "javascript:alert(1)", true) == P.PojavnoOkno.ZAVRNI) { "samo http(s)" }
    check(P.pojavnoOkno(true, "file:///sdcard/a.html", "file:///sdcard/b.html", null, true) == P.PojavnoOkno.ZAVRNI) { "file ni dovoljen" }
    check(P.pojavnoOkno(true, "https://a.rtvslo.si.zlo.example/", rtv, null, true) == P.PojavnoOkno.ZAVRNI) { "podobna domena ni ista eTLD+1" }
    check(P.pojavnoOkno(true, "https://news.bbc.co.uk/x", "https://www.bbc.co.uk/", null, true) == P.PojavnoOkno.ISTI_ZAVIHEK) { "dvodelna pripona" }
    check(P.pojavnoOkno(true, "https://evil.co.uk/x", "https://www.bbc.co.uk/", null, true) == P.PojavnoOkno.ZAVRNI) { "co.uk ni registrirana domena" }
    // 1. Namera iz strani: samo VIEW, nikoli nas paket, nikoli lokalne ali skriptne sheme.
    check(P.dovoljenaNamera("android.intent.action.VIEW", "market", null, nas))
    check(P.dovoljenaNamera("android.intent.action.VIEW", "mailto", "com.example.posta", nas))
    check(P.dovoljenaNamera(null, "geo", null, nas)) { "intent: brez akcije je VIEW" }
    check(!P.dovoljenaNamera("android.intent.action.VIEW", "https", nas, nas)) { "lasten paket" }
    check(!P.dovoljenaNamera("android.intent.action.VIEW", "https", "si.safeer.tv", "si.safeer.tv"))
    check(!P.dovoljenaNamera("android.intent.action.MAIN", "market", null, nas)) { "samo VIEW" }
    check(!P.dovoljenaNamera("android.intent.action.VIEW", null, "com.example", nas)) { "intent: brez podatkov ni povezava (pregled 3. 11.)" }
    check(!P.dovoljenaNamera(null, "", null, nas)) { "prazna shema" }
    // Pregled 3. 11. 2026: MobileFileProvider samo znotraj predpomnilnika.
    val koren = java.nio.file.Files.createTempDirectory("ponudnik").toFile()
    try {
        java.io.File(koren, "posodobitve").mkdirs(); java.io.File(koren, "posodobitve/a.apk").writeText("x")
        check(P.datotekaPonudnika(koren, "/cache_files/posodobitve/a.apk")?.name == "a.apk")
        check(P.datotekaPonudnika(koren, "/cache_files/../shared_prefs/x.xml") == null) { "../ iz predpomnilnika" }
        check(P.datotekaPonudnika(koren, "/cache_files/posodobitve/../../x") == null)
        check(P.datotekaPonudnika(koren, "/external_files/Android/x") == null) { "zunanja mapa ni vec dovoljena" }
        check(P.datotekaPonudnika(koren, "/shared_prefs/x.xml") == null)
        check(P.datotekaPonudnika(koren, "/cache_files/") == null) { "koren sam" }
        check(P.datotekaPonudnika(koren, null) == null)
    } finally { koren.deleteRecursively() }
    check(!P.dovoljenaNamera("android.intent.action.SEND", null, null, nas))
    check(!P.dovoljenaNamera("android.intent.action.CALL", "tel", null, nas))
    for (s in listOf("content", "file", "javascript", "data", "blob", "about", "intent", "android-app", "CONTENT", "File")) {
        check(!P.dovoljenaNamera("android.intent.action.VIEW", s, null, nas)) { "shema $s" }
    }
    // 2. browser_fallback_url: samo spletni naslov http(s) z gostiteljem.
    check(P.varenRezervniNaslov("https://primer.si/pot?x=1"))
    check(P.varenRezervniNaslov("http://primer.si"))
    for (u in listOf(null, "", "javascript:alert(1)", "JavaScript:alert(1)", "file:///android_asset/brave_home.html",
                     "content://si.safeer.tv.datoteke/x", "data:text/html,x", "intent://x#Intent;end", "https:///brez-gostitelja",
                     "about:blank", " javascript:alert(1)")) {
        check(!P.varenRezervniNaslov(u)) { "rezervni $u" }
    }
    // 3. Zunanjo aplikacijo odpre samo uporabnikovo dejanje (kretnja ali tipka/dotik pred najvec 3 s).
    check(P.dovoljenZunanjiZagon(kretnja = true, msOdDejanja = Long.MAX_VALUE))
    check(P.dovoljenZunanjiZagon(kretnja = false, msOdDejanja = 1200))
    check(P.dovoljenZunanjiZagon(kretnja = false, msOdDejanja = P.ZAGON_PO_DEJANJU_MS))
    check(!P.dovoljenZunanjiZagon(kretnja = false, msOdDejanja = P.ZAGON_PO_DEJANJU_MS + 1))
    check(!P.dovoljenZunanjiZagon(kretnja = false, msOdDejanja = Long.MAX_VALUE)) { "oglas ob nalaganju" }
    check(!P.dovoljenZunanjiZagon(kretnja = false, msOdDejanja = -5)) { "ura nazaj ne steje" }
    // 3b. Eno dejanje odpre najvec eno zunanjo aplikacijo: po uspesnem zagonu se porabi.
    run {
        val d = P.PorabljivoDejanje()
        check(d.msOd(1000) == Long.MAX_VALUE) { "brez dejanja" }
        d.oznaci(1000)
        check(d.msOd(1500) == 500L)
        d.porabi()
        check(d.msOd(1600) == Long.MAX_VALUE) { "porabljeno dejanje ne velja vec" }
        check(!P.dovoljenZunanjiZagon(kretnja = false, msOdDejanja = d.msOd(1600))) { "drugi zagon brez novega dejanja" }
        d.oznaci(2000)
        check(d.msOd(2100) == 100L) { "novo dejanje spet velja" }
        d.porabi(); d.porabi()
        check(d.msOd(2200) == Long.MAX_VALUE)
    }
    // 4. Domaca stran brskalnika: natanko brave_home.html (poizvedba in sidro smeta biti), nic drugega.
    check(P.jeDomacaBrskalnika("file:///android_asset/brave_home.html"))
    check(P.jeDomacaBrskalnika("file:///android_asset/brave_home.html?tv=1#x"))
    for (u in listOf(null, "", "about:blank", "about:blank#x", "safeer://home", "file:///android_asset/link/index.html",
                     "file:///android_asset/brave_home.html.evil", "https://napadalec.test/file:///android_asset/brave_home.html",
                     "file:///android_asset/../android_asset/brave_home.html", "data:text/html,brave_home")) {
        check(!P.jeDomacaBrskalnika(u)) { "domaca $u" }
    }
    // 5. content:// samo kot glavni dokument (priponka, ki jo odpre uporabnik), nikoli kot vir strani.
    check(P.dovoliContentZahtevo("content://com.android.externalstorage.documents/x.html", glavniOkvir = true))
    check(!P.dovoliContentZahtevo("content://media/external/images/media/1", glavniOkvir = false))
    check(!P.dovoliContentZahtevo("CONTENT://media/external/images/media/1", glavniOkvir = false))
    check(P.dovoliContentZahtevo("https://primer.si/slika.png", glavniOkvir = false)) { "drugih shem pravilo ne zadeva" }
    // 6. Potrdila TLS: javni gostitelj nikoli ne dobi »Odpri vseeno« za tuj ali napacen izdajatelj/ime.
    val NOTYETVALID = 0; val EXPIRED = 1; val IDMISMATCH = 2; val UNTRUSTED = 3; val DATE_INVALID = 4; val INVALID = 5
    for (e in listOf(IDMISMATCH, UNTRUSTED, INVALID)) {
        check(!P.sslSmeNadaljevati(e, "banka.si")) { "javni $e" }
        check(!P.sslSmeNadaljevati(e, "10.evil.com")) { "ime, ki se zacne kot zasebni IP, ni zasebno ($e)" }
        check(P.sslSmeNadaljevati(e, "192.168.1.1")) { "usmerjevalnik v domacem omrezju ($e)" }
        check(P.sslSmeNadaljevati(e, "nas.local"))
        check(P.sslSmeNadaljevati(e, "[fd12:3456::1]"))
    }
    for (e in listOf(NOTYETVALID, EXPIRED, DATE_INVALID)) check(P.sslSmeNadaljevati(e, "primer.si")) { "cas $e" }
    check(!P.sslSmeNadaljevati(99, "primer.si")) { "neznana napaka" }
    // Zasebni gostitelji: oktete razclenimo, imena ne primerjamo po predponi.
    for (h in listOf("10.0.0.1", "172.16.5.4", "172.31.255.255", "192.168.1.10", "169.254.1.1", "127.0.0.1", "localhost",
                     "tiskalnik", "nas.local", "router.lan", "x.home.arpa", "[::1]", "fe80::1", "[fe80::1%wlan0]", "fd00::5")) {
        check(P.jeZasebniGostitelj(h)) { "zasebni $h" }
    }
    for (h in listOf("10.evil.com", "192.168.1.10.evil.com", "172.32.0.1", "172.15.0.1", "8.8.8.8", "256.1.1.1", "10.0.0",
                     "10.0.0.1.5", "banka.si", ".local", "evil.local.com", "2001:db8::1", "", "0x0a.0.0.1", "010.0.0.1")) {
        check(!P.jeZasebniGostitelj(h)) { "javni $h" }
    }
    // Zunanji pregled 2. 11. 2026: enako kot WebtorPravila.zasebenNaslov - 0/8, CGNAT 100.64/10, 192.0.0/24, 224+.
    for (h in listOf("0.0.0.0", "0.1.2.3", "100.64.0.1", "100.127.255.254", "192.0.0.8", "224.0.0.251", "239.255.255.250",
                     "240.0.0.1", "255.255.255.255", "[::ffff:100.64.0.1]")) {
        check(P.jeZasebniGostitelj(h)) { "ni javni $h" }
    }
    for (h in listOf("100.63.255.255", "100.128.0.1", "192.0.1.1", "192.0.2.1", "223.255.255.255", "1.0.0.1")) {
        check(!P.jeZasebniGostitelj(h)) { "javni $h" }
    }
    // Odlocitev velja za gostitelja IN vrata.
    run {
        val a = P.sslKljucSPotrdilom("nas.lan:443", byteArrayOf(1, 2, 3))
        val b = P.sslKljucSPotrdilom("nas.lan:443", byteArrayOf(9, 9, 9))
        check(a != null && b != null && a != b) { "drugo potrdilo na istem naslovu = druga izjema (pregled 3. 11.)" }
        check(P.sslKljucSPotrdilom("nas.lan:443", null) == null) { "brez potrdila ni izjeme" }
        check(P.sslKljucSPotrdilom("", byteArrayOf(1)) == null)
    }
    check(P.sslKljuc("https://Primer.si/pot") == "primer.si:443")
    check(P.sslKljuc("https://primer.si:8443/x") == "primer.si:8443")
    check(P.sslKljuc("https://[fd12::1]:8443/") == "[fd12::1]:8443")
    check(P.sslKljuc("http://primer.si/") == "primer.si:80")
    check(P.sslKljuc("ni naslov") == "")
    // 7. Vrstni red odlocitve o potrdilu (F1): trda ovira velja PRED zapomnjeno izjemo seje.
    val PREKLICI = P.SslOdlocitev.PREKLICI; val NADALJUJ = P.SslOdlocitev.NADALJUJ; val VPRASAJ = P.SslOdlocitev.VPRASAJ
    // Napad F1: uporabnik je prej ob pretecenem potrdilu potrdil izjemo (gostitelj je v dovoljenih); kasneje za
    // istega gostitelja pride nezaupano / napacno-ime potrdilo -> kljub zapomnjeni izjemi PREKLICI.
    check(P.sslOdlocitev(UNTRUSTED, "banka.si", zeDovoljen = true) == PREKLICI) { "nezaupano kljub zapomnjeni izjemi" }
    check(P.sslOdlocitev(IDMISMATCH, "banka.si", zeDovoljen = true) == PREKLICI) { "napacno ime kljub zapomnjeni izjemi" }
    check(P.sslOdlocitev(INVALID, "banka.si", zeDovoljen = true) == PREKLICI)
    check(P.sslOdlocitev(UNTRUSTED, "banka.si", zeDovoljen = false) == PREKLICI) { "nezaupano javno brez izjeme" }
    // Napaka casa pri javnem naslovu: brez izjeme VPRASAJ, z zapomnjeno izjemo NADALJUJ (brez ponovnega vprasanja).
    check(P.sslOdlocitev(EXPIRED, "banka.si", zeDovoljen = false) == VPRASAJ) { "pretecen javni: vprasaj" }
    check(P.sslOdlocitev(EXPIRED, "banka.si", zeDovoljen = true) == NADALJUJ) { "pretecen javni, ze dovoljen: nadaljuj" }
    check(P.sslOdlocitev(DATE_INVALID, "banka.si", zeDovoljen = true) == NADALJUJ)
    // Zasebni gostitelj (usmerjevalnik, NAS): vsaka napaka je uporabnikova odlocitev.
    check(P.sslOdlocitev(UNTRUSTED, "192.168.1.1", zeDovoljen = false) == VPRASAJ) { "zasebni nezaupano: vprasaj" }
    check(P.sslOdlocitev(UNTRUSTED, "192.168.1.1", zeDovoljen = true) == NADALJUJ)
    check(P.sslOdlocitev(IDMISMATCH, "nas.local", zeDovoljen = false) == VPRASAJ)
    // Neznana napaka: nikoli nadaljuj, niti z zapomnjeno izjemo.
    check(P.sslOdlocitev(99, "banka.si", zeDovoljen = true) == PREKLICI) { "neznana napaka kljub izjemi" }
    // Gmail -> accounts.google.com (TV 1. 11. 2026, SIGTRAP): preusmeritev na Googlu gre znova skozi loadUrl
    check(P.naloziPoPreusmeritvi(true, true, "https://accounts.google.com/ServiceLogin", true)) { "Google preusmeritev" }
    run {
        var st = 0; var od = 0L; var dovoljenih = 0
        for (i in 0 until 20) { val (s, o, ok) = P.ponovnoNalaganje(st, od, 1_000L + i * 100L); st = s; od = o; if (ok) dovoljenih++ }
        check(dovoljenih == P.NAJVEC_PONOVNIH_NALAGANJ) { "zanka preusmeritev ustavljena po meji ($dovoljenih)" }
        check(P.ponovnoNalaganje(st, od, 1_000L + 20 * 100L + P.OKNO_ZANKE_MS + 1).third) { "po oknu spet dovoljeno" }
        // 2. 11. 2026: Gmail Sign in v novem oknu - vratar odloci pred ponovnim nalaganjem z novo identiteto
        check(P.vratarNajprej(true, true, "https://accounts.google.com/AccountChooser/signinchooser", false)) { "vratar prvi" }
        check(P.dotikVPogledu(10f, 10f, 1920, 1080)) { "dotik v pogledu" }
        check(!P.dotikVPogledu(-5f, 10f, 1920, 1080) && !P.dotikVPogledu(10f, 2000f, 1920, 1080) && !P.dotikVPogledu(Float.NaN, 1f, 10, 10)) { "dotik zunaj" }
        check(!P.vratarNajprej(false, true, "https://accounts.google.com/", false)) { "brez vratarja ne" }
        check(!P.vratarNajprej(true, false, "https://accounts.google.com/", false)) { "podokvir ne" }
        check(!P.vratarNajprej(true, true, "https://web.stremio.com/#/addons", true)) { "dodatek obravnava sam" }
        check(!P.vratarNajprej(true, true, "stremio://x/manifest.json", false)) { "ne-http ne" }
    }
    check(!P.naloziPoPreusmeritvi(true, false, "https://accounts.google.com/", true)) { "ni preusmeritev" }
    check(!P.naloziPoPreusmeritvi(false, true, "https://accounts.google.com/", true)) { "podokvir" }
    check(!P.naloziPoPreusmeritvi(true, true, "https://example.com/", false)) { "ni Google" }
    check(!P.naloziPoPreusmeritvi(true, true, "intent://x", true)) { "ni http" }
    check(P.naloziZNovoIdentiteto(true, "https://accounts.google.com/v3/signin/identifier", true)) { "Gmail Sign in: nova identiteta" }
    check(!P.naloziZNovoIdentiteto(true, "https://accounts.google.com/", false)) { "ista identiteta" }
    check(!P.naloziZNovoIdentiteto(false, "https://accounts.google.com/", true)) { "podokvir" }
    check(!P.naloziZNovoIdentiteto(true, "intent://x", true)) { "ni http" }
    // Pregled Gemini 3. 11. 2026: dotik mostu samo z zetonom skripta glavnega okvirja (tuj okvir ga nima).
    val z = P.novZeton()
    check(z.length == 48 && z != P.novZeton()) { "zeton nakljucen, 24 bajtov" }
    check(P.zetonVelja(z, z)) { "pravi zeton velja" }
    check(!P.zetonVelja(null, z) && !P.zetonVelja("", z) && !P.zetonVelja(z.dropLast(1), z) && !P.zetonVelja(z + "0", z)) { "tuj okvir brez zetona" }
    check(!P.zetonVelja(P.ZETON_DOTIKA_MESTO, P.ZETON_DOTIKA_MESTO)) { "nevstavljeno mesto ni zeton" }
    check(!P.zetonVelja("", "")) { "prazen ni zeton" }
    check(P.vstaviZeton("var Z = '${P.ZETON_DOTIKA_MESTO}';", z) == "var Z = '$z';") { "vstavljen v skript" }
    // Tedenski pregled 4. 11. 2026 (UXSS): naslov od zunaj (namera, Safeer Link) samo http(s), krajevni PDF ali domaca.
    val N = P.Vhod.NAMERA; val L = P.Vhod.LINK; val S = P.Vhod.SPLET
    for (v in listOf(N, L, S)) {
        check(P.vhodniNaslov("https://primer.si/pot?x=1#a", v)) { "https $v" }
        check(P.vhodniNaslov("HTTP://primer.si", v)) { "shema z velikimi $v" }
        check(P.vhodniNaslov("http://192.168.1.5:8443/cast/screen/abc", v)) { "Hub $v" }
        check(P.vhodniNaslov("https://www.youtube.com/watch?v=x&t=1", v))
        for (u in listOf(null, "", "javascript:alert(document.cookie)", "JavaScript:alert(1)", " javascript:alert(1)",
            "java\tscript:alert(1)", "java\nscript:alert(1)", "javascript:/*https://a.si*/alert(1)",
            "data:text/html,<script>alert(1)</script>", "blob:https://a.si/x", "intent://x#Intent;scheme=https;end",
            "about:blank", "chrome://settings", "android-app://com.x", "https:", "https://", "https:///pot", "https://:80/",
            "https:primer.si", "https://a.si/\u0000", "https://a.si/ x", "file:///sdcard/x.html", "file:///android_asset/link/index.html",
            "https://" + "a".repeat(P.NAJDALJSI_VHODNI))) {
            check(!P.vhodniNaslov(u, v)) { "zavrnjen $v: $u" }
        }
    }
    // Namera: krajevni PDF gre v nas pregledovalnik (ne v WebView); file: samo PDF, nikoli assets.
    check(P.vhodniNaslov("content://com.android.providers.downloads.documents/document/12", N))
    check(P.vhodniNaslov("file:///storage/emulated/0/Download/racun.pdf", N))
    check(!P.vhodniNaslov("file:///storage/emulated/0/Download/stran.html", N)) { "file: ni PDF" }
    check(!P.vhodniNaslov("file:///android_asset/x.pdf", N)) { "assets niso dokument" }
    check(!P.vhodniNaslov("file:///sdcard/../data/x.pdf", N)) { "../" }
    check(!P.vhodniNaslov("content://", N))
    check(!P.vhodniNaslov("content://x/y", L) && !P.vhodniNaslov("content://x/y", S)) { "content: iz Linka ni dovoljen" }
    check(!P.vhodniNaslov("file:///storage/x.pdf", L)) { "file: iz Linka" }
    // Link: nasa domaca stran (Daljinec open_url »home«), natanko.
    check(P.vhodniNaslov(SpletMostPravila.DOMACA + "?barve=1,2&tv=1", L))
    check(P.vhodniNaslov(P.DOMACA_BRSKALNIKA, L))
    check(!P.vhodniNaslov(SpletMostPravila.DOMACA, N) && !P.vhodniNaslov(SpletMostPravila.DOMACA, S)) { "domaca ni za namero" }
    check(!P.vhodniNaslov("file:///android_asset/splet/drugo.html", L))
    // S1: spletna aplikacija cez cel zaslon samo od Safeerja ali s seznama; tuja aplikacija dobi navaden zavihek.
    check(P.spletnaAplikacija("https://banka.si/prijava", odSafeerja = false) { false } == P.SpletnaAplikacija.ZAVIHEK) { "tuja aplikacija: zavihek z vrstico" }
    check(P.spletnaAplikacija("https://youtube.com/", odSafeerja = false) { true } == P.SpletnaAplikacija.CEZ_ZASLON) { "naslov s seznama" }
    check(P.spletnaAplikacija("https://a.si/", odSafeerja = true) { false } == P.SpletnaAplikacija.CEZ_ZASLON) { "od Safeerja" }
    for (u in listOf("javascript:alert(1)", "file:///sdcard/x.pdf", "content://x/y", SpletMostPravila.DOMACA, null))
        check(P.spletnaAplikacija(u, odSafeerja = true) { true } == P.SpletnaAplikacija.ZAVRNI) { "spletna aplikacija ne: $u" }
    // Zadnja vrata v loadUrl: skripta in data: nikoli, tudi z zamaskirano shemo.
    for (u in listOf("javascript:alert(1)", "JAVASCRIPT:x", " \u0001javascript:x", "java\tscript:x", "jav\na\rscript:x", "data:text/html,x", "vbscript:x"))
        check(P.skriptniNaslov(u)) { "skripta: $u" }
    for (u in listOf(null, "https://a.si/javascript:x", "about:blank", SpletMostPravila.DOMACA, "http://127.0.0.1:1/pdfjs/web/viewer.html"))
        check(!P.skriptniNaslov(u)) { "ni skripta: $u" }
    // Neodvisni pregled 4. 11. 2026: navigate() z domace strani - obrezan cilj, prazen zavrnjen (kot telefon).
    check(P.ciljNavigacije("  https://a.si/ \n") == "https://a.si/") { "navigate: trim" }
    check(P.ciljNavigacije("   ") == null && P.ciljNavigacije("") == null && P.ciljNavigacije(null) == null) { "navigate: prazen cilj zavrnjen" }
    // Neodvisni pregled 4. 11. 2026: pripeti odtis Huba velja za isti izvor ne glede na zapis privzetih vrat in velike crke.
    val hub = "https://192.168.1.20/"
    check(P.istiIzvor("https://192.168.1.20:443/cast/x", hub)) { "privzeta vrata zapisana / nezapisana" }
    check(P.istiIzvor("https://192.168.1.20/cast/x", "https://192.168.1.20:443/")) { "zaupan z vrati, naslov brez" }
    check(P.istiIzvor("https://Hub.Local/x", "https://hub.local/")) { "gostitelj brez razlike v velikosti crk" }
    check(!P.istiIzvor("https://192.168.1.21/", hub)) { "drug gostitelj ne" }
    check(!P.istiIzvor("https://192.168.1.20:8443/", hub)) { "druga vrata ne" }
    check(!P.istiIzvor("http://192.168.1.20:443/", hub)) { "druga shema ne" }
    check(!P.istiIzvor("https://192.168.1.20/", "")) { "brez zaupanega naslova ne" }
    check(!P.istiIzvor("ni naslov", hub)) { "neveljaven naslov ne" }
    println("SpletVarnostPravilaTest: OK")
}
