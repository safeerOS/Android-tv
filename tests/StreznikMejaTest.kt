package si.safeer.tv.link

/** Pregled 10. 11. 2026 (3.4): meja socasnih povezav streznika datotek in rok branja zahteve. */
private var preverb = 0

private fun preveri(opis: String, pogoj: Boolean) {
    check(pogoj) { "NAPAKA: $opis" }
    preverb++
}

private fun povezave() {
    val m = StreznikMeja(skupaj = 5, naNaslov = 2)
    preveri("prva povezava naslova gre", m.sprejmi("192.168.1.7"))
    preveri("druga povezava naslova gre", m.sprejmi("192.168.1.7"))
    preveri("tretja povezava istega naslova ne gre", !m.sprejmi("192.168.1.7"))
    preveri("zavrnjena povezava se ne steje", m.odprtih == 2)
    preveri("drug naslov gre", m.sprejmi("192.168.1.8") && m.sprejmi("192.168.1.8"))
    preveri("peta povezava (tretji naslov) gre", m.sprejmi("192.168.1.9"))
    preveri("sesta povezava skupaj ne gre", !m.sprejmi("192.168.1.10"))
    preveri("odprtih je natanko meja", m.odprtih == 5)
    m.sprosti("192.168.1.7")
    preveri("po koncu povezave je mesto prosto", m.odprtih == 4 && m.sprejmi("192.168.1.10"))
    preveri("streznik je spet poln", !m.sprejmi("192.168.1.11") && m.odprtih == 5)
    m.sprosti("neznan-naslov")
    preveri("sprostitev nestete povezave ne spremeni stevca", m.odprtih == 5)
    for (n in listOf("192.168.1.7", "192.168.1.8", "192.168.1.8", "192.168.1.9", "192.168.1.10")) m.sprosti(n)
    preveri("po koncu vseh je streznik prazen", m.odprtih == 0)
    m.sprosti("192.168.1.7")
    preveri("stevec ne gre pod nic", m.odprtih == 0 && m.sprejmi("192.168.1.7"))

    // Privzete meje: en naslov ne zasede streznika, vsi skupaj ne vec kot 32.
    val p = StreznikMeja()
    var sprejetih = 0
    for (i in 1..100) if (p.sprejmi("10.0.0.66")) sprejetih++
    preveri("en naslov dobi najvec 8 povezav", sprejetih == StreznikMeja.NAJVEC_NA_NASLOV && sprejetih == 8)
    preveri("druga naprava se vedno pride zraven", p.sprejmi("10.0.0.5"))
    var vseh = p.odprtih
    for (i in 1..200) if (p.sprejmi("10.0.1.$i")) vseh++
    preveri("skupaj najvec 32 povezav", vseh == StreznikMeja.NAJVEC_POVEZAV && p.odprtih == 32)

    // Vec niti hkrati: stevec ne preseze meje.
    val s = StreznikMeja(skupaj = 16, naNaslov = 16)
    val uspehov = java.util.concurrent.atomic.AtomicInteger(0)
    val niti = (1..8).map { Thread { for (i in 1..50) if (s.sprejmi("a")) uspehov.incrementAndGet() } }
    for (n in niti) n.start()
    for (n in niti) n.join()
    preveri("socasni sprejemi ne presezejo meje", uspehov.get() == 16 && s.odprtih == 16)
}

private fun rok() {
    val rok = StreznikMeja.ROK_ZAHTEVE_MS
    preveri("na zacetku velja rok enega branja", StreznikMeja.preostanek(1000L, 1000L) == StreznikMeja.ROK_BRANJA_MS)
    preveri("pred koncem ostane samo razlika", StreznikMeja.preostanek(1000L, 1000L + rok - 300L) == 300)
    preveri("ob roku nic vec", StreznikMeja.preostanek(1000L, 1000L + rok) == 0)
    preveri("po roku nic vec", StreznikMeja.preostanek(1000L, 1000L + rok + 60_000L) == 0)
    preveri("ura nazaj ne podaljsa roka", StreznikMeja.preostanek(1000L, 500L) == 0)
    // Pocasen odjemalec (bajt na 14 s): vsota dovoljenih cakanj ne preseze skupnega roka.
    var zdaj = 0L
    var cakanj = 0
    while (true) {
        val ostane = StreznikMeja.preostanek(0L, zdaj)
        if (ostane <= 0) break
        zdaj += minOf(14_000, ostane)
        cakanj++
    }
    preveri("pocasno branje se konca ob skupnem roku", zdaj == rok && cakanj <= 3)
}

/** Streznik pravila res uporablja (DatotekeStreznik rabi Android in tu ne tece). */
private fun uporaba(izvorna: String) {
    if (izvorna.isEmpty()) return
    val vir = java.io.File(izvorna, "si/safeer/tv/link/DatotekeStreznik.kt").readText()
    val zanka = vir.substringAfter("private fun zanka(v: ServerSocket) {").substringBefore("private fun postrezi(s: Socket)")
    preveri("presezek povezav se zapre takoj", zanka.contains("if (!meja.sprejmi(naslov)) { try { s.close() }"))
    preveri("povezava se ob koncu sprosti", zanka.contains("finally { meja.sprosti(naslov) }"))
    preveri("brez niti na vsako povezavo", !zanka.contains("Thread(") && zanka.contains("delavciZahtev.execute"))
    val postrezi = vir.substringAfter("private fun postrezi(s: Socket)").substringBefore("fun jePotPrekHuba")
    preveri("branje zahteve ima skupen rok", postrezi.contains("StreznikMeja.preostanek(zacetek, zdaj())") && postrezi.contains("s.soTimeout = rok"))
    preveri("stevilo glav je omejeno", postrezi.contains("glave.size >= StreznikMeja.NAJVEC_GLAV"))
}

fun main(args: Array<String>) {
    povezave()
    rok()
    uporaba(args.firstOrNull().orEmpty())
    println("StreznikMejaTest: OK ($preverb preverb)")
}
