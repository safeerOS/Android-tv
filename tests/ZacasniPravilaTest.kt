package si.safeer.tv.os

fun main() {
    val ura = 3_600_000L
    val zdaj = 1_000 * ura
    // Lastnik 25. 10. 2026: 12 ur po zadnjem predvajanju torrent gre (prej 48 ur).
    check(ZacasniPravila.VELJA_MS == 12 * ura)
    check(!ZacasniPravila.odstrani(zdaj - 11 * ura, zdaj, false))
    check(!ZacasniPravila.odstrani(zdaj - 12 * ura, zdaj, false))
    check(ZacasniPravila.odstrani(zdaj - 12 * ura - 1, zdaj, false))
    check(ZacasniPravila.odstrani(zdaj - 13 * ura, zdaj, false))
    check(ZacasniPravila.odstrani(zdaj - 26 * ura, zdaj, false))
    // Izrecno shranjeno (»Obdrži«) ostane tudi po 12 urah.
    check(!ZacasniPravila.odstrani(zdaj - 13 * ura, zdaj, false, obdrzan = true))
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
    // Grok 9, Z12: zapis iz prihodnosti velja od zdaj (potece cez 12 ur), pravi cas ostane.
    check(ZacasniPravila.popraviCas(zdaj + 5 * ura, zdaj) == zdaj)
    check(ZacasniPravila.popraviCas(zdaj - 5 * ura, zdaj) == zdaj - 5 * ura)
    // Grok 9, Z9: povezava v mapi torrenta na tujo mapo - mapa torrenta gre, tuja vsebina ostane.
    val koren = java.nio.file.Files.createTempDirectory("safeer-zacasni").toFile()
    try {
        val tuja = java.io.File(koren, "tuja").apply { mkdirs() }
        val skrivnost = java.io.File(tuja, "skrivnost.txt").apply { writeText("ostane") }
        val torrent = java.io.File(koren, "Film").apply { mkdirs() }
        java.io.File(torrent, "film.mkv").writeText("x")
        java.io.File(torrent, "Podnapisi").mkdirs()
        java.io.File(torrent, "Podnapisi/sl.srt").writeText("1")
        java.nio.file.Files.createSymbolicLink(java.io.File(torrent, "povezava").toPath(), tuja.toPath())
        java.nio.file.Files.createSymbolicLink(java.io.File(torrent, "datoteka").toPath(), skrivnost.toPath())
        check(ZacasniPravila.izbrisiBrezPovezav(torrent)) { "Z9: mapa torrenta ni izbrisana" }
        check(!torrent.exists()) { "Z9: mapa torrenta ostala" }
        check(skrivnost.isFile && skrivnost.readText() == "ostane") { "Z9: izbrisana vsebina zunaj torrenta (sledenje povezavi)" }
        check(ZacasniPravila.izbrisiBrezPovezav(java.io.File(koren, "ni-je"))) { "Z9: neobstojeca pot" }
    } finally {
        // Pospravi samo prave datoteke (morebitne povezave brez sledenja).
        ZacasniPravila.izbrisiBrezPovezav(koren)
    }
    println("ZacasniPravilaTest: OK")
}
