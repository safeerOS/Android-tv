package si.safeer.tv.os

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

private fun brezIzjeme(opis: String, f: () -> Any?) {
    try { f(); println("  ok  $opis") } catch (e: Throwable) { napak++; println("  NAPAKA  $opis: izjema $e") }
}

/** Neodvisni pregled 12. 11. 2026: S1 (izjema ob neveljavnem %) in S2 (skrajsan zapis naslova IP) v [MagnetPravila]. */
fun main() {
    println("Preizkus pravil magnet povezav")
    val hex = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"
    preveriEnako("veljaven magnet", hex, MagnetPravila.hash("magnet:?xt=urn:btih:${hex.uppercase()}&dn=Film"))
    preveriEnako("xt=%zz je neveljaven, brez izjeme", null, MagnetPravila.hash("magnet:?xt=%zz"))
    preveriEnako("xt s pustim % je neveljaven", null, MagnetPravila.hash("magnet:?xt=urn:btih:%"))
    preveriEnako("neveljaven xt, nato veljaven", hex, MagnetPravila.hash("magnet:?xt=%zz&xt=urn:btih:$hex"))
    brezIzjeme("zSledilniki s tr=% ne vrze izjeme") { MagnetPravila.zSledilniki("magnet:?xt=urn:btih:$hex&tr=%&dn=100%", listOf("udp://p.example.com:1/a")) }
    preveriEnako("zSledilniki preskoci neveljaven tr", "magnet:?xt=urn:btih:$hex&tr=udp%3A%2F%2Fp.example.com%3A1%2Fa",
        MagnetPravila.zSledilniki("magnet:?xt=urn:btih:$hex&tr=%", listOf("udp://p.example.com:1/a")))

    for (naslov in listOf("http://127.1/announce", "http://0x7f.0.0.1/a", "http://192.168.1/a", "http://10.1/a", "http://2130706433/a",
        "http://127.0.0.1/a", "http://192.168.0.1:80/a", "udp://[::1]:80/a", "http://localhost/a", "http://tracker.local/a")) {
        preveriEnako("domaci naslov zavrnjen: $naslov", false, MagnetPravila.javenSledilnik(naslov))
    }
    for (naslov in listOf("udp://tracker.opentrackr.org:1337/announce", "https://tracker.example.com/announce", "http://8.8.8.8/announce", "http://1.2.3.example.com/a")) {
        preveriEnako("javni sledilnik dovoljen: $naslov", true, MagnetPravila.javenSledilnik(naslov))
    }
    preveriEnako("shema file: zavrnjena", false, MagnetPravila.javenSledilnik("file:///etc/passwd"))

    if (napak == 0) println("MagnetPravilaTest: OK") else { println("Napak: $napak"); System.exit(1) }
}
