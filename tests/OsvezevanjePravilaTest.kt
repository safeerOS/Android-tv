package si.safeer.tv.os

private var napak = 0
private fun enako(opis: String, pricakovano: Any?, dobljeno: Any?) {
    if (pricakovano == dobljeno) println("  ok  $opis")
    else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }
}

/** Lastnik 13. 11. 2026: ujemanje osvezevanja zaslona s filmom (24p brez trzanja). */
fun main() {
    val N = OsvezevanjePravila
    val m60 = OsvezevanjePravila.Nacin(1, 3840, 2160, 60f)
    val m5994 = OsvezevanjePravila.Nacin(2, 3840, 2160, 59.94f)
    val m24 = OsvezevanjePravila.Nacin(3, 3840, 2160, 24f)
    val m23976 = OsvezevanjePravila.Nacin(4, 3840, 2160, 23.976f)
    val m50 = OsvezevanjePravila.Nacin(5, 3840, 2160, 50f)
    val fhd24 = OsvezevanjePravila.Nacin(6, 1920, 1080, 23.976f)
    val vsi = listOf(m60, m5994, m24, m23976, m50, fhd24)
    enako("23,976 fps: tocni 23,976 Hz", m23976, N.izberi(vsi, m60, 23.976f))
    enako("24 fps: 24 Hz", m24, N.izberi(vsi, m60, 24f))
    enako("25 fps: 50 Hz (dvakratnik)", m50, N.izberi(vsi, m60, 25f))
    enako("29,97 fps: 59,94 Hz", m5994, N.izberi(vsi, m60, 29.97f))
    enako("ze ustreza: brez preklopa", null, N.izberi(vsi, m23976, 23.976f))
    enako("60 fps na 60 Hz: brez preklopa", null, N.izberi(vsi, m60, 60f))
    enako("30 fps na 60 Hz: ze brez trzanja, brez preklopa", null, N.izberi(vsi + OsvezevanjePravila.Nacin(7, 3840, 2160, 30f), m60, 30f))
    enako("druga locljivost se ne izbere", null, N.izberi(listOf(m60, fhd24), m60, 23.976f))
    enako("brez primernega nacina: ostane", null, N.izberi(listOf(m60), m60, 23.976f))
    enako("neznana hitrost (0): ostane", null, N.izberi(vsi, m60, 0f))
    enako("120 fps je veljavna hitrost", true, N.veljavnaHitrost(120f))
    enako("500 fps ni veljavna hitrost", false, N.veljavnaHitrost(500f))
    enako("neznana hitrost (Format.NO_VALUE)", false, N.veljavnaHitrost(-1f))
    // N8: 23,976 na 24 Hz: obstaja tocni 23,976 -> preklop nanj; brez tocnega 24 Hz ostane.
    enako("23,976 na 24 Hz: tocni nacin ima prednost", m23976, N.izberi(vsi, m24, 23.976f))
    enako("23,976 na 24 Hz brez tocnega: ostane", null, N.izberi(listOf(m60, m24), m24, 23.976f))
    enako("23,976 na 60 Hz brez tocnega: 24 Hz (priblizno)", m24, N.izberi(listOf(m60, m24), m60, 23.976f))
    if (napak > 0) { println("OsvezevanjePravila: $napak napak"); kotlin.system.exitProcess(1) }
    println("OsvezevanjePravila: vse ok")
}
