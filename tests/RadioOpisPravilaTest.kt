package si.safeer.tv.os

import java.util.Locale

// Preverba TV 4. 11. 2026: opis postaje (»Slovenia · rock«) v jeziku vmesnika.
private var napakOpisa = 0
private fun preveriOpis(opis: String, pricakovano: String, dobljeno: String) {
    if (pricakovano == dobljeno) println("  OK   $opis") else { println("  NAPAKA $opis: pricakovano '$pricakovano', dobljeno '$dobljeno'"); napakOpisa++ }
}

fun main() {
    println("\nPreizkus opisa radijske postaje")
    val sl = Locale("sl")
    val zvrsti = mapOf("rock" to "rock", "news" to "novice", "classical" to "klasična")
    val z: (String) -> String? = { zvrsti[it.lowercase(Locale.ROOT)] }
    preveriOpis("drzava in zvrst v slovenscini", "Slovenija · rock", RadioOpisPravila.prevediOpis("Slovenia · rock", sl, z))
    preveriOpis("Nemcija, novice", "Nemčija · novice", RadioOpisPravila.prevediOpis("Germany · news", sl, z))
    preveriOpis("nemski vmesnik", "Slowenien · rock", RadioOpisPravila.prevediOpis("Slovenia · rock", Locale.GERMAN, z))
    preveriOpis("»The« in velike crke (Radio Browser)", "Nizozemska · klasična", RadioOpisPravila.prevediOpis("The Netherlands · classical", sl, z))
    preveriOpis("neznana zvrst ostane", "Slovenija · balade", RadioOpisPravila.prevediOpis("Slovenia · balade", sl, z))
    preveriOpis("samo zvrst", "novice", RadioOpisPravila.prevediOpis("news", sl, z))
    preveriOpis("prazno ostane", "", RadioOpisPravila.prevediOpis("", sl, z))
    preveriOpis("neznana drzava ostane", "Atlantida · rock", RadioOpisPravila.prevediOpis("Atlantida · rock", sl, z))
    if (napakOpisa > 0) { println("NAPAKE: $napakOpisa"); kotlin.system.exitProcess(1) }
    println("RadioOpisPravilaTest: OK")
}
