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
    println("TvTipkeTest: OK")
}
