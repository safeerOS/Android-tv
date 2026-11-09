package si.safeer.tv

import si.safeer.tv.KazalecPravila as K

/** Lastnik 9. 11. 2026: na straneh, ki niso za TV (24ur.com), daljinec privzeto deluje kot kazalec. */
fun main() {
    check(K.privzetoKazalec(true, "https://www.24ur.com/", zaTv = false, zapomnjeno = null)) { "24ur.com na TV: kazalec" }
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
    println("KazalecPravilaTest: OK")
}
