package si.safeer.tv.os

import si.safeer.tv.os.FilterTorBoxPravila.Gumb
import si.safeer.tv.os.FilterTorBoxPravila.Stanje

private var napak = 0

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    if (pricakovano == dobljeno) println("  ok  $opis") else { napak++; println("  NAPAKA  $opis: pricakovano $pricakovano, dobljeno $dobljeno") }

/** Filter vira TorBox (lastnik 10. 11. 2026: svoj filter kot Usenet; priprava z opozorilom; hitrejsi vir najprej). */
fun main() {
    println("Preizkus filtra vira (TorBox)")
    val s = FilterTorBoxPravila::stanje
    preveriEnako("TorBox ima pripravljen torrent -> takoj", Stanje.TAKOJ, s(5, 2, false))
    preveriEnako("pripravljen pri TorBoxu ima prednost pred neposredno povezavo", Stanje.TAKOJ, s(5, 1, true))
    preveriEnako("TorBox nima, drug vir ima takoj -> ponudimo drug vir", Stanje.TAKOJ_DRUGJE, s(5, 0, true))
    preveriEnako("ni torrentov, drug vir ima takoj -> ponudimo drug vir", Stanje.TAKOJ_DRUGJE, s(0, 0, true))
    preveriEnako("TorBox ni odgovoril, drug vir ima takoj -> uporabnik lahko gleda", Stanje.TAKOJ_DRUGJE, s(3, null, true))
    preveriEnako("torrenti so, nobeden pripravljen, drugje nic -> priprava", Stanje.PRIPRAVA, s(4, 0, false))
    preveriEnako("dodatki odgovorili brez tokov -> ni", Stanje.NI, s(0, 0, false))
    preveriEnako("dodatki niso odgovorili -> ne vemo", Stanje.NE_VEMO, s(null, null, false))
    preveriEnako("TorBox ni odgovoril in drugje nic -> ne vemo (ne »priprava«)", Stanje.NE_VEMO, s(4, null, false))

    preveriEnako("oznaka Priprava samo, kjer je priprava edina pot", listOf(false, false, true, false, false),
        Stanje.values().map { FilterTorBoxPravila.oznakaPriprave(it) })
    preveriEnako("v mrezi: takoj, takoj drugje, priprava", listOf(true, true, true, false, false),
        Stanje.values().map { FilterTorBoxPravila.pokazi(it) })
    preveriEnako("negativno se zapise samo potrjen »ni«", listOf(false, false, false, true, false),
        Stanje.values().map { FilterTorBoxPravila.zapisiNegativno(it) })

    val stanja = mapOf("a" to Stanje.PRIPRAVA, "b" to Stanje.TAKOJ, "c" to Stanje.NI, "d" to Stanje.TAKOJ_DRUGJE, "e" to Stanje.PRIPRAVA, "f" to Stanje.NE_VEMO)
    preveriEnako("pripravljeni prvi, priprava za njimi, vrstni red kataloga ostane; ni in ne vemo izpadeta",
        listOf("b", "d", "a", "e"), FilterTorBoxPravila.uredi(stanja.keys.toList()) { stanja.getValue(it) })

    preveriEnako("okno pri drugem viru: najprej predvajaj drugje, nato priprava", listOf(Gumb.PREDVAJAJ_DRUGJE, Gumb.PRIPRAVI_IN_OBVESTI, Gumb.PREKLICI),
        FilterTorBoxPravila.gumbi(Stanje.TAKOJ_DRUGJE))
    preveriEnako("okno pri pripravi: priprava in preklic", listOf(Gumb.PRIPRAVI_IN_OBVESTI, Gumb.PREKLICI), FilterTorBoxPravila.gumbi(Stanje.PRIPRAVA))
    preveriEnako("pripravljen: brez okna, samo predvajaj", listOf(Gumb.PREDVAJAJ), FilterTorBoxPravila.gumbi(Stanje.TAKOJ))
    preveriEnako("priprava se nikoli ne ponudi kot prvi gumb, ce je hitrejsa pot", false,
        FilterTorBoxPravila.gumbi(Stanje.TAKOJ_DRUGJE).first() == Gumb.PRIPRAVI_IN_OBVESTI)

    if (napak > 0) { println("NAPAK: $napak"); kotlin.system.exitProcess(1) }
    println("FilterTorBoxPravilaTest OK")
}
