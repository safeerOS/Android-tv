package si.safeer.tv

import si.safeer.tv.KazalecOblika as O

/** Lastnik 15. 11. 2026: kazalec je puscica, tocka klika je njena konica; berljivost, stanja, rob okna. */
fun main() {
    // Tocka klika: konica puscice, ne sredina slike (stari krog: sredina).
    val (kx, ky) = O.tockaKlika(300f, 200f, O.Oblika.PUSCICA)
    check(kx == 300f && ky == 200f) { "puscica: tocka klika je konica (zgornji levi kot slike), ne sredina: $kx,$ky" }
    val (sx, sy) = O.tockaKlika(300f, 200f, O.Oblika.KROG)
    check(sx == 300f + O.PREMER_KROGA_PX / 2f && sy == 200f + O.PREMER_KROGA_PX / 2f) { "krog: tocka klika je sredina" }
    check(O.PRIVZETA == O.Oblika.PUSCICA) { "privzeta oblika je puscica" }
    for (obl in O.Oblika.values()) {
        val (ix, iy) = O.slikaIzTocke(640f, 360f, obl)
        val (bx, by) = O.tockaKlika(ix, iy, obl)
        check(bx == 640f && by == 360f) { "slikaIzTocke in tockaKlika sta inverzni ($obl)" }
    }
    // Konture: konica v (0,0), vse ostale tocke desno/navzdol od nje (telo), visina = VISINA_PX.
    val t = O.tocke()
    check(t[0] == 0f && t[1] == 0f) { "prva tocka konture je konica (0,0)" }
    var najX = 0f; var najY = 0f
    for (i in t.indices step 2) { check(t[i] >= 0f && t[i + 1] >= 0f) { "telo puscice je desno in pod konico" }; najX = maxOf(najX, t[i]); najY = maxOf(najY, t[i + 1]) }
    check(Math.abs(najY - O.VISINA_PX) < 0.5f) { "visina konture je VISINA_PX: $najY" }
    check(Math.abs(najX - O.SIRINA_PX) < 1.5f) { "sirina konture je SIRINA_PX: $najX" }
    // Velikost: ne manjsa od sedanjega kroga (zunanji premer 39 px = 19,5 dp pri 960 dp).
    check(O.VISINA_PX >= O.PREMER_KROGA_PX) { "puscica ni nizja od sedanjega kroga" }
    check(O.VISINA_PX / 2f >= 20f) { "visina vsaj 20 dp pri 960 dp (gostota 2)" }
    // Rob: konica je lahko natancno na robu, telo ostane vidno (zrcaljenje).
    check(O.omejiKonico(-50f, 1920) == 0f) { "levi rob: konica na 0" }
    check(O.omejiKonico(5000f, 1920) == 1919f) { "desni rob: konica na zadnji tocki zaslona" }
    check(O.omejiKonico(5f, 1920) == 5f) { "brez odmika 10 px od roba (prej 10)" }
    check(O.obrat(1919f, 500f, 1920, 1080) == O.Obrat(vodoravno = true, navpicno = false)) { "desni rob: telo zrcaljeno vstran" }
    check(O.obrat(500f, 1079f, 1920, 1080) == O.Obrat(vodoravno = false, navpicno = true)) { "spodnji rob: telo zrcaljeno navzgor" }
    check(O.obrat(1919f, 1079f, 1920, 1080) == O.Obrat(true, true)) { "spodnji desni kot: oboje" }
    check(O.obrat(0f, 0f, 1920, 1080) == O.Obrat(false, false)) { "zgornji levi kot: navadno" }
    check(O.obrat(1920f - O.SIRINA_PX, 100f, 1920, 1080) == O.Obrat(false, false)) { "telo ravno se vidno: brez zrcaljenja" }
    // Telo zrcaljene puscice (konica na robu) je cisto znotraj zaslona.
    run {
        val o = O.obrat(1919f, 1079f, 1920, 1080)
        val levo = if (o.vodoravno) 1919f - O.SIRINA_PX else 1919f
        val zgoraj = if (o.navpicno) 1079f - O.VISINA_PX else 1079f
        check(levo >= 0f && zgoraj >= 0f) { "zrcaljeno telo ostane na zaslonu" }
    }
    // Stanja ostanejo razlocljiva: navaden, zgrabljen (vlecenje), klik (kratek signal).
    check(O.stanje(false, 0L, 1000L) == O.Stanje.NAVADEN) { "navaden" }
    check(O.stanje(true, 0L, 1000L) == O.Stanje.ZGRABLJEN) { "zgrabljen med vlecenjem" }
    check(O.stanje(false, 1100L, 1000L) == O.Stanje.KLIK) { "klik: kratek signal" }
    check(O.stanje(false, 1100L, 1100L) == O.Stanje.NAVADEN) { "po izteku signala spet navaden" }
    check(O.stanje(true, 1100L, 1000L) == O.Stanje.ZGRABLJEN) { "zgrabljen ima prednost pred klikom" }
    val iz = O.Stanje.values().map { O.izgled(it) }
    check(iz.toSet().size == 3) { "vsa tri stanja imajo razlicen izgled" }
    check(iz.map { it.polnitev }.toSet().size == 3) { "polnitev se razlikuje med stanji (ne samo merilo)" }
    check(O.izgled(O.Stanje.ZGRABLJEN).merilo > O.izgled(O.Stanje.NAVADEN).merilo && O.izgled(O.Stanje.KLIK).merilo < O.izgled(O.Stanje.NAVADEN).merilo) { "velikost loci zgrabljen in klik" }
    // Vidnost na svetli in temni strani: polnitev proti obrisu, obris proti beli in proti crni strani.
    val beli = 0xFFFFFFFF.toInt(); val crni = 0xFF000000.toInt()
    for (s in O.Stanje.values()) {
        val i = O.izgled(s)
        check(O.kontrast(i.polnitev, i.obris) >= 4.5) { "$s: polnitev proti obrisu kontrast ${O.kontrast(i.polnitev, i.obris)}" }
    }
    val n = O.izgled(O.Stanje.NAVADEN)
    check(O.kontrast(n.obris, beli) >= 4.5) { "temen obris je viden na beli strani" }
    check(O.kontrast(n.polnitev, crni) >= 4.5) { "bela polnitev je vidna na crni strani" }
    println("KazalecOblikaTest: OK")
}
