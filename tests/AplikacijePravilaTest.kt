package si.safeer.tv.os

// Odstrani ali onemogoci: tovarniske sistemske aplikacije Android ne odstrani (samo onemogoci), zato jih ne ponujamo za odstranitev.
private var napakAplikacij = 0
private fun preveriAplikacije(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napakAplikacij++ }
}

fun main() {
    println("\nPreizkus pravil odstranitve aplikacij")
    preveriAplikacije("uporabniska aplikacija se odstrani", AplikacijePravila.dejanjeOdstranitve(false, false) == AplikacijePravila.DejanjeOdstranitve.ODSTRANI)
    preveriAplikacije("tovarniska sistemska se onemogoci", AplikacijePravila.dejanjeOdstranitve(true, false) == AplikacijePravila.DejanjeOdstranitve.ONEMOGOCI)
    preveriAplikacije("posodobljena sistemska se odstrani (posodobitev)", AplikacijePravila.dejanjeOdstranitve(true, true) == AplikacijePravila.DejanjeOdstranitve.ODSTRANI)
    // Preverba TV 4. 11. 2026: pasica F-Droid (640x360) se je obrezala, ker sta bili stranici omejeni vsaka zase.
    preveriAplikacije("pasica 640x360 sorazmerno na 512x288", AplikacijePravila.meraSlike(640, 360, 512) == (512 to 288))
    preveriAplikacije("pokoncna 300x900 na 171x512", AplikacijePravila.meraSlike(300, 900, 512) == (171 to 512))
    preveriAplikacije("majhna ikona ostane", AplikacijePravila.meraSlike(192, 192, 512) == (192 to 192))
    preveriAplikacije("nikoli 0 px", AplikacijePravila.meraSlike(5000, 1, 512).second >= 1)
    if (napakAplikacij > 0) { println("NAPAKE: $napakAplikacij"); kotlin.system.exitProcess(1) }
}
