package si.safeer.tv.os

import si.safeer.tv.os.WebtorPravila.Izid
import si.safeer.tv.os.WebtorPravila.Kljuc
import si.safeer.tv.os.WebtorPravila.Korak
import si.safeer.tv.os.WebtorPravila.Oznaka
import si.safeer.tv.os.WebtorPravila.Takoj
import si.safeer.tv.os.WebtorPravila.Vrsta

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

private fun jwt(telo: String): String {
    val e = java.util.Base64.getUrlEncoder().withoutPadding()
    return e.encodeToString("{\"alg\":\"HS256\"}".toByteArray()) + "." + e.encodeToString(telo.toByteArray()) + ".podpis"
}

/** Webtor rezerva ([WebtorPravila]): prepoznava, ucenje Scita, predpone, izdaja, vrstni red, sonda, TorBox v ozadju. */
fun main() {
    println("Preizkus pravil Webtor")
    val W = WebtorPravila
    // --- V1: prepoznava dodatka in ucenje gostitelja
    preveriEnako("id + webtor.io", true, W.jeWebtorDodatek("org.stremio.webtor.io", "https://webtor.io/safeer-skrito-0123456789abcdef"))
    preveriEnako("id + webtor.io (pravi naslov)", true, W.jeWebtorDodatek("org.stremio.webtor.io", "https://webtor.io/token/x1/stremio"))
    preveriEnako("lazen id na drugem gostitelju", false, W.jeWebtorDodatek("org.stremio.webtor.io", "https://evil.example/stremio"))
    preveriEnako("lazen id na poddomeni", false, W.jeWebtorDodatek("org.stremio.webtor.io", "https://x.webtor.io.evil.example/a"))
    preveriEnako("lazen id na podobni domeni", false, W.jeWebtorDodatek("org.stremio.webtor.io", "https://evilwebtor.io/a"))
    preveriEnako("brez https", false, W.jeWebtorDodatek("org.stremio.webtor.io", "http://webtor.io/token/x/stremio"))
    preveriEnako("webtor.io z drugim id", false, W.jeWebtorDodatek("com.stremio.torrentio.addon", "https://webtor.io/x"))
    val resolve = "https://webtor.io/token/x1/stremio/resolve/a.b.c"
    preveriEnako("uci se: resolve -> 206", "abra--1.api.cosmic-crab.buzz", W.naucenGostitelj(resolve, "https://abra--1.api.cosmic-crab.buzz/f.mkv", 206))
    preveriEnako("ne uci se: zacetek ni webtor.io", null, W.naucenGostitelj("https://evil.example/stremio/resolve/a", "https://tok.example.net/f", 206))
    preveriEnako("ne uci se: zacetek ni /resolve/", null, W.naucenGostitelj("https://webtor.io/token/x/stremio/stream/movie/tt1.json", "https://tok.example.net/f", 200))
    preveriEnako("ne uci se: 302 na oglasno domeno brez 2xx", null, W.naucenGostitelj(resolve, "https://ads.doubleclick.net/x", 302))
    preveriEnako("ne uci se: 404", null, W.naucenGostitelj(resolve, "https://tok.example.net/f", 404))
    preveriEnako("ne uci se: koncni brez https", null, W.naucenGostitelj(resolve, "http://tok.example.net/f", 206))
    preveriEnako("ne uci se: koncni je webtor.io", null, W.naucenGostitelj(resolve, "https://webtor.io/x", 200))
    preveriEnako("gostitelj", "a.example.net", W.gostitelj("https://u@A.Example.net:443/x?y"))

    // --- predpone
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
    preveriEnako("⚡WT je takoj", true, W.takoj(Vrsta.PREDPOMNJEN))
    preveriEnako("WT ni takoj", false, W.takoj(Vrsta.PRENOS))

    // --- izdaja
    val a = "a".repeat(40); val b = "b".repeat(40); val c = "c".repeat(40); val d = "d".repeat(40); val e = "e".repeat(40)
    preveriEnako("kljuc: male crke", Kljuc(a, 2), W.kljuc("A".repeat(40), 2))
    preveriEnako("kljuc: -1 za neznano datoteko", Kljuc(a, -1), W.kljuc(a, null))
    preveriEnako("kljuc: base32 ni hex", null, W.kljuc("ABCDEFGHIJKLMNOPQRSTUVWXYZ234567", 0))
    preveriEnako("JWT: hash in idx", Kljuc(a, 3), W.izJwt("https://webtor.io/safeer-skrito-0123456789abcdef/resolve/" + jwt("{\"hash\":\"$a\",\"idx\":3,\"exp\":1}")))
    preveriEnako("JWT: brez idx", Kljuc(b, -1), W.izJwt("https://x/resolve/" + jwt("{\"hash\":\"$b\"}") + "?q=1"))
    preveriEnako("JWT: ni JWT", null, W.izJwt("https://x/resolve/abc"))
    preveriEnako("JWT: ni resolve", null, W.izJwt("https://x/stream/" + jwt("{\"hash\":\"$b\"}")))

    // --- vrstni red tokov Webtor
    val nasi = listOf(Kljuc(a, 0), Kljuc(b, 0), null, Kljuc(c, 1))
    val wt = listOf(Kljuc(c, 1) to Vrsta.PRENOS, Kljuc(d, 0) to Vrsta.PREDPOMNJEN, Kljuc(a, 0) to Vrsta.TORBOX, Kljuc(b, 0) to Vrsta.PRENOS,
        Kljuc(e, 0) to Vrsta.PRENOS, null to Vrsta.PREDPOMNJEN, Kljuc(e, 1) to Vrsta.P2P)
    preveriEnako("vrstni red: nasi najprej (brez TB, ce imamo TorBox), nato takoj, nato WT; P2P izpade", listOf(3, 0, 1, 5, 4), W.vrstniRed(nasi, wt, true))
    preveriEnako("vrstni red: brez nasega TorBoxa ostane TB", listOf(2, 3, 0, 1, 5, 4), W.vrstniRed(nasi, wt, false))
    preveriEnako("samo takoj (korak ⚡)", listOf(1, 5), W.vrstniRed(nasi, wt, true, samoTakoj = true))
    preveriEnako("druga datoteka istega torrenta ni ista izdaja", listOf(0), W.vrstniRed(listOf(Kljuc(a, 1)), listOf(Kljuc(a, 2) to Vrsta.PRENOS), true))
    preveriEnako("prazno", emptyList<Int>(), W.vrstniRed(nasi, emptyList(), true))
    preveriEnako("za izdajo", 3, W.zaIzdajo(Kljuc(b, 0), wt, true))
    preveriEnako("za izdajo: TB skrit", -1, W.zaIzdajo(Kljuc(a, 0), wt, true))
    preveriEnako("za izdajo: ni", -1, W.zaIzdajo(Kljuc(e, 5), wt, true))
    preveriEnako("za izdajo: brez kljuca", -1, W.zaIzdajo(null, wt, true))

    // --- sonda
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
    preveriEnako("rok prve sonde", 15_000L, W.rokSonde(null, 100, 0))
    preveriEnako("rok druge sonde: ostanek skupnega", 5_000L, W.rokSonde(100, 10_100, 1))
    preveriEnako("rok porabljen", 0L, W.rokSonde(100, 20_000, 1))
    preveriEnako("najvec 2 sondi", 0L, W.rokSonde(100, 200, 2))

    // --- TorBox v ozadju in magnet
    preveriEnako("TorBox v ozadju: da", true, W.dodajVTorBox(true, true, false, a))
    preveriEnako("TorBox v ozadju: ze pripravljen", false, W.dodajVTorBox(true, true, true, a))
    preveriEnako("TorBox v ozadju: brez kljuca", false, W.dodajVTorBox(false, true, false, a))
    preveriEnako("TorBox v ozadju: Brez debrida", false, W.dodajVTorBox(true, false, false, a))
    preveriEnako("TorBox v ozadju: brez hasha", false, W.dodajVTorBox(true, true, false, null))
    preveriEnako("magnet s sledilniki toka", "magnet:?xt=urn:btih:$a&tr=udp%3A%2F%2Ft.example%3A80",
        W.magnet("A".repeat(40), listOf("tracker:udp://t.example:80", "dht:abc", "tracker:udp://t.example:80")))
    preveriEnako("magnet s privzetimi sledilniki", true, W.magnet(a).startsWith("magnet:?xt=urn:btih:$a&tr=udp%3A%2F%2Ftracker.opentrackr.org"))

    // --- vrstni red virov (lastnik 25. 10. 2026 07:25)
    preveriEnako("koraki z Usenetom", listOf(Korak.USENET_PRIPRAVLJEN, Korak.TAKOJ, Korak.USENET_CAKANJE, Korak.WEBTOR, Korak.CAKANJE_DEBRID), W.koraki(true))
    preveriEnako("koraki brez Useneta", listOf(Korak.TAKOJ, Korak.WEBTOR, Korak.CAKANJE_DEBRID), W.koraki(false))
    preveriEnako("takoj: ⚡ torrent prvi", Takoj.TORRENT, W.takojPrvi(true, true, true))
    preveriEnako("takoj: povezava pred ⚡WT", Takoj.POVEZAVA, W.takojPrvi(false, true, true))
    preveriEnako("takoj: ⚡WT", Takoj.WEBTOR, W.takojPrvi(false, false, true))
    preveriEnako("takoj: nic", null, W.takojPrvi(false, false, false))
    preveriEnako("Usenet: najboljsi pripravljen", 0, W.izberiUsenet(listOf(true, null, null), false))
    preveriEnako("Usenet: boljsi se ni odgovoril - cakaj", W.CAKAJ, W.izberiUsenet(listOf(null, true, false), false))
    preveriEnako("Usenet: boljsi ni pripravljen", 1, W.izberiUsenet(listOf(false, true, null), false))
    preveriEnako("Usenet: rok potekel - najboljsi med odgovorjenimi", 1, W.izberiUsenet(listOf(null, true, false), true))
    preveriEnako("Usenet: nobena", W.NOBENA, W.izberiUsenet(listOf(false, false, false), false))
    preveriEnako("Usenet: rok potekel, nic", W.NOBENA, W.izberiUsenet(listOf(null, false, null), true))
    preveriEnako("Usenet: prazno", W.NOBENA, W.izberiUsenet(emptyList(), false))

    // Pregled U4: sonda ne sme v domace omrezje ali na to napravo (po razresitvi DNS).
    fun ip(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    preveriEnako("192.168.1.1 zaseben", true, W.zasebenNaslov(ip(192, 168, 1, 1)))
    preveriEnako("10.0.0.1 zaseben", true, W.zasebenNaslov(ip(10, 0, 0, 1)))
    preveriEnako("172.16.5.4 zaseben", true, W.zasebenNaslov(ip(172, 16, 5, 4)))
    preveriEnako("172.32.0.1 javen", false, W.zasebenNaslov(ip(172, 32, 0, 1)))
    preveriEnako("127.0.0.1 zanka", true, W.zasebenNaslov(ip(127, 0, 0, 1)))
    preveriEnako("169.254.1.1 link-local", true, W.zasebenNaslov(ip(169, 254, 1, 1)))
    preveriEnako("100.64.0.1 CGNAT", true, W.zasebenNaslov(ip(100, 64, 0, 1)))
    preveriEnako("0.0.0.0 nedolocen", true, W.zasebenNaslov(ip(0, 0, 0, 0)))
    preveriEnako("1.1.1.1 javen", false, W.zasebenNaslov(ip(1, 1, 1, 1)))
    preveriEnako("::1 zanka", true, W.zasebenNaslov(ip(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
    preveriEnako("fd00::1 ULA", true, W.zasebenNaslov(ip(0xfd, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
    preveriEnako("fe80::1 link-local", true, W.zasebenNaslov(ip(0xfe, 0x80, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)))
    preveriEnako("::ffff:192.168.0.1 zaseben", true, W.zasebenNaslov(ip(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 192, 168, 0, 1)))
    preveriEnako("2606:4700::1111 javen", false, W.zasebenNaslov(ip(0x26, 0x06, 0x47, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x11, 0x11)))

    if (napak == 0) println("WebtorPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
