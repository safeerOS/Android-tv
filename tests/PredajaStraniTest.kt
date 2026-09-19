package si.safeer.tv

/**
 * Rezerva WebView: tok, ki ga domaci predvajalnik ne zmore, gre strani - in to se ne sme vrteti v
 * zanki, tudi ce CDN ob vsakem nalaganju strani vrne drugacen naslov manifesta.
 */

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

/** Kot v DashPrevzem: poizvedba (zeton) ne steje, pot pa. */
private fun brezPoizvedbe(a: String, b: String): Boolean =
    a.substringBefore('?').lowercase() == b.substringBefore('?').lowercase()

private fun preizkusIstegaToka() {
    println("\n== isti tok ==")
    val p = PredajaStrani(::brezPoizvedbe)
    val mpd = "https://cdn.primer.si/v/film/manifest.mpd?token=a1"
    preveri("pred predajo gre tok domacemu predvajalniku", !p.jePrepuscen(mpd))
    p.prepusti(mpd)
    preveri("po predaji isti naslov ostane strani", p.jePrepuscen(mpd))
    preveri("nov zeton v poizvedbi je isti tok", p.jePrepuscen("https://cdn.primer.si/v/film/manifest.mpd?token=b2"))
    preveri("drug film na istem izvoru se poskusi domace", !p.jePrepuscen("https://cdn.primer.si/v/drug/manifest.mpd"))
}

private fun preizkusZanke() {
    println("\n== CDN ob vsakem nalaganju da nov naslov (najslabsi primer) ==")
    // Primerjava ne prepozna nicesar kot isti tok - varnost mora zdrzati tudi tako.
    val p = PredajaStrani { _, _ -> false }
    var nalaganj = 0
    var i = 0
    while (i < 20) {
        val mpd = "https://cdn.primer.si/seja-$i/manifest.mpd"
        if (p.jePrepuscen(mpd)) break
        p.prepusti(mpd)      // domaci predvajalnik je padel: stran se nalozi znova
        nalaganj++
        i++
    }
    preveri("stran se nalozi znova najvec ${PredajaStrani.NAJVEC}-krat (dobljeno $nalaganj)", nalaganj == PredajaStrani.NAJVEC)
    preveri("potem stran predvaja vse sama", p.jePrepuscen("https://cdn.primer.si/nekaj-novega.mpd"))
}

private fun preizkusIzvora() {
    println("\n== drug izvor ==")
    val p = PredajaStrani(::brezPoizvedbe)
    p.prepusti("https://a.si/1.mpd"); p.prepusti("https://a.si/2.mpd")
    preveri("po dveh predajah je vse prepusceno", p.jePrepuscen("https://a.si/3.mpd"))
    p.pocisti()
    preveri("na drugem izvoru spet domaci predvajalnik", !p.jePrepuscen("https://b.si/1.mpd"))
    preveri("stevec predaj je na nic", p.predaj == 0)
}

fun main() {
    preizkusIstegaToka()
    preizkusZanke()
    preizkusIzvora()
    if (napak > 0) {
        println("\nNAPAK: $napak")
        kotlin.system.exitProcess(1)
    }
    println("\nVse v redu.")
}
