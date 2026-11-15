package si.safeer.tv

import si.safeer.tv.KazalecPravila as K

/** Lastnik 9. 11. 2026: na straneh, ki niso za TV (24ur.com), daljinec privzeto deluje kot kazalec. */
fun main() {
    // Lastnik 15. 11. 2026 (Ai Lina tv browser): kazalec je privzet na vsaki strani, ki ni narejena za TV (prej 9. 11.: puscice).
    check(K.privzetoKazalec(true, "https://www.24ur.com/", zaTv = false, zapomnjeno = null)) { "24ur.com na TV: kazalec" }
    check(K.privzetoKazalec(true, "https://www.rtvslo.si/", zaTv = false, zapomnjeno = null)) { "rtvslo.si na TV: kazalec" }
    check(K.privzetoKazalec(true, "https://example.org/", zaTv = false, zapomnjeno = null)) { "navadna stran brez posebnega pravila: kazalec" }
    check(K.privzetoKazalec(true, "https://www.facebook.com/", zaTv = false, zapomnjeno = null, miska = true)) { "Facebook: kazalec" }
    check(K.privzetoKazalec(true, "https://www.24ur.com/", zaTv = false, zapomnjeno = true)) { "uporabnik je izbral kazalec" }
    check(!K.privzetoKazalec(true, "https://www.youtube.com/tv", zaTv = true, zapomnjeno = null)) { "YouTube za TV: puscice" }
    check(!K.privzetoKazalec(true, "https://safeer.si/", zaTv = true, zapomnjeno = null, miska = true)) { "nasa stran za TV: puscice tudi z miska" }
    check(!K.privzetoKazalec(true, "file:///android_asset/home.html", zaTv = false, zapomnjeno = null)) { "nasa domaca stran: puscice" }
    check(!K.privzetoKazalec(false, "https://www.24ur.com/", zaTv = false, zapomnjeno = null)) { "telefon/tablica: brez kazalca" }
    check(!K.privzetoKazalec(true, "https://www.24ur.com/", zaTv = false, zapomnjeno = false)) { "uporabnik je za gostitelja izbral puscice" }
    check(K.privzetoKazalec(true, "https://safeer.si/", zaTv = true, zapomnjeno = true)) { "uporabnik je izbral kazalec tudi na strani za TV" }
    // Lastnik 15. 11. 2026: »cela stran ni vec skripta, ker nas to samo upocasni« - skript za fokus samo tam, kjer vodijo puscice.
    check(!K.skriptZaFokus(true, "https://www.24ur.com/", zaTv = false, zapomnjeno = null)) { "24ur.com: kazalec, brez skripta za fokus" }
    check(!K.skriptZaFokus(true, "https://www.rtvslo.si/", zaTv = false, zapomnjeno = null)) { "rtvslo.si: brez skripta za fokus" }
    check(!K.skriptZaFokus(true, "https://www.facebook.com/", zaTv = false, zapomnjeno = null, miska = true)) { "Facebook: brez skripta za fokus" }
    check(K.skriptZaFokus(true, "https://www.youtube.com/tv", zaTv = true, zapomnjeno = null)) { "YouTube za TV: puscice, skript tece" }
    check(K.skriptZaFokus(true, "file:///android_asset/home.html", zaTv = false, zapomnjeno = null)) { "nasa domaca stran: skript tece" }
    check(K.skriptZaFokus(true, "https://www.24ur.com/", zaTv = false, zapomnjeno = false)) { "uporabnik je izbral puscice: skript tece" }
    check(!K.skriptZaFokus(true, "https://safeer.si/", zaTv = true, zapomnjeno = true)) { "uporabnik je izbral kazalec: brez skripta" }
    check(K.skriptZaFokus(false, "https://www.24ur.com/", zaTv = false, zapomnjeno = null)) { "naprava brez TV: staro vedenje (odloca dotik v UserScriptManager)" }
    check(K.gostitelj("https://WWW.24ur.com./clanek") == "www.24ur.com") { "gostitelj: malo, brez pike" }
    check(K.gostitelj("javascript:alert(1)") == null) { "samo http(s)" }
    // Lastnik 11. 11. 2026: brskalnik kot spletna aplikacija - premiki sporocajo lebdenje. (Pravilo »kazalec se nikoli ne skrije sam«
    // je 15. 11. 2026 preklicano s skrivanjem ob mirovanju in ob pravi miski, spodaj; nacin kazalca in zapomnjena izbira ostaneta.)
    check(K.lebdenje()) { "premiki kazalca sporocajo lebdenje v brskalniku in aplikaciji" }
    // Lastnik 15. 11. 2026 (K7): navidezni kazalec se ob nedejavnosti skrije, ob tipki pokaze.
    val Z = K.MIROVANJE_SKRIJ_MS
    val vid = K.Skrivanje(skrit = false, zadnjaAktivnostMs = 1000L)
    check(Z in 3_000L..10_000L) { "mirovanje za skritje je nekaj sekund: $Z" }
    check(!K.skrivanjeCas(vid, 1000L + Z - 1, tipkaDrzana = false, vlecem = false).skrit) { "tik pred iztekom mirovanja ostane viden" }
    check(K.skrivanjeCas(vid, 1000L + Z, tipkaDrzana = false, vlecem = false).skrit) { "po mirovanju brez tipke se skrije" }
    check(!K.skrivanjeCas(vid, 1000L + 10 * Z, tipkaDrzana = true, vlecem = false).skrit) { "drzana smerna tipka: ne skrije" }
    check(!K.skrivanjeCas(vid, 1000L + 10 * Z, tipkaDrzana = false, vlecem = true).skrit) { "vlecenje: ne skrije" }
    val skrit = K.skrivanjeCas(vid, 1000L + Z, tipkaDrzana = false, vlecem = false)
    val poTipki = K.skrivanjeTipka(skrit, 1000L + Z + 50)
    check(!poTipki.skrit && poTipki.zadnjaAktivnostMs == 1000L + Z + 50) { "tipka pokaze skritega in zacne mirovanje znova" }
    check(!K.skrivanjeCas(poTipki, 1000L + Z + 50 + Z - 1, tipkaDrzana = false, vlecem = false).skrit) { "po tipki ne skrije takoj" }
    check(K.skrivanjeAktivnost(vid, 4000L).zadnjaAktivnostMs == 4000L) { "gibanje podaljsa mirovanje" }
    check(K.skrivanjeAktivnost(skrit, 9999L) == skrit) { "gibanje skritega ne pokaze (to naredi samo tipka)" }
    check(K.doSkritjaMs(vid, 2000L) == Z - 1000L) { "casovnik tece do izteka mirovanja" }
    check(K.doSkritjaMs(vid, 1000L + Z) == Z && K.doSkritjaMs(vid, 500L) == Z && K.doSkritjaMs(vid, 1000L) == Z) { "potekel rok ali ura nazaj: cel rok, brez zanke na 1 ms" }
    check(K.tipkaSamoPokaze(true, true) && !K.tipkaSamoPokaze(true, false) && !K.tipkaSamoPokaze(false, true)) { "prvi OK ob skritem kazalcu ga samo pokaze" }
    // Lastnik 15. 11. 2026 (K8): prava miska (zracna, USB) skrije navideznega, tipka daljinca ga spet pokaze.
    check(K.skrivanjeMiska(vid).skrit) { "prava miska skrije navidezni kazalec" }
    check(K.skrivanjeTipka(K.skrivanjeMiska(vid), 5000L) == K.Skrivanje(false, 5000L)) { "tipka daljinca po miski spet pokaze navideznega" }
    check(K.jeDogodekMiske(0x2002, 7) && K.jeDogodekMiske(0x2002, 9) && K.jeDogodekMiske(0x2002, 8) && K.jeDogodekMiske(0x2002, 2) &&
        K.jeDogodekMiske(0x2002, 0) && K.jeDogodekMiske(0x2002, 11)) { "dogodki miske (lebdenje, kolesce, premik, pritisk) skrijejo" }
    check(!K.jeDogodekMiske(0x1002, 2) && !K.jeDogodekMiske(0x1002, 7)) { "dotik na zaslonu ni miska" }
    check(!K.jeDogodekMiske(0x2002, 10) && !K.jeDogodekMiske(0x2002, 1)) { "odhod miske in spust ne skrijeta" }
    check(!K.jeDogodekMiske(0x101, 7) && !K.jeDogodekMiske(0, 7)) { "tipkovnica/neznan izvor ni miska" }
    // Spletna aplikacija uporablja isto pravilo (zaTv = false): zapomnjena izbira je nad privzetim v obe smeri.
    check(!K.privzetoKazalec(true, "https://www.facebook.com/", zaTv = false, zapomnjeno = false, miska = true)) { "aplikacija: izbrane puscice veljajo" }
    check(K.privzetoKazalec(true, "https://example.org/", zaTv = false, zapomnjeno = true, miska = false)) { "aplikacija: izbran kazalec velja" }
    check(K.privzetoKazalec(true, "https://www.instagram.com/", zaTv = false, zapomnjeno = null, miska = true)) { "aplikacija: Instagram privzeto kazalec" }
    check(K.kljucStrani("www.24ur.com", false) == "www.24ur.com") { "kljuc: navadna stran" }
    check(K.kljucStrani("www.24ur.com", true) != K.kljucStrani("www.24ur.com", false)) { "kljuc: ogled istega gostitelja je druga stran" }
    check(K.kljucStrani(null, true) == null) { "kljuc: brez gostitelja" }
    // Lastnik 9. 11. 2026: strani na TV v namizni sirini (24ur.com ni vec v postavitvi za tablico).
    check(K.sirinaStrani(0, jeTelevizor = true) == 1280) { "TV: privzeta namizna sirina" }
    check(K.sirinaStrani(0, jeTelevizor = false) == 0) { "telefon: brez" }
    check(K.sirinaStrani(1600, jeTelevizor = true) == 1600) { "izrecna sirina (Medijski center) ostane" }
    check(K.brezSirine(dotik = false, youtubeZaTv = true, androidIdentiteta = false, pregledovalnik = false, http = true)) { "YouTube za TV ostane" }
    check(K.brezSirine(dotik = false, youtubeZaTv = false, androidIdentiteta = true, pregledovalnik = false, http = true)) { "Googlova prijava ostane" }
    check(!K.brezSirine(dotik = false, youtubeZaTv = false, androidIdentiteta = false, pregledovalnik = false, http = true)) { "24ur: namizna sirina" }
    check(K.zacetnaPovecava(1280, brezSirine = false, google = true, namizna = 75) == 100) { "Google: 100, ne namizna" }
    check(K.zacetnaPovecava(1280, brezSirine = false, google = false, namizna = 75) == 75) { "24ur: namizna" }
    check(K.zacetnaPovecava(0, brezSirine = false, google = false, namizna = 75) == 100) { "brez sirine: 100" }
    // Video lastnika 9. 11. 2026: gor pri 100 px je skocil v naslovno vrstico, stran se ni premaknila.
    check(!K.vNaslovnoVrstico(100f, stranLahkoGor = true)) { "100 px pod vrhom: drsi, ne naslovna vrstica" }
    check(!K.vNaslovnoVrstico(10f, stranLahkoGor = true)) { "na vrhu, stran lahko gor: drsi" }
    check(K.vNaslovnoVrstico(10f, stranLahkoGor = false)) { "na vrhu, stran na zacetku: naslovna vrstica" }
    check(K.LEBDENJE_MS in 50L..200L) { "lebdenje najvec vsakih ~100 ms" }
    check(K.PX_NA_ZAREZO > 0f) { "nadomestni faktor drsenja" }
    // Slepi pregled TV2 (F1): stran brez tipke ne preklopi nacina; ogled je samo POT na znanem gostitelju.
    for (u in listOf("https://www.youtube.com/watch?v=abc", "https://m.youtube.com/watch?v=1", "https://youtube.com/shorts/abc",
        "https://www.youtube.com/embed/abc", "https://www.youtube-nocookie.com/embed/x", "http://www.YouTube.com/WATCH?v=1",
        "https://music.youtube.com/watch?v=1")) {
        check(K.jeStranOgleda(u)) { "stran za ogled: $u" }
    }
    for (u in listOf("https://evil.example/watch", "https://evil.example/#/watch", "https://evil.example/x?u=youtube.com/embed",
        "https://evil.example/youtube.com/embed/x", "https://youtube.com.evil.example/watch?v=1", "https://notyoutube.com/watch",
        "https://www.24ur.com/shorts/1", "https://www.youtube.com/watchlist", "https://www.youtube.com/results?search_query=/watch",
        "https://www.youtube.com/#/watch", "https://www.youtube.com/tv#/watch?v=1", "https://www.youtube.com/", "", "javascript:alert(1)")) {
        check(!K.jeStranOgleda(u)) { "NI stran za ogled: $u" }
    }
    check(!K.jeStranOgleda(null)) { "brez naslova ni ogled" }
    // history.replaceState('#/watch') na tuji strani ne spremeni kljuca strani (nacina kazalec/puscice).
    check(K.kljucStrani(K.gostitelj("https://evil.example/"), K.jeStranOgleda("https://evil.example/")) ==
        K.kljucStrani(K.gostitelj("https://evil.example/#/watch"), K.jeStranOgleda("https://evil.example/#/watch"))) { "replaceState ne preklopi kljuca" }
    // Izbira za gostitelja velja vedno, tudi na strani za ogled; izbira na ogledu velja samo tam.
    check(K.zapomnjenaZaStran(zapisOgled = null, zapisGostitelj = true, ogled = true) == true) { "izbira gostitelja velja na ogledu" }
    check(K.zapomnjenaZaStran(zapisOgled = null, zapisGostitelj = false, ogled = true) == false) { "izbira puscic gostitelja velja na ogledu" }
    check(K.zapomnjenaZaStran(zapisOgled = false, zapisGostitelj = true, ogled = true) == false) { "izbira na ogledu ima tam prednost" }
    check(K.zapomnjenaZaStran(zapisOgled = true, zapisGostitelj = false, ogled = false) == false) { "izbira na ogledu ne velja drugje" }
    check(K.zapomnjenaZaStran(zapisOgled = null, zapisGostitelj = null, ogled = true) == null) { "brez izbire: privzeto" }
    check(!K.privzetoKazalec(true, "https://www.24ur.com/", zaTv = false,
        zapomnjeno = K.zapomnjenaZaStran(null, false, ogled = true))) { "uporabnik je izbral puscice: ostanejo tudi ob ogledu" }
    // Slepi pregled TV2 (F5): Googlove strani imajo lastne tipke za puscice (prijava, iskanje) - privzeto puscice.
    check(K.stranZaTv(ogled = false, nasaStran = false, youtubeZaTv = false, pdf = false, google = true)) { "Google: puscice" }
    check(K.stranZaTv(true, false, false, false, false) && K.stranZaTv(false, true, false, false, false) &&
        K.stranZaTv(false, false, true, false, false) && K.stranZaTv(false, false, false, true, false)) { "ogled, nasa stran, YouTube za TV, PDF: puscice" }
    check(!K.stranZaTv(false, false, false, false, false)) { "navadna stran ni za TV" }
    check(!K.privzetoKazalec(true, "https://accounts.google.com/", zaTv = K.stranZaTv(false, false, false, false, true), zapomnjeno = null)) { "Googlova prijava: puscice" }
    println("KazalecPravilaTest: OK")
}
