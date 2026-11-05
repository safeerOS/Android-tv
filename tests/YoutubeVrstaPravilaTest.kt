package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Naloga 113: seznam YouTube na Safeer TV - prva pot Media3, spletna stran je rezerva z rokom ([YoutubeVrstaPravila]). */
fun main() {
    println("Preizkus poti posnetkov YouTube v vrsti (naloga 113)")
    val P = YoutubeVrstaPravila
    // (a) posnetek zalozbe (vgradnja zavrnjena) ne gre vec na stran: Media3 ne potrebuje vgradnje.
    preveriEnako("YouTube na tej napravi: Media3", YoutubeVrstaPravila.Pot.MEDIA3_ZVOK, P.pot(jeYoutube = true, video = false, naZvocniku = false, znanoNe = false))
    preveriEnako("YouTube na zvocniku: obstojeca pot zvocnika", YoutubeVrstaPravila.Pot.SPLETNA_STRAN, P.pot(jeYoutube = true, video = false, naZvocniku = true, znanoNe = false))
    preveriEnako("YouTube, nedavno brez toka (DRM): spletna stran", YoutubeVrstaPravila.Pot.SPLETNA_STRAN, P.pot(jeYoutube = true, video = false, naZvocniku = false, znanoNe = true))
    preveriEnako("ni YouTube: spletna stran", YoutubeVrstaPravila.Pot.SPLETNA_STRAN, P.pot(jeYoutube = false, video = false, naZvocniku = false, znanoNe = false))
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
    // Cetrti slepi pregled 5. 11. 2026 (N-E): predvajalnik v okvirju drugega izvora (CDN) igra, a ne javi polozaja.
    val zdaj = 1_000_000L
    preveriEnako("N-E: okvir drugega izvora igra (pred 1 s): ni zastoja", false,
        P.zastoj(60_000L, 0L, pripravljeno = false, zelja = true, nepreverjenoIgra = P.nepreverjenoIgra(zdaj - 1_000L, zdaj)))
    preveriEnako("N-E: znak okvirja zastarel (3 s): pravi zastoj", true,
        P.zastoj(60_000L, 0L, pripravljeno = false, zelja = true, nepreverjenoIgra = P.nepreverjenoIgra(zdaj - 3_000L, zdaj)))
    preveriEnako("N-E: nic ne igra (znaka ni bilo): pravi zastoj", true,
        P.zastoj(60_000L, 0L, pripravljeno = false, zelja = true, nepreverjenoIgra = P.nepreverjenoIgra(0L, zdaj)))
    preveriEnako("N-E: znak iz prihodnosti (ura) ne velja", false, P.nepreverjenoIgra(zdaj + 10L, zdaj))
    // Pravi zastoj se vedno preskoci z mejo (brez zanke): najvec NAJVEC_ZAPOREDNIH_ZASTOJEV zapored.
    preveriEnako("N-E: pravi zastoji: naprej 3-krat, nato obstane", listOf(true, true, true, false),
        (0..3).map { P.naprejPoZastoju(it, imaNaslednjo = true) })
    // Lastnik 27. 10.: posnetek YouTube s police/iskanja ne sme iti mimo vrste na spletno stran (Naprej, konec skladbe)
    preveriEnako("glasba YouTube s police: vrsta", true, P.kotVrsta(jeYoutube = true, video = false))
    preveriEnako("video YouTube: ostane na strani", false, P.kotVrsta(jeYoutube = true, video = true))
    preveriEnako("ni YouTube: dosedanja pot", false, P.kotVrsta(jeYoutube = false, video = false))
    preveriEnako("posamezna glasba YouTube: najprej Media3", true, P.posameznoPrekMedia3(true, false, dovoliPrevzem = true))
    preveriEnako("rezerva po napaki Media3: stran, brez zanke", false, P.posameznoPrekMedia3(true, false, dovoliPrevzem = false))
    preveriEnako("posamezen video: stran", false, P.posameznoPrekMedia3(true, true, dovoliPrevzem = true))
    // Lastnik 27. 10. 13:48: tok zavrnjen (403) pri vsaki skladbi - preskakovanje brez zvoka in brez konca
    preveriEnako("403 glasba YouTube: spletna stran", true, P.tokZavrnjen(403, jeYoutube = true))
    preveriEnako("410 glasba YouTube: spletna stran", true, P.tokZavrnjen(410, jeYoutube = true))
    preveriEnako("404: ne (druga napaka)", false, P.tokZavrnjen(404, jeYoutube = true))
    preveriEnako("403 ni YouTube: ne", false, P.tokZavrnjen(403, jeYoutube = false))
    // Zunanji pregled 3. 11. 2026: film iz vrste ne gre v Media3 kot zvok (itag 140/251), 403 odpre stran vedno.
    preveriEnako("video YouTube iz vrste: spletna stran (slika)", YoutubeVrstaPravila.Pot.SPLETNA_STRAN, P.pot(jeYoutube = true, video = true, naZvocniku = false, znanoNe = false))
    preveriEnako("403 pri posnetku YouTube: spletna stran", true, P.tokZavrnjen(403, jeYoutube = true))
    preveriEnako("vgradni okvir brez polozaja: polna stran watch", true, P.poZastojuPolnaStran(vgradno = true))
    preveriEnako("polna stran brez polozaja: naprej kot doslej", false, P.poZastojuPolnaStran(vgradno = false))
    preveriEnako("stran v ozadju stoji: premor", true, P.ozadjeStoji(zelja = true, igralObOdhodu = true, polozajObOdhodu = 61_000, polozajZdaj = 61_000, odOdhodaMs = 6_000))
    preveriEnako("stran v ozadju igra: naprej", false, P.ozadjeStoji(zelja = true, igralObOdhodu = true, polozajObOdhodu = 61_000, polozajZdaj = 66_500, odOdhodaMs = 6_000))
    preveriEnako("se nalaga ob odhodu: ni premora", false, P.ozadjeStoji(zelja = true, igralObOdhodu = false, polozajObOdhodu = 0, polozajZdaj = 0, odOdhodaMs = 9_000))
    preveriEnako("pred rokom: se ne", false, P.ozadjeStoji(zelja = true, igralObOdhodu = true, polozajObOdhodu = 61_000, polozajZdaj = 61_000, odOdhodaMs = 2_000))
    preveriEnako("uporabnik je dal pavzo: nic", false, P.ozadjeStoji(zelja = false, igralObOdhodu = true, polozajObOdhodu = 61_000, polozajZdaj = 61_000, odOdhodaMs = 6_000))
    preveriEnako("prva napaka, naslednja obstaja: naprej", true, P.naprejPoNapaki(0, imaNaslednjo = true, zeliIgrati = true))
    preveriEnako("tretja napaka zapored: obstane", false, P.naprejPoNapaki(3, imaNaslednjo = true, zeliIgrati = true))
    preveriEnako("uporabnik je ustavil: ne naprej", false, P.naprejPoNapaki(0, imaNaslednjo = true, zeliIgrati = false))
    preveriEnako("brez naslednje: ne", false, P.naprejPoNapaki(0, imaNaslednjo = false, zeliIgrati = true))
    println(if (napak == 0) "VSE OK" else "NAPAK: $napak")
    if (napak > 0) kotlin.system.exitProcess(1)
}
