package si.safeer.tv.os

private var napak = 0

private fun preveri(opis: String, pogoj: Boolean) {
    if (pogoj) println("  OK   $opis") else { println("  NAPAKA $opis"); napak++ }
}

private fun preveriEnako(opis: String, pricakovano: Any?, dobljeno: Any?) =
    preveri("$opis (pričakovano=$pricakovano, dobljeno=$dobljeno)", pricakovano == dobljeno)

data class TestNaslov(override val id: String, override val zadnjiVideni: Long) : KatalogPravila.Naslov {
    override fun sCasom(cas: Long) = copy(zadnjiVideni = cas)
}

fun main() {
    val zdaj = 100000000000L
    val dan = 24L * 60 * 60 * 1000L

    preveri("Nikoli se ni bilo sveza", KatalogPravila.potrebenSvez(0L, zdaj))
    preveri("23h - ne potrebuje sveza", !KatalogPravila.potrebenSvez(zdaj - dan + 3600000L, zdaj))
    preveri("24h - potrebuje svez", KatalogPravila.potrebenSvez(zdaj - dan, zdaj))
    preveri("25h - potrebuje svez", KatalogPravila.potrebenSvez(zdaj - dan - 3600000L, zdaj))

    val stare = listOf(
        TestNaslov("1", zdaj - 8 * dan), // Pade ven
        TestNaslov("2", zdaj - 6 * dan), // Ostane
        TestNaslov("3", zdaj - 6 * dan)  // Osvezena
    )
    val nove = listOf(
        TestNaslov("3", 0L), // Stara, osvezena
        TestNaslov("4", 0L)  // Nova
    )

    val rezultat = KatalogPravila.zdruzi(stare, nove, zdaj)
    
    preveriEnako("Velikost je 3", 3, rezultat.size)
    preveriEnako("Prvi je nov id", "4", rezultat[0].id)
    preveriEnako("Prvi ima nov cas", zdaj, rezultat[0].zadnjiVideni)
    preveriEnako("Drugi je stari id", "2", rezultat[1].id)
    preveriEnako("Drugi ima stari cas", zdaj - 6 * dan, rezultat[1].zadnjiVideni)
    preveriEnako("Tretji je osvezen stari id", "3", rezultat[2].id)
    preveriEnako("Tretji ima nov cas", zdaj, rezultat[2].zadnjiVideni)

    if (napak > 0) kotlin.system.exitProcess(1)
}
