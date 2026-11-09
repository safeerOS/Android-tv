package si.safeer.tv

import si.safeer.tv.KazalecPravila as K

/** Lastnik 9. 11. 2026: na straneh, ki niso za TV (24ur.com), daljinec privzeto deluje kot kazalec. */
fun main() {
    // Lastnik 9. 11. 2026 (RTV SLO kot aplikacija): privzeto puscice kot v aplikaciji, kazalec samo za strani brez daljinca.
    check(!K.privzetoKazalec(true, "https://www.24ur.com/", zaTv = false, zapomnjeno = null)) { "24ur.com na TV: puscice" }
    check(!K.privzetoKazalec(true, "https://www.rtvslo.si/", zaTv = false, zapomnjeno = null)) { "rtvslo.si na TV: puscice" }
    check(K.privzetoKazalec(true, "https://www.facebook.com/", zaTv = false, zapomnjeno = null, miska = true)) { "Facebook: kazalec" }
    check(K.privzetoKazalec(true, "https://www.24ur.com/", zaTv = false, zapomnjeno = true)) { "uporabnik je izbral kazalec" }
    check(!K.privzetoKazalec(true, "https://www.youtube.com/tv", zaTv = true, zapomnjeno = null)) { "YouTube za TV: puscice" }
    check(!K.privzetoKazalec(true, "file:///android_asset/home.html", zaTv = false, zapomnjeno = null)) { "nasa domaca stran: puscice" }
    check(!K.privzetoKazalec(false, "https://www.24ur.com/", zaTv = false, zapomnjeno = null)) { "telefon/tablica: brez kazalca" }
    check(!K.privzetoKazalec(true, "https://www.24ur.com/", zaTv = false, zapomnjeno = false)) { "uporabnik je za gostitelja izbral puscice" }
    check(K.privzetoKazalec(true, "https://safeer.si/", zaTv = true, zapomnjeno = true)) { "uporabnik je izbral kazalec tudi na strani za TV" }
    check(K.gostitelj("https://WWW.24ur.com./clanek") == "www.24ur.com") { "gostitelj: malo, brez pike" }
    check(K.gostitelj("javascript:alert(1)") == null) { "samo http(s)" }
    // Slepi pregled 9. 11. 2026: privzeti kazalec na strani, ki ni za TV, se po 30 s ne skrije sam.
    check(!K.samSkrij(trajen = false, vlecem = false, privzet = true)) { "privzet kazalec: brez samodejnega skrivanja" }
    check(K.samSkrij(trajen = false, vlecem = false, privzet = false)) { "rocno vklopljen kazalec: skrije se po 30 s" }
    check(!K.samSkrij(trajen = true, vlecem = false, privzet = false)) { "spletna aplikacija: trajen" }
    check(!K.samSkrij(trajen = false, vlecem = true, privzet = false)) { "med vlecenjem ne" }
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
    println("KazalecPravilaTest: OK")
}
