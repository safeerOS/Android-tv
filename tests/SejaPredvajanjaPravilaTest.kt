package si.safeer.tv.os

/** Seja predvajanja: stanje samo ob pravi spremembi ([SejaPredvajanjaPravila], TV 5. 11. 2026). */
fun main() {
    val P = SejaPredvajanjaPravila
    fun st(stanje: Int, polozaj: Long, cas: Long, hitrost: Float = 1f, posebne: List<String> = listOf("na_napravi")) =
        P.Stanje(stanje, hitrost, 823L, posebne, polozaj, cas)
    check(P.bistvenoSpremenjeno(null, st(2, 0, 0))) { "prvo stanje gre vedno" }
    // Izmerjeni izbruh: state=2, polozaj 0, trikrat v 20 ms - samo prvo.
    val premor = st(2, 0, 1_000)
    check(!P.bistvenoSpremenjeno(premor, st(2, 0, 1_010))) { "isti premor, isti polozaj: ne posljemo" }
    check(!P.bistvenoSpremenjeno(premor, st(2, 0, 60_000))) { "premor cez minuto, polozaj isti: ne posljemo" }
    check(P.bistvenoSpremenjeno(premor, st(6, 0, 1_020, 0f))) { "nalaganje (6) je novo stanje" }
    check(P.bistvenoSpremenjeno(premor, st(3, 0, 1_030))) { "predvaja (3) je novo stanje" }
    // Predvajanje: polozaj tece s casom - tik ni sprememba.
    val igra = st(3, 10_000, 5_000)
    check(!P.bistvenoSpremenjeno(igra, st(3, 11_000, 6_000))) { "tik predvajanja (polozaj sledi uri): ne posljemo" }
    check(!P.bistvenoSpremenjeno(igra, st(3, 70_400, 65_000))) { "po minuti 400 ms odstopanja: ne posljemo" }
    check(P.bistvenoSpremenjeno(igra, st(3, 120_000, 6_000))) { "skok naprej" }
    check(P.bistvenoSpremenjeno(igra, st(3, 0, 6_000))) { "nova skladba od zacetka (polozaj 0)" }
    check(P.bistvenoSpremenjeno(st(2, 196_759, 1_000), st(2, 0, 1_010))) { "TV 19:29:14: premor 196759 -> 0 je skok" }
    check(P.bistvenoSpremenjeno(igra, st(3, 11_000, 6_000, 0f))) { "hitrost 1 -> 0 (zvocnik pristaja)" }
    check(P.bistvenoSpremenjeno(igra, st(3, 11_000, 6_000, posebne = emptyList()))) { "druge posebne akcije" }
    check(P.bistvenoSpremenjeno(igra, igra.copy(akcije = 1L, polozajMs = 11_000, posodobljenoMs = 6_000))) { "druge akcije" }
    check(P.pricakovanPolozaj(st(3, 1_000, 100, 2f), 600) == 2_000L) { "hitrost 2: 500 ms ure = 1000 ms posnetka" }
    check(P.pricakovanPolozaj(st(3, 1_000, 600), 100) == 1_000L) { "ura nazaj: polozaj ne gre nazaj" }
    println("SejaPredvajanjaPravilaTest: OK")
}
