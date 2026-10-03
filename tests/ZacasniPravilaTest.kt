package si.safeer.tv.os

fun main() {
    val ura = 3_600_000L
    val zdaj = 1_000 * ura
    // 48 ur brez predvajanja: torrent gre. Film, ki ga prekines zvecer, naslednji vecer se caka.
    check(!ZacasniPravila.odstrani(zdaj - 26 * ura, zdaj, false))
    check(!ZacasniPravila.odstrani(zdaj - 48 * ura, zdaj, false))
    check(ZacasniPravila.odstrani(zdaj - 49 * ura, zdaj, false))
    // Za nov film manjka prostora: gredo tudi mlajsi, a ne tisti, ki ga je kdo predvajal v zadnjih urah.
    check(ZacasniPravila.odstrani(zdaj - 7 * ura, zdaj, true))
    check(!ZacasniPravila.odstrani(zdaj - 5 * ura, zdaj, true))
    check(!ZacasniPravila.odstrani(zdaj - 7 * ura, zdaj, false))
    // Ura naprave, prestavljena nazaj (zapis "iz prihodnosti"), ne odstrani nicesar.
    check(!ZacasniPravila.odstrani(zdaj + 5 * ura, zdaj, true))
    // Najdlje neuporabljeni prvi.
    check(ZacasniPravila.poVrsti(listOf(30L to "b", 10L to "a", 20L to "c")).map { it.second } == listOf("a", "c", "b"))
    // Prostor, ki ga smemo obljubiti: kar bi sprostili torrenti, ki jih ze nekaj ur nihce ne gleda.
    check(ZacasniPravila.sprostljiv(zdaj - 7 * ura, zdaj) && !ZacasniPravila.sprostljiv(zdaj - 1 * ura, zdaj))
    // Skrajsana roka (preizkus na napravi).
    check(ZacasniPravila.odstrani(zdaj - 61_000, zdaj, false, velja = 60_000, vTeku = 10_000))
    // »Obdrži« (krog 85): obdrzan prenos ne potece, ne gre ob pomanjkanju prostora in se ne steje kot sprostljiv prostor.
    check(!ZacasniPravila.odstrani(zdaj - 500 * ura, zdaj, false, obdrzan = true))
    check(!ZacasniPravila.odstrani(zdaj - 500 * ura, zdaj, true, obdrzan = true))
    check(ZacasniPravila.odstrani(zdaj - 500 * ura, zdaj, false, obdrzan = false))
    check(!ZacasniPravila.sprostljiv(zdaj - 7 * ura, zdaj, obdrzan = true))
    println("ZacasniPravilaTest: OK")
}
