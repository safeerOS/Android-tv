package si.safeer.tv.os

import java.time.LocalDateTime
import java.time.ZoneId

private var napakGalerije = 0
private fun preveriGalerijo(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napakGalerije++ }
}

fun main() {
    println("\nPreizkus pravil galerije")
    val cona = ZoneId.of("Europe/Ljubljana")
    val zdaj = LocalDateTime.of(2026, 9, 28, 12, 0).atZone(cona).toInstant().toEpochMilli()
    fun cas(dan: Int, ura: Int = 10) = LocalDateTime.of(2026, 9, dan, ura, 0).atZone(cona).toInstant().toEpochMilli()

    preveriGalerijo("danasnja slika je v Danes", GalerijaPravila.skupinaDatuma(cas(28), zdaj, cona) == GalerijaPravila.SkupinaDatuma.DANES)
    preveriGalerijo("vcerajsnja slika je v Vceraj", GalerijaPravila.skupinaDatuma(cas(27), zdaj, cona) == GalerijaPravila.SkupinaDatuma.VCERAJ)
    preveriGalerijo("starejsa slika je v datumski skupini", GalerijaPravila.skupinaDatuma(cas(26), zdaj, cona) == GalerijaPravila.SkupinaDatuma.DATUM)
    preveriGalerijo("neznan datum ima svojo skupino", GalerijaPravila.skupinaDatuma(0, zdaj, cona) == GalerijaPravila.SkupinaDatuma.BREZ_DATUMA)
    preveriGalerijo("trajanje pod uro", GalerijaPravila.trajanje(106_000) == "1:46")
    preveriGalerijo("trajanje z urami", GalerijaPravila.trajanje(3_661_000) == "1:01:01")
    preveriGalerijo("Slike izberejo mrezo", GalerijaPravila.jeMrezaPrivzeto(listOf("Slike"), listOf("folder")))
    preveriGalerijo("DCIM izbere mrezo", GalerijaPravila.jeMrezaPrivzeto(listOf("/storage/DCIM/Camera"), emptyList()))
    preveriGalerijo("vec kot polovica medijev izbere mrezo", GalerijaPravila.jeMrezaPrivzeto(emptyList(), listOf("image", "video", "file")))
    preveriGalerijo("natanko polovica ostane seznam", !GalerijaPravila.jeMrezaPrivzeto(emptyList(), listOf("image", "file")))
    preveriGalerijo("telefon ima 4 stolpce", GalerijaPravila.stolpci(411) == 4)
    preveriGalerijo("TV ima najvec 8 stolpcev", GalerijaPravila.stolpci(960) == 8)
    if (napakGalerije != 0) { println("Napak galerije: $napakGalerije"); System.exit(1) }
}
