package si.safeer.tv

import si.safeer.tv.TvTipke.Dejanje

/** Barvni gumbi v brskalniku (TV 3. 11. 2026: zeleni gumb vzame sistem, kazalca ni bilo mogoce vklopiti). */
fun main() {
    check(TvTipke.dejanje(183) == Dejanje.KAZALEC)   // rdeci: prej hitre strani
    check(TvTipke.dejanje(184) == Dejanje.KAZALEC)   // zeleni
    check(TvTipke.dejanje(165) == Dejanje.KAZALEC)   // INFO
    check(TvTipke.dejanje(82) == Dejanje.HITRE_STRANI)
    check(TvTipke.dejanje(185) == Dejanje.ZAZNAMKI)
    check(TvTipke.dejanje(19) == Dejanje.NIC)
    // Drzan OK vklopi kazalec samo enkrat (ob tocno doloceni ponovitvi), samo na strani in ko ga se ni.
    check(TvTipke.drzanOkVkljuciKazalec(23, TvTipke.PONOVITEV_ZA_KAZALEC, kazalecViden = false, vVrstici = false))
    check(!TvTipke.drzanOkVkljuciKazalec(23, 0, kazalecViden = false, vVrstici = false))
    check(!TvTipke.drzanOkVkljuciKazalec(23, TvTipke.PONOVITEV_ZA_KAZALEC + 1, kazalecViden = false, vVrstici = false))
    check(!TvTipke.drzanOkVkljuciKazalec(23, TvTipke.PONOVITEV_ZA_KAZALEC, kazalecViden = true, vVrstici = false))
    check(!TvTipke.drzanOkVkljuciKazalec(23, TvTipke.PONOVITEV_ZA_KAZALEC, kazalecViden = false, vVrstici = true))
    check(!TvTipke.drzanOkVkljuciKazalec(19, TvTipke.PONOVITEV_ZA_KAZALEC, kazalecViden = false, vVrstici = false))
    // GOR v naslovno vrstico (zunanji pregled fokusa 3. 11. 2026): v nacinu aplikacije med nalaganjem ne.
    check(TvTipke.gorVNaslovnoVrstico("-1", nacinAplikacije = true, napredek = 100))
    check(!TvTipke.gorVNaslovnoVrstico("1", nacinAplikacije = true, napredek = 100))
    check(!TvTipke.gorVNaslovnoVrstico("0", nacinAplikacije = false, napredek = 100))
    check(!TvTipke.gorVNaslovnoVrstico("null", nacinAplikacije = true, napredek = 20))    // prej: aplikacija zapuscena
    check(!TvTipke.gorVNaslovnoVrstico(null, nacinAplikacije = true, napredek = 99))
    check(TvTipke.gorVNaslovnoVrstico("null", nacinAplikacije = true, napredek = 100))    // stran brez skripte: ni ujet
    check(TvTipke.gorVNaslovnoVrstico("null", nacinAplikacije = false, napredek = 20))    // brskalnik: vrstica kot prej
    // OK na polju za datoteko (TV 4. 11. 2026): Enter iz aplikacije samo na odgovor nasega skripta.
    // Drugi krog pregleda 5. 11. 2026: golo "datoteka" lahko vrne stran, ki je funkcijo zasedla pred nami - ne velja.
    // Vrednosti spodaj je izracunal tv_spatial.js (dokazDatoteke; isti primer v tests/tv_pregled_fokusa_test.js).
    val z = "z".repeat(48)
    val izziv = "0123456789abcdef0123456789abcdef"
    check(TvTipke.odtisZetona(z, "datoteka|$izziv") == "safeer-cg41mu") { "odtis ni enak kot v tv_spatial.js" }
    check(TvTipke.odtisZetona("a1".repeat(24), "datoteka|ffffffffffffffff") == "safeer-i7jcly") { "odtis ni enak kot v tv_spatial.js (2)" }
    check(TvTipke.okNaDatoteki("\"datoteka:safeer-cg41mu\"", z, izziv))      // evaluateJavascript vrne niz v narekovajih
    check(TvTipke.okNaDatoteki("datoteka:safeer-cg41mu", z, izziv))
    check(!TvTipke.okNaDatoteki("\"datoteka\"", z, izziv) && !TvTipke.okNaDatoteki("datoteka", z, izziv))   // stran brez zetona
    check(!TvTipke.okNaDatoteki("datoteka:safeer-cg41mu", z, "ffffffffffffffffffffffffffffffff"))       // odgovor drugega izziva
    check(!TvTipke.okNaDatoteki("datoteka:safeer-cg41mu", "y".repeat(48), izziv))                       // drug zeton (nova stran)
    check(!TvTipke.okNaDatoteki("datoteka:safeer-cg41mu", z.take(31), izziv))                           // prekratek zeton
    check(!TvTipke.okNaDatoteki("true", z, izziv) && !TvTipke.okNaDatoteki("false", z, izziv))   // gumb, povezava: klik skripta
    check(!TvTipke.okNaDatoteki(null, z, izziv) && !TvTipke.okNaDatoteki("null", z, izziv) && !TvTipke.okNaDatoteki("", z, izziv))
    // Pregled Groka 5. 11. 2026: dokaz tipke in znak namestitve - iste vrednosti kot tv_spatial.js (tests/tv_pregled_groka_test.js).
    check(TvTipke.odtisStevilo(z, "tipka|$izziv") == 2855865788L) { "odtis tipke ni enak kot v tv_spatial.js" }
    check(TvTipke.znakNamestitve(z) == "safeer-pjd3yd") { "znak namestitve ni enak kot v tv_spatial.js" }
    val ukaz = TvTipke.ukazTipke(z)
    val um = Regex("window\\._safeer_tipka&&window\\._safeer_tipka\\('([0-9a-f]{32})',(\\d+)\\);").matchEntire(ukaz)
    check(um != null && um.groupValues[2].toLong() == TvTipke.odtisStevilo(z, "tipka|" + um.groupValues[1])) { "ukaz tipke: $ukaz" }
    check(TvTipke.ukazTipke(null) == "" && TvTipke.ukazTipke(z.take(31)) == "") { "brez zetona ni dokaza" }
    val i1 = TvTipke.novIzziv(); val i2 = TvTipke.novIzziv()
    check(i1.length == 32 && i1 != i2 && i1.all { it in "0123456789abcdef" })
    println("TvTipkeTest: OK")
}
