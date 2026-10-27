package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 113: seznam YouTube na Safeer TV - prva pot Media3, spletna stran je rezerva z rokom ([YoutubeVrstaPravila]). */
fun main() {
    println("Preizkus poti posnetkov YouTube v vrsti (naloga 113)")
    val P = YoutubeVrstaPravila
    // (a) posnetek zalozbe (vgradnja zavrnjena) ne gre vec na stran: Media3 ne potrebuje vgradnje.
    preveriEnako("YouTube na tej napravi: Media3", YoutubeVrstaPravila.Pot.MEDIA3_ZVOK, P.pot(jeYoutube = true, naZvocniku = false, znanoNe = false))
    preveriEnako("YouTube na zvocniku: obstojeca pot zvocnika", YoutubeVrstaPravila.Pot.SPLETNA_STRAN, P.pot(jeYoutube = true, naZvocniku = true, znanoNe = false))
    preveriEnako("YouTube, nedavno brez toka (DRM): spletna stran", YoutubeVrstaPravila.Pot.SPLETNA_STRAN, P.pot(jeYoutube = true, naZvocniku = false, znanoNe = true))
    preveriEnako("ni YouTube: spletna stran", YoutubeVrstaPravila.Pot.SPLETNA_STRAN, P.pot(jeYoutube = false, naZvocniku = false, znanoNe = false))
    // (b) logcat 27. 10. 2026: stran m.youtube.com obvisi (state=6, position=0) - po roku je zastoj, ne vecno nalaganje.
    preveriEnako("15 s brez zacetka: zastoj", true, P.zastoj(15_000L, 0L, pripravljeno = false, zelja = true))
    preveriEnako("2 min brez zacetka: zastoj", true, P.zastoj(120_000L, 0L, pripravljeno = false, zelja = true))
    preveriEnako("pred rokom: se cakamo", false, P.zastoj(14_999L, 0L, pripravljeno = false, zelja = true))
    preveriEnako("ze igra: ni zastoj", false, P.zastoj(60_000L, 3_000L, pripravljeno = true, zelja = true))
    preveriEnako("pripravljeno na 0 (oglas, zacetek): ni zastoj", false, P.zastoj(60_000L, 0L, pripravljeno = true, zelja = true))
    preveriEnako("uporabnik je ustavil: ni zastoj", false, P.zastoj(60_000L, 0L, pripravljeno = false, zelja = false))
    preveriEnako("zastoj, naslednja obstaja: naprej", true, P.naprejPoZastoju(0, imaNaslednjo = true))
    preveriEnako("zastoj, zadnja v vrsti: obstane z obvestilom", false, P.naprejPoZastoju(0, imaNaslednjo = false))
    preveriEnako("tretji zastoj zapored: obstane", false, P.naprejPoZastoju(3, imaNaslednjo = true))
    println(if (napak == 0) "VSE OK" else "NAPAK: $napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
