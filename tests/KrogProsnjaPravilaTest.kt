package si.safeer.tv.link

import si.safeer.tv.link.KrogProsnjaPravila.Odziv

/** Preizkus [KrogProsnjaPravila]: en klik, potrditev na drugi napravi, brez zasipanja z vprasanji. */
fun main() {
    val p = KrogProsnjaPravila
    val t = 1_800_000_000_000L
    check(p.jeDejanje("circle.request") && p.jeDejanje(" Circle.Grant ")) { "obe dejanji sta prepoznani" }
    check(!p.jeDejanje("secrets.sync") && !p.jeDejanje("circle")) { "drugo dejanje ni prosnja" }

    check(p.odziv(true, false, 0, 0, t) == Odziv.VPRASAJ) { "clan kroga: vprasamo uporabnika" }
    check(p.odziv(false, false, 0, 0, t) == Odziv.ZAVRNI_NI_V_KROGU) { "naprava zunaj kroga se ne vprasa" }
    check(p.odziv(false, true, 0, 0, t) == Odziv.ZAVRNI_NI_V_KROGU) { "zunaj kroga ni »ze odprto«" }
    check(p.odziv(true, true, 0, 0, t) == Odziv.ZE_ODPRTO) { "vse odprto in kljuci potrjeni: brez novega vprasanja" }
    check(p.odziv(true, false, t - 5_000, 0, t) == Odziv.ZAVRNI_PREHITRO) { "druga prosnja v 30 s je prehitra" }
    check(p.odziv(true, false, t - 31_000, 0, t) == Odziv.VPRASAJ) { "po 30 s lahko znova" }
    check(p.odziv(true, false, t + 5_000, 0, t) == Odziv.VPRASAJ) { "ura, ki skoci nazaj, ne zaklene prosilca" }
    check(p.odziv(true, false, 0, p.NAJVEC_CAKAJOCIH, t) == Odziv.ZAVRNI_PREVEC) { "preveč cakajocih" }
    check(p.odziv(true, false, 0, p.NAJVEC_CAKAJOCIH - 1, t) == Odziv.VPRASAJ) { "se ena prosnja gre" }

    check(p.velja(t, t + 9 * 60_000L)) { "prosnja velja 10 minut" }
    check(!p.velja(t, t + p.VELJA_MS)) { "po 10 minutah ne velja vec" }
    check(!p.velja(t + 1_000, t)) { "cas iz prihodnosti ne velja" }

    check(p.smeOdgovor(t, t + 1_000)) { "odgovor na poslano prosnjo velja" }
    check(!p.smeOdgovor(null, t)) { "odgovor brez poslane prosnje se zavrne" }
    check(!p.smeOdgovor(t, t + p.VELJA_MS + 1)) { "odgovor po poteku se zavrne" }

    check(p.ponudi(true, false)) { "naprava, ki zna, je ponujena" }
    check(!p.ponudi(false, false)) { "stara naprava ni ponujena" }
    check(!p.ponudi(true, true)) { "sebi ne prosimo" }
    println("KrogProsnjaPravilaTest: VSE V REDU")
}
