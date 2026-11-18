package si.safeer.tv.os

// Slepi pregled TV 18. 11. 2026, S1: sosed je zaupan samo z enakim podpisom (ime paketa ni dokaz).
private var napak = 0
private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

fun main() {
    println("SosedZaupanje")
    preveri("omogocen paket z enakim podpisom je zaupan", SosedZaupanje.jeZaupan("si.safeer.os", "si.safeer.tv", true, true))
    preveri("omogocen paket z drugim podpisom NI zaupan (tuja aplikacija z imenom si.safeer.os)",
        !SosedZaupanje.jeZaupan("si.safeer.os", "si.safeer.tv", true, false))
    preveri("onemogocen paket ni zaupan", !SosedZaupanje.jeZaupan("si.safeer.os", "si.safeer.tv", false, true))
    preveri("jaz sem zaupan tudi brez primerjave podpisa", SosedZaupanje.jeZaupan("si.safeer.os", "si.safeer.os", true, false))
    preveri("onemogocen jaz ni zaupan", !SosedZaupanje.jeZaupan("si.safeer.os", "si.safeer.os", false, false))
    if (napak > 0) { println("SosedZaupanje: $napak napak"); kotlin.system.exitProcess(1) }
    println("SosedZaupanje: vse OK")
}
