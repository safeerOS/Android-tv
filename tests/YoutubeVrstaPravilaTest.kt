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
    // Lastnik 27. 10.: posnetek YouTube s police/iskanja ne sme iti mimo vrste na spletno stran (Naprej, konec skladbe)
    preveriEnako("glasba YouTube s police: vrsta", true, P.kotVrsta(jeYoutube = true, video = false))
    preveriEnako("video YouTube: ostane na strani", false, P.kotVrsta(jeYoutube = true, video = true))
    preveriEnako("ni YouTube: dosedanja pot", false, P.kotVrsta(jeYoutube = false, video = false))
    preveriEnako("posamezna glasba YouTube: najprej Media3", true, P.posameznoPrekMedia3(true, false, dovoliPrevzem = true))
    preveriEnako("rezerva po napaki Media3: stran, brez zanke", false, P.posameznoPrekMedia3(true, false, dovoliPrevzem = false))
    preveriEnako("posamezen video: stran", false, P.posameznoPrekMedia3(true, true, dovoliPrevzem = true))
    // Lastnik 27. 10. 13:48: tok zavrnjen (403) pri vsaki skladbi - preskakovanje brez zvoka in brez konca
    preveriEnako("403 glasba YouTube: spletna stran", true, P.tokZavrnjen(403, jeYoutube = true, video = false))
    preveriEnako("410 glasba YouTube: spletna stran", true, P.tokZavrnjen(410, jeYoutube = true, video = false))
    preveriEnako("404: ne (druga napaka)", false, P.tokZavrnjen(404, jeYoutube = true, video = false))
    preveriEnako("403 ni YouTube: ne", false, P.tokZavrnjen(403, jeYoutube = false, video = false))
    preveriEnako("prva napaka, naslednja obstaja: naprej", true, P.naprejPoNapaki(0, imaNaslednjo = true, zeliIgrati = true))
    preveriEnako("tretja napaka zapored: obstane", false, P.naprejPoNapaki(3, imaNaslednjo = true, zeliIgrati = true))
    preveriEnako("uporabnik je ustavil: ne naprej", false, P.naprejPoNapaki(0, imaNaslednjo = true, zeliIgrati = false))
    preveriEnako("brez naslednje: ne", false, P.naprejPoNapaki(0, imaNaslednjo = false, zeliIgrati = true))
    println(if (napak == 0) "VSE OK" else "NAPAK: $napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
