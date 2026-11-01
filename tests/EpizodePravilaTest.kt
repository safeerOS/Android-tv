package si.safeer.tv.os

private var napakEpizod = 0
private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napakEpizod++ }
}

fun main() {
    println("\nPreizkus pravil epizod")
    
    // Oznaka
    preveri("oznaka 1/1 je S01E01", EpizodePravila.oznakaEpizode(1, 1) == "S01E01")
    preveri("oznaka 12/5 je S12E05", EpizodePravila.oznakaEpizode(12, 5) == "S12E05")
    preveri("oznaka 0/5 je S00E05", EpizodePravila.oznakaEpizode(0, 5) == "S00E05")
    preveri("oznaka 0/0 je prazna", EpizodePravila.oznakaEpizode(0, 0) == "")
    preveri("oznaka -1/-1 je prazna", EpizodePravila.oznakaEpizode(-1, -1) == "")
    
    // Pokazi levi stolpec
    preveri("več različnih sezon -> pokaži", EpizodePravila.pokaziLeviStolpec(listOf(1, 2, 3)))
    preveri("ena sezona -> skrij", !EpizodePravila.pokaziLeviStolpec(listOf(1)))
    preveri("več enakih sezon -> skrij", !EpizodePravila.pokaziLeviStolpec(listOf(1, 1, 1)))
    preveri("brez sezon -> skrij", !EpizodePravila.pokaziLeviStolpec(emptyList()))
    
    // Smer fokusa
    preveri("DESNO na sezonah -> gre na epizode", EpizodePravila.smerFokusa(true, false) == false)
    preveri("LEVO na sezonah -> nič", EpizodePravila.smerFokusa(true, true) == null)
    preveri("LEVO na epizodah -> gre na sezone", EpizodePravila.smerFokusa(false, true) == true)
    preveri("DESNO na epizodah -> nič", EpizodePravila.smerFokusa(false, false) == null)

    if (napakEpizod != 0) { println("Napak epizod: $napakEpizod"); kotlin.system.exitProcess(1) }
}
