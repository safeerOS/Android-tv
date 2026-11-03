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
    println("TvTipkeTest: OK")
}
