package si.safeer.tv

import si.safeer.tv.SpletVarnostPravila as P

/**
 * Varnost brskalnika (zunanji pregled kode 8. 10. 2026): namere iz strani, rezervni naslov, zagon zunanje aplikacije
 * brez uporabnika, domaca stran za most SafeerBridge, content:// iz strani, potrdila TLS.
 */
fun main() {
    val nas = "si.safeer.tv"
    // 1. Namera iz strani: samo VIEW, nikoli nas paket, nikoli lokalne ali skriptne sheme.
    check(P.dovoljenaNamera("android.intent.action.VIEW", "market", null, nas))
    check(P.dovoljenaNamera("android.intent.action.VIEW", "mailto", "com.example.posta", nas))
    check(P.dovoljenaNamera(null, "geo", null, nas)) { "intent: brez akcije je VIEW" }
    check(!P.dovoljenaNamera("android.intent.action.VIEW", "https", nas, nas)) { "lasten paket" }
    check(!P.dovoljenaNamera("android.intent.action.VIEW", "https", "si.safeer.tv", "si.safeer.tv"))
    check(!P.dovoljenaNamera("android.intent.action.MAIN", "market", null, nas)) { "samo VIEW" }
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
    // Odlocitev velja za gostitelja IN vrata.
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
    println("SpletVarnostPravilaTest: OK")
}
