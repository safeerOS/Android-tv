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
    if (napakAplikacij > 0) { println("NAPAKE: $napakAplikacij"); kotlin.system.exitProcess(1) }
}
