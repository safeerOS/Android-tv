package si.safeer.tv.os

import si.safeer.tv.os.WebtorPravila.Izid
import si.safeer.tv.os.WebtorPravila.Kljuc
import si.safeer.tv.os.WebtorPravila.Oznaka
import si.safeer.tv.os.WebtorPravila.Vrsta

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

private fun jwt(telo: String): String {
    val e = java.util.Base64.getUrlEncoder().withoutPadding()
    return e.encodeToString("{\"alg\":\"HS256\"}".toByteArray()) + "." + e.encodeToString(telo.toByteArray()) + ".podpis"
}

/** Webtor rezerva ([WebtorPravila]): predpone, ujemanje izdaje, vrstni red, odlocitev sonde, TorBox v ozadju. */
fun main() {
    println("Preizkus pravil Webtor")
    val W = WebtorPravila
    preveriEnako("prepoznava po id manifesta", true, W.jeWebtor("org.stremio.webtor.io"))
    preveriEnako("drug dodatek ni Webtor", false, W.jeWebtor("com.stremio.torrentio.addon"))
    preveriEnako("brez id", false, W.jeWebtor(null))

    preveriEnako("[⚡TB]", Oznaka(Vrsta.TORBOX, "Torrentio\n4k"), W.razcleni("[⚡TB] Torrentio\n4k"))
    preveriEnako("[⚡WT]", Oznaka(Vrsta.PREDPOMNJEN, "Torrentio 1080p"), W.razcleni("[⚡WT] Torrentio 1080p"))
    preveriEnako("[⚡️WT] z izbirnikom", Oznaka(Vrsta.PREDPOMNJEN, "X"), W.razcleni("[⚡️WT] X"))
    preveriEnako("[WT]", Oznaka(Vrsta.PRENOS, "Torrentio 720p"), W.razcleni("[WT] Torrentio 720p"))
    preveriEnako("[wt] male crke", Oznaka(Vrsta.PRENOS, "x"), W.razcleni("[wt]x"))
    preveriEnako("[P2P]", Oznaka(Vrsta.P2P, "Torrentio"), W.razcleni("[P2P] Torrentio"))
    preveriEnako("brez predpone", Oznaka(Vrsta.PRENOS, "Torrentio"), W.razcleni(" Torrentio "))
    preveriEnako("predpona sredi imena ni predpona", Oznaka(Vrsta.PRENOS, "Film [WT]"), W.razcleni("Film [WT]"))
    preveriEnako("vrsta iz imena", Vrsta.TORBOX, W.vrstaIz("TORBOX"))
    preveriEnako("vrsta neznana", Vrsta.PRENOS, W.vrstaIz(""))

    preveriEnako("skrij TB, ce imamo TorBox", true, W.skrij(Vrsta.TORBOX, true))
    preveriEnako("TB ostane brez nasega TorBoxa", false, W.skrij(Vrsta.TORBOX, false))
    preveriEnako("skrij P2P", true, W.skrij(Vrsta.P2P, false))
    preveriEnako("WT ostane", false, W.skrij(Vrsta.PRENOS, true))

    val a = "a".repeat(40); val b = "b".repeat(40); val c = "c".repeat(40); val d = "d".repeat(40); val e = "e".repeat(40)
    preveriEnako("kljuc: male crke", Kljuc(a, 2), W.kljuc("A".repeat(40), 2))
    preveriEnako("kljuc: -1 za neznano datoteko", Kljuc(a, -1), W.kljuc(a, null))
    preveriEnako("kljuc: base32 ni hex", null, W.kljuc("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567", 0))
    preveriEnako("JWT: hash in idx", Kljuc(a, 3), W.izJwt("https://webtor.io/safeer-skrito-0123456789abcdef/stremio/resolve/" + jwt("{\"hash\":\"$a\",\"idx\":3,\"exp\":1}")))
    preveriEnako("JWT: brez idx", Kljuc(b, -1), W.izJwt("https://x/resolve/" + jwt("{\"hash\":\"$b\"}") + "?q=1"))
    preveriEnako("JWT: ni JWT", null, W.izJwt("https://x/resolve/abc"))
    preveriEnako("JWT: ni resolve", null, W.izJwt("https://x/stream/" + jwt("{\"hash\":\"$b\"}")))

    val nasi = listOf(Kljuc(a, 0), Kljuc(b, 0), null, Kljuc(c, 1))
    val wt = listOf(Kljuc(c, 1) to Vrsta.PRENOS, Kljuc(d, 0) to Vrsta.PREDPOMNJEN, Kljuc(a, 0) to Vrsta.TORBOX, Kljuc(b, 0) to Vrsta.PRENOS,
        Kljuc(e, 0) to Vrsta.PRENOS, null to Vrsta.PREDPOMNJEN, Kljuc(e, 1) to Vrsta.P2P)
    preveriEnako("vrstni red: nasi najprej (brez TB, ce imamo TorBox), nato ⚡WT, nato WT; P2P izpade", listOf(3, 0, 1, 5, 4), W.vrstniRed(nasi, wt, true))
    preveriEnako("vrstni red: brez nasega TorBoxa ostane TB", listOf(2, 3, 0, 1, 5, 4), W.vrstniRed(nasi, wt, false))
    preveriEnako("druga datoteka istega torrenta ni ista izdaja", listOf(0), W.vrstniRed(listOf(Kljuc(a, 1)), listOf(Kljuc(a, 2) to Vrsta.PRENOS), true))
    preveriEnako("prazno", emptyList<Int>(), W.vrstniRed(nasi, emptyList(), true))
    preveriEnako("za izdajo", 3, W.zaIzdajo(Kljuc(b, 0), wt, true))
    preveriEnako("za izdajo: TB skrit", -1, W.zaIzdajo(Kljuc(a, 0), wt, true))
    preveriEnako("za izdajo: ni", -1, W.zaIzdajo(Kljuc(e, 5), wt, true))
    preveriEnako("za izdajo: brez kljuca", -1, W.zaIzdajo(null, wt, true))

    preveriEnako("sonda 206 hitro", Izid.PREDVAJAJ, W.odlocitev(206, 300, false))
    preveriEnako("sonda 200 na meji", Izid.PREDVAJAJ, W.odlocitev(200, 15_000, false))
    preveriEnako("sonda prvi bajt po 15 s", Izid.PREPOCASI, W.odlocitev(206, 15_001, false))
    preveriEnako("sonda rok potekel", Izid.PREPOCASI, W.odlocitev(0, null, true))
    preveriEnako("402 ni paketa", Izid.NI_PAKETA, W.odlocitev(402, null, false))
    preveriEnako("404 ni toka", Izid.NI_TOKA, W.odlocitev(404, null, false))
    preveriEnako("429 omejitev ali drug IP", Izid.OMEJITEV, W.odlocitev(429, null, true))
    preveriEnako("500 napaka", Izid.NAPAKA, W.odlocitev(500, null, false))
    preveriEnako("206 brez bajta", Izid.NAPAKA, W.odlocitev(206, null, false))
    preveriEnako("brez odgovora", Izid.NAPAKA, W.odlocitev(0, null, false))

    preveriEnako("TorBox v ozadju: da", true, W.dodajVTorBox(true, true, false, a))
    preveriEnako("TorBox v ozadju: ze pripravljen", false, W.dodajVTorBox(true, true, true, a))
    preveriEnako("TorBox v ozadju: brez kljuca", false, W.dodajVTorBox(false, true, false, a))
    preveriEnako("TorBox v ozadju: Brez debrida", false, W.dodajVTorBox(true, false, false, a))
    preveriEnako("TorBox v ozadju: brez hasha", false, W.dodajVTorBox(true, true, false, null))
    preveriEnako("magnet", "magnet:?xt=urn:btih:$a", W.magnet("A".repeat(40)))

    if (napak == 0) println("WebtorPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
