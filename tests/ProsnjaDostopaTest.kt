package si.safeer.tv.os

import si.safeer.tv.os.ProsnjaDostopa.Stanje

/**
 * Preizkus [ProsnjaDostopa]: zavrnitev racunalnika zaradi dostopa te naprave ni »racunalnik zaslona ne deli«
 * (lastnik, 15. 10. 2026), cakanje na potrditev pa je omejeno.
 */
fun main() {
    val p = ProsnjaDostopa
    // Racunalnik caka na potrditev (core/link_dostop.z_prosnjo): koda ostane "ni_dovoljeno", zraven je access_request.
    check(p.stanje("ni_dovoljeno", null, "pending") == Stanje.CAKA) { "pending pri kratki zavrnitvi" }
    check(p.stanje("", "access", "pending") == Stanje.CAKA) { "pending pri seznamu (data.reason = access)" }
    check(p.stanje("ni_dovoljeno", null, " Pending ") == Stanje.CAKA) { "presledki in velike crke" }
    check(p.stanje("ni_dovoljeno", null, "declined") == Stanje.ZAVRNJENO)
    check(p.stanje("", "access", "declined") == Stanje.ZAVRNJENO)
    // Prosnje ni mogoce pokazati: dostop se dovoli rocno - NE »vklopi Zaslon za televizor«.
    check(p.stanje("ni_dovoljeno", null, "none") == Stanje.NI_DOSTOPA) { "none pri kratki zavrnitvi" }
    check(p.stanje("", "access", "none") == Stanje.NI_DOSTOPA)
    check(p.stanje("ni_dovoljeno", null, "<b>karkoli</b>") == Stanje.NI_DOSTOPA) { "neznana vrednost ni izklopljeno stikalo" }
    check(p.stanje("", "access", null) == Stanje.NI_DOSTOPA) { "starejsi racunalnik: seznam zaradi dostopa brez polja" }
    // Stara zavrnitev brez polja: deljenje je na racunalniku izklopljeno (core/link_daljinec: zaslon izklopljen).
    check(p.stanje("ni_dovoljeno", null, null) == Stanje.IZKLOPLJENO)
    check(p.stanje("ni_dovoljeno", "", "") == Stanje.IZKLOPLJENO)
    // Ni zavrnitev dostopa: druge napake in drugi razlogi gredo po stari poti.
    check(p.stanje("", null, null) == null && p.stanje(null, null, null) == null) { "uspeh" }
    check(p.stanje("ni_programa", null, null) == null && p.stanje("napaka", "", "") == null) { "druga napaka" }
    check(p.stanje("", "permission", null) == null && p.stanje("", "off", null) == null) { "telefon: dovoljenje ali izklop" }
    check(p.stanje("", "", "pending") == Stanje.CAKA) { "polje samo zadosca" }

    // Cakanje: na PONOVI_MS, najvec NAJDLJE_MS.
    val t0 = 1_000_000L
    check(p.naslednjiPoskus(t0, t0) == p.PONOVI_MS)
    check(p.naslednjiPoskus(t0, t0 + 60_000L) == p.PONOVI_MS)
    check(p.naslednjiPoskus(t0, t0 + p.NAJDLJE_MS - 1_000L) == 1_000L) { "zadnji poskus tik pred koncem" }
    check(p.naslednjiPoskus(t0, t0 + p.NAJDLJE_MS) == null) { "konec cakanja" }
    check(p.naslednjiPoskus(t0, t0 + 10 * p.NAJDLJE_MS) == null)
    check(p.naslednjiPoskus(t0, t0 - 1L) == null) { "ura nazaj ne podaljsa cakanja" }
    check(p.PONOVI_MS in 2_000L..5_000L && p.NAJDLJE_MS in 60_000L..180_000L) { "okoli 3 s in 2 minuti" }
    var poskusov = 0
    var t = t0
    while (true) { val z = p.naslednjiPoskus(t0, t) ?: break; poskusov++; t += z }
    check(poskusov == (p.NAJDLJE_MS / p.PONOVI_MS).toInt()) { "omejeno stevilo poskusov: $poskusov" }

    check(p.preostalo(t0, t0) == "2:00") { p.preostalo(t0, t0) }
    check(p.preostalo(t0, t0 + 3_000L) == "1:57")
    check(p.preostalo(t0, t0 + 119_500L) == "0:01")
    check(p.preostalo(t0, t0 + 500_000L) == "0:00" && p.preostalo(t0, t0 - 5_000L) == "2:00")
    println("ProsnjaDostopaTest: OK")
}
