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
    println("KazalecPravilaTest: OK")
}
