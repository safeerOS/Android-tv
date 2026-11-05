package si.safeer.tv.link

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Vodja 5. 11. 2026: Pretok ni zavrnil zanke in povezavno-lokalnih naslovov (Linux link_sprotno.vrsta_naslova jih). */
fun main() {
    println("Preizkus cilja sprotne pretvorbe")
    val P = PretokPravila
    for (g in listOf("127.0.0.1", "127.8.9.10", "localhost", "LOCALHOST.", "tv.localhost", "169.254.169.254", "0.0.0.0", "::1", "[::1]",
            "::ffff:127.0.0.1", "::127.0.0.1", "2002:7f00:1::1", "64:ff9b::7f00:1", "fe80::1", "ff02::1", "224.0.0.1", "255.255.255.255", "::", ""))
        preveriEnako("prepovedan: $g", true, P.gostiteljPrepovedan(g))
    for (g in listOf("192.168.1.20", "10.0.0.5", "100.64.1.1", "8.8.8.8", "2a00:1450::1", "cdn.example", "fd00::1"))
        preveriEnako("dovoljen: $g", false, P.gostiteljPrepovedan(g))
    preveriEnako("razreseni: en prepovedan -> nobeden", false,
        P.razreseniDovoljeni(listOf(byteArrayOf(8, 8, 8, 8), byteArrayOf(127, 0, 0, 1))))
    preveriEnako("razreseni: javni", true, P.razreseniDovoljeni(listOf(byteArrayOf(8, 8, 8, 8))))
    preveriEnako("razreseni: nic (ime ne obstaja)", false, P.razreseniDovoljeni(emptyList()))
    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("Vse v redu.")
}
