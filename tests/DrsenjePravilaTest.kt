package si.safeer.tv.os

// Preverba TV 4. 11. 2026: drsnik Medijskega centra obstane na zacetku vrste, ne sredi nje (prva vrstica ni odrezana).
private var napakDrsenja = 0
private fun preveriDrsenje(opis: String, pricakovano: Int, dobljeno: Int) {
    if (pricakovano == dobljeno) println("  OK   $opis") else { println("  NAPAKA $opis: pricakovano $pricakovano, dobljeno $dobljeno"); napakDrsenja++ }
}

fun main() {
    println("\nPreizkus drsenja po vrstah")
    // Naslov 0, vrste po 300 px na 100, 400, 700, 1000; zaslon 800 px, vsebina 1300 px (najvec 500).
    val vrhovi = listOf(0, 100, 400, 700, 1000)
    // Navzdol na vrsto 1000..1300: ScrollView bi dal 500 (konec). Konec je dovoljen.
    preveriDrsenje("na konec vsebine", 500, DrsenjePravila.poravnajNaVrsto(vrhovi, 500, 1000, 1300, 800, 500))
    // Navzdol na vrsto 700..1000: ScrollView da 200 (zgoraj bi bil odrezan kos vrste 100..400) - poravnano na 400.
    preveriDrsenje("navzdol: zgoraj zacetek vrste", 400, DrsenjePravila.poravnajNaVrsto(vrhovi, 200, 700, 1000, 800, 500))
    // Navzgor na vrsto 100..400 z odmika 400: ScrollView da 100 - to je ze vrh vrste.
    preveriDrsenje("navzgor: vrh vrste", 100, DrsenjePravila.poravnajNaVrsto(vrhovi, 100, 100, 400, 800, 500))
    // Navzgor, ScrollView predlaga 130 (rob kartice z zamikom): najblizji vrh vrste je 100.
    preveriDrsenje("navzgor: na vrh vrste, ne sredi nje", 100, DrsenjePravila.poravnajNaVrsto(vrhovi, 130, 140, 400, 800, 500))
    // Vrsta visja od zaslona: brez poravnave.
    preveriDrsenje("previsoka vrsta: kot ScrollView", 250, DrsenjePravila.poravnajNaVrsto(vrhovi, 250, 100, 1000, 800, 500))
    if (napakDrsenja > 0) { println("NAPAKE: $napakDrsenja"); kotlin.system.exitProcess(1) }
    println("DrsenjePravilaTest: OK")
}
