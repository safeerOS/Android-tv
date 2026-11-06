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

    // Pregled 6. 11. 2026 (P4): lokalna predpona NAT64 64:ff9b:1::/48 (RFC 8215), kot SondaVeriga.zasebenCilj.
    for (g in listOf("64:ff9b:1::7f00:1", "64:ff9b:1:abcd::a9fe:a9fe", "[64:ff9b:1::7f00:1]"))
        preveriEnako("prepovedan NAT64 lokalno: $g", true, P.gostiteljPrepovedan(g))
    preveriEnako("NAT64 lokalno z javnim naslovom ni prepovedan", false, P.gostiteljPrepovedan("64:ff9b:1::808:808"))

    // Pregled 6. 11. 2026 (P3): z odtisom (zeton naprave) samo https.
    val fp = "a".repeat(64)
    preveriEnako("zahteva: https z odtisom", true, P.zahtevaVeljavna("https://192.168.1.5:8443/d/1", fp))
    preveriEnako("zahteva: http z odtisom NE", false, P.zahtevaVeljavna("http://192.168.1.5:8443/d/1", fp))
    preveriEnako("zahteva: http brez odtisa (splet)", true, P.zahtevaVeljavna("http://cdn.example/f.mkv", ""))
    preveriEnako("zahteva: odtis napacne dolzine NE", false, P.zahtevaVeljavna("https://192.168.1.5/d/1", "abc"))
    preveriEnako("zahteva: druga shema NE", false, P.zahtevaVeljavna("ftp://cdn.example/f.mkv", ""))

    // Pregled 6. 11. 2026 (P2): izrinjenje po nastanku, nikoli novi; koncani brez bralca 60 s ven.
    val zdaj = 1_000_000L
    fun o(id: String, nastal: Long, koncano: Boolean = false, bralcev: Int = 0, zadnji: Long = zdaj) = PretokPravila.Opis(id, nastal, koncano, bralcev, zadnji)
    val devet = (1..9).map { o("t$it", it.toLong()) }
    preveriEnako("9 tokov, najvec 8: gre najstarejsi", listOf("t1"), P.izrini(devet, "t9", 8, zdaj, 60_000))
    // Novi ima najmanjso stevilko (npr. ura) - se vedno ostane.
    val noviPrvi = listOf(o("nov", 0)) + (1..8).map { o("t$it", it.toLong()) }
    preveriEnako("novega nikoli", listOf("t1"), P.izrini(noviPrvi, "nov", 8, zdaj, 60_000))
    val koncani = listOf(o("k", 1, koncano = true, zadnji = zdaj - 61_000), o("b", 2, koncano = true, bralcev = 1, zadnji = zdaj - 61_000),
        o("s", 3, koncano = true, zadnji = zdaj - 5_000), o("n", 4))
    preveriEnako("koncan brez bralca 60 s ven, z bralcem in nedavni ostaneta", listOf("k"), P.izrini(koncani, "n", 8, zdaj, 60_000))
    preveriEnako("en sam novi: nic", emptyList<String>(), P.izrini(listOf(o("n", 1, koncano = true, zadnji = 0)), "n", 8, zdaj, 60_000))
    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("Vse v redu.")
}
